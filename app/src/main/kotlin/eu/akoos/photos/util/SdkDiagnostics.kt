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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * How the operations switched over to the Drive SDK ran in this process: per route, how many went through
 * the SDK, how many fell back to the app's own path, how many failed with an outcome the app could not
 * confirm (nothing else runs in their place), what each of those failures was, and the last one in full.
 * A release build strips every log line, so this is how a tester's copied diagnostics show the SDK really
 * ran and why it did not. Fixed route labels, class names and codes only in the tally; the last failure
 * keeps a short redacted message.
 */
object SdkDiagnostics {
    private val viaSdk = ConcurrentHashMap<String, AtomicInteger>()
    private val fellBack = ConcurrentHashMap<String, AtomicInteger>()
    private val unconfirmed = ConcurrentHashMap<String, AtomicInteger>()

    /** Per route, how often each kind of failure happened, in the order first seen; at most [MAX_KINDS] kinds. */
    private val kinds = HashMap<String, LinkedHashMap<String, Int>>()

    @Volatile
    private var lastFallback: String? = null

    private val _changes = MutableStateFlow(0)

    /** Moves on at every count, so a screen showing the counts redraws. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun recordSdk(route: String) {
        viaSdk.getOrPut(route) { AtomicInteger() }.incrementAndGet()
        _changes.value++
    }

    /**
     * Counts a fallback of [route] to the app's own path; [cause] is a short, redacted description of the
     * failure, kept as the last one, and [kind] its classes and codes, tallied per route.
     */
    fun recordFallback(route: String, cause: String, kind: String = cause) {
        fellBack.getOrPut(route) { AtomicInteger() }.incrementAndGet()
        recordFailure(route, cause, kind)
    }

    /** Counts an SDK failure of [route] whose outcome could not be confirmed, so nothing ran in its place. */
    fun recordUnconfirmed(route: String, cause: String, kind: String = cause) {
        unconfirmed.getOrPut(route) { AtomicInteger() }.incrementAndGet()
        recordFailure(route, cause, kind)
    }

    private fun recordFailure(route: String, cause: String, kind: String) {
        synchronized(kinds) {
            val tally = kinds.getOrPut(route) { LinkedHashMap() }
            val key = if (kind in tally || tally.size < MAX_KINDS) kind else OTHER_KINDS
            tally[key] = (tally[key] ?: 0) + 1
        }
        lastFallback = "$route $cause"
        _changes.value++
    }

    fun sdkCount(route: String): Int = viaSdk[route]?.get() ?: 0

    /** The failures of [route] the app's own path took over; an unconfirmed outcome is not one of them. */
    fun fallbackCount(route: String): Int = fellBack[route]?.get() ?: 0

    fun unconfirmedCount(route: String): Int = unconfirmed[route]?.get() ?: 0

    fun hasData(): Boolean = viaSdk.isNotEmpty() || fellBack.isNotEmpty() || unconfirmed.isNotEmpty()

    /** One line per route that ran, each followed by its kinds of failure with their counts, then the last failure. */
    fun snapshot(): String = buildString {
        val tallies = synchronized(kinds) { kinds.mapValues { (_, tally) -> tally.toList() } }
        for (route in (viaSdk.keys + fellBack.keys + unconfirmed.keys).sorted()) {
            if (isNotEmpty()) append('\n')
            append(route).append(": sdk=").append(sdkCount(route)).append(" fallback=").append(fallbackCount(route))
            if (unconfirmedCount(route) > 0) append(" unconfirmed=").append(unconfirmedCount(route))
            tallies[route]?.forEach { (kind, count) -> append("\n  ").append(count).append(" x ").append(kind) }
        }
        lastFallback?.let { append("\nlastFallback=").append(it) }
    }

    fun clear() {
        viaSdk.clear()
        fellBack.clear()
        unconfirmed.clear()
        synchronized(kinds) { kinds.clear() }
        lastFallback = null
        _changes.value++
    }

    private const val MAX_KINDS = 6
    private const val OTHER_KINDS = "other"
}
