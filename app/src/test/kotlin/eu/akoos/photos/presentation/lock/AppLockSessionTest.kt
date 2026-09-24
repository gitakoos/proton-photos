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

package eu.akoos.photos.presentation.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app lock decision ([shouldLock]) and the session bookkeeping around it ([LockSessionState]).
 * Times are milliseconds on the monotonic clock; only their differences matter.
 */
class AppLockSessionTest {

    private val fiveMinutes = 5 * 60_000L
    private val immediately = 0L

    // shouldLock

    @Test
    fun `a disabled lock never locks`() {
        assertFalse(shouldLock(false, fiveMinutes, null, null, 0L))
        assertFalse(shouldLock(false, null, null, null, 0L))
        assertFalse(shouldLock(false, immediately, 0L, 10_000L, 20_000L))
        assertFalse(shouldLock(false, fiveMinutes, 0L, 10_000L, 10_000L + fiveMinutes * 10))
        assertFalse(shouldLock(false, fiveMinutes, 50_000L, null, 10_000L))
        assertFalse(shouldLock(false, fiveMinutes, 0L, 50_000L, 10_000L))
    }

    @Test
    fun `an enabled lock with no unlock in this process locks`() {
        assertTrue(shouldLock(true, fiveMinutes, null, null, 0L))
        assertTrue(shouldLock(true, fiveMinutes, null, null, 1_000_000L))
        assertTrue(shouldLock(true, fiveMinutes, null, 10_000L, 10_001L))
        assertTrue(shouldLock(true, immediately, null, null, 0L))
    }

    @Test
    fun `not backgrounded since the unlock stays unlocked`() {
        assertFalse(shouldLock(true, fiveMinutes, 0L, null, 10 * fiveMinutes))
        assertFalse(shouldLock(true, immediately, 0L, null, 10 * fiveMinutes))
    }

    @Test
    fun `a return within the timeout stays unlocked`() {
        assertFalse(shouldLock(true, fiveMinutes, 0L, 10_000L, 10_000L))
        assertFalse(shouldLock(true, fiveMinutes, 0L, 10_000L, 10_000L + fiveMinutes - 1))
    }

    @Test
    fun `a return at the timeout locks`() {
        assertTrue(shouldLock(true, fiveMinutes, 0L, 10_000L, 10_000L + fiveMinutes))
    }

    @Test
    fun `a return past the timeout locks`() {
        assertTrue(shouldLock(true, fiveMinutes, 0L, 10_000L, 10_000L + fiveMinutes + 1))
        assertTrue(shouldLock(true, fiveMinutes, 0L, 10_000L, 10_000L + 100 * fiveMinutes))
    }

    @Test
    fun `lock immediately locks on any background`() {
        assertTrue(shouldLock(true, immediately, 0L, 10_000L, 10_000L))
        assertTrue(shouldLock(true, immediately, 0L, 10_000L, 10_001L))
        assertTrue(shouldLock(true, immediately, 0L, 10_000L, 10_000L + fiveMinutes))
    }

    @Test
    fun `the post-unlock grace keeps the app open through the prompt's stop and start`() {
        // Unlocked at 10 s, the prompt stops the host at 10.5 s and starts it again.
        assertFalse(shouldLock(true, immediately, 10_000L, 10_500L, 10_600L))
        assertFalse(shouldLock(true, immediately, 10_000L, 10_500L, 10_000L + UNLOCK_GRACE_MS))
        assertTrue(shouldLock(true, immediately, 10_000L, 10_500L, 10_000L + UNLOCK_GRACE_MS + 1))
    }

    @Test
    fun `the grace only covers the first moments after an unlock`() {
        assertTrue(shouldLock(true, immediately, 0L, 10_000L, 10_001L))
        assertTrue(shouldLock(true, fiveMinutes, 0L, 1_000L, 1_000L + fiveMinutes))
    }

    @Test
    fun `a clock set back before the unlock locks`() {
        assertTrue(shouldLock(true, fiveMinutes, 100_000L, null, 50_000L))
        assertTrue(shouldLock(true, fiveMinutes, 100_000L, 100_000L, 99_999L))
    }

    @Test
    fun `a clock set back before the background mark locks`() {
        assertTrue(shouldLock(true, fiveMinutes, 0L, 100_000L, 50_000L))
        // Even inside the post-unlock grace.
        assertTrue(shouldLock(true, fiveMinutes, 0L, 1_000L, 500L))
    }

    @Test
    fun `an unknown timeout locks`() {
        assertTrue(shouldLock(true, null, 0L, null, 10_000L))
        assertTrue(shouldLock(true, null, 0L, 10_000L, 10_001L))
        assertTrue(shouldLock(true, null, 10_000L, null, 10_500L))
    }

    // LockSessionState

    private fun session(enabled: Boolean = true, timeoutMs: Long = fiveMinutes) =
        LockSessionState().apply { applySettings(AppLockSettings(enabled, timeoutMs)) }

