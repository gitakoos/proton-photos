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

package eu.akoos.photos.data.repository.drive

import eu.akoos.photos.di.CdnOkHttpClient
import eu.akoos.photos.util.retryAfterMs
import eu.akoos.photos.util.retryWithBackoff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared CDN block fetcher for the Drive content + thumbnail endpoints.
 *
 * Both [PhotoDownloadService] (full-res content blocks) and [ThumbnailHelpers] (thumbnail
 * blobs) need the same OkHttp GET + retry-after + exponential-backoff loop. The shape is
 * identical — only the encrypted-byte fetch — so it lives here, while each caller keeps
 * its own decrypt path.
 *
 * Uses the pinned [@CdnOkHttpClient] [OkHttpClient] (cert-pinned to *.proton.me) and the
 * shared [retryWithBackoff] / [retryAfterMs] helpers, so the network semantics are the
 * SAME as the previous inline loops — this is purely deduplication, not a behavior change.
 *
 * The `pm-storage-token` header is the CDN's substitute for `Authorization: Bearer`, so
 * callers that bypass the ApiProvider stack always pass it (when present) via [token].
 */
@Singleton
class CdnBlockFetcher @Inject constructor(
    @CdnOkHttpClient private val client: OkHttpClient,
) {

    /**
     * Fetches the encrypted bytes at [url] from the Drive CDN with retry semantics that
     * match the previous inline pattern.
     *
     * @param url The block / thumbnail download URL (typically `block.bareUrl` for content
     *  blocks or `info.bareUrl` for thumbnails — the un-templated raw URL the CDN expects).
     * @param token Optional `pm-storage-token` value. Sent as a header when non-null; the
     *  CDN rejects unauthenticated GETs with 401/403.
     * @param maxAttempts Maximum retry attempts. The two callers historically used different
     *  caps (4 for full-res blocks, 3 for thumbnails) so we keep that parameterized rather
     *  than silently merging.
     * @return The raw encrypted ciphertext bytes. The caller is responsible for decryption
     *  (session-key SEIPD or binary-PGP fallback) — see [PhotoDownloadService] and
     *  [ThumbnailHelpers] for the decrypt step.
     * @throws IllegalStateException if every attempt fails (the final error message is
     *  propagated). On 429/503 the `Retry-After` header is honoured before the backoff.
     */
    suspend fun fetchBlock(
        url: String,
        token: String?,
        maxAttempts: Int = 4,
    ): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { if (token != null) header("pm-storage-token", token) }
            .build()
        retryWithBackoff(maxAttempts = maxAttempts) { attempt ->
            client.newCall(request).execute().use { resp ->
                if (resp.code == 429 || resp.code == 503) {
                    val ra = resp.retryAfterMs()
                    if (ra != null) delay(ra)
                    error("HTTP ${resp.code} on CDN block download (attempt ${attempt + 1})")
                }
                if (!resp.isSuccessful) error("CDN block download failed: HTTP ${resp.code}")
                resp.body?.bytes() ?: error("Empty body from CDN block download")
            }
        }
    }
}
