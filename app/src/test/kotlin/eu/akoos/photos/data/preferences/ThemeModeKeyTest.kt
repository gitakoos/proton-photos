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

package eu.akoos.photos.data.preferences

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeModeKeyTest {

    @Test
    fun a_fresh_install_follows_the_system() {
        assertEquals("system", resolveThemeModeKey(null, null))
    }

    @Test
    fun the_saved_mode_wins_over_the_legacy_switch() {
        assertEquals("light", resolveThemeModeKey("light", true))
        assertEquals("system", resolveThemeModeKey("system", false))
    }

    @Test
    fun the_legacy_switch_still_maps_to_dark_or_light() {
        assertEquals("dark", resolveThemeModeKey(null, true))
        assertEquals("light", resolveThemeModeKey(null, false))
    }

    @Test
    fun the_boot_mirror_defaults_to_system_until_written() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("system", ThemePrefsBoot.read(context))
        ThemePrefsBoot.write(context, "dark")
        assertEquals("dark", ThemePrefsBoot.read(context))
    }

    @Test
    fun a_fresh_install_keeps_no_saved_theme_and_follows_the_system() {
        val prefs = mutablePreferencesOf()
        assertFalse(settleThemeDefault(prefs, mirrorWritten = false, installUpdated = false))
        assertNull(prefs[SettingsKeys.THEME_MODE])
        assertEquals("system", resolveThemeModeKey(prefs[SettingsKeys.THEME_MODE], prefs[SettingsKeys.DARK_MODE]))
        assertEquals(true, prefs[SettingsKeys.THEME_DEFAULT_SETTLED])
    }

    @Test
    fun settings_written_on_a_fresh_first_start_do_not_make_it_an_earlier_install() {
        val prefs = mutablePreferencesOf(SettingsKeys.COMPRESS_CODEC_MIGRATED to true)
        assertFalse(settleThemeDefault(prefs, mirrorWritten = false, installUpdated = false))
        assertNull(prefs[SettingsKeys.THEME_MODE])
    }

    @Test
    fun an_earlier_install_without_a_theme_gets_dark_saved_once() {
        val prefs = mutablePreferencesOf()
        assertTrue(settleThemeDefault(prefs, mirrorWritten = true, installUpdated = true))
        assertEquals("dark", prefs[SettingsKeys.THEME_MODE])
        prefs[SettingsKeys.THEME_MODE] = "system"
        assertFalse(settleThemeDefault(prefs, mirrorWritten = true, installUpdated = true))
        assertEquals("system", prefs[SettingsKeys.THEME_MODE])
    }

    @Test
    fun an_updated_install_with_other_settings_but_no_mirror_counts_as_earlier() {
        val prefs = mutablePreferencesOf(SettingsKeys.ONBOARDING_COMPLETE to true)
        assertTrue(settleThemeDefault(prefs, mirrorWritten = false, installUpdated = true))
        assertEquals("dark", prefs[SettingsKeys.THEME_MODE])
    }

    @Test
    fun an_updated_install_with_nothing_saved_is_left_alone() {
        val prefs = mutablePreferencesOf()
        assertFalse(settleThemeDefault(prefs, mirrorWritten = false, installUpdated = true))
        assertNull(prefs[SettingsKeys.THEME_MODE])
    }

    @Test
    fun an_earlier_install_with_a_theme_is_untouched() {
        val light = mutablePreferencesOf(SettingsKeys.THEME_MODE to "light")
        assertFalse(settleThemeDefault(light, mirrorWritten = true, installUpdated = true))
        assertEquals("light", light[SettingsKeys.THEME_MODE])
        val legacy = mutablePreferencesOf(SettingsKeys.DARK_MODE to false)
        assertFalse(settleThemeDefault(legacy, mirrorWritten = true, installUpdated = true))
        assertNull(legacy[SettingsKeys.THEME_MODE])
        assertEquals("light", resolveThemeModeKey(legacy[SettingsKeys.THEME_MODE], legacy[SettingsKeys.DARK_MODE]))
    }

    @Test
    fun the_boot_mirror_reports_whether_it_was_written() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(ThemePrefsBoot.isWritten(context))
        ThemePrefsBoot.write(context, "system")
        assertTrue(ThemePrefsBoot.isWritten(context))
    }
}
