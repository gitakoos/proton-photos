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

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.compositionLocalOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How the time of day is written, chosen apart from the app language so an English app can still show a
 * 24-hour clock. [AUTO] follows the phone's own 12 or 24 hour setting.
 */
enum class ClockFormat(val key: String) {
    AUTO("auto"),
    H24("24h"),
    H12("12h"),
    ;

    /** Whether the clock reads 24 hours, given what the phone's setting says. */
    fun is24Hour(phoneIs24Hour: Boolean): Boolean = when (this) {
        AUTO -> phoneIs24Hour
        H24 -> true
        H12 -> false
    }

    companion object {
        fun fromKey(key: String?): ClockFormat = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * The clock the screens are showing right now, kept for the formatters that have no composition to read it
 * from. [MainActivity][eu.akoos.photos.MainActivity] refreshes it whenever the choice or the phone's setting
 * changes.
 */
object ClockFormatState {
    @Volatile private var choice: ClockFormat = ClockFormat.AUTO
    @Volatile private var phoneIs24Hour: Boolean = false

    fun update(choice: ClockFormat, phoneIs24Hour: Boolean) {
        this.choice = choice
        this.phoneIs24Hour = phoneIs24Hour
    }

    fun is24Hour(): Boolean = choice.is24Hour(phoneIs24Hour)
}

/**
 * Whether the phone itself writes a 24-hour clock: its 12/24 hour setting when one is chosen, else the usual
 * clock of the phone's own language. The app language is left out on purpose: choosing it applies to the
 * context's configuration too, which would make an English app read as 12-hour on a phone set to 24.
 */
fun phoneUses24Hour(context: Context): Boolean {
    val config = Configuration(context.resources.configuration).apply { setLocales(phoneLocales(context)) }
    return android.text.format.DateFormat.is24HourFormat(context.createConfigurationContext(config))
}

/**
 * The phone's own language list. From Android 13 the system answers for itself; before that the process-wide
 * system resources still hold it. Those resources follow the app language once one is chosen on Android 13 and
 * later, which is why they are not read there.
 */
internal fun phoneLocales(context: Context): LocaleList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val fromSystem = context.getSystemService(LocaleManager::class.java)?.systemLocales
        if (fromSystem != null && !fromSystem.isEmpty) return fromSystem
    }
    return Resources.getSystem().configuration.locales
}

/** The same answer as [ClockFormatState.is24Hour], for composables that must redraw when it changes. */
val LocalIs24Hour = compositionLocalOf { false }

/**
 * A moment as date and time to the minute, with the date in [locale]'s own order and names and the time on a
 * 24 or 12 hour clock as [is24Hour] says.
 */
fun formatDateTimeMinute(ms: Long, locale: Locale, is24Hour: Boolean): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24Hour) "yMMMdHm" else "yMMMdhma")
    return SimpleDateFormat(pattern, locale).format(Date(ms))
}

/** A moment's time of day alone, in [locale] on a 24 or 12 hour clock as [is24Hour] says. */
fun formatTimeOfDay(ms: Long, locale: Locale, is24Hour: Boolean): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hma")
    return SimpleDateFormat(pattern, locale).format(Date(ms))
}

/** [formatDateTimeMinute] on the clock the app is showing now, for code outside a composition. */
fun formatDateTimeMinute(ms: Long, locale: Locale = Locale.getDefault()): String =
    formatDateTimeMinute(ms, locale, ClockFormatState.is24Hour())
