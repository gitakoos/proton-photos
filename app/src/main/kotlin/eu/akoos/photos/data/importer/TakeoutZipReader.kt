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

package eu.akoos.photos.data.importer

import eu.akoos.photos.domain.importer.SidecarMeta
import eu.akoos.photos.domain.importer.TakeoutSidecar
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * What pass 1 learns from a single walk of the zip: the sidecar metadata index plus the number of
 * media entries the second pass will visit. Both come out of the one walk so a progress total is known
 * before any upload starts without a third traversal.
 */
data class TakeoutScan(
    val sidecars: Map<String, SidecarMeta>,
    val mediaCount: Int,
)

/**
 * Streams a Google Takeout `.zip` in two passes so a multi-gigabyte export can be imported over a
 * forward-only [InputStream] (a SAF `content://` stream has no random access and must never be fully
 * buffered).
 *
 * The reader is decoupled from Android for JVM testability: it takes a stream supplier rather than a
 * `ContentResolver`/`Uri`, and each pass calls [openStream] for a fresh stream, wraps it in a
 * [ZipInputStream] and closes it. The caller wires it as `TakeoutZipReader { resolver.openInputStream(uri) }`.
 *
 * Pass 1 ([scan]) walks the whole zip once and builds a small in-memory index of the tiny sidecar JSONs
 * while counting the media entries. Pass 2 ([forEachMedia]) walks the whole zip again and hands each
 * media entry to the caller one at a time; the caller copies that single entry to a temp file before the
 * reader advances. The zip is read twice by re-opening the stream.
 */
class TakeoutZipReader(private val openStream: () -> InputStream) {

    /**
     * Pass 1: the sidecar metadata index plus the media count, both from the same walk. The index is
     * keyed by [TakeoutSidecar.sidecarKey] so a media file resolves to its entry via
     * [TakeoutSidecar.candidateMediaKeys]. Only `.json` entries small enough to be a real sidecar are
     * read, and only those [TakeoutSidecar.parse] turns into a [SidecarMeta] carrying a date, GPS or
     * description are kept. An album folder's `metadata.json` holds just a title, so it parses to an
     * empty record and falls out of the index on its own. The media count is every entry pass 2 will
     * hand back (the same extension allow-list [forEachMedia] uses), so it is the progress denominator.
     */
    fun scan(): TakeoutScan {
        val index = HashMap<String, SidecarMeta>()
        var mediaCount = 0
        ZipInputStream(openStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    if (isJson(name)) {
                        val bytes = readBounded(zip, MAX_SIDECAR_BYTES)
                        if (bytes != null) {
                            val meta = TakeoutSidecar.parse(String(bytes, Charsets.UTF_8))
                            if (meta != null && meta.isUsable()) {
                                index[TakeoutSidecar.sidecarKey(baseName(name))] = meta
                            }
                        }
                    } else if (isMedia(name)) {
                        mediaCount++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return TakeoutScan(index, mediaCount)
    }

    /** The sidecar index alone, for a caller that does not need the media count. Delegates to [scan]. */
    fun indexSidecars(): Map<String, SidecarMeta> = scan().sidecars

    /**
     * Pass 2: invokes [onMedia] once per media entry (by extension allow-list, never a `.json`), in the
     * zip's own order, with the entry name and a stream positioned at that entry's bytes. The stream is a
     * non-closing view of the underlying [ZipInputStream]: the reader owns the zip's lifecycle and advances
     * to the next entry after the callback returns, so a callback that closes its stream cannot break the
     * walk. The callback must read what it needs synchronously (typically copying to a temp file).
     */
    fun forEachMedia(onMedia: (entryName: String, input: InputStream) -> Unit) {
        ZipInputStream(openStream().buffered()).use { zip ->
            val guard = NonClosingInputStream(zip)
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && isMedia(entry.name)) {
                    onMedia(entry.name, guard)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /** True when this record carries something worth importing rather than being an album title stub. */
    private fun SidecarMeta.isUsable(): Boolean =
        takenMs != null || lat != null || lng != null || description != null

    /**
     * Reads the current entry from [input] into memory, or null once it exceeds [cap] bytes (too large to
     * be a sidecar). The caller closes the entry afterwards, discarding any unread tail.
     */
    private fun readBounded(input: InputStream, cap: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * A view over the zip stream whose [close] is a no-op. The media callback reads through this while the
     * reader keeps ownership of the real stream, so closing it never severs the walk from its next entry.
     */
    private class NonClosingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        override fun close() {
            // Intentionally left blank: the reader owns the underlying zip stream's lifecycle.
        }
    }

    companion object {
        /** A real sidecar is a few kilobytes; anything past this is not one and is skipped unread. */
        private const val MAX_SIDECAR_BYTES = 1_048_576

        private const val READ_CHUNK = 8_192

        /** Media containers a Takeout export can hold, including Google's `.mp` motion-photo track. */
        private val MEDIA_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "tif", "tiff",
            "bmp", "dng", "mp4", "mov", "m4v", "3gp", "3gpp", "mkv", "webm", "avi", "mp",
        )

        /** The file name of a zip [path], with any forward-slash directory prefix removed. */
        internal fun baseName(path: String): String {
            val slash = path.lastIndexOf('/')
            return if (slash >= 0) path.substring(slash + 1) else path
        }

        internal fun isJson(name: String): Boolean =
            baseName(name).endsWith(".json", ignoreCase = true)

        internal fun isMedia(name: String): Boolean = extensionOf(baseName(name)) in MEDIA_EXTENSIONS

        private fun extensionOf(base: String): String {
            val dot = base.lastIndexOf('.')
            if (dot < 0 || dot == base.length - 1) return ""
            return base.substring(dot + 1).lowercase()
        }
    }
}
