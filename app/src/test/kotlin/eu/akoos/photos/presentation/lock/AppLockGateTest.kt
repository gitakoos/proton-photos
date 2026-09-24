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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a guarded screen shows ([LockGateFlags]): on its own, and driven by the session bookkeeping
 * ([LockSessionState]) the way a host's settings reads and lifecycle callbacks drive it.
 */
class AppLockGateTest {

    private val fiveMinutes = 5 * 60_000L

    // What shows

    @Test
    fun `the cover shows until the lock settings are known`() {
        assertEquals(LockGateView.Cover, LockGateFlags().view)
    }

    @Test
    fun `a settings read with the session locked shows the lock`() {
        assertEquals(LockGateView.Lock, LockGateFlags().settle(unlocked = false).view)
    }

    @Test
    fun `a settings read with the session open shows the content`() {
        assertEquals(LockGateView.Content, LockGateFlags().settle(unlocked = true).view)
    }

    @Test
    fun `a due lock covers an open screen`() {
        val open = LockGateFlags().settle(unlocked = true)
        assertEquals(LockGateView.Lock, open.settle(unlocked = false).view)
    }

    @Test
    fun `an open session does not lift a lock the screen already shows`() {
        val locked = LockGateFlags().settle(unlocked = false)
        assertEquals(LockGateView.Lock, locked.settle(unlocked = true).view)
    }

    @Test
    fun `an unlock opens the screen`() {
        val locked = LockGateFlags().settle(unlocked = false)
        assertEquals(LockGateView.Content, locked.unlock().view)
    }

    @Test
    fun `the lock outranks the cover`() {
        assertEquals(LockGateView.Lock, LockGateFlags(locked = true, pending = true).view)
    }

    // Composing the content

    @Test
    fun `content kept under the lock is composed under the cover and the lock`() {
        val cover = LockGateFlags()
        val lock = cover.settle(unlocked = false)
        assertTrue(cover.composesContent(keepUnderLock = true))
        assertTrue(lock.composesContent(keepUnderLock = true))
    }

    @Test
    fun `other content waits for the first unlock`() {
        val cover = LockGateFlags()
        val lock = cover.settle(unlocked = false)
        assertFalse(cover.composesContent(keepUnderLock = false))
        assertFalse(lock.composesContent(keepUnderLock = false))
        assertFalse(lock.settle(unlocked = true).composesContent(keepUnderLock = false))
        assertTrue(lock.unlock().composesContent(keepUnderLock = false))
    }

    @Test
    fun `other content shows at once when the session is open`() {
        assertTrue(LockGateFlags().settle(unlocked = true).composesContent(keepUnderLock = false))
    }

    @Test
    fun `other content stays composed under a later lock`() {
        val relocked = LockGateFlags().settle(unlocked = false).unlock().settle(unlocked = false)
        assertEquals(LockGateView.Lock, relocked.view)
        assertTrue(relocked.composesContent(keepUnderLock = false))
    }

    // Driven by the session

    private fun session(enabled: Boolean = true, timeoutMs: Long = fiveMinutes) =
        LockSessionState().apply { applySettings(AppLockSettings(enabled, timeoutMs)) }

    @Test
    fun `a disabled lock opens the screen on the first read`() {
        val state = session(enabled = false)
        val flags = LockGateFlags().settle(state.isUnlocked(0L))
        assertEquals(LockGateView.Content, flags.view)
        assertTrue(flags.composesContent(keepUnderLock = false))
    }

    @Test
    fun `a cold start with the lock on shows nothing guarded until the unlock`() {
        val state = session()
        var flags = LockGateFlags().settle(state.isUnlocked(0L))
        assertEquals(LockGateView.Lock, flags.view)
        assertFalse(flags.composesContent(keepUnderLock = false))
        flags = flags.settle(state.onForeground(500L))
        assertEquals(LockGateView.Lock, flags.view)
        state.markUnlocked(1_000L)
        flags = flags.unlock()
        assertEquals(LockGateView.Content, flags.view)
        assertTrue(flags.composesContent(keepUnderLock = false))
    }

    @Test
    fun `a return within the timeout stays open and one past it locks`() {
        val state = session()
        state.markUnlocked(0L)
        var flags = LockGateFlags().settle(state.isUnlocked(0L))
        state.markBackgrounded(10_000L)
        flags = flags.settle(state.onForeground(10_000L + fiveMinutes - 1))
        assertEquals(LockGateView.Content, flags.view)
        state.markBackgrounded(20_000L + fiveMinutes)
        flags = flags.settle(state.onForeground(20_000L + 2 * fiveMinutes))
        assertEquals(LockGateView.Lock, flags.view)
    }

    @Test
    fun `a second screen opened within the timeout shows its content at once`() {
        val state = session()
        state.markUnlocked(0L)
        state.markBackgrounded(10_000L)
        val second = LockGateFlags()
            .settle(state.isUnlocked(20_000L))
            .settle(state.onForeground(20_000L))
        assertEquals(LockGateView.Content, second.view)
        assertTrue(second.composesContent(keepUnderLock = false))
    }

    @Test
    fun `a second screen opened past the timeout asks for authentication`() {
        val state = session()
        state.markUnlocked(0L)
        state.markBackgrounded(10_000L)
        val second = LockGateFlags()
            .settle(state.isUnlocked(10_000L + fiveMinutes))
            .settle(state.onForeground(10_000L + fiveMinutes))
        assertEquals(LockGateView.Lock, second.view)
        assertFalse(second.composesContent(keepUnderLock = false))
    }

    @Test
    fun `turning the lock on locks an open screen`() {
        val state = session(enabled = false)
        var flags = LockGateFlags().settle(state.isUnlocked(0L))
        state.applySettings(AppLockSettings(enabled = true, timeoutMs = fiveMinutes))
        flags = flags.settle(state.isUnlocked(10L))
        assertEquals(LockGateView.Lock, flags.view)
    }
}
