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

package eu.akoos.photos.presentation.people

import eu.akoos.photos.data.db.dao.FaceOnPhoto
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.gallery.FaceBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the face step of Add to person: the row order, the per-photo faces and the names under them, the
 *  face that starts chosen, the single-choice toggle, the face search offer, when the step is skipped and
 *  what the name step is handed. */
class AddToPersonFacesTest {

    private val anna = 7L
    private val unnamedGroup = 9L
    private val named = mapOf(anna to "Anna")

    private fun face(id: String, personId: Long? = null, score: Float = 0.9f, photo: String = "p") =
        FaceOnPhoto(id, photo, personId, 0.1f, 0.2f, 0.3f, 0.4f, score)

    private fun photo(uri: String, taken: Long = 0L) = GalleryItem.LocalOnly(
        LocalMediaItem(
            uri = uri,
            dateTaken = taken,
            displayName = "$uri.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1L,
            bucketName = null,
        ),
    )

    private fun option(id: String, name: String? = null) = AddToPersonFaceOption(id, FaceBox(0f, 0f, 1f, 1f), name)

    private fun row(uri: String, vararg faceIds: String, chosen: String? = null) =
        rowOf(uri, faceIds.map { option(it) }, chosen)

    private fun rowOf(uri: String, faces: List<AddToPersonFaceOption>, chosen: String? = null) =
        AddToPersonPhotoRow(item = photo(uri), faces = faces, chosenFaceId = chosen)

    private fun preselect(vararg faces: FaceOnPhoto) =
        AddToPersonFaces.preselect(AddToPersonFaces.options(faces.toList(), named))

    @Test
    fun `rows run newest first, one per photo, the id breaking a tie`() {
        val items = listOf(photo("b", 100L), photo("a", 300L), photo("c", 100L), photo("a", 300L))
        assertEquals(listOf("a", "b", "c"), AddToPersonFaces.orderedItems(items).map { it.stableId })
    }

    @Test
    fun `faces group per photo, clearest first`() {
        val grouped = AddToPersonFaces.facesByPhoto(
            listOf(
                face("x#1", score = 0.6f, photo = "x"),
                face("y#0", photo = "y"),
                face("x#2", score = 0.9f, photo = "x"),
                face("x#0", score = 0.9f, photo = "x"),
            ),
        )
        assertEquals(listOf("x#0", "x#2", "x#1"), grouped.getValue("x").map { it.id })
        assertEquals(listOf("y#0"), grouped.getValue("y").map { it.id })
    }

    @Test
    fun `a face on a named person carries the name, any other face none`() {
        val options = AddToPersonFaces.options(listOf(face("f0", anna), face("f1", unnamedGroup), face("f2")), named)
        assertEquals(listOf("f0", "f1", "f2"), options.map { it.faceId })
        assertEquals(listOf("Anna", null, null), options.map { it.personName })
        assertEquals(FaceBox(0.1f, 0.2f, 0.3f, 0.4f), options.first().box)
    }

    @Test
    fun `the named people leave out the Unsorted bucket and blank names`() {
        val people = listOf(
            PersonEntity(id = 1L, userId = "u", displayName = "Anna"),
            PersonEntity(id = 2L, userId = "u", displayName = "  "),
            PersonEntity(id = 3L, userId = "u", displayName = null),
            PersonEntity(id = 4L, userId = "u", displayName = "Unsorted", isOther = true),
        )
        assertEquals(mapOf(1L to "Anna"), AddToPersonFaces.namedPeople(people))
    }

    @Test
    fun `a lone face nobody named holds starts chosen`() {
        assertEquals("f0", preselect(face("f0")))
        assertEquals("f0", preselect(face("f0", unnamedGroup)))
    }

    @Test
    fun `a lone face on a named person starts unchosen`() {
        assertNull(preselect(face("f0", anna)))
    }

    @Test
    fun `a photo of several faces starts with none chosen`() {
        assertNull(preselect(face("f0"), face("f1")))
        assertNull(preselect(face("f0", unnamedGroup), face("f1", anna)))
        assertNull(preselect())
    }

