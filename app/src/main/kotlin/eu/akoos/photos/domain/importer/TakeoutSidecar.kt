/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://www.photosforproton.eu
 *
 * This file is part of Photos for Proton.
 *
 * Photos for Proton is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package eu.akoos.photos.domain.importer

import eu.akoos.photos.domain.entity.TimestampSanity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.ZoneOffset

/**
 * The capture metadata a Google Takeout sidecar carries, reduced to the fields an import needs. A
 * Takeout export drops a small JSON next to each media file that holds the true capture date, GPS and
 * caption the media itself often lost, so this is the record the importer trusts over the file's own
 * (frequently empty) metadata.
 */
data class SidecarMeta(
    val takenMs: Long?,
    val lat: Double?,
    val lng: Double?,
    val description: String?,
    val title: String?,
    val favorited: Boolean = false,
)

/**
 * Reads a Google Takeout sidecar JSON and pairs a media file with its (awkwardly named) sidecar.
 *
 * Three independent jobs live here, all pure and JVM verifiable (no Android, no I/O, no network):
 *
 * 1. [parse] turns a sidecar's JSON text into a [SidecarMeta]. `photoTakenTime.timestamp` is a string of
 *    Unix seconds and is the capture date we want; `creationTime` is the upload time and is ignored.
 *    `geoData` (falling back to `geoDataExif`) supplies GPS, with Google's 0.0/0.0 no-location sentinel
 *    read as absent. A date that is not a real, plausible timestamp becomes null rather than a garbage
 *    value, and any malformed, empty or oversized input returns null instead of throwing.
 *
 * 2. [sidecarKey] and [candidateMediaKeys] let the caller build a `Map<String, SidecarMeta>` keyed by
 *    [sidecarKey] and resolve a media file by walking [candidateMediaKeys] until one hits. The keys are
 *    normalized so a media name and its sidecar collapse to the same string across every naming quirk
 *    Takeout produces:
 *      1. old form `IMG_1234.jpg.json`.
 *      2. current form `IMG_1234.jpg.supplemental-metadata.json`.
 *      3. Google clips the whole sidecar name near 46 characters, so the tag arrives truncated
 *         (`.supplemental-metad.json`, `.suppl.json`, `.s.json`); any leading fragment of
 *         `supplemental-metadata` is stripped, and a long media base that is clipped too is met by a
 *         truncated candidate key.
 *      4. the extension is sometimes absent from the sidecar (`IMG_1234.json` for `IMG_1234.jpg`), so an
 *         extension-less key is always offered as a fallback candidate.
 *      5. a numbered duplicate's `(n)` counter can sit before or after the extension
 *         (`IMG_1234(1).jpg` vs `IMG_1234.jpg(1)`); it is normalized to one canonical position.
 *      6. an edited copy (`-edited`, and localized `-bearbeitet`, `-modifi...`) shares the original's
 *         sidecar, so the suffix is stripped before matching.
 *
 * 3. [albumFolderOf] names the export album a media entry belongs to (its immediate parent folder),
 *    reading through both the Takeout `Takeout/Google Photos/<Album>/<file>` and the Ente
 *    `<Album>/<file>` layouts, and returns null for a timeline entry no album claims.
 */
object TakeoutSidecar {

    /** A sane sidecar JSON is a few kilobytes; anything past this is treated as not a sidecar. */
    private const val MAX_JSON_CHARS = 1_000_000

    /** Digital photography predates this by little; a capture year below it is not a real date. */
    private const val MIN_YEAR = 1990

    /** A generous ceiling; a year past it is a corrupt timestamp, not a capture date. */
    private const val MAX_YEAR = 2100

    /** Google caps the whole sidecar filename near this length, clipping a long media base with it. */
    private const val GOOGLE_NAME_CAP = 46

    /** The tag Takeout inserts before `.json`; a truncated form is any non-empty leading fragment. */
    private const val SUPPLEMENTAL = "supplemental-metadata"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** A trailing `(n)` duplicate counter. */
    private val TRAILING_COUNTER = Regex("""\((\d+)\)$""")

    /** Localized stems of Google's edited-copy suffix; a copy shares the original's sidecar. */
    private val EDIT_TOKENS = listOf(
        "edited", "bearbeitet", "modifi", "bewerkt", "editado", "modificato", "redigerad",
    )

