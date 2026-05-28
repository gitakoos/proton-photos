package eu.akoos.photos.data.hidden

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.akoos.photos.util.ProtonPhotosStorage
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
            val src = runCatching { Uri.parse(srcUri) }.getOrNull() ?: return@withContext null
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
                } ?: return@withContext null
            } catch (e: Exception) {
                Log.w(TAG, "store: failed to copy $srcUri → ${dest.absolutePath}: ${e.message}")
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
     * @param albumFolderName Optional album name — when the file's cloud sibling belongs to
     *   an album, pass the (sanitized) album name so the restored copy lands in the matching
     *   `Pictures/<AlbumName>/` folder, where downloads for that album also go. When null,
     *   the file lands in `Pictures/` root, matching where non-album downloads go. The legacy
     *   `Pictures/Proton Photos/Recovered/` location is no longer used — it created a phantom
     *   album in the device gallery that didn't match the unified download routing.
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
            // Mirror DownloadPhotosUseCase's routing — album-bound files into
            // Pictures/<AlbumName>/ (or Movies/<AlbumName>/), unaffiliated files into the
            // Pictures/ or Movies/ root. The previous "Recovered" subfolder is gone.
            val baseDir = if (isVideo) "Movies" else "Pictures"
            val relPath = if (albumFolderName.isNullOrBlank()) baseDir else "$baseDir/$albumFolderName"

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
            val target = context.contentResolver.insert(collection, values) ?: return@withContext null
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

    /** Hard-deletes a hidden file from app-private storage. */
    fun delete(hiddenUri: String): Boolean {
        val parsed = runCatching { Uri.parse(hiddenUri) }.getOrNull() ?: return false
        val file = parsed.path?.let { File(it) } ?: return false
        return file.exists() && file.delete()
    }

    private companion object {
        const val TAG = "HiddenStorage"
    }
}
