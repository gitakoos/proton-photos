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

import eu.akoos.photos.domain.importer.ImportFailure
import eu.akoos.photos.domain.importer.SidecarMeta
import eu.akoos.photos.domain.importer.TakeoutSidecar
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * How a walk over the zip ended. A zip stream cannot step past an entry whose own bytes are unusable, so
 * a damaged entry that the stream cannot recover from stops the walk there: [endedEarly] is true and
 * [endedBy] is the fault, with everything before that point already handed to the caller. [damagedEntries]
 * counts the entries that were skipped without ending the walk. [bytesRead] is how far into the file the
 * walk got.
 */
data class WalkResult(
    val damagedEntries: Int = 0,
    val endedEarly: Boolean = false,
    val endedBy: Throwable? = null,
    val bytesRead: Long = 0L,
    val entries: Int = 0,
) {
    /**
     * True when the walk stopped cleanly, but well short of the file's end ([fileBytes] long): a zip
     * stream reads a damaged entry header as "the entries are over", so the only sign of such damage is
     * that the file goes on far beyond where the entries stopped. What legitimately follows the last entry
     * is the central directory, a few hundred bytes per entry, which the allowance covers.
     */
    fun endedShort(fileBytes: Long): Boolean =
        !endedEarly && fileBytes > 0 &&
            bytesRead + entries * CENTRAL_ENTRY_ALLOWANCE + TAIL_ALLOWANCE < fileBytes

    private companion object {
        const val CENTRAL_ENTRY_ALLOWANCE = 400L
        const val TAIL_ALLOWANCE = 4_096L
    }
}

/** One media entry of an export: its path and its modified time, null when the source records none. */
data class MediaEntry(val name: String, val modifiedMs: Long?)

/** A read failure of the place the export is stored (as opposed to a fault in the export's own bytes), so
 *  the readers and their callers can tell "the source dropped out" from "the archive is damaged" even
 *  though a broken zip stream also raises plain I/O errors. */
class SourceReadException(cause: IOException) : IOException(cause.message, cause)

/**
 * What pass 1 learns from a single walk of the zip: the sidecar metadata index plus the number of
 * media entries the second pass will visit. Both come out of the one walk so a progress total is known
 * before any upload starts without a third traversal.
 */
