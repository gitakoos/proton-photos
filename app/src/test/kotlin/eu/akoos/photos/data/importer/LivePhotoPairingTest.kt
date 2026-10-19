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

package eu.akoos.photos.data.importer

import eu.akoos.photos.data.importer.LivePhotoPairing.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePhotoPairingTest {

    @Test
    fun aStillAndAVideoOfOneNameInOneFolderArePaired() {
        val pairs = LivePhotoPairing.stillByVideo(
            listOf(
                Entry("Trip/IMG_0001.HEIC", 1_000_000L),
                Entry("Trip/IMG_0001.MOV", 1_000_500L),
                Entry("Trip/IMG_0002.HEIC", 2_000_000L),
            ),
        )
        assertEquals(mapOf("Trip/IMG_0001.MOV" to "Trip/IMG_0001.HEIC"), pairs)
    }

    @Test
    fun theNameIsMatchedWithoutRegardToCaseOrTheExtensionKind() {
        val pairs = LivePhotoPairing.stillByVideo(
            listOf(Entry("a/Img_7.jpg", null), Entry("a/IMG_7.mp4", null)),
        )
        assertEquals(mapOf("a/IMG_7.mp4" to "a/Img_7.jpg"), pairs)
    }

    @Test
    fun theSameNameInAnotherFolderIsAnotherPhoto() {
        val pairs = LivePhotoPairing.stillByVideo(
            listOf(Entry("a/IMG_1.HEIC", 5L), Entry("b/IMG_1.MOV", 5L)),
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun twoStillsOfOneNameAreNotPairedWithTheVideo() {
        val pairs = LivePhotoPairing.stillByVideo(
            listOf(Entry("IMG_1.HEIC", 5L), Entry("IMG_1.JPG", 5L), Entry("IMG_1.MOV", 5L)),
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun twoVideosOfOneNameAreNotPairedWithTheStill() {
        val pairs = LivePhotoPairing.stillByVideo(
            listOf(Entry("IMG_1.HEIC", 5L), Entry("IMG_1.MOV", 5L), Entry("IMG_1.MP4", 5L)),
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun datesMoreThanThreeSecondsApartAreTwoPhotos() {
        val apart = LivePhotoPairing.stillByVideo(
            listOf(Entry("IMG_1.HEIC", 10_000L), Entry("IMG_1.MOV", 10_000L + LivePhotoPairing.DATE_SLACK_MS + 1)),
        )
        assertTrue(apart.isEmpty())
        val edge = LivePhotoPairing.stillByVideo(
            listOf(Entry("IMG_1.HEIC", 10_000L), Entry("IMG_1.MOV", 10_000L + LivePhotoPairing.DATE_SLACK_MS)),
        )
        assertEquals(1, edge.size)
    }

    @Test
    fun withoutADateOnBothTheNameDecides() {
        assertEquals(1, LivePhotoPairing.stillByVideo(listOf(Entry("IMG_1.HEIC", null), Entry("IMG_1.MOV", 9L))).size)
        assertEquals(1, LivePhotoPairing.stillByVideo(listOf(Entry("IMG_1.HEIC", 9L), Entry("IMG_1.MOV", null))).size)
    }

    @Test
    fun aVideoAloneOrAPlainPhotoIsNeverPaired() {
        val pairs = LivePhotoPairing.stillByVideo(
            listOf(Entry("clip.MOV", 1L), Entry("scan.PNG", 1L), Entry("notes.json", null)),
        )
        assertTrue(pairs.isEmpty())
    }
}
