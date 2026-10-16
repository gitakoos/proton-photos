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

package eu.akoos.photos.util

import eu.akoos.photos.util.FaceDiagnostics.RegroupTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the copied Faces diagnostics text so it stays stable and privacy-safe: the locale-independent
 * byte-to-MB formatting (rounding to one tenth of a MiB, the "?" sentinel for a missing size, and no
 * overflow on a gigabyte-scale total), and the lines that tell detection apart from grouping: the library
 * totals with how long ago they were read, and the full-regroup tallies with the latest trigger, failure
 * class and completion, in their blank state before anything is recorded and after a clear.
 * [FaceDiagnostics] is a process singleton, so each test brackets itself with a clear.
 */
class FaceDiagnosticsFormatTest {

    @Before
    fun reset() = FaceDiagnostics.clear()

    @After
    fun tearDown() = FaceDiagnostics.clear()

    @Test
    fun a_missing_or_nonpositive_size_reads_as_a_question_mark() {
        assertEquals("?", FaceDiagnostics.formatMb(0L))
        assertEquals("?", FaceDiagnostics.formatMb(-1L))
    }

    @Test
    fun whole_and_half_mebibytes_format_to_one_decimal() {
        assertEquals("0.5MB", FaceDiagnostics.formatMb(512L * 1024L))
        assertEquals("1.0MB", FaceDiagnostics.formatMb(1024L * 1024L))
        assertEquals("1.5MB", FaceDiagnostics.formatMb(1024L * 1024L + 512L * 1024L))
        assertEquals("2.0MB", FaceDiagnostics.formatMb(2L * 1024L * 1024L))
    }

    @Test
    fun a_sub_tenth_size_rounds_to_zero_but_still_prints() {
        // ~0.01 MiB rounds to 0.0, so a tiny item still shows a size rather than dropping to "?".
        assertEquals("0.0MB", FaceDiagnostics.formatMb(10_000L))
    }

    @Test
    fun a_gigabyte_total_does_not_overflow() {
        // The session download total can reach the GB range; the *10 term must stay within Long.
        assertEquals("1024.0MB", FaceDiagnostics.formatMb(1024L * 1024L * 1024L))
    }

    @Test
    fun nothing_recorded_reads_as_unread_totals_and_no_regroups_right_after_the_detections() {
        val snapshot = FaceDiagnostics.snapshot(now = 60_000L)
        assertTrue(snapshot, snapshot.contains(BLANK_BLOCK))
    }

    @Test
    fun library_totals_print_with_how_long_ago_they_were_read() {
        FaceDiagnostics.recordLibraryCounts(faces = 1234, ungrouped = 34, people = 40, named = 5, atMs = 48_000L)
        val snapshot = FaceDiagnostics.snapshot(now = 60_000L)
        assertTrue(snapshot, snapshot.contains("storedFaces=1234 ungrouped=34 people=40 named=5 counted=12s ago\n"))
    }

    @Test
    fun a_total_whose_read_failed_prints_as_unknown() {
        FaceDiagnostics.recordLibraryCounts(faces = 1234, ungrouped = -1, people = 0, named = 0, atMs = 60_000L)
        val snapshot = FaceDiagnostics.snapshot(now = 60_000L)
        assertTrue(snapshot, snapshot.contains("storedFaces=1234 ungrouped=? people=0 named=0 counted=0s ago\n"))
    }

    @Test
    fun faces_stored_but_never_grouped_reads_apart_from_the_session_detections() {
        // Faces are stored yet no person exists, which reads apart from a library with no faces at all.
        // The session's detector count keeps its own line and is not folded into the stored total.
        FaceDiagnostics.recordFacesDetected(7)
        FaceDiagnostics.recordLibraryCounts(faces = 1234, ungrouped = 1234, people = 0, named = 0, atMs = 0L)
        val snapshot = FaceDiagnostics.snapshot(now = 0L)
        assertTrue(snapshot, snapshot.contains("facesDetected=7\n"))
        assertTrue(snapshot, snapshot.contains("storedFaces=1234 ungrouped=1234 people=0 named=0 counted=0s ago\n"))
    }

    @Test
    fun regroup_outcomes_tally_with_the_latest_trigger_and_completion() {
        FaceDiagnostics.recordRegroupStarted(RegroupTrigger.DRAINED)
        FaceDiagnostics.recordRegroupCompleted(durationMs = 4_210L, endMs = 10_000L)
        FaceDiagnostics.recordRegroupStarted(RegroupTrigger.PASS_CONTINUING)
        FaceDiagnostics.recordRegroupTimedOut()
        FaceDiagnostics.recordRegroupStarted(RegroupTrigger.PASS_SETTLED)
        FaceDiagnostics.recordRegroupFailed(IllegalStateException())
        // Started and not yet returned, so it moves the trigger and the started count only.
        FaceDiagnostics.recordRegroupStarted(RegroupTrigger.REQUEST)
        val snapshot = FaceDiagnostics.snapshot(now = 60_000L)
        assertTrue(snapshot, snapshot.contains("regroupsStarted=4 completed=1 timedOut=1 failed=1 (IllegalStateException)\n"))
        assertTrue(snapshot, snapshot.contains("lastRegroupTrigger=request lastRegroupCompleted=50s ago (4210ms)\n"))
    }

