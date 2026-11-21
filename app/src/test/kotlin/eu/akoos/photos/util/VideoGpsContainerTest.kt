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

package eu.akoos.photos.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A video's place the platform reader does not report is read from its own boxes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoGpsContainerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixtures = PlaceFixtures(context)

    private fun uriOf(bytes: ByteArray): String =
        Uri.fromFile(File.createTempFile("video_gps_", ".mp4", context.cacheDir).apply { writeBytes(bytes) }).toString()

    @Test
    fun `a loci place is found when the platform reports none`() {
        assertEquals(47.5 to 19.25, PhotoGpsResolver.videoGps(context, uriOf(fixtures.movieWithLoci())))
    }

    @Test
    fun `the throwing read finds a loci place too`() {
        assertEquals(47.5 to 19.25, PhotoGpsResolver.videoGpsOrThrow(context, uriOf(fixtures.movieWithLoci())))
    }

    @Test
    fun `a video whose loci was blanked has no place`() {
        val movie = fixtures.movieWithLoci().also { MovieLocation.process(it, 0, it.size, erase = true) }

        assertNull(PhotoGpsResolver.videoGps(context, uriOf(movie)))
    }
}