data class TakeoutScan(
    val sidecars: Map<String, SidecarMeta>,
    val mediaCount: Int,
    val walk: WalkResult = WalkResult(),
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
 *
 * Both passes report how many bytes of the file they have consumed, so a caller can show a real
 * percentage over a file of many gigabytes, and both survive a damaged archive: an entry the stream
 * cannot decode is skipped, and one it cannot step past ends the walk with a [WalkResult] describing
 * where, rather than throwing away everything read so far. Any other failure (the place the zip is read
 * from stops answering) is rethrown for the caller to retry.
 */
class TakeoutZipReader(private val openStream: () -> InputStream) : ImportSourceReader {

    /**
     * Pass 1: the sidecar metadata index plus the media count, both from the same walk. The index is
     * keyed by [TakeoutSidecar.sidecarKey] so a media file resolves to its entry via
     * [TakeoutSidecar.candidateMediaKeys]. Only `.json` entries small enough to be a real sidecar are
     * read, and only those [TakeoutSidecar.parse] turns into a [SidecarMeta] carrying a date, GPS or
     * description are kept (see [SidecarIndexBuilder]). The media count is every entry pass 2 will hand
     * back (the same extension allow-list [forEachMedia] uses), so it is the progress denominator.
     * [onBytes] receives the running count of file bytes consumed after each entry.
     */
    override fun scan(onBytes: ((Long) -> Unit)?): TakeoutScan {
        val index = SidecarIndexBuilder()
        val walk = walk(onBytes) { entry, zip ->
            val name = entry.name
            if (isJson(name)) index.offerSidecar(name, zip) else if (isMedia(name)) index.countMedia()
        }
        return index.build(walk)
    }

    /** The sidecar index alone, for a caller that does not need the media count. Delegates to [scan]. */
    fun indexSidecars(): Map<String, SidecarMeta> = scan().sidecars

    /**
     * Pass 2: invokes [onMedia] once per media entry (by extension allow-list, never a `.json`), in the
     * zip's own order, with the entry and a stream positioned at that entry's bytes. The stream is a
     * non-closing view of the underlying [ZipInputStream]: the reader owns the zip's lifecycle and advances
     * to the next entry after the callback returns, so a callback that closes its stream cannot break the
     * walk. The callback must read what it needs synchronously (typically copying to a temp file).
     * [onBytes] receives the running count of file bytes consumed after each entry.
     */
    override fun forEachMediaEntry(
        onBytes: ((Long) -> Unit)?,
        onMedia: (entry: MediaEntry, input: InputStream) -> Unit,
    ): WalkResult = walk(onBytes) { entry, zip ->
        if (isMedia(entry.name)) onMedia(MediaEntry(entry.name, modifiedMsOf(entry)), zip)
    }

    /**
     * One walk over the archive. [onEntry] sees every non-directory entry with a stream positioned at its
     * bytes. A fault the zip stream raises about its own bytes (see [ImportFailure.isArchiveFault]) skips
     * that entry, or ends the walk when the stream cannot be advanced past it; anything else, including
     * cancellation, propagates.
     */
    private fun walk(onBytes: ((Long) -> Unit)?, onEntry: (ZipEntry, InputStream) -> Unit): WalkResult {
        // Counted after the buffer, so the figure is what the zip stream has consumed (within its own small
        // read-ahead), not what the buffer has pulled in advance.
        val counting = CountingInputStream(BufferedInputStream(SourceStream(openStream()), READ_BUFFER))
        var damaged = 0
        var entries = 0
        var endedBy: Throwable? = null
        ZipInputStream(counting).use { zip ->
            val guard = EntryStream(zip)
            while (true) {
                var entry: ZipEntry? = null
                try {
                    entry = zip.nextEntry
                } catch (e: Exception) {
                    if (e is CancellationException || !isZipFault(e)) throw e
                    endedBy = e
                }
                if (entry == null) break
                entries++
                try {
                    if (!entry.isDirectory) onEntry(entry, guard)
                } catch (e: Exception) {
                    if (e is CancellationException || !ImportFailure.isArchiveFault(e)) throw e
                    damaged++
                }
                try {
                    zip.closeEntry()
                } catch (e: Exception) {
                    if (e is CancellationException || !isZipFault(e)) throw e
                    endedBy = e
                    break
                }
                onBytes?.invoke(counting.count)
            }
        }
        return WalkResult(damaged, endedBy != null, endedBy, counting.count, entries)
    }

    /**
     * A view over the zip stream whose [close] is a no-op. The media callback reads through this while the
     * reader keeps ownership of the real stream, so closing it never severs the walk from its next entry.
     * A plain I/O error the zip decoder raises while reading an entry (its own bookkeeping failing on bad
     * data) is raised as a [ZipException], so a caller sees damage in the archive as damage in the archive;
     * an error from the source itself passes through untouched.
     */
    private class EntryStream(delegate: InputStream) : FilterInputStream(delegate) {
        override fun read(): Int = guarded { super.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = guarded { super.read(b, off, len) }

        override fun skip(n: Long): Long = guarded { super.skip(n) }

        override fun close() {
            // Intentionally left blank: the reader owns the underlying zip stream's lifecycle.
        }

        private inline fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            if (isZipFault(e)) throw e
            throw ZipException("unreadable entry data").also { it.initCause(e) }
        }
    }

    /** Counts the bytes taken from the file itself (compressed, before inflating), which is what a
     *  percentage of the file's size needs. */
    private class CountingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        @Volatile
        var count: Long = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count += it }
    }

    companion object {
        /** True for what a zip decoder raises about its own bytes: the recognised archive faults, and any
         *  other I/O error that did not come from the source stream. */
        private fun isZipFault(e: Throwable): Boolean {
            if (ImportFailure.isArchiveFault(e)) return true
            if (e !is IOException) return false
            return !ImportEntries.isSourceFault(e)
        }

        /** An entry's modified time, or null when it carries none. A zip stores DOS times, whose earliest
         *  value (1 January 1980) is what a writer leaves when it has no time, so that day counts as none. */
        internal fun modifiedMsOf(entry: ZipEntry): Long? = entry.time.takeIf { it > DOS_EPOCH_END_MS }

        /** 1980-01-02T00:00:00Z, past 1 January 1980 at local midnight in every zone. */
        private const val DOS_EPOCH_END_MS = 315_619_200_000L

        private const val READ_BUFFER = 64 * 1024

        internal fun baseName(path: String): String = ImportEntries.baseName(path)

        internal fun isJson(name: String): Boolean = ImportEntries.isJson(name)

        internal fun isMedia(name: String): Boolean = ImportEntries.isMedia(name)
    }
}
