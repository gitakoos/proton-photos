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
import org.junit.Assert.assertTrue
import org.junit.Test

/** The classification rule on plain score arrays: no model, database or Android. */
class ThemeClassifierTest {

    private fun scores(vararg v: Float) = floatArrayOf(*v)

    @Test
    fun `best category below the top floor is no theme`() {
        assertEquals(ThemeAssignment.NONE, classifyThemeScores(scores(0.19f, 0.10f)))
    }

    @Test
    fun `best category at the top floor counts`() {
        val a = classifyThemeScores(scores(0.05f, THEME_TOP_MIN_SCORE))
        assertEquals(1, a.first)
        assertEquals(-1, a.second)
    }

    @Test
    fun `a close strong runner up becomes the second theme`() {
        val a = classifyThemeScores(scores(0.29f, 0.30f, 0.12f))
        assertEquals(1, a.first)
        assertEquals(0, a.second)
        assertEquals(0.29f, a.secondScore, 1e-6f)
    }

    @Test
    fun `a runner up under the second floor is dropped even inside the margin`() {
        val a = classifyThemeScores(scores(0.225f, 0.230f))
        assertEquals(1, a.first)
        assertEquals(-1, a.second)
    }

    @Test
    fun `a runner up outside the margin is dropped`() {
        val a = classifyThemeScores(scores(0.30f, 0.26f))
        assertEquals(0, a.first)
        assertEquals(-1, a.second)
    }

    @Test
    fun `only one second theme is kept, the strongest`() {
        val a = classifyThemeScores(scores(0.30f, 0.29f, 0.295f))
        assertEquals(0, a.first)
        assertEquals(2, a.second)
    }

    @Test
    fun `ties for best go to the lowest index and the other becomes second`() {
        val a = classifyThemeScores(scores(0.3f, 0.3f))
        assertEquals(0, a.first)
        assertEquals(1, a.second)
    }

    @Test
    fun `ids map the winning positions`() {
        val a = classifyThemeScores(scores(0.1f, 0.3f), ids = intArrayOf(7, 12))
        assertEquals(12, a.first)
    }

    @Test
    fun `a photo vector scores each category as its best prompt`() {
        val photo = floatArrayOf(1f, 0f)
        val prompts = ThemePromptVectors(
            listOf(
                listOf(floatArrayOf(0f, 1f), floatArrayOf(0.5f, 0.866f)),
                listOf(floatArrayOf(0f, 1f)),
            ),
        )
        // The best prompt of category 0 scores 0.5, above the floor; category 1 scores 0.
        val a = classifyTheme(photo, prompts, intArrayOf(3, 4))
        assertEquals(3, a.first)
        assertEquals(0.5f, a.firstScore, 1e-4f)
    }

    @Test
    fun `a zero tombstone and a wrong width vector are no theme`() {
        val prompts = ThemePromptVectors(listOf(listOf(floatArrayOf(1f, 0f))))
        assertEquals(ThemeAssignment.NONE, classifyTheme(floatArrayOf(0f, 0f), prompts, intArrayOf(0)))
        assertEquals(ThemeAssignment.NONE, classifyTheme(FloatArray(3), prompts, intArrayOf(0)))
    }

    @Test
    fun `the table has unique ids and every category has prompts`() {
        val all = ThemeCategories.ALL
        assertEquals(20, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.all { it.prompts.isNotEmpty() })
        assertEquals(all.sumOf { it.prompts.size }, ThemeCategories.FLAT_PROMPTS.size)
    }
}
