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

import eu.akoos.photos.data.preferences.InMemoryPreferencesDataStore
import eu.akoos.photos.data.preferences.SettingsKeys
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The people's photo counts an older build left wrong are recounted from the faces once per account, not at
 * every pass end: the recount reads every face, and the batches keep the counts right afterwards. A recount
 * that failed, or whose write the fence turned away, is not recorded, so it runs again.
 */
class FaceCountsRecountOnceTest {

    private val store = InMemoryPreferencesDataStore()
    private var recounts = 0

    private suspend fun run(account: String, allowed: Boolean = true, result: Int? = 3): Boolean =
        recountFaceCountsOnce(store, account, { allowed }) {
            recounts++
            result
        }

    @Test
    fun `the first run recounts and records the account`() = runTest {
        assertTrue(run("acct"))

        assertEquals(1, recounts)
        assertEquals(setOf("acct"), store.current[SettingsKeys.FACE_COUNTS_RECOUNTED])
    }

    @Test
    fun `later runs for the same account do not recount again`() = runTest {
        run("acct")
        assertTrue(run("acct"))
        assertTrue(run("acct"))

        assertEquals(1, recounts)
    }

    @Test
    fun `each account is recounted once on its own`() = runTest {
        run("acct")
        run("other")
        run("other")

        assertEquals(2, recounts)
        assertEquals(setOf("acct", "other"), store.current[SettingsKeys.FACE_COUNTS_RECOUNTED])
    }

    @Test
    fun `a failed recount is reported and runs again next time`() = runTest {
        assertFalse(run("acct", result = null))
        assertNull(store.current[SettingsKeys.FACE_COUNTS_RECOUNTED])

        assertTrue(run("acct"))
        assertEquals(2, recounts)
    }

    @Test
    fun `a recount the fence turned away is not recorded`() = runTest {
        assertTrue(run("acct", allowed = false))
        assertNull(store.current[SettingsKeys.FACE_COUNTS_RECOUNTED])

        run("acct")
        assertEquals(2, recounts)
    }
}
