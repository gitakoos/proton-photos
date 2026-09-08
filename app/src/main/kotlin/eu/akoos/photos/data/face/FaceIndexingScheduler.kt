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

package eu.akoos.photos.data.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.domain.model.FaceBoxNorm
import eu.akoos.photos.domain.usecase.FACE_CLUSTER_PARAMS_VERSION
import eu.akoos.photos.domain.usecase.FACE_CLUSTER_THRESHOLD
import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import eu.akoos.photos.domain.usecase.FACE_MODEL_VERSION
import eu.akoos.photos.domain.usecase.clusterFaces
import eu.akoos.photos.domain.usecase.cosineSimilarity
import eu.akoos.photos.domain.usecase.REATTACH_MIN_IOU
import eu.akoos.photos.domain.usecase.ReattachFace
import eu.akoos.photos.domain.usecase.ReattachLabel
import eu.akoos.photos.domain.usecase.matchReattachLabels
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.crypto.DecryptPriority
import eu.akoos.photos.crypto.DecryptPriorityContext
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.FaceScanDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.FaceScanEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.drive.PhotoDownloadService
import eu.akoos.photos.data.repository.drive.ThumbnailDecryptScheduler
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.ClusterFacesUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.service.FaceIndexingService
import eu.akoos.photos.util.DeviceHealthPolicy
import eu.akoos.photos.util.FaceDiagnostics
import eu.akoos.photos.util.NetworkObserver
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "FaceIndexingSched"

/** Where the background face-indexing walk currently stands, so Settings can label it. */
enum class FaceIndexingState { Idle, WaitingModel, Running, Paused, Done }

/**
 * A snapshot of the indexer's progress. [indexed] out of [total] photos have been scanned in the
 * current pass; both are 0 in the states that do no walk ([FaceIndexingState.Idle],
 * [FaceIndexingState.WaitingModel]).
 */
data class FaceIndexingProgress(
    val state: FaceIndexingState,
    val indexed: Int,
    val total: Int,
)

/** How large a not-yet-indexed backlog promotes the initial walk onto a foreground service with a
 *  progress notification. Below it the walk stays on the plain in-process coroutine, so a handful of
 *  incremental photos never posts a notification. */
const val FACE_INDEX_FOREGROUND_THRESHOLD = 50

/**
 * Whether an about-to-run indexing pass of [pending] photos should host itself on the foreground
 * service: only when the background switch is on and the backlog is at least
 * [FACE_INDEX_FOREGROUND_THRESHOLD]. Pure so it is unit-tested without Android.
 */
fun shouldRunForegroundIndex(pending: Int, backgroundEnabled: Boolean): Boolean =
    backgroundEnabled && pending >= FACE_INDEX_FOREGROUND_THRESHOLD

/**
 * Walks the whole library once and records the faces in each photo, in the background, only while
 * the AI features are on and the user has not paused it.
 *
 * The walk mirrors the bounded-scheduler shape the rest of the app uses (see
 * [eu.akoos.photos.data.repository.LocalExifBackfillScheduler] and [ThumbnailDecryptScheduler]): a
 * small fixed worker pool drains the not-yet-scanned remainder off the main thread, one photo per
 * turn, so a 15k-photo library never floods memory or the crypto service. It is idempotent and
 * resumable: the skip set is [FaceScanDao.scannedKeysForUser], the photo keys the walk has already
 * scanned, so a re-run only touches photos it has never looked at. A read error on one photo skips
 * that photo and never aborts the walk.
 *
 * Photo identity is [GalleryItem.stableId] (a cloud linkId or a device content URI), which is the
 * key stored on each [FaceEntity.photoKey] and each [FaceScanEntity.photoKey], so a later person
 * filter joins straight back to the timeline.
 *
 * A photo the detector clears of faces writes no face row, but the walk still records that it scanned
 * the photo, so the skip set covers faceless photos as well as ones that held a face. A photo is
 * marked only once its source loaded and detection actually ran; one deferred for want of Wi-Fi or
 * budget (no source this pass) is left unmarked so a later pass still reaches it. Persisting the
 * marker is what keeps a faceless photo from being re-decoded (and, for a cloud photo, re-downloaded)
 * on every gallery re-entry, rather than only within one process.
 *
 * Memory is the hard constraint here. Only bounded bitmaps are decoded (long edge ~[SOURCE_MAX_EDGE]),
 * every intermediate is recycled at once, the worker pool is tiny, and the detector + embedder ONNX
 * sessions are serialised through [mlLock] so at most one network run is ever resident. A device photo
 * (LocalOnly, or the local twin of a Synced pair) is read straight off its local file. A cloud photo is
 * detected from its full-resolution image: the same download+decrypt the viewer uses fetches it into
 * the `fullres` cache, it is decoded bounded, and the full-res file is deleted right after, so the whole
 * library is never stored (only the small face rows persist). That fetch runs only on Wi-Fi and is
 * rate-limited per pass, one download at a time through [downloadLock]; off Wi-Fi or once the budget is
 * spent the photo is left for a later pass rather than indexed from a low-detail thumbnail, since a
 * thumbnail's tiny faces are exactly what the full-res path is here to avoid. A cloud video keeps to its
 * still-frame thumbnail, the only bitmap a video container yields.
 */
