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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.akoos.photos.presentation.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.domain.usecase.PlaceCity
import eu.akoos.photos.presentation.common.AppSearchField
import eu.akoos.photos.presentation.search.SearchFilter
import eu.akoos.photos.presentation.theme.AppColors

/** Case- and diacritic-insensitive name filter over the account's cities, shared by the Places screen
 *  and the vector map so both search the same way. */
internal fun filterPlaceCities(cities: List<PlaceCity>, query: String): List<PlaceCity> {
    val q = SearchFilter.fold(query.trim())
    if (q.isEmpty()) return cities
    return cities.filter { SearchFilter.fold(it.city).contains(q) }
}

/**
 * A tall bottom drawer that searches every city the account has photos in: a name field filters the
 * cities, each result shows "City, Country" with its photo count, and picking one returns that
 * [PlaceCity]. Shared so the Places screen and the vector map open the identical search.
 */
@Composable
internal fun PlaceSearchSheet(
    cities: List<PlaceCity>,
    onPick: (PlaceCity) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val filtered = remember(cities, query) { filterPlaceCities(cities, query) }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
        containerColor = colors.sheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 16.dp),
        ) {
            AppSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.map_search_places),
                modifier = Modifier.fillMaxWidth(),
                // Match the map's floating search bar (18dp icon, 14sp text) so tapping it reads as the
                // same bar rising into the drawer rather than a heavier field appearing.
                iconSize = 18.dp,
                textSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Text(stringResource(R.string.map_search_no_places), color = colors.fgMute, fontSize = 14.sp)
            } else {
                // Cities are grouped by country: a thin line sits between one country's cities and the
                // next, without a country heading, so the list reads as tidy blocks per place.
                val grouped = remember(filtered) {
                    filtered.sortedWith(compareBy({ it.countryName }, { it.city }))
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(grouped, key = { _, c -> c.city + " " + c.countryCode }) { index, city ->
                        if (index > 0 && grouped[index - 1].countryCode != city.countryCode) {
                            HorizontalDivider(color = colors.line2, thickness = 0.5.dp)
                        }
                        PlaceSearchRow(city = city, onClick = { onPick(city) })
                    }
                }
            }
        }
    }
}

/** One city row in the Places search: its "City, Country" label above the photo count. */
@Composable
private fun PlaceSearchRow(city: PlaceCity, onClick: () -> Unit) {
    val colors = AppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = "${city.city}, ${city.countryName}",
            color = colors.fgPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = pluralStringResource(R.plurals.count_photos_plural, city.count, city.count),
            color = colors.fgMute,
            fontSize = 12.sp,
        )
    }
}
