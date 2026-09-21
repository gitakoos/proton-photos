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
 * Every value is a NON-IDENTIFYING count, flag, short label, or a deliberately truncated id fragment.
 * The in-flight item pointer and each rolling per-item line are cut to a short prefix so they can show
 * WHERE a scan wedged and how each item performed without ever carrying a full photo name, key, path,
 * or token; everything else is an aggregate count, a duration, a byte total, or the health gate's own
 * short reason string.
 *
 * The scheduler pushes from up to three worker coroutines plus the foreground service, so the counters
 * are atomics, the in-flight pointers are a per-worker concurrent map (so one worker clearing its own
 * entry never blanks another's live label), and the rolling per-item ring plus the slowest-item pair are
 * guarded by `@Synchronized`; only the latest-wins progress and park fields stay `@Volatile`.
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

    /** elapsedRealtime the [indexed] counter last climbed, so the snapshot can say how long the walk has
     *  been standing still. 0 until it first advances. This is THE frozen-vs-slow discriminator. */
    private val lastAdvanceMs = AtomicLong(0L)

    /** Total faces the detector has returned this session, before the blur / size gates drop any, so a
     *  "0 people found" report separates no-faces-in-library from faces-but-no-cluster. */
    private val facesDetected = AtomicInteger(0)

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

    /** Rolling last-[RECENT_CAPACITY] processed-item lines, newest appended last, guarded by `this`. */
    private const val RECENT_CAPACITY = 25
    private val recent = ArrayDeque<String>(RECENT_CAPACITY)

    /** Record the current walk state and progress; called wherever the scheduler updates its flow. */
    fun record(state: String, indexed: Int, total: Int) {
        // The scanned count climbing is the frozen-vs-slow discriminator: stamp the advance time whenever
        // it moves, so the snapshot can say how long the walk has been standing still.
        if (indexed > this.indexed) lastAdvanceMs.set(SystemClock.elapsedRealtime())
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

    /** Add the faces one successful detect() returned to the running total. */
    fun recordFacesDetected(count: Int) {
        if (count > 0) facesDetected.addAndGet(count)
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
        inFlight.clear()
        lastParkReason = "none"
        lastAdvanceMs.set(0L)
        facesDetected.set(0)
        fgsStartMs.set(0L)
        fgsTimeouts.set(0)
        imagesProcessed.set(0)
        videosProcessed.set(0)
        cloudDownloads.set(0)
        downloadedBytesTotal.set(0L)
        clearRecent()
    }

    /** Live numbers-only block for the copied diagnostics: state, progress, how long since the count last
     *  moved, pending, skips, faces, the in-flight pointer, the last park reason, the foreground-service
     *  runtime and budget-timeout count, the run aggregates, the slowest item, and the rolling per-item
     *  detail newest-first. Pending is what is left to reach the whole library, so a scan stuck short of
     *  the end is obvious at a glance. */
    fun snapshot(): String {
        val pending = (total - indexed).coerceAtLeast(0)
        val now = SystemClock.elapsedRealtime()
        val adv = lastAdvanceMs.get()
        val lastAdvance = if (adv == 0L) "never" else "${((now - adv) / 1000L).coerceAtLeast(0L)}s ago"
        val fgs = fgsStartMs.get()
        val fgsRuntime = if (fgs == 0L) "off" else formatMmSs(now - fgs)
        val slowest = slowestSnapshot()
        val recentLines = recentNewestFirst()
        return buildString {
            append("state=").append(state).append('\n')
            append("indexed=").append(indexed).append('/').append(total)
            append(" pending=").append(pending).append('\n')
            append("migration=").append(migration).append('\n')
            append("lastAdvance=").append(lastAdvance).append('\n')
            append("skippedUnloadable=").append(unloadableLocal.get()).append('\n')
            append("watchdogSkips=").append(watchdogTimeouts.get()).append('\n')
            append("facesDetected=").append(facesDetected.get()).append('\n')
            append("inFlight=").append(inFlightSnapshot()).append('\n')
            append("lastPark=").append(lastParkReason).append('\n')
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
