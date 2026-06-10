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

package eu.akoos.photos.data.repository.drive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.api.dto.BatchLinksRequest
import eu.akoos.photos.data.api.dto.DeleteLinksRequest
import eu.akoos.photos.data.api.dto.FavoriteRequest
import eu.akoos.photos.data.api.dto.LinkCoreDto
import eu.akoos.photos.data.api.dto.TagRequest
import eu.akoos.photos.data.api.dto.ThumbnailBatchRequest
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.CloudTrashItem
import eu.akoos.photos.domain.entity.DriveNotFoundException
import eu.akoos.photos.domain.entity.LocalMediaItem
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CloudTrashSvc"

/** Per-link result of a restore-from-trash batch. [failedLinkIds] are the links the
 *  server rejected (per-item code != 1000) and that therefore stayed in trash.
 *  [galleryRefreshFailed] is true when the restore itself succeeded but the follow-up
 *  stream refresh couldn't run — the restored photos are back on the server but won't
 *  reappear in the gallery until a manual pull-to-refresh. */
data class CloudRestoreOutcome(
    val restoredLinkIds: Set<String>,
    val failedLinkIds: Set<String>,
    val galleryRefreshFailed: Boolean,
)

/** Per-link result of a permanent-delete batch. [failedLinkIds] are the links the
 *  server rejected (per-item code != 1000) and that therefore remain in trash. */
data class CloudDeleteOutcome(
    val deletedLinkIds: Set<String>,
    val failedLinkIds: Set<String>,
)

/**
 * Drive trash + favorite + rename operations.
 *
 * `renameOrCopyCloudPhoto` lives here because Drive has no server-side rename endpoint —
 * it's emulated as download-then-reupload + optional trash, which is conceptually still a
 * trash-or-keep operation on the original linkId. All API calls go through
 * [PhotosShareService.networkSemaphore] for shared permit accounting.
 */
