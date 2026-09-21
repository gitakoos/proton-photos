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

package eu.akoos.photos.presentation.folders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one-shot rule a folder screen consumes an Albums-grid intent by.
 *
 * The interesting inputs are the two ways it can misfire: on the empty photo list the screen mounts
 * with, and a second time on the frames between running and the host's clear. Plain JVM assertions,
 * no Android.
 */
class DeviceFolderOpenIntentTest {

    @Test
    fun `an intent on a loaded folder runs`() {
        DeviceFolderOpenAction.entries.forEach { action ->
            assertTrue(DeviceFolderOpenIntent.shouldRun(action, photoCount = 12, alreadyRun = false))
        }
    }

    @Test
    fun `a plain open runs nothing`() {
        assertFalse(DeviceFolderOpenIntent.shouldRun(null, photoCount = 12, alreadyRun = false))
    }

    @Test
    fun `an intent waits for the folder's photos`() {
        assertFalse(
            DeviceFolderOpenIntent.shouldRun(
                DeviceFolderOpenAction.BackUpToTimeline, photoCount = 0, alreadyRun = false,
            ),
        )
    }

    @Test
    fun `an intent that has run does not run again`() {
        assertFalse(
            DeviceFolderOpenIntent.shouldRun(
                DeviceFolderOpenAction.Slideshow, photoCount = 12, alreadyRun = true,
            ),
        )
    }

    @Test
    fun `a folder emptied after the intent ran stays quiet`() {
        assertFalse(
            DeviceFolderOpenIntent.shouldRun(
                DeviceFolderOpenAction.BackUpAndMirror, photoCount = 0, alreadyRun = true,
            ),
        )
    }

    @Test
    fun `one photo is enough to act on`() {
        assertTrue(
            DeviceFolderOpenIntent.shouldRun(
                DeviceFolderOpenAction.Slideshow, photoCount = 1, alreadyRun = false,
            ),
        )
    }
}
