/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.widget

import androidx.annotation.StringRes
import eu.akoos.photos.R

/** Display modes for the home-screen photo widget. */
enum class WidgetMode {
    /** A random photo picked from the full local library each cycle. */
    ALL_PHOTOS,

    /** Cycles (in order) through a fixed set of user-selected device photos. */
    SELECTED,

    /** Cycles (in order) through the photos in a chosen local album. */
    ALBUM,

    /**
     * Cycles through a fixed set of user-selected CLOUD photos. The widget pulls
     * thumbnails from the app's encrypted on-disk thumbnail cache so the source
     * material never appears in MediaStore, the Android system photo picker, or
     * any other app's view. Only the rendered bitmap (decoded, scaled to 480 px)
     * is handed to the launcher process for display.
     */
    CLOUD_SELECTED,
}

/** Built-in interval options the user can choose in the config screen.
 *  Labels are localized via [labelRes] — `stringResource(interval.labelRes)` at call sites. */
enum class WidgetInterval(val minutes: Int, @StringRes val labelRes: Int) {
    THIRTY_MIN(30, R.string.widget_interval_30min),
    ONE_HOUR(60, R.string.widget_interval_1hr),
    THREE_HOURS(180, R.string.widget_interval_3hr),
    EIGHT_HOURS(480, R.string.widget_interval_8hr),
    ONE_DAY(1440, R.string.widget_interval_1day),
}
