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

import android.content.Context
import eu.akoos.photos.R

/**
 * True when the throwable (or any cause in the chain) looks like an OkHttp/Retrofit
 * networking failure: UnknownHost, SocketTimeout, SocketException, SSLException,
 * InterruptedIOException, ConnectException. Walks the cause chain because Retrofit
 * usually wraps the underlying OkHttp IOException several layers deep.
 *
 * Centralised here so every ViewModel can share the same shape-matcher instead of
 * each one copying the substring list and drifting out of sync.
 */
fun looksLikeNetworkError(t: Throwable?): Boolean {
    var cur: Throwable? = t
    while (cur != null) {
        val n = cur.javaClass.name
        if (n.contains("UnknownHost") || n.contains("SocketTimeout") ||
            n.contains("SocketException") || n.contains("SSLException") ||
            n.contains("InterruptedIOException") || n.contains("ConnectException")) return true
        // ProtonCore's ApiException wraps the underlying IOException in a data-class
        // field (ApiResult.Error.Connection / NoInternet / Timeout) instead of chaining
        // it to Throwable.cause, so the chain walk above can miss it. Inspect the
        // toString() of the wrapper to spot the connectivity variants.
        if (n.contains("ApiException") || n.contains("ApiResult")) {
            val asString = cur.toString()
            if (asString.contains("Connection", ignoreCase = true) ||
                asString.contains("NoInternet", ignoreCase = true) ||
                asString.contains("Timeout", ignoreCase = true)) return true
        }
        cur = cur.cause
    }
    // Last-resort sniff on the message string. Catches anything OkHttp / HttpURLConnection
    // raises through a wrapper we don't recognise by class name.
    val msg = t?.message?.lowercase().orEmpty()
    return msg.contains("unable to resolve host") ||
        msg.contains("no address associated with hostname") ||
        msg.contains("failed to connect") ||
        msg.contains("connection refused") ||
        msg.contains("network is unreachable") ||
        msg.contains("connection reset by peer") ||
        msg.contains("connection timed out") ||
        msg.contains("software caused connection abort")
}

/**
 * Maps a Throwable to a friendly user-facing string when it looks network-shaped;
 * returns null otherwise. Callers fall through to [sanitizeErrorMessage] for
 * non-network exceptions to keep the existing PII-redaction guarantees.
 *
 * Discriminator:
 *  - `isOnline == false` -> "You're offline. Try again when you reconnect."
 *  - `isOnline == true`  -> "Connection failed. Try again later." (likely flaky,
 *    captive portal that lost validation, or a transient DNS hiccup)
 */
fun friendlyNetworkError(
    t: Throwable?,
    isOnline: Boolean,
    context: Context,
): String? {
    if (t == null) return null
    // Offline trumps everything. Any failure while NetworkObserver reports offline is
    // almost certainly caused by lack of connectivity from the user's perspective —
    // "Album link not found" / "Server returned 0 results" / etc. all reduce to the
    // same actionable advice. This also catches wrapper exception types we don't
    // recognise by name OR message: cause was offline regardless of the symptom.
    if (!isOnline) return context.getString(R.string.network_error_offline)
    // Online — only friendly the message when the failure looks network-shaped, so a
    // legitimate non-network error (permission denied, bad input) still surfaces its
    // sanitised original through the caller's fallback.
    if (!looksLikeNetworkError(t)) return null
    return context.getString(R.string.network_error_transient)
}
