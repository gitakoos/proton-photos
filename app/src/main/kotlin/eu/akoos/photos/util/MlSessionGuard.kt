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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Closes a native resource only once no detached run still uses it. A native ONNX run cannot be
 * cancelled once it is in flight, and its caller can return while the run keeps going (a watchdog
 * timeout, or the caller itself cancelled); closing the session then frees memory the run still reads, a
 * native use-after-free that kills the process. Each run is counted by [acquire] before it is launched
 * and uncounted by the release [acquire] returns. [close] with no run in flight runs [closeAction] at
 * once; with runs in flight it only marks the close, and the release that brings the count to zero
 * performs it. [closeAction] runs at most once. Pure so it is unit-tested with fake closeables.
 */
class SessionRunGuard(private val closeAction: () -> Unit) {
    private var inFlight = 0
    private var closeRequested = false
    private var closed = false

    /** Counts one run about to use the resource. The returned release uncounts it on its first call and
     *  ignores any later one, so a completion handler and a failure path can both call it safely. */
    fun acquire(): () -> Unit {
        synchronized(this) { inFlight++ }
        val released = AtomicBoolean(false)
        return { if (released.compareAndSet(false, true)) release() }
    }

    /** Closes the resource now when no run uses it, else once the last run in flight is released. */
    fun close() {
        val closeNow = synchronized(this) {
            closeRequested = true
            takeCloseLocked()
        }
        if (closeNow) runCatching { closeAction() }
    }

    /** How many runs are counted on the resource right now. */
    @Synchronized
    fun runsInFlight(): Int = inFlight

    /** Whether [closeAction] has run. */
    @Synchronized
    fun isClosed(): Boolean = closed

    private fun release() {
        val closeNow = synchronized(this) {
            inFlight--
            takeCloseLocked()
        }
        if (closeNow) runCatching { closeAction() }
    }

    private fun takeCloseLocked(): Boolean {
        if (!closeRequested || inFlight > 0 || closed) return false
        closed = true
        return true
    }
}

/** What one run reads from a guarded instance, and the release that must run once that run is over. */
class SessionLease<R>(val value: R, val release: () -> Unit)

/**
 * A reassignable holder of one set of native sessions (a model, or a detector + embedder pair), each
 * instance behind its own [SessionRunGuard]. The guard is per instance, never shared, so an instance
 * retired while a wedged run still uses it never holds up the fresh one that replaces it.
 *
 * [lease] opens the live instance if needed and counts a run on it; [retire] drops the live instance and
 * closes it at once when idle, or when its last run ends. A watchdog abandon and a normal close are both
 * a [retire]: an abandoned instance closes itself when its wedged run finally returns (never earlier),
 * and a caller cancelled mid-run never closes a session its detached run still uses.
 */
class GuardedSessionHolder<T : AutoCloseable>(private val open: () -> T) {
    private class Entry<T : AutoCloseable>(val instance: T) {
        val guard = SessionRunGuard { instance.close() }
    }

    private var live: Entry<T>? = null

    /** The live instance, or null when none is open. Opens nothing. */
    @Synchronized
    fun peek(): T? = live?.instance

    /**
     * Opens the live instance if none is, reads what a run needs from it with [prepare], and counts one
     * run on it. The lease's release must run exactly once when the run is over; hand it to
     * [boundedDetachedRun] as its run-end callback. A [prepare] that throws counts nothing.
     */
    @Synchronized
    fun <R> lease(prepare: (T) -> R): SessionLease<R> {
        val entry = live ?: Entry(open()).also { live = it }
        val value = prepare(entry.instance)
        return SessionLease(value, entry.guard.acquire())
    }

    /** Drops the live instance, so the next [lease] opens a fresh one, and closes it once no run uses it. */
    @Synchronized
    fun retire() {
        val entry = live ?: return
        live = null
        entry.guard.close()
    }
}

/**
 * Runs [block] detached on [scope] in [context] and waits at most [timeoutMs] for it, so a blocking or
 * wedged call frees the caller instead of pinning it. Returns the block's result, or null when it
 * returned null. On a true timeout it flips [timedOut], cancels the wait and returns null; the work keeps
 * running on its own (a native call cannot be cancelled).
 *
 * [onRunEnd] runs exactly once, when the detached run is over: after the block returns or throws, when
 * the run is cancelled before its body ever starts, or right away when the run is never launched (the
 * caller was already cancelled). It is tied to the run's completion, never the caller's, so a caller
 * cancelled mid-run returns at once while the release still waits for the run.
 */
suspend fun <T> boundedDetachedRun(
    scope: CoroutineScope,
    context: CoroutineContext,
    timeoutMs: Long,
    timedOut: AtomicBoolean? = null,
    onRunEnd: (() -> Unit)? = null,
    block: suspend CoroutineScope.() -> T,
): T? {
    val work = try {
        // A cancelled caller launches nothing: its result would be dropped, and a second run on a
        // session another run still uses is exactly what the owners serialise against.
        currentCoroutineContext().ensureActive()
        scope.async(context, block = block)
    } catch (e: Throwable) {
        onRunEnd?.let { end -> runCatching { end() } }
        throw e
    }
    onRunEnd?.let { end -> work.invokeOnCompletion { runCatching { end() } } }
    val result = withTimeoutOrNull(timeoutMs) { work.await() }
    if (result == null && work.isActive) {
        timedOut?.set(true)
        work.cancel()
    }
    return result
}
