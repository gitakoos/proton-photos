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
package eu.akoos.photos.presentation.duplicates

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.usecase.FindDuplicatesUseCase.DuplicateGroup
import eu.akoos.photos.domain.usecase.FindDuplicatesUseCase.GroupType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which duplicate groups count as ignored, and which remembered keys bring one back. */
class IgnoredDuplicateGroupsTest {

    private fun group(vararg uris: String) = DuplicateGroup(
        GroupType.DEVICE,
        uris.map { uri ->
            GalleryItem.LocalOnly(
                LocalMediaItem(
                    uri = uri, dateTaken = 1_000L, displayName = "photo.jpg", mimeType = "image/jpeg",
                    sizeBytes = 1024L, bucketName = "Camera",
                ),
            )
        },
    )

    @Test
    fun `the key does not depend on the order the copies are listed in`() {
        assertEquals(duplicateIgnoreKey(group("b", "a", "c")), duplicateIgnoreKey(group("c", "a", "b")))
        assertEquals("a|b|c", duplicateIgnoreKey(group("c", "a", "b")))
    }

    @Test
    fun `a remembered group is ignored however its copies are ordered`() {
        val ignored = IgnoredDuplicateGroups(setOf(duplicateIgnoreKey(group("a", "b"))))
        assertTrue(ignored.contains(group("a", "b")))
        assertTrue(ignored.contains(group("b", "a")))
    }

    @Test
    fun `a group that lost a copy stays ignored`() {
        val ignored = IgnoredDuplicateGroups(setOf(duplicateIgnoreKey(group("a", "b", "c"))))
        assertTrue(ignored.contains(group("a", "b")))
        assertTrue(ignored.contains(group("b", "c")))
    }

    @Test
    fun `a group that gained a copy it did not have is listed again`() {
        val ignored = IgnoredDuplicateGroups(setOf(duplicateIgnoreKey(group("a", "b"))))
        assertFalse(ignored.contains(group("a", "b", "c")))
    }

    @Test
    fun `an unrelated group, or nothing remembered, ignores nothing`() {
        assertFalse(IgnoredDuplicateGroups(setOf(duplicateIgnoreKey(group("a", "b")))).contains(group("x", "y")))
        assertFalse(IgnoredDuplicateGroups(setOf(duplicateIgnoreKey(group("a", "b")))).contains(group("b", "x")))
        assertFalse(IgnoredDuplicateGroups(emptySet()).contains(group("a", "b")))
    }

    @Test
    fun `the keys covering a group are the remembered ones that hold all its copies`() {
        val whole = duplicateIgnoreKey(group("a", "b", "c"))
        val other = duplicateIgnoreKey(group("x", "y"))
        val ignored = IgnoredDuplicateGroups(setOf(whole, other))
        assertEquals(listOf(whole), ignored.keysCovering(group("a", "c")))
        assertEquals(listOf(other), ignored.keysCovering(group("y", "x")))
        assertTrue(ignored.keysCovering(group("a", "x")).isEmpty())
    }

    @Test
    fun `an empty group is never ignored`() {
        assertFalse(IgnoredDuplicateGroups(setOf("a|b")).contains(DuplicateGroup(GroupType.DEVICE, emptyList())))
    }
}
