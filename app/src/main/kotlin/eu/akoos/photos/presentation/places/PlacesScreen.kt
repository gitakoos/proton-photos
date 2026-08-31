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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.usecase.PlaceCity
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.theme.AppColors

/**
 * Top of the hierarchical Places browser: a two-column grid of one album-style [PlaceCard] per country
 * the account has located photos in, busiest first, mirroring the Memories category page shell. A tap
 * opens that country's cities via [onCountryClick]. The floating header carries a search that lists
 * every city and jumps straight to its photos through [onCityClick]. No map. Empty when signed out or
 * before any located photo resolves, matching the People screen's empty gate.
 */
@Composable
fun PlacesScreen(
    onCountryClick: (countryCode: String) -> Unit,
    onCityClick: (latitude: Double, longitude: Double) -> Unit,
    onBack: () -> Unit,
    viewModel: PlacesViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val data by viewModel.places.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        if (data.countries.isEmpty()) {
            Text(
                text = stringResource(R.string.map_search_no_places),
                color = colors.fgMute,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
            )
        } else {
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
                items(items = data.countries, key = { it.countryCode }) { country ->
                    PlaceCard(
                        coverItem = country.cover,
                        title = country.countryName,
                        count = country.count,
                        onClick = { onCountryClick(country.countryCode) },
                    )
                }
            }
        }

        FloatingHeader(
            title = stringResource(R.string.places_title),
            onBack = onBack,
            trailing = {
                if (data.cities.isNotEmpty()) {
                    IconBubble(
                        icon = Icons.Default.Search,
                        contentDescription = stringResource(R.string.map_search_places),
                        onClick = { showSearch = true },
                        diameter = 40.dp,
                        iconSize = 18.dp,
                        background = colors.pillBg,
                        borderColor = colors.pillBorder,
                        tint = colors.fgPrimary,
                    )
                }
            },
        )

        if (showSearch) {
            PlaceSearchSheet(
                cities = data.cities,
                onPick = { showSearch = false; onCityClick(it.latitude, it.longitude) },
                onDismiss = { showSearch = false },
            )
        }
    }
}

// The Places search drawer + its row now live in PlaceSearch.kt, shared with the vector map.
