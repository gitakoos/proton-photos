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

import java.util.Locale

/**
 * Privacy-safe, in-memory performance diagnostics for heap / RAM / library-size pressure.
 *
 * Mirrors [SyncDiagnostics]: a small ring buffer that survives a release build (where
 * `android.util.Log` is minified out) so a tester can copy it from Settings and paste it into a
 * public issue. Nothing here is auto-sent; it only feeds the on-device buffer the user copies.
 *
 * Every value emitted is a NON-IDENTIFYING number, count, flag, or MB value. Never a photo name,
 * link / share / volume id, email, account / user id, URL, token, or file path (mirrors the
 * SyncDiagnostics rule). Callers push only aggregate counts / byte totals.
 *
 * Thread-safe: every entry point is [Synchronized] on the singleton so the foreground sampler and
 * OS memory-pressure callbacks (different threads) never interleave partial lines.
 */
object PerfDiagnostics {

    // ~120 lines is enough to span a foreground session at a 10 s sample with the heartbeat below
    // deduping steady-state periodic reads; the oldest line is evicted past the cap.
    private const val MAX_LINES = 120

    // Only append a "periodic" read this often, so a 10 s foreground sampler does not spam the
    // buffer with near-identical steady-state lines; peaks and trim / lowmem events always append.
    private const val HEARTBEAT_MS = 60_000L

    private const val BYTES_PER_MB = 1024L * 1024L

    // Phase markers keep a ring of their own, so a burst of them cannot evict the heap samples and a
    // long run of samples cannot evict them.
    private const val MAX_PHASE_LINES = 64

    // Distinct phase labels tracked for the peaks table and the open start marks. A fixed pipeline has
    // far fewer; the cap only stops a misbehaving caller from growing the maps.
    private const val MAX_PHASES = 24

    private const val LOG_TAG = "PFPPerf"

    private const val MAX_LABEL_CHARS = 32
    private const val START_SUFFIX = ":start"
    private const val END_SUFFIX = ":end"

    private val lines = ArrayDeque<String>(MAX_LINES)

    private val phaseLines = ArrayDeque<String>(MAX_PHASE_LINES)

    // Used heap (MB) and wall clock at each phase's start mark, keyed by the label before the colon.
    private val openPhases = HashMap<String, LongArray>()

    // Highest values seen per phase, which outlive the ring once it has rolled past them.
    private val phasePeaks = LinkedHashMap<String, PhasePeak>()

    private class PhasePeak {
        var marks = 0
        var maxItems = 0
        var maxHeapMb = 0L
        var maxGrowthMb: Long? = null
    }

    // Anchor for the relative timestamp: wall-clock of the first appended line (same as
    // SyncDiagnostics), so the dump carries no locally-identifying absolute timeline.
    private var startedAtMs: Long = 0L

    // Wall-clock of the last APPENDED line, for the heartbeat throttle above.
    private var lastAppendedAtMs: Long = 0L

    /** High-water mark of used Java heap (MB) this process lifetime; surfaced live and at crash. */
    @Volatile
    var peakHeapUsedMb: Long = 0L
        private set

    /** Current total gallery item count, pushed by the gallery feed on each emission. */
    @Volatile
    var libraryPhotoCount: Int = 0

    /** Current on-disk decrypted-thumbnail cache size in bytes, pushed by the thumbnail scheduler. */
    @Volatile
    var thumbnailCacheBytes: Long = 0L

    /**
     * Sample the Java heap. Updates [peakHeapUsedMb] and appends a compact buffer line only when it
     * is worth keeping: a new peak, a non-"periodic" reason (a trim / lowmem pressure event), or the
     * heartbeat window has elapsed since the last appended line. This keeps a 10 s foreground sampler
     * from flooding the 120-line buffer while never dropping a peak or a pressure moment.
     */
    @Synchronized
    fun sample(reason: String) {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MB
        val maxMb = rt.maxMemory() / BYTES_PER_MB
        val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB

        val isNewPeak = usedMb > peakHeapUsedMb
        if (isNewPeak) peakHeapUsedMb = usedMb

        val now = System.currentTimeMillis()
        val heartbeatDue = lastAppendedAtMs == 0L || (now - lastAppendedAtMs) >= HEARTBEAT_MS
        val isPressure = reason != "periodic"
        if (!isNewPeak && !isPressure && !heartbeatDue) return

        append(
            now,
            String.format(
                Locale.US,
                "sample(reason=%s) heap=%d/%dMB peak=%dMB nativeMB=%d",
                reason, usedMb, maxMb, peakHeapUsedMb, nativeMb,
            ),
        )
    }

