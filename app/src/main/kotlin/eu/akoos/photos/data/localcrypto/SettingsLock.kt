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

package eu.akoos.photos.data.localcrypto

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether the settings file is held back, and why. While it is, what reads it sees an empty file: the app lock
 * reads off, the Hidden password record is absent and the hidden cloud photos are listed again. So the app
 * fails closed instead: the app lock is on, the screens that could show a hidden photo are covered, the widget
 * draws nothing and nothing is saved to the shared backup.
 */
enum class SettingsHold {
    /** The settings were read as they are. */
    NONE,

    /** A copy was set aside because its key did not open, and the user has not chosen to go on without it. */
    SET_ASIDE,

    /** The file could not be read at this start (the Keystore did not answer); it is read again on the next try. */
    UNREADABLE,
}

/** The [SettingsHold] of the settings file for the whole process. */
object SettingsLock {
    const val STORE_NAME = "settings"

    @Volatile private var files: LockedStoreFiles? = null
    private val readFailed = AtomicBoolean(false)
    private val _state = MutableStateFlow(SettingsHold.NONE)
    val state: StateFlow<SettingsHold> = _state.asStateFlow()

    /** True while the settings are held back for either reason. */
    val isHeldBack: Boolean get() = _state.value != SettingsHold.NONE

    /** Reads the set-aside copies under [locked] from now on. */
    fun attach(locked: LockedStoreFiles) {
        files = locked
        refresh()
    }

    /** [attach] with the app's own set-aside folder, once. */
    fun attach(context: Context) {
        if (files == null) attach(LocalCrypto.lockedStores(context))
    }

    /** Looks at the set-aside copies again; called after a copy is set aside, merged back or acknowledged. */
    fun refresh() {
        val waiting = runCatching { files?.unacknowledged(STORE_NAME)?.isNotEmpty() == true }.getOrDefault(true)
        _state.value = when {
            readFailed.get() -> SettingsHold.UNREADABLE
            waiting -> SettingsHold.SET_ASIDE
            else -> SettingsHold.NONE
        }
    }

    /** The settings file [storeName] was read ([readable]) or failed to read. */
    internal fun readOutcome(storeName: String, readable: Boolean) {
        if (storeName != STORE_NAME) return
        readFailed.set(!readable)
        refresh()
    }

    /**
     * The user chose to go on without the copies set aside so far. They stay where they are and still merge back
     * the moment their key opens; only the cover lifts.
     */
    fun continueWithout() {
        runCatching { files?.acknowledge(STORE_NAME) }
        refresh()
    }

    @VisibleForTesting
    internal fun resetForTest() {
        files = null
        readFailed.set(false)
        _state.value = SettingsHold.NONE
    }
}
