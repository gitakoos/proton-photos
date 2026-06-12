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

package eu.akoos.photos.domain.usecase

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
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
        // Size is the ONLY reliable disambiguator here (MediaStore indexes no content hash).
        // Without it we can't tell two same-named-but-different photos apart — e.g. burst frames
        // that "rename on upload" collapsed to the same capture-time filename — so claiming the
        // photo already exists would skip the second frame and lose it. When the size is unknown
        // (cloud DTO carried 0) we must NOT pre-skip: fall through to the download, and let the
        // save-time check (which always has the real file size) dedupe precisely. The cost is at
        // most a redundant download when the photo really was already on device.
        if (sizeBytes <= 0L) return null
        val isVideo = mimeType.startsWith("video/")
        // IMPORTANT: use EXTERNAL_CONTENT_URI (the "external" volume alias), NOT the
        // VOLUME_EXTERNAL_PRIMARY-keyed URI. LocalMediaRepository reads through the same
        // "external" URI, so SyncState lookups by localUri only match when both URIs share
        // the same authority — otherwise downloaded photos appear twice in the gallery.
        val collection = if (isVideo)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        // Match on (DISPLAY_NAME, SIZE): displayName alone collides too easily (IMG_0001.jpg is
        // common across folders, and capture-time renames make burst frames share a name); the
        // size pins it to bytes-of-the-same-photo. Size is guaranteed > 0 here (the unknown-size
        // case returned null above).
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
        val args = arrayOf(displayName, sizeBytes.toString())

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

        // Resolve the timestamp once so the insert + post-publish UPDATE use the same
        // value. Always write something — leaving DATE_TAKEN unset lets MediaStore drop
        // the photo at "now" which the user perceives as "the gallery shows my Drive
        // photo as taken today". When the cloud row has no captureTime we deliberately
        // fall back to the file's last-modified time on disk (the download time stamped
        // at decrypt) — still better than the insert-time MediaStore would pick, which
        // is the same instant but minus the few-second decrypt window, so the photo
        // wouldn't even sort alongside other recent downloads.
        val timestampMs = when {
            captureTimeSeconds > 0L -> captureTimeSeconds * 1000L
            file.lastModified() > 0L -> file.lastModified()
            else -> System.currentTimeMillis()
        }

        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            // DATE_TAKEN is MILLISECONDS, DATE_MODIFIED is SECONDS — keep them in sync so
            // galleries that read either column sort the photo to the right place.
            put(MediaStore.MediaColumns.DATE_TAKEN, timestampMs)
            put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMs / 1000L)
            // Don't set DATE_ADDED — MediaStore manages it as "when was this row inserted"
            // and overriding can break some gallery apps' "Recently added" sort.
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

            // EXIF write removed by request — modifying the file's EXIF block changes its
            // byte content and would break hash-based cloud↔local matching (the cloud's
            // contentHash was computed over the original bytes). We rely on MediaStore's
            // DATE_TAKEN column instead; the split-update below makes that write reliable
            // on the Android 13+ versions that were dropping single-update timestamps.

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Flip IS_PENDING in a FIRST update — committing the row makes it visible
                // to MediaStore's EXIF scanner so the column is now backed by the file's
                // EXIF block (above). On Pixel + Samsung Android 13+ the IS_PENDING flip
                // alone triggers a media scan that overrides any column values we tried
                // to set during insert, which is why we write EXIF directly above instead.
                val pending = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, pending, null, null)
                // SECOND update — explicitly re-write DATE_TAKEN + DATE_MODIFIED AFTER the
                // pending flip. Some Android 13+ versions drop timestamp updates when
                // combined with the IS_PENDING flip, so split this into two updates.
                val dates = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_TAKEN, timestampMs)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMs / 1000L)
                }
                context.contentResolver.update(uri, dates, null, null)
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
