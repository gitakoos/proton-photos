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

package eu.akoos.photos.data.repository

import eu.akoos.photos.data.db.entity.LocalExifCheckEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalExifCheckRulesTest {

    private fun check(modified: Long = 100, gpsNone: Boolean = false, dateRead: Boolean = false) =
        LocalExifCheckEntity("content://m/1", modified, gpsNone, dateRead)

    private fun owed(
        check: LocalExifCheckEntity?,
        modified: Long = 100,
        canReadLocation: Boolean = true,
        located: Boolean = false,
        dateRecorded: Boolean = false,
    ) = owedLegs(modified, canReadLocation, located, dateRecorded, check)

    @Test
    fun `a file never read owes both legs`() {
        assertEquals(OwedLegs(gps = true, date = true), owed(check = null))
    }

    @Test
    fun `a file read before with no GPS and its date checked owes nothing`() {
        assertEquals(OwedLegs(gps = false, date = false), owed(check(gpsNone = true, dateRead = true)))
    }

    @Test
    fun `a check stops counting once the file was modified`() {
        assertEquals(
            OwedLegs(gps = true, date = true),
            owed(check(gpsNone = true, dateRead = true), modified = 101),
        )
    }

    @Test
    fun `a GPS check alone still leaves the date owed and the other way round`() {
        assertEquals(OwedLegs(gps = false, date = true), owed(check(gpsNone = true)))
        assertEquals(OwedLegs(gps = true, date = false), owed(check(dateRead = true)))
    }

    @Test
    fun `no location grant means no GPS leg, a stored fix or override means no read`() {
        assertEquals(OwedLegs(gps = false, date = true), owed(check = null, canReadLocation = false))
        assertEquals(OwedLegs(gps = false, date = false), owed(check = null, located = true, dateRecorded = true))
    }

    @Test
    fun `a read that found no GPS and checked the date leaves a full check`() {
        assertEquals(
            check(gpsNone = true, dateRead = true),
            checkAfterRead("content://m/1", 100, readGps = true, gpsFound = false, readDate = true, prior = null),
        )
    }

    @Test
    fun `a found fix is not recorded as a check`() {
        assertEquals(
            check(gpsNone = false, dateRead = true),
            checkAfterRead("content://m/1", 100, readGps = true, gpsFound = true, readDate = true, prior = null),
        )
        assertNull(checkAfterRead("content://m/1", 100, readGps = true, gpsFound = true, readDate = false, prior = null))
    }

    @Test
    fun `a leg not read this time keeps what the current check knew`() {
        assertEquals(
            check(gpsNone = true, dateRead = true),
            checkAfterRead("content://m/1", 100, readGps = false, gpsFound = false, readDate = true, prior = check(gpsNone = true)),
        )
    }

    @Test
    fun `an outdated check is not carried onto the modified file`() {
        assertEquals(
            check(modified = 101, gpsNone = false, dateRead = true),
            checkAfterRead("content://m/1", 101, readGps = false, gpsFound = false, readDate = true, prior = check(gpsNone = true)),
        )
    }

    @Test
    fun `a second walk over the files the first one checked reads none of them`() {
        val first = owed(check = null)
        val left = checkAfterRead("content://m/1", 100, first.gps, gpsFound = false, first.date, prior = null)
        assertEquals(OwedLegs(gps = false, date = false), owed(left))
    }
}
