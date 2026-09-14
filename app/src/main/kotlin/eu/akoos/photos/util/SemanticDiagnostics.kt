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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Privacy-safe, in-memory semantic-search diagnostics, the sibling of [FaceDiagnostics] for the photo
 * search rail. It makes a stalled index or a slow search legible on a real (release) build, where the
 * Log calls are stripped, without ever leaking user content: the indexing scheduler and the foreground
 * service push a handful of live fields, and a numbers-only [snapshot] is what the user copies from
 * Settings. Nothing here is auto-sent.
 *
 * Every value is a NON-IDENTIFYING count, flag, or duration. It never holds a photo key, path, file
 * name, or a search query string; the search side records only timings and counts, never the query text.
 *
 * The search-side timings are the reason this exists beyond mirroring the face rail: [snapshot]'s rank
 * time scales with the library size, because the ranker scores every stored embedding per query. On a
 * handful of photos it is a millisecond or two; on tens of thousands it is the honest read on whether
 * search is still instant. Paired with the index throughput and the pending count, it answers "will a
 * fifty-thousand-photo library finish indexing, and is search still fast once it has". The scheduler
 * pushes from up to three workers plus the foreground service, so the counters are atomics and the
 * pass-throughput pair and the recent-search ring are guarded by `this`; latest-wins fields stay
 * `@Volatile`.
 */
object SemanticDiagnostics {

    /** The walk's last reported state (Idle / WaitingModel / Running / Paused / Done). */
    @Volatile
    var state: String = "Idle"

    /** How many photos have been embedded so far, out of the whole library. */
    @Volatile
    var indexed: Int = 0

    @Volatile
    var total: Int = 0

    /** elapsedRealtime the [indexed] counter last climbed, so the snapshot can say how long the walk has
     *  been standing still. 0 until it first advances. The frozen-vs-slow discriminator: a stalled index
     *  with lastPark=none is a per-item wedge, with a park reason it is the health gate. */
    private val lastAdvanceMs = AtomicLong(0L)

    /** The most recent completed pass's embedded count and wall-clock duration, so the snapshot can show a
     *  throughput (photos/min) rather than only a running total. Guarded together by [this]. */
    private var lastPassCount: Int = 0
    private var lastPassDurationMs: Long = 0L

    /** The health gate's reason the last time a worker parked, so a standing-still index says why. */
    @Volatile
    var lastParkReason: String = "none"

    /** Items the per-item watchdog abandoned this session for running past the inference timeout, and
     *  items whose embed threw. Non-zero counts explain an index that skipped photos or crawled. */
    private val watchdogTimeouts = AtomicInteger(0)
    private val embedFailures = AtomicInteger(0)

    /** elapsedRealtime the foreground service last began, or 0 when off, so the snapshot shows how long a
     *  large first index has been running against the platform's daily foreground-service budget. */
    private val fgsStartMs = AtomicLong(0L)
    private val fgsTimeouts = AtomicInteger(0)

    /** The last search's timings and counts: text-encode ms, rank ms (scores every stored embedding, so it
     *  grows with the library), embeddings scanned, and results shown. Latest-wins, no query text. */
    @Volatile
    var lastSearchEmbedMs: Long = -1L
    @Volatile
    var lastSearchRankMs: Long = -1L
    @Volatile
    var lastSearchScanned: Int = 0
    @Volatile
    var lastSearchShown: Int = 0

    /** Rolling last-[SEARCH_RING] search lines ("rankMs/scanned"), newest last, guarded by [this], so the
     *  snapshot shows whether rank time is trending up as the library grows. No query text. */
    private const val SEARCH_RING = 10
    private val recentSearches = ArrayDeque<String>(SEARCH_RING)

    /** Record the walk state and progress; the scheduler mirrors every progress emit here. */
    fun record(state: String, indexed: Int, total: Int) {
        if (indexed > this.indexed) lastAdvanceMs.set(SystemClock.elapsedRealtime())
        this.state = state
        this.indexed = indexed
        this.total = total
    }

    /** Fold a finished pass's throughput in: how many rows it wrote and how long the pass took. */
    @Synchronized
    fun recordPass(count: Int, durationMs: Long) {
        lastPassCount = count
        lastPassDurationMs = durationMs
    }

    fun recordPark(reason: String) {
        lastParkReason = reason
    }

