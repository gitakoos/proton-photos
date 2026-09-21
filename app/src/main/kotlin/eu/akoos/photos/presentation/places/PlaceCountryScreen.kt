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

package eu.akoos.photos.presentation.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.theme.AppColors

/**
 * The middle level of the Places browser: a two-column grid of one [PlaceCard] per city in the opened
 * country, busiest first, filtered from the shared [PlacesViewModel] by [countryCode] so it stays in
 * step with the countries grid. Same shell as [PlacesScreen]; the header shows the country's display
 * name. A tap opens that city's photos via [onCityClick]. No map.
 */
@Composable
fun PlaceCountryScreen(
    countryCode: String,
    onCityClick: (latitude: Double, longitude: Double) -> Unit,
    onBack: () -> Unit,
    viewModel: PlacesViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val data by viewModel.places.collectAsStateWithLifecycle()
    val cities = data.cities.filter { it.countryCode == countryCode }
    val title = data.countries.firstOrNull { it.countryCode == countryCode }?.countryName
        ?: cities.firstOrNull()?.countryName
        ?: countryCode

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = floatingHeaderContentTopPadding(),
                bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items = cities, key = { it.city + " " + it.countryCode }) { city ->
                PlaceCard(
                    coverItem = city.cover,
                    title = city.city,
                    count = city.count,
                    onClick = { onCityClick(city.latitude, city.longitude) },
                )
            }
        }

        FloatingHeader(
            title = title,
            onBack = onBack,
        )
    }
}
