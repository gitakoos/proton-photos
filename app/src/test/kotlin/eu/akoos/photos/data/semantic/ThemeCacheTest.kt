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

package eu.akoos.photos.data.semantic

import eu.akoos.photos.domain.usecase.ThemeAssignment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The theme cache files: the round trip, and every way a stored cache stops being valid. */
class ThemeCacheTest {

    private val key = ThemeCacheKey(modelVersion = 2, tableVersion = 1)

    private val entries = mapOf(
        "linkA" to ThemeAssignment(3, 0.31f, 9, 0.30f),
        "content://media/1" to ThemeAssignment(0, 0.22f),
        "linkNone" to ThemeAssignment.NONE,
    )

    private fun bytes(write: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(write).toByteArray()

    @Test
    fun `assignments round trip`() {
        val data = bytes { writeThemeAssignments(it, key, entries) }
        assertEquals(entries, readThemeAssignments(ByteArrayInputStream(data), key))
    }

    @Test
    fun `a model version bump drops the cached assignments`() {
        val data = bytes { writeThemeAssignments(it, key, entries) }
        assertNull(readThemeAssignments(ByteArrayInputStream(data), key.copy(modelVersion = 3)))
    }

    @Test
    fun `a prompt table bump drops the cached assignments`() {
        val data = bytes { writeThemeAssignments(it, key, entries) }
        assertNull(readThemeAssignments(ByteArrayInputStream(data), key.copy(tableVersion = 2)))
    }

    @Test
    fun `a truncated or foreign file reads as absent`() {
        val data = bytes { writeThemeAssignments(it, key, entries) }
        assertNull(readThemeAssignments(ByteArrayInputStream(data.copyOf(data.size - 5)), key))
        assertNull(readThemeAssignments(ByteArrayInputStream(ByteArray(0)), key))
        assertNull(readThemeAssignments(ByteArrayInputStream(ByteArray(64) { 7 }), key))
    }

    @Test
    fun `validity needs both generations to match`() {
        assertTrue(isThemeCacheCurrent(key, ThemeCacheKey(2, 1)))
        assertFalse(isThemeCacheCurrent(key, ThemeCacheKey(2, 2)))
        assertFalse(isThemeCacheCurrent(key, ThemeCacheKey(1, 1)))
    }

    @Test
    fun `prompt vectors round trip and respect the generation and count`() {
        val vectors = listOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.3f, 0.4f))
        val data = bytes { writeThemePrompts(it, key, vectors) }
        val back = readThemePrompts(ByteArrayInputStream(data), key, 2)
        assertNotNull(back)
        assertEquals(vectors.map { it.toList() }, back!!.map { it.toList() })
        assertNull(readThemePrompts(ByteArrayInputStream(data), key, 3))
        assertNull(readThemePrompts(ByteArrayInputStream(data), key.copy(tableVersion = 9), 2))
    }
}
