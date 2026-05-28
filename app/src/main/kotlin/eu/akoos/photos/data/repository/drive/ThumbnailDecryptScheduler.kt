package eu.akoos.photos.data.repository.drive

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ThumbDecryptSched"

/**
 * On-demand thumbnail decryption (v1.3 lazy-thumbnail architecture).
 *
 * Replaces the cold-sync "decrypt EVERY thumbnail up-front" loop in
 * [PhotoStreamService.refreshCloudPhotos] / [AlbumService.loadAlbumPhotos]: that loop
 * piled hundreds of libgojni JNI calls onto the IO dispatcher in a single burst and
 * tripped the `slice bounds out of range [:-1]` SIGABRT on Android-16 Samsung S22 BETA
 * once GC + Go-runtime memory layout interacted badly. The new path moves the decrypt
 * work to the moment a grid cell becomes visible, bounded by [semaphore] (2 concurrent
 * decrypts max — empirically high enough to keep scroll feeling instant, low enough to
 * stay well below the JNI / GC threshold that triggered SIGABRT).
 *
 * Each enqueue is deduped through [inFlight]: if the same linkId is already in progress
 * we don't fire a second job. Cancellations propagate via Job.cancel() — the
 * Semaphore.withPermit block honours coroutine cancellation, so scrolling past a cell
 * before its turn comes up does NOT cost JNI work.
 *
 * Parent key resolution: every photo's nodeKey was originally encrypted to its parent
 * link's key (root or an album). We cache `parentLinkId → decrypted parentKeyBytes` in
 * [parentKeyCache] so subsequent thumbnails in the same parent skip the second-tier
 * decrypt. The root key is resolved through [PhotosShareService.getRootLinkKeyBytes]
 * which itself caches.
 */
