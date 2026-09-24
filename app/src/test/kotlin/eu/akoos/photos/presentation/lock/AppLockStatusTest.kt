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

import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app lock holds back from the content under it ([lockHoldsLaunches], [runWhenOpen]) and the
 * window it secures over a pause below Android 13 ([securesWindowWhilePaused], [ownsSecureFlag]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockStatusTest {

    private val fiveMinutes = 5 * 60_000L

    // lockHoldsLaunches

    @Test
    fun `the lock and its cover hold launches whatever the host does`() {
        for (resumed in listOf(true, false)) {
            for (enabled in listOf(true, false, null)) {
                assertTrue(lockHoldsLaunches(LockGateView.Lock, resumed, enabled))
                assertTrue(lockHoldsLaunches(LockGateView.Cover, resumed, enabled))
            }
        }
    }

    @Test
    fun `open content in the foreground launches`() {
        assertFalse(lockHoldsLaunches(LockGateView.Content, resumed = true, lockEnabled = true))
        assertFalse(lockHoldsLaunches(LockGateView.Content, resumed = true, lockEnabled = false))
    }

    @Test
    fun `a host out of the foreground holds launches while the lock may be on`() {
        assertTrue(lockHoldsLaunches(LockGateView.Content, resumed = false, lockEnabled = true))
        assertTrue(lockHoldsLaunches(LockGateView.Content, resumed = false, lockEnabled = null))
    }

    @Test
    fun `a host out of the foreground with the lock off launches as before`() {
        assertFalse(lockHoldsLaunches(LockGateView.Content, resumed = false, lockEnabled = false))
    }

    @Test
    fun `a lock due on the return holds launches from the start that decides it`() {
        val state = LockSessionState().apply { applySettings(AppLockSettings(true, fiveMinutes)) }
        state.markUnlocked(0L)
        var flags = LockGateFlags().settle(state.isUnlocked(0L))
        assertFalse(lockHoldsLaunches(flags.view, resumed = true, lockEnabled = true))
        state.markBackgrounded(10_000L)
        flags = flags.settle(state.onForeground(10_000L + fiveMinutes))
        assertTrue(lockHoldsLaunches(flags.view, resumed = true, lockEnabled = true))
        flags = flags.unlock()
        assertFalse(lockHoldsLaunches(flags.view, resumed = true, lockEnabled = true))
    }

    // runWhenOpen

    private class FakeLock(held: Boolean) : AppLockStatus {
        var held by mutableStateOf(held)
        override val isLocked: Boolean get() = held
        override val holdsLaunches: Boolean get() = held
    }

    private fun FakeLock.set(held: Boolean) {
        this.held = held
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun `runWhenOpen runs at once when nothing is held`() = runTest {
        val lock = FakeLock(held = false)
        var runs = 0
        lock.runWhenOpen { runs++ }
        assertEquals(1, runs)
    }

    @Test
    fun `runWhenOpen waits for the unlock and runs once`() = runTest {
        val lock = FakeLock(held = true)
        var runs = 0
        val job = launch { lock.runWhenOpen { runs++ } }
        runCurrent()
        assertEquals(0, runs)
        lock.set(held = false)
        runCurrent()
        assertEquals(1, runs)
        assertTrue(job.isCompleted)
        lock.set(held = true)
        lock.set(held = false)
        runCurrent()
        assertEquals(1, runs)
    }

    @Test
    fun `runWhenOpen keeps waiting through a lock that returns before it runs`() = runTest {
        val lock = FakeLock(held = true)
        var runs = 0
        launch { lock.runWhenOpen { runs++ } }
        runCurrent()
        // Opened and locked again before the waiting coroutine gets to run: it goes by the lock as it is.
        lock.held = false
        Snapshot.sendApplyNotifications()
        lock.held = true
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(0, runs)
        lock.set(held = false)
        runCurrent()
        assertEquals(1, runs)
    }

    @Test
    fun `a cancelled wait never runs`() = runTest {
        val lock = FakeLock(held = true)
        var runs = 0
        val job = launch { lock.runWhenOpen { runs++ } }
        runCurrent()
        job.cancel()
        lock.set(held = false)
        runCurrent()
        assertEquals(0, runs)
    }

    // securesWindowWhilePaused

    @Test
    fun `below Android 13 the window is secured over a pause while the lock may be on`() {
        for (sdk in 26..32) {
            assertTrue("API $sdk", securesWindowWhilePaused(sdk, lockEnabled = true))
            assertTrue("API $sdk", securesWindowWhilePaused(sdk, lockEnabled = null))
        }
    }

    @Test
    fun `a lock that is off leaves the window alone`() {
        for (sdk in 26..36) {
            assertFalse("API $sdk", securesWindowWhilePaused(sdk, lockEnabled = false))
        }
    }

    @Test
    fun `from Android 13 the Recents setting covers the pause instead`() {
        for (sdk in 33..36) {
            assertFalse("API $sdk", securesWindowWhilePaused(sdk, lockEnabled = true))
            assertFalse("API $sdk", securesWindowWhilePaused(sdk, lockEnabled = null))
        }
    }

    // ownsSecureFlag

    @Test
    fun `a hold on a window without FLAG_SECURE sets it itself and clears it again`() {
        assertTrue(ownsSecureFlag(0))
        assertTrue(ownsSecureFlag(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON))
    }

    @Test
    fun `a hold on a window already secured leaves the flag to its owner`() {
        assertFalse(ownsSecureFlag(WindowManager.LayoutParams.FLAG_SECURE))
        assertFalse(
            ownsSecureFlag(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON),
        )
    }
}
