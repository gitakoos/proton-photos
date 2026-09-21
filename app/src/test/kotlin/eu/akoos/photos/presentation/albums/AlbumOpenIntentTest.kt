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

package eu.akoos.photos.presentation.albums

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one-shot rule an album screen consumes an Albums-grid intent by.
 *
 * The interesting inputs are the three ways it can misfire: against the empty member list the screen
 * mounts with, against an album with nothing to download or play, and a second time on the frames
 * between running and the host's clear. Plain JVM assertions, no Android.
 */
class AlbumOpenIntentTest {

    @Test
    fun `an intent on a loaded album runs`() {
        AlbumOpenAction.entries.forEach { action ->
            assertTrue(
                AlbumOpenIntent.shouldRun(action, photoCount = 12, photosLoaded = true, alreadyRun = false),
            )
        }
    }

    @Test
    fun `a plain open runs nothing`() {
        assertFalse(
            AlbumOpenIntent.shouldRun(null, photoCount = 12, photosLoaded = true, alreadyRun = false),
        )
    }

    @Test
    fun `every intent waits for the album's members`() {
        AlbumOpenAction.entries.forEach { action ->
            assertFalse(
                AlbumOpenIntent.shouldRun(action, photoCount = 12, photosLoaded = false, alreadyRun = false),
            )
        }
    }

    @Test
    fun `adding to an empty album runs`() {
        assertTrue(
            AlbumOpenIntent.shouldRun(
                AlbumOpenAction.AddPhotos, photoCount = 0, photosLoaded = true, alreadyRun = false,
            ),
        )
    }

    @Test
    fun `downloading an empty album stays quiet`() {
        assertFalse(
            AlbumOpenIntent.shouldRun(
                AlbumOpenAction.DownloadAll, photoCount = 0, photosLoaded = true, alreadyRun = false,
            ),
        )
    }

    @Test
    fun `playing an empty album stays quiet`() {
        assertFalse(
            AlbumOpenIntent.shouldRun(
                AlbumOpenAction.Slideshow, photoCount = 0, photosLoaded = true, alreadyRun = false,
            ),
        )
    }

    @Test
    fun `an intent that has run does not run again`() {
        AlbumOpenAction.entries.forEach { action ->
            assertFalse(
                AlbumOpenIntent.shouldRun(action, photoCount = 12, photosLoaded = true, alreadyRun = true),
            )
        }
    }

    @Test
    fun `one photo is enough to act on`() {
        assertTrue(
            AlbumOpenIntent.shouldRun(
                AlbumOpenAction.Slideshow, photoCount = 1, photosLoaded = true, alreadyRun = false,
            ),
        )
    }
}
