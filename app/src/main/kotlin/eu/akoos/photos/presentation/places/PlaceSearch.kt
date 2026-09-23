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
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.util.appLocale
import eu.akoos.photos.util.countryName
import java.text.Collator

/**
 * Case- and diacritic-insensitive filter over the account's cities, shared by the Places screen and the
 * vector map so both search the same way. A city matches on its own name or on its country's, either the
 * heading [countryLabelOf] gives it in the app's language or the name the geocode stored, so typing a
 * country lists every city of it under that country's heading.
 */
internal fun filterPlaceCities(
    cities: List<PlaceCity>,
    query: String,
    countryLabelOf: (PlaceCity) -> String = { it.countryName },
): List<PlaceCity> {
    val q = SearchFilter.fold(query.trim())
    if (q.isEmpty()) return cities
    return cities.filter {
        SearchFilter.fold(it.city).contains(q) ||
            SearchFilter.fold(countryLabelOf(it)).contains(q) ||
            SearchFilter.fold(it.countryName).contains(q)
    }
}

/** One line of the grouped place search: a country's heading, or one of its cities. */
internal sealed interface PlaceSearchEntry {
    val key: String

    data class Country(override val key: String, val label: String) : PlaceSearchEntry

    data class City(override val key: String, val city: PlaceCity) : PlaceSearchEntry
}

/**
 * Lays [cities] out under one heading per country: countries in [order] of the heading
 * [countryLabelOf] gives them, cities in [order] of name within each, so the list reads alphabetically
 * in the app's language. A block is one country code under one heading, which keeps a country whole
 * even when its cities carry stored names from different languages. Pure.
 */
internal fun groupPlaceSearch(
    cities: List<PlaceCity>,
    order: Comparator<in String>,
    countryLabelOf: (PlaceCity) -> String,
): List<PlaceSearchEntry> {
    val labelled = cities.map { it to countryLabelOf(it) }.sortedWith(
        compareBy<Pair<PlaceCity, String>, String>(order) { it.second }
            .thenBy { it.first.countryCode }
            .thenBy(order) { it.first.city },
    )
    val entries = ArrayList<PlaceSearchEntry>(labelled.size + 8)
    var block: Pair<String, String>? = null
    for ((city, label) in labelled) {
        val next = city.countryCode to label
        if (next != block) {
            entries += PlaceSearchEntry.Country(key = "country ${city.countryCode} $label", label = label)
            block = next
        }
        entries += PlaceSearchEntry.City(key = city.city + " " + city.countryCode, city = city)
    }
    return entries
}

/**
 * A tall bottom drawer that searches every city the account has photos in: a name field filters the
 * cities by city or country name, the cities sit under a heading per country, each result shows the
 * city with its photo count, and picking one returns that [PlaceCity]. Shared so the Places screen and
 * the vector map open the identical search.
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
    // Country names come from the ISO code in the language this drawer's own strings resolve in, so the
    // headings match the rest of it and a country is found by the name the heading shows.
    val locale = appLocale()
    val countryLabelOf: (PlaceCity) -> String = remember(locale) {
        { city -> countryName(city.countryCode, locale, city.countryName) }
    }
    val filtered = remember(cities, query, countryLabelOf) { filterPlaceCities(cities, query, countryLabelOf) }

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
                // Cities are grouped by country, each block under its country's name with a thin line
                // closing the block above.
                val entries = remember(filtered, locale, countryLabelOf) {
                    groupPlaceSearch(filtered, Collator.getInstance(locale), countryLabelOf)
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(
                        entries,
                        key = { _, entry -> entry.key },
                        contentType = { _, entry -> entry is PlaceSearchEntry.Country },
                    ) { index, entry ->
                        when (entry) {
                            is PlaceSearchEntry.Country -> PlaceSearchCountryHeader(entry.label, divided = index > 0)
                            is PlaceSearchEntry.City -> PlaceSearchRow(city = entry.city, onClick = { onPick(entry.city) })
                        }
                    }
                }
            }
        }
    }
}

/** A country's heading in the Places search: the thin line closing the block above (none over the
 *  first), then the country name as the app's section label. */
@Composable
private fun PlaceSearchCountryHeader(label: String, divided: Boolean) {
    val colors = AppColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        if (divided) HorizontalDivider(color = colors.line2, thickness = 0.5.dp)
        Spacer(Modifier.height(if (divided) 16.dp else 4.dp))
        if (label.isNotBlank()) SectionLabel(label)
    }
}

/** One city row in the Places search: its name above the photo count. The country is the heading the
 *  row sits under, so the row does not repeat it. */
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
            text = city.city,
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
