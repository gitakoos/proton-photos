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

package eu.akoos.photos.presentation.search

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Text
import eu.akoos.photos.presentation.gallery.PersonTile
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.gallery.rememberSeamlessGrid
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.SelectionAction
import eu.akoos.photos.presentation.common.SelectionDrawer
import eu.akoos.photos.presentation.common.favoriteSelectionAction
import eu.akoos.photos.presentation.common.favoriteTurnsOn
import eu.akoos.photos.presentation.common.anyCloudOnly
import eu.akoos.photos.presentation.common.anyLocalOnly
import eu.akoos.photos.presentation.common.anyMetadataEditable
import eu.akoos.photos.presentation.common.hasDownloadable
import eu.akoos.photos.presentation.common.offlinePinnableLinkIds
import eu.akoos.photos.presentation.common.offlineTurnsOn
import eu.akoos.photos.presentation.common.allLocalOnly
import eu.akoos.photos.presentation.common.MultiStripState
import eu.akoos.photos.presentation.common.ReturnToViewerPhoto
import eu.akoos.photos.presentation.common.ScrollScrubber
import androidx.compose.material.icons.filled.PrivacyTip
import eu.akoos.photos.presentation.gallery.CategoryRail
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.presentation.gallery.ContentFilterSheet
import eu.akoos.photos.presentation.gallery.GalleryAddToAlbumDialog
import eu.akoos.photos.presentation.gallery.GalleryFilter
import eu.akoos.photos.presentation.gallery.GalleryMultiDeleteDialog
import eu.akoos.photos.presentation.gallery.MetadataStripPickerDialog
import eu.akoos.photos.presentation.gallery.MediaType
import eu.akoos.photos.presentation.gallery.SyncStatusFilter
import eu.akoos.photos.presentation.gallery.rememberDragMultiSelectModifier
import eu.akoos.photos.presentation.memories.FloatingMemoriesHeader
import eu.akoos.photos.presentation.search.components.CalendarPreviewCard
import eu.akoos.photos.presentation.search.components.OfflinePreviewCard
import eu.akoos.photos.presentation.search.components.PeoplePreviewCard
import eu.akoos.photos.presentation.search.components.JumpToMonthHeader
import eu.akoos.photos.presentation.search.components.MonthTileRow
import eu.akoos.photos.presentation.search.components.MapPreviewCard
import eu.akoos.photos.presentation.search.components.OnThisDayRow
import eu.akoos.photos.presentation.search.components.RecentRow
import eu.akoos.photos.presentation.search.components.MonthBucket
import eu.akoos.photos.presentation.search.components.buildMonthBuckets
import eu.akoos.photos.presentation.gallery.TimelineScrubber
import eu.akoos.photos.presentation.gallery.TimelineGrouping
import eu.akoos.photos.presentation.theme.ErrorColor
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.util.computeOnThisDay
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
    onOpenMap: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenOffline: () -> Unit = {},
    onOpenPeople: () -> Unit = {},
    /** Opens the date + place editor for the current selection, matching the timeline's entry. */
    onEditMetadata: (items: List<GalleryItem>) -> Unit = {},
    /** Opens a matched person's page from the name suggestion row. */
    onOpenPerson: (Long) -> Unit = {},
    vm: SearchViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val query by vm.query.collectAsStateWithLifecycle()
    val peopleSuggestions by vm.peopleSuggestions.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val filter by vm.contentFilter.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategory.collectAsStateWithLifecycle()
    val allItems by vm.allItems.collectAsStateWithLifecycle()
    val geotaggedPins by vm.geotaggedPins.collectAsStateWithLifecycle()
    val distinctCityCount by vm.distinctCityCount.collectAsStateWithLifecycle()
    val selectedItems by vm.selectedItems.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val pendingDeleteIntent by vm.pendingDeleteIntent.collectAsStateWithLifecycle()
    val isDeleting by vm.isDeleting.collectAsStateWithLifecycle()
    val pendingStripIntent by vm.pendingStripIntent.collectAsStateWithLifecycle()
    val multiStripState by vm.multiStripState.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val offlinePinIds by vm.offlinePinIds.collectAsStateWithLifecycle()
    val favoriteState by vm.favoriteState.collectAsStateWithLifecycle()

    val isSelectionMode = selectedItems.isNotEmpty()
    // In selection mode the back button cancels the selection instead of leaving the screen.
    BackHandler(enabled = isSelectionMode) { vm.clearSelection() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // The VM builds the system-share intent off the UI thread; the screen launches the chooser.
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    LaunchedEffect(Unit) {
        vm.shareIntent.collect { intent ->
            runCatching { context.startActivity(android.content.Intent.createChooser(intent, shareChooserTitle)) }
        }
    }
    // Offline-batch result — the pins apply optimistically, this only reports the outcome.
    val offlineRemovedMsg = stringResource(R.string.offline_removed)
    LaunchedEffect(Unit) {
        vm.offlineBatchResult.collect { count ->
            when {
                count > 0 -> snackbarHostState.showSnackbar(
                    context.resources.getQuantityString(R.plurals.offline_batch_result, count, count),
                )
                count < 0 -> snackbarHostState.showSnackbar(offlineRemovedMsg)
            }
        }
    }
    // An action that did not do all it said says so; the message already reads for the user.
    LaunchedEffect(Unit) {
        vm.actionFailure.collect { snackbarHostState.showSnackbar(it) }
    }

    // A download says it began the moment it does. Its progress is shown on the Activity screen and
    // the selection clears straight away, so this screen said nothing at all until the whole batch
    // had finished, which on a slow connection reads as a button that did nothing. Same wording the
    // timeline uses for the same moment.
    val downloadStartedMsg = stringResource(R.string.download_started_background)
    LaunchedEffect(Unit) {
        vm.downloadStarted.collect { snackbarHostState.showSnackbar(downloadStartedMsg) }
    }

    var showDeleteSheet by remember { mutableStateOf(false) }
    // The selection's hide split while its confirmation is up, null when none is. Holding the split
    // rather than a flag is what lets the sheet describe the photos the tap was made on.
    var hideConfirmSplit by remember {
        mutableStateOf<eu.akoos.photos.data.hidden.HiddenFolderRecords.HideSplit?>(null)
    }
    var showAddToAlbumSheet by remember { mutableStateOf(false) }
    val addToAlbumSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The picker's own "New album" row, which names an album and then adds the selection to it.
    var showCreateAlbumInline by remember { mutableStateOf(false) }
    // System trash-dialog launcher for a delete/hide that needs MANAGE_MEDIA on Android 11+.
    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.onDeletePermissionGranted()
        else vm.clearPendingDeleteIntent()
    }
    LaunchedEffect(pendingDeleteIntent) {
        val pi = pendingDeleteIntent ?: return@LaunchedEffect
        // The launch itself can throw if the sender was already consumed; drop the pending work so a
        // stale intent can't force-close the screen.
        runCatching {
            deletePermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }.onFailure { vm.clearPendingDeleteIntent() }
    }
    // System write-permission launcher for the batch metadata strip (foreign files on Android 11+).
    val stripPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.onStripPermissionGranted()
        else vm.clearPendingStripIntent()
    }
    LaunchedEffect(pendingStripIntent) {
        val pi = pendingStripIntent ?: return@LaunchedEffect
        runCatching {
            stripPermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }.onFailure { vm.clearPendingStripIntent() }
    }
    // Strip result: one snackbar when a batch finishes, then reset so it fires once.
    LaunchedEffect(multiStripState) {
        when (val s = multiStripState) {
            is MultiStripState.Done -> {
                val msg = if (s.skipped > 0)
                    context.getString(R.string.gallery_stripped_with_skipped, s.stripped, s.skipped)
                else context.getString(R.string.gallery_stripped_metadata, s.stripped)
                snackbarHostState.showSnackbar(msg)
                vm.resetMultiStripState()
            }
            is MultiStripState.Failed -> {
                snackbarHostState.showSnackbar(s.message)
                vm.resetMultiStripState()
            }
            else -> Unit
        }
    }

    var showFilterSheet by remember { mutableStateOf(false) }
    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val resultsGridState = rememberLazyGridState()
    // Hide the secondary filter rows once results are scrolled so browsing reclaims that
    // vertical space; they slide back at the top. The search field + title stay put.
    val filterRowsVisible by remember { derivedStateOf { resultsGridState.firstVisibleItemIndex == 0 } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg0),
    ) {
      // Search content fills the screen; the floating header overlays it (added after this
      // Column in the Box so it draws on top). The status-bar inset plus a top pad clears
      // the collapsed header row so the search field never slips under the title pill.
      Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 52.dp),
      ) {
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

        // People whose name matches the query, above the results so a name search reaches a person
        // even when no photo filename matches the text.
        if (query.isNotBlank() && peopleSuggestions.isNotEmpty()) {
            PeopleSuggestionRow(people = peopleSuggestions, onOpenPerson = onOpenPerson)
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
            val idleListState = rememberLazyListState()
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = idleListState,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "map_preview_section") {
                    MapPreviewCard(
                        pins = geotaggedPins,
                        cityCount = distinctCityCount,
                        onOpenMap = onOpenMap,
                    )
                }
                item(key = "calendar_preview_section") {
                    CalendarPreviewCard(onClick = onOpenCalendar)
                }
                item(key = "offline_preview_section") {
                    OfflinePreviewCard(onClick = onOpenOffline)
                }
                item(key = "people_preview_section") {
                    PeoplePreviewCard(onClick = onOpenPeople)
                }
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
                    // Header + one list item per tile row so the whole idle page (including the
                    // months) scrolls as granular items, which lets the position-based scrubber
                    // scrub through the month picker. The old single-block section wrapped these in
                    // a Column with top 4 / bottom 16 outer padding; that pad is reproduced on the
                    // header and the final row so the layout stays identical.
                    item(key = "jump_to_month_header") {
                        JumpToMonthHeader(modifier = Modifier.padding(top = 4.dp))
                    }
                    val monthRows = monthBuckets.chunked(3)
                    items(monthRows.size, key = { "month_row_$it" }) { i ->
                        MonthTileRow(
                            row = monthRows[i],
                            onMonthClick = { year, month ->
                                vm.setContentFilter(
                                    ContentFilter(year = year, month = month),
                                )
                            },
                            modifier = if (i == monthRows.lastIndex) {
                                Modifier.padding(bottom = 16.dp)
                            } else {
                                Modifier
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
            // Fast-scroll grabber over the whole idle page. Position only: the page mixes preview
            // cards with month rows, so there is no single date axis to label. minItemsToShow keeps
            // it hidden on a near-empty library (just the three preview cards) and shows it once the
            // month rows make the page long enough to be worth jumping.
            ScrollScrubber(
                listState = idleListState,
                topPadding = 8.dp,
                bottomPadding = 24.dp + navBottom,
                minItemsToShow = 8,
            )
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
                // Drag-to-select: long-press a cell then drag to sweep a range. Cells key on the
                // shared keyOf() (LocalOnly/Synced by uri, CloudOnly by linkId); the swept keys map
                // back to the whole GalleryItem the selection holds.
                val selectableKeys = remember(results) { results.map { keyOf(it) } }
                val keyToIndex = remember(selectableKeys) { selectableKeys.mapIndexed { i, k -> k to i }.toMap() }
                // Armed at the long-press anchor so the release-tap skips toggling the just-anchored cell.
                val tapGuard = remember { mutableStateOf(false) }
                val dragSelectModifier = rememberDragMultiSelectModifier(
                    gridState = resultsGridState,
                    items = results,
                    indexByKey = keyToIndex,
                    selected = selectedItems,
                    onSelectionChange = vm::setSelection,
                    tapGuard = tapGuard,
                    enabled = !isDeleting,
                )
                val seamless = rememberSeamlessGrid()
                // Land back on the photo the viewer closed on. Results are one flat run with no
                // header and nothing ahead of them. The cells key on the prefixed local keyOf(),
                // which the viewer knows nothing about, so match on the gallery identity instead.
                val returnGroups = remember(results) { listOf(results) }
                ReturnToViewerPhoto(
                    gridState = resultsGridState,
                    groups = returnGroups,
                    headerPerGroup = false,
                    keyOf = { it.stableId },
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = resultsGridState,
                    contentPadding = PaddingValues(
                        start = if (seamless) 0.dp else 6.dp,
                        end = if (seamless) 0.dp else 6.dp,
                        top = 4.dp,
                        bottom = 12.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 4.dp),
                    verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 4.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .then(dragSelectModifier),
                ) {
                    itemsIndexed(results, key = { _, it -> keyOf(it) }) { idx, item ->
                        val inputs = remember(item, favoriteIds) {
                            photoCellInputsFor(item, favoriteIds = favoriteIds)
                        }
                        val isSelected = item in selectedItems
                        PhotoCell(
                            imageData = inputs.imageData,
                            stableKey = inputs.stableKey,
                            isVideo = inputs.isVideo,
                            isLocalVideo = inputs.isLocalVideo,
                            durationMs = inputs.durationMs,
                            isPlaceholder = inputs.isPlaceholder,
                            selected = isSelected,
                            isSelectionMode = isSelectionMode,
                            showCloudBadge = inputs.showCloudBadge,
                            showSyncedBadge = inputs.showSyncedBadge,
                            isFavorite = inputs.isFavorite,
                            typeBadgeRes = inputs.typeBadgeRes,
                            typeBadgeCdRes = inputs.typeBadgeCdRes,
                            // Fixed 3-column results grid, so always the big-tile (all badges) tier.
                            columns = 3,
                            cornerRadius = if (seamless) 0.dp else 10.dp,
                            onClick = {
                                // Skip the release-tap that ends a long-press select; otherwise it
                                // would toggle the just-anchored cell back off.
                                if (tapGuard.value) {
                                    tapGuard.value = false
                                } else if (isSelectionMode) {
                                    vm.toggleSelection(item)
                                } else onPhotoClick(results, idx)
                            },
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

        // Floating title pill — drops down to switch to the Map or Calendar view, like the
        // Collection. It carries its own statusBarsPadding and floats over the search content.
        // Hidden while selecting, so the screen belongs to the selection alone.
        if (!isSelectionMode) {
            FloatingMemoriesHeader(
                title = stringResource(R.string.search_title),
                onBack = onBack,
            )
        }

        // A multi-select delete blocks the screen behind a progress drawer so a second tap can't
        // fire into a half-finished delete.
        val opDeletingLabel = stringResource(R.string.op_deleting)
        eu.akoos.photos.presentation.common.BlockingOperationSheet(
            if (isDeleting) eu.akoos.photos.presentation.common.OperationProgress(0, 0, opDeletingLabel, indeterminate = true) else null,
        )

        // Selection-mode overlay: the shared drawer, so every bulk action sits in one place here
        // exactly as it does on the timeline, album and device-folder surfaces.
        val allResultsSelected = results.isNotEmpty() && selectedItems.size == results.size
        var showStripPicker by remember { mutableStateOf(false) }
        // Which way the offline row goes, so it names the press rather than the state: a pin while
        // anything pinnable in the selection is still un-pinned, a removal once none is.
        val offlinePinsSelection = remember(selectedItems, offlinePinIds) {
            offlineTurnsOn(offlinePinnableLinkIds(selectedItems), offlinePinIds)
        }
        val searchSelectionActions = buildList {
            add(
                SelectionAction(
                    icon = Icons.Default.SelectAll,
                    label = stringResource(
                        if (allResultsSelected) R.string.gallery_deselect_all else R.string.select_all,
                    ),
                    onClick = { if (allResultsSelected) vm.clearSelection() else vm.selectAll() },
                )
            )
            add(
                SelectionAction(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.sel_label_share),
                    onClick = { vm.shareSelected() },
                )
            )
            add(
                SelectionAction(
                    icon = Icons.Default.PhotoAlbum,
                    label = stringResource(R.string.gallery_add_to_album),
                    onClick = { showAddToAlbumSheet = true },
                )
            )
            // Favourite the whole result set in one press. Search is how a batch worth favouriting
            // gets assembled in the first place, so the action belongs where the results are rather
            // than one photo at a time in the viewer.
            add(
                favoriteSelectionAction(
                    turnsOn = remember(selectedItems, favoriteIds) {
                        favoriteTurnsOn(selectedItems, favoriteIds)
                    },
                    state = favoriteState,
                    onClick = { vm.toggleSelectedFavorite() },
                )
            )
            // Back up the not-yet-uploaded (LocalOnly) photos in the selection.
            if (anyLocalOnly(selectedItems)) {
                add(
                    SelectionAction(
                        icon = Icons.Default.CloudUpload,
                        label = stringResource(R.string.sel_label_back_up),
                        onClick = {
                            vm.backUpSelected { queued ->
                                if (queued > 0) scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.backup_started))
                                }
                            }
                        },
                    )
                )
            }
            // Download / offline apply to cloud-only photos (no local file yet).
            if (hasDownloadable(selectedItems)) {
                add(
                    SelectionAction(
                        icon = Icons.Default.FileDownload,
                        label = stringResource(R.string.sel_label_download),
                        onClick = {
                            vm.downloadSelected { succeeded, failed ->
                                val msg = when {
                                    failed > 0 -> context.getString(R.string.gallery_download_partial, succeeded, failed)
                                    succeeded == 1 -> context.getString(R.string.gallery_download_done_singular)
                                    else -> context.getString(R.string.gallery_download_done, succeeded)
                                }
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        },
                    )
                )
            }
            if (anyCloudOnly(selectedItems)) {
                add(
                    SelectionAction(
                        icon = Icons.Default.OfflinePin,
                        label = stringResource(
                            if (offlinePinsSelection) R.string.offline_make_available
                            else R.string.offline_remove,
                        ),
                        onClick = { vm.toggleSelectedOffline() },
                    )
                )
            }
            // The date + place editor opens for any editable photo (a device photo, or a cloud or
            // backed-up image the corrected-copy replace can rewrite), so a mixed selection keeps the
            // entry (the editor writes exactly those photos and names the count).
            if (anyMetadataEditable(selectedItems)) {
                add(
                    SelectionAction(
                        icon = Icons.Default.EditNote,
                        label = stringResource(R.string.metadata_editor_edit_metadata),
                        onClick = { onEditMetadata(selectedItems.toList()) },
                    )
                )
            }
            // The strip stays behind an all-device-only selection: a Synced photo keeps its Drive
            // copy's EXIF, so stripping just the local file is a misleading half-strip.
            if (allLocalOnly(selectedItems)) {
                add(
                    SelectionAction(
                        icon = Icons.Default.PrivacyTip,
                        label = stringResource(R.string.gallery_strip_metadata),
                        onClick = { showStripPicker = true },
                    )
                )
            }
            // Hide any non-empty selection: a photo that lives only on this device moves into the
            // vault, one with a cloud copy is filtered by linkId.
            if (selectedItems.isNotEmpty()) {
                add(
                    SelectionAction(
                        icon = Icons.Default.VisibilityOff,
                        label = stringResource(R.string.sel_label_hide),
                        enabled = !isDeleting,
                        // A hide ends in a permanent removal of the device originals it vaults, so it
                        // is confirmed exactly as the delete beside it is. The split is read at the
                        // tap, so the sheet names what THIS selection will have done to it.
                        onClick = { hideConfirmSplit = vm.hideSplitForSelection().takeIf { !it.isEmpty } },
                    )
                )
            }
            add(
                SelectionAction(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(R.string.sel_label_delete),
                    tint = ErrorColor,
                    enabled = !isDeleting,
                    onClick = { showDeleteSheet = true },
                )
            )
        }
        SelectionDrawer(
            visible = isSelectionMode,
            items = remember(selectedItems) { selectedItems.toList() },
            actions = searchSelectionActions,
            onDismiss = { vm.clearSelection() },
            // Scrolling the results collapses the drawer, so reaching past it to carry on through
            // them needs no deliberate pull or tap first.
            contentScrolling = resultsGridState.isScrollInProgress,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (showStripPicker) {
            MetadataStripPickerDialog(
                onConfirm = {
                    showStripPicker = false
                    vm.stripMetadataSelected(it)
                },
                onDismiss = { showStripPicker = false },
            )
        }

        // Hide confirmation — the shared sheet every hide surface raises, worded from this
        // selection's own split.
        hideConfirmSplit?.let { split ->
            eu.akoos.photos.presentation.common.HideConfirmSheet(
                split = split,
                title = stringResource(R.string.hide_confirm_title),
                onConfirm = {
                    hideConfirmSplit = null
                    vm.hideSelected()
                },
                onDismiss = { hideConfirmSplit = null },
            )
        }

        // Bulk-delete sheet — reuses the gallery's dialog so options + copy stay identical.
        if (showDeleteSheet && selectedItems.isNotEmpty()) {
            GalleryMultiDeleteDialog(
                selectedItems = selectedItems,
                onDismiss = { showDeleteSheet = false },
                onDelete = { freeUpSpace, deleteFromCloud ->
                    showDeleteSheet = false
                    vm.deleteSelected(freeUpSpace, deleteFromCloud)
                },
            )
        }

        // Add-to-album sheet — reuses the gallery's picker. Cloud-backed selections join now;
        // local-only selections upload first and join afterwards.
        if (showAddToAlbumSheet && selectedItems.isNotEmpty()) {
            GalleryAddToAlbumDialog(
                selectedItems = selectedItems,
                cloudAlbums = albums,
                sheetState = addToAlbumSheetState,
                onCreateNew = {
                    showAddToAlbumSheet = false
                    showCreateAlbumInline = true
                },
                onCloudAlbumSelected = { album ->
                    showAddToAlbumSheet = false
                    vm.addSelectedToAlbum(album.linkId) { joined, _ ->
                        if (joined > 0) scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.gallery_added_to_album, joined, album.name),
                            )
                        }
                    }
                },
                onDismiss = { showAddToAlbumSheet = false },
            )
        }

        // Name a brand-new album for the selection, then create it and add the photos to it — the
        // same two steps the timeline's picker runs, so the row means the same thing on both.
        if (showCreateAlbumInline) {
            eu.akoos.photos.presentation.gallery.GalleryNewAlbumDialog(
                onDismiss = { showCreateAlbumInline = false },
                onCreate = { name ->
                    showCreateAlbumInline = false
                    vm.createAlbumThenAddSelected(name) { joined, _, error ->
                        val msg = error
                            ?: context.getString(R.string.gallery_added_to_album, joined, name)
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    }
                },
            )
        }

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = filterSheetState,
            containerColor = colors.bg2,
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

/**
 * A horizontally scrolling row of the people whose name matches the search query, each shown as the
 * same circular face tile the timeline rail uses. Tapping one opens that person's page. Sits above the
 * results so a name search reaches a person even when no photo text matches.
 */
@Composable
private fun PeopleSuggestionRow(
    people: List<PersonUi>,
    onOpenPerson: (Long) -> Unit,
) {
    val colors = AppColors.current
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.gallery_category_people),
            color = colors.fgDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            people.forEach { person ->
                PersonTile(
                    person = person,
                    selected = false,
                    onClick = { onOpenPerson(person.personId) },
                )
            }
        }
    }
}
