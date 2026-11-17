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

import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.ProtonErrorException
import me.proton.core.network.domain.ApiResult
import me.proton.drive.sdk.SdkHttpClient
import me.proton.drive.sdk.SdkHttpClientException
import me.proton.drive.sdk.SdkHttpRequest
import me.proton.drive.sdk.SdkHttpRequestType
import me.proton.drive.sdk.SdkHttpResponse
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.source
import retrofit2.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends the Drive SDK's requests for [userId] through the sign-in stack's [ApiProvider], which adds the
 * session, refreshes it on a 401 and applies the stack's own rules. A status the server answered with,
 * 4xx and 5xx included, goes back as a response: the SDK does its own retrying on those.
 */
internal class CoreSdkHttpClient(
    private val userId: UserId,
    private val apiProvider: ApiProvider,
) : SdkHttpClient {

    override suspend fun execute(request: SdkHttpRequest): SdkHttpResponse {
        val outgoing = request.toOutgoing()
        val result = apiProvider.get<SdkHttpApi>(userId).invoke(forceNoRetryOnConnectionErrors = true) {
            send(request, outgoing)
        }
        if (result is ApiResult.Error) {
            val cause = result.cause
            if (cause is ProtonErrorException) {
                val response = cause.response
                return SdkHttpResponse(
                    statusCode = response.code,
                    headers = response.headers.toMultimap(),
                    body = response.body?.byteStream(),
                )
            }
            throw result.asSdkException()
        }
        val response = result.valueOrThrow
        return SdkHttpResponse(
            statusCode = response.code(),
            headers = response.headers().toMultimap(),
            body = response.body()?.byteStream(),
        )
    }

    private fun ApiResult.Error.asSdkException(): Throwable = when (this) {
        is ApiResult.Error.Timeout -> SdkHttpClientException.Timeout(cause)
        is ApiResult.Error.Connection, is ApiResult.Error.NoInternet, is ApiResult.Error.Certificate ->
            SdkHttpClientException.Connection(cause)
        is ApiResult.Error.Parse -> SdkHttpClientException.Parse(cause)
        else -> cause ?: IOException("Drive request failed")
    }
}

/** Sends [request] as [outgoing] carries it. */
internal suspend fun SdkHttpApi.send(request: SdkHttpRequest, outgoing: SdkOutgoingRequest): Response<ResponseBody> {
    val headers = outgoing.headers
    val body = outgoing.body
    return with(request) {
        when (method.uppercase()) {
            "GET" -> if (type == SdkHttpRequestType.STORAGE_DOWNLOAD) getStreaming(url, headers) else get(url, headers)
            "POST" -> post(url, headers, body)
            "PUT" -> put(url, headers, body)
            "DELETE" -> delete(url, headers, body)
            else -> throw IllegalArgumentException("Unsupported method: $method")
        }
    }
}

/** An SDK request as the call sends it: the headers for the header map and the body. */
internal class SdkOutgoingRequest(val headers: Map<String, String>, val body: RequestBody)

/**
 * The request as the call needs it. An upload block arrives as a stream of unknown length that can be read
 * once, so it goes out chunked, and its Content-Type (the multipart boundary) is carried by the body rather
 * than the header map: Retrofit wraps a body whose Content-Type comes as a header, and the wrapper hides
 * [RequestBody.isOneShot], which lets OkHttp send the request again (on a 408, or after a dropped connection)
 * with the stream already read. Every other body is read into memory here, which lets the stack send it again
 * after a session refresh.
 */
internal fun SdkHttpRequest.toOutgoing(): SdkOutgoingRequest {
    val stream = body ?: return SdkOutgoingRequest(headers, ByteArray(0).toRequestBody())
    if (type != SdkHttpRequestType.STORAGE_UPLOAD) {
        return SdkOutgoingRequest(headers, stream.use { it.readBytes() }.toRequestBody())
    }
    val typeHeader = headers.keys.firstOrNull { it.equals("Content-Type", ignoreCase = true) }
    val mediaType = typeHeader?.let { headers[it]?.toMediaTypeOrNull() }
    val rest = if (mediaType != null) headers - typeHeader else headers
    return SdkOutgoingRequest(rest, StreamRequestBody(stream, mediaType))
}

/**
 * An upload block's body, read from [stream] once. A second send fails with an [IOException] (which reaches
 * the SDK as a connection failure, so it uploads the block again from its own copy) rather than sending what
 * is left of the stream: an empty or cut multipart without the block in it. The sign-in stack can run a call
 * a second time, which is how that second send comes.
 */
private class StreamRequestBody(private val stream: InputStream, private val mediaType: MediaType?) : RequestBody() {
    private val sent = AtomicBoolean(false)

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = -1

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        if (!sent.compareAndSet(false, true)) throw IOException("an upload block cannot be sent a second time")
        sink.writeAll(stream.source())
    }
}
