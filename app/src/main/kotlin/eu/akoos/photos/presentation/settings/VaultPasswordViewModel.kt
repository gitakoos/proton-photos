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

package eu.akoos.photos.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.data.hidden.VaultPasswordStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sets, changes and removes the optional vault password for both places that offer it: the Settings
 * row and the Hidden photos screen. The typed passwords are wiped from memory once used.
 */
@HiltViewModel
class VaultPasswordViewModel @Inject constructor(
    private val store: VaultPasswordStore,
) : ViewModel() {

    val isSet: StateFlow<Boolean> = store.observeIsSet()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Sets the first password, then calls [onDone]. Ignored once one is set: changing it goes through [change]. */
    fun set(password: CharArray, onDone: () -> Unit) {
        viewModelScope.launch {
            val first = !store.isSet()
            if (first) store.set(password)
            password.fill(WIPED)
            if (first) onDone()
        }
    }

    /** Replaces the password with [new] once [current] is confirmed to be the stored one; [onChecked] says whether it was. */
    fun change(current: CharArray, new: CharArray, onChecked: (Boolean) -> Unit) {
        viewModelScope.launch {
            val right = store.matches(current)
            current.fill(WIPED)
            if (right) store.set(new)
            new.fill(WIPED)
            onChecked(right)
        }
    }

    /** Removes the password once [current] is confirmed to be the stored one; [onChecked] says whether it was. */
    fun remove(current: CharArray, onChecked: (Boolean) -> Unit) {
        viewModelScope.launch {
            val right = store.matches(current)
            current.fill(WIPED)
            if (right) store.clear()
            onChecked(right)
        }
    }

    /** Removes the password after the caller confirmed the user with the device lock, for a forgotten one. */
    fun removeAfterDeviceAuth(onDone: () -> Unit) {
        viewModelScope.launch {
            store.clear()
            onDone()
        }
    }

    private companion object {
        const val WIPED = '\u0000'
    }
}
