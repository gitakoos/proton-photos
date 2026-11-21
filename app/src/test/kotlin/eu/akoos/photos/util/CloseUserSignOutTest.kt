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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The closing steps of a sign-out from Settings: a step that throws does not stop the rest, and a failure
 * to let the account go is answered so the app can stay signed in and say so, never left half way.
 */
class CloseUserSignOutTest {

    @Test
    fun `every step runs and the account is let go`() = runBlocking {
        val ran = mutableListOf<String>()

        val left = SignOutCleanup.closeUserSignOut(
            steps = listOf({ ran += "mark" }, { ran += "lock" }),
            letGo = { ran += "disable" },
        )

        assertTrue(left)
        assertEquals(listOf("mark", "lock", "disable"), ran)
    }

    @Test
    fun `a step that throws does not stop the ones after it`() = runBlocking {
        val ran = mutableListOf<String>()

        val left = SignOutCleanup.closeUserSignOut(
            steps = listOf({ error("mark failed") }, { ran += "lock" }),
            letGo = { ran += "disable" },
        )

        assertTrue(left)
        assertEquals(listOf("lock", "disable"), ran)
    }

    @Test
    fun `an account that could not be let go is answered as still signed in`() = runBlocking {
        val left = SignOutCleanup.closeUserSignOut(steps = emptyList(), letGo = { error("disable failed") })

        assertFalse(left)
    }

    @Test(expected = CancellationException::class)
    fun `a cancellation still stops the sign-out`() {
        runBlocking {
            SignOutCleanup.closeUserSignOut(steps = listOf({ throw CancellationException("stopped") }), letGo = {})
        }
    }
}
