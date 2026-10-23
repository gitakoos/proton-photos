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

import java.io.InputStream

/**
 * A picked export, read in two passes: [scan] for the sidecar index and the media count, then
 * [forEachMediaEntry] for the media themselves, one entry at a time. Entry names are `/`-separated paths,
 * the same for a `.zip` ([TakeoutZipReader]) and a folder ([FolderImportReader]), so everything keyed on
 * them (the sidecar lookup, the album folder, Live Photo pairing, the staged rows) works for both.
 *
 * Both passes report through `onBytes` how many bytes of the source they have consumed. A failure of the
 * place the export is stored surfaces as a [SourceReadException]; damage confined to one entry is counted
 * in [WalkResult.damagedEntries] and skipped.
 */
interface ImportSourceReader {

    /** Pass 1: the sidecar index and the media count. */
    fun scan(onBytes: ((Long) -> Unit)? = null): TakeoutScan

    /**
     * Pass 2: [onMedia] once per media entry with a stream at its bytes. The reader owns the stream; the
     * callback reads what it needs before returning.
     */
    fun forEachMediaEntry(
        onBytes: ((Long) -> Unit)? = null,
        onMedia: (entry: MediaEntry, input: InputStream) -> Unit,
    ): WalkResult

    /** [forEachMediaEntry] with the entry name only. */
    fun forEachMedia(
        onBytes: ((Long) -> Unit)? = null,
        onMedia: (entryName: String, input: InputStream) -> Unit,
    ): WalkResult = forEachMediaEntry(onBytes) { entry, input -> onMedia(entry.name, input) }
}

/**
 * A picked export ready to read: its [reader], the [displayName] an import run is recorded under, and its
 * size. The size is read only when asked for, because a folder learns it by listing the whole tree.
 */
class ImportSource(
    val reader: ImportSourceReader,
    val displayName: String,
    private val sizeOf: () -> Long,
) {
    /** The source's size in bytes, or -1 when it is not known. Never throws: a source that cannot be read
     *  fails on the first pass instead, where the failure is reported. */
    fun totalBytes(): Long = try {
        sizeOf()
    } catch (e: Exception) {
        -1L
    }
}