@Singleton
class FaceIndexingScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val faceDao: FaceDao,
    private val faceScanDao: FaceScanDao,
    private val personDao: PersonDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
    private val photoListingDao: PhotoListingDao,
    private val thumbnailScheduler: ThumbnailDecryptScheduler,
    private val photoDownloadService: PhotoDownloadService,
    private val networkObserver: NetworkObserver,
    // Broken with a Provider: GetGalleryItemsUseCase depends on DrivePhotoRepository, which depends
    // on this scheduler, so a direct injection would be a construction cycle. The Provider defers the
    // use-case's construction to the first walk, by which point the repository graph is built.
    private val galleryItemsProvider: Provider<GetGalleryItemsUseCase>,
    private val clusterFacesUseCase: ClusterFacesUseCase,
    private val deviceHealth: DeviceHealthPolicy,
) {
    /** Own scope so an unpause kick and the settings watcher outlive the caller that triggered them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val faceModelManager by lazy { FaceModelManager(context) }
    private val faceEmbeddingModelManager by lazy { FaceEmbeddingModelManager(context) }

    /** Serialises the two ONNX sessions: neither is safe for concurrent runs, and holding one run at
     *  a time also caps the transient tensor memory a detection or embedding allocates. */
    private val mlLock = Mutex()

    /** Caps how many cloud full-res downloads run at once. A few in parallel is the same shape the
     *  photo download path already uses, and overlapping them is what makes a cloud scan keep pace
     *  rather than crawl one file at a time. Kept separate from [mlLock] so the downloads overlap the
     *  serialised ONNX inference. */
    private val downloadLock = Semaphore(FULLRES_DOWNLOAD_CONCURRENCY)

    /** One walk at a time: a second trigger (refresh, unpause) while one runs is a no-op instead of
     *  stacking a second full library walk and its item set in memory. */
    private val running = AtomicBoolean(false)

    /** Live mirror of the two gates, kept current by [watchSettings] so the per-photo check is a
     *  volatile read rather than a DataStore round trip per item. */
    @Volatile private var aiEnabled = false
    @Volatile private var paused = false

    /** Set by [reset] to stop a running walk promptly on sign-out; cleared at the start of each walk. */
    @Volatile private var stopRequested = false

    /** A resume asked for while a walk was still winding down: the walk's own tail re-kicks a fresh
     *  one when it lands, so a pause-then-resume never wedges with nothing running. */
    @Volatile private var restartRequested = false

    /** The last account a walk ran for, so an unpause can resume without being handed a userId. */
    @Volatile private var lastUserId: UserId? = null

    /** When the last mid-walk clustering ran (elapsedRealtime), so groups can refresh during a long
     *  drain without re-clustering on every pass. */
    @Volatile private var lastIncrementalClusterMs = 0L

    private val _progress = MutableStateFlow(FaceIndexingProgress(FaceIndexingState.Idle, 0, 0))
    val progress: StateFlow<FaceIndexingProgress> = _progress.asStateFlow()

    init {
        watchSettings()
        // Mirror every progress emit into the copied diagnostics, so a scan standing still short of the
        // end is legible there rather than a blind spot.
        scope.launch { progress.collect { FaceDiagnostics.record(it.state.name, it.indexed, it.total) } }
    }

    /** Keep [aiEnabled] / [paused] current so a mid-walk toggle is seen on the next per-photo check. */
    private fun watchSettings() {
        scope.launch {
            context.settingsDataStore.data.collect { prefs ->
                aiEnabled = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
                paused = prefs[SettingsKeys.FACE_INDEXING_PAUSED] == true
            }
        }
    }

    /**
     * Index every not-yet-indexed photo for [userId], newest listing first. A no-op (with the state
     * set accordingly) when the AI features are off, when the user has paused indexing, or when a
     * model is not on the device yet (a later trigger retries once it is). Safe to call repeatedly;
     * overlapping calls collapse to one walk.
     */
    suspend fun indexAll(userId: UserId?) {
        lastUserId = userId
        val prefs = context.settingsDataStore.data.first()
        aiEnabled = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
        paused = prefs[SettingsKeys.FACE_INDEXING_PAUSED] == true
        // Dark when off: no model prep, no enumeration, no walk.
        if (!aiEnabled) { setIdle(); return }
        if (paused) { _progress.value = FaceIndexingProgress(FaceIndexingState.Paused, 0, 0); return }
        // A walk is already running: instead of dropping this request, ask the active walk to re-kick
        // when it finishes, so a start requested during a winding-down pass is never lost (which is
        // what made a fresh start after import or reset appear to do nothing).
        if (!running.compareAndSet(false, true)) { restartRequested = true; return }
        stopRequested = false
        // Photos that actually got a source this walk. Zero means no progress (off Wi-Fi, or the
        // budget could load nothing more), so the tail does not re-kick and spin.
        val sourcesLoaded = AtomicInteger(0)
        try {
            val detFile = (faceModelManager.prepare() as? FaceModelPreparation.Ready)?.file
            val embFile = (faceEmbeddingModelManager.prepare() as? FaceModelPreparation.Ready)?.file
            if (detFile == null || embFile == null) {
                _progress.value = FaceIndexingProgress(FaceIndexingState.WaitingModel, 0, 0)
                return
            }

            // The models are on the device and a walk is about to run, so this is the choke point to
            // reconcile the stored embeddings' model version. On a recognition-model change the old
            // rows are wiped once here, before anything reads them, and the walk below rebuilds every
            // embedding with the current model.
            runCatching { migrateFaceModelVersionIfNeeded(userId) }
                .onFailure { Log.w(TAG, "face model version wipe failed: ${it.message}") }

            val items = runCatching {
                val provider = galleryItemsProvider.get()
                (if (userId == null) provider.invokeLocalOnly() else provider.invoke(userId)).first()
            }.getOrDefault(emptyList())
            val alreadyScanned = runCatching { faceScanDao.scannedKeysForUser(userId?.id ?: PhotoLocationEntity.LOCAL_USER).toHashSet() }
                .getOrDefault(HashSet())
            val pending = items.filter { it.stableId !in alreadyScanned }
            val total = pending.size
            // Progress is cumulative across the auto-continue passes, not per pass: the total is the
            // whole library and the indexed value is how many photos have been scanned so far, so the
            // bar climbs steadily to full instead of resetting to 0 with a shrinking total each pass.
            val libraryTotal = items.size
            val scannedBefore = libraryTotal - total
            // Nothing left to scan: the library is fully drained, so this is the one place clustering
            // runs. Only when new faces were added since the last cluster, so a gallery re-entry that
            // finds everything already grouped does no work. A debug build re-clusters every drained
            // pass instead, so changing the cluster threshold and relaunching re-groups the stored
            // embeddings with no re-embed; release keeps the new-faces-only guard.
            if (total == 0) {
                // The library is fully re-detected, so any names a wipe or an import parked can now be
                // put back on the faces before the groups form.
                val reattached = reattachPendingLabels(userId)
                val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
                // A clustering-parameter bump, a label reattach, or a debug build needs the global pass: it
                // regroups the stored embeddings once with no re-embed, and the new params generation is
                // recorded only after the pass actually completes (a failed one simply retries next walk).
                val paramsOutdated =
                    (prefs[SettingsKeys.FACE_CLUSTER_PARAMS_VERSION_KEY] ?: 0) != FACE_CLUSTER_PARAMS_VERSION
                if (BuildConfig.DEBUG || reattached || paramsOutdated) {
                    val grouped = cluster(userId)
                    if (paramsOutdated && grouped) {
                        context.settingsDataStore.edit {
                            it[SettingsKeys.FACE_CLUSTER_PARAMS_VERSION_KEY] = FACE_CLUSTER_PARAMS_VERSION
                        }
                    }
                } else {
                    // Routine new photos: let the incremental pass attach them to existing people AND form
                    // new clusters from the fresh faces, so the whole library is not regrouped just to place
                    // a few. Only the weak faces it cannot confidently place are left over, and those fall to
                    // a full regroup (which routes them to Unsorted) so none are orphaned.
                    tryAssignNewFaces(userId)
                    if (faceDao.unclusteredCount(account) > 0) cluster(userId)
                }
                _progress.value = FaceIndexingProgress(FaceIndexingState.Done, libraryTotal, libraryTotal)
                return
            }
            _progress.value = FaceIndexingProgress(FaceIndexingState.Running, scannedBefore, libraryTotal)

            // Promote a substantial initial backlog onto a foreground service so the walk survives the
            // app being swiped from Recents; a few incremental photos stay on this coroutine with no
            // notification. Idempotent across passes, and swallowed if a background start is disallowed.
            // A guest gets the same host: the service carries the local partition, so a large signed-out
            // library is exactly as durable as a signed-in one.
            if (shouldRunForegroundIndex(total, prefs[SettingsKeys.FACE_INDEX_BACKGROUND] != false)) {
                FaceIndexingService.start(context, userId)
            }

            val detector = debugTimed("face detector") { FaceDetector(detFile) }
            val embedder = debugTimed("embedder") { FaceEmbedder(embFile) }
            val cursor = AtomicInteger(0)
            val processed = AtomicInteger(0)
            val facesThisPass = AtomicInteger(0)
            // Running tally of accepted detection scores, logged with the size-gate drop count at the
            // pass end so the junk-vs-real score split is visible for tuning the threshold. Debug only.
            val acceptedScores: MutableList<Float>? = if (BuildConfig.DEBUG) ArrayList() else null
            // How many crops the blur gate dropped this pass, and the accepted crops' sharpness, logged
            // together at the pass end so the reject floor can be raised from the real spread. Debug only.
            val droppedTooBlurry = AtomicInteger(0)
            val acceptedBlur: MutableList<Float>? = if (BuildConfig.DEBUG) ArrayList() else null
            val passStart = SystemClock.elapsedRealtime()
            val decryptBudget = AtomicInteger(MAX_COLD_DECRYPTS_PER_PASS)
            // Photos this pass actually scanned (source loaded, detection ran), collected across the
            // workers and persisted as skip markers once the walk finishes; a deferred photo is never
            // added, so it stays pending for a later pass. Concurrent because the workers add in parallel.
            val scannedKeys = ConcurrentHashMap.newKeySet<String>()
            // Markers are also flushed mid-pass in chunks (not only at pass end), so a long single-pass
            // drain that is killed keeps the photos it already scanned. [persistedThisPass] counts the keys
            // already flushed-and-removed, so progress stays the running scanned total even though
            // [scannedKeys] then holds only the not-yet-flushed remainder. One flush runs at a time.
            val persistedThisPass = AtomicInteger(0)
            val scanFlushing = AtomicBoolean(false)
            try {
                coroutineScope {
                    repeat(WORKER_COUNT) {
                        launch {
                            while (active()) {
                                // Heavy face indexing stands down while the device is not in a good
                                // state: the user is interacting, or the phone is hot / low on battery /
                                // in the power saver. Poll so a pause or sign-out still stops the walk
                                // promptly, and so the models stay loaded across a short pause rather
                                // than reloading them on every scroll.
                                while (active() && !deviceHealth.heavyMlAllowed()) {
                                    FaceDiagnostics.recordPark(deviceHealth.verdict().reason)
                                    delay(HEALTH_PAUSE_POLL_MS)
                                }
                                if (!active()) break
                                val i = cursor.getAndIncrement()
                                if (i >= total) break
                                val item = pending[i]
                                try {
                                    indexOne(item, userId, detector, embedder, decryptBudget, sourcesLoaded, scannedKeys, facesThisPass, acceptedScores, droppedTooBlurry, acceptedBlur)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    Log.w(TAG, "index ${item.stableId} failed: ${e.message}")
                                }
                                val done = processed.incrementAndGet()
                                if (done % PROGRESS_STRIDE == 0 || done >= total) {
                                    _progress.value = FaceIndexingProgress(
                                        if (active()) FaceIndexingState.Running else FaceIndexingState.Paused,
                                        scannedBefore + persistedThisPass.get() + scannedKeys.size,
                                        libraryTotal,
                                    )
                                }
                                if (BuildConfig.DEBUG && (done % DEBUG_PROGRESS_LOG_STRIDE == 0 || done >= total)) {
                                    val elapsedS = (SystemClock.elapsedRealtime() - passStart) / 1000.0
                                    val rate = if (elapsedS > 0.0) done / elapsedS else 0.0
                                    Log.i(
                                        TAG,
                                        "indexed ${scannedBefore + persistedThisPass.get() + scannedKeys.size}/$libraryTotal photos, " +
                                            "faces found ${facesThisPass.get()}, " +
                                            "rate ${String.format(Locale.US, "%.1f", rate)}/s",
                                    )
                                }
                                // Persist scan markers mid-pass in chunks, so a long single-pass drain that
                                // is killed keeps the photos it already scanned instead of restarting from
                                // zero. One flush at a time; the other workers keep scanning meanwhile.
                                if (scannedKeys.size >= SCAN_FLUSH_CHUNK && scanFlushing.compareAndSet(false, true)) {
                                    try { flushScannedChunk(userId, scannedKeys, persistedThisPass) }
                                    finally { scanFlushing.set(false) }
                                }
                            }
                        }
                    }
                }
                if (BuildConfig.DEBUG && acceptedScores != null) {
                    logDetectionStats(acceptedScores, detector.droppedTooSmall.get())
                    logBlurStats(acceptedBlur.orEmpty(), droppedTooBlurry.get())
                }
            } finally {
                runCatching { detector.close() }
                runCatching { embedder.close() }
            }

            // Persist this pass's scan markers in one batch, so the next run skips these photos whether
            // or not they held a face. A stopped or paused pass still records what it did scan; the
            // sign-out wipe clears the table separately.
            if (scannedKeys.isNotEmpty()) {
                runCatching { faceScanDao.upsert(scannedKeys.map { FaceScanEntity(userId?.id ?: PhotoLocationEntity.LOCAL_USER, it) }) }
                    .onFailure { Log.w(TAG, "scan marker flush failed: ${it.message}") }
            }

            // Cluster DURING the walk, not only when the whole library drains. Two triggers, both
            // bounded and cancellable (cluster() runs under a timeout): (1) the walk is about to pause
            // rather than continue (off Wi-Fi, health-parked, nothing more to source this pass), so
            // group what is indexed instead of leaving it until a drain that may never come, the
            // reported "scan stalls, no groups" case; (2) a long continuous drain crossed the refresh
            // interval, so people appear and grow as the scan runs. Rate-limited by the interval so a
            // fast drain does not re-cluster every pass.
            val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
            val willContinue = restartRequested || (active() && sourcesLoaded.get() > 0)
            val nowMs = SystemClock.elapsedRealtime()
            if (active() && facesThisPass.get() > 0 &&
                (!willContinue || nowMs - lastIncrementalClusterMs >= INCREMENTAL_CLUSTER_MIN_INTERVAL_MS) &&
                runCatching { faceDao.unclusteredCount(account) }.getOrDefault(0) > 0
            ) {
                lastIncrementalClusterMs = nowMs
                // Place just this pass's new faces against the cached people first; only fall back to the
                // whole-library rebuild when the cache is not usable (first run, stale, or a big backlog).
                if (!tryAssignNewFaces(userId)) cluster(userId)
            }
            val indexedNow = scannedBefore + persistedThisPass.get() + scannedKeys.size
            _progress.value = when {
                !aiEnabled -> FaceIndexingProgress(FaceIndexingState.Idle, indexedNow, libraryTotal)
                // Not Done here: a non-drained pass hands off to the next through the tail re-kick, and
                // the drained branch above is the only place that reports Done. Reporting Done at every
                // pass end is what made the bar look like it kept finishing and starting over.
                paused || stopRequested -> FaceIndexingProgress(FaceIndexingState.Paused, indexedNow, libraryTotal)
                else -> FaceIndexingProgress(FaceIndexingState.Running, indexedNow, libraryTotal)
            }
        } finally {
            running.set(false)
            // Auto-continue: a full-res walk only downloads a bounded batch per pass, so keep going
            // until the library is drained. A pending resume always re-kicks; otherwise re-kick only
            // while the walk is still permitted and made progress, which stops cleanly at Done, off
            // Wi-Fi, or on pause.
            val resume = restartRequested
            restartRequested = false
            if (resume || (active() && sourcesLoaded.get() > 0)) {
                scope.launch { runCatching { indexAll(userId) } }
            }
        }
    }

    /**
     * Persist a chunk of accrued scan markers mid-pass and drop them from [scannedKeys], so a process or
     * foreground-service kill during a long single-pass drain keeps the photos already scanned rather than
     * restarting from zero. Snapshot then remove, so each marker is written once (idempotent upsert aside);
     * concurrent adds during the write land in the set and the next flush (or the pass-end flush) carries
     * them. On a write failure the keys are kept so the pass-end flush retries them. [persistedThisPass]
     * tracks the removed count so progress stays the running scanned total after the set shrinks.
     */
    private suspend fun flushScannedChunk(
        userId: UserId?,
        scannedKeys: MutableSet<String>,
        persistedThisPass: AtomicInteger,
    ) {
        val snapshot = scannedKeys.toList()
        if (snapshot.isEmpty()) return
        val ok = runCatching {
            faceScanDao.upsert(snapshot.map { FaceScanEntity(userId?.id ?: PhotoLocationEntity.LOCAL_USER, it) })
        }.onFailure { Log.w(TAG, "incremental scan flush failed: ${it.message}") }.isSuccess
        if (ok) {
            scannedKeys.removeAll(snapshot.toHashSet())
            persistedThisPass.addAndGet(snapshot.size)
        }
    }

    /**
     * Persist the pause switch. Pausing lets the running walk stop itself on its next per-photo check;
     * unpausing kicks a fresh walk for the last account. Never auto-restarts while paused: [indexAll]
     * returns early when the switch is on, so only an explicit unpause here re-arms it.
     */
    suspend fun setPaused(paused: Boolean) {
        runCatching { context.settingsDataStore.edit { it[SettingsKeys.FACE_INDEXING_PAUSED] = paused } }
        this.paused = paused
        if (paused) {
            if (_progress.value.state == FaceIndexingState.Running) {
                _progress.value = _progress.value.copy(state = FaceIndexingState.Paused)
            }
        } else {
            stopRequested = false
            lastUserId?.let { uid -> scope.launch { runCatching { indexAll(uid) } } }
        }
    }

    /**
     * Start or resume a walk for [userId] right now, clearing the pause switch first. The settings
     * card's start / resume control calls this so indexing can be kicked off even from Idle or right
     * after a fresh launch, when no last account is on hand for the unpause path above. Runs on the
     * scheduler's own scope so it outlives the screen, and the one-walk guard collapses a redundant
     * kick into the walk already going.
     */
    fun requestIndex(userId: UserId?) {
        scope.launch {
            runCatching { context.settingsDataStore.edit { it[SettingsKeys.FACE_INDEXING_PAUSED] = false } }
            paused = false
            stopRequested = false
            // If a walk is still winding down (an in-flight full-res download), let its tail re-kick a
            // fresh one rather than no-op here, so a resume never leaves nothing running.
            restartRequested = true
            runCatching { indexAll(userId) }
        }
    }

    /** Re-run clustering now (e.g. right after the user named or merged a person) so the name attracts
     *  its other faces without waiting for the next scan pass. Forced, so it runs even while paused. */
    fun requestRecluster(userId: UserId?) { scope.launch { runCatching { cluster(userId, force = true) } } }

    /**
     * Scan just the photo now on screen, so the viewer can offer a face to tag before the background
     * walk has reached it. A no-op when AI is off or the photo is already scanned, so a faceless photo
     * is only ever looked at once. Net-free: the photo would be scanned in the background regardless, so
     * this only brings it forward. Runs regardless of the pause switch, since it answers a direct action
     * on the photo the user is looking at, and serialises with any running walk through [mlLock] inside
     * the scan. Returns true when it scanned the photo (whether or not a face was found).
     */
    suspend fun indexPhotoOnDemand(item: GalleryItem, userId: UserId?, force: Boolean = false): Boolean {
        val prefs = context.settingsDataStore.data.first()
        val enabled = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
        if (!enabled) return false
        // The walk marks every photo scanned, so a plain call would skip them all. A forced call (the
        // user asked, from the viewer) re-scans regardless, at a lower confidence so it can catch a face
        // the precision-first walk left behind. The few extra weak detections are on this one photo and
        // the user reviews them.
        if (!force && faceScanDao.isScanned(userId?.id ?: PhotoLocationEntity.LOCAL_USER, item.stableId)) return false
        val detFile = (faceModelManager.prepare() as? FaceModelPreparation.Ready)?.file ?: return false
        val embFile = (faceEmbeddingModelManager.prepare() as? FaceModelPreparation.Ready)?.file ?: return false
        val detector = if (force) FaceDetector(detFile, scoreThreshold = 0.5f, inputSize = 640) else FaceDetector(detFile)
        val embedder = FaceEmbedder(embFile)
        val scanned = ConcurrentHashMap.newKeySet<String>()
        try {
            scanOnePhoto(item, userId, detector, embedder, AtomicInteger(2), AtomicInteger(0), scanned, force = force)
        } finally {
            runCatching { detector.close() }
            runCatching { embedder.close() }
        }
        if (scanned.isEmpty()) return false
        runCatching { faceScanDao.upsert(scanned.map { FaceScanEntity(userId?.id ?: PhotoLocationEntity.LOCAL_USER, it) }) }
        // Give the fresh faces person ids so the viewer can show and tag them. The incremental path places
        // them against the cached people first (assign-to-existing, no rebuild); it runs regardless of the
        // pause switch since it is plain DB work, matching the forced full rebuild it falls back to. That
        // fallback stays forced, so it clusters even while background indexing is paused (the common state
        // after the first scan); a plain call would no-op there and leave the freshly scanned faces with a
        // null person id, so the viewer's grouped-faces read stays empty and the photo reads as "no faces".
        if (!tryAssignNewFaces(userId)) cluster(userId, force = true)
        return true
    }

    /**
     * "Find more photos of this person": re-check the faceless photos at the sensitive high-resolution
     * detector setting, and stream back the faces whose mean-direction matches [centroid] over
     * [matchThreshold] so the screen can offer them for this person.
     *
     * The sweep is incremental and self-populating. Its candidate set is [FaceScanDao.hiResPendingKeys],
     * the faceless photos not yet hi-res swept, so each faceless photo is looked at once ever and a
     * repeat call gets progressively cheaper instead of re-scanning the whole faceless set from zero.
     * Every face the hi-res pass detects is persisted as a normal [FaceEntity] (all of them, not only
     * the current person's matches), exactly as the index walk writes them, so the faces enter
     * clustering and surface for whichever person they belong to on their own, and the photo leaves the
     * faceless set. A photo that still holds no face is marked hi-res swept with no face row, so it is
     * never re-checked. Once the sweep drains, a clustering pass groups the newly persisted faces.
     *
     * Progress is emitted per photo so the screen can show how far the sweep has got. Runs off the main
     * thread, and serialises its native inference through [mlLock] like the walk.
     */
    fun sweepFacelessForPerson(
        userId: UserId?,
        centroid: FloatArray,
        matchThreshold: Float,
    ): Flow<FaceSweepEvent> = channelFlow {
        val prefs = context.settingsDataStore.data.first()
        val enabled = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
        val detFile = if (enabled) (faceModelManager.prepare() as? FaceModelPreparation.Ready)?.file else null
        val embFile = if (enabled) (faceEmbeddingModelManager.prepare() as? FaceModelPreparation.Ready)?.file else null
        if (detFile == null || embFile == null || centroid.size != FACE_EMBEDDING_DIM) {
            send(FaceSweepEvent.Progress(0, 0)); return@channelFlow
        }
        val items = runCatching {
            val pending = faceScanDao.hiResPendingKeys(userId?.id ?: PhotoLocationEntity.LOCAL_USER).toHashSet()
            val provider = galleryItemsProvider.get()
            (if (userId == null) provider.invokeLocalOnly() else provider.invoke(userId))
                .first().filter { it.stableId in pending }
        }.getOrDefault(emptyList())
        send(FaceSweepEvent.Progress(0, items.size))
        if (items.isEmpty()) return@channelFlow
        // The detector + embedder are shared; the mlLock below serialises their (native) inference, so
        // the workers overlap on decode and take turns on inference, matching how the walk parallelises.
        val detector = FaceDetector(detFile, scoreThreshold = 0.6f, inputSize = 640)
        val embedder = FaceEmbedder(embFile)
        val cursor = AtomicInteger(0)
        val done = AtomicInteger(0)
        // Faces the sweep persisted this run, so it clusters once at the end only when it actually
        // added something (a run over truly faceless photos writes nothing and skips the recluster).
        val newFaces = AtomicInteger(0)
        try {
            coroutineScope {
                repeat(WORKER_COUNT) {
                    launch {
                        while (isActive) {
                            val i = cursor.getAndIncrement()
                            if (i >= items.size) break
                            val item = items[i]
                            val photoKey = item.stableId
                            val source = runCatching { loadSource(item, userId, AtomicInteger(2), force = true) }.getOrNull()
                            if (source != null) {
                                // Under the lock: detect + embed, build the rows to persist and the
                                // matches to stream. The DB writes happen after the lock is released, so
                                // the mlLock only ever holds native inference, exactly as the walk does.
                                val (rows, matches) = try {
                                    mlLock.withLock {
                                        val sw = source.width.toFloat()
                                        val sh = source.height.toFloat()
                                        val faceRows = ArrayList<FaceEntity>()
                                        val out = ArrayList<FaceSweepEvent.Match>()
                                        detector.detect(source).forEachIndexed { idx, face ->
                                            val aligned = FaceAlignment.alignFace(source, face.landmarks)
                                            val blur = runCatching { alignedBlur(aligned) }.getOrNull()
                                            // Same hard blur gate as the index path: an extremely blurred
                                            // crop is neither stored nor offered as a match.
                                            if (blur != null && blur < MIN_SHARPNESS) {
                                                if (!aligned.isRecycled) aligned.recycle()
                                                return@forEachIndexed
                                            }
                                            val emb = embedder.embed(aligned)
                                            if (!aligned.isRecycled) aligned.recycle()
                                            val box = FaceBoxNorm(
                                                left = if (sw > 0f) (face.box.left / sw).coerceIn(0f, 1f) else 0f,
                                                top = if (sh > 0f) (face.box.top / sh).coerceIn(0f, 1f) else 0f,
                                                right = if (sw > 0f) (face.box.right / sw).coerceIn(0f, 1f) else 1f,
                                                bottom = if (sh > 0f) (face.box.bottom / sh).coerceIn(0f, 1f) else 1f,
                                            )
                                            val landmarks = encodeLandmarks(face.landmarks)
                                            val embedding = packEmbedding(emb)
                                            // A normal index row, built exactly as scanOnePhoto does
                                            // (same id derivation, box, landmarks, score, blur, null
                                            // personId/manualName), so the persisted face is
                                            // indistinguishable from an index-time one and clusters
                                            // the same. Adding it later just upserts personId onto it.
                                            faceRows.add(
                                                FaceEntity(
                                                    id = "$photoKey#$idx",
                                                    userId = userId?.id ?: PhotoLocationEntity.LOCAL_USER,
                                                    photoKey = photoKey,
                                                    left = box.left,
                                                    top = box.top,
                                                    right = box.right,
                                                    bottom = box.bottom,
                                                    landmarks = landmarks,
                                                    embedding = embedding,
                                                    personId = null,
                                                    score = face.score,
                                                    blur = blur,
                                                ),
                                            )
                                            if (emb.size == FACE_EMBEDDING_DIM &&
                                                cosineSimilarity(emb, centroid) >= matchThreshold
                                            ) {
                                                out.add(
                                                    FaceSweepEvent.Match(
                                                        photoKey = photoKey,
                                                        index = idx,
                                                        box = box,
                                                        landmarks = landmarks,
                                                        embedding = embedding,
                                                        score = face.score,
                                                        blur = blur,
                                                    ),
                                                )
                                            }
                                        }
                                        faceRows to out
                                    }
                                } finally {
                                    if (!source.isRecycled) source.recycle()
                                }
                                // Persist every detected face as a normal index row and drop this photo
                                // from the faceless set, so it surfaces for the right person on its own
                                // and a later sweep never looks at it again. Written after the lock so
                                // the DB I/O never holds up another worker's inference.
                                if (rows.isNotEmpty()) {
                                    runCatching { faceDao.upsert(rows) }
                                        .onFailure { Log.w(TAG, "sweep face upsert $photoKey failed: ${it.message}") }
                                    newFaces.addAndGet(rows.size)
                                }
                                // Mark hi-res swept whether or not a face was found, so a truly faceless
                                // photo is recorded as looked-at and never re-swept.
                                runCatching { faceScanDao.markHiResScanned(userId?.id ?: PhotoLocationEntity.LOCAL_USER, listOf(photoKey)) }
                                    .onFailure { Log.w(TAG, "sweep mark $photoKey failed: ${it.message}") }
                                matches.forEach { send(it) }
                            }
                            send(FaceSweepEvent.Progress(done.incrementAndGet(), items.size))
                        }
                    }
                }
            }
        } finally {
            runCatching { detector.close() }
            runCatching { embedder.close() }
        }
        // The sweep added faces to photos the walk had left faceless, so group them once the sweep
        // drains: a newly persisted face then surfaces under the right person on its own, the same way
        // the background walk clusters from its drained branch. Forced, since "Find more photos" is a
        // user action that must group its results even while background indexing is paused. Skipped
        // when nothing was persisted.
        if (newFaces.get() > 0) cluster(userId, force = true)
    }.flowOn(Dispatchers.Default)

    /**
     * Stop any running walk and drop the session's indexing state. Called on sign-out just before the
     * face rows are wiped, so the walk is already standing down when the wipe lands and never writes a
     * row for the account that is leaving.
     */
    suspend fun reset() {
        stopRequested = true
        lastUserId = null
        _progress.value = FaceIndexingProgress(FaceIndexingState.Idle, 0, 0)
        FaceDiagnostics.clear()
        runCatching { context.settingsDataStore.edit { it.remove(SettingsKeys.FACE_INDEXING_PAUSED) } }
    }

    /**
     * Reconcile the stored embeddings' model version once. When the persisted marker differs from
     * [FACE_MODEL_VERSION] (or is absent, an install from before the marker existed), every face, scan
     * marker and "not this person" mark is dropped so the walk rebuilds the whole library with the
     * current recognition model instead of comparing two incompatible embedding widths.
     *
     * The wipe is resumable and non-destructive. The names and exclusions the wipe would orphan are
     * captured into a durable snapshot BEFORE the tables are cleared, and that snapshot is left in place
     * until the re-detected library has actually carried them back. The marker is written LAST, so a
     * crash between the wipe and the marker re-enters here with an empty face table and a live snapshot,
     * which [reattachMigrationStep] reads as a migration to resume rather than a cue to re-capture an
     * empty snapshot over the pending one (which would lose every name). A no-op once the marker already
     * matches, so past the one-time migration a walk pays a single prefs read.
     */
    private suspend fun migrateFaceModelVersionIfNeeded(userId: UserId?) {
        val stored = context.settingsDataStore.data.first()[SettingsKeys.FACE_MODEL_VERSION_KEY]
        if (stored == FACE_MODEL_VERSION) {
            if (BuildConfig.DEBUG) Log.i(TAG, "face model version $FACE_MODEL_VERSION current, no wipe")
            return
        }
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        val faceTableEmpty = faceDao.facePhotoKeysForUser(account).isEmpty()
        val snapshotPresent = reattachFile.isFile
        when (reattachMigrationStep(faceTableEmpty, snapshotPresent)) {
            // Faces still present: snapshot the names and exclusions the wipe is about to orphan, so the
            // re-detected library can carry them again once it is rebuilt.
            ReattachMigrationStep.Capture ->
                runCatching { captureReattachSnapshot(account) }
                    .onFailure { Log.w(TAG, "reattach snapshot failed: ${it.message}") }
            // A snapshot still on disk holds curation an earlier migration has not reattached yet. Keep it
            // and let the drained walk resume the reattach; capturing over it now would lose those names.
            ReattachMigrationStep.Resume ->
                if (BuildConfig.DEBUG) Log.i(TAG, "resuming a pending face model migration, keeping the snapshot")
            ReattachMigrationStep.Skip ->
                if (BuildConfig.DEBUG) Log.i(TAG, "no faces and no snapshot, nothing to preserve")
        }
        // Clear the faces, the scan markers and this account's "not this person" marks. The marks' ids
        // are positional (photoKey#index), so a detector swap would leave them pointing at whatever new
        // face reused the index; they are re-created by geometry from the snapshot once the library
        // re-detects. Idempotent, so a resumed migration re-running the clears is a no-op.
        val clearedFaces = faceDao.clearAll()
        val clearedScans = faceScanDao.clearAll()
        runCatching { notPersonDao.clearForUser(account) }
            .onFailure { Log.w(TAG, "not-person clear failed: ${it.message}") }
        // Marker last: only now is the wipe durably complete. The snapshot is left on disk for the
        // drained reattach to consume, so a crash after this still finds it waiting.
        context.settingsDataStore.edit { it[SettingsKeys.FACE_MODEL_VERSION_KEY] = FACE_MODEL_VERSION }
        // A recognition-set swap leaves the previous set's model files behind, referenced by no current
        // code. Prune the superseded filenames so a re-detected library does not also carry the dead
        // models. Best-effort: a failure here never affects the migration completed above.
        runCatching { pruneObsoleteModelFiles() }
            .onFailure { Log.w(TAG, "obsolete model prune failed: ${it.message}") }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "face model version $stored -> $FACE_MODEL_VERSION, wiped $clearedFaces faces + $clearedScans scans")
        }
    }

    /**
     * Delete model files from a superseded recognition set. A detector or embedder swap leaves the
     * previous set's files in the model directory referenced by no current code, and a model-version
     * change is the one moment they are known to be obsolete. The names are the OpenCV YuNet + SFace set.
     */
    private fun pruneObsoleteModelFiles() {
        val dir = File(context.filesDir, FaceModelAssets.DIRECTORY)
        listOf("yunet.onnx", "sface.onnx").forEach { name ->
            File(dir, name).takeIf { it.isFile }?.delete()
        }
    }

    /** App-private file holding the names waiting to be put back on the re-detected library. */
    private val reattachFile: File get() = FaceReattachSnapshot.file(File(context.filesDir, FaceModelAssets.DIRECTORY))

    /**
     * Snapshot the model-independent curation a wipe would orphan (which face, in which photo, where),
     * so a model swap does not lose it. Captures the named faces, the "not this person" marks and the
     * removed ("not a person") faces, each with its box so it rebinds by geometry after a detector swap
     * shifts the positional ids. Every read here joins the live face table, so it must run before the
     * wipe. Nothing to preserve clears any stale snapshot; a capture from a live table is never an
     * interrupted migration, so clearing here cannot destroy a pending reattach.
     */
    private suspend fun captureReattachSnapshot(account: String) {
        val nameById = personDao.namedPeopleForUser(account)
            .mapNotNull { p -> p.displayName?.takeIf { it.isNotBlank() }?.let { p.id to it } }
            .toMap()
        val members = faceDao.allFacesByScoreDesc(account).mapNotNull { f ->
            val name = f.personId?.let { nameById[it] } ?: f.manualName?.takeIf { it.isNotBlank() }
            name ?: return@mapNotNull null
            ReattachLabel(f.id, f.photoKey, f.left, f.top, f.right, f.bottom, name)
        }
        val nots = notPersonDao.facesForUser(account).map {
            ReattachLabel(it.faceId, it.photoKey, it.boxLeft, it.boxTop, it.boxRight, it.boxBottom, it.personName)
        }
        val rejected = faceDao.rejectedFacesForUser(account).map {
            ReattachBox(it.photoKey, it.boxLeft, it.boxTop, it.boxRight, it.boxBottom)
        }
        if (members.isEmpty() && nots.isEmpty() && rejected.isEmpty()) {
            FaceReattachSnapshot.clear(reattachFile)
            return
        }
        FaceReattachSnapshot.write(reattachFile, FaceReattachData(members, emptyList(), nots, rejected))
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "captured ${members.size} labels, ${nots.size} nots, ${rejected.size} rejected before wipe")
        }
    }

    /**
     * Put a pending snapshot's names and exclusions back on the freshly detected library, then let it
     * go. Members bind to the re-detected faces by [matchReattachLabels] (same id first, then box
     * overlap) and get the name as a manual label the clusterer then groups under; manual attachments are
     * restored under their names, the "not this person" marks rebind to the new faces the same way, and
     * the removed ("not a person") faces are re-rejected on whichever new face lands in their box. Runs
     * once the library is fully re-detected and just before clustering, so the names are in place and the
     * exclusions are honoured when the groups form. The snapshot is cleared only after everything has been
     * applied, so a crash part way through resumes from the still-pending snapshot on the next drain.
     */
    private suspend fun reattachPendingLabels(userId: UserId?): Boolean {
        val data = FaceReattachSnapshot.read(reattachFile) ?: return false
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        val localFaces = faceDao.allFacesByScoreDesc(account).map {
            ReattachFace(it.id, it.photoKey, it.left, it.top, it.right, it.bottom)
        }
        val labelled = matchReattachLabels(data.members, localFaces)
        labelled.entries.groupBy({ it.value }, { it.key }).forEach { (name, ids) ->
            faceDao.labelFacesByIds(ids, name)
        }
        if (data.manual.isNotEmpty()) {
            personManualPhotoDao.add(data.manual.map { PersonManualPhotoEntity(account, it.name, it.photoKey) })
        }
        if (data.nots.isNotEmpty()) {
            val marks = matchReattachLabels(data.nots, localFaces)
            if (marks.isNotEmpty()) {
                notPersonDao.add(marks.map { (faceId, name) -> NotPersonEntity(account, name, faceId) })
            }
        }
        // Re-apply the removed faces by box overlap, at the same threshold the named-face reattach uses:
        // the detector swap changed the positional ids, so the removal follows the geometry onto the new
        // face and does not silently lapse.
        if (data.rejected.isNotEmpty()) {
            val toReject = matchRejectedByGeometry(data.rejected, localFaces, REATTACH_MIN_IOU)
            if (toReject.isNotEmpty()) faceDao.rejectByIds(toReject)
        }
        FaceReattachSnapshot.clear(reattachFile)
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "reattached ${labelled.size} labels, ${data.manual.size} manual, " +
                    "${data.nots.size} nots, ${data.rejected.size} rejected",
            )
        }
        return true
    }

    /** Detect + embed every face in one photo and write a row per face. On a source that loaded and
     *  detected without throwing, add the photo to [scannedKeys] so the pass's end persists it as
     *  scanned, with faces or not, and a later run skips it. Bounded source in, recycled on the way out. */
    private suspend fun indexOne(
        item: GalleryItem,
        userId: UserId?,
        detector: FaceDetector,
        embedder: FaceEmbedder,
        decryptBudget: AtomicInteger,
        sourcesLoaded: AtomicInteger,
        scannedKeys: MutableSet<String>,
        facesFound: AtomicInteger,
        acceptedScores: MutableList<Float>?,
        droppedTooBlurry: AtomicInteger,
        acceptedBlur: MutableList<Float>?,
    ) {
        if (!active()) return
        // Background face indexing yields the process-global crypto gate to interactive decrypts; the
        // on-demand and faceless-sweep paths run scanOnePhoto/loadSource directly and stay foreground.
        withContext(DecryptPriorityContext(DecryptPriority.BACKGROUND)) {
            scanOnePhoto(item, userId, detector, embedder, decryptBudget, sourcesLoaded, scannedKeys, facesFound = facesFound, acceptedScores = acceptedScores, droppedTooBlurry = droppedTooBlurry, acceptedBlur = acceptedBlur)
        }
    }

    /**
     * The per-photo scan itself, without the walk's active() gate, so an on-demand call can run it for
     * the photo on screen even while the background walk is paused or idle.
     */
    private suspend fun scanOnePhoto(
        item: GalleryItem,
        userId: UserId?,
        detector: FaceDetector,
        embedder: FaceEmbedder,
        decryptBudget: AtomicInteger,
        sourcesLoaded: AtomicInteger,
        scannedKeys: MutableSet<String>,
        force: Boolean = false,
        facesFound: AtomicInteger? = null,
        acceptedScores: MutableList<Float>? = null,
        droppedTooBlurry: AtomicInteger? = null,
        acceptedBlur: MutableList<Float>? = null,
    ) {
        val photoKey = item.stableId
        val source = loadSource(item, userId, decryptBudget, force) ?: run {
            // A device-only item that still yields no source is neither a decodable image nor a
            // decodable video (a corrupt or unsupported local file), and has no cloud original to try on
            // a later pass. Record it scanned rather than leave it pending forever: once only such items
            // remained, a pass would load nothing, the walk would not re-kick, and the count would stand
            // still at "Running" for good. A cloud item returning null is a transient defer (off Wi-Fi or
            // out of budget) and stays pending on purpose.
            if (item is GalleryItem.LocalOnly) {
                scannedKeys.add(photoKey)
                FaceDiagnostics.recordUnloadableLocal()
            }
            return
        }
        sourcesLoaded.incrementAndGet()
        // The source loaded and is about to be handed to detection, so mark the photo scanned now, before
        // the ML block. If detect or embed then throws (an OOM on a crop, a bad frame), the photo is still
        // recorded, so a poison photo is not re-decoded and retried on every later pass; a face-free photo
        // is likewise marked once. A source that never loaded returned above and stays pending for a later pass.
        scannedKeys.add(photoKey)
        try {
            mlLock.withLock {
                val faces = detector.detect(source)
                for ((index, face) in faces.withIndex()) {
                    val aligned = FaceAlignment.alignFace(source, face.landmarks)
                    val blur = runCatching { alignedBlur(aligned) }.getOrNull()
                    // Hard quality gate: drop an extremely blurred crop before it costs an embed or a
                    // stored row, mirroring the detector's min-size gate. The floor stays conservative so
                    // only unusable faces are lost while the accepted-blur log calibrates where to raise it.
                    if (blur != null && blur < MIN_SHARPNESS) {
                        if (!aligned.isRecycled) aligned.recycle()
                        if (BuildConfig.DEBUG) droppedTooBlurry?.incrementAndGet()
                        continue
                    }
                    val embedding = try {
                        embedder.embed(aligned)
                    } finally {
                        if (!aligned.isRecycled) aligned.recycle()
                    }
                    // Store the box as a 0..1 fraction of the (EXIF-oriented) source, so a cover crops
                    // correctly with no need for the photo's pixel dimensions later. A cloud-only cover
                    // has no stored dimensions, so a pixel box could not be normalised and the whole
                    // group photo showed instead of the one face.
                    val sw = source.width.toFloat()
                    val sh = source.height.toFloat()
                    val entity = FaceEntity(
                        id = "$photoKey#$index",
                        userId = userId?.id ?: PhotoLocationEntity.LOCAL_USER,
                        photoKey = photoKey,
                        left = if (sw > 0f) (face.box.left / sw).coerceIn(0f, 1f) else 0f,
                        top = if (sh > 0f) (face.box.top / sh).coerceIn(0f, 1f) else 0f,
                        right = if (sw > 0f) (face.box.right / sw).coerceIn(0f, 1f) else 1f,
                        bottom = if (sh > 0f) (face.box.bottom / sh).coerceIn(0f, 1f) else 1f,
                        landmarks = encodeLandmarks(face.landmarks),
                        embedding = packEmbedding(embedding),
                        personId = null,
                        score = face.score,
                        blur = blur,
                    )
                    runCatching { faceDao.upsert(entity) }
                        .onFailure { Log.w(TAG, "face upsert $photoKey#$index failed: ${it.message}") }
                    if (BuildConfig.DEBUG) {
                        facesFound?.incrementAndGet()
                        acceptedScores?.add(face.score)
                        blur?.let { acceptedBlur?.add(it) }
                    }
                }
            }
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    /**
     * A bounded source bitmap for [item]. A device file (LocalOnly, or the local twin of a Synced pair)
     * is decoded straight off the local original with no crypto. A cloud photo is detected from its
     * full-resolution image on Wi-Fi (downloaded, decoded bounded, then deleted right away); off Wi-Fi
     * or once the per-pass budget is spent it returns null so the photo is deferred to a later pass
     * rather than indexed from a low-detail thumbnail. A cloud video falls back to its still-frame
     * thumbnail, the only bitmap a video container yields.
     */
    private suspend fun loadSource(
        item: GalleryItem,
        userId: UserId?,
        decryptBudget: AtomicInteger,
        force: Boolean = false,
    ): Bitmap? = when (item) {
        // A device video decodes to no bitmap through BitmapFactory, so fall through to a still frame;
        // an image takes the first branch and never opens the retriever.
        is GalleryItem.LocalOnly -> decodeLocalBounded(item.local.uri)
            ?: decodeLocalVideoFrame(item.local.uri)
        // The cloud fallback stays account-bound: a guest has only device items, so it is never reached.
        is GalleryItem.Synced -> decodeLocalBounded(item.local.uri)
            ?: decodeLocalVideoFrame(item.local.uri)
            ?: userId?.let { decodeCloud(item.cloud, it, decryptBudget, force) }
        is GalleryItem.CloudOnly -> userId?.let { decodeCloud(item.cloud, it, decryptBudget, force) }
    }

    /**
     * A bounded still frame from a device video, so a video that lives only on the phone is indexed from
     * a face in it exactly as a cloud video is from its thumbnail. Tried only after [decodeLocalBounded]
     * returns null (an image never reaches here). A near-start sync frame is scaled down on the way out
     * of the decoder on API 27+, or decoded whole and left for the detector's own bound on API 26.
     * Everything is wrapped: an unreadable or non-video file returns null, which the caller treats as a
     * photo that cannot be sourced.
     */
    private suspend fun decodeLocalVideoFrame(uri: String): Bitmap? {
        // MediaMetadataRetriever can hang indefinitely on a malformed file; the blocking helper's
        // runCatching catches a throw but not a hang. Run the decode detached and bound the wait, so a
        // wedged decode frees this worker rather than pinning it (three hung videos would otherwise stall
        // all WORKER_COUNT workers) and the walk moves on. The abandoned decode is cancelled best-effort;
        // its retriever is released in the helper's finally if the call ever returns.
        val frame = scope.async(Dispatchers.IO) { decodeLocalVideoFrameBlocking(uri) }
        return withTimeoutOrNull(VIDEO_FRAME_TIMEOUT_MS) { frame.await() } ?: run { frame.cancel(); null }
    }

    private fun decodeLocalVideoFrameBlocking(uri: String): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, u)
            val vw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && vw > 0 && vh > 0) {
                val sample = sampleSizeFor(vw, vh)
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (vw / sample).coerceAtLeast(1),
                    (vh / sample).coerceAtLeast(1),
                )
            } else {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    /** Route a cloud item: an image detects from its full-resolution bytes, a video from its
     *  still-frame thumbnail (BitmapFactory cannot decode a video container, and downloading one to
     *  discard it would waste the bandwidth). */
    private suspend fun decodeCloud(
        photo: CloudPhoto,
        userId: UserId,
        decryptBudget: AtomicInteger,
        force: Boolean = false,
    ): Bitmap? =
        if (photo.mimeType.startsWith("video/")) decodeCloudBounded(photo.linkId, userId, decryptBudget)
        else decodeCloudFullRes(photo, userId, decryptBudget, force)

    /**
     * Detect a cloud image from its full-resolution bytes. On Wi-Fi and while the per-pass budget
     * allows, the same download+decrypt the viewer uses ([PhotoDownloadService.downloadFullResPhoto])
     * writes the file into the `fullres` cache; it is decoded bounded and then deleted right away, so
     * the library is never stored on disk. Off Wi-Fi, out of budget, or on a download failure it
     * returns null and the photo waits for a later pass: a low-detail thumbnail face is exactly what
     * the full-res path is here to avoid. The download itself is serialised through [downloadLock] so
     * only one is ever in flight, and each spends one unit of the shared per-pass budget.
     */
    private suspend fun decodeCloudFullRes(
        photo: CloudPhoto,
        userId: UserId,
        decryptBudget: AtomicInteger,
        force: Boolean = false,
    ): Bitmap? {
        // A forced pass (the user asked, from the viewer) downloads this one photo regardless of the
        // Wi-Fi-only rule and the walk's own stop/pause state; both exist to keep the BACKGROUND walk
        // from flooding data or crypto, which a single deliberate request is not.
        if (!force && !networkObserver.currentlyOnWifi()) return null
        if (!takeBudget(decryptBudget)) return null
        val file = downloadLock.withPermit {
            if (!force && !active()) return null
            runCatching { photoDownloadService.downloadFullResPhoto(userId, photo) }.getOrNull()
        } ?: return null
        return try {
            decodeFileBounded(file)
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun decodeLocalBounded(uri: String): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = context.contentResolver.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return@runCatching null
        val orientation = context.contentResolver.openInputStream(u)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        orientedBitmap(bmp, orientation)
    }.getOrNull()

    private fun decodeFileBounded(file: File): Bitmap? = runCatching {
        if (!file.exists() || file.length() <= 0L) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
        val orientation = runCatching {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        orientedBitmap(bmp, orientation)
    }.getOrNull()

    /**
     * Applies a photo's EXIF orientation to its decoded bitmap, so a face detected on a phone-portrait
     * shot stored as landscape-with-a-rotate-flag is fed to the detector UPRIGHT. Without this the
     * detector sees a sideways face and misses it (measured: this alone recovers a large share of the
     * rotated self-portraits a phone produces), and the stored box would not line up with the viewer,
     * which does honour EXIF. Returns the input untouched for the no-rotation case.
     */
    private fun orientedBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return runCatching {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        }.getOrDefault(bitmap)
    }

    /**
     * Decode a cloud video's still-frame thumbnail (an image goes through the full-res path instead),
     * preferring the HD (Type 2, ~1920px) thumbnail so a face crop carries ~2x the detail of the
     * gallery's Type 1 (~512px) thumbnail. A warm HD file is a plain decode; a cold one is decrypted
     * through the gallery's bounded HD decrypt (same crypto semaphore and libgojni lock), rate-limited
     * per pass so the walk can never flood the crypto service. When the budget is spent the item is left
     * for a later pass (a warm Type 1 still decodes for free), and a revision with no Type 2 thumbnail
     * falls back to the Type 1 path so it still indexes.
     */
    private suspend fun decodeCloudBounded(
        linkId: String,
        userId: UserId,
        decryptBudget: AtomicInteger,
    ): Bitmap? {
        // Warm HD wins: best detail, no crypto, no budget.
        val hdCached = cloudHdThumbFile(linkId)
        if (hdCached.exists() && hdCached.length() > 0L) return decodeFileBounded(hdCached)

        // Cold path is budgeted. Once the per-pass budget is spent, still index off a warm Type 1
        // thumbnail the gallery already made (free), else leave the photo for a later pass.
        if (!takeBudget(decryptBudget)) {
            val warmT1 = cloudThumbFile(linkId)
            return if (warmT1.exists() && warmT1.length() > 0L) decodeFileBounded(warmT1) else null
        }

        val entity = runCatching { photoListingDao.getByLinkId(linkId) }.getOrNull() ?: return null
        val contentKeyPacket = entity.contentKeyPacket ?: return null
        val encNodeKey = entity.encNodeKey ?: return null
        val encNodePass = entity.encNodePassphrase ?: return null
        val parentLinkId = entity.parentLinkId ?: return null

        // Spend the budget unit on the HD decrypt (fresh Type 2 url + the shared node/session decrypt).
        val hdProduced = runCatching {
            thumbnailScheduler.decryptHdThumbnailToFileBounded(
                userId = userId,
                linkId = linkId,
                volumeId = entity.volumeId,
                contentKeyPacketBase64 = contentKeyPacket,
                encNodeKey = encNodeKey,
                encNodePass = encNodePass,
                parentLinkId = parentLinkId,
            )
        }.getOrNull()
        if (!hdProduced.isNullOrBlank()) return decodeFileBounded(cloudHdThumbFile(linkId))

        // No Type 2 thumbnail (older upload) or a transient HD failure: fall back to the Type 1 path so
        // the photo still indexes. A warm Type 1 file decodes straight off disk; a cold one rides the
        // gallery's bounded Type 1 decrypt.
        val warmT1 = cloudThumbFile(linkId)
        if (warmT1.exists() && warmT1.length() > 0L) return decodeFileBounded(warmT1)
        val serverUrl = entity.serverThumbnailUrl ?: return null
        val produced = runCatching {
            thumbnailScheduler.decryptThumbnailToFileBounded(
                userId = userId,
                linkId = linkId,
                volumeId = entity.volumeId,
                serverUrl = serverUrl,
                serverToken = entity.serverThumbnailToken,
                contentKeyPacketBase64 = contentKeyPacket,
                encNodeKey = encNodeKey,
                encNodePass = encNodePass,
                parentLinkId = parentLinkId,
            )
        }.getOrNull() ?: return null
        return if (produced.isNotBlank()) decodeFileBounded(cloudThumbFile(linkId)) else null
    }

    private fun cloudThumbFile(linkId: String): File =
        File(File(context.cacheDir, "thumbnails"), "thumb_$linkId.jpg")

    private fun cloudHdThumbFile(linkId: String): File =
        File(File(context.cacheDir, "thumbnails"), "thumb_hd_$linkId.jpg")

    /** Power-of-two downsample that lands the decoded long edge in [SOURCE_MAX_EDGE, 2*SOURCE_MAX_EDGE):
     *  enough detail for detection without ever holding a full-resolution bitmap. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        val longEdge = maxOf(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= SOURCE_MAX_EDGE) sample *= 2
        return sample
    }

    /** True while a walk may keep going: not stopping, AI on, not paused. */
    private fun active(): Boolean = !stopRequested && aiEnabled && !paused

    private fun setIdle() {
        _progress.value = FaceIndexingProgress(FaceIndexingState.Idle, 0, 0)
    }

    /** Runs [block], and in a debug build logs how long it took under [label], so the ONNX session-open
     *  cost (the model load) is visible. A release build folds the timing away. */
    private inline fun <T> debugTimed(label: String, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val start = SystemClock.elapsedRealtime()
        return block().also { Log.i(TAG, "$label session init ${SystemClock.elapsedRealtime() - start}ms") }
    }

    /**
     * Try the incremental fast path: place only the newly indexed faces against the cached people,
     * without a whole-library rebuild. Returns true when it handled the pass (so the caller skips the
     * full rebuild), false when a full rebuild is needed (first run, a stale cache, a large backlog) or
     * when it failed. A failure is swallowed and reported as "needs a full rebuild" so the caller falls
     * back cleanly, exactly as [cluster] swallows-and-continues; cancellation still propagates.
     */
    private suspend fun tryAssignNewFaces(userId: UserId?): Boolean =
        try {
            clusterFacesUseCase.assignNewFaces(userId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "incremental assign failed, falling back to full cluster: ${e.message}")
            false
        }

    /**
     * Group every stored face into people, once the library is fully scanned and is still permitted
     * to run. A clustering failure is logged and swallowed so it never fails the walk; cancellation
     * still propagates. Returns true only when a grouping actually completed (not skipped, timed out,
     * or failed), so the caller can record that a parameter generation was applied.
     */
    private suspend fun cluster(userId: UserId?, force: Boolean = false): Boolean {
        if (!force && !active()) return false
        return try {
            // Hard ceiling on a single clustering round. The clusterer is bounded and cancellable
            // (MAX_CLUSTERS + a polled cancellation check), so this only fires in a pathological case;
            // when it does, the in-transaction rebuild rolls back and the walk carries on rather than
            // hanging. withTimeoutOrNull absorbs its own timeout, so it never reads as a walk cancel.
            val finished = withTimeoutOrNull(CLUSTER_TIMEOUT_MS) {
                clusterFacesUseCase(userId)
                true
            }
            if (finished == null) {
                Log.w(TAG, "clustering timed out after ${CLUSTER_TIMEOUT_MS}ms, skipped this round")
                false
            } else {
                if (BuildConfig.DEBUG) {
                    // The debug threshold sweep reads per-account samples through the non-null diagnostic
                    // path; a guest still clusters above, it just skips this measurement-only log.
                    userId?.let { logClusterSweep(it) }
                }
                true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "clustering failed: ${e.message}")
            false
        }
    }

    /**
     * Debug threshold sweep. Re-groups the real stored embeddings at a spread of cluster distances and
     * logs each grouping's shape next to the one the real constant produces, so a good
     * [FACE_CLUSTER_THRESHOLD] can be read off a logcat sweep with no re-embed. Measurement only: the
     * stored grouping still comes from the real clustering call above; this writes nothing.
     */
    private suspend fun logClusterSweep(userId: UserId) {
        runCatching {
            val samples = clusterFacesUseCase.samplesForSweep(userId)
            if (samples.isEmpty()) {
                Log.i(TAG, "SWEEP no stored faces")
                return
            }
            logGrouping("REAL", FACE_CLUSTER_THRESHOLD, clusterFaces(samples))
            // Bracket the production threshold so the cluster-count trend on BOTH sides of it shows:
            // fewer clusters below it (fragments rejoin, but contamination risk rises), more above.
            for (t in floatArrayOf(0.34f, 0.40f, 0.46f, 0.50f, 0.53f, 0.56f, 0.60f)) {
                logGrouping("SWEEP", t, clusterFaces(samples, t, t))
            }
            // Each face's nearest OTHER face by cosine, bucketed, so the same-vs-different-person
            // separation and where FACE_CLUSTER_THRESHOLD sits in it are visible in logcat. A same-person
            // mass just below the threshold is the tell of over-strict fragmentation; overlap of the two
            // humps warns that lowering the threshold would merge different people. Capped so the O(n^2)
            // scan stays bounded on a large library.
            val pool = samples.take(2500)
            val n = pool.size
            if (n >= 2) {
                val buckets = IntArray(20)
                var atOrAbove = 0
                var justBelow = 0
                for (i in 0 until n) {
                    var best = -1f
                    val ei = pool[i].embedding
                    for (j in 0 until n) {
                        if (j != i) {
                            val s = cosineSimilarity(ei, pool[j].embedding)
                            if (s > best) best = s
                        }
                    }
                    if (best < 0f) continue
                    buckets[(best * 20f).toInt().coerceIn(0, 19)]++
                    if (best >= FACE_CLUSTER_THRESHOLD) atOrAbove++
                    else if (best >= FACE_CLUSTER_THRESHOLD - 0.10f) justBelow++
                }
                val hist = buckets.mapIndexed { b, c ->
                    if (c == 0) null else String.format(Locale.US, "%.2f:%d", b * 0.05, c)
                }.filterNotNull().joinToString(" ")
                Log.i(
                    TAG,
                    "SWEEP nearest (n=$n) t=${String.format(Locale.US, "%.2f", FACE_CLUSTER_THRESHOLD)} " +
                        "atOrAbove=$atOrAbove justBelow0.10=$justBelow hist[$hist]",
                )
            }
        }.onFailure { Log.w(TAG, "cluster sweep failed: ${it.message}") }
    }

    private fun logGrouping(label: String, threshold: Float, assignment: IntArray) {
        val sizes = assignment.toList().groupingBy { it }.eachCount().values.sortedDescending()
        Log.i(
            TAG,
            "$label t=${String.format(Locale.US, "%.2f", threshold)} -> clusters=${sizes.size}, " +
                "faces=${assignment.size}, top5 sizes=[${sizes.take(5).joinToString(",")}]",
        )
    }

    /**
     * Debug summary of one pass's detections: the accepted faces' score spread beside how many boxes the
     * min-size gate dropped, so the junk-vs-real score split shows in logcat for tuning the detector
     * threshold. Measurement only, writes nothing.
     */
    private fun logDetectionStats(scores: List<Float>, rejectedSmall: Int) {
        if (scores.isEmpty()) {
            Log.i(TAG, "DET accepted=0, rejected_small=$rejectedSmall")
            return
        }
        val sorted = scores.sorted()
        val median = sorted[sorted.size / 2]
        Log.i(
            TAG,
            "DET accepted=${sorted.size}, score min/median/max = " +
                "${String.format(Locale.US, "%.3f", sorted.first())}/" +
                "${String.format(Locale.US, "%.3f", median)}/" +
                "${String.format(Locale.US, "%.3f", sorted.last())}, " +
                "rejected_small=$rejectedSmall",
        )
    }

    /**
     * Debug summary of one pass's accepted-crop sharpness: the Laplacian-variance spread of the crops
     * that cleared the blur gate beside how many the gate dropped, so the reject floor can be read off a
     * logcat pass and raised from the real distribution. Measurement only, writes nothing.
     */
    private fun logBlurStats(blurs: List<Float>, rejectedBlur: Int) {
        if (blurs.isEmpty()) {
            Log.i(TAG, "BLUR accepted=0, rejected_blur=$rejectedBlur")
            return
        }
        val sorted = blurs.sorted()
        val median = sorted[sorted.size / 2]
        Log.i(
            TAG,
            "BLUR accepted min/median/max = " +
                "${String.format(Locale.US, "%.1f", sorted.first())}/" +
                "${String.format(Locale.US, "%.1f", median)}/" +
                "${String.format(Locale.US, "%.1f", sorted.last())}, " +
                "rejected_blur=$rejectedBlur",
        )
    }

    /** Claim one unit of the per-pass cold-decrypt budget, or false when it is spent. */
    private fun takeBudget(budget: AtomicInteger): Boolean {
        while (true) {
            val current = budget.get()
            if (current <= 0) return false
            if (budget.compareAndSet(current, current - 1)) return true
        }
    }

    /** Laplacian-variance sharpness of the aligned crop, read to grayscale once and handed to the
     *  pure scorer. A blurred face scores low and is later held to a stricter cluster distance. */
    private fun alignedBlur(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return 0f
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { i ->
            val p = argb[i]
            ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        return laplacianVariance(gray, w, h).toFloat()
    }

    private fun encodeLandmarks(points: List<PointF>): String =
        points.joinToString(";") { "${it.x},${it.y}" }

    private fun packEmbedding(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        return buffer.array()
    }

    private companion object {
        /** Worker pool size. Three keeps a few photos moving through the download / decode / ONNX
         *  pipeline at once while the ONNX sessions themselves stay serialised by [mlLock]. */
        const val WORKER_COUNT = 3

        /** How many cloud full-res downloads may run at once. A few in parallel keeps a cloud scan
         *  from crawling one file at a time; the ONNX inference each feeds stays serialised. */
        const val FULLRES_DOWNLOAD_CONCURRENCY = 3

        /** Long edge the source bitmap is bounded to. A cloud photo now decodes from its full-resolution
         *  image, so this is raised to hand the detector and embedder a larger face crop while still
         *  bounding a couple of resident bitmaps to a cheap size. Big enough for the detector (which
         *  reads a 640 square) and a crisp aligned crop. */
        const val SOURCE_MAX_EDGE = 1600

        /** Update the progress flow every this many photos, so a large pass does not churn the flow. */
        const val PROGRESS_STRIDE = 20

        /** Debug builds log a progress line every this many photos during a pass. */
        const val DEBUG_PROGRESS_LOG_STRIDE = 50

        /** Cold cloud thumbnails a single pass will decrypt inline, so the whole-library walk cannot
         *  flood the crypto service; the rest wait for a later pass once their thumbnail has warmed. */
        const val MAX_COLD_DECRYPTS_PER_PASS = 64

        /** While the device is not in a good state for heavy work, each worker re-checks at this
         *  cadence, so a resume or a pause / sign-out both take effect within a second. */
        const val HEALTH_PAUSE_POLL_MS = 1_000L

        /** Hard ceiling on one clustering round (see [cluster]). The clusterer is bounded and
         *  cancellable, so this only trips in a pathological case; when it does the walk carries on
         *  rather than hanging. */
        const val CLUSTER_TIMEOUT_MS = 180_000L

        /** Least time between two mid-walk clusterings during a long continuous drain, so groups
         *  refresh periodically without re-clustering on every pass. */
        const val INCREMENTAL_CLUSTER_MIN_INTERVAL_MS = 60_000L

        /** How many accrued scan markers trigger a mid-pass flush, so a long single-pass drain that is
         *  killed keeps the photos it already scanned instead of restarting from zero. */
        const val SCAN_FLUSH_CHUNK = 200

        /** Hard ceiling on one device-video still-frame decode, so a malformed file that hangs
         *  MediaMetadataRetriever frees the worker instead of pinning it (three would stall the pool). */
        const val VIDEO_FRAME_TIMEOUT_MS = 10_000L
    }
}
