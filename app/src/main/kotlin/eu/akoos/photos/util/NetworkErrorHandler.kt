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

import android.content.Context
import eu.akoos.photos.R
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Bounds the cause walk so a cyclic chain terminates instead of spinning. */
private const val MAX_CAUSE_DEPTH = 16

private val NETWORK_MESSAGE_PHRASES = listOf(
    "unable to resolve host",
    "no address associated with hostname",
    "failed to connect",
    "connection refused",
    "network is unreachable",
    "connection reset by peer",
    "connection timed out",
    "software caused connection abort",
)

/**
 * True when the throwable (or any cause in the chain) looks like an OkHttp/Retrofit
 * networking failure. Walks the cause chain because Retrofit usually wraps the
 * underlying OkHttp IOException several layers deep.
 *
 * Matched by type rather than by class name, so every subclass the JDK and Conscrypt
 * raise (SSLHandshakeException, ConnectException, SocketTimeoutException, ...) is
 * covered without naming it here.
 *
 * Centralised so every ViewModel can share the same shape-matcher instead of each one
 * copying the list and drifting out of sync.
 */
fun looksLikeNetworkError(t: Throwable?): Boolean {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth++ < MAX_CAUSE_DEPTH) {
        // ProtocolException extends IOException directly, so SocketException does not cover it.
        if (cur is UnknownHostException || cur is SSLException ||
            cur is SocketException || cur is InterruptedIOException ||
            cur is ProtocolException || cur is EOFException) return true
        val n = cur.javaClass.name
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
        // Last-resort sniff on the message string. Catches anything OkHttp / HttpURLConnection
        // raises through a wrapper we don't recognise by type. Read at every link, because a
        // wrapper carries its own message and would otherwise hide a matching cause.
        val msg = cur.message?.lowercase().orEmpty()
        if (NETWORK_MESSAGE_PHRASES.any { msg.contains(it) }) return true
        cur = cur.cause
    }
    return false
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
    // almost certainly caused by lack of connectivity from the reader's perspective:
    // "Album link not found" / "Server returned 0 results" / etc. all reduce to the
    // same actionable advice. This also catches wrapper exception types we don't
    // recognise by name OR message: cause was offline regardless of the symptom.
    if (!isOnline) return context.getString(R.string.network_error_offline)
    // Online: only friendly the message when the failure looks network-shaped, so a
    // legitimate non-network error (permission denied, bad input) still surfaces its
    // sanitised original through the caller's fallback.
    if (!looksLikeNetworkError(t)) return null
    return context.getString(R.string.network_error_transient)
}
