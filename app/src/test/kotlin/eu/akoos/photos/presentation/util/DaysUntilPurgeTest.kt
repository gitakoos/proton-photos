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

package eu.akoos.photos.presentation.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [daysUntilPurge] to a ceiling count: the retention badge must never promise the reader more
 * time than the OS or the server will actually give, so any part of a day still reads as a whole
 * day, and a purge time already reached collapses to zero rather than a negative or a stray "0
 * days" that a caller would have to special-case away from the imminent-deletion label.
 */
class DaysUntilPurgeTest {

    private val dayMs = 86_400_000L

    @Test
    fun `the purge moment itself counts as zero`() {
        assertEquals(0, daysUntilPurge(purgeAtMs = 1_700_000_000_000L, nowMs = 1_700_000_000_000L))
    }

    @Test
    fun `a purge time in the past counts as zero`() {
        assertEquals(0, daysUntilPurge(purgeAtMs = 1_000L, nowMs = 5_000L))
    }

    @Test
    fun `exactly five whole days reads as five`() {
        assertEquals(5, daysUntilPurge(purgeAtMs = 5 * dayMs, nowMs = 0L))
    }

    @Test
    fun `one millisecond past five whole days rounds up to six`() {
        assertEquals(6, daysUntilPurge(purgeAtMs = 5 * dayMs + 1L, nowMs = 0L))
    }

    @Test
    fun `half a day of remainder rounds up to one`() {
        assertEquals(1, daysUntilPurge(purgeAtMs = dayMs / 2, nowMs = 0L))
    }
}
