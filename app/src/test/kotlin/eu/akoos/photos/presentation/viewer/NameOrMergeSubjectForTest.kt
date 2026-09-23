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

package eu.akoos.photos.presentation.viewer

import eu.akoos.photos.presentation.gallery.FaceBox
import eu.akoos.photos.presentation.people.NameOrMergeSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins what a tap on a face tag or a People pill opens: a named person's page, or the naming sheet for a
 * nameless face, asked about as that one face for an Unsorted leftover and as the group for an unnamed
 * group, never as the whole Unsorted bucket. Plain JVM assertions, no Android.
 */
class NameOrMergeSubjectForTest {

    private val box = FaceBox(0.25f, 0.25f, 0.5f, 0.5f)
    private val source = "https://thumbs.example/photo-42.jpg"

    private fun person(
        personId: Long,
        faceId: String,
        name: String? = null,
        isOther: Boolean = false,
        photoCount: Int = 0,
    ) = PhotoViewerViewModel.ViewerPerson(
        personId = personId,
        name = name,
        faceBox = box,
        faceId = faceId,
        isOther = isOther,
        photoCount = photoCount,
    )

    @Test
    fun `a named person opens their page`() {
        assertNull(nameOrMergeSubjectFor(person(1L, "face-a", name = "Alice", photoCount = 12), source))
    }

    @Test
    fun `an Unsorted face is named on its own`() {
        assertEquals(
            NameOrMergeSubject.Face(faceId = "face-c", faceSource = source, faceBox = box),
            nameOrMergeSubjectFor(person(9L, "face-c", isOther = true), source),
        )
    }

    @Test
    fun `the Unsorted bucket is never asked about as a group, even carrying a name`() {
        assertEquals(
            NameOrMergeSubject.Face(faceId = "face-c", faceSource = source, faceBox = box),
            nameOrMergeSubjectFor(person(9L, "face-c", name = "Unsorted", isOther = true, photoCount = 40), source),
        )
    }

    @Test
    fun `an unnamed group is asked about as the group, headed by its face on this photo`() {
        assertEquals(
            NameOrMergeSubject.Group(personId = 4L, photoCount = 7, coverSource = source, faceBox = box),
            nameOrMergeSubjectFor(person(4L, "face-b", photoCount = 7), source),
        )
    }

    @Test
    fun `a blank name counts as none`() {
        for (blank in listOf("", " ", "\t")) {
            assertEquals(
                NameOrMergeSubject.Group(personId = 4L, photoCount = 3, coverSource = source, faceBox = box),
                nameOrMergeSubjectFor(person(4L, "face-b", name = blank, photoCount = 3), source),
            )
        }
    }

    @Test
    fun `a group with no count known passes zero on, which the sheet leaves unshown`() {
        assertEquals(
            NameOrMergeSubject.Group(personId = 4L, photoCount = 0, coverSource = source, faceBox = box),
            nameOrMergeSubjectFor(person(4L, "face-b"), source),
        )
    }

    @Test
    fun `with no image to crop from the sheet still opens`() {
        // The sheet shows its person glyph where the face would be.
        assertEquals(
            NameOrMergeSubject.Face(faceId = "face-c", faceSource = null, faceBox = box),
            nameOrMergeSubjectFor(person(9L, "face-c", isOther = true), faceSource = null),
        )
        assertEquals(
            NameOrMergeSubject.Group(personId = 4L, photoCount = 2, coverSource = null, faceBox = box),
            nameOrMergeSubjectFor(person(4L, "face-b", photoCount = 2), faceSource = null),
        )
    }
}
