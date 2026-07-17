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

package eu.akoos.photos.data.upload

import java.io.File
import java.util.UUID

/**
 * Crash-consistent journal for the mirror-overwrite paths (mirror-strip, mirror-compress) that
 * replace the user's original on-device file with new bytes. A process killed mid-write throws no
 * exception, so the in-memory restore in [eu.akoos.photos.domain.usecase.UploadPendingUseCase]
 * never runs; without an on-disk record the file is left truncated and the only intact copy is gone.
 * This journal stages the original bytes to a durable backup BEFORE the overwrite starts and replays
 * any unfinished overwrite on the next launch.
 *
 * On-disk state is two files per pending overwrite, keyed by a unique id:
 *   <id>.bak  the original bytes, staged before the device file is touched
 *   <id>.uri  the MediaStore target the bytes must be restored to
 *
 * The write ORDER is the whole correctness argument, because a kill can land between any two file
 * operations and nothing else records progress:
 *
 *   stage: write <id>.bak fully, THEN write <id>.uri. A complete pair (both files) exists only once
 *          the backup is safely on disk. A kill during the backup leaves a <id>.bak with no
 *          <id>.uri, which is indistinguishable from "never started", so it is swept, not restored
 *          (the device file was not touched yet).
 *
 *   overwrite (the caller): only after stage returns a complete pair does the device file get its
 *          truncate-write. A kill here leaves the complete pair intact, so recovery restores the
 *          original from <id>.bak.
 *
 *   commit: delete <id>.uri FIRST, then <id>.bak. A kill between the two leaves a <id>.bak with no
 *          <id>.uri. Reaching commit means the device file already holds the intended new bytes, so
 *          that orphan is swept, never restored (restoring would UNDO a good write). The rule "a lone
 *          .bak is swept, never restored" is therefore correct for BOTH the never-started and the
 *          already-succeeded cases, which are the only two ways a lone .bak can arise.
 *
 * All operations are pure [java.io.File] work (no Context, no ContentResolver, no Android types), so
 * a plain JVM unit test drives the protocol end to end. The directory is owned exclusively by this
 * journal and must live in filesDir, never cacheDir, so the OS cache wipe can never destroy a backup.
 */
class MirrorOverwriteJournal(private val dir: File) {

    /** A pending overwrite: its unique [id], the [targetUri] to restore to, and its [backup] file. */
    data class Entry(val id: String, val targetUri: String, val backup: File)

    /**
     * Stages the current bytes of [targetUri] for a mirror overwrite. [writeBackup] is handed the
     * backup file and must fill it with the original bytes, returning true on success. The <id>.uri
     * marker is written only after [writeBackup] succeeds, so a returned [Entry] always maps to a
     * complete on-disk pair. Returns null and leaves no files behind when [writeBackup] returns false
     * or throws, so a failed staging never leaves a half-pair that a later launch could misread.
     */
    fun stage(targetUri: String, writeBackup: (File) -> Boolean): Entry? {
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val backup = File(dir, id + BAK_SUFFIX)
        val filled = try {
            writeBackup(backup)
        } catch (t: Throwable) {
            backup.delete()
            return null
        }
        if (!filled) {
            backup.delete()
            return null
        }
        // The backup is complete on disk. Only now publish the .uri marker that turns this into a
        // pending pair; a kill before this point leaves an orphan .bak that sweepOrphans discards.
        return try {
            File(dir, id + URI_SUFFIX).writeText(targetUri)
            Entry(id, targetUri, backup)
        } catch (t: Throwable) {
            File(dir, id + URI_SUFFIX).delete()
            backup.delete()
            null
        }
    }

    /**
     * Marks [entry]'s overwrite finished. Deletes the .uri marker FIRST so a kill before the .bak
     * delete leaves an orphan backup that [sweepOrphans] discards without restoring, which is right
     * because reaching commit means the device file already holds the intended new bytes.
     */
    fun commit(entry: Entry) {
        File(dir, entry.id + URI_SUFFIX).delete()
        File(dir, entry.id + BAK_SUFFIX).delete()
    }

