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
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DownloadPhotos"

/** How many album photos download concurrently. Each photo already fetches its blocks in
 *  parallel, so a moderate fan-out keeps the pipeline busy without overwhelming the device;
 *  the rate-limit-sensitive metadata calls stay bounded by the shared network semaphore, and
 *  the CDN block fetches carry their own retry/backoff, so a handful of photos in flight stays
 *  well within Drive's limits. This is the dominant limiter for large-album downloads. */
private const val DOWNLOAD_PARALLELISM = 6

/** Image MIME types ExifInterface can reliably WRITE via saveAttributes. Other formats (HEIC,
 *  video) keep only the MediaStore column date. */
private val EXIF_WRITABLE_MIMES = setOf("image/jpeg", "image/png", "image/webp")

@Singleton
class DownloadPhotosUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
    private val linkDetailHelpers: LinkDetailHelpers,
) {
    data class Progress(val done: Int, val total: Int, val failed: Int, val skipped: Int = 0)

    /** Pre-resolved per-file metadata for a download set, keyed strictly by linkId. A linkId
     *  absent from a map means "not prefetched" — the downloader falls back to its per-file
     *  fetch for that file, so behaviour is identical to a download with no prefetch at all. */
    private data class PrefetchedDownloadMetadata(
        val linkDetailByLinkId: Map<String, BatchLinkDto>,
    )

    /**
     * Batch-prefetches the redundant per-file link-detail round-trip for every photo in [photos],
     * in 50-chunks, reusing the exact batch helper the listing path uses. This collapses the
     * serialized one-element batch call × N files into a couple of full-width batch calls. The
     * prefetched link detail already carries the photo's ContentKeyPacket fields, so the
     * downloader's CKP chain resolves from it without a separate fetch.
     *
     * Best-effort and additive: a chunk that fails (network/rate-limit) is logged and skipped so
     * those linkIds simply stay unresolved and fall back to the per-file fetch — the whole
     * download is never aborted. Grouped by (volumeId, shareId) because the batch link endpoint
     * is keyed on volumeId; a mixed-source set (e.g. own + shared album) is handled per group.
     */
    private suspend fun prefetchDownloadMetadata(
        userId: UserId,
        photos: List<CloudPhoto>,
    ): PrefetchedDownloadMetadata {
        val linkDetail = mutableMapOf<String, BatchLinkDto>()
        // Distinct linkIds per (volumeId, shareId) — the batch link endpoint keys on volumeId,
        // so a group sharing it is one clean batch pass.
        val groups = photos
            .distinctBy { it.linkId }
            .groupBy { it.volumeId to it.shareId }
        for ((key, group) in groups) {
            val (volumeId, _) = key
            val linkIds = group.map { it.linkId }
            try {
                linkDetail.putAll(linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, linkIds))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "prefetchDownloadMetadata: link-detail prefetch skipped for a chunk: ${e.message}")
            }
        }
        Log.d(TAG, "prefetchDownloadMetadata: ${linkDetail.size} link details for ${photos.size} photo(s)")
        return PrefetchedDownloadMetadata(linkDetail)
    }

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
        onSaved: (String) -> Unit = {},
        onProgress: suspend (Progress) -> Unit = {},
    ): Progress = coroutineScope {
        val done = AtomicInteger(0); val failed = AtomicInteger(0); val skipped = AtomicInteger(0)
        val gate = Semaphore(DOWNLOAD_PARALLELISM)
        fun snapshot() = Progress(done.get() + skipped.get() + failed.get(), photos.size, failed.get(), skipped.get())
        // Batch-prefetch the redundant per-file link details for the whole set in 50-chunks
        // BEFORE the fan-out, so each photo's download skips its own one-element batch round-trip.
        // Any entry missing here falls back to the per-file fetch inside the downloader, so the
        // result is identical when prefetch yields nothing.
        val prefetch = prefetchDownloadMetadata(userId, photos)
        photos.map { photo ->
            async {
                gate.withPermit {
                    try {
                        // Reliable skip first: a photo already SYNCED to a still-present local file
                        // is on the device, so don't re-download it. This catches album photos whose
                        // cloud DTO carries size 0. Those can't match the MediaStore name+size
                        // heuristic below, yet they show the green "synced" badge, so skipping them
                        // here avoids a needless re-download.
                        val syncedUri = syncStateRepo.getByCloudId(photo.linkId)
                            ?.takeIf { it.status == SyncStatus.SYNCED }
                            ?.localUri?.takeIf { it.isNotBlank() && localUriExists(it) }
                        val existing = syncedUri?.let(Uri::parse)
                            ?: alreadyExistsInMediaStore(photo.displayName, photo.mimeType, photo.sizeBytes)
                        if (existing != null) {
                            linkSyncState(userId, existing, photo)
                            skipped.incrementAndGet()
                            Log.d(TAG, "Already on device, skipped: ${photo.displayName}")
                        } else {
                            val folder = folderByLinkId[photo.linkId] ?: folderName
                            val file = cloudRepo.downloadFullResPhoto(
                                userId, photo,
                                preResolvedLinkDetail = prefetch.linkDetailByLinkId[photo.linkId],
                            )
                            val savedUri = saveFileToMediaStore(
                                file, photo.displayName, photo.mimeType, folder,
                                captureTimeSeconds = photo.captureTime,
                            )
                            file.delete()
                            if (savedUri != null) {
                                linkSyncState(userId, savedUri, photo)
                                onSaved(savedUri.toString())
                            }
                            done.incrementAndGet()
                            Log.d(TAG, "Downloaded ${photo.displayName} → ${folder.ifEmpty { "<root>" }}")
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w(TAG, "Failed to download ${photo.displayName}: ${e.message}")
                        failed.incrementAndGet()
                    }
                    onProgress(snapshot())
                }
            }
        }.awaitAll()
        snapshot()
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
        onSaved: (String) -> Unit = {},
        onProgress: suspend (Progress) -> Unit = {},
    ): Progress = coroutineScope {
        val cloudItems = items.filterIsInstance<GalleryItem.CloudOnly>().map { it.cloud }
        val alreadyOnDevice = items.count { it !is GalleryItem.CloudOnly }
        // CloudOnly items have no local twin by definition (the gallery merge would have paired
        // them otherwise), so only the MediaStore name+size check is needed here — no SyncState
        // pre-check like the album path.
        val done = AtomicInteger(0); val failed = AtomicInteger(0); val skipped = AtomicInteger(alreadyOnDevice)
        val gate = Semaphore(DOWNLOAD_PARALLELISM)
        fun snapshot() = Progress(done.get() + skipped.get() + failed.get(), items.size, failed.get(), skipped.get())
        onProgress(snapshot())

        // Same batch-prefetch as the album path: resolve link details for the whole multi-select
        // up front so each photo skips its own one-element metadata batch.
        val prefetch = prefetchDownloadMetadata(userId, cloudItems)
        cloudItems.map { photo ->
            async {
                gate.withPermit {
                    try {
                        val existing = alreadyExistsInMediaStore(photo.displayName, photo.mimeType, photo.sizeBytes)
                        if (existing != null) {
                            linkSyncState(userId, existing, photo)
                            skipped.incrementAndGet()
                            Log.d(TAG, "Already on device, skipped: ${photo.displayName}")
                        } else {
                            // Per-photo override beats the default folder. Album-bound photos land in
                            // Pictures/<AlbumName>/; non-album photos in Pictures/ root (folder = "").
                            val folder = folderByLinkId[photo.linkId] ?: folderName
                            val file = cloudRepo.downloadFullResPhoto(
                                userId, photo,
                                preResolvedLinkDetail = prefetch.linkDetailByLinkId[photo.linkId],
                            )
                            val savedUri = saveFileToMediaStore(
                                file, photo.displayName, photo.mimeType, folder,
                                captureTimeSeconds = photo.captureTime,
                            )
                            file.delete()
                            if (savedUri != null) {
                                linkSyncState(userId, savedUri, photo)
                                onSaved(savedUri.toString())
                            }
                            done.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w(TAG, "Failed to download ${photo.displayName}: ${e.message}")
                        failed.incrementAndGet()
                    }
                    onProgress(snapshot())
                }
            }
        }.awaitAll()
        snapshot()
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
            if (e is kotlinx.coroutines.CancellationException) throw e
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

    /** True if [uriString] still resolves to a MediaStore row — i.e. the local file behind a
     *  SYNCED SyncState wasn't deleted out from under it. A stale record then falls through to
     *  a fresh download instead of being skipped. */
    private fun localUriExists(uriString: String): Boolean = try {
        context.contentResolver.query(
            Uri.parse(uriString), arrayOf(MediaStore.MediaColumns._ID), null, null, null,
        )?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        false
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

        // Stamp the original capture date into the file's EXIF BEFORE handing it to MediaStore.
        // Android's media scanner derives DATE_TAKEN from EXIF when the row is published; a file
        // with no EXIF date (a screenshot, or a photo uploaded with metadata stripping on) ends up
        // with DATE_TAKEN = scan time (~now) on some OS builds, and the column write below can't
        // reliably override that after publish. Fill ONLY when the tag is missing, so a real camera
        // date is never overwritten and unchanged bytes keep hashing to their cloud twin. Best-effort:
        // limited to formats ExifInterface can write; any failure leaves the column write as the
        // fallback. Changing the bytes is dedup-safe because the download pairs by cloud id
        // (linkSyncState -> reconcile byId), not by content hash.
        if (!isVideo && captureTimeSeconds > 0L && mimeType.lowercase() in EXIF_WRITABLE_MIMES) {
            runCatching {
                val exif = ExifInterface(file.absolutePath)
                if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrBlank()) {
                    val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                        .format(Date(captureTimeSeconds * 1000L))
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                    if (exif.getAttribute(ExifInterface.TAG_DATETIME).isNullOrBlank()) {
                        exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
                    }
                    exif.saveAttributes()
                }
            }.onFailure { Log.w(TAG, "EXIF date stamp skipped for $displayName: ${it.message}") }
        }

        // A photo that belongs to an album downloads into a folder named after that album (the
        // caller passes it as [folder], already sanitised) so the album's photos stay grouped on
        // the device — no matter whether the download starts from the album or from the timeline.
        // A photo in no album lands in the Pictures/ (or Movies/) root.
        val base = if (isVideo) "Movies" else "Pictures"
        val relPath = if (folder.isNotBlank()) "$base/$folder" else base

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

            // The EXIF block is left untouched on purpose: modifying it changes the file's
            // byte content and would break hash-based cloud/local matching (the cloud's
            // contentHash was computed over the original bytes). MediaStore's DATE_TAKEN
            // column carries the capture date instead; the split-update below makes that write
            // reliable on the Android 13+ versions that were dropping single-update timestamps.

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
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Clean up the pending MediaStore entry on failure
            context.contentResolver.delete(uri, null, null)
            Log.w(TAG, "saveFileToMediaStore failed for $displayName: ${e.message}")
            null
        }
    }
}
