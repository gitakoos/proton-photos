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

package eu.akoos.photos.data.ocr

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signal has to refuse work from the moment it is cancelled, including work that has not reached
 * the runtime yet. Registering a run against a signal that is already spent would start inference
 * nobody is waiting for and hold the session open past the screen that owns it.
 */
class OnnxCancellationTest {

    @Test
    fun `a fresh signal lets work through`() {
        val signal = OnnxCancellation()
        assertFalse(signal.isCancelled)
        signal.ensureActive()
    }

    @Test
    fun `a cancelled signal refuses work that has not started`() {
        val signal = OnnxCancellation()
        signal.cancel()
        assertTrue(signal.isCancelled)
        assertThrows(CancellationException::class.java) { signal.ensureActive() }
    }

    @Test
    fun `cancelling twice is the same as cancelling once`() {
        val signal = OnnxCancellation()
        signal.cancel()
        signal.cancel()
        assertTrue(signal.isCancelled)
    }
}
