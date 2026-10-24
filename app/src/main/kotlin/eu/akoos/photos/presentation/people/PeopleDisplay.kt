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

package eu.akoos.photos.presentation.people

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.data.preferences.AlbumGridPrefsBoot
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.gallery.AlbumsViewMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How the People page lays out its people: [columns] cards per row in a grid, or one row each in a list. */
data class PeopleDisplay(val viewMode: AlbumsViewMode, val columns: Int)

/** The cards-per-row choices the People page offers, the same as the Albums tab's cover sizes. */
internal val PeopleColumnChoices = listOf(2, 3, 4)

private fun columnsOf(stored: Int?): Int = stored?.takeIf { it in PeopleColumnChoices } ?: 2

/** The People page's layout from the stored settings: the Albums tab's while it follows that, else its own. */
internal fun peopleDisplayOf(prefs: Preferences): PeopleDisplay =
    if (prefs[SettingsKeys.PEOPLE_FOLLOW_ALBUMS] ?: true) {
        PeopleDisplay(
            AlbumsViewMode.fromOrdinal(prefs[SettingsKeys.ALBUMS_VIEW_MODE]),
            columnsOf(prefs[SettingsKeys.ALBUM_GRID_COLUMNS]),
        )
    } else {
        ownPeopleDisplayOf(prefs)
    }

/** The People page's own layout, the one it takes while it does not follow the Albums tab. */
internal fun ownPeopleDisplayOf(prefs: Preferences): PeopleDisplay =
    PeopleDisplay(
        AlbumsViewMode.fromOrdinal(prefs[SettingsKeys.PEOPLE_VIEW_MODE]),
        columnsOf(prefs[SettingsKeys.PEOPLE_GRID_COLUMNS]),
    )

/**
 * Live People page layout. Opens on the Albums tab's stored layout, which the page follows by default,
 * so the common case lands in its final shape on the first frame.
 */
@Composable
fun rememberPeopleDisplay(): PeopleDisplay {
    val context = LocalContext.current
    val initial = remember {
        PeopleDisplay(
            AlbumsViewMode.fromOrdinal(AlbumGridPrefsBoot.readViewMode(context)),
            columnsOf(AlbumGridPrefsBoot.read(context)),
        )
    }
    val display by remember {
        context.settingsDataStore.data.map(::peopleDisplayOf).distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = initial)
    return display
}
