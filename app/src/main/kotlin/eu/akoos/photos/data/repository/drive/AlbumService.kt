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

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.api.dto.AddAlbumMultipleEntry
import eu.akoos.photos.data.api.dto.AddAlbumMultipleRequest
import eu.akoos.photos.data.api.dto.AlbumDto
import eu.akoos.photos.data.api.dto.CreateAlbumLinkData
import eu.akoos.photos.data.api.dto.CreateAlbumRequest
import eu.akoos.photos.data.api.dto.PhotoLinkDto
import eu.akoos.photos.data.api.dto.RemoveFromAlbumRequest
import eu.akoos.photos.data.api.dto.UpdateAlbumLinkData
import eu.akoos.photos.data.api.dto.UpdateAlbumRequest
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao
import eu.akoos.photos.data.db.dao.CloudAlbumDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.CloudAlbumEntity
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.VideoDurationBackfillScheduler
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.AlbumChild
import eu.akoos.photos.domain.entity.AlbumDeleteWouldLosePhotos
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.DriveNotFoundException
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.flatMapSqlChunks
import eu.akoos.photos.util.forEachSqlChunk
import eu.akoos.photos.util.isBatteryLow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AlbumSvc"

// The add-multiple / remove-multiple album endpoints reject any request with more than 10 links
// ("This collection should contain 10 elements or less"), so both paths chunk to this size.
private const val ALBUM_LINK_BATCH_MAX = 10

/** Drive's "this would destroy data" refusal, returned when deleting an album that holds the only
 *  copy of some photos. A plain HTTP status cannot be used to spot it: the response is a 422, which
 *  many unrelated validation failures also return. */
private const val ALBUM_DELETE_DATA_LOSS_CODE = 200302

/**
 * How many shared-with-me albums one membership prefetch pass may walk. Every page it asks for lands
 * on the album OWNER's volume and counts against their limits, so the pass stays near the head of the
 * Shared grid — the albums a tap is most likely to open — rather than sweeping the whole list.
 */
private const val MAX_SHARED_MEMBERSHIP_PREFETCH = 8

/**
 * The volume an album's children listing has to be asked on.
 *
 * An album someone else shared lives on the OWNER's volume and carries it on the row, so asking this
 * user's own volume for it names a collection that is not there. Everything they own records no
 * volume of its own and answers on [ownVolumeId]. A blank counts as absent, since a cached row can
 * carry one from a listing that had no volume to record.
 *
 * Pure values → no DI, no DB, no network.
 */
internal fun albumChildrenVolumeId(album: Album, ownVolumeId: String?): String? =
    album.volumeId?.takeIf { it.isNotBlank() } ?: ownVolumeId

/**
 * Whether an album still owes a membership walk.
 *
 * [reportedPhotoCount] is Drive's own count for the album. The owned listing always carries it; the
 * shared-with-me listing does not, since the rows its backup-feed half builds report 0 whatever the
 * album holds, and nothing on the row says which half built it. Measuring against a count that may
 * be a placeholder would re-walk another user's volume on every reload of the tab, so [countIsKnown]
 * false answers from the rows alone — any cached membership is enough for the first paint this
 * prefetch serves, and opening the album refreshes it in full.
 *
 * Pure values → no DI, no DB, no network.
 */
internal fun needsMembershipPrefetch(
    cachedCount: Int,
    reportedPhotoCount: Int,
    countIsKnown: Boolean,
): Boolean = when {
    cachedCount <= 0 -> true
    countIsKnown -> cachedCount != reportedPhotoCount
    else -> false
}

/**
 * The shared-with-me albums a membership prefetch pass may spend requests on, in the order the grid
 * draws them.
 *
 * The share and the owner's volume are what a cross-volume children listing needs to run at all, so
 * a row missing either has no path in. [attempted] carries the albums this process already walked,
 * which is what moves the budget down the list on the next pass instead of re-asking the same
 * albums every time the tab reloads. A process restart is the reset.
 *
 * Pure lists → no DI, no DB, no network.
 */
internal fun sharedMembershipPrefetchTargets(
    albums: List<Album>,
    cap: Int,
    attempted: Set<String> = emptySet(),
): List<Album> {
    if (cap <= 0) return emptyList()
    return albums.asSequence()
        .filter { it.sharingShareId != null && !it.volumeId.isNullOrBlank() }
        .filter { it.linkId !in attempted }
        .distinctBy { it.linkId }
        .take(cap)
        .toList()
}

/** How a shared-album prefetch pass ended for one album. */
internal enum class PrefetchOutcome {
    /** The album's work reached its end, or the pass found none left to do for it. */
    COMPLETED,

    /** The album answered in a way another pass in this process would answer the same. */
    FAILED,

    /** The pass unwound before it reached an answer, so the album is still unknown. */
    CANCELLED,
}

/**
 * Whether an album's pass spends its once-per-process prefetch budget.
 *
 * The budget is what keeps an album that will never resolve — a share handing nothing over, a cover
 * with no thumbnail behind it — costing its OWNER one round trip per process instead of one per open
 * of the Shared tab, so a definitive failure still spends it. A pass cut short spends nothing: the
 * tab fires both prefetches into a job that leaving the tab cancels, and counting that as an answer
 * strands the album's tile blank and its grid un-enumerable offline until the process restarts.
 *
 * Pure values → no DI, no DB, no network.
 */
internal fun burnsPrefetchBudget(outcome: PrefetchOutcome): Boolean =
    outcome != PrefetchOutcome.CANCELLED

/**
 * Album CRUD + album-photo loading + add-to-album. Reads cached photo entities through
 * [PhotoListingDao] so opening a previously-loaded album is a hot DB lookup instead of
 * a Drive round-trip + decrypt loop.
 */
