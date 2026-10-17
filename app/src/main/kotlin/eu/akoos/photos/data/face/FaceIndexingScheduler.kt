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
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.domain.model.FaceBoxNorm
import eu.akoos.photos.domain.usecase.FACE_CLUSTER_PARAMS_VERSION
import eu.akoos.photos.domain.usecase.FACE_CLUSTER_THRESHOLD
import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import eu.akoos.photos.domain.usecase.FACE_MODEL_VERSION
import eu.akoos.photos.domain.usecase.IncrementalBatch
import eu.akoos.photos.domain.usecase.clusterFaces
import eu.akoos.photos.domain.usecase.cosineSimilarity
import eu.akoos.photos.domain.usecase.REATTACH_MIN_IOU
import eu.akoos.photos.domain.usecase.ReattachLabel
import eu.akoos.photos.domain.usecase.bindReattachLabels
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.crypto.DecryptPriority
import eu.akoos.photos.crypto.DecryptPriorityContext
import eu.akoos.photos.data.db.AppDatabase
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
import eu.akoos.photos.util.FaceDiagnostics.RegroupTrigger
import eu.akoos.photos.util.GuardedSessionHolder
import eu.akoos.photos.util.MlAbandonCap
import eu.akoos.photos.util.MlRail
import eu.akoos.photos.util.MlWalkState
import eu.akoos.photos.util.MlWalkStateStore
import eu.akoos.photos.util.NetworkObserver
import eu.akoos.photos.util.SOURCE_RETRIES_PER_PASS
import eu.akoos.photos.util.SessionLease
import eu.akoos.photos.util.boundedDetachedRun
import eu.akoos.photos.util.elapsedFromEpoch
import eu.akoos.photos.util.epochFromElapsed
import eu.akoos.photos.util.SourceMiss
import eu.akoos.photos.util.SourceMissNote
import eu.akoos.photos.util.SourcePassTally
import eu.akoos.photos.util.SourceRetryBook
import eu.akoos.photos.util.SyncDiagnostics
import eu.akoos.photos.util.WalkRestart
import eu.akoos.photos.util.WalkRetryKick
import eu.akoos.photos.util.retryKickDelayMs
import eu.akoos.photos.util.settledLocalMiss
import eu.akoos.photos.util.sourceMissForDownloadFailure
import eu.akoos.photos.util.sourceMissForUndecodedDownload
import eu.akoos.photos.util.sourceMissForUndecodedLocal
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "FaceIndexingSched"

/** Where the background face-indexing walk currently stands, so Settings can label it. */
enum class FaceIndexingState { Idle, WaitingModel, Running, Paused, Done }

/**
 * A snapshot of the indexer's progress. [indexed] out of [total] photos have been scanned in the
 * current pass; both are 0 in the states that do no walk ([FaceIndexingState.Idle],
 * [FaceIndexingState.WaitingModel]). [heartbeat] climbs while a grouping works with the count standing
 * still (a batch placed, a closing step under way), so a host watching for progress sees the work move; it
 * only ever grows within a process, and 0 means none. [grouping] says the count is faces being placed into
 * groups rather than photos scanned.
 */
