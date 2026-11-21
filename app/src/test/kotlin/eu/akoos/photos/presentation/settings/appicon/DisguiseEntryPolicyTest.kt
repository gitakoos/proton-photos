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

import android.content.pm.PackageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val ENABLED = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
private const val DISABLED = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
private const val DEFAULT_STATE = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

class DisguiseEntryPolicyTest {

    private class MemoryStore : DisguiseEntryPolicy.Store {
        var saved: Boolean? = null
        override suspend fun savedPicker(): Boolean? = saved
        override suspend fun setSavedPicker(value: Boolean?) { saved = value }
    }

    private val switch = FakeComponentSwitch()
    private val store = MemoryStore()
    private val policy = DisguiseEntryPolicy(switch, store)

    private fun sync(disguise: Boolean, option: Boolean = true) =
        runBlocking { policy.sync(disguiseActive = disguise, cutOption = option) }

    @Test
    fun a_disguise_turns_both_entries_off() {
        sync(disguise = true)
        assertEquals(DISABLED, switch.state(DisguiseEntryPolicy.SHARE_ENTRY))
        assertEquals(DEFAULT_STATE, switch.state(DisguiseEntryPolicy.PICKER_ENTRY))
    }

    @Test
    fun leaving_the_disguise_restores_a_picker_that_was_off() {
        sync(disguise = true)
        sync(disguise = false)
        assertEquals(DEFAULT_STATE, switch.state(DisguiseEntryPolicy.SHARE_ENTRY))
        assertEquals(DEFAULT_STATE, switch.state(DisguiseEntryPolicy.PICKER_ENTRY))
        assertNull(store.saved)
    }

    @Test
    fun leaving_the_disguise_restores_a_picker_that_was_on() {
        switch.states[DisguiseEntryPolicy.PICKER_ENTRY] = ENABLED
        sync(disguise = true)
        assertEquals(DEFAULT_STATE, switch.state(DisguiseEntryPolicy.PICKER_ENTRY))
        assertEquals(true, store.saved)
        sync(disguise = false)
        assertEquals(ENABLED, switch.state(DisguiseEntryPolicy.PICKER_ENTRY))
        assertNull(store.saved)
    }

    @Test
    fun the_saved_choice_is_taken_once_across_disguise_changes() {
        switch.states[DisguiseEntryPolicy.PICKER_ENTRY] = ENABLED
        sync(disguise = true)
        sync(disguise = true)
        assertEquals(true, store.saved)
        sync(disguise = false)
        assertEquals(ENABLED, switch.state(DisguiseEntryPolicy.PICKER_ENTRY))
    }

    @Test
    fun with_the_option_off_a_disguise_changes_nothing() {
        switch.states[DisguiseEntryPolicy.PICKER_ENTRY] = ENABLED
        sync(disguise = true, option = false)
        assertEquals(ENABLED, switch.state(DisguiseEntryPolicy.PICKER_ENTRY))
        assertEquals(DEFAULT_STATE, switch.state(DisguiseEntryPolicy.SHARE_ENTRY))
        assertNull(store.saved)
    }

    @Test
    fun switching_the_option_off_during_a_disguise_brings_the_entries_back() {
        switch.states[DisguiseEntryPolicy.PICKER_ENTRY] = ENABLED
        sync(disguise = true, option = true)
        sync(disguise = true, option = false)
        assertEquals(DEFAULT_STATE, switch.state(DisguiseEntryPolicy.SHARE_ENTRY))
        assertEquals(ENABLED, switch.state(DisguiseEntryPolicy.PICKER_ENTRY))
    }

    @Test
    fun a_plain_icon_with_nothing_saved_leaves_the_picker_alone() {
        switch.states[DisguiseEntryPolicy.PICKER_ENTRY] = ENABLED
        sync(disguise = false)
        assertEquals(ENABLED, switch.state(DisguiseEntryPolicy.PICKER_ENTRY))
        assertEquals(true, switch.calls.none { it.first == DisguiseEntryPolicy.PICKER_ENTRY })
    }

    @Test
    fun the_picker_choice_is_saved_before_anything_is_switched_off() {
        switch.states[DisguiseEntryPolicy.PICKER_ENTRY] = ENABLED
        var savedAtFirstCall: Boolean? = null
        var seen = false
        val spy = object : ComponentSwitch by switch {
            override fun set(className: String, state: Int) {
                if (!seen) {
                    seen = true
                    savedAtFirstCall = store.saved
                }
                switch.set(className, state)
            }
        }
        runBlocking { DisguiseEntryPolicy(spy, store).sync(disguiseActive = true, cutOption = true) }
        assertEquals(true, savedAtFirstCall)
    }
}
