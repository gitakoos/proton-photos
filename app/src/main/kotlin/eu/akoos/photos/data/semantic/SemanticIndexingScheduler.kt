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

package eu.akoos.photos.data.semantic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.crypto.DecryptPriority
import eu.akoos.photos.crypto.DecryptPriorityContext
import eu.akoos.photos.data.db.dao.ImageEmbeddingDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.ImageEmbeddingEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.drive.PhotoDownloadService
import eu.akoos.photos.data.repository.drive.ThumbnailDecryptScheduler
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.service.SemanticIndexingService
import eu.akoos.photos.util.DeviceHealthPolicy
import eu.akoos.photos.util.MlRail
import eu.akoos.photos.util.MlWalkGate
import eu.akoos.photos.util.NetworkObserver
import eu.akoos.photos.util.SemanticDiagnostics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.domain.entity.UserId
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext

private const val TAG = "SemanticIndexingSched"

/** Length of the CLIP image embedding each photo is reduced to. */
private const val EMBEDDING_DIM = 512

/**
 * Generation of the embedding model the stored vectors were produced by, kept on every
 * [ImageEmbeddingEntity]. When it is raised the indexer drops the older-generation rows once at the
 * start of a walk and re-embeds them with the current model, so two incompatible vector spaces are
 * never ranked against each other.
 */
const val SEMANTIC_MODEL_VERSION = 2

/** A pending set at least this large hosts the first pass in a foreground service, so a big initial
 *  index survives the app being swiped from Recents. Mirrors the face walk's threshold. */
const val SEMANTIC_INDEX_FOREGROUND_THRESHOLD = 50

/** Where the background semantic-indexing walk currently stands, so a settings card can label it. */
enum class SemanticIndexingState { Idle, WaitingModel, Running, Paused, Done }

/**
 * A snapshot of the indexer's progress. [indexed] out of [total] photos have been embedded in the
 * current pass; both are 0 in the states that do no walk ([SemanticIndexingState.Idle],
 * [SemanticIndexingState.WaitingModel]).
 */
data class SemanticIndexingProgress(
    val state: SemanticIndexingState,
    val indexed: Int,
    val total: Int,
)

/**
 * Whether a photo whose source failed to load should be recorded embedded (a zero-vector tombstone the
 * search ranks at similarity 0, so a re-run skips it) rather than left pending. A device item that
 * yields no bitmap is a corrupt or unsupported local file with no cloud original to retry, so it is
 * tombstoned. A cloud item is tombstoned only when its load wedged past a decode or decrypt watchdog (a
 * deterministic hang that would re-wedge next pass); a plain defer (off Wi-Fi, out of budget) or a
 * transient download timeout leaves it pending for a later retry. Pure so it is unit-tested without
 * Android; a copy of the face walk's rule so the semantic rail does not depend on the face file.
 */
internal fun shouldTombstoneOnNullSource(isLocalOnly: Boolean, loadTimedOut: Boolean): Boolean =
    isLocalOnly || loadTimedOut

/**
 * Walks the whole library once and stores one CLIP image embedding per photo, in the background, only
 * while the AI features and semantic search are both on.
 *
 * The walk mirrors the face-indexing scheduler's bounded shape: a small fixed worker pool drains the
 * not-yet-embedded remainder off the main thread, one photo per turn, so a large library never floods
 * memory or the crypto service. It is idempotent and resumable: the skip set is
 * [ImageEmbeddingDao.scannedKeysForUser], the photo keys already embedded, so a re-run only touches
 * photos it has never looked at. A read error on one photo skips that photo and never aborts the walk.
 *
 * Photo identity is [GalleryItem.stableId] (a cloud linkId or a device content URI), stored on each
 * [ImageEmbeddingEntity.photoKey], so a search hit joins straight back to the timeline. The row's
 * presence is the scan marker: a successful embed writes the real vector, a decode-unloadable photo
 * writes a zero-vector tombstone so it is not re-decoded every pass, and a photo that only failed
 * transiently (off Wi-Fi, out of budget, a slow download, or an inference wedge) writes nothing and
 * stays pending for a later trigger.
 *
 * Memory is the hard constraint. Only bounded bitmaps are decoded (long edge ~[SOURCE_MAX_EDGE]), each
 * is recycled at once, the worker pool is tiny, and the single ONNX session is serialised through
 * [mlLock] so at most one inference is ever resident. A device photo is read straight off its local
 * file. A cloud photo is embedded from its full-resolution image: the same download+decrypt the viewer
 * uses fetches it into the `fullres` cache, it is decoded bounded, and the file is deleted right after,
 * so the whole library is never stored. That fetch runs only on Wi-Fi and is rate-limited per pass, one
 * download at a time through [downloadLock]; off Wi-Fi or once the budget is spent the photo is left for
 * a later pass. A cloud video keeps to its still-frame thumbnail, the only bitmap a video container
 * yields.
 */
