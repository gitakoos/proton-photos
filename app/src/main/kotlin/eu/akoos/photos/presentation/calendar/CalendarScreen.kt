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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.akoos.photos.presentation.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.calendar.components.MonthGrid
import eu.akoos.photos.presentation.memories.FloatingMemoriesHeader
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.PillBorder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Top-level Calendar view: a vertically scrolling, one-month-per-page list of [MonthGrid]s.
 * The floating header reuses the same back-pill recipe as the rest of the app; its search action
 * opens an overlay that jumps to a typed month or year. Tapping a populated day routes to the Day
 * Detail screen via [onDayClick].
 */
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    onDayClick: (date: String) -> Unit,
    onOpenMap: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    // Remember a target month for the stacked layout so the LazyColumn can scroll to it.
    // Cleared after the scroll is performed.
    var stackedTargetIndex by remember { mutableStateOf<Int?>(null) }
    val stackedListState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(stackedTargetIndex) {
        val idx = stackedTargetIndex ?: return@LaunchedEffect
        if (idx in state.months.indices) {
            // Jump straight to the month — an animated scroll across many months (years back) renders
            // every intervening page and feels laggy, so position instantly instead.
            stackedListState.scrollToItem(idx)
        }
        stackedTargetIndex = null
    }

    // System back unwinds the in-bar surfaces in order before letting the nav pop.
    BackHandler(enabled = state.isSearchActive) {
        viewModel.setSearchActive(false)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg0),
    ) {
        // ───────────── Main content area ─────────────
        // Reserve enough space at the top for the floating header. The header column
        // applies statusBarsPadding() on top of its 52dp tap-target row (40dp icon +
        // 6dp vertical padding ×2), so the on-screen header height is the actual
        // status-bar inset + 52dp. We resolve the inset at runtime instead of guessing
        // a single dp number that breaks on devices with taller cutouts/notches.
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val headerBaseHeight = 52.dp
        // The search is a floating overlay now, not an inline field, so the calendar keeps the
        // same top inset whether the search is open or not — it must not shift under the overlay.
        val contentTopPad = statusBarTop + headerBaseHeight
        // Each stacked month fills exactly the page below the header, so one month shows per page —
        // the next month sits right at the screen's bottom edge (off-screen), nothing bleeds in.
        val pageHeight = maxHeight - contentTopPad
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
            else -> {
                // Stacked vertical scroll — the legacy default. `contentTopPad` ensures
                // the first month header doesn't slip under the floating bar.
                LazyColumn(
                    state = stackedListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (state.isSearchActive && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                Modifier.blur(16.dp)
                            } else {
                                Modifier
                            },
                        ),
                    flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(stackedListState),
                    contentPadding = PaddingValues(top = contentTopPad),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(
                        items = state.months,
                        key = { bucket -> "${bucket.year}-${bucket.month}" },
                    ) { bucket ->
                        // Each month tile takes a full screen so day cells stay generously
                        // sized in both stacked and per-month modes. fillParentMaxHeight
                        // gives the inner expanded grid a concrete height to weight against.
                        // Empty months render as a blank grid (no caption) so the per-month
                        // height rhythm stays consistent while scrolling.
                        Box(modifier = Modifier.height(pageHeight)) {
                            MonthGrid(
                                bucket = bucket,
                                onDayClick = { day -> onDayClick(day.date) },
                                expanded = true,
                            )
                        }
                    }
                }

            }
        }

        // Search overlay — dims the calendar; typing a month or year lists the matching months
        // to jump to. Sits below the floating header so the header's close button stays on top.
        if (state.isSearchActive) {
            CalendarSearchOverlay(
                query = state.searchQuery,
                onQueryChange = viewModel::setSearchQuery,
                months = state.months,
                onMonthPick = { year, month ->
                    val idx = state.months.indexOfFirst { it.year == year && it.month == month }
                    if (idx >= 0) stackedTargetIndex = idx
                    viewModel.setSearchActive(false)
                },
                searchResults = state.searchResults,
                onDayClick = { day ->
                    viewModel.setSearchActive(false)
                    onDayClick(day.date)
                },
                onClose = { viewModel.setSearchActive(false) },
            )
        }

        // ───────────── Floating header ─────────────
        // The title pill floats over the calendar (drawn on top via zIndex) so its
        // view-switch menu grows downward over the content instead of pushing it down.
        // It carries its own statusBarsPadding, so it isn't wrapped in another inset.
        FloatingMemoriesHeader(
            title = stringResource(R.string.calendar_title),
            onBack = onBack,
            trailing = {
                // Single search action — flips to Close when the search panel is open. The
                // panel also surfaces the month jump, so there's no separate jump or view toggle.
                IconBtn(
                    icon = if (state.isSearchActive) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = stringResource(R.string.calendar_search_cd),
                    onClick = { viewModel.setSearchActive(!state.isSearchActive) },
                    tint = colors.fgPrimary,
                )
            },
            menuItems = listOf(stringResource(R.string.map_title) to onOpenMap),
        )

    }
}

/**
 * Tiny circular icon button reused across the top bar — matches the back-pill size + tap
 * target so the row feels evenly weighted.
 */
@Composable
private fun IconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color,
) {
    val colors = AppColors.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.pillBg, CircleShape)
            .border(0.5.dp, colors.pillBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}

