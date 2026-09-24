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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure rules that decide when a finished walk pass groups the account's faces into people, and
 * when that grouping goes on to a full regroup. Grouping follows the faces still waiting, not the ones a
 * single pass found, so a library whose scan never drains still gets its people, but never while names
 * wait to be reattached, and a forced regroup skips only the walk's own gate, never that one. A settled
 * walk mirrors the drained branch (a full regroup for whatever the incremental path left unplaced), both
 * regroup only for faces beyond what the last regroup left ungrouped on purpose, a full regroup that
 * recently timed out or threw is held back until its backoff passes, and full regroups are spaced by what
 * the last one cost.
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

    @Test
    fun names_waiting_to_be_reattached_hold_every_pass_end_grouping() {
        // The faces carry no names until the drained walk reattaches them, so grouping them earlier would
        // rebuild every named person unnamed, whether the walk settles or goes on.
        assertFalse(group(willContinue = false, ungrouped = 12, reattachPending = true))
        assertFalse(group(willContinue = true, ungrouped = 12, sinceLastGroup = INTERVAL * 10, reattachPending = true))
        assertTrue(group(willContinue = false, ungrouped = 12, reattachPending = false))
    }

    @Test
    fun names_waiting_to_be_reattached_hold_every_regroup_forced_or_not() {
        // A forced regroup (a user naming or merging, an import handing its faces back, the viewer's scan)
        // skips the walk's own gate but not this one: the faces do not carry the names yet, and what the
        // user confirms meanwhile is stored for the regroup the drained walk runs once they are back.
        assertFalse(regroupAllowed(force = true, active = true, reattachPending = true))
        assertFalse(regroupAllowed(force = true, active = false, reattachPending = true))
        assertFalse(regroupAllowed(force = false, active = true, reattachPending = true))
        assertFalse(regroupAllowed(force = false, active = false, reattachPending = true))
    }

    @Test
    fun a_forced_regroup_runs_while_the_walk_is_paused_and_any_other_needs_a_permitted_walk() {
        assertTrue(regroupAllowed(force = true, active = false, reattachPending = false))
        assertTrue(regroupAllowed(force = true, active = true, reattachPending = false))
        assertTrue(regroupAllowed(force = false, active = true, reattachPending = false))
        assertFalse(regroupAllowed(force = false, active = false, reattachPending = false))
    }

    @Test
    fun a_full_regroup_waits_for_faces_beyond_what_the_last_one_left_ungrouped() {
        // Three faces kept out of a named person stay ungrouped by design: they alone never call for
        // another full regroup, a fourth waiting face does.
        assertFalse(regroup(handled = true, settled = true, leftover = 3, baseline = 3))
        assertFalse(regroup(handled = true, settled = true, leftover = 2, baseline = 3))
        assertTrue(regroup(handled = true, settled = true, leftover = 4, baseline = 3))
        // The same holds when the incremental path could not run: with nothing new waiting, the whole
        // library is not reloaded.
        assertFalse(regroup(handled = false, settled = false, leftover = 3, baseline = 3))
        assertTrue(regroup(handled = false, settled = false, leftover = 4, baseline = 3))
    }

    @Test
    fun a_full_regroup_beyond_the_baseline_still_obeys_the_backoff_and_the_cooldown() {
        assertFalse(regroup(handled = true, settled = true, leftover = 9, baseline = 3, sinceFailure = BACKOFF - 1))
        assertFalse(regroup(handled = true, settled = true, leftover = 9, baseline = 3, cooledDown = false))
    }

    @Test
    fun no_baseline_holds_nothing_back() {
        // Before any regroup has completed (a fresh process, or faces just replaced by an import).
        assertTrue(exceedsRegroupBaseline(ungrouped = 1, baseline = null))
        assertTrue(exceedsRegroupBaseline(ungrouped = 0, baseline = null))
        assertTrue(regroup(handled = true, settled = true, leftover = 1, baseline = null))
    }

    @Test
    fun a_drained_walk_regroups_only_for_new_unplaced_faces() {
        assertFalse(drained(leftover = 0, baseline = null))
        assertTrue(drained(leftover = 3, baseline = null))
        // The leftovers the last regroup kept out on purpose: every gallery visit would otherwise reload
        // every stored embedding.
        assertFalse(drained(leftover = 3, baseline = 3))
        assertTrue(drained(leftover = 4, baseline = 3))
        assertFalse(drained(leftover = 0, baseline = 0))
    }

    @Test
    fun a_drained_regroup_obeys_the_backoff_and_the_cooldown() {
        assertFalse(drained(leftover = 4, baseline = 3, sinceFailure = 0L))
        assertFalse(drained(leftover = 4, baseline = 3, sinceFailure = BACKOFF - 1))
        assertTrue(drained(leftover = 4, baseline = 3, sinceFailure = BACKOFF))
        assertFalse(drained(leftover = 4, baseline = 3, cooledDown = false))
        assertFalse(drained(leftover = 4, baseline = null, cooledDown = false))
    }

    @Test
    fun the_baseline_only_ever_comes_down_between_regroups() {
        // Faces the last regroup left ungrouped have gone (deleted, placed, removed): the next new face
        // must still read as new.
        assertEquals(2, lowerRegroupBaseline(baseline = 5, ungrouped = 2))
        assertEquals(5, lowerRegroupBaseline(baseline = 5, ungrouped = 7))
        assertEquals(5, lowerRegroupBaseline(baseline = 5, ungrouped = 5))
        assertNull(lowerRegroupBaseline(baseline = null, ungrouped = 2))
    }

    private fun group(
        active: Boolean = true,
        willContinue: Boolean,
        ungrouped: Int,
        sinceLastGroup: Long = 0L,
        reattachPending: Boolean = false,
    ): Boolean = shouldGroupAtPassEnd(
        active = active,
        willContinue = willContinue,
        reattachPending = reattachPending,
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
        baseline: Int? = null,
    ): Boolean = shouldRunFullRegroup(
        incrementalHandled = handled,
        settled = settled,
        ungroupedAfterIncremental = leftover,
        ungroupedBaseline = baseline,
        msSinceLastFailedRegroup = sinceFailure,
        failureBackoffMs = BACKOFF,
        cooledDown = cooledDown,
    )

    private fun drained(
        leftover: Int,
        baseline: Int?,
        sinceFailure: Long? = null,
        cooledDown: Boolean = true,
    ): Boolean = shouldRunDrainedRegroup(
        ungroupedAfterIncremental = leftover,
        ungroupedBaseline = baseline,
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