@Singleton
class AlbumService @Inject constructor(
    private val apiProvider: ApiProvider,
    private val cryptoHelper: DriveCryptoHelper,
    private val cryptoContext: CryptoContext,
    private val photoListingDao: PhotoListingDao,
    private val cloudAlbumDao: CloudAlbumDao,
    private val albumPhotoMembershipDao: AlbumPhotoMembershipDao,
    private val shareService: PhotosShareService,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val thumbnailHelpers: ThumbnailHelpers,
    private val photoEntityBuilder: PhotoEntityBuilder,
    private val thumbnailDecryptScheduler: ThumbnailDecryptScheduler,
    private val videoDurationBackfillScheduler: VideoDurationBackfillScheduler,
    private val albumCryptoChain: AlbumCryptoChain,
    private val sharedAlbumKeyStore: SharedAlbumKeyStore,
    private val albumCacheCleanup: AlbumCacheCleanup,
    @ApplicationContext private val context: Context,
) {
    private val semaphore get() = shareService.networkSemaphore

    // Canonical album photo order: effective captureTime DESC, then linkId as a stable
    // tie-breaker so equal-captureTime photos (camera bursts) keep a total order and don't
    // swap places between the cache paint and the network refresh. captureTimeMs routes
    // through TimestampSanity so a sub-floor value collapses to the same key the timeline
    // gives the same photo, instead of sorting on a raw 0-ish second value.
    private val photoOrder = compareByDescending<CloudPhoto> { it.captureTimeMs }.thenBy { it.linkId }

    /**
     * In-memory cache of `photoLinkId → albumName` so the gallery / photo-viewer download
     * paths can route album-bound photos into per-album folders without re-fetching every
     * album's children on every download click. Every in-app album mutation invalidates this
     * immediately via [invalidateMembershipCache], so the TTL only bounds how long an
     * out-of-app (web) album change can leave a stale routing entry — and a stale entry only
     * misroutes a download folder, never affects correctness — so a longer TTL is safe and
     * spares the full album-walk refetch on routine downloads.
     */
    @Volatile private var membershipCache: Map<String, String>? = null
    @Volatile private var membershipCacheTime: Long = 0L
    private val membershipCacheTtlMs = 20 * 60 * 1000L

    /**
     * Full multi-album membership lookup: `photoLinkId → Set<albumLinkId>`. Distinct from
     * [membershipCache] which only keeps the first album per photo (used for download
     * folder routing). This map is what the PhotoViewer's "Add to album" sheet needs so it
     * can show a checkmark next to every album the current photo is already in — and let
     * the user tap one of them to remove the photo from that album.
     *
     * Same TTL + invalidation as [membershipCache] (both are dropped together by
     * [invalidateMembershipCache]). Both maps are built in the same loop in
     * [getAlbumMemberships], so adding the second map costs nothing extra at refresh time.
     */
    @Volatile private var fullMembershipCache: Map<String, Set<String>>? = null

    /**
     * Whether the last [ensureMembershipCachesFresh] build enumerated EVERY album fully: loadAlbums
     * succeeded AND every album's children loaded. [getVerifiedAlbumIdsByPhoto] reads this to tell a
     * verified-complete map apart from a silent partial, so a destructive caller never trashes an
     * original against an under-reported album set. The best-effort readers never consult it.
     */
    @Volatile private var membershipCacheComplete = false

    /** Serializes the album walk so several concurrent callers (a 4-wide metadata batch that all read
     *  membership before trashing) share ONE walk instead of each launching a full, network-heavy pass
     *  that invalidates the others' result. Callers wait on this, then read the now-fresh cache. */
    private val membershipMutex = Mutex()

    /**
     * Returns a `photoLinkId → albumName` lookup spanning every album the user owns.
     * For photos that live in multiple albums, the alphabetically first album wins
     * (stable, deterministic, no surprises for users sorting by name in their gallery).
     *
     * Cached with the membership TTL; per-album errors are logged and skipped so one broken
     * album never poisons the whole map.
     */
    suspend fun getAlbumMemberships(userId: UserId): Map<String, String> = withContext(Dispatchers.IO) {
        ensureMembershipCachesFresh(userId)
        membershipCache ?: emptyMap()
    }

    /**
     * Returns `photoLinkId → Set<albumLinkId>` covering every album the user owns. Used by
     * the photo viewer's "Add to album" sheet to mark which albums the current photo is
     * already in (so the same tap can REMOVE it instead of just adding it again).
     */
    suspend fun getAlbumIdsByPhoto(userId: UserId): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        ensureMembershipCachesFresh(userId)
        fullMembershipCache ?: emptyMap()
    }

    /**
     * Returns the FULL `photoLinkId → Set<albumLinkId>` map, but only when the enumeration is verified
     * complete: loadAlbums succeeded AND every album's children loaded. Throws otherwise. A photo that
     * is genuinely in zero albums under a complete walk simply has no entry (an empty set downstream via
     * `.orEmpty()`), which is a valid answer a destructive caller may act on. What this refuses to hand
     * back is a silent partial, so the metadata-replace use cases never trash an original against an
     * under-reported album set and drop the photo from an album.
     */
    suspend fun getVerifiedAlbumIdsByPhoto(userId: UserId): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        ensureMembershipCachesFresh(userId)
        fullMembershipCache?.takeIf { membershipCacheComplete }?.let { return@withContext it }
        // The cache was absent or built from a partial walk. Force exactly one fresh rebuild before
        // refusing, so a single transient album-list hiccup does not fail an otherwise valid save.
        invalidateMembershipCache()
        ensureMembershipCachesFresh(userId)
        fullMembershipCache?.takeIf { membershipCacheComplete }
            ?: throw IllegalStateException("album membership enumeration incomplete; refusing to trash original")
    }

    /** Serializes the walk (see [membershipMutex]) so concurrent callers share one pass instead of each
     *  running its own. A caller that arrives while a walk is in flight waits, then finds the cache fresh
     *  and returns without walking again. */
    private suspend fun ensureMembershipCachesFresh(userId: UserId) = membershipMutex.withLock {
        ensureMembershipCachesFreshLocked(userId)
    }

    /**
     * Builds both [membershipCache] (name lookup, alphabetically-first wins) and
     * [fullMembershipCache] (full set of album linkIds) in a single album-walk pass. Both
     * maps share one TTL and a single invalidation entry point.
     */
    private suspend fun ensureMembershipCachesFreshLocked(userId: UserId) {
        val now = System.currentTimeMillis()
        if (membershipCache != null && fullMembershipCache != null
            && now - membershipCacheTime < membershipCacheTtlMs) {
            return
        }
        // A rebuild is committed from here, so hold the completeness flag low until the walk finishes:
        // a verified read must never trust a half-built map, and the loadAlbums-failure return below
        // leaves the flag low so getVerifiedAlbumIdsByPhoto refuses rather than trusts a stale set.
        membershipCacheComplete = false
        val albums = runCatching { loadAlbums(userId) }.getOrElse {
            Log.w(TAG, "membership refresh: loadAlbums failed: ${it.message}")
            return
        }
        val sortedAlbums = albums.sortedBy { it.name.lowercase() }
        val nameMap = mutableMapOf<String, String>()
        val idsMap = mutableMapOf<String, MutableSet<String>>()
        var complete = true
        for (album in sortedAlbums) {
            try {
                val children = loadAlbumChildren(userId, album.linkId)
                for (child in children) {
                    nameMap.putIfAbsent(child.linkId, album.name)
                    idsMap.getOrPut(child.linkId) { mutableSetOf() }.add(album.linkId)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "membership refresh: album ${album.linkId} children failed: ${e.message}")
                complete = false
            }
        }
        membershipCache = nameMap
        fullMembershipCache = idsMap.mapValues { it.value.toSet() }
        membershipCacheTime = now
        // Written after the map so a reader that sees the flag set is guaranteed the fresh map too.
        membershipCacheComplete = complete
        Log.d(TAG, "membership refresh: built ${nameMap.size} name entries, ${idsMap.size} id sets across ${albums.size} albums (complete=$complete)")
    }

    /** Drops both membership caches so the next read re-fetches. */
    fun invalidateMembershipCache() {
        membershipCache = null
        fullMembershipCache = null
        membershipCacheTime = 0L
        membershipCacheComplete = false
    }

    suspend fun loadAlbums(userId: UserId): List<Album> = withContext(Dispatchers.IO) {
        val volumeId = shareService.getVolumeId(userId)
        val shareId = shareService.getShareId(userId, volumeId)
        // The Photos share key chain is built lazily — on a cold cache the first
        // getRootLinkKeyBytes call can return null if any link in the chain
        // (share key, share passphrase, root link details) hasn't been fetched yet.
        // Without the root key the per-album loop below falls into the placeholder
        // branch and emits 8-char linkId stubs as names, forcing the user to
        // pull-to-refresh once before real names appear. Warm the chain explicitly
        // and re-attempt the key load so the names decrypt on the first paint.
        val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId)
            ?: run {
                shareService.ensurePhotosVolumeReady(userId)
                shareService.getRootLinkKeyBytes(userId)
            }
            ?: error("loadAlbums: root link key unavailable — album names cannot be decrypted")
        val thumbnailCacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }
        val manager = apiProvider.get<DriveApiService>(userId)
        val albumStubs = mutableListOf<AlbumDto>()
        var anchorId: String? = null

        do {
            val response = semaphore.withPermit {
                manager.invoke { getAlbums(volumeId, anchorId) }.valueOrThrow
            }
            albumStubs.addAll(response.albums)
            anchorId = if (response.more) response.anchorId else null
        } while (anchorId != null)

        // Initial batch fetch + retry pass for any album whose link details didn't come
        // back in the first round (silent chunk failure inside batchFetchLinkDetails). The
        // retry keeps the placeholder-name branch below from emitting linkId stubs when a
        // single batch chunk hit a transient error.
        val linkDetailMap = linkDetailHelpers
            .batchFetchLinkDetails(userId, volumeId, albumStubs.map { it.linkId })
            .toMutableMap()
        val missingDetailIds = albumStubs.map { it.linkId }.filter { it !in linkDetailMap }
        if (missingDetailIds.isNotEmpty()) {
            val retry = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, missingDetailIds)
            linkDetailMap.putAll(retry)
            Log.d(TAG, "loadAlbums: link-detail retry recovered ${retry.size}/${missingDetailIds.size} entries")
        }

        // Pre-fetch cover thumbnails for any cover photo we don't already have cached locally.
        // Without this the Albums tab shows a blank tile for cloud-only albums until the user
        // opens each one (the in-album loader fills the cache as a side-effect, but the Albums
        // list itself never triggered a fetch). Two-step pipeline:
        //   1. Identify cover linkIds whose thumbnail is missing from disk + DB.
        //   2. Bulk-fetch their link details + thumbnail CDN URLs + CKPs, then decrypt to disk.
        // After this completes the per-album lookup below picks the freshly cached file URLs.
        val missingCoverIds = albumStubs.mapNotNull { it.coverLinkId }
            .distinct()
            .filter { linkId ->
                val cached = photoListingDao.getByLinkId(linkId)?.thumbnailUrl
                if (cached != null && thumbnailHelpers.isCachedValid(cached)) return@filter false
                val onDisk = File(thumbnailCacheDir, "thumb_$linkId.jpg")
                !(onDisk.exists() && onDisk.length() > 0)
            }
        if (missingCoverIds.isNotEmpty()) {
            try {
                val coverDetails = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, missingCoverIds)
                val thumbnailIdToLinkId = mutableMapOf<String, String>()
                for ((coverId, detail) in coverDetails) {
                    val tl = detail.link.fileProperties?.activeRevision?.thumbnails
                        ?: detail.photo?.activeRevision?.thumbnails
                    // Prefer Type 2 (HD, ~512px+) over Type 1 (~200px) for sharp album-card covers.
                    val tid = tl?.firstOrNull { it.type == 2 }?.thumbnailId
                        ?: tl?.firstOrNull { it.type == 1 }?.thumbnailId
                        ?: tl?.firstOrNull()?.thumbnailId
                    if (tid != null) thumbnailIdToLinkId[tid] = coverId
                }
                val thumbUrlMap = if (thumbnailIdToLinkId.isNotEmpty())
                    linkDetailHelpers.batchFetchThumbnailUrls(userId, volumeId, thumbnailIdToLinkId.keys.toList())
                else emptyMap()
                val ckpMap = linkDetailHelpers.batchFetchContentKeyPackets(userId, shareId, missingCoverIds)

                for ((coverId, detail) in coverDetails) {
                    val nodeKeyArmored = detail.link.nodeKey ?: run {
                        Log.w(TAG, "cover prefetch: $coverId missing nodeKey, skipping")
                        continue
                    }
                    val nodePassArmored = detail.link.nodePassphrase ?: run {
                        Log.w(TAG, "cover prefetch: $coverId missing nodePassphrase, skipping")
                        continue
                    }
                    val nodeKeyBytes = runCatching {
                        cryptoHelper.decryptNodeKey(nodeKeyArmored, nodePassArmored, rootLinkKeyBytes)
                    }.getOrElse { e ->
                        Log.w(TAG, "cover prefetch: $coverId nodeKey decrypt failed: ${e.message}")
                        continue
                    }
                    val thumbId = thumbnailIdToLinkId.entries.firstOrNull { it.value == coverId }?.key
                    val thumbInfo = thumbId?.let { thumbUrlMap[it] }
                    if (thumbInfo == null) {
                        Log.d(TAG, "cover prefetch: $coverId has no thumbnail URL, skipping")
                        continue
                    }
                    val ckp = ckpMap[coverId]
                        ?: detail.photo?.contentKeyPacket
                        ?: detail.link.fileProperties?.contentKeyPacket
                    val sessionKey = ckp?.let {
                        runCatching { cryptoHelper.decryptSessionKey(it, nodeKeyBytes) }
                            .getOrElse { e ->
                                Log.w(TAG, "cover prefetch: $coverId sessionKey decrypt failed: ${e.message}")
                                null
                            }
                    }
                    runCatching {
                        thumbnailHelpers.downloadAndDecryptBinary(
                            info = thumbInfo,
                            nodeKeyBytes = nodeKeyBytes,
                            sessionKey = sessionKey,
                            linkId = coverId,
                            cacheDir = thumbnailCacheDir,
                        )
                    }.onFailure { e ->
                        Log.w(TAG, "cover prefetch: $coverId thumbnail download/decrypt failed: ${e.message}")
                    }
                }
                Log.d(TAG, "loadAlbums: prefetched ${missingCoverIds.size} cover thumbnails")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "loadAlbums: cover-prefetch failed (${e.message}) — albums will load without covers")
            }
        }

        val undecryptedLinkIds = mutableListOf<String>()
        // Hidden-photo set resolved once so a cover pointing at a hidden photo can be swapped for a
        // non-hidden member below (a hidden photo must never paint as an album cover). The hidden-album
        // ids let a hidden album keep its own cover in the Hidden view.
        val hiddenAlbumIds = runCatching { context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_ALBUM_IDS] }.getOrNull().orEmpty()
        val hiddenCoverIds = runCatching { observeHiddenAlbumMemberLinkIds().first() }.getOrNull().orEmpty()
        val initialAlbums = albumStubs.map { dto ->
            val link = linkDetailMap[dto.linkId]?.link
            var name = dto.linkId.take(8)
            var nameDecrypted = false
            if (link != null) {
                val nodeKey = link.nodeKey
                val nodePassphrase = link.nodePassphrase
                val encName = link.name
                if (nodeKey != null && nodePassphrase != null && encName != null) {
                    try {
                        cryptoHelper.decryptNodeKey(nodeKey, nodePassphrase, rootLinkKeyBytes)
                        val plain = cryptoHelper.decryptLinkName(encName, rootLinkKeyBytes)
                        if (plain != null) {
                            name = plain
                            nameDecrypted = true
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w(TAG, "album name decrypt failed for ${dto.linkId}: ${e.message}")
                    }
                }
            }
            if (!nameDecrypted) undecryptedLinkIds += dto.linkId
            val coverThumbnailUrl = resolveVisibleCoverThumbnail(dto.linkId, dto.coverLinkId, dto.linkId in hiddenAlbumIds, hiddenCoverIds, thumbnailCacheDir)
            val sharing = linkDetailMap[dto.linkId]?.sharing
            Album(
                linkId = dto.linkId,
                name = name,
                photoCount = dto.photoCount,
                coverLinkId = dto.coverLinkId,
                lastActivityTimeMs = dto.lastActivityTime?.let { it * 1000L },
                coverThumbnailUrl = coverThumbnailUrl,
                sharingShareId = sharing?.shareId,
                sharingShareUrlId = sharing?.shareUrlId,
            )
        }

        // Final recovery pass: if any album fell into the placeholder branch despite the
        // earlier link-detail retry — typically because a single batch chunk hit a transient
        // PGP error, not a missing DTO — re-fetch just those links and re-decrypt. Last line
        // of defence before the UI sees stub names.
        val albums = if (undecryptedLinkIds.isEmpty()) {
            initialAlbums
        } else {
            val recoveryMap = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, undecryptedLinkIds)
            Log.d(TAG, "loadAlbums: name-recovery pass for ${undecryptedLinkIds.size} albums")
            initialAlbums.map { album ->
                if (album.linkId !in undecryptedLinkIds) return@map album
                val link = recoveryMap[album.linkId]?.link ?: return@map album
                val nodeKey = link.nodeKey ?: return@map album
                val nodePassphrase = link.nodePassphrase ?: return@map album
                val encName = link.name ?: return@map album
                val plain = try {
                    cryptoHelper.decryptNodeKey(nodeKey, nodePassphrase, rootLinkKeyBytes)
                    cryptoHelper.decryptLinkName(encName, rootLinkKeyBytes)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "album name recovery decrypt failed for ${album.linkId}: ${e.message}")
                    null
                }
                if (plain != null) album.copy(name = plain) else album
            }
        }

        // Persist for offline / cold-launch instant paint. Best-effort: a Room write failure
        // here must not poison the in-memory result the caller already has. The cache is a
        // performance optimisation, not the source of truth — re-fetch wins on the next refresh.
        runCatching {
            val now = System.currentTimeMillis()
            val entities = albums.map { CloudAlbumEntity.fromDomain(it, now) }
            cloudAlbumDao.upsertAll(entities)
            // Drop rows for albums the server no longer reports, so deletions made on Drive
            // Web don't linger in the local grid after the next successful refresh.
            // An empty list is not evidence that the user has none: a refresh whose feeds all
            // failed reports the same empty result, and SQLite reads `NOT IN ()` as true for every
            // row, so the prune would clear the whole cache on a bad network. Pruning only against
            // a non-empty answer costs a stale row until the next refresh that actually read
            // something, which is the cheaper of the two mistakes.
            if (albums.isNotEmpty()) cloudAlbumDao.deleteWhereNotIn(albums.map { it.linkId })
        }.onFailure { e ->
            Log.w(TAG, "loadAlbums: cache persist failed (${e.message}) — in-memory result still returned")
        }

        // Pin + warm the album cover thumbnails so the large-library cache trim never evicts them
        // and the Albums grid doesn't fall back to blank covers once the cold tail starts churning.
        thumbnailDecryptScheduler.pinCovers(userId, albums.mapNotNull { it.coverLinkId })

        albums
    }

    /**
     * Read-only fast path: returns the cached album list straight from Room with cover URLs
     * rehydrated from the local DB / on-disk thumbnail cache where available. Used by
     * [AlbumsViewModel.loadAlbums] for instant paint on cold launch (including airplane-mode
     * starts) before the network refresh kicks in. Empty when no successful refresh has ever
     * run on this device, or when the user has just signed out.
     */
    suspend fun loadAlbumsCached(): List<Album> = withContext(Dispatchers.IO) {
        runCatching {
            val thumbnailCacheDir = File(context.cacheDir, "thumbnails")
            val hiddenAlbumIds = runCatching { context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_ALBUM_IDS] }.getOrNull().orEmpty()
            val hiddenCoverIds = runCatching { observeHiddenAlbumMemberLinkIds().first() }.getOrNull().orEmpty()
            cloudAlbumDao.getOwned().map { entity ->
                val domain = entity.toDomain()
                // Rehydrate coverThumbnailUrl from local sources (the entity doesn't persist expiring
                // CDN URLs) and skip a hidden cover, resolving a non-hidden member instead. A hidden
                // album keeps its own cover for the Hidden view.
                val coverUrl = resolveVisibleCoverThumbnail(domain.linkId, domain.coverLinkId, domain.linkId in hiddenAlbumIds, hiddenCoverIds, thumbnailCacheDir)
                domain.copy(coverThumbnailUrl = coverUrl)
            }
        }.getOrElse { e ->
            Log.w(TAG, "loadAlbumsCached: failed (${e.message}), returning empty")
            emptyList()
        }
    }

    /**
     * Every cached album someone else shared with this user, whatever rights they hold on it.
     *
     * Read straight from the cache with no network call: the Shared tab and the add-to-album picker
     * both open on a tap and cannot wait on the shared-with-me walk, which is a bootstrap per share.
     * The rows are refreshed whenever that walk runs. Album-scale and free of crypto material — the
     * `cloud_albums` row is a name and a few ids.
     *
     * Covers are rehydrated the way the owned list's are: the entity never persists an expiring CDN
     * URL, so without this the cards draw blank.
     */
    suspend fun loadSharedWithMeAlbumsCached(): List<Album> = withContext(Dispatchers.IO) {
        runCatching {
            val thumbnailCacheDir = File(context.cacheDir, "thumbnails")
            val hiddenCoverIds = runCatching { observeHiddenAlbumMemberLinkIds().first() }.getOrNull().orEmpty()
            cloudAlbumDao.getSharedWithMe()
                .map { it.toDomain() }
                .map { album ->
                    album.copy(
                        coverThumbnailUrl = resolveVisibleCoverThumbnail(
                            album.linkId, album.coverLinkId, false, hiddenCoverIds, thumbnailCacheDir,
                        )
                    )
                }
        }.getOrElse { e ->
            Log.w(TAG, "loadSharedWithMeAlbumsCached: failed (${e.message}), returning empty")
            emptyList()
        }
    }

    /**
     * The share of [loadSharedWithMeAlbumsCached] this user may add photos to, for the add-to-album
     * picker.
     *
     * Filtered to what the user can actually contribute to, so a viewer-only album never appears as
     * a destination that would fail on tap.
     */
    suspend fun loadSharedAddableAlbumsCached(): List<Album> =
        loadSharedWithMeAlbumsCached().filter { it.canAddPhotos }

    /**
     * Resolve an album's display cover thumbnail, skipping any hidden photo. Uses the server cover
     * when it is not hidden; if that cover photo is hidden, falls back to the first non-hidden member
     * with a resolvable thumbnail so a hidden photo never becomes an album's visible cover. Null when
     * nothing resolves (the card then shows a neutral tile).
     */
    private suspend fun resolveVisibleCoverThumbnail(
        albumLinkId: String,
        coverLinkId: String?,
        albumIsHidden: Boolean,
        hiddenIds: Set<String>,
        thumbnailCacheDir: File,
    ): String? {
        if (coverLinkId == null) return null
        // A hidden album (shown only in the Hidden view) keeps its real cover: its own members all sit
        // in hiddenIds, so the skip below would wrongly blank it. Only a non-hidden album skips a
        // hidden cover.
        if (albumIsHidden) return thumbnailUrlForLink(coverLinkId, thumbnailCacheDir)
        // Common path (cover not hidden): resolve it exactly as before, no membership query.
        if (coverLinkId !in hiddenIds) return thumbnailUrlForLink(coverLinkId, thumbnailCacheDir)
        for (memberLinkId in runCatching { albumPhotoMembershipDao.getPhotoLinkIds(albumLinkId) }.getOrNull().orEmpty()) {
            if (memberLinkId in hiddenIds) continue
            thumbnailUrlForLink(memberLinkId, thumbnailCacheDir)?.let { return it }
        }
        return null
    }

    /** Local thumbnail for a photo link: the DB row's URL (synced) or the on-disk cache (cloud-only). */
    private suspend fun thumbnailUrlForLink(linkId: String, thumbnailCacheDir: File): String? =
        photoListingDao.getByLinkId(linkId)?.thumbnailUrl
            ?: File(thumbnailCacheDir, "thumb_$linkId.jpg")
                .takeIf { it.exists() && it.length() > 0 }
                ?.let { "file://${it.absolutePath}" }

    /** Wipes the cached album list AND the album→photo membership table. Called from
     *  the sign-out path so cached data doesn't bleed across accounts. */
    /**
     * Cloud photo linkIds hidden from every listing: the members of any client-side HIDDEN album
     * ([SettingsKeys.HIDDEN_ALBUM_IDS]) unioned with the individually-hidden cloud photos
     * ([SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS]). Reactive: re-emits when either set or an affected
     * album's membership changes. Emits an empty set (and runs no membership query) when nothing is
     * hidden, so non-users pay zero cost. A read landing mid-membership-write degrades to "hide
     * nothing" rather than surfacing an error.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeHiddenAlbumMemberLinkIds(): Flow<Set<String>> {
        val albumMemberLinkIds = context.settingsDataStore.data
            .map { it[SettingsKeys.HIDDEN_ALBUM_IDS] ?: emptySet() }
            .distinctUntilChanged()
            .flatMapLatest { hiddenIds ->
                if (hiddenIds.isEmpty()) flowOf(emptySet())
                else albumPhotoMembershipDao.observeAssociatedPhotoLinkIdsForAlbums(hiddenIds).map { it.toSet() }
            }
        val hiddenCloudPhotoIds = context.settingsDataStore.data
            .map { it[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet() }
            .distinctUntilChanged()
        // One combined Set: hidden-album members plus the individually-hidden cloud photos. The
        // empty-side fast paths keep the "no membership query, no allocation" cost for the common
        // case where the user has hidden nothing.
        return combine(albumMemberLinkIds, hiddenCloudPhotoIds) { members, photos ->
            when {
                members.isEmpty() -> photos
                photos.isEmpty() -> members
                else -> members + photos
            }
        }
            .distinctUntilChanged()
            .catch { emit(emptySet()) }
    }

    suspend fun clearAlbumCache(): Unit = withContext(Dispatchers.IO) {
        runCatching { cloudAlbumDao.clearAll() }
            .onFailure { Log.w(TAG, "clearAlbumCache: cloudAlbumDao: ${it.message}") }
        runCatching { albumPhotoMembershipDao.clearAll() }
            .onFailure { Log.w(TAG, "clearAlbumCache: albumPhotoMembershipDao: ${it.message}") }
    }

    /**
     * Background prefetch — for each album in [albums] whose cached membership row count
     * does not match Drive's photoCount, walks the album-children pagination to refresh
     * the [albumPhotoMembershipDao] rows. No crypto, no thumbnail fetching — just the
     * `linkId` list per album. This lets a later [loadAlbumPhotosCached] enumerate the
     * grid offline without forcing the user to manually open each album online first.
     *
     * Throttled through the existing [shareService.networkSemaphore] so it shares
     * concurrency budget with the rest of the app's Drive traffic. Failures per album
     * are swallowed individually so one broken album doesn't poison the rest.
     *
     * For the albums this user owns. The ones someone else shared go through
     * [prefetchSharedAlbumsMembership], which is bounded far more tightly.
     */
    suspend fun prefetchAlbumsMembership(userId: UserId, albums: List<Album>): Unit = withContext(Dispatchers.IO) {
        if (albums.isEmpty()) return@withContext
        val ownVolumeId = runCatching { shareService.getVolumeId(userId) }.getOrNull() ?: return@withContext
        walkAlbumsMembership(userId, albums, ownVolumeId, countIsKnown = true)
    }

    /**
     * Shared albums this process already ran a membership walk to an answer for. An album the share
     * will not enumerate would otherwise cost its owner a request on every open of the Shared tab for
     * the same nothing, and one that walked fine has no second thing to learn. An album whose walk was
     * cancelled reached no answer and stays out, so the next pass picks it up. A process restart is
     * the reset.
     */
    private val sharedMembershipPrefetchAttempted = ConcurrentHashMap.newKeySet<String>()

    /**
     * The same membership rows, for the albums someone else shared with this user.
     *
     * Separate from [prefetchAlbumsMembership] because the traffic is charged to the album's OWNER
     * rather than to the caller: capped at [MAX_SHARED_MEMBERSHIP_PREFETCH] albums per pass, one
     * album at a time, each album once per process, and skipped outright on a low battery. Nothing
     * periodic calls it — the Shared tab does, once its own refresh has landed.
     *
     * Edge rows only, which is the half that costs nothing to decrypt: the walk asks the owner's
     * volume for link ids and writes them, and the photos themselves stay untouched until the album
     * is opened.
     */
    suspend fun prefetchSharedAlbumsMembership(userId: UserId, albums: List<Album>): Unit = withContext(Dispatchers.IO) {
        val targets = sharedMembershipPrefetchTargets(
            albums, MAX_SHARED_MEMBERSHIP_PREFETCH, sharedMembershipPrefetchAttempted,
        )
        if (targets.isEmpty()) return@withContext
        if (context.isBatteryLow()) {
            Log.d(TAG, "prefetchSharedAlbumsMembership: battery low — skipping ${targets.size} album(s)")
            return@withContext
        }
        // Every target carries the owner's volume, so there is no own-volume fallback to resolve. The
        // budget is spent per album as that album settles, so a pass the user cancels by leaving the
        // tab leaves the albums it never reached available to the next one.
        walkAlbumsMembership(userId, targets, ownVolumeId = null, countIsKnown = false) { linkId, outcome ->
            if (burnsPrefetchBudget(outcome)) sharedMembershipPrefetchAttempted.add(linkId)
        }
    }

    /**
     * The walk both prefetches run: per album, ask the volume that album lives on for its child link
     * ids and replace that album's edge rows with them.
     *
     * [onAlbumSettled] reports each album the walk reached an answer for. The owned prefetch passes
     * none — it keeps no budget — so only the shared caller pays any attention. A cancellation unwinds
     * out of here without reporting, which is [PrefetchOutcome.CANCELLED] as the caller sees it.
     */
    private suspend fun walkAlbumsMembership(
        userId: UserId,
        albums: List<Album>,
        ownVolumeId: String?,
        countIsKnown: Boolean,
        onAlbumSettled: ((linkId: String, outcome: PrefetchOutcome) -> Unit)? = null,
    ) {
        val manager = apiProvider.get<DriveApiService>(userId)
        for (album in albums) {
            val volumeId = albumChildrenVolumeId(album, ownVolumeId)
            if (volumeId == null) {
                // Neither the row nor the caller names a volume, and neither gains one by being asked
                // again.
                onAlbumSettled?.invoke(album.linkId, PrefetchOutcome.FAILED)
                continue
            }
            // Freshness gate — a cheap O(1) DAO call rather than a fresh pagination.
            val cachedCount = runCatching { albumPhotoMembershipDao.getPhotoLinkIds(album.linkId).size }.getOrDefault(-1)
            if (!needsMembershipPrefetch(cachedCount, album.photoCount, countIsKnown)) {
                // Cached rows already cover the album, so this pass owes it nothing.
                onAlbumSettled?.invoke(album.linkId, PrefetchOutcome.COMPLETED)
                continue
            }
            try {
                val linkIds = mutableListOf<String>()
                var anchor: String? = null
                do {
                    val resp = semaphore.withPermit {
                        manager.invoke { getAlbumChildren(volumeId, album.linkId, anchor) }.valueOrThrow
                    }
                    resp.photos.mapTo(linkIds) { it.linkId }
                    anchor = if (resp.more) resp.anchorId else null
                } while (anchor != null)
                albumPhotoMembershipDao.replaceAllForAlbum(album.linkId, linkIds.distinct())
                onAlbumSettled?.invoke(album.linkId, PrefetchOutcome.COMPLETED)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "membership prefetch: ${album.linkId} failed (${e.message}) — continuing")
                onAlbumSettled?.invoke(album.linkId, PrefetchOutcome.FAILED)
            }
        }
    }

    suspend fun createDriveAlbum(userId: UserId, name: String): Album = withContext(Dispatchers.IO) {
        val volumeId = shareService.getVolumeId(userId)

        // A brand-new Photos share has no materialised root link until the first
        // createOrGetPhotosVolume call. Album-create needs to encrypt name + nodePassphrase
        // to the root link key, so trigger the lazy materialisation here. Idempotent on
        // the server side; only does network work when our cache is empty.
        if (shareService.rootLinkArmoredKey() == null) {
            shareService.ensurePhotosVolumeReady(userId)
        }

        // Ensure root link key is loaded so we can encrypt to it.
        val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId)
            ?: error("Cannot load root link key for album creation")
        val rootLinkArmoredKey = shareService.rootLinkArmoredKey()
            ?: error("Root link armored key not available")
        val rootLinkPublicKey = cryptoHelper.withCryptoLock {
            cryptoContext.pgpCrypto.getPublicKey(rootLinkArmoredKey)
        }
        // Warm the volume-owner-address cache BEFORE picking the signing key.
        // `getAddressSigningKey` falls back to mail-primary when
        // `volumeOwnerAddressId()` returns null (cold-start race: bootstrap
        // not yet run, the call inside getAddressSigningKey races against
        // album creation), and the first album-create after install would
        // silently land signed by mail-primary while every subsequent
        // operation resolves to volume-owner. The recipient then refuses to
        // verify the album signature chain because the album's SignatureEmail
        // and the volume's owner relation disagree. Force the cache to
        // populate here so the very next signing key is always the canonical
        // volume owner.
        if (shareService.cachedVolumeOwnerAddressIdOrNull() == null) {
            val warmed = shareService.volumeOwnerAddressId(userId)
            if (warmed == null) {
                error("createDriveAlbum: volume owner address could not be resolved — refusing to sign with mail-primary")
            }
        }
        val signingKey = cryptoHelper.getAddressSigningKey(userId)

        // Album NodeKey comes from the same gopenpgp path as photo NodeKeys;
        // the pinned android-golib 2.10.0-2 emits the SHA-512 binding
        // signatures Drive web's recipient verifier requires.
        val albumNodeKey = cryptoHelper.generateAlbumNodeKey()

        // NodePassphrase encrypted to root link's public key
        val nodePassphraseEncrypted = cryptoHelper.encryptDataToPgpMessage(
            albumNodeKey.passphraseBytes, rootLinkPublicKey)
        val nodePassphraseSignature = cryptoHelper.signData(
            albumNodeKey.passphraseBytes, signingKey.unlockedKeyBytes)

        // Album name encrypted to root link's public key, signed with address key.
        val encryptedName = cryptoHelper.encryptName(name, rootLinkPublicKey, signingKey.unlockedKeyBytes)

        // NodeHashKey: a random 32-byte secret encrypted to the album's own public key
        // AND signed with the album's own private key. The album's NodeHashKey is what
        // the album's CHILDREN (photos) use to compute their own name hashes — it is
        // NOT used for the album's own name hash.
        //
        // Signer choice — Drive Android's `GenerateHashKey` (drive/crypto-base/.../usecase/
        // GenerateHashKey.kt:32-42) calls `encryptAndSignHashKey` inside an
        // `encryptKey.useKeys(cryptoContext) { ... }` block where `encryptKey` is the
        // album NodeKey itself. The implicit signer becomes the keyHolder's primary
        // private key — i.e. the album NodeKey — NOT the user's address key.
        //
        // The corresponding verifier `BuildNodeHashKey.kt:71-86` walks a `verifyKey =
        // listOfNotNull(decryptKey, legacyVerifyKey)` list where `decryptKey` is the
        // album NodeKey and `legacyVerifyKey` is the address key. A blob signed by
        // the address key (our previous behaviour) is still verifiable via the
        // legacy fallback, but Drive web's stricter "Missing signature for hash key"
        // detail dialog only surfaces a clean check when the primary signer matches
        // the album NodeKey. Switching the signer to the album NodeKey itself puts
        // every new album back on the modern verification path.
        val hashKeyBytes = cryptoHelper.withCryptoLock {
            cryptoContext.pgpCrypto.generateRandomBytes(32)
        }
        val nodeHashKey = cryptoHelper.encryptAndSignDataToPgpMessage(
            hashKeyBytes,
            albumNodeKey.publicKeyArmored,
            albumNodeKey.unlockedKeyBytes,
        )
        // Album's own name hash is computed with the PARENT's NodeHashKey because albums are
        // direct children of root — their name hash space lives under root. Computing it with
        // hashKeyBytes (the album's own children-key) puts the hash in the wrong hash-space
        // (children-of-album rather than children-of-root), so a later rename fails with "out
        // of date" because OriginalHash doesn't line up with what the server expects for a
        // root-child rename.
        val rootNodeHashKey = shareService.rootNodeHashKeyBytes()
            ?: error("createDriveAlbum: rootNodeHashKey unavailable")
        val nameHash = cryptoHelper.computeNameHash(name, rootNodeHashKey)

        // xAttr — Drive Android's CreateFolderInfo always emits an encrypted +
        // signed XAttr blob on album-create (tempandroid-drive
        // .../CreateFolderInfo.kt:83 calls encryptAndSignXAttr unconditionally).
        // Drive web treats it as optional on the wire but expects the full
        // signature chain anchored at the album to be intact on shared-with-me
        // reads. Sending null left a hole in that chain and Drive web's
        // strict node-decrypt verifier refused every photo nested under the
        // album because the trust path did not anchor cleanly. Mirror Drive
        // Android: write an empty-media xAttr signed by the same signingKey
        // used for the rest of the album.
        // Side-by-side wire comparison with a Drive-web-created album (Drive web
        // GET response: `"type":"album", ...` with NO `"folder"` sub-object) vs our
        // album (same shape but with `"folder":{"claimedModificationTime":"..."}`)
        // shows that Drive web SKIPS the XAttr field entirely on album-create.
        // Sending an XAttr makes the backend stamp the album as a hybrid folder+album,
        // and the recipient verifier walks the folder-shaped trust chain instead of
        // the album-shaped one — every per-photo decrypt then cascades into "Failed
        // to decrypt node". Match Drive web byte-for-byte: omit the XAttr.
        val albumXAttr: String? = null

        val manager = apiProvider.get<DriveApiService>(userId)

        // API body: {"Locked": false, "Link": {"Name": ..., "Hash": ..., "NodeKey": ..., ...}}
        val response = semaphore.withPermit {
            manager.invoke {
                createAlbum(
                    volumeId,
                    CreateAlbumRequest(
                        locked = false,
                        link   = CreateAlbumLinkData(
                            name                    = encryptedName,
                            hash                    = nameHash,
                            nodeKey                 = albumNodeKey.armoredPrivateKey,
                            nodePassphrase          = nodePassphraseEncrypted,
                            nodePassphraseSignature = nodePassphraseSignature,
                            signatureEmail          = signingKey.email,
                            nodeHashKey             = nodeHashKey,
                            xAttr                   = albumXAttr,
                        ),
                    ),
                )
            }.valueOrThrow
        }
        // Response: {"Album": {"Link": {"LinkID": "..."}}, "Code": 1000}
        val newLinkId = response.album.link.linkId
        Log.d(TAG, "createDriveAlbum: created album linkId=$newLinkId name=$name")
        Album(
            linkId             = newLinkId,
            name               = name,   // plaintext for immediate display
            photoCount         = 0,
            coverLinkId        = null,
            lastActivityTimeMs = null,
        )
    }

    suspend fun loadAlbumChildren(userId: UserId, albumLinkId: String): List<AlbumChild> = withContext(Dispatchers.IO) {
        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)
        val children = mutableListOf<AlbumChild>()
        var anchorId: String? = null

        do {
            val response = semaphore.withPermit {
                manager.invoke { getAlbumChildren(volumeId, albumLinkId, anchorId) }.valueOrThrow
            }
            response.photos.mapTo(children) { dto ->
                AlbumChild(
                    linkId = dto.linkId,
                    captureTime = dto.captureTime,
                    addedTime = dto.addedTime,
                )
            }
            anchorId = if (response.more) response.anchorId else null
        } while (anchorId != null)

        children
    }

    /**
     * Read-only fast path: returns whatever the local DB has for this album, in capture-time
     * order. Used by [AlbumDetailViewModel.load] to render the grid INSTANTLY on subsequent
     * opens without waiting for the full 5-round-trip network refresh. Empty for albums
     * never opened before, or for pre-v5 legacy rows where parentLinkId is null.
     */
    suspend fun loadAlbumPhotosCached(albumLinkId: String): List<CloudPhoto> = withContext(Dispatchers.IO) {
        // Album → photos is many-to-many: an owned photo physically lives in the photos-root
        // folder on Drive and the album is just a reference list, so its photo_listing row is
        // parented to the root. Only a shared-with-me album's rows are parented to the album
        // itself. The membership table is the one lookup that covers both shapes; a
        // parent-based query would miss every owned photo.
        val photoLinkIds = albumPhotoMembershipDao.getPhotoLinkIds(albumLinkId)
        if (photoLinkIds.isEmpty()) return@withContext emptyList()
        // Chunked: an album's membership is unbounded, and one oversized IN list would fail the whole
        // read, leaving the cached paint empty for exactly the albums that most need it.
        val rowsByLinkId = photoLinkIds.flatMapSqlChunks { photoListingDao.getByLinkIds(it) }
            .associateBy { it.linkId }
        // The final ordering is captureTime DESC (with linkId tie-break) applied at the
        // end of this function. The membership-table walk below only resolves rows; the
        // single sort site keeps the cache paint and the network refresh in agreement.
        val thumbnailCacheDir = File(context.cacheDir, "thumbnails")
        photoLinkIds.mapNotNull { linkId ->
            val entity = rowsByLinkId[linkId] ?: return@mapNotNull null
            val photo = entity.toDomain()
            // Prefer the on-disk decrypted JPG whenever it exists. Without this the
            // album tile is blank even when the same photo's thumb_<linkId>.jpg is
            // already there from a prior gallery view. Three cases handled:
            //   1. DB column null but disk file present -> wire it up.
            //   2. DB column file:// whose backing file is gone -> null it so the
            //      decrypt scheduler picks the row up on the next online refresh.
            //   3. DB column is a CDN URL with a valid disk file too -> prefer disk.
            val cachedJpg = File(thumbnailCacheDir, "thumb_${photo.linkId}.jpg")
            val diskHit = cachedJpg.exists() && cachedJpg.length() > 0L
            val currentUrl = photo.thumbnailUrl
            val resolvedUrl = when {
                diskHit -> "file://${cachedJpg.absolutePath}"
                currentUrl != null && !currentUrl.startsWith("file://") -> currentUrl
                else -> null
            }
            if (resolvedUrl == currentUrl) photo else photo.copy(thumbnailUrl = resolvedUrl)
        }
            // Match Drive web UI: captureTime DESC. Same order [loadAlbumPhotos] returns
            // after the network refresh, so the cache paint and the final paint agree.
            .sortedWith(photoOrder)
    }

    /**
     * @param onLinkIdsResolved fired once we know the album's child link IDs (after the
     *  initial paginated children fetch but BEFORE the chunked metadata + thumbnail-info
     *  work). Lets the caller flip its loading-skeleton off and start observing the DB
     *  rows for these linkIds so chunked upserts trickle into the UI as they land.
     */
    suspend fun loadAlbumPhotos(
        userId: UserId,
        albumLinkId: String,
        volumeId: String?,
        sharingShareId: String? = null,
        onLinkIdsResolved: ((List<String>) -> Unit)? = null,
    ): List<CloudPhoto> = withContext(Dispatchers.IO) {
        val resolvedVolumeId = volumeId ?: shareService.getVolumeId(userId)
        val shareId = shareService.getShareId(userId, resolvedVolumeId)
        val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId)
        val thumbnailCacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }

        // For a shared-with-me album the user doesn't hold the owner's photos root link
        // key — they unlock the album via the share they accepted. Bootstrap the share
        // and decrypt the share's private key bytes; everything downstream
        // (NodePassphrase decrypt, thumbnail key resolution) then uses these in place
        // of `rootLinkKeyBytes`.
        val sharedAlbumParentKeyBytes: ByteArray? = sharingShareId?.let { ssid ->
            runCatching {
                val mgr = apiProvider.get<DriveApiService>(userId)
                val bs = semaphore.withPermit {
                    mgr.invoke { getShareBootstrap(ssid) }.valueOrThrow
                }
                val key = bs.key ?: return@let null
                val pp = bs.passphrase ?: return@let null
                cryptoHelper.decryptExternalShareKey(userId, key, pp)
            }.onFailure {
                Log.w(TAG, "loadAlbumPhotos: shared-album share key decrypt failed for $albumLinkId: ${it.message}")
            }.getOrNull()
        }
        val parentKeyBytes = sharedAlbumParentKeyBytes ?: rootLinkKeyBytes

        // Photos inside an album have NodePassphrase/Name encrypted to the ALBUM's key, not root link's.
        // Centralised through [AlbumCryptoChain.decryptAlbumKey] so the error policy stays consistent
        // with every other album-key decrypt site (recipient bootstrap, save-to-library, the scheduler
        // fallback). The chain logs at Warn with the context hint and returns null on any failure.
        val albumKeyBytes: ByteArray? = if (parentKeyBytes != null) {
            val albumDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, resolvedVolumeId, listOf(albumLinkId))
            val albumLink = albumDetailMap[albumLinkId]?.link
            if (albumLink?.nodeKey != null && albumLink.nodePassphrase != null) {
                albumCryptoChain.decryptAlbumKey(
                    nodeKeyArmored = albumLink.nodeKey,
                    nodePassphraseArmored = albumLink.nodePassphrase,
                    parentKeyBytes = parentKeyBytes,
                    contextHint = "loadAlbumPhotos albumLinkId=$albumLinkId sharedAlbum=${sharingShareId != null}",
                )?.also {
                    Log.d(TAG, "loadAlbumPhotos: albumKey decrypted OK for $albumLinkId (sharedAlbum=${sharingShareId != null})")
                }
            } else {
                Log.w(TAG, "loadAlbumPhotos: albumLink missing nodeKey/nodePassphrase for $albumLinkId")
                null
            }
        } else null
        // Shared-with-me album whose key never decrypted: the recipient doesn't hold the
        // root link key the fallback used, so every per-photo passphrase/name decrypt below
        // would silently fail and render a blank grid with no error or retry. Surface the
        // same recoverable failure path the rest of the load uses (thrown → ViewModel banner
        // + refresh()) instead of returning silently-empty rows.
        if (sharingShareId != null && albumKeyBytes == null) {
            error("loadAlbumPhotos: shared-album key unavailable for $albumLinkId")
        }
        // The recipient-side crypto bundle for this album, resolved once. The thumbnail scheduler's
        // cache-miss fallback, the video-duration pass, and every per-photo parent-key selection in the
        // chunk loop below all want the same share id + key pair, so building it here keeps the "is the
        // share resolved yet" question single-valued instead of re-asked per photo.
        val sharingContext = if (
            sharingShareId != null && albumKeyBytes != null && sharedAlbumParentKeyBytes != null
        ) {
            AlbumCryptoChain.SharingContext(
                albumLinkId = albumLinkId,
                sharingShareId = sharingShareId,
                sharedShareKeyBytes = sharedAlbumParentKeyBytes,
                albumKeyBytes = albumKeyBytes,
            )
        } else null

        // Seed the lazy-thumbnail scheduler with the album key we just decrypted, so cells
        // scrolling into view skip the per-photo album-key resolution round trip.
        // For shared-with-me albums we also seed a SharingContext so the scheduler's
        // cache-miss fallback knows to re-fetch via the share endpoint (and re-decrypt
        // with the share key bytes) instead of the recipient's own volume — without that
        // hook a long backgrounded session would lose the in-memory cache entry and the
        // grid would silently regress to placeholders after process restart.
        if (sharingContext != null) {
            // The store is what every other reader of this album asks, notably the full-resolution
            // download, whose own link fetch sees a null parent for these photos and so has nothing
            // to resolve the album from on its own.
            sharedAlbumKeyStore.put(userId, sharingContext)
            thumbnailDecryptScheduler.populateSharedAlbumContext(sharingContext)
        } else if (albumKeyBytes != null) {
            thumbnailDecryptScheduler.populateParentKeys(mapOf(albumLinkId to albumKeyBytes))
        }

        // Fetch album children using the resolved volumeId (may be an external share's volume)
        val manager = apiProvider.get<DriveApiService>(userId)
        val children = mutableListOf<AlbumChild>()
        var anchor: String? = null
        do {
            val resp = semaphore.withPermit {
                manager.invoke { getAlbumChildren(resolvedVolumeId, albumLinkId, anchor) }.valueOrThrow
            }
            resp.photos.mapTo(children) { dto ->
                AlbumChild(
                    linkId = dto.linkId,
                    captureTime = dto.captureTime,
                    addedTime = dto.addedTime,
                    isChildOfAlbum = dto.isChildOfAlbum,
                )
            }
            anchor = if (resp.more) resp.anchorId else null
        } while (anchor != null)

        // Notify the caller so it can drop its skeleton and start observing the DB
        // rows for these linkIds — chunked upserts below will trickle in via the Flow.
        onLinkIdsResolved?.invoke(children.map { it.linkId }.distinct())

        // Persist album → photos membership FIRST, before the slow per-photo crypto loop.
        // The membership is the only piece loadAlbumPhotosCached needs to enumerate the
        // grid offline; doing it here means a partially-completed online load (user goes
        // offline mid-decrypt) still leaves the album's photo list reachable next time.
        // The photo entities themselves are written chunk-by-chunk below, so even a
        // partial decrypt produces a partial-but-valid offline grid.
        runCatching {
            albumPhotoMembershipDao.replaceAllForAlbum(
                albumLinkId,
                children.map { it.linkId }.distinct(),
            )
        }.onFailure { Log.w(TAG, "loadAlbumPhotos: membership upsert failed: ${it.message}") }

        // Bulk-read every already-cached row up front (chunked — SQLite's bind-variable
        // limit is 999 on older Android releases). A complete row — full key material,
        // and for shared albums the recipient-side parent pin — needs no metadata fetch
        // and no rebuild, so the batch round-trips below shrink to just the photos this
        // device has never processed. Re-opening a fully-cached album costs zero
        // per-photo network work.
        val childIds = children.map { it.linkId }.distinct()
        val cachedRows = HashMap<String, PhotoListingEntity>(childIds.size)
        for (idChunk in childIds.chunked(500)) {
            photoListingDao.getByLinkIds(idChunk).forEach { cachedRows[it.linkId] = it }
        }
        fun cachedUsable(e: PhotoListingEntity?): Boolean = e != null &&
            !e.encNodeKey.isNullOrBlank() &&
            !e.encNodePassphrase.isNullOrBlank() &&
            !e.contentKeyPacket.isNullOrBlank() &&
            // A row without the recipient-side pin may be the owner's view from a previous
            // session under the same SQLite file; its parentLinkId then points at the
            // owner's photos root, a linkId the recipient has no key for, and the
            // thumbnail scheduler would dead-end at placeholder cells. Rebuild those.
            (sharingShareId == null || e.parentLinkId == albumLinkId)
        val missingIds = childIds.filter { !cachedUsable(cachedRows[it]) }

        // Three independent batch round-trips drive the rest of the load, scoped to the
        // photos that actually need processing:
        //   • linkDetailMap — full link metadata (parent / passphrase / name)
        //   • thumbnailUrlMap — CDN URLs for each Type-2 thumbnail (needs linkDetailMap)
        //   • ckpMap — content-key packets for thumbnail decryption (own endpoint)
        // ckpMap is fully independent of the other two, so we launch it concurrently
        // with the link-detail fetch and only join at the chunk loop. The share id we
        // use here is the per-album share id when we have one (covers the OWNER's photo
        // linkIds the recipient inherited) and falls back to our own primary share id
        // for owner-side album opens.
        val ckpFetchShareId = sharingShareId ?: shareId
        val (linkDetailMap, ckpMap) = coroutineScope {
            // For shared-with-me albums the per-photo Size / Type / fileProperties
            // come back blank from the photos volume endpoint because the recipient
            // isn't a direct member of the owner's volume — go through the share
            // endpoint instead. The share-endpoint response carries the same data
            // the volume endpoint does for owners (thumbnail IDs included), so the
            // downstream thumbnail-url / entity-build pipeline keeps working.
            val linkDetailDeferred = async {
                when {
                    missingIds.isEmpty() -> emptyMap()
                    sharingShareId != null ->
                        linkDetailHelpers.batchFetchLinkDetailsViaShare(userId, sharingShareId, missingIds)
                    else -> linkDetailHelpers.batchFetchLinkDetails(userId, resolvedVolumeId, missingIds)
                }
            }
            val ckpDeferred = async {
                if (missingIds.isEmpty()) emptyMap()
                else linkDetailHelpers.batchFetchContentKeyPackets(userId, ckpFetchShareId, missingIds)
            }
            Pair(linkDetailDeferred.await(), ckpDeferred.await())
        }

        // Build thumbnail ID → linkId map and batch-fetch download URLs.
        val thumbnailIdToLinkId = mutableMapOf<String, String>()
        for ((linkId, detail) in linkDetailMap) {
            val thumbnailList = detail.link.fileProperties?.activeRevision?.thumbnails
                ?: detail.photo?.activeRevision?.thumbnails
            // Prefer Type 2 (HD, ~512px+) over Type 1 (~200px) so the album grid is crisp.
            val tid = thumbnailList?.firstOrNull { it.type == 2 }?.thumbnailId
                ?: thumbnailList?.firstOrNull { it.type == 1 }?.thumbnailId
                ?: thumbnailList?.firstOrNull()?.thumbnailId
            if (tid != null) thumbnailIdToLinkId[tid] = linkId
        }
        val thumbnailUrlMap = if (thumbnailIdToLinkId.isNotEmpty())
            linkDetailHelpers.batchFetchThumbnailUrls(userId, resolvedVolumeId, thumbnailIdToLinkId.keys.toList())
        else emptyMap()
        Log.d(TAG, "loadAlbumPhotos: fetched ${thumbnailUrlMap.size} thumbnail URLs for album $albumLinkId")
        val ownPublicKeys = cryptoHelper.getOwnPublicKeysArmored(userId)

        // Parent-side answer to "does this photo live only inside an album", used wherever the
        // listing left IsChildOfAlbum unset. The album being opened joins the cached set because
        // this call is the proof it is one, which covers an album opened before its own row landed.
        val albumLinkIds = runCatching { cloudAlbumDao.getAllLinkIds().toSet() }
            .getOrElse {
                Log.w(TAG, "loadAlbumPhotos: cached album ids unavailable: ${it.message}")
                emptySet()
            } + albumLinkId

        // Deduplicate by linkId in case the same photo was added to the album multiple times.
        val uniqueChildren = children.distinctBy { it.linkId }
        if (uniqueChildren.size != children.size) {
            Log.w(TAG, "loadAlbumPhotos: removed ${children.size - uniqueChildren.size} duplicate linkIds")
        }

        // Build result in chunks (same pattern as PhotoStreamService.refreshCloudPhotos): batch
        // the DB lookups, run crypto for misses chunk-by-chunk, persist after each chunk + yield.
        // For users opening an album of a few hundred photos this drops the user-perceived
        // "loading…" time from N×crypto-sequential to one chunk's worth (~1s), since the rest
        // streams in via observePhotosByLinkIds while the user is already looking at the first
        // tiles. Also makes the work cooperative with the GC, mirroring the fix that stopped
        // the Android 16 BETA SIGABRT on Samsung BP2A firmware.
        //
        // Same chunk-size + delay tuning as PhotoStreamService.refreshCloudPhotos:
        // 10 photos per chunk + a 100ms breather lets the ART CMC GC run cleanly between
        // bursts of Go crypto calls.
        val result = mutableListOf<CloudPhoto>()
        // Small chunks with a short cooperative breath. The per-chunk crypto on the
        // lazy path is light (no thumbnail decrypt, just name + passphrase verify), so
        // a 100ms delay is unnecessary; 30ms still lets the ART CMC GC land a cycle
        // between bursts on Android 16, which is the only case the delay protects.
        val chunkSize = 10
        val interChunkDelayMs = 30L
        for (chunk in uniqueChildren.chunked(chunkSize)) {
            val chunkNewEntities = mutableListOf<PhotoListingEntity>()
            for (child in chunk) {
                val cached = cachedRows[child.linkId]
                // Fast path: a photo already in the DB skips the rebuild entirely. Even
                // with its thumbnail not yet decrypted (thumbnailUrl null) the row still
                // carries every encrypted input the scheduler needs, so rebuilding it
                // would only overwrite identical material and re-incur the per-chunk
                // delay — the main reason a second open ever feels slow. When the cached
                // row still lacks its decrypted thumbnail, re-issue the scheduler request
                // here: on shared albums the cell-mount request path races the Flow
                // emission and can otherwise miss the nudge.
                if (cachedUsable(cached)) {
                    if (cached!!.thumbnailUrl == null && !cached.serverThumbnailUrl.isNullOrBlank()) {
                        thumbnailDecryptScheduler.request(userId, cached)
                    }
                    result.add(cached.toDomain())
                    continue
                }

                val stub = PhotoLinkDto(
                    linkId = child.linkId,
                    captureTime = child.captureTime ?: 0L,
                )
                val thumbnailInfo = thumbnailIdToLinkId.entries
                    .firstOrNull { it.value == child.linkId }
                    ?.key?.let { thumbnailUrlMap[it] }

                // Photo parent-key selection is centralised in [AlbumCryptoChain.selectPhotoParentKey].
                // The rule that matters here: after addPhotosToAlbum runs, every photo's wire
                // NodePassphrase is encrypted to the ALBUM NodeKey even when the photo's wire
                // parentLinkId still points at the photos root (the photo isn't physically moved —
                // only the passphrase wrapping changes). The helper prefers the album key first,
                // falls back to root only for legacy pre-rewrap data, and pins to album for any
                // shared-with-me path since the recipient has no useful root key.
                val photoParentLinkId = linkDetailMap[child.linkId]?.link?.parentLinkId
                val photoParentKeyBytes = albumCryptoChain.selectPhotoParentKey(
                    rootLinkKeyBytes = rootLinkKeyBytes,
                    albumKeyBytes = albumKeyBytes,
                    photoParentLinkId = photoParentLinkId,
                    photosRootLinkId = shareService.photosRootLinkId(),
                    sharingContext = sharingContext,
                )

                // Lazy-thumbnail: album opens were the OTHER big libgojni
                // burst path. Album of 200 photos = 200 sequential thumbnail decrypts
                // back-to-back. With decryptThumbnail=false the grid populates with
                // placeholders within a chunk's worth of crypto and the
                // [ThumbnailDecryptScheduler] fills them in as cells scroll.
                val builtEntity = photoEntityBuilder.build(
                    stub = stub,
                    detail = linkDetailMap[child.linkId],
                    userId = userId,
                    shareId = shareId,
                    volumeId = resolvedVolumeId,
                    parentKeyBytes = photoParentKeyBytes,
                    thumbnailCacheDir = thumbnailCacheDir,
                    thumbnailInfo = thumbnailInfo,
                    contentKeyPacket = ckpMap[child.linkId],
                    ownPublicKeys = ownPublicKeys,
                    decryptThumbnail = false,
                    // Shared-with-me photos have their Name PKESK substituted to the
                    // share's encryption subkey, not the album NodeKey. Offer the
                    // share private key bytes as a second candidate so the photo
                    // Name actually decrypts in the Details sheet.
                    fallbackParentKeyBytes = sharedAlbumParentKeyBytes,
                    albumLinkIds = albumLinkIds,
                    knownChildOfAlbum = child.isChildOfAlbum,
                    photosRootLinkId = shareService.photosRootLinkId(),
                )
                // Shared-with-me album: pin every photo's persisted parentLinkId to the
                // album linkId, regardless of what the share endpoint returns on the wire.
                // The scheduler's parent-key cache is keyed by parentLinkId, and the only
                // value we have for these photos is the album NodeKey we just decrypted.
                // If the backend's `fetch_metadata` response carries a non-empty
                // parentLinkId pointing at the owner's photos root (a linkId the recipient
                // has no key for), the scheduler's cache-miss fallback would head to the
                // wrong endpoint and the grid would stay at placeholders. The override
                // also keeps the on-disk row stable across cache evictions and process
                // restarts so the same lookup path keeps working on later opens.
                //
                // isChildOfAlbum follows that pin: a row reachable only through an album someone
                // shared belongs to the album alone, so the timeline must not claim it either.
                val entity = if (sharingShareId != null) {
                    builtEntity.copy(parentLinkId = albumLinkId, isChildOfAlbum = true)
                } else {
                    builtEntity
                }
                chunkNewEntities.add(entity)
                result.add(entity.toDomain())
            }
            // Persist this chunk so the observePhotosByLinkIds Flow can emit it to the UI
            // before the next chunk's crypto work begins.
            if (chunkNewEntities.isNotEmpty()) {
                try {
                    photoListingDao.upsertAll(chunkNewEntities)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "loadAlbumPhotos: DB upsert failed for chunk: ${e.message}")
                }
                // Proactively kick the thumbnail-decrypt scheduler for every just-
                // inserted row with valid crypto material. Without this nudge the
                // grid stays at placeholder cells on first album-open and only
                // fills after the user manually pulls to refresh — the Compose
                // cell `LaunchedEffect(linkId)` only fires when its row arrives
                // visible, and on a shared-with-me album the path to `request()`
                // races the Flow emission. Triggering decrypt at row-insert time
                // closes the gap: every photo's request lands as soon as the
                // entity is durable, regardless of whether the cell has mounted.
                for (entity in chunkNewEntities) {
                    if (entity.thumbnailUrl != null) continue
                    if (entity.encNodeKey.isNullOrBlank()) continue
                    if (entity.encNodePassphrase.isNullOrBlank()) continue
                    if (entity.contentKeyPacket.isNullOrBlank()) continue
                    if (entity.serverThumbnailUrl.isNullOrBlank()) continue
                    thumbnailDecryptScheduler.request(userId, entity)
                }
            }
            kotlinx.coroutines.yield()
            // The inter-chunk breather exists to let the GC land between crypto bursts;
            // an all-cached chunk ran no crypto, so it skips the delay.
            if (chunkNewEntities.isNotEmpty()) kotlinx.coroutines.delay(interChunkDelayMs)
        }

        // The album's rows are durable by here, which is what lets the video-duration pass find them on
        // its first query. The bundle that unlocked the album is also the only thing that unlocks the
        // LENGTH of its videos: such a row keeps the owner's volumeId, so the volume-scoped duration
        // walk cannot reach it and its grid tile carries no length pill until this pass fills the value
        // in. The pass runs on the scheduler's own scope, so nothing here waits on it.
        if (sharingContext != null) {
            videoDurationBackfillScheduler.populateSharedAlbumContext(userId, sharingContext)
        }

        // Match Drive web UI: captureTime DESC. Drive's pagination order is addedTime
        // (insertion order), not captureTime, so we sort here. Cache + observe phases
        // do the same on their side so the user never sees a reorder when the network
        // refresh settles.
        result.sortedWith(photoOrder)
    }

    suspend fun addPhotosToAlbum(
        userId: UserId,
        albumLinkId: String,
        photoLinkIds: List<String>,
    ): DrivePhotoRepository.AddPhotosToAlbumResult = withContext(Dispatchers.IO) {
        if (photoLinkIds.isEmpty()) {
            return@withContext DrivePhotoRepository.AddPhotosToAlbumResult(emptyList(), emptyList())
        }
        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)

        // 1. Ensure root link key is available
        val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId)
            ?: error("addPhotosToAlbum: cannot load root link key")

        // 2. Fetch album link to get its NodeKey, NodePassphrase, and NodeHashKey
        val albumDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(albumLinkId))
        val albumDto = albumDetailMap[albumLinkId]
            ?: error("addPhotosToAlbum: album link not found: $albumLinkId")
        val albumLinkDto = albumDto.link
        val albumNodeKeyArmored = albumLinkDto.nodeKey
            ?: error("addPhotosToAlbum: album has no NodeKey")
        val albumNodePassphraseArmored = albumLinkDto.nodePassphrase
            ?: error("addPhotosToAlbum: album has no NodePassphrase")

        // Decrypt the album's private key (to decrypt its NodeHashKey). Funnels
        // through the chain helper so the error policy stays consistent with
        // every other album-key decrypt site — Warn-level log + return null,
        // then we error out with a clear message instead of swallowing.
        val albumKeyBytes = albumCryptoChain.decryptAlbumKey(
            nodeKeyArmored = albumNodeKeyArmored,
            nodePassphraseArmored = albumNodePassphraseArmored,
            parentKeyBytes = rootLinkKeyBytes,
            contextHint = "addPhotosToAlbum albumLinkId=$albumLinkId",
        ) ?: error("addPhotosToAlbum: album key decrypt failed for $albumLinkId — see AlbumCryptoChain log")
        // Extract album's public key — used to re-encrypt photo passphrases and names TO the album
        val albumPublicKeyArmored = cryptoHelper.withCryptoLock {
            cryptoContext.pgpCrypto.getPublicKey(albumNodeKeyArmored)
        }
        Log.d(TAG, "addPhotosToAlbum: albumKey OK, encrypting ${photoLinkIds.size} photos to album=$albumLinkId pubKeyLen=${albumPublicKeyArmored.length}")

        // Decrypt the album's NodeHashKey (symmetric key for HMAC-SHA256 name hashing)
        val albumNodeHashKeyEncrypted = albumDto.album?.nodeHashKey
            ?: error("addPhotosToAlbum: album has no NodeHashKey in AlbumMeta")
        val albumNodeHashKeyBytes = cryptoHelper.withCryptoLock {
            cryptoContext.pgpCrypto.decryptData(albumNodeHashKeyEncrypted, albumKeyBytes)
        }

        // 3. Address signing key (for signing re-encrypted names)
        val signingKey = cryptoHelper.getAddressSigningKey(userId)

        // 4. Fetch photo link details in chunks
        val photoDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, photoLinkIds)

        // 5. Build AddAlbumMultipleEntry for each photo (PGP re-encryption)
        val albumDataEntries = mutableListOf<AddAlbumMultipleEntry>()
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (photoLinkId in photoLinkIds) {
            val photoDto = photoDetailMap[photoLinkId]
            if (photoDto == null) {
                Log.w(TAG, "addPhotosToAlbum: photo link not found: $photoLinkId")
                failed += photoLinkId
                continue
            }
            val photoLink = photoDto.link
            val photoNodeKeyArmored = photoLink.nodeKey
            val photoNodePassArmored = photoLink.nodePassphrase
            val photoNameEncrypted = photoLink.name
            if (photoNodeKeyArmored == null || photoNodePassArmored == null || photoNameEncrypted == null) {
                Log.w(TAG, "addPhotosToAlbum: missing crypto fields for $photoLinkId " +
                    "(nodeKey=${photoNodeKeyArmored != null} pass=${photoNodePassArmored != null} name=${photoNameEncrypted != null})")
                failed += photoLinkId
                continue
            }
            val contentHash = photoDto.photo?.contentHash ?: ""

            try {
                // Decrypt photo's raw passphrase bytes (currently encrypted to root link key)
                val photoPassphraseBytes = cryptoHelper.withCryptoLock {
                    cryptoContext.pgpCrypto.decryptData(photoNodePassArmored, rootLinkKeyBytes)
                }

                // Unlock photo's node key with decrypted passphrase
                val photoNodeKeyBytes = cryptoHelper.withCryptoLock {
                    cryptoContext.pgpCrypto.unlock(photoNodeKeyArmored, photoPassphraseBytes).let {
                        val b = it.value.copyOf(); it.close(); b
                    }
                }

                // Decrypt plaintext file name. Link.Name is encrypted to the
                // PARENT key (photos-root for stream photos) — the official
                // client's DecryptLinkName resolves the parent key, never the
                // photo's own node key. The node-key attempt stays as a
                // fallback for blobs produced by earlier builds of ours; the
                // placeholder is the last resort so a single undecryptable
                // name cannot sink the whole batch.
                val plainNameFromRoot = cryptoHelper.decryptLinkName(photoNameEncrypted, rootLinkKeyBytes)
                val plainName = plainNameFromRoot
                    ?: cryptoHelper.decryptLinkName(photoNameEncrypted, photoNodeKeyBytes)
                    ?: "photo_$photoLinkId"

                // Re-encrypt the photo's passphrase TO the album's public key AND
                // detached-sign the raw passphrase bytes with the user's address key.
                // The detached signature is ALWAYS computed because we need it for
                // the anonymous-source branch; whether we actually wire it onto the
                // request depends on the source link's signature state below.
                val reencryptedPass = cryptoHelper.reencryptNodePassphraseForCopy(
                    sourceNodePassphraseArmored = photoNodePassArmored,
                    sourceParentKeyBytes        = rootLinkKeyBytes,
                    targetParentPublicKeyArmored = albumPublicKeyArmored,
                    signerKeyBytes              = signingKey.unlockedKeyBytes,
                )
                val newNodePassphrase = reencryptedPass.armoredPassphrase

                // Re-target the Name to the album key the way Drive Android's
                // ChangeMessage does: SAME session key as the original blob,
                // fresh embedded signature by the address key, new PKESK to
                // the album. Falls back to a fresh-session-key encrypt when
                // the source blob's session key is not extractable (only
                // legacy uploads whose Name was not parent-encrypted).
                val newName = if (plainNameFromRoot != null) {
                    cryptoHelper.changeNameRecipient(
                        oldNameArmored          = photoNameEncrypted,
                        oldDecryptKeyBytes      = rootLinkKeyBytes,
                        newPlaintextName        = plainName,
                        targetPublicKeyArmored  = albumPublicKeyArmored,
                        signerKeyBytes          = signingKey.unlockedKeyBytes,
                    )
                } else {
                    cryptoHelper.encryptName(plainName, albumPublicKeyArmored, signingKey.unlockedKeyBytes)
                }

                // HMAC-SHA256 of plaintext name using album's NodeHashKey
                val nameHash = cryptoHelper.computeNameHash(plainName, albumNodeHashKeyBytes)

                // Drive Android `Link.signatureEmail()` / `Link.nodePassphraseSignature()`
                // extensions return null when the source link is already owned (its
                // signatureEmail is non-empty). The backend enforces this:
                // "A NodePassphraseSignature and a SignatureEmail are required only
                // when moving anonymous Links". Sending them on an owned source is a
                // hard reject — every photo gets dropped from the batch with that
                // exact error text.
                // Drive Android's Link.signatureEmail()/nodePassphraseSignature()
                // (tempandroid-drive .../Link.kt:30-44) gate on
                // `signatureEmail.isEmpty() || nameSignatureEmail.isNullOrEmpty()`.
                // Both fields must be checked: a source photo with a present
                // signatureEmail but a missing nameSignatureEmail (some legacy
                // upload paths) still counts as anonymous, so it needs a fresh
                // signature on the re-wrapped passphrase or the recipient refuses
                // to verify the photo NodeKey decrypt chain. Match Drive Android's
                // predicate exactly so every consumer agrees.
                val sourceMissingSignature = photoLink.signatureEmail.orEmpty().isEmpty() ||
                    photoLink.nameSignatureEmail.orEmpty().isEmpty()
                val nodePassphraseSigToSend = if (sourceMissingSignature) reencryptedPass.armoredSignature else null
                val signatureEmailToSend = if (sourceMissingSignature) signingKey.email else null

                albumDataEntries += AddAlbumMultipleEntry(
                    linkId                  = photoLinkId,
                    hash                    = nameHash,
                    name                    = newName,
                    nodePassphrase          = newNodePassphrase,
                    nameSignatureEmail      = signingKey.email,
                    contentHash             = contentHash,
                    nodePassphraseSignature = nodePassphraseSigToSend,
                    signatureEmail          = signatureEmailToSend,
                )
                succeeded += photoLinkId
                Log.d(TAG, "addPhotosToAlbum: prepared entry for $photoLinkId ('$plainName')")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "addPhotosToAlbum: crypto failed for $photoLinkId: ${e.message}", e)
                failed += photoLinkId
            }
        }

        if (albumDataEntries.isEmpty()) {
            // Throw on empty so the caller's runCatching surfaces total crypto failure
            // instead of "0 added, all good".
            Log.w(TAG, "addPhotosToAlbum: no valid entries built — every photo failed crypto")
            error("addPhotosToAlbum: all ${photoLinkIds.size} photos failed crypto preparation")
        }

        // 6. POST in chunks of ALBUM_LINK_BATCH_MAX. The backend acks the BATCH with top-level Code=1000
        //    even when individual entries fail their own validation, so the per-photo
        //    response array is the only source of truth for what actually landed.
        //    Move every photo the backend rejected from `succeeded` into `failed` so
        //    the UI snackbar and downstream cache writes match reality.
        val rejectedByLink = mutableMapOf<String, String>() // linkId → error
        albumDataEntries.chunked(ALBUM_LINK_BATCH_MAX).forEach { chunk ->
            // An uncaught throw here would unwind the whole call, discarding chunks that already
            // landed so the caller reports nothing added while the album holds half the selection.
            // Recording the chunk as rejected keeps the earlier ones and names which photos still
            // need a retry.
            val resp = try {
                semaphore.withPermit {
                    manager.invoke {
                        addPhotosToAlbum(volumeId, albumLinkId, AddAlbumMultipleRequest(chunk))
                    }.valueOrThrow
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "addPhotosToAlbum: batch of ${chunk.size} failed: ${e.message}")
                for (entry in chunk) {
                    rejectedByLink[entry.linkId] = e.message ?: "request failed"
                }
                return@forEach
            }
            for (entry in resp.responses) {
                if (entry.response.code != 1000) {
                    rejectedByLink[entry.linkId] =
                        entry.response.error ?: "code=${entry.response.code}"
                }
            }
        }
        if (rejectedByLink.isNotEmpty()) {
            // Promote rejected photos from succeeded → failed and log each one so a
            // bug report includes the exact server error per photo, and the success
            // toast reflects only the photos that were actually added.
            val rejectedSet = rejectedByLink.keys
            val (kept, dropped) = succeeded.partition { it !in rejectedSet }
            succeeded.clear(); succeeded.addAll(kept)
            failed.addAll(dropped)
            for ((linkId, err) in rejectedByLink) {
                Log.w(TAG, "addPhotosToAlbum: backend rejected $linkId: $err")
            }
        }
        Log.d(TAG, "addPhotosToAlbum: added ${succeeded.size}/${photoLinkIds.size} photos to album $albumLinkId" +
            if (failed.isEmpty()) "" else " (failed: ${failed.size})")
        // Sync the local membership table so reactive consumers (Gallery's
        // "hide-photos-in-albums" filter, the album-detail cached list, etc.) see
        // the change immediately instead of waiting for the next prefetch pass.
        if (succeeded.isNotEmpty()) {
            runCatching {
                albumPhotoMembershipDao.upsertAll(
                    succeeded.map { eu.akoos.photos.data.db.entity.AlbumPhotoMembershipEntity(albumLinkId, it) }
                )
            }.onFailure { Log.w(TAG, "addPhotosToAlbum: membership upsert failed: ${it.message}") }
        }
        invalidateMembershipCache()
        DrivePhotoRepository.AddPhotosToAlbumResult(succeeded, failed)
    }

    /**
     * Deletes the album container.
     *
     * [deletePhotosToo] maps to the server's `DeleteAlbumPhotos` flag. Left false, the server
     * REFUSES the delete with [ALBUM_DELETE_DATA_LOSS_CODE] when the album holds photos that exist
     * nowhere else in the owner's library, which is what a guest's contribution looks like: it was
     * copied onto this volume parented to the album, never to the photos root. That refusal is not
     * an error to swallow, it is the server offering a choice, so it surfaces as
     * [AlbumDeleteWouldLosePhotos] for the caller to put to the user.
     */
    suspend fun deleteAlbum(
        userId: UserId,
        albumLinkId: String,
        deletePhotosToo: Boolean = false,
    ): Unit = withContext(Dispatchers.IO) {
        try {
            val volumeId = shareService.getVolumeId(userId)
            val manager = apiProvider.get<DriveApiService>(userId)
            // DELETE /drive/photos/volumes/{volumeId}/albums/{albumLinkId}?DeleteAlbumPhotos=0
            // Photos inside the album are NOT deleted; only the album container is removed.
            try {
                semaphore.withPermit {
                    manager.invoke {
                        deleteAlbum(volumeId, albumLinkId, deleteAlbumPhotos = if (deletePhotosToo) 1 else 0)
                    }.valueOrThrow
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isAlbumDeleteDataLoss(e)) throw AlbumDeleteWouldLosePhotos(albumLinkId)
                throw e
            }
            // Drop the local rows too, otherwise loadAlbumsCached repaints the dead
            // album on the next cold start. Shares leaveSharedAlbum's cleanup, which leaves an
            // owned album's photos alone and only clears them for one shared with this user.
            albumCacheCleanup.dropCachedAlbum(userId, albumLinkId)
            invalidateMembershipCache()
            Log.d(TAG, "deleteAlbum: deleted albumLinkId=$albumLinkId")
        } catch (e: DriveNotFoundException) {
            // Already gone server-side — still wipe local cache so the grid doesn't
            // keep showing a phantom album entry.
            Log.w(TAG, "deleteAlbum: DriveNotFoundException: ${e.message}")
            albumCacheCleanup.dropCachedAlbum(userId, albumLinkId)
            invalidateMembershipCache()
        }
    }

    /**
     * Whether [e] is the server saying an album delete would destroy photos held nowhere else.
     *
     * Matched on Proton's own error code rather than the HTTP status, because the status is a plain
     * 422 that a dozen unrelated validation failures also produce.
     */
    private fun isAlbumDeleteDataLoss(e: Throwable): Boolean {
        val http = (e as? me.proton.core.network.domain.ApiException)?.error
            as? me.proton.core.network.domain.ApiResult.Error.Http ?: return false
        return http.proton?.code == ALBUM_DELETE_DATA_LOSS_CODE
    }

    /**
     * Removes the album reference for each [photoLinkIds] — photos themselves stay in Photos
     * root. Mirrors `addPhotosToAlbum`'s chunking (50 per POST) so a request that's too large
     * doesn't hit the API limit. Returns the linkIds the server confirmed; chunks that fail
     * are logged and excluded so the UI can still react to partial success.
     */
    /**
     * Removes photos from an album's membership.
     *
     * [albumVolumeId] overrides the volume the request is addressed to, which is what an album
     * shared with this user needs: it lives on the sharer's volume, and the caller's own volume
     * holds no such album. Unlike adding, this needs no copy and no re-encryption, because dropping
     * a membership is a change inside the album's own volume and touches no photo bytes.
     */
    suspend fun removePhotosFromAlbum(
        userId: UserId,
        albumLinkId: String,
        photoLinkIds: List<String>,
        albumVolumeId: String? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        if (photoLinkIds.isEmpty()) return@withContext emptyList()
        val volumeId = albumVolumeId ?: shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)
        val removed = mutableListOf<String>()
        // The add/remove-multiple endpoints reject any request carrying more than 10 links
        // ("This collection should contain 10 elements or less"), so a larger chunk fails wholesale.
        for (chunk in photoLinkIds.chunked(ALBUM_LINK_BATCH_MAX)) {
            try {
                // Wrap the chunk so a 429 / 5xx backs off and retries (honouring any Retry-After)
                // instead of being caught below and silently dropping the whole chunk from `removed`.
                val resp = eu.akoos.photos.util.retryWithBackoff {
                    semaphore.withPermit {
                        manager.invoke {
                            removePhotosFromAlbum(volumeId, albumLinkId, RemoveFromAlbumRequest(chunk))
                        }.valueOrThrow
                    }
                }
                // remove-multiple returns a per-photo response array, exactly like add-multiple: the
                // top-level Code only means the batch was processed, so each entry's own code is the
                // truth for what actually left the album. Treating the chunk as all-or-nothing hid
                // real per-photo rejections; only the photos the server confirms leave the membership.
                val rejected = resp.responses.filter { it.response.code != 1000 }
                removed += chunk.filter { id -> rejected.none { it.linkId == id } }
                for (entry in rejected) {
                    val detail = entry.response.error ?: "code=${entry.response.code}"
                    eu.akoos.photos.util.SyncDiagnostics.log(
                        "album-remove: server rejected ${eu.akoos.photos.util.uploadLogRef(entry.linkId)} " +
                            "(${eu.akoos.photos.util.sanitizeErrorMessage(detail)})"
                    )
                    Log.w(TAG, "removePhotosFromAlbum: server rejected ${entry.linkId}: $detail")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Surface the real whole-batch rejection — the cause of the silent "0/N removed".
                // Routed through SyncDiagnostics so it survives the release build's log stripping and
                // shows up in the in-app "Copy log".
                eu.akoos.photos.util.SyncDiagnostics.log(
                    "album-remove: chunk of ${chunk.size} failed (${eu.akoos.photos.util.sanitizeErrorMessage(e.message)})"
                )
                Log.w(TAG, "removePhotosFromAlbum: chunk failed (${chunk.size} ids): ${e.message}")
            }
        }
        Log.d(TAG, "removePhotosFromAlbum: removed ${removed.size}/${photoLinkIds.size} from album $albumLinkId")
        if (removed.isNotEmpty()) {
            // Mirror the add path: keep the local membership table in step with the
            // server so reactive consumers (gallery filter, album cache) re-emit
            // immediately. Failure here is non-fatal — the prefetch will resync.
            // Chunked on `removed`, which accumulates across every ALBUM_LINK_BATCH_MAX request above
            // and so grows with the whole selection, not with one request's 10 links.
            runCatching {
                removed.forEachSqlChunk { albumPhotoMembershipDao.deleteForAlbumPhotos(albumLinkId, it) }
            }.onFailure { Log.w(TAG, "removePhotosFromAlbum: membership delete failed: ${it.message}") }
            invalidateMembershipCache()
        }
        removed
    }

    /**
     * Renames an album. The new name is re-encrypted to the root link's public key (signed by
     * the user's primary address), and a fresh HMAC-SHA256 hash is computed with the root
     * NodeHashKey. The previous hash is sent as OriginalHash for the server's
     * optimistic-concurrency check — if someone else renamed the album between our read and
     * write, the server rejects the update instead of silently overwriting.
     *
     * Throws on any crypto / API failure so the calling ViewModel can surface a meaningful
     * error rather than a silent no-op.
     */
    suspend fun renameAlbum(userId: UserId, albumLinkId: String, newName: String): Unit = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "renameAlbum: name must be non-empty" }

        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)

        // Parent of every album is the root link. Use the root key for encryption and the
        // root NodeHashKey for hash computation — albums are NOT children of other albums.
        //
        // A fresh-login session can land here with the root link key populated but the root
        // NodeHashKey still null — the batch endpoint sometimes omits the Folder DTO on the
        // initial fetch. getRootLinkKeyBytes self-heals that on cache hit, so calling it
        // unconditionally before reading rootNodeHashKey is what makes rename work on the
        // first try instead of "album already exists" / "NodeHashKey unavailable".
        val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId)
            ?: error("renameAlbum: root link key unavailable")
        val rootLinkArmored = shareService.rootLinkArmoredKey()
            ?: error("renameAlbum: root link armored key unavailable")
        val rootPublicKey = cryptoHelper.withCryptoLock {
            cryptoContext.pgpCrypto.getPublicKey(rootLinkArmored)
        }
        val rootNodeHashKey = shareService.rootNodeHashKeyBytes()
            ?: error("renameAlbum: root NodeHashKey unavailable after eager refresh")

        // Pull the album's current state so we can supply OriginalHash for the server's
        // optimistic-concurrency check.
        //
        // Why we recompute locally rather than echoing back the server's `Hash` field:
        // older app versions stored album name hashes computed with the album's own
        // NodeHashKey instead of the root's. The server's stored Hash for those albums is in a
        // different hash-space — echoing it straight back makes the server's "is this
        // OriginalHash consistent with the current root-child" check reject with "out of
        // date". Recomputing OriginalHash locally with the same rootNodeHashKey we'll use for
        // `newHash` keeps both sides in the same hash-space. Drive Web's rename works the same
        // way (it never relies on the server-echoed value).
        val albumDetail = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(albumLinkId))[albumLinkId]
            ?: error("renameAlbum: album link not found: $albumLinkId")
        val currentEncryptedName = albumDetail.link.name
            ?: error("renameAlbum: album has no encrypted Name field")
        val currentPlainName = cryptoHelper.decryptLinkName(currentEncryptedName, rootLinkKeyBytes)
            ?: error("renameAlbum: failed to decrypt current album name")
        val originalHash = cryptoHelper.computeNameHash(currentPlainName, rootNodeHashKey)

        // Encrypt + sign the new name under the CURRENT name's session key; HMAC for the lookup
        // hash; signing email goes in metadata. Every share of this album holds a NameKeyPacket
        // bound to that session key, so a fresh one would blind every recipient (#88).
        val signingKey = cryptoHelper.getAddressSigningKey(userId)
        val newEncryptedName = cryptoHelper.renameNamePreservingSessionKey(
            oldNameArmored = currentEncryptedName,
            oldDecryptKeyBytes = rootLinkKeyBytes,
            newPlaintextName = trimmed,
            parentPublicKeyArmored = rootPublicKey,
            signerKeyBytes = signingKey.unlockedKeyBytes,
        )
        val newHash = cryptoHelper.computeNameHash(trimmed, rootNodeHashKey)

        semaphore.withPermit {
            manager.invoke {
                updateAlbum(
                    volumeId, albumLinkId,
                    UpdateAlbumRequest(
                        link = UpdateAlbumLinkData(
                            name = newEncryptedName,
                            hash = newHash,
                            nameSignatureEmail = signingKey.email,
                            originalHash = originalHash,
                        ),
                    ),
                )
            }.valueOrThrow
        }
        Log.d(TAG, "renameAlbum: renamed $albumLinkId to '$trimmed'")
        invalidateMembershipCache()
    }

    /**
     * Sets the cover photo for an album. The cover linkId is sent as-is — Drive doesn't
     * validate album membership on this endpoint, so the caller is responsible for picking a
     * photo that's actually in the album (otherwise the album list shows a broken thumbnail).
     *
     * Throws on API failure.
     */
    suspend fun setAlbumCover(userId: UserId, albumLinkId: String, coverPhotoLinkId: String): Unit =
        withContext(Dispatchers.IO) {
            val volumeId = shareService.getVolumeId(userId)
            val manager = apiProvider.get<DriveApiService>(userId)
            semaphore.withPermit {
                manager.invoke {
                    updateAlbum(volumeId, albumLinkId, UpdateAlbumRequest(coverLinkId = coverPhotoLinkId))
                }.valueOrThrow
            }
            Log.d(TAG, "setAlbumCover: set $coverPhotoLinkId as cover for $albumLinkId")
        }
}
