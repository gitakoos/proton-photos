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

import eu.akoos.photos.util.HealthVerdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwaitMlGoAheadTest {

    private fun verdict(allowed: Boolean, reason: String = "ok") = HealthVerdict(allowed, allowed, reason)

    @Test
    fun `goes on at once when the device allows it`() = runTest {
        var waits = 0
        assertTrue(awaitMlGoAhead({ verdict(true) }, { waits++ }, {}) { true })
        assertEquals(0, waits)
    }

    @Test
    fun `off the charger it parks until the charger is back`() = runTest {
        val answers = ArrayDeque(listOf(verdict(false, "not charging"), verdict(false, "not charging"), verdict(true)))
        val parks = mutableListOf<String>()
        var waits = 0
        assertTrue(awaitMlGoAhead({ answers.removeFirst() }, { waits++ }, { parks += it }) { true })
        assertEquals(2, waits)
        assertEquals(listOf("not charging", "not charging"), parks)
    }

    @Test
    fun `a pass that ends while parked does not go on`() = runTest {
        var live = true
        assertFalse(awaitMlGoAhead({ verdict(false, "hot") }, { live = false }, {}) { live })
    }
}
