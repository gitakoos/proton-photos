package me.proton.photos.data.repository.drive

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.photos.data.api.DriveApiService
import me.proton.photos.data.api.dto.AddAlbumMultipleEntry
import me.proton.photos.data.api.dto.AddAlbumMultipleRequest
import me.proton.photos.data.api.dto.AlbumDto
import me.proton.photos.data.api.dto.CreateAlbumLinkData
import me.proton.photos.data.api.dto.CreateAlbumRequest
import me.proton.photos.data.api.dto.PhotoLinkDto
import me.proton.photos.data.api.dto.RemoveFromAlbumRequest
import me.proton.photos.data.api.dto.UpdateAlbumLinkData
import me.proton.photos.data.api.dto.UpdateAlbumRequest
import me.proton.photos.data.crypto.DriveCryptoHelper
import me.proton.photos.data.db.dao.PhotoListingDao
import me.proton.photos.data.db.entity.PhotoListingEntity
import me.proton.photos.domain.entity.Album
import me.proton.photos.domain.entity.AlbumChild
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.domain.entity.DriveNotFoundException
import me.proton.photos.domain.repository.DrivePhotoRepository
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
    private val shareService: PhotosShareService,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val thumbnailHelpers: ThumbnailHelpers,
    private val photoEntityBuilder: PhotoEntityBuilder,
    @ApplicationContext private val context: Context,
) {
    private val semaphore get() = shareService.networkSemaphore

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
     * Returns a `photoLinkId → albumName` lookup spanning every album the user owns.
     * For photos that live in multiple albums, the alphabetically first album wins
     * (stable, deterministic, no surprises for users sorting by name in their gallery).
     *
     * Cached for 5 minutes; per-album errors are logged and skipped so one broken album
     * never poisons the whole map.
     */
    suspend fun getAlbumMemberships(userId: UserId): Map<String, String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = membershipCache
        if (cached != null && now - membershipCacheTime < membershipCacheTtlMs) {
            return@withContext cached
        }
        val albums = runCatching { loadAlbums(userId) }.getOrElse {
            Log.w(TAG, "getAlbumMemberships: loadAlbums failed: ${it.message}")
            return@withContext emptyMap()
        }
        // Sort alphabetically before walking so putIfAbsent gives us the ABC-first album
        // for multi-album photos. lowercase() to match what users see in case-insensitive
        // gallery apps.
        val sorted = albums.sortedBy { it.name.lowercase() }
        val result = mutableMapOf<String, String>()
        for (album in sorted) {
            try {
                val children = loadAlbumChildren(userId, album.linkId)
                for (child in children) {
                    result.putIfAbsent(child.linkId, album.name)
                }
            } catch (e: Exception) {
                Log.w(TAG, "getAlbumMemberships: album ${album.linkId} children failed: ${e.message}")
            }
        }
        membershipCache = result
        membershipCacheTime = now
        Log.d(TAG, "getAlbumMemberships: built ${result.size}-entry map across ${albums.size} albums")
        result
    }

    /** Drops the cached membership map so the next [getAlbumMemberships] re-fetches. */
    fun invalidateMembershipCache() {
        membershipCache = null
        membershipCacheTime = 0L
    }

    suspend fun loadAlbums(userId: UserId): List<Album> = withContext(Dispatchers.IO) {
        val volumeId = shareService.getVolumeId(userId)
        val shareId = shareService.getShareId(userId, volumeId)
        val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId)
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

        val linkDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, albumStubs.map { it.linkId })

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
        if (missingCoverIds.isNotEmpty() && rootLinkKeyBytes != null) {
            try {
                val coverDetails = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, missingCoverIds)
                val thumbnailIdToLinkId = mutableMapOf<String, String>()
                for ((coverId, detail) in coverDetails) {
                    val tl = detail.link.fileProperties?.activeRevision?.thumbnails
                        ?: detail.photo?.activeRevision?.thumbnails
                    val tid = tl?.firstOrNull { it.type == 1 }?.thumbnailId
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
                Log.w(TAG, "loadAlbums: cover-prefetch failed (${e.message}) — albums will load without covers")
            }
        }

        albumStubs.map { dto ->
            val link = linkDetailMap[dto.linkId]?.link
            var name = dto.linkId.take(8)
            if (link != null && rootLinkKeyBytes != null) {
                val nodeKey = link.nodeKey
                val nodePassphrase = link.nodePassphrase
                val encName = link.name
                if (nodeKey != null && nodePassphrase != null && encName != null) {
                    try {
                        cryptoHelper.decryptNodeKey(nodeKey, nodePassphrase, rootLinkKeyBytes)
                        name = cryptoHelper.decryptLinkName(encName, rootLinkKeyBytes) ?: dto.linkId.take(8)
                    } catch (e: Exception) {
                        Log.w(TAG, "album name decrypt failed for ${dto.linkId}: ${e.message}")
                    }
                }
            }
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
        val rootLinkPublicKey = cryptoContext.pgpCrypto.getPublicKey(rootLinkArmoredKey)
        val signingKey = cryptoHelper.getAddressSigningKey(userId)

        // Generate album node key pair
        val albumNodeKey = cryptoHelper.generateNodeKey()

        // NodePassphrase encrypted to root link's public key
        val nodePassphraseEncrypted = cryptoHelper.encryptDataToPgpMessage(
            albumNodeKey.passphraseBytes, rootLinkPublicKey)
        val nodePassphraseSignature = cryptoHelper.signData(
            albumNodeKey.passphraseBytes, signingKey.unlockedKeyBytes)

        // Album name encrypted to root link's public key, signed with address key.
        val encryptedName = cryptoHelper.encryptName(name, rootLinkPublicKey, signingKey.unlockedKeyBytes)

        // NodeHashKey: random 32-byte secret encrypted to the album's own public key.
        // The raw bytes are also used to compute the name Hash (HMAC-SHA256 of plaintext name).
        val hashKeyBytes = cryptoContext.pgpCrypto.generateRandomBytes(32)
        val nodeHashKey = cryptoHelper.encryptDataToPgpMessage(hashKeyBytes, albumNodeKey.publicKeyArmored)
        val nameHash = cryptoHelper.computeNameHash(name, hashKeyBytes)

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
                            xAttr                   = null,
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

    suspend fun loadAlbumPhotos(userId: UserId, albumLinkId: String, volumeId: String?): List<CloudPhoto> = withContext(Dispatchers.IO) {
        val resolvedVolumeId = volumeId ?: shareService.getVolumeId(userId)
        val shareId = shareService.getShareId(userId, resolvedVolumeId)
        val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId)
        val thumbnailCacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }

        // Photos inside an album have NodePassphrase/Name encrypted to the ALBUM's key, not root link's.
        val albumKeyBytes: ByteArray? = if (rootLinkKeyBytes != null) {
            val albumDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, resolvedVolumeId, listOf(albumLinkId))
            val albumLink = albumDetailMap[albumLinkId]?.link
            if (albumLink?.nodeKey != null && albumLink.nodePassphrase != null) {
                try {
                    cryptoHelper.decryptNodeKey(albumLink.nodeKey, albumLink.nodePassphrase, rootLinkKeyBytes)
                        .also { Log.d(TAG, "loadAlbumPhotos: albumKey decrypted OK for $albumLinkId") }
                } catch (e: Exception) {
                    Log.w(TAG, "loadAlbumPhotos: album key decrypt failed for $albumLinkId: ${e.message}")
                    null
                }
            } else {
                Log.w(TAG, "loadAlbumPhotos: albumLink missing nodeKey/nodePassphrase for $albumLinkId")
                null
            }
        } else null

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

        val linkDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, resolvedVolumeId, children.map { it.linkId })

        // Build thumbnail ID → linkId map and batch-fetch download URLs.
        val thumbnailIdToLinkId = mutableMapOf<String, String>()
        for ((linkId, detail) in linkDetailMap) {
            val thumbnailList = detail.link.fileProperties?.activeRevision?.thumbnails
                ?: detail.photo?.activeRevision?.thumbnails
            val tid = thumbnailList?.firstOrNull { it.type == 1 }?.thumbnailId
                ?: thumbnailList?.firstOrNull()?.thumbnailId
            if (tid != null) thumbnailIdToLinkId[tid] = linkId
        }
        val thumbnailUrlMap = if (thumbnailIdToLinkId.isNotEmpty())
            linkDetailHelpers.batchFetchThumbnailUrls(userId, resolvedVolumeId, thumbnailIdToLinkId.keys.toList())
        else emptyMap()
        Log.d(TAG, "loadAlbumPhotos: fetched ${thumbnailUrlMap.size} thumbnail URLs for album $albumLinkId")

        // Batch-fetch CKPs for thumbnail decryption.
        val childIds = children.map { it.linkId }
        val ckpMap = linkDetailHelpers.batchFetchContentKeyPackets(userId, shareId, childIds)
        val ownPublicKeys = cryptoHelper.getOwnPublicKeysArmored(userId)

        // Deduplicate by linkId in case the same photo was added to the album multiple times.
        val uniqueChildren = children.distinctBy { it.linkId }
        if (uniqueChildren.size != children.size) {
            Log.w(TAG, "loadAlbumPhotos: removed ${children.size - uniqueChildren.size} duplicate linkIds")
        }

        // Build result list using for-loop so we can call suspend DB functions inside.
        val result = mutableListOf<CloudPhoto>()
        val newEntities = mutableListOf<PhotoListingEntity>()

        for (child in uniqueChildren) {
            // Fast path: photo already in DB — use it directly, BUT verify the cached thumbnail
            // file still exists on disk. The thumbnails dir is private cache and the OS can wipe
            // it; the DB row would still point at a now-missing file:// URI and the album grid
            // would render empty tiles. If the file is gone, drop the fast path and re-decrypt.
            val cached = photoListingDao.getByLinkId(child.linkId)
            if (cached != null && thumbnailHelpers.isCachedValid(cached.thumbnailUrl)) {
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

            // Determine the correct parent key for each photo.
            // Photos physically live in the Photos root folder (parentLinkId == rootLinkId),
            // so their NodePassphrase is encrypted to the ROOT LINK key — NOT the album key.
            // (The album is just a reference list; photos are never moved into the album folder.)
            // For safety, fall back to the album key if the parentLinkId differs.
            val photoParentLinkId = linkDetailMap[child.linkId]?.link?.parentLinkId
            val photoParentKeyBytes = when {
                rootLinkKeyBytes != null &&
                    (photoParentLinkId == null || photoParentLinkId == shareService.photosRootLinkId()) ->
                    rootLinkKeyBytes
                else -> albumKeyBytes  // fallback for exotic layouts
            }

            val entity = photoEntityBuilder.build(
                stub, linkDetailMap[child.linkId], userId, shareId,
                resolvedVolumeId, photoParentKeyBytes, thumbnailCacheDir,
                thumbnailInfo, ckpMap[child.linkId], ownPublicKeys,
            )

            // Always persist album photo entities in the local DB so future opens are fast
            // (DB cache hit) and the observePhotosByLinkIds Flow can serve them reactively.
            newEntities.add(entity)

            result.add(entity.toDomain())
        }

        // Batch-persist any newly resolved thumbnails.
        if (newEntities.isNotEmpty()) {
            try {
                photoListingDao.upsertAll(newEntities)
                Log.d(TAG, "loadAlbumPhotos: cached ${newEntities.size} new thumbnail entries in DB")
            } catch (e: Exception) {
                Log.w(TAG, "loadAlbumPhotos: DB upsert failed: ${e.message}")
            }
        }

        // Sort by captureTime DESC to match the order produced by observeByLinkIds,
        // so the album detail has no visual reorder jump when the Flow emits DB updates.
        result.sortedByDescending { it.captureTime }
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

        // Decrypt the album's private key (to decrypt its NodeHashKey)
        val albumKeyBytes = cryptoHelper.decryptNodeKey(albumNodeKeyArmored, albumNodePassphraseArmored, rootLinkKeyBytes)
        // Extract album's public key — used to re-encrypt photo passphrases and names TO the album
        val albumPublicKeyArmored = cryptoContext.pgpCrypto.getPublicKey(albumNodeKeyArmored)

        // Decrypt the album's NodeHashKey (symmetric key for HMAC-SHA256 name hashing)
        val albumNodeHashKeyEncrypted = albumDto.album?.nodeHashKey
            ?: error("addPhotosToAlbum: album has no NodeHashKey in AlbumMeta")
        val albumNodeHashKeyBytes = cryptoContext.pgpCrypto.decryptData(albumNodeHashKeyEncrypted, albumKeyBytes)

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
                val photoPassphraseBytes = cryptoContext.pgpCrypto.decryptData(photoNodePassArmored, rootLinkKeyBytes)

                // Unlock photo's node key with decrypted passphrase
                val photoNodeKeyBytes = cryptoContext.pgpCrypto.unlock(photoNodeKeyArmored, photoPassphraseBytes).let {
                    val b = it.value.copyOf(); it.close(); b
                }

                // Decrypt plaintext file name
                val plainName = cryptoHelper.decryptLinkName(photoNameEncrypted, photoNodeKeyBytes)
                    ?: "photo_$photoLinkId"

                // Re-encrypt the photo's passphrase TO the album's public key
                val newNodePassphrase = cryptoHelper.encryptDataToPgpMessage(photoPassphraseBytes, albumPublicKeyArmored)

                // Encrypt name to album's public key, signed with address key
                val newName = cryptoHelper.encryptName(plainName, albumPublicKeyArmored, signingKey.unlockedKeyBytes)

                // HMAC-SHA256 of plaintext name using album's NodeHashKey
                val nameHash = cryptoHelper.computeNameHash(plainName, albumNodeHashKeyBytes)

                albumDataEntries += AddAlbumMultipleEntry(
                    linkId             = photoLinkId,
                    hash               = nameHash,
                    name               = newName,
                    nodePassphrase     = newNodePassphrase,
                    nameSignatureEmail = signingKey.email,
                    contentHash        = contentHash,
                )
                succeeded += photoLinkId
                Log.d(TAG, "addPhotosToAlbum: prepared entry for $photoLinkId ('$plainName')")
            } catch (e: Exception) {
                Log.e(TAG, "addPhotosToAlbum: crypto failed for $photoLinkId: ${e.message}", e)
                failed += photoLinkId
            }
        }

        if (albumDataEntries.isEmpty()) {
            // Old behaviour silently returned here, masking total crypto failure as success. Now
            // we throw so the caller's runCatching surfaces it instead of "0 added, all good".
            Log.w(TAG, "addPhotosToAlbum: no valid entries built — every photo failed crypto")
            error("addPhotosToAlbum: all ${photoLinkIds.size} photos failed crypto preparation")
        }

        // 6. POST in chunks of 50
        albumDataEntries.chunked(50).forEach { chunk ->
            semaphore.withPermit {
                manager.invoke {
                    addPhotosToAlbum(volumeId, albumLinkId, AddAlbumMultipleRequest(chunk))
                }.valueOrThrow
            }
        }
        Log.d(TAG, "addPhotosToAlbum: added ${succeeded.size}/${photoLinkIds.size} photos to album $albumLinkId" +
            if (failed.isEmpty()) "" else " (failed: ${failed.size})")
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
            invalidateMembershipCache()
            Log.d(TAG, "deleteAlbum: deleted albumLinkId=$albumLinkId")
        } catch (e: DriveNotFoundException) {
            Log.w(TAG, "deleteAlbum: DriveNotFoundException: ${e.message}")
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
                Log.w(TAG, "removePhotosFromAlbum: chunk failed (${chunk.size} ids): ${e.message}")
            }
        }
        Log.d(TAG, "removePhotosFromAlbum: removed ${removed.size}/${photoLinkIds.size} from album $albumLinkId")
        if (removed.isNotEmpty()) invalidateMembershipCache()
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
        val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId)
            ?: error("renameAlbum: root link key unavailable")
        val rootLinkArmored = shareService.rootLinkArmoredKey()
            ?: error("renameAlbum: root link armored key unavailable")
        val rootPublicKey = cryptoContext.pgpCrypto.getPublicKey(rootLinkArmored)
        val rootNodeHashKey = shareService.rootNodeHashKeyBytes()
            ?: error("renameAlbum: root NodeHashKey unavailable")

        // Pull the album's current state so we can supply OriginalHash for the server's
        // optimistic-concurrency check. The server compares OriginalHash to its stored Hash
        // byte-for-byte; if we recompute the hash locally (decrypt name → HMAC) the result
        // can differ from the server-stored hash (e.g. names containing combining unicode
        // can normalise differently across libraries), giving a confusing "out of date"
        // rejection on the very first rename attempt. Use the server-canonical Hash field
        // directly and only fall back to a local recompute if the server omitted it.
        val albumDetail = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(albumLinkId))[albumLinkId]
            ?: error("renameAlbum: album link not found: $albumLinkId")
        val originalHash = albumDetail.link.hash ?: run {
            // Defensive fallback — should not happen in practice; the Drive API always
            // returns Hash on link-detail responses.
            val currentEncryptedName = albumDetail.link.name
                ?: error("renameAlbum: album has no encrypted Name field")
            val currentPlainName = cryptoHelper.decryptLinkName(currentEncryptedName, rootLinkKeyBytes)
                ?: error("renameAlbum: failed to decrypt current album name for fallback hash")
            cryptoHelper.computeNameHash(currentPlainName, rootNodeHashKey)
        }

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
