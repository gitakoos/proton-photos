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

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** DataStore Preferences keys shared across the widget components. */
object PhotoWidgetKeys {
    val MODE              = stringPreferencesKey("mode")
    val SELECTED_URIS     = stringPreferencesKey("selected_uris")   // pipe-separated
    val ALBUM_NAME        = stringPreferencesKey("album_name")
    val INTERVAL_MINUTES  = intPreferencesKey("interval_minutes")
    val CURRENT_INDEX     = intPreferencesKey("current_index")
    val CACHED_BITMAP_PATH = stringPreferencesKey("cached_bitmap_path")
    val CURRENT_URI       = stringPreferencesKey("current_uri")

    /**
     * Pipe-separated list of Drive linkIds for [WidgetMode.CLOUD_SELECTED]. Each
     * id resolves to a decrypted thumbnail at `cacheDir/thumbnails/thumb_<linkId>.jpg`
     * inside the app sandbox.
     */
    val SELECTED_LINK_IDS = stringPreferencesKey("selected_link_ids")

    const val URI_SEPARATOR = "|"
}