@Singleton
class CloudTrashService @Inject constructor(
    private val apiProvider: ApiProvider,
    private val shareService: PhotosShareService,
    private val photoListingDao: PhotoListingDao,
    private val downloadService: PhotoDownloadService,
    private val uploadService: PhotoUploadService,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val photoStreamService: PhotoStreamService,
) {
    suspend fun deleteFiles(userId: UserId, linkIds: List<String>): Unit = withContext(Dispatchers.IO) {
        if (linkIds.isEmpty()) return@withContext
        try {
            val volumeId = shareService.getVolumeId(userId)
            val manager = apiProvider.get<DriveApiService>(userId)
            // POST /drive/v2/volumes/{volumeId}/trash_multiple
            // Moves cloud photos to the server-side trash (recoverable from Recently Deleted).
            // Using trash instead of permanent delete for safety.
            shareService.networkSemaphore.withPermit {
                manager.invoke {
                    trashPhotos(volumeId, DeleteLinksRequest(linkIds))
                }.valueOrThrow
            }
            // Drop the trashed rows from our local photo_listing immediately. Without this
            // the gallery kept showing the deleted photo as still-on-cloud until the next
            // full refreshCloudPhotos pass picked up the trash event (which can be minutes
            // away if the user is on the rate-limited fallback path). The Flow on
            // observePhotosByLinkIds re-emits when rows disappear, so the cell drops out
            // immediately.
            runCatching { photoListingDao.deleteByLinkIds(linkIds) }
            Log.d(TAG, "deleteFiles: trashed ${linkIds.size} photos + cleared local rows")
        } catch (e: DriveNotFoundException) {
            Log.w(TAG, "deleteFiles: DriveNotFoundException: ${e.message}")
        }
    }

    suspend fun setCloudFavorite(userId: UserId, photo: CloudPhoto, favorite: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val volumeId = photo.volumeId.ifEmpty { shareService.getVolumeId(userId) }
            val manager = apiProvider.get<DriveApiService>(userId)
            try {
                if (favorite) {
                    shareService.networkSemaphore.withPermit {
                        manager.invoke {
                            addFavorite(volumeId, photo.linkId, FavoriteRequest(photoData = null))
                        }.valueOrThrow
                    }
                } else {
                    shareService.networkSemaphore.withPermit {
                        manager.invoke {
                            deletePhotoTags(volumeId, photo.linkId, TagRequest(tags = listOf(0L)))
                        }.valueOrThrow
                    }
                }
                // Reflect the change in the local DB so the gallery updates immediately,
                // without waiting for the next incremental sync.
                photoListingDao.getByLinkId(photo.linkId)?.let { existing ->
                    val current = if (existing.tagsCsv.isEmpty()) emptySet()
                                  else existing.tagsCsv.split(',').mapNotNull { it.toIntOrNull() }.toSet()
                    val updated = if (favorite) current + 0 else current - 0
                    photoListingDao.upsertAll(listOf(existing.copy(tagsCsv = updated.joinToString(","))))
                }
                Log.d(TAG, "setCloudFavorite: linkId=${photo.linkId} favorite=$favorite OK")
                true
            } catch (e: Exception) {
                Log.w(TAG, "setCloudFavorite failed for linkId=${photo.linkId}: ${e.message}")
                false
            }
        }

    suspend fun getCloudTrash(userId: UserId): List<CloudTrashItem> = withContext(Dispatchers.IO) {
        try {
            val volumeId = shareService.getVolumeId(userId)
            val manager = apiProvider.get<DriveApiService>(userId)

            // GET drive/volumes/{volumeId}/trash?Page=N — paginated grouping response.
            // The server returns trashed items grouped by ShareID with only LinkIDs +
            // ParentIDs — no mime, no thumbnails, no crypto keys. We hydrate per-link
            // details below. Pagination is 0-indexed (matches Drive web's request
            // pattern); the first empty Trash list signals the end.
            data class TrashGroup(val shareId: String, val linkIds: List<String>)
            val groups = mutableListOf<TrashGroup>()
            var page = 0
            while (true) {
                val resp = shareService.networkSemaphore.withPermit {
                    manager.invoke { getVolumeTrash(volumeId, page) }.valueOrThrow
                }
                if (resp.trash.isEmpty()) break
                resp.trash.forEach { g -> groups += TrashGroup(g.shareId, g.linkIds) }
                page++
            }

            val totalLinkIds = groups.sumOf { it.linkIds.size }
            if (totalLinkIds == 0) return@withContext emptyList()
            Log.d(TAG, "getCloudTrash: $totalLinkIds link IDs across ${groups.size} trash group(s); hydrating details")

            // Hydrate the link metadata via the share-based fetch endpoint
            // (drive/shares/{shareId}/links/fetch_metadata). The volume-based batch
            // endpoint is photos-share-only — trashed items may live in any share on
            // the volume, so we walk per group and use the matching shareId for each.
            val linksById = mutableMapOf<String, LinkCoreDto>()
            for (group in groups) {
                for (chunk in group.linkIds.chunked(150)) {
                    val resp = runCatching {
                        shareService.networkSemaphore.withPermit {
                            manager.invoke {
                                fetchLinkMetadata(group.shareId, BatchLinksRequest(chunk))
                            }.valueOrThrow
                        }
                    }
                    resp.fold(
                        onSuccess = { r -> r.links.forEach { linksById[it.linkId] = it } },
                        onFailure = { e -> Log.w(TAG, "getCloudTrash: fetch_metadata chunk failed for share ${group.shareId}: ${e.message}") },
                    )
                }
            }
            Log.d(TAG, "getCloudTrash: hydrated ${linksById.size} / $totalLinkIds link details")

            // Filter to photo / video media. Mime lives on link.mimeType; entries without
            // a mime (folders, share roots that surface in trash unexpectedly) are dropped.
            val photoLinks = linksById.values.filter { link ->
                val mime = link.mimeType ?: return@filter false
                mime.startsWith("image/") || mime.startsWith("video/")
            }

            if (photoLinks.isEmpty()) return@withContext emptyList()
            Log.d(TAG, "getCloudTrash: ${photoLinks.size} photo/video items after mime filter")

            // Collect thumbnail IDs from FileProperties.ActiveRevision (primary) or top-level
            // ActiveRevision (fallback for older link shapes).
            val thumbnailIds = photoLinks.mapNotNull { link ->
                link.fileProperties?.activeRevision?.thumbnails?.firstOrNull()?.thumbnailId
                    ?: link.activeRevision?.thumbnails?.firstOrNull()?.thumbnailId
            }

            // Batch-fetch thumbnail download URLs (same mechanism as gallery).
            val thumbMap = mutableMapOf<String, Pair<String, String?>>() // thumbnailId → (bareUrl, token)
            if (thumbnailIds.isNotEmpty()) {
                runCatching {
                    val resp = shareService.networkSemaphore.withPermit {
                        manager.invoke {
                            getThumbnailUrls(volumeId, ThumbnailBatchRequest(thumbnailIds))
                        }.valueOrThrow
                    }
                    resp.thumbnails.forEach { t ->
                        if (t.bareUrl != null) thumbMap[t.thumbnailId] = t.bareUrl to t.token
                    }
                }.onFailure { e -> Log.w(TAG, "getCloudTrash: thumbnail batch failed — ${e.message}") }
            }

            photoLinks.map { link ->
                val thumbId = link.fileProperties?.activeRevision?.thumbnails?.firstOrNull()?.thumbnailId
                    ?: link.activeRevision?.thumbnails?.firstOrNull()?.thumbnailId
                val (thumbUrl, thumbToken) = thumbId?.let { thumbMap[it] } ?: (null to null)
                val captureTime = link.modifyTime ?: link.createTime
                // CKP can live in fileProperties or activeRevision depending on link era.
                val ckp = link.fileProperties?.contentKeyPacket
                    ?: link.activeRevision?.contentKeyPacket
                CloudTrashItem(
                    linkId            = link.linkId,
                    captureTime       = captureTime,
                    thumbnailUrl      = thumbUrl,
                    thumbnailToken    = thumbToken,
                    encNodeKey        = link.nodeKey,
                    encNodePassphrase = link.nodePassphrase,
                    contentKeyPacket  = ckp,
                    parentLinkId      = link.parentLinkId,
                    volumeId          = volumeId,
                )
            }
        } catch (e: Exception) {
            // Propagate so the UI can show a retry surface instead of silently rendering an
            // empty trash list. A successful call with a genuinely empty server-side trash
            // already returns the empty list naturally, so callers can distinguish the two.
            Log.e(TAG, "getCloudTrash: failed — ${e.message}", e)
            throw e
        }
    }

    suspend fun restoreFromCloudTrash(userId: UserId, linkIds: List<String>): CloudRestoreOutcome =
        withContext(Dispatchers.IO) {
            if (linkIds.isEmpty()) return@withContext CloudRestoreOutcome(emptySet(), emptySet(), false)
            try {
                val volumeId = shareService.getVolumeId(userId)
                val manager = apiProvider.get<DriveApiService>(userId)
                val failed = mutableSetOf<String>()
                linkIds.chunked(150).forEach { chunk ->
                    val resp = shareService.networkSemaphore.withPermit {
                        manager.invoke {
                            restoreFromTrash(volumeId, DeleteLinksRequest(chunk))
                        }.valueOrThrow
                    }
                    // The multi-endpoint returns one Response per link; collect the link IDs
                    // the server rejected (code != 1000) so they can stay selected for retry.
                    // An empty Responses list (older server shape) means the top-level Code
                    // already vouched for the whole chunk.
                    resp.responses.forEach { entry ->
                        if (entry.response.code != 1000) failed += entry.linkId
                    }
                }
                val restored = linkIds.toSet() - failed
                Log.d(TAG, "restoreFromCloudTrash: restored ${restored.size}/${linkIds.size} items (${failed.size} failed)")

                // Drive moves restored items out of trash on the server, but our local
                // photo_listing was emptied of those rows when they were trashed. Without a
                // follow-up refresh the gallery Flow has no way to know the items are back —
                // even pull-to-refresh in the gallery races with the post-restore server
                // index and frequently misses them on the first attempt. Trigger a full
                // refresh here so the restored linkIds flow back into the listing DB and the
                // gallery Flow observers re-emit with them. Retry once on failure; if it still
                // fails, report it so the caller can prompt a manual pull-to-refresh instead
                // of leaving the gallery silently stale.
                val refreshFailed = restored.isNotEmpty() && !refreshCloudPhotosWithRetry(userId)
                CloudRestoreOutcome(restored, failed, refreshFailed)
            } catch (e: Exception) {
                Log.w(TAG, "restoreFromCloudTrash: failed — ${e.message}")
                throw e
            }
        }

    /** Refreshes the cloud photo stream, retrying once. Returns true on success. */
    private suspend fun refreshCloudPhotosWithRetry(userId: UserId): Boolean {
        repeat(2) { attempt ->
            val ok = runCatching { photoStreamService.refreshCloudPhotos(userId) }
                .onFailure { e -> Log.w(TAG, "restoreFromCloudTrash: stream refresh attempt ${attempt + 1} failed — ${e.message}") }
                .isSuccess
            if (ok) return true
        }
        return false
    }

    suspend fun deleteFromCloudForever(userId: UserId, linkIds: List<String>): CloudDeleteOutcome =
        withContext(Dispatchers.IO) {
            if (linkIds.isEmpty()) return@withContext CloudDeleteOutcome(emptySet(), emptySet())
            try {
                val volumeId = shareService.getVolumeId(userId)
                val manager = apiProvider.get<DriveApiService>(userId)
                val failed = mutableSetOf<String>()
                linkIds.chunked(150).forEach { chunk ->
                    val resp = shareService.networkSemaphore.withPermit {
                        manager.invoke {
                            deleteForever(volumeId, DeleteLinksRequest(chunk))
                        }.valueOrThrow
                    }
                    // Same per-link parsing as restore: links the server rejected stay in
                    // trash and must remain selected so the user can retry the delete.
                    resp.responses.forEach { entry ->
                        if (entry.response.code != 1000) failed += entry.linkId
                    }
                }
                val deleted = linkIds.toSet() - failed
                Log.d(TAG, "deleteFromCloudForever: permanently deleted ${deleted.size}/${linkIds.size} items (${failed.size} failed)")
                CloudDeleteOutcome(deleted, failed)
            } catch (e: Exception) {
                Log.w(TAG, "deleteFromCloudForever: failed — ${e.message}")
                throw e
            }
        }

    /**
     * Drive Photos has no server-side rename endpoint; emulate it by re-uploading the
     * same bytes under the new name and (optionally) trashing the original.
     */
    suspend fun renameOrCopyCloudPhoto(
        userId: UserId,
        photo: CloudPhoto,
        newName: String,
        trashOriginal: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val fullResFile = downloadService.downloadFullResPhoto(userId, photo)
        if (!fullResFile.exists() || fullResFile.length() == 0L) error("Full-res download failed for ${photo.linkId}")

        // SHA-1 hex of the plaintext file. Drive Android pins
        // ConfigurationProvider.contentDigestAlgorithm to SHA-1 and Drive web's
        // photosTransferPayloadBuilder rejects ContentHash payloads derived from
        // any other algorithm with "Cannot build photo payload without a content
        // hash". The hex string is what PhotoUploadService passes into both
        // `Common.Digests.SHA1` of the xAttr blob and the HMAC that produces the
        // wire ContentHash.
        val hash = run {
            val digest = MessageDigest.getInstance("SHA-1")
            fullResFile.inputStream().use { stream ->
                val buf = ByteArray(8192)
                var read: Int
                while (stream.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        val fileUri = android.net.Uri.fromFile(fullResFile).toString()
        // Preserve the original capture time on rename so the photo stays in its timeline slot.
        val item = LocalMediaItem(
            uri         = fileUri,
            dateTaken   = photo.captureTime * 1000L,
            displayName = newName,
            mimeType    = photo.mimeType,
            sizeBytes   = fullResFile.length(),
            bucketName  = null,
            width       = 0,
            height      = 0,
            duration    = 0L,
        )
        val newLinkId = uploadService.uploadFile(userId, item, hash, fileUri)
        if (trashOriginal) {
            runCatching { deleteFiles(userId, listOf(photo.linkId)) }
                .onFailure { e -> Log.w(TAG, "rename: trashing original ${photo.linkId} failed: ${e.message}") }
        }
        newLinkId
    }
}
