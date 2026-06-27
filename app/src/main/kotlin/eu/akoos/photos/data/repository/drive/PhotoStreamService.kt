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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.domain.ApiManager
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.api.dto.PhotoLinkDto
import eu.akoos.photos.data.api.dto.PhotoLinksResponse
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
 * Window in which a just-recorded upload (in [RecentUploadsTracker]) is protected from the
 * delete-missing cleanup of [PhotoStreamService.refreshCloudPhotos], even though the photo stream
 * API responded successfully without listing it.
 *
 * Why the window has to be this large: after this app uploads a photo it exists on Drive at once,
 * but the photo-stream LISTING is eventually consistent — the new linkId takes a few seconds
 * (sometimes longer on a busy backend or a large library) to appear in the index. A refresh that
 * runs inside that gap wouldn't find the fresh upload in the listing and would prune it as
 * "deleted", flickering its green cloud badge off until the next refresh re-added it. 90s
 * comfortably clears the worst observed indexing lag: too SHORT flickers every fresh upload; too
 * long only delays the rare "upload then immediately delete on Drive" case — deleting any older
 * photo still prunes on the very next refresh, since it isn't a recent upload.
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
 * Per-page retry budget for the full listing walk. A single photo-stream page that fails
 * transiently (a network blip, a brief 5xx) is retried this many times with a short backoff
 * before the walk pauses — so a momentary hiccup mid-library doesn't truncate the listing and
 * leave older photos unfetched until the next launch. A persistent failure still falls through,
 * and the saved cursor lets the next run resume from the same page.
 */
private const val PHOTO_PAGE_MAX_ATTEMPTS = 3
private const val PHOTO_PAGE_RETRY_BASE_MS: Long = 800L
// When a listing page still throws after fetchPhotoPageWithRetry's short per-page backoff (a
// sustained rate-limit), the walk waits the page out on this slower OUTER backoff — honouring the
// server's Retry-After when present — and resumes the SAME page from the saved cursor. The counter
// resets after every page that succeeds, so a large library completes however many pages need a
// wait; only one page that keeps failing past MAX_LISTING_PAGE_RETRIES retries (server truly down) gives
// up for this pass, with the cursor preserved so a later run continues.
private const val MAX_LISTING_PAGE_RETRIES = 6
private const val STREAM_RESUME_BASE_MS: Long = 30_000L
private const val STREAM_RESUME_MAX_MS: Long = 180_000L
// Gap between successful listing pages. The listing only fetches light link ids, so a modest pace
// keeps it fast while easing the burst that trips Proton's rate-limit on large libraries.
private const val LISTING_PAGE_DELAY_MS: Long = 400L

/** Privacy-safe one-line description of a listing error for the diagnostics buffer: exception type,
 *  the ProtonCore error variant, the HTTP status, and the Retry-After (seconds) when present. Never
 *  the message body, ids, or any account data. */
private fun listingErrorDetail(e: Throwable): String {
    val apiError = (e as? me.proton.core.network.domain.ApiException)?.error
    val http = apiError as? me.proton.core.network.domain.ApiResult.Error.Http
    return buildString {
        append(e.javaClass.simpleName)
        if (apiError != null) append('/').append(apiError.javaClass.simpleName)
        if (http != null) append("/http=").append(http.httpCode)
        // A Parse error's cause (a SerializationException) names the DTO field that didn't match —
        // log a truncated form (field name, no values) so a tester log pinpoints which photo field
        // tripped the listing parse.
        (apiError as? me.proton.core.network.domain.ApiResult.Error.Parse)?.cause?.message?.let {
            append("/cause=").append(it.replace('\n', ' ').take(160))
        }
        val ra = http?.retryAfter?.inWholeSeconds
        if (ra != null && ra > 0) append("/retryAfter=").append(ra).append('s')
    }
}

/** Only a clearly-permanent auth failure (401/403) is worth abandoning the listing for — a retry
 *  can't fix those. Every other error is waited out and resumed, since completing the listing is
 *  what makes the whole library load and the saved cursor makes a retry safe. */
private fun isPermanentListingError(e: Throwable): Boolean {
    val http = (e as? me.proton.core.network.domain.ApiException)?.error
        as? me.proton.core.network.domain.ApiResult.Error.Http ?: return false
    return http.httpCode == 401 || http.httpCode == 403
}

/** How long to wait before re-trying a failed listing page: the server's Retry-After when it sent
 *  one (so we back off exactly as asked), otherwise an escalating, capped backoff. */