    fun recordWatchdogTimeout() {
        watchdogTimeouts.incrementAndGet()
    }

    fun recordEmbedFailure() {
        embedFailures.incrementAndGet()
    }

    fun recordFgsStart() {
        fgsStartMs.compareAndSet(0L, SystemClock.elapsedRealtime())
    }

    fun recordFgsStop() {
        fgsStartMs.set(0L)
    }

    fun recordFgsTimeout() {
        fgsTimeouts.incrementAndGet()
    }

    /** Record one search's timings and counts (no query text): text-encode ms, rank ms, embeddings
     *  scanned, and results shown. The rank time is the library-size signal. */
    fun recordSearch(embedMs: Long, rankMs: Long, scanned: Int, shown: Int) {
        lastSearchEmbedMs = embedMs
        lastSearchRankMs = rankMs
        lastSearchScanned = scanned
        lastSearchShown = shown
        addRecentSearch("${rankMs}ms/$scanned")
    }

    @Synchronized
    private fun addRecentSearch(line: String) {
        if (recentSearches.size >= SEARCH_RING) recentSearches.removeFirst()
        recentSearches.addLast(line)
    }

    @Synchronized
    private fun recentSearchesNewestFirst(): List<String> = recentSearches.toList().asReversed()

    @Synchronized
    private fun throughputSnapshot(): String {
        if (lastPassCount <= 0 || lastPassDurationMs <= 0L) return "n/a"
        val perMin = lastPassCount * 60_000L / lastPassDurationMs
        return "$perMin/min (lastPass $lastPassCount in ${formatMmSs(lastPassDurationMs)})"
    }

    /** Reset to the signed-out baseline, so a new account does not inherit the previous one's counts. */
    @Synchronized
    fun clear() {
        state = "Idle"
        indexed = 0
        total = 0
        lastAdvanceMs.set(0L)
        lastPassCount = 0
        lastPassDurationMs = 0L
        lastParkReason = "none"
        watchdogTimeouts.set(0)
        embedFailures.set(0)
        fgsStartMs.set(0L)
        fgsTimeouts.set(0)
        lastSearchEmbedMs = -1L
        lastSearchRankMs = -1L
        lastSearchScanned = 0
        lastSearchShown = 0
        recentSearches.clear()
    }

    /** Live numbers-only block for the copied diagnostics: index state + progress + throughput + stalls,
     *  the foreground-service runtime, and the search timings that reveal how the ranker scales with the
     *  library. Nothing here identifies a photo or a query. */
    fun snapshot(): String {
        val pending = (total - indexed).coerceAtLeast(0)
        val now = SystemClock.elapsedRealtime()
        val adv = lastAdvanceMs.get()
        val lastAdvance = if (adv == 0L) "never" else "${((now - adv) / 1000L).coerceAtLeast(0L)}s ago"
        val fgs = fgsStartMs.get()
        val fgsRuntime = if (fgs == 0L) "off" else formatMmSs(now - fgs)
        val lastSearch = if (lastSearchRankMs < 0L) {
            "none"
        } else {
            "embed=${lastSearchEmbedMs}ms rank=${lastSearchRankMs}ms scanned=$lastSearchScanned shown=$lastSearchShown"
        }
        val recent = recentSearchesNewestFirst()
        return buildString {
            append("state=").append(state).append('\n')
            append("indexed=").append(indexed).append('/').append(total)
            append(" pending=").append(pending).append('\n')
            append("throughput=").append(throughputSnapshot()).append('\n')
            append("lastAdvance=").append(lastAdvance).append('\n')
            append("watchdogSkips=").append(watchdogTimeouts.get())
            append(" embedFailures=").append(embedFailures.get()).append('\n')
            append("lastPark=").append(lastParkReason).append('\n')
            append("fgsRuntime=").append(fgsRuntime)
            append(" fgsTimeouts=").append(fgsTimeouts.get()).append('\n')
            append("search=").append(lastSearch)
            if (recent.isNotEmpty()) append("\n  recent: ").append(recent.joinToString(", "))
        }
    }

    private fun formatMmSs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val m = totalSec / 60L
        val s = totalSec % 60L
        val mm = if (m < 10L) "0$m" else "$m"
        val ss = if (s < 10L) "0$s" else "$s"
        return "$mm:$ss"
    }
}
