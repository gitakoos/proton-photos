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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One value read by [query] and shared by everyone who observes it, kept current by change signals.
 *
 * While [shared] has a collector the source listens for changes once, through [listen], reads once
 * to start, and reads again for each settled burst of signals: after [quietMs] without a new signal,
 * or [maxWaitMs] after the first signal of the burst, so a steady stream of changes still refreshes
 * on a bounded interval. Every collector sees the same value, and a re-read equal to the current one
 * is not published again. [stopTimeoutMs] after the last collector leaves the source stops listening
 * and drops its value, so nothing holds a copy no one reads, and the next collector starts from a
 * new read.
 *
 * A failed read is reported to [onReadFailure] and leaves the current value in place; it is retried
 * on its own after a growing pause, so a collector still waiting for a first value is not left
 * waiting on a change that may never come. Running out of memory is not retried: it ends the source,
 * which keeps the value it holds.
 *
 * [fresh] is for a one-shot reader that must act on what the store holds now, with the rules of
 * [FreshQueryGate]. It reads through the same gate as the source, so two reads never run at once,
 * and what it reads also reaches [shared] while anyone collects it.
 *
 * [isStale] covers what the store never signals. It is asked whenever a collector arrives at a value
 * already held, and a true answer requests a re-read; the arriving collector still gets the held
 * value at once and the re-read after it.
 */
