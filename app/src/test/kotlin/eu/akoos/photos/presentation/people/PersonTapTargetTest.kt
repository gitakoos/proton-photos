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

import eu.akoos.photos.presentation.gallery.PersonUi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the one tap rule the people surfaces share (the review list, the face-recognition groups in
 * Settings, the Collection's people row): a named person opens their page, a group with no name opens the
 * name-or-merge sheet, and the Unsorted bucket opens its own page.
 */
class PersonTapTargetTest {

    private fun person(name: String?, isOther: Boolean = false, faceCount: Int = 5) =
        PersonUi(personId = 1L, displayName = name, coverPhotoKey = "k1", faceCount = faceCount, isOther = isOther)

    @Test
    fun `a named person opens their page`() {
        assertEquals(PersonTapTarget.Page, personTapTarget(person("Anna")))
    }

    @Test
    fun `a group with no name opens the name-or-merge sheet`() {
        assertEquals(PersonTapTarget.NameOrMerge, personTapTarget(person(null)))
    }

    @Test
    fun `a blank name counts as no name`() {
        assertEquals(PersonTapTarget.NameOrMerge, personTapTarget(person("")))
        assertEquals(PersonTapTarget.NameOrMerge, personTapTarget(person("   ")))
    }

    @Test
    fun `a small group with no name still opens the sheet`() {
        assertEquals(PersonTapTarget.NameOrMerge, personTapTarget(person(null, faceCount = 1)))
    }

    @Test
    fun `the Unsorted bucket opens its page and is never named as a whole`() {
        assertEquals(PersonTapTarget.Page, personTapTarget(person(null, isOther = true, faceCount = 40)))
        assertEquals(PersonTapTarget.Page, personTapTarget(person("Unsorted", isOther = true)))
    }
}
