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

package eu.akoos.photos.data.autosave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pins the automatic save's timing rule: nothing runs without a change, a save after changes waits out its
 * quiet spell and its hourly gap, the daily and weekly saves wait for 23:59 local time (Sunday for the weekly)
 * and catch up a slot that was missed, and a failed save waits before the next try.
 */
class AutoSavePlanTest {

    private val zone: ZoneId = ZoneId.of("Europe/Budapest")

    /** Wall-clock milliseconds of a local date and time. Oct 11 2026 is a Sunday. */
    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0, second: Int = 0): Long =
        LocalDateTime.of(2026, month, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    private fun clock(
        dirtySince: Long = 0L,
        lastChange: Long = 0L,
        lastSaveStart: Long = 0L,
        lastFailure: Long = 0L,
    ) = AutoSaveClock(dirtySince, lastChange, lastSaveStart, lastFailure)

    private fun decide(frequency: AutoSaveFrequency, clock: AutoSaveClock, now: Long) =
        decideAutoSave(frequency, clock, now, zone)

    private fun minutes(n: Long) = n * 60_000L

    @Test
    fun nothing_runs_while_nothing_changed() {
        for (frequency in AutoSaveFrequency.entries) {
            assertEquals(AutoSaveDecision.Idle, decide(frequency, clock(), at(10, 11, 23, 59, 30)))
        }
    }

    @Test
    fun a_save_after_changes_waits_out_the_quiet_spell() {
        val change = at(10, 11, 10, 0)
        val c = clock(dirtySince = change, lastChange = change)
        assertEquals(AutoSaveDecision.Wait(minutes(5)), decide(AutoSaveFrequency.AFTER_CHANGES, c, at(10, 11, 10, 5)))
        assertEquals(AutoSaveDecision.Run, decide(AutoSaveFrequency.AFTER_CHANGES, c, at(10, 11, 10, 10)))
    }

    @Test
    fun a_newer_change_pushes_the_save_back() {
        val first = at(10, 11, 10, 0)
        val latest = at(10, 11, 10, 8)
        val c = clock(dirtySince = first, lastChange = latest)
        assertEquals(AutoSaveDecision.Wait(minutes(8)), decide(AutoSaveFrequency.AFTER_CHANGES, c, at(10, 11, 10, 10)))
    }

    @Test
    fun a_save_after_changes_keeps_an_hour_between_two_saves() {
        val change = at(10, 11, 10, 0)
        val c = clock(dirtySince = change, lastChange = change, lastSaveStart = at(10, 11, 9, 50))
        assertEquals(AutoSaveDecision.Wait(minutes(40)), decide(AutoSaveFrequency.AFTER_CHANGES, c, at(10, 11, 10, 10)))
        assertEquals(AutoSaveDecision.Run, decide(AutoSaveFrequency.AFTER_CHANGES, c, at(10, 11, 10, 50)))
    }

    @Test
    fun a_daily_save_waits_for_2359_and_then_runs() {
        val c = clock(dirtySince = at(10, 11, 10, 0), lastChange = at(10, 11, 10, 0))
        assertEquals(AutoSaveDecision.Wait(minutes(8 * 60 + 59)), decide(AutoSaveFrequency.DAILY, c, at(10, 11, 15, 0)))
        assertEquals(AutoSaveDecision.Run, decide(AutoSaveFrequency.DAILY, c, at(10, 11, 23, 59, 10)))
    }

    @Test
    fun a_slot_missed_with_the_phone_off_is_saved_at_once() {
        val c = clock(dirtySince = at(10, 10, 20, 0), lastChange = at(10, 10, 20, 0))
        assertEquals(AutoSaveDecision.Run, decide(AutoSaveFrequency.DAILY, c, at(10, 11, 9, 0)))
    }

    @Test
    fun a_change_after_the_latest_slot_waits_for_the_next() {
        val c = clock(dirtySince = at(10, 11, 0, 30), lastChange = at(10, 11, 0, 30))
        assertEquals(AutoSaveDecision.Wait(minutes(14 * 60 + 59)), decide(AutoSaveFrequency.DAILY, c, at(10, 11, 9, 0)))
    }

    @Test
    fun a_weekly_save_waits_for_sunday_night() {
        val c = clock(dirtySince = at(10, 6, 12, 0), lastChange = at(10, 6, 12, 0))
        val wednesdayNoon = at(10, 7, 12, 0)
        assertEquals(AutoSaveDecision.Wait(at(10, 11, 23, 59) - wednesdayNoon), decide(AutoSaveFrequency.WEEKLY, c, wednesdayNoon))
        assertEquals(AutoSaveDecision.Run, decide(AutoSaveFrequency.WEEKLY, c, at(10, 11, 23, 59, 30)))
    }

    @Test
    fun a_weekly_save_does_not_run_on_a_weekday_slot() {
        val c = clock(dirtySince = at(10, 7, 8, 0), lastChange = at(10, 7, 8, 0))
        val decision = decide(AutoSaveFrequency.WEEKLY, c, at(10, 8, 23, 59, 30))
        assertTrue(decision is AutoSaveDecision.Wait)
    }

    @Test
    fun a_failed_save_waits_half_an_hour_for_every_kind() {
        val failure = at(10, 11, 10, 30)
        val c = clock(dirtySince = at(10, 11, 9, 0), lastChange = at(10, 11, 9, 0), lastFailure = failure)
        assertEquals(AutoSaveDecision.Wait(minutes(29)), decide(AutoSaveFrequency.AFTER_CHANGES, c, at(10, 11, 10, 31)))
        assertEquals(AutoSaveDecision.Run, decide(AutoSaveFrequency.AFTER_CHANGES, c, at(10, 11, 11, 0)))
        val missedDaily = clock(dirtySince = at(10, 10, 20, 0), lastChange = at(10, 10, 20, 0), lastFailure = failure)
        assertEquals(AutoSaveDecision.Wait(minutes(29)), decide(AutoSaveFrequency.DAILY, missedDaily, at(10, 11, 10, 31)))
    }

    @Test
    fun a_failure_older_than_the_last_save_is_ignored() {
        val change = at(10, 11, 10, 0)
        val c = clock(dirtySince = change, lastChange = change, lastSaveStart = at(10, 11, 9, 0), lastFailure = at(10, 11, 8, 0))
        // The hourly gap from the 09:00 save is over by 10:05, so only the quiet spell holds the save back.
        assertEquals(AutoSaveDecision.Wait(minutes(5)), decide(AutoSaveFrequency.AFTER_CHANGES, c, at(10, 11, 10, 5)))
    }

    @Test
    fun the_slot_is_2359_local_time_across_the_end_of_summer_time() {
        // Summer time ends in Hungary on Oct 25 2026.
        val noon = at(10, 25, 12, 0)
        assertEquals(at(10, 25, 23, 59), nextSlotAfter(AutoSaveFrequency.DAILY, noon, zone))
        assertEquals(at(10, 24, 23, 59), lastSlotAtOrBefore(AutoSaveFrequency.DAILY, noon, zone))
    }

    @Test
    fun the_next_slot_is_strictly_after_now_and_the_last_is_at_or_before() {
        val slot = at(10, 11, 23, 59)
        assertEquals(at(10, 12, 23, 59), nextSlotAfter(AutoSaveFrequency.DAILY, slot, zone))
        assertEquals(slot, lastSlotAtOrBefore(AutoSaveFrequency.DAILY, slot, zone))
    }

    @Test
    fun a_change_during_a_save_stays_waiting_but_one_before_it_is_covered() {
        assertEquals(0L, dirtySinceAfterSave(lastChangeMs = 1_000L, saveStartMs = 2_000L))
        assertEquals(0L, dirtySinceAfterSave(lastChangeMs = 2_000L, saveStartMs = 2_000L))
        assertEquals(2_001L, dirtySinceAfterSave(lastChangeMs = 3_000L, saveStartMs = 2_000L))
    }
}
