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

import eu.akoos.photos.data.repository.drive.PhotoUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Why a background model walk (faces, semantic search) got no source for one photo in a pass, so the
 * walk can settle the photo, back it off, or simply leave it for a later pass.
 */
enum class SourceMiss {
    /** It will not load however often it is tried: the server answered that the link, its keys or its
     *  revision are gone, or the downloaded original is a format the decoder cannot read. Settled the way
     *  an unreadable device file is, so it never holds the walk up again; a full rescan tries it afresh. */
    PERMANENT,

    /** An attempt ran and failed for a reason that can pass: the connection, a timeout, a busy service.
     *  Backed off in the [SourceRetryBook] and tried again once due. */
    TRANSIENT,

    /** Not attempted, because the pass had already spent its download budget. Left pending, with no
     *  backoff, for the next pass. */
    OVER_BUDGET,

    /** Not attempted for a reason outside the photo: off Wi-Fi, or the walk standing down. Left pending,
     *  with no backoff. */
    DEFERRED,
}

/**
 * Carries why one photo's source load came back empty, from the load step that gave up to the walk that
 * acts on it. Confined to one photo on one worker. A load that gave up without naming a reason reads as
 * [SourceMiss.TRANSIENT], the choice that neither settles the photo nor skips its backoff.
 */
class SourceMissNote {
    @Volatile
    var reason: SourceMiss = SourceMiss.TRANSIENT
        private set

    /** Notes [reason] and returns null, so a load step gives up with `return note.giveUp(reason)`. */
    fun giveUp(reason: SourceMiss): Nothing? {
        this.reason = reason
        return null
    }
}

/**
 * How a failed full-resolution download counts: permanent only when the download service reports the
 * photo itself unavailable ([PhotoUnavailableException]); any other failure (the connection, a timeout,
 * the crypto service, a busy server, or none recorded) is transient.
 */
fun sourceMissForDownloadFailure(error: Throwable?): SourceMiss =
    if (error is PhotoUnavailableException) SourceMiss.PERMANENT else SourceMiss.TRANSIENT

/**
 * How a downloaded original that yielded no bitmap counts: permanent when the decoder read the file and
 * could make no image of it ([unreadableFormat]), transient when the decode itself failed (out of memory,
 * the file gone) or ran past its watchdog.
 */
fun sourceMissForUndecodedDownload(unreadableFormat: Boolean): SourceMiss =
    if (unreadableFormat) SourceMiss.PERMANENT else SourceMiss.TRANSIENT

/**
 * How a device file that yielded no bitmap counts: permanent when the file opened and the decoder could
 * make no image of it (a corrupt or unsupported file), transient when it could not be opened (the card
 * unmounted, a provider that answered nothing) or the decode itself threw (out of memory).
 */
fun sourceMissForUndecodedLocal(readable: Boolean, decodeThrew: Boolean): SourceMiss =
    if (readable && !decodeThrew) SourceMiss.PERMANENT else SourceMiss.TRANSIENT

/** How many times in a row a device file may fail to open before the walk settles it: a card that is
 *  only unmounted comes back within a few passes, a row whose file is gone never does. */
const val LOCAL_UNREADABLE_ATTEMPTS = 3

/**
 * A device file's miss after [priorAttempts] earlier failed attempts: a transient miss that has now
 * repeated [LOCAL_UNREADABLE_ATTEMPTS] times counts as permanent, so a library row whose file is gone
 * cannot keep a walk pending for good. Any other miss is returned as it is.
 */
fun settledLocalMiss(miss: SourceMiss, priorAttempts: Int): SourceMiss =
    if (miss == SourceMiss.TRANSIENT && priorAttempts + 1 >= LOCAL_UNREADABLE_ATTEMPTS) SourceMiss.PERMANENT else miss

/** The waits between a photo's transient failure and its next attempt: five minutes, thirty minutes, two
 *  hours, then six hours for every later one. */
val SOURCE_RETRY_BACKOFF_MS: List<Long> = listOf(5L * 60_000L, 30L * 60_000L, 2L * 3_600_000L, 6L * 3_600_000L)