private fun listingRetryWaitMs(e: Throwable, attempt: Int): Long {
    val serverWait = ((e as? me.proton.core.network.domain.ApiException)?.error
        as? me.proton.core.network.domain.ApiResult.Error.Http)
        ?.retryAfter?.inWholeMilliseconds?.takeIf { it > 0 }
    val backoff = minOf(STREAM_RESUME_BASE_MS shl (attempt - 1), STREAM_RESUME_MAX_MS)
    return (serverWait ?: backoff).coerceAtMost(STREAM_RESUME_MAX_MS)
}

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
    // While still backfilling an interrupted/partial listing, retry the fallback full refresh on
    // this short floor so it finishes. Once the whole library has been listed, the only reason to
    // re-walk is to poll for photos the (unavailable) event anchor can't deliver as deltas — gate
    // THAT far more loosely so an account whose event anchor never resolves doesn't re-walk the
    // entire stream on every screen unlock, which was draining the battery on large libraries.
    // Pull-to-refresh bypasses both (it calls refreshCloudPhotos directly).
    private val fallbackFullRefreshCooldownMs: Long = 5L * 60_000L
    private val completedListingPollCooldownMs: Long = 2L * 60L * 60_000L
    // Coalesce window for full refreshes: launch, resume, tab-switch and the incremental fallback can
    // pile several onto refreshFullMutex, which then drains them one whole library walk at a time —
    // minutes of needless re-listing + re-decrypt. A non-forced refresh that finds one already
    // completed this recently just returns; explicit user actions (pull-to-refresh, Sync now) pass
    // force = true so they always fetch.
    private val lastFullRefreshCompletedMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val minFullRefreshIntervalMs: Long = 90_000L

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
        // observeOwnStreamLite, not observeAll: rows loaded from shared-with-me albums sit in the
        // same table and must not surface in the user's own timeline.
        // The LITE projection selects only the display columns — NOT the per-row crypto material
        // (encNodeKey / contentKeyPacket / encNodePassphrase / encXAttr, kilobytes each). A
        // thumbnail-decrypt burst re-emits the whole listing on every DB write; selecting the crypto
        // blobs for the entire library each time, only to drop them in toDomain(), materialised
        // hundreds of MB of transient garbage per write on a large library and pinned the heap at
        // its ceiling. flowOn(Default) keeps the per-row map off the collector's Main thread.
        photoListingDao.observeOwnStreamLite(userId.id)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    fun observePhotosByLinkIds(linkIds: List<String>): Flow<List<CloudPhoto>> =
        photoListingDao.observeByLinkIds(linkIds).map { list -> list.map { it.toDomain() } }

    suspend fun refreshCloudPhotos(userId: UserId, force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        refreshFullMutex.withLock {
            val sinceLast = System.currentTimeMillis() - lastFullRefreshCompletedMs.get()
            if (!force && lastFullRefreshCompletedMs.get() > 0L && sinceLast < minFullRefreshIntervalMs) {
                Log.d(TAG, "refreshCloudPhotos: coalesced — a full refresh completed ${sinceLast}ms ago")
                return@withLock
            }
            doRefreshCloudPhotos(userId, force)
            lastFullRefreshCompletedMs.set(System.currentTimeMillis())
        }
    }

    /**
     * Fetch one photo-stream page, retrying a few times on transient failure with a short
     * backoff. Rethrows CancellationException immediately; rethrows the last error once the
     * attempts are exhausted so the caller's pagination loop stops and leaves the saved cursor
     * in place for the next run to resume from.
     */
    private suspend fun fetchPhotoPageWithRetry(
        manager: ApiManager<out DriveApiService>,
        volumeId: String,
        previousPageLastLinkId: String?,
    ): PhotoLinksResponse =
        // Route through the shared 429 / 5xx-aware helper (jittered exponential backoff) rather
        // than a bespoke linear retry, so a rate-limited large-library listing backs off properly
        // and stops retrying permanent (non-transient) errors that a retry can't fix.
        eu.akoos.photos.util.retryWithBackoff(
            maxAttempts = PHOTO_PAGE_MAX_ATTEMPTS,
            baseMs = PHOTO_PAGE_RETRY_BASE_MS,
        ) {
            semaphore.withPermit {
                manager.invoke { getPhotoLinks(volumeId, previousPageLastLinkId) }.valueOrThrow
            }
        }

    private suspend fun doRefreshCloudPhotos(userId: UserId, force: Boolean = false) {
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

            // Resume support for large libraries. The full listing walk persists its page cursor
            // (and a "walked to the end" flag) so a page that throws partway through a
            // multi-thousand-photo library no longer restarts the whole walk from the newest
            // photo every launch — leaving older photos perpetually unfetched. A finished walk
            // clears the cursor; an interrupted one keeps it pointing at the last good page so the
            // next run continues from there until the entire library has been listed. A
            // previously-complete listing (or a first-ever run) walks fresh from the newest photo
            // so the stale-entry cleanup below gets a complete picture.
            val cursorKey = SettingsKeys.photoListingCursorKey(userId.id, activeVolumeId)
            val completeKey = SettingsKeys.photoListingCompleteKey(userId.id, activeVolumeId)
            val everCompleteKey = SettingsKeys.photoListingEverCompleteKey(userId.id, activeVolumeId)
            val listingPrefs = context.settingsDataStore.data.first()
            val previouslyComplete = listingPrefs[completeKey] ?: false
            // Once the library has been fully listed, a forced refresh (pull-to-refresh) walks fresh
            // from the newest photo so the stale-entry prune sees the complete server set and a
            // deletion made elsewhere on Drive disappears immediately. While a large library is still
            // being backfilled, even a forced refresh RESUMES the saved cursor — otherwise
            // pull-to-refresh would restart at the newest photo and re-hit the same rate-limit wall,
            // never advancing the backfill (older photos would stay unfetched). A resumed walk only
            // sees its tail, so it suppresses the prune, which is the right call mid-backfill.
            val resumeCursor = if (previouslyComplete) null else listingPrefs[cursorKey]
            val startedFresh = resumeCursor == null
            eu.akoos.photos.util.SyncDiagnostics.log("refresh start (force=$force, resume=${!startedFresh})")
            // A forced refresh mid-backfill resumes the saved cursor, so it only re-walks the older
            // tail and would miss photos added at the TOP of the library since (e.g. uploaded from
            // Drive web) until the backfill finally completes. Pull the newest page first — insert-
            // only, one cheap page — so new uploads appear right away without abandoning the backfill.
            if (force && !startedFresh) {
                try {
                    refreshNewestPage(userId)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "refreshCloudPhotos: newest-page pre-fetch failed: ${e.message}")
                }
            }
            // This walk is not complete until the loop below reaches the final short page; reset
            // up front so a crash/abort midway correctly leaves the listing marked incomplete.
            context.settingsDataStore.edit { it[completeKey] = false }

            // 2. Paginated photo stream — matches the official Drive SDK: the photos timeline
            // endpoint takes no Limit/PageSize param. Pass the last LinkID as PreviousPageLastLinkID
            // and walk until the server returns an empty page.
            val streamLinks = mutableListOf<PhotoLinkDto>()
            // Track whether the stream API responded without throwing (even if it returned 0 photos).
            // Used below to decide if it's safe to delete DB entries not found in this refresh.
            var streamCallSucceeded = false
            try {
                var lastLinkId: String? = resumeCursor
                if (resumeCursor != null) {
                    Log.d(TAG, "refreshCloudPhotos: resuming stream listing from saved cursor")
                }
                // Per-page failure counter. Reset to zero after every page that succeeds, so the
                // patience is "this one page failed N times in a row", not "the whole walk failed N
                // times". A large library that needs a rate-limit wait on many pages still completes;
                // only a page that keeps failing with no progress (server truly down) gives up for
                // this pass — the saved cursor lets a later run resume.
                var consecutiveFailures = 0
                do {
                    // fetchPhotoPageWithRetry already absorbs brief blips with a short jittered
                    // backoff. If a page still throws, completing the listing is what makes the whole
                    // library load, and the per-page cursor saved below makes resuming safe — so wait
                    // the page out (honouring the server's Retry-After) and retry the SAME page rather
                    // than abandoning the walk. Only a clearly-permanent auth failure rethrows
                    // immediately; every other error (a rate-limit the shared classifier doesn't
                    // recognise, a 5xx, a parse blip, a dropped connection) is waited out. Every wait
                    // is a delay(), never a spin.
                    var fetched: PhotoLinksResponse? = null
                    while (fetched == null) {
                        try {
                            fetched = fetchPhotoPageWithRetry(manager, activeVolumeId, lastLinkId)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            if (isPermanentListingError(e)) throw e
                            consecutiveFailures++
                            if (consecutiveFailures > MAX_LISTING_PAGE_RETRIES) {
                                eu.akoos.photos.util.SyncDiagnostics.log("listing give up: page stuck after $consecutiveFailures tries (${listingErrorDetail(e)})")
                                throw e
                            }
                            val waitMs = listingRetryWaitMs(e, consecutiveFailures)
                            eu.akoos.photos.util.SyncDiagnostics.log("listing rate-limited (${listingErrorDetail(e)}), wait ${waitMs}ms, try $consecutiveFailures")
                            Log.w(TAG, "refreshCloudPhotos: listing rate-limited, pausing ${waitMs}ms then resuming from cursor (try $consecutiveFailures)")
                            delay(waitMs)
                        }
                    }
                    consecutiveFailures = 0
                    val page = checkNotNull(fetched)
                    streamLinks.addAll(page.links)
                    eu.akoos.photos.util.SyncDiagnostics.log("listing page: +${page.links.size} (total ${streamLinks.size})")
                    // Stop ONLY on an empty page, never on a merely-short one. The Proton photos
                    // timeline endpoint can return a page with fewer than the requested limit while
                    // more photos still follow — it carries no More/AnchorID, so pagination is purely
                    // "give me what's after this LinkID". Treating any short page as the end truncated
                    // large libraries mid-listing and dropped every older photo after the short page
                    // (the root cause of the "timeline only goes back a few years" reports). The
                    // official Drive SDK paginates the same way: keep walking while a page returns any
                    // photos, stop when one comes back empty.
                    val nextCursor = if (page.links.isNotEmpty()) page.links.last().linkId else null
                    if (nextCursor != null && nextCursor == lastLinkId) {
                        // Defensive: a backend that returns a page ending on the same cursor we just
                        // queried would otherwise loop forever. Stop rather than hammer the API.
                        Log.w(TAG, "refreshCloudPhotos: cursor did not advance ($nextCursor), stopping the walk")
                        lastLinkId = null
                    } else if (nextCursor != null) {
                        // Persist the cursor before fetching the next page so a crash, process
                        // kill, or thrown page resumes from here rather than the newest photo.
                        context.settingsDataStore.edit { it[cursorKey] = nextCursor }
                        lastLinkId = nextCursor
                        delay(LISTING_PAGE_DELAY_MS)
                    } else {
                        lastLinkId = null
                    }
                } while (lastLinkId != null)
                streamCallSucceeded = true
                eu.akoos.photos.util.SyncDiagnostics.log("listing done: ${streamLinks.size} links")
                Log.d(TAG, "refreshCloudPhotos: stream walk reached the end, ${streamLinks.size} photos this pass")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // The cursor saved per page above stays pointing at the last good page, so the
                // next run resumes from there instead of restarting from the newest photo.
                eu.akoos.photos.util.SyncDiagnostics.log("listing PAUSED (${listingErrorDetail(e)}) at ${streamLinks.size} links")
                Log.w(TAG, "refreshCloudPhotos: stream paused at saved cursor (${e.javaClass.simpleName}: ${e.message})")
            }

            // Prune photos deleted elsewhere RIGHT AFTER the listing completes, not after the (much
            // slower, ~30s) per-batch detail/crypto processing below. On a fresh top-to-bottom walk the
            // listing (streamLinks) is already the complete server set, so it's a safe found-set — the
            // per-row processing only ADDS/updates rows, it never decides what's gone. This makes a
            // deletion made on Drive disappear within ~1s of a pull-to-refresh instead of ~30s later.
            // The slower foundIds-based cleanup at the end stays as a no-op safety net.
            if (startedFresh && streamCallSucceeded) {
                val listedIds = streamLinks.mapTo(HashSet()) { it.linkId }
                val recentUploads = recentUploadsTracker.snapshotWithinMs(UPLOAD_PROTECTION_WINDOW_MS)
                val existingIds = photoListingDao.getAllLinkIds(userId.id).toSet()
                val toDelete = (existingIds - listedIds - recentUploads).toList()
                    .let { ids ->
                        if (eu.akoos.photos.BuildConfig.DEBUG)
                            ids.filterNot { it.startsWith(LargeLibrarySim.LINK_ID_PREFIX) } else ids
                    }
                if (toDelete.isNotEmpty()) {
                    // Chunk to stay under SQLite's bound-variable limit on a big server-side deletion.
                    toDelete.chunked(500).forEach { photoListingDao.deleteByLinkIds(it) }
                    recentUploadsTracker.forget(toDelete)
                    Log.d(TAG, "refreshCloudPhotos: removed ${toDelete.size} stale entries (early, post-listing)")
                }
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
            val allPhotoLinks = streamLinks.sortedByDescending { it.captureTime ?: 0L }
            Log.d(TAG, "refreshCloudPhotos: ${allPhotoLinks.size} photos in stream")

            // Persist a minimal stub per streamed link up front, so a photo is in the dedup index
            // (keyed on its content hash) the moment pagination saw it — even if its detail batch
            // below later fails. INSERT OR IGNORE never clobbers a fully-built row from a prior run;
            // the per-chunk upsert in the detail loop then REPLACES each stub with the full row.
            // Chunked so the gallery Flow isn't churned by one huge insert.
            if (streamCallSucceeded && allPhotoLinks.isNotEmpty()) {
                val stubRows = allPhotoLinks.map { link ->
                    eu.akoos.photos.data.db.entity.PhotoListingEntity(
                        linkId = link.linkId,
                        shareId = effectiveShareId,
                        volumeId = activeVolumeId,
                        userId = userId.id,
                        captureTime = link.captureTime ?: 0L,
                        displayName = "",
                        mimeType = "",
                        sizeBytes = 0L,
                        revisionId = "",
                        thumbnailUrl = null,
                        contentHash = link.contentHash,
                        tagsCsv = (link.tags ?: emptyList()).sorted().joinToString(","),
                    )
                }
                stubRows.chunked(500).forEach { photoListingDao.insertStubsIgnore(it) }
                eu.akoos.photos.util.SyncDiagnostics.log("stubs inserted: ${allPhotoLinks.size}")
            }

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
            // Running tally of detail rows whose batch completed, for the diagnostics buffer only.
            var processed = 0
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
                    // Retry the heavy per-batch work on a transient rate-limit/5xx (jittered
                    // backoff) rather than letting one 429 fail the batch — a failed batch would
                    // block the sticky "listed at least once" marker and stall the upload gate. The
                    // stubs above already keep the photos in the dedup index, so this is purely to
                    // complete the detail rows gently.
                    eu.akoos.photos.util.retryWithBackoff(maxAttempts = 4, baseMs = 1_000L, maxBackoffMs = 30_000L) {
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
                        // Grid uses the small 512px (type 1) thumbnail to keep the adaptive cache
                        // small across a large library; the 1920px PHOTO thumbnail is for album
                        // cells / web, not the timeline grid.
                        val tid = thumbnailList?.firstOrNull { it.type == 1 }?.thumbnailId
                            ?: thumbnailList?.firstOrNull { it.type == 2 }?.thumbnailId
                            ?: thumbnailList?.firstOrNull()?.thumbnailId
                        if (tid != null) thumbnailIdToLinkId[tid] = linkId
                    }
                    val thumbnailUrlMap: Map<String, ThumbnailUrlInfo> =
                        if (thumbnailIdToLinkId.isNotEmpty())
                            linkDetailHelpers.batchFetchThumbnailUrls(userId, activeVolumeId, thumbnailIdToLinkId.keys.toList())
                        else emptyMap()
                    // Invert thumbnailId→linkId into linkId→ThumbnailUrlInfo once, so the per-stub
                    // lookup below is O(1) instead of an O(n) reverse scan inside the batch loop.
                    val thumbnailInfoByLinkId: Map<String, ThumbnailUrlInfo> =
                        thumbnailIdToLinkId.entries.mapNotNull { (tid, linkId) ->
                            thumbnailUrlMap[tid]?.let { linkId to it }
                        }.toMap()

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
                    // Accumulate every built/merged row for the WHOLE REFRESH_BATCH and upsert
                    // once after the chunk loop, instead of an upsert per 5-to-10-row crypto chunk.
                    // Each upsert makes Room re-emit the entire photo_listing table; collapsing
                    // ~10-20 per-chunk writes into one per batch cuts the gallery's full
                    // merge+sort+regroup re-runs by the same factor during the cold listing. The
                    // crypto-paced yield/delay cadence stays PER CHUNK so JNI/GC pressure is
                    // unchanged — only the DB write frequency drops.
                    val batchToSave = mutableListOf<eu.akoos.photos.data.db.entity.PhotoListingEntity>()
                    var chunkStart = 0
                    while (chunkStart < batch.size) {
                        val gentle = gentleSyncActive
                        val chunkSize = if (gentle) 5 else 10
                        val interChunkDelayMs = if (gentle) 300L else 100L
                        val chunk = batch.subList(chunkStart, minOf(chunkStart + chunkSize, batch.size))
                        chunkStart += chunkSize
                        val chunkLinkIds = chunk.map { it.linkId }
                        // True once this chunk ran the heavy decrypt/build path (a cache miss). A
                        // pure cache-hit chunk leaves it false and skips the yield/delay, so the
                        // steady-state refresh stays snappy exactly as before.
                        var chunkDidCryptoWork = false
                        // Fast path: photos already in DB with a still-on-disk cached thumbnail
                        // get re-used as-is. This avoids re-running the per-photo decrypt +
                        // thumbnail-decrypt pipeline on every cold start, which is what pushes
                        // the Go OpenPGP library into "slice bounds out of range" territory once
                        // enough crypto ops pile up. Cache-cleared installs still walk the full
                        // path (no cache hits possible), but the steady-state app launch with
                        // existing local cache touches almost no Go code at all.
                        val existingByLinkId = photoListingDao.getByLinkIds(chunkLinkIds).associateBy { it.linkId }
                        for (stub in chunk) {
                            val cached = existingByLinkId[stub.linkId]
                            // Reuse an existing row when its decrypted thumbnail is still on disk OR it's a
                            // lazy row that already carries its encrypted thumbnail material (the scheduler
                            // decrypts those on scroll). Rebuilding + re-saving such rows every refresh only
                            // churns the DB — which re-emits the whole gallery list and re-runs Go crypto for
                            // nothing. The recently-trashed guard keeps a just-deleted photo from being re-added
                            // by the fast path before the server stops returning it.
                            val lazyReady = cached != null &&
                                cached.serverThumbnailUrl != null &&
                                cached.contentKeyPacket != null &&
                                cached.encNodeKey != null
                            if (cached != null && !isRecentlyTrashed(stub.linkId) &&
                                (thumbnailHelpers.isCachedValid(cached.thumbnailUrl) || lazyReady)) {
                                // Fast path reuses the cached row to skip a crypto rebuild, BUT
                                // the server-side tags (PhotoTag IDs — 0=Favorite) on the freshly
                                // fetched stub can have changed since the last cache write (e.g.
                                // user favorited the photo on Drive Web). Reusing `cached` as-is
                                // dropped those updates silently, so the heart icon never appeared
                                // even after a forced pull-to-refresh. Merge the stub's tags into
                                // the cached row when they differ, otherwise leave it alone.
                                val stubTagsCsv = (stub.tags ?: emptyList()).sorted().joinToString(",")
                                val cachedTagsCsv = cached.tagsCsv
                                    .split(',')
                                    .mapNotNull { it.toIntOrNull() }
                                    .sorted()
                                    .joinToString(",")
                                // If the decrypted thumbnail file is gone (e.g. the user cleared the
                                // app cache) but the row still points at it, null the URL so the lazy
                                // scheduler re-decrypts it on scroll — PhotoCell only requests a decrypt
                                // when thumbnailUrl is null. Without this the fast path keeps a dead path
                                // and the cloud tile stays blank until a row rebuild.
                                val staleThumb = cached.thumbnailUrl != null &&
                                    !thumbnailHelpers.isCachedValid(cached.thumbnailUrl)
                                if (stubTagsCsv != cachedTagsCsv || staleThumb) {
                                    batchToSave += cached.copy(
                                        tagsCsv = if (stubTagsCsv != cachedTagsCsv) stubTagsCsv else cached.tagsCsv,
                                        thumbnailUrl = if (staleThumb) null else cached.thumbnailUrl,
                                    )
                                }
                                foundIds += stub.linkId
                                continue
                            }
                            // Cache miss: the build() below runs the Go-crypto path.
                            chunkDidCryptoWork = true
                            val detail = linkDetailMap[stub.linkId]
                            val parentId = detail?.link?.parentLinkId
                            val parentKeyBytes = parentKeyCache[parentId] ?: rootLinkKeyBytes
                            val thumbnailInfo = thumbnailInfoByLinkId[stub.linkId]
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
                                batchToSave += merged
                                foundIds += stub.linkId
                            }
                        }
                        // Pace only when this chunk actually did Go-crypto work; a pure cache-hit
                        // chunk skips both so the steady-state refresh stays snappy. The DB write
                        // itself is deferred to one upsert after the loop (below).
                        if (chunkDidCryptoWork) {
                            yield()
                            delay(interChunkDelayMs)
                        }
                    }
                    // Single write for the whole batch — one Room re-emission instead of one per chunk.
                    if (batchToSave.isNotEmpty()) {
                        photoListingDao.upsertAll(batchToSave)
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
                    }
                    processed += batch.size
                    eu.akoos.photos.util.SyncDiagnostics.log("detail: ${processed}/${allPhotoLinks.size}")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // One batch failing (a decode error, transient OOM on a single oversized batch,
                    // or a rate-limit that even the per-batch retry above couldn't outlast) must not
                    // abort the whole refresh: the chunks that already upserted stay in the DB and we
                    // press on to the next batch. But it DID leave a detail region unfetched, so it
                    // counts as a failed batch — that keeps the listing marked incomplete (below), so
                    // the next run re-walks fresh and re-fetches the gap once the limit clears. The
                    // stubs still hold every photo in the dedup index, so a failed batch never causes
                    // a re-upload; it only defers the "listing complete" declaration.
                    failedBatches++
                    eu.akoos.photos.util.SyncDiagnostics.log("detail batch FAILED (${e.javaClass.simpleName})")
                    Log.w(TAG, "refreshCloudPhotos: batch failed (${e.javaClass.simpleName}: ${e.message}), continuing")
                }
            }
            eu.akoos.photos.util.SyncDiagnostics.log("detail complete: processed $processed, $failedBatches failed batches")

            // Backfill any rows still left as stubs by a failed detail batch (rare, since the
            // per-batch retry above absorbs ordinary rate limits). One bounded pass over just the
            // gap — never a full re-walk — so a permanently-undecryptable photo can't loop.
            try {
                backfillIncompleteRows(
                    userId, activeVolumeId, effectiveShareId, rootLinkId, rootLinkKeyBytes,
                    ownPublicKeys, thumbnailCacheDir, accumulatedParentKeys, foundIds,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "refreshCloudPhotos: incomplete-row backfill failed: ${e.message}")
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
            // Completion is gated strictly: the listing is COMPLETE only when THIS pass started
            // fresh from the newest photo, paginated all the way to the final short page, AND had
            // zero failed detail batches. A resumed-tail pass (it only saw the older tail) or any
            // pass with a 429-truncated page or detail batch leaves the listing INCOMPLETE, so the
            // next run walks fresh and re-fetches the missing region once the limit clears.
            val listingComplete = startedFresh && streamCallSucceeded && failedBatches == 0
            // Pagination reaching the end is enough to clear the resume cursor (nothing left to
            // resume) and to open the sticky upload-dedup gate — every streamed link already has a
            // stub row in the dedup index, so a pass with a failed detail batch still can't cause a
            // re-upload (the backfill fills the row, the next fresh walk completes it).
            if (streamCallSucceeded) {
                context.settingsDataStore.edit {
                    // Strict: only a clean fresh full walk marks the listing complete, so the
                    // fresh-vs-resume decision and the prune downstream never act on a truncated set.
                    it[completeKey] = listingComplete
                    // Sticky "listed at least once" marker — keyed on pagination success (not
                    // failedBatches) so a transient 429 on a detail batch can't strand the upload on
                    // "Preparing backup"; never cleared at a later walk start (only on sign-out).
                    it[everCompleteKey] = true
                    it.remove(cursorKey)
                }
            }

            // Stale-entry cleanup needs a COMPLETE picture of the server's photos, so it only runs
            // after a FRESH top-to-bottom walk that finished with no failed batches — the exact same
            // condition that marks the listing complete above. A resumed walk only saw the older tail
            // this pass, so its found-set would wrongly treat the newer rows (listed in an earlier
            // run) as gone and delete them.
            val safeToPrune = listingComplete
            if (safeToPrune) {
                // Whole picture in hand → safe to clean up stale entries. Use the tight
                // protection window (see comment above) rather than the full TTL.
                val recentUploads = recentUploadsTracker.snapshotWithinMs(UPLOAD_PROTECTION_WINDOW_MS)
                val existingIds = photoListingDao.getAllLinkIds(userId.id).toSet()
                // Stub rows (detail not fetched yet) carry a valid contentHash but are absent from
                // foundIds when their detail batch failed transiently. Exclude them so a rate-limit
                // can't make the prune delete the very dedup rows the stub upsert just added.
                val stubIds = photoListingDao.getIncompleteRows(userId.id).map { it.linkId }.toSet()
                val toDelete = (existingIds - foundIds - recentUploads - stubIds).toList()
                    // The debug large-library simulator's synthetic rows aren't in the server
                    // listing, so a refresh must not prune them out from under an active test.
                    .let { ids ->
                        if (eu.akoos.photos.BuildConfig.DEBUG)
                            ids.filterNot { it.startsWith(LargeLibrarySim.LINK_ID_PREFIX) } else ids
                    }
                if (toDelete.isNotEmpty()) {
                    // Chunk to stay under SQLite's bound-variable limit on a big server-side deletion.
                    toDelete.chunked(500).forEach { photoListingDao.deleteByLinkIds(it) }
                    // Forget them in the tracker too — they're confirmed gone from server, so
                    // they should never be "protected" again on a subsequent refresh.
                    recentUploadsTracker.forget(toDelete)
                    Log.d(TAG, "refreshCloudPhotos: removed ${toDelete.size} stale entries " +
                        "(protected ${recentUploads.size} recent uploads within ${UPLOAD_PROTECTION_WINDOW_MS}ms window)")
                }
                Log.d(TAG, "refreshCloudPhotos: saved ${foundIds.size} photos")
            } else {
                Log.d(TAG, "refreshCloudPhotos: partial/resumed pass (fresh=$startedFresh, streamOk=$streamCallSucceeded, failedBatches=$failedBatches) — upserted ${foundIds.size} photos, skipping stale-entry cleanup to avoid data loss")
            }
            // Report completeness so the incremental fallback knows whether the event anchor
            // may be persisted (see [lastFullRefreshComplete]). Only true after a clean fresh full
            // walk: the pass started fresh, listed to the end, AND every batch processed cleanly.
            lastFullRefreshComplete = listingComplete
        } catch (e: DriveNotFoundException) {
            Log.w(TAG, "refreshCloudPhotos: DriveNotFoundException: ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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

    /**
     * Fills the detail (name, mime, size, revision, thumbnail material) on rows the full-walk left
     * as bare stubs because their detail batch failed. ONE bounded pass over [getIncompleteRows]
     * only — never a re-walk of the whole listing — and it just upserts the completed rows, so a
     * photo that stays undecryptable is left as a stub (still dedup-safe via its content hash) and
     * not retried in a loop. Mirrors the per-photo build in [doRefreshCloudPhotos]; every completed
     * linkId is added to [foundIds] so the caller's stale-entry prune doesn't treat it as gone.
     */
    private suspend fun backfillIncompleteRows(
        userId: UserId,
        activeVolumeId: String,
        effectiveShareId: String,
        rootLinkId: String?,
        rootLinkKeyBytes: ByteArray?,
        ownPublicKeys: List<String>,
        thumbnailCacheDir: File,
        accumulatedParentKeys: MutableMap<String, ByteArray>,
        foundIds: MutableSet<String>,
    ) {
        val incomplete = photoListingDao.getIncompleteRows(userId.id)
        if (incomplete.isEmpty()) return
        Log.d(TAG, "refreshCloudPhotos: backfilling ${incomplete.size} incomplete row(s)")

        for (rows in incomplete.chunked(REFRESH_BATCH)) {
            val linkIds = rows.map { it.linkId }
            val linkDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, activeVolumeId, linkIds)

            // Parent keys: reuse what the main loop already decrypted, plus any album parents that
            // only appear among these rows. Same shape as the detail loop above.
            val parentKeyCache = mutableMapOf<String?, ByteArray?>()
            parentKeyCache[rootLinkId] = rootLinkKeyBytes
            for ((k, v) in accumulatedParentKeys) parentKeyCache[k] = v
            val albumParentIds = linkDetailMap.values
                .mapNotNull { it.link.parentLinkId }
                .filter { it != rootLinkId && parentKeyCache[it] == null }
                .toSet()
            if (albumParentIds.isNotEmpty() && rootLinkKeyBytes != null) {
                val albumDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, activeVolumeId, albumParentIds.toList())
                for ((albumId, albumDetail) in albumDetailMap) {
                    val aLink = albumDetail.link
                    if (aLink.nodeKey != null && aLink.nodePassphrase != null) {
                        parentKeyCache[albumId] = try {
                            cryptoHelper.decryptNodeKey(aLink.nodeKey, aLink.nodePassphrase, rootLinkKeyBytes)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w(TAG, "refreshCloudPhotos: backfill albumKey failed for $albumId: ${e.message}")
                            rootLinkKeyBytes
                        }
                    }
                }
            }
            for ((k, v) in parentKeyCache) {
                if (k != null && v != null) accumulatedParentKeys[k] = v
            }

            val thumbnailIdToLinkId = mutableMapOf<String, String>()
            for ((linkId, detail) in linkDetailMap) {
                val thumbnailList = detail.link.fileProperties?.activeRevision?.thumbnails
                    ?: detail.photo?.activeRevision?.thumbnails
                val tid = thumbnailList?.firstOrNull { it.type == 1 }?.thumbnailId
                    ?: thumbnailList?.firstOrNull { it.type == 2 }?.thumbnailId
                    ?: thumbnailList?.firstOrNull()?.thumbnailId
                if (tid != null) thumbnailIdToLinkId[tid] = linkId
            }
            val thumbnailUrlMap: Map<String, ThumbnailUrlInfo> =
                if (thumbnailIdToLinkId.isNotEmpty())
                    linkDetailHelpers.batchFetchThumbnailUrls(userId, activeVolumeId, thumbnailIdToLinkId.keys.toList())
                else emptyMap()
            val thumbnailInfoByLinkId: Map<String, ThumbnailUrlInfo> =
                thumbnailIdToLinkId.entries.mapNotNull { (tid, linkId) ->
                    thumbnailUrlMap[tid]?.let { linkId to it }
                }.toMap()

            val ckpMap = linkDetailHelpers.batchFetchContentKeyPackets(userId, effectiveShareId, linkIds)

            val toSave = mutableListOf<eu.akoos.photos.data.db.entity.PhotoListingEntity>()
            for (row in rows) {
                val detail = linkDetailMap[row.linkId] ?: continue
                // Rebuild the stub the builder expects from the persisted row (it carries the wire
                // fields the dedup keys on: capture time, content hash, tags).
                val stub = PhotoLinkDto(
                    linkId = row.linkId,
                    captureTime = row.captureTime,
                    contentHash = row.contentHash,
                    tags = if (row.tagsCsv.isEmpty()) emptyList()
                           else row.tagsCsv.split(',').mapNotNull { it.toIntOrNull() },
                )
                val parentId = detail.link.parentLinkId
                val parentKeyBytes = parentKeyCache[parentId] ?: rootLinkKeyBytes
                val thumbnailInfo = thumbnailInfoByLinkId[row.linkId]
                if (!isRecentlyTrashed(row.linkId)) {
                    toSave += photoEntityBuilder.build(
                        stub, detail, userId, effectiveShareId, activeVolumeId, parentKeyBytes,
                        thumbnailCacheDir, thumbnailInfo, ckpMap[row.linkId], ownPublicKeys,
                        decryptThumbnail = false,
                    )
                    foundIds += row.linkId
                }
            }
            if (toSave.isNotEmpty()) {
                photoListingDao.upsertAll(toSave)
                yield()
                delay(100)
            }
        }
    }

    /**
     * Resume-time poll for a fully-listed account that can't arm an event anchor (its events
     * endpoint returns "Invalid ID"). Fetches ONLY the newest listing page and inserts any photo not
     * already in the DB — a new Drive upload (this app's or another client's) is the newest, so it
     * shows up within one cheap page instead of forcing a full re-walk of the whole library on every
     * resume. Strictly insert-only: it never prunes, never touches the listing cursor or completion
     * flag, so it can't drop a row the full refresh owns. Mirrors the per-photo build in
     * [doRefreshCloudPhotos] for the handful of genuinely new links.
     */
    private suspend fun refreshNewestPage(userId: UserId) {
        try {
            val volumeId = shareService.getVolumeId(userId)
            val shareId = shareService.getShareId(userId, volumeId)
            shareService.ensurePhotosVolumeReady(userId)
            val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId) ?: return
            val manager = apiProvider.get<DriveApiService>(userId)
            val thumbnailCacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }
            val activeVolumeId = shareService.volumeId() ?: volumeId
            val effectiveShareId = shareService.shareId() ?: shareId
            val rootLinkId = shareService.photosRootLinkId()
            val ownPublicKeys = cryptoHelper.getOwnPublicKeysArmored(userId)

            val page = fetchPhotoPageWithRetry(manager, activeVolumeId, null)
            if (page.links.isEmpty()) return

            // Only photos we don't already have are interesting — new uploads. Tag changes and
            // deletes stay the full refresh's job; this path is purely additive.
            val existing = photoListingDao.getByLinkIds(page.links.map { it.linkId }).associateBy { it.linkId }
            val newStubs = page.links.filter { existing[it.linkId] == null && !isRecentlyTrashed(it.linkId) }
            if (newStubs.isEmpty()) return
            Log.d(TAG, "refreshNewestPage: ${newStubs.size} new photo(s) on the newest page")

            val newLinkIds = newStubs.map { it.linkId }
            val linkDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, activeVolumeId, newLinkIds)

            // Parent keys: root plus any album parents among the new photos (mirrors doRefreshCloudPhotos).
            val parentKeyCache = mutableMapOf<String?, ByteArray?>()
            parentKeyCache[rootLinkId] = rootLinkKeyBytes
            val albumParentIds = linkDetailMap.values
                .mapNotNull { it.link.parentLinkId }
                .filter { it != rootLinkId }
                .toSet()
            if (albumParentIds.isNotEmpty()) {
                val albumDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, activeVolumeId, albumParentIds.toList())
                for ((albumId, albumDetail) in albumDetailMap) {
                    val aLink = albumDetail.link
                    if (aLink.nodeKey != null && aLink.nodePassphrase != null) {
                        parentKeyCache[albumId] = try {
                            cryptoHelper.decryptNodeKey(aLink.nodeKey, aLink.nodePassphrase, rootLinkKeyBytes)
                        } catch (e: Exception) {
                            Log.w(TAG, "refreshNewestPage: albumKey failed for $albumId: ${e.message}")
                            rootLinkKeyBytes
                        }
                    }
                }
            }

            // Thumbnail URLs (prefer the small 512px type-1, same as the grid).
            val thumbnailIdToLinkId = mutableMapOf<String, String>()
            for ((linkId, detail) in linkDetailMap) {
                val thumbnailList = detail.link.fileProperties?.activeRevision?.thumbnails
                    ?: detail.photo?.activeRevision?.thumbnails
                val tid = thumbnailList?.firstOrNull { it.type == 1 }?.thumbnailId
                    ?: thumbnailList?.firstOrNull { it.type == 2 }?.thumbnailId
                    ?: thumbnailList?.firstOrNull()?.thumbnailId
                if (tid != null) thumbnailIdToLinkId[tid] = linkId
            }
            val thumbnailUrlMap: Map<String, ThumbnailUrlInfo> =
                if (thumbnailIdToLinkId.isNotEmpty())
                    linkDetailHelpers.batchFetchThumbnailUrls(userId, activeVolumeId, thumbnailIdToLinkId.keys.toList())
                else emptyMap()
            val thumbnailInfoByLinkId: Map<String, ThumbnailUrlInfo> =
                thumbnailIdToLinkId.entries.mapNotNull { (tid, linkId) ->
                    thumbnailUrlMap[tid]?.let { linkId to it }
                }.toMap()

            val ckpMap = linkDetailHelpers.batchFetchContentKeyPackets(userId, effectiveShareId, newLinkIds)

            val toSave = mutableListOf<eu.akoos.photos.data.db.entity.PhotoListingEntity>()
            for (stub in newStubs) {
                val detail = linkDetailMap[stub.linkId]
                val parentId = detail?.link?.parentLinkId
                val parentKeyBytes = parentKeyCache[parentId] ?: rootLinkKeyBytes
                val thumbnailInfo = thumbnailInfoByLinkId[stub.linkId]
                toSave += photoEntityBuilder.build(
                    stub, detail, userId, effectiveShareId, activeVolumeId, parentKeyBytes,
                    thumbnailCacheDir, thumbnailInfo, ckpMap[stub.linkId], ownPublicKeys,
                    decryptThumbnail = false,
                )
            }
            if (toSave.isEmpty()) return
            photoListingDao.upsertAll(toSave)

            // Seed the lazy scheduler with these parents, then prefetch the new rows so the freshly
            // inserted photo binds a real thumbnail at the top of the gallery instead of a blank.
            val parentKeys = parentKeyCache.entries
                .mapNotNull { (k, v) -> if (k != null && v != null) k to v else null }
                .toMap()
            if (parentKeys.isNotEmpty()) thumbnailDecryptScheduler.populateParentKeys(parentKeys)
            thumbnailDecryptScheduler.prefetch(userId, toSave)

            context.settingsDataStore.edit { it[SettingsKeys.LAST_SYNC_MS] = System.currentTimeMillis() }
            Log.d(TAG, "refreshNewestPage: inserted ${toSave.size} new photo(s)")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "refreshNewestPage failed: ${e.message}")
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
                // A fully-listed account that still can't arm an event anchor (its events endpoint
                // returns "Invalid ID") would otherwise re-walk the whole library on every resume
                // just to notice one new photo. Poll only the newest listing page instead — a new
                // upload (this app's or another client's) is the newest, so it lands within one cheap
                // page. The rate-limited full refresh below still runs (far less often) to catch
                // deletes, backfill the rest, and retry arming the anchor.
                val newestPageCompleteKey = SettingsKeys.photoListingCompleteKey(userId.id, volumeId)
                if (prefs[newestPageCompleteKey] == true) refreshNewestPage(userId)

                // Rate-limit the fallback full-refresh — if getLatestEventAnchor keeps
                // returning "Invalid ID" every call would otherwise re-trigger a full
                // refresh, looping forever (see lastFallbackFullRefreshMs comment above).
                // CAS the timestamp atomically so two simultaneous callers can't both
                // pass the gate on the same stale value: the loser sees the winner's
                // freshly-written `now` and falls into the skip branch.
                val now = System.currentTimeMillis()
                val prev = lastFallbackFullRefreshMs.get()
                val elapsed = now - prev
                // Backfilling (lastFullRefreshComplete == false) → short floor so an interrupted
                // listing finishes; fully listed → long poll interval so a missing-anchor account
                // doesn't re-walk the whole stream on every unlock.
                val cooldown = if (lastFullRefreshComplete) completedListingPollCooldownMs
                               else fallbackFullRefreshCooldownMs
                if (prev > 0L && elapsed < cooldown) {
                    Log.d(TAG, "incremental: skipping fallback full-refresh — last ${elapsed}ms ago (cooldown ${cooldown}ms)")
                    return
                }
                if (!lastFallbackFullRefreshMs.compareAndSet(prev, now)) {
                    // Another caller raced past us — they own this refresh window, bail.
                    Log.d(TAG, "incremental: skipping fallback full-refresh — another caller claimed the window")
                    return
                }

                refreshCloudPhotos(userId)
                // Re-arm the cooldown from when the walk FINISHED, not when it started: with the
                // mid-walk resume-after-backoff a single pass can run for minutes, so arming only up
                // front would let a partial backfill keep re-claiming the floor and stall. (The CAS
                // above still atomically claims the window so two racing callers can't double-walk.)
                lastFallbackFullRefreshMs.set(System.currentTimeMillis())
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
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
                val thumbnailInfoByLinkId: Map<String, ThumbnailUrlInfo> =
                    thumbnailIdToLinkId.entries.mapNotNull { (tid, lId) ->
                        thumbnailUrlMap[tid]?.let { lId to it }
                    }.toMap()

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
                    val thumbnailInfo = thumbnailInfoByLinkId[linkId]
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (eu.akoos.photos.util.isTransientApiError(e)) {
                // A transient rate-limit on the event feed must not escalate to a full library
                // re-walk. The anchor isn't advanced on this path, so the next incremental pass
                // retries the same events instead of re-listing everything.
                Log.w(TAG, "refreshCloudPhotosIncremental: transient error, retrying incrementally next pass: ${e.message}")
            } else {
                Log.e(TAG, "refreshCloudPhotosIncremental failed, falling back to full refresh", e)
                refreshCloudPhotos(userId)
            }
        }
    }
}
