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
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.first

/**
 * Keeps the share sheet entry and the external picker entry from showing the real name while a
 * disguise icon is on. Both are manifest components that read the application's name and icon, which
 * a disguise cannot change, so the optional switch under the Disguise card turns them off for as long
 * as one of the three disguise icons is active and back on when any other icon is chosen.
 *
 * The picker entry is a user choice of its own (Settings, Privacy and security) and ships off, while
 * the share entry ships on. So on the way into a disguise the picker's state is remembered, and on the
 * way out the picker is put back to exactly that, never blindly to on. The remembered value lives in
 * [Store] only while the entries are cut.
 */
class DisguiseEntryPolicy(
    private val switch: ComponentSwitch,
    private val store: Store,
) {
    interface Store {
        /** The picker's enabled state from before the entries were cut, or null when they are not cut. */
        suspend fun savedPicker(): Boolean?
        suspend fun setSavedPicker(value: Boolean?)
    }

    /**
     * Brings the two entries in line with [disguiseActive] and [cutOption]. Safe to call again with the
     * same arguments. The picker's own state is saved before anything is switched off, so a process
     * that dies half-way cannot lose it.
     */
    suspend fun sync(disguiseActive: Boolean, cutOption: Boolean) {
        if (disguiseActive && cutOption) {
            if (store.savedPicker() == null) store.setSavedPicker(pickerEnabled())
            setIfChanged(PICKER_ENTRY, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            setIfChanged(SHARE_ENTRY, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        } else {
            setIfChanged(SHARE_ENTRY, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            val saved = store.savedPicker()
            if (saved != null) {
                setIfChanged(
                    PICKER_ENTRY,
                    if (saved) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                )
                store.setSavedPicker(null)
            }
        }
    }

    private fun pickerEnabled(): Boolean =
        switch.state(PICKER_ENTRY) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun setIfChanged(className: String, state: Int) {
        if (switch.state(className) != state) switch.set(className, state)
    }

    companion object {
        /** Class names of the two manifest components; a test compares them with the manifest. */
        const val SHARE_ENTRY = "eu.akoos.photos.presentation.share.ShareBackupActivity"
        const val PICKER_ENTRY = "eu.akoos.photos.PickPhotoEntry"
    }
}

/** The settings store behind [DisguiseEntryPolicy], plus the option switch itself. */
class DisguiseEntrySettings(private val context: Context) : DisguiseEntryPolicy.Store {

    /** Opt-out: absent reads as on. */
    suspend fun cutOption(): Boolean =
        context.settingsDataStore.data.first()[SettingsKeys.DISGUISE_CUTS_ENTRIES] != false

    suspend fun setCutOption(on: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.DISGUISE_CUTS_ENTRIES] = on }
    }

    override suspend fun savedPicker(): Boolean? =
        context.settingsDataStore.data.first()[SettingsKeys.DISGUISE_SAVED_PICKER]

    override suspend fun setSavedPicker(value: Boolean?) {
        context.settingsDataStore.edit {
            if (value == null) it.remove(SettingsKeys.DISGUISE_SAVED_PICKER)
            else it[SettingsKeys.DISGUISE_SAVED_PICKER] = value
        }
    }
}
