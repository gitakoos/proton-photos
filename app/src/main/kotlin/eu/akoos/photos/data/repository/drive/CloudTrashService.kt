package eu.akoos.photos.data.repository.drive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import eu.akoos.photos.data.api.DriveApiService
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

            // GET drive/volumes/{volumeId}/trash?Page=N — full Drive trash, page-based (1-indexed).
            // Stop when the server returns an empty Links list.
            // Filter client-side: only image/* and video/* links belong in the photo trash UI.
            val allLinks = mutableListOf<LinkCoreDto>()
            var page = 1
            while (true) {
                val resp = shareService.networkSemaphore.withPermit {
                    manager.invoke { getVolumeTrash(volumeId, page) }.valueOrThrow
                }
                if (resp.links.isEmpty()) break
                allLinks.addAll(resp.links)
                page++
            }

            val photoLinks = allLinks.filter { link ->
                val mime = link.mimeType ?: return@filter false
                mime.startsWith("image/") || mime.startsWith("video/")
            }

            if (photoLinks.isEmpty()) return@withContext emptyList()
            Log.d(TAG, "getCloudTrash: ${photoLinks.size} photo/video items in trash (${allLinks.size} total)")

            // Collect thumbnail IDs from ActiveRevision so we can batch-fetch CDN URLs.
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
                // Use modifyTime as a proxy for captureTime (volume trash doesn't return photo metadata).
                val captureTime = link.modifyTime ?: link.createTime
                CloudTrashItem(
                    linkId        = link.linkId,
                    captureTime   = captureTime,
                    thumbnailUrl  = thumbUrl,
                    thumbnailToken = thumbToken,
                )
            }
        } catch (e: Exception) {
            // TODO(api-surface): currently returns empty list on EVERY error path so callers
            // cannot distinguish "truly empty trash" from "network/auth/parse failure". When a
            // cloud-trash UI surface lands, switch this to throw or return Result so the screen
            // can show a retry button. For now elevate to ERROR-level log instead of WARN so
            // the failure is at least obvious in production logs.
            Log.e(TAG, "getCloudTrash: failed — ${e.message}", e)
            emptyList()
        }
    }

    suspend fun restoreFromCloudTrash(userId: UserId, linkIds: List<String>): Unit = withContext(Dispatchers.IO) {
        if (linkIds.isEmpty()) return@withContext
        try {
            val volumeId = shareService.getVolumeId(userId)
            val manager = apiProvider.get<DriveApiService>(userId)
            linkIds.chunked(150).forEach { chunk ->
                shareService.networkSemaphore.withPermit {
                    manager.invoke {
                        restoreFromTrash(volumeId, DeleteLinksRequest(chunk))
                    }.valueOrThrow
                }
            }
            Log.d(TAG, "restoreFromCloudTrash: restored ${linkIds.size} items")
        } catch (e: Exception) {
            Log.w(TAG, "restoreFromCloudTrash: failed — ${e.message}")
            throw e
        }
    }

    suspend fun deleteFromCloudForever(userId: UserId, linkIds: List<String>): Unit = withContext(Dispatchers.IO) {
        if (linkIds.isEmpty()) return@withContext
        try {
            val volumeId = shareService.getVolumeId(userId)
            val manager = apiProvider.get<DriveApiService>(userId)
            linkIds.chunked(150).forEach { chunk ->
                shareService.networkSemaphore.withPermit {
                    manager.invoke {
                        deleteForever(volumeId, DeleteLinksRequest(chunk))
                    }.valueOrThrow
                }
            }
            Log.d(TAG, "deleteFromCloudForever: permanently deleted ${linkIds.size} items")
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

        val hash = run {
            val digest = MessageDigest.getInstance("SHA-256")
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