    /** Every complete pair currently on disk, each carrying its target uri and backup file. */
    fun pending(): List<Entry> {
        val (baks, uris) = indexById()
        val out = ArrayList<Entry>()
        for ((id, uriFile) in uris) {
            val backup = baks[id] ?: continue
            val target = runCatching { uriFile.readText() }.getOrNull()?.trim().orEmpty()
            if (target.isEmpty()) continue
            out.add(Entry(id, target, backup))
        }
        return out
    }

    /**
     * Deletes the half-pairs that carry no restorable overwrite: a .bak with no .uri (the overwrite
     * already finished or never started) and a .uri with no .bak (nothing left to restore from).
     */
    fun sweepOrphans() {
        val (baks, uris) = indexById()
        for ((id, backup) in baks) if (id !in uris) backup.delete()
        for ((id, uriFile) in uris) if (id !in baks) uriFile.delete()
    }

    /**
     * Replays every pending overwrite. [restore] is handed each entry's target uri and backup file
     * and must return true once the original bytes are back on the device. A restored entry is
     * committed (both files removed) and counted; a failed one is LEFT so a later launch retries it.
     * Orphans are swept afterwards. Idempotent and never throws: a second call after everything
     * restored finds nothing pending and returns 0.
     */
    fun recover(restore: (targetUri: String, backup: File) -> Boolean): Int {
        var restored = 0
        runCatching {
            for (entry in pending()) {
                val ok = runCatching { restore(entry.targetUri, entry.backup) }.getOrDefault(false)
                if (ok) {
                    commit(entry)
                    restored++
                }
            }
            sweepOrphans()
        }
        return restored
    }

    /** Groups the directory's journal files into backups and markers keyed by their shared id. */
    private fun indexById(): Pair<Map<String, File>, Map<String, File>> {
        val baks = HashMap<String, File>()
        val uris = HashMap<String, File>()
        dir.listFiles()?.forEach { file ->
            val name = file.name
            when {
                name.endsWith(BAK_SUFFIX) -> baks[name.dropLast(BAK_SUFFIX.length)] = file
                name.endsWith(URI_SUFFIX) -> uris[name.dropLast(URI_SUFFIX.length)] = file
            }
        }
        return baks to uris
    }

    /** How a guarded overwrite ended. [STRANDED] is the only state that leaves a journal entry behind. */
    enum class Outcome { SKIPPED, WRITTEN, ROLLED_BACK, STRANDED }

    companion object {
        /**
         * The one decision that decides whether an interrupted overwrite can lose the original, lifted
         * out of the Android I/O so a plain JVM test drives every branch.
         *
         * [stage] must return an entry only once the backup is durable, or null to abort before the file
         * is touched. [openAndWrite] returns false when the OS refused the write, meaning nothing was
         * truncated, and throws once it has truncated but cannot finish. [restore] puts the staged bytes
         * back. [commit] drops the entry.
         *
         * The entry is dropped in exactly three cases: the write succeeded, the OS refused it so the file
         * was never touched, or the in-place restore put the original back. When the write truncated the
         * file and the restore ALSO failed, the staged backup is the only surviving copy of the original,
         * so the entry is deliberately left pending and the next launch replays it. A cancellation is
         * restored first and then rethrown, so a cancelled overwrite never leaves a partial file.
         */
        fun <E : Any> guardedOverwrite(
            stage: () -> E?,
            openAndWrite: () -> Boolean,
            restore: () -> Boolean,
            commit: (E) -> Unit,
        ): Outcome {
            val entry = stage() ?: return Outcome.SKIPPED
            return try {
                if (!openAndWrite()) {
                    commit(entry)
                    Outcome.SKIPPED
                } else {
                    commit(entry)
                    Outcome.WRITTEN
                }
            } catch (e: Throwable) {
                val restored = runCatching { restore() }.getOrDefault(false)
                if (restored) commit(entry)
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (restored) Outcome.ROLLED_BACK else Outcome.STRANDED
            }
        }

        /** Subdirectory of filesDir that holds the journal. filesDir, never cacheDir. */
        const val DIR_NAME = "mirror_restore"

        private const val BAK_SUFFIX = ".bak"
        private const val URI_SUFFIX = ".uri"
    }
}
