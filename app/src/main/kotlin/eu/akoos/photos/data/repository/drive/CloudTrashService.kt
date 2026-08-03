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
import eu.akoos.photos.data.api.dto.RenameLinkRequest
import eu.akoos.photos.data.api.dto.TagRequest
import eu.akoos.photos.data.api.dto.ThumbnailBatchRequest
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.CloudTrashItem
import eu.akoos.photos.domain.entity.DriveNotFoundException
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.util.forEachSqlChunk
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

/** Which key set a photo rename may use, decided from the photo's wire parent. */
internal enum class RenameParent {
    /** Wire parent is the photos root, so the root link key and the root NodeHashKey both apply. */
    PHOTOS_ROOT,

    /** Wire parent is another link, or unknown. Renaming with root keys here would write a name
     *  nothing can decrypt, so the rename must not run at all. */
    UNSUPPORTED,
}

/**
 * Answers only one question: does the photos-root key set speak for this photo's name?
 *
 * The parent whose key wrapped a photo's NodePassphrase is not always the parent the server files
 * the link under. Adding a photo to an album rewraps that passphrase to the album key while the
 * wire ParentLinkID stays on the photos root, and [AlbumCryptoChain.selectPhotoParentKey] carries
 * that rule for the passphrase side. Name encryption and the name Hash follow the WIRE parent
 * instead, so anything other than an exact photos-root match answers [RenameParent.UNSUPPORTED]
 * rather than guessing. An empty id counts as unknown: a blank parent matching a blank root would
 * otherwise read as agreement.
 */
internal fun renameParentFor(photoParentLinkId: String?, photosRootLinkId: String?): RenameParent =
    if (!photoParentLinkId.isNullOrEmpty() && photoParentLinkId == photosRootLinkId) {
        RenameParent.PHOTOS_ROOT
    } else {
        RenameParent.UNSUPPORTED
    }

/**
 * Drive trash + favorite + rename operations.
 *
 * [renameCloudPhoto] renames a link in place through the server's own share-scoped rename route.
 * [copyCloudPhotoAs] builds a second photo from the same bytes, and lives here because it runs on
 * the same volume-scoped calls as the trash code. All API calls go through
 * [PhotosShareService.networkSemaphore] for shared permit accounting.
 */
