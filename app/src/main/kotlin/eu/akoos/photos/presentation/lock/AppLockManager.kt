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

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import eu.akoos.photos.data.localcrypto.SettingsHold
import eu.akoos.photos.data.localcrypto.SettingsLock
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val isLockEnabled: Flow<Boolean> get() = settings.map { it.enabled }

    /**
     * Timeout in seconds between backgrounding the app and re-locking it on resume.
     * 0 = lock immediately (the default before this option existed). Larger values let the user briefly
     * switch to other apps without re-authenticating on every return. A value saved under the old
     * minutes key migrates read-side (seconds = minutes * 60) when the seconds key is absent.
     */
    val lockTimeoutSeconds: Flow<Int> = context.settingsDataStore.data
        .map { prefs -> timeoutSeconds(prefs) }

    /**
     * The enabled flag and the timeout in one read, for the in-memory cache of [AppLockSession].
     *
     * Fails closed: while the settings are held back ([SettingsLock]) the stored flag may be the empty file's
     * default rather than the user's choice, so the lock is on; a file that cannot be read at all reads as the
     * lock on with no grace, and is read again shortly.
     */
    val settings: Flow<AppLockSettings> = combine(
        context.settingsDataStore.data
            .map { prefs ->
                AppLockSettings(
                    enabled = prefs[SettingsKeys.APP_LOCK_ENABLED] ?: false,
                    timeoutMs = timeoutSeconds(prefs).toLong() * 1000L,
                )
            }
            .retryWhen { cause, _ ->
                if (cause !is java.io.IOException) return@retryWhen false
                emit(AppLockSettings(enabled = true, timeoutMs = 0L))
                delay(UNREADABLE_RETRY_MS)
                true
            },
        SettingsLock.state,
    ) { stored, hold -> heldBackLockSettings(stored, hold) }
        .distinctUntilChanged()

    private fun timeoutSeconds(prefs: Preferences): Int =
        prefs[SettingsKeys.APP_LOCK_TIMEOUT_SECONDS]
            ?: prefs[SettingsKeys.APP_LOCK_TIMEOUT_MINUTES]?.let { it * 60 }
            ?: 0

    suspend fun setLockEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setLockTimeoutSeconds(seconds: Int) {
        val clamped = seconds.coerceAtLeast(0)
        context.settingsDataStore.edit { it[SettingsKeys.APP_LOCK_TIMEOUT_SECONDS] = clamped }
    }

    /**
     * Sign-out signal for [AppLockSession], which ends its post-unlock grace window so the stop and start
     * of the sign-in flow is judged by the lock timeout alone. The unlock and the background mark stay as
     * they are: clearing the mark could leave a backgrounded app open.
     *
     * SettingsViewModel.signOut() emits here right before `accountManager.disableAccount`.
     */
    private val _resetLockTimestamps = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val resetLockTimestamps: SharedFlow<Unit> = _resetLockTimestamps.asSharedFlow()

    fun notifyResetLockTimestamps() {
        _resetLockTimestamps.tryEmit(Unit)
    }

    private companion object {
        const val UNREADABLE_RETRY_MS = 5_000L
    }
}

/** [stored] as the lock applies it while the settings are [hold]: on whenever they are held back. Pure. */
internal fun heldBackLockSettings(stored: AppLockSettings, hold: SettingsHold): AppLockSettings =
    if (hold == SettingsHold.NONE) stored else stored.copy(enabled = true)
