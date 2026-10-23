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

import eu.akoos.photos.R
import eu.akoos.photos.domain.usecase.AssignFacesToPersonUseCase
import eu.akoos.photos.presentation.gallery.FaceBox
import eu.akoos.photos.presentation.gallery.PersonUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the name-or-merge sheet's rules: who it offers, what naming a group reports, when a selection may
 * create a person and which of its photos go in without a face, and how an action's result finds its way
 * back to the sheet it was started from.
 */
class NameOrMergeRulesTest {

    private fun person(id: Long, name: String?, isOther: Boolean = false) =
        PersonUi(personId = id, displayName = name, coverPhotoKey = "k$id", isOther = isOther)

    private fun chosen(faceId: String, photoKey: String, source: Any? = null, box: FaceBox? = null) =
        ChosenFace(faceId = faceId, photoKey = photoKey, faceSource = source, faceBox = box)

    private fun faces(vararg picked: ChosenFace, manual: List<String> = emptyList()) =
        NameOrMergeSubject.Faces(faces = picked.toList(), manualPhotoKeys = manual)

    private fun offered(people: List<PersonUi>, subjectPersonId: Long? = null) =
        NameOrMergeRules.pickablePeople(people, subjectPersonId).map { it.personId }

    @Test
    fun `only named people are offered, in the given order`() {
        val list = listOf(person(1, "Anna"), person(2, null), person(3, "  "), person(4, "Béla"), person(5, ""))
        assertEquals(listOf(1L, 4L), offered(list))
    }

    @Test
    fun `the Unsorted bucket is never offered`() {
        val list = listOf(person(1, "Anna"), person(9, null, isOther = true), person(10, "Unsorted", isOther = true))
        assertEquals(listOf(1L), offered(list))
    }

    @Test
    fun `the group being named is left out of its own choices`() {
        val list = listOf(person(1, "Anna"), person(2, "Béla"))
        assertEquals(listOf(1L), offered(list, subjectPersonId = 2L))
        assertEquals(listOf(1L, 2L), offered(list, subjectPersonId = 7L))
    }

    @Test
    fun `keeping its own id reads as a rename and folding into someone else as a merge`() {
        assertEquals(R.string.person_msg_renamed, NameOrMergeRules.groupMessage(5L, 5L))
        assertEquals(R.string.person_msg_merged, NameOrMergeRules.groupMessage(5L, 8L))
    }

    @Test
    fun `a subject keeps its key whatever image it carries`() {
        val withImage = NameOrMergeSubject.Face("f1", "content://media/1", FaceBox(0.1f, 0.1f, 0.4f, 0.4f))
        assertEquals(withImage.key, NameOrMergeSubject.Face("f1", null, null).key)
        assertEquals(
            NameOrMergeSubject.Group(7L, 3, "cover", null).key,
            NameOrMergeSubject.Group(7L, 12, null, FaceBox(0f, 0f, 1f, 1f)).key,
        )
    }

    @Test
    fun `faces and groups never share a key`() {
        assertNotEquals(NameOrMergeSubject.Face("7", null, null).key, NameOrMergeSubject.Group(7L, 1, null, null).key)
        assertNotEquals(NameOrMergeSubject.Face("a", null, null).key, NameOrMergeSubject.Face("b", null, null).key)
        assertNotEquals(NameOrMergeSubject.Group(7L, 1, null, null).key, NameOrMergeSubject.Group(8L, 1, null, null).key)
        assertNotEquals(NameOrMergeSubject.Face("f1", null, null).key, faces(chosen("f1", "a")).key)
    }

    @Test
    fun `a selection keeps its key whatever images its faces carry`() {
        assertEquals(
            faces(chosen("f1", "a", "content://media/1", FaceBox(0.1f, 0.1f, 0.4f, 0.4f)), manual = listOf("b")).key,
            faces(chosen("f1", "a"), manual = listOf("b")).key,
        )
    }

    @Test
    fun `selections differ by their faces and by their photos without one`() {
        val base = faces(chosen("f1", "a"), manual = listOf("b"))
        assertNotEquals(base.key, faces(chosen("f2", "a"), manual = listOf("b")).key)
        assertNotEquals(base.key, faces(chosen("f1", "a"), manual = listOf("c")).key)
        assertNotEquals(base.key, faces(chosen("f1", "a")).key)
    }

    @Test
    fun `a selection may create a person only while it holds a face`() {
        assertTrue(NameOrMergeRules.canCreate(faces(chosen("f1", "a"), manual = listOf("b"))))
        assertFalse(NameOrMergeRules.canCreate(faces(manual = listOf("a", "b"))))
        assertTrue(NameOrMergeRules.canCreate(NameOrMergeSubject.Face("f1", null, null)))
        assertTrue(NameOrMergeRules.canCreate(NameOrMergeSubject.Group(7L, 3, null, null)))
    }

    @Test
    fun `the photos without a face are kept, and every photo when no face was written`() {
        val subject = faces(chosen("f1", "a"), chosen("f2", "b"), manual = listOf("c", "d"))
        assertEquals(listOf("c", "d"), NameOrMergeRules.manualPhotoKeys(subject, facesAssigned = true))
        assertEquals(listOf("a", "b", "c", "d"), NameOrMergeRules.manualPhotoKeys(subject, facesAssigned = false))
        assertEquals(listOf("a"), NameOrMergeRules.manualPhotoKeys(faces(manual = listOf("a")), facesAssigned = false))
    }

    @Test
    fun `a picked person takes the photos even when no face was written, a typed name does not`() {
        val picked = AssignFacesToPersonUseCase.Target.Existing(5L)
        val typed = AssignFacesToPersonUseCase.Target.Named("Anna")
        assertEquals(5L, NameOrMergeRules.facesPerson(5L, picked))
        assertEquals(5L, NameOrMergeRules.facesPerson(-1L, picked))
        assertEquals(9L, NameOrMergeRules.facesPerson(9L, typed))
        assertNull(NameOrMergeRules.facesPerson(-1L, typed))
    }

    @Test
    fun `a stack shows up to three faces, past that two and a count`() {
        assertEquals(2 to 0, NameOrMergeRules.faceStack(2))
        assertEquals(3 to 0, NameOrMergeRules.faceStack(3))
        assertEquals(2 to 2, NameOrMergeRules.faceStack(4))
        assertEquals(2 to 8, NameOrMergeRules.faceStack(10))
    }

    @Test
    fun `the people most alike to the face lead, the rest keep their order after them`() {
        val list = listOf(person(1, "Anna"), person(2, "Béla"), person(3, "Cili"), person(4, "Dani"))
        val ranked = NameOrMergeRules.mostAlikeFirst(list, mapOf(3L to 0.81f, 1L to 0.42f))
        assertEquals(listOf(3L, 1L, 2L, 4L), ranked.map { it.personId })
    }

    @Test
    fun `with nothing to compare the list keeps its own order`() {
        val list = listOf(person(1, "Anna"), person(2, "Béla"))
        assertEquals(list, NameOrMergeRules.mostAlikeFirst(list, emptyMap()))
    }
}