data class FaceIndexingProgress(
    val state: FaceIndexingState,
    val indexed: Int,
    val total: Int,
    val heartbeat: Long = 0L,
    val grouping: Boolean = false,
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
 * Whether a photo whose source failed to load should be recorded scanned (so a later pass skips it)
 * rather than left pending. It is marked when its load wedged past a decode or decrypt watchdog (a
 * deterministic hang that would re-wedge next pass), or when the load reported a [SourceMiss.PERMANENT]
 * miss: a cloud link, its keys or its revision gone, or an original (cloud or device) the decoder cannot
 * read. Any other [miss] (off Wi-Fi, out of budget, a device file that could not be opened, a transient
 * failure) leaves it pending for a later retry. Pure so it is unit-tested without Android.
 */
internal fun shouldMarkScannedOnNullSource(loadTimedOut: Boolean, miss: SourceMiss): Boolean =
    loadTimedOut || miss == SourceMiss.PERMANENT

/**
 * Whether a finished walk pass should group the account's waiting faces into people. It needs a walk
 * that is still permitted and at least one face not yet grouped ([ungrouped]), however many faces the
 * pass itself found, so faces already stored are grouped even when this pass loads nothing. A walk about
 * to stop groups at once; a continuing drain groups at most once per [minIntervalMs].
 *
 * Also while names wait to be put back on the re-detected library: the incremental placement only adds
 * new faces to the people already there, and the full regroup that would rebuild them is held back by
 * [regroupAllowed] alone.
 *
 * A walk about to stop also groups with nothing ungrouped while a batched grouping still owes its closing
 * steps ([finalizeOwed]): the merge and the Unsorted bucket wait for the run that ends a walk. Pure so it is
 * unit-tested without Android.
 */
internal fun shouldGroupAtPassEnd(
    active: Boolean,
    willContinue: Boolean,
    ungrouped: Int,
    msSinceLastGroup: Long,
    minIntervalMs: Long,
    finalizeOwed: Boolean = false,
): Boolean =
    active && (ungrouped > 0 || (finalizeOwed && !willContinue)) &&
        (!willContinue || msSinceLastGroup >= minIntervalMs)

/**
 * Whether a library of [faceCount] stored faces is grouped in batches only, never by the whole-library
 * rebuild: the batched placement keeps memory and time bounded however many faces there are, which a full
 * regroup stops doing past [maxFaces]. Pure so it is unit-tested without Android.
 */
internal fun groupsInBatchesOnly(faceCount: Int, maxFaces: Int): Boolean = faceCount > maxFaces

/** Ungrouped faces from which a batched grouping that no scan is hosting runs on the foreground service: a
 *  backlog this size takes minutes, which a process Android freezes in the background never gets. */
internal const val FACE_GROUPING_FOREGROUND_MIN = 5_000

/**
 * Whether a batched grouping of [ungrouped] faces runs on the foreground service. Only when the user allows
 * background indexing ([backgroundEnabled]), the backlog is big enough to outlast the app's time in the
 * foreground, and no scan is already running ([walkRunning]), since a running scan has its own host and its
 * own progress; while that scan groups at its pass end its count stands still, and the heartbeat the batches
 * beat ([FaceIndexingProgress.heartbeat]) keeps its host from stopping. Pure so it is unit-tested without
 * Android.
 */
internal fun shouldHostGroupingInForeground(ungrouped: Int, backgroundEnabled: Boolean, walkRunning: Boolean): Boolean =
    backgroundEnabled && !walkRunning && ungrouped >= FACE_GROUPING_FOREGROUND_MIN

/**
 * Whether a full regroup may start. A forced one (a user naming or merging, an import handing its faces
 * back, the viewer's or a find-more scan) runs even while the walk is paused; any other needs a walk still
 * permitted ([active]). Neither while names wait to be put back on the re-detected library
 * ([reattachPending], see [reattachHoldsGrouping]): the faces do not carry those names yet, so a regroup
 * would rebuild every named person unnamed. Waiting loses nothing, since what the user names, merges or
 * confirms meanwhile is stored, and the regroup that follows the reattach honours it. A forced regroup
 * runs anyway once the names have waited past their grace ([reattachOverdue]), so a library whose scan
 * never reaches the reattach still regroups; the names stay on disk for the reattach. Pure so it is
 * unit-tested without Android.
 */
internal fun regroupAllowed(
    force: Boolean,
    active: Boolean,
    reattachPending: Boolean,
    reattachOverdue: Boolean = false,
): Boolean =
    (!reattachPending || (force && reattachOverdue)) && (force || active)

/**
 * Whether the names waiting on disk ([onDisk], null when none wait) hold a full regroup back: only while
 * they are not what the last reattach over this library left there ([tried]). A reattach puts back every
 * name whose face it finds and keeps only the ones whose photo is still to be scanned; those sit on no
 * face, so a regroup cannot strip them, and they go back once their photo is scanned. Names written in
 * since (an import, a rescan) hold grouping back until a reattach has run over them. Pure so it is
 * unit-tested without Android.
 */
internal fun reattachHoldsGrouping(onDisk: ReattachStamp?, tried: ReattachStamp?): Boolean =
    onDisk != null && onDisk != tried

/**
 * Whether names have waited on disk ([writtenAtMs], when the file was last written) at least [graceMs], so
 * a forced regroup no longer waits for them ([regroupAllowed]). A missing file (0) and a clock set back
 * never count as overdue. Pure so it is unit-tested without Android.
 */
internal fun reattachOverdue(writtenAtMs: Long, nowMs: Long, graceMs: Long): Boolean =
    writtenAtMs > 0L && nowMs - writtenAtMs >= graceMs

/**
 * Whether a walk pass that has not drained the library may put waiting names back now: every photo it
 * planned ([planned]) that it did not reach ([reached] of them it did) has been tried before and waits out
 * a retry backoff in [retries], so no never-tried photo is left, and none was left over the download
 * budget ([overBudget]) for the next pass. A photo the pass reached and deferred (off Wi-Fi, the walk
 * standing down) counts as tried. The names whose photos are still to load stay waiting for a later pass.
 * Pure so it is unit-tested without Android.
 */
internal fun <T> reattachDueBeforeDrain(
    planned: List<T>,
    reached: Int,
    keyOf: (T) -> String,
    retries: SourceRetryBook,
    overBudget: Int,
): Boolean =
    overBudget == 0 &&
        planned.subList(reached.coerceIn(0, planned.size), planned.size).none { retries.attempts(keyOf(it)) == 0 }

/** What a walk pass may take from its read of the library (see [libraryReadVerdict]). */
internal enum class LibraryRead {
    /** Read in full: an empty one is an empty library. */
    Complete,

    /** The read threw: the pass is skipped and retried later, never taken for a drained library. */
    Failed,

    /** It came back empty while the account holds scan markers or faces, which a library emptied and a
     *  listing not loaded yet both look like, so the pass is skipped rather than taken for drained. */
    Unknown,
}

/**
 * How a walk pass reads its library: [LibraryRead.Failed] when either read threw ([readFailed]),
 * [LibraryRead.Unknown] when it came back [empty] while the account still holds scan markers or faces
 * ([holdsFaceData]), else [LibraryRead.Complete]. Only a complete read may run the drained branch, whose
 * reattach lets go of the names whose photos the library no longer holds. Pure so it is unit-tested without
 * Android.
 */
internal fun libraryReadVerdict(readFailed: Boolean, empty: Boolean, holdsFaceData: Boolean): LibraryRead = when {
    readFailed -> LibraryRead.Failed
    empty && holdsFaceData -> LibraryRead.Unknown
    else -> LibraryRead.Complete
}

/**
 * Runs one grouping write [step] the way every grouping runs: under [lock], so it never interleaves with a
 * regroup or a placement, and with its writes allowed only while the [fence] token it took once it held the
 * lock still holds. Pure so it is unit-tested without Android.
 */
internal suspend fun <T> fencedGroupingStep(
    lock: Mutex,
    fence: FaceWriteFence,
    step: suspend (writeAllowed: () -> Boolean) -> T,
): T = lock.withLock {
    val token = fence.token()
    step { fence.allows(token) }
}

/**
 * Whether the pass-end grouping follows its incremental placement with a full regroup: when the
 * incremental path could not handle the pass (no usable cache, a large backlog, a failure), or when a
 * [settled] walk (about to stop rather than continue) still holds faces the incremental path left
 * unplaced, so the full regroup groups them and routes the weak ones to Unsorted instead of leaving any
 * orphaned. Only for faces beyond what the last completed full regroup itself left ungrouped
 * ([exceedsRegroupBaseline] over [ungroupedBaseline]). A regroup that timed out or threw less than
 * [failureBackoffMs] ago is not retried; [msSinceLastFailedRegroup] is null when none has failed. Nor does
 * one run before [cooledDown] ([fullRegroupCooledDown]) allows it. Pure so it is unit-tested without
 * Android.
 */
internal fun shouldRunFullRegroup(
    incrementalHandled: Boolean,
    settled: Boolean,
    ungroupedAfterIncremental: Int,
    ungroupedBaseline: Int?,
    msSinceLastFailedRegroup: Long?,
    failureBackoffMs: Long,
    cooledDown: Boolean = true,
): Boolean =
    (!incrementalHandled || (settled && ungroupedAfterIncremental > 0)) &&
        exceedsRegroupBaseline(ungroupedAfterIncremental, ungroupedBaseline) &&
        (msSinceLastFailedRegroup == null || msSinceLastFailedRegroup >= failureBackoffMs) &&
        cooledDown

/**
 * Whether the drained walk (the whole library scanned, and no reattach, parameter change or debug build
 * calling for a regroup of its own) follows its incremental placement with a full regroup: only for faces
 * waiting beyond what the last completed full regroup itself left ungrouped ([exceedsRegroupBaseline] over
 * [ungroupedBaseline]), and under the same failure backoff and [cooledDown] the pass-end grouping obeys,
 * so a gallery visit to a library whose leftovers never reach zero does not reload every stored embedding.
 * Pure so it is unit-tested without Android.
 */
internal fun shouldRunDrainedRegroup(
    ungroupedAfterIncremental: Int,
    ungroupedBaseline: Int?,
    msSinceLastFailedRegroup: Long?,
    failureBackoffMs: Long,
    cooledDown: Boolean,
): Boolean =
    ungroupedAfterIncremental > 0 &&
        exceedsRegroupBaseline(ungroupedAfterIncremental, ungroupedBaseline) &&
        (msSinceLastFailedRegroup == null || msSinceLastFailedRegroup >= failureBackoffMs) &&
        cooledDown

/**
 * Whether more faces wait ungrouped ([ungrouped]) than the last completed full regroup left that way on
 * purpose ([baseline]). A face kept out of a named person it was marked as not being stays ungrouped by
 * design, so a count that rarely reaches zero would otherwise call for a full regroup again and again;
 * only faces beyond the baseline are new ones to place. A null [baseline] (no regroup has completed since
 * the process started or the faces were last replaced) holds nothing back. Pure so it is unit-tested
 * without Android.
 */
internal fun exceedsRegroupBaseline(ungrouped: Int, baseline: Int?): Boolean =
    baseline == null || ungrouped > baseline

/**
 * The baseline for [exceedsRegroupBaseline] once a grouping that stopped short of a full regroup has seen
 * [ungrouped] faces waiting: never above that, so when faces the last regroup left ungrouped have since
 * gone (a photo deleted, a face placed or removed), the next new face still reads as new. A null [baseline]
 * stays null. Pure so it is unit-tested without Android.
 */
internal fun lowerRegroupBaseline(baseline: Int?, ungrouped: Int): Int? =
    baseline?.let { minOf(it, ungrouped) }

/**
 * Whether a library grouped in batches only runs a batched grouping now: for faces beyond what the last
 * batched run left ungrouped ([exceedsRegroupBaseline] over [baseline]), or, for a run that ends a walk
 * ([finalPass]), when an earlier run left its closing steps owed ([finalizeOwed]) however few faces wait.
 * Pure so it is unit-tested without Android.
 */
internal fun batchedRunDue(ungrouped: Int, baseline: Int?, finalizeOwed: Boolean, finalPass: Boolean): Boolean =
    exceedsRegroupBaseline(ungrouped, baseline) || (finalPass && finalizeOwed)

/**
 * Whether the pass-end grouping goes on from its placement to a full regroup: never for a library grouped in
 * batches only ([batchedOnly]), whose placement already was its whole grouping, so a batched run that failed
 * is tried again at the next pass rather than rerun at once as a final one; otherwise when names were just
 * put back ([reattached]) or [shouldRunFullRegroup] calls for one ([fullRegroupDue]). Pure so it is
 * unit-tested without Android.
 */
internal fun passEndRegroups(batchedOnly: Boolean, reattached: Boolean, fullRegroupDue: Boolean): Boolean =
    !batchedOnly && (reattached || fullRegroupDue)

/** People a batched run may open after its last merge before it merges again between batches. */
internal const val MID_RUN_MERGE_MIN_NEW_PEOPLE = 1_500

/** Batches a batched run places at least between two of its merges between batches. */
internal const val MID_RUN_MERGE_MIN_BATCHES = 10

/**
 * Whether a batched run folds its groups together between two batches: once it has opened at least
 * [MID_RUN_MERGE_MIN_NEW_PEOPLE] people ([openedSinceMerge]) and placed at least [MID_RUN_MERGE_MIN_BATCHES]
 * batches ([batchesSinceMerge]) since its last merge, so the people every batch is matched against stay few
 * enough to keep a batch cheap on a big library. Pure so it is unit-tested without Android.
 */
internal fun midRunMergeDue(openedSinceMerge: Int, batchesSinceMerge: Int): Boolean =
    openedSinceMerge >= MID_RUN_MERGE_MIN_NEW_PEOPLE && batchesSinceMerge >= MID_RUN_MERGE_MIN_BATCHES

/**
 * Keeps face data the indexer writes from landing over an import. Every scan (a walk pass, an on-demand
 * scan, a find-more sweep) and every grouping takes a [token] before it reads the face data it will write
 * back, and writes only while [allows] still holds for that token, asked inside the same database
 * transaction as the write. An import runs between [enterImport] and [exitImport], which both move the
 * generation, so work that began before the import ended writes nothing more; and since the database runs
 * one write transaction at a time, a write that passed the check commits before the import's own
 * transaction can begin. [invalidate] moves the generation alone, for a sign-out or a rescan that makes
 * all work under way stale. Pure so it is unit-tested without Android.
 */
internal class FaceWriteFence {
    private val generation = AtomicLong(0L)
    private val importsInFlight = AtomicInteger(0)

    /** The token a unit of work takes before it reads anything it will write back. */
    fun token(): Long = generation.get()

    /** Whether work holding [token] may still write: no import is running, and none has started or ended,
     *  nor has the fence been invalidated, since the token was taken. */
    fun allows(token: Long): Boolean = importsInFlight.get() == 0 && generation.get() == token

    /** An import is about to write: every token taken so far stops allowing writes. */
    fun enterImport() {
        importsInFlight.incrementAndGet()
        generation.incrementAndGet()
    }

    /** The import has committed or given up. Tokens taken while it ran stay refused; new ones allow. */
    fun exitImport() {
        generation.incrementAndGet()
        importsInFlight.decrementAndGet()
    }

    /** Every token taken so far stops allowing writes. */
    fun invalidate() {
        generation.incrementAndGet()
    }
}

/**
 * Whether enough time has passed since the last completed full regroup for the pass-end grouping to
 * start another: at least [minIntervalMs], and at least [dutyFactor] times as long as that regroup took,
 * so on a large library the full regroups (which read every stored embedding) take a bounded share of
 * the time and memory while the scan runs. [msSinceLastRegroup] is null before any has completed. Pure
 * so it is unit-tested without Android.
 */
internal fun fullRegroupCooledDown(
    msSinceLastRegroup: Long?,
    lastRegroupDurationMs: Long,
    minIntervalMs: Long,
    dutyFactor: Long,
): Boolean =
    msSinceLastRegroup == null || msSinceLastRegroup >= maxOf(minIntervalMs, dutyFactor * lastRegroupDurationMs)

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
    private val autoMergePeople: eu.akoos.photos.domain.usecase.AutoMergePeopleUseCase,
    private val migrateGuestFaceData: eu.akoos.photos.domain.usecase.MigrateGuestFaceDataUseCase,
    private val rekeyPhotoFaces: eu.akoos.photos.domain.usecase.RekeyPhotoFacesUseCase,
    private val deviceHealth: DeviceHealthPolicy,
    private val mlWalkGate: eu.akoos.photos.util.MlWalkGate,
    // Only for the fenced writes (a photo's faces with its scan marker, a batch of markers, a reattach), each
    // one transaction that also asks [writeFence], so no import can commit between that check and the write.
    private val appDatabase: AppDatabase,
    // Read only when a walk's tail is about to start another pass, to check the account is still signed in.
    private val accountManager: Provider<AccountManager>,
) {
    /** Own scope so an unpause kick and the settings watcher outlive the caller that triggered them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Photos whose last load failed transiently, each with its attempts and when it is due again, so a
     *  pass tries never-tried photos first and a failing one waits out its backoff. Saved to [stateStore]
     *  at the end of a pass and taken back by the first pass of a new process; dropped on [reset], so a
     *  rescan or a new account tries everything afresh. */
    private val retryBook = SourceRetryBook()

    /** What the walk keeps between process starts (the retry book and the full regroups' spacing), so a
     *  process the system killed does not begin its backoffs and its regroups again. */
    private val stateStore by lazy {
        MlWalkStateStore(File(File(context.filesDir, "ml_state"), "face_walk.bin"), BuildConfig.VERSION_CODE)
    }

    /** The account whose saved state this process has already taken back, so it is read once. */
    @Volatile private var stateRestoredFor: String? = null

    /** The re-kick a settled walk leaves for its earliest waiting retry. */
    private val retryKick = WalkRetryKick(scope)

    /** Model pairs the watchdog has abandoned in this process, by any scan; once it is reached the walk and
     *  the find-more sweep open no more pairs until the next process. */
    private val abandonCap = MlAbandonCap()

    private val faceModelManager by lazy { FaceModelManager(context) }
    private val faceEmbeddingModelManager by lazy { FaceEmbeddingModelManager(context) }

    /** Serialises the two ONNX sessions: neither is safe for concurrent runs on ONE session, and holding
     *  one run at a time also caps the transient tensor memory a detection or embedding allocates. A run
     *  the watchdog abandons keeps its own (immediately replaced) session, so it never shares one with the
     *  next run. */
    private val mlLock = Mutex()

    /** A dedicated elastic pool for the native ONNX runs, owned here. A run the per-item watchdog abandons
     *  stays wedged in an uncancellable native call and keeps its thread; keeping those off
     *  [Dispatchers.Default] (shared with Compose) and [Dispatchers.IO] (the load path) means a systematic
     *  wedge starves neither the UI nor the loads. Elastic, so a fresh run always gets a thread while an
     *  abandoned one lingers; idle threads are reclaimed. */
    private val mlDispatcher = Executors.newCachedThreadPool { r ->
        Thread(r, "face-ml").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** Caps how many cloud full-res downloads run at once. A few in parallel is the same shape the
     *  photo download path already uses, and overlapping them is what makes a cloud scan keep pace
     *  rather than crawl one file at a time. Kept separate from [mlLock] so the downloads overlap the
     *  serialised ONNX inference. */
    private val downloadLock = Semaphore(FULLRES_DOWNLOAD_CONCURRENCY)

    /** One walk at a time: a second trigger (refresh, unpause) while one runs is a no-op instead of
     *  stacking a second full library walk and its item set in memory. */
    private val running = AtomicBoolean(false)

    /** Mirrors [running] as a flow, so [awaitIdle] can suspend until the walk in flight has ended. */
    private val walkRunning = MutableStateFlow(false)

    /** Fences every face, scan-marker, reattach and grouping write this scheduler makes against an import
     *  ([withImportFence]) and a sign-out or rescan ([reset]), so work that read the face data before
     *  either never writes back over what came after. */
    private val writeFence = FaceWriteFence()

    /** Serialises every grouping (the pass-end and drained placement and regroup, a forced regroup, the
     *  on-demand and sweep groupings), so two full regroups never hold every stored embedding at once and
     *  an incremental placement never interleaves with a full rewrite. Never held across a scan. */
    private val groupingLock = Mutex()

    /** Live mirror of the two gates, kept current by [watchSettings] so the per-photo check is a
     *  volatile read rather than a DataStore round trip per item. */
    @Volatile private var aiEnabled = false
    @Volatile private var paused = false

    /** The user's "download full-res on Wi-Fi only" choice (default on). When off, the background scan may
     *  fetch cloud full-res over mobile data too, so a scan is not stuck at the device photos off Wi-Fi. */
    @Volatile private var fullresWifiOnly = true

    /** The user's opt-in "automatically merge likely-same people" choice (default off). When on, a drained
     *  pass folds each named person's closest look-alike cluster in without asking. */
    @Volatile private var autoMerge = false

    /** Set by [reset] to stop a running walk promptly on sign-out; cleared at the start of each walk. */
    @Volatile private var stopRequested = false

    /** A resume asked for while a walk was still winding down, which the walk's own tail honours when it
     *  lands so a pause-then-resume never wedges with nothing running, and the last account a walk ran for,
     *  so an unpause can resume without being handed a userId. Both dropped by [reset]. */
    private val restart = WalkRestart()

    /** Where the reattach file stood once the last reattach over this library was done with it, so what it
     *  left (names whose photos are still to be scanned) holds no grouping back ([reattachHoldsGrouping]).
     *  In memory: a new process holds grouping back until its first reattach. */
    @Volatile private var triedReattachStamp: ReattachStamp? = null

    /** When the last mid-walk clustering ran (elapsedRealtime), so groups can refresh during a long
     *  drain without re-clustering on every pass. */
    @Volatile private var lastIncrementalClusterMs = 0L

    /** When a full regroup last timed out or threw (elapsedRealtime), or null when none has, so the
     *  pass-end grouping backs off a failing regroup instead of retrying it on every settled pass. */
    @Volatile private var lastFailedRegroupMs: Long? = null

    /** When the last full regroup completed (elapsedRealtime), or null before any has, and how long it
     *  ran, so the pass-end grouping spaces full regroups by their cost ([fullRegroupCooledDown]). */
    @Volatile private var lastRegroupEndMs: Long? = null
    @Volatile private var lastRegroupDurationMs = 0L

    /** How many faces the last completed full regroup or batched run left ungrouped on purpose, with the
     *  [writeFence] token it read them under, so a later grouping regroups fully only for faces beyond it
     *  ([exceedsRegroupBaseline]), and whether a batched run still owes its closing steps. Saved with the
     *  walk state ([saveWalkState]) and taken back by a new process ([restoreWalkState]). Read through
     *  [currentRegroupBaseline], which drops it once an import, a sign-out or a rescan has moved the fence;
     *  written under [groupingLock], and cleared by a model-version wipe. */
    @Volatile private var regroupBaseline: RegroupBaseline? = null

    private val _progress = MutableStateFlow(FaceIndexingProgress(FaceIndexingState.Idle, 0, 0))
    val progress: StateFlow<FaceIndexingProgress> = _progress.asStateFlow()

    /** The last [FaceIndexingProgress.heartbeat] handed out, so every beat of this process is higher. */
    private val heartbeat = AtomicLong(0L)

    init {
        watchSettings()
        watchNetworkResume()
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
                fullresWifiOnly = prefs[SettingsKeys.FULLRES_WIFI_ONLY] ?: true
                autoMerge = prefs[SettingsKeys.FACE_AUTO_MERGE] == true
            }
        }
    }

    /** Resume a scan that stalled while off Wi-Fi once the connection becomes unmetered again. An
     *  off-Wi-Fi cloud backlog otherwise sits in Running with a frozen count until a manual trigger,
     *  because a pass that loads no source does not re-kick itself. Only a signed-in account has cloud
     *  items to defer, so a guest (lastUserId null) never kicks; indexAll collapses an overlapping call. */
    private fun watchNetworkResume() {
        scope.launch {
            var wasUnmetered = networkObserver.isUnmetered.value
            networkObserver.isUnmetered.collect { nowUnmetered ->
                if (shouldResumeFaceScanOnUnmetered(wasUnmetered, nowUnmetered)) {
                    restart.lastUserId?.let { uid -> runCatching { indexAll(uid) } }
                }
                wasUnmetered = nowUnmetered
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
        restart.lastUserId = userId
        val prefs = context.settingsDataStore.data.first()
        aiEnabled = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
        paused = prefs[SettingsKeys.FACE_INDEXING_PAUSED] == true
        fullresWifiOnly = prefs[SettingsKeys.FULLRES_WIFI_ONLY] ?: true
        autoMerge = prefs[SettingsKeys.FACE_AUTO_MERGE] == true
        // Dark when off: no model prep, no enumeration, no walk.
        if (!aiEnabled) { setIdle(); return }
        if (paused) { _progress.value = FaceIndexingProgress(FaceIndexingState.Paused, 0, 0); return }
        // A walk is already running: instead of dropping this request, ask the active walk to re-kick
        // when it finishes, so a start requested during a winding-down pass is never lost (which is
        // what made a fresh start after import or reset appear to do nothing).
        if (!running.compareAndSet(false, true)) { restart.request(); return }
        walkRunning.value = true
        // This pass supersedes any re-kick an earlier settled pass left for its retries.
        retryKick.cancel()
        // Taken before this pass reads a face or a scan marker: once an import begins (or a sign-out or
        // rescan resets the scheduler), everything this pass would still write is dropped, and the pass
        // stops at its next photo.
        val passToken = writeFence.token()
        stopRequested = false
        // What this pass's loads came to. Its moved count (a source loaded, or a photo settled without
        // one) is the pass's headway: zero means none (off Wi-Fi, or every attempt failed transiently), so
        // the tail does not re-kick and spin.
        val tally = SourcePassTally(retryBook)
        // Whether this pass holds the shared model gate: taken for the scan and kept through the pass-end
        // grouping, released once that is done, or in the finally below on any other way out.
        var holdsGate = false
        // Whether this pass was skipped because the library could not be read, so the tail comes back for it.
        var libraryUnread = false
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

            // Before this account reads any scan markers or enumerates, adopt a guest's face data if
            // this is the sign-in after a local-only session: re-key local -> account so the device
            // photos are not re-scanned and their names overwritten. Idempotent, skipped for a guest.
            userId?.let { acct ->
                runCatching {
                    val result = migrateGuestFaceData.invoke(acct.id)
                    FaceDiagnostics.recordMigration(result.action.name, result.people, result.faces)
                }.onFailure { Log.w(TAG, "guest face adopt failed: ${it.message}") }
            }

            val account = accountOf(userId)
            restoreWalkState(account)
            val items = try {
                val provider = galleryItemsProvider.get()
                (if (userId == null) provider.invokeLocalOnly() else provider.invoke(userId)).first()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "library read failed: ${e.message}")
                null
            }
            val alreadyScanned = try {
                faceScanDao.scannedKeysForUser(account).toHashSet()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "scan marker read failed: ${e.message}")
                null
            }
            // A read that failed, or came back empty while this account still holds scan markers or faces,
            // says nothing about the library: taken for an empty one, it would run the drained branch below,
            // whose reattach lets go of every waiting name whose photo it does not see (and a lost marker
            // read would re-detect every photo). Such a pass is skipped; a failed read comes back after the
            // retry floor, an unknown one with the next trigger.
            val read = libraryReadVerdict(
                readFailed = items == null || alreadyScanned == null,
                empty = items.isNullOrEmpty(),
                holdsFaceData = items?.isEmpty() == true && alreadyScanned != null &&
                    (alreadyScanned.isNotEmpty() || runCatching { faceDao.countForUser(account) > 0 }.getOrDefault(true)),
            )
            if (read != LibraryRead.Complete || items == null || alreadyScanned == null) {
                libraryUnread = read == LibraryRead.Failed
                if (_progress.value.state == FaceIndexingState.Running) {
                    _progress.value = _progress.value.copy(state = FaceIndexingState.Idle)
                }
                return
            }
            // A device photo backed up to Drive re-appears under its cloud linkId. If its device copy
            // was already scanned, move its faces to the cloud key instead of re-scanning a duplicate
            // set, or merge them into the cloud key's own when that copy was scanned too (a restored
            // face snapshot), and treat the cloud key as scanned so the walk skips it. Each photo is one
            // transaction behind this pass's fence token, like the scan writes. Account only (a guest has
            // no cloud copy).
            userId?.let { acct ->
                val syncedPairs = items.filterIsInstance<GalleryItem.Synced>().map { it.local.uri to it.cloud.linkId }
                eu.akoos.photos.domain.usecase.backupRekeyPairs(syncedPairs, alreadyScanned).forEach { (oldKey, newKey) ->
                    runCatching {
                        if (rekeyPhotoFaces.invoke(acct.id, oldKey, newKey) { writeFence.allows(passToken) }) alreadyScanned.add(newKey)
                    }.onFailure { Log.w(TAG, "face rekey on backup failed: ${it.message}") }
                }
            }
            val pending = items.filter { it.stableId !in alreadyScanned }
            val total = pending.size
            // Progress is cumulative across the auto-continue passes, not per pass: the total is the
            // whole library and the indexed value is how many photos have been scanned so far, so the
            // bar climbs steadily to full instead of resetting to 0 with a shrinking total each pass.
            val libraryTotal = items.size
            val scannedBefore = libraryTotal - total
            // Nothing left to scan: the library is fully drained, so this is where the final grouping
            // runs. Only when new faces were added since the last cluster, so a gallery re-entry that
            // finds everything already grouped does no work. A debug build re-clusters every drained
            // pass instead, so changing the cluster threshold and relaunching re-groups the stored
            // embeddings with no re-embed; release keeps the new-faces-only guard.
            if (total == 0) {
                retryBook.clear()
                // "Fully scanned" holds only for the library as this pass read it. An import since then may
                // have brought or replaced faces, so the reattach and the grouping wait for the walk the
                // import's caller starts next rather than acting on a stale view.
                if (writeFence.allows(passToken)) {
                    // The library is fully re-detected, so any names a wipe or an import parked can now be
                    // put back on the faces before the groups form. A name whose photo this complete read
                    // does not hold goes; an empty read lets none go, since no photo could be checked.
                    val reattached = reattachPendingLabels(userId, passToken, library = items.takeIf { it.isNotEmpty() })
                    // A clustering-parameter bump, a label reattach, or a debug build needs the global pass:
                    // it regroups the stored embeddings once with no re-embed, and the new params generation
                    // is recorded only after the pass actually completes (a failed one simply retries next
                    // walk).
                    val paramsOutdated =
                        (prefs[SettingsKeys.FACE_CLUSTER_PARAMS_VERSION_KEY] ?: 0) != FACE_CLUSTER_PARAMS_VERSION
                    if (BuildConfig.DEBUG || reattached || paramsOutdated) {
                        val grouped = cluster(userId, RegroupTrigger.DRAINED) { true }
                        if (paramsOutdated && grouped) {
                            context.settingsDataStore.edit {
                                it[SettingsKeys.FACE_CLUSTER_PARAMS_VERSION_KEY] = FACE_CLUSTER_PARAMS_VERSION
                            }
                        }
                    } else {
                        // Routine new photos: let the incremental pass attach them to existing people AND
                        // form new clusters from the fresh faces, so the whole library is not regrouped just
                        // to place a few. Only the weak faces it cannot confidently place are left over, and
                        // those fall to a full regroup (which routes them to Unsorted) so none are orphaned,
                        // once they outnumber what the last regroup left ungrouped on purpose, the failure
                        // backoff and cooldown allow, and no names still wait (a reattach the fence turned
                        // away above, see [regroupAllowed]). Decided under the grouping lock, so a regroup
                        // that just finished elsewhere counts.
                        groupingLock.withLock {
                            tryAssignNewFaces(userId, finalPass = true)
                            val leftover = runCatching { faceDao.unclusteredCount(account) }.getOrDefault(0)
                            val decidedMs = SystemClock.elapsedRealtime()
                            if (regroupAllowed(force = false, active = active(), reattachPending = reattachPending()) &&
                                shouldRunDrainedRegroup(
                                    ungroupedAfterIncremental = leftover,
                                    ungroupedBaseline = currentRegroupBaseline(userId),
                                    msSinceLastFailedRegroup = lastFailedRegroupMs?.let { decidedMs - it },
                                    failureBackoffMs = FULL_REGROUP_FAILURE_BACKOFF_MS,
                                    cooledDown = regroupCooledDown(decidedMs),
                                )
                            ) {
                                regroupLocked(userId, RegroupTrigger.DRAINED, finalPass = true)
                            } else {
                                lowerBaselineTo(userId, leftover)
                            }
                        }
                    }
                    // Opt-in auto-merge: once the groups have settled, fold each named person's closest
                    // look-alike cluster in (the same matches the manual suggestion card would offer), so
                    // the user does not confirm them one by one. A grouping write like any other: under the
                    // grouping lock and behind the fence. Best-effort; a failure never fails the walk.
                    if (autoMerge) {
                        runCatching { fencedGroupingStep(groupingLock, writeFence) { allowed -> autoMergePeople(account, allowed) } }
                            .onFailure { Log.w(TAG, "auto-merge failed: ${it.message}") }
                    }
                }
                pushLibraryCounts(userId)
                _progress.value = FaceIndexingProgress(FaceIndexingState.Done, libraryTotal, libraryTotal)
                return
            }
            // Never-tried photos go first, then at most SOURCE_RETRIES_PER_PASS of the retries that are due,
            // so photos that keep failing can never take the pass's download budget from ones not yet
            // tried; a retry not yet due sits this pass out.
            val planned = retryBook.planPass(pending, { it.stableId }, SystemClock.elapsedRealtime(), SOURCE_RETRIES_PER_PASS).queue
            // Once this process has abandoned its cap of model pairs, the walk opens no more: the pass scans
            // nothing, still groups what is stored, and settles until the next process resumes it.
            val halted = abandonCap.reached()
            if (halted) FaceDiagnostics.recordAbandonedSessions(abandonCap.count(), halted = true)
            val queue = if (halted) emptyList() else planned
            _progress.value = FaceIndexingProgress(FaceIndexingState.Running, scannedBefore, libraryTotal)

            // Promote a substantial initial backlog onto a foreground service so the walk survives the
            // app being swiped from Recents; a few incremental photos stay on this coroutine with no
            // notification. Idempotent across passes, and swallowed if a background start is disallowed.
            // A guest gets the same host: the service carries the local partition, so a large signed-out
            // library is exactly as durable as a signed-in one.
            if (shouldRunForegroundIndex(queue.size, prefs[SettingsKeys.FACE_INDEX_BACKGROUND] != false)) {
                FaceIndexingService.start(context, userId)
            }

            // Reassignable pair: a per-item ML timeout abandons the wedged instances (leaks their sessions
            // rather than closing them under a live native call) and opens a fresh pair for the next item.
            val ml = FaceMl(
                { debugTimed("face detector") { FaceDetector(detFile, dispatcher = mlDispatcher) } },
                { debugTimed("embedder") { FaceEmbedder(embFile, dispatcher = mlDispatcher) } },
            )
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
            // A photo whose source loaded is written with its faces and its skip marker together the moment
            // its detection ends, and counted in [persistedThisPass]. [scannedKeys] collects the rest a pass
            // records: the photos recorded scanned without a source (or whose faces failed to write), whose
            // markers are persisted once the walk finishes; a deferred photo is never added, so it stays
            // pending for a later pass. Concurrent because the workers add in parallel.
            val scannedKeys = ConcurrentHashMap.newKeySet<String>()
            // Those markers are also flushed mid-pass in chunks (not only at pass end), so a long single-pass
            // drain that is killed keeps them. [persistedThisPass] also counts the keys already
            // flushed-and-removed, so progress stays the running scanned total even though [scannedKeys]
            // then holds only the not-yet-flushed remainder. One flush runs at a time.
            val persistedThisPass = AtomicInteger(0)
            val scanFlushing = AtomicBoolean(false)
            // A pass keeps going only while the walk is permitted, nothing has fenced its writes off, and the
            // process has not abandoned its cap of model pairs.
            val passLive = { active() && writeFence.allows(passToken) && !abandonCap.reached() }
            // Hold the shared model gate for the session-resident window, so this face pair and a
            // semantic walk's CLIP session are never both loaded at once (the large-heap OOM risk), and on
            // through the pass-end grouping, so the search model cannot load while a regroup holds every
            // embedding on the heap. The short user-facing scans stay ungated. A pass with nothing due
            // (every pending photo waiting out a backoff) loads no model and waits on no gate.
            if (queue.isNotEmpty()) {
                mlWalkGate.acquire(MlRail.FACE)
                holdsGate = true
            }
            try {
                coroutineScope {
                    repeat(WORKER_COUNT) { workerIndex ->
                        launch {
                            while (passLive()) {
                                // The face scan runs serialized on background threads, so it no longer
                                // stands down for touch input: gate the walk on the physical/power tier
                                // only, so watching the face screen never freezes the scan. It still
                                // stands down for low battery / heat / power-save. Poll so a pause or
                                // sign-out still stops the walk promptly and the models stay loaded across
                                // a short park rather than reloading. One verdict snapshot per check keeps
                                // the recorded reason honest: a snapshot that denies background work always
                                // names a physical/power reason (the interaction branch is reached only
                                // once those gates pass), so the walk never records an interaction park.
                                var verdict = deviceHealth.mlVerdict()
                                while (passLive() && !verdict.backgroundWorkAllowed) {
                                    FaceDiagnostics.recordPark(verdict.reason)
                                    deviceHealth.awaitMlWorkAllowed()
                                    verdict = deviceHealth.mlVerdict()
                                }
                                if (!passLive()) break
                                val i = cursor.getAndIncrement()
                                if (i >= queue.size) break
                                val item = queue[i]
                                try {
                                    indexOne(item, userId, ml, passToken, decryptBudget, tally, scannedKeys, persistedThisPass, "w$workerIndex", facesThisPass, acceptedScores, droppedTooBlurry, acceptedBlur)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    Log.w(TAG, "index ${item.stableId} failed: ${e.message}")
                                }
                                val done = processed.incrementAndGet()
                                if (done % PROGRESS_STRIDE == 0 || done >= queue.size) {
                                    _progress.value = FaceIndexingProgress(
                                        if (active()) FaceIndexingState.Running else FaceIndexingState.Paused,
                                        scannedBefore + persistedThisPass.get() + scannedKeys.size,
                                        libraryTotal,
                                    )
                                }
                                if (BuildConfig.DEBUG && (done % DEBUG_PROGRESS_LOG_STRIDE == 0 || done >= queue.size)) {
                                    val elapsedS = (SystemClock.elapsedRealtime() - passStart) / 1000.0
                                    val rate = if (elapsedS > 0.0) done / elapsedS else 0.0
                                    Log.i(
                                        TAG,
                                        "indexed ${scannedBefore + persistedThisPass.get() + scannedKeys.size}/$libraryTotal photos, " +
                                            "faces found ${facesThisPass.get()}, " +
                                            "rate ${String.format(Locale.US, "%.1f", rate)}/s",
                                    )
                                }
                                // Persist the collected scan markers mid-pass in chunks, so a long single-pass
                                // drain that is killed keeps them instead of restarting from zero. One flush at
                                // a time; the other workers keep scanning meanwhile.
                                if (scannedKeys.size >= SCAN_FLUSH_CHUNK && scanFlushing.compareAndSet(false, true)) {
                                    try { flushScannedChunk(userId, scannedKeys, persistedThisPass, passToken) }
                                    finally { scanFlushing.set(false) }
                                }
                            }
                        }
                    }
                }
                if (BuildConfig.DEBUG && acceptedScores != null) {
                    logDetectionStats(acceptedScores, ml.droppedTooSmall())
                    logBlurStats(acceptedBlur.orEmpty(), droppedTooBlurry.get())
                }
            } finally {
                // Retires the live pair: closed now when idle, else once the run a cancelled worker left in
                // flight returns. An abandoned pair closes itself when its wedged run ends, so no session is
                // freed while a native call still uses it.
                ml.close()
            }

            // Persist this pass's remaining collected markers in one batch, so the next run skips these
            // photos. A stopped or paused pass still records what it did scan; the sign-out wipe clears the
            // table separately, and a pass an import or a sign-out has fenced off records nothing more.
            if (scannedKeys.isNotEmpty()) {
                try {
                    storeScanMarkers(userId, scannedKeys.toList(), passToken)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "scan marker flush failed: ${e.message}")
                }
            }

            // Group DURING the walk, not only when the whole library drains, whenever faces wait ungrouped
            // (however many this pass found), so a library whose remainder never drains still gets its
            // people. Two triggers, both bounded (a regroup runs under a timeout): (1) the walk is about
            // to stop rather than continue (off Wi-Fi, health-parked, nothing more to source this pass),
            // so group what is indexed instead of waiting for a drain that may never come; (2) a long
            // continuous drain crossed the refresh interval, so people appear and grow as the scan runs.
            // Incremental placement runs first, also while names wait to be reattached, since it only adds
            // new faces to the people already there. A settled walk then regroups fully while faces are
            // still unplaced, as the drained branch does, so the weak ones land in Unsorted instead of
            // staying orphaned; a continuing drain regroups fully only when the incremental path cannot be
            // used. Either only for faces beyond what the last full regroup left ungrouped on purpose, and a
            // full regroup that timed out or threw is not retried from here until its backoff passes. Never
            // while names wait to be reattached, asked once the lock is held, since an import or a rescan
            // may have staged names while this waited ([regroupAllowed]). Decided under the grouping lock,
            // so a regroup that just finished elsewhere (a forced one after an import) counts and is not
            // repeated.
            //
            // A library that never drains (off Wi-Fi, photos retried for good) would keep waiting names off
            // its faces for good, so once no never-tried photo is left the names whose faces are there go
            // back now and a full regroup follows; the rest wait for their photos ([reattachDueBeforeDrain]).
            // Only while some name has not been tried against the library yet, or this pass scanned photos
            // a waiting name may sit on.
            val willContinue = restart.isRequested() || (active() && tally.moved > 0)
            val reached = if (halted) 0 else minOf(cursor.get(), planned.size)
            val reattached = active() && writeFence.allows(passToken) && reattachFile.isFile &&
                (reattachPending() || tally.moved > 0) &&
                reattachDueBeforeDrain(planned, reached, { it.stableId }, retryBook, tally.overBudget) &&
                reattachPendingLabels(userId, passToken, library = null)
            val nowMs = SystemClock.elapsedRealtime()
            if (reattached || shouldGroupAtPassEnd(
                    active = active(),
                    willContinue = willContinue,
                    ungrouped = runCatching { faceDao.unclusteredCount(account) }.getOrDefault(0),
                    msSinceLastGroup = nowMs - lastIncrementalClusterMs,
                    minIntervalMs = INCREMENTAL_CLUSTER_MIN_INTERVAL_MS,
                    finalizeOwed = currentFinalizeOwed(userId),
                )
            ) {
                lastIncrementalClusterMs = nowMs
                groupingLock.withLock {
                    // A batched run during a continuing scan leaves the merge and the Unsorted bucket for the
                    // run that ends the walk: both are final steps over everything the scan has found.
                    val handled = tryAssignNewFaces(userId, finalPass = !willContinue)
                    val leftover = runCatching { faceDao.unclusteredCount(account) }.getOrDefault(0)
                    val batchedOnly = groupsInBatchesOnly(
                        runCatching { faceDao.countForUser(account) }.getOrDefault(0),
                        FULL_REGROUP_MAX_FACES,
                    )
                    val decidedMs = SystemClock.elapsedRealtime()
                    if (regroupAllowed(force = false, active = active(), reattachPending = reattachPending()) &&
                        passEndRegroups(
                            batchedOnly = batchedOnly,
                            reattached = reattached,
                            fullRegroupDue = shouldRunFullRegroup(
                                incrementalHandled = handled,
                                settled = !willContinue,
                                ungroupedAfterIncremental = leftover,
                                ungroupedBaseline = currentRegroupBaseline(userId),
                                msSinceLastFailedRegroup = lastFailedRegroupMs?.let { decidedMs - it },
                                failureBackoffMs = FULL_REGROUP_FAILURE_BACKOFF_MS,
                                cooledDown = regroupCooledDown(decidedMs),
                            ),
                        )
                    ) {
                        regroupLocked(
                            userId,
                            if (willContinue) RegroupTrigger.PASS_CONTINUING else RegroupTrigger.PASS_SETTLED,
                            finalPass = !willContinue,
                            gateHeld = holdsGate,
                        )
                    } else {
                        lowerBaselineTo(userId, leftover)
                    }
                }
            }
            // The grouping is over, so a semantic walk may load its model now.
            if (holdsGate) {
                holdsGate = false
                mlWalkGate.release(MlRail.FACE)
            }
            pushLibraryCounts(userId)
            FaceDiagnostics.recordSourceBacklog(transientWaiting = retryBook.size(), overBudget = tally.overBudget)
            val indexedNow = scannedBefore + persistedThisPass.get() + scannedKeys.size
            _progress.value = when {
                !aiEnabled -> FaceIndexingProgress(FaceIndexingState.Idle, indexedNow, libraryTotal)
                // Not Done here: a non-drained pass hands off to the next through the tail re-kick, and
                // the drained branch above is the only place that reports Done. Reporting Done at every
                // pass end is what made the bar look like it kept finishing and starting over.
                paused || stopRequested -> FaceIndexingProgress(FaceIndexingState.Paused, indexedNow, libraryTotal)
                // A re-kick is coming (this pass loaded a source or settled a photo, or a restart is already
                // queued), so stay Running to match the tail re-kick below and not flap the foreground service.
                tally.moved > 0 || restart.isRequested() -> FaceIndexingProgress(FaceIndexingState.Running, indexedNow, libraryTotal)
                // A full pass that moved nothing on and is not paused means the remainder can't be scanned
                // right now (deferred off Wi-Fi, every attempt failed transiently, or every photo left waits
                // out a retry backoff). There is no immediate re-kick then, so settle to Idle rather than a
                // Running spinner that reads as "scanning forever, stuck at N of M". The pending items stay
                // pending: the tail re-kicks once the earliest retry is due, and any other trigger retries.
                else -> FaceIndexingProgress(FaceIndexingState.Idle, indexedNow, libraryTotal)
            }
        } finally {
            if (holdsGate) mlWalkGate.release(MlRail.FACE)
            if (!stopRequested && writeFence.allows(passToken)) saveWalkState(userId)
            running.set(false)
            walkRunning.value = false
            // Auto-continue: a full-res walk only downloads a bounded batch per pass, so keep going
            // until the library is drained. A pending resume always re-kicks; otherwise re-kick only
            // while the walk is still permitted and made headway, which stops cleanly at Done, off
            // Wi-Fi, or on pause. A walk that settles with retries still waiting, or that could not read the
            // library, comes back when the earliest is due (the retry floor for an unread library), unless the
            // process has halted the walk. A resume is for the account last asked for, a continuation for
            // this walk's own, and either starts only while that account is still the one signed in
            // ([restartIfCurrent]).
            val resume = restart.take()
            if (resume || (active() && tally.moved > 0)) {
                val next = if (resume) restart.lastUserId else userId
                scope.launch { restartIfCurrent(next) }
            } else if (active() && !abandonCap.reached()) {
                val now = SystemClock.elapsedRealtime()
                retryKickDelayMs(if (libraryUnread) now else retryBook.nextDueAfter(now), now)?.let { wait ->
                    retryKick.schedule(wait) { restartIfCurrent(userId) }
                }
            }
        }
    }

    /**
     * Persist a chunk of accrued scan markers mid-pass and drop them from [scannedKeys], so a process or
     * foreground-service kill during a long single-pass drain keeps the photos already scanned rather than
     * restarting from zero. Snapshot then remove, so each marker is written once (idempotent upsert aside);
     * concurrent adds during the write land in the set and the next flush (or the pass-end flush) carries
     * them. On a write failure the keys are kept so the pass-end flush retries them; keys the fence turned
     * away ([fenceToken] stale) are dropped, their photos left pending for a later pass. [persistedThisPass]
     * tracks the written count so progress stays the running scanned total after the set shrinks.
     */
    private suspend fun flushScannedChunk(
        userId: UserId?,
        scannedKeys: MutableSet<String>,
        persistedThisPass: AtomicInteger,
        fenceToken: Long,
    ) {
        val snapshot = scannedKeys.toList()
        if (snapshot.isEmpty()) return
        val written = try {
            storeScanMarkers(userId, snapshot, fenceToken)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "incremental scan flush failed: ${e.message}")
            return
        }
        scannedKeys.removeAll(snapshot.toHashSet())
        if (written) persistedThisPass.addAndGet(snapshot.size)
    }

    /**
     * Records [photoKeys] scanned for the account in one transaction, only while [fenceToken] still holds
     * ([writeFence], asked inside the transaction). Returns whether it wrote; a database failure propagates.
     */
    private suspend fun storeScanMarkers(userId: UserId?, photoKeys: Collection<String>, fenceToken: Long): Boolean {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        return appDatabase.withTransaction {
            if (!writeFence.allows(fenceToken)) return@withTransaction false
            faceScanDao.upsert(photoKeys.map { FaceScanEntity(account, it) })
            true
        }
    }

    /**
     * Writes one scanned photo's faces ([faces], possibly none) and its scan marker together, in one
     * transaction, only while [fenceToken] still holds ([writeFence], asked inside the transaction so no
     * import can commit between the check and the write). Both land or neither does: a photo is never
     * recorded scanned without its faces, nor are its faces left without the marker that keeps a later pass
     * from detecting over them (and blanking any names an import has put on them since). Returns whether it
     * wrote; a database failure propagates.
     */
    private suspend fun storePhotoScan(
        userId: UserId?,
        photoKey: String,
        faces: List<FaceEntity>,
        fenceToken: Long,
    ): Boolean {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        return appDatabase.withTransaction {
            if (!writeFence.allows(fenceToken)) return@withTransaction false
            if (faces.isNotEmpty()) faceDao.upsert(faces)
            faceScanDao.upsert(listOf(FaceScanEntity(account, photoKey)))
            true
        }
    }

    /** The find-more sweep's [storePhotoScan]: one swept photo's faces and its hi-res mark, together and
     *  behind the same fence. Returns whether it wrote; a database failure propagates. */
    private suspend fun storeSweptPhoto(
        userId: UserId?,
        photoKey: String,
        faces: List<FaceEntity>,
        fenceToken: Long,
    ): Boolean {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        return appDatabase.withTransaction {
            if (!writeFence.allows(fenceToken)) return@withTransaction false
            if (faces.isNotEmpty()) faceDao.upsert(faces)
            faceScanDao.markHiResScanned(account, listOf(photoKey))
            true
        }
    }

    /**
     * Suspends until no walk pass is running. A pass stands down at its next per-photo check once the walk
     * is paused or stopped, but first finishes the photos already in flight, and a full-resolution
     * download can stretch that to a couple of minutes, so there is no timeout; the wait is cancellable.
     * Pause ([setPaused]) first, or the next trigger simply starts another pass. The finished photos land
     * before the caller moves on, and the walk's model sessions are closed by then. It does not keep a
     * later write off anything: that is [withImportFence]'s job.
     */
    suspend fun awaitIdle() {
        walkRunning.first { !it }
    }

    /**
     * Runs [block], an import that writes face data (a face snapshot restore, a face index import), fenced
     * against this scheduler: every scan and grouping that took its token before [block] returns writes
     * nothing more, whatever it had in flight, so faces read before the import are never written back over
     * it (a re-detected face would otherwise come back with no name, not removed and in no person). Wrap
     * the importer call itself, after pausing the walk and [awaitIdle]; the import must write through the
     * app database's own transactions, as the importers do. Afterwards group the imported faces
     * ([regroupNow] or [requestRecluster]) and restart the walk if it was running.
     */
    suspend fun <T> withImportFence(block: suspend () -> T): T {
        writeFence.enterImport()
        // The saved leftover count described the faces as they were before this import.
        runCatching { stateStore.delete() }
        try {
            return block()
        } finally {
            writeFence.exitImport()
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
            restart.lastUserId?.let { uid -> scope.launch { runCatching { indexAll(uid) } } }
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
            restart.request()
            runCatching { indexAll(userId) }
        }
    }

    /** Re-run clustering now (e.g. right after the user named or merged a person) so the name attracts
     *  its other faces without waiting for the next scan pass. Forced, so it runs even while paused, though
     *  not while names wait to be reattached, short of their grace ([regroupAllowed]). */
    fun requestRecluster(userId: UserId?) {
        scope.launch { runCatching { cluster(userId, RegroupTrigger.REQUEST, force = true) { !walkRunning.value } } }
    }

    /**
     * A full regroup now, forced like [requestRecluster] but awaited, for a caller that reports once the
     * people are rebuilt (an import). Queued behind any grouping already running, like every other one.
     * Returns whether a regroup completed, so false while names wait to be reattached, short of their grace
     * ([regroupAllowed]).
     */
    suspend fun regroupNow(userId: UserId?): Boolean =
        cluster(userId, RegroupTrigger.REQUEST, force = true) { !walkRunning.value }

    /**
     * Scan just the photo now on screen, so the viewer can offer a face to tag before the background
     * walk has reached it. A no-op when AI is off or the photo is already scanned, so a faceless photo
     * is only ever looked at once. Net-free: the photo would be scanned in the background regardless, so
     * this only brings it forward. Runs regardless of the pause switch, since it answers a direct action
     * on the photo the user is looking at, and serialises with any running walk through [mlLock] inside
     * the scan. Returns true when it scanned the photo (whether or not a face was found).
     */
    suspend fun indexPhotoOnDemand(item: GalleryItem, userId: UserId?, force: Boolean = false): Boolean {
        // Taken before anything is read, as a walk pass takes its own: an import that begins meanwhile
        // turns this scan's writes away (see [writeFence]).
        val fenceToken = writeFence.token()
        val prefs = context.settingsDataStore.data.first()
        val enabled = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
        if (!enabled) return false
        // Adopt a guest's face data before this path writes the first account-owned row: the viewer can
        // reach here right after sign-in, ahead of the walk, and a fresh account marker would otherwise
        // flip the walk's later migration to DISCARD. Idempotent + transaction-guarded, so racing the
        // walk's own call is safe.
        userId?.let { acct ->
            runCatching {
                val result = migrateGuestFaceData.invoke(acct.id)
                FaceDiagnostics.recordMigration(result.action.name, result.people, result.faces)
            }.onFailure { Log.w(TAG, "guest face adopt failed: ${it.message}") }
        }
        // The walk marks every photo scanned, so a plain call would skip them all. A forced call (the
        // user asked, from the viewer) re-scans regardless, at a lower confidence so it can catch a face
        // the precision-first walk left behind. The few extra weak detections are on this one photo and
        // the user reviews them.
        if (!force && faceScanDao.isScanned(userId?.id ?: PhotoLocationEntity.LOCAL_USER, item.stableId)) return false
        val detFile = (faceModelManager.prepare() as? FaceModelPreparation.Ready)?.file ?: return false
        val embFile = (faceEmbeddingModelManager.prepare() as? FaceModelPreparation.Ready)?.file ?: return false
        // Same abandon-on-timeout holder as the walk: a single on-demand inference that exceeds the ML
        // watchdog on a slow device abandons its (wedged) pair rather than closing a session mid-run, and a
        // caller that leaves the viewer mid-run defers the close below until that run returns.
        val ml = FaceMl(
            {
                if (force) FaceDetector(detFile, scoreThreshold = 0.5f, inputSize = 640, dispatcher = mlDispatcher)
                else FaceDetector(detFile, dispatcher = mlDispatcher)
            },
            { FaceEmbedder(embFile, dispatcher = mlDispatcher) },
        )
        // The photo's faces are written with its marker inside the scan; this collects only a marker the
        // scan records without a source (an unreadable device file).
        val markerOnly = ConcurrentHashMap.newKeySet<String>()
        val recorded = try {
            scanOnePhoto(item, userId, ml, fenceToken, AtomicInteger(2), null, markerOnly, worker = "ondemand", force = force)
        } finally {
            ml.close()
        }
        if (!recorded) return false
        if (markerOnly.isNotEmpty()) {
            try {
                storeScanMarkers(userId, markerOnly, fenceToken)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "on-demand scan marker failed: ${e.message}")
            }
        }
        // Give the fresh faces person ids so the viewer can show and tag them. The incremental path places
        // them against the cached people first (assign-to-existing, no rebuild); it runs regardless of the
        // pause switch since it is plain DB work, matching the full rebuild it falls back to. That fallback
        // runs even while background indexing is paused (the common state after the first scan), since
        // leaving the freshly scanned faces with a null person id would keep the viewer's grouped-faces read
        // empty and the photo would read as "no faces". Not while names wait to be reattached, though, short
        // of their grace ([regroupAllowed]): the faces then stay unplaced until the reattach puts the names
        // back and groups. Under the grouping lock, like every grouping.
        groupingLock.withLock {
            // A scan still running owes the closing steps of a batched grouping to the run that ends it.
            val finalPass = !walkRunning.value
            if (!tryAssignNewFaces(userId, finalPass) &&
                regroupAllowed(
                    force = true,
                    active = active(),
                    reattachPending = reattachPending(),
                    reattachOverdue = reattachOverdueNow(),
                )
            ) {
                regroupLocked(userId, RegroupTrigger.ON_DEMAND, finalPass)
            }
        }
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
        // Taken before the candidate set is read: an import that begins meanwhile turns the sweep's
        // remaining writes away (see [writeFence]).
        val fenceToken = writeFence.token()
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
        // A reassignable pair like the walk's: a per-item ML timeout abandons the wedged instances and
        // opens a fresh pair rather than closing a session under a live native call.
        val ml = FaceMl(
            { FaceDetector(detFile, scoreThreshold = 0.6f, inputSize = 640, dispatcher = mlDispatcher) },
            { FaceEmbedder(embFile, dispatcher = mlDispatcher) },
        )
        val cursor = AtomicInteger(0)
        val done = AtomicInteger(0)
        // Faces the sweep persisted this run, so it clusters once at the end only when it actually
        // added something (a run over truly faceless photos writes nothing and skips the recluster).
        val newFaces = AtomicInteger(0)
        try {
            coroutineScope {
                repeat(WORKER_COUNT) {
                    launch {
                        // Like the walk, the sweep opens no more pairs once this process has abandoned its cap.
                        while (isActive && !abandonCap.reached()) {
                            val i = cursor.getAndIncrement()
                            if (i >= items.size) break
                            val item = items[i]
                            val photoKey = item.stableId
                            val source = runCatching { loadSource(item, userId, AtomicInteger(2), force = true) }.getOrNull()
                            if (source != null) {
                                // Under the lock: detect + embed, build the rows to persist and the
                                // matches to stream. The DB writes happen after the lock is released, so
                                // the mlLock only ever holds native inference, exactly as the walk does.
                                val (rows, matches) = mlLock.withLock {
                                    // Lease the current pair inside the lock: a timeout below abandons
                                    // exactly this pair, and the next lock holder opens the fresh one. The
                                    // lease is released when the detached run ends, so the pair is never
                                    // closed under it, even once this sweep is cancelled mid-run.
                                    val lease = ml.beginRun()
                                    val detector = lease.value.detector
                                    val embedder = lease.value.embedder
                                    val completed = boundedRun(ML_TIMEOUT_MS, mlDispatcher, onRunEnd = lease.release) {
                                        try {
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
                                        } finally {
                                            // The detached run owns [source] and recycles it here on a
                                            // normal, thrown, or timed-out finish, so a leaked in-flight run
                                            // keeps a valid bitmap rather than reading a recycled one.
                                            if (!source.isRecycled) source.recycle()
                                        }
                                    }
                                    if (completed == null) {
                                        FaceDiagnostics.recordWatchdogTimeout()
                                        // The run is still inside an uncancellable native call on this pair;
                                        // abandon it (it closes only once that run returns) so the next item
                                        // opens a fresh pair.
                                        abandonWedgedPair(ml)
                                    }
                                    completed ?: (emptyList<FaceEntity>() to emptyList<FaceSweepEvent.Match>())
                                }
                                // Persist every detected face as a normal index row and drop this photo
                                // from the faceless set (marked hi-res swept whether or not a face was
                                // found, so a truly faceless photo is never re-swept), so it surfaces for
                                // the right person on its own and a later sweep never looks at it again.
                                // Written after the lock so the DB I/O never holds up another worker's
                                // inference, in one fenced transaction like the walk's. A photo whose write
                                // did not land offers no match, since there is no stored face behind it.
                                val stored = try {
                                    storeSweptPhoto(userId, photoKey, rows, fenceToken)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    Log.w(TAG, "sweep write failed: ${e.message}")
                                    false
                                }
                                if (stored) {
                                    newFaces.addAndGet(rows.size)
                                    matches.forEach { send(it) }
                                }
                            }
                            send(FaceSweepEvent.Progress(done.incrementAndGet(), items.size))
                        }
                    }
                }
            }
        } finally {
            // Leaving the screen cancels the sweep while a detached run may still be inside the pair; the
            // close then waits for that run to return rather than freeing its sessions under it.
            runCatching { ml.close() }
        }
        // The sweep added faces to photos the walk had left faceless, so group them once the sweep
        // drains: a newly persisted face then surfaces under the right person on its own, the same way
        // the background walk clusters from its drained branch. Forced, since "Find more photos" is a
        // user action that must group its results even while background indexing is paused. Skipped
        // when nothing was persisted.
        if (newFaces.get() > 0) cluster(userId, RegroupTrigger.ON_DEMAND, force = true) { !walkRunning.value }
    }.flowOn(Dispatchers.Default)

    /**
     * Stop any running walk and drop the session's indexing state. Called on sign-out just before the
     * face rows are wiped, so the walk is already standing down when the wipe lands, and whatever a scan
     * or a grouping still has in flight is fenced off ([writeFence]), so none of it writes a row for the
     * account that is leaving (nor over a rescan's wipe). A restart held for the walk's tail goes too, so
     * that tail starts no pass for the account that is leaving.
     */
    suspend fun reset() {
        stopRequested = true
        writeFence.invalidate()
        retryKick.cancel()
        retryBook.clear()
        runCatching { stateStore.delete() }
        stateRestoredFor = null
        restart.reset()
        triedReattachStamp = null
        lastFailedRegroupMs = null
        lastRegroupEndMs = null
        lastRegroupDurationMs = 0L
        _progress.value = FaceIndexingProgress(FaceIndexingState.Idle, 0, 0)
        FaceDiagnostics.clear()
        runCatching { context.settingsDataStore.edit { it.remove(SettingsKeys.FACE_INDEXING_PAUSED) } }
    }

    /**
     * Re-detect the whole library at the current detector while KEEPING the user's names and exclusions.
     * The names, "not this person" marks and removed faces are snapshotted by face-box geometry BEFORE the
     * wipe, exactly as a model-version migration does, so the re-detected library carries them back once
     * [reattachPendingLabels] runs on the drained pass. Names still waiting from an earlier wipe or import
     * are not on the faces yet, so the capture is merged into their snapshot rather than written over it.
     * Faces, scan markers and the account's exclusions are cleared (the exclusions return from the
     * snapshot); the person rows and the name-keyed manual attachments are left in place. A snapshot that
     * cannot be written leaves the faces as they are, since the wipe would take every name with it. A fresh
     * walk then re-detects, reattaches the names by geometry and regroups. Guest-safe: a null userId
     * rescans the local partition.
     */
    suspend fun rescan(userId: UserId?) = withContext(NonCancellable) {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        reset()
        val secured = runCatching { captureReattachSnapshot(account) }
            .onFailure { Log.w(TAG, "rescan snapshot failed, faces kept: ${it.message}") }
            .isSuccess
        if (secured) {
            // One transaction: faces gone with their scan markers kept would leave every photo marked as
            // scanned and nothing to re-detect.
            appDatabase.withTransaction {
                faceDao.clearForUser(account)
                faceScanDao.clearForUser(account)
                notPersonDao.clearForUser(account)
            }
        }
        requestIndex(userId)
    }

    /**
     * Wipes every detected face, person, scan marker and correction for the account and starts a fresh walk
     * (unless [thenIndex] is false). The tables go in ONE transaction and the whole call outlives the screen
     * that asked for it: cleared one table at a time from a screen's own scope, leaving that screen stopped
     * the wipe half-way, with the faces gone, the scan markers kept (so nothing was ever scanned again) and the
     * people still listed.
     */
    suspend fun wipeFaceData(userId: UserId?, thenIndex: Boolean = true) = withContext(NonCancellable) {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        reset()
        appDatabase.withTransaction {
            faceDao.clearForUser(account)
            personDao.clearForUser(account)
            faceScanDao.clearForUser(account)
            personManualPhotoDao.clearForUser(account)
            notPersonDao.clearForUser(account)
            appDatabase.personCoverDao().clearForUser(account)
            appDatabase.clusterSummaryDao().clearForUser(account)
        }
        if (thenIndex) requestIndex(userId)
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
        // A leftover whose content cannot be read has nothing to resume: taken for a pending snapshot, it
        // would let the wipe below run with no name kept, so it goes and the live faces, if any, are
        // captured instead.
        FaceReattachSnapshot.discardIfUnreadable(reattachFile)
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
        // The leftover count of the last regroup described faces that are gone now, and every name waiting
        // on disk is off the faces again until the next reattach.
        regroupBaseline = null
        retryBook.clear()
        runCatching { stateStore.delete() }
        triedReattachStamp = null
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
     * Whether names wait in [reattachFile] that no reattach has run over yet, which holds every full regroup
     * back ([regroupAllowed], [reattachHoldsGrouping]). Answered from the file's stamp alone, cheap enough
     * for every pass end and grouping request: each write lands whole (a temp file renamed over it), so a
     * file on disk is a complete snapshot, and one whose content still cannot be read is discarded by the
     * next reattach.
     */
    private fun reattachPending(): Boolean =
        reattachHoldsGrouping(FaceReattachSnapshot.stamp(reattachFile), triedReattachStamp)

    /** Whether the names in [reattachFile] have waited past [REATTACH_FORCED_REGROUP_GRACE_MS], so a forced
     *  regroup no longer waits for them ([reattachOverdue]). */
    private fun reattachOverdueNow(): Boolean =
        reattachOverdue(reattachFile.lastModified(), System.currentTimeMillis(), REATTACH_FORCED_REGROUP_GRACE_MS)

    /**
     * Snapshot the model-independent curation a wipe would orphan (which face, in which photo, where),
     * so a model swap or a rescan does not lose it. Captures the named faces, the "not this person" marks
     * and the removed ("not a person") faces, each with its box so it rebinds by geometry after a detector
     * swap shifts the positional ids. Every read here joins the live face table, so it must run before the
     * wipe. Names still waiting in a snapshot on disk are not on these faces yet, so the capture is merged
     * into it ([FaceReattachSnapshot.merge], the capture winning where both speak for the same face) rather
     * than written over it; nothing captured and nothing waiting leaves no file. Throws when the snapshot
     * cannot be read or written, leaving what was on disk.
     */
    private suspend fun captureReattachSnapshot(account: String) {
        val nameById = personDao.namedPeopleForUser(account)
            .mapNotNull { p -> p.displayName?.takeIf { it.isNotBlank() }?.let { p.id to it } }
            .toMap()
        val members = readReattachMembers(faceDao, account, nameById)
        val nots = notPersonDao.facesForUser(account).map {
            ReattachLabel(it.faceId, it.photoKey, it.boxLeft, it.boxTop, it.boxRight, it.boxBottom, it.personName)
        }
        val rejected = faceDao.rejectedFacesForUser(account).map {
            ReattachBox(it.photoKey, it.boxLeft, it.boxTop, it.boxRight, it.boxBottom)
        }
        val captured = FaceReattachData(members, emptyList(), nots, rejected)
        withContext(Dispatchers.IO) {
            FaceReattachSnapshot.update(reattachFile) { waiting ->
                waiting?.let { FaceReattachSnapshot.merge(under = it, over = captured) } ?: captured
            }
        }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "captured ${members.size} labels, ${nots.size} nots, ${rejected.size} rejected before wipe")
        }
    }

    /**
     * Put a pending snapshot's names and exclusions back on the re-detected library, and let go of what went
     * back. Members bind to the re-detected faces by [bindReattachLabels] (same id first, then box overlap)
     * and get the name as a manual label the clusterer then groups under; manual attachments are restored
     * under their names, the "not this person" marks rebind to the new faces the same way, and the removed
     * ("not a person") faces are re-rejected on whichever new face lands in their box. Runs just before a
     * regroup, so the names are in place and the exclusions are honoured when the groups form. Everything is
     * applied in one transaction, and only while [fenceToken] (the pass's) still holds: an import since that
     * pass read the library leaves the snapshot as it is rather than applied over the imported faces.
     *
     * Once that transaction has committed, only what found a face leaves the snapshot, with what never can:
     * an entry on a photo already re-detected with no face for it, or, when [library] (a complete read of the
     * drained library) is given, on a photo it no longer holds. The rest waits for a later pass, so a name
     * whose photo has not been scanned yet is never lost. The snapshot changes only while it still holds what
     * was read, so names an import or a rescan merged in meanwhile wait whole; a crash part way through
     * resumes from it. A snapshot whose content cannot be read is discarded instead, so it never holds
     * grouping back for good. Returns true when it put anything back or let anything go, which calls for a
     * regroup; a pass with nothing to change returns false.
     */
    private suspend fun reattachPendingLabels(userId: UserId?, fenceToken: Long, library: List<GalleryItem>?): Boolean {
        val pending = try {
            FaceReattachSnapshot.load(reattachFile)
        } catch (e: IOException) {
            // Not a fault in its content: it stays for the next reattach, and grouping waits with it.
            Log.w(TAG, "reattach snapshot could not be opened: ${e.message}")
            return false
        }
        val data = when (pending) {
            is ReattachLoad.Loaded -> pending.data
            ReattachLoad.Absent -> return false
            ReattachLoad.Unreadable -> {
                runCatching { FaceReattachSnapshot.discardIfUnreadable(reattachFile) }
                    .onFailure { Log.w(TAG, "unreadable reattach snapshot kept: ${it.message}") }
                return false
            }
        }
        val account = accountOf(userId)
        val localFaces = readReattachFaces(faceDao, account, data)
        val boundMembers = bindReattachLabels(data.members, localFaces)
        val boundNots = if (data.nots.isNotEmpty()) bindReattachLabels(data.nots, localFaces) else emptyMap()
        // Re-apply the removed faces by box overlap, at the same threshold the named-face reattach uses:
        // the detector swap changed the positional ids, so the removal follows the geometry onto the new
        // face and does not silently lapse.
        val boundBoxes = if (data.rejected.isNotEmpty()) {
            bindRejectedBoxes(data.rejected, localFaces, REATTACH_MIN_IOU)
        } else {
            emptyList()
        }
        val applied = appDatabase.withTransaction {
            if (!writeFence.allows(fenceToken)) return@withTransaction false
            boundMembers.entries.groupBy({ it.value.name }, { it.key }).forEach { (name, ids) ->
                faceDao.labelFacesByIds(ids, name)
            }
            if (data.manual.isNotEmpty()) {
                personManualPhotoDao.add(data.manual.map { PersonManualPhotoEntity(account, it.name, it.photoKey) })
            }
            if (boundNots.isNotEmpty()) {
                notPersonDao.add(boundNots.map { (faceId, label) -> NotPersonEntity(account, label.name, faceId) })
            }
            if (boundBoxes.isNotEmpty()) faceDao.rejectByIds(boundBoxes.map { it.second })
            true
        }
        if (!applied) return false
        val remainder = FaceReattachSnapshot.remainder(
            data = data,
            applied = FaceReattachData(
                members = boundMembers.values.toList(),
                manual = data.manual,
                nots = boundNots.values.toList(),
                rejected = boundBoxes.map { data.rejected[it.first] },
            ),
            settledPhotos = unplaceablePhotos(account, data, library),
        )
        runCatching {
            if (FaceReattachSnapshot.consumeIfUnchanged(reattachFile, data, remainder)) {
                triedReattachStamp = FaceReattachSnapshot.stampIfHolds(reattachFile, remainder)
            }
        }.onFailure { Log.w(TAG, "reattach snapshot kept whole: ${it.message}") }
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "reattached ${boundMembers.size} labels, ${data.manual.size} manual, " +
                    "${boundNots.size} nots, ${boundBoxes.size} rejected; ${remainder.members.size} labels wait",
            )
        }
        return remainder != data
    }

    /**
     * The photos among [data]'s where an entry the reattach left unplaced can never be placed: the ones
     * already scanned (re-detected, with no face for it), and, given a complete read of the drained library
     * ([library]), the ones it no longer holds. A marker read that fails counts none, so nothing goes.
     */
    private suspend fun unplaceablePhotos(
        account: String,
        data: FaceReattachData,
        library: List<GalleryItem>?,
    ): Set<String> {
        val keys = HashSet<String>()
        data.members.mapTo(keys) { it.photoKey }
        data.nots.mapTo(keys) { it.photoKey }
        data.rejected.mapTo(keys) { it.photoKey }
        if (keys.isEmpty()) return emptySet()
        val scanned = runCatching { faceScanDao.scannedKeysAmong(account, keys) }
            .getOrElse { return emptySet() }
        val settled = scanned.toHashSet()
        if (library != null) {
            val held = HashSet<String>()
            for (item in library) if (item.stableId in keys) held += item.stableId
            keys.filterTo(settled) { it !in held }
        }
        return settled
    }

    /** Detect + embed every face in one photo and write them with its scan marker (see [scanOnePhoto]),
     *  counting a written photo in [stored], so a later run skips it whether or not it held a face, and
     *  filing what its load came to in [tally]. Bounded source in, recycled on the way out. */
    private suspend fun indexOne(
        item: GalleryItem,
        userId: UserId?,
        ml: FaceMl,
        fenceToken: Long,
        decryptBudget: AtomicInteger,
        tally: SourcePassTally,
        scannedKeys: MutableSet<String>,
        stored: AtomicInteger,
        worker: String,
        facesFound: AtomicInteger,
        acceptedScores: MutableList<Float>?,
        droppedTooBlurry: AtomicInteger,
        acceptedBlur: MutableList<Float>?,
    ) {
        if (!active()) return
        // Background face indexing yields the process-global crypto gate to interactive decrypts; the
        // on-demand and faceless-sweep paths run scanOnePhoto/loadSource directly and stay foreground.
        withContext(DecryptPriorityContext(DecryptPriority.BACKGROUND)) {
            scanOnePhoto(item, userId, ml, fenceToken, decryptBudget, tally, scannedKeys, worker = worker, stored = stored, facesFound = facesFound, acceptedScores = acceptedScores, droppedTooBlurry = droppedTooBlurry, acceptedBlur = acceptedBlur)
        }
    }

    /**
     * The per-photo scan itself, without the walk's active() gate, so an on-demand call can run it for
     * the photo on screen even while the background walk is paused or idle.
     *
     * A photo whose source loads is written right after its detection, its faces and its scan marker in
     * one transaction behind [fenceToken] ([storePhotoScan]), and counted in [stored]. A photo recorded
     * scanned without a source (or whose faces failed to write) goes to [scannedKeys] for the caller to
     * persist. Returns true when the photo is recorded either way, false when it stays pending: no source
     * this time, or the fence turned its write away. A walk's [tally] (null for an on-demand scan) learns
     * whether the photo moved on or why it stayed pending.
     */
    private suspend fun scanOnePhoto(
        item: GalleryItem,
        userId: UserId?,
        ml: FaceMl,
        fenceToken: Long,
        decryptBudget: AtomicInteger,
        tally: SourcePassTally?,
        scannedKeys: MutableSet<String>,
        worker: String,
        force: Boolean = false,
        stored: AtomicInteger? = null,
        facesFound: AtomicInteger? = null,
        acceptedScores: MutableList<Float>? = null,
        droppedTooBlurry: AtomicInteger? = null,
        acceptedBlur: MutableList<Float>? = null,
    ): Boolean {
        val photoKey = item.stableId
        // Name the item this worker is on right now, so a scan that freezes is legible in the copied
        // diagnostics instead of a blind spot. Cleared on the way out, whichever branch returns.
        val label = inFlightLabel(item)
        FaceDiagnostics.recordInFlight(worker, label)
        try {
            // The load path (decode, cloud download, thumbnail decrypt) can wedge on a pathological item;
            // loadSource bounds each blocking call and flips this when one runs past its watchdog, so the
            // caller can tell a true wedge from a plain defer (off Wi-Fi or out of budget). Time the load
            // and gather the cloud fetch metrics so the diagnostics ring shows per-item load / dl cost.
            val loadTimedOut = AtomicBoolean(false)
            val loadMetrics = LoadMetrics()
            val missNote = SourceMissNote()
            val loadStart = SystemClock.elapsedRealtime()
            val loaded = loadSource(item, userId, decryptBudget, force, loadTimedOut, loadMetrics, missNote)
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            val source = loaded ?: run {
                // A device-only item that opens but decodes to neither an image nor a video frame is a
                // corrupt or unsupported local file with no cloud original to try on a later pass. Record
                // it scanned rather than leave it pending forever: once only such items remained, a pass
                // would load nothing, the walk would not re-kick, and the count would stand still at
                // "Running" for good. A cloud item the load reports permanently unavailable is recorded
                // the same way, so it never again spends a pass's budget. A decode or decrypt that wedged
                // past its watchdog (loadTimedOut) is recorded too, so a deterministically hanging source
                // is skipped next pass rather than re-hung. Any other miss stays pending: a device file that
                // could not be opened (the card unmounted) or a transient cloud failure waits out its
                // backoff, a defer (off Wi-Fi or out of budget) waits for the next pass.
                val miss = missNote.reason.let {
                    if (item is GalleryItem.LocalOnly) settledLocalMiss(it, tally?.attempts(photoKey) ?: 0) else it
                }
                if (shouldMarkScannedOnNullSource(loadTimedOut.get(), miss)) {
                    if (item is GalleryItem.LocalOnly) FaceDiagnostics.recordUnloadableLocal()
                    val permanent = item !is GalleryItem.LocalOnly && miss == SourceMiss.PERMANENT
                    if (permanent) FaceDiagnostics.recordPermanentSkip()
                    tally?.movedOn(photoKey, permanent)
                    scannedKeys.add(photoKey)
                    return true
                }
                tally?.stayed(photoKey, miss, SystemClock.elapsedRealtime())
                return false
            }
            tally?.movedOn(photoKey)
            // The faces the detection accepts, built inside the bounded run and written below by this worker
            // once the run is over and the ML lock is free, so the database write never holds up another
            // photo's inference nor counts against the inference watchdog. Guarded by its own monitor: a run
            // the watchdog abandons may still add to it after the worker has taken what was there.
            val rows = ArrayList<FaceEntity>()
            // Bound the native inference like the video-frame decode is bounded: one wedged ONNX run must not
            // pin the shared lock and freeze every worker forever. On a timeout control returns here, mlLock
            // releases, and the item keeps only the faces found before the timeout (usually none) so the walk
            // moves on. The run cannot be cancelled once native, so on a timeout the wedged pair is ABANDONED
            // (closed only once that run returns) and the next item opens a fresh pair (see [FaceMl]); the lease
            // below binds THIS run to the current pair so the abandon retires exactly it, and no later run shares
            // a session with the wedged one. The detached block owns [source] and recycles it in its own
            // finally, so a leaked in-flight run keeps a valid bitmap rather than reading a recycled one.
            var inferMs = 0L
            var mlFailure: Throwable? = null
            try {
                mlLock.withLock {
                    // A walk photo that waited here while the process abandoned its cap of pairs opens no
                    // new one: it stays pending for the next process.
                    if (tally != null && abandonCap.reached()) {
                        if (!source.isRecycled) source.recycle()
                        return false
                    }
                    val inferStart = SystemClock.elapsedRealtime()
                    // Lease the current pair inside the lock: a timeout below abandons exactly this pair, and the
                    // next lock holder opens the fresh one. The lease is released when the detached run ends, so
                    // the pair is never closed under it, even once this caller is cancelled mid-run.
                    val lease = ml.beginRun()
                    val detector = lease.value.detector
                    val embedder = lease.value.embedder
                    val completed = boundedRun(ML_TIMEOUT_MS, mlDispatcher, onRunEnd = lease.release) {
                        try {
                            val faces = detector.detect(source)
                            // Count every face the detector returned, before the blur / size gates drop any,
                            // so a "0 people found" report separates no-faces-in-library from
                            // faces-but-no-cluster.
                            FaceDiagnostics.recordFacesDetected(faces.size)
                            for ((index, face) in faces.withIndex()) {
                                val aligned = FaceAlignment.alignFace(source, face.landmarks)
                                val blur = runCatching { alignedBlur(aligned) }.getOrNull()
                                // Hard quality gate: drop an extremely blurred crop before it costs an embed or
                                // a stored row, mirroring the detector's min-size gate. The floor stays
                                // conservative so only unusable faces are lost while the accepted-blur log
                                // calibrates where to raise it.
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
                                // Store the box as a 0..1 fraction of the (EXIF-oriented) source, so a cover
                                // crops correctly with no need for the photo's pixel dimensions later. A
                                // cloud-only cover has no stored dimensions, so a pixel box could not be
                                // normalised and the whole group photo showed instead of the one face.
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
                                synchronized(rows) { rows.add(entity) }
                                facesFound?.incrementAndGet()
                                if (BuildConfig.DEBUG) {
                                    acceptedScores?.add(face.score)
                                    blur?.let { acceptedBlur?.add(it) }
                                }
                            }
                            true
                        } finally {
                            // The detached run is the sole owner of [source]: it recycles here on a normal or
                            // thrown finish, and on a timeout it keeps [source] until its wedged native call
                            // returns, so the bitmap is never recycled out from under a live read. The worker
                            // does not recycle it.
                            if (!source.isRecycled) source.recycle()
                        }
                    }
                    if (completed == null) {
                        FaceDiagnostics.recordWatchdogTimeout()
                        // The detached run is still inside an uncancellable native call on this pair; abandon
                        // it (it closes only once that run returns) so the next item opens a fresh pair.
                        abandonWedgedPair(ml)
                    }
                    inferMs = SystemClock.elapsedRealtime() - inferStart
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                mlFailure = e
            }
            // Record the photo even when its detection threw (an OOM on a crop, a bad frame), with whatever
            // faces came before, so a poison photo is not re-decoded and retried on every later pass; a
            // face-free photo is likewise recorded once. The faces and the marker land together, and not at
            // all once an import or a sign-out has fenced this scan off: the photo then stays pending for a
            // later pass, which reads the data as it is by then.
            val found = synchronized(rows) { ArrayList(rows) }
            val recorded = try {
                storePhotoScan(userId, photoKey, found, fenceToken).also { if (it) stored?.incrementAndGet() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The faces could not be written: still record the photo scanned, as one with no face,
                // rather than fetch and detect it again on every later pass.
                Log.w(TAG, "face write failed: ${e.message}")
                scannedKeys.add(photoKey)
                true
            }
            mlFailure?.let { throw it }
            // Append this processed item to the diagnostics ring (newest-first) and fold it into the run
            // aggregates, so the next tester copy shows per-item load / infer / dl timings and the slowest
            // item rather than only a standing count.
            FaceDiagnostics.recordProcessed(
                label = label,
                isVideo = itemIsVideo(item),
                sizeBytes = itemSizeBytes(item),
                loadMs = loadMs,
                inferMs = inferMs,
                downloadMs = loadMetrics.downloadMs,
                downloadedBytes = loadMetrics.downloadedBytes,
            )
            return recorded
        } finally {
            FaceDiagnostics.clearInFlight(worker)
        }
    }

    /**
     * A bounded source bitmap for [item]. A device file (LocalOnly, or the local twin of a Synced pair)
     * is decoded straight off the local original with no crypto. A cloud photo is detected from its
     * full-resolution image on Wi-Fi (downloaded, decoded bounded, then deleted right away); off Wi-Fi
     * or once the per-pass budget is spent it returns null so the photo is deferred to a later pass
     * rather than indexed from a low-detail thumbnail. A cloud video falls back to its still-frame
     * thumbnail, the only bitmap a video container yields. A cloud load that returns null notes why in
     * [miss].
     */
    private suspend fun loadSource(
        item: GalleryItem,
        userId: UserId?,
        decryptBudget: AtomicInteger,
        force: Boolean = false,
        timedOut: AtomicBoolean = AtomicBoolean(false),
        metrics: LoadMetrics = LoadMetrics(),
        miss: SourceMissNote = SourceMissNote(),
    ): Bitmap? = when (item) {
        // A device video decodes to no bitmap through BitmapFactory, so fall through to a still frame;
        // an image takes the first branch and never opens the retriever.
        is GalleryItem.LocalOnly -> loadLocalOnly(item.local.uri, timedOut, miss)
        // The cloud fallback stays account-bound: a guest has only device items, so it is never reached.
        is GalleryItem.Synced -> decodeLocalBounded(item.local.uri, timedOut)
            ?: decodeLocalVideoFrame(item.local.uri)
            ?: userId?.let { decodeCloud(item.cloud, it, decryptBudget, force, timedOut, metrics, miss) }
        is GalleryItem.CloudOnly -> userId?.let { decodeCloud(item.cloud, it, decryptBudget, force, timedOut, metrics, miss) }
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
        timedOut: AtomicBoolean = AtomicBoolean(false),
        metrics: LoadMetrics = LoadMetrics(),
        miss: SourceMissNote = SourceMissNote(),
    ): Bitmap? =
        if (photo.mimeType.startsWith("video/")) decodeCloudBounded(photo.linkId, userId, decryptBudget, timedOut, miss)
        else decodeCloudFullRes(photo, userId, decryptBudget, force, timedOut, metrics, miss)

    /**
     * Detect a cloud image from its full-resolution bytes. On Wi-Fi and while the per-pass budget
     * allows, the same download+decrypt the viewer uses ([PhotoDownloadService.downloadFullResPhoto])
     * writes the file into the `fullres` cache; it is decoded bounded and then deleted right away, so
     * the library is never stored on disk. Off Wi-Fi, out of budget, or on a download failure it
     * returns null and the photo waits for a later pass: a low-detail thumbnail face is exactly what
     * the full-res path is here to avoid. The download itself is serialised through [downloadLock] so
     * only one is ever in flight, and each spends one unit of the shared per-pass budget. A null return
     * notes why in [miss]: a download the service reports unavailable, or an original the decoder cannot
     * read, is permanent; any other failure is transient.
     */
    private suspend fun decodeCloudFullRes(
        photo: CloudPhoto,
        userId: UserId,
        decryptBudget: AtomicInteger,
        force: Boolean = false,
        timedOut: AtomicBoolean = AtomicBoolean(false),
        metrics: LoadMetrics = LoadMetrics(),
        miss: SourceMissNote = SourceMissNote(),
    ): Bitmap? {
        // A forced pass (the user asked, from the viewer) downloads this one photo regardless of the
        // Wi-Fi-only rule and the walk's own stop/pause state; both exist to keep the BACKGROUND walk
        // from flooding data or crypto, which a single deliberate request is not. The Wi-Fi-only rule is
        // the user's "full-res on Wi-Fi only" setting, so once that is off the walk fetches over mobile
        // data too instead of stalling at the on-device photos.
        if (!force && fullresWifiOnly && !networkObserver.currentlyOnWifi()) return miss.giveUp(SourceMiss.DEFERRED)
        if (!takeBudget(decryptBudget)) return miss.giveUp(SourceMiss.OVER_BUDGET)
        // A download timeout is transient (a slow or stalled link), unlike a deterministic decode or
        // decrypt wedge: it gets its OWN flag, kept OFF [timedOut], so the item is left PENDING for a later
        // pass to retry when the network is better rather than marked permanently scanned. Only the decode
        // below feeds [timedOut] (a file that will not decode this pass will not decode next pass either).
        val downloadTimedOut = AtomicBoolean(false)
        val downloadError = AtomicReference<Throwable?>(null)
        val file = downloadLock.withPermit {
            if (!force && !active()) return miss.giveUp(SourceMiss.DEFERRED)
            // Bound the full-res download so a stalled link frees the worker rather than pinning it.
            // Generous, since a large original on a slow link can be legitimately slow. Time just the
            // download and note the bytes it fetched for the diagnostics ring; only a produced file counts.
            val dlStart = SystemClock.elapsedRealtime()
            boundedRun(DOWNLOAD_TIMEOUT_MS, Dispatchers.IO, downloadTimedOut) {
                val produced = runCatching { photoDownloadService.downloadFullResPhoto(userId, photo) }
                    .onFailure { downloadError.set(it) }
                    .getOrNull()
                // A download that lands after the watchdog gave up has no consumer, so this detached block
                // deletes it here rather than leave an orphan in the fullres cache.
                if (produced != null && downloadTimedOut.get()) {
                    runCatching { produced.delete() }
                    null
                } else {
                    produced
                }
            }?.also {
                metrics.downloadMs = SystemClock.elapsedRealtime() - dlStart
                metrics.downloadedBytes = runCatching { it.length() }.getOrDefault(0L)
            }
        } ?: return miss.giveUp(sourceMissForDownloadFailure(downloadError.get()))
        val unreadable = AtomicBoolean(false)
        return try {
            decodeFileBounded(file, timedOut, unreadable)
                ?: miss.giveUp(sourceMissForUndecodedDownload(unreadable.get()))
        } finally {
            runCatching { file.delete() }
        }
    }

    /** A device-only photo's source: the decoded image, else a video's still frame. When neither comes, the
     *  miss says whether the file is unreadable for good or merely could not be read now. */
    private suspend fun loadLocalOnly(uri: String, timedOut: AtomicBoolean, miss: SourceMissNote): Bitmap? {
        val threw = AtomicBoolean(false)
        return decodeLocalBounded(uri, timedOut, threw)
            ?: decodeLocalVideoFrame(uri)
            ?: miss.giveUp(sourceMissForUndecodedLocal(readable = isLocalReadable(uri), decodeThrew = threw.get()))
    }

    private suspend fun isLocalReadable(uri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false }
            .getOrDefault(false)
    }

    /** A bounded decode of a device file, run detached under a watchdog so a decode that wedges frees the
     *  worker and flips [timedOut] rather than pinning it. [threw] flips when the decode threw. */
    private suspend fun decodeLocalBounded(uri: String, timedOut: AtomicBoolean, threw: AtomicBoolean? = null): Bitmap? =
        boundedRun(DECODE_TIMEOUT_MS, Dispatchers.IO, timedOut) { decodeLocalBlocking(uri, threw) }

    private fun decodeLocalBlocking(uri: String, threw: AtomicBoolean? = null): Bitmap? = runCatching {
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
    }.getOrElse { threw?.set(true); null }

    /** A bounded decode of a cache file, run detached under a watchdog so a decode that wedges frees the
     *  worker and flips [timedOut] rather than pinning it. [unreadable] as for [decodeFileBlocking]. */
    private suspend fun decodeFileBounded(file: File, timedOut: AtomicBoolean, unreadable: AtomicBoolean? = null): Bitmap? =
        boundedRun(DECODE_TIMEOUT_MS, Dispatchers.IO, timedOut) { decodeFileBlocking(file, unreadable) }

    /** Flips [unreadable] when the decoder read the file and could make no image of it (a format it does
     *  not support, or corrupt bytes), as opposed to a decode that threw (out of memory) or a file gone. */
    private fun decodeFileBlocking(file: File, unreadable: AtomicBoolean? = null): Bitmap? = runCatching {
        if (!file.exists() || file.length() <= 0L) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            if (file.exists()) unreadable?.set(true)
            return@runCatching null
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: run {
            if (file.exists()) unreadable?.set(true)
            return@runCatching null
        }
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
     * falls back to the Type 1 path so it still indexes. A row still missing its cipher material spends
     * no budget. A null return notes why in [miss].
     */
    private suspend fun decodeCloudBounded(
        linkId: String,
        userId: UserId,
        decryptBudget: AtomicInteger,
        timedOut: AtomicBoolean = AtomicBoolean(false),
        miss: SourceMissNote = SourceMissNote(),
    ): Bitmap? {
        // Warm HD wins: best detail, no crypto, no budget.
        val hdCached = cloudHdThumbFile(linkId)
        if (hdCached.exists() && hdCached.length() > 0L) return decodeFileBounded(hdCached, timedOut)

        // The cipher material comes off the listing row, read before any budget is spent: a row still
        // missing it (a later listing refresh fills it in) has nothing to decrypt, so it costs the pass
        // no budget and waits out a backoff, still indexing off a warm Type 1 thumbnail meanwhile.
        val entity = runCatching { photoListingDao.getByLinkId(linkId) }.getOrNull()
        val contentKeyPacket = entity?.contentKeyPacket
        val encNodeKey = entity?.encNodeKey
        val encNodePass = entity?.encNodePassphrase
        val parentLinkId = entity?.parentLinkId
        if (entity == null || contentKeyPacket == null || encNodeKey == null || encNodePass == null || parentLinkId == null) {
            val warmT1 = cloudThumbFile(linkId)
            return if (warmT1.exists() && warmT1.length() > 0L) decodeFileBounded(warmT1, timedOut) else null
        }

        // Cold path is budgeted. Once the per-pass budget is spent, still index off a warm Type 1
        // thumbnail the gallery already made (free), else leave the photo for a later pass.
        if (!takeBudget(decryptBudget)) {
            val warmT1 = cloudThumbFile(linkId)
            return if (warmT1.exists() && warmT1.length() > 0L) {
                decodeFileBounded(warmT1, timedOut)
            } else {
                miss.giveUp(SourceMiss.OVER_BUDGET)
            }
        }

        // Spend the budget unit on the HD decrypt (fresh Type 2 url + the shared node/session decrypt).
        // Bounded like the rest of the load path: a decrypt that wedges frees the worker and flips
        // [timedOut] rather than pinning it.
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

        // No Type 2 thumbnail (older upload) or a transient HD failure: fall back to the Type 1 path so
        // the photo still indexes. A warm Type 1 file decodes straight off disk; a cold one rides the
        // gallery's bounded Type 1 decrypt.
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
     *  enough detail for detection without ever holding a full-resolution bitmap. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        val longEdge = maxOf(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= SOURCE_MAX_EDGE) sample *= 2
        return sample
    }

    /** True while a walk may keep going: not stopping, AI on, not paused. */
    private fun active(): Boolean = !stopRequested && aiEnabled && !paused

    /** Starts the pass a walk's tail owes [userId], only while it is still the account last asked for and
     *  still the signed-in one, or the guest while none is ([WalkRestart.mayRestart]). An account read that
     *  fails starts nothing. */
    private suspend fun restartIfCurrent(userId: UserId?) {
        val primary = runCatching { accountManager.get().getPrimaryUserId().first() }.getOrElse { return }
        if (restart.mayRestart(userId, primary)) runCatching { indexAll(userId) }
    }

    private fun setIdle() {
        _progress.value = FaceIndexingProgress(FaceIndexingState.Idle, 0, 0)
    }

    /**
     * Run [block] detached and wait at most [timeoutMs] for it, so a blocking or wedged call (a native
     * ONNX inference, a bitmap decode, a cloud download, a decrypt) frees this worker instead of pinning
     * it forever: one pathological item then releases the shared lock and the walk moves to the next.
     * Returns the block's result, or null when the block itself returned null. On a true timeout (the
     * work is still running when the wait elapses) it flips [timedOut], cancels the wait, and returns
     * null; the abandoned work keeps running on its own detached coroutine (a wedged native call cannot
     * be cancelled) and finishes or dies on its own. An abandoned ONNX run keeps using its session, so the
     * ML caller ABANDONS that detector / embedder and opens a fresh pair for the next item (see [FaceMl]);
     * that reallocation, not buffer isolation, is what keeps two runs off the one session. [onRunEnd] runs
     * exactly once when the detached run itself is over (see [boundedDetachedRun]), which is how an ML
     * run releases its pair's lease. The caller's decrypt priority rides into the detached context so a
     * background load stays background at the crypto gate. This is [decodeLocalVideoFrame]'s
     * bounded-decode pattern, generalised.
     */
    private suspend fun <T> boundedRun(
        timeoutMs: Long,
        dispatcher: CoroutineDispatcher,
        timedOut: AtomicBoolean? = null,
        onRunEnd: (() -> Unit)? = null,
        block: suspend CoroutineScope.() -> T,
    ): T? = boundedDetachedRun(
        scope,
        dispatcher + (coroutineContext[DecryptPriorityContext] ?: EmptyCoroutineContext),
        timeoutMs,
        timedOut,
        onRunEnd,
        block,
    )

    /** What kind of item [item] is, for the copied diagnostics' in-flight pointer and per-item lines: where
     *  it lives and nothing that identifies it (no key, id, path or token, not even a fragment of one),
     *  since testers often post those diagnostics publicly. */
    private fun inFlightLabel(item: GalleryItem): String = when (item) {
        is GalleryItem.CloudOnly -> "cloud"
        is GalleryItem.Synced -> "synced"
        is GalleryItem.LocalOnly -> "local"
    }

    /** Whether [item] is a video, for the diagnostics ring's img / vid tag. */
    private fun itemIsVideo(item: GalleryItem): Boolean = when (item) {
        is GalleryItem.CloudOnly -> item.cloud.mimeType.startsWith("video/")
        is GalleryItem.Synced -> item.cloud.mimeType.startsWith("video/")
        is GalleryItem.LocalOnly -> item.local.mimeType.startsWith("video/")
    }

    /** [item]'s byte size, for the diagnostics ring's per-item size. */
    private fun itemSizeBytes(item: GalleryItem): Long = when (item) {
        is GalleryItem.CloudOnly -> item.cloud.sizeBytes
        is GalleryItem.Synced -> item.cloud.sizeBytes
        is GalleryItem.LocalOnly -> item.local.sizeBytes
    }

    /** Runs [block], and in a debug build logs how long it took under [label], so the ONNX session-open
     *  cost (the model load) is visible. A release build folds the timing away. */
    private inline fun <T> debugTimed(label: String, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val start = SystemClock.elapsedRealtime()
        return block().also { Log.i(TAG, "$label session init ${SystemClock.elapsedRealtime() - start}ms") }
    }

    /**
     * Try the incremental fast path, by a caller holding [groupingLock]: place only the newly indexed
     * faces against the cached people, without a whole-library rebuild. Returns true when it handled the
     * pass (so the caller skips the full rebuild), false when a full rebuild is needed (first run, a stale
     * cache, a large backlog), when it failed, or when the fence turned its write away (an import or a
     * sign-out since it read the faces). A failure is swallowed and reported as "needs a full rebuild" so
     * the caller falls back cleanly, exactly as [regroupLocked] swallows-and-continues; cancellation still
     * propagates. [finalPass] says whether a batched run may take its closing steps (see
     * [placeBacklogInBatches]): false while a scan is still going on.
     */
    private suspend fun tryAssignNewFaces(userId: UserId?, finalPass: Boolean): Boolean {
        val fenceToken = writeFence.token()
        // A library past the whole-rebuild limit is grouped in batches at every pass, so its people grow
        // as the scan goes and a backlog of any size is never turned away to a rebuild that cannot run.
        val faceCount = runCatching { faceDao.countForUser(accountOf(userId)) }.getOrDefault(0)
        if (groupsInBatchesOnly(faceCount, FULL_REGROUP_MAX_FACES)) {
            // Only faces beyond what the last batched run left ungrouped on purpose (the weak ones that
            // matched nobody) are new work, so a launch that finds nothing new does not read them all again,
            // unless a run that ends the walk still owes the closing steps an earlier run left.
            val ungrouped = runCatching { faceDao.unclusteredCount(accountOf(userId)) }.getOrDefault(0)
            if (!batchedRunDue(ungrouped, currentRegroupBaseline(userId), currentFinalizeOwed(userId), finalPass)) {
                // Nothing new to place, but names put back on the faces since the last run (a restore, an
                // import) still have to reach their groups: one aggregate, cheap enough to run every time.
                nameBatchedGroups(userId, fenceToken)
                return true
            }
            return placeBacklogInBatches(userId, fenceToken, finalPass)
        }
        return try {
            clusterFacesUseCase.assignNewFaces(userId) { writeFence.allows(fenceToken) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "incremental assign failed, falling back to full cluster: ${e.message}")
            false
        }
    }

    /**
     * The route for a library too big for the whole-library rebuild's heap: places the ungrouped faces
     * against the people already held, [INCREMENTAL_BATCH_SIZE] at a time, so a big library still gains
     * people while the full rebuild keeps being retried after its backoff. A caller holds [groupingLock].
     * Each batch is its own fenced transaction, so an import or a sign-out stops the next one; a failure
     * ends the pass quietly, since the same faces are tried again at the next one.
     *
     * Faces left grouped under a person a curation edit has since removed are put back among the ungrouped
     * ones first, so the batches place them again. The people matched against stay in memory across the
     * batches of the run; once it has opened many people since its last merge, it folds the groups that are
     * one person together between two batches ([midRunMergeDue]), so the people each batch is matched against
     * stay few enough to keep a batch cheap. Only a run that ends a walk ([finalPass]) fills the Unsorted
     * bucket; a run that does not, or whose closing merge or Unsorted step failed, leaves them owed
     * ([RegroupBaseline.finalizeOwed]) to the next run that ends a walk.
     */
    private suspend fun placeBacklogInBatches(userId: UserId?, fenceToken: Long, finalPass: Boolean): Boolean {
        val account = accountOf(userId)
        val startMs = SystemClock.elapsedRealtime()
        var batches = 0
        var placed = 0
        var created = 0
        var merged = 0
        guardedGroupingStep("releasing the faces of removed people") {
            clusterFacesUseCase.releaseOrphanedFaces(userId) { writeFence.allows(fenceToken) }
        }
        // A big backlog with no scan running to host it goes on the foreground service, reporting the faces
        // looked at so far, so it keeps running when the app leaves the screen and its notification moves.
        val progressBefore = _progress.value
        // A run that does not end a walk looks only at the faces stored since the last one: the faces it left
        // ungrouped on purpose are not read and matched again at every pass, which on a big library took
        // minutes each time. The run that ends the walk reads everything once.
        val startRow = if (finalPass) 0L else currentGroupedUpToRow(userId)
        var runMaxRow = 0L
        val backlogAtStart = runCatching { faceDao.unclusteredCountSince(account, startRow) }.getOrDefault(0)
        val hosted = shouldHostGroupingInForeground(
            ungrouped = backlogAtStart,
            backgroundEnabled = context.settingsDataStore.data.first()[SettingsKeys.FACE_INDEX_BACKGROUND] != false,
            walkRunning = progressBefore.state == FaceIndexingState.Running,
        )
        // A walk that starts while this run is hosted owns the progress from then on: this run keeps beating its
        // heartbeat but writes no count and puts nothing back over the walk's.
        fun hostsProgress() = hosted && !walkRunning.value
        if (hosted) {
            _progress.value = progressOf(FaceIndexingState.Running, 0, backlogAtStart, grouping = true)
            FaceIndexingService.start(context, userId, triggersWalk = false)
        }
        var examined = 0
        var progressTotal = backlogAtStart
        var sweepsDone = false
        var centroids = 0
        try {
            var openedSinceMerge = 0
            var batchesSinceMerge = 0
            for (sweep in 1..MAX_BATCH_SWEEPS) {
                val batch = IncrementalBatch(INCREMENTAL_BATCH_SIZE).also { it.minRow = startRow }
                // The count only ever climbs, across sweeps too: the service that hosts it stops itself once
                // the count stands still for two minutes, so a later sweep reports its faces on top of the
                // ones already looked at, with the total growing by what that sweep has still to read.
                val examinedBeforeSweep = examined
                val sweepBacklog = if (sweep == 1) backlogAtStart
                    else runCatching { faceDao.unclusteredCountSince(account, startRow) }.getOrDefault(0)
                progressTotal = examinedBeforeSweep + sweepBacklog
                while (true) {
                    coroutineContext.ensureActive()
                    batch.scanned = 0
                    val createdBefore = batch.created
                    val handled = clusterFacesUseCase.assignNewFaces(userId, batch) { writeFence.allows(fenceToken) }
                    if (!handled) return false
                    batches++
                    examined += batch.scanned
                    runMaxRow = maxOf(runMaxRow, batch.maxRow)
                    centroids = batch.centroidCount
                    openedSinceMerge += batch.created - createdBefore
                    batchesSinceMerge++
                    if (hostsProgress()) {
                        _progress.value = progressOf(FaceIndexingState.Running, examined, progressTotal, grouping = true)
                    } else {
                        beat()
                    }
                    if (batches % BATCHED_PROGRESS_LOG_EVERY == 0) {
                        noteBatchedProgress(batches, examined, created + batch.created, centroids, startMs)
                    }
                    if (batches % BATCHED_MARK_SAVE_EVERY == 0) saveBatchedMark(userId, fenceToken, runMaxRow)
                    if (batch.scanned < batch.limit) break
                    if (midRunMergeDue(openedSinceMerge, batchesSinceMerge)) {
                        // Named first, so two groups whose faces carry different names never fuse unnamed.
                        nameBatchedGroups(userId, fenceToken)
                        merged += guardedGroupingStep("merging between batches") {
                            clusterFacesUseCase.mergeSimilarGroups(userId, { writeFence.allows(fenceToken) }, onTick = { beat() })
                        } ?: 0
                        // The merge rewrote the people the batches hold, so the next batch reads them again.
                        batch.forgetCentroids()
                        openedSinceMerge = 0
                        batchesSinceMerge = 0
                    }
                    yield()
                }
                placed += batch.placed
                created += batch.created
                // A second pass only when the first opened people, since only a new person can take a face
                // an earlier batch found nobody for.
                if (batch.created == 0) break
            }
            sweepsDone = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "batched incremental assign failed: ${e.message}")
            return false
        } finally {
            // Left as it is when the sweeps finished: the closing steps below are still the hosted work, and
            // a state that stopped reading Running would end the service before they run.
            if (hostsProgress() && !sweepsDone) _progress.value = progressBefore
        }
        // The steps the whole-library rebuild ends with, done on the groups the batches formed: give the groups
        // whose faces already carry a confirmed name that name, fold together the groups that are one person
        // (on the centroids) and name what the merge left unnamed, then route the faces nobody took to the
        // Unsorted bucket. Named before the merge, so two groups carrying different confirmed names are never
        // fused before either has its name. Only the run that ends a walk does the merge and the bucket: both
        // are final steps over everything the scan found, and a face put in the bucket is no longer offered to
        // the people a later run opens. A ticker keeps the work visible through these quiet steps: the count
        // climbing while a service reports it, the heartbeat otherwise.
        val ticker = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(FINALIZE_TICK_MS)
                if (hostsProgress()) {
                    // The count stands still through these steps; the heartbeat each post bumps shows the host the work moves.
                    _progress.value = progressOf(
                        FaceIndexingState.Running, examined, maxOf(progressTotal, examined), grouping = true,
                    )
                } else {
                    beat()
                }
            }
        }
        var named = 0
        var unsorted = 0
        var closingFailed = false
        try {
            named += nameBatchedGroups(userId, fenceToken) ?: 0
            if (finalPass) {
                val folded = guardedGroupingStep("merging the batched groups") {
                    clusterFacesUseCase.mergeSimilarGroups(userId, { writeFence.allows(fenceToken) }, onTick = { beat() })
                }
                if (folded == null) closingFailed = true else merged += folded
                named += nameBatchedGroups(userId, fenceToken) ?: 0
                // The counts an older build left wrong, set once from the faces now held. The batches keep every
                // count right from here on (a new person starts with its photos, a person that gains faces moves
                // on by the photos it gains), so the read of every face this takes is not repeated at each pass end.
                val recountDone = account in context.settingsDataStore.data.first()[SettingsKeys.FACE_COUNTS_RECOUNTED].orEmpty()
                if (!recountDone) {
                    val recounted = guardedGroupingStep("recounting the people's photos") {
                        clusterFacesUseCase.recountPhotoCounts(userId) { writeFence.allows(fenceToken) }
                    }
                    if (recounted == null) closingFailed = true
                    else if (writeFence.allows(fenceToken)) {
                        context.settingsDataStore.edit {
                            it[SettingsKeys.FACE_COUNTS_RECOUNTED] = it[SettingsKeys.FACE_COUNTS_RECOUNTED].orEmpty() + account
                        }
                    }
                }
                val moved = guardedGroupingStep("filling the Unsorted bucket") {
                    clusterFacesUseCase.moveLeftoverToUnsorted(userId) { writeFence.allows(fenceToken) }
                }
                if (moved == null) closingFailed = true else unsorted = moved
            }
        } finally {
            ticker.cancel()
            if (hostsProgress()) _progress.value = progressBefore
        }
        // What is left ungrouped now is what this run could not place (weak faces, faces matching nobody)
        // and, after a final run, nothing: the baseline the pass end measures new faces against, so a later
        // pass does not read the same faces all again.
        val leftover = runCatching { faceDao.unclusteredCount(account) }.getOrDefault(0)
        regroupBaseline = RegroupBaseline(
            account, leftover, fenceToken,
            finalizeOwed = !finalPass || closingFailed,
            groupedUpToRow = maxOf(currentGroupedUpToRow(userId), runMaxRow),
        )
        saveWalkState(userId)
        val tookMs = SystemClock.elapsedRealtime() - startMs
        noteBatchedProgress(batches, examined, created, centroids, startMs)
        FaceDiagnostics.recordBatchedRun(batches, placed, created, merged, named, unsorted, leftover, tookMs)
        SyncDiagnostics.log(
            "faces: batched placement $batches batches, $placed faces placed, $created people opened, " +
                "$merged merged, $named named, $unsorted to Unsorted, $leftover left ungrouped (${tookMs}ms)",
        )
        return true
    }

    /**
     * Records, every few batches, how far a batched run has looked, so a run the process loses part-way (the
     * app closed, the phone restarted) resumes from there instead of reading every ungrouped face again. The
     * closing steps stay owed, and the ungrouped baseline is whatever the last completed run left, or none.
     * Called under [groupingLock].
     */
    private fun saveBatchedMark(userId: UserId?, fenceToken: Long, runMaxRow: Long) {
        val previous = currentBaseline(userId)
        regroupBaseline = RegroupBaseline(
            accountOf(userId), previous?.ungrouped ?: 0, fenceToken,
            finalizeOwed = true,
            groupedUpToRow = maxOf(previous?.groupedUpToRow ?: 0L, runMaxRow),
        )
        saveWalkState(userId)
    }

    /** Notes how far a batched run that began at [startMs] has come, in the copied diagnostics: counts only. */
    private fun noteBatchedProgress(batch: Int, examined: Int, opened: Int, centroids: Int, startMs: Long) {
        val elapsedMs = SystemClock.elapsedRealtime() - startMs
        FaceDiagnostics.recordBatchedProgress(batch, examined, opened, centroids, elapsedMs)
        SyncDiagnostics.log(
            "faces: batched progress batch $batch, $examined faces examined, $opened people opened, " +
                "$centroids people matched against (${elapsedMs}ms)",
        )
    }

    /** Puts the confirmed names on the groups whose faces carry them ([ClusterFacesUseCase.nameGroupsFromConfirmedFaces]),
     *  returning how many it named, or null when it fails. */
    private suspend fun nameBatchedGroups(userId: UserId?, fenceToken: Long): Int? =
        guardedGroupingStep("naming the batched groups") {
            clusterFacesUseCase.nameGroupsFromConfirmedFaces(userId) { writeFence.allows(fenceToken) }
        }

    /** Runs one closing step of a batched run, giving null when it fails so one step failing leaves the rest
     *  to run and the caller can tell a failure from a step with nothing to do; cancellation still propagates. */
    private suspend fun guardedGroupingStep(what: String, step: suspend () -> Int): Int? = try {
        step()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "$what failed: ${e.message}")
        null
    }

    /**
     * Group every stored face into people while the walk is still permitted to run (a forced call runs
     * regardless), queued behind any grouping already running ([groupingLock]), and not while names wait
     * to be reattached, a forced call short of their grace ([regroupAllowed]); see [regroupLocked].
     * [finalPass] is asked once the lock is held, so a scan that started or ended while this waited counts.
     */
    private suspend fun cluster(
        userId: UserId?,
        trigger: RegroupTrigger,
        force: Boolean = false,
        finalPass: () -> Boolean,
    ): Boolean {
        if (!regroupAllowed(force, active(), reattachPending(), reattachOverdueNow())) return false
        return groupingLock.withLock {
            // Checked again once the lock is held: the walk may have been paused, or names staged by an
            // import or a rescan, while this waited.
            if (!regroupAllowed(force, active(), reattachPending(), reattachOverdueNow())) false
            else regroupLocked(userId, trigger, finalPass())
        }
    }

    /**
     * One full regroup, by a caller holding [groupingLock]. A clustering failure is logged and swallowed
     * so it never fails the walk; cancellation still propagates. A timeout or a failure stamps
     * [lastFailedRegroupMs], so the pass-end grouping backs off; a completed one records how many faces it
     * left ungrouped as the new [regroupBaseline]. Its rewrite is fenced ([writeFence]) by a token taken
     * before it reads the faces, so a regroup of the faces as they were before an import never lands after
     * it. Returns true only when a grouping actually completed (not timed out, failed, or turned away), so
     * the caller can record that a parameter generation was applied. Each run's [trigger] and outcome, and
     * the library totals after it, go to the copied diagnostics. Where it groups in batches, [finalPass]
     * says whether that run may take its closing steps ([placeBacklogInBatches]).
     *
     * A regroup holds every stored embedding on the heap at once, so one that the free heap cannot hold is
     * skipped before it reads a face: it is recorded, stamps [lastFailedRegroupMs] like a failure so the
     * pass-end grouping retries after the usual backoff, and returns false. Across the rest it holds the
     * shared model gate so a semantic walk cannot load its model meanwhile: the walk's pass-end grouping
     * passes [gateHeld] since its pass already holds it; any other caller takes it only when it is free,
     * without waiting, since it already holds [groupingLock] and the walk takes the gate before that lock.
     */
    private suspend fun regroupLocked(
        userId: UserId?,
        trigger: RegroupTrigger,
        finalPass: Boolean,
        gateHeld: Boolean = false,
    ): Boolean {
        val fenceToken = writeFence.token()
        // An import is writing right now: a rebuild would read every stored embedding only to be turned
        // away, so leave it to the grouping the import's caller runs once it is done.
        if (!writeFence.allows(fenceToken)) return false
        val faceCount = runCatching { faceDao.countForUser(accountOf(userId)) }.getOrDefault(0)
        // A big library is never regrouped whole: it is grouped in batches, with no heap check to pass, no
        // failure to back off from and nothing to time out, and a batched run that finishes counts as the
        // grouping having completed (the params generation is recorded from it).
        if (groupsInBatchesOnly(faceCount, FULL_REGROUP_MAX_FACES)) {
            // A curation edit or a repeated request finds nothing new beyond the last run's leftover: the
            // groups were edited in place, and the next batched run rebuilds the cache it clears. A run that
            // ends a walk still takes the closing steps an earlier run left owed.
            val ungrouped = runCatching { faceDao.unclusteredCount(accountOf(userId)) }.getOrDefault(0)
            if (!batchedRunDue(ungrouped, currentRegroupBaseline(userId), currentFinalizeOwed(userId), finalPass)) {
                nameBatchedGroups(userId, fenceToken)
                return true
            }
            val completed = placeBacklogInBatches(userId, fenceToken, finalPass)
            saveWalkState(userId)
            pushLibraryCounts(userId)
            return completed
        }
        if (!regroupFitsHeapNow(regroupHeapNeedBytes(faceCount))) {
            lastFailedRegroupMs = SystemClock.elapsedRealtime()
            placeBacklogInBatches(userId, fenceToken, finalPass)
            saveWalkState(userId)
            return false
        }
        val tookGate = !gateHeld && mlWalkGate.tryAcquire(MlRail.FACE)
        try {
            return regroupHoldingMemory(userId, trigger, fenceToken, finalPass)
        } finally {
            if (tookGate) mlWalkGate.release(MlRail.FACE)
        }
    }

    /**
     * Whether a regroup needing [needBytes] fits the heap still free. The used figure counts garbage not yet
     * collected, so a first no is asked again after a collection before the regroup is skipped; a skip is
     * recorded with the need and the free heap it found.
     */
    private fun regroupFitsHeapNow(needBytes: Long): Boolean {
        val runtime = Runtime.getRuntime()
        val used = { runtime.totalMemory() - runtime.freeMemory() }
        if (regroupFitsHeap(needBytes, runtime.maxMemory(), used())) return true
        runtime.gc()
        val usedAfter = used()
        if (regroupFitsHeap(needBytes, runtime.maxMemory(), usedAfter)) return true
        Log.w(TAG, "regroup skipped: needs ${needBytes / MIB}MiB, ${(runtime.maxMemory() - usedAfter) / MIB}MiB free")
        FaceDiagnostics.recordRegroupHeapSkip(needBytes, runtime.maxMemory() - usedAfter)
        return false
    }

    /** The body of [regroupLocked], run once the heap check has passed and the gate is settled. */
    private suspend fun regroupHoldingMemory(
        userId: UserId?,
        trigger: RegroupTrigger,
        fenceToken: Long,
        finalPass: Boolean,
    ): Boolean {
        FaceDiagnostics.recordRegroupStarted(trigger)
        val grouped = try {
            // Hard ceiling on a single clustering round. The clusterer is bounded and cancellable
            // (MAX_CLUSTERS + a polled cancellation check), so this only fires in a pathological case;
            // when it does, the in-transaction rebuild rolls back and the walk carries on rather than
            // hanging. withTimeoutOrNull absorbs its own timeout, so it never reads as a walk cancel.
            val startMs = SystemClock.elapsedRealtime()
            val outcome = withTimeoutOrNull(CLUSTER_TIMEOUT_MS) {
                clusterFacesUseCase(userId) { writeFence.allows(fenceToken) }
            }
            when {
                outcome == null -> {
                    Log.w(TAG, "clustering timed out after ${CLUSTER_TIMEOUT_MS}ms, skipped this round")
                    lastFailedRegroupMs = SystemClock.elapsedRealtime()
                    FaceDiagnostics.recordRegroupTimedOut()
                    // The timed-out rebuild wrote nothing, so the faces are still waiting: place them in
                    // batches now rather than leaving them for a rebuild that has just failed.
                    placeBacklogInBatches(userId, fenceToken, finalPass)
                    false
                }
                // Turned away by the fence: an import or a sign-out replaced the faces it read, so it wrote
                // nothing. Not a failure, so no backoff; whoever moved the fence groups afterwards.
                !outcome.committed -> false
                else -> {
                    val endMs = SystemClock.elapsedRealtime()
                    lastRegroupDurationMs = endMs - startMs
                    lastRegroupEndMs = endMs
                    regroupBaseline = RegroupBaseline(accountOf(userId), outcome.leftUngrouped, fenceToken, finalizeOwed = false)
                    FaceDiagnostics.recordRegroupCompleted(durationMs = endMs - startMs, endMs = endMs)
                    if (BuildConfig.DEBUG) {
                        // The debug threshold sweep reads per-account samples through the non-null
                        // diagnostic path; a guest still clusters above, it just skips this
                        // measurement-only log.
                        userId?.let { logClusterSweep(it) }
                    }
                    true
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "clustering failed: ${e.message}")
            lastFailedRegroupMs = SystemClock.elapsedRealtime()
            FaceDiagnostics.recordRegroupFailed(e)
            false
        }
        saveWalkState(userId)
        pushLibraryCounts(userId)
        return grouped
    }

    /**
     * Takes back, once per process and account, what the last process saved: the photos waiting out a
     * retry and how the full regroups were spaced, so a restart does not begin them again. Whatever it
     * cannot read counts as nothing saved. The leftover count is taken back under the fence token now in
     * force, so it lasts only until an import, a sign-out or a rescan moves the fence, as a measured one
     * does.
     */
    private fun restoreWalkState(account: String) {
        if (stateRestoredFor == account) return
        stateRestoredFor = account
        val saved = runCatching { stateStore.read(account) }.getOrNull() ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()
        retryBook.restore(saved.retries, nowMs, nowEpoch)
        if (lastFailedRegroupMs == null) {
            lastFailedRegroupMs = saved.lastFailedRegroupEpochMs?.let { elapsedFromEpoch(it, nowMs, nowEpoch) }
        }
        if (lastRegroupEndMs == null) {
            lastRegroupEndMs = saved.lastRegroupEndEpochMs?.let { elapsedFromEpoch(it, nowMs, nowEpoch) }
            lastRegroupDurationMs = saved.lastRegroupDurationMs
        }
        if (regroupBaseline == null) {
            saved.baselineUngrouped?.let {
                regroupBaseline = RegroupBaseline(account, it, writeFence.token(), saved.finalizeOwed, saved.groupedUpToRow)
            }
        }
    }

    /** Saves what [restoreWalkState] takes back. Best effort: a failed write costs only the backoff. */
    private fun saveWalkState(userId: UserId?) {
        val account = accountOf(userId)
        if (stateRestoredFor != account) return
        runCatching {
            val nowMs = SystemClock.elapsedRealtime()
            val nowEpoch = System.currentTimeMillis()
            stateStore.write(
                MlWalkState(
                    account = account,
                    retries = retryBook.export(nowMs, nowEpoch),
                    lastFailedRegroupEpochMs = lastFailedRegroupMs?.let { epochFromElapsed(it, nowMs, nowEpoch) },
                    lastRegroupEndEpochMs = lastRegroupEndMs?.let { epochFromElapsed(it, nowMs, nowEpoch) },
                    lastRegroupDurationMs = lastRegroupDurationMs,
                    baselineUngrouped = currentRegroupBaseline(userId),
                    finalizeOwed = currentFinalizeOwed(userId),
                    groupedUpToRow = currentGroupedUpToRow(userId),
                ),
            )
        }.onFailure { Log.w(TAG, "walk state save failed: ${it.message}") }
    }

    /** The last completed regroup's leftover count for [userId]'s faces while the face data it measured is
     *  still current (no import, sign-out or rescan has moved [writeFence] since), else null. */
    private fun currentRegroupBaseline(userId: UserId?): Int? = currentBaseline(userId)?.ungrouped

    /** Whether the last batched run over [userId]'s faces, while its baseline is still current, left its
     *  closing steps owed. */
    private fun currentFinalizeOwed(userId: UserId?): Boolean = currentBaseline(userId)?.finalizeOwed == true

    /** The last stored face row a batched run has looked at while the baseline is current, else 0 (read all). */
    private fun currentGroupedUpToRow(userId: UserId?): Long = currentBaseline(userId)?.groupedUpToRow ?: 0L

    private fun currentBaseline(userId: UserId?): RegroupBaseline? =
        regroupBaseline?.takeIf { it.account == accountOf(userId) && writeFence.allows(it.fenceToken) }

    /** Lowers [userId]'s regroup baseline to [ungrouped] faces seen waiting by a grouping that stopped short
     *  of a full regroup ([lowerRegroupBaseline]). Called under [groupingLock]. */
    private fun lowerBaselineTo(userId: UserId?, ungrouped: Int) {
        val current = regroupBaseline?.takeIf { it.account == accountOf(userId) } ?: return
        val lowered = lowerRegroupBaseline(current.ungrouped, ungrouped) ?: return
        if (lowered != current.ungrouped) {
            regroupBaseline = RegroupBaseline(
                current.account, lowered, current.fenceToken, current.finalizeOwed, current.groupedUpToRow,
            )
        }
    }

    /** The face-data partition [userId] owns: the account id, or the local partition for a guest. */
    private fun accountOf(userId: UserId?): String = userId?.id ?: PhotoLocationEntity.LOCAL_USER

    /** Moves [FaceIndexingProgress.heartbeat] on, keeping the rest of the progress as it is, so a host sees
     *  a grouping at work while the scanned count stands still. */
    private fun beat() {
        val next = heartbeat.incrementAndGet()
        _progress.update { it.copy(heartbeat = maxOf(it.heartbeat, next)) }
    }

    /** [progress] set to [state], [indexed] and [total], carrying the latest beat. */
    private fun progressOf(state: FaceIndexingState, indexed: Int, total: Int, grouping: Boolean = false): FaceIndexingProgress =
        FaceIndexingProgress(state, indexed, total, heartbeat.incrementAndGet(), grouping)

    /** Whether a full regroup may start at [nowMs] as far as the spacing after the last one goes
     *  ([fullRegroupCooledDown]). */
    private fun regroupCooledDown(nowMs: Long): Boolean = fullRegroupCooledDown(
        msSinceLastRegroup = lastRegroupEndMs?.let { nowMs - it },
        lastRegroupDurationMs = lastRegroupDurationMs,
        minIntervalMs = INCREMENTAL_CLUSTER_MIN_INTERVAL_MS,
        dutyFactor = FULL_REGROUP_DUTY_FACTOR,
    )

    /**
     * Read the account's library totals into the copied diagnostics: stored faces, faces not yet grouped,
     * people (the single Unsorted bucket excluded) and named people. Each read is guarded, so one that
     * fails prints as unknown and never fails the caller. Skipped once a sign-out has asked the walk to
     * stop, so the next account never inherits these totals, and when cancelled, so a cancelled read
     * never overwrites the last good ones.
     */
    private suspend fun pushLibraryCounts(userId: UserId?) {
        if (stopRequested) return
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        val faces = runCatching { faceDao.countForUser(account) }.getOrDefault(-1)
        val ungrouped = runCatching { faceDao.unclusteredCount(account) }.getOrDefault(-1)
        val people = runCatching {
            personDao.countForUser(account) - (if (personDao.otherPersonId(account) != null) 1 else 0)
        }.getOrDefault(-1)
        val named = runCatching { personDao.namedCountForUser(account) }.getOrDefault(-1)
        if (!coroutineContext.isActive) return
        FaceDiagnostics.recordLibraryCounts(faces, ungrouped, people, named)
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

    /** Abandons [ml]'s wedged pair (see [FaceMl.abandon]) and counts it against [abandonCap], recording the
     *  process's total and whether it now halts the walk. */
    private fun abandonWedgedPair(ml: FaceMl) {
        ml.abandon()
        val count = abandonCap.recordAbandon()
        FaceDiagnostics.recordAbandonedSessions(count, halted = abandonCap.reached())
        if (abandonCap.reached()) Log.w(TAG, "$count model pairs abandoned, background scan stops until the next start")
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

    /**
     * Holds a walk's or an on-demand call's detector + embedder as a reassignable pair, so a per-item ML
     * timeout can abandon the wedged pair and open a fresh one. A native run cannot be cancelled and keeps
     * using its ONNX sessions after its caller has gone (the watchdog gave up, or the caller itself was
     * cancelled), so a pair is never closed under a run (a native use-after-free). Every detached run
     * holds a lease on its pair ([beginRun]) that its completion releases; [abandon] and [close] both
     * retire the live pair, which closes at once when idle, else when its last run returns. An abandoned
     * pair therefore closes itself once its wedged run ends, and the next [beginRun] opens a fresh pair,
     * so no later run shares a session with the wedged one.
     */
    private class FaceMl(
        openDetector: () -> FaceDetector,
        openEmbedder: () -> FaceEmbedder,
    ) : AutoCloseable {
        private val pairs = GuardedSessionHolder { FacePair(openDetector, openEmbedder) }

        /** Opens the live pair if needed and counts one run on it. The run's end must call the lease's
         *  release, which [boundedRun] does through its run-end callback. */
        fun beginRun(): SessionLease<FaceRun> =
            pairs.lease { pair -> FaceRun(pair.detector(), pair.embedder()) }

        /** The live detector's min-size drop count for the debug pass-end log; 0 before it opens. */
        fun droppedTooSmall(): Int = pairs.peek()?.droppedTooSmall() ?: 0

        /** A run on the live pair timed out: retire it (closed when that run ends) so the next run opens a
         *  fresh one. */
        fun abandon() = pairs.retire()

        override fun close() = pairs.retire()
    }

    /** One detached run's view of a [FaceMl] pair. */
    private class FaceRun(val detector: FaceDetector, val embedder: FaceEmbedder)

    /** A detector + embedder opened lazily and closed together once no run uses them (see [FaceMl]). */
    private class FacePair(
        private val openDetector: () -> FaceDetector,
        private val openEmbedder: () -> FaceEmbedder,
    ) : AutoCloseable {
        @Volatile private var detector: FaceDetector? = null
        @Volatile private var embedder: FaceEmbedder? = null

        fun detector(): FaceDetector = detector ?: openDetector().also { detector = it }

        fun embedder(): FaceEmbedder = embedder ?: openEmbedder().also { embedder = it }

        fun droppedTooSmall(): Int = detector?.droppedTooSmall?.get() ?: 0

        override fun close() {
            detector?.let { d -> runCatching { d.close() } }
            embedder?.let { e -> runCatching { e.close() } }
        }
    }

    /** Per-item cloud fetch metrics the load path fills in for the diagnostics ring: the download
     *  duration and the bytes fetched. Confined to one item's scan on one worker, so plain fields are
     *  safe; left at the sentinel for a device item or a deferred cloud item that fetched nothing. */
    private class LoadMetrics {
        var downloadMs: Long = -1L
        var downloadedBytes: Long = 0L
    }

    /** How many of [account]'s faces a completed full regroup left ungrouped, and the [writeFence] token it
     *  read them under, so the count is ignored for another account and once the face data it describes has
     *  been replaced. [finalizeOwed] marks a batched run that left its closing steps (the merge, the Unsorted
     *  bucket) to a later run that ends a walk, and [groupedUpToRow] the last stored face row a batched run
     *  looked at, from which a run that does not end a walk reads on. */
    private class RegroupBaseline(
        val account: String,
        val ungrouped: Int,
        val fenceToken: Long,
        val finalizeOwed: Boolean = false,
        val groupedUpToRow: Long = 0L,
    )

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

        const val MIB = 1024L * 1024L

        /** Hard ceiling on one clustering round (see [regroupLocked]). The clusterer is bounded and
         *  cancellable, so this only trips in a pathological case; when it does the walk carries on
         *  rather than hanging. */
        const val CLUSTER_TIMEOUT_MS = 180_000L

        /** Least time between two mid-walk clusterings during a long continuous drain, so groups
         *  refresh periodically without re-clustering on every pass. */
        const val INCREMENTAL_CLUSTER_MIN_INTERVAL_MS = 60_000L

        /** Thirty minutes in which the pass-end grouping holds off a full regroup after one timed out or
         *  threw, so a regroup that keeps failing is not retried on every settled pass. */
        const val FULL_REGROUP_FAILURE_BACKOFF_MS = 1_800_000L

        /** Ungrouped faces placed per batch when the whole-library rebuild does not fit the heap: about
         *  two megabytes of embeddings, so a batch is small next to the heap it is spared. */
        const val INCREMENTAL_BATCH_SIZE = 2_000

        /** Stored faces past which a library is grouped in batches only ([groupsInBatchesOnly]): the
         *  whole-library rebuild holds every vector at once and compares each face with every cluster, so
         *  it costs memory and time that a phone runs out of well before the 120k faces of a big library
         *  (measured: a 120k-face pass took the heap to 447 MB of 512 and was cut off at its 180 s ceiling
         *  with nothing written). */
        const val FULL_REGROUP_MAX_FACES = 30_000

        /** Passes over the ungrouped faces in one batched run: a second one lets faces that matched nobody
         *  early in the first find the people opened later in it. */
        const val MAX_BATCH_SWEEPS = 2

        /** How often the progress count (or, with no service reporting it, the heartbeat) ticks up through the
         *  quiet closing steps of a batched run (merge, naming, the Unsorted bucket), well inside the two
         *  minutes the hosting service waits for progress. */
        const val FINALIZE_TICK_MS = 10_000L

        /** A batched run notes how far it has come in the copied diagnostics every this many batches. */
        const val BATCHED_PROGRESS_LOG_EVERY = 5

        /** Batches between two saves of how far a batched run has looked; see [saveBatchedMark]. */
        const val BATCHED_MARK_SAVE_EVERY = 5

        /** A full regroup started from the pass end waits at least this many times as long as the last one
         *  ran, so the full regroups take at most about a tenth of the time while a large library scans. */
        const val FULL_REGROUP_DUTY_FACTOR = 9L

        /** A day: how long names may wait on disk for their reattach before a forced regroup stops waiting
         *  for them ([reattachOverdue]). */
        const val REATTACH_FORCED_REGROUP_GRACE_MS = 24L * 3_600_000L

        /** How many accrued scan markers trigger a mid-pass flush, so a long single-pass drain that is
         *  killed keeps the photos it already scanned instead of restarting from zero. */
        const val SCAN_FLUSH_CHUNK = 200

        /** Hard ceiling on one device-video still-frame decode, so a malformed file that hangs
         *  MediaMetadataRetriever frees the worker instead of pinning it (three would stall the pool). */
        const val VIDEO_FRAME_TIMEOUT_MS = 10_000L

        /** Hard ceiling on one photo's native face inference (detect + embed). Generous, so a slow but
         *  valid run on a large crop is never falsely skipped; only a true multi-second wedge trips it,
         *  freeing the shared ML lock and the worker rather than freezing every worker on one item. */
        const val ML_TIMEOUT_MS = 30_000L

        /** Hard ceiling on one bitmap decode (a device file or a cloud cache file), so a decode that
         *  wedges frees the worker instead of pinning it. */
        const val DECODE_TIMEOUT_MS = 20_000L

        /** Hard ceiling on one cloud full-res download. Generous, since a large original over a slow link
         *  can legitimately take a while; only a stalled link trips it, and a trip leaves the item pending
         *  for a later pass (a download timeout is transient) rather than marking it scanned. */
        const val DOWNLOAD_TIMEOUT_MS = 120_000L

        /** Hard ceiling on one cloud thumbnail decrypt, so a wedged decrypt frees the worker instead of
         *  pinning it. */
        const val DECRYPT_TIMEOUT_MS = 20_000L
    }
}

/** True only on the edge where the network becomes unmetered, so a scan deferred while off Wi-Fi
 *  resumes on reconnect rather than staying idle, without kicking on the initial replayed state. */
internal fun shouldResumeFaceScanOnUnmetered(wasUnmetered: Boolean, nowUnmetered: Boolean): Boolean =
    !wasUnmetered && nowUnmetered