@Singleton
class ThumbnailDecryptScheduler @Inject constructor(
    private val cryptoHelper: DriveCryptoHelper,
    private val thumbnailHelpers: ThumbnailHelpers,
    private val photoListingDao: PhotoListingDao,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val shareService: PhotosShareService,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * Concurrent thumbnail decrypts queued at the scheduler level. The actual libgojni
     * calls are serialized by DriveCryptoHelper's cryptoLock anyway, so this value just
     * sizes the queue of in-progress decrypts; raising it doesn't increase JNI pressure.
     */
    private val semaphore = Semaphore(3)
    private val inFlight = ConcurrentHashMap<String, Job>()
    private val parentKeyCache = ConcurrentHashMap<String, ByteArray>()

    /**
     * Seed [parentKeyCache] with already-decrypted parent (root / album) keys. Called from
     * the sync pass which already had these in hand — saves us a per-photo
     * batchFetchLinkDetails round trip when the scheduler kicks in. Existing entries are
     * NOT overwritten; the scheduler may already have its own copy from an earlier album
     * decrypt.
     */
    fun populateParentKeys(keys: Map<String, ByteArray>) {
        keys.forEach { (parentLinkId, bytes) ->
            parentKeyCache.putIfAbsent(parentLinkId, bytes)
        }
    }

    /**
     * Request the thumbnail for [photo]. No-op when:
     *   • The row already has a decrypted thumbnailUrl (eager path or previous lazy run).
     *   • The row is missing any of the encrypted material we need.
     *   • A decrypt for the same linkId is already in flight (dedup).
     *
     * Returns immediately — the actual work runs on the IO dispatcher and surfaces to the
     * UI through the existing Flow on [PhotoListingDao.observeAll] /
     * [PhotoListingDao.observeByLinkIds].
     */
    fun request(userId: UserId, photo: PhotoListingEntity) {
        if (photo.thumbnailUrl != null) return
        val serverUrl = photo.serverThumbnailUrl ?: return
        val ckp = photo.contentKeyPacket ?: return
        val encNodeKey = photo.encNodeKey ?: return
        val encNodePass = photo.encNodePassphrase ?: return
        val parentLinkId = photo.parentLinkId ?: return
        val linkId = photo.linkId
        inFlight.computeIfAbsent(linkId) {
            scope.launch {
                try {
                    semaphore.withPermit {
                        runCatching {
                            decryptOne(
                                userId = userId,
                                linkId = linkId,
                                volumeId = photo.volumeId,
                                serverUrl = serverUrl,
                                serverToken = photo.serverThumbnailToken,
                                contentKeyPacketBase64 = ckp,
                                encNodeKey = encNodeKey,
                                encNodePass = encNodePass,
                                parentLinkId = parentLinkId,
                            )
                        }.onFailure { e ->
                            Log.w(TAG, "decrypt $linkId failed: ${e.message}")
                        }
                    }
                } finally {
                    inFlight.remove(linkId)
                }
            }
        }
    }

    /**
     * Cancel any in-flight decrypt for [linkId]. Called when the cell scrolls off-screen
     * so we don't waste CPU + JNI bandwidth on work the user no longer wants to see.
     */
    fun cancel(linkId: String) {
        inFlight.remove(linkId)?.cancel()
    }

    /**
     * Drop in-flight work and the parent-key cache. Called on sign-out — keeps decrypted
     * key material from outliving the session.
     */
    fun clear() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        parentKeyCache.values.forEach { it.fill(0) }
        parentKeyCache.clear()
    }

    private suspend fun decryptOne(
        userId: UserId,
        linkId: String,
        volumeId: String,
        serverUrl: String,
        serverToken: String?,
        contentKeyPacketBase64: String,
        encNodeKey: String,
        encNodePass: String,
        parentLinkId: String,
    ) {
        val cacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }

        // 1. Resolve parent's decrypted key (root / album / shared link parent).
        val parentKey = getParentKeyBytes(userId, parentLinkId, volumeId) ?: run {
            Log.w(TAG, "decryptOne $linkId: parent key for $parentLinkId unavailable")
            return
        }

        // 2. Decrypt this photo's node key with the parent's key.
        val nodeKeyBytes = runCatching {
            cryptoHelper.decryptNodeKey(encNodeKey, encNodePass, parentKey)
        }.getOrElse { e ->
            Log.w(TAG, "decryptOne $linkId: decryptNodeKey failed: ${e.message}")
            return
        }

        // 3. Decrypt session key from the persisted PKESK.
        val sessionKey = cryptoHelper.decryptSessionKey(contentKeyPacketBase64, nodeKeyBytes)

        // 4. Download + decrypt the encrypted thumbnail blob, save to disk cache.
        val info = ThumbnailUrlInfo(bareUrl = serverUrl, token = serverToken)
        val fileUrl = thumbnailHelpers.downloadAndDecryptBinary(
            info = info,
            nodeKeyBytes = nodeKeyBytes,
            sessionKey = sessionKey,
            linkId = linkId,
            cacheDir = cacheDir,
        )
        if (fileUrl == null) {
            Log.w(TAG, "decryptOne $linkId: downloadAndDecryptBinary returned null")
            return
        }

        // 5. Write the URL back to the row. The Flow-based observation re-emits this row
        //    and the grid cell rebinds with the new thumbnailUrl → AsyncImage renders.
        runCatching { photoListingDao.updateThumbnailUrl(linkId, fileUrl) }
            .onFailure { Log.w(TAG, "decryptOne $linkId: DB update failed: ${it.message}") }
    }

    /**
     * Resolves the decrypted nodeKey bytes for [parentLinkId]. Two cases:
     *   • Root link: short-circuit through [PhotosShareService.getRootLinkKeyBytes] (also
     *     cached at the share-service layer).
     *   • Album link: fetch the album's BatchLinkDto, decrypt its nodeKey with the root
     *     key, and memoise in [parentKeyCache] so subsequent thumbnails in the same album
     *     skip the round-trip.
     */
    private suspend fun getParentKeyBytes(userId: UserId, parentLinkId: String, volumeId: String): ByteArray? {
        // Root link path — keys are managed by PhotosShareService.
        if (parentLinkId == shareService.photosRootLinkId()) {
            return shareService.getRootLinkKeyBytes(userId)
        }

        parentKeyCache[parentLinkId]?.let { return it }

        val rootKey = shareService.getRootLinkKeyBytes(userId) ?: return null
        val albumDetail = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(parentLinkId))[parentLinkId]
            ?: run {
                Log.w(TAG, "getParentKeyBytes: album link $parentLinkId not in batch response")
                return null
            }
        val albumLink = albumDetail.link
        val albumNodeKey = albumLink.nodeKey ?: return null
        val albumNodePass = albumLink.nodePassphrase ?: return null
        return try {
            val bytes = cryptoHelper.decryptNodeKey(albumNodeKey, albumNodePass, rootKey)
            parentKeyCache[parentLinkId] = bytes
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "getParentKeyBytes: album $parentLinkId decrypt failed: ${e.message}")
            null
        }
    }
}
