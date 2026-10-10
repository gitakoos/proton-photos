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

package eu.akoos.photos.data.semantic

import eu.akoos.photos.util.SourceMiss
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the semantic walk's copy of the settle rule: which photos with no source get a zero-vector
 * tombstone (so a later pass skips them) and which stay pending for a retry.
 */
class SemanticTombstonePolicyTest {

    @Test
    fun a_device_file_that_opens_but_will_not_decode_is_tombstoned() {
        // The load names it PERMANENT once the file opened and the decoder made no image of it.
        assertTrue(shouldTombstoneOnNullSource(loadTimedOut = false, miss = SourceMiss.PERMANENT))
    }

    @Test
    fun a_device_file_that_could_not_be_opened_stays_pending() {
        // An unmounted card or a provider that answered nothing is a transient miss, retried after a backoff.
        assertFalse(shouldTombstoneOnNullSource(loadTimedOut = false, miss = SourceMiss.TRANSIENT))
    }

    @Test
    fun a_load_that_wedged_is_tombstoned() {
        assertTrue(shouldTombstoneOnNullSource(loadTimedOut = true, miss = SourceMiss.TRANSIENT))
    }

    @Test
    fun a_deferred_load_stays_pending() {
        assertFalse(shouldTombstoneOnNullSource(loadTimedOut = false, miss = SourceMiss.OVER_BUDGET))
        assertFalse(shouldTombstoneOnNullSource(loadTimedOut = false, miss = SourceMiss.DEFERRED))
    }
}
