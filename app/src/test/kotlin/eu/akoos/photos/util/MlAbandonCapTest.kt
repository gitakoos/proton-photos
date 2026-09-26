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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pins the abandoned-session cap: a walk keeps going through its first abandoned session, stops at the
 * second, and stays stopped for the life of the counter (the process), however the count got there.
 */
class MlAbandonCapTest {

    @Test
    fun `the cap is two sessions`() {
        assertEquals(2, ML_ABANDON_CAP)
    }

    @Test
    fun `a fresh process has abandoned nothing and may scan`() {
        val cap = MlAbandonCap()
        assertEquals(0, cap.count())
        assertFalse(cap.reached())
    }

    @Test
    fun `one abandoned session leaves the walk running`() {
        val cap = MlAbandonCap()
        assertEquals(1, cap.recordAbandon())
        assertFalse(cap.reached())
    }

    @Test
    fun `the second abandoned session stops the walk`() {
        val cap = MlAbandonCap()
        cap.recordAbandon()
        assertEquals(2, cap.recordAbandon())
        assertTrue(cap.reached())
    }

    @Test
    fun `further abandons keep it stopped and keep counting`() {
        val cap = MlAbandonCap()
        repeat(5) { cap.recordAbandon() }
        assertTrue(cap.reached())
        assertEquals(5, cap.count())
    }

    @Test
    fun `abandons from several scans at once all count`() {
        val cap = MlAbandonCap(cap = 50)
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(50)
        repeat(50) { pool.execute { cap.recordAbandon(); done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(50, cap.count())
        assertTrue(cap.reached())
    }
}
