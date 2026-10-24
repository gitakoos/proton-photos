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

import android.content.Context
import android.net.Uri
import android.util.Log
import eu.akoos.photos.domain.usecase.UPLOAD_TAG
import java.io.File
import java.security.MessageDigest

/** cacheDir entries with one of these name prefixes are upload-path temps. Age-swept once per pass
 *  so a permanently-failing file's temp (or one stranded by a process death) can't linger forever.
 *  `upload_` resume dirs are covered here too, backstopping the periodic CachePruneWorker sweep. */
private val UPLOAD_TEMP_PREFIXES = listOf(
    // `mirror_bak_` sweeps any legacy mirror-overwrite backup left in cacheDir by an older build. The
    // crash-consistent backups now live in filesDir/<MirrorOverwriteJournal.DIR_NAME>, outside cacheDir,
    // so this cacheDir sweep never reaches the journal and can never delete the only intact copy.
    "upload_", "videocompress_", "revision_", "stripped_", "compressed_", "mirror_bak_",
)

/** Only upload temps older than this are swept, so an in-flight upload or a recently-preserved
 *  resume dir (both freshly created) is never removed; the age gate is the whole safety. */
private val STALE_UPLOAD_TEMP_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(3)

/**
 * The upload pipeline's reads and writes of the on-device file: the content hash, the current length,
 * the mirror overwrite and rename of the original, and the age sweep of upload temps.
 */
internal class UploadDeviceFiles(private val context: Context) {

    /** Crash-consistent backup of the bytes a mirror overwrite is about to replace. Lives in
     *  filesDir (never cacheDir), so a process killed mid-truncate is recovered on the next launch
     *  by [eu.akoos.photos.data.upload.MirrorOverwriteJournal]. Lazy so nothing reads filesDir until
     *  a mirror overwrite actually runs. */
    private val mirrorOverwriteJournal by lazy {
        eu.akoos.photos.data.upload.MirrorOverwriteJournal(
            File(context.filesDir, eu.akoos.photos.data.upload.MirrorOverwriteJournal.DIR_NAME),
        )
    }

    /**
     * Deletes upload-path temps in cacheDir whose name starts with a known prefix and whose
     * lastModified is older than [STALE_UPLOAD_TEMP_MS]. Dirs (e.g. an `upload_` resume dir) are
     * removed recursively. The age gate is the safety: an in-flight upload only reads freshly
     * created temps, and a retryable failure's preserved resume dir is only ever >3 days old once
     * abandoned, so a recent one is never swept and a resume can still find it. Best-effort and
     * cheap: one listFiles() with nothing to do when no temp is stale; never blocks upload work.
     */
    fun sweepStaleUploadTemps() {
        runCatching {
            val cutoff = System.currentTimeMillis() - STALE_UPLOAD_TEMP_MS
            var removed = 0
            context.cacheDir.listFiles()?.forEach { entry ->
                if (UPLOAD_TEMP_PREFIXES.none { entry.name.startsWith(it) }) return@forEach
                val mtime = entry.lastModified()
                if (mtime in 1..cutoff) {
                    val ok = if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
                    if (ok) removed++
                }
            }
            if (removed > 0) Log.d(UPLOAD_TAG, "Swept $removed stale upload temp(s) from cache")
        }.onFailure { e -> Log.w(UPLOAD_TAG, "Stale upload-temp sweep failed: ${e.message}") }
    }