@Singleton
class CloudTrashService @Inject constructor(
    private val apiProvider: ApiProvider,
    private val shareService: PhotosShareService,
    private val photoListingDao: PhotoListingDao,
    private val syncStateDao: eu.akoos.photos.data.db.dao.SyncStateDao,
    private val downloadService: PhotoDownloadService,
    private val uploadService: PhotoUploadService,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val photoStreamService: PhotoStreamService,
    private val cryptoHelper: eu.akoos.photos.data.crypto.DriveCryptoHelper,
    private val cryptoContext: me.proton.core.crypto.common.context.CryptoContext,
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
                // Chunked: a multi-select delete is user-sized, and one oversized IN list would fail the
                // whole statement, leaving every row behind. Failures are logged rather than swallowed,
                // since a silent one shows up later as a deleted photo still sitting in the grid.
                runCatching { trashed.forEachSqlChunk { photoListingDao.deleteByLinkIds(userId.id, it) } }
                    .onFailure { Log.w(TAG, "deleteFiles: local listing delete failed: ${it.message}") }
                // A cloud copy we just trashed is gone, so drop its stale SYNCED marker right now (the
                // on-device twin becomes LOCAL_ONLY) instead of waiting out the reconcile grace window.
                // Re-adding that photo to an album then uploads it fresh instead of doing nothing.
                runCatching { trashed.forEachSqlChunk { syncStateDao.demoteSyncedByCloudIds(it) } }
                    .onFailure { Log.w(TAG, "deleteFiles: sync-state demote failed: ${it.message}") }
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
            runCatching { linkIds.forEachSqlChunk { photoListingDao.deleteByLinkIds(userId.id, it) } }
                .onFailure { Log.w(TAG, "deleteFiles: local listing delete failed: ${it.message}") }
            runCatching { linkIds.forEachSqlChunk { syncStateDao.demoteSyncedByCloudIds(it) } }
                .onFailure { Log.w(TAG, "deleteFiles: sync-state demote failed: ${it.message}") }
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
                    photoListingDao.upsertAll(listOf(existing.copy(tagsCsv = updated.sorted().joinToString(","))))
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
     * Renames a cloud photo in place: one metadata call, the same linkId, no second photo. Album
     * membership and every other reference to the link survive untouched, because nothing about the
     * link changes except its name.
     *
     * The Name ciphertext and the name Hash both belong to the parent the SERVER files the link
     * under, which is not always the parent whose key wrapped the NodePassphrase: a photo added to
     * an album carries an album-wrapped passphrase while its wire ParentLinkID still points at the
     * photos root. [renameParentFor] holds that line and refuses any parent the root key set cannot
     * speak for, since encrypting to the wrong key writes a name nothing can read back.
     *
     * Throws on any crypto or API failure. A rename that quietly did nothing is indistinguishable
     * from one that worked, so the caller has to be able to tell the two apart.
     */
    suspend fun renameCloudPhoto(userId: UserId, photo: CloudPhoto, newName: String): Unit =
        withContext(Dispatchers.IO) {
            val trimmed = newName.trim()
            require(trimmed.isNotEmpty()) { "Name cannot be empty" }

            val volumeId = photo.volumeId.ifEmpty { shareService.getVolumeId(userId) }
            val manager = apiProvider.get<DriveApiService>(userId)

            // Resolve the root link key first: it self-heals a session that cached the root link
            // with a null NodeHashKey because the batch endpoint omitted the Folder DTO, which is
            // what makes the hash key below available on the first attempt.
            val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId)
                ?: renameUnavailable("root link key unavailable")
            val rootLinkArmored = shareService.rootLinkArmoredKey()
                ?: renameUnavailable("root link armored key unavailable")
            val rootNodeHashKey = shareService.rootNodeHashKeyBytes()
                ?: renameUnavailable("root NodeHashKey unavailable")
            val shareId = photo.shareId.ifEmpty { shareService.shareId().orEmpty() }
            if (shareId.isEmpty()) renameUnavailable("photos shareId unavailable")

            val detail = linkDetailHelpers
                .batchFetchLinkDetails(userId, volumeId, listOf(photo.linkId))[photo.linkId]
                ?: renameUnavailable("photo not found on Drive")
            val parent = renameParentFor(detail.link.parentLinkId, shareService.photosRootLinkId())
            if (parent != RenameParent.PHOTOS_ROOT) renameUnavailable("photo is not parented to the photos root")

            // OriginalHash is recomputed from the photo's own decrypted name rather than echoed
            // back from the server's Hash, so it lands in the same hash-space as newHash below. A
            // stored Hash computed under a different key answers a different question, and the
            // server rejects the pair as out of date.
            val currentEncryptedName = detail.link.name ?: renameUnavailable("photo has no encrypted name")
            val currentPlainName = cryptoHelper.decryptLinkName(currentEncryptedName, rootLinkKeyBytes)
                ?: renameUnavailable("current name could not be read")
            val originalHash = cryptoHelper.computeNameHash(currentPlainName, rootNodeHashKey)

            val rootPublicKey = cryptoHelper.withCryptoLock {
                cryptoContext.pgpCrypto.getPublicKey(rootLinkArmored)
            }
            val signingKey = cryptoHelper.getAddressSigningKey(userId)
            // The new name rides the CURRENT name's session key, recovered with the same root key
            // that just read the old one. A link Name's session key is what any share of that link
            // hands its recipients, so it has to outlive every rename (#88).
            val newEncryptedName = cryptoHelper.renameNamePreservingSessionKey(
                oldNameArmored = currentEncryptedName,
                oldDecryptKeyBytes = rootLinkKeyBytes,
                newPlaintextName = trimmed,
                parentPublicKeyArmored = rootPublicKey,
                signerKeyBytes = signingKey.unlockedKeyBytes,
            )
            val newHash = cryptoHelper.computeNameHash(trimmed, rootNodeHashKey)

            suspend fun send(withOriginalHash: String) {
                shareService.networkSemaphore.withPermit {
                    manager.invoke {
                        renameLink(
                            shareId,
                            photo.linkId,
                            RenameLinkRequest(
                                name = newEncryptedName,
                                hash = newHash,
                                originalHash = withOriginalHash,
                                mimeType = photo.mimeType,
                                signatureAddress = signingKey.email,
                            ),
                        )
                    }.valueOrThrow
                }
            }

            // The recomputed OriginalHash is the one the server accepts, since it shares a
            // hash-space with newHash. The two values only diverge when the stored hash was written
            // under a different key, so when they agree the question does not arise at all. The
            // retry behind it covers that divergence, which `AlbumService.renameAlbum` documents for
            // albums written by older versions, without making a photo pay a request for it in the
            // ordinary case. The log names which value answered.
            val serverHash = detail.link.hash
            if (serverHash == null || serverHash == originalHash) {
                send(originalHash)
            } else {
                try {
                    send(originalHash)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "renameCloudPhoto: recomputed OriginalHash rejected (${e.message}), retrying with the server's")
                    send(serverHash)
                    Log.d(TAG, "renameCloudPhoto: the server's own Hash was the accepted OriginalHash")
                }
            }

            // The link keeps its id, so the listing row is updated in place instead of being
            // dropped. The fingerprint has to move with the name: it digests the ciphertext a stored
            // name was decrypted from, so a stale one costs the next sync walk a decrypt to re-derive
            // a name it already holds, and a wrong one would mask a rename made on another client.
            photoListingDao.getByLinkId(photo.linkId)?.let { existing ->
                photoListingDao.upsertAll(
                    listOf(
                        existing.copy(
                            displayName = trimmed,
                            nameFingerprint = nameFingerprint(newEncryptedName),
                        ),
                    ),
                )
            }
            Log.d(TAG, "renameCloudPhoto: renamed ${photo.linkId}")
        }

    /**
     * Builds a SECOND cloud photo from the same bytes under a new name, leaving the source link
     * untouched. This is the "Save as copy" path; an in-place rename is [renameCloudPhoto].
     *
     * Kept as its own path because it reaches the server differently: the rename route is
     * share-scoped, whereas every photo operation here is volume-scoped, so a copy stays available
     * for any photo the rename route will not take.
     */
    suspend fun copyCloudPhotoAs(
        userId: UserId,
        photo: CloudPhoto,
        newName: String,
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
        // The copy carries the source's capture time, so it lands in the same timeline slot.
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
        uploadService.uploadFile(userId, item, hash, fileUri)
    }
}

/**
 * Rename cannot go ahead, for a reason only a log can use.
 *
 * These messages are written here in English and the rename sheet renders whatever message it is
 * given, so throwing them with text put untranslated developer strings in front of every user. The
 * throw carries no message, which is what makes the sheet fall back to its own translated line, and
 * [why] goes to the log where a bug report can still pick it up.
 */
private fun renameUnavailable(why: String): Nothing {
    Log.w(TAG, "rename unavailable: $why")
    throw IllegalStateException()
}
