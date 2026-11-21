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

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real string resources: every theme has a name in every shipped locale and is found by it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeNamesTest {

    private val names = themeNamesInAllLocales(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `every theme has a distinct non blank name set`() {
        assertEquals(ThemeCategories.ALL.map { it.id }.toSet(), names.keys)
        assertTrue(names.values.all { set -> set.isNotEmpty() && set.all { it.isNotBlank() } })
    }

    @Test
    fun `a name in another locale finds its theme`() {
        assertEquals(3, matchThemeCategory("Strand", names))
        assertEquals(3, matchThemeCategory("playa", names))
        assertEquals(3, matchThemeCategory("Tengerpart", names))
        assertEquals(16, matchThemeCategory("autos", names))
        assertEquals(9, matchThemeCategory("naplementék", names))
    }
}
