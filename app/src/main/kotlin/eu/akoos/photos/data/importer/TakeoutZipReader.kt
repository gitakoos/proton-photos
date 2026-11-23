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
import java.io.PushbackInputStream
import java.util.zip.ZipException

/**
 * How a walk over the zip ended. A walk front to back cannot step past an entry whose end it cannot find
 * (cut off, or deflated data that does not inflate), nor go on where no entry header follows, so it stops
 * there: [endedEarly] is true and [endedBy] is the fault, with everything before that point already handed
 * to the caller. [damagedEntries]
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
     * True when the walk stopped cleanly, but well short of the file's end ([fileBytes] long): what looked
     * like the central directory turned up far before the file's end. What legitimately follows the last
     * entry is the central directory, a few hundred bytes per entry, which the allowance covers.
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
 *  though a broken archive also raises plain I/O errors. */
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
 * `ContentResolver`/`Uri`, and each pass calls [openStream] for a fresh stream, walks its local headers
 * ([LocalHeaderReader]) and closes it. The caller wires it as `TakeoutZipReader { resolver.openInputStream(uri) }`.
 *
 * When [openRandomAccess] gives a file that can be read at any position, each pass reads the zip through its
 * central directory instead ([ZipCentralDirectory]): a zip written with data descriptors, zip64 fields or
 * an odd entry name then reads in full, and an entry that cannot be read is skipped alone. The stream walk
 * stays for a source with no random access and for a file with no readable directory (cut short).
 *
 * Pass 1 ([scan]) walks the whole zip once and builds a small in-memory index of the tiny sidecar JSONs
 * while counting the media entries. Pass 2 ([forEachMedia]) walks the whole zip again and hands each
 * media entry to the caller one at a time; the caller copies that single entry to a temp file before the
 * reader advances. The zip is read twice by re-opening the stream.
 *
 * Both passes report how many bytes of the file they have consumed, so a caller can show a real
 * percentage over a file of many gigabytes, and both survive a damaged archive: an entry that cannot be
 * decoded is skipped, and one the walk cannot step past ends it with a [WalkResult] describing
 * where, rather than throwing away everything read so far. Any other failure (the place the zip is read
 * from stops answering) is rethrown for the caller to retry.
 */