/** At most this many due retries join one pass, a quarter of the walks' per-pass download budget, so
 *  photos that keep failing can never take the budget from ones not yet tried. */
const val SOURCE_RETRIES_PER_PASS = 16

/**
 * One pass's visiting order ([queue]): every never-tried photo first, in library order, then the due
 * retries, at most the pass's cap of them. [retries] is how many of the queue are retries, [waiting] how
 * many retries sit this pass out because they are not due yet, and [heldBack] how many due ones the cap
 * left for a later pass.
 */
class SourcePassPlan<T>(val queue: List<T>, val retries: Int, val waiting: Int, val heldBack: Int)

/**
 * The record of photos whose source failed transiently: how many attempts each has had and when it is
 * due again, on the [backoffMs] ladder. Each walk keeps its own, never in the library database; it saves
 * the book to its [MlWalkStateStore] at the end of a pass and takes it back with [restore] at its first
 * pass in a new process, so a process the system killed does not begin the backoffs again. A successful
 * load, a settle, or a sign-out or rescan drops the record. Thread-safe, since a pass's workers file their
 * outcomes concurrently.
 */
class SourceRetryBook(private val backoffMs: List<Long> = SOURCE_RETRY_BACKOFF_MS) {
    private class Entry(val attempts: Int, val dueMs: Long)

    private val entries = HashMap<String, Entry>()

    /** Files one more transient failure for [key] at [nowMs]; it is due again one ladder step later. */
    @Synchronized
    fun recordFailure(key: String, nowMs: Long) {
        val attempts = (entries[key]?.attempts ?: 0) + 1
        val step = backoffMs[(attempts - 1).coerceAtMost(backoffMs.size - 1)]
        entries[key] = Entry(attempts, nowMs + step)
    }

    /** Drops [key]'s record: its source loaded, or the photo was settled. */
    @Synchronized
    fun forget(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** How many transient failures [key] has had in a row, 0 when it has no record. */
    @Synchronized
    fun attempts(key: String): Int = entries[key]?.attempts ?: 0

    /** When [key] is due again, or null when it has no record. */
    @Synchronized
    fun dueMs(key: String): Long? = entries[key]?.dueMs

    /** How many photos hold a record, due or not. */
    @Synchronized
    fun size(): Int = entries.size

    /** The records as they are kept on disk: each due time on the wall clock, given both clocks now. */
    @Synchronized
    fun export(nowMs: Long, nowEpochMs: Long): List<RetryRecord> =
        entries.entries.take(MAX_PERSISTED_RETRIES).map { (key, entry) ->
            RetryRecord(key, entry.attempts, epochFromElapsed(entry.dueMs, nowMs, nowEpochMs))
        }

    /**
     * Takes back records [export]ed by an earlier process. A record keeps its attempt count, so a photo that
     * keeps failing climbs the ladder across restarts instead of beginning it again; its wait is what is
     * left of the one it was given, never more than the longest step of the ladder, so a wall clock that
     * was set back cannot park a photo for good. A record already due is due at once. Photos that already
     * hold a record in this process keep it.
     */
    @Synchronized
    fun restore(records: List<RetryRecord>, nowMs: Long, nowEpochMs: Long) {
        val longest = backoffMs.last()
        for (record in records.take(MAX_PERSISTED_RETRIES)) {
            if (entries.containsKey(record.key)) continue
            val remaining = (record.dueEpochMs - nowEpochMs).coerceIn(0L, longest)
            entries[record.key] = Entry(record.attempts.coerceAtLeast(1), nowMs + remaining)
        }
    }

    /** When the earliest record not yet due at [nowMs] falls due, or null when none waits past it. */
    @Synchronized
    fun nextDueAfter(nowMs: Long): Long? {
        var earliest: Long? = null
        for (entry in entries.values) {
            if (entry.dueMs > nowMs && (earliest == null || entry.dueMs < earliest)) earliest = entry.dueMs
        }
        return earliest
    }

    /**
     * Orders [pending] for one pass at [nowMs]: never-tried photos first, then the due retries up to
     * [maxRetries], each group in library order; a retry not yet due is left out. Records for photos no
     * longer pending (loaded elsewhere, or gone from the library) are dropped on the way.
     */
    @Synchronized
    fun <T> planPass(pending: List<T>, keyOf: (T) -> String, nowMs: Long, maxRetries: Int): SourcePassPlan<T> {
        val fresh = ArrayList<T>(pending.size)
        val due = ArrayList<T>()
        val seen = HashSet<String>()
        var waiting = 0
        var heldBack = 0
        for (item in pending) {
            val key = keyOf(item)
            val entry = entries[key]
            if (entry == null) {
                fresh.add(item)
                continue
            }
            seen.add(key)
            when {
                nowMs < entry.dueMs -> waiting++
                due.size < maxRetries -> due.add(item)
                else -> heldBack++
            }
        }
        entries.keys.retainAll(seen)
        fresh.addAll(due)
        return SourcePassPlan(fresh, due.size, waiting, heldBack)
    }
}

/**
 * What one walk pass's source loads came to, gathered by its workers, filing each outcome with [book].
 * [moved] counts the photos the pass moved on (a source loaded, or the photo settled without one); the
 * rest say why photos stayed pending. Shared by the pass's workers, so every counter is atomic.
 */
class SourcePassTally(private val book: SourceRetryBook) {
    private val movedOn = AtomicInteger(0)
    private val permanentMisses = AtomicInteger(0)
    private val transientMisses = AtomicInteger(0)
    private val overBudgetMisses = AtomicInteger(0)
    private val deferredMisses = AtomicInteger(0)

