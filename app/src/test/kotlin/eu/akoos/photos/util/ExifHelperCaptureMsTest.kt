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

package eu.akoos.photos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ExifHelperCaptureMsTest {

    private val budapest = ZoneId.of("Europe/Budapest")

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `an offset pins the instant whatever the zone`() {
        val meta = PhotoMetadata(dateTimeOriginal = "2023:07:01 12:00:00", offsetTimeOriginal = "+09:00")
        assertEquals(ms("2023-07-01T03:00:00Z"), ExifHelper.captureMs(meta, budapest))
        assertEquals(ms("2023-07-01T03:00:00Z"), ExifHelper.captureMs(meta, ZoneId.of("UTC")))
    }

    @Test
    fun `without an offset the wall clock is read in the zone`() {
        val meta = PhotoMetadata(dateTimeOriginal = "2023:07:01 12:00:00")
        assertEquals(ms("2023-07-01T10:00:00Z"), ExifHelper.captureMs(meta, budapest))
    }

    @Test
    fun `DateTime stands in for a missing DateTimeOriginal`() {
        val meta = PhotoMetadata(dateTime = "2020:01:10 08:30:00", offsetTimeOriginal = "+01:00")
        assertEquals(ms("2020-01-10T07:30:00Z"), ExifHelper.captureMs(meta, ZoneId.of("UTC")))
    }

    @Test
    fun `DateTimeOriginal wins over DateTime`() {
        val meta = PhotoMetadata(dateTime = "2024:05:05 05:05:05", dateTimeOriginal = "2023:07:01 12:00:00")
        assertEquals(ms("2023-07-01T12:00:00Z"), ExifHelper.captureMs(meta, ZoneId.of("UTC")))
    }

    @Test
    fun `no readable datetime is no date`() {
        assertNull(ExifHelper.captureMs(PhotoMetadata(), budapest))
        assertNull(ExifHelper.captureMs(PhotoMetadata(dateTimeOriginal = "    :  :     "), budapest))
    }
}
