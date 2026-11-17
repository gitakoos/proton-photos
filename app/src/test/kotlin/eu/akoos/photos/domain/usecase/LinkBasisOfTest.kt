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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.data.db.dao.SyncStateLite
import eu.akoos.photos.data.repository.SyncStateSnapshot
import eu.akoos.photos.domain.entity.LinkBasis
import eu.akoos.photos.domain.entity.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** How reconcile marks the link it writes, and who reads the mark. */
class LinkBasisOfTest {

    private fun basis(
        matched: String?,
        byId: String? = null,
        byHash: String? = null,
        unverifiable: Boolean = false,
        rewrites: Boolean = false,
        existing: Int? = null,
    ) = linkBasisOf(matched, byId, byHash, unverifiable, rewrites, existing)

    @Test
    fun `a match on the bytes is proven`() {
        assertEquals(LinkBasis.PROVEN, basis("c1", byHash = "c1"))
        assertEquals("the bytes outrank a name mark the row carried", LinkBasis.PROVEN, basis("c1", byId = "c1", byHash = "c1", existing = LinkBasis.NAME))
    }

    @Test
    fun `a match on the name alone is marked`() {
        assertEquals(LinkBasis.NAME, basis("c1"))
        assertEquals(LinkBasis.NAME, basis("c1", unverifiable = true, rewrites = false))
        assertEquals(LinkBasis.NAME_REWRITTEN, basis("c1", unverifiable = true, rewrites = true))
    }

    @Test
    fun `a match on the stored id keeps the mark the row had`() {
        assertEquals(LinkBasis.NAME, basis("c1", byId = "c1", existing = LinkBasis.NAME))
        assertEquals(LinkBasis.NAME_REWRITTEN, basis("c1", byId = "c1", existing = LinkBasis.NAME_REWRITTEN))
        assertEquals(LinkBasis.PROVEN, basis("c1", byId = "c1", existing = LinkBasis.PROVEN))
        assertEquals("a row from before the mark existed reads proven", LinkBasis.PROVEN, basis("c1", byId = "c1"))
    }

    @Test
    fun `a name link stays marked on the next pass and turns proven once the bytes match`() {
        val first = basis("c1", unverifiable = true)
        val second = basis("c1", byId = "c1", existing = first)
        val proven = basis("c1", byId = "c1", byHash = "c1", existing = second)

        assertEquals(LinkBasis.NAME, first)
        assertEquals(LinkBasis.NAME, second)
        assertEquals(LinkBasis.PROVEN, proven)
    }

    @Test
    fun `an unpaired row has no link to mark`() {
        assertEquals(LinkBasis.PROVEN, basis(null))
    }

    @Test
    fun `only proven links count as a device copy of a Drive photo`() {
        fun row(uri: String, cloud: String, basis: Int) =
            SyncStateLite(localUri = uri, cloudFileId = cloud, localHash = "", status = SyncStatus.SYNCED, queued = false, linkBasis = basis)
        val rows = SyncStateSnapshot.Rows(
            listOf(
                row("uri://1", "c1", LinkBasis.PROVEN),
                row("uri://2", "c2", LinkBasis.NAME),
                row("uri://3", "c3", LinkBasis.NAME_REWRITTEN),
            ),
        )

        assertEquals(setOf("c1"), rows.syncedCloudIds)
    }
}
