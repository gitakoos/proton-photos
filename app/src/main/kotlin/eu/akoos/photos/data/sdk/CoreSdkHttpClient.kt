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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.source
import retrofit2.Response
import java.io.IOException
import java.io.InputStream

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
        val body = request.requestBody()
        val result = apiProvider.get<SdkHttpApi>(userId).invoke(forceNoRetryOnConnectionErrors = true) {
            send(request, body)
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

    private suspend fun SdkHttpApi.send(request: SdkHttpRequest, body: RequestBody): Response<ResponseBody> =
        with(request) {
            when (method.uppercase()) {
                "GET" -> if (type == SdkHttpRequestType.STORAGE_DOWNLOAD) getStreaming(url, headers) else get(url, headers)
                "POST" -> post(url, headers, body)
                "PUT" -> put(url, headers, body)
                "DELETE" -> delete(url, headers, body)
                else -> throw IllegalArgumentException("Unsupported method: $method")
            }
        }

    private fun ApiResult.Error.asSdkException(): Throwable = when (this) {
        is ApiResult.Error.Timeout -> SdkHttpClientException.Timeout(cause)
        is ApiResult.Error.Connection, is ApiResult.Error.NoInternet, is ApiResult.Error.Certificate ->
            SdkHttpClientException.Connection(cause)
        is ApiResult.Error.Parse -> SdkHttpClientException.Parse(cause)
        else -> cause ?: IOException("Drive request failed")
    }
}

/**
 * The request's body as the call needs it. An upload block arrives as a stream of unknown length that can be
 * read once, so it goes out chunked; every other body is read into memory here, which lets the stack send it
 * again after a session refresh.
 */
private fun SdkHttpRequest.requestBody(): RequestBody {
    val stream = body
    return when {
        stream == null -> ByteArray(0).toRequestBody()
        type == SdkHttpRequestType.STORAGE_UPLOAD -> StreamRequestBody(stream)
        else -> stream.use { it.readBytes() }.toRequestBody()
    }
}

private class StreamRequestBody(private val stream: InputStream) : RequestBody() {
    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = -1

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        sink.writeAll(stream.source())
    }
}
