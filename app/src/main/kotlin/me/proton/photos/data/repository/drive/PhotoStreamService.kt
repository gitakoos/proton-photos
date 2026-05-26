package me.proton.photos.data.repository.drive

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.photos.data.api.DriveApiService
import me.proton.photos.data.api.dto.PhotoLinkDto
import me.proton.photos.data.crypto.DriveCryptoHelper
import me.proton.photos.data.db.dao.PhotoListingDao
import me.proton.photos.data.preferences.SettingsKeys
import me.proton.photos.data.preferences.settingsDataStore
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.domain.entity.DriveNotFoundException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhotoStreamSvc"

/**
 * Window in which a just-recorded upload (in [RecentUploadsTracker]) is protected from
 * the delete-missing cleanup of [PhotoStreamService.refreshCloudPhotos], even though the
 * photo stream API responded successfully without listing it. 90 seconds is well above
 * any observed server-side stream-indexing lag, while short enough that items the user
 * deleted on Drive web are cleaned up on the very next refresh.
 *
 * See the comment in `refreshCloudPhotos` for the wider story (over-protection caused
 * beta-blocker bug 2026-05-26: cloud-deleted photos kept showing the green cloud icon).
 */
private const val UPLOAD_PROTECTION_WINDOW_MS: Long = 90L * 1000L

/**
 * Cloud photo stream: paginated full refresh + event-based incremental refresh +
 * observe-from-DB flows.
 *
 * Deletion safety: consult [RecentUploadsTracker] before pruning DB entries the stream
 * didn't return — protects fresh uploads from a temporarily-empty stream response.
 */
