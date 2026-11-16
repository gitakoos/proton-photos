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

package eu.akoos.photos.data.hidden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One owner per photo on its way into the vault, whichever screen asks and however many ask at once. */
class HideSourceClaimsTest {

    @Test
    fun `a photo another run holds is not handed to a second run`() {
        val claims = HideSourceClaims()
        val first = Any()
        val second = Any()

        assertEquals(setOf("a", "b"), claims.claim(first, listOf("a", "b")))
        assertEquals(setOf("c"), claims.claim(second, listOf("b", "c")))
    }

    @Test
    fun `a run claiming again keeps what it already holds`() {
        val claims = HideSourceClaims()
        val run = Any()
        claims.claim(run, listOf("a"))

        assertEquals(setOf("a", "b"), claims.claim(run, listOf("a", "b")))
    }

    @Test
    fun `a release by another owner leaves the claim standing`() {
        val claims = HideSourceClaims()
        val owner = Any()
        claims.claim(owner, listOf("a"))

        claims.release(Any(), listOf("a"))
        assertEquals(setOf("a"), claims.held())

        claims.release(owner, listOf("a"))
        assertTrue(claims.held().isEmpty())
    }

    @Test
    fun `a screen going away drops only its own claims`() {
        val claims = HideSourceClaims()
        val gone = Any()
        val staying = Any()
        claims.claim(gone, listOf("a", "b"))
        claims.claim(staying, listOf("c"))

        claims.releaseAll(gone)

        assertEquals(setOf("c"), claims.held())
        assertEquals(setOf("a"), claims.claim(Any(), listOf("a", "c")))
    }

    @Test
    fun `many runs claiming one photo at the same moment leave exactly one owner`() {
        val claims = HideSourceClaims()
        val runs = 16
        val pool = Executors.newFixedThreadPool(runs)
        val start = CountDownLatch(1)
        try {
            val won = (1..runs).map {
                pool.submit<Set<String>> {
                    start.await(5, TimeUnit.SECONDS)
                    claims.claim(Any(), listOf("photo"))
                }
            }
            start.countDown()
            assertEquals(1, won.count { it.get(5, TimeUnit.SECONDS).isNotEmpty() })
        } finally {
            pool.shutdownNow()
        }
    }
}
