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

package eu.akoos.photos.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.gallery.AlbumsViewMode
import eu.akoos.photos.presentation.people.PeopleColumnChoices
import eu.akoos.photos.presentation.people.PeopleDisplay
import eu.akoos.photos.presentation.people.ownPeopleDisplayOf
import eu.akoos.photos.presentation.people.peopleDisplayOf
import eu.akoos.photos.presentation.settings.components.CollapsibleSection
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import eu.akoos.photos.presentation.settings.components.ToggleRow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The People page's display settings, reached from the page's overflow menu and from Settings >
 * Appearance: whether the page lays its people out as the Albums tab lays out its albums, and while it
 * does not, its own view and cards per row. Every value is a stored key the People page reads itself.
 */
@Composable
fun PeopleSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val follow by remember {
        ctx.settingsDataStore.data.map { it[SettingsKeys.PEOPLE_FOLLOW_ALBUMS] ?: true }
    }.collectAsState(initial = true)
    val own by remember {
        ctx.settingsDataStore.data.map(::ownPeopleDisplayOf)
    }.collectAsState(initial = PeopleDisplay(AlbumsViewMode.Default, PeopleColumnChoices.first()))

    SettingsSubPageScaffold(title = stringResource(R.string.gallery_category_people), onBack = onBack) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.people_follow_albums),
                description = stringResource(R.string.people_follow_albums_desc),
                checked = follow,
                onCheckedChange = { on ->
                    scope.launch {
                        ctx.settingsDataStore.edit { prefs ->
                            // The page's own layout starts from the one it was just following, so
                            // turning the switch off changes nothing on the page until a pick below.
                            if (!on) {
                                val shown = peopleDisplayOf(prefs)
                                if (prefs[SettingsKeys.PEOPLE_VIEW_MODE] == null) {
                                    prefs[SettingsKeys.PEOPLE_VIEW_MODE] = shown.viewMode.ordinal
                                }
                                if (prefs[SettingsKeys.PEOPLE_GRID_COLUMNS] == null) {
                                    prefs[SettingsKeys.PEOPLE_GRID_COLUMNS] = shown.columns
                                }
                            }
                            prefs[SettingsKeys.PEOPLE_FOLLOW_ALBUMS] = on
                        }
                    }
                },
            )
        }

        if (!follow) {
            Spacer(Modifier.height(20.dp))
            CollapsibleSection(label = stringResource(R.string.albums_view_heading)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        AlbumsViewMode.Grid to stringResource(R.string.albums_view_grid),
                        AlbumsViewMode.List to stringResource(R.string.albums_view_list),
                    ).forEach { (mode, label) ->
                        TimelinePill(
                            label = label,
                            selected = own.viewMode == mode,
                            onClick = {
                                scope.launch {
                                    ctx.settingsDataStore.edit { it[SettingsKeys.PEOPLE_VIEW_MODE] = mode.ordinal }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            // The size is there for the grid alone: a list row's face has one size.
            if (own.viewMode == AlbumsViewMode.Grid) {
                Spacer(Modifier.height(20.dp))
                CollapsibleSection(label = stringResource(R.string.albums_cover_size_heading)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PeopleColumnChoices.forEach { count ->
                            TimelinePill(
                                label = count.toString(),
                                selected = own.columns == count,
                                onClick = {
                                    scope.launch {
                                        ctx.settingsDataStore.edit { it[SettingsKeys.PEOPLE_GRID_COLUMNS] = count }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