    @Test
    fun `the session is locked until the settings are known`() {
        val state = LockSessionState()
        assertFalse(state.isUnlocked(0L))
        assertFalse(state.onForeground(0L))
        state.markUnlocked(0L)
        assertFalse(state.isUnlocked(10L))
    }

    @Test
    fun `a disabled lock leaves the session open`() {
        val state = session(enabled = false)
        assertTrue(state.isUnlocked(0L))
        state.markBackgrounded(0L)
        assertTrue(state.onForeground(100 * fiveMinutes))
    }

    @Test
    fun `an enabled lock stays locked until an unlock`() {
        val state = session()
        assertFalse(state.onForeground(0L))
        state.markBackgrounded(1_000L)
        assertFalse(state.onForeground(2_000L))
        state.markUnlocked(3_000L)
        assertTrue(state.onForeground(3_500L))
        assertTrue(state.isUnlocked(10 * fiveMinutes))
    }

    @Test
    fun `a background past the timeout locks the return`() {
        val state = session()
        state.markUnlocked(0L)
        state.markBackgrounded(10_000L)
        assertFalse(state.onForeground(10_000L + fiveMinutes))
        // Committed: it stays locked, however the timer would read now.
        assertFalse(state.isUnlocked(10_000L + fiveMinutes + 1))
    }

    @Test
    fun `a return within the timeout restarts the timer`() {
        val state = session()
        state.markUnlocked(0L)
        state.markBackgrounded(10_000L)
        assertTrue(state.onForeground(10_000L + fiveMinutes - 1))
        // In the foreground again: the old mark no longer counts.
        assertTrue(state.isUnlocked(10_000L + 3 * fiveMinutes))
        state.markBackgrounded(10_000L + 3 * fiveMinutes)
        assertTrue(state.onForeground(10_000L + 4 * fiveMinutes - 1))
    }

    @Test
    fun `a later background mark cannot restart a timer that already ran out`() {
        val state = session()
        state.markUnlocked(0L)
        state.markBackgrounded(10_000L)
        state.markBackgrounded(10_000L + fiveMinutes + 1)
        assertFalse(state.onForeground(10_000L + fiveMinutes + 2))
    }

    @Test
    fun `lock immediately locks the next return but not the prompt's own stop and start`() {
        val state = session(timeoutMs = immediately)
        state.markUnlocked(10_000L)
        state.markBackgrounded(10_500L)
        assertTrue(state.onForeground(11_000L))
        state.markBackgrounded(20_000L)
        assertFalse(state.onForeground(20_001L))
    }

    @Test
    fun `switching the lock on drops an earlier unlock`() {
        val state = session(enabled = false)
        state.markUnlocked(0L)
        state.applySettings(AppLockSettings(enabled = true, timeoutMs = fiveMinutes))
        assertFalse(state.isUnlocked(10L))
        assertFalse(state.onForeground(10L))
    }

    @Test
    fun `switching the lock off and on again asks for authentication`() {
        val state = session()
        state.markUnlocked(0L)
        state.applySettings(AppLockSettings(enabled = false, timeoutMs = fiveMinutes))
        assertTrue(state.isUnlocked(10L))
        state.applySettings(AppLockSettings(enabled = true, timeoutMs = fiveMinutes))
        assertFalse(state.isUnlocked(20L))
    }

    @Test
    fun `changing the timeout keeps the unlock`() {
        val state = session()
        state.markUnlocked(0L)
        state.applySettings(AppLockSettings(enabled = true, timeoutMs = immediately))
        assertTrue(state.isUnlocked(10_000L))
        state.markBackgrounded(10_000L)
        assertFalse(state.onForeground(10_001L))
    }

    @Test
    fun `ending the grace early locks a quick return under lock immediately`() {
        val state = session(timeoutMs = immediately)
        state.markUnlocked(10_000L)
        state.markBackgrounded(10_500L)
        state.endUnlockGrace(10_600L)
        assertFalse(state.onForeground(10_700L))
    }

    @Test
    fun `ending the grace keeps the unlock and the background timer`() {
        val state = session()
        state.markUnlocked(0L)
        state.endUnlockGrace(500L)
        assertTrue(state.isUnlocked(1_000L))
        state.markBackgrounded(10_000L)
        state.endUnlockGrace(10_001L)
        assertTrue(state.onForeground(10_000L + fiveMinutes - 1))
        state.markBackgrounded(20_000L + fiveMinutes)
        state.endUnlockGrace(20_001L + fiveMinutes)
        assertFalse(state.onForeground(20_000L + 2 * fiveMinutes))
    }

    @Test
    fun `ending the grace leaves a rolled-back unlock time locked`() {
        val state = session()
        state.markUnlocked(100_000L)
        state.endUnlockGrace(50_000L)
        assertFalse(state.isUnlocked(50_000L))
    }

    @Test
    fun `a background mark while locked does not count as an unlock`() {
        val state = session()
        state.markBackgrounded(0L)
        assertFalse(state.onForeground(1L))
        assertFalse(state.isUnlocked(10 * fiveMinutes))
    }
}
