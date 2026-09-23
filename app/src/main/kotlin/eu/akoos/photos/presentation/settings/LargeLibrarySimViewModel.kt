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

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.drive.LargeLibraryDeviceSimulator
import eu.akoos.photos.data.repository.drive.LargeLibrarySimulator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DEBUG-only ViewModel backing the large-library simulator settings entry. Kept out of the main
 * [SettingsViewModel] so the simulator graph (and its injection of [LargeLibrarySimulator]) only
 * loads when the debug section is actually shown.
 *
 * The device-photo generator's work lives in [LargeLibraryDeviceSimulator]'s own scope, not this
 * ViewModel's, so a run keeps going after Settings closes; this only starts, cancels and mirrors it.
 */
@HiltViewModel
class LargeLibrarySimViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val simulator: LargeLibrarySimulator,
    private val deviceSimulator: LargeLibraryDeviceSimulator,
) : ViewModel() {

    data class UiState(
        val count: Int = 0,
        val running: Boolean = false,
        val message: String? = null,
        val deviceTarget: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val deviceState: StateFlow<LargeLibraryDeviceSimulator.State> = deviceSimulator.state

    init {
        viewModelScope.launch {
            val saved = context.settingsDataStore.data.first()[SettingsKeys.SIM_LARGE_LIBRARY_COUNT] ?: 0
            _state.update { it.copy(count = saved) }
        }
        deviceSimulator.refreshCount()
    }

    fun setDeviceTarget(target: Int) = _state.update { it.copy(deviceTarget = target.coerceAtLeast(0)) }

    fun generateDevicePhotos() = deviceSimulator.generate(_state.value.deviceTarget)

    fun removeDevicePhotos() = deviceSimulator.removeAll()

    fun cancelDeviceWork() = deviceSimulator.cancel()

    fun setCount(count: Int) = _state.update { it.copy(count = count.coerceAtLeast(0)) }

    fun populate() {
        if (_state.value.running) return
        viewModelScope.launch {
            val n = _state.value.count
            _state.update { it.copy(running = true, message = null) }
            val result = simulator.populate(n)
            context.settingsDataStore.edit { it[SettingsKeys.SIM_LARGE_LIBRARY_COUNT] = n }
            _state.update { it.copy(running = false, message = result.message) }
        }
    }

    fun clear() {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, message = null) }
            val removed = simulator.clear()
            context.settingsDataStore.edit { it[SettingsKeys.SIM_LARGE_LIBRARY_COUNT] = 0 }
            _state.update { it.copy(running = false, count = 0, message = "Cleared $removed simulated photos") }
        }
    }
}
