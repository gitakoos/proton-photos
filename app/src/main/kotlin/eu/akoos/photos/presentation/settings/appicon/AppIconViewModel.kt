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

package eu.akoos.photos.presentation.settings.appicon

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the App icon page. The icon itself is read from the system ([AppIconManager]); the only
 * stored setting is the option that cuts the share sheet and picker entries while a disguise is on.
 * The page has no account gate, so it works the same in guest mode.
 */
@HiltViewModel
class AppIconViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val manager = AppIconManager(context)
    private val entrySettings = DisguiseEntrySettings(context)
    private val entryPolicy = DisguiseEntryPolicy(PackageManagerSwitch(context), entrySettings)

    private val _current = MutableStateFlow(AppIcon.DEFAULT)
    val current: StateFlow<AppIcon> = _current.asStateFlow()

    /** Whether a disguise also turns off the share sheet and picker entries. On unless switched off. */
    val cutEntries: StateFlow<Boolean> = context.settingsDataStore.data
        .map { it[SettingsKeys.DISGUISE_CUTS_ENTRIES] != false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _changed = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    /** One value per finished [select]: true when the system took the change. */
    val changed: SharedFlow<Boolean> = _changed.asSharedFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            manager.reconcile()
            _current.value = manager.current()
        }
    }

    fun select(icon: AppIcon) {
        viewModelScope.launch(Dispatchers.Default) {
            val ok = manager.apply(icon)
            manager.reconcile()
            val now = manager.current()
            _current.value = now
            if (ok) entryPolicy.sync(disguiseActive = now.isDisguise, cutOption = entrySettings.cutOption())
            _changed.emit(ok)
        }
    }

    fun setCutEntries(on: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            entrySettings.setCutOption(on)
            entryPolicy.sync(disguiseActive = manager.current().isDisguise, cutOption = on)
        }
    }
}
