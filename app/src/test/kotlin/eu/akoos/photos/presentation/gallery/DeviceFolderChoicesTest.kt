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

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [deviceFolderChoices], the pure grouping behind the "Move to folder" picker: it turns the
 * device photos into one target per MediaStore bucket, with the newest photo's uri as the cover and
 * the most-populated folder first. A cloud-only photo carries no [LocalMediaItem], so exclusion of
 * cloud-only rows is handled by the caller before this fn ever sees them; here every input is a real
 * device file and only a missing bucket drops it.
 */
class DeviceFolderChoicesTest {

    private fun item(uri: String, bucket: String?, dateTaken: Long): LocalMediaItem =
        LocalMediaItem(
            uri = uri,
            dateTaken = dateTaken,
            displayName = uri,
            mimeType = "image/jpeg",
            sizeBytes = 0,
            bucketName = bucket,
        )

    @Test
    fun `photos are grouped by bucket and counted`() {
        val choices = deviceFolderChoices(
            listOf(
                item("a1", "Trip", 10),
                item("a2", "Trip", 20),
                item("b1", "Home", 30),
            ),
        )
        assertEquals(2, choices.size)
        assertEquals(2, choices.first { it.name == "Trip" }.count)
        assertEquals(1, choices.first { it.name == "Home" }.count)
    }

    @Test
    fun `the cover is the newest photo in the bucket by dateTaken`() {
        val choices = deviceFolderChoices(
            listOf(
                item("old", "Trip", 100),
                item("newest", "Trip", 300),
                item("mid", "Trip", 200),
            ),
        )
        assertEquals(1, choices.size)
        assertEquals("newest", choices.single().coverUri)
        assertEquals(3, choices.single().count)
    }

    @Test
    fun `folders are ordered by photo count descending`() {
        val choices = deviceFolderChoices(
            listOf(
                item("a1", "Alpha", 1),
                item("b1", "Beta", 1),
                item("b2", "Beta", 2),
                item("b3", "Beta", 3),
                item("g1", "Gamma", 1),
                item("g2", "Gamma", 2),
            ),
        )
        assertEquals(listOf("Beta", "Gamma", "Alpha"), choices.map { it.name })
    }

    @Test
    fun `an equal count breaks the tie by name case-insensitively`() {
        val choices = deviceFolderChoices(
            listOf(
                item("z", "Zebra", 1),
                item("a", "alpha", 1),
                item("c", "Charlie", 1),
            ),
        )
        assertEquals(listOf("alpha", "Charlie", "Zebra"), choices.map { it.name })
    }

    @Test
    fun `photos with a null or blank bucket are excluded`() {
        val choices = deviceFolderChoices(
            listOf(
                item("keep", "Trip", 1),
                item("noBucket", null, 2),
                item("empty", "", 3),
                item("spaces", "   ", 4),
            ),
        )
        assertEquals(1, choices.size)
        assertEquals("Trip", choices.single().name)
    }

    @Test
    fun `no device photos yields no choices`() {
        assertTrue(deviceFolderChoices(emptyList()).isEmpty())
    }
}
