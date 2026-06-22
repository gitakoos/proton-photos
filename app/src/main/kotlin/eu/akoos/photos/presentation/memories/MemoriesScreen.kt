/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
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

        val isEmpty = state.onThisDay.isEmpty() && state.seasons.isEmpty()
        if (isEmpty) {
            Box(
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
        } else {
            val now = remember { Calendar.getInstance().get(Calendar.YEAR) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 14.dp, end = 14.dp, top = contentTopPad, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

        // Floating pills — back button + title, layered above the scroll area.
        FloatingMemoriesHeader(
            title = stringResource(R.string.memories_title),
            onBack = onBack,
        )
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
 * Floating back-bar shared by the memories screens: a circular pill back button and a pill-shaped
 * title chip at the top-start, floating over the scrolling content rather than a full-width bar.
 */
@Composable
internal fun FloatingMemoriesHeader(
    title: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    menuItems: List<Pair<String, () -> Unit>>? = null,
) {
    val colors = AppColors.current
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 12.dp, top = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(PillBg, CircleShape)
                .border(0.5.dp, PillBorder, CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.onboarding_back),
                tint = colors.fgPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        val hasMenu = !menuItems.isNullOrEmpty()
        // The title pill itself stretches open into the category list (animated height) instead of
        // popping a separate menu, so switching reads as the same pill growing downward.
        val menuShape = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier
                .clip(menuShape)
                .background(PillBg, menuShape)
                .border(0.5.dp, PillBorder, menuShape)
                .animateContentSize(),
        ) {
            Row(
                modifier = Modifier
                    .then(if (hasMenu) Modifier.clickable { menuExpanded = !menuExpanded } else Modifier)
                    .padding(start = 14.dp, end = if (hasMenu) 8.dp else 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    color = colors.fgPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (hasMenu) {
                    Icon(
                        if (menuExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = colors.fgPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (hasMenu && menuExpanded) {
                menuItems?.forEach { (label, action) ->
                    Text(
                        text = label,
                        color = colors.fgPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { menuExpanded = false; action() }
                            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
                    )
                }
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.weight(1f))
            trailing()
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
