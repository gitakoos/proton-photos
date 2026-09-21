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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure thumbnail-name hash the staging pass keys each cached preview by. Plain JVM, no Android,
 * no I/O; the Android `stage()` shell is out of scope here.
 */
class StageImportUseCaseTest {

    @Test fun `stableName is deterministic for the same inputs`() {
        val a = StageImportUseCase.stableName("content://zip", "Takeout/Photos/IMG_0001.jpg")
        val b = StageImportUseCase.stableName("content://zip", "Takeout/Photos/IMG_0001.jpg")
        assertEquals(a, b)
    }

    @Test fun `stableName is a 40-char lowercase hex digest`() {
        val name = StageImportUseCase.stableName("content://zip", "Takeout/Photos/IMG_0001.jpg")
        assertEquals(40, name.length)
        assertTrue(name.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun `stableName differs when the entry name differs`() {
        val a = StageImportUseCase.stableName("content://zip", "Takeout/Photos/IMG_0001.jpg")
        val b = StageImportUseCase.stableName("content://zip", "Takeout/Photos/IMG_0002.jpg")
        assertNotEquals(a, b)
    }

    @Test fun `stableName differs when the zip differs`() {
        val a = StageImportUseCase.stableName("content://zipA", "Takeout/Photos/IMG_0001.jpg")
        val b = StageImportUseCase.stableName("content://zipB", "Takeout/Photos/IMG_0001.jpg")
        assertNotEquals(a, b)
    }

    @Test fun `stableName does not collide across the zip and entry boundary`() {
        // The zero-byte separator keeps ("ab", "c") and ("a", "bc") distinct rather than both hashing
        // the concatenation "abc".
        val a = StageImportUseCase.stableName("ab", "c")
        val b = StageImportUseCase.stableName("a", "bc")
        assertNotEquals(a, b)
    }
}
