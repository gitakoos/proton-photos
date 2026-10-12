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

package eu.akoos.photos.presentation.util

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * Pins the clock choice: Automatic follows the phone, the other two ignore it, an unknown stored value
 * reads as Automatic, and the English app writes a 24-hour clock when asked to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClockFormatTest {

    @Test
    fun automatic_follows_the_phone_and_the_others_ignore_it() {
        assertTrue(ClockFormat.AUTO.is24Hour(true))
        assertFalse(ClockFormat.AUTO.is24Hour(false))
        assertTrue(ClockFormat.H24.is24Hour(false))
        assertFalse(ClockFormat.H12.is24Hour(true))
    }

    @Test
    fun a_stored_key_maps_back_and_anything_else_is_automatic() {
        for (format in ClockFormat.entries) assertEquals(format, ClockFormat.fromKey(format.key))
        assertEquals(ClockFormat.AUTO, ClockFormat.fromKey(null))
        assertEquals(ClockFormat.AUTO, ClockFormat.fromKey("sideways"))
    }

    @Test
    fun the_state_holder_resolves_the_choice_against_the_phone() {
        ClockFormatState.update(ClockFormat.AUTO, phoneIs24Hour = true)
        assertTrue(ClockFormatState.is24Hour())
        ClockFormatState.update(ClockFormat.H12, phoneIs24Hour = true)
        assertFalse(ClockFormatState.is24Hour())
        ClockFormatState.update(ClockFormat.AUTO, phoneIs24Hour = false)
    }

    private fun appContextIn(locale: Locale): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    @Test
    @Config(sdk = [34], qualifiers = "hu")
    fun a_hungarian_phone_reads_24_hour_while_the_app_is_english() {
        assertTrue(phoneUses24Hour(appContextIn(Locale.US)))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en-rUS")
    fun an_english_phone_reads_12_hour_while_the_app_is_hungarian() {
        assertFalse(phoneUses24Hour(appContextIn(Locale("hu"))))
    }

    @Test
    fun the_english_app_shows_just_after_midnight_on_either_clock() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Budapest"))
        try {
            val ms = LocalDateTime.of(2026, 10, 12, 0, 46).atZone(ZoneId.of("Europe/Budapest")).toInstant().toEpochMilli()
            val twentyFour = formatDateTimeMinute(ms, Locale.US, is24Hour = true)
            val twelve = formatDateTimeMinute(ms, Locale.US, is24Hour = false)
            assertTrue(twentyFour, twentyFour.contains("00:46") && !twentyFour.contains("AM"))
            assertTrue(twelve, twelve.contains("12:46") && twelve.contains("AM"))
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
