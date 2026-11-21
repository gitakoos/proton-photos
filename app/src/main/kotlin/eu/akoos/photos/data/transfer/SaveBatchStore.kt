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

package eu.akoos.photos.data.transfer

import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase.ItemResult
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A save-to-device batch kept on disk, so it survives the app process ending and the background
 * download that runs it picks it up where it stopped. One folder per batch:
 *  - `batch.json`: the account, the linkIds of the cloud photos to save and how many picked photos
 *    were on the device already;
 *  - `done.txt`: one line per cloud photo that is over, with how it ended, so a resumed run never
 *    fetches or saves it a second time;
 *  - `saved.txt`: the gallery uri of every photo saved, for the History entry;
 *  - `rows.txt`: the gallery rows a save opened (`+uri`) and settled (`-uri`); one still open when a
 *    run starts was cut off part-way and is removed.
 * Lines are appended and the files are only read when a run starts, so a process that ends mid-write
 * loses at most the last line.
 */
class SaveBatchStore(root: File, val id: String) {

    /** The account, the linkIds of the cloud photos to save, and how many picked photos were on the
     *  device already. Only ids are kept: a photo's name and details are read back from the library
     *  when the batch runs, so nothing on disk names a photo. */
    @Serializable
    data class Batch(val userId: String, val linkIds: List<String>, val onDeviceCount: Int)

    private val dir = File(File(root, DIR), id)
    private val batchFile = File(dir, "batch.json")
    private val doneFile = File(dir, "done.txt")
    private val savedFile = File(dir, "saved.txt")
    private val rowsFile = File(dir, "rows.txt")

    fun exists(): Boolean = batchFile.isFile

    /** Writes the batch through a temporary file, so a half-written batch is never read as whole. */
    fun create(batch: Batch) {
        dir.mkdirs()
        val tmp = File(dir, "batch.json.tmp")
        tmp.writeText(json.encodeToString(Batch.serializer(), batch))
        if (!tmp.renameTo(batchFile)) {
            tmp.delete()
            error("could not write the batch")
        }
    }

    fun read(): Batch? = runCatching { json.decodeFromString(Batch.serializer(), batchFile.readText()) }.getOrNull()

    /** How every finished cloud photo ended, by linkId. */
    fun done(): Map<String, ItemResult> = lines(doneFile).mapNotNull { line ->
        val key = line.substringBeforeLast(' ', "")
        val result = runCatching { ItemResult.valueOf(line.substringAfterLast(' ')) }.getOrNull()
        if (key.isEmpty() || result == null) null else key to result
    }.toMap()

    @Synchronized fun markDone(linkId: String, result: ItemResult) = append(doneFile, "$linkId ${result.name}")

    fun savedUris(): List<String> = lines(savedFile)

    @Synchronized fun markSaved(uri: String) = append(savedFile, uri)

    @Synchronized fun rowOpened(uri: String) = append(rowsFile, "+$uri")

    @Synchronized fun rowSettled(uri: String) = append(rowsFile, "-$uri")

    /** Rows opened and never settled, in the order they were opened. */
    fun openRows(): List<String> {
        val open = LinkedHashSet<String>()
        for (line in lines(rowsFile)) {
            when {
                line.startsWith("+") -> open += line.substring(1)
                line.startsWith("-") -> open -= line.substring(1)
            }
        }
        return open.toList()
    }

    /** Forgets the open rows once they have been dealt with. */
    @Synchronized fun clearRows() {
        rowsFile.delete()
    }

    fun delete() {
        dir.deleteRecursively()
    }

    private fun lines(file: File): List<String> =
        if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()

    /** A batch dropped while a save was still finishing stays dropped: nothing brings its folder back. */
    private fun append(file: File, line: String) {
        if (!dir.isDirectory) return
        file.appendText(line + "\n")
    }

    companion object {
        /** The folder under the app's files dir that holds every batch; wiped on sign-out. */
        const val DIR = "save_batches"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
