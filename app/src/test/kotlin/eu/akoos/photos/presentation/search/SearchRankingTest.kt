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

package eu.akoos.photos.presentation.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRankingTest {

    private fun held(loading: Boolean, vararg keys: String) = loading to keys.toList()

    @Test
    fun `a new query keeps the ranking of the one before until its own arrives`() {
        val before = held(false, "a", "b")
        val running = before.after(RankStep(loading = true, keys = null))
        assertEquals(held(true, "a", "b"), running)
        assertEquals(held(false, "c"), running.after(RankStep(loading = false, keys = listOf("c"))))
    }

    @Test
    fun `a cleared ranking empties what was held`() {
        val before = held(true, "a", "b")
        assertEquals(held(false), before.after(RankStep(loading = false, keys = emptyList())))
    }
}
