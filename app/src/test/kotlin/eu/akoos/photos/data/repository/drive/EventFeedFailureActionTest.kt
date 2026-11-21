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

import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What an incremental refresh does once its pass failed ([eventFeedFailureAction]), and when a fallback
 * full refresh may start ([fallbackRefreshDue]). A lost connection must never cost a full walk of the
 * library.
 */
class EventFeedFailureActionTest {

    private fun protonError(httpCode: Int, protonCode: Int) = ApiException(
        ApiResult.Error.Http(
            httpCode = httpCode,
            message = "http $httpCode",
            proton = ApiResult.Error.ProtonData(code = protonCode, error = "refused"),
        ),
    )

    @Test
    fun `a DNS lookup that found nothing keeps the anchor and retries the feed`() {
        val action = eventFeedFailureAction(
            ApiException(ApiResult.Error.Connection(false, UnknownHostException("Unable to resolve host"))),
        )

        assertEquals(EventFeedFailureAction.RETRY_INCREMENTAL, action)
    }

    @Test
    fun `no internet keeps the anchor and retries the feed`() {
        assertEquals(
            EventFeedFailureAction.RETRY_INCREMENTAL,
            eventFeedFailureAction(ApiException(ApiResult.Error.NoInternet())),
        )
    }

    @Test
    fun `an offline failure wrapped by another layer still retries the feed`() {
        val wrapped = IllegalStateException("volume lookup failed", UnknownHostException("Unable to resolve host"))

        assertEquals(EventFeedFailureAction.RETRY_INCREMENTAL, eventFeedFailureAction(wrapped))
    }

    @Test
    fun `a connection failure from the SDK route retries the feed`() {
        val sdk = me.proton.drive.sdk.SdkHttpClientException.Connection(UnknownHostException("Unable to resolve host"))

        assertEquals(EventFeedFailureAction.RETRY_INCREMENTAL, eventFeedFailureAction(RuntimeException(sdk)))
    }

    @Test
    fun `a timeout or a rate limit retries the feed`() {
        assertEquals(EventFeedFailureAction.RETRY_INCREMENTAL, eventFeedFailureAction(SocketTimeoutException("timeout")))
        assertEquals(
            EventFeedFailureAction.RETRY_INCREMENTAL,
            eventFeedFailureAction(ApiException(ApiResult.Error.Http(429, "rate limited"))),
        )
        assertEquals(
            EventFeedFailureAction.RETRY_INCREMENTAL,
            eventFeedFailureAction(ApiException(ApiResult.Error.Http(503, "unavailable"))),
        )
    }

    @Test
    fun `an anchor the server refused is reset`() {
        assertEquals(EventFeedFailureAction.RESET_ANCHOR_AND_REFRESH, eventFeedFailureAction(protonError(422, 2501)))
        assertEquals(EventFeedFailureAction.RESET_ANCHOR_AND_REFRESH, eventFeedFailureAction(protonError(422, 2000)))
    }

    @Test
    fun `a failure of this pass alone keeps the anchor and falls back to a full refresh`() {
        assertEquals(
            EventFeedFailureAction.FULL_REFRESH,
            eventFeedFailureAction(IllegalStateException("datastore write failed")),
        )
    }

    @Test
    fun `the first fallback full refresh may start at once`() {
        assertTrue(fallbackRefreshDue(nowMs = 1_000L, lastMs = 0L, cooldownMs = 300_000L))
    }

    @Test
    fun `a fallback full refresh waits out the cool-down after the last one`() {
        assertFalse(fallbackRefreshDue(nowMs = 299_999L, lastMs = 1L, cooldownMs = 300_000L))
        assertTrue(fallbackRefreshDue(nowMs = 300_001L, lastMs = 1L, cooldownMs = 300_000L))
    }
}
