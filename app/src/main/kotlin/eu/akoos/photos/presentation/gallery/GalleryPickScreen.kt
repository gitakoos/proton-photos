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

package eu.akoos.photos.presentation.gallery

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.albums.AlbumPhotoPickerViewModel
import eu.akoos.photos.presentation.albums.AlbumsScreen
import eu.akoos.photos.presentation.albums.PickerDoneButton
import eu.akoos.photos.presentation.albums.deviceBucketOf
import eu.akoos.photos.presentation.common.EmptyState
import eu.akoos.photos.presentation.common.PickHeader
import eu.akoos.photos.presentation.common.rememberStripCollapse
import eu.akoos.photos.presentation.common.ThemedSnackbarHost
import eu.akoos.photos.presentation.search.SearchPick
import eu.akoos.photos.presentation.search.SearchScope
import eu.akoos.photos.presentation.search.SearchScreen
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.pillShape
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Adding photos to [albumLinkId] from the gallery itself, as bare as a system gallery's own picker. The
 * screen is the gallery, not a lookalike: the same timeline grid with its zoom, the same Albums page and
 * the same bottom bar, all drawn from the gallery's own view model. What differs is what the picking
 * leaves out: no filter or category rows, no menus, no action drawer. A bar names the album and holds Done
 * (with the picked count), every tap selects, and the bottom bar carries a third tab, Search, which is the
 * gallery's real search as a page of its own (its field and category chips above the bar, a pill for the
 * album it is searching in), so the tabs never go away.
 *
 * Back cancels (from an opened album it steps back to the Albums page first). Photos already in the album
 * are left out, and the selection survives every switch between the tabs and an opened album.
 *
 * [onDone], when given, replaces the add with what the caller does with the picked photos (handing them
 * to the app that asked for them), and [doneWorking] holds Done busy while it runs. With [singlePick] a tap
 * replaces the pick rather than adding to it, for an app that takes one photo.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryPickScreen(
    albumLinkId: String,
    albumName: String,
    excludeLinkIds: Set<String>,
    onBack: () -> Unit,
    onAdded: () -> Unit,
    viewModel: GalleryViewModel,
    membersViewModel: AlbumPhotoPickerViewModel = hiltViewModel(),
    onDone: ((List<GalleryItem>) -> Unit)? = null,
    doneWorking: Boolean = false,
    singlePick: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val thumbnailUrls by viewModel.thumbnailUrls.collectAsStateWithLifecycle()
    val downloadedCloudLinkIds by viewModel.downloadedCloudLinkIds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appColors = AppColors.current
    val tabScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // The gallery's selection and filters are shared with the timeline, so the picking starts from nothing
    // selected and from the whole library, and hands the timeline back the filters it had. The picker has
    // no filter rows of its own, so nothing narrowed beforehand may linger in it.
    DisposableEffect(Unit) {
        val category = viewModel.uiState.value.selectedFilter
        val content = viewModel.uiState.value.contentFilter
        val person = viewModel.uiState.value.selectedPersonId
        viewModel.clearSelection()
        viewModel.onFilterSelected(GalleryFilter.All)
        viewModel.setContentFilter(ContentFilter())
        viewModel.onPersonSelected(null)
        onDispose {
            viewModel.clearSelection()
            viewModel.onFilterSelected(category)
            viewModel.setContentFilter(content)
            viewModel.onPersonSelected(person)
        }
    }

    val pagerState = rememberPagerState { 3 }
    var scopeKey by rememberSaveable { mutableStateOf<String?>(null) }
    var scopeName by rememberSaveable { mutableStateOf("") }
    val scope = scopeKey?.let { SearchScope(it, scopeName) }
    fun openScope(key: String, name: String) { scopeKey = key; scopeName = name }
    // The scope stays readable while its page fades out, so the photos do not vanish before the fade ends.
    val lastScope = remember { arrayOfNulls<SearchScope>(1) }
    if (scope != null) lastScope[0] = scope
    val activeScope = scope ?: lastScope[0]
    BackHandler(enabled = scope != null) { scopeKey = null }
    // The album the Search tab opens narrowed to: the one that was open when the tab was pressed.
    var searchScope by remember { mutableStateOf<SearchScope?>(null) }

    val albumsGridState = rememberLazyGridState()
    val gridState = rememberLazyGridState()
    val staggeredState = rememberLazyStaggeredGridState()
    // One scroll state per opened album or folder, so each starts at its top.
    val scopeGridState = key(activeScope?.key) { rememberLazyGridState() }
    val scopeStaggeredState = key(activeScope?.key) { rememberLazyStaggeredGridState() }
    // Whether the page in front is scrolled away from its top: the bar's name and Done are plain text
    // on the page until then and lift off it as pills once it is.
    val photosScrolled by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 ||
                staggeredState.firstVisibleItemIndex > 0 || staggeredState.firstVisibleItemScrollOffset > 0
        }
    }
    val albumsScrolled by remember {
        derivedStateOf { albumsGridState.firstVisibleItemIndex > 0 || albumsGridState.firstVisibleItemScrollOffset > 0 }
    }
    val scopeScrolled by remember(scopeGridState, scopeStaggeredState) {
        derivedStateOf {
            scopeGridState.firstVisibleItemIndex > 0 || scopeGridState.firstVisibleItemScrollOffset > 0 ||
                scopeStaggeredState.firstVisibleItemIndex > 0 || scopeStaggeredState.firstVisibleItemScrollOffset > 0
        }
    }
    var searchScrolled by remember { mutableStateOf(false) }
    val albumColumns = rememberAlbumColumns()
    val albumsViewMode = rememberAlbumsViewMode()

    // What the add reports: back to the album on success, a snackbar on failure.
    val addState = state.addToAlbumState
    val addFailedFmt = stringResource(R.string.gallery_add_to_album_failed)
    val queuedMsg = stringResource(R.string.gallery_add_to_album_queued)
    LaunchedEffect(addState) {
        when (addState) {
            is AddToAlbumState.Done -> {
                // A local pick uploads first and only then joins the album, so closing with nothing yet
                // visible would read as nothing happened.
                if (addState.localMoved > 0) Toast.makeText(context, queuedMsg, Toast.LENGTH_LONG).show()
                viewModel.resetAddToAlbumState()
                onAdded()
            }
            is AddToAlbumState.Failed -> {
                snackbarHostState.showSnackbar(addState.message.ifBlank { addFailedFmt })
                viewModel.resetAddToAlbumState()
            }
            else -> Unit
        }
    }

    val working = addState is AddToAlbumState.Working || doneWorking
    val toggle: (GalleryItem) -> Unit = if (!singlePick) viewModel::toggleSelection else { item ->
        if (item in viewModel.uiState.value.selectedItems) viewModel.clearSelection()
        else viewModel.setSelection(setOf(item))
    }
    val toggleGroup: (List<GalleryItem>) -> Unit = if (singlePick) { _ -> } else viewModel::toggleGroup
    val setPicks: (Set<GalleryItem>) -> Unit = if (!singlePick) viewModel::setSelection else { next ->
        viewModel.setSelection(singlePickOf(next, viewModel.uiState.value.selectedItems))
    }
    val selectedCount = state.selectedItems.size
    val title = albumName.ifBlank { stringResource(R.string.album_picker_title) }

    // The photos the album already holds are never offered again.
    val timelineItems = remember(state.filteredItems, excludeLinkIds) {
        state.filteredItems.filterNot { it.isIn(excludeLinkIds) }
    }
    val monthGroups = remember(state.monthGroups, excludeLinkIds) { state.monthGroups.without(excludeLinkIds) }
    val dayGroups = remember(state.dayGroups, excludeLinkIds) { state.dayGroups.without(excludeLinkIds) }

    // The members arrive tagged with the album they are of, and are read only for the album now open. A
    // collected value outlives a change of flow, so without the tag the previous album's photos showed
    // for a moment as the next one opened, and the grid jumped when the right ones replaced them.
    val membersOfScope by remember(activeScope) {
        val open = activeScope
        val id = open?.cloudLinkId
        if (open != null && id != null) {
            membersViewModel.albumMembers(id).map { open.key to it }
        } else {
            flowOf(null)
        }
    }.collectAsStateWithLifecycle(initialValue = null)
    val scopeMembers = membersOfScope?.takeIf { it.first == activeScope?.key }?.second
    val scopeItems = remember(activeScope, scopeMembers, state.items, excludeLinkIds) {
        val s = activeScope ?: return@remember emptyList()
        val members = scopeMembers
        state.items.filter { item ->
            !item.isIn(excludeLinkIds) && when {
                s.cloudLinkId != null -> members != null && item.cloudLinkId()?.let { it in members } == true
                else -> deviceBucketOf(item) == s.folder
            }
        }
    }
    val scopeMonthGroups = remember(scopeItems) { groupByMonth(scopeItems) }
    val scopeDayGroups = remember(scopeItems) { groupByDay(scopeItems) }

    val selectedList = remember(state.selectedItems) { state.selectedItems.toList() }
    val collapse = rememberStripCollapse()
    // The page under the header block starts a little below its rule.
    val pageTop = 4.dp
    // An opened album is shown once its members are known, so it slides in with its photos in place instead
    // of being cleared and filled in front of the user.
    val scopeReady = scope != null && (scope.cloudLinkId == null || scopeMembers != null)
    val barScrolled = when {
        scopeReady -> scopeScrolled
        pagerState.currentPage == 0 -> photosScrolled
        pagerState.currentPage == 1 -> albumsScrolled
        else -> searchScrolled
    }
    // The bottom bar and the navigation bar, which the Search tab's field sits above.
    val tabBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp

    Box(modifier = Modifier.fillMaxSize().background(appColors.bg0)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The header block, fixed at the top as a system gallery's picker has it: the album the photos are
            // going into with Done, then the picked photos (or a line saying there are none) over a rule. The
            // page scrolls below the rule.
            PickHeader(
                title = if (scopeReady) (scope?.name ?: title) else title,
                scrolled = barScrolled,
                items = selectedList,
                expanded = collapse.expanded,
                emptyLabel = stringResource(R.string.picker_nothing_selected),
                onItemClick = viewModel::toggleSelection,
                onToggleStrip = { collapse.expanded = !collapse.expanded },
                trailing = { pill ->
                    PickDone(
                        enabled = selectedCount > 0 && !working,
                        working = working,
                        count = selectedCount,
                        pill = pill,
                        onClick = {
                            if (onDone != null) onDone(selectedList)
                            else viewModel.addSelectedToAlbum(albumLinkId, albumName)
                        },
                    )
                },
            )
            // Scrolling the page down folds the picked strip away and scrolling up brings it back.
            Box(modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(collapse.connection)) {
                HorizontalPager(
                    state = pagerState,
                    // A horizontal drag belongs to the grid's drag-select, so the pages change from the bar only.
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        0 -> if (timelineItems.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize().padding(top = pageTop), contentAlignment = Alignment.Center) {
                                if (state.isLoading) {
                                    CircularProgress()
                                } else {
                                    EmptyState(
                                        title = stringResource(R.string.search_empty_no_results),
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        } else PhotoGrid(
                            items = timelineItems,
                            allItems = state.items,
                            monthGroups = monthGroups,
                            dayGroups = dayGroups,
                            onThisDayGroups = emptyList(),
                            gridState = gridState,
                            staggeredState = staggeredState,
                            topContentPadding = pageTop,
                            permissionState = state.permissionState,
                            onPermissionGrant = {},
                            onPhotoClick = { _, _ -> },
                            selectedItems = state.selectedItems,
                            isSelectionMode = true,
                            dragSelectEnabled = !working && !singlePick,
                            onToggleSelect = toggle,
                            onToggleGroup = toggleGroup,
                            groupSelectable = !singlePick,
                            onSelectionChange = setPicks,
                            initialZoomLevel = state.initialZoomLevel,
                            gridRememberLast = state.gridRememberLast,
                            gridDefaultColumns = state.gridDefaultColumns,
                            onZoomLevelChanged = viewModel::setZoomLevel,
                            hiddenCloudLinkIds = state.hiddenCloudLinkIds,
                            downloadedCloudLinkIds = downloadedCloudLinkIds,
                            favoriteIds = state.favoriteIds,
                            offlinePinIds = state.offlinePinIds,
                            thumbnailUrls = thumbnailUrls,
                            onRequestThumbnail = viewModel::requestThumbnailDecrypt,
                            onCancelThumbnail = viewModel::cancelThumbnailDecrypt,
                            denseGridWarningDismissed = state.denseGridWarningDismissed,
                            onDismissDenseGridWarning = viewModel::dismissDenseGridWarning,
                        )
                        1 -> AlbumsScreen(
                            topPadding = pageTop,
                            gridState = albumsGridState,
                            columns = albumColumns,
                            viewMode = albumsViewMode,
                            pickMode = true,
                            hideAlbumLinkId = albumLinkId,
                            onAlbumClick = { album -> openScope("c:${album.linkId}", album.name) },
                            onDeviceFolderClick = { name -> openScope("f:$name", name) },
                        )
                        else -> SearchScreen(
                            onBack = onBack,
                            onPhotoClick = { _, _ -> },
                            pick = SearchPick(
                                selected = state.selectedItems,
                                onToggle = toggle,
                                onSetSelection = setPicks,
                                excludeLinkIds = excludeLinkIds,
                                initialScope = searchScope,
                                topInset = pageTop,
                                bottomInset = tabBarInset,
                                onScrolled = { searchScrolled = it },
                            ),
                        )
                    }
                }

                // An opened album or folder takes the page over with its own photos.
                SlideFadePage(visible = scopeReady, modifier = Modifier.fillMaxSize()) {
                    val shown = activeScope
                    Box(modifier = Modifier.fillMaxSize().background(appColors.bg0)) {
                        run {
                            PhotoGrid(
                                items = scopeItems,
                                allItems = scopeItems,
                                monthGroups = scopeMonthGroups,
                                dayGroups = scopeDayGroups,
                                onThisDayGroups = emptyList(),
                                gridState = scopeGridState,
                                staggeredState = scopeStaggeredState,
                                topContentPadding = pageTop,
                                permissionState = state.permissionState,
                                onPermissionGrant = {},
                                onPhotoClick = { _, _ -> },
                                selectedItems = state.selectedItems,
                                isSelectionMode = true,
                                dragSelectEnabled = !working && !singlePick,
                                onToggleSelect = toggle,
                                onToggleGroup = toggleGroup,
                                groupSelectable = !singlePick,
                                onSelectionChange = setPicks,
                                initialZoomLevel = state.initialZoomLevel,
                                gridRememberLast = state.gridRememberLast,
                                gridDefaultColumns = state.gridDefaultColumns,
                                onZoomLevelChanged = viewModel::setZoomLevel,
                                hiddenCloudLinkIds = state.hiddenCloudLinkIds,
                                downloadedCloudLinkIds = downloadedCloudLinkIds,
                                favoriteIds = state.favoriteIds,
                                offlinePinIds = state.offlinePinIds,
                                thumbnailUrls = thumbnailUrls,
                                onRequestThumbnail = viewModel::requestThumbnailDecrypt,
                                onCancelThumbnail = viewModel::cancelThumbnailDecrypt,
                                denseGridWarningDismissed = true,
                            )
                        }
                    }
                }
            }
        }

        // The bottom bar: Photos, Albums and Search as tabs of one pill. A tab always leaves an opened album;
        // Search takes the album it was pressed in as its scope.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            BottomDock(
                position = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                onTabSelected = { tab ->
                    searchScope = if (tab == 2) scope else null
                    scopeKey = null
                    tabScope.launch { pagerState.animateScrollToPage(tab) }
                },
            )
        }

        ThemedSnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        )
    }
}

