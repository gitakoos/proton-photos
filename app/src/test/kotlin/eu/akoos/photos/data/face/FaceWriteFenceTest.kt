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
 * Pins the fence that keeps the face indexer's writes off an import: work that took its token before an
 * import started, or while one ran, never writes again, work that starts after it does, and a sign-out or
 * rescan turns away everything already under way.
 */
class FaceWriteFenceTest {

    @Test
    fun a_token_allows_writes_while_nothing_moves() {
        val fence = FaceWriteFence()
        val token = fence.token()
        assertTrue(fence.allows(token))
        assertTrue(fence.allows(token))
    }

    @Test
    fun an_import_turns_away_work_that_began_before_it_while_it_runs_and_after() {
        val fence = FaceWriteFence()
        val pass = fence.token()
        fence.enterImport()
        assertFalse("a scan in flight must not write over the import", fence.allows(pass))
        fence.exitImport()
        assertFalse("nor once it has committed", fence.allows(pass))
    }

    @Test
    fun work_that_began_during_an_import_never_writes() {
        val fence = FaceWriteFence()
        fence.enterImport()
        val during = fence.token()
        assertFalse(fence.allows(during))
        fence.exitImport()
        assertFalse(fence.allows(during))
    }

    @Test
    fun work_that_begins_after_an_import_writes() {
        val fence = FaceWriteFence()
        fence.enterImport()
        fence.exitImport()
        assertTrue(fence.allows(fence.token()))
    }

    @Test
    fun overlapping_imports_hold_every_write_until_the_last_one_ends() {
        val fence = FaceWriteFence()
        fence.enterImport()
        fence.enterImport()
        fence.exitImport()
        assertFalse("one import still runs", fence.allows(fence.token()))
        fence.exitImport()
        assertTrue(fence.allows(fence.token()))
    }

    @Test
    fun invalidating_turns_away_every_earlier_token_but_not_later_ones() {
        val fence = FaceWriteFence()
        val before = fence.token()
        fence.invalidate()
        assertFalse(fence.allows(before))
        assertTrue(fence.allows(fence.token()))
    }
}
