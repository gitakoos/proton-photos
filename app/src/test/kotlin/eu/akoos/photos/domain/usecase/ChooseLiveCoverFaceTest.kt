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

import eu.akoos.photos.data.db.entity.FaceEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChooseLiveCoverFaceTest {

    private fun face(id: String, photoKey: String) = FaceEntity(
        id = id, userId = "u", photoKey = photoKey,
        left = 0f, top = 0f, right = 1f, bottom = 1f, landmarks = "",
        embedding = ByteArray(0), personId = 1L, score = 0.9f,
    )

    @Test
    fun `keeps the stored cover when its photo is live`() = runTest {
        val stored = face("k1#0", "k1")
        val chosen = chooseLiveCoverFace(stored, feedKeys = setOf("k1"), feedEmpty = false) { error("not used") }
        assertSame(stored, chosen)
    }

    @Test
    fun `falls back to a live face when the stored cover photo is gone`() = runTest {
        val stored = face("gone#0", "gone")
        val alt = face("k2#0", "k2")
        val chosen = chooseLiveCoverFace(stored, feedKeys = setOf("k2"), feedEmpty = false) { alt }
        assertSame("the person is not dropped; a live face stands in", alt, chosen)
    }

    @Test
    fun `drops the person when no face is live`() = runTest {
        val stored = face("gone#0", "gone")
        val chosen = chooseLiveCoverFace(stored, feedKeys = setOf("other"), feedEmpty = false) { null }
        assertNull(chosen)
    }

    @Test
    fun `keeps the stored cover untouched while the feed is still loading`() = runTest {
        val stored = face("k1#0", "k1")
        val chosen = chooseLiveCoverFace(stored, feedKeys = emptySet(), feedEmpty = true) { error("not used") }
        assertSame(stored, chosen)
    }

    @Test
    fun `uses the fallback when there is no stored cover`() = runTest {
        val alt = face("k2#0", "k2")
        val chosen = chooseLiveCoverFace(stored = null, feedKeys = setOf("k2"), feedEmpty = false) { alt }
        assertEquals(alt, chosen)
    }
}
