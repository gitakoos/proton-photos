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

package eu.akoos.photos.data.face

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure rules that decide when a finished walk pass groups the account's faces into people, and
 * when that grouping goes on to a full regroup. Grouping follows the faces still waiting, not the ones a
 * single pass found, so a library whose scan never drains still gets its people. A settled walk mirrors
 * the drained branch (a full regroup for whatever the incremental path left unplaced), a full regroup
 * that recently timed out or threw is held back until its backoff passes, and full regroups are spaced by
 * what the last one cost.
 */
class FaceGroupingPolicyTest {

    @Test
    fun a_settled_pass_that_found_no_faces_still_groups_the_waiting_ones() {
        // The rule has no per-pass face count: stored faces waiting for a group are enough, and a walk
        // about to stop groups at once, however recently the last grouping ran.
        assertTrue(group(willContinue = false, ungrouped = 12, sinceLastGroup = 0L))
    }

    @Test
    fun nothing_ungrouped_never_groups() {
        assertFalse(group(willContinue = false, ungrouped = 0))
        assertFalse(group(willContinue = true, ungrouped = 0, sinceLastGroup = INTERVAL * 10))
    }

    @Test
    fun an_inactive_walk_never_groups() {
        // Paused, stopping for sign-out, or AI switched off.
        assertFalse(group(active = false, willContinue = false, ungrouped = 12))
        assertFalse(group(active = false, willContinue = true, ungrouped = 12, sinceLastGroup = INTERVAL * 10))
    }

    @Test
    fun a_continuing_walk_groups_only_once_the_interval_has_passed() {
        assertFalse(group(willContinue = true, ungrouped = 12, sinceLastGroup = 0L))
        assertFalse(group(willContinue = true, ungrouped = 12, sinceLastGroup = INTERVAL - 1))
        assertTrue(group(willContinue = true, ungrouped = 12, sinceLastGroup = INTERVAL))
        assertTrue(group(willContinue = true, ungrouped = 12, sinceLastGroup = INTERVAL * 10))
    }

    @Test
    fun an_incremental_pass_that_was_not_handled_falls_to_a_full_regroup() {
        // No usable cache or a large backlog: the full regroup runs whether the walk settles or goes on.
        assertTrue(regroup(handled = false, settled = true, leftover = 0))
        assertTrue(regroup(handled = false, settled = false, leftover = 0))
    }

    @Test
    fun a_settled_pass_with_faces_left_unplaced_runs_a_full_regroup() {
        assertTrue(regroup(handled = true, settled = true, leftover = 3))
    }

    @Test
    fun a_settled_pass_the_incremental_path_placed_fully_skips_the_full_regroup() {
        assertFalse(regroup(handled = true, settled = true, leftover = 0))
    }

    @Test
    fun a_continuing_pass_with_faces_left_unplaced_does_not_regroup_fully() {
        // The drain goes on and groups again later; the leftovers wait for the settle or the drained branch.
        assertFalse(regroup(handled = true, settled = false, leftover = 3))
    }

    @Test
    fun a_failed_regroup_within_the_backoff_blocks_the_full_regroup() {
        assertFalse(regroup(handled = false, settled = true, leftover = 3, sinceFailure = 0L))
        assertFalse(regroup(handled = true, settled = true, leftover = 3, sinceFailure = BACKOFF - 1))
        // A failure stamped after this pass read its clock reads as negative and still blocks.
        assertFalse(regroup(handled = false, settled = false, leftover = 0, sinceFailure = -1L))
    }

    @Test
    fun a_failed_regroup_is_retried_once_the_backoff_has_passed() {
        assertTrue(regroup(handled = false, settled = true, leftover = 3, sinceFailure = BACKOFF))
        assertTrue(regroup(handled = true, settled = true, leftover = 3, sinceFailure = BACKOFF * 2))
    }

    @Test
    fun no_recorded_failure_never_blocks() {
        assertTrue(regroup(handled = false, settled = false, leftover = 0, sinceFailure = null))
        assertTrue(regroup(handled = true, settled = true, leftover = 3, sinceFailure = null))
    }

    @Test
    fun a_regroup_that_has_not_cooled_down_is_held_back() {
        assertFalse(regroup(handled = false, settled = true, leftover = 3, cooledDown = false))
        assertFalse(regroup(handled = true, settled = true, leftover = 3, cooledDown = false))
    }

    @Test
    fun the_first_regroup_is_never_held_by_the_cooldown() {
        // A library that has never grouped (the stuck-scan case) regroups on its first settled pass.
        assertTrue(cooled(sinceLast = null, lastDuration = 0L))
        assertTrue(cooled(sinceLast = null, lastDuration = 120_000L))
    }

    @Test
    fun a_quick_regroup_waits_the_plain_interval() {
        // A 2 s regroup: nine times that is under the interval, so the interval decides.
        assertFalse(cooled(sinceLast = INTERVAL - 1, lastDuration = 2_000L))
        assertTrue(cooled(sinceLast = INTERVAL, lastDuration = 2_000L))
    }

    @Test
    fun a_slow_regroup_waits_in_proportion_to_its_cost() {
        // A 40 s regroup on a large library waits nine times as long (6 min), so full regroups stay near a
        // tenth of the scan time.
        assertFalse(cooled(sinceLast = INTERVAL, lastDuration = 40_000L))
        assertFalse(cooled(sinceLast = 359_999L, lastDuration = 40_000L))
        assertTrue(cooled(sinceLast = 360_000L, lastDuration = 40_000L))
    }

    private fun group(
        active: Boolean = true,
        willContinue: Boolean,
        ungrouped: Int,
        sinceLastGroup: Long = 0L,
    ): Boolean = shouldGroupAtPassEnd(
        active = active,
        willContinue = willContinue,
        ungrouped = ungrouped,
        msSinceLastGroup = sinceLastGroup,
        minIntervalMs = INTERVAL,
    )

    private fun regroup(
        handled: Boolean,
        settled: Boolean,
        leftover: Int,
        sinceFailure: Long? = null,
        cooledDown: Boolean = true,
    ): Boolean = shouldRunFullRegroup(
        incrementalHandled = handled,
        settled = settled,
        ungroupedAfterIncremental = leftover,
        msSinceLastFailedRegroup = sinceFailure,
        failureBackoffMs = BACKOFF,
        cooledDown = cooledDown,
    )

    private fun cooled(sinceLast: Long?, lastDuration: Long): Boolean = fullRegroupCooledDown(
        msSinceLastRegroup = sinceLast,
        lastRegroupDurationMs = lastDuration,
        minIntervalMs = INTERVAL,
        dutyFactor = DUTY,
    )

    private companion object {
        const val INTERVAL = 60_000L
        const val BACKOFF = 1_800_000L
        const val DUTY = 9L
    }
}
