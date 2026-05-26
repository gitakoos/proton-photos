package me.proton.photos.data.repository.drive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.photos.data.api.DriveApiService
import me.proton.photos.data.api.dto.BatchLinksRequest
import me.proton.photos.data.crypto.DriveCryptoHelper
import me.proton.photos.domain.entity.DriveNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhotosShareSvc"

/**
 * Owns the Photos share / root-link key cache. Lazy-loads volumeId / shareId / rootLinkId
 * via the Drive Photos endpoint, decrypts the share + root-link keys + root NodeHashKey,
 * and wipes the cache on user-switch so user-A keys never apply to user-B data.
 */
@Singleton
class PhotosShareService @Inject constructor(
    private val apiProvider: ApiProvider,
    private val cryptoHelper: DriveCryptoHelper,
    private val cryptoContext: CryptoContext,
    private val photosVolumeBootstrap: PhotosVolumeBootstrap,
) {
    /**
     * Bounded permits for Drive API calls. Crypto work (block encrypt + sign) will use a
     * separate, wider pool inside [PhotoUploadService] so a multi-block upload's CPU work
     * doesn't compete with network parallelism for the same 4 slots.
     */
    val networkSemaphore = Semaphore(4)

    @Volatile private var cachedPhotosVolumeId: String? = null
    @Volatile private var cachedPhotosShareId: String? = null
    @Volatile private var cachedPhotosShareKey: String? = null
    @Volatile private var cachedPhotosSharePassphrase: String? = null
    @Volatile private var cachedPhotosRootLinkId: String? = null
    @Volatile private var cachedRootLinkKeyBytes: ByteArray? = null
    @Volatile private var cachedRootLinkArmoredKey: String? = null
    @Volatile private var cachedRootNodeHashKeyBytes: ByteArray? = null
    /** UserId the current cache was populated for; mismatch triggers [wipeKeyCache]. */
    @Volatile private var cachedUserId: String? = null

    // ── Read-only accessors used by the other Drive services ──────────────────

    /** Last-known root-link ID; null if [getVolumeId] hasn't completed yet. */
    fun photosRootLinkId(): String? = cachedPhotosRootLinkId
    fun shareId(): String? = cachedPhotosShareId
    fun volumeId(): String? = cachedPhotosVolumeId
    fun rootLinkArmoredKey(): String? = cachedRootLinkArmoredKey
    fun rootNodeHashKeyBytes(): ByteArray? = cachedRootNodeHashKeyBytes

    // ── Mutating operations ──────────────────────────────────────────────────

    /**
     * Wipes every plaintext key material. Call before sign-out / user switch so a heap
     * dump cannot recover share / root-link / node-hash key material across sessions.
     */
    @Synchronized
    fun wipeKeyCache() {
        cachedRootLinkKeyBytes?.fill(0)
        cachedRootLinkKeyBytes = null
        cachedRootNodeHashKeyBytes?.fill(0)
        cachedRootNodeHashKeyBytes = null
        cachedPhotosShareKey = null
        cachedPhotosSharePassphrase = null
        cachedRootLinkArmoredKey = null
        cachedPhotosVolumeId = null
        cachedPhotosShareId = null
        cachedPhotosRootLinkId = null
        cachedUserId = null
    }

    /**
     * Called by [PhotoStreamService] (the future home of `refreshCloudPhotos`) when
     * `createOrGetPhotosVolume` reveals a volumeId / shareId different from what the
     * initial `getPhotosShare` call returned. Resets derived keys so callers re-derive
     * them under the corrected volume mapping.
     */
    @Synchronized
    fun adoptStreamVolumeMapping(streamVolumeId: String, streamShareId: String?, currentVolumeId: String) {
        if (streamVolumeId != currentVolumeId && streamVolumeId.isNotBlank()) {
            Log.d(TAG, "adoptStreamVolumeMapping: switching to Photos-volume volumeId=$streamVolumeId")
            cachedPhotosVolumeId = streamVolumeId
            cachedPhotosRootLinkId = null
            cachedRootLinkKeyBytes?.fill(0)
            cachedRootLinkKeyBytes = null
            cachedRootLinkArmoredKey = null
            if (!streamShareId.isNullOrBlank()) {
                cachedPhotosShareId = streamShareId
            }
        } else if (!streamShareId.isNullOrBlank() && cachedPhotosShareId == null) {
            cachedPhotosShareId = streamShareId
        }
    }

    // ── Lazy-loading the Drive Photos share / root-link material ─────────────

    suspend fun getVolumeId(userId: UserId): String = withContext(Dispatchers.IO) {
        // User switch ⇒ wipe everything before reading anything. Keeps user-A keys from
        // being applied to user-B's share even if the Singleton is reused after sign-out.
        if (cachedUserId != null && cachedUserId != userId.id) {
            wipeKeyCache()
            cryptoHelper.clearAllCaches()
        }
        cachedPhotosVolumeId?.let { return@withContext it }
        networkSemaphore.withPermit {
            cachedPhotosVolumeId?.let { return@withPermit it }
            val manager = apiProvider.get<DriveApiService>(userId)
            val response = manager.invoke { getPhotosShare() }.valueOrThrow
            val shareId = response.share.shareId
            Log.d(TAG, "getPhotosShare: volumeId=${response.volume.volumeId} shareId=$shareId linkId=${response.share.linkId} hasKey=${response.share.key != null} hasPassphrase=${response.share.passphrase != null}")
            cachedUserId = userId.id
            cachedPhotosVolumeId = response.volume.volumeId
            cachedPhotosShareId = shareId
            cachedPhotosRootLinkId = response.share.linkId

            cachedPhotosShareKey = response.share.key
            cachedPhotosSharePassphrase = response.share.passphrase

            // If the Photos share didn't return a LinkID, get it from the standard share endpoint.
            var fallbackFailures = mutableListOf<String>()
            if (cachedPhotosRootLinkId == null) {
                try {
                    val shareDetail = manager.invoke { getShareById(shareId) }.valueOrThrow.share
                    if (shareDetail.linkId != null) {
                        cachedPhotosRootLinkId = shareDetail.linkId
                        Log.d(TAG, "getVolumeId: rootLinkId=${shareDetail.linkId} (from getShareById)")
                    } else {
                        fallbackFailures += "getShareById returned null linkId"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getVolumeId: getShareById fallback failed: ${e.message}")
                    fallbackFailures += "getShareById: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            // Last resort: derive root link ID from an album's parentLinkId.
            if (cachedPhotosRootLinkId == null) {
                Log.d(TAG, "getVolumeId: linkId still missing, deriving from album parentLinkId")
                try {
                    val albumsResp = manager.invoke { getAlbums(response.volume.volumeId) }.valueOrThrow
                    Log.d(TAG, "getVolumeId: found ${albumsResp.albums.size} albums")
                    val firstAlbumId = albumsResp.albums.firstOrNull()?.linkId
                    if (firstAlbumId != null) {
                        val batchResp = manager.invoke {
                            batchGetLinks(response.volume.volumeId, BatchLinksRequest(listOf(firstAlbumId)))
                        }.valueOrThrow
                        val rootLinkId = batchResp.links.firstOrNull()?.link?.parentLinkId
                        Log.d(TAG, "getVolumeId: rootLinkId=$rootLinkId (from album parent)")
                        cachedPhotosRootLinkId = rootLinkId
                        if (rootLinkId == null) {
                            fallbackFailures += "album parentLinkId was null"
                        }
                    } else {
                        Log.w(TAG, "getVolumeId: no albums found, cannot derive rootLinkId")
                        fallbackFailures += "no albums to derive parentLinkId from"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "rootLinkId derivation failed: ${e.message}")
                    fallbackFailures += "album-derive: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            // After every fallback: if rootLinkId is still null, log loudly but return the
            // volumeId we DO have. This is a legitimate state for a brand-new Proton account
            // that has never uploaded a photo — no Photos volume root link exists yet, and
            // `createOrGetPhotosVolume` on the next upload will materialise it. Throwing
            // here would crash anything that calls getVolumeId on app launch (gallery refresh,
            // SyncWorker), making the app unusable for first-time users.
            //
            // Downstream getRootLinkKeyBytes() handles null rootLinkId by returning null
            // (caller skips the work); PhotoStreamService's refresh treats this as "no
            // cloud photos yet" and the first upload triggers volume creation.
            if (cachedPhotosRootLinkId == null) {
                Log.w(TAG, "getVolumeId: no rootLinkId available yet (likely a fresh Photos " +
                    "share). Fallback chain: ${fallbackFailures.joinToString(" | ")}. The " +
                    "next upload via createOrGetPhotosVolume will populate it.")
            }
            response.volume.volumeId
        }
    }

    suspend fun getShareId(userId: UserId, volumeId: String): String = withContext(Dispatchers.IO) {
        cachedPhotosShareId?.let { return@withContext it }
        getVolumeId(userId)
        cachedPhotosShareId ?: throw DriveNotFoundException("No photos share found")
    }

    /**
     * Forces the Photos volume to be materialised on the server (idempotent), then refreshes
     * the local cache so subsequent calls to [getRootLinkKeyBytes] see the root link.
     *
     * Why this exists: a brand-new Proton account that has Photos enabled but never uploaded
     * a photo has a Photos SHARE but no Photos ROOT LINK — the server creates the root link
     * lazily on first `createOrGetPhotosVolume` call. Operations that need to encrypt to the
     * root key (album creation, the very first upload setup) must trigger materialisation
     * BEFORE asking for the key, otherwise [getRootLinkKeyBytes] returns null and the caller
     * surfaces "Cannot load root link key" to the user.
     *
     * Returns true when the root link is available after this call, false if even materialisation
     * couldn't produce it (network failure, server error). Idempotent and safe to call from
     * any feature path; the underlying API endpoint is also idempotent.
     */
    suspend fun ensurePhotosVolumeReady(userId: UserId): Boolean = withContext(Dispatchers.IO) {
        // Aggressive readiness check — we don't just need a rootLinkId, we need the actual
        // decrypted key bytes loaded. A previous version of this function early-returned on
        // "rootLinkId != null" alone, which silently skipped key-load for callers that needed
        // to encrypt to the root (album create). Failure manifested as "Cannot load root
        // link key for album creation" because [getRootLinkKeyBytes] would then trip on a
        // batchGetLinks call that wasn't fully wired.
        if (cachedRootLinkKeyBytes != null && cachedRootLinkArmoredKey != null) {
            return@withContext true
        }

        // Make sure the share is loaded first (volumeId / shareId / shareKey / sharePassphrase).
        val volumeId = getVolumeId(userId)
        Log.d(TAG, "ensurePhotosVolumeReady: state before " +
            "volumeId=$volumeId shareId=$cachedPhotosShareId " +
            "rootLinkId=$cachedPhotosRootLinkId hasKey=${cachedPhotosShareKey != null}")

        // If we already have a rootLinkId, the key might just need to be loaded — skip the
        // server-side materialisation step and go straight to the key-load attempt.
        if (cachedPhotosRootLinkId == null) {
            try {
                val manager = apiProvider.get<DriveApiService>(userId)
                // Check FIRST whether a Photos volume already exists on the server. If yes,
                // skip the expensive (and panic-prone for some accounts) PGP key generation
                // in PhotosVolumeBootstrap.build() entirely — the server only consumes the
                // keys when creating a brand-new volume, and re-running the GoOpenPGP key
                // generation against the user's address key has been observed to trigger
                // "panic: slice bounds out of range" in the native crypto layer on certain
                // account configurations. Only legitimately-fresh accounts need build() to run.
                val volumesResp = runCatching {
                    networkSemaphore.withPermit { manager.invoke { getVolumes() }.valueOrThrow }
                }.getOrNull()
                val existingPhotoVolume = volumesResp?.volumes?.firstOrNull { it.type == VOLUME_TYPE_PHOTO }
                if (existingPhotoVolume != null) {
                    Log.d(TAG, "ensurePhotosVolumeReady: existing PHOTO volume " +
                        "id=${existingPhotoVolume.volumeId} share=${existingPhotoVolume.share?.shareId} " +
                        "link=${existingPhotoVolume.share?.linkId} — skipping bootstrap key generation")
                    existingPhotoVolume.share?.shareId?.let { cachedPhotosShareId = it }
                    existingPhotoVolume.share?.linkId?.let { cachedPhotosRootLinkId = it }
                    if (existingPhotoVolume.volumeId != volumeId && existingPhotoVolume.volumeId.isNotBlank()) {
                        cachedPhotosVolumeId = existingPhotoVolume.volumeId
                    }
                    // Re-fetch getPhotosShare to populate the Share key + passphrase fields
                    // that getVolumes doesn't carry but our key-load needs.
                    runCatching {
                        val freshShare = networkSemaphore.withPermit {
                            manager.invoke { getPhotosShare() }.valueOrThrow
                        }
                        cachedPhotosShareKey = freshShare.share.key ?: cachedPhotosShareKey
                        cachedPhotosSharePassphrase = freshShare.share.passphrase ?: cachedPhotosSharePassphrase
                        freshShare.share.linkId?.let { cachedPhotosRootLinkId = it }
                    }.onFailure { e ->
                        Log.w(TAG, "ensurePhotosVolumeReady (existing-volume path): getPhotosShare refresh failed: ${e.message}")
                    }
                    // Skip the rest of the materialisation flow.
                    val keyBytes = getRootLinkKeyBytes(userId)
                    Log.d(TAG, "ensurePhotosVolumeReady: final state (existing-volume) " +
                        "rootLinkId=$cachedPhotosRootLinkId hasKeyBytes=${keyBytes != null} " +
                        "hasArmored=${cachedRootLinkArmoredKey != null}")
                    return@withContext keyBytes != null && cachedRootLinkArmoredKey != null
                }
                // No existing PHOTO volume — fall through to genuine materialisation.
                // Build the full Share+Link key bundle BEFORE acquiring the network permit
                // so multiple concurrent ensurePhotosVolumeReady callers don't all hog the
                // 4 network slots while doing CPU-bound PGP key generation.
                val body = photosVolumeBootstrap.build(userId)
                val resp = networkSemaphore.withPermit {
                    manager.invoke {
                        createOrGetPhotosVolume(body)
                    }.valueOrThrow
                }
                val newVolumeId = resp.volume.volumeId
                val newShareId  = resp.volume.shareId
                Log.d(TAG, "ensurePhotosVolumeReady: createOrGetPhotosVolume volumeId=$newVolumeId shareId=$newShareId")
                adoptStreamVolumeMapping(newVolumeId, newShareId, volumeId)

                // Re-call getPhotosShare to pick up the now-materialised root linkId AND
                // refresh the share key/passphrase if the share itself was just created.
                runCatching {
                    val freshShare = networkSemaphore.withPermit {
                        manager.invoke { getPhotosShare() }.valueOrThrow
                    }
                    cachedPhotosShareKey = freshShare.share.key ?: cachedPhotosShareKey
                    cachedPhotosSharePassphrase = freshShare.share.passphrase ?: cachedPhotosSharePassphrase
                    freshShare.share.linkId?.let { cachedPhotosRootLinkId = it }
                    Log.d(TAG, "ensurePhotosVolumeReady: re-fetched getPhotosShare linkId=${freshShare.share.linkId}")
                }.onFailure { e ->
                    Log.w(TAG, "ensurePhotosVolumeReady: getPhotosShare refresh failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ensurePhotosVolumeReady: createOrGetPhotosVolume failed: ${e.message}")
                // ALREADY_EXISTS (Proton error code 2500 / HTTP 409) is the expected response
                // when the account's Photos volume was created in a previous session: the
                // server refuses re-creation but the existing volume still has the LinkID +
                // ShareID we need. Mirror the official Android client's recovery path
                // (CreatePhotoShare.kt → onFailure if `hasProtonErrorCode(ALREADY_EXISTS)`):
                // call getVolumes, filter for Type=2 (PHOTO), and pull ShareID + root LinkID
                // from the nested Volume.Share object. See VolumeDto in DriveDto.kt for the
                // wire-format reference into the official client.
                val msg = e.message.orEmpty()
                val isAlreadyExists = msg.contains("already active", ignoreCase = true) ||
                    msg.contains("ALREADY_EXISTS", ignoreCase = true) ||
                    msg.contains("2500") ||
                    msg.contains("409")
                if (isAlreadyExists) {
                    try {
                        Log.d(TAG, "ensurePhotosVolumeReady: ALREADY_EXISTS → calling getVolumes for recovery")
                        val manager = apiProvider.get<DriveApiService>(userId)
                        val volumesResp = networkSemaphore.withPermit {
                            manager.invoke { getVolumes() }.valueOrThrow
                        }
                        Log.d(TAG, "ensurePhotosVolumeReady: getVolumes returned ${volumesResp.volumes.size} volume(s)")
                        // Official client constants: TYPE_REGULAR=1L, TYPE_PHOTO=2L (see
                        // VolumeDto.Companion in android-drive). Filter by Type==2.
                        val photoVolume = volumesResp.volumes.firstOrNull { it.type == VOLUME_TYPE_PHOTO }
                        if (photoVolume == null) {
                            // Unexpected: server says ALREADY_EXISTS but no PHOTO volume in the list.
                            // Emit the full set of (id, type, state) so we can diagnose from logcat.
                            val dump = volumesResp.volumes.joinToString { "(id=${it.volumeId}, type=${it.type}, state=${it.state})" }
                            Log.e(TAG, "ensurePhotosVolumeReady: ALREADY_EXISTS but no PHOTO volume in list: $dump")
                        } else {
                            Log.d(TAG, "ensurePhotosVolumeReady: PHOTO volume found id=${photoVolume.volumeId} state=${photoVolume.state} shareId=${photoVolume.share?.shareId} linkId=${photoVolume.share?.linkId}")
                            adoptStreamVolumeMapping(
                                streamVolumeId = photoVolume.volumeId,
                                streamShareId = photoVolume.share?.shareId,
                                currentVolumeId = volumeId,
                            )
                            photoVolume.share?.linkId?.let { cachedPhotosRootLinkId = it }
                            // The volume list response carries IDs but not the share Key /
                            // Passphrase needed for crypto. Re-fetch getPhotosShare so the
                            // share key material is loaded for getRootLinkKeyBytes below.
                            runCatching {
                                val freshShare = networkSemaphore.withPermit {
                                    manager.invoke { getPhotosShare() }.valueOrThrow
                                }
                                cachedPhotosShareKey = freshShare.share.key ?: cachedPhotosShareKey
                                cachedPhotosSharePassphrase = freshShare.share.passphrase ?: cachedPhotosSharePassphrase
                                freshShare.share.linkId?.let { cachedPhotosRootLinkId = it }
                                Log.d(TAG, "ensurePhotosVolumeReady: re-fetched getPhotosShare after getVolumes; hasKey=${cachedPhotosShareKey != null} hasPass=${cachedPhotosSharePassphrase != null} linkId=${cachedPhotosRootLinkId}")
                            }.onFailure { ex ->
                                Log.w(TAG, "ensurePhotosVolumeReady: post-recovery getPhotosShare failed: ${ex.message}")
                            }
                        }
                    } catch (recoveryEx: Exception) {
                        Log.e(TAG, "ensurePhotosVolumeReady: getVolumes recovery failed", recoveryEx)
                    }
                }
            }
        }

        // Always finish with an explicit key-load attempt — this is what populates
        // cachedRootLinkKeyBytes + cachedRootLinkArmoredKey for downstream encryption.
        val keyBytes = getRootLinkKeyBytes(userId)
        Log.d(TAG, "ensurePhotosVolumeReady: final state " +
            "rootLinkId=$cachedPhotosRootLinkId hasKeyBytes=${keyBytes != null} " +
            "hasArmored=${cachedRootLinkArmoredKey != null}")
        keyBytes != null && cachedRootLinkArmoredKey != null
    }

    suspend fun getShareKeyBytes(userId: UserId): ByteArray? {
        val key = cachedPhotosShareKey ?: run { getVolumeId(userId); cachedPhotosShareKey } ?: return null
        val passphrase = cachedPhotosSharePassphrase ?: return null
        return try {
            cryptoHelper.getOrDecryptShareKey(userId, key, passphrase)
        } catch (e: Exception) {
            Log.e(TAG, "getShareKeyBytes failed", e)
            null
        }
    }

    /**
     * Proton Drive key hierarchy:
     *   address key → share passphrase → share key
     *   share key   → root link passphrase → root link key
     *   root link key → photo/album node passphrase → photo/album node key
     *
     * Photos and albums are direct children of the root link, so their NodePassphrase
     * is encrypted to the ROOT LINK key, NOT the share key.
     */
    suspend fun getRootLinkKeyBytes(userId: UserId): ByteArray? {
        cachedRootLinkKeyBytes?.let { Log.d(TAG, "getRootLinkKeyBytes: cache hit"); return it }
        val shareKeyBytes = getShareKeyBytes(userId)
        if (shareKeyBytes == null) {
            Log.w(TAG, "getRootLinkKeyBytes: shareKey=NULL cachedKey=${cachedPhotosShareKey?.take(30)} cachedPass=${cachedPhotosSharePassphrase?.take(30)}")
            return null
        }
        val shareId = cachedPhotosShareId ?: run { getVolumeId(userId); cachedPhotosShareId }
        if (shareId == null) { Log.w(TAG, "getRootLinkKeyBytes: shareId=NULL"); return null }
        val rootLinkId = cachedPhotosRootLinkId
        if (rootLinkId == null) { Log.w(TAG, "getRootLinkKeyBytes: rootLinkId=NULL"); return null }
        val volumeId = cachedPhotosVolumeId ?: run { getVolumeId(userId); cachedPhotosVolumeId }
        if (volumeId == null) { Log.w(TAG, "getRootLinkKeyBytes: volumeId=NULL"); return null }
        return try {
            val manager = apiProvider.get<DriveApiService>(userId)
            // drive/v2/shares/{id}/links/{id} returns 404 for the root link.
            // batchGetLinks (photos volume endpoint) works for any linkId including root.
            val batchResp = networkSemaphore.withPermit {
                manager.invoke { batchGetLinks(volumeId, BatchLinksRequest(listOf(rootLinkId))) }.valueOrThrow
            }
            val rootLink = batchResp.links.firstOrNull()?.link
                ?: run { Log.w(TAG, "getRootLinkKeyBytes: root link not in batch response"); return null }
            val nodeKeyArmored = rootLink.nodeKey ?: run { Log.w(TAG, "getRootLinkKeyBytes: no nodeKey"); return null }
            val nodePassphraseArmored = rootLink.nodePassphrase ?: run { Log.w(TAG, "getRootLinkKeyBytes: no nodePassphrase"); return null }
            val keyBytes = cryptoHelper.decryptNodeKey(nodeKeyArmored, nodePassphraseArmored, shareKeyBytes)
            cachedRootLinkKeyBytes = keyBytes
            cachedRootLinkArmoredKey = nodeKeyArmored

            // Decrypt the root folder's NodeHashKey — needed to compute correct name hashes
            // for files created inside the root (HMAC-SHA256(filename, nodeHashKeyBytes)).
            val encNodeHashKey = batchResp.links.firstOrNull()?.folder?.nodeHashKey
            if (encNodeHashKey != null) {
                try {
                    cachedRootNodeHashKeyBytes = cryptoContext.pgpCrypto.decryptData(encNodeHashKey, keyBytes)
                    Log.d(TAG, "getRootLinkKeyBytes: root NodeHashKey decrypted (${cachedRootNodeHashKeyBytes?.size} bytes)")
                } catch (e: Exception) {
                    Log.w(TAG, "getRootLinkKeyBytes: failed to decrypt root NodeHashKey: ${e.message}")
                }
            } else {
                Log.w(TAG, "getRootLinkKeyBytes: root link has no Folder.NodeHashKey in batch response")
            }

            Log.d(TAG, "getRootLinkKeyBytes: root link key decrypted successfully")
            keyBytes
        } catch (e: Exception) {
            Log.e(TAG, "getRootLinkKeyBytes failed", e)
            null
        }
    }

    private companion object {
        /**
         * Volume.Type integer for the Photos volume. Mirrors `VolumeDto.TYPE_PHOTO = 2L` in
         * the official Proton Drive Android client (see VolumeDto.kt companion object).
         */
        const val VOLUME_TYPE_PHOTO = 2
    }
}
