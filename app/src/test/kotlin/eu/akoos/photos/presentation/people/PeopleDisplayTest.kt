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

import androidx.datastore.preferences.core.mutablePreferencesOf
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.presentation.gallery.AlbumsViewMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** The People page's layout: the Albums tab's by default, its own once the switch is off. */
class PeopleDisplayTest {

    @Test
    fun `nothing stored gives a two-column grid`() {
        assertEquals(PeopleDisplay(AlbumsViewMode.Grid, 2), peopleDisplayOf(mutablePreferencesOf()))
    }

    @Test
    fun `by default the page follows the Albums tab's view and size`() {
        val prefs = mutablePreferencesOf(
            SettingsKeys.ALBUMS_VIEW_MODE to AlbumsViewMode.List.ordinal,
            SettingsKeys.ALBUM_GRID_COLUMNS to 4,
            SettingsKeys.PEOPLE_VIEW_MODE to AlbumsViewMode.Grid.ordinal,
            SettingsKeys.PEOPLE_GRID_COLUMNS to 3,
        )
        assertEquals(PeopleDisplay(AlbumsViewMode.List, 4), peopleDisplayOf(prefs))
    }

    @Test
    fun `with the switch off the page keeps its own view and size`() {
        val prefs = mutablePreferencesOf(
            SettingsKeys.PEOPLE_FOLLOW_ALBUMS to false,
            SettingsKeys.ALBUMS_VIEW_MODE to AlbumsViewMode.List.ordinal,
            SettingsKeys.ALBUM_GRID_COLUMNS to 4,
            SettingsKeys.PEOPLE_GRID_COLUMNS to 3,
        )
        assertEquals(PeopleDisplay(AlbumsViewMode.Grid, 3), peopleDisplayOf(prefs))
        assertEquals(peopleDisplayOf(prefs), ownPeopleDisplayOf(prefs))
    }

    @Test
    fun `a size outside the offered choices falls back to two`() {
        val prefs = mutablePreferencesOf(SettingsKeys.ALBUM_GRID_COLUMNS to 7)
        assertEquals(2, peopleDisplayOf(prefs).columns)
    }
}
