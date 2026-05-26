package me.proton.photos.domain.usecase

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import me.proton.core.domain.entity.UserId
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.domain.entity.GalleryItem
import me.proton.photos.domain.entity.SyncState
import me.proton.photos.domain.entity.SyncStatus
import me.proton.photos.domain.repository.DrivePhotoRepository
import me.proton.photos.domain.repository.SyncStateRepository
import me.proton.photos.util.ProtonPhotosStorage
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DownloadPhotos"

@Singleton
class DownloadPhotosUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
) {
    data class Progress(val done: Int, val total: Int, val failed: Int, val skipped: Int = 0)

    /**
     * Downloads cloud photos into the per-photo folder. Caller passes [folderName] as the
     * fallback used when a photo isn't in [folderByLinkId] — empty string means "save into
     * Pictures/ (or Movies/) root, no subfolder".
     *
     * Already-on-device photos are recognised globally (across all MediaStore folders) so
     * the same file is never downloaded twice; SyncState is linked to the existing URI.
     */
    suspend fun downloadCloudPhotos(
        userId: UserId,
        photos: List<CloudPhoto>,
        folderName: String,
        folderByLinkId: Map<String, String> = emptyMap(),
        onProgress: suspend (Progress) -> Unit = {},
    ): Progress {
        var done = 0; var failed = 0; var skipped = 0
        photos.forEach { photo ->
            try {
                val existing = alreadyExistsInMediaStore(photo.displayName, photo.mimeType, photo.sizeBytes)
                if (existing != null) {
                    linkSyncState(userId, existing, photo)
                    skipped++
                    Log.d(TAG, "Already on device, skipped: ${photo.displayName}")
                } else {
                    val folder = folderByLinkId[photo.linkId] ?: folderName
                    val file = cloudRepo.downloadFullResPhoto(userId, photo)
                    val savedUri = saveFileToMediaStore(
                        file, photo.displayName, photo.mimeType, folder,
                        captureTimeSeconds = photo.captureTime,
                    )
                    file.delete()
                    if (savedUri != null) linkSyncState(userId, savedUri, photo)
                    done++
                    Log.d(TAG, "Downloaded ${photo.displayName} → ${folder.ifEmpty { "<root>" }}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download ${photo.displayName}: ${e.message}")
                failed++
            }
            onProgress(Progress(done + skipped + failed, photos.size, failed, skipped))
        }
        return Progress(done + skipped + failed, photos.size, failed, skipped)
    }

    /**
     * Downloads only the gallery items that aren't already on the device.
     *
     * Three buckets:
     *  - **skipped** — already-on-device (LocalOnly, Synced, or cloud item whose DISPLAY_NAME
     *    already lives in the target folder). No network or decrypt work; we just refresh
     *    the SyncState link so the gallery shows the existing file as Synced.
     *  - **done**    — actually downloaded this run.
     *  - **failed**  — fetch/decrypt error.
     */
    suspend fun downloadGalleryItems(
        userId: UserId,
        items: List<GalleryItem>,
        folderName: String = "",
        folderByLinkId: Map<String, String> = emptyMap(),
        onProgress: suspend (Progress) -> Unit = {},
    ): Progress {
        val cloudItems = items.filterIsInstance<GalleryItem.CloudOnly>().map { it.cloud }
        val alreadyOnDevice = items.count { it !is GalleryItem.CloudOnly }
        var done = 0; var failed = 0; var skipped = alreadyOnDevice
        onProgress(Progress(done + skipped + failed, items.size, failed, skipped))

        cloudItems.forEach { photo ->
            try {
                val existing = alreadyExistsInMediaStore(photo.displayName, photo.mimeType, photo.sizeBytes)
                if (existing != null) {
                    linkSyncState(userId, existing, photo)
                    skipped++
                    Log.d(TAG, "Already on device, skipped: ${photo.displayName}")
                } else {
                    // Per-photo override beats the default folder. Album-bound photos land in
                    // Pictures/<AlbumName>/; non-album photos in Pictures/ root (folder = "").
                    val folder = folderByLinkId[photo.linkId] ?: folderName
                    val file = cloudRepo.downloadFullResPhoto(userId, photo)
                    val savedUri = saveFileToMediaStore(
                        file, photo.displayName, photo.mimeType, folder,
                        captureTimeSeconds = photo.captureTime,
                    )
                    file.delete()
                    if (savedUri != null) linkSyncState(userId, savedUri, photo)
                    done++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download ${photo.displayName}: ${e.message}")
                failed++
            }
            onProgress(Progress(done + skipped + failed, items.size, failed, skipped))
        }
        return Progress(done + skipped + failed, items.size, failed, skipped)
    }

    /**
     * Creates (or refreshes) a SYNCED SyncState record that links [savedUri] on the device
     * to [photo] in the cloud.  This makes the gallery merge the two into a single
     * [GalleryItem.Synced] entry with the green-cloud indicator instead of showing a separate
     * LocalOnly + CloudOnly pair after download.
     */
    private suspend fun linkSyncState(userId: UserId, savedUri: Uri, photo: CloudPhoto) {
        try {
            syncStateRepo.upsert(
                SyncState(
                    localUri          = savedUri.toString(),
                    cloudFileId       = photo.linkId,
                    localHash         = "",
                    cloudHash         = null,
                    status            = SyncStatus.SYNCED,
                    lastSyncAttemptMs = System.currentTimeMillis(),
                    lastSyncSuccessMs = System.currentTimeMillis(),
                    backedUpAtMs      = null,
                    sizeBytes         = photo.sizeBytes,
                ),
                userId,
            )
            Log.d(TAG, "SyncState linked: ${photo.displayName} → ${savedUri}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create SyncState for ${photo.displayName}: ${e.message}")
        }
    }

    /**
     * Looks up an existing MediaStore entry for [displayName] across the ENTIRE images/videos
     * volume — not just under [folder]. Reason: a photo the user took on this device originally
     * lives in `DCIM/Camera/` (or wherever the camera app saved it). When we download the cloud
     * copy of the same photo, the user expects us to recognise the existing local file and skip
     * the download, not create a duplicate under `Pictures/Proton Photos/`.
     *
     * Match key is `(DISPLAY_NAME, SIZE)`: the displayName alone collides too easily (`IMG_0001.jpg`
     * is common across folders); the size pair pins it to bytes-of-the-same-photo. We can't use
     * a content hash here because MediaStore doesn't index one cheaply.
     *
     * Returns null on pre-Q devices (legacy storage; no MediaStore RELATIVE_PATH yet) — those
     * fall through to a fresh save under [folder].
     */
    private fun alreadyExistsInMediaStore(displayName: String, mimeType: String, sizeBytes: Long): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val isVideo = mimeType.startsWith("video/")
        // IMPORTANT: use EXTERNAL_CONTENT_URI (the "external" volume alias), NOT the
        // VOLUME_EXTERNAL_PRIMARY-keyed URI. LocalMediaRepository reads through the same
        // "external" URI, so SyncState lookups by localUri only match when both URIs share
        // the same authority — otherwise downloaded photos appear twice in the gallery.
        val collection = if (isVideo)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        // Size = 0 means "we don't know" (cloud DTO didn't carry it) — fall back to a
        // displayName-only match. Risk: same filename in two different folders matches the
        // wrong file, but that's the same risk LocalMediaRepository already accepts for the
        // gallery merge. Better than re-downloading every time.
        val (selection, args) = if (sizeBytes > 0L) {
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?" to
                arrayOf(displayName, sizeBytes.toString())
        } else {
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?" to arrayOf(displayName)
        }

        val cursor = context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
            selection,
            args,
            null,
        )
        return cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val path = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                Log.d(TAG, "Found existing $displayName at $path (size=$sizeBytes)")
                Uri.withAppendedPath(collection, id.toString())
            } else null
        }
    }

    /**
     * Saves [file] into MediaStore and returns the content URI of the saved entry.
     * If the file already exists in the target folder, returns the existing URI (no duplicate).
     * Returns null on failure.
     *
     * @param captureTimeSeconds Original capture time (Unix seconds) — written to DATE_TAKEN
     *   and DATE_MODIFIED so the device gallery sorts the downloaded photo by its real-world
     *   capture moment instead of by download time. Pass 0 to skip and let MediaStore default
     *   to current time (only for local copies whose origin time isn't known).
     */
    private fun saveFileToMediaStore(
        file: File,
        displayName: String,
        mimeType: String,
        folder: String,
        captureTimeSeconds: Long = 0L,
    ): Uri? {
        val isVideo = mimeType.startsWith("video/")
        // Empty folder = save into Pictures/ (or Movies/) root with no subfolder, so
        // the device gallery doesn't surface a redundant "Proton Photos" album for
        // non-album-bound downloads. Album-bound photos get folder = "<AlbumName>" and
        // land in Pictures/<AlbumName>/.
        val base = if (isVideo) "Movies" else "Pictures"
        val relPath = if (folder.isEmpty()) base else "$base/$folder"

        // Second-chance global dedupe: callers already pre-skipped via alreadyExistsInMediaStore,
        // but a concurrent write between the check and the save can sneak in. Size = file.length()
        // is the bytes we're about to write; if a row with the same (displayName, size) sprang up
        // meanwhile, return its URI instead of creating a duplicate.
        alreadyExistsInMediaStore(displayName, mimeType, file.length())?.let { existingUri ->
            Log.d(TAG, "Skipped duplicate: $displayName already in MediaStore")
            return existingUri
        }

        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            if (captureTimeSeconds > 0L) {
                // DATE_TAKEN is the primary "when was this photo/video shot" column the
                // device gallery sorts by. Stored in MILLISECONDS since epoch.
                put(MediaStore.MediaColumns.DATE_TAKEN, captureTimeSeconds * 1000L)
                // DATE_MODIFIED is SECONDS since epoch and acts as a fallback for galleries
                // that don't read DATE_TAKEN. Match the capture time so both sorts agree.
                put(MediaStore.MediaColumns.DATE_MODIFIED, captureTimeSeconds)
                // Note: don't set DATE_ADDED — MediaStore manages it as "when was this row
                // inserted" and overriding can break some gallery apps' "Recently added".
            }
        }

        // Always insert through the "external" alias (EXTERNAL_CONTENT_URI) so the returned
        // URI uses the same authority LocalMediaRepository observes — otherwise the gallery
        // can't merge the downloaded file with its cloud counterpart via SyncState.
        val collection = if (isVideo)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val uri = context.contentResolver.insert(collection, cv)
            ?: run {
                Log.w(TAG, "MediaStore insert failed for $displayName")
                return null
            }

        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("openOutputStream returned null for $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pending = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, pending, null, null)
            }
            uri
        } catch (e: Exception) {
            // Clean up the pending MediaStore entry on failure
            context.contentResolver.delete(uri, null, null)
            Log.w(TAG, "saveFileToMediaStore failed for $displayName: ${e.message}")
            null
        }
    }
}
