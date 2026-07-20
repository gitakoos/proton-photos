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

package eu.akoos.photos.presentation.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Pins [formatBytes] to binary units and to the reader's locale.
 *
 * The unit base is the load-bearing part. Proton reports storage in binary units, so a quota
 * rendered here has to equal the same quota on Proton's own pages; a switch to decimal would
 * print 214.75 GB where Proton prints 200.00 GB and read as a bug in this app. The decimal
 * cases below fail on exactly that switch, so the base cannot drift silently.
 *
 * The separator is the second thing worth holding. [formatBytes] formats against the default
 * locale, so a reader in a comma-decimal locale sees "1,5 GB" and that is correct, not a defect
 * to be normalised away by pinning the formatter to one language. The locale case covers it, and
 * the rest of the assertions fix the locale first so they read the same on any machine.
 *
 * The third invariant is that ONE formatter serves every surface. A per-screen copy is how a
 * single photo ends up showing two different sizes on two screens, so any new byte rendering
 * routes through this function rather than a local variant.
 */
class FormatUtilsTest {

    private lateinit var systemLocale: Locale

    @Before
    fun fixLocale() {
        systemLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(systemLocale)
    }

    @Test
    fun `bytes below a kilobyte stay unscaled`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun `unit boundaries are powers of 1024`() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.00 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `decimal round numbers do not land on a unit boundary`() {
        // A decimal formatter would print "1.0 MB" and "1.00 GB" for these two.
        assertEquals("976.6 KB", formatBytes(1_000_000))
        assertEquals("953.7 MB", formatBytes(1_000_000_000))
    }

    @Test
    fun `a plan quota reads as its advertised size`() {
        assertEquals("5.00 GB", formatBytes(5L * 1024 * 1024 * 1024))
        assertEquals("200.00 GB", formatBytes(200L * 1024 * 1024 * 1024))
    }

    @Test
    fun `the decimal separator follows the reader's locale`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1,5 KB", formatBytes(1536))
        assertEquals("200,00 GB", formatBytes(200L * 1024 * 1024 * 1024))
    }
}
