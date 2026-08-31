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

package eu.akoos.photos.presentation.map.vector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.domain.usecase.ObservePlacesUseCase
import eu.akoos.photos.domain.usecase.PlaceCity
import eu.akoos.photos.domain.usecase.PlaceCountry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Feeds the vector globe only what it needs to show WHERE THE USER HAS BEEN: [countries] to highlight and
 * outline, and [cities] (name, coordinate, photo count, cover photo) so each place the account has photos
 * in gets a thumbnail pin and a label. Nothing about the rest of the world's places is loaded, which is
 * what keeps the globe fast and uncluttered. Both empty when signed out.
 */
@HiltViewModel
class CustomMapViewModel @Inject constructor(
    observePlaces: ObservePlacesUseCase,
) : ViewModel() {

    private val places = observePlaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val countries: StateFlow<List<PlaceCountry>> = places
        .map { it?.countries.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val cities: StateFlow<List<PlaceCity>> = places
        .map { it?.cities.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** True once the places query has produced its first result, so the globe can reveal itself already
     *  centred on the busiest city rather than visibly rotating there from a default view on open. */
    val loaded: StateFlow<Boolean> = places
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)
}
