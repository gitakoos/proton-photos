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

package eu.akoos.photos.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-value coverage for the free-up-interval lookup that maps the persisted
 * [SettingsKeys.FREE_UP_INTERVAL] name back to an interval. The value is free-form string storage,
 * so the lookup sits on the settings load path and must total: any name it does not recognise
 * resolves to [FreeUpInterval.OneMonth] rather than raising. No Android, no Robolectric.
 */
class FreeUpIntervalTest {

    @Test
    fun fromKey_maps_each_entry_name_back_to_its_interval() {
        for (interval in FreeUpInterval.entries) {
            assertEquals(interval, FreeUpInterval.fromKey(interval.name))
        }
    }

    @Test
    fun fromKey_falls_back_to_one_month_when_absent() {
        // null is the "key never written" case a fresh install hits.
        assertEquals(FreeUpInterval.OneMonth, FreeUpInterval.fromKey(null))
    }

    @Test
    fun fromKey_falls_back_to_one_month_on_an_unknown_name() {
        assertEquals(FreeUpInterval.OneMonth, FreeUpInterval.fromKey(""))
        assertEquals(FreeUpInterval.OneMonth, FreeUpInterval.fromKey("SomethingElse"))
        // Matching is exact: a case variant of a real entry is not that entry.
        assertEquals(FreeUpInterval.OneMonth, FreeUpInterval.fromKey("oneday"))
    }

    @Test
    fun fromKey_resolves_a_stored_after_backup_name_instead_of_raising() {
        // An install carrying the retired "AfterBackup" name reads back as the default.
        assertEquals(FreeUpInterval.OneMonth, FreeUpInterval.fromKey("AfterBackup"))
    }

    @Test
    fun intervals_get_progressively_longer_and_none_is_zero() {
        assertEquals(600_000L, FreeUpInterval.TenMinutes.ms)
        assertEquals(86_400_000L, FreeUpInterval.OneDay.ms)
        assertEquals(604_800_000L, FreeUpInterval.OneWeek.ms)
        assertEquals(2_592_000_000L, FreeUpInterval.OneMonth.ms)
        // Declaration order is picker order, and no entry states an age of zero: a zero would put
        // the sweep cutoff at "now", which FreeUpSpaceWorker.isUsableInterval exists to refuse.
        val ages = FreeUpInterval.entries.map { it.ms }
        assertEquals(ages.sorted(), ages)
        assertTrue("every interval must state a positive age", ages.all { it > 0L })
    }
}
