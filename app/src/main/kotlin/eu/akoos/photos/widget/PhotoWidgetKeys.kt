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

    const val URI_SEPARATOR = "|"
}
