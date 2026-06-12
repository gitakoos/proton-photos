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
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.api.dto.PhotoLinkDto
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.DriveNotFoundException
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
 * See the comment in `refreshCloudPhotos` for the wider rationale — over-protection
 * here means cloud-deleted photos keep showing the green cloud icon until the window
 * elapses.
 */
private const val UPLOAD_PROTECTION_WINDOW_MS: Long = 90L * 1000L

/**
 * How long a just-trashed photo is kept out of the refresh upsert. Covers the server-side
 * eventual-consistency window where the photo stream still lists a photo the user already
 * trashed; comfortably longer than [UPLOAD_PROTECTION_WINDOW_MS] since trash propagation is the
 * slower of the two. After this, a still-listed linkId is assumed to be a genuine re-add (e.g.
 * restored from Drive web) and is allowed back in.
 */
private const val TRASH_PROTECTION_WINDOW_MS: Long = 3L * 60L * 1000L

/**
 * Number of link stubs processed per metadata/thumbnail/CKP/build/upsert pass inside the
 * full-refresh loop. The listing pagination still pulls 500 small stubs per page, but the
 * heavy per-link work runs in sub-batches of this size so the transient maps (link details,
 * thumbnail URLs, content-key packets, built entities) only ever hold one batch at a time
 * and are released before the next. Bounding the live set this way is what keeps a
 * thousands-of-photos library from inflating tens of MB of simultaneous transient heap and
 * pushing the device into OutOfMemoryError on a fresh-login full refresh.
 *
 * 100 keeps the network round-trips coarse enough (the batch endpoints already chunk to 50
 * internally) while staying an order of magnitude below the heap pressure that triggered the
 * crash, and stays a clean multiple of the per-batch crypto-pacing chunk (10) below.
 */
