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

package eu.akoos.photos.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Text
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import eu.akoos.photos.presentation.gallery.CategoryRail
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.presentation.gallery.ContentFilterSheet
import eu.akoos.photos.presentation.gallery.FilterChip
import eu.akoos.photos.presentation.gallery.GalleryFilter
import eu.akoos.photos.presentation.gallery.MediaType
import eu.akoos.photos.presentation.gallery.SyncStatusFilter
import eu.akoos.photos.presentation.search.components.JumpToMonthGridSection
import eu.akoos.photos.presentation.search.components.OnThisDayRow
import eu.akoos.photos.presentation.search.components.RecentRow
import eu.akoos.photos.presentation.search.components.MonthBucket
import eu.akoos.photos.presentation.search.components.buildMonthBuckets
import eu.akoos.photos.presentation.gallery.TimelineScrubber
import eu.akoos.photos.presentation.gallery.TimelineGrouping
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.util.computeOnThisDay
import java.text.DateFormatSymbols
import java.util.Calendar

// Shared rounded shape for the filter rail's pill controls. A real corner radius —
// rather than a full-capsule RoundedCornerShape(50) — keeps the hairline border crisp
// at the chip ends instead of breaking up into a faint, fragmented outline. Matches the
// filter-sheet chip radius so the two surfaces read as the same control family.
private val chipShape = RoundedCornerShape(10.dp)

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onPhotoClick: (List<GalleryItem>, Int) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val filter by vm.contentFilter.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategory.collectAsStateWithLifecycle()
    val allItems by vm.allItems.collectAsStateWithLifecycle()

    var showFilterSheet by remember { mutableStateOf(false) }
    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val resultsGridState = rememberLazyGridState()
    // Hide the secondary filter rows once results are scrolled so browsing reclaims that
    // vertical space; they slide back at the top. The search field + title stay put.
    val filterRowsVisible by remember { derivedStateOf { resultsGridState.firstVisibleItemIndex == 0 } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg0)
            .statusBarsPadding(),
    ) {
        // Top bar: back arrow + screen title.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.pillBg, CircleShape)
                    .border(0.5.dp, colors.pillBorder, CircleShape)
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
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.search_title),
                color = colors.fgPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Slim single-line search field (a plain OutlinedTextField sits at ~56dp and reads as
            // two rows tall). Custom row keeps it compact and matches the filter button's height.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.chipUnselectedBg)
                    .border(
                        1.dp,
                        if (query.isNotEmpty()) colors.accent else colors.line,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = colors.fgDim,
                    modifier = Modifier.size(20.dp),
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search_placeholder),
                            color = colors.fgMute,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        singleLine = true,
                        textStyle = TextStyle(color = colors.fgPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { vm.setQuery("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_clear_search),
                            tint = colors.fgDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            // Filter button beside the search field — opens the sheet (sync status). Accent
            // outline + tint when a sheet filter is active, so it reads as "filters applied".
            val sheetFilterActive = filter.syncStatus != SyncStatusFilter.All
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (sheetFilterActive) colors.accent.copy(alpha = 0.15f) else colors.chipUnselectedBg)
                    .border(1.dp, if (sheetFilterActive) colors.accent else colors.line, RoundedCornerShape(14.dp))
                    .clickable { showFilterSheet = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = stringResource(R.string.filter_title),
                    tint = if (sheetFilterActive) colors.accent else colors.fgDim,
                )
            }
        }

        AnimatedVisibility(visible = filterRowsVisible) {
            Column {
                Spacer(modifier = Modifier.height(10.dp))

                // Tap-to-filter category chips (same row as the timeline). Tapping a category
                // narrows the results immediately, without opening the filter sheet.
                CategoryRail(
                    selectedFilter = selectedCategory,
                    onFilterSelected = vm::onCategorySelected,
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        val isIdle = query.isBlank() && filter == ContentFilter() && selectedCategory == GalleryFilter.All
        if (results.isEmpty() && isIdle) {
            // Idle empty state — surface "On this day" memories + a month-jump grid
            // so the page is useful without typing anything. Both sections live inside
            // one LazyColumn so they scroll as a unit and benefit from item-level
            // recycling when there are many month buckets.
            // Heavy on a large library — compute off the main thread so the idle page renders
            // immediately and the sections fill in a moment later, instead of blocking the first
            // frame on a full-library walk.
            // Both producers assign `value` below; @Suppress silences a lint false-positive it
            // raises on the `value = withContext { … }` form.
            @Suppress("ProduceStateDoesNotAssignValue")
            val onThisDay by produceState(emptyList<Pair<Int, List<GalleryItem>>>(), allItems) {
                value = withContext(Dispatchers.Default) { computeOnThisDay(allItems) }
            }
            @Suppress("ProduceStateDoesNotAssignValue")
            val monthBuckets by produceState(emptyList<MonthBucket>(), allItems) {
                value = withContext(Dispatchers.Default) { buildMonthBuckets(allItems) }
            }
            val recent = remember(allItems) { allItems.take(6) }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (recent.isNotEmpty()) {
                    item(key = "recent_section") {
                        RecentRow(
                            items = recent,
                            onPhotoClick = onPhotoClick,
                        )
                    }
                }
                if (onThisDay.isNotEmpty()) {
                    item(key = "on_this_day_section") {
                        OnThisDayRow(
                            yearGroups = onThisDay,
                            onPhotoClick = onPhotoClick,
                        )
                    }
                }
                if (monthBuckets.isNotEmpty()) {
                    item(key = "jump_to_month_section") {
                        JumpToMonthGridSection(
                            buckets = monthBuckets,
                            onMonthClick = { year, month ->
                                vm.setContentFilter(
                                    ContentFilter(year = year, month = month),
                                )
                            },
                        )
                    }
                }
                // True empty library — show the legacy hint so the user understands
                // why the page is otherwise blank. The "type a name" copy still fits
                // the active-filter-but-no-results path below.
                if (onThisDay.isEmpty() && monthBuckets.isEmpty()) {
                    item(key = "empty_hint") {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.search_empty_idle),
                                color = colors.fgMute,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        } else if (results.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.search_empty_no_results),
                    color = colors.fgMute,
                    fontSize = 14.sp,
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = resultsGridState,
                    contentPadding = PaddingValues(
                        start = 6.dp, end = 6.dp, top = 4.dp, bottom = 12.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                ) {
                    itemsIndexed(results, key = { _, it -> keyOf(it) }) { idx, item ->
                        val inputs = photoCellInputsFor(item)
                        PhotoCell(
                            imageData = inputs.imageData,
                            stableKey = inputs.stableKey,
                            isVideo = inputs.isVideo,
                            isPlaceholder = inputs.isPlaceholder,
                            showCloudBadge = inputs.showCloudBadge,
                            showSyncedBadge = inputs.showSyncedBadge,
                            isFavorite = inputs.isFavorite,
                            typeBadgeRes = inputs.typeBadgeRes,
                            typeBadgeCdRes = inputs.typeBadgeCdRes,
                            onClick = { onPhotoClick(results, idx) },
                        )
                    }
                }
                // Fast-scroll scrubber over the results. Search results are a flat, date-sorted grid,
                // so the month tooltip tracks the scroll position directly.
                TimelineScrubber(
                    gridState = resultsGridState,
                    items = results,
                    grouping = TimelineGrouping.Month,
                    topPadding = 8.dp,
                    bottomPadding = 24.dp,
                    keyOf = { keyOf(it) },
                )
            }
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = filterSheetState,
            containerColor = colors.cardBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            ContentFilterSheet(
                currentFilter = filter,
                currentCategory = selectedCategory,
                onApply = { vm.setContentFilter(it) },
                onCategorySelected = vm::onCategorySelected,
                onDismiss = { showFilterSheet = false },
                // Categories + media type are inline now (chips above); the sheet keeps the
                // sync-status + precise date pickers only.
                showCategorySection = false,
                showMediaTypeSection = false,
            )
        }
    }
}

private fun keyOf(item: GalleryItem): String = when (item) {
    is GalleryItem.LocalOnly -> "L:" + item.local.uri
    is GalleryItem.Synced    -> "S:" + item.local.uri
    is GalleryItem.CloudOnly -> "C:" + item.cloud.linkId
}