@Singleton
class PhotoStreamService @Inject constructor(
    private val apiProvider: ApiProvider,
    private val cryptoHelper: DriveCryptoHelper,
    private val photoListingDao: PhotoListingDao,
    private val shareService: PhotosShareService,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val photoEntityBuilder: PhotoEntityBuilder,
    private val recentUploadsTracker: RecentUploadsTracker,
    private val photosVolumeBootstrap: PhotosVolumeBootstrap,
    @ApplicationContext private val context: Context,
) {
    private val semaphore get() = shareService.networkSemaphore

    fun observeCloudPhotos(userId: UserId): Flow<List<CloudPhoto>> =
        photoListingDao.observeAll(userId.id).map { list -> list.map { it.toDomain() } }

    fun observePhotosByLinkIds(linkIds: List<String>): Flow<List<CloudPhoto>> =
        photoListingDao.observeByLinkIds(linkIds).map { list -> list.map { it.toDomain() } }

    suspend fun refreshCloudPhotos(userId: UserId): Unit = withContext(Dispatchers.IO) {
        try {
            val volumeId = shareService.getVolumeId(userId)
            val shareId = shareService.getShareId(userId, volumeId)

            // 1. Ensure Photos volume is properly initialized. Goes through
            //    PhotosShareService.ensurePhotosVolumeReady which carries the full
            //    materialise-or-recover flow (createOrGetPhotosVolume → on ALREADY_EXISTS
            //    fall back to getVolumes() → adopt the existing Photos volume's root
            //    linkId). Calling this in-line ensures the stream refresh ALWAYS has a
            //    populated rootLinkId before it tries to paginate links — otherwise the
            //    refresh swallows the createOrGetPhotosVolume error and proceeds with a
            //    NULL root key, fetching nothing useful.
            shareService.ensurePhotosVolumeReady(userId)

            val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId)
            Log.d(TAG, "refreshCloudPhotos: rootLinkKey=${if (rootLinkKeyBytes != null) "OK" else "NULL"}")
            val manager = apiProvider.get<DriveApiService>(userId)
            val thumbnailCacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }
            val activeVolumeId  = shareService.volumeId() ?: volumeId
            val effectiveShareId = shareService.shareId() ?: shareId

            // 2. Paginated photo stream.
            // Pagination: PreviousPageLastLinkID = last LinkID on current page; stop when page < limit.
            val pageLimit = 500
            val streamLinks = mutableListOf<PhotoLinkDto>()
            // Track whether the stream API responded without throwing (even if it returned 0 photos).
            // Used below to decide if it's safe to delete DB entries not found in this refresh.
            var streamCallSucceeded = false
            try {
                var lastLinkId: String? = null
                do {
                    val page = semaphore.withPermit {
                        manager.invoke { getPhotoLinks(activeVolumeId, lastLinkId, pageLimit) }.valueOrThrow
                    }
                    streamLinks.addAll(page.links)
                    lastLinkId = if (page.links.size >= pageLimit) page.links.last().linkId else null
                    if (lastLinkId != null) delay(100)
                } while (lastLinkId != null)
                streamCallSucceeded = true
                Log.d(TAG, "refreshCloudPhotos: stream has ${streamLinks.size} photos")
            } catch (e: Exception) {
                Log.w(TAG, "refreshCloudPhotos: stream unavailable (${e.javaClass.simpleName}: ${e.message})")
            }

            // 3. Build the photo list from the stream only.
            // Album children are intentionally excluded here: all backed-up photos are in the
            // Photos stream, so albums add no new photos that belong in the main timeline.
            // Including album children would cause shared-album photos (from other users) to
            // appear in the owner's main gallery as if they were backed up.
            // Album photos are fetched on demand by loadAlbumPhotos() and cached in the DB.
            val allPhotoLinks = streamLinks.toList()
            val linkShareMap = streamLinks.associate { it.linkId to effectiveShareId }
            Log.d(TAG, "refreshCloudPhotos: ${allPhotoLinks.size} photos in stream")

            // 4. Batch-fetch link details.
            val linkDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, activeVolumeId, allPhotoLinks.map { it.linkId })

            // 5. Build parentKey cache.
            val rootLinkId = shareService.photosRootLinkId()
            val parentKeyCache = mutableMapOf<String?, ByteArray?>()
            parentKeyCache[rootLinkId] = rootLinkKeyBytes
            val albumParentIds = linkDetailMap.values
                .mapNotNull { it.link.parentLinkId }
                .filter { it != rootLinkId }
                .toSet()
            if (albumParentIds.isNotEmpty() && rootLinkKeyBytes != null) {
                val albumDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, activeVolumeId, albumParentIds.toList())
                for ((albumId, albumDetail) in albumDetailMap) {
                    val aLink = albumDetail.link
                    if (aLink.nodeKey != null && aLink.nodePassphrase != null) {
                        parentKeyCache[albumId] = try {
                            cryptoHelper.decryptNodeKey(aLink.nodeKey, aLink.nodePassphrase, rootLinkKeyBytes)
                        } catch (e: Exception) {
                            Log.w(TAG, "refreshCloudPhotos: albumKey failed for $albumId: ${e.message}")
                            rootLinkKeyBytes
                        }
                    }
                }
            }

            // 6. Batch-fetch thumbnail URLs for all photos that have ThumbnailIDs.
            //    Type 1 = small thumbnail (preferred for gallery grid).
            val thumbnailIdToLinkId = mutableMapOf<String, String>()
            for ((linkId, detail) in linkDetailMap) {
                val thumbnailList = detail.link.fileProperties?.activeRevision?.thumbnails
                    ?: detail.photo?.activeRevision?.thumbnails
                val tid = thumbnailList?.firstOrNull { it.type == 1 }?.thumbnailId
                    ?: thumbnailList?.firstOrNull()?.thumbnailId
                if (tid != null) thumbnailIdToLinkId[tid] = linkId
            }
            val thumbnailUrlMap: Map<String, ThumbnailUrlInfo> =
                if (thumbnailIdToLinkId.isNotEmpty())
                    linkDetailHelpers.batchFetchThumbnailUrls(userId, activeVolumeId, thumbnailIdToLinkId.keys.toList())
                else emptyMap()
            Log.d(TAG, "refreshCloudPhotos: fetched ${thumbnailUrlMap.size} thumbnail URLs")

            // 6b. Batch-fetch ContentKeyPackets via regular Drive API (Photos batch API omits them).
            //     CKPs are needed to decrypt thumbnail blocks (SEIPD format, same as content blocks).
            val ckpMap = linkDetailHelpers.batchFetchContentKeyPackets(userId, effectiveShareId, allPhotoLinks.map { it.linkId })
            val ownPublicKeys = cryptoHelper.getOwnPublicKeysArmored(userId)

            // 7. Build entities.
            val entities = allPhotoLinks.map { stub ->
                val detail = linkDetailMap[stub.linkId]
                val parentId = detail?.link?.parentLinkId
                val parentKeyBytes = parentKeyCache[parentId] ?: rootLinkKeyBytes
                val itemShareId = linkShareMap[stub.linkId] ?: effectiveShareId
                val thumbnailInfo = thumbnailIdToLinkId.entries
                    .firstOrNull { it.value == stub.linkId }
                    ?.key
                    ?.let { thumbnailUrlMap[it] }
                photoEntityBuilder.build(stub, detail, userId, itemShareId, activeVolumeId, parentKeyBytes, thumbnailCacheDir, thumbnailInfo, ckpMap[stub.linkId], ownPublicKeys)
            }

            // Smart merge: upsert all found entities, then conditionally remove stale DB entries.
            //
            // Deletion policy:
            //   • Only delete entries that (a) aren't in the current found set AND
            //     (b) aren't a VERY recent upload (within UPLOAD_PROTECTION_WINDOW_MS).
            //   • We skip deletion entirely when the photo stream was unavailable (404 / network
            //     error): in that case we only have a PARTIAL picture of what's on the server,
            //     so deleting would incorrectly remove stream-only photos — including those
            //     uploaded in previous sessions.
            //
            // Why the protection window is TIGHT (≈90s, not the full 1h TTL): the only legitimate
            // "don't delete despite missing from stream" case is the narrow race where an upload
            // committed but the server-side photo stream index hasn't caught up yet (usually
            // seconds, never minutes). Using the full TTL caused user-reported beta blocker:
            // photos deleted on Drive web kept showing as SYNCED with green cloud icons because
            // their linkIds were still in the 1h-wide protection set. The persistent TTL is for
            // crash/restart resilience (so a process kill between upload and refresh can't drop
            // a fresh upload); the in-refresh protection only needs to span the upload→stream
            // visibility race, which is orders of magnitude shorter.
            val foundIds = entities.map { it.linkId }.toSet()
            // Preserve cached thumbnail URLs from DB for photos where no server thumbnail is available
            // (e.g. photos uploaded via the v2/volumes path which doesn't support server-side thumbnails).
            // Without this, each full refresh would overwrite the locally cached file:// URL with null.
            val existingThumbByLinkId = photoListingDao.getByLinkIds(entities.map { it.linkId })
                .associateBy({ it.linkId }, { it.thumbnailUrl })
            val entitiesToSave = entities.map { entity ->
                if (entity.thumbnailUrl == null) entity.copy(thumbnailUrl = existingThumbByLinkId[entity.linkId])
                else entity
            }
            photoListingDao.upsertAll(entitiesToSave)
            if (streamCallSucceeded) {
                // Stream responded → we have a full picture; safe to clean up stale entries.
                // Use the tight protection window (see comment above) rather than the full TTL.
                val recentUploads = recentUploadsTracker.snapshotWithinMs(UPLOAD_PROTECTION_WINDOW_MS)
                val existingIds = photoListingDao.getAllLinkIds(userId.id).toSet()
                val toDelete = (existingIds - foundIds - recentUploads).toList()
                if (toDelete.isNotEmpty()) {
                    photoListingDao.deleteByLinkIds(toDelete)
                    // Forget them in the tracker too — they're confirmed gone from server, so
                    // they should never be "protected" again on a subsequent refresh.
                    recentUploadsTracker.forget(toDelete)
                    Log.d(TAG, "refreshCloudPhotos: removed ${toDelete.size} stale entries " +
                        "(protected ${recentUploads.size} recent uploads within ${UPLOAD_PROTECTION_WINDOW_MS}ms window)")
                }
                Log.d(TAG, "refreshCloudPhotos: saved ${entities.size} photos (db now has ${existingIds.size - toDelete.size + entities.count { it.linkId !in existingIds }} total)")
            } else {
                Log.d(TAG, "refreshCloudPhotos: stream unavailable — upserted ${entities.size} photos, skipping stale-entry cleanup to avoid data loss")
            }
        } catch (e: DriveNotFoundException) {
            Log.w(TAG, "refreshCloudPhotos: DriveNotFoundException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "refreshCloudPhotos failed", e)
        }
    }

    suspend fun refreshCloudPhotosIncremental(userId: UserId): Unit = withContext(Dispatchers.IO) {
        try {
            val volumeId = shareService.getVolumeId(userId)
            val anchorKey = SettingsKeys.eventAnchorKey(userId.id, volumeId)
            val prefs = context.settingsDataStore.data.first()
            val storedAnchor: String? = prefs[anchorKey]

            if (storedAnchor == null) {
                refreshCloudPhotos(userId)
                try {
                    val manager = apiProvider.get<DriveApiService>(userId)
                    val latestAnchor = semaphore.withPermit {
                        manager.invoke { getLatestEventAnchor(volumeId) }.valueOrThrow
                    }
                    context.settingsDataStore.edit { it[anchorKey] = latestAnchor.eventId }
                    Log.d(TAG, "incremental: initial anchor saved ${latestAnchor.eventId}")
                } catch (e: Exception) {
                    Log.w(TAG, "incremental: could not get event anchor (${e.message}), will full-refresh next time")
                }
                return@withContext
            }

            val shareId = shareService.getShareId(userId, volumeId)
            val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId)
            val thumbnailCacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }
            val manager = apiProvider.get<DriveApiService>(userId)

            var currentAnchor: String = storedAnchor
            var hasMore = true
            var upsertLinkIds = mutableListOf<String>()
            val deleteLinkIds = mutableListOf<String>()

            while (hasMore) {
                val eventsResp = semaphore.withPermit {
                    manager.invoke { getEvents(volumeId, currentAnchor) }.valueOrThrow
                }
                for (event in eventsResp.events) {
                    val linkId = event.link?.linkId ?: event.linkId ?: continue
                    if (event.eventType == 0) {
                        deleteLinkIds += linkId
                    } else {
                        upsertLinkIds += linkId
                    }
                }
                currentAnchor = eventsResp.eventId
                hasMore = eventsResp.more != 0
            }

            if (deleteLinkIds.isNotEmpty()) {
                photoListingDao.deleteByLinkIds(deleteLinkIds)
                Log.d(TAG, "incremental: deleted ${deleteLinkIds.size} links")
            }

            upsertLinkIds = upsertLinkIds.filter { it !in deleteLinkIds }.toMutableList()
            if (upsertLinkIds.isNotEmpty()) {
                val linkDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, upsertLinkIds)

                val rootLinkId = shareService.photosRootLinkId()
                val parentKeyCache = mutableMapOf<String?, ByteArray?>()
                parentKeyCache[rootLinkId] = rootLinkKeyBytes
                val albumParentIds = linkDetailMap.values
                    .mapNotNull { it.link.parentLinkId }
                    .filter { it != rootLinkId }
                    .toSet()
                if (albumParentIds.isNotEmpty() && rootLinkKeyBytes != null) {
                    val albumDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, albumParentIds.toList())
                    for ((albumId, albumDetail) in albumDetailMap) {
                        val aLink = albumDetail.link
                        if (aLink.nodeKey != null && aLink.nodePassphrase != null) {
                            parentKeyCache[albumId] = try {
                                cryptoHelper.decryptNodeKey(aLink.nodeKey, aLink.nodePassphrase, rootLinkKeyBytes)
                            } catch (e: Exception) {
                                // Falling back to root key means photos under this album will
                                // decrypt with the wrong parent key — they'll show garbage names
                                // or fail thumbnail decrypt. Most common cause is a shared
                                // album whose access was revoked mid-sync. Worth a WARN log so
                                // it doesn't disappear silently.
                                Log.w(TAG, "stream: album $albumId key decrypt failed (${e.message}) — falling back to root key, expect cosmetic glitches on its photos")
                                rootLinkKeyBytes
                            }
                        }
                    }
                }

                val thumbnailIdToLinkId = mutableMapOf<String, String>()
                for ((linkId, detail) in linkDetailMap) {
                    val thumbnailList = detail.link.fileProperties?.activeRevision?.thumbnails
                        ?: detail.photo?.activeRevision?.thumbnails
                    val tid = thumbnailList?.firstOrNull { it.type == 1 }?.thumbnailId
                        ?: thumbnailList?.firstOrNull()?.thumbnailId
                    if (tid != null) thumbnailIdToLinkId[tid] = linkId
                }
                val thumbnailUrlMap = if (thumbnailIdToLinkId.isNotEmpty())
                    linkDetailHelpers.batchFetchThumbnailUrls(userId, volumeId, thumbnailIdToLinkId.keys.toList())
                else emptyMap()

                val ckpMap = linkDetailHelpers.batchFetchContentKeyPackets(userId, shareId, upsertLinkIds)
                val ownPublicKeys = cryptoHelper.getOwnPublicKeysArmored(userId)

                val entities = upsertLinkIds.mapNotNull { linkId ->
                    val detail = linkDetailMap[linkId] ?: return@mapNotNull null
                    detail.photo ?: return@mapNotNull null.also { Log.w(TAG, "incremental: skip non-photo $linkId") }
                    val stub = PhotoLinkDto(
                        linkId = linkId,
                        captureTime = detail.photo.captureTime ?: 0L,
                    )
                    val parentId = detail.link.parentLinkId
                    val parentKeyBytes = parentKeyCache[parentId] ?: rootLinkKeyBytes
                    val thumbnailInfo = thumbnailIdToLinkId.entries
                        .firstOrNull { it.value == linkId }
                        ?.key?.let { thumbnailUrlMap[it] }
                    photoEntityBuilder.build(stub, detail, userId, shareId, volumeId, parentKeyBytes, thumbnailCacheDir, thumbnailInfo, ckpMap[linkId], ownPublicKeys)
                }
                val existingThumbByLinkId = photoListingDao.getByLinkIds(entities.map { it.linkId })
                    .associateBy({ it.linkId }, { it.thumbnailUrl })
                val entitiesToSave = entities.map { entity ->
                    if (entity.thumbnailUrl == null) entity.copy(thumbnailUrl = existingThumbByLinkId[entity.linkId])
                    else entity
                }
                photoListingDao.upsertAll(entitiesToSave)
                Log.d(TAG, "incremental: upserted ${entities.size} photos")
            }

            context.settingsDataStore.edit { it[anchorKey] = currentAnchor }
            Log.d(TAG, "incremental: anchor updated to $currentAnchor")
        } catch (e: DriveNotFoundException) {
            Log.w(TAG, "refreshCloudPhotosIncremental: DriveNotFoundException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "refreshCloudPhotosIncremental failed, falling back to full refresh", e)
            refreshCloudPhotos(userId)
        }
    }
}
