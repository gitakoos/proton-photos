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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.domain.usecase.ObservePlacesUseCase
import eu.akoos.photos.domain.usecase.PlaceCity
import eu.akoos.photos.domain.usecase.PlacesData
import eu.akoos.photos.presentation.search.SearchFilter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the hierarchical Places surfaces. Streams the account's photos grouped into countries and
 * cities from the shared [ObservePlacesUseCase] and feeds the one flow to both levels: the countries
 * grid reads [PlacesData.countries], and a country's cities grid filters [PlacesData.cities] by the
 * opened country code. Both lists arrive already sorted by photo count descending. Empty when signed
 * out or before any located photo resolves. The search filter is pure, run against the latest data.
 */
@HiltViewModel
class PlacesViewModel @Inject constructor(
    observePlaces: ObservePlacesUseCase,
) : ViewModel() {

    val places: StateFlow<PlacesData> = observePlaces()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            PlacesData(emptyList(), emptyList()),
        )

    /**
     * The [cities] whose name matches [query], case- and diacritic-insensitive so an ASCII query still
     * finds an accented city; a blank query returns them unchanged, in their count-descending order.
     */
    fun filterCities(cities: List<PlaceCity>, query: String): List<PlaceCity> {
        val q = SearchFilter.fold(query.trim())
        if (q.isEmpty()) return cities
        return cities.filter { SearchFilter.fold(it.city).contains(q) }
    }
}
