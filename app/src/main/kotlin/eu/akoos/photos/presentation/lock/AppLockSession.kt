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

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The app lock settings as last read from the settings store. */
data class AppLockSettings(val enabled: Boolean, val timeoutMs: Long)

/** How long after a successful unlock a stop and start of the host never re-locks. */
internal const val UNLOCK_GRACE_MS = 2_000L

/**
 * Whether the app lock must show. Every time is [SystemClock.elapsedRealtime], which cannot be set back.
 *
 * - A disabled lock never locks.
 * - An enabled lock locks with no unlock in this process ([unlockedAtMs] null) and with an unknown
 *   timeout ([timeoutMs] null).
 * - It stays unlocked while the app has not been backgrounded since the unlock ([backgroundedAtMs] null).
 * - It locks once the time since backgrounding reaches [timeoutMs], so a zero timeout ("immediately")
 *   locks on any background, except within [UNLOCK_GRACE_MS] of the unlock: the biometric and credential
 *   prompts cycle the host through stop and start as they succeed.
 * - A negative elapsed time, which only a rolled-back clock or state can produce, locks.
 */
internal fun shouldLock(
    enabled: Boolean,
    timeoutMs: Long?,
    unlockedAtMs: Long?,
    backgroundedAtMs: Long?,
    nowMs: Long,
): Boolean {
    if (!enabled) return false
    if (timeoutMs == null || unlockedAtMs == null) return true
    val sinceUnlock = nowMs - unlockedAtMs
    if (sinceUnlock < 0) return true
    if (backgroundedAtMs == null) return false
    val sinceBackground = nowMs - backgroundedAtMs
    if (sinceBackground < 0) return true
    return sinceBackground >= timeoutMs && sinceUnlock > UNLOCK_GRACE_MS
}

/**
 * The unlock bookkeeping behind [AppLockSession], free of Android and coroutines so it is unit-testable.
 * Not thread-safe on its own.
 */
internal class LockSessionState {

    var settings: AppLockSettings? = null
        private set

    private var unlockedAtMs: Long? = null
    private var backgroundedAtMs: Long? = null

    /** Switching the lock on or off drops the unlock, so turning it on asks for authentication. */
    fun applySettings(next: AppLockSettings) {
        val previous = settings
        if (previous != null && previous.enabled != next.enabled) dropUnlock()
        settings = next
    }

    fun isUnlocked(nowMs: Long): Boolean {
        val current = settings ?: return false
        return !shouldLock(current.enabled, current.timeoutMs, unlockedAtMs, backgroundedAtMs, nowMs)
    }

    /** A guarded screen came to the foreground: a due lock is committed, otherwise the timer restarts. */
    fun onForeground(nowMs: Long): Boolean {
        if (settings == null) return false
        settle(nowMs)
        val unlocked = isUnlocked(nowMs)
        if (unlocked) backgroundedAtMs = null
        return unlocked
    }

    fun markUnlocked(nowMs: Long) {
        unlockedAtMs = nowMs
        backgroundedAtMs = null
    }

    fun markBackgrounded(nowMs: Long) {
        settle(nowMs)
        if (unlockedAtMs != null) backgroundedAtMs = nowMs
    }

    /** Ends the post-unlock grace early. Leaves the unlock, the background mark and a negative elapsed time alone. */
    fun endUnlockGrace(nowMs: Long) {
        val unlockedAt = unlockedAtMs ?: return
        if (nowMs - unlockedAt in 0..UNLOCK_GRACE_MS) unlockedAtMs = nowMs - UNLOCK_GRACE_MS - 1
    }

    /** Commits a lock that is already due, so a later background mark cannot restart its timer. */
    private fun settle(nowMs: Long) {
        if (settings != null && !isUnlocked(nowMs)) dropUnlock()
    }

    private fun dropUnlock() {
        unlockedAtMs = null
        backgroundedAtMs = null
    }
}

/**
 * The app-wide unlock session shared by every screen the app lock guards. The lock settings are cached
 * in memory from the settings store, so a screen decides synchronously in onStart. [settings] stays null
 * until the first read lands, and an unknown state counts as locked.
 *
 * A guarded screen calls [onForeground] from onStart and shows the lock when it returns false,
 * [markBackgrounded] from onStop, and [markUnlocked] after a successful authentication. [isUnlocked] is
 * the same decision without side effects. Times are [SystemClock.elapsedRealtime].
 */
@Singleton
class AppLockSession @Inject constructor(appLockManager: AppLockManager) {

    private val state = LockSessionState()
    private val _settings = MutableStateFlow<AppLockSettings?>(null)
    val settings: StateFlow<AppLockSettings?> = _settings.asStateFlow()

    init {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            appLockManager.settings.collect { next ->
                synchronized(state) { state.applySettings(next) }
                _settings.value = next
            }
        }
        scope.launch {
            appLockManager.resetLockTimestamps.collect {
                synchronized(state) { state.endUnlockGrace(SystemClock.elapsedRealtime()) }
            }
        }
    }

    fun isUnlocked(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        synchronized(state) { state.isUnlocked(nowMs) }

    fun onForeground(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        synchronized(state) { state.onForeground(nowMs) }

    fun markUnlocked(nowMs: Long = SystemClock.elapsedRealtime()) {
        synchronized(state) { state.markUnlocked(nowMs) }
    }

    fun markBackgrounded(nowMs: Long = SystemClock.elapsedRealtime()) {
        synchronized(state) { state.markBackgrounded(nowMs) }
    }
}
