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

package eu.akoos.photos.domain.entity

import eu.akoos.photos.data.api.dto.ShareBootstrapResponse
import eu.akoos.photos.data.api.dto.ShareMembershipDto
import eu.akoos.photos.data.repository.drive.ownPermissionsIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the two pure pieces that decide whether the app offers to add photos to an album:
 * [Album.canAddPhotos] and [ownPermissionsIn].
 *
 * These gate a request the server can refuse. Drive's bitmask is 4 for a viewer and 6 for an
 * editor, so the write bit is what separates them, and reading it wrong in the permissive direction
 * shows an add button that fails on tap. Reading it wrong in the strict direction hides a button
 * the user is entitled to, which is the bug report this exists for. Both directions are asserted.
 *
 * No Android, no network: plain JVM assertions on values.
 */
class AlbumPermissionsTest {

    private fun album(sharedBy: String?, permissions: Long?) = Album(
        linkId = "a1",
        name = "Wedding",
        photoCount = 3,
        coverLinkId = null,
        lastActivityTimeMs = null,
        sharedByEmail = sharedBy,
        permissions = permissions,
    )

    private fun bootstrap(vararg memberships: ShareMembershipDto) = ShareBootstrapResponse(
        code = 1000,
        shareId = "s1",
        memberships = memberships.toList(),
    )

    @Test
    fun `an album you own is always addable regardless of permissions`() {
        assertTrue(album(sharedBy = null, permissions = null).canAddPhotos)
        assertTrue(album(sharedBy = null, permissions = 4L).canAddPhotos)
    }

    @Test
    fun `an editor on a shared album may add`() {
        assertTrue(
            "6 is Drive's editor grant and must unlock the add",
            album(sharedBy = "her@example.test", permissions = 6L).canAddPhotos,
        )
    }

    @Test
    fun `a viewer on a shared album may not add`() {
        assertFalse(
            "4 is viewer-only and must not offer an add the server would refuse",
            album(sharedBy = "her@example.test", permissions = 4L).canAddPhotos,
        )
    }

    @Test
    fun `an unknown grant on a shared album is treated as read-only`() {
        assertFalse(
            "a null permission is not an editor permission",
            album(sharedBy = "her@example.test", permissions = null).canAddPhotos,
        )
        assertFalse(album(sharedBy = "her@example.test", permissions = 0L).canAddPhotos)
    }

    @Test
    fun `the write bit is what decides, not the exact number`() {
        // Guards against anyone rewriting this as `permissions == 6L`: Drive documents a bitmask,
        // so a future grant that adds another bit alongside write must still count as an editor.
        assertTrue(album(sharedBy = "her@example.test", permissions = 7L).canAddPhotos)
        assertTrue(album(sharedBy = "her@example.test", permissions = 2L).canAddPhotos)
        assertFalse(album(sharedBy = "her@example.test", permissions = 5L).canAddPhotos)
    }

    @Test
    fun `the membership matching one of my addresses wins`() {
        val picked = ownPermissionsIn(
            bootstrap(
                ShareMembershipDto(memberId = "m1", addressId = "addrOther", permissions = 4L),
                ShareMembershipDto(memberId = "m2", addressId = "addrMine", permissions = 6L),
            ),
            ownAddressIds = setOf("addrMine"),
        )
        assertEquals(6L, picked)
    }

    @Test
    fun `the first membership is the fallback when no address matches`() {
        // Memberships are caller-scoped, so an unmatched row is still this user's. This is the
        // path taken when the address lookup failed and the set came back empty.
        val picked = ownPermissionsIn(
            bootstrap(ShareMembershipDto(memberId = "m1", addressId = "addrOther", permissions = 6L)),
            ownAddressIds = emptySet(),
        )
        assertEquals(6L, picked)
    }

    @Test
    fun `a share with no memberships yields no permission`() {
        // What an owner's own share looks like, and it must not be mistaken for an editor grant.
        assertNull(ownPermissionsIn(bootstrap(), ownAddressIds = setOf("addrMine")))
    }

    @Test
    fun `a membership without a permission field yields null`() {
        assertNull(
            ownPermissionsIn(
                bootstrap(ShareMembershipDto(memberId = "m1", addressId = "addrMine", permissions = null)),
                ownAddressIds = setOf("addrMine"),
            )
        )
    }
}
