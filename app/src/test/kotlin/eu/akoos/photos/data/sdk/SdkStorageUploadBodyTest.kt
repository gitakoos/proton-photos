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

package eu.akoos.photos.data.sdk

import kotlinx.coroutines.runBlocking
import me.proton.drive.sdk.SdkHttpRequest
import me.proton.drive.sdk.SdkHttpRequestType
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Pins how an SDK block upload goes out through [SdkHttpApi]: the block's multipart body is read from a stream
 * once, so it is never sent a second time. The SDK's Content-Type (the multipart boundary) used to go in the
 * header map, where Retrofit wraps the body and drops its one-shot flag, so OkHttp re-sent the request on a 408
 * with the stream already drained: an empty body instead of the multipart with the block in it.
 */
class SdkStorageUploadBodyTest {

    private val servers = mutableListOf<RecordingServer>()

    @After
    fun stop() = servers.forEach { it.close() }

    private val boundary = "-----------------------------0123456789abcdef"
    private val contentType = "multipart/form-data; boundary=\"$boundary\""

    /** A multipart body shaped like the SDK's block upload, [size] bytes of block data in it. */
    private fun multipart(size: Int): ByteArray {
        val head = "--$boundary\r\nContent-Disposition: form-data; name=Block; filename=blob\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val block = ByteArray(size) { (it * 31 + 7).toByte() }
        return head.toByteArray() + block + "\r\n--$boundary--\r\n".toByteArray()
    }

    private fun storageUpload(url: String, body: ByteArray, headers: Map<String, String> = sdkHeaders()) = SdkHttpRequest(
        type = SdkHttpRequestType.STORAGE_UPLOAD,
        method = "POST",
        url = url,
        headers = headers,
        body = ByteArrayInputStream(body),
        contentLength = -1L,
    )

    private fun sdkHeaders() = mapOf("Content-Type" to contentType, "pm-storage-token" to "token")

    private fun api(server: RecordingServer): SdkHttpApi = Retrofit.Builder()
        .baseUrl(server.baseUrl)
        .client(OkHttpClient())
        .build()
        .create(SdkHttpApi::class.java)

    private fun server(vararg statuses: Int) = RecordingServer(statuses.toList()).also { servers += it }

    @Test
    fun `a block the storage answers with a 408 is never sent again with a drained body`() = runBlocking {
        val server = server(408, 200)
        val body = multipart(256 * 1024)
        val request = storageUpload(server.url, body)

        runCatching { api(server).send(request, request.toOutgoing()) }

        assertTrue("the block reached the storage", server.received.isNotEmpty())
        server.received.forEachIndexed { index, received ->
            assertArrayEquals("request ${index + 1} carried the whole block", body, received.body)
        }
    }

    @Test
    fun `the multipart boundary reaches the storage unchanged`() = runBlocking {
        val server = server(200)
        val body = multipart(4096)
        val request = storageUpload(server.url, body)

        val response = api(server).send(request, request.toOutgoing())

        assertEquals(200, response.code())
        assertEquals(1, server.received.size)
        assertEquals(contentType, server.received.single().headers["content-type"])
        assertEquals("token", server.received.single().headers["pm-storage-token"])
        assertArrayEquals(body, server.received.single().body)
    }

    @Test
    fun `the boundary goes out unchanged whatever case the SDK names the header in`() = runBlocking {
        val server = server(200)
        val body = multipart(1024)
        val request = storageUpload(server.url, body, mapOf("content-type" to contentType))

        api(server).send(request, request.toOutgoing())

        assertEquals(contentType, server.received.single().headers["content-type"])
        assertArrayEquals(body, server.received.single().body)
    }

    @Test
    fun `a block body sent a second time fails rather than sending nothing`() {
        val outgoing = storageUpload("https://storage.invalid/upload", multipart(2048)).toOutgoing()
        val first = Buffer()
        outgoing.body.writeTo(first)
        assertTrue(first.size > 0)

        try {
            outgoing.body.writeTo(Buffer())
            fail("a second send of a drained block went through")
        } catch (_: IOException) {
            // Expected: the sign-in stack can run a call again, which must fail the request, never send it empty.
        }
    }

