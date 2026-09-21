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

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CategorizeItem] is pure, so every case below is a plain data assertion with no Android or Room
 * involved. The tag ids are the Drive PhotoTag enum: 0 Favorites, 1 Screenshots, 2 Videos,
 * 3 LivePhotos, 4 MotionPhotos, 5 Selfies, 6 Portraits, 7 Bursts, 8 Panoramas, 9 Raw.
 */
class CategorizeItemTest {

    private fun local(
        uri: String = "content://media/external/images/media/1",
        displayName: String = "IMG_0001.jpg",
        mimeType: String = "image/jpeg",
        bucketName: String? = "Camera",
        width: Int = 4000,
        height: Int = 3000,
        tags: Set<Int> = emptySet(),
        userTags: Set<Int> = emptySet(),
    ) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_700_000_000_000L,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = 2048L,
        bucketName = bucketName,
        width = width,
        height = height,
        tags = tags,
        userTags = userTags,
    )

    private fun cloud(
        displayName: String = "IMG_0001.jpg",
        mimeType: String = "image/jpeg",
        tags: Set<Int> = emptySet(),
    ) = CloudPhoto(
        linkId = "link1",
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1_700_000_000L,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = 2048L,
        thumbnailUrl = null,
        revisionId = "rev1",
        tags = tags,
    )

    // region the user's set outranks every automatic source

    @Test
    fun `user set wins over the cached detector set`() {
        // The detector found a motion photo and a panorama; the user says it is a screenshot.
        val item = GalleryItem.LocalOnly(local(tags = setOf(4, 8), userTags = setOf(1)))

        assertEquals(setOf(1), CategorizeItem.classify(item))
        assertTrue(CategorizeItem.belongsTo(item, 1))
        assertFalse(CategorizeItem.belongsTo(item, 4))
        assertFalse(CategorizeItem.belongsTo(item, 8))
    }

    @Test
    fun `user set wins over the heuristics`() {
        // Every signal the heuristics read says screenshot: the bucket, the filename and the mime.
        val item = GalleryItem.LocalOnly(
            local(
                displayName = "Screenshot_20260724.png",
                mimeType = "image/png",
                bucketName = "Screenshots",
                userTags = setOf(9),
            )
        )

        assertEquals(setOf(9), CategorizeItem.classify(item))
        assertTrue(CategorizeItem.belongsTo(item, 9))
        assertFalse(CategorizeItem.belongsTo(item, 1))
    }

    @Test
    fun `a backed-up photo reports its server tags and ignores a device-side user set`() {
        // Once a photo is backed up Drive owns the answer: the chips write straight to the server for
        // one of these, and another client can change them too, so a device-side set must not outrank
        // what the server reports. A choice made before the upload survives by riding along in it.
        val item = GalleryItem.Synced(cloud(tags = setOf(0, 5)), local(userTags = setOf(6)))

        assertEquals(setOf(0, 5), CategorizeItem.classify(item))
        assertTrue(CategorizeItem.belongsTo(item, 0))
        assertTrue(CategorizeItem.belongsTo(item, 5))
        assertFalse(CategorizeItem.belongsTo(item, 6))
    }

    // endregion
    // region a cached detection and the folder answer different halves of the question

    @Test
    fun `a cached detection does not silence the folder the file sits in`() {
        // The scanner reads the file and never the folder, so a detection it found cannot stand as
        // the whole answer: this video would otherwise stop matching the Live Photos chip the moment
        // its scan landed, having matched it right up until then.
        val item = GalleryItem.LocalOnly(
            local(displayName = "VID_0009.mp4", mimeType = "video/mp4", bucketName = "Live Photos", tags = setOf(2))
        )

        assertEquals(setOf(2, 3), CategorizeItem.classify(item))
        assertTrue(CategorizeItem.belongsTo(item, 2))
        assertTrue(CategorizeItem.belongsTo(item, 3))
    }

    @Test
    fun `a scan landing does not change which chips a file answers to`() {
        // The same file before and after its scan. The detection only ever confirms what the cheap
        // heuristics already said about a video, so the two sets have to be identical.
        val unscanned = GalleryItem.LocalOnly(
            local(displayName = "VID_0010.mp4", mimeType = "video/mp4", bucketName = "Burst")
        )
        val scanned = GalleryItem.LocalOnly(
            local(displayName = "VID_0010.mp4", mimeType = "video/mp4", bucketName = "Burst", tags = setOf(2))
        )

        assertEquals(setOf(2, 7), CategorizeItem.classify(unscanned))
        assertEquals(CategorizeItem.classify(unscanned), CategorizeItem.classify(scanned))
    }

    @Test
    fun `the XMP markers only the scanner can see survive the merge`() {
        // The other direction: the folder says nothing here, and the motion and panorama markers the
        // heuristics have no way of reading still come through.
        val item = GalleryItem.LocalOnly(local(displayName = "IMG_0011.jpg", tags = setOf(4, 8)))

        assertEquals(setOf(4, 8), CategorizeItem.classify(item))
    }

    @Test
    fun `the shape guess stays a fallback for a file the scanner has already read`() {
        // A wide RAW is not a panorama: the scanner opened the file and found no GPano marker, so the
        // aspect ratio does not get to overrule it.
        val scanned = GalleryItem.LocalOnly(
            local(displayName = "IMG_0012.dng", mimeType = "image/x-adobe-dng", width = 8000, height = 2000, tags = setOf(9))
        )

        assertEquals(setOf(9), CategorizeItem.classify(scanned))
        assertFalse(CategorizeItem.belongsTo(scanned, 8))
    }

    // endregion
    // region the not-yet-scanned file, where the two "empty" meanings would collide

    @Test
    fun `unscanned video keeps the heuristic video marker when the user picked nothing`() {
        // No detection cached yet and no user choice: the heuristics still owe this file a verdict.
        val item = GalleryItem.LocalOnly(
            local(displayName = "VID_20260724.mp4", mimeType = "video/mp4", width = 1920, height = 1080)
        )

        assertEquals(setOf(2), CategorizeItem.classify(item))
        assertTrue(CategorizeItem.belongsTo(item, 2))
    }

    @Test
    fun `unscanned video takes the user set in place of the heuristics`() {
        // Same unscanned file, now carrying a choice. The choice is complete, so the video marker
        // goes only because the user's set leaves it out, never because the lookup was cut short.
        val item = GalleryItem.LocalOnly(
            local(
                displayName = "VID_20260724.mp4",
                mimeType = "video/mp4",
                width = 1920,
                height = 1080,
                userTags = setOf(3),
            )
        )

        assertEquals(setOf(3), CategorizeItem.classify(item))
        assertTrue(CategorizeItem.belongsTo(item, 3))
        assertFalse(CategorizeItem.belongsTo(item, 2))
    }

    @Test
    fun `a user set that keeps the video marker keeps it alongside its own picks`() {
        val item = GalleryItem.LocalOnly(
            local(
                displayName = "VID_20260724.mp4",
                mimeType = "video/mp4",
                width = 1920,
                height = 1080,
                userTags = setOf(2, 7),
            )
        )

        assertEquals(setOf(2, 7), CategorizeItem.classify(item))
    }

    // endregion
    // region an empty user set changes nothing

    /**
     * Every automatic path, each with an empty user set, pinned to its exact result. A photo nobody
     * has touched must categorise the same as it does with no user column at all.
     */
    @Test
    fun `empty user set leaves every automatic path untouched`() {
        for ((label, item, expected) in untouchedCases()) {
            assertEquals(label, expected, CategorizeItem.classify(item))
        }
    }

    @Test
    fun `clearing every category falls back to automatic detection`() {
        // An emptied choice is stored as an empty set, which reads as "no choice made".
        val cleared = GalleryItem.LocalOnly(
            local(displayName = "VID_20260724.mp4", mimeType = "video/mp4", userTags = emptySet())
        )
        val untouched = GalleryItem.LocalOnly(
            local(displayName = "VID_20260724.mp4", mimeType = "video/mp4")
        )

        assertEquals(CategorizeItem.classify(untouched), CategorizeItem.classify(cleared))
    }

    @Test
    fun `a cloud-only item carries no user set and is unaffected`() {
        val item = GalleryItem.CloudOnly(cloud(displayName = "VID.mp4", mimeType = "video/mp4", tags = setOf(0, 5)))

        assertEquals(setOf(0, 2, 5), CategorizeItem.classify(item))
    }

    // endregion

    @Test
    fun `belongsTo agrees with classify for every tag`() {
        val cases = untouchedCases().map { (label, item, _) -> label to item } + listOf(
            "user set over detector" to GalleryItem.LocalOnly(local(tags = setOf(4, 8), userTags = setOf(1))),
            "user set over heuristics" to GalleryItem.LocalOnly(
                local(displayName = "Screenshot_1.png", mimeType = "image/png", bucketName = "Screenshots", userTags = setOf(9))
            ),
            "user set over server tags" to GalleryItem.Synced(cloud(tags = setOf(0, 5)), local(userTags = setOf(6))),
            "user set on an unscanned video" to GalleryItem.LocalOnly(
                local(displayName = "VID.mp4", mimeType = "video/mp4", userTags = setOf(3))
            ),
            "user set spanning several ids" to GalleryItem.LocalOnly(local(userTags = setOf(1, 2, 8, 9))),
        )

        for ((label, item) in cases) {
            val classified = CategorizeItem.classify(item)
            for (tagId in 0..9) {
                assertEquals(
                    "$label, tag $tagId",
                    tagId in classified,
                    CategorizeItem.belongsTo(item, tagId),
                )
            }
        }
    }

    /** Items with no user choice, each paired with the set the automatic paths produce for it. */
    private fun untouchedCases(): List<Triple<String, GalleryItem, Set<Int>>> = listOf(
        Triple(
            "plain camera photo",
            GalleryItem.LocalOnly(local()),
            emptySet(),
        ),
        Triple(
            "screenshot by bucket and name",
            GalleryItem.LocalOnly(
                local(displayName = "Screenshot_20260724.png", mimeType = "image/png", bucketName = "Screenshots", width = 1080, height = 2400)
            ),
            setOf(1),
        ),
        Triple(
            "screenshot by the screen_shot filename",
            GalleryItem.LocalOnly(
                local(displayName = "screen_shot_5.png", mimeType = "image/png", bucketName = "Pictures", width = 1080, height = 1920)
            ),
            setOf(1),
        ),
        Triple(
            "video by mime",
            GalleryItem.LocalOnly(local(displayName = "VID_20260724.mp4", mimeType = "video/mp4", width = 1920, height = 1080)),
            setOf(2),
        ),
        Triple(
            "live photo by bucket",
            GalleryItem.LocalOnly(local(displayName = "IMG_0002.jpg", bucketName = "Live Photos")),
            setOf(3),
        ),
        Triple(
            "motion photo by filename",
            GalleryItem.LocalOnly(local(displayName = "MVIMG_0003.jpg")),
            setOf(4),
        ),
        Triple(
            "burst by bucket",
            GalleryItem.LocalOnly(local(displayName = "IMG_0004.jpg", bucketName = "Burst")),
            setOf(7),
        ),
        Triple(
            "panorama by aspect ratio",
            GalleryItem.LocalOnly(local(displayName = "PANO_0005.jpg", width = 8000, height = 2000)),
            setOf(8),
        ),
        Triple(
            "tall screenshot stays out of panoramas",
            GalleryItem.LocalOnly(
                local(displayName = "Screenshot_tall.png", mimeType = "image/png", bucketName = "Screenshots", width = 1080, height = 3600)
            ),
            setOf(1),
        ),
        Triple(
            "raw by extension",
            GalleryItem.LocalOnly(local(displayName = "IMG_0006.dng", mimeType = "image/x-adobe-dng")),
            setOf(9),
        ),
        Triple(
            "cached detector set the folder adds nothing to",
            GalleryItem.LocalOnly(local(displayName = "IMG_0007.jpg", tags = setOf(4, 8))),
            setOf(4, 8),
        ),
        Triple(
            "cached video marker joined by the folder the file sits in",
            GalleryItem.LocalOnly(
                local(displayName = "VID_0009.mp4", mimeType = "video/mp4", bucketName = "Live Photos", tags = setOf(2))
            ),
            setOf(2, 3),
        ),
        Triple(
            "cloud-only favourite selfie video",
            GalleryItem.CloudOnly(cloud(displayName = "VID.mp4", mimeType = "video/mp4", tags = setOf(0, 5))),
            setOf(0, 2, 5),
        ),
        Triple(
            "synced favourite video",
            GalleryItem.Synced(
                cloud(displayName = "VID_0008.mp4", mimeType = "video/mp4", tags = setOf(0)),
                local(displayName = "VID_0008.mp4", mimeType = "video/mp4", width = 1920, height = 1080),
            ),
            setOf(0, 2),
        ),
    )
}