private const val REFRESH_BATCH = 100

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
    private val thumbnailHelpers: ThumbnailHelpers,
    private val recentUploadsTracker: RecentUploadsTracker,
    private val thumbnailDecryptScheduler: ThumbnailDecryptScheduler,
    @ApplicationContext private val context: Context,
) {
    private val semaphore get() = shareService.networkSemaphore

    /**
     * Single-flight guard: cold-start fires up to three concurrent refreshes (SyncWorker
     * boot kick, MainActivity.onResume silent refresh, GalleryViewModel.doSync) and each
     * one previously ran the full crypto loop in parallel. With 315 photos × 3 callers ×
     * sequential per-photo decrypt, the system would queue ~945 Go OpenPGP calls within a
     * couple of seconds on Android 16 beta firmware, and the runtime would eventually trip the
     * `slice bounds out of range [:-1]` panic in libgojni.so. Coalescing all callers onto
     * one in-flight refresh both eliminates the duplicate work AND keeps Go-runtime
     * concurrency well-bounded — the second / third caller just waits for the first to
     * finish and returns immediately (they get the same DB state anyway).
     */
    private val refreshFullMutex = Mutex()
    private val refreshIncrementalMutex = Mutex()

    /**
     * Live, per-chunk-read pacing signal for [doRefreshCloudPhotos]. When true the full
     * refresh runs on the gentle knobs (smaller effective batch, smaller crypto chunk,
     * longer inter-chunk delay); when false it runs on the normal knobs.
     *
     * Read fresh on every chunk rather than captured once, so flipping it mid-refresh
     * takes effect on the very next chunk — the refresh starts gentle while a heavy
     * first-screen surface is on display, then speeds up the instant that surface signals
     * it no longer needs the slack.
     *
     * The gentle cadence stretches the gopenpgp decrypt calls further apart in wall-clock
     * time. That widens the window the CMC GC has to interleave with the Go runtime's
     * memory layout instead of colliding with it, lowering the chance of the libgojni
     * SIGABRT that a tight cold-cache decrypt burst can trip.
     */
    @Volatile
    private var gentleSyncActive: Boolean = false

    /** Set the live pacing signal read per chunk by [doRefreshCloudPhotos]. */
    fun setGentleSync(active: Boolean) {
        gentleSyncActive = active
    }

    /**
     * Set by [doRefreshCloudPhotos] to report whether the most recent full refresh saw the
     * WHOLE listing through to a clean finish (listing paginated completely AND every
     * processing batch upserted without error). The incremental fallback in
     * [doRefreshCloudPhotosIncremental] consults it before persisting the initial event
     * anchor: the events feed only delivers FUTURE changes, never a backfill, so storing the
     * "caught up to now" anchor on top of a partially-populated DB would permanently strand
     * the photos that a crashed/aborted batch never wrote. Leaving the anchor unsaved makes
     * the next launch fall through to another full refresh, which retries the missing rows.
     */
    @Volatile
    private var lastFullRefreshComplete: Boolean = false

    /**
     * Cooldown to break a known refresh-loop: when [getLatestEventAnchor]
     * returns "Invalid ID" (some accounts simply don't have an event anchor available),
     * [doRefreshCloudPhotosIncremental] never persists an anchor, so EVERY subsequent
     * call falls through to a full refresh. With three callers (gallery init, onResume,
     * SyncWorker) firing in close succession that turned into a back-to-back loop that
     * hammered the Drive API at ~1/sec — observed in logcat as continuous
     * "incremental: could not get event anchor … will full-refresh next time" lines.
     *
     * Rate-limiting the fallback path to once per minute keeps the user's data fresh
     * without thrashing the network. Pull-to-refresh bypasses this entirely because it
     * goes through [refreshCloudPhotos] directly, not the incremental path.
     *
     * AtomicLong (not @Volatile var) so the read-then-write CAS below is one atomic op —
     * without CAS, two callers racing on the same stale value can both pass the cooldown
     * gate and fire parallel full refreshes (the Mutex would then serialize them but the
     * extra network round-trip already went out).
     */
    private val lastFallbackFullRefreshMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val fallbackFullRefreshCooldownMs: Long = 60_000L

    /**
     * LinkIds the user trashed within [TRASH_PROTECTION_WINDOW_MS]. The refresh skips re-adding
     * these so a server stream that hasn't caught up to the trash can't flash the photo (and its
     * green-cloud badge) back. In-memory only: a process death before the next refresh is harmless
     * because the trash is already committed server-side, so the post-restart refresh sees it gone.
     */
    private val recentlyTrashed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Mark [linkIds] as just-trashed so the next refresh keeps them out (see [isRecentlyTrashed]). */
    fun markRecentlyTrashed(linkIds: Collection<String>) {
        val now = System.currentTimeMillis()
        linkIds.forEach { recentlyTrashed[it] = now }
    }

    /**
     * Clear the just-trashed mark for [linkIds] — call this when a photo is RESTORED from trash
     * within the protection window. Otherwise the refresh would keep treating it as trashed and
     * skip re-adding it (leaving it invisible until the window expires).
     */
    fun forgetRecentlyTrashed(linkIds: Collection<String>) {
        linkIds.forEach { recentlyTrashed.remove(it) }
    }

    private fun isRecentlyTrashed(linkId: String): Boolean {
        val at = recentlyTrashed[linkId] ?: return false
        if (System.currentTimeMillis() - at > TRASH_PROTECTION_WINDOW_MS) {
            recentlyTrashed.remove(linkId)
            return false
        }
        return true
    }

    fun observeCloudPhotos(userId: UserId): Flow<List<CloudPhoto>> =
        // observeOwnStream, not observeAll: rows loaded from shared-with-me albums sit in the
        // same table and must not surface in the user's own timeline.
        photoListingDao.observeOwnStream(userId.id).map { list -> list.map { it.toDomain() } }

    fun observePhotosByLinkIds(linkIds: List<String>): Flow<List<CloudPhoto>> =
        photoListingDao.observeByLinkIds(linkIds).map { list -> list.map { it.toDomain() } }

    suspend fun refreshCloudPhotos(userId: UserId): Unit = withContext(Dispatchers.IO) {
        refreshFullMutex.withLock { doRefreshCloudPhotos(userId) }
    }

    private suspend fun doRefreshCloudPhotos(userId: UserId) {
        // Default to "incomplete" until the loop below proves the listing finished cleanly;
        // any early return or thrown error then correctly leaves the event anchor unsaved.
        lastFullRefreshComplete = false
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

            // 3. Order the stream stubs for processing.
            // Album children are intentionally excluded here: all backed-up photos are in the
            // Photos stream, so albums add no new photos that belong in the main timeline.
            // Including album children would cause shared-album photos (from other users) to
            // appear in the owner's main gallery as if they were backed up.
            // Album photos are fetched on demand by loadAlbumPhotos() and cached in the DB.
            //
            // Sort by captureTime DESC so the processing loop handles newest-first — matches
            // the gallery's display order (GetGalleryItemsUseCase sortedByDescending captureTime).
            // Without this, batches land in linkId order from the server, and a photo from the
            // middle of the timeline can show up before the most-recent shot at the top of the
            // gallery, so the load order looks random instead of top-down.
            //
            // Only these light stubs are retained for the whole library; all the heavy maps
            // (link details, thumbnail URLs, content-key packets, built entities) live one
            // REFRESH_BATCH at a time inside the loop below and are released before the next
            // batch — that bound is the fix for the fresh-login OutOfMemoryError on big
            // libraries, where fetching every link's metadata into one map blew the heap.
            val allPhotoLinks = streamLinks.sortedByDescending { it.captureTime }
            Log.d(TAG, "refreshCloudPhotos: ${allPhotoLinks.size} photos in stream")

            val rootLinkId = shareService.photosRootLinkId()
            val ownPublicKeys = cryptoHelper.getOwnPublicKeysArmored(userId)

            // Parent keys decrypted across ALL batches, used once after the loop to seed the
            // lazy thumbnail scheduler. Keyed material only (linkId → key bytes) — small and
            // de-duplicated, so accumulating it for the whole library is not a heap concern.
            val accumulatedParentKeys = mutableMapOf<String, ByteArray>()
            // LinkIds successfully observed in the stream listing — collected as plain strings
            // (cheap) so the post-loop stale-entry cleanup can compute what to delete without
            // holding any heavy per-link state.
            val foundIds = mutableSetOf<String>()
            // Batches that threw partway: one bad batch is logged and skipped so the rest of
            // the refresh still lands, but its presence suppresses the stale-entry cleanup and
            // the incremental-anchor save (an incomplete picture must not prune valid rows or
            // declare the library caught-up).
            var failedBatches = 0
            // Fire the first-screen thumbnail pre-warm exactly once, after the first batch's
            // rows have been persisted. prefetch dedupes + bounds, so a re-fire would be
            // harmless, but the flag keeps it to the one batch whose rows the gallery shows first.
            var firstScreenPrewarmed = false

            // Per-chunk crypto pacing. Two cadences, selected live per chunk off
            // [gentleSyncActive] (see its field doc):
            //   • normal (chunk 10 + 100ms delay): empirically the smallest chunks-per-second
            //     that don't trip the Go OpenPGP panic during a cold-cache full refresh. The
            //     first chunk lands in the gallery within ~1 sec; the remainder streams in.
            //     Lowering the chunk further didn't measurably reduce crash rate but made
            //     perceived progress noticeably slower, so 10 is the floor.
            //   • gentle (chunk 5 + 300ms delay): a slower cadence used while a heavy
            //     first-screen surface is on display, halving the per-tick decrypt count and
            //     tripling the gap so the decrypt burst spreads further across wall-clock time.
            // Read fresh inside the inner loop so a mid-refresh flip applies on the next chunk.

            // 4. Process the library in REFRESH_BATCH-sized passes. Everything heavy — link
            //    details, parent-key decrypt, thumbnail URLs, content-key packets, entity
            //    build, and the DB upsert — happens INSIDE each pass so the transient maps are
            //    scoped to one batch and garbage-collected before the next one starts.
            for (batch in allPhotoLinks.chunked(REFRESH_BATCH)) {
                try {
                    val batchLinkIds = batch.map { it.linkId }
                    val linkDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, activeVolumeId, batchLinkIds)

                    // Parent-key cache for THIS batch: root key plus any album parents whose
                    // photos appear in the batch. Decrypted album keys are also copied into
                    // accumulatedParentKeys so the post-loop scheduler seed sees the union.
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
                    for ((k, v) in parentKeyCache) {
                        if (k != null && v != null) accumulatedParentKeys[k] = v
                    }

                    // Thumbnail URLs for the batch's photos that carry a ThumbnailID.
                    //   Drive offers two sizes per photo: Type 1 (small ~200px) and Type 2 (HD
                    //   ~512px+). The grid renders at ~1/3 screen width which is well above
                    //   200px on modern phones, so the Type 1 thumb was visibly blurry. Prefer
                    //   Type 2 with Type 1 fallback for older revisions that only have the small
                    //   variant.
                    val thumbnailIdToLinkId = mutableMapOf<String, String>()
                    for ((linkId, detail) in linkDetailMap) {
                        val thumbnailList = detail.link.fileProperties?.activeRevision?.thumbnails
                            ?: detail.photo?.activeRevision?.thumbnails
                        val tid = thumbnailList?.firstOrNull { it.type == 2 }?.thumbnailId
                            ?: thumbnailList?.firstOrNull { it.type == 1 }?.thumbnailId
                            ?: thumbnailList?.firstOrNull()?.thumbnailId
                        if (tid != null) thumbnailIdToLinkId[tid] = linkId
                    }
                    val thumbnailUrlMap: Map<String, ThumbnailUrlInfo> =
                        if (thumbnailIdToLinkId.isNotEmpty())
                            linkDetailHelpers.batchFetchThumbnailUrls(userId, activeVolumeId, thumbnailIdToLinkId.keys.toList())
                        else emptyMap()

                    // ContentKeyPackets via regular Drive API (Photos batch API omits them).
                    // CKPs are needed to decrypt thumbnail blocks (SEIPD format, same as content
                    // blocks).
                    val ckpMap = linkDetailHelpers.batchFetchContentKeyPackets(userId, effectiveShareId, batchLinkIds)

                    // Build + upsert the batch in crypto-paced chunks. Building all rows in a
                    // single sequential pass monopolised one IO-dispatcher thread for a long
                    // run of consecutive crypto calls and raced Android's CMC GC against the Go
                    // OpenPGP runtime memory layout; the chunk-and-delay cadence keeps the JNI
                    // pressure bounded and lets the first rows reach the gallery quickly.
                    //
                    // The window is stepped by an index (not a one-shot `.chunked()`) so the
                    // chunk size + delay can be re-read from [gentleSyncActive] on every chunk —
                    // a flip to non-gentle mid-batch speeds the remaining chunks up immediately.
                    var chunkStart = 0
                    while (chunkStart < batch.size) {
                        val gentle = gentleSyncActive
                        val chunkSize = if (gentle) 5 else 10
                        val interChunkDelayMs = if (gentle) 300L else 100L
                        val chunk = batch.subList(chunkStart, minOf(chunkStart + chunkSize, batch.size))
                        chunkStart += chunkSize
                        val chunkLinkIds = chunk.map { it.linkId }
                        // Fast path: photos already in DB with a still-on-disk cached thumbnail
                        // get re-used as-is. This avoids re-running the per-photo decrypt +
                        // thumbnail-decrypt pipeline on every cold start, which is what pushes
                        // the Go OpenPGP library into "slice bounds out of range" territory once
                        // enough crypto ops pile up. Cache-cleared installs still walk the full
                        // path (no cache hits possible), but the steady-state app launch with
                        // existing local cache touches almost no Go code at all.
                        val existingByLinkId = photoListingDao.getByLinkIds(chunkLinkIds).associateBy { it.linkId }
                        val chunkToSave = mutableListOf<eu.akoos.photos.data.db.entity.PhotoListingEntity>()
                        for (stub in chunk) {
                            val cached = existingByLinkId[stub.linkId]
                            if (cached != null && thumbnailHelpers.isCachedValid(cached.thumbnailUrl)) {
                                // Fast path reuses the cached row to skip a crypto rebuild, BUT
                                // the server-side tags (PhotoTag IDs — 0=Favorite) on the freshly
                                // fetched stub can have changed since the last cache write (e.g.
                                // user favorited the photo on Drive Web). Reusing `cached` as-is
                                // dropped those updates silently, so the heart icon never appeared
                                // even after a forced pull-to-refresh. Merge the stub's tags into
                                // the cached row when they differ, otherwise leave it alone.
                                val stubTagsCsv = stub.tags.sorted().joinToString(",")
                                val cachedTagsCsv = cached.tagsCsv
                                    .split(',')
                                    .mapNotNull { it.toIntOrNull() }
                                    .sorted()
                                    .joinToString(",")
                                if (stubTagsCsv != cachedTagsCsv) {
                                    chunkToSave += cached.copy(tagsCsv = stubTagsCsv)
                                }
                                foundIds += stub.linkId
                                continue
                            }
                            val detail = linkDetailMap[stub.linkId]
                            val parentId = detail?.link?.parentLinkId
                            val parentKeyBytes = parentKeyCache[parentId] ?: rootLinkKeyBytes
                            val thumbnailInfo = thumbnailIdToLinkId.entries
                                .firstOrNull { it.value == stub.linkId }
                                ?.key
                                ?.let { thumbnailUrlMap[it] }
                            // Lazy-thumbnail: skip the eager thumbnail download+decrypt for
                            // cache-miss items. We persist the encrypted material into the row;
                            // [ThumbnailDecryptScheduler] consumes it when the cell scrolls into
                            // view. This trades cold-start "minutes-of-libgojni-burn" for "snappy
                            // grid populates immediately, thumbnails materialise as you scroll".
                            val built = photoEntityBuilder.build(
                                stub, detail, userId, effectiveShareId, activeVolumeId, parentKeyBytes,
                                thumbnailCacheDir, thumbnailInfo, ckpMap[stub.linkId], ownPublicKeys,
                                decryptThumbnail = false,
                            )
                            // Preserve a previously-cached thumbnail URL when the new build came
                            // back without one (e.g. v2/volumes uploads have no server-side
                            // thumbnail); otherwise the gallery tile would blank out on refresh.
                            //
                            // BUT only when the cached URL STILL points at a real file. After
                            // "Clear app cache" the DB rows survive (Room is in /databases) while
                            // every thumbnail file under /cache/thumbnails/ is gone — leaving the
                            // URL field populated but the file missing means PhotoCell skips the
                            // lazy-decrypt request (it only fires when thumbnailUrl == null) and
                            // the grid sticks on broken placeholders until a future full refresh
                            // happens to walk the slow path for that row. Clearing the stale URL
                            // lets the scheduler rebuild the cache file on demand as cells scroll
                            // into view.
                            val cachedThumb = cached?.thumbnailUrl
                                ?.takeIf { thumbnailHelpers.isCachedValid(it) }
                            val merged = if (built.thumbnailUrl == null) built.copy(thumbnailUrl = cachedThumb) else built
                            // Skip photos the user just trashed. The server photo stream can keep
                            // returning a trashed photo for up to ~a minute (eventual consistency);
                            // without this guard the refresh re-adds the row we already removed on
                            // delete, flashing the green-cloud badge back on until a later refresh
                            // finally sees it gone. Treating it as "not found" also lets the
                            // stale-entry cleanup drop any lingering row immediately.
                            if (!isRecentlyTrashed(stub.linkId)) {
                                chunkToSave += merged
                                foundIds += stub.linkId
                            }
                        }
                        if (chunkToSave.isNotEmpty()) {
                            photoListingDao.upsertAll(chunkToSave)
                            // Yielding + delaying only matter when we actually did Go-crypto work;
                            // a pure cache-hit chunk skips both so the steady-state refresh stays
                            // snappy.
                            yield()
                            delay(interChunkDelayMs)
                        }
                    }

                    // First-screen pre-warm: as soon as the first batch's rows are in the DB,
                    // kick the lazy scheduler on the newest ~30 of them so the gallery binds real
                    // thumbnails on arrival instead of blank cells. The keys these rows need are
                    // already decrypted (this batch merged them into accumulatedParentKeys above);
                    // seed the scheduler with them first so the prefetch doesn't fall back to a
                    // per-photo key round-trip. populateParentKeys uses putIfAbsent and prefetch
                    // dedupes/bounds (WORKER_COUNT), so this can't burst and the later post-loop
                    // seed + on-scroll requests stay correct.
                    if (!firstScreenPrewarmed) {
                        firstScreenPrewarmed = true
                        val warmKeys = accumulatedParentKeys.toMap()
                        if (warmKeys.isNotEmpty()) thumbnailDecryptScheduler.populateParentKeys(warmKeys)
                        val firstScreenIds = batch.take(30).map { it.linkId }
                        val firstScreenRows = photoListingDao.getByLinkIds(firstScreenIds)
                        if (firstScreenRows.isNotEmpty()) {
                            thumbnailDecryptScheduler.prefetch(userId, firstScreenRows)
                        }
                    }
                } catch (e: Exception) {
                    // One batch failing (network blip, a decode error, transient OOM on a
                    // single oversized batch) must not abort the whole refresh: the chunks that
                    // already upserted stay in the DB, and we press on to the next batch.
                    // Recording the failure suppresses the stale-entry cleanup below.
                    failedBatches++
                    Log.w(TAG, "refreshCloudPhotos: batch failed (${e.javaClass.simpleName}: ${e.message}), continuing")
                }
            }

            // Seed the lazy scheduler with the parent keys decrypted across every batch.
            // Without this, each cell that scrolls into view would trigger a per-photo
            // batchFetchLinkDetails + decrypt round trip to resolve its parent's node key.
            // Pre-seeding makes the scheduler's per-cell work just a network blob fetch plus a
            // few cryptoLock-serialized JNI calls.
            thumbnailDecryptScheduler.populateParentKeys(accumulatedParentKeys)

            // Stale-entry cleanup: remove DB rows the fresh listing no longer contains.
            //
            // Deletion policy:
            //   • Only delete entries that (a) aren't in the current found set AND
            //     (b) aren't a VERY recent upload (within UPLOAD_PROTECTION_WINDOW_MS).
            //   • Skip deletion entirely when the photo stream was unavailable (404 / network
            //     error) OR any processing batch failed: in those cases we only have a PARTIAL
            //     picture of what's on the server, so deleting would incorrectly remove valid
            //     rows — including stream-only photos uploaded in previous sessions.
            //
            // Why the protection window is TIGHT (≈90s, not the full 1h TTL): the only legitimate
            // "don't delete despite missing from stream" case is the narrow race where an upload
            // committed but the server-side photo stream index hasn't caught up yet (usually
            // seconds, never minutes). Using the full TTL meant photos deleted on Drive web kept
            // showing as SYNCED with green cloud icons because their linkIds were still in the
            // 1h-wide protection set. The persistent TTL is for crash/restart resilience (so a
            // process kill between upload and refresh can't drop a fresh upload); the in-refresh
            // protection only needs to span the upload→stream visibility race, which is orders of
            // magnitude shorter.
            val listingComplete = streamCallSucceeded && failedBatches == 0
            if (listingComplete) {
                // Whole picture in hand → safe to clean up stale entries. Use the tight
                // protection window (see comment above) rather than the full TTL.
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
                Log.d(TAG, "refreshCloudPhotos: saved ${foundIds.size} photos")
            } else {
                Log.d(TAG, "refreshCloudPhotos: incomplete refresh (streamOk=$streamCallSucceeded, failedBatches=$failedBatches) — upserted ${foundIds.size} photos, skipping stale-entry cleanup to avoid data loss")
            }
            // Report completeness so the incremental fallback knows whether the event anchor
            // may be persisted (see [lastFullRefreshComplete]).
            lastFullRefreshComplete = listingComplete
        } catch (e: DriveNotFoundException) {
            Log.w(TAG, "refreshCloudPhotos: DriveNotFoundException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "refreshCloudPhotos failed", e)
        } catch (t: Throwable) {
            // Catch OutOfMemoryError (and any other Error) so a heap exhaustion that slips past
            // the per-batch guard — e.g. while a single batch's maps are live — doesn't crash
            // the app. Whatever batches already upserted remain in the DB by construction (each
            // chunk is committed inside the loop). lastFullRefreshComplete is already false from
            // the entry reset, so the event anchor stays unsaved and the next launch retries a
            // full refresh. Swallow rather than rethrow: a degraded gallery beats a crash loop.
            Log.e(TAG, "refreshCloudPhotos aborted (${t.javaClass.simpleName}: ${t.message})", t)
        }
    }

    suspend fun refreshCloudPhotosIncremental(userId: UserId): Unit = withContext(Dispatchers.IO) {
        refreshIncrementalMutex.withLock { doRefreshCloudPhotosIncremental(userId) }
    }

    private suspend fun doRefreshCloudPhotosIncremental(userId: UserId) {
        try {
            val volumeId = shareService.getVolumeId(userId)
            val anchorKey = SettingsKeys.eventAnchorKey(userId.id, volumeId)
            val prefs = context.settingsDataStore.data.first()
            val storedAnchor: String? = prefs[anchorKey]

            if (storedAnchor == null) {
                // Rate-limit the fallback full-refresh — if getLatestEventAnchor keeps
                // returning "Invalid ID" every call would otherwise re-trigger a full
                // refresh, looping forever (see lastFallbackFullRefreshMs comment above).
                // CAS the timestamp atomically so two simultaneous callers can't both
                // pass the gate on the same stale value: the loser sees the winner's
                // freshly-written `now` and falls into the skip branch.
                val now = System.currentTimeMillis()
                val prev = lastFallbackFullRefreshMs.get()
                val elapsed = now - prev
                if (prev > 0L && elapsed < fallbackFullRefreshCooldownMs) {
                    Log.d(TAG, "incremental: skipping fallback full-refresh — last ${elapsed}ms ago")
                    return
                }
                if (!lastFallbackFullRefreshMs.compareAndSet(prev, now)) {
                    // Another caller raced past us — they own this refresh window, bail.
                    Log.d(TAG, "incremental: skipping fallback full-refresh — another caller claimed the window")
                    return
                }

                refreshCloudPhotos(userId)
                // Only arm the incremental event feed when the full refresh actually wrote the
                // WHOLE library. The events endpoint delivers future deltas, never a backfill,
                // so saving the "caught up to now" anchor on top of a partially-populated DB
                // (a crashed/aborted/incomplete refresh) would strand the rows that never
                // landed. Leaving the anchor unsaved makes the next launch full-refresh again
                // and retry them.
                if (!lastFullRefreshComplete) {
                    Log.w(TAG, "incremental: full refresh incomplete, leaving anchor unsaved to retry next launch")
                    return
                }
                try {
                    val manager = apiProvider.get<DriveApiService>(userId)
                    val latestAnchor = semaphore.withPermit {
                        manager.invoke { getLatestEventAnchor(volumeId) }.valueOrThrow
                    }
                    context.settingsDataStore.edit { it[anchorKey] = latestAnchor.eventId }
                    Log.d(TAG, "incremental: initial anchor saved ${latestAnchor.eventId}")
                } catch (e: Exception) {
                    Log.w(TAG, "incremental: could not get event anchor (${e.message}), will retry after cooldown")
                }
                return
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
                    // Same lazy-thumbnail rationale as the full refresh — incremental
                    // updates also defer the per-photo thumbnail decrypt to scroll time.
                    photoEntityBuilder.build(
                        stub, detail, userId, shareId, volumeId, parentKeyBytes,
                        thumbnailCacheDir, thumbnailInfo, ckpMap[linkId], ownPublicKeys,
                        decryptThumbnail = false,
                    )
                }
                // Validate cached file:// URLs against the on-disk file too, not just the
                // DB value: after "Clear app cache" the rows survive but the underlying
                // files don't, and preserving the stale URL would suppress the lazy-decrypt
                // request in PhotoCell (it only fires for thumbnailUrl == null). See the
                // longer comment in [doRefreshCloudPhotos] for the full story.
                val existingThumbByLinkId = photoListingDao.getByLinkIds(entities.map { it.linkId })
                    .associateBy({ it.linkId }, { it.thumbnailUrl })
                val entitiesToSave = entities.map { entity ->
                    if (entity.thumbnailUrl == null) {
                        val carry = existingThumbByLinkId[entity.linkId]
                            ?.takeIf { thumbnailHelpers.isCachedValid(it) }
                        entity.copy(thumbnailUrl = carry)
                    } else entity
                }
                photoListingDao.upsertAll(entitiesToSave)
                Log.d(TAG, "incremental: upserted ${entities.size} photos")

                // Seed lazy-thumbnail scheduler with parent keys (root + albums) we already
                // decrypted in this incremental pass — same rationale as the full refresh.
                thumbnailDecryptScheduler.populateParentKeys(
                    parentKeyCache.entries.mapNotNull { (k, v) ->
                        if (k != null && v != null) k to v else null
                    }.toMap(),
                )
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