    @Test
    fun `a block body tells OkHttp it can be sent only once`() {
        val outgoing = storageUpload("https://storage.invalid/upload", multipart(16)).toOutgoing()
        assertTrue(outgoing.body.isOneShot())
        assertEquals(contentType, outgoing.body.contentType().toString())
        assertTrue(outgoing.headers.keys.none { it.equals("Content-Type", ignoreCase = true) })
    }

    @Test
    fun `an API body stays in memory and can be sent again`() {
        val json = """{"Name":"x"}""".toByteArray()
        val outgoing = SdkHttpRequest(
            type = SdkHttpRequestType.API,
            method = "POST",
            url = "https://api.invalid/drive",
            headers = mapOf("Content-Type" to "application/json"),
            body = ByteArrayInputStream(json),
            contentLength = json.size.toLong(),
        ).toOutgoing()

        repeat(2) {
            val sink = Buffer()
            outgoing.body.writeTo(sink)
            assertArrayEquals(json, sink.readByteArray())
        }
        assertEquals("application/json", outgoing.headers["Content-Type"])
    }

    /**
     * A minimal HTTP/1.1 server on the loopback: records each request's headers and body (chunked or sized) and
     * answers them with [statuses] in order, 200 once they run out.
     */
    private class RecordingServer(private val statuses: List<Int>) : Closeable {
        class Received(val headers: Map<String, String>, val body: ByteArray)

        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val connections = CopyOnWriteArrayList<Socket>()
        val received = CopyOnWriteArrayList<Received>()
        val baseUrl = "http://127.0.0.1:${socket.localPort}/"
        val url = baseUrl + "storage/upload"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val connection = runCatching { socket.accept() }.getOrNull() ?: break
                    connections += connection
                    thread(isDaemon = true) { serve(connection) }
                }
            }
        }

        private fun serve(connection: Socket) = runCatching {
            connection.use {
                val input = BufferedInputStream(it.getInputStream())
                val output = it.getOutputStream()
                while (true) {
                    val requestLine = input.readLine() ?: break
                    if (requestLine.isEmpty()) continue
                    val headers = LinkedHashMap<String, String>()
                    while (true) {
                        val line = input.readLine() ?: return@use
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                    }
                    val body = when {
                        headers["transfer-encoding"].equals("chunked", ignoreCase = true) -> input.readChunked()
                        headers["content-length"] != null -> input.readExactly(headers.getValue("content-length").toInt())
                        else -> ByteArray(0)
                    }
                    val status = statuses.getOrElse(received.size) { 200 }
                    received += Received(headers, body)
                    output.write("HTTP/1.1 $status Status\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    output.flush()
                }
            }
        }

        override fun close() {
            runCatching { socket.close() }
            connections.forEach { runCatching { it.close() } }
        }

        private fun InputStream.readLine(): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = read()
                if (b == -1) return if (line.size() == 0) null else line.toString(Charsets.US_ASCII.name())
                if (b == '\n'.code) return line.toString(Charsets.US_ASCII.name()).trimEnd('\r')
                line.write(b)
            }
        }

        private fun InputStream.readExactly(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var done = 0
            while (done < count) {
                val n = read(bytes, done, count - done)
                if (n == -1) throw IOException("body ended early")
                done += n
            }
            return bytes
        }

        private fun InputStream.readChunked(): ByteArray {
            val body = ByteArrayOutputStream()
            while (true) {
                val size = (readLine() ?: throw IOException("chunk size missing")).substringBefore(';').trim().toInt(16)
                if (size == 0) {
                    while ((readLine() ?: "").isNotEmpty()) Unit
                    return body.toByteArray()
                }
                body.write(readExactly(size))
                readLine()
            }
        }
    }
}
