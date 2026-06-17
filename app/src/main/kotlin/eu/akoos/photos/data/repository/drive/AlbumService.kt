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

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.AlbumChild
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.DriveNotFoundException
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AlbumSvc"


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
    private val albumCryptoChain: AlbumCryptoChain,
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
     * album's children on every download click. TTL is intentionally short (5 min) —
     * stale entries only hurt routing, not correctness, and any album mutation immediately
     * invalidates via [invalidateMembershipCache].
     */
    @Volatile private var membershipCache: Map<String, String>? = null
    @Volatile private var membershipCacheTime: Long = 0L
    private val membershipCacheTtlMs = 5 * 60 * 1000L

    /**
     * Full multi-album membership lookup: `photoLinkId → Set<albumLinkId>`. Distinct from
     * [membershipCache] which only keeps the first album per photo (used for download
     * folder routing). This map is what the PhotoViewer's "Add to album" sheet needs so it
     * can show a checkmark next to every album the current photo is already in — and let
     * the user tap one of them to remove the photo from that album.
     *
     * Same 5-minute TTL + invalidation as [membershipCache] (both are dropped together by
     * [invalidateMembershipCache]). Both maps are built in the same loop in
     * [getAlbumMemberships], so adding the second map costs nothing extra at refresh time.
     */
    @Volatile private var fullMembershipCache: Map<String, Set<String>>? = null

    /**
     * Returns a `photoLinkId → albumName` lookup spanning every album the user owns.
     * For photos that live in multiple albums, the alphabetically first album wins
     * (stable, deterministic, no surprises for users sorting by name in their gallery).
     *
     * Cached for 5 minutes; per-album errors are logged and skipped so one broken album
     * never poisons the whole map.
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
     * Builds both [membershipCache] (name lookup, alphabetically-first wins) and
     * [fullMembershipCache] (full set of album linkIds) in a single album-walk pass. Both
     * maps share a 5-minute TTL and a single invalidation entry point.
     */
    private suspend fun ensureMembershipCachesFresh(userId: UserId) {
        val now = System.currentTimeMillis()
        if (membershipCache != null && fullMembershipCache != null
            && now - membershipCacheTime < membershipCacheTtlMs) {
            return
        }
        val albums = runCatching { loadAlbums(userId) }.getOrElse {
            Log.w(TAG, "membership refresh: loadAlbums failed: ${it.message}")
            return
        }
        val sortedAlbums = albums.sortedBy { it.name.lowercase() }
        val nameMap = mutableMapOf<String, String>()
        val idsMap = mutableMapOf<String, MutableSet<String>>()
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
            }
        }
        membershipCache = nameMap
        fullMembershipCache = idsMap.mapValues { it.value.toSet() }
        membershipCacheTime = now
        Log.d(TAG, "membership refresh: built ${nameMap.size} name entries, ${idsMap.size} id sets across ${albums.size} albums")
    }

    /** Drops both membership caches so the next read re-fetches. */
    fun invalidateMembershipCache() {
        membershipCache = null
        fullMembershipCache = null
        membershipCacheTime = 0L
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
            val coverThumbnailUrl = dto.coverLinkId?.let { coverLinkId ->
                // 1. Check local DB (fastest — works for synced photos)
                photoListingDao.getByLinkId(coverLinkId)?.thumbnailUrl
                // 2. Check thumbnail disk cache (works for cloud-only photos after album was opened once)
                    ?: File(thumbnailCacheDir, "thumb_$coverLinkId.jpg")
                        .takeIf { it.exists() && it.length() > 0 }
                        ?.let { "file://${it.absolutePath}" }
            }
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
            cloudAlbumDao.deleteWhereNotIn(albums.map { it.linkId })
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
            cloudAlbumDao.getAll().map { entity ->
                val domain = entity.toDomain()
                // Rehydrate coverThumbnailUrl from local sources — the entity doesn't persist
                // CDN URLs (expiring signatures) so the disk cache is our only offline source.
                val coverUrl = domain.coverLinkId?.let { coverLinkId ->
                    photoListingDao.getByLinkId(coverLinkId)?.thumbnailUrl
                        ?: File(thumbnailCacheDir, "thumb_$coverLinkId.jpg")
                            .takeIf { it.exists() && it.length() > 0 }
                            ?.let { "file://${it.absolutePath}" }
                }
                domain.copy(coverThumbnailUrl = coverUrl)
            }
        }.getOrElse { e ->
            Log.w(TAG, "loadAlbumsCached: failed (${e.message}) — returning empty")
            emptyList()
        }
    }

    /** Wipes the cached album list AND the album→photo membership table. Called from
     *  the sign-out path so cached data doesn't bleed across accounts. */
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
     */
    suspend fun prefetchAlbumsMembership(userId: UserId, albums: List<Album>): Unit = withContext(Dispatchers.IO) {
        if (albums.isEmpty()) return@withContext
        val volumeId = runCatching { shareService.getVolumeId(userId) }.getOrNull() ?: return@withContext
        val manager = apiProvider.get<DriveApiService>(userId)
        for (album in albums) {
            // Freshness gate — if we already have a membership-row count matching the
            // album's photoCount we trust it. Cheap O(1) DAO call vs a fresh pagination.
            val cachedCount = runCatching { albumPhotoMembershipDao.getPhotoLinkIds(album.linkId).size }.getOrDefault(-1)
            if (cachedCount > 0 && cachedCount == album.photoCount) continue
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
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "prefetchAlbumsMembership: ${album.linkId} failed (${e.message}) — continuing")
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
        // Album → photos is many-to-many: photos physically live in the photos-root
        // folder on Drive, the album is just a reference list. We walk that reference
        // list via the membership table, then fetch the matching photo_listing rows.
        // The membership join is required because photo_listing.parentLinkId points at
        // the root, not the album, so a parent-based lookup would return zero rows.
        val photoLinkIds = albumPhotoMembershipDao.getPhotoLinkIds(albumLinkId)
        if (photoLinkIds.isEmpty()) return@withContext emptyList()
        val rowsByLinkId = photoListingDao.getByLinkIds(photoLinkIds).associateBy { it.linkId }
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
        // Seed the lazy-thumbnail scheduler with the album key we just decrypted, so cells
        // scrolling into view skip the per-photo album-key resolution round trip.
        // For shared-with-me albums we also seed a SharingContext so the scheduler's
        // cache-miss fallback knows to re-fetch via the share endpoint (and re-decrypt
        // with the share key bytes) instead of the recipient's own volume — without that
        // hook a long backgrounded session would lose the in-memory cache entry and the
        // grid would silently regress to placeholders after process restart.
        if (albumKeyBytes != null) {
            if (sharingShareId != null && sharedAlbumParentKeyBytes != null) {
                thumbnailDecryptScheduler.populateSharedAlbumContext(
                    AlbumCryptoChain.SharingContext(
                        albumLinkId = albumLinkId,
                        sharingShareId = sharingShareId,
                        sharedShareKeyBytes = sharedAlbumParentKeyBytes,
                        albumKeyBytes = albumKeyBytes,
                    ),
                )
            } else {
                thumbnailDecryptScheduler.populateParentKeys(mapOf(albumLinkId to albumKeyBytes))
            }
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
                AlbumChild(linkId = dto.linkId, captureTime = dto.captureTime, addedTime = dto.addedTime)
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
                val sharingContext = if (sharingShareId != null && albumKeyBytes != null && sharedAlbumParentKeyBytes != null) {
                    AlbumCryptoChain.SharingContext(
                        albumLinkId = albumLinkId,
                        sharingShareId = sharingShareId,
                        sharedShareKeyBytes = sharedAlbumParentKeyBytes,
                        albumKeyBytes = albumKeyBytes,
                    )
                } else null
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
                val entity = if (sharingShareId != null) {
                    builtEntity.copy(parentLinkId = albumLinkId)
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

        // 6. POST in chunks of 50. The backend acks the BATCH with top-level Code=1000
        //    even when individual entries fail their own validation, so the per-photo
        //    response array is the only source of truth for what actually landed.
        //    Move every photo the backend rejected from `succeeded` into `failed` so
        //    the UI snackbar and downstream cache writes match reality.
        val rejectedByLink = mutableMapOf<String, String>() // linkId → error
        albumDataEntries.chunked(50).forEach { chunk ->
            val resp = semaphore.withPermit {
                manager.invoke {
                    addPhotosToAlbum(volumeId, albumLinkId, AddAlbumMultipleRequest(chunk))
                }.valueOrThrow
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

    suspend fun deleteAlbum(userId: UserId, albumLinkId: String): Unit = withContext(Dispatchers.IO) {
        try {
            val volumeId = shareService.getVolumeId(userId)
            val manager = apiProvider.get<DriveApiService>(userId)
            // DELETE /drive/photos/volumes/{volumeId}/albums/{albumLinkId}?DeleteAlbumPhotos=0
            // Photos inside the album are NOT deleted; only the album container is removed.
            semaphore.withPermit {
                manager.invoke { deleteAlbum(volumeId, albumLinkId, deleteAlbumPhotos = 0) }.valueOrThrow
            }
            // Drop the local rows too, otherwise loadAlbumsCached repaints the dead
            // album on the next cold start. Mirrors leaveSharedAlbum's cache cleanup.
            cloudAlbumDao.deleteByLinkId(albumLinkId)
            albumPhotoMembershipDao.deleteAllForAlbum(albumLinkId)
            invalidateMembershipCache()
            Log.d(TAG, "deleteAlbum: deleted albumLinkId=$albumLinkId")
        } catch (e: DriveNotFoundException) {
            // Already gone server-side — still wipe local cache so the grid doesn't
            // keep showing a phantom album entry.
            Log.w(TAG, "deleteAlbum: DriveNotFoundException: ${e.message}")
            cloudAlbumDao.deleteByLinkId(albumLinkId)
            albumPhotoMembershipDao.deleteAllForAlbum(albumLinkId)
            invalidateMembershipCache()
        }
    }

    /**
     * Removes the album reference for each [photoLinkIds] — photos themselves stay in Photos
     * root. Mirrors `addPhotosToAlbum`'s chunking (50 per POST) so a request that's too large
     * doesn't hit the API limit. Returns the linkIds the server confirmed; chunks that fail
     * are logged and excluded so the UI can still react to partial success.
     */
    suspend fun removePhotosFromAlbum(
        userId: UserId,
        albumLinkId: String,
        photoLinkIds: List<String>,
    ): List<String> = withContext(Dispatchers.IO) {
        if (photoLinkIds.isEmpty()) return@withContext emptyList()
        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)
        val removed = mutableListOf<String>()
        for (chunk in photoLinkIds.chunked(50)) {
            try {
                semaphore.withPermit {
                    manager.invoke {
                        removePhotosFromAlbum(volumeId, albumLinkId, RemoveFromAlbumRequest(chunk))
                    }.valueOrThrow
                }
                removed += chunk
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "removePhotosFromAlbum: chunk failed (${chunk.size} ids): ${e.message}")
            }
        }
        Log.d(TAG, "removePhotosFromAlbum: removed ${removed.size}/${photoLinkIds.size} from album $albumLinkId")
        if (removed.isNotEmpty()) {
            // Mirror the add path: keep the local membership table in step with the
            // server so reactive consumers (gallery filter, album cache) re-emit
            // immediately. Failure here is non-fatal — the prefetch will resync.
            runCatching {
                albumPhotoMembershipDao.deleteForAlbumPhotos(albumLinkId, removed)
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

        // Encrypt + sign the new name; HMAC for the lookup hash; signing email goes in metadata.
        val signingKey = cryptoHelper.getAddressSigningKey(userId)
        val newEncryptedName = cryptoHelper.encryptName(trimmed, rootPublicKey, signingKey.unlockedKeyBytes)
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
