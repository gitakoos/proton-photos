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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ThumbDecryptSched"

/**
 * On-demand thumbnail decryption (lazy-thumbnail architecture).
 *
 * Replaces the cold-sync "decrypt EVERY thumbnail up-front" loop in
 * [PhotoStreamService.refreshCloudPhotos] / [AlbumService.loadAlbumPhotos]: that loop
 * piled hundreds of libgojni JNI calls onto the IO dispatcher in a single burst and
 * tripped the `slice bounds out of range [:-1]` SIGABRT on Android-16 beta firmware
 * once GC + Go-runtime memory layout interacted badly. The new path moves the decrypt
 * work to the moment a grid cell becomes visible, bounded by [semaphore] (3 concurrent
 * decrypts max — empirically high enough to keep scroll feeling instant, low enough to
 * stay well below the JNI / GC threshold that triggered SIGABRT).
 *
 * ## Priority pipeline
 *
 * Requests are not launched immediately. They land in a single ordered [queue] drained by
 * a fixed pool of [WORKER_COUNT] workers. Ordering is a total sort over three keys, applied
 * in this precedence:
 *
 *   1. **Band** — [Band.VISIBLE] (viewport) always sorts ahead of [Band.PREFETCH]
 *      (look-ahead). A freshly visible cell therefore jumps the entire prefetch backlog.
 *   2. **Generation** — newer generations sort ahead of older ones within the same band.
 *      [bumpGeneration] advances the counter on each viewport change; queued work from an
 *      older generation only runs once the current generation's slice of its band is
 *      drained. This is the "pause on scroll, resume when idle" rule: a new visible burst
 *      parks all not-yet-started prefetch and stale visible work behind the fresh items
 *      without ever aborting an in-flight gopenpgp call (those cannot be interrupted safely
 *      mid-call, so running jobs always finish).
 *   3. **Sequence** — FIFO by request order within the same band+generation, so rows fill
 *      top-to-bottom instead of popping in a random order.
 *
 * Each enqueue is deduped through [enqueued]: if the same linkId is already queued or
 * running we don't add a second task. A new [Band.VISIBLE] request for a linkId already
 * sitting in the queue at [Band.PREFETCH] promotes that entry (and re-stamps it to the
 * current generation) instead of duplicating it. Cancellation removes a not-yet-started
 * task from the queue; a task already picked up by a worker runs to completion.
 *
 * The decrypted thumbnail is cached on disk as `thumb_<linkId>.jpg`; [cachedThumbnailUrl]
 * short-circuits the enqueue when that file already exists so a re-scroll over warm rows
 * costs nothing (re-pointing the DB row at the cached file if needed, without any decrypt).
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
    private val albumCryptoChain: AlbumCryptoChain,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Concurrency bound shared by the worker pool AND the ad-hoc
     * [decryptThumbnailToFileBounded] callers. The actual libgojni calls are serialized by
     * DriveCryptoHelper's cryptoLock anyway, so this just keeps the number of in-flight
     * download+decrypt jobs low enough to stay below the JNI / GC pressure threshold.
     * Both the gallery pipeline and ad-hoc callers draw from the same permits, so total
     * concurrent decrypts never exceeds this regardless of which path queued them.
     */
    private val semaphore = Semaphore(WORKER_COUNT)

    /** Priority band of a queued task. VISIBLE work always precedes PREFETCH work. */
    private enum class Band { VISIBLE, PREFETCH }

    /**
     * One queued decrypt. [generation] and [seq] freeze the request's position in the sort
     * order; [band] can be upgraded in place (PREFETCH → VISIBLE) when a visible request
     * arrives for an already-queued linkId, at which point [generation] is re-stamped to
     * the current value so the promoted item rides with the fresh burst.
     */
    private class Task(
        val userId: UserId,
        val photo: PhotoListingEntity,
        @Volatile var band: Band,
        @Volatile var generation: Long,
        val seq: Long,
    )

    /**
     * Ordered backlog. Guarded by [queueLock]; workers signal each other through
     * [available]. Kept as a plain list rather than a [java.util.PriorityQueue] because
     * band/generation promotion mutates entries already in the structure, which a heap
     * cannot reorder in place — the worker simply scans for the current best each turn,
     * which is cheap at the few-hundred-entry depth a viewport plus prefetch window reach.
     */
    private val queue = ArrayList<Task>()
    private val queueLock = Mutex()

    /**
     * Wakes an idle worker when new work is enqueued. Conflated: the workers re-scan the
     * whole queue each time they wake, so collapsing multiple signals into one is correct
     * and avoids unbounded buffering during a fast fling that enqueues many cells at once.
     */
    private val available = Channel<Unit>(Channel.CONFLATED)

    /** linkIds currently queued or running — the dedupe set, mirrors [queue] plus in-flight. */
    private val enqueued = ConcurrentHashMap.newKeySet<String>()

    /** Monotonic request counter — the FIFO tiebreaker within a band + generation. */
    private val seqCounter = AtomicLong(0L)

    /**
     * Current viewport generation. Advanced by [bumpGeneration] on each viewport change so
     * a fresh burst of visible requests sorts ahead of stale work in the same band. Reads
     * and writes are racy by design — an off-by-one generation only changes ordering, never
     * correctness, and the values are monotonic so the newest burst always wins.
     */
    @Volatile private var generation: Long = 0L

    private val parentKeyCache = ConcurrentHashMap<String, ByteArray>()
    /**
     * Shared-with-me album context map. Keyed by the album link id. When a thumbnail
     * for a photo in a shared album comes through after the [parentKeyCache] entry
     * expires (process kill, cache eviction during a long backgrounded session),
     * the cache-miss fallback in [getParentKeyBytes] needs to re-fetch the album
     * link via the share endpoint and decrypt it with the share key — NOT through
     * the recipient's own volume, which returns 404 because the album physically
     * lives on the owner's volume. Populated by [populateSharedAlbumContext] from
     * the recipient-side load path.
     *
     * Process-local only — the singleton scope drops on app restart and the next
     * open of the album re-seeds via [AlbumService.loadAlbumPhotos]. Persisting the
     * share key bytes to disk would be a security risk; the in-memory cache is the
     * right scope for now.
     */
    private val sharedAlbumContext = ConcurrentHashMap<String, AlbumCryptoChain.SharingContext>()

    init {
        repeat(WORKER_COUNT) { worker() }
    }

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
     * Register a shared-with-me album so the cache-miss fallback in [getParentKeyBytes]
     * routes through the share endpoint instead of the recipient's own volume. Without
     * this hook, a thumbnail decrypt that misses the in-memory parent-key cache hits
     * the recipient's `batchFetchLinkDetails(volumeId)` endpoint, gets a 404 because
     * the album physically lives on the owner's volume, and silently leaves the
     * thumbnail at a placeholder.
     *
     * [AlbumService.loadAlbumPhotos] calls this alongside [populateParentKeys] so the
     * fast path (cache hit) keeps working unchanged and the slow path (cache miss)
     * has the share key + share id it needs to walk the chain correctly.
     */
    fun populateSharedAlbumContext(ctx: AlbumCryptoChain.SharingContext) {
        sharedAlbumContext[ctx.albumLinkId] = ctx
        parentKeyCache.putIfAbsent(ctx.albumLinkId, ctx.albumKeyBytes)
    }

    /**
     * Request the thumbnail for [photo] at [Band.VISIBLE] priority (the viewport band).
     * No-op when:
     *   • The row already has a decrypted thumbnailUrl (eager path or previous lazy run).
     *   • The decrypted thumbnail already sits in the on-disk cache.
     *   • The row is missing any of the encrypted material we need.
     *   • A decrypt for the same linkId is already queued or running (dedupe).
     *
     * A visible request advances the viewport [generation] so the burst it belongs to
     * sorts ahead of older work; if the linkId is already queued at [Band.PREFETCH] it is
     * promoted to VISIBLE in place. Returns immediately — the actual work runs on a worker
     * and surfaces to the UI through the existing Flow on [PhotoListingDao.observeAll] /
     * [PhotoListingDao.observeByLinkIds].
     */
    fun request(userId: UserId, photo: PhotoListingEntity) {
        bumpGeneration()
        enqueue(userId, photo, Band.VISIBLE)
    }

    /**
     * Enqueue [photos] at [Band.PREFETCH] priority — the look-ahead band that fills the next
     * couple of screens in the scroll direction so they are already warm by the time the
     * user reaches them. Prefetch work always sorts behind every visible request, and a
     * later [request] for any of these linkIds promotes it to the viewport band. Rows that
     * are already decrypted, cached, queued, or running are skipped. Does NOT bump the
     * generation — only an actual viewport change does that.
     */
    fun prefetch(userId: UserId, photos: List<PhotoListingEntity>) {
        photos.forEach { enqueue(userId, it, Band.PREFETCH) }
    }

    /** Advance the viewport generation so the next visible burst outranks stale work. */
    private fun bumpGeneration() {
        generation++
    }

    private fun enqueue(userId: UserId, photo: PhotoListingEntity, band: Band) {
        // Fail fast on rows that carry no decryptable thumbnail material; the worker
        // re-reads the same fields off the entity when it runs the task.
        if (photo.thumbnailUrl != null) return
        if (photo.serverThumbnailUrl == null) return
        if (photo.contentKeyPacket == null) return
        if (photo.encNodeKey == null) return
        if (photo.encNodePassphrase == null) return
        if (photo.parentLinkId == null) return
        val linkId = photo.linkId
        // Disk-cache short-circuit: a warm thumbnail needs no decrypt. If the decrypted file
        // is already on disk but this row's thumbnailUrl is still null (decrypted in a prior
        // session before the DB write landed, or persisted then evicted from memory) we just
        // re-point the row at the cached file — a cheap DB write, no JNI — so the cell binds.
        cachedThumbnailUrl(linkId)?.let { url ->
            scope.launch { runCatching { photoListingDao.updateThumbnailUrl(linkId, url) } }
            return
        }

        if (!enqueued.add(linkId)) {
            // Already queued or running. A visible request still upgrades a pending
            // prefetch entry so it rides ahead with the current burst.
            if (band == Band.VISIBLE) promoteToVisible(linkId)
            return
        }
        val task = Task(userId, photo, band, generation, seqCounter.getAndIncrement())
        scope.launch {
            queueLock.withLock { queue.add(task) }
            available.trySend(Unit)
        }
    }

    /** Upgrade an already-queued [linkId] from PREFETCH to VISIBLE and re-stamp its
     *  generation so it sorts with the active burst. No-op if it is already running or
     *  already VISIBLE. */
    private fun promoteToVisible(linkId: String) {
        val gen = generation
        scope.launch {
            queueLock.withLock {
                queue.firstOrNull { it.photo.linkId == linkId }?.let { t ->
                    if (t.band != Band.VISIBLE) {
                        t.band = Band.VISIBLE
                        t.generation = gen
                    }
                }
            }
            available.trySend(Unit)
        }
    }

    /**
     * Pull the highest-priority task under the sort order (VISIBLE before PREFETCH, newer
     * generation first, then FIFO by seq). Returns null when the queue is empty.
     */
    private suspend fun takeNext(): Task? = queueLock.withLock {
        if (queue.isEmpty()) return@withLock null
        var bestIdx = 0
        for (i in 1 until queue.size) {
            if (higherPriority(queue[i], queue[bestIdx])) bestIdx = i
        }
        queue.removeAt(bestIdx)
    }

    /** True when [a] should run before [b]: band, then generation (descending), then seq. */
    private fun higherPriority(a: Task, b: Task): Boolean {
        if (a.band != b.band) return a.band == Band.VISIBLE
        if (a.generation != b.generation) return a.generation > b.generation
        return a.seq < b.seq
    }

    private fun worker() {
        scope.launch {
            for (signal in available) {
                // Drain everything currently runnable before parking again, so one signal
                // after a fling clears the whole burst this worker can reach.
                while (true) {
                    val task = takeNext() ?: break
                    // A burst (e.g. a 60-row prefetch) collapses into one CONFLATED signal, so
                    // wake a sibling to share the remaining backlog. The signal is a no-op
                    // once the queue drains — the woken worker just re-parks.
                    available.trySend(Unit)
                    val linkId = task.photo.linkId
                    try {
                        semaphore.withPermit {
                            runCatching { decryptOne(task.userId, task.photo) }
                                .onFailure { e -> Log.w(TAG, "decrypt $linkId failed: ${e.message}") }
                        }
                    } finally {
                        enqueued.remove(linkId)
                    }
                }
            }
        }
    }

    /**
     * Cancel a not-yet-started decrypt for [linkId]. Called when the cell scrolls off-screen
     * so we don't waste CPU + JNI bandwidth on work the user no longer wants to see. A task
     * a worker has already picked up runs to completion — an in-flight gopenpgp decrypt
     * can't be aborted mid-call safely.
     */
    fun cancel(linkId: String) {
        if (!enqueued.contains(linkId)) return
        scope.launch {
            val removed = queueLock.withLock {
                val idx = queue.indexOfFirst { it.photo.linkId == linkId }
                if (idx >= 0) { queue.removeAt(idx); true } else false
            }
            // Only clear the dedupe marker if we actually pulled it from the queue; if it
            // wasn't there it is already running and the worker's finally block owns removal.
            if (removed) enqueued.remove(linkId)
        }
    }

    /**
     * Drop queued work and the parent-key cache. Called on sign-out — keeps decrypted
     * key material from outliving the session. Tasks a worker has already started finish
     * naturally; nothing new is dispatched.
     */
    fun clear() {
        scope.launch {
            queueLock.withLock { queue.clear() }
            enqueued.clear()
        }
        parentKeyCache.values.forEach { it.fill(0) }
        parentKeyCache.clear()
    }

    /** The `file://` URL of the decrypted thumbnail for [linkId] when it is already cached
     *  on disk, or null when no decrypt has produced it yet. Mirrors the cache path + URL
     *  shape [ThumbnailHelpers.downloadAndDecryptBinary] writes. */
    private fun cachedThumbnailUrl(linkId: String): String? {
        val f = File(File(context.cacheDir, "thumbnails"), "thumb_$linkId.jpg")
        return if (f.exists() && f.length() > 0) "file://${f.absolutePath}" else null
    }

    private suspend fun decryptOne(userId: UserId, photo: PhotoListingEntity) {
        val linkId = photo.linkId
        val fileUrl = decryptThumbnailToFile(
            userId = userId,
            linkId = linkId,
            volumeId = photo.volumeId,
            serverUrl = photo.serverThumbnailUrl ?: return,
            serverToken = photo.serverThumbnailToken,
            contentKeyPacketBase64 = photo.contentKeyPacket ?: return,
            encNodeKey = photo.encNodeKey ?: return,
            encNodePass = photo.encNodePassphrase ?: return,
            parentLinkId = photo.parentLinkId ?: return,
        ) ?: return
        // Write the URL back to the row. The Flow-based observation re-emits this row
        // and the grid cell rebinds with the new thumbnailUrl → AsyncImage renders.
        runCatching { photoListingDao.updateThumbnailUrl(linkId, fileUrl) }
            .onFailure { Log.w(TAG, "decryptOne $linkId: DB update failed: ${it.message}") }
    }

    /**
     * Shared decryption core used by both the photo-listing Flow-driven pipeline and any
     * caller that needs an ad-hoc decrypt without a backing DB row (e.g. the cloud trash
     * grid, where entries are an in-memory list not tracked by [PhotoListingDao]).
     * Returns the file:// URI of the decrypted thumbnail on disk, or null on any failure.
     * The shared on-disk cache (keyed by linkId) means re-entries hit instantly.
     */
    suspend fun decryptThumbnailToFile(
        userId: UserId,
        linkId: String,
        volumeId: String,
        serverUrl: String,
        serverToken: String?,
        contentKeyPacketBase64: String,
        encNodeKey: String,
        encNodePass: String,
        parentLinkId: String,
    ): String? {
        val cacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }
        val parentKey = getParentKeyBytes(userId, parentLinkId, volumeId) ?: run {
            Log.w(TAG, "decryptThumbnailToFile $linkId: parent key for $parentLinkId unavailable")
            return null
        }
        val nodeKeyBytes = runCatching {
            cryptoHelper.decryptNodeKey(encNodeKey, encNodePass, parentKey)
        }.getOrElse { e ->
            Log.w(TAG, "decryptThumbnailToFile $linkId: decryptNodeKey failed: ${e.message}")
            return null
        }
        val sessionKey = cryptoHelper.decryptSessionKey(contentKeyPacketBase64, nodeKeyBytes)
        val info = ThumbnailUrlInfo(bareUrl = serverUrl, token = serverToken)
        return thumbnailHelpers.downloadAndDecryptBinary(
            info = info,
            nodeKeyBytes = nodeKeyBytes,
            sessionKey = sessionKey,
            linkId = linkId,
            cacheDir = cacheDir,
        )
    }

    /** Permit-bounded variant of [decryptThumbnailToFile] so ad-hoc callers ride the same
     *  worker-count semaphore the gallery path uses. Without this an external caller could
     *  spin up dozens of parallel decrypts and starve the gallery. */
    suspend fun decryptThumbnailToFileBounded(
        userId: UserId,
        linkId: String,
        volumeId: String,
        serverUrl: String,
        serverToken: String?,
        contentKeyPacketBase64: String,
        encNodeKey: String,
        encNodePass: String,
        parentLinkId: String,
    ): String? = semaphore.withPermit {
        decryptThumbnailToFile(
            userId = userId,
            linkId = linkId,
            volumeId = volumeId,
            serverUrl = serverUrl,
            serverToken = serverToken,
            contentKeyPacketBase64 = contentKeyPacketBase64,
            encNodeKey = encNodeKey,
            encNodePass = encNodePass,
            parentLinkId = parentLinkId,
        )
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

        // Shared-with-me album fallback: the seeded cache entry expired (process
        // restart, long background) but the singleton-scoped context map remembers
        // the share id + share private key. Re-fetch the album link through the
        // SHARE endpoint (not the recipient's own volume) and decrypt the album
        // NodeKey with the share key. Routing through batchFetchLinkDetails on the
        // recipient's volume would 404 because the album physically lives on the
        // owner's volume.
        sharedAlbumContext[parentLinkId]?.let { ctx ->
            val albumDetail = linkDetailHelpers.batchFetchLinkDetailsViaShare(
                userId = userId,
                shareId = ctx.sharingShareId,
                linkIds = listOf(parentLinkId),
            )[parentLinkId] ?: run {
                Log.w(TAG, "getParentKeyBytes: shared album $parentLinkId not in fetch_metadata response")
                return null
            }
            val albumNodeKey = albumDetail.link.nodeKey ?: return null
            val albumNodePass = albumDetail.link.nodePassphrase ?: return null
            val bytes = albumCryptoChain.decryptAlbumKey(
                nodeKeyArmored = albumNodeKey,
                nodePassphraseArmored = albumNodePass,
                parentKeyBytes = ctx.sharedShareKeyBytes,
                contextHint = "scheduler shared-album cache-miss albumLinkId=$parentLinkId shareId=${ctx.sharingShareId}",
            ) ?: return null
            parentKeyCache[parentLinkId] = bytes
            return bytes
        }

        // Owner-side fallback: the album lives in the recipient's own volume, so
        // the standard batchFetchLinkDetails path is the right answer.
        val rootKey = shareService.getRootLinkKeyBytes(userId) ?: return null
        val albumDetail = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(parentLinkId))[parentLinkId]
            ?: run {
                Log.w(TAG, "getParentKeyBytes: album link $parentLinkId not in batch response")
                return null
            }
        val albumLink = albumDetail.link
        val albumNodeKey = albumLink.nodeKey ?: return null
        val albumNodePass = albumLink.nodePassphrase ?: return null
        val bytes = albumCryptoChain.decryptAlbumKey(
            nodeKeyArmored = albumNodeKey,
            nodePassphraseArmored = albumNodePass,
            parentKeyBytes = rootKey,
            contextHint = "scheduler owner-side cache-miss albumLinkId=$parentLinkId",
        ) ?: return null
        parentKeyCache[parentLinkId] = bytes
        return bytes
    }

    private companion object {
        /**
         * Worker pool size and concurrency bound in one. Three keeps scroll feeling instant
         * while staying well under the JNI / GC pressure that tripped SIGABRT on the cold-sync
         * burst; raising it does not help because DriveCryptoHelper's cryptoLock serializes
         * the actual libgojni calls anyway.
         */
        const val WORKER_COUNT = 3
    }
}
