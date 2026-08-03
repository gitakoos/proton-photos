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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-value coverage for [MetadataStripConfig.allowedCopyGroups], the decision the upload compressor
 * consults before it re-injects EXIF onto a recompressed JPEG. The compressor always re-encodes to a
 * writable JPEG and rebuilds its EXIF from scratch, so this group-level allow-list is what actually
 * closes the strip leak for every source format (including one whose container cannot be rewritten in
 * place). Each flag maps to exactly one group and a stripped group must be absent from the result. No
 * Android, no Context, no ExifInterface, no Robolectric: plain JVM assertions on the five booleans.
 */
class MetadataStripConfigTest {

    @Test
    fun `nothing stripped allows every group`() {
        assertEquals(
            setOf(
                ExifMetadataGroup.GPS,
                ExifMetadataGroup.CAMERA,
                ExifMetadataGroup.TIMESTAMP,
                ExifMetadataGroup.SOFTWARE,
                ExifMetadataGroup.AUTHORSHIP,
            ),
            MetadataStripConfig().allowedCopyGroups(),
        )
    }

    @Test
    fun `stripping GPS only drops the GPS group`() {
        assertEquals(
            setOf(
                ExifMetadataGroup.CAMERA,
                ExifMetadataGroup.TIMESTAMP,
                ExifMetadataGroup.SOFTWARE,
                ExifMetadataGroup.AUTHORSHIP,
            ),
            MetadataStripConfig(stripGps = true).allowedCopyGroups(),
        )
    }

    @Test
    fun `stripping camera info only drops the camera group`() {
        assertEquals(
            setOf(
                ExifMetadataGroup.GPS,
                ExifMetadataGroup.TIMESTAMP,
                ExifMetadataGroup.SOFTWARE,
                ExifMetadataGroup.AUTHORSHIP,
            ),
            MetadataStripConfig(stripCameraInfo = true).allowedCopyGroups(),
        )
    }

    @Test
    fun `stripping timestamp only drops the timestamp group`() {
        assertEquals(
            setOf(
                ExifMetadataGroup.GPS,
                ExifMetadataGroup.CAMERA,
                ExifMetadataGroup.SOFTWARE,
                ExifMetadataGroup.AUTHORSHIP,
            ),
            MetadataStripConfig(stripTimestamp = true).allowedCopyGroups(),
        )
    }

    @Test
    fun `stripping software info only drops the software group`() {
        assertEquals(
            setOf(
                ExifMetadataGroup.GPS,
                ExifMetadataGroup.CAMERA,
                ExifMetadataGroup.TIMESTAMP,
                ExifMetadataGroup.AUTHORSHIP,
            ),
            MetadataStripConfig(stripSoftwareInfo = true).allowedCopyGroups(),
        )
    }

    @Test
    fun `stripping authorship only drops the authorship group`() {
        assertEquals(
            setOf(
                ExifMetadataGroup.GPS,
                ExifMetadataGroup.CAMERA,
                ExifMetadataGroup.TIMESTAMP,
                ExifMetadataGroup.SOFTWARE,
            ),
            MetadataStripConfig(stripAuthorship = true).allowedCopyGroups(),
        )
    }

    @Test
    fun `stripping everything allows no group`() {
        assertEquals(
            emptySet<ExifMetadataGroup>(),
            MetadataStripConfig(
                stripGps = true,
                stripCameraInfo = true,
                stripTimestamp = true,
                stripSoftwareInfo = true,
                stripAuthorship = true,
            ).allowedCopyGroups(),
        )
    }

    /**
     * The group allow-list only protects a tag that [ExifHelper.groupForTag] actually classifies: an
     * unclassified tag copies unconditionally. So a GPS tag added to [COPYABLE_EXIF_TAGS] but not to
     * the GPS group would silently reintroduce location onto a stripped upload. This pins that no such
     * tag exists, which is the invariant the leak fix rests on.
     */
    @Test
    fun `a GPS strip blocks every copyable GPS tag`() {
        val gpsTags = COPYABLE_EXIF_TAGS.filter { it.startsWith("GPS") }
        assertTrue("no GPS tag found, the tag naming assumption broke", gpsTags.isNotEmpty())

        val allowed = MetadataStripConfig(stripGps = true).allowedCopyGroups()
        val survivors = gpsTags.filter { tag ->
            val group = ExifHelper.groupForTag(tag)
            group == null || group in allowed
        }
        assertEquals("these GPS tags would still be copied onto a stripped upload", emptyList<String>(), survivors)
    }
}
