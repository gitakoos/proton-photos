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

package eu.akoos.photos.data.db

import eu.akoos.photos.data.db.entity.PendingMetadataEditEntity
import eu.akoos.photos.data.db.entity.sentCopiesBesideOriginals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Emptying the edit queue at sign-out first trashes the corrected copies already sent whose original is
 * still on Drive, so no photo is left there twice.
 */
class SentEditCopiesTest {

    private fun row(linkId: String, newLinkId: String?, userId: String = "u1") = PendingMetadataEditEntity(
        linkId = linkId, userId = userId, deviceUri = null, newCaptureMs = 1L, locationMode = "UNCHANGED",
        lat = null, lng = null, description = null, artist = null, copyright = null, enqueuedAt = 1L,
        newLinkId = newLinkId,
    )

    @Test
    fun `a sent copy whose original is still listed is trashed`() {
        val copies = sentCopiesBesideOriginals(listOf(row("orig", "copy")), "u1", setOf("orig"))

        assertEquals(listOf("copy"), copies)
    }

    @Test
    fun `a sent copy whose original is gone stays`() {
        val copies = sentCopiesBesideOriginals(listOf(row("orig", "copy")), "u1", emptySet())

        assertEquals(emptyList<String>(), copies)
    }

    @Test
    fun `an edit with no copy sent has nothing to trash`() {
        val copies = sentCopiesBesideOriginals(listOf(row("orig", null)), "u1", setOf("orig"))

        assertEquals(emptyList<String>(), copies)
    }

    @Test
    fun `another account's copies are left alone`() {
        val copies = sentCopiesBesideOriginals(listOf(row("orig", "copy", userId = "u2")), "u1", setOf("orig"))

        assertEquals(emptyList<String>(), copies)
    }
}
