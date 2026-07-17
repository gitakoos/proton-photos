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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the delete-safety invariant of the duplicate finder's selection: the user ticks the copies to
 * DELETE, so the dangerous state is a group with every copy ticked. [toggleDuplicateRemoval] refuses
 * exactly that, which makes the [keepIdsForRemoval] complement handed to the view model always
 * non-empty and a whole group impossible to wipe out. Verified here without the app, a device, or a
 * user in the loop.
 */
class DuplicateSelectionSafetyTest {

    private val allIds = setOf("a", "b", "c")

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

    /** Every subset of this set, so a test can sweep all reachable selections of a small group. */
    private fun Set<String>.subsets(): List<Set<String>> {
        val ids = toList()
        return (0 until (1 shl ids.size)).map { mask ->
            ids.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
        }
    }
}
