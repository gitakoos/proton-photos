/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
 * Privacy-safe, in-memory diagnostics ring buffer for the cloud-sync pipeline.
 *
 * Two reasons this is a custom buffer rather than [android.util.Log]:
 *   • Release builds minify out `android.util.Log` calls, so log statements would vanish from the
 *     exact builds a tester runs. The entries live here so they survive a release build and can be
 *     copied out of Settings.
 *   • A tester pastes the dump into a public issue, so the content must never identify the account.
 *
 * Callers MUST pass only NON-IDENTIFYING strings: counts, phase markers, timings, flags, and
 * exception class names ([Throwable.javaClass]'s simpleName). Never an email, user id, display
 * name, file name, link / share / volume id, cursor value, token, URL, or photo content.
 *
 * Thread-safe: every entry point is [Synchronized] on the singleton, so concurrent refresh
 * coroutines on different dispatcher threads can append without interleaving partial lines.
 */
object SyncDiagnostics {

    // ~600 recent lines is enough to span a full large-library walk (listing pages + detail
    // batches) while keeping the retained text small; the oldest line is evicted past the cap.
    private const val MAX_LINES = 600

    private val lines = ArrayDeque<String>(MAX_LINES)

    // Anchor for the relative timestamp: the wall-clock of the FIRST logged line. Relative stamps
    // (rather than absolute clock times) keep the dump free of any locally-identifying timeline.
    private var startedAtMs: Long = 0L

    @Synchronized
    fun log(message: String) {
        val now = System.currentTimeMillis()
        if (startedAtMs == 0L) startedAtMs = now
        val elapsedSeconds = (now - startedAtMs) / 1000.0
        val stamp = String.format(Locale.US, "[%7.1fs] %s", elapsedSeconds, message)
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(stamp)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun isEmpty(): Boolean = lines.isEmpty()

    @Synchronized
    fun clear() {
        lines.clear()
        startedAtMs = 0L
    }
}
