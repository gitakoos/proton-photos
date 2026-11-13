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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The typed-query to theme match, over folded names from several locales. */
class ThemeQueryMatchTest {

    private val names = mapOf(
        3 to setOf("beach", "strand", "plaz", "plage"),
        13 to setOf("group photos", "gruppenfotos"),
        16 to setOf("cars", "autos", "voitures"),
        1 to setOf("pets", "haustiere"),
    )

    @Test
    fun `matches a name in any locale, any case`() {
        assertEquals(3, matchThemeCategory("Beach", names))
        assertEquals(3, matchThemeCategory("STRAND", names))
        assertEquals(3, matchThemeCategory("  Plage ", names))
    }

    @Test
    fun `diacritics are folded on both sides`() {
        assertEquals(3, matchThemeCategory("Plaž", names))
        assertEquals(3, matchThemeCategory("plaz", names))
    }

    @Test
    fun `a simple singular or plural still matches`() {
        assertEquals(16, matchThemeCategory("car", names))
        assertEquals(1, matchThemeCategory("pet", names))
        assertEquals(3, matchThemeCategory("beaches", names))
    }

    @Test
    fun `a multi word name matches whole`() {
        assertEquals(13, matchThemeCategory("group photos", names))
    }

    @Test
    fun `short or unrelated queries do not match`() {
        assertNull(matchThemeCategory("be", names))
        assertNull(matchThemeCategory("", names))
        assertNull(matchThemeCategory("beac", names))
        assertNull(matchThemeCategory("mountain lake", names))
    }

    @Test
    fun `folding drops accents and lowercases`() {
        assertEquals("zapady slunce", foldThemeText("  Západy Slunce "))
    }
}