    @Test
    fun `a tap chooses a face, a second tap clears it, a tap on another switches`() {
        val chosen = AddToPersonFaces.toggle(row("p", "f0", "f1"), "f0")
        assertEquals("f0", chosen.chosenFaceId)
        assertEquals("f1", AddToPersonFaces.toggle(chosen, "f1").chosenFaceId)
        assertNull(AddToPersonFaces.toggle(chosen, "f0").chosenFaceId)
        assertSame(chosen, AddToPersonFaces.toggle(chosen, "q#0"))
    }

    @Test
    fun `a face search is offered only for a photo with no face row at all`() {
        assertTrue(AddToPersonFaces.canFindFaces(faceRecognitionOn = true, hasKeptFace = false, hasAnyFaceRow = false))
        assertFalse(AddToPersonFaces.canFindFaces(faceRecognitionOn = true, hasKeptFace = false, hasAnyFaceRow = true))
        assertFalse(AddToPersonFaces.canFindFaces(faceRecognitionOn = true, hasKeptFace = true, hasAnyFaceRow = true))
        assertFalse(AddToPersonFaces.canFindFaces(faceRecognitionOn = false, hasKeptFace = false, hasAnyFaceRow = false))
    }

    @Test
    fun `exactly one photo with exactly one unnamed face skips the face step`() {
        assertEquals("a#0", AddToPersonFaces.loneFace(listOf(row("a", "a#0")))?.faceId)
    }

    @Test
    fun `a lone face a named person holds goes through the face step`() {
        assertNull(AddToPersonFaces.loneFace(listOf(rowOf("a", listOf(option("a#0", "Anna"))))))
    }

    @Test
    fun `any other selection goes through the face step`() {
        assertNull(AddToPersonFaces.loneFace(listOf(row("a", "a#0", "a#1"))))
        assertNull(AddToPersonFaces.loneFace(listOf(row("a", "a#0"), row("b", "b#0"))))
        assertNull(AddToPersonFaces.loneFace(listOf(row("a"))))
        assertNull(AddToPersonFaces.loneFace(emptyList()))
    }

    @Test
    fun `the name step gets the chosen faces and the photos without one`() {
        val rows = listOf(
            row("a", "a#0", chosen = "a#0"),
            row("b", "b#0", "b#1"),
            row("c"),
            row("d", "d#0", "d#1", chosen = "d#1"),
        )
        val subject = AddToPersonFaces.nameSubject(rows) { "src:${it.stableId}" }
        assertEquals(listOf("a#0", "d#1"), subject.faces.map { it.faceId })
        assertEquals(listOf("a", "d"), subject.faces.map { it.photoKey })
        assertEquals(listOf("src:a", "src:d"), subject.faces.map { it.faceSource })
        assertEquals(FaceBox(0f, 0f, 1f, 1f), subject.faces.first().faceBox)
        assertEquals(listOf("b", "c"), subject.manualPhotoKeys)
    }

    @Test
    fun `a choice the row does not hold leaves the photo without a face, never dropped`() {
        val subject = AddToPersonFaces.nameSubject(listOf(row("a", "a#0", chosen = "gone"))) { null }
        assertTrue(subject.faces.isEmpty())
        assertEquals(listOf("a"), subject.manualPhotoKeys)
    }

    @Test
    fun `a skipped face step hands over the lone unnamed face`() {
        val subject = AddToPersonFaces.loneFaceSubject(listOf(row("a", "a#0"))) { "src" }
        assertEquals(listOf("a#0"), subject?.faces?.map { it.faceId })
        assertEquals(emptyList<String>(), subject?.manualPhotoKeys)
        assertNull(AddToPersonFaces.loneFaceSubject(listOf(rowOf("a", listOf(option("a#0", "Anna"))))) { "src" })
        assertNull(AddToPersonFaces.loneFaceSubject(listOf(row("a", "a#0"), row("b"))) { "src" })
    }
}