class TakeoutZipReader(
    private val openRandomAccess: (() -> ZipRandomSource?)?,
    private val openStream: () -> InputStream,
) : ImportSourceReader {

    /** A reader that only ever walks [openStream] front to back. */
    constructor(openStream: () -> InputStream) : this(null, openStream)

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
        val walk = walk(onBytes) { entry, input ->
            val name = entry.name
            if (isJson(name)) index.offerSidecar(name, input) else if (isMedia(name)) index.countMedia()
        }
        return index.build(walk)
    }

    /** The sidecar index alone, for a caller that does not need the media count. Delegates to [scan]. */
    fun indexSidecars(): Map<String, SidecarMeta> = scan().sidecars

    /**
     * Pass 2: invokes [onMedia] once per media entry (by extension allow-list, never a `.json`), in the
     * zip's own order, with the entry and a stream positioned at that entry's bytes. The stream is a
     * non-closing view of the entry's bytes: the reader owns the zip's lifecycle and advances
     * to the next entry after the callback returns, so a callback that closes its stream cannot break the
     * walk. The callback must read what it needs synchronously (typically copying to a temp file).
     * [onBytes] receives the running count of file bytes consumed after each entry.
     */
    override fun forEachMediaEntry(
        onBytes: ((Long) -> Unit)?,
        onMedia: (entry: MediaEntry, input: InputStream) -> Unit,
    ): WalkResult = walk(onBytes) { entry, input ->
        if (isMedia(entry.name)) onMedia(entry, input)
    }

    /**
     * One walk over the archive. [onEntry] sees every non-directory entry with a stream positioned at its
     * bytes. A fault about the zip's own bytes (see [ImportFailure.isArchiveFault]) skips
     * that entry, or ends the walk when the walk cannot get past it; anything else, including
     * cancellation, propagates.
     */
    private fun walk(onBytes: ((Long) -> Unit)?, onEntry: (MediaEntry, InputStream) -> Unit): WalkResult {
        openRandomAccessOrNull()?.use { source ->
            val directory = try {
                ZipCentralDirectory.read(source)
            } catch (e: ZipException) {
                null
            }
            if (directory != null) return walkDirectory(directory, onBytes, onEntry)
        }
        return walkStream(onBytes, onEntry)
    }

    private fun openRandomAccessOrNull(): ZipRandomSource? = try {
        openRandomAccess?.invoke()
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /**
     * A walk over the central directory: every entry is reached by its own offset, so one whose bytes are
     * unusable is counted in [WalkResult.damagedEntries] and the walk goes on to the next.
     */
    private fun walkDirectory(
        directory: ZipCentralDirectory,
        onBytes: ((Long) -> Unit)?,
        onEntry: (MediaEntry, InputStream) -> Unit,
    ): WalkResult {
        var damaged = 0
        var reached = 0L
        for (entry in directory.entries) {
            if (!entry.isDirectory && !ZipEntryNames.climbsOut(entry.name)) {
                val input = LazyEntryStream { directory.open(entry) }
                try {
                    onEntry(MediaEntry(entry.name, modifiedMsOf(entry.modifiedMs)), input)
                } catch (e: Exception) {
                    if (e is CancellationException || !ImportFailure.isArchiveFault(e)) throw e
                    damaged++
                } finally {
                    input.release()
                }
            }
            reached = maxOf(reached, entry.endOffset)
            onBytes?.invoke(reached)
        }
        return WalkResult(damaged, endedEarly = false, endedBy = null, bytesRead = directory.fileSize, entries = directory.entries.size)
    }

    /**
     * A walk over the local headers front to back ([LocalHeaderReader]): an entry whose bytes are unusable is
     * counted and stepped past, and one whose end cannot be found ends the walk.
     */
    private fun walkStream(onBytes: ((Long) -> Unit)?, onEntry: (MediaEntry, InputStream) -> Unit): WalkResult {
        // Counted after the buffer, so the figure is what the walk has consumed, not what the buffer has
        // pulled in advance.
        val source = WalkSource(BufferedInputStream(SourceStream(openStream()), READ_BUFFER))
        var damaged = 0
        var entries = 0
        var endedBy: Throwable? = null
        source.use {
            LocalHeaderReader(source).use { reader ->
                while (true) {
                    val entry = try {
                        reader.next()
                    } catch (e: Exception) {
                        if (e is CancellationException || !isZipFault(e)) throw e
                        endedBy = e
                        null
                    } ?: break
                    entries++
                    try {
                        if (!entry.name.endsWith("/") && !ZipEntryNames.climbsOut(entry.name)) {
                            onEntry(MediaEntry(entry.name, modifiedMsOf(entry.modifiedMs)), EntryStream(entry))
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException || !ImportFailure.isArchiveFault(e)) throw e
                        damaged++
                    }
                    try {
                        entry.skipRest()
                    } catch (e: Exception) {
                        if (e is CancellationException || !isZipFault(e)) throw e
                        endedBy = e
                        break
                    }
                    onBytes?.invoke(source.consumed)
                }
            }
        }
        return WalkResult(damaged, endedBy != null, endedBy, source.consumed, entries)
    }

    /**
     * A view over an entry's bytes whose [close] is a no-op. The media callback reads through this while the
     * reader keeps ownership of the real stream, so closing it never severs the walk from its next entry.
     * A plain I/O error the decoder raises while reading an entry (its own bookkeeping failing on bad data)
     * is raised as a [ZipException], so a caller sees damage in the archive as damage in the archive; an
     * error from the source itself passes through untouched.
     */
    private class EntryStream(delegate: InputStream) : FilterInputStream(delegate) {
        override fun read(): Int = guarded { super.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = guarded { super.read(b, off, len) }

        override fun skip(n: Long): Long = guarded { super.skip(n) }

        override fun close() {
            // Intentionally left blank: the reader owns the underlying stream's lifecycle.
        }

        private inline fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            if (ImportFailure.isArchiveFault(e) || ImportEntries.isSourceFault(e)) throw e
            throw ZipException("unreadable entry data").also { it.initCause(e) }
        }
    }

    /**
     * An entry opened on its first read, so an entry the callback never reads (one it only counts) is never
     * decoded and its damage never counted. [close] is a no-op: the walk calls [release] once the callback
     * returns.
     */
    private class LazyEntryStream(private val open: () -> InputStream) : InputStream() {
        private var opened: InputStream? = null

        private fun input(): InputStream = opened ?: open().also { opened = it }

        override fun read(): Int = input().read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = input().read(b, off, len)

        override fun skip(n: Long): Long = input().skip(n)

        override fun close() {
            // Intentionally left blank: the walk releases the entry once the callback returns.
        }

        fun release() {
            opened?.close()
        }
    }

    /** Counts the bytes taken from the file itself (compressed, before inflating), which is what a
     *  percentage of the file's size needs; bytes put back are taken off again. */
    private class WalkSource(delegate: InputStream) : PushbackInputStream(delegate, LocalHeaderReader.SOURCE_PUSHBACK) {
        @Volatile
        var consumed: Long = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) consumed++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) consumed += it }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) consumed += it }

        override fun unread(b: Int) {
            super.unread(b)
            consumed--
        }

        override fun unread(b: ByteArray, off: Int, len: Int) {
            super.unread(b, off, len)
            consumed -= len
        }
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
        private fun modifiedMsOf(ms: Long?): Long? = ms?.takeIf { it > DOS_EPOCH_END_MS }

        /** 1980-01-02T00:00:00Z, past 1 January 1980 at local midnight in every zone. */
        private const val DOS_EPOCH_END_MS = 315_619_200_000L

        private const val READ_BUFFER = 64 * 1024

        internal fun baseName(path: String): String = ImportEntries.baseName(path)

        internal fun isJson(name: String): Boolean = ImportEntries.isJson(name)

        internal fun isMedia(name: String): Boolean = ImportEntries.isMedia(name)
    }
}
