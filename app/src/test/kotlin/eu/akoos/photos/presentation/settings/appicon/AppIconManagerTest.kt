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

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.presentation.picker.ExternalPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val ENABLED = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
private const val DISABLED = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
private const val DEFAULT_STATE = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

/** An in-memory stand-in for the system's component states that also records the order of the calls. */
internal class FakeComponentSwitch : ComponentSwitch {
    val states = mutableMapOf<String, Int>()
    val calls = mutableListOf<Pair<String, Int>>()

    override fun state(className: String): Int = states[className] ?: DEFAULT_STATE

    override fun set(className: String, state: Int) {
        calls += className to state
        states[className] = state
    }
}

private fun FakeComponentSwitch.enabledCount(): Int = AppIcon.entries.count {
    when (state(it.aliasName)) {
        ENABLED -> true
        DEFAULT_STATE -> it == AppIcon.DEFAULT
        else -> false
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppIconManagerTest {

    private val switch = FakeComponentSwitch()
    private val manager = AppIconManager(switch)

    private fun enabledAliases(): List<AppIcon> = AppIcon.entries.filter {
        when (switch.state(it.aliasName)) {
            ENABLED -> true
            DEFAULT_STATE -> it == AppIcon.DEFAULT
            else -> false
        }
    }

    @Test
    fun a_fresh_install_shows_the_default_icon() {
        assertEquals(AppIcon.DEFAULT, manager.current())
        assertEquals(listOf(AppIcon.DEFAULT), enabledAliases())
    }

    @Test
    fun there_are_sixteen_icons_three_of_them_disguises() {
        assertEquals(16, AppIcon.entries.size)
        assertEquals(listOf(AppIcon.CLOCK, AppIcon.CALCULATOR, AppIcon.NOTES), AppIcon.disguises)
        assertEquals(AppIcon.entries.size, AppIcon.entries.map { it.aliasName }.toSet().size)
    }

    @Test
    fun applying_an_icon_leaves_exactly_that_one_enabled() {
        assertTrue(manager.apply(AppIcon.GOLD))
        assertEquals(listOf(AppIcon.GOLD), enabledAliases())
        assertEquals(AppIcon.GOLD, manager.current())
    }

    @Test
    fun the_chosen_alias_is_enabled_before_any_other_is_disabled() {
        manager.apply(AppIcon.CLOCK)
        assertEquals(AppIcon.CLOCK.aliasName to ENABLED, switch.calls.first())
        assertTrue(switch.calls.any { it.second == DISABLED })
    }

    @Test
    fun switching_between_two_icons_never_has_zero_enabled() {
        manager.apply(AppIcon.CLOCK)
        val recorder = FakeComponentSwitch().also { it.states.putAll(switch.states) }
        AppIconManager(recorder).apply(AppIcon.NOTES)
        // Replay the recorded calls one at a time and count the enabled aliases after each.
        val probe = FakeComponentSwitch().also { it.states.putAll(switch.states) }
        for ((cls, state) in recorder.calls) {
            probe.set(cls, state)
            assertTrue("no launcher entry after $cls", probe.enabledCount() >= 1)
        }
        assertEquals(1, probe.enabledCount())
    }

    @Test
    fun applying_the_default_restores_the_manifest_states() {
        manager.apply(AppIcon.NOTES)
        assertTrue(manager.apply(AppIcon.DEFAULT))
        for (icon in AppIcon.entries) {
            assertEquals(icon.aliasName, DEFAULT_STATE, switch.state(icon.aliasName))
        }
        assertEquals(AppIcon.DEFAULT, manager.current())
    }

    @Test
    fun applying_the_icon_already_shown_makes_no_system_call() {
        manager.apply(AppIcon.SEA)
        switch.calls.clear()
        manager.apply(AppIcon.SEA)
        assertTrue(switch.calls.isEmpty())
    }

    @Test
    fun current_falls_back_to_the_default_with_none_or_several_enabled() {
        switch.states[AppIcon.DEFAULT.aliasName] = DISABLED
        assertEquals(AppIcon.DEFAULT, manager.current())
        switch.states[AppIcon.DEFAULT.aliasName] = ENABLED
        switch.states[AppIcon.RUBY.aliasName] = ENABLED
        assertEquals(AppIcon.DEFAULT, manager.current())
    }

    @Test
    fun reconcile_enables_the_default_when_none_is_enabled() {
        switch.states[AppIcon.DEFAULT.aliasName] = DISABLED
        assertTrue(manager.reconcile())
        assertEquals(listOf(AppIcon.DEFAULT), enabledAliases())
    }

    @Test
    fun reconcile_keeps_one_when_several_are_enabled() {
        switch.states[AppIcon.CLOCK.aliasName] = ENABLED
        switch.states[AppIcon.RUBY.aliasName] = ENABLED
        assertTrue(manager.reconcile())
        assertEquals(1, enabledAliases().size)
    }

    @Test
    fun reconcile_leaves_a_correct_state_alone() {
        manager.apply(AppIcon.MINT)
        switch.calls.clear()
        manager.reconcile()
        assertTrue(switch.calls.isEmpty())
    }

    @Test
    fun the_real_package_manager_follows_the_same_rules() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val real = AppIconManager(context)
        assertEquals(AppIcon.DEFAULT, real.current())
        assertTrue(real.apply(AppIcon.CALCULATOR))
        assertEquals(AppIcon.CALCULATOR, real.current())
        val pm = context.packageManager
        assertEquals(
            ENABLED,
            pm.getComponentEnabledSetting(ComponentName(context.packageName, AppIcon.CALCULATOR.aliasName)),
        )
        assertEquals(
            DISABLED,
            pm.getComponentEnabledSetting(ComponentName(context.packageName, AppIcon.DEFAULT.aliasName)),
        )
        assertTrue(real.apply(AppIcon.DEFAULT))
        assertEquals(AppIcon.DEFAULT, real.current())
    }

    @Test
    fun the_picker_switch_and_the_policy_name_the_same_component() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val real = PackageManagerSwitch(context)
        ExternalPicker.setEnabled(context, true)
        assertEquals(ENABLED, real.state(DisguiseEntryPolicy.PICKER_ENTRY))
        ExternalPicker.setEnabled(context, false)
        assertFalse(ExternalPicker.isEnabled(context))
        assertEquals(DEFAULT_STATE, real.state(DisguiseEntryPolicy.PICKER_ENTRY))
    }
}