    /** Renames the on-device MediaStore file in place (silent with MANAGE_MEDIA), so a mirrored
     *  upload's local copy shares the cloud name. A write the OS refuses is logged and skipped. */
    fun renameLocalInPlace(uri: String, newName: String): Boolean = try {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, newName)
        }
        val rows = context.contentResolver.update(Uri.parse(uri), values, null, null)
        if (rows > 0) Log.d(UPLOAD_TAG, "Mirror rename: on-device file renamed to '$newName'")
        rows > 0
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(UPLOAD_TAG, "Mirror rename skipped for $uri: ${e.message}")
        false
    }

    /** Overwrites the on-device MediaStore file with [src]'s bytes (silent with MANAGE_MEDIA /
     *  all-files). Used to mirror a strip onto the original: a motion photo's in-place EXIF rewrite
     *  would drop the trailer, and a video can't be EXIF-edited at all, so the whole stripped file is
     *  written instead. The current bytes are first staged into [mirrorOverwriteJournal], then "wt"
     *  truncates and rewrites the file. A copy that throws (I/O error, eject) is rolled back in place
     *  from the staged backup, and a process killed mid-truncate is rolled back from the same backup
     *  on the next launch, so the original is never left partial. A write the OS refuses is logged and
     *  skipped (the original stays intact); a staging failure returns false without touching the file. */
    fun overwriteLocalInPlace(uri: String, src: File): Boolean {
        val parsed = Uri.parse(uri)
        var backup: File? = null
        val outcome = eu.akoos.photos.data.upload.MirrorOverwriteJournal.guardedOverwrite(
            stage = {
                // Staging publishes its marker only once the backup copy is complete, so a kill during
                // the truncate-write below always leaves a journal pair the next launch can replay.
                mirrorOverwriteJournal.stage(uri) { file ->
                    context.contentResolver.openInputStream(parsed)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                        true
                    } ?: false
                }?.also { backup = it.backup }
            },
            openAndWrite = {
                // A null stream means the OS refused the write, so nothing was truncated. Once the
                // stream opens, "wt" has already emptied the file and any failure must be rolled back.
                val out = context.contentResolver.openOutputStream(parsed, "wt")
                if (out == null) {
                    false
                } else {
                    out.use { o -> src.inputStream().use { it.copyTo(o) } }
                    true
                }
            },
            restore = {
                val bak = backup
                bak != null && context.contentResolver.openOutputStream(parsed, "wt")?.use { o ->
                    bak.inputStream().use { it.copyTo(o) }
                } != null
            },
            commit = { entry -> mirrorOverwriteJournal.commit(entry) },
        )
        when (outcome) {
            MirrorOverwriteJournal.Outcome.WRITTEN -> return true
            MirrorOverwriteJournal.Outcome.SKIPPED ->
                Log.w(UPLOAD_TAG, "Mirror overwrite skipped for $uri (backup or write refused)")
            MirrorOverwriteJournal.Outcome.ROLLED_BACK ->
                Log.w(UPLOAD_TAG, "Mirror overwrite failed for $uri; original restored")
            MirrorOverwriteJournal.Outcome.STRANDED ->
                Log.e(
                    UPLOAD_TAG,
                    "Mirror overwrite failed for $uri and the in-place restore also failed; the original " +
                        "stays staged in the journal and is replayed on the next launch",
                )
        }
        return false
    }

    /** The device file's length as it is now, read from the file itself rather than a scan. */
    fun deviceFileLength(uri: String): Long? = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { it.statSize }
    }.getOrNull()?.takeIf { it > 0L }

    /**
     * Hex-encodes the SHA-1 of the file's plaintext content. Drive's `ContentHash`
     * wire field is `HMAC-SHA256(rootNodeHashKey, sha1Hex.utf8Bytes())` and Drive
     * web's `photosTransferPayloadBuilder` rejects any payload whose photo content
     * hash was derived from a SHA-256 input with the misleading error
     * "Cannot build photo payload without a content hash". Drive Android picks SHA-1
     * via `ConfigurationProvider.contentDigestAlgorithm = "SHA1"` (Drive Android's
     * `ConfigurationProvider`). The xAttr Common.Digests
     * map carries the same SHA-1 hex so a later cross-client xAttr verify lines up.
     */
    fun computeSha1(uri: String): String? {
        val digest = MessageDigest.getInstance("SHA-1")
        try {
            // A null stream (or a read error below) means the content is unreadable this pass. Return
            // null rather than the SHA-1 of empty/partial input: a bogus hash persisted as localHash
            // and sent as the content hash never content-pairs on reconcile, so the file uploads as a
            // silent Drive duplicate (and multiple failures would even share one hash). The caller
            // treats null as a hard per-file failure and retries the row next pass.
            val stream = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
            stream.use { s ->
                val buffer = ByteArray(8192)
                var read: Int
                while (s.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return null
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
