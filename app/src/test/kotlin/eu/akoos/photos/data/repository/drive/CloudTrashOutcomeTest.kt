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

import eu.akoos.photos.data.api.dto.TrashActionOutcome
import eu.akoos.photos.data.api.dto.TrashActionOutcomeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-link outcome parsing for the `*_multiple` trash endpoints. The bulk trash / permanent-delete
 * calls return a top-level Code that only means "batch processed" plus a per-link Responses array
 * whose own codes are the truth for what actually moved (1000 = ok). [rejectedLinkIds] is the shared
 * extractor both paths use to build their failed set; trusting the top-level code alone would report
 * success while some links silently stayed put. Pure data → no DI / network / crypto is exercised.
 */
class CloudTrashOutcomeTest {

    private fun entry(linkId: String, code: Int) =
        TrashActionOutcomeEntry(linkId = linkId, response = TrashActionOutcome(code = code))

    @Test
    fun `all per-link codes 1000 yields an empty rejected set`() {
        val responses = listOf(entry("a", 1000), entry("b", 1000), entry("c", 1000))
        assertTrue(rejectedLinkIds(responses).isEmpty())
    }

    @Test
    fun `non-1000 per-link codes are reported as rejected`() {
        val responses = listOf(entry("a", 1000), entry("b", 2011), entry("c", 1000), entry("d", 500))
        assertEquals(setOf("b", "d"), rejectedLinkIds(responses))
    }

    @Test
    fun `an empty responses array reports nothing rejected`() {
        // A server that returns only a top-level Code (no per-link array) must degrade to
        // "all ok" — the requested - failed difference then counts every link as succeeded.
        assertTrue(rejectedLinkIds(emptyList()).isEmpty())
    }

    @Test
    fun `succeeded set is requested minus rejected`() {
        // Mirrors how deleteFiles / deleteFromCloudForever derive their succeeded set: the
        // requested ids minus the per-link rejections, so a partial batch is tracked exactly.
        val requested = listOf("a", "b", "c", "d")
        val responses = listOf(entry("a", 1000), entry("b", 422), entry("c", 1000), entry("d", 422))
        val failed = rejectedLinkIds(responses)
        val succeeded = requested.toSet() - failed
        assertEquals(setOf("b", "d"), failed)
        assertEquals(setOf("a", "c"), succeeded)
    }
}
