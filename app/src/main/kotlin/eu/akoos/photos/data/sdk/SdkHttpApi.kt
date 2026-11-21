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

import me.proton.core.network.data.protonApi.BaseRetrofitApi
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HTTP
import retrofit2.http.HeaderMap
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * The one call shape the Drive SDK's requests go through, so they ride the sign-in stack's session,
 * token refresh and retry rules. The URL is absolute (the API or a storage host); the headers are the SDK's own.
 */
interface SdkHttpApi : BaseRetrofitApi {
    @HTTP(method = "GET", path = "", hasBody = false)
    suspend fun get(
        @Url url: String,
        @HeaderMap headers: Map<String, String> = emptyMap(),
    ): Response<ResponseBody>

    @HTTP(method = "GET", path = "", hasBody = false)
    @Streaming
    suspend fun getStreaming(
        @Url url: String,
        @HeaderMap headers: Map<String, String> = emptyMap(),
    ): Response<ResponseBody>

    @HTTP(method = "POST", path = "", hasBody = true)
    suspend fun post(
        @Url url: String,
        @HeaderMap headers: Map<String, String> = emptyMap(),
        @Body body: RequestBody? = null,
    ): Response<ResponseBody>

    @HTTP(method = "PUT", path = "", hasBody = true)
    suspend fun put(
        @Url url: String,
        @HeaderMap headers: Map<String, String> = emptyMap(),
        @Body body: RequestBody? = null,
    ): Response<ResponseBody>

    @HTTP(method = "DELETE", path = "", hasBody = true)
    suspend fun delete(
        @Url url: String,
        @HeaderMap headers: Map<String, String> = emptyMap(),
        @Body body: RequestBody? = null,
    ): Response<ResponseBody>
}
