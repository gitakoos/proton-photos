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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.crypto.CryptoServiceClient
import eu.akoos.photos.crypto.DecryptPriority
import eu.akoos.photos.crypto.DecryptPriorityContext
import eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.util.DeviceHealthPolicy
import eu.akoos.photos.util.HEALTH_PAUSE_POLL_MS
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 * tripped the `slice bounds out of range [:-1]` SIGABRT under certain firmware / GC
 * interactions once GC + Go-runtime memory layout aligned badly. The new path moves the decrypt
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
    private val cryptoServiceClient: CryptoServiceClient,
    private val thumbnailHelpers: ThumbnailHelpers,
    private val photoListingDao: PhotoListingDao,
    private val albumPhotoMembershipDao: AlbumPhotoMembershipDao,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val shareService: PhotosShareService,
    private val albumCryptoChain: AlbumCryptoChain,
    private val thumbnailUrlStore: ThumbnailUrlStore,
    @ApplicationContext private val context: Context,
    private val deviceHealth: DeviceHealthPolicy,
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

    /**
     * Priority band of a queued task. Lower ordinal = higher priority: VISIBLE (viewport)
     * runs before PREFETCH (look-ahead), which runs before BACKGROUND (whole-library warm-up
     * that only drains while the viewport and prefetch windows are idle).
     */
    private enum class Band { VISIBLE, PREFETCH, BACKGROUND }

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

    /** Counts completed decrypts so the on-disk cache trim runs only once every
     *  [TRIM_CHECK_INTERVAL] writes instead of on every single thumbnail. */
    private val decryptCounter = AtomicLong(0L)

    /** Guards [trimThumbnailCache] so at most one eviction pass runs at a time. */
    private val trimming = AtomicBoolean(false)

    /** One background warm-up at a time — a second trigger (resume, refresh) while one is running is
     *  a no-op instead of stacking another full library walk and its row set in memory. */
    private val backfilling = AtomicBoolean(false)

    /**
     * Earliest wall-clock ms at which the next BACKGROUND (whole-library warm-up) decrypt may start.
     * Every worker that picks up a BACKGROUND task reserves the next slot here and waits it out
     * BEFORE taking a semaphore permit, so the aggregate warm-up CDN request rate across all workers
     * is capped to one fetch per [BACKGROUND_PACE_MS] (a gentle trickle). VISIBLE / PREFETCH never
     * touch this gate, so the viewport and look-ahead stay instant even while a warm-up trickles.
     */
    private val backgroundPaceUntilMs = AtomicLong(0L)

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

    /**
     * Album + Collection cover linkIds: warmed ahead of the bulk library and never LRU-evicted,
     * so covers stay populated even when the full thumbnail set exceeds the cache budget. Bounded
     * by [MAX_PINNED_COVERS] so the never-evicted set can't itself crowd the cache.
     */
    private val pinnedCoverLinkIds = ConcurrentHashMap.newKeySet<String>()

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

    /**
     * Mark [linkIds] as album / Collection cover thumbnails: warm any not-yet-cached one at
     * [Band.PREFETCH] (ahead of the bulk [backfillAll] warm-up) and exempt them all from LRU
     * eviction. Keeps album and Collection covers populated on a large library whose full
     * thumbnail set can't fit the cache budget, where the cold tail would otherwise never warm
     * or get evicted. The pinned set is capped at [MAX_PINNED_COVERS] as a backstop.
     */
    fun pinCovers(userId: UserId, linkIds: Collection<String>) {
        if (linkIds.isEmpty()) return
        scope.launch {
            for (linkId in linkIds) {
                if (pinnedCoverLinkIds.size < MAX_PINNED_COVERS) pinnedCoverLinkIds.add(linkId)
                val row = runCatching { photoListingDao.getByLinkId(linkId) }.getOrNull() ?: continue
                enqueue(userId, row, Band.PREFETCH)
            }
        }
    }

    /**
     * Warm the background thumbnail cache, newest photos first, stopping once it reaches the
     * adaptive budget ([trimTargetBytes]). The size is sampled (not estimated) every [BACKFILL_SAMPLE_EVERY]
     * enqueues while pacing enqueue to the decrypt rate, so the guard tracks real on-disk growth
     * and the warm-up stops near the budget regardless of how large each photo's HD thumbnail is.
     *
     * The pass also stops after [MAX_BACKFILL_THUMBNAILS] enqueues regardless of bytes, so a library
     * of many small or near-identical thumbnails (whose bytes never reach the budget) can't enqueue
     * tens of thousands of decrypts in one burst and flood the decrypt pipeline.
     *
     * Bounding the warm-up here is what keeps a large library from churning: once the cache is at
     * budget this enqueues nothing, so a later refresh won't re-decrypt thumbnails a previous trim
     * evicted. The cold tail beyond the budget decrypts on demand when scrolled into view.
     */
    fun backfillAll(userId: UserId) {
        if (!backfilling.compareAndSet(false, true)) return
        scope.launch {
            try {
                // Photos in a client-side hidden album are kept out of the proactive warm-up; snapshot the
                // member set once so the pass stays consistent across its pages. Resolved via the DAO and
                // DataStore directly because AlbumService injects this scheduler, so injecting it back
                // would form a dependency cycle. The on-demand request / prefetch paths do not consult
                // this set, so revealing a hidden album still warms its thumbnails on demand.
                val hiddenLinkIds = runCatching {
                    val hiddenAlbumIds = context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_ALBUM_IDS] ?: emptySet()
                    if (hiddenAlbumIds.isEmpty()) emptySet()
                    else albumPhotoMembershipDao.observeAssociatedPhotoLinkIdsForAlbums(hiddenAlbumIds).first().toSet()
                }.getOrDefault(emptySet())
                val budget = trimTargetBytes()
                var cacheBytes = thumbnailCacheBytes()
                // Walk the library newest-first in bounded pages so only one page of rows (and their
                // crypto material) is ever resident, instead of the whole undecrypted set at once.
                var beforeTime = Long.MAX_VALUE
                var enqueuedCount = 0
                page@ while (cacheBytes < budget && enqueuedCount < MAX_BACKFILL_THUMBNAILS) {
                    // Defer this background walk while the phone is hot, low on battery, or in the power saver; it
                    // resumes on its own once conditions clear. Not gated on interaction: it runs quietly in the
                    // background and does not compete with the UI.
                    while (!deviceHealth.backgroundWorkAllowed()) delay(HEALTH_PAUSE_POLL_MS)
                    val batch = runCatching {
                        photoListingDao.getUndecryptedThumbnailsBefore(userId.id, beforeTime, BACKFILL_PAGE_SIZE)
                    }.getOrElse { e -> Log.w(TAG, "backfillAll: query failed: ${e.message}"); break@page }
                    if (batch.isEmpty()) break
                    for (row in batch) {
                        // Stop at EITHER guard: the byte budget (varied real thumbnails) or the count
                        // cap. The cap is what bounds a library of many small or near-identical
                        // thumbnails whose bytes never reach the budget — without it the warm-up would
                        // enqueue tens of thousands of decrypts in one burst and flood the pipeline.
                        if (cacheBytes >= budget || enqueuedCount >= MAX_BACKFILL_THUMBNAILS) break@page
                        if (row.linkId in hiddenLinkIds) continue
                        enqueue(userId, row, Band.BACKGROUND)
                        // Every sample interval, let the workers catch up (so in-flight decrypts can't
                        // outrun the budget guard) and re-measure the cache off disk.
                        if (++enqueuedCount % BACKFILL_SAMPLE_EVERY == 0) {
                            while (queueLock.withLock { queue.size } > BACKFILL_QUEUE_HIGH_WATER) delay(150)
                            cacheBytes = thumbnailCacheBytes()
                        }
                    }
                    if (batch.size < BACKFILL_PAGE_SIZE) break
                    beforeTime = batch.last().captureTime
                }
                Log.d(TAG, "backfillAll: enqueued $enqueuedCount thumbnail(s) (cap $MAX_BACKFILL_THUMBNAILS), cache ~${cacheBytes / (1024 * 1024)}MB")
            } finally {
                backfilling.set(false)
            }
        }
    }

    /** Advance the viewport generation so the next visible burst outranks stale work. */
    private fun bumpGeneration() {
        generation++
    }

    private fun enqueue(userId: UserId, photo: PhotoListingEntity, band: Band) {
        // Fail fast on rows that carry no decryptable thumbnail material; the worker
        // re-reads the same fields off the entity when it runs the task.
        // Every caller (request / prefetch / pinCovers / backfillAll) hydrates a FULL row via a
        // SELECT * query (getByLinkId / getByLinkIds / getUndecryptedThumbnailsBefore), so this
        // field IS populated when a URL was persisted and the check genuinely short-circuits the
        // already-decrypted row. The timeline's lite projection drops thumbnailUrl but never reaches
        // here. The authoritative short-circuit for a warm-but-unpersisted row is cachedThumbnailUrl()
        // below, so this stays correct even if a future caller ever passed a projection without it.
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
            // Paint the cell now: the timeline projection no longer carries thumbnailUrl, so the store
            // is what repaints this tile; the DB write is for persistence + next-launch seeding.
            thumbnailUrlStore.put(linkId, url)
            scope.launch {
                runCatching { photoListingDao.updateThumbnailUrl(linkId, url) }
                // Touch so a re-viewed thumbnail counts as young and survives LRU eviction —
                // otherwise the warm-up's decrypt order, not actual use, decides what gets dropped.
                runCatching {
                    File(File(context.cacheDir, "thumbnails"), "thumb_$linkId.jpg")
                        .setLastModified(System.currentTimeMillis())
                }
            }
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

    /** True when [a] should run before [b]: band (lower ordinal first), then generation
     *  (descending), then seq. */
    private fun higherPriority(a: Task, b: Task): Boolean {
        if (a.band != b.band) return a.band.ordinal < b.band.ordinal
        if (a.generation != b.generation) return a.generation > b.generation
        return a.seq < b.seq
    }

    /**
     * Space out BACKGROUND warm-up decrypts to one per [BACKGROUND_PACE_MS] across the whole worker
     * pool, so the aggregate warm-up CDN request rate is a trickle instead of a flood (proactively
     * keeping the whole-library warm-up from tripping a 429 in the first place). Reserves the next
     * slot atomically (concurrent workers claim staggered slots rather than the same one), then
     * sleeps until it arrives.
     *
     * Called BEFORE the semaphore permit is taken, so a paced BACKGROUND task never holds a worker
     * permit while it waits: a freshly VISIBLE thumbnail can grab all [WORKER_COUNT] permits
     * immediately mid-warm-up. Only BACKGROUND tasks call this; VISIBLE and PREFETCH are unpaced.
     */
    private suspend fun paceBackground() {
        val now = System.currentTimeMillis()
        val slot = backgroundPaceUntilMs.updateAndGet { prev -> maxOf(prev, now) + BACKGROUND_PACE_MS }
        val wait = slot - BACKGROUND_PACE_MS - now
        if (wait > 0) delay(wait)
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
                    val background = task.band == Band.BACKGROUND
                    // Trickle the warm-up: BACKGROUND tasks wait out the shared pace gate BEFORE taking a
                    // permit, so the aggregate warm-up fetch rate stays gentle without ever occupying a
                    // worker permit a newly VISIBLE thumbnail needs. VISIBLE / PREFETCH skip the gate.
                    if (background) paceBackground()
                    try {
                        semaphore.withPermit {
                            // The whole-library warm-up (BACKGROUND band) rides the shared CDN cooldown so a
                            // 429 burst self-limits; VISIBLE / PREFETCH fetches stay responsive.
                            runCatching {
                                if (background) {
                                    // Warm-up yields the process-global crypto gate to interactive decrypts.
                                    withContext(DecryptPriorityContext(DecryptPriority.BACKGROUND)) {
                                        decryptOne(task.userId, task.photo, true)
                                    }
                                } else {
                                    decryptOne(task.userId, task.photo, false)
                                }
                            }.onFailure { e -> Log.w(TAG, "decrypt $linkId failed: ${e.message}") }
                        }
                    } finally {
                        enqueued.remove(linkId)
                    }
                    // Keep the on-disk thumbnail cache under its size cap. Sampled once every
                    // TRIM_CHECK_INTERVAL decrypts so a large library's background warm-up can't
                    // grow the cache without bound, while staying off the per-thumbnail hot path.
                    if (decryptCounter.incrementAndGet() % TRIM_CHECK_INTERVAL == 0L) {
                        scope.launch { trimThumbnailCache() }
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
     *
     * Zeroing the cache arrays here is safe even while a worker is mid-decrypt: [getParentKeyBytes]
     * hands every worker a defensive copy of the key, so no in-flight decrypt is ever reading from
     * the cache's own arrays this wipes. That copy is short-lived (dropped when the decrypt returns),
     * so it does not meaningfully extend how long key material lives past the session.
     */
    fun clear() {
        scope.launch {
            queueLock.withLock { queue.clear() }
            enqueued.clear()
        }
        thumbnailUrlStore.clear()
        parentKeyCache.values.forEach { it.fill(0) }
        parentKeyCache.clear()
        pinnedCoverLinkIds.clear()
        // Zero and drop the shared-album crypto contexts too, so a signed-out session's share /
        // album key bytes don't outlive it in this Singleton's heap.
        sharedAlbumContext.values.forEach {
            it.sharedShareKeyBytes.fill(0)
            it.albumKeyBytes.fill(0)
        }
        sharedAlbumContext.clear()
    }

    /** The `file://` URL of the decrypted thumbnail for [linkId] when it is already cached
     *  on disk, or null when no decrypt has produced it yet. Mirrors the cache path + URL
     *  shape [ThumbnailHelpers.downloadAndDecryptBinary] writes. */
    private fun cachedThumbnailUrl(linkId: String): String? {
        val f = File(File(context.cacheDir, "thumbnails"), "thumb_$linkId.jpg")
        return if (f.exists() && f.length() > 0) "file://${f.absolutePath}" else null
    }

    /**
     * Evict the oldest decrypted thumbnails once the on-disk cache passes the adaptive budget
     * ([maxThumbCacheBytes]), down to 90% of it. Files are ranked by last-modified time, and a
     * re-viewed thumbnail is touched so it counts as young, so genuine least-recently-used entries
     * (the cold tail of a large library) are dropped first. Each evicted file's row
     * has its thumbnailUrl nulled so the cell re-decrypts the next time it scrolls into view —
     * a re-warm is one local decrypt, never a network round-trip, because the crypto material
     * stays on the row. Only the final `thumb_<linkId>.jpg` files count toward the cap; the
     * transient `thumb_enc_*` / `thumb_dec_*` work files have no `.jpg` suffix and are skipped
     * by the LRU, but are separately swept here once stale (see [sweepOrphanThumbTemps]).
     */
    private suspend fun trimThumbnailCache() {
        if (!trimming.compareAndSet(false, true)) return
        try {
            sweepOrphanThumbTemps()
            val cap = maxThumbCacheBytes()
            // Cheap streaming size check FIRST. The common case — the cache at or under budget —
            // exits here without ever materialising a File[] of every cached thumbnail. That listing
            // of a large cache (30k+ files) ran every TRIM_CHECK_INTERVAL decrypts and was the
            // allocation that slowed the pipeline as the cache grew and tipped a near-full heap into
            // OOM on big libraries.
            if (thumbnailCacheBytes() <= cap) return

            // Genuinely over budget: stream the directory once, collecting a light record per file
            // (a single stat each via readAttributes) rather than a File[] plus a second stat per
            // file, then evict oldest-first down to the low-water target.
            class Entry(val path: java.nio.file.Path, val linkId: String, val mtime: Long, val size: Long)
            val entries = ArrayList<Entry>()
            var total = 0L
            val dir = File(context.cacheDir, "thumbnails")
            val ok = runCatching {
                java.nio.file.Files.newDirectoryStream(dir.toPath()).use { stream ->
                    for (path in stream) {
                        val name = path.fileName.toString()
                        if (!name.startsWith("thumb_") || !name.endsWith(".jpg")) continue
                        val attrs = runCatching {
                            java.nio.file.Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
                        }.getOrNull() ?: continue
                        val sz = attrs.size()
                        total += sz
                        entries.add(Entry(path, name.removePrefix("thumb_").removeSuffix(".jpg"), attrs.lastModifiedTime().toMillis(), sz))
                    }
                }
            }.onFailure { Log.w(TAG, "trim: directory stream failed: ${it.message}") }.isSuccess
            if (!ok || total <= cap) return

            val target = (cap / 10) * 9
            entries.sortBy { it.mtime }
            val evicted = ArrayList<String>()
            for (e in entries) {
                if (total <= target) break
                // Never evict a pinned album / Collection cover, even if it is the oldest file.
                if (e.linkId in pinnedCoverLinkIds) continue
                if (runCatching { java.nio.file.Files.deleteIfExists(e.path) }.getOrDefault(false)) {
                    total -= e.size
                    evicted += e.linkId
                }
            }
            if (evicted.isNotEmpty()) {
                runCatching { evicted.chunked(500).forEach { photoListingDao.clearThumbnailUrlsByLinkIds(it) } }
                    .onFailure { Log.w(TAG, "trim: clearing ${evicted.size} rows failed: ${it.message}") }
                // Drop the evicted URLs from the store too so their cells stop pointing at deleted files
                // and re-decrypt on next scroll (mirrors the DB null-out above).
                thumbnailUrlStore.remove(evicted)
                Log.d(TAG, "thumb cache trim: evicted ${evicted.size}, now ${total / (1024 * 1024)}MB")
            }
        } finally {
            trimming.set(false)
        }
    }

    /**
     * Delete `thumb_enc_*` / `thumb_dec_*` decrypt work temps that outlived their decrypt. The
     * normal path removes them in a finally the instant the decrypt returns (well under a second),
     * so anything older than [ORPHAN_TEMP_MAX_AGE_MS] was orphaned by a process kill mid-decrypt.
     * The age gate guarantees an in-flight decrypt's temp is never touched.
     */
    private fun sweepOrphanThumbTemps() {
        val dir = File(context.cacheDir, "thumbnails")
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - ORPHAN_TEMP_MAX_AGE_MS
        runCatching {
            java.nio.file.Files.newDirectoryStream(dir.toPath()).use { stream ->
                for (path in stream) {
                    val name = path.fileName.toString()
                    if (!name.startsWith("thumb_enc_") && !name.startsWith("thumb_dec_")) continue
                    val mtime = runCatching { java.nio.file.Files.getLastModifiedTime(path).toMillis() }.getOrNull()
                    if (mtime != null && mtime < cutoff) {
                        runCatching { java.nio.file.Files.deleteIfExists(path) }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "trim: orphan temp sweep failed: ${it.message}") }
    }

    /** Current total size of the decrypted-thumbnail cache on disk, in bytes. Streams the directory
     *  so the frequently-sampled warm-up path never materialises a File[] for every cached thumbnail
     *  (a 30k-file listing was the allocation that tipped a near-full heap into OOM). */
    private fun thumbnailCacheBytes(): Long {
        val dir = File(context.cacheDir, "thumbnails")
        if (!dir.isDirectory) return 0L
        var total = 0L
        runCatching {
            java.nio.file.Files.newDirectoryStream(dir.toPath()).use { stream ->
                for (path in stream) {
                    val name = path.fileName.toString()
                    if (name.startsWith("thumb_") && name.endsWith(".jpg")) {
                        total += runCatching { java.nio.file.Files.size(path) }.getOrDefault(0L)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "thumbnailCacheBytes: ${it.message}") }
        // Mirror the just-measured size into the perf diagnostics (byte total only, no paths) so the
        // copied diagnostics show the on-disk thumbnail cache against the heap.
        eu.akoos.photos.util.PerfDiagnostics.thumbnailCacheBytes = total
        return total
    }

    /**
     * Adaptive disk budget for decrypted thumbnails: up to a 1/[FREE_SPACE_DIVISOR] slice of the
     * cache volume's free space, floored at [MIN_THUMB_CACHE_BYTES] so small libraries always fit
     * and capped at [MAX_THUMB_CACHE_CEILING] so a huge library on a roomy device warms fully
     * without monopolising storage. The old fixed 1 GB cap was smaller than a 10k+ photo library's
     * thumbnail set, which forced perpetual evict-then-re-decrypt churn (older photos never kept a
     * thumbnail and re-scrolling re-decrypted them endlessly).
     */
    private fun maxThumbCacheBytes(): Long {
        val usable = runCatching { File(context.cacheDir, "thumbnails").usableSpace }.getOrDefault(0L)
        return (usable / FREE_SPACE_DIVISOR).coerceIn(MIN_THUMB_CACHE_BYTES, MAX_THUMB_CACHE_CEILING)
    }

    /** Low-water mark a trim evicts down to — 90% of the current budget, leaving headroom so the
     *  cache isn't re-trimmed on every subsequent decrypt once it first reaches the cap. */
    private fun trimTargetBytes(): Long = (maxThumbCacheBytes() / 10) * 9

    private suspend fun decryptOne(userId: UserId, photo: PhotoListingEntity, background: Boolean) {
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
            background = background,
        ) ?: return
        // Repaint just this cell through the in-memory store (the timeline projection no longer
        // carries thumbnailUrl, so a per-row DB write no longer re-emits the whole library). The DB
        // write persists the URL for next-launch seeding.
        thumbnailUrlStore.put(linkId, fileUrl)
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
        background: Boolean = false,
    ): String? {
        val cacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }
        val parentKey = getParentKeyBytes(userId, parentLinkId, volumeId) ?: run {
            Log.w(TAG, "decryptThumbnailToFile $linkId: parent key for $parentLinkId unavailable")
            return null
        }
        val nodeKeyBytes = runCatching {
            cryptoServiceClient.decryptNodeKey(encNodeKey, encNodePass, parentKey)
        }.getOrElse { e ->
            Log.w(TAG, "decryptThumbnailToFile $linkId: decryptNodeKey failed: ${e.message}")
            return null
        }
        val sessionKey = cryptoServiceClient.decryptSessionKey(contentKeyPacketBase64, nodeKeyBytes)
        val first = thumbnailHelpers.downloadAndDecryptBinary(
            info = ThumbnailUrlInfo(bareUrl = serverUrl, token = serverToken),
            nodeKeyBytes = nodeKeyBytes,
            sessionKey = sessionKey,
            linkId = linkId,
            cacheDir = cacheDir,
            background = background,
        )
        if (first != null) return first
        // The stored CDN url may have expired — signed thumbnail urls return HTTP 404 after a while,
        // which hits any row listed long before it scrolled into view, and every row after a cache
        // clear. Fetch a fresh url for this photo and retry once (node + session keys are unchanged,
        // only the download location), so the lazy fast path never has to rebuild a whole row just to
        // refresh a url.
        val fresh = runCatching { fetchFreshThumbnailInfo(userId, linkId, volumeId) }
            .getOrNull() ?: return null
        return thumbnailHelpers.downloadAndDecryptBinary(
            info = fresh,
            nodeKeyBytes = nodeKeyBytes,
            sessionKey = sessionKey,
            linkId = linkId,
            cacheDir = cacheDir,
            background = background,
        )
    }

    /** Re-fetch a fresh CDN url + token for [linkId]'s thumbnail when the stored one has expired
     *  (HTTP 404). Signed thumbnail urls are short-lived, but the lazy path keeps the url first seen
     *  at listing time, so the decrypt refreshes it on demand instead of forcing a full row rebuild.
     *  Public so the debug large-library simulator can refresh a seed's expired url the same way.
     *  [preferType] picks the thumbnail size: 1 (default) keeps the gallery's Type 1 fallback chain
     *  (1, then 2, then any); 2 requests the HD Type 2 thumbnail strictly, returning null when the
     *  revision carries none so the caller can fall back to Type 1. */
    suspend fun fetchFreshThumbnailInfo(
        userId: UserId,
        linkId: String,
        volumeId: String,
        preferType: Int = 1,
    ): ThumbnailUrlInfo? {
        val detail = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(linkId))[linkId] ?: return null
        val thumbs = detail.link.fileProperties?.activeRevision?.thumbnails
            ?: detail.photo?.activeRevision?.thumbnails
        val tid = if (preferType == 2) {
            thumbs?.firstOrNull { it.type == 2 }?.thumbnailId ?: return null
        } else {
            thumbs?.firstOrNull { it.type == 1 }?.thumbnailId
                ?: thumbs?.firstOrNull { it.type == 2 }?.thumbnailId
                ?: thumbs?.firstOrNull()?.thumbnailId
                ?: return null
        }
        return linkDetailHelpers.batchFetchThumbnailUrls(userId, volumeId, listOf(tid))[tid]
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
     * HD (Type 2, ~1920px) counterpart of [decryptThumbnailToFileBounded] for the face indexer,
     * which needs more detail than the gallery's Type 1 (~512px) thumbnail so face crops stay sharp.
     * The Type 2 CDN url + token are not stored on the row, so they are fetched fresh here; the
     * per-revision cipher material (content key packet, node key, node passphrase, parent) is the
     * SAME as Type 1, so the decrypt reuses the exact node-key + session-key path. The result is
     * written to a distinct `thumb_hd_<linkId>.jpg`, so it never collides with the gallery's
     * `thumb_<linkId>.jpg` cache. Rides the same [semaphore] permit as the bounded Type 1 variant.
     *
     * Returns the decrypted file path, or null when the revision carries no Type 2 thumbnail (an
     * older upload) or the fetch/decrypt fails, so the caller can fall back to the Type 1 path.
     */
    suspend fun decryptHdThumbnailToFileBounded(
        userId: UserId,
        linkId: String,
        volumeId: String,
        contentKeyPacketBase64: String,
        encNodeKey: String,
        encNodePass: String,
        parentLinkId: String,
    ): String? = semaphore.withPermit {
        val cacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }
        val hdInfo = runCatching { fetchFreshThumbnailInfo(userId, linkId, volumeId, preferType = 2) }
            .getOrNull() ?: return@withPermit null
        val parentKey = getParentKeyBytes(userId, parentLinkId, volumeId) ?: run {
            Log.w(TAG, "decryptHdThumbnailToFile $linkId: parent key for $parentLinkId unavailable")
            return@withPermit null
        }
        val nodeKeyBytes = runCatching {
            cryptoServiceClient.decryptNodeKey(encNodeKey, encNodePass, parentKey)
        }.getOrElse { e ->
            Log.w(TAG, "decryptHdThumbnailToFile $linkId: decryptNodeKey failed: ${e.message}")
            return@withPermit null
        }
        val sessionKey = cryptoServiceClient.decryptSessionKey(contentKeyPacketBase64, nodeKeyBytes)
        thumbnailHelpers.downloadAndDecryptBinary(
            info = hdInfo,
            nodeKeyBytes = nodeKeyBytes,
            sessionKey = sessionKey,
            linkId = linkId,
            cacheDir = cacheDir,
            fileName = "thumb_hd_$linkId.jpg",
        )
    }

    /**
     * Resolves the decrypted nodeKey bytes for [parentLinkId]. Two cases:
     *   • Root link: short-circuit through [PhotosShareService.getRootLinkKeyBytes] (also
     *     cached at the share-service layer).
     *   • Album link: fetch the album's BatchLinkDto, decrypt its nodeKey with the root
     *     key, and memoise in [parentKeyCache] so subsequent thumbnails in the same album
     *     skip the round-trip.
     *
     * Every path returns a defensive [ByteArray.copyOf] of the cached key, never the cache's
     * own array. A worker holds this key across the decrypt below, so handing out a copy lets
     * [clear] zero the cache originals on sign-out without ever zeroing an array an in-flight
     * decrypt is still reading from. The short-lived copy is dropped when the decrypt returns.
     */
    private suspend fun getParentKeyBytes(userId: UserId, parentLinkId: String, volumeId: String): ByteArray? {
        // Root link path — keys are managed by PhotosShareService.
        if (parentLinkId == shareService.photosRootLinkId()) {
            return shareService.getRootLinkKeyBytes(userId)?.copyOf()
        }

        parentKeyCache[parentLinkId]?.let { return it.copyOf() }

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
            return bytes.copyOf()
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
        return bytes.copyOf()
    }

    private companion object {
        /**
         * Worker pool size and concurrency bound in one. Three keeps scroll feeling instant
         * while staying well under the JNI / GC pressure that tripped SIGABRT on the cold-sync
         * burst; raising it does not help because DriveCryptoHelper's cryptoLock serializes
         * the actual libgojni calls anyway.
         */
        const val WORKER_COUNT = 3

        /**
         * Floor for the adaptive thumbnail-cache budget (see [maxThumbCacheBytes]). A fixed 1 GB
         * cap used to be smaller than a large (10k+ photo) library's thumbnail set, which forced a
         * perpetual evict-then-re-decrypt churn so older photos never kept a thumbnail. The budget
         * now scales with free space and only falls back to this floor on a nearly-full device.
         */
        const val MIN_THUMB_CACHE_BYTES = 1024L * 1024 * 1024

        /** Hard ceiling on the adaptive budget so a huge library on a roomy device still can't
         *  monopolise storage. */
        const val MAX_THUMB_CACHE_CEILING = 6L * 1024 * 1024 * 1024

        /** The adaptive budget is at most a 1/[FREE_SPACE_DIVISOR] slice of the cache volume's
         *  free space, so the warm cache grows with available storage instead of a fixed cap. */
        const val FREE_SPACE_DIVISOR = 4L

        /** Run the size check once every this many decrypts — frequent enough that the cap is
         *  never overshot by more than a few tens of MB, rare enough to stay off the hot path. */
        const val TRIM_CHECK_INTERVAL = 100L

        /** A decrypt work temp (`thumb_enc_*` / `thumb_dec_*`) lives for well under a second, so
         *  anything older than this was orphaned by a process kill mid-decrypt and is safe to sweep.
         *  The wide margin guarantees an in-flight decrypt's temp is never deleted. */
        const val ORPHAN_TEMP_MAX_AGE_MS = 5L * 60 * 1000

        /** Background warm-up re-measures the cache (and lets workers drain below
         *  [BACKFILL_QUEUE_HIGH_WATER]) every this many enqueues, so in-flight decrypts can't
         *  overshoot the budget before the size guard notices. */
        const val BACKFILL_SAMPLE_EVERY = 32

        /** Pause the background warm-up's enqueue loop while more than this many tasks are still
         *  queued, pacing it to the decrypt rate instead of dumping the whole library at once. */
        const val BACKFILL_QUEUE_HIGH_WATER = 64

        /** Minimum gap between BACKGROUND (whole-library warm-up) decrypts across the whole worker
         *  pool. 90 ms caps the aggregate warm-up CDN request rate near 11 fetches/sec, gentle
         *  enough to seldom trip a 429 while still warming the library steadily. VISIBLE and
         *  PREFETCH are never paced, so the viewport and look-ahead stay instant during a warm-up. */
        const val BACKGROUND_PACE_MS = 90L

        /** Library-walk page size for the warm-up — bounds how many rows (and their crypto material)
         *  are resident at once while still warming the whole library across successive pages. */
        const val BACKFILL_PAGE_SIZE = 2000

        /** Hard cap on how many thumbnails a single warm-up pass enqueues, independent of the byte
         *  budget. A large library of small or near-identical thumbnails never reaches the byte
         *  budget, so without a count cap the warm-up would enqueue tens of thousands of decrypts in
         *  one burst and flood the decrypt pipeline. The newest [MAX_BACKFILL_THUMBNAILS] cover
         *  typical scrolling; the cold tail still decrypts on demand when scrolled into view. */
        const val MAX_BACKFILL_THUMBNAILS = 12_000

        /** Cap on pinned cover linkIds so the never-evicted cover set can't itself crowd the cache
         *  budget. Real album + Collection cover counts sit far below this; it is only a backstop. */
        const val MAX_PINNED_COVERS = 2000
    }
}
