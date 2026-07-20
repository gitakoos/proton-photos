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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.BindException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLKeyException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException

/**
 * Pins [looksLikeNetworkError], the matcher that decides whether a failure gets the friendly
 * "you're offline / try again" copy instead of a raw error. Matching by type rather than by class
 * name is the load-bearing part: the SSL and Socket families are almost always raised as a subclass,
 * so a matcher that only recognised the base name would show a transport hiccup as a hard failure.
 */
class NetworkErrorHandlerTest {

    @Test
    fun `each base transport failure is recognised`() {
        assertTrue(looksLikeNetworkError(UnknownHostException("api.example")))
        assertTrue(looksLikeNetworkError(SSLException("tls")))
        assertTrue(looksLikeNetworkError(SocketException("socket")))
        assertTrue(looksLikeNetworkError(InterruptedIOException("interrupted")))
        assertTrue(looksLikeNetworkError(ProtocolException("bad protocol")))
        assertTrue(looksLikeNetworkError(EOFException("stream ended")))
    }

    @Test
    fun `every SSL subclass is recognised, not just the base type`() {
        assertTrue(looksLikeNetworkError(SSLHandshakeException("handshake failed")))
        assertTrue(looksLikeNetworkError(SSLPeerUnverifiedException("peer not verified")))
        assertTrue(looksLikeNetworkError(SSLProtocolException("protocol error")))
        assertTrue(looksLikeNetworkError(SSLKeyException("bad key")))
    }

    @Test
    fun `socket and timeout subclasses are recognised`() {
        assertTrue(looksLikeNetworkError(SocketTimeoutException("timeout")))
        assertTrue(looksLikeNetworkError(ConnectException("connect")))
        assertTrue(looksLikeNetworkError(NoRouteToHostException("no route")))
        assertTrue(looksLikeNetworkError(PortUnreachableException("port")))
        assertTrue(looksLikeNetworkError(BindException("bind")))
    }

    @Test
    fun `a network failure buried in the cause chain is found`() {
        val wrapped = RuntimeException("wrapper", SSLHandshakeException("handshake failed"))
        assertTrue(looksLikeNetworkError(wrapped))
        // Retrofit routinely stacks several layers before the transport exception shows up.
        val deep = IllegalStateException("outer", IOException("middle", ConnectException("connect")))
        assertTrue(looksLikeNetworkError(deep))
    }

    @Test
    fun `a wrapper message does not hide a matching cause message`() {
        // The wrapper answers message with its own text, so reading only the top of the chain
        // would miss the transport phrase carried by the cause.
        val wrapped = RuntimeException(
            "could not load album",
            IOException("Unable to resolve host \"drive.proton.me\""),
        )
        assertTrue(looksLikeNetworkError(wrapped))
    }

    @Test
    fun `a transport phrase in the message is recognised without a matching type`() {
        assertTrue(looksLikeNetworkError(RuntimeException("failed to connect to /10.0.0.1:443")))
        assertTrue(looksLikeNetworkError(RuntimeException("Connection refused")))
        assertTrue(looksLikeNetworkError(RuntimeException("Software caused connection abort")))
    }

    @Test
    fun `a non-network failure is left alone`() {
        assertFalse(looksLikeNetworkError(IllegalArgumentException("bad album id")))
        assertFalse(looksLikeNetworkError(IllegalStateException("view model already cleared")))
        assertFalse(looksLikeNetworkError(RuntimeException("decryption failed for this photo")))
        assertFalse(looksLikeNetworkError(RuntimeException(null as String?)))
    }

    @Test
    fun `nothing to classify is not a network failure`() {
        assertFalse(looksLikeNetworkError(null))
    }

    @Test(timeout = 5_000)
    fun `a cyclic cause chain terminates`() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)
        assertFalse(looksLikeNetworkError(first))

        // The same loop must not swallow a match that sits inside it.
        val outer = RuntimeException("outer")
        val inner = ConnectException("connect")
        inner.initCause(outer)
        outer.initCause(inner)
        assertTrue(looksLikeNetworkError(outer))
    }
}
