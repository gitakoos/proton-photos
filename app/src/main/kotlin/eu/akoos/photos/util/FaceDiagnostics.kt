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

package eu.akoos.photos.util

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Privacy-safe, in-memory face-indexing diagnostics, so a stalled or standing-still scan is legible
 * in the copied diagnostics instead of a blind spot. Mirrors [PerfDiagnostics] / [SyncDiagnostics]:
 * a handful of live fields the scheduler pushes and a numbers-only [snapshot] the user copies from
 * Settings. Nothing here is auto-sent.
 *
 * Every value is a NON-IDENTIFYING count, flag, short label, exception class name, or a deliberately
 * truncated id fragment. The in-flight item pointer and each rolling per-item line are cut to a short
 * prefix so they can show WHERE a scan wedged and how each item performed without ever carrying a full
 * photo name, key, path, or token; everything else is an aggregate count, a duration, a byte total, a
 * fixed regroup-trigger label, an exception class name (never its message), or the health gate's own
 * short reason string.
 *
 * The scheduler pushes from up to three worker coroutines plus the foreground service, so the counters
 * are atomics, the in-flight pointers are a per-worker concurrent map (so one worker clearing its own
 * entry never blanks another's live label), and the rolling per-item ring plus the slowest-item pair are
 * guarded by `@Synchronized`; only the latest-wins fields (progress, park, library totals, latest
 * regroup) stay `@Volatile`, the library totals and the latest completed regroup each swapped in as one
 * immutable value.
 */
object FaceDiagnostics {

    /** The walk's last reported state (Idle / WaitingModel / Running / Paused / Done). */
    @Volatile
    var state: String = "Idle"

    /** How many photos have been scanned so far, out of the whole library. */
    @Volatile
    var indexed: Int = 0

    @Volatile
    var total: Int = 0

    /** What the last sign-in did with a prior guest scan this session: ADOPT carried the guest's people
     *  and faces across to the account, DISCARD dropped them because the account already had its own,
     *  each with the counts it moved. "none" until a real migration runs. Set only through
     *  [recordMigration], which drops a NONE so an idempotent second call cannot clobber the real
     *  outcome. */
    @Volatile
    var migration: String = "none"
        private set

    /** Device-only files skipped this session because they yielded no image or video frame (corrupt or
     *  unsupported). A non-zero count is what would otherwise have wedged the walk before the skip.
     *  Atomic: several workers increment it. */
    private val unloadableLocal = AtomicInteger(0)

    /** The health gate's reason the last time a worker parked (e.g. "battery ..% not charging", "power
     *  saver on"), so a scan that is standing still says why. The background walk parks on the physical /
     *  power tier only, so its parks never read "user interacting". */
    @Volatile
    var lastParkReason: String = "none"

    /** Per-worker map of the short, truncated pointer to the item each worker is scanning right now (an
     *  8-char cloud link prefix, or a device media id), so a frozen scan names WHERE it wedged instead of
     *  a blind spot, and one worker clearing its own entry never blanks another's live label. Deliberately
     *  a fragment, never a full key, path, or token. Empty when no scan is in flight. */
    private val inFlight = ConcurrentHashMap<String, String>()

    /** How many items the per-item watchdog has skipped this session because a native inference, decode,
     *  download, or decrypt ran past its timeout. A non-zero count is what would otherwise have wedged
     *  the whole walk on one pathological item. Atomic: several workers increment it. */
    private val watchdogTimeouts = AtomicInteger(0)

    /** Cloud photos recorded scanned this session, with no face, because their source can never load:
     *  the server answered that the link, its keys or its revision are gone, or the downloaded original
     *  is a format the decoder cannot read. Atomic: several workers increment it. */
    private val permanentSkips = AtomicInteger(0)

    /** As of the last pass end: photos waiting out a retry backoff after a transient load failure, and
     *  photos that pass left for the next one because its download budget was spent. */
    @Volatile
    private var transientWaiting = 0
    @Volatile
    private var overBudget = 0

    /** elapsedRealtime the [indexed] counter last climbed, so the snapshot can say how long the walk has
     *  been standing still. 0 until it first advances. This is THE frozen-vs-slow discriminator. */
    private val lastAdvanceMs = AtomicLong(0L)

    /** Total faces the detector has returned this session, before the blur / size gates drop any, so a
     *  "0 people found" report separates no-faces-in-library from faces-but-no-cluster. */
    private val facesDetected = AtomicInteger(0)

    /** The account's library totals as last read from the database: stored faces, faces not yet grouped,
     *  people (the Unsorted bucket excluded) and named people. Beside [facesDetected] they tell "no faces
     *  stored" apart from "faces stored but never grouped". A count whose read failed is negative and
     *  prints "?"; null until the first read. */
    @Volatile
    private var libraryCounts: LibraryCounts? = null

    /** Full regroups this session: how many started, and how each one that returned ended. A start with
     *  no outcome is still running or was cancelled. Atomic: a walk's regroup and a forced one can
     *  overlap. */
    private val regroupsStarted = AtomicInteger(0)
    private val regroupsCompleted = AtomicInteger(0)
    private val regroupsTimedOut = AtomicInteger(0)
    private val regroupsFailed = AtomicInteger(0)

    /** What started the latest regroup, the exception class name (never the message) of the latest one
     *  that threw, and the run time and finish time of the latest one that completed. Null before any. */
    @Volatile
    private var lastRegroupTrigger: RegroupTrigger? = null
    @Volatile
    private var lastRegroupFailure: String? = null
    @Volatile
    private var lastRegroupCompleted: CompletedRegroup? = null

    /** Full regroups skipped this session because the heap still free could not hold every embedding at
     *  once, and the latest one's estimated need beside the free heap it found. Null before any. */
    private val regroupsSkippedHeap = AtomicInteger(0)
    @Volatile
    private var lastHeapSkip: HeapSkip? = null

    /** Batched placement runs this session and the latest one's numbers (batches, faces placed, people
     *  opened, faces left ungrouped, run time). Empty before any. */
    private val batchedRuns = AtomicInteger(0)
    @Volatile
    private var lastBatchedRun: String = ""

    /** Face model pairs the inference watchdog has abandoned in this process (never closed, so their
     *  memory stays until the process ends), and whether that count has stopped the background scan for the
     *  rest of the process. Written whole by the scheduler, which owns the count, so the next pass after a
     *  clear sets them right again. */
    @Volatile
    private var abandonedSessions = 0
    @Volatile
    private var scanHalted = false

    /** What started a full regroup, printed as its fixed [label]: the drained walk, a pass end about to
     *  stop or about to continue, a scan brought forward from the viewer or a find-more sweep, or a
     *  request after a naming or merge edit. A closed set, so a trigger never carries free text. */
    enum class RegroupTrigger(val label: String) {
        DRAINED("drained"),
        PASS_SETTLED("pass-settled"),
        PASS_CONTINUING("pass-continuing"),
        ON_DEMAND("on-demand"),
        REQUEST("request"),
    }

    /** One read of the library totals and the elapsedRealtime it was taken at. */
    private data class LibraryCounts(
        val faces: Int,
        val ungrouped: Int,
        val people: Int,
        val named: Int,
        val atMs: Long,
    )

    /** A completed regroup's run time and the elapsedRealtime it finished at. */
    private data class CompletedRegroup(val durationMs: Long, val endMs: Long)

    /** A regroup skipped for want of heap: the bytes it was estimated to need and the bytes free. */
    private data class HeapSkip(val needBytes: Long, val freeBytes: Long)

    /** elapsedRealtime the foreground service last began a session, or 0 when it is not running, so the
     *  snapshot can show how long the dataSync service has run against the platform's daily budget. */
    private val fgsStartMs = AtomicLong(0L)

    /** How many times the platform has hit the dataSync foreground daily budget and called onTimeout, so
     *  the snapshot can confirm or rule out the 6h cap as the reason a large scan stopped short. */
    private val fgsTimeouts = AtomicInteger(0)

    /** Images vs videos fully processed (source loaded and inference ran) this session. */
    private val imagesProcessed = AtomicInteger(0)
    private val videosProcessed = AtomicInteger(0)

    /** How many cloud full-res downloads ran this session, and the bytes they fetched, so the snapshot
     *  can show the scan's data cost. */
    private val cloudDownloads = AtomicInteger(0)
    private val downloadedBytesTotal = AtomicLong(0L)

    /** The slowest item this session (total load + infer), guarded together with [slowestMs]. */
    private var slowestLabel: String = "none"
    private var slowestMs: Long = 0L

    /** Logcat tag of the numbers-only mirror; a published release strips every Log call. */
    private const val LOG_TAG = "PFPFace"

    /** Rolling last-[RECENT_CAPACITY] processed-item lines, newest appended last, guarded by `this`. */
    private const val RECENT_CAPACITY = 25
    private val recent = ArrayDeque<String>(RECENT_CAPACITY)

    /** Record the current walk state and progress; called wherever the scheduler updates its flow. */
    fun record(state: String, indexed: Int, total: Int) {
        // The scanned count climbing is the frozen-vs-slow discriminator: stamp the advance time whenever
        // it moves, so the snapshot can say how long the walk has been standing still.
        if (indexed > this.indexed) lastAdvanceMs.set(SystemClock.elapsedRealtime())
        // Progress mirrored to logcat (counts only; stripped from a published release) on a state
        // change and every 500 scanned, so a measurement build can follow a long walk.
        if (state != this.state || (indexed != this.indexed && indexed % 500 == 0)) {
            android.util.Log.i(LOG_TAG, "scan state=$state indexed=$indexed total=$total")
        }
        this.state = state
        this.indexed = indexed
        this.total = total
    }

    /** Record what a sign-in did with a prior guest scan: the action name plus the people and face
     *  counts it carried across or dropped. A NONE is ignored, so the idempotent migration running from
     *  both the walk and the on-demand path (whichever runs second sees no guest rows and resolves to
     *  NONE) cannot overwrite the real ADOPT / DISCARD the first call recorded. */
    fun recordMigration(action: String, people: Int, faces: Int) {
        if (action.equals("NONE", ignoreCase = true)) return
        migration = "$action people=$people faces=$faces"
    }

    /** Note that a worker parked on the health gate, with the gate's own reason. */
    fun recordPark(reason: String) {
        lastParkReason = reason
    }

    /** Note one more device-only file skipped for want of any decodable frame. */
    fun recordUnloadableLocal() {
        unloadableLocal.incrementAndGet()
    }

    /** Note the item [worker] just started scanning, as a short truncated pointer. */
    fun recordInFlight(worker: String, label: String) {
        inFlight[worker] = label
    }

    /** Clear [worker]'s in-flight pointer when it returns from an item, without touching another's. */
    fun clearInFlight(worker: String) {
        inFlight.remove(worker)
    }

    /** Note one more item the per-item watchdog skipped for running past its timeout. */
    fun recordWatchdogTimeout() {
        watchdogTimeouts.incrementAndGet()
    }

    /** Note one more cloud photo recorded scanned because its source can never load. */
    fun recordPermanentSkip() {
        permanentSkips.incrementAndGet()
    }

    /** Record, at a pass end, how many photos wait out a retry backoff and how many the pass left over
     *  its download budget. */
    fun recordSourceBacklog(transientWaiting: Int, overBudget: Int) {
        this.transientWaiting = transientWaiting
        this.overBudget = overBudget
    }

    /** Add the faces one successful detect() returned to the running total. */
    fun recordFacesDetected(count: Int) {
        if (count > 0) facesDetected.addAndGet(count)
    }

    /** Record the account's library totals as just read, at [atMs] (elapsedRealtime); a negative count
     *  marks one whose read failed. */
    fun recordLibraryCounts(
        faces: Int,
        ungrouped: Int,
        people: Int,
        named: Int,
        atMs: Long = SystemClock.elapsedRealtime(),
    ) {
        libraryCounts = LibraryCounts(faces, ungrouped, people, named, atMs)
        android.util.Log.i(LOG_TAG, "counts faces=$faces ungrouped=$ungrouped people=$people named=$named")
    }

    /** Note a full regroup starting, and what started it. */
    fun recordRegroupStarted(trigger: RegroupTrigger) {
        regroupsStarted.incrementAndGet()
        lastRegroupTrigger = trigger
        android.util.Log.i(LOG_TAG, "regroup started trigger=$trigger")
    }

    /** Note a full regroup completing after [durationMs], finishing at [endMs] (elapsedRealtime). */
    fun recordRegroupCompleted(durationMs: Long, endMs: Long = SystemClock.elapsedRealtime()) {
        regroupsCompleted.incrementAndGet()
        lastRegroupCompleted = CompletedRegroup(durationMs, endMs)
        android.util.Log.i(LOG_TAG, "regroup completed ms=$durationMs")
    }

    /** Note one batched placement run: its batches, the faces it placed, the people it opened, the groups it
     *  merged and named, the faces it moved to Unsorted, how many it left ungrouped and how long it took. */
    fun recordBatchedRun(
        batches: Int,
        placed: Int,
        created: Int,
        merged: Int,
        named: Int,
        unsorted: Int,
        leftover: Int,
        tookMs: Long,
    ) {
        batchedRuns.incrementAndGet()
        lastBatchedRun = "batches=$batches placed=$placed opened=$created merged=$merged named=$named " +
            "unsorted=$unsorted left=$leftover ms=$tookMs"
        android.util.Log.i(LOG_TAG, "batched run $lastBatchedRun")
    }

    /** Note a full regroup given up at its timeout. */
    fun recordRegroupTimedOut() {
        regroupsTimedOut.incrementAndGet()
        android.util.Log.i(LOG_TAG, "regroup timed out")
    }

    /** Note a full regroup skipped before it read a face, because it was estimated to need [needBytes] of
     *  heap and only [freeBytes] were free. */
    fun recordRegroupHeapSkip(needBytes: Long, freeBytes: Long) {
        regroupsSkippedHeap.incrementAndGet()
        lastHeapSkip = HeapSkip(needBytes, freeBytes)
        android.util.Log.i(LOG_TAG, "regroup heap skip needMB=${needBytes / 1_048_576} freeMB=${freeBytes / 1_048_576}")
    }

    /** Record how many face model pairs this process has abandoned to the watchdog, and whether the
     *  background scan has stopped for the rest of the process because of them. */
    fun recordAbandonedSessions(count: Int, halted: Boolean) {
        abandonedSessions = count
        scanHalted = halted
    }

    /** Note a full regroup that threw. Only the exception's simple class name is kept, never its message,
     *  so nothing about the account or a photo can reach the copied text. */
    fun recordRegroupFailed(error: Throwable) {
        regroupsFailed.incrementAndGet()
        lastRegroupFailure = error.javaClass.simpleName.ifEmpty { error.javaClass.name.substringAfterLast('.') }
        android.util.Log.i(LOG_TAG, "regroup failed ${lastRegroupFailure}")
    }

    /** Mark the foreground service session as started, once: a re-kick while it is already running does
     *  not reset the clock, so the runtime measures the true session length against the daily budget. */
    fun recordFgsStart() {
        fgsStartMs.compareAndSet(0L, SystemClock.elapsedRealtime())
    }

    /** Mark the foreground service session as ended, so the runtime reads "off" until the next start. */
    fun recordFgsStop() {
        fgsStartMs.set(0L)
    }

    /** Note the platform hitting the dataSync foreground daily budget. */
    fun recordFgsTimeout() {
        fgsTimeouts.incrementAndGet()
    }

    /**
     * Fold one fully processed item into the rolling ring and the run aggregates: its img / vid kind and
     * size, its load and inference timings (plus the cloud download time when one ran), the images-vs-
     * videos and download tallies, and the slowest item so far. [downloadMs] is negative when no cloud
     * download ran (a device item, or a cloud item served warm or deferred).
     */
    fun recordProcessed(
        label: String,
        isVideo: Boolean,
        sizeBytes: Long,
        loadMs: Long,
        inferMs: Long,
        downloadMs: Long,
        downloadedBytes: Long,
    ) {
        if (isVideo) videosProcessed.incrementAndGet() else imagesProcessed.incrementAndGet()
        if (downloadMs >= 0L) {
            cloudDownloads.incrementAndGet()
            downloadedBytesTotal.addAndGet(downloadedBytes.coerceAtLeast(0L))
        }
        noteSlowest(label, loadMs + inferMs)
        addRecent(buildRecentLine(label, isVideo, sizeBytes, loadMs, inferMs, downloadMs))
    }

    @Synchronized
    private fun noteSlowest(label: String, totalMs: Long) {
        if (totalMs > slowestMs) {
            slowestMs = totalMs
            slowestLabel = label
        }
    }

    @Synchronized
    private fun addRecent(line: String) {
        if (recent.size >= RECENT_CAPACITY) recent.removeFirst()
        recent.addLast(line)
    }

    @Synchronized
    private fun recentNewestFirst(): List<String> = recent.toList().asReversed()

    @Synchronized
    private fun slowestSnapshot(): String =
        if (slowestMs <= 0L) "none" else "$slowestLabel (${slowestMs}ms)"

    @Synchronized
    private fun clearRecent() {
        recent.clear()
        slowestLabel = "none"
        slowestMs = 0L
    }

    private fun buildRecentLine(
        label: String,
        isVideo: Boolean,
        sizeBytes: Long,
        loadMs: Long,
        inferMs: Long,
        downloadMs: Long,
    ): String = buildString {
        append(label)
        append(if (isVideo) " vid " else " img ")
        append(formatMb(sizeBytes))
        append(" load=").append(loadMs)
        append(" infer=").append(inferMs)
        if (downloadMs >= 0L) append(" dl=").append(downloadMs)
    }

    /** Reset to the signed-out baseline, so a new account does not inherit the previous one's counts. */
    fun clear() {
        state = "Idle"
        indexed = 0
        total = 0
        migration = "none"
        unloadableLocal.set(0)
        watchdogTimeouts.set(0)
        permanentSkips.set(0)
        transientWaiting = 0
        overBudget = 0
        inFlight.clear()
        lastParkReason = "none"
        lastAdvanceMs.set(0L)
        facesDetected.set(0)
        libraryCounts = null
        regroupsStarted.set(0)
        regroupsCompleted.set(0)
        regroupsTimedOut.set(0)
        regroupsFailed.set(0)
        lastRegroupTrigger = null
        lastRegroupFailure = null
        lastRegroupCompleted = null
        regroupsSkippedHeap.set(0)
        lastHeapSkip = null
        batchedRuns.set(0)
        lastBatchedRun = ""
        abandonedSessions = 0
        scanHalted = false
        fgsStartMs.set(0L)
        fgsTimeouts.set(0)
        imagesProcessed.set(0)
        videosProcessed.set(0)
        cloudDownloads.set(0)
        downloadedBytesTotal.set(0L)
        clearRecent()
    }

    /** Live numbers-only block for the copied diagnostics: state, progress, how long since the count last
     *  moved, pending, skips, the photos left pending or settled by why their source did not load, faces
     *  detected, the library totals, the regroup tallies with the latest trigger, failure class and
     *  completion, the regroups skipped for want of heap, the in-flight pointer, the last park reason, the
     *  abandoned model pairs and whether they halted the scan, the foreground-service runtime and
     *  budget-timeout count, the run aggregates, the slowest item, and the rolling per-item detail
     *  newest-first. Pending is what is left to reach the whole library, so a scan stuck short of the end
     *  is obvious at a glance. Every relative age is measured from [now] (elapsedRealtime). */
    fun snapshot(now: Long = SystemClock.elapsedRealtime()): String {
        val pending = (total - indexed).coerceAtLeast(0)
        val adv = lastAdvanceMs.get()
        val lastAdvance = if (adv == 0L) "never" else "${((now - adv) / 1000L).coerceAtLeast(0L)}s ago"
        val fgs = fgsStartMs.get()
        val fgsRuntime = if (fgs == 0L) "off" else formatMmSs(now - fgs)
        val slowest = slowestSnapshot()
        val recentLines = recentNewestFirst()
        val counts = libraryCounts
        val completed = lastRegroupCompleted
        val heapSkip = lastHeapSkip
        return buildString {
            append("state=").append(state).append('\n')
            append("indexed=").append(indexed).append('/').append(total)
            append(" pending=").append(pending).append('\n')
            append("migration=").append(migration).append('\n')
            append("lastAdvance=").append(lastAdvance).append('\n')
            append("skippedUnloadable=").append(unloadableLocal.get()).append('\n')
            append("watchdogSkips=").append(watchdogTimeouts.get()).append('\n')
            append("permanentSkipped=").append(permanentSkips.get())
            append(" transientWaiting=").append(transientWaiting)
            append(" overBudget=").append(overBudget).append('\n')
            append("facesDetected=").append(facesDetected.get()).append('\n')
            append("storedFaces=").append(countText(counts?.faces))
            append(" ungrouped=").append(countText(counts?.ungrouped))
            append(" people=").append(countText(counts?.people))
            append(" named=").append(countText(counts?.named))
            append(" counted=").append(if (counts == null) "never" else secondsAgo(now, counts.atMs)).append('\n')
            append("regroupsStarted=").append(regroupsStarted.get())
            append(" completed=").append(regroupsCompleted.get())
            append(" timedOut=").append(regroupsTimedOut.get())
            append(" failed=").append(regroupsFailed.get())
            lastRegroupFailure?.let { append(" (").append(it).append(')') }
            append('\n')
            append("lastRegroupTrigger=").append(lastRegroupTrigger?.label ?: "none")
            append(" lastRegroupCompleted=")
            if (completed == null) {
                append("never")
            } else {
                append(secondsAgo(now, completed.endMs)).append(" (").append(completed.durationMs).append("ms)")
            }
            append('\n')
            append("regroupHeapSkips=").append(regroupsSkippedHeap.get())
            heapSkip?.let {
                append(" (need=").append(formatMb(it.needBytes)).append(" free=").append(formatMb(it.freeBytes)).append(')')
            }
            append('\n')
            if (batchedRuns.get() > 0) {
                append("batchedRuns=").append(batchedRuns.get()).append(" last(").append(lastBatchedRun).append(")\n")
            }
            append("inFlight=").append(inFlightSnapshot()).append('\n')
            append("lastPark=").append(lastParkReason).append('\n')
            append("abandonedSessions=").append(abandonedSessions)
            append(" scanHalted=").append(if (scanHalted) "yes" else "no").append('\n')
            append("fgsRuntime=").append(fgsRuntime)
            append(" fgsTimeouts=").append(fgsTimeouts.get()).append('\n')
            append("img=").append(imagesProcessed.get())
            append(" vid=").append(videosProcessed.get())
            append(" downloads=").append(cloudDownloads.get())
            append(" (").append(formatMb(downloadedBytesTotal.get())).append(")").append('\n')
            append("slowest=").append(slowest).append('\n')
            append("Recent:")
            if (recentLines.isEmpty()) {
                append(" (none)")
            } else {
                for (line in recentLines) append("\n  ").append(line)
            }
        }
    }

    /** The in-flight pointers across all workers, worker-sorted for a stable copy, or "none" when idle.
     *  Weakly-consistent iteration over the concurrent map, so a worker starting or finishing meanwhile
     *  never throws here. */
    private fun inFlightSnapshot(): String {
        if (inFlight.isEmpty()) return "none"
        return inFlight.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}:${it.value}" }
    }

    /** A library total for the copied text, "?" when it was never read or its read failed. */
    private fun countText(count: Int?): String = if (count == null || count < 0) "?" else count.toString()

    /** Whole seconds from [atMs] to [now] (both elapsedRealtime), as "Ns ago". */
    private fun secondsAgo(now: Long, atMs: Long): String = "${((now - atMs) / 1000L).coerceAtLeast(0L)}s ago"

    /** Bytes to a one-decimal MB string with no locale dependence, so the copied text is stable. "?" for
     *  a missing size. Internal so the byte formatting is unit-tested. */
    internal fun formatMb(bytes: Long): String {
        if (bytes <= 0L) return "?"
        val tenths = (bytes * 10L + 524_288L) / 1_048_576L
        return "${tenths / 10L}.${tenths % 10L}MB"
    }

    /** Milliseconds to mm:ss (minutes are not capped at two digits, so a multi-hour runtime still reads). */
    private fun formatMmSs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val m = totalSec / 60L
        val s = totalSec % 60L
        val mm = if (m < 10L) "0$m" else "$m"
        val ss = if (s < 10L) "0$s" else "$s"
        return "$mm:$ss"
    }
}
