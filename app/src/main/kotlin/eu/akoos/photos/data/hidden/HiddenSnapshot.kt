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

package eu.akoos.photos.data.hidden

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One photo or album of a hidden-list copy: its Drive link id, whether it is hidden, and when that last changed. A
 * copy read from Drive drops an entry that says neither hidden nor shown, so [hidden] is never null past
 * [HiddenSnapshotCodec.decode].
 */
@Serializable
data class HiddenSnapshotEntry(
    @SerialName("k") val key: String = "",
    @SerialName("h") val hidden: Boolean? = null,
    @SerialName("t") val changedAtMillis: Long = 0L,
)

/**
 * One install's hidden photos and albums as it saves them to Drive, the format the desktop client reads and writes:
 * JSON v1 `{ v, d, n, c, p: [{ k, h, t }], a: [{ k, h, t }] }`. `h: false` is an item shown again, kept so the
 * unhide reaches the other devices. A device-only photo has no link id and never appears.
 */
@Serializable
data class HiddenSnapshot(
    @SerialName("v") val version: Int = VERSION,
    @SerialName("d") val deviceId: String,
    @SerialName("n") val deviceLabel: String,
    @SerialName("c") val createdAtMillis: Long,
    @SerialName("p") val photos: List<HiddenSnapshotEntry>,
    @SerialName("a") val albums: List<HiddenSnapshotEntry>,
) {
    companion object {
        const val VERSION = 1
    }
}

/** The hidden list's plaintext form, before it is sealed. */
object HiddenSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: HiddenSnapshot): ByteArray =
        json.encodeToString(HiddenSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8)

    /** The format version [bytes] declares, or null when they are not a hidden list at all. */
    fun versionOf(bytes: ByteArray): Int? = runCatching {
        Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject["v"]?.jsonPrimitive?.int
    }.getOrNull()

    /** The hidden list in [bytes], or null when it is not one this build reads. Device keys are dropped. */
    fun decode(bytes: ByteArray): HiddenSnapshot? =
        runCatching { json.decodeFromString(HiddenSnapshot.serializer(), String(bytes, Charsets.UTF_8)) }
            .getOrNull()
            ?.takeIf { it.version == HiddenSnapshot.VERSION }
            ?.let { copy -> copy.copy(photos = copy.photos.filter(::portable), albums = copy.albums.filter(::portable)) }

    private fun portable(entry: HiddenSnapshotEntry): Boolean =
        entry.key.isNotBlank() && "://" !in entry.key && entry.hidden != null
}

/** One kind of hidden item as the merge reads it: link id to when it was hidden, and to when it was shown again. */
data class HiddenKindState(
    val hidden: MutableMap<String, Long> = HashMap(),
    val shown: MutableMap<String, Long> = HashMap(),
) {
    /** When [key] last changed here, hidden or shown, or -1 when it never did. */
    fun changedAt(key: String): Long = maxOf(hidden[key] ?: -1L, shown[key] ?: -1L)

    fun deepCopy(): HiddenKindState = HiddenKindState(HashMap(hidden), HashMap(shown))
}

/** This device's hidden photos and albums with their times, as the merge reads and writes them. */
data class HiddenSyncState(
    val photos: HiddenKindState = HiddenKindState(),
    val albums: HiddenKindState = HiddenKindState(),
) {
    fun deepCopy(): HiddenSyncState = HiddenSyncState(photos.deepCopy(), albums.deepCopy())
}

/**
 * The hidden lists' merge rule, the same the desktop client keeps: per photo and per album, the later change wins,
 * here or there, and an entry without a time (0) only fills a gap. Taking the same copy twice changes nothing.
 * Pure, so it runs in a plain JVM test.
 */
object HiddenCopyMerge {

    /** What taking a copy in changed, by link id and the time each change carries. */
    data class Changes(
        val photosHidden: Map<String, Long> = emptyMap(),
        val photosShown: Map<String, Long> = emptyMap(),
        val albumsHidden: Map<String, Long> = emptyMap(),
        val albumsShown: Map<String, Long> = emptyMap(),
    ) {
        val isEmpty: Boolean
            get() = photosHidden.isEmpty() && photosShown.isEmpty() && albumsHidden.isEmpty() && albumsShown.isEmpty()

        operator fun plus(other: Changes) = Changes(
            photosHidden + other.photosHidden - other.photosShown.keys,
            photosShown + other.photosShown - other.photosHidden.keys,
            albumsHidden + other.albumsHidden - other.albumsShown.keys,
            albumsShown + other.albumsShown - other.albumsHidden.keys,
        )
    }

    /** Takes another install's [copy] into [state]; returns what changed. This install's own copy is never taken. */
    fun takeIn(state: HiddenSyncState, copy: HiddenSnapshot, ownDeviceId: String): Changes {
        if (copy.deviceId.isEmpty() || copy.deviceId == ownDeviceId) return Changes()
        val photos = apply(state.photos, copy.photos)
        val albums = apply(state.albums, copy.albums)
        return Changes(photos.first, photos.second, albums.first, albums.second)
    }

    private fun apply(kind: HiddenKindState, entries: List<HiddenSnapshotEntry>): Pair<Map<String, Long>, Map<String, Long>> {
        val hidden = HashMap<String, Long>()
        val shown = HashMap<String, Long>()
        for (entry in entries) {
            if (entry.changedAtMillis <= kind.changedAt(entry.key)) continue
            if (entry.hidden == true) {
                kind.hidden[entry.key] = entry.changedAtMillis
                kind.shown.remove(entry.key)
                hidden[entry.key] = entry.changedAtMillis
                shown.remove(entry.key)
            } else {
                kind.hidden.remove(entry.key)
                kind.shown[entry.key] = entry.changedAtMillis
                shown[entry.key] = entry.changedAtMillis
                hidden.remove(entry.key)
            }
        }
        return hidden to shown
    }

    /** [state] as this install's copy: every entry, hidden or shown again, with its time, ordered by link id. */
    fun forDrive(state: HiddenSyncState, deviceId: String, deviceLabel: String, createdAt: Long): HiddenSnapshot =
        HiddenSnapshot(
            deviceId = deviceId,
            deviceLabel = deviceLabel,
            createdAtMillis = createdAt,
            photos = entries(state.photos),
            albums = entries(state.albums),
        )

    private fun entries(kind: HiddenKindState): List<HiddenSnapshotEntry> =
        (kind.hidden.map { HiddenSnapshotEntry(it.key, true, it.value) } +
            kind.shown.filterKeys { it !in kind.hidden }.map { HiddenSnapshotEntry(it.key, false, it.value) })
            .sortedBy { it.key }
}