    /** Ratio of used to max Java heap right now, 0..1. Cheap [Runtime] read; the heap-relief watchdog
     *  polls this each sample to decide whether the process is nearing the cap. */
    fun heapUsedRatio(): Double {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()).toDouble()
        val max = rt.maxMemory().toDouble()
        return if (max > 0.0) used / max else 0.0
    }

    /** Append a numbers-only line recording that the heap-relief valve shed the image cache near the
     *  cap. Distinct reason so it is never deduped away by the periodic heartbeat. */
    @Synchronized
    fun recordHeapRelief() {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MB
        val maxMb = rt.maxMemory() / BYTES_PER_MB
        append(
            System.currentTimeMillis(),
            String.format(Locale.US, "heap relief: cleared image cache at %d/%dMB", usedMb, maxMb),
        )
    }

    /** Append a numbers-only line recording that a targeted large-allocation site caught an
     *  OutOfMemoryError, cleared the image cache, and surfaced a friendly failure instead of crashing.
     *  [site] is a fixed non-identifying label (e.g. "editor-decode", "video-transcode"). */
    @Synchronized
    fun recordOom(site: String) {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MB
        val maxMb = rt.maxMemory() / BYTES_PER_MB
        append(
            System.currentTimeMillis(),
            String.format(Locale.US, "oom caught: %s at %d/%dMB", site, usedMb, maxMb),
        )
    }

    /**
     * Numbers-only marker at a big pipeline phase boundary (device scan, cloud listing refresh, reconcile,
     * upload batch, tag scan, EXIF walk): records the used Java heap, the native heap and [items]. Name
     * the edges `<phase>:start` and `<phase>:end`; an end whose start was recorded also reports how far
     * the heap grew across the phase and how long it ran, and each phase keeps its peaks, so the copied
     * diagnostics show which phase grows the heap.
     *
     * [phase] must be a fixed label, never a name, path or id: it is lowercased and cut down to letters,
     * `:`, `-`, `_` and `.`, so digits, slashes and `@` can never reach the buffer.
     */
    @Synchronized
    fun mark(phase: String, items: Int) {
        val label = phaseLabel(phase)
        val base = label.substringBefore(':')
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MB
        val maxMb = rt.maxMemory() / BYTES_PER_MB
        val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB
        if (usedMb > peakHeapUsedMb) peakHeapUsedMb = usedMb
        val now = System.currentTimeMillis()

        val opened = if (label.endsWith(END_SUFFIX)) openPhases.remove(base) else null
        if (label.endsWith(START_SUFFIX)) {
            if (base !in openPhases && openPhases.size >= MAX_PHASES) openPhases.clear()
            openPhases[base] = longArrayOf(usedMb, now)
        }
        val growthMb = opened?.let { usedMb - it[0] }
        val tookMs = opened?.let { now - it[1] }

        val peak = phasePeaks[base]
            ?: if (phasePeaks.size < MAX_PHASES) PhasePeak().also { phasePeaks[base] = it } else null
        if (peak != null) {
            peak.marks++
            peak.maxItems = maxOf(peak.maxItems, items)
            peak.maxHeapMb = maxOf(peak.maxHeapMb, usedMb)
            if (growthMb != null) peak.maxGrowthMb = maxOf(peak.maxGrowthMb ?: growthMb, growthMb)
        }

        if (phaseLines.size >= MAX_PHASE_LINES) phaseLines.removeFirst()
        val line = stamp(now, phaseLine(label, items, usedMb, maxMb, nativeMb, growthMb, tookMs))
        phaseLines.addLast(line)
        tee(line)
    }

    /** The newest [max] phase lines, oldest first. The buffer dies with the process, so an OOM crash
     *  keeps which phase was running only when the crash record carries this tail. */
    @Synchronized
    fun phaseTail(max: Int = 8): String = phaseLines.takeLast(max.coerceAtLeast(0)).joinToString("\n")

    /** [phase] cut down to the fixed-label alphabet [mark] documents; never empty. */
    internal fun phaseLabel(phase: String): String =
        phase.lowercase(Locale.US)
            .filter { it in 'a'..'z' || it == ':' || it == '-' || it == '_' || it == '.' }
            .take(MAX_LABEL_CHARS)
            .ifEmpty { "phase" }

    /** One phase line: the label, the item count, the heap numbers and, for a closed phase, the growth. */
    internal fun phaseLine(
        label: String,
        items: Int,
        usedMb: Long,
        maxMb: Long,
        nativeMb: Long,
        growthMb: Long?,
        tookMs: Long?,
    ): String = buildString {
        append(label).append(" items=").append(items)
        append(" heap=").append(usedMb).append('/').append(maxMb).append("MB")
        append(" nativeMB=").append(nativeMb)
        if (growthMb != null && tookMs != null) {
            append(" grew=").append(signedMb(growthMb))
            append(String.format(Locale.US, " in %.1fs", tookMs / 1000.0))
        }
    }

    /** One row of the per-phase peaks table. */
    internal fun peakLine(base: String, marks: Int, maxItems: Int, maxHeapMb: Long, maxGrowthMb: Long?): String =
        buildString {
            append(base).append(" marks=").append(marks)
            append(" maxItems=").append(maxItems)
            append(" maxHeap=").append(maxHeapMb).append("MB")
            if (maxGrowthMb != null) append(" maxGrowth=").append(signedMb(maxGrowthMb))
        }

    private fun signedMb(mb: Long): String = (if (mb >= 0) "+" else "") + mb + "MB"

    /** Live point-in-time block (no buffer) for the copied diagnostics: heap, native heap, device RAM,
     *  library size, thumbnail-cache size. Numbers only. */
    fun snapshot(context: android.content.Context): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MB
        val maxMb = rt.maxMemory() / BYTES_PER_MB
        val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB
        val cacheMb = thumbnailCacheBytes / BYTES_PER_MB

        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val largeHeap = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0

        return buildString {
            append("javaHeap=").append(usedMb).append('/').append(maxMb)
                .append("MB peak=").append(peakHeapUsedMb).append("MB\n")
            append("nativeHeap=").append(nativeMb).append("MB\n")
            append("deviceRAM total=").append(mem.totalMem / BYTES_PER_MB)
                .append("MB avail=").append(mem.availMem / BYTES_PER_MB)
                .append("MB low=").append(mem.lowMemory)
                .append(" threshold=").append(mem.threshold / BYTES_PER_MB).append("MB\n")
            append("libraryPhotos=").append(libraryPhotoCount).append('\n')
            append("thumbCache=").append(cacheMb).append("MB\n")
            append("largeHeap=").append(largeHeap)
        }
    }

    /** The sample lines, then the phase markers and the per-phase peaks when any phase was marked. */
    @Synchronized
    fun dump(): String = buildString {
        lines.joinTo(this, "\n")
        if (phaseLines.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append("phases:\n")
            phaseLines.joinTo(this, "\n")
            append("\nphase peaks:")
            for ((base, peak) in phasePeaks) {
                append('\n').append(peakLine(base, peak.marks, peak.maxItems, peak.maxHeapMb, peak.maxGrowthMb))
            }
        }
    }

    @Synchronized
    fun isEmpty(): Boolean = lines.isEmpty() && phaseLines.isEmpty()

    @Synchronized
    fun clear() {
        lines.clear()
        phaseLines.clear()
        openPhases.clear()
        phasePeaks.clear()
        startedAtMs = 0L
        lastAppendedAtMs = 0L
    }

    private fun append(now: Long, message: String) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        val line = stamp(now, message)
        lines.addLast(line)
        lastAppendedAtMs = now
        tee(line)
    }

    /** Mirrors a numbers-only line to logcat. A published release strips every Log call, so this only
     *  speaks in a debug build or a measurement build made with `-PpfpKeepLogs`. */
    private fun tee(line: String) {
        android.util.Log.i(LOG_TAG, line)
    }

    /** [message] behind the relative timestamp shared by the sample lines and the phase markers. */
    private fun stamp(now: Long, message: String): String {
        if (startedAtMs == 0L) startedAtMs = now
        val elapsedSeconds = (now - startedAtMs) / 1000.0
        return String.format(Locale.US, "[%7.1fs] %s", elapsedSeconds, message)
    }
}