internal class SharedQuerySource<T : Any>(
    private val scope: CoroutineScope,
    private val listen: (onChange: () -> Unit) -> () -> Unit,
    private val quietMs: Long,
    private val maxWaitMs: Long,
    stopTimeoutMs: Long,
    private val isStale: () -> Boolean = { false },
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onReadFailure: (Throwable) -> Unit = {},
    private val onEvent: (String) -> Unit = {},
    query: suspend () -> T,
) {
    /** Counts every change the store has signalled, so a read can say which changes it began after. */
    private val changeVersion = java.util.concurrent.atomic.AtomicLong()

    /** What the latest finished read found, and the change count it began under and the time it finished. */
    private class Read<T>(val value: T, val version: Long, val atMs: Long)

    /** The latest finished read, kept only while the source runs, so it holds no copy the shared value
     *  does not hold already. */
    @Volatile private var lastRead: Read<T>? = null

    /** Orders storing a finished read against the source stopping, so a read that finishes after the stop
     *  cannot leave a copy behind. */
    private val readLock = Any()

    /** True from the moment the listener is registered until the source stops. */
    private var listening = false

    /** A read under way, and the change count it began under, so [recent] can wait for it instead of
     *  queueing a second whole read right behind it. */
    private class InFlight<T>(val version: Long, val result: CompletableDeferred<T>)

    @Volatile private var inFlight: InFlight<T>? = null

    private val gate = FreshQueryGate(scope) {
        val version = changeVersion.get()
        val running = InFlight(version, CompletableDeferred<T>())
        inFlight = running
        try {
            val value = query()
            synchronized(readLock) {
                if (listening) {
                    // A read equal to the shared value is stored as that value, so one copy of the list is held.
                    val shared = latest.value
                    val kept = if (shared != null && shared == value) shared else value
                    lastRead = Read(kept, version, now())
                }
            }
            running.result.complete(value)
            value
        } catch (e: Throwable) {
            running.result.completeExceptionally(e)
            throw e
        } finally {
            if (inFlight === running) inFlight = null
        }
    }

    /** Change signals of the running source; null while nothing collects. */
    @Volatile private var signals: SendChannel<Unit>? = null

    private val latest: StateFlow<T?> = channelFlow<T> {
        // Subscribed before the first read can finish, so no result is missed, including one a
        // one-shot reader asked for.
        launch(start = CoroutineStart.UNDISPATCHED) { gate.results.collect { send(it) } }
        val pending = Channel<Unit>(Channel.CONFLATED)
        // Listening starts before the first read, so a change landing during it is signalled
        // rather than lost.
        val stopListening: (() -> Unit)? = try {
            listen {
                changeVersion.incrementAndGet()
                onEvent("signal")
                pending.trySend(Unit)
            }.also {
                // A read that began before the listener was there cannot vouch for what changed meanwhile.
                changeVersion.incrementAndGet()
                synchronized(readLock) { listening = true }
            }
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            ensureActive()
            onReadFailure(e)
            null
        }
        signals = pending
        var failedReads = 0L
        suspend fun read() {
            try {
                gate.fresh()
                failedReads = 0L
            } catch (e: Throwable) {
                // Out of memory is no failure to retry: the next whole read would run out again, and
                // again after that, for good. Cancellation ends the source as it ends anything else.
                if (e is VirtualMachineError || e is CancellationException) throw e
                ensureActive()
                onReadFailure(e)
                val pauseMs = retryBackoffMs(failedReads++)
                launch {
                    delay(pauseMs)
                    pending.trySend(Unit)
                }
            }
        }
        try {
            read()
            settle(pending) { read() }
        } finally {
            if (signals === pending) {
                signals = null
            }
            changeVersion.incrementAndGet()
            synchronized(readLock) {
                listening = false
                lastRead = null
            }
            stopListening?.let { stop -> runCatching { stop() } }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMs, replayExpirationMillis = 0L), null)

    /** The shared value. A new collector gets the one already held at once, else the first read's. */
    val shared: Flow<T> = latest
        .onSubscription { if (latest.value != null) scope.launch { if (isStale()) requestRefresh() } }
        .filterNotNull()

    /** Asks for a re-read. Does nothing while no one collects, since the next collector reads anyway. */
    fun requestRefresh() {
        changeVersion.incrementAndGet()
        onEvent("refresh")
        signals?.trySend(Unit)
    }

    /** A value read after this call began; see [FreshQueryGate.fresh]. Throws when the read fails. */
    suspend fun fresh(): T {
        onEvent("fresh")
        return gate.fresh()
    }

    /**
     * What [fresh] answers, without the read when the source already holds one that still is it: the latest
     * read finished no more than [maxAgeMs] ago, no change has been signalled since it began, nothing
     * [isStale] knows of has moved, and [reusable] accepts it. Otherwise a read as [fresh] runs. While the
     * store is quiet this lets a burst of one-shot readers (a reconcile, a backfill walk, an upload pass)
     * share the read the source already made, instead of each running its own whole-library scan.
     *
     * A read still under way is waited for and answered in the same way, while the source listens and no
     * change has been signalled since it began: [fresh] would only queue another whole read behind it.
     */
    suspend fun recent(maxAgeMs: Long, reusable: (T) -> Boolean = { true }): T {
        val held = lastRead
        val version = changeVersion.get()
        val miss = when {
            held == null -> "none"
            held.version != version -> "changed"
            now() - held.atMs > maxAgeMs -> "old"
            isStale() -> "stale"
            !reusable(held.value) -> "partial"
            else -> null
        }
        if (miss == null) {
            onEvent("reuse-held")
            return held!!.value
        }
        val running = inFlight
        if (running != null && running.version == version && synchronized(readLock) { listening } && !isStale()) {
            val value = try {
                running.result.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                null
            }
            if (value != null && changeVersion.get() == version && reusable(value)) {
                onEvent("reuse-joined")
                return value
            }
        }
        onEvent("miss-$miss")
        return gate.fresh()
    }

    /** Waits out each burst of [pending] signals, then runs [read] once for the whole burst. */
    private suspend fun settle(pending: ReceiveChannel<Unit>, read: suspend () -> Unit) {
        while (true) {
            pending.receive()
            val deadline = now() + maxWaitMs
            while (true) {
                val left = deadline - now()
                if (left <= 0L) break
                withTimeoutOrNull(minOf(quietMs, left)) { pending.receive() } ?: break
            }
            read()
        }
    }
}
