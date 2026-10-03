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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the delete-safety invariant of the duplicate finder's selection: the user ticks the copies to
 * DELETE, so the dangerous state is a group with every copy ticked. [toggleDuplicateRemoval] refuses
 * exactly that and [selectAllButFirst] never ticks a group's first copy, which makes the
 * [keepIdsForRemoval] complement handed to the view model always non-empty and a whole group
 * impossible to wipe out. Verified here without the app, a device, or a user in the loop.
 */
class DuplicateSelectionSafetyTest {

    private val allIds = setOf("a", "b", "c")

    private fun deviceGroup(vararg uris: String) = DuplicateGroup(
        GroupType.DEVICE,
        uris.map { uri ->
            GalleryItem.LocalOnly(
                LocalMediaItem(
                    uri = uri,
                    dateTaken = 1_000L,
                    displayName = "photo.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 1024L,
                    bucketName = "Camera",
                ),
            )
        },
    )

    private fun cloudGroup(vararg linkIds: String) = DuplicateGroup(
        GroupType.CLOUD,
        linkIds.map { linkId ->
            GalleryItem.CloudOnly(
                CloudPhoto(
                    linkId = linkId,
                    shareId = "share1",
                    volumeId = "vol1",
                    captureTime = 1L,
                    displayName = "photo.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 1024L,
                    thumbnailUrl = null,
                    revisionId = "rev1",
                ),
            )
        },
    )

    private fun DuplicateGroup.ids(): Set<String> = items.map { it.stableId }.toSet()

    @Test
    fun ticking_the_last_untouched_copy_is_refused_and_returns_the_input_unchanged() {
        val fullMinusOne = setOf("a", "b")
        val result = toggleDuplicateRemoval(current = fullMinusOne, id = "c", allIds = allIds)
        assertEquals(fullMinusOne, result)
    }

    @Test
    fun no_single_tick_from_any_reachable_state_can_cover_the_whole_group() {
        // Every subset of the group, ticking every id: the result is never the full set.
        for (current in allIds.subsets()) {
            for (id in allIds) {
                val next = toggleDuplicateRemoval(current, id, allIds)
                assertTrue(
                    "toggle($current, $id) produced $next, which covers the whole group",
                    next.size < allIds.size,
                )
            }
        }
    }

    @Test
    fun a_two_copy_group_can_only_ever_tick_one_of_its_copies() {
        val pair = setOf("a", "b")
        val ticked = toggleDuplicateRemoval(current = emptySet(), id = "a", allIds = pair)
        assertEquals(setOf("a"), ticked)
        assertEquals(setOf("a"), toggleDuplicateRemoval(ticked, "b", pair))
    }

    @Test
    fun unticking_always_works_including_from_a_full_minus_one_set() {
        assertEquals(setOf("a"), toggleDuplicateRemoval(setOf("a", "b"), "b", allIds))
        assertEquals(emptySet<String>(), toggleDuplicateRemoval(setOf("a"), "a", allIds))
    }

    @Test
    fun unticking_then_reticking_round_trips() {
        val start = setOf("a", "b")
        val unticked = toggleDuplicateRemoval(start, "a", allIds)
        assertEquals(start, toggleDuplicateRemoval(unticked, "a", allIds))
    }

    @Test
    fun the_keep_set_is_never_empty_for_any_state_the_toggle_can_produce() {
        for (current in allIds.subsets()) {
            for (id in allIds) {
                val next = toggleDuplicateRemoval(current, id, allIds)
                val keep = keepIdsForRemoval(allIds, next)
                assertTrue("toggle($current, $id) left nothing to keep", keep.isNotEmpty())
            }
        }
    }

    @Test
    fun the_keep_set_is_the_complement_of_the_removal_set() {
        assertEquals(setOf("c"), keepIdsForRemoval(allIds, setOf("a", "b")))
        assertEquals(allIds, keepIdsForRemoval(allIds, emptySet()))
    }

    @Test
    fun ticking_every_copy_but_one_leaves_exactly_that_one() {
        val keeper = "b"
        val keep = keepIdsForRemoval(allIds, allIds - keeper)
        assertEquals(setOf(keeper), keep)
        assertEquals(1, keep.size)
    }

    @Test
    fun the_default_state_of_a_two_copy_group_leaves_exactly_one_keeper() {
        val pair = setOf("a", "b")
        assertEquals(setOf("a"), keepIdsForRemoval(pair, pair - "a"))
    }

    @Test
    fun select_all_but_the_first_ticks_every_copy_but_the_first_in_identical_and_similar_groups() {
        // The keeper is the copy listed first, not the lowest id: nothing ranks the copies.
        val identical = deviceGroup("z", "a", "m")
        val similar = cloudGroup("c2", "c1")
        val picked = selectAllButFirst(listOf(identical, similar))
        assertEquals(
            mapOf(
                duplicateGroupKey(identical) to setOf("a", "m"),
                duplicateGroupKey(similar) to setOf("c1"),
            ),
            picked,
        )
    }

