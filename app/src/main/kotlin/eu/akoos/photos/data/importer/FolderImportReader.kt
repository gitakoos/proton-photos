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
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InputStream

/**
 * One file of a picked folder. [name] is `<picked folder name>/<relative path>` with `/` separators,
 * exactly what a zip of the same folder holds; [sizeBytes] is 0 when the provider does not report it;
 * [handle] is what the lister opens the file by.
 */
data class FolderEntry(val name: String, val sizeBytes: Long, val modifiedMs: Long?, val handle: String)

/** One child of a directory as a lister reports it. */
data class FolderChild(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedMs: Long?,
    val handle: String,
)

/** Turns a directory tree into [FolderEntry]s, breadth first, one [children] call per directory. */
internal object FolderTree {

    /** Every file under the root [rootHandle] named [rootName]. A name starting with `.` (a hidden file or
     *  folder, or a provider's pending or trashed item) is left out together with everything under it. */
    fun list(rootName: String, rootHandle: String, children: (handle: String) -> List<FolderChild>): List<FolderEntry> {
        val out = ArrayList<FolderEntry>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.addLast(rootHandle to rootName)
        while (queue.isNotEmpty()) {
            val (handle, path) = queue.removeFirst()
            if (!seen.add(handle)) continue
            for (child in children(handle)) {
                if (child.name.isEmpty() || child.name.startsWith('.')) continue
                val childPath = "$path/${child.name}"
                if (child.isDirectory) {
                    queue.addLast(child.handle to childPath)
                } else {
                    out += FolderEntry(childPath, child.sizeBytes.coerceAtLeast(0L), child.modifiedMs, child.handle)
                }
            }
        }
        return out
    }
}

/**
 * Reads a picked folder as an export, with the same entry names, sidecar rules and results a `.zip` of
 * that folder gives through [TakeoutZipReader]. [list] lists the tree (once per reader), [open] opens one
 * file, and [rootReachable] tells a file that went missing from a folder that is gone altogether.
 *
 * Entries are visited in path order. [scan] opens only the sidecar `.json` files; the media walk opens
 * each media file in turn and closes it after the callback. A file that cannot be read while the folder
 * itself still answers is skipped and counted in [WalkResult.damagedEntries]; a folder that stopped
 * answering (unmounted, its access revoked) ends the walk with a [SourceReadException]. Progress is the
 * running sum of the listed sizes, so a finished walk has read exactly [totalBytes].
 */
class FolderImportReader(
    private val list: () -> List<FolderEntry>,
    private val open: (FolderEntry) -> InputStream,
    private val rootReachable: () -> Boolean,
) : ImportSourceReader {

    private var listed: List<FolderEntry>? = null

    /** The sum of the listed file sizes. Lists the folder on first use. */
    fun totalBytes(): Long = entries().sumOf { it.sizeBytes }

    override fun scan(onBytes: ((Long) -> Unit)?): TakeoutScan {
        val index = SidecarIndexBuilder()
        val walk = walk(
            onBytes,
            opens = { ImportEntries.isJson(it.name) && it.sizeBytes <= ImportEntries.MAX_SIDECAR_BYTES },
            onListed = { if (ImportEntries.isMedia(it.name)) index.countMedia() },
        ) { entry, input -> index.offerSidecar(entry.name, input) }
        return index.build(walk)
    }

    override fun forEachMediaEntry(
        onBytes: ((Long) -> Unit)?,
        onMedia: (entry: MediaEntry, input: InputStream) -> Unit,
    ): WalkResult = walk(onBytes, opens = { ImportEntries.isMedia(it.name) }) { entry, input ->
        onMedia(MediaEntry(entry.name, entry.modifiedMs), input)
    }

    private fun entries(): List<FolderEntry> =
        listed ?: fromSource { list() }.sortedBy { it.name }.also { listed = it }

    private inline fun walk(
        noinline onBytes: ((Long) -> Unit)?,
        opens: (FolderEntry) -> Boolean,
        onListed: (FolderEntry) -> Unit = {},
        onEntry: (FolderEntry, InputStream) -> Unit,
    ): WalkResult {
        val all = entries()
        var bytes = 0L
        var damaged = 0
        for (entry in all) {
            onListed(entry)
            if (opens(entry)) {
                try {
                    val stream = SourceStream(fromSource { open(entry) })
                    try {
                        onEntry(entry, stream)
                    } finally {
                        runCatching { stream.close() }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val skippable = ImportFailure.isArchiveFault(e) ||
                        (ImportEntries.isSourceFault(e) && runCatching(rootReachable).getOrDefault(false))
                    if (!skippable) throw e
                    damaged++
                }
            }
            bytes += entry.sizeBytes
            onBytes?.invoke(bytes)
        }
        return WalkResult(damagedEntries = damaged, bytesRead = bytes, entries = all.size)
    }

    private inline fun <T> fromSource(block: () -> T): T = try {
        block()
    } catch (e: SourceReadException) {
        throw e
    } catch (e: IOException) {
        throw SourceReadException(e)
    } catch (e: SecurityException) {
        throw SourceReadException(IOException(e))
    }
}