/**
 * Done, with the picked count that grows in beside it. Plain text at rest, the active or the idle header
 * pill once [pill] has risen; the count slides in and the pill follows its width instead of snapping.
 */
@Composable
private fun PickDone(
    enabled: Boolean,
    working: Boolean,
    count: Int,
    pill: Float,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Accent else FgMute
    val fill = if (enabled) Accent.copy(alpha = 0.18f * pill) else PillBg.copy(alpha = PillBg.alpha * pill)
    val line = if (enabled) Color.Transparent else PillBorder.copy(alpha = pill)
    // Held while the count folds away, so the badge does not read 0 on its way out.
    val shownCount = remember { intArrayOf(0) }
    if (count > 0) shownCount[0] = count
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(pillShape)
            .background(fill, pillShape)
            .border(0.5.dp, line, pillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .animateContentSize(tween(180))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (working) {
            CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            stringResource(R.string.picker_done),
            color = tint,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        AnimatedVisibility(
            visible = count > 0,
            enter = expandHorizontally(tween(180)) + fadeIn(tween(180)),
            exit = shrinkHorizontally(tween(150)) + fadeOut(tween(120)),
        ) {
            Row {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                        .background(tint.copy(alpha = 0.2f), RoundedCornerShape(11.dp))
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = shownCount[0],
                        transitionSpec = {
                            (slideInVertically(tween(140)) { it / 2 } + fadeIn(tween(140))) togetherWith
                                (slideOutVertically(tween(100)) { -it / 2 } + fadeOut(tween(100)))
                        },
                        label = "pickDoneCount",
                    ) { n ->
                        Text(n.toString(), color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** A page that slides in a short way from the side with a fade, and leaves the same way. A function of its own
 *  so it resolves the plain AnimatedVisibility, not the one of an enclosing Column. */
@Composable
private fun SlideFadePage(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 6 },
        exit = fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { it / 6 },
    ) { content() }
}

@Composable
private fun CircularProgress() {
    CircularProgressIndicator(color = Accent)
}

private fun GalleryItem.cloudLinkId(): String? = when (this) {
    is GalleryItem.CloudOnly -> cloud.linkId
    is GalleryItem.Synced -> cloud.linkId
    is GalleryItem.LocalOnly -> null
}

private fun GalleryItem.isIn(linkIds: Set<String>): Boolean =
    linkIds.isNotEmpty() && cloudLinkId()?.let { it in linkIds } == true

/** [this] with the photos in [linkIds] taken out, and a group left empty by that dropped. */
private fun List<Pair<String, List<GalleryItem>>>.without(
    linkIds: Set<String>,
): List<Pair<String, List<GalleryItem>>> {
    if (linkIds.isEmpty()) return this
    return mapNotNull { (label, group) ->
        group.filterNot { it.isIn(linkIds) }.takeIf { it.isNotEmpty() }?.let { label to it }
    }
}

/** The one pick a single-photo pick keeps out of [next]: the photo just added, else the first. */
internal fun singlePickOf(next: Set<GalleryItem>, current: Set<GalleryItem>): Set<GalleryItem> =
    (next.firstOrNull { it !in current } ?: next.firstOrNull())?.let { setOf(it) } ?: emptySet()