    @Test
    fun select_all_but_the_first_never_selects_a_whole_group() {
        val groups = listOf(
            deviceGroup("a", "b"),
            deviceGroup("c", "d", "e", "f"),
            cloudGroup("g", "h", "i"),
        )
        val picked = selectAllButFirst(groups)
        for (group in groups) {
            val remove = picked.getValue(duplicateGroupKey(group))
            assertTrue("$remove covers the whole group ${group.ids()}", remove.size < group.ids().size)
            assertEquals(setOf(group.items.first().stableId), keepIdsForRemoval(group.ids(), remove))
        }
    }

    @Test
    fun select_all_but_the_first_selects_nothing_in_a_one_photo_group() {
        val single = deviceGroup("only")
        assertEquals(emptySet<String>(), selectAllButFirst(listOf(single)).getValue(duplicateGroupKey(single)))
    }

    @Test
    fun select_all_but_the_first_over_no_groups_selects_nothing() {
        assertTrue(selectAllButFirst(emptyList()).isEmpty())
    }

    @Test
    fun toggling_after_select_all_but_the_first_works_as_before() {
        val group = deviceGroup("a", "b", "c")
        val picked = selectAllButFirst(listOf(group)).getValue(duplicateGroupKey(group))
        // Ticking the kept first copy on top would cover the whole group, so it is refused.
        assertEquals(picked, toggleDuplicateRemoval(picked, "a", group.ids()))
        // Unticking a copy and then ticking the first moves the keeper to that copy.
        val unticked = toggleDuplicateRemoval(picked, "c", group.ids())
        assertEquals(setOf("b"), unticked)
        val moved = toggleDuplicateRemoval(unticked, "a", group.ids())
        assertEquals(setOf("a", "b"), moved)
        assertEquals(setOf("c"), keepIdsForRemoval(group.ids(), moved))
    }

    @Test
    fun select_all_keeps_a_group_the_user_picked_in_and_fills_the_groups_with_no_pick() {
        val picked = deviceGroup("a", "b", "c")
        val untouched = deviceGroup("d", "e", "f")
        val cleared = cloudGroup("g", "h")
        val current = mapOf(
            duplicateGroupKey(picked) to setOf("c"),
            duplicateGroupKey(cleared) to emptySet(),
        )
        val added = selectAllButFirstInUnpicked(listOf(picked, untouched, cleared), current)
        assertEquals(
            mapOf(
                duplicateGroupKey(untouched) to setOf("e", "f"),
                duplicateGroupKey(cleared) to setOf("h"),
            ),
            added,
        )
        val after = current + added
        assertEquals(setOf("c"), after.getValue(duplicateGroupKey(picked)))
    }

    @Test
    fun select_all_never_adds_to_a_hand_ticked_first_copy() {
        // The user moved the keeper by ticking the first copy; adding the rule's pick would cover the group.
        val group = deviceGroup("a", "b", "c")
        val current = mapOf(duplicateGroupKey(group) to setOf("a", "b"))
        val added = selectAllButFirstInUnpicked(listOf(group), current)
        assertTrue(added.isEmpty())
        val remove = (current + added).getValue(duplicateGroupKey(group))
        assertEquals(setOf("c"), keepIdsForRemoval(group.ids(), remove))
    }

    @Test
    fun select_all_with_no_picks_matches_select_all_but_the_first() {
        val groups = listOf(deviceGroup("a", "b"), cloudGroup("c", "d", "e"))
        assertEquals(selectAllButFirst(groups), selectAllButFirstInUnpicked(groups, emptyMap()))
    }

    @Test
    fun select_all_over_any_picks_never_selects_a_whole_group() {
        val group = deviceGroup("a", "b", "c")
        val other = deviceGroup("d", "e")
        for (pick in group.ids().subsets()) {
            if (pick.size == group.ids().size) continue
            val current = mapOf(duplicateGroupKey(group) to pick)
            val after = current + selectAllButFirstInUnpicked(listOf(group, other), current)
            for (g in listOf(group, other)) {
                val remove = after[duplicateGroupKey(g)].orEmpty()
                assertTrue("$pick then Select all left nothing of ${g.ids()}", keepIdsForRemoval(g.ids(), remove).isNotEmpty())
            }
        }
    }

    /** Every subset of this set, so a test can sweep all reachable selections of a small group. */
    private fun Set<String>.subsets(): List<Set<String>> {
        val ids = toList()
        return (0 until (1 shl ids.size)).map { mask ->
            ids.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
        }
    }
}
