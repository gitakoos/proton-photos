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

    private val lines = ArrayDeque<String>(MAX_LINES)

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

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun isEmpty(): Boolean = lines.isEmpty()

    @Synchronized
    fun clear() {
        lines.clear()
        startedAtMs = 0L
        lastAppendedAtMs = 0L
    }

    private fun append(now: Long, message: String) {
        if (startedAtMs == 0L) startedAtMs = now
        val elapsedSeconds = (now - startedAtMs) / 1000.0
        val stamp = String.format(Locale.US, "[%7.1fs] %s", elapsedSeconds, message)
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(stamp)
        lastAppendedAtMs = now
    }
}
