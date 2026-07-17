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

package eu.akoos.photos.data.repository.drive

import eu.akoos.photos.di.CdnOkHttpClient
import eu.akoos.photos.util.retryAfterMs
import eu.akoos.photos.util.retryWithBackoff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicLong
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
     * Process-wide CDN cooldown, in `System.currentTimeMillis()` terms. Set when any block GET
     * sees a 429 / 503 so a burst of BACKGROUND thumbnail warm-up fetches pauses GLOBALLY after
     * the first rate-limit response instead of each request only backing off its own retry loop
     * while the others keep hammering. Repeated 429s push the window out further (adaptive), and
     * a single [AtomicLong.updateAndGet] with a max keeps the extension race-safe across the
     * worker pool. Foreground fetches ignore this window; only [fetchBlock] callers passing
     * `background = true` wait on it.
     */
    private val cooldownUntilMs = AtomicLong(0L)

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
     * @param background When true, wait out the shared CDN cooldown before each GET so a
     *  whole-library thumbnail warm-up self-limits after the first 429 instead of sustaining
     *  the rate-limit storm. Foreground fetches (a user opening a full-res photo, or a visible
     *  thumbnail) leave this false and proceed immediately, they are low-volume and the user
     *  is actively waiting.
     * @return The raw encrypted ciphertext bytes. The caller is responsible for decryption
     *  (session-key SEIPD or binary-PGP fallback) — see [PhotoDownloadService] and
     *  [ThumbnailHelpers] for the decrypt step.
     * @throws IllegalStateException if every attempt fails (the final error message is
     *  propagated). On 429/503 the `Retry-After` header is honoured before the backoff, and the
     *  shared cooldown is extended so other background fetches pause too.
     */
    suspend fun fetchBlock(
        url: String,
        token: String?,
        maxAttempts: Int = 4,
        background: Boolean = false,
    ): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { if (token != null) header("pm-storage-token", token) }
            .build()
        retryWithBackoff(maxAttempts = maxAttempts) { attempt ->
            if (background) awaitCooldown()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 429 || resp.code == 503) {
                    val ra = resp.retryAfterMs()
                    extendCooldown(ra)
                    if (ra != null) delay(ra)
                    error("HTTP ${resp.code} on CDN block download (attempt ${attempt + 1})")
                }
                if (!resp.isSuccessful) error("CDN block download failed: HTTP ${resp.code}")
                resp.body?.bytes() ?: error("Empty body from CDN block download")
            }
        }
    }

    /**
     * Push the shared cooldown out to `now + wait`, keeping the later of the two so a longer
     * server window already in effect is never shortened by a shorter one. [retryAfterMs] is a
     * server `Retry-After` (capped at [MAX_COOLDOWN_MS] to defend against a hostile value); a
     * missing header falls back to [DEFAULT_COOLDOWN_MS].
     */
    private fun extendCooldown(retryAfterMs: Long?) {
        val wait = (retryAfterMs ?: DEFAULT_COOLDOWN_MS).coerceIn(0L, MAX_COOLDOWN_MS)
        val until = System.currentTimeMillis() + wait
        cooldownUntilMs.updateAndGet { current -> maxOf(current, until) }
    }

    /**
     * Block until the shared cooldown elapses. Re-checks after each sleep because a concurrent
     * 429 can extend the window while we wait; the per-iteration slice is capped so an extension
     * is picked up promptly, and the total wait can never exceed [MAX_COOLDOWN_MS] past entry.
     */
    private suspend fun awaitCooldown() {
        val ceiling = System.currentTimeMillis() + MAX_COOLDOWN_MS
        while (true) {
            val now = System.currentTimeMillis()
            val until = minOf(cooldownUntilMs.get(), ceiling)
            if (now >= until) return
            delay((until - now).coerceAtMost(MAX_COOLDOWN_MS))
        }
    }

    private companion object {
        /** Cooldown applied on a 429 / 503 with no `Retry-After` header. */
        const val DEFAULT_COOLDOWN_MS = 4_000L

        /** Ceiling on both the honoured `Retry-After` and any single background wait, so a
         *  hostile or absurd server value can't stall the warm-up indefinitely. */
        const val MAX_COOLDOWN_MS = 60_000L
    }
}
