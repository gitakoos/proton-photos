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

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.usecase.FindDuplicatesUseCase.DuplicateGroup
import eu.akoos.photos.domain.usecase.FindDuplicatesUseCase.GroupType
import eu.akoos.photos.presentation.duplicates.DuplicateFinderViewModel.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A deleted copy leaves every listed group by its id, whatever group object the delete was handed. */
class DuplicateDeletePruneTest {

    private fun local(uri: String) = GalleryItem.LocalOnly(
        LocalMediaItem(uri = uri, dateTaken = 1_000L, displayName = "photo.jpg", mimeType = "image/jpeg", sizeBytes = 1024L, bucketName = "Camera"),
    )

    private fun cloud(linkId: String) = GalleryItem.CloudOnly(
        CloudPhoto(
            linkId = linkId, shareId = "share1", volumeId = "vol1", captureTime = 1L, displayName = "photo.jpg",
            mimeType = "image/jpeg", sizeBytes = 1024L, thumbnailUrl = null, revisionId = "rev1",
        ),
    )

    private fun ids(groups: List<DuplicateGroup>) = groups.map { g -> g.items.map { it.stableId } }

    @Test
    fun a_pair_with_one_copy_deleted_leaves_the_list_even_when_the_group_object_was_rebuilt() {
        val shown = listOf(DuplicateGroup(GroupType.DEVICE, listOf(local("e1"), local("e2"))))
        // The object the review held: equal members, a different instance (a later fill pass rebuilt it).
        val handedToDelete = DuplicateGroup(GroupType.DEVICE, listOf(local("e1"), local("e2")))
        assert(shown[0] !== handedToDelete)
        assertEquals(emptyList<List<String>>(), ids(shown.withoutIds(setOf("e2"))))
    }

    @Test
    fun a_larger_group_keeps_its_other_copies_and_untouched_groups_stay_the_same_objects() {
        val similar = DuplicateGroup(GroupType.CLOUD, listOf(cloud("c1"), cloud("c2"), cloud("c3")))
        val other = DuplicateGroup(GroupType.CLOUD, listOf(cloud("x1"), cloud("x2")))
        val out = listOf(similar, other).withoutIds(setOf("c1"))
        assertEquals(listOf(listOf("c2", "c3"), listOf("x1", "x2")), ids(out))
        assertSame(other, out[1])
    }

    @Test
    fun no_deleted_ids_returns_the_same_list() {
        val groups = listOf(DuplicateGroup(GroupType.DEVICE, listOf(local("a"), local("b"))))
        assertSame(groups, groups.withoutIds(emptySet()))
    }

    @Test
    fun an_identical_regroup_that_lands_after_a_delete_does_not_bring_the_deleted_copy_back() {
        val staleRegroup = listOf(DuplicateGroup(GroupType.DEVICE, listOf(local("e1"), local("e2"))))
        val shown = UiState().withIdenticalGroups(staleRegroup, emptyList(), deleted = emptySet())
        val afterDelete = shown.withoutDeleted(setOf("e2"))
        val landed = afterDelete.withIdenticalGroups(staleRegroup, emptyList(), deleted = setOf("e2"))
        assertEquals(emptyList<List<String>>(), ids(landed.deviceGroups))
    }

    @Test
    fun a_similar_regroup_that_lands_after_a_delete_keeps_only_the_live_copies() {
        val staleRegroup = listOf(DuplicateGroup(GroupType.CLOUD, listOf(cloud("c1"), cloud("c2"), cloud("c3"))))
        val shown = UiState().withSimilarGroups(emptyList(), staleRegroup, deleted = emptySet(), scanning = false)
        val afterDelete = shown.withoutDeleted(setOf("c2"))
        val landed = afterDelete.withSimilarGroups(emptyList(), staleRegroup, deleted = setOf("c2"), scanning = false)
        assertEquals(listOf(listOf("c1", "c3")), ids(landed.similarCloudGroups))
    }

    @Test
    fun a_delete_prunes_the_copy_from_identical_and_similar_lists_alike() {
        val state = UiState(
            deviceGroups = listOf(DuplicateGroup(GroupType.DEVICE, listOf(local("e1"), local("e2")))),
            similarCloudGroups = listOf(DuplicateGroup(GroupType.CLOUD, listOf(cloud("c1"), cloud("c2"), cloud("c3")))),
        )
        val pruned = state.withoutDeleted(setOf("e1", "c3"))
        assertEquals(emptyList<List<String>>(), ids(pruned.deviceGroups))
        assertEquals(listOf(listOf("c1", "c2")), ids(pruned.similarCloudGroups))
    }
}