    /** Google's per-year timeline bucket (`Photos from 2019`); a photo in one belongs to no album. */
    private val TIMELINE_YEAR = Regex("""^photos from \d{4}$""")

    /** Google's export container folders; a media file sitting straight under one has no album. */
    private val EXPORT_CONTAINERS = setOf("takeout", "google photos", "google fotos")

    /** Google's non-album timeline buckets, matched case-insensitively alongside [TIMELINE_YEAR]. */
    private val TIMELINE_BUCKETS = setOf("archive", "trash", "bin")

    /**
     * The metadata [jsonText] holds, or null when it is not a usable sidecar. Never throws: malformed,
     * empty or oversized input, and any single unreadable field, degrade to null rather than an error.
     */
    fun parse(jsonText: String): SidecarMeta? {
        if (jsonText.isBlank() || jsonText.length > MAX_JSON_CHARS) return null
        val root = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() as? JsonObject ?: return null

        val takenMs = root.child("photoTakenTime")?.string("timestamp")?.toLongOrNull()
            ?.let { runCatching { Math.multiplyExact(it, 1000L) }.getOrNull() }
            ?.takeIf { isSane(it) }

        val (lat, lng) = pickGeo(root.child("geoData"), root.child("geoDataExif"))
        val description = root.string("description")?.trim()?.takeIf { it.isNotEmpty() }
        val title = root.string("title")?.trim()?.takeIf { it.isNotEmpty() }
        val favorited = root.bool("favorited") ?: false

        return SidecarMeta(takenMs, lat, lng, description, title, favorited)
    }

    /**
     * The single lookup key a sidecar json filename maps to: its `.json` extension removed, any
     * (possibly truncated) `supplemental-metadata` tag stripped, a duplicate counter normalized, and
     * the result lowercased. Feed every sidecar entry through this to build the index.
     */
    fun sidecarKey(jsonEntryName: String): String {
        var rest = jsonEntryName.trim()
        if (rest.length >= 5 && rest.regionMatches(rest.length - 5, ".json", 0, 5, ignoreCase = true)) {
            rest = rest.substring(0, rest.length - 5)
        }
        // A duplicate counter can sit after the tag (`...supplemental-metadata(1)`); pull it aside so it
        // lands in the same canonical place as the media file's own counter.
        val (untagged, tagCounter) = pullTrailingCounter(rest)
        var core = stripSupplementalTag(untagged)
        if (tagCounter != null) core += "($tagCounter)"
        val (stem, ext, counter) = decompose(core)
        return keyOf(stem, ext, counter)
    }

    /**
     * The keys to look a media file up by, most specific first: the literal name with its extension, the
     * edited-suffix-stripped name with its extension, then both again without the extension (the sidecar
     * sometimes omits it), and finally a length-capped prefix for a base Google truncated. The caller
     * returns the first candidate present in the sidecar index.
     */
    fun candidateMediaKeys(mediaEntryName: String): List<String> {
        val (stemFull, ext, counter) = decompose(mediaEntryName.trim())
        val stemBase = stripEdited(stemFull)
        val out = LinkedHashSet<String>()

        val literalWithExt = keyOf(stemFull, ext, counter)
        out += literalWithExt
        out += keyOf(stemBase, ext, counter)
        val literalNoExt = keyOf(stemFull, "", counter)
        out += literalNoExt
        out += keyOf(stemBase, "", counter)

        if (literalWithExt.length > GOOGLE_NAME_CAP) out += literalWithExt.take(GOOGLE_NAME_CAP)
        if (literalNoExt.length > GOOGLE_NAME_CAP) out += literalNoExt.take(GOOGLE_NAME_CAP)

        return out.toList()
    }

