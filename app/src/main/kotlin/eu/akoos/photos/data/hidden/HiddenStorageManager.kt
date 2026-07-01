/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves photos in and out of app-private storage so that other gallery apps on the device
 * cannot see hidden items. The Hidden Album then renders directly from the private files
 * (file://...path) instead of from MediaStore.
 *
 * Hide flow:
 *   1. [store] copies the source bytes into `filesDir/hidden/<uuid>.<ext>` (private to this app)
 *   2. The caller is expected to delete the source MediaStore entry via the existing
 *      DeletePhotoUseCase pipeline (which handles the Android 11+ system trash confirmation).
 *
 * Unhide flow:
 *   1. [restore] inserts a fresh MediaStore entry under Pictures/Proton Photos/Recovered with
 *      the original bytes, then deletes the private copy.
 */
@Singleton
class HiddenStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val hiddenDir: File
        get() = File(context.filesDir, "hidden").also { it.mkdirs() }

    /** Deletes every blob in the vault. Used on sign-out, after the hidden index is cleared, so the
     *  now-orphaned decrypted photos do not linger on disk for the next account on this device. */
    fun clearVault() {
        hiddenDir.deleteRecursively()
    }

    /**
     * Resolves the full folder a source media item lives in, stashed at hide time so [restore] can
     * return the file to its EXACT original location — including non-MediaStore folders such as an
     * app's "Android/media/<pkg>/…". Returns the real MediaStore RELATIVE_PATH unchanged (e.g.
     * "DCIM/Camera", "Pictures/Vacation", "Android/media/com.whatsapp/Media/WhatsApp Images"), or
     * [fallbackBucketName] when RELATIVE_PATH is unreadable (pre-Q / non-MediaStore URI), or null.
     */
    fun sourceFolderFor(srcUri: String, fallbackBucketName: String?): String? {
        val relativePath: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val src = Uri.parse(srcUri)
                context.contentResolver.query(
                    src, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        } else null
        // Keep the FULL original RELATIVE_PATH so restore can put the file back exactly where it
        // came from, not just somewhere under Pictures/.
        val resolved = relativePath?.trim('/')?.takeIf { it.isNotBlank() }
        return (resolved ?: fallbackBucketName?.trim('/'))?.takeIf { it.isNotBlank() }
    }

    /** Returns true when [uri] is a private hidden-storage file URI managed by this class. */
    fun isHiddenUri(uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        val path = parsed.path ?: return false
        return parsed.scheme == "file" && path.startsWith(hiddenDir.absolutePath)
    }

    /**
     * Copies [srcUri] (typically `content://media/...`) into private storage.
     * Returns the new `file://...` URI string on success, null on failure.
     *
     * Does NOT delete the source — callers must do that explicitly (so the user can confirm
     * the system trash dialog on Android 11+).
     */
    suspend fun store(
        srcUri: String,
        originalDisplayName: String? = null,
        mimeType: String? = null,
        /** Original capture-time of the source in epoch-ms. Encoded into the hidden file's
         *  name as a "__<ms>" suffix so [restore] can rebuild DATE_TAKEN without depending
         *  on EXIF (which PNGs / WebPs / Screenshots / Videos rarely carry). When null,
         *  restore falls back to EXIF DateTimeOriginal, then to "now". */
        captureTimeMs: Long? = null,
    ): String? =
        withContext(Dispatchers.IO) {
            // Non-reversible per-file token so a failure can be correlated in the diagnostics
            // log without recording the file name (which would leak what was being hidden).
            val ref = logRef(srcUri)
            val src = runCatching { Uri.parse(srcUri) }.getOrNull() ?: run {
                eu.akoos.photos.util.SyncDiagnostics.log("hide $ref: skipped, bad source uri")
                return@withContext null
            }
            val ext = (mimeType ?: context.contentResolver.getType(src))
                ?.substringAfterLast('/')
                ?.let { if (it == "jpeg") "jpg" else it }
                ?.lowercase()
                ?: originalDisplayName?.substringAfterLast('.', "")
                ?: "bin"
            val timeSuffix = if (captureTimeMs != null && captureTimeMs > 0L) "__$captureTimeMs" else ""
            val dest = File(hiddenDir, "${UUID.randomUUID()}$timeSuffix.$ext")
            try {
                context.contentResolver.openInputStream(src)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    eu.akoos.photos.util.SyncDiagnostics.log("hide $ref: skipped, source stream unavailable")
                    return@withContext null
                }
            } catch (e: Exception) {
                Log.w(TAG, "store: failed to copy source → ${dest.absolutePath}: ${e.message}")
                eu.akoos.photos.util.SyncDiagnostics.log(
                    "hide $ref: failed copy (${e.javaClass.simpleName})",
                )
                dest.delete()
                return@withContext null
            }
            Uri.fromFile(dest).toString()
        }

    /**
     * Restores a previously hidden file back to MediaStore so it becomes visible to other
     * gallery apps again. The hidden private file is removed once the MediaStore entry is
     * created. Returns the new MediaStore content URI string, or null on failure.
     *
     * @param albumFolderName No longer used for routing — restored files always land in the
     *   `Pictures/` (or `Movies/`) root, never a subfolder, matching where downloads now go. A
     *   per-album subfolder used to surface as a redundant phantom album in the device gallery.
     */
    suspend fun restore(
        hiddenUri: String,
        originalDisplayName: String? = null,
        albumFolderName: String? = null,
    ): String? =
        withContext(Dispatchers.IO) {
            val parsed = runCatching { Uri.parse(hiddenUri) }.getOrNull() ?: return@withContext null
            val srcFile = parsed.path?.let { File(it) }?.takeIf { it.exists() } ?: return@withContext null
            val ext = srcFile.extension.lowercase()
            val isVideo = ext in setOf("mp4", "m4v", "mov", "3gp", "ts", "mkv", "webm", "avi")
            val resolvedName = originalDisplayName ?: "recovered_${System.currentTimeMillis()}.$ext"
            val mime = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "heic", "heif" -> "image/heic"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                else -> if (isVideo) "video/*" else "image/*"
            }
            val collection = if (isVideo)
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val baseDir = if (isVideo) "Movies" else "Pictures"

            // Preserve the original capture time across hide → unhide. Without this, the
            // restored MediaStore row gets DATE_TAKEN = now, which makes the file look like
            // a brand-new photo: (1) the gallery groups it under today instead of the
            // original capture day; (2) ReconcileSyncStateUseCase's byNameAndDate match
            // fails (cloud has the original timestamp, local has "now"), the file is
            // classified LOCAL_ONLY, and it gets re-uploaded as a duplicate.
            //
            // Strategy: pull DateTimeOriginal out of the hidden file's EXIF. JPEG / HEIC
            // images that came through our backup pipeline carry the original timestamp
            // even after a hide/unhide round-trip. Videos rarely have an EXIF block —
            // accept that they fall back to "now" (the alternative is parsing MP4 atoms,
            // disproportionate effort).
            // 1. Filename-encoded capture time wins — that's what we stashed at hide time
            //    via [store]'s captureTimeMs param, covering PNG/WebP/Screenshot/Video
            //    where EXIF DateTimeOriginal is absent. Format: "<uuid>__<ms>.<ext>".
            // 2. Fallback to EXIF for files imported into hidden through older code paths
            //    that didn't pass captureTimeMs (or were copied around outside this app).
            // 3. Both null = "now" (acceptable last-resort; user can manually fix DATE_TAKEN
            //    via a gallery editor if it matters to them).
            val nameStem = srcFile.nameWithoutExtension
            val captureTimeFromName: Long? = nameStem.substringAfter("__", "")
                .takeIf { it.isNotBlank() }
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            val captureTimeMs: Long? = captureTimeFromName ?: if (!isVideo) runCatching {
                androidx.exifinterface.media.ExifInterface(srcFile.absolutePath)
                    .getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?.let { dt ->
                        // EXIF DateTimeOriginal format: "yyyy:MM:dd HH:mm:ss" (local time, no zone).
                        val fmt = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                        fmt.parse(dt)?.time
                    }
            }.getOrNull() else null

            // Restore the file to exactly where it came from. [albumFolderName] is the full original
            // RELATIVE_PATH stashed at hide time (e.g. "DCIM/Camera", "Pictures/Vacation", or an app's
            // "Android/media/com.whatsapp/Media/WhatsApp Images").
            val origPath = albumFolderName?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
            val firstSeg = origPath?.substringBefore('/')
            // MediaStore can only create image/video rows under these roots; anything else (an app's
            // Android/media folder, Download/, …) has to be written by hand and then scanned in.
            val mediaStoreRoots = if (isVideo) setOf("DCIM", "Movies") else setOf("DCIM", "Pictures")
            val standardRoots = setOf(
                "DCIM", "Pictures", "Movies", "Music", "Download", "Documents",
                "Android", "Audiobooks", "Podcasts", "Ringtones", "Alarms", "Notifications", "Recordings",
            )

            // 1. Original location is a real path OUTSIDE MediaStore's image/video roots (e.g. the
            //    WhatsApp media folder) — write the bytes straight back there and index them. Needs
            //    all-files access; if that is not granted the write fails and we fall through to the
            //    Pictures/Movies root below.
            if (origPath != null && firstSeg != null && firstSeg in standardRoots && firstSeg !in mediaStoreRoots) {
                val restored = restoreToOriginalPath(srcFile, origPath, resolvedName, captureTimeMs)
                if (restored != null) {
                    srcFile.delete()
                    return@withContext restored
                }
            }

            // 2. MediaStore path. A standard image/video dir (DCIM/Pictures/Movies) is restored in
            //    place; an older stripped bare folder name lands under Pictures/<name>; nothing known
            //    falls back to the Pictures/ (or Movies/) root.
            val relPath = when {
                origPath == null -> baseDir
                firstSeg in mediaStoreRoots -> origPath
                firstSeg in standardRoots -> baseDir
                else -> "$baseDir/$origPath"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, resolvedName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                if (captureTimeMs != null && captureTimeMs > 0L) {
                    // Both DATE_TAKEN (ms) and DATE_MODIFIED (s) reflect the original
                    // capture moment — matches what DownloadPhotosUseCase writes so the
                    // unhide produces the same row shape as a fresh cloud download would.
                    put(MediaStore.MediaColumns.DATE_TAKEN, captureTimeMs)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, captureTimeMs / 1000L)
                }
            }
            // Some MediaProvider builds reject an uncommon video mime (notably video/x-matroska for
            // .mkv) by THROWING IllegalArgumentException instead of returning null. Retry under the
            // generic video/* (or image/*) mime so the file still lands, and skip gracefully rather
            // than crashing the unhide if even that is refused.
            val target = try {
                context.contentResolver.insert(collection, values)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "restore: insert rejected mime '$mime', retrying generic: ${e.message}")
                values.put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/*" else "image/*")
                runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
            } ?: return@withContext null
            try {
                context.contentResolver.openOutputStream(target)?.use { out ->
                    srcFile.inputStream().use { it.copyTo(out) }
                } ?: return@withContext null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finalize = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    context.contentResolver.update(target, finalize, null, null)
                }
                // Private copy no longer needed.
                srcFile.delete()
                target.toString()
            } catch (e: Exception) {
                Log.w(TAG, "restore: failed for $hiddenUri: ${e.message}")
                // Roll back the half-created MediaStore entry.
                runCatching { context.contentResolver.delete(target, null, null) }
                null
            }
        }

    /** Writes a restored file straight to its original [relPath] under primary external storage and
     *  indexes it with the media scanner. Returns the file:// URI on success, or null on any failure
     *  (e.g. all-files access not granted) so the caller can fall back to a MediaStore location. */
    private fun restoreToOriginalPath(srcFile: File, relPath: String, name: String, captureTimeMs: Long?): String? =
        runCatching {
            @Suppress("DEPRECATION")
            val root = android.os.Environment.getExternalStorageDirectory()
            val destDir = File(root, relPath).apply { mkdirs() }
            val dest = File(destDir, name)
            srcFile.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            if (captureTimeMs != null && captureTimeMs > 0L) dest.setLastModified(captureTimeMs)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
            Uri.fromFile(dest).toString()
        }.getOrNull()

    /** Hard-deletes a hidden file from app-private storage. */
    fun delete(hiddenUri: String): Boolean {
        val parsed = runCatching { Uri.parse(hiddenUri) }.getOrNull() ?: return false
        val file = parsed.path?.let { File(it) } ?: return false
        return file.exists() && file.delete()
    }

    /**
     * Rename a hidden file on disk. Returns the new file:// URI string on success.
     *
     * Preserves the `__<captureMs>.<ext>` suffix [store] embeds so a later [restore] still
     * recovers DATE_TAKEN — only the stem (the part before `__`) gets the user's name.
     * If the existing file has no suffix (older entries from before capture-time was
     * embedded), the new name keeps the original extension.
     *
     * The user-supplied [newName] is sanitised: filesystem-illegal characters become `_`,
     * extension stripped (we always reuse the original file's extension to keep the MIME
     * lookup in [LocalMediaRepositoryImpl.queryByUri] working).
     */
    suspend fun rename(hiddenUri: String, newName: String): String? = withContext(Dispatchers.IO) {
        val parsed = runCatching { Uri.parse(hiddenUri) }.getOrNull() ?: return@withContext null
        val srcFile = parsed.path?.let { File(it) }?.takeIf { it.exists() } ?: return@withContext null
        val ext = srcFile.extension
        // Pull the "__<ms>" capture-time tag out of the OLD stem so we can re-attach it
        // to the new name. Without this the renamed file loses its capture-time hint and
        // restore falls back to "now".
        val oldStem = srcFile.nameWithoutExtension
        val captureSuffix = oldStem.indexOf("__").let { if (it >= 0) oldStem.substring(it) else "" }
        // Strip extension if the user accidentally typed it, sanitise the rest.
        val userStem = newName.trim().substringBeforeLast('.', newName.trim())
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "renamed_${System.currentTimeMillis()}" }
        val newFileName = if (ext.isNotEmpty()) "$userStem$captureSuffix.$ext" else "$userStem$captureSuffix"
        val dest = File(srcFile.parentFile, newFileName)
        if (dest.exists()) {
            Log.w(TAG, "rename: target $newFileName already exists, refusing")
            return@withContext null
        }
        if (!srcFile.renameTo(dest)) {
            Log.w(TAG, "rename: srcFile.renameTo() returned false for $srcFile → $dest")
            return@withContext null
        }
        Uri.fromFile(dest).toString()
    }

    private companion object {
        const val TAG = "HiddenStorage"

        /** Stable, non-reversible 6-char token from a source URI for privacy-safe diagnostics
         *  correlation — matches PhotoUploadService.logRef so log lines read the same way. */
        fun logRef(uri: String): String =
            uri.hashCode().toUInt().toString(16).padStart(8, '0').take(6)
    }
}
