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
import eu.akoos.photos.util.retryWithBackoff
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CloudTrashSvc"

/** The trash/delete bulk endpoints reject an oversized batch, so link ids are split into
 *  chunks of this many before each request. */
private const val TRASH_BATCH = 150

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

/** Per-link result of a move-to-trash batch. [trashedLinkIds] are the links the server
 *  accepted (per-item code 1000); [failedLinkIds] are the ones it rejected and that are
 *  therefore still on the cloud. A caller can use the failed count to avoid reporting a
 *  delete as fully successful when some links stayed put. */
data class CloudTrashOutcome(
    val trashedLinkIds: Set<String>,
    val failedLinkIds: Set<String>,
)

/**
 * The linkIds in a `*_multiple` trash response the server REJECTED (per-link code != 1000).
 * The top-level Code only means the batch was processed, so each entry's own code is the truth
 * for whether that link actually moved. Shared by the trash + permanent-delete paths so the
 * per-link accounting lives in one place. Entries the response omits aren't reported failed —
 * a server that returns no per-link array (only a top-level Code) then degrades to "all ok".
 */
internal fun rejectedLinkIds(
    responses: List<eu.akoos.photos.data.api.dto.TrashActionOutcomeEntry>,
): Set<String> = responses.filter { it.response.code != 1000 }.map { it.linkId }.toSet()

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
    suspend fun deleteFiles(userId: UserId, linkIds: List<String>): CloudTrashOutcome = withContext(Dispatchers.IO) {
        if (linkIds.isEmpty()) return@withContext CloudTrashOutcome(emptySet(), emptySet())
        try {
            val volumeId = shareService.getVolumeId(userId)
            val manager = apiProvider.get<DriveApiService>(userId)
            // POST /drive/v2/volumes/{volumeId}/trash_multiple
            // Moves cloud photos to the server-side trash (recoverable from Recently Deleted).
            // Using trash instead of permanent delete for safety.
            // Chunked: trash_multiple rejects an oversized batch outright (a large multi-select
            // delete failed with "Could not delete from Drive"), so split it the same way the
            // permanent-delete path below does.
            val failed = mutableSetOf<String>()
            linkIds.chunked(TRASH_BATCH).forEach { chunk ->
                // Wrap each chunk so a 429 / 5xx backs off and retries (honouring any Retry-After)
                // instead of failing the whole multi-select delete.
                val resp = retryWithBackoff {
                    shareService.networkSemaphore.withPermit {
                        manager.invoke {
                            trashPhotos(volumeId, DeleteLinksRequest(chunk))
                        }.valueOrThrow
                    }
                }
                // Same per-link parsing as deleteFromCloudForever: links the server rejected
                // stayed on the cloud, so they must NOT be dropped locally or reported trashed.
                failed += rejectedLinkIds(resp.responses)
            }
            val trashed = linkIds.toSet() - failed
            // Drop ONLY the rows the server confirmed trashed from our local photo_listing
            // immediately. Without this the gallery kept showing the deleted photo as
            // still-on-cloud until the next full refreshCloudPhotos pass picked up the trash
            // event (which can be minutes away if the user is on the rate-limited fallback
            // path). The Flow on observePhotosByLinkIds re-emits when rows disappear, so the
            // cell drops out immediately. A rejected link stays so it doesn't vanish from the
            // grid while still living on the cloud.
            if (trashed.isNotEmpty()) {
                runCatching { photoListingDao.deleteByLinkIds(trashed.toList()) }
                // Keep these out of the next refresh's upsert until the server's trash propagates, so
                // an in-flight or about-to-run stream listing (which can still return a just-trashed
                // photo for ~a minute) can't re-add the rows we just removed and flash the green-cloud
                // badge back on in the timeline / device folders.
                photoStreamService.markRecentlyTrashed(trashed.toList())
            }
            Log.d(TAG, "deleteFiles: trashed ${trashed.size}/${linkIds.size} photos + cleared local rows (${failed.size} failed)")
            CloudTrashOutcome(trashed, failed)
        } catch (e: DriveNotFoundException) {
            // The links are already gone server-side — treat as fully trashed so callers don't
            // surface a phantom failure for something that no longer exists.
            Log.w(TAG, "deleteFiles: DriveNotFoundException: ${e.message}")
            runCatching { photoListingDao.deleteByLinkIds(linkIds) }
            photoStreamService.markRecentlyTrashed(linkIds)
            CloudTrashOutcome(linkIds.toSet(), emptySet())
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "setCloudFavorite failed for linkId=${photo.linkId}: ${e.message}")
                false
            }
        }

    /**
     * Adds or removes a single Drive PhotoTag category id on a cloud photo. Metadata-only: it
     * POSTs / DELETEs the integer tag id to the photo's /tags endpoint, exactly like
     * [setCloudFavorite] does for tag 0, and never uploads content, creates a revision, or
     * re-encrypts anything — the photo's blocks/name/revision are untouched. Mirrors the change
     * into the local DB tagsCsv so the gallery + category filter update immediately. Tag 0
     * (Favorites) is routed through [setCloudFavorite] because the server rejects adding 0 via
     * /tags. Returns true on success; logs and returns false on any API/network error.
     */
    suspend fun setCloudTag(userId: UserId, photo: CloudPhoto, tagId: Int, add: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (tagId == 0) return@withContext setCloudFavorite(userId, photo, add)
            val volumeId = photo.volumeId.ifEmpty { shareService.getVolumeId(userId) }
            val manager = apiProvider.get<DriveApiService>(userId)
            try {
                val tag = listOf(tagId.toLong())
                if (add) {
                    shareService.networkSemaphore.withPermit {
                        manager.invoke {
                            addPhotoTags(volumeId, photo.linkId, TagRequest(tags = tag))
                        }.valueOrThrow
                    }
                } else {
                    shareService.networkSemaphore.withPermit {
                        manager.invoke {
                            deletePhotoTags(volumeId, photo.linkId, TagRequest(tags = tag))
                        }.valueOrThrow
                    }
                }
                // Reflect the change in the local DB so the gallery + category filter update
                // immediately, without waiting for the next incremental sync.
                photoListingDao.getByLinkId(photo.linkId)?.let { existing ->
                    val current = if (existing.tagsCsv.isEmpty()) emptySet()
                                  else existing.tagsCsv.split(',').mapNotNull { it.toIntOrNull() }.toSet()
                    val updated = if (add) current + tagId else current - tagId
                    photoListingDao.upsertAll(listOf(existing.copy(tagsCsv = updated.sorted().joinToString(","))))
                }
                Log.d(TAG, "setCloudTag: linkId=${photo.linkId} tag=$tagId add=$add OK")
                true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "setCloudTag failed for linkId=${photo.linkId} tag=$tagId: ${e.message}")
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
            if (e is kotlinx.coroutines.CancellationException) throw e
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
                linkIds.chunked(TRASH_BATCH).forEach { chunk ->
                    // Wrap each chunk so a 429 / 5xx backs off and retries (honouring any
                    // Retry-After) instead of throwing the whole restore out on a transient blip.
                    val resp = retryWithBackoff {
                        shareService.networkSemaphore.withPermit {
                            manager.invoke {
                                restoreFromTrash(volumeId, DeleteLinksRequest(chunk))
                            }.valueOrThrow
                        }
                    }
                    // restore_multiple's Codes (top-level AND per-link) are unreliable for Photos:
                    // links the server actually restores come back non-1000 with spurious Errors,
                    // so gating on them wrongly reported every restore as failed. The per-link
                    // Responses array IS present in the DTO, but this server quirk makes its codes
                    // untrustworthy for restore — so the conservative all-restored behaviour stays
                    // and `failed` is intentionally left empty (verified: delete_multiple's codes,
                    // by contrast, are reliable and ARE parsed in deleteFromCloudForever).
                    // valueOrThrow has already raised any real HTTP/transport failure, so a
                    // returning call means the batch was accepted — treat every link in it as
                    // restored. This also keeps `restored` non-empty so the gallery refresh below
                    // runs and the photos reappear without needing a re-login. Logged for diagnosis.
                    Log.d(TAG, "restoreFromCloudTrash: chunk accepted code=${resp.code} " +
                        "responses=${resp.responses.map { it.response.code to it.response.error }}")
                }
                val restored = linkIds.toSet() - failed
                Log.d(TAG, "restoreFromCloudTrash: restored ${restored.size}/${linkIds.size} items (${failed.size} failed)")
                // Lift the just-trashed guard for the restored ids, or the refresh below would
                // keep skipping them (they'd stay invisible until the trash window expires).
                photoStreamService.forgetRecentlyTrashed(restored)

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
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "restoreFromCloudTrash: failed — ${e.message}")
                throw e
            }
        }

    /** Refreshes the cloud photo stream with jittered backoff between attempts. Returns true on
     *  success. A transient (429 / 5xx / network) failure retries; a non-transient one stops early.
     *  Wrapped in runCatching so an exhausted retry degrades to false (caller prompts a manual
     *  pull-to-refresh) rather than propagating. */
    private suspend fun refreshCloudPhotosWithRetry(userId: UserId): Boolean =
        runCatching {
            retryWithBackoff(maxAttempts = 3) {
                photoStreamService.refreshCloudPhotos(userId)
            }
        }.onFailure { e ->
            Log.w(TAG, "restoreFromCloudTrash: stream refresh failed — ${e.message}")
        }.isSuccess

    suspend fun deleteFromCloudForever(userId: UserId, linkIds: List<String>): CloudDeleteOutcome =
        withContext(Dispatchers.IO) {
            if (linkIds.isEmpty()) return@withContext CloudDeleteOutcome(emptySet(), emptySet())
            try {
                val volumeId = shareService.getVolumeId(userId)
                val manager = apiProvider.get<DriveApiService>(userId)
                val failed = mutableSetOf<String>()
                linkIds.chunked(TRASH_BATCH).forEach { chunk ->
                    // Wrap each chunk so a 429 / 5xx backs off and retries (honouring any
                    // Retry-After) instead of failing the whole permanent-delete batch.
                    val resp = retryWithBackoff {
                        shareService.networkSemaphore.withPermit {
                            manager.invoke {
                                deleteForever(volumeId, DeleteLinksRequest(chunk))
                            }.valueOrThrow
                        }
                    }
                    // delete_multiple's per-link codes are reliable (unlike restore): links the
                    // server rejected stay in trash and must remain selected so the user can retry.
                    failed += rejectedLinkIds(resp.responses)
                }
                val deleted = linkIds.toSet() - failed
                Log.d(TAG, "deleteFromCloudForever: permanently deleted ${deleted.size}/${linkIds.size} items (${failed.size} failed)")
                CloudDeleteOutcome(deleted, failed)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