    /**
     * The export album a media entry at [entryPath] belongs to, or null when it belongs to none. The
     * album is the media file's immediate parent folder, which serves both layouts an import accepts: a
     * Google Takeout `Takeout/Google Photos/<Album>/<file>` and an Ente `<Album>/<file>` (Ente keeps its
     * sidecars in `<Album>/metadata/`, but the media itself sits directly in `<Album>`).
     *
     * Null means there is no user album to regroup into: the parent is one of Google's timeline buckets
     * (`Photos from <year>`, `Archive`, `Trash`/`Bin`, all case-insensitive), or the file has no album
     * folder at all because it sits at the export root or straight under the `Takeout`/`Google Photos`
     * container. Separators are normalised and every segment is trimmed, so a padded or empty folder name
     * never leaks through.
     */
    fun albumFolderOf(entryPath: String): String? {
        val segments = entryPath.replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // A file with no directory component sits at the export root: no album wraps it.
        if (segments.size < 2) return null
        val parent = segments[segments.size - 2]
        // The container wrapper is not an album, and neither is a timeline bucket.
        if (parent.lowercase() in EXPORT_CONTAINERS) return null
        if (isTimelineBucket(parent)) return null
        return parent
    }

    /** True when [folder] is one of Google's non-album timeline buckets (a year stream, archive, trash). */
    private fun isTimelineBucket(folder: String): Boolean {
        val lower = folder.lowercase()
        return lower in TIMELINE_BUCKETS || TIMELINE_YEAR.matches(lower)
    }

    /** True when [ms] is a real timestamp that also falls in a plausible capture year range. */
    private fun isSane(ms: Long): Boolean {
        if (!TimestampSanity.isReal(ms)) return false
        val year = runCatching { Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).year }.getOrNull() ?: return false
        return year in MIN_YEAR..MAX_YEAR
    }

    /** Prefer `geoData`, fall back to `geoDataExif`, reading Google's 0.0/0.0 sentinel as no location. */
    private fun pickGeo(primary: JsonObject?, exif: JsonObject?): Pair<Double?, Double?> {
        liveGeo(primary)?.let { return it }
        liveGeo(exif)?.let { return it }
        return null to null
    }

    private fun liveGeo(geo: JsonObject?): Pair<Double, Double>? {
        val lat = geo?.double("latitude") ?: return null
        val lng = geo.double("longitude") ?: return null
        if (lat == 0.0 && lng == 0.0) return null
        return lat to lng
    }

    /** Splits a name into its stem, lowercase-comparable extension and duplicate counter. */
    private fun decompose(raw: String): Triple<String, String, String?> {
        var counter: String?
        val (afterTail, tail) = pullTrailingCounter(raw)
        counter = tail
        var name = afterTail

        var stem = name
        var ext = ""
        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            val cand = name.substring(dot + 1)
            if (isExtLike(cand)) {
                stem = name.substring(0, dot)
                ext = cand
            }
        }
        // A counter that sat before the extension (`IMG_1234(1).jpg`) is now on the stem's tail.
        if (counter == null) {
            val (afterStemTail, stemTail) = pullTrailingCounter(stem)
            if (stemTail != null) {
                stem = afterStemTail
                counter = stemTail
            }
        }
        return Triple(stem, ext, counter)
    }

    private fun pullTrailingCounter(s: String): Pair<String, String?> {
        val m = TRAILING_COUNTER.find(s) ?: return s to null
        return s.substring(0, m.range.first) to m.groupValues[1]
    }

    private fun stripSupplementalTag(s: String): String {
        val dot = s.lastIndexOf('.')
        if (dot < 0) return s
        val seg = s.substring(dot + 1).lowercase()
        return if (seg.isNotEmpty() && seg.length <= SUPPLEMENTAL.length && SUPPLEMENTAL.startsWith(seg)) {
            s.substring(0, dot)
        } else {
            s
        }
    }

    private fun stripEdited(stem: String): String {
        val dash = stem.lastIndexOf('-')
        if (dash <= 0) return stem
        val seg = stem.substring(dash + 1).lowercase()
        return if (EDIT_TOKENS.any { seg.startsWith(it) }) stem.substring(0, dash) else stem
    }

    /** A real media extension is 1 to 5 alphanumeric characters with at least one letter. */
    private fun isExtLike(s: String): Boolean =
        s.length in 1..5 && s.all { it.isLetterOrDigit() } && s.any { it.isLetter() }

    private fun keyOf(stem: String, ext: String, counter: String?): String {
        val sb = StringBuilder(stem.length + ext.length + 4)
        sb.append(stem.lowercase())
        if (ext.isNotEmpty()) {
            sb.append('.').append(ext.lowercase())
        }
        if (counter != null) {
            sb.append('(').append(counter).append(')')
        }
        return sb.toString()
    }

    private fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