    /** How many transient failures in a row [key] has had in this process. */
    fun attempts(key: String): Int = book.attempts(key)

    val moved: Int get() = movedOn.get()
    val permanent: Int get() = permanentMisses.get()
    val transient: Int get() = transientMisses.get()
    val overBudget: Int get() = overBudgetMisses.get()

    /** Photos left for a reason outside them ([SourceMiss.DEFERRED]): off Wi-Fi, or the walk standing down. */
    val deferred: Int get() = deferredMisses.get()

    /** [key] leaves the pending set: its source loaded, or it was settled without one, [permanent] when
     *  for a permanent miss. Any retry record goes with it. */
    fun movedOn(key: String, permanent: Boolean = false) {
        book.forget(key)
        movedOn.incrementAndGet()
        if (permanent) permanentMisses.incrementAndGet()
    }

    /** [key] stays pending for [reason]: a transient miss is filed for a backoff from [nowMs], an
     *  over-budget or deferred one only counted. */
    fun stayed(key: String, reason: SourceMiss, nowMs: Long) {
        when (reason) {
            SourceMiss.TRANSIENT -> {
                book.recordFailure(key, nowMs)
                transientMisses.incrementAndGet()
            }
            SourceMiss.OVER_BUDGET -> overBudgetMisses.incrementAndGet()
            SourceMiss.DEFERRED -> deferredMisses.incrementAndGet()
            SourceMiss.PERMANENT -> Unit
        }
    }
}

/**
 * How long a walk that settled waits before it kicks itself again for its waiting retries: until the
 * earliest falls due ([nextDueMs]), but never less than [floorMs], so a walk whose every attempt failed
 * comes back at the pace of the first backoff rather than at once. Null when no retry waits.
 */
fun retryKickDelayMs(nextDueMs: Long?, nowMs: Long, floorMs: Long = SOURCE_RETRY_BACKOFF_MS.first()): Long? =
    nextDueMs?.let { maxOf(it - nowMs, floorMs) }

/**
 * The one delayed re-kick a settled walk leaves for its earliest waiting retry. A new [schedule] replaces
 * the pending one, and [cancel] drops it; the kick clears itself before it runs, so a walk that cancels
 * at its start never cancels the kick that started it.
 */
class WalkRetryKick(private val scope: CoroutineScope) {
    private val pending = AtomicReference<Job?>(null)

    fun schedule(delayMs: Long, kick: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMs)
            pending.compareAndSet(coroutineContext[Job], null)
            kick()
        }
        pending.getAndSet(job)?.cancel()
        job.start()
    }

    fun cancel() {
        pending.getAndSet(null)?.cancel()
    }
}
