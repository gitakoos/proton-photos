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

package eu.akoos.photos.domain.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportCaptureDateTest {

    private val now = 1_760_000_000_000L
    private val sidecar = 1_600_000_000_000L
    private val file = 1_610_000_000_000L
    private val name = 1_620_000_000_000L
    private val modified = 1_630_000_000_000L
    private val day = 86_400_000L

    private fun resolve(
        sidecarMs: Long? = null,
        fileMs: Long? = null,
        nameMs: Long? = null,
        modifiedMs: Long? = null,
        onFileRead: () -> Unit = {},
    ) = ImportCaptureDate.resolve(sidecarMs, { onFileRead(); fileMs }, nameMs, modifiedMs, now)

    @Test
    fun `the sidecar wins and the file is not read`() {
        var reads = 0
        assertEquals(sidecar, resolve(sidecar, file, name, modified) { reads++ })
        assertEquals(0, reads)
    }

    @Test
    fun `each source stands in for the one before it`() {
        assertEquals(file, resolve(null, file, name, modified))
        assertEquals(name, resolve(null, null, name, modified))
        assertEquals(modified, resolve(null, null, null, modified))
        assertEquals(now, resolve())
    }

    @Test
    fun `an implausible source is skipped`() {
        assertEquals("a file date of 1970 falls through", name, resolve(null, 0L, name))
        assertEquals("a file date a week ahead falls through", name, resolve(null, now + 7 * day, name))
        assertEquals("a sidecar date at the epoch falls through", file, resolve(0L, file))
    }

    @Test
    fun `an implausible or future modified time gives the stage time`() {
        assertEquals(now, resolve(modifiedMs = 0L))
        assertEquals(now, resolve(modifiedMs = 1_000L))
        assertEquals(now, resolve(modifiedMs = now + 2 * day))
        assertEquals("within a day ahead still counts", now + day / 2, resolve(modifiedMs = now + day / 2))
    }

    @Test
    fun `the result is never the epoch`() {
        val all = listOf(null, 0L, -5L, 1L, now + 30 * day)
        for (s in all) for (f in all) for (n in all) for (m in all) {
            assertTrue(ImportCaptureDate.resolve(s, { f }, n, m, now) > 31_536_000_000L)
        }
    }

    @Test
    fun `plausibility is after 1971 and at most a day ahead`() {
        assertTrue(ImportCaptureDate.isPlausible(now, now))
        assertTrue(ImportCaptureDate.isPlausible(now + day, now))
        assertFalse(ImportCaptureDate.isPlausible(now + day + 1, now))
        assertFalse(ImportCaptureDate.isPlausible(31_536_000_000L, now))
    }
}
