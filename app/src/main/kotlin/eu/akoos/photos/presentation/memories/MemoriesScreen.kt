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

package eu.akoos.photos.presentation.memories

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.MIN_FACES_TO_SHOW_PERSON
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.ShimmerBox
import eu.akoos.photos.presentation.common.ShimmerTextLine
import eu.akoos.photos.presentation.gallery.PersonCard
import eu.akoos.photos.presentation.places.PlaceCard
import eu.akoos.photos.presentation.theme.AppColors
import java.util.Calendar

/** The two memory groupings, each with its own preview section and "see all" sub-page. */
enum class MemoryCategory { ON_THIS_DAY, SEASONS }

/**
 * Top-level Memories view. Floating pills (back button + title) over a LazyColumn that stacks one
 * preview section per non-empty category: a tappable header row plus a fixed 2×2 grid of four cards
 * picked at random per open. Tapping a header opens the category's full sub-page via [onSeeAll];
 * tapping a card routes its photos into the shared viewer via [onPhotoClick]. With nothing to show,
 * a neutral empty state stands in.
 */
@Composable
fun MemoriesScreen(
    onBack: () -> Unit,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    onSeeAll: (MemoryCategory) -> Unit = {},
    onPersonClick: (Long) -> Unit = {},
    onSeeAllPeople: () -> Unit = {},
    onPlaceClick: (Double, Double) -> Unit = { _, _ -> },
    onSeeAllPlaces: () -> Unit = {},
    viewModel: MemoriesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg0),
    ) {
        // Reserve room for the floating header — the resolved status-bar inset plus the pill-row
        // height, with extra breathing space so the first row sits comfortably below the pills
        // instead of crowding them.
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val contentTopPad = statusBarTop + 72.dp

        // Only people in at least two photos reach the preview, so a stray detection cannot open its own
        // section; the empty state keys off the same set. The preview shows the people you have NAMED,
        // falling back to the clusters worth naming before any are named so the first is still reachable.
        val peopleNamed = state.people.filter { !it.displayName.isNullOrBlank() }
        val peopleToName = state.people.filter {
            it.displayName.isNullOrBlank() && it.faceCount >= MIN_FACES_TO_SHOW_PERSON
        }
        val peopleShown = if (peopleNamed.isNotEmpty()) peopleNamed else peopleToName
        val isEmpty = state.onThisDay.isEmpty() && state.seasons.isEmpty() &&
            peopleShown.isEmpty() && state.places.isEmpty()
        // Loading / empty / content phase, cross-faded so the neutral empty state never flashes before
        // the first grouped library emission arrives.
        val phase = when {
            state.isLoading -> 0
            isEmpty -> 1
            else -> 2
        }
        Crossfade(targetState = phase, label = "memoriesContent", modifier = Modifier.fillMaxSize()) { p ->
        when (p) {
        0 -> MemoriesSkeleton(contentTopPad = contentTopPad)
        1 -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = contentTopPad),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.gallery_empty_title),
                    color = colors.fgMute,
                    fontSize = 14.sp,
                )
            }
        else -> {
            val now = remember { Calendar.getInstance().get(Calendar.YEAR) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 14.dp, end = 14.dp, top = contentTopPad, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (peopleShown.isNotEmpty()) {
                    item(key = "people_section") {
                        // Same shape as Seasons: a tappable header with a chevron opening the full
                        // People sub-page, over a preview of the first few most-photographed people.
                        SectionHeaderRow(
                            title = stringResource(R.string.gallery_category_people),
                            onClick = onSeeAllPeople,
                        )
                        // A single horizontally scrolling row of person cards sized to match the
                        // Seasons cards (132x168), so People read as the same album-style tile rather
                        // than a taller, wider grid. The header chevron opens the full People page.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            peopleShown.take(12).forEach { person ->
                                Box(Modifier.width(132.dp)) {
                                    PersonCard(
                                        person = person,
                                        onClick = { onPersonClick(person.personId) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (state.places.isNotEmpty()) {
                    item(key = "places_section") {
                        // Same shape as People: a tappable header with a chevron opening the full
                        // Places browser, over a horizontally scrolling row of the busiest cities.
                        SectionHeaderRow(
                            title = stringResource(R.string.places_title),
                            onClick = onSeeAllPlaces,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            state.places.forEach { city ->
                                Box(Modifier.width(132.dp)) {
                                    PlaceCard(
                                        coverItem = city.cover,
                                        title = city.city,
                                        count = city.count,
                                        onClick = { onPlaceClick(city.latitude, city.longitude) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (state.onThisDay.isNotEmpty()) {
                    item(key = "otd_section") {
                        SectionHeaderRow(
                            title = stringResource(R.string.gallery_on_this_day),
                            onClick = { onSeeAll(MemoryCategory.ON_THIS_DAY) },
                        )
                        // Four random entries in a fixed 2×2 grid (the sub-page lists every entry),
                        // keyed on the set of years (not the list instance) so the shuffle stays stable
                        // across decrypt-driven re-emits, then resolved against the latest state each
                        // recomposition so covers still refresh without the preview jumping.
                        val pickedYears = remember(state.onThisDay.map { it.first }) {
                            state.onThisDay.map { it.first }.shuffled().take(4)
                        }
                        val previewOnThisDay = pickedYears.mapNotNull { yr ->
                            state.onThisDay.firstOrNull { it.first == yr }
                        }
                        PreviewGrid(previewOnThisDay) { (year, items) ->
                            OnThisDayCard(
                                coverItem = items.first(),
                                yearsAgo = (now - year).coerceAtLeast(1),
                                count = items.size,
                                onClick = { onPhotoClick(items, 0) },
                            )
                        }
                    }
                }
                if (state.seasons.isNotEmpty()) {
                    item(key = "seasons_section") {
                        SectionHeaderRow(
                            title = stringResource(R.string.memories_seasons),
                            onClick = { onSeeAll(MemoryCategory.SEASONS) },
                        )
                        val pickedSeasons = remember(state.seasons.map { it.title }) {
                            state.seasons.map { it.title }.shuffled().take(4)
                        }
                        val previewSeasons = pickedSeasons.mapNotNull { title ->
                            state.seasons.firstOrNull { it.title == title }
                        }
                        PreviewGrid(previewSeasons) { bucket ->
                            SeasonCard(
                                coverItem = bucket.cover,
                                title = bucket.title,
                                count = bucket.items.size,
                                onClick = { onPhotoClick(bucket.items, 0) },
                            )
                        }
                    }
                }
            }
        }
        }
        }

        // Floating pills — back button + title, layered above the scroll area.
        FloatingHeader(
            title = stringResource(R.string.memories_title),
            onBack = onBack,
        )
    }
}

/**
 * Loading stand-in for the Collection preview: a couple of section headers, each over a 2×2 grid of
 * card placeholders sized to the 132×168 memory cards, so the swap to real content does not jump.
 */
@Composable
private fun MemoriesSkeleton(contentTopPad: Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 14.dp, end = 14.dp, top = contentTopPad),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(2) {
            ShimmerTextLine(
                widthFraction = 0.5f,
                height = 22.dp,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        repeat(2) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                ShimmerBox(
                                    modifier = Modifier.size(width = 132.dp, height = 168.dp),
                                    cornerRadius = 16.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Non-scrolling 2×2 preview: a [Column] of two [Row]s, each holding two weighted cells so the
 * columns share the row width. With an odd count the trailing cell is an empty spacer.
 */
@Composable
private fun <T> PreviewGrid(items: List<T>, card: @Composable (T) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { item ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        card(item)
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Tappable section header — the group title with a trailing chevron. The whole row opens the
 * category's "see all" sub-page. Title typography matches the app's other section headers.
 */
@Composable
private fun SectionHeaderRow(title: String, onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = colors.fgPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.44).sp,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.fgMute,
            modifier = Modifier.size(24.dp),
        )
    }
}