    @Test
    fun a_failure_shows_only_its_class_name_never_its_message() {
        FaceDiagnostics.recordRegroupStarted(RegroupTrigger.PASS_SETTLED)
        FaceDiagnostics.recordRegroupFailed(IllegalStateException("IMG_2041.jpg of Anna in /DCIM/Camera"))
        val snapshot = FaceDiagnostics.snapshot(now = 0L)
        assertTrue(snapshot, snapshot.contains(" failed=1 (IllegalStateException)\n"))
        assertFalse(snapshot, snapshot.contains("IMG_2041"))
        assertFalse(snapshot, snapshot.contains("Anna"))
        assertFalse(snapshot, snapshot.contains("DCIM"))
    }

    @Test
    fun an_anonymous_exception_still_prints_a_class_name_and_no_message() {
        // An anonymous class has an empty simple name; the failure still names the class it was declared in.
        FaceDiagnostics.recordRegroupFailed(object : RuntimeException("IMG_2041.jpg") {})
        val snapshot = FaceDiagnostics.snapshot(now = 0L)
        assertTrue(snapshot, snapshot.contains(" failed=1 ("))
        assertFalse(snapshot, snapshot.contains(" failed=1 ()"))
        assertFalse(snapshot, snapshot.contains("IMG_2041"))
    }

    @Test
    fun every_trigger_prints_its_fixed_label() {
        assertEquals(
            listOf("drained", "pass-settled", "pass-continuing", "on-demand", "request"),
            RegroupTrigger.entries.map { it.label },
        )
        for (trigger in RegroupTrigger.entries) {
            FaceDiagnostics.recordRegroupStarted(trigger)
            val snapshot = FaceDiagnostics.snapshot(now = 0L)
            assertTrue(snapshot, snapshot.contains("lastRegroupTrigger=${trigger.label} "))
        }
    }

    @Test
    fun source_deferrals_read_zero_before_anything_is_recorded() {
        val snapshot = FaceDiagnostics.snapshot(now = 0L)
        assertTrue(snapshot, snapshot.contains("watchdogSkips=0\npermanentSkipped=0 transientWaiting=0 overBudget=0\nfacesDetected=0\n"))
    }

    @Test
    fun source_deferrals_print_the_session_skips_and_the_last_pass_backlog() {
        FaceDiagnostics.recordPermanentSkip()
        FaceDiagnostics.recordPermanentSkip()
        FaceDiagnostics.recordSourceBacklog(transientWaiting = 7, overBudget = 120)
        // A later pass end replaces the backlog rather than adding to it.
        FaceDiagnostics.recordSourceBacklog(transientWaiting = 5, overBudget = 90)
        val snapshot = FaceDiagnostics.snapshot(now = 0L)
        assertTrue(snapshot, snapshot.contains("permanentSkipped=2 transientWaiting=5 overBudget=90\n"))
    }

    @Test
    fun clear_resets_the_source_deferrals() {
        FaceDiagnostics.recordPermanentSkip()
        FaceDiagnostics.recordSourceBacklog(transientWaiting = 7, overBudget = 120)
        FaceDiagnostics.clear()
        val snapshot = FaceDiagnostics.snapshot(now = 0L)
        assertTrue(snapshot, snapshot.contains("permanentSkipped=0 transientWaiting=0 overBudget=0\n"))
    }

    @Test
    fun clear_resets_the_totals_and_the_regroups() {
        FaceDiagnostics.recordLibraryCounts(faces = 1234, ungrouped = 34, people = 40, named = 5, atMs = 0L)
        FaceDiagnostics.recordRegroupStarted(RegroupTrigger.DRAINED)
        FaceDiagnostics.recordRegroupCompleted(durationMs = 4_210L, endMs = 0L)
        FaceDiagnostics.recordRegroupTimedOut()
        FaceDiagnostics.recordRegroupFailed(IllegalStateException())
        FaceDiagnostics.clear()
        val snapshot = FaceDiagnostics.snapshot(now = 60_000L)
        assertTrue(snapshot, snapshot.contains(BLANK_BLOCK))
        assertFalse(snapshot, snapshot.contains("IllegalStateException"))
    }

    @Test
    fun the_people_list_builds_show_only_once_one_has_started_and_go_with_a_clear() {
        assertFalse(FaceDiagnostics.snapshot(now = 0L).contains("peopleBuilds="))
        FaceDiagnostics.recordPeopleBuildStarted()
        FaceDiagnostics.recordPeopleBuildStarted()
        FaceDiagnostics.recordPeopleBuildCompleted(durationMs = 900L, people = 5_700)
        FaceDiagnostics.recordPeopleBuildStarted()
        FaceDiagnostics.recordPeopleBuildCompleted(
            durationMs = 300L, people = 5_701, fallbacks = 3_200, storedReadMs = 40L, fallbackMs = 200L,
        )
        val snapshot = FaceDiagnostics.snapshot(now = 0L)
        assertTrue(
            snapshot,
            snapshot.contains(
                "peopleBuilds=3 done=2 lastMs=300 maxMs=900 lastPeople=5701 " +
                    "fallbacks=3200 storedMs=40 fallbackMs=200 otherMs=60\n",
            ),
        )
        FaceDiagnostics.clear()
        assertFalse(FaceDiagnostics.snapshot(now = 0L).contains("peopleBuilds="))
    }

    private companion object {
        /** The detections line followed by the three grouping lines, as a fresh or cleared session prints them. */
        const val BLANK_BLOCK =
            "facesDetected=0\n" +
                "storedFaces=? ungrouped=? people=? named=? counted=never\n" +
                "regroupsStarted=0 completed=0 timedOut=0 failed=0\n" +
                "lastRegroupTrigger=none lastRegroupCompleted=never\n"
    }
}
