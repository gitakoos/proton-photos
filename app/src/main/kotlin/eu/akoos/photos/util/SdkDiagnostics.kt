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
 * the SDK and how many fell back to the app's own path, and the exception class of the last fallback. A
 * release build strips every log line, so this is how a tester's copied diagnostics show the SDK really
 * ran. Fixed route labels and class names only, never a name, id or message.
 */
object SdkDiagnostics {
    private val viaSdk = ConcurrentHashMap<String, AtomicInteger>()
    private val fellBack = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var lastFallback: String? = null

    private val _changes = MutableStateFlow(0)

    /** Moves on at every count, so a screen showing the counts redraws. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun recordSdk(route: String) {
        viaSdk.getOrPut(route) { AtomicInteger() }.incrementAndGet()
        _changes.value++
    }

    fun recordFallback(route: String, error: Throwable) {
        fellBack.getOrPut(route) { AtomicInteger() }.incrementAndGet()
        lastFallback = "$route ${error.javaClass.simpleName}"
        _changes.value++
    }

    fun sdkCount(route: String): Int = viaSdk[route]?.get() ?: 0

    fun fallbackCount(route: String): Int = fellBack[route]?.get() ?: 0

    fun hasData(): Boolean = viaSdk.isNotEmpty() || fellBack.isNotEmpty()

    /** One line per route that ran, then the last fallback. */
    fun snapshot(): String = buildString {
        for (route in (viaSdk.keys + fellBack.keys).sorted()) {
            if (isNotEmpty()) append('\n')
            append(route).append(": sdk=").append(sdkCount(route)).append(" fallback=").append(fallbackCount(route))
        }
        lastFallback?.let { append("\nlastFallback=").append(it) }
    }

    fun clear() {
        viaSdk.clear()
        fellBack.clear()
        lastFallback = null
        _changes.value++
    }
}