@Singleton
class SemanticIndexingScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val thumbnailScheduler: ThumbnailDecryptScheduler,
    private val photoDownloadService: PhotoDownloadService,
    private val networkObserver: NetworkObserver,
    private val photoListingDao: PhotoListingDao,
    // A Provider for the same reason the face scheduler needs one: GetGalleryItemsUseCase depends on
    // DrivePhotoRepository, which depends on this scheduler, so a direct injection would be a
    // construction cycle. The Provider defers the use-case's construction to the first walk.
    private val galleryItemsProvider: Provider<GetGalleryItemsUseCase>,
    private val deviceHealth: DeviceHealthPolicy,
    private val mlWalkGate: MlWalkGate,
) {
    /** Own scope so an unpause kick and the settings watcher outlive the caller that triggered them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val semanticModelManager by lazy { SemanticModelManager(context) }

    /** A zero-vector blob written as a tombstone for a decode-unloadable photo, so it is recorded
     *  embedded (and skipped next pass) without a re-decode. All-zero bytes are 512 little-endian zero
     *  floats; the search ranks it at cosine 0, so it is never a match. */
    private val tombstoneBlob = ByteArray(EMBEDDING_DIM * Float.SIZE_BYTES)

    /** Serialises the ONNX session: it is not safe for concurrent runs, and holding one run at a time
     *  also caps the transient tensor memory an inference allocates. A run the watchdog abandons keeps
     *  its own (immediately replaced) session, so it never shares one with the next run. */
    private val mlLock = Mutex()

    /** A dedicated elastic pool for the native ONNX runs, owned here. A run the per-item watchdog abandons
     *  stays wedged in an uncancellable native call and keeps its thread; keeping those off
     *  [Dispatchers.Default] and [Dispatchers.IO] means a systematic wedge starves neither the UI nor the
     *  loads. Elastic, so a fresh run always gets a thread while an abandoned one lingers. */
    private val mlDispatcher = Executors.newCachedThreadPool { r ->
        Thread(r, "semantic-ml").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** Caps how many cloud full-res downloads run at once, the same shape the face walk and the photo
     *  download path use, kept separate from [mlLock] so the downloads overlap the serialised inference. */
    private val downloadLock = Semaphore(FULLRES_DOWNLOAD_CONCURRENCY)

    /** One walk at a time: a second trigger while one runs is a no-op instead of stacking a second full
     *  library walk and its item set in memory. */
    private val running = AtomicBoolean(false)

    /** Live mirror of the gate, kept current by [watchSettings] so the per-photo check is a volatile
     *  read rather than a DataStore round trip per item. */
    @Volatile private var aiEnabled = false

    /** In-memory pause, toggled by [setPaused]; there is no persisted semantic pause switch yet, so it
     *  resets to not-paused on a fresh process (turning the feature off is the durable off switch). */
    @Volatile private var paused = false

    /** The user's "download full-res on Wi-Fi only" choice (default on). When off, the background walk may
     *  fetch cloud full-res over mobile data too, instead of stalling at the on-device photos off Wi-Fi. */
    @Volatile private var fullresWifiOnly = true

    /** Set by [reset] to stop a running walk promptly on sign-out; cleared at the start of each walk. */
    @Volatile private var stopRequested = false

    /** A resume asked for while a walk was still winding down: the walk's own tail re-kicks a fresh one
     *  when it lands, so a pause-then-resume never wedges with nothing running. */
    @Volatile private var restartRequested = false

    /** The last account a walk ran for, so an unpause can resume without being handed a userId. */
    @Volatile private var lastUserId: UserId? = null

    private val _progress = MutableStateFlow(SemanticIndexingProgress(SemanticIndexingState.Idle, 0, 0))
    val progress: StateFlow<SemanticIndexingProgress> = _progress.asStateFlow()

    init {
        watchSettings()
        // Mirror every progress emit into the copied diagnostics, so an index standing still short of the
        // end is legible there (on a release build, where the logs are stripped) rather than a blind spot.
        scope.launch { progress.collect { SemanticDiagnostics.record(it.state.name, it.indexed, it.total) } }
    }

    /** Keep [aiEnabled] current so a mid-walk toggle is seen on the next per-photo check. */
    private fun watchSettings() {
        scope.launch {
            context.settingsDataStore.data.collect { prefs ->
                aiEnabled = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.SEMANTIC_ENABLED] == true
                fullresWifiOnly = prefs[SettingsKeys.FULLRES_WIFI_ONLY] ?: true
            }
        }
    }

    /**
     * Embed every not-yet-embedded photo for [userId], newest listing first. A no-op (with the state set
     * accordingly) when the AI features or semantic search are off, when the user has paused indexing, or
     * when the image model is not on the device yet (a later trigger retries once it is). Safe to call
     * repeatedly; overlapping calls collapse to one walk.
     */
    suspend fun indexAll(userId: UserId?) {
        lastUserId = userId
        val prefs = context.settingsDataStore.data.first()
        aiEnabled = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.SEMANTIC_ENABLED] == true
        fullresWifiOnly = prefs[SettingsKeys.FULLRES_WIFI_ONLY] ?: true
        // Dark when off: no model prep, no enumeration, no walk.
        if (!aiEnabled) { setIdle(); return }
        if (paused) { _progress.value = SemanticIndexingProgress(SemanticIndexingState.Paused, 0, 0); return }
        // A walk is already running: ask the active walk to re-kick when it finishes, so a start requested
        // during a winding-down pass is never lost.
        if (!running.compareAndSet(false, true)) { restartRequested = true; return }
        stopRequested = false
        // Rows written (real vectors and tombstones) this pass. Each drains one pending item, so a pass
        // that writes any keeps the auto-continue going and a pass that writes none stops cleanly. Zero
        // also means no progress (off Wi-Fi, or the budget could load nothing more).
        val embeddedThisPass = AtomicInteger(0)
        try {
            val modelFile = semanticModelManager.imageModelFile()
            if (modelFile == null) {
                _progress.value = SemanticIndexingProgress(SemanticIndexingState.WaitingModel, 0, 0)
                return
            }

            val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
            // The model is on the device and a walk is about to run, so this is the choke point to
            // reconcile the stored embeddings' model version: an older-generation row is dropped once
            // here, before anything reads it, and the walk below re-embeds it with the current model.
            runCatching { imageEmbeddingDao.deleteByModelVersionNot(account, SEMANTIC_MODEL_VERSION) }
                .onFailure { Log.w(TAG, "stale-model embedding wipe failed: ${it.message}") }

            val items = runCatching {
                val provider = galleryItemsProvider.get()
                (if (userId == null) provider.invokeLocalOnly() else provider.invoke(userId)).first()
            }.getOrDefault(emptyList())
            val alreadyScanned = runCatching { imageEmbeddingDao.scannedKeysForUser(account).toHashSet() }
                .getOrDefault(HashSet())
            val pending = items.filter { it.stableId !in alreadyScanned }
            val total = pending.size
            // Progress is cumulative across the auto-continue passes, not per pass: the total is the whole
            // library and the indexed value is how many photos have been embedded so far, so the bar climbs
            // steadily to full instead of resetting each pass.
            val libraryTotal = items.size
            val scannedBefore = libraryTotal - total
            if (total == 0) {
                _progress.value = SemanticIndexingProgress(SemanticIndexingState.Done, libraryTotal, libraryTotal)
                return
            }
            _progress.value = SemanticIndexingProgress(SemanticIndexingState.Running, scannedBefore, libraryTotal)

            // Host a large first pass in a foreground service so it survives the app being swiped from
            // Recents; the service only reports progress and re-triggers, the walk and all gating stay
            // here. A redundant start on the already-running service is a no-op.
            if (total >= SEMANTIC_INDEX_FOREGROUND_THRESHOLD) {
                SemanticIndexingService.start(context, userId)
            }

            // Reassignable holder: a per-item inference timeout abandons the wedged session (leaks it rather
            // than closing it under a live native call) and opens a fresh one for the next item.
            val ml = SemanticMl { ClipImageEncoder(modelFile) }
            val cursor = AtomicInteger(0)
            val processed = AtomicInteger(0)
            val decryptBudget = AtomicInteger(MAX_COLD_DECRYPTS_PER_PASS)
            // Wall clock over the embedding pass, folded into the diagnostics at the end as a photos/min
            // throughput, so a fifty-thousand-photo library's finish time is readable from the snapshot.
            val passStart = SystemClock.elapsedRealtime()
            // Hold the shared model gate for the session-resident window, so a face walk's ONNX sessions
            // and this CLIP session are never both loaded at once (the large-heap OOM risk). Taken after
            // the pending set is computed and released the moment the session is closed.
            mlWalkGate.acquire(MlRail.SEMANTIC)
            try {
                coroutineScope {
                    repeat(WORKER_COUNT) {
                        launch {
                            while (active()) {
                                // Gate the walk on the physical/power tier only, not interaction, matching
                                // the face walk: the scan runs serialized on background threads, so viewing
                                // a screen never freezes it. Poll so a pause or sign-out still stops the walk
                                // promptly and the model stays loaded across a short park.
                                var verdict = deviceHealth.verdict()
                                while (active() && !verdict.backgroundWorkAllowed) {
                                    SemanticDiagnostics.recordPark(verdict.reason)
                                    delay(HEALTH_PAUSE_POLL_MS)
                                    verdict = deviceHealth.verdict()
                                }
                                if (!active()) break
                                val i = cursor.getAndIncrement()
                                if (i >= total) break
                                val item = pending[i]
                                try {
                                    indexOne(item, userId, ml, decryptBudget, embeddedThisPass)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    SemanticDiagnostics.recordEmbedFailure()
                                    Log.w(TAG, "index ${item.stableId} failed: ${e.message}")
                                }
                                val done = processed.incrementAndGet()
                                if (done % PROGRESS_STRIDE == 0 || done >= total) {
                                    _progress.value = SemanticIndexingProgress(
                                        if (active()) SemanticIndexingState.Running else SemanticIndexingState.Paused,
                                        scannedBefore + embeddedThisPass.get(),
                                        libraryTotal,
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                // Closes only the live session; an abandoned (timed-out) one was already dropped and is
                // never closed here, so no session is freed while a wedged native call still uses it.
                ml.close()
                mlWalkGate.release(MlRail.SEMANTIC)
            }
            SemanticDiagnostics.recordPass(embeddedThisPass.get(), SystemClock.elapsedRealtime() - passStart)

            val indexedNow = scannedBefore + embeddedThisPass.get()
            _progress.value = when {
                !aiEnabled -> SemanticIndexingProgress(SemanticIndexingState.Idle, indexedNow, libraryTotal)
                // Not Done here: a non-drained pass hands off to the next through the tail re-kick, and the
                // drained branch above is the only place that reports Done.
                paused || stopRequested -> SemanticIndexingProgress(SemanticIndexingState.Paused, indexedNow, libraryTotal)
                // A re-kick is coming (this pass wrote a row, or a restart is already queued), so stay
                // Running to match the tail re-kick below and not flap the foreground service.
                embeddedThisPass.get() > 0 || restartRequested -> SemanticIndexingProgress(SemanticIndexingState.Running, indexedNow, libraryTotal)
                // A full pass that embedded nothing and is not paused means the remainder can't be
                // processed right now (deferred off Wi-Fi, or a few undecodable / low-memory cloud items).
                // There is no re-kick in that case, so settle to Idle rather than leave a Running spinner
                // that reads as "scanning forever, stuck at N of M". The pending items stay pending and are
                // retried on the next trigger (Wi-Fi returns, app relaunch, new photos).
                else -> SemanticIndexingProgress(SemanticIndexingState.Idle, indexedNow, libraryTotal)
            }
        } finally {
            running.set(false)
            // Auto-continue: a full-res walk only downloads a bounded batch per pass, so keep going until
            // the library is drained. A pending resume always re-kicks; otherwise re-kick only while the
            // walk is still permitted and wrote at least one row, which stops cleanly at Done, off Wi-Fi,
            // or on pause.
            val resume = restartRequested
            restartRequested = false
            if (resume || (active() && embeddedThisPass.get() > 0)) {
                scope.launch { runCatching { indexAll(userId) } }
            }
        }
    }

    /**
     * Persist the pause switch in memory. Pausing lets the running walk stop itself on its next per-photo
     * check; unpausing kicks a fresh walk for the last account.
     */
    fun setPaused(paused: Boolean) {
        this.paused = paused
        if (paused) {
            if (_progress.value.state == SemanticIndexingState.Running) {
                _progress.value = _progress.value.copy(state = SemanticIndexingState.Paused)
            }
        } else {
            stopRequested = false
            lastUserId?.let { uid -> scope.launch { runCatching { indexAll(uid) } } }
        }
    }

    /**
     * Start or resume a walk for [userId] right now, clearing the pause first. Runs on the scheduler's own
     * scope so it outlives the screen, and the one-walk guard collapses a redundant kick into the walk
     * already going.
     */
    fun requestIndex(userId: UserId?) {
        scope.launch {
            paused = false
            stopRequested = false
            // If a walk is still winding down, let its tail re-kick a fresh one rather than no-op here.
            restartRequested = true
            runCatching { indexAll(userId) }
        }
    }

    /**
     * Stop any running walk and drop this account's embeddings. Called on sign-out just as the other
     * per-account rows are wiped: image embeddings are private derived data, so they leave with the
     * session rather than lingering for the next account.
     */
    suspend fun reset(userId: UserId?) {
        stopRequested = true
        lastUserId = null
        _progress.value = SemanticIndexingProgress(SemanticIndexingState.Idle, 0, 0)
        SemanticDiagnostics.clear()
        runCatching { imageEmbeddingDao.deleteAllForUser(userId?.id ?: PhotoLocationEntity.LOCAL_USER) }
            .onFailure { Log.w(TAG, "embedding wipe on reset failed: ${it.message}") }
    }

    /**
     * Embed one photo and write its row. A source that loaded is encoded under [mlLock] and its vector
     * stored. A source that never loaded is tombstoned only when it is decode-unloadable (a corrupt local
     * file or a deterministic decode/decrypt wedge), so it is skipped next pass; a transient miss (off
     * Wi-Fi, out of budget, a slow download) writes nothing and stays pending. An inference that wedges
     * past the watchdog abandons the session and also leaves the photo pending. Bounded source in,
     * recycled on the way out.
     */
    private suspend fun indexOne(
        item: GalleryItem,
        userId: UserId?,
        ml: SemanticMl,
        decryptBudget: AtomicInteger,
        embeddedThisPass: AtomicInteger,
    ) {
        if (!active()) return
        // Background semantic indexing yields the process-global crypto gate to interactive decrypts.
        withContext(DecryptPriorityContext(DecryptPriority.BACKGROUND)) {
            val photoKey = item.stableId
            val loadTimedOut = AtomicBoolean(false)
            val source = loadSource(item, userId, decryptBudget, loadTimedOut) ?: run {
                // No source this pass. A device-only item, or one whose decode/decrypt wedged past its
                // watchdog, will not load next pass either, so tombstone it rather than re-decode it every
                // pass. A cloud item deferred off Wi-Fi or out of budget, and a transient download timeout
                // (kept off loadTimedOut), stay pending for a later pass.
                if (shouldTombstoneOnNullSource(item is GalleryItem.LocalOnly, loadTimedOut.get())) {
                    writeRow(account(userId), photoKey, tombstoneBlob, embeddedThisPass)
                }
                return@withContext
            }
            // Bound the native inference: one wedged ONNX run must not pin the shared lock and freeze every
            // worker. On a timeout control returns here, mlLock releases, the wedged session is ABANDONED
            // (never closed under a live native call), and the next item opens a fresh one. The detached
            // block owns [source] and recycles it in its own finally, so a leaked run keeps a valid bitmap.
            val vector = mlLock.withLock {
                val encoder = ml.encoder()
                val completed = boundedRun(ML_TIMEOUT_MS, mlDispatcher) {
                    try {
                        encoder.encode(source)
                    } finally {
                        if (!source.isRecycled) source.recycle()
                    }
                }
                if (completed == null) {
                    SemanticDiagnostics.recordWatchdogTimeout()
                    ml.abandon()
                }
                completed
            }
            // A wedged inference is a transient hard failure: leave the photo pending so a later trigger
            // retries it, rather than tombstoning a photo that may embed cleanly next time.
            if (vector == null) return@withContext
            writeRow(account(userId), photoKey, packEmbedding(vector), embeddedThisPass)
        }
    }

    /** Upsert one embedding row (a real vector or a tombstone). The row's presence is the scan marker, so
     *  a successful write drains this pending item; [embeddedThisPass] counts only rows actually stored. */
    private suspend fun writeRow(
        account: String,
        photoKey: String,
        blob: ByteArray,
        embeddedThisPass: AtomicInteger,
    ) {
        val ok = runCatching {
            imageEmbeddingDao.upsert(
                ImageEmbeddingEntity(account, photoKey, blob, SEMANTIC_MODEL_VERSION, System.currentTimeMillis()),
            )
        }.onFailure { Log.w(TAG, "embedding upsert $photoKey failed: ${it.message}") }.isSuccess
        if (ok) embeddedThisPass.incrementAndGet()
    }

    private fun account(userId: UserId?): String = userId?.id ?: PhotoLocationEntity.LOCAL_USER

    /**
     * A bounded source bitmap for [item]. A device file (LocalOnly, or the local twin of a Synced pair)
     * is decoded straight off the local original with no crypto. A cloud photo is embedded from its
     * full-resolution image on Wi-Fi (downloaded, decoded bounded, then deleted right away); off Wi-Fi or
     * once the per-pass budget is spent it returns null so the photo is deferred. A cloud video falls back
     * to its still-frame thumbnail, the only bitmap a video container yields.
     */
    private suspend fun loadSource(
        item: GalleryItem,
        userId: UserId?,
        decryptBudget: AtomicInteger,
        timedOut: AtomicBoolean,
    ): Bitmap? = when (item) {
        is GalleryItem.LocalOnly -> decodeLocalBounded(item.local.uri, timedOut)
            ?: decodeLocalVideoFrame(item.local.uri)
        is GalleryItem.Synced -> decodeLocalBounded(item.local.uri, timedOut)
            ?: decodeLocalVideoFrame(item.local.uri)
            ?: userId?.let { decodeCloud(item.cloud, it, decryptBudget, timedOut) }
        is GalleryItem.CloudOnly -> userId?.let { decodeCloud(item.cloud, it, decryptBudget, timedOut) }
    }

    /**
     * A bounded still frame from a device video, so a video that lives only on the phone is embedded from
     * a frame exactly as a cloud video is from its thumbnail. Tried only after [decodeLocalBounded] returns
     * null. The decode runs detached and the wait is bounded, so a wedged MediaMetadataRetriever frees this
     * worker rather than pinning it.
     */
    private suspend fun decodeLocalVideoFrame(uri: String): Bitmap? {
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

    /** Route a cloud item: an image embeds from its full-resolution bytes, a video from its still-frame
     *  thumbnail (BitmapFactory cannot decode a video container). */
    private suspend fun decodeCloud(
        photo: CloudPhoto,
        userId: UserId,
        decryptBudget: AtomicInteger,
        timedOut: AtomicBoolean,
    ): Bitmap? =
        if (photo.mimeType.startsWith("video/")) decodeCloudBounded(photo.linkId, userId, decryptBudget, timedOut)
        else decodeCloudFullRes(photo, userId, decryptBudget, timedOut)

    /**
     * Embed a cloud image from its full-resolution bytes. On Wi-Fi and while the per-pass budget allows,
     * the same download+decrypt the viewer uses writes the file into the `fullres` cache; it is decoded
     * bounded and then deleted right away, so the library is never stored. Off Wi-Fi, out of budget, or on
     * a download failure it returns null and the photo waits for a later pass. The download is serialised
     * through [downloadLock] and spends one unit of the shared per-pass budget.
     */
    private suspend fun decodeCloudFullRes(
        photo: CloudPhoto,
        userId: UserId,
        decryptBudget: AtomicInteger,
        timedOut: AtomicBoolean,
    ): Bitmap? {
        // Honour the user's "full-res on Wi-Fi only" setting: once it is off, the walk fetches cloud
        // full-res over mobile data too instead of stalling at the on-device photos off Wi-Fi.
        if (fullresWifiOnly && !networkObserver.currentlyOnWifi()) return null
        if (!takeBudget(decryptBudget)) return null
        // A download timeout is transient (a slow or stalled link), unlike a deterministic decode wedge: it
        // gets its own flag, kept off [timedOut], so the item is left pending for a later pass rather than
        // tombstoned. Only the decode below feeds [timedOut].
        val downloadTimedOut = AtomicBoolean(false)
        val file = downloadLock.withPermit {
            if (!active()) return null
            boundedRun(DOWNLOAD_TIMEOUT_MS, Dispatchers.IO, downloadTimedOut) {
                val produced = runCatching { photoDownloadService.downloadFullResPhoto(userId, photo) }.getOrNull()
                // A download that lands after the watchdog gave up has no consumer, so delete it rather than
                // leave an orphan in the fullres cache.
                if (produced != null && downloadTimedOut.get()) {
                    runCatching { produced.delete() }
                    null
                } else {
                    produced
                }
            }
        } ?: return null
        return try {
            decodeFileBounded(file, timedOut)
        } finally {
            runCatching { file.delete() }
        }
    }

    /** A bounded decode of a device file, run detached under a watchdog so a decode that wedges frees the
     *  worker and flips [timedOut] rather than pinning it. */
    private suspend fun decodeLocalBounded(uri: String, timedOut: AtomicBoolean): Bitmap? =
        boundedRun(DECODE_TIMEOUT_MS, Dispatchers.IO, timedOut) { decodeLocalBlocking(uri) }

    private fun decodeLocalBlocking(uri: String): Bitmap? = runCatching {
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

    /** A bounded decode of a cache file, run detached under a watchdog so a decode that wedges frees the
     *  worker and flips [timedOut] rather than pinning it. */
    private suspend fun decodeFileBounded(file: File, timedOut: AtomicBoolean): Bitmap? =
        boundedRun(DECODE_TIMEOUT_MS, Dispatchers.IO, timedOut) { decodeFileBlocking(file) }

    private fun decodeFileBlocking(file: File): Bitmap? = runCatching {
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
     * Applies a photo's EXIF orientation to its decoded bitmap, so the encoder sees the image upright and
     * the vector describes what the viewer shows. Returns the input untouched for the no-rotation case.
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
     * Decode a cloud video's still-frame thumbnail, preferring the HD (Type 2) thumbnail so the embedding
     * reads more detail than the gallery's Type 1 thumbnail carries. A warm HD file is a plain decode; a
     * cold one is decrypted through the gallery's bounded HD decrypt (same crypto semaphore and libgojni
     * lock), rate-limited per pass. When the budget is spent the item falls back to a warm Type 1 thumbnail
     * or is left for a later pass, and a revision with no Type 2 thumbnail falls back to the Type 1 path.
     */
    private suspend fun decodeCloudBounded(
        linkId: String,
        userId: UserId,
        decryptBudget: AtomicInteger,
        timedOut: AtomicBoolean,
    ): Bitmap? {
        val hdCached = cloudHdThumbFile(linkId)
        if (hdCached.exists() && hdCached.length() > 0L) return decodeFileBounded(hdCached, timedOut)

        if (!takeBudget(decryptBudget)) {
            val warmT1 = cloudThumbFile(linkId)
            return if (warmT1.exists() && warmT1.length() > 0L) decodeFileBounded(warmT1, timedOut) else null
        }

        val entity = runCatching { photoListingDao.getByLinkId(linkId) }.getOrNull() ?: return null
        val contentKeyPacket = entity.contentKeyPacket ?: return null
        val encNodeKey = entity.encNodeKey ?: return null
        val encNodePass = entity.encNodePassphrase ?: return null
        val parentLinkId = entity.parentLinkId ?: return null

        val hdProduced = boundedRun(DECRYPT_TIMEOUT_MS, Dispatchers.IO, timedOut) {
            runCatching {
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
        }
        if (!hdProduced.isNullOrBlank()) return decodeFileBounded(cloudHdThumbFile(linkId), timedOut)

        val warmT1 = cloudThumbFile(linkId)
        if (warmT1.exists() && warmT1.length() > 0L) return decodeFileBounded(warmT1, timedOut)
        val serverUrl = entity.serverThumbnailUrl ?: return null
        val produced = boundedRun(DECRYPT_TIMEOUT_MS, Dispatchers.IO, timedOut) {
            runCatching {
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
            }.getOrNull()
        } ?: return null
        return if (produced.isNotBlank()) decodeFileBounded(cloudThumbFile(linkId), timedOut) else null
    }

    private fun cloudThumbFile(linkId: String): File =
        File(File(context.cacheDir, "thumbnails"), "thumb_$linkId.jpg")

    private fun cloudHdThumbFile(linkId: String): File =
        File(File(context.cacheDir, "thumbnails"), "thumb_hd_$linkId.jpg")

    /** Power-of-two downsample that lands the decoded long edge in [SOURCE_MAX_EDGE, 2*SOURCE_MAX_EDGE):
     *  enough detail for the encoder without ever holding a full-resolution bitmap. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        val longEdge = maxOf(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= SOURCE_MAX_EDGE) sample *= 2
        return sample
    }

    /** Claim one unit of the per-pass cold-decrypt budget, or false when it is spent. */
    private fun takeBudget(budget: AtomicInteger): Boolean {
        while (true) {
            val current = budget.get()
            if (current <= 0) return false
            if (budget.compareAndSet(current, current - 1)) return true
        }
    }

    /** True while a walk may keep going: not stopping, AI + semantic on, not paused. */
    private fun active(): Boolean = !stopRequested && aiEnabled && !paused

    private fun setIdle() {
        _progress.value = SemanticIndexingProgress(SemanticIndexingState.Idle, 0, 0)
    }

    /**
     * Run [block] detached and wait at most [timeoutMs] for it, so a blocking or wedged call (a native
     * ONNX inference, a bitmap decode, a cloud download, a decrypt) frees this worker instead of pinning
     * it: one pathological item then releases the shared lock and the walk moves on. Returns the block's
     * result, or null when the block returned null. On a true timeout it flips [timedOut], cancels the
     * wait, and returns null; the abandoned work keeps running on its own detached coroutine (a wedged
     * native call cannot be cancelled). The caller's decrypt priority rides into the detached context so a
     * background load stays background at the crypto gate.
     */
    private suspend fun <T> boundedRun(
        timeoutMs: Long,
        dispatcher: CoroutineDispatcher,
        timedOut: AtomicBoolean? = null,
        block: suspend CoroutineScope.() -> T,
    ): T? {
        val work = scope.async(
            dispatcher + (coroutineContext[DecryptPriorityContext] ?: EmptyCoroutineContext),
            block = block,
        )
        val result = withTimeoutOrNull(timeoutMs) { work.await() }
        if (result == null && work.isActive) {
            timedOut?.set(true)
            work.cancel()
        }
        return result
    }

    private fun packEmbedding(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        return buffer.array()
    }

    /**
     * Holds a walk's image encoder as a reassignable reference, so a per-item inference timeout can abandon
     * the wedged session and open a fresh one. A timed-out native run cannot be cancelled and keeps using
     * its ONNX session after the watchdog gives up, so that instance must never be [close]d (closing frees
     * the session mid-run: a native use-after-free). [abandon] drops the wedged reference without closing
     * it and the next [encoder] opens a fresh one; [close] frees only the live session. Opened lazily and
     * every access is serialised under [mlLock], so the plain nullable field is safe.
     */
    private class SemanticMl(private val openEncoder: () -> ClipImageEncoder) : AutoCloseable {
        private var encoder: ClipImageEncoder? = null

        fun encoder(): ClipImageEncoder = encoder ?: openEncoder().also { encoder = it }

        /** A run on the current session timed out: drop the reference without closing, so the wedged native
         *  call keeps a valid session and the next [encoder] opens a fresh one. */
        fun abandon() {
            encoder = null
        }

        override fun close() {
            encoder?.let { e -> runCatching { e.close() } }
            encoder = null
        }
    }

    private companion object {
        /** Worker pool size. Three keeps a few photos moving through the download / decode / ONNX pipeline
         *  at once while the ONNX session itself stays serialised by [mlLock]. */
        const val WORKER_COUNT = 3

        /** How many cloud full-res downloads may run at once, so a cloud scan does not crawl one file at a
         *  time; the ONNX inference each feeds stays serialised. */
        const val FULLRES_DOWNLOAD_CONCURRENCY = 3

        /** Long edge the source bitmap is bounded to: big enough for the encoder's square crop while still
         *  bounding a couple of resident bitmaps to a cheap size. */
        const val SOURCE_MAX_EDGE = 1600

        /** Update the progress flow every this many photos, so a large pass does not churn the flow. */
        const val PROGRESS_STRIDE = 20

        /** Cold cloud thumbnails a single pass will decrypt inline, so the whole-library walk cannot flood
         *  the crypto service; the rest wait for a later pass once their thumbnail has warmed. */
        const val MAX_COLD_DECRYPTS_PER_PASS = 64

        /** While the device is not in a good state for heavy work, each worker re-checks at this cadence,
         *  so a resume or a pause / sign-out both take effect within a second. */
        const val HEALTH_PAUSE_POLL_MS = 1_000L

        /** Hard ceiling on one device-video still-frame decode, so a malformed file that hangs
         *  MediaMetadataRetriever frees the worker instead of pinning it. */
        const val VIDEO_FRAME_TIMEOUT_MS = 10_000L

        /** Hard ceiling on one photo's native inference. Generous, so a slow but valid run on a large image
         *  is never falsely skipped; only a true multi-second wedge trips it, freeing the shared ML lock and
         *  the worker rather than freezing every worker on one item. */
        const val ML_TIMEOUT_MS = 30_000L

        /** Hard ceiling on one bitmap decode (a device file or a cloud cache file), so a decode that wedges
         *  frees the worker instead of pinning it. */
        const val DECODE_TIMEOUT_MS = 20_000L

        /** Hard ceiling on one cloud full-res download. Generous, since a large original over a slow link
         *  can legitimately take a while; a trip leaves the item pending for a later pass. */
        const val DOWNLOAD_TIMEOUT_MS = 120_000L

        /** Hard ceiling on one cloud thumbnail decrypt, so a wedged decrypt frees the worker instead of
         *  pinning it. */
        const val DECRYPT_TIMEOUT_MS = 20_000L
    }
}
