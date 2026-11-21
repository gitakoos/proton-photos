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

package eu.akoos.photos.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Test

/** A rebuilt listing row keeps how far the GPS walk got with it, unless the photo's file changed. */
class GpsCheckCarryTest {

    private fun row(revisionId: String, gpsChecked: Int) = PhotoListingEntity(
        linkId = "l", shareId = "s", volumeId = "v", userId = "u", captureTime = 1L,
        displayName = "a.jpg", mimeType = "image/jpeg", sizeBytes = 1L, revisionId = revisionId, gpsChecked = gpsChecked,
    )

    @Test
    fun `a rebuild of the same revision keeps the walk state`() {
        val rebuilt = row("r1", GpsCheck.UNCHECKED).keepingGpsCheckOf(row("r1", GpsCheck.CONTENT_READ))

        assertEquals(GpsCheck.CONTENT_READ, rebuilt.gpsChecked)
    }

    @Test
    fun `a new revision starts the walk over`() {
        val rebuilt = row("r2", GpsCheck.UNCHECKED).keepingGpsCheckOf(row("r1", GpsCheck.CONTENT_READ))

        assertEquals(GpsCheck.UNCHECKED, rebuilt.gpsChecked)
    }

    @Test
    fun `a row with nothing stored before is left as built`() {
        val rebuilt = row("r1", GpsCheck.UNCHECKED).keepingGpsCheckOf(null)

        assertEquals(GpsCheck.UNCHECKED, rebuilt.gpsChecked)
    }
}
