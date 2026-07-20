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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The decision [shouldResetEventAnchor] makes when the event feed fails: keep the stored anchor and
 * retry, or drop it so the next pass re-arms from a full refresh.
 *
 * The two mistakes cost very different amounts, which is the whole design. Resetting an anchor that
 * was fine costs one extra full walk, once. Keeping one the server has stopped accepting costs the
 * incremental feed for good: every pass re-sends it, fails, re-walks the library, and the cheap path
 * never runs again. So the tests pin the default (reset) as hard as they pin the two exemptions.
 *
 * Classified from real [ApiException] / [ApiResult.Error] values rather than message strings,
 * because ProtonCore carries both the status and its own body code in separate fields and neither
 * ever appears in the message.
 */
class EventAnchorResetTest {

    private fun protonError(httpCode: Int, protonCode: Int) = ApiException(
        ApiResult.Error.Http(
            httpCode = httpCode,
            message = "http $httpCode",
            proton = ApiResult.Error.ProtonData(code = protonCode, error = "refused"),
        ),
    )

    @Test
    fun `the two codes that name an unusable anchor reset it`() {
        // 2000 (the request cannot be satisfied) and 2501 (the anchor names something the server no
        // longer holds). Both arrive as an ordinary 422, so the body code is the only thing that
        // separates them from a dozen unrelated validation failures.
        assertTrue(shouldResetEventAnchor(protonError(422, 2000)))
        assertTrue(shouldResetEventAnchor(protonError(422, 2501)))
    }

    @Test
    fun `a transient failure keeps the anchor`() {
        // The outcome most worth avoiding: a blip that turns into a full re-walk of the library.
        // The server is saying "not now", not "not this anchor".
        assertFalse(shouldResetEventAnchor(ApiException(ApiResult.Error.Http(429, "rate limited"))))
        assertFalse(shouldResetEventAnchor(ApiException(ApiResult.Error.Http(503, "unavailable"))))
        assertFalse(shouldResetEventAnchor(ApiException(ApiResult.Error.NoInternet())))
        assertFalse(shouldResetEventAnchor(ApiException(ApiResult.Error.Connection(false))))
        assertFalse(shouldResetEventAnchor(ApiException(ApiResult.Error.Timeout(false))))
    }

    @Test
    fun `a transient code still keeps the anchor when the body carries a proton code`() {
        // The status is checked before the body: a 429 that also carries a code must not be read as
        // a refusal of the anchor itself.
        assertFalse(shouldResetEventAnchor(protonError(429, 2000)))
    }

    @Test
    fun `an unrecognised code resets, which is the safe direction`() {
        // The asymmetry decides the default. An unnecessary reset costs one full walk; a missed one
        // leaves the feed dead, so anything the classifier cannot vouch for as temporary resets.
        assertTrue(shouldResetEventAnchor(protonError(422, 2011)))
        assertTrue(shouldResetEventAnchor(protonError(400, 999999)))
    }

    @Test
    fun `an api error with no body code at all still resets`() {
        // Same default, reached without any code to read.
        assertTrue(shouldResetEventAnchor(ApiException(ApiResult.Error.Http(404, "not found"))))
        assertTrue(shouldResetEventAnchor(ApiException(ApiResult.Error.Parse(null))))
    }

    @Test
    fun `a failure that is not an api error never resets`() {
        // A local write, a decode, a bug: none of these reached the endpoint, so none of them
        // carries evidence about the anchor and none may be read as though it did.
        assertFalse(shouldResetEventAnchor(IllegalStateException("datastore write failed")))
        assertFalse(shouldResetEventAnchor(IOException("socket reset")))
    }

    @Test
    fun `the known-code check recognises exactly the two refusals`() {
        // Drives the log wording only, so it must not claim a refusal the server did not make.
        assertTrue(isKnownFatalEventAnchorCode(protonError(422, 2000)))
        assertTrue(isKnownFatalEventAnchorCode(protonError(422, 2501)))
        assertFalse(isKnownFatalEventAnchorCode(protonError(422, 2011)))
        assertFalse(isKnownFatalEventAnchorCode(ApiException(ApiResult.Error.Http(404, "not found"))))
        assertFalse(isKnownFatalEventAnchorCode(IllegalStateException("datastore write failed")))
    }
}
