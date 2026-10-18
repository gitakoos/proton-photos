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
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.rememberAddLabelsAction
import eu.akoos.photos.presentation.common.AppSearchField
import eu.akoos.photos.presentation.common.SearchFieldTerm
import eu.akoos.photos.presentation.common.SearchTermRow
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.PrimarySlot
import eu.akoos.photos.presentation.common.SelectionAction
import eu.akoos.photos.presentation.common.SelectionDrawer
import eu.akoos.photos.presentation.common.SelectionShareState
import eu.akoos.photos.presentation.common.addToAlbumResultText
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
import eu.akoos.photos.presentation.common.ShimmerSquare
import androidx.compose.material.icons.filled.PrivacyTip
import eu.akoos.photos.presentation.gallery.CategoryRail
import eu.akoos.photos.presentation.util.appLocale
import eu.akoos.photos.presentation.gallery.categoryLabel
import eu.akoos.photos.presentation.gallery.FilterChip
import eu.akoos.photos.presentation.gallery.LocalPeopleRail
import eu.akoos.photos.presentation.gallery.PeopleRailData
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.presentation.gallery.ContentFilterSheet
import eu.akoos.photos.presentation.gallery.GalleryAddToAlbumDialog
import eu.akoos.photos.presentation.people.AddToPersonFaceSheet
import eu.akoos.photos.presentation.gallery.GalleryFilter
import eu.akoos.photos.presentation.gallery.GalleryMultiDeleteDialog
import eu.akoos.photos.presentation.gallery.MetadataStripPickerDialog
import eu.akoos.photos.presentation.gallery.MoveToFolderHost
import eu.akoos.photos.presentation.gallery.MediaType
import eu.akoos.photos.presentation.gallery.SyncStatusFilter
import eu.akoos.photos.presentation.gallery.rememberDragMultiSelectModifier
import eu.akoos.photos.presentation.gallery.selectAllCountFor
import eu.akoos.photos.presentation.gallery.selectionGridBottomPadding
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.lock.runWhenOpen
import eu.akoos.photos.presentation.albums.LibraryTile
import eu.akoos.photos.presentation.search.components.AlbumsRow
import eu.akoos.photos.presentation.search.components.DestinationsRow
import eu.akoos.photos.presentation.search.components.JumpToMonthHeader
import eu.akoos.photos.presentation.search.components.MonthTileRow
import eu.akoos.photos.presentation.search.components.OnThisDayRow
import eu.akoos.photos.presentation.search.components.RecentRow
import eu.akoos.photos.presentation.search.components.MonthBucket
import eu.akoos.photos.presentation.search.components.buildMonthBuckets
import eu.akoos.photos.presentation.gallery.TimelineScrubber
import eu.akoos.photos.presentation.gallery.TimelineGrouping
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.util.computeOnThisDay
import eu.akoos.photos.util.onThisDayWindowDays
import java.util.Calendar

/**
 * The search run for picking photos into something (the photo picker's search) instead of acting on them:
 * every tap selects into [selected] (the picker's own selection, [onToggle] and [onSetSelection] write it),
 * nothing but the search itself shows (no selection drawer, no idle page), photos in [excludeLinkIds] are
 * never offered, and the search opens narrowed to [initialScope]. The picker owns the top bar and the tab
 * bar, so the search draws neither a header nor a back button: its field and the category chips sit at the
 * bottom, above the tab bar and above the keyboard, with no filter button.
 */
class SearchPick(
    val selected: Set<GalleryItem>,
    val onToggle: (GalleryItem) -> Unit,
    val onSetSelection: (Set<GalleryItem>) -> Unit,
    val excludeLinkIds: Set<String>,
    val initialScope: SearchScope?,
    /** What the picker draws over the top of the screen, which the results clear. */
    val topInset: Dp,
    /** What the picker draws over the bottom (its tab bar), which the search field sits above. */
    val bottomInset: Dp,
    /** Whether the results have scrolled away from the top, so the picker's bar can lift off the page. */
    val onScrolled: (Boolean) -> Unit,
)

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onPhotoClick: (List<GalleryItem>, Int) -> Unit,
    /** Opens the date + place editor for the current selection, matching the timeline's entry. */
    onEditMetadata: (items: List<GalleryItem>) -> Unit = {},
    /** Opens a matched person's page from the name suggestion row. */
    onOpenPerson: (Long) -> Unit = {},
    /** Opens one of the user's own cloud albums, from the Albums row or the album name matches. */
    onOpenAlbum: (Album) -> Unit = {},
    /** Opens a device folder by its bucket name, from the same two places. */
    onOpenDeviceFolder: (bucketName: String) -> Unit = {},
    /** Opens the page of a shortcut tile the typed name matches, from the row above the results. */
    onOpenDestination: (LibraryTile) -> Unit = {},
    /** Set when the search picks photos for a picker rather than acting on them. */
    pick: SearchPick? = null,
    vm: SearchViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val query by vm.query.collectAsStateWithLifecycle()
    val peopleSuggestions by vm.peopleSuggestions.collectAsStateWithLifecycle()
    val albumRow by vm.albumRow.collectAsStateWithLifecycle()
    val matchedAlbums by vm.albumSuggestions.collectAsStateWithLifecycle()
    val destinations by vm.destinations.collectAsStateWithLifecycle()
    val rawResults by vm.results.collectAsStateWithLifecycle()
    val searchScope by vm.scope.collectAsStateWithLifecycle()
    // A picker never offers what the target already holds.
    val pickExcluded = pick?.excludeLinkIds.orEmpty()
    val results = remember(rawResults, pickExcluded) {
        if (pickExcluded.isEmpty()) rawResults else rawResults.filterNot { item ->
            when (item) {
                is GalleryItem.CloudOnly -> item.cloud.linkId in pickExcluded
                is GalleryItem.Synced -> item.cloud.linkId in pickExcluded
                is GalleryItem.LocalOnly -> false
            }
        }
    }
    val semanticSearching by vm.semanticSearching.collectAsStateWithLifecycle()
    val filter by vm.contentFilter.collectAsStateWithLifecycle()
    val dateSuggestions by vm.dateSuggestions.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategory.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val selectedPersonId by vm.selectedPersonId.collectAsStateWithLifecycle()
    // Whether the People face bar is revealed under the category rail (mirrors the timeline).
    var peopleExpanded by remember { mutableStateOf(false) }
    val allItems by vm.allItems.collectAsStateWithLifecycle()
    val vmSelectedItems by vm.selectedItems.collectAsStateWithLifecycle()
    val selectedItems = pick?.selected ?: vmSelectedItems
    val albums by vm.albums.collectAsStateWithLifecycle()
    val pendingDeleteIntent by vm.pendingDeleteIntent.collectAsStateWithLifecycle()
    val isDeleting by vm.isDeleting.collectAsStateWithLifecycle()
    val pendingStripIntent by vm.pendingStripIntent.collectAsStateWithLifecycle()
    val multiStripState by vm.multiStripState.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val offlinePinIds by vm.offlinePinIds.collectAsStateWithLifecycle()
    val favoriteState by vm.favoriteState.collectAsStateWithLifecycle()
    val shareState by vm.sel.shareState.collectAsStateWithLifecycle()
    val isSignedIn by vm.isSignedIn.collectAsStateWithLifecycle()
    val faceEnabled by vm.faceEnabled.collectAsStateWithLifecycle()

    // Typed month and category names match in the language the page is drawn in.
    val screenLocale = appLocale()
    LaunchedEffect(screenLocale) { vm.setLocale(screenLocale) }

    // A picker is always selecting: every tap on a result picks it.
    val isSelectionMode = pick != null || selectedItems.isNotEmpty()
    // The search the picker opens starts from nothing, narrowed to the album it was opened in, if any.
    if (pick != null) {
        DisposableEffect(Unit) {
            vm.clearAll()
            vm.setScope(pick.initialScope)
            onDispose { vm.clearAll() }
        }
    }
    // In selection mode the back button cancels the selection instead of leaving the screen.
    BackHandler(enabled = pick == null && isSelectionMode) { vm.clearSelection() }

    // The name matches the floating header lists above the results.
    val showPeopleSuggestions = query.isNotBlank() && peopleSuggestions.isNotEmpty()
    // A picker narrows to the user's own albums, so an album shared with them is not offered there, and
    // the shortcut tiles lead away from the picker, so none are.
    val albumSuggestions = remember(matchedAlbums, pick == null) {
        if (pick == null) matchedAlbums
        else matchedAlbums.filterNot { it is SearchAlbum.Cloud && it.album.isSharedWithMe }
    }
    val showAlbumSuggestions = query.isNotBlank() && albumSuggestions.isNotEmpty()
    val showDestinations = pick == null && query.isNotBlank() && destinations.isNotEmpty()
    val openAlbum: (SearchAlbum) -> Unit = { entry ->
        if (pick != null) {
            // A picker narrows the search to the album, which sits in the field as a pill.
            vm.setScope(
                when (entry) {
                    is SearchAlbum.Cloud -> SearchScope("c:${entry.album.linkId}", entry.album.name)
                    is SearchAlbum.Folder -> SearchScope("f:${entry.folder.name}", entry.folder.name)
                },
            )
            vm.setQuery("")
        } else when (entry) {
            is SearchAlbum.Cloud -> onOpenAlbum(entry.album)
            is SearchAlbum.Folder -> onOpenDeviceFolder(entry.folder.name)
        }
    }
    val openPerson: (Long) -> Unit = if (pick != null) {
        { personId -> vm.onPersonSelected(personId); vm.setQuery("") }
    } else {
        onOpenPerson
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // The VM builds the system-share intent off the UI thread; the screen launches the chooser. It can
    // arrive under the app lock, once cloud originals finish downloading, and the chooser would open above
    // the lock, so it waits for the unlock.
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    val appLock = LocalAppLock.current
    LaunchedEffect(Unit) {
        vm.shareIntent.collect { intent ->
            appLock.runWhenOpen {
                runCatching { context.startActivity(android.content.Intent.createChooser(intent, shareChooserTitle)) }
            }
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
    var showAddToPersonSheet by remember { mutableStateOf(false) }
    // The picker's own "New album" row, which names an album and then adds the selection to it.
    var showCreateAlbumInline by remember { mutableStateOf(false) }
    // Move the device selection into another folder, logged-out, device-data only. The picker's
    // targets and the write consent ride the shared host; the selection is snapshotted in the VM.
    var showMoveSheet by remember { mutableStateOf(false) }
    val moveTargetFolders by vm.moveTargetFolders.collectAsStateWithLifecycle()
    val pendingMoveIntent by vm.pendingMoveIntent.collectAsStateWithLifecycle()
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
        // The system trash dialog would open above the app lock, so the launch waits for the unlock.
        appLock.runWhenOpen {
            runCatching {
                deletePermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }.onFailure { vm.clearPendingDeleteIntent() }
        }
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
    val idleListState = rememberLazyListState()
    // The whole search header (back button, field, filter, categories) hides while the active list is
    // scrolled DOWN, and slides back on a scroll up or once scrolling stops, like the timeline's chrome
    // behaviour. Only one of the two lists is on screen at a time, so whichever is scrolling drives it.
    var resultsScrollingDown by remember { mutableStateOf(false) }
    LaunchedEffect(resultsGridState) {
        var prevIndex = resultsGridState.firstVisibleItemIndex
        var prevOffset = resultsGridState.firstVisibleItemScrollOffset
        snapshotFlow { resultsGridState.firstVisibleItemIndex to resultsGridState.firstVisibleItemScrollOffset }
            .collect { (idx, off) ->
                resultsScrollingDown = idx > prevIndex || (idx == prevIndex && off > prevOffset)
                prevIndex = idx
                prevOffset = off
            }
    }
    var idleScrollingDown by remember { mutableStateOf(false) }
    LaunchedEffect(idleListState) {
        var prevIndex = idleListState.firstVisibleItemIndex
        var prevOffset = idleListState.firstVisibleItemScrollOffset
        snapshotFlow { idleListState.firstVisibleItemIndex to idleListState.firstVisibleItemScrollOffset }
            .collect { (idx, off) ->
                idleScrollingDown = idx > prevIndex || (idx == prevIndex && off > prevOffset)
                prevIndex = idx
                prevOffset = off
            }
    }
    // A real finger drag on either list dismisses the keyboard (clears the field focus), so browsing
    // results hides the header the same as the timeline. The auto-scroll a growing result set emits
    // while typing is not a drag, so typing keeps its focus.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(resultsGridState, idleListState) {
        merge(
            resultsGridState.interactionSource.interactions,
            idleListState.interactionSource.interactions,
        ).collect { if (it is DragInteraction.Start) focusManager.clearFocus() }
    }
    // Match the timeline exactly: the header shows while the active list is scrolled UP or sits at the
    // top, and hides while it is scrolled down. It does NOT pop back on a mere stop, which is what read
    // as an instant, off-tempo reappearance.
    val showingResults = rememberUpdatedState(results.isNotEmpty())
    val headerVisible by remember {
        derivedStateOf {
            if (showingResults.value) {
                !resultsScrollingDown || resultsGridState.firstVisibleItemIndex == 0
            } else {
                !idleScrollingDown || idleListState.firstVisibleItemIndex == 0
            }
        }
    }
    var searchFocused by remember { mutableStateOf(false) }

    // The pieces of the header, defined once so the picker can lay them out at the bottom instead.
    // Whatever already narrows the results is a pill in a row beneath the field: the chosen category (the
    // rail steps aside for it), the type and storage filters and the date, each removable on its own.
    val searchLocale = appLocale()
    val categoryName = if (selectedCategory == GalleryFilter.All) null else categoryLabel(selectedCategory)
    val photosName = stringResource(R.string.gallery_filter_photos)
    val videosName = stringResource(R.string.filter_type_videos)
    val localName = stringResource(R.string.filter_sync_local)
    val backedUpName = stringResource(R.string.filter_sync_backedup)
    val cloudName = stringResource(R.string.filter_sync_cloud)
    val offlineName = stringResource(R.string.filter_offline)
    // The chosen category takes the rail's own place as a pill; the other filters share the row above it.
    val categoryTerm = categoryName?.let { SearchFieldTerm(it) { vm.onCategorySelected(GalleryFilter.All) } }
    val filterTerms = buildList {
        searchScope?.let { add(SearchFieldTerm(it.name) { vm.setScope(null) }) }
        when (filter.mediaType) {
            MediaType.PhotosOnly -> add(SearchFieldTerm(photosName) { vm.setContentFilter(filter.copy(mediaType = MediaType.All)) })
            MediaType.VideosOnly -> add(SearchFieldTerm(videosName) { vm.setContentFilter(filter.copy(mediaType = MediaType.All)) })
            MediaType.All -> Unit
        }
        when (filter.syncStatus) {
            SyncStatusFilter.LocalOnly -> add(SearchFieldTerm(localName) { vm.setContentFilter(filter.copy(syncStatus = SyncStatusFilter.All)) })
            SyncStatusFilter.BackedUp -> add(SearchFieldTerm(backedUpName) { vm.setContentFilter(filter.copy(syncStatus = SyncStatusFilter.All)) })
            SyncStatusFilter.CloudOnly -> add(SearchFieldTerm(cloudName) { vm.setContentFilter(filter.copy(syncStatus = SyncStatusFilter.All)) })
            SyncStatusFilter.Offline -> add(SearchFieldTerm(offlineName) { vm.setContentFilter(filter.copy(syncStatus = SyncStatusFilter.All)) })
            SyncStatusFilter.All -> Unit
        }
        dateTermLabel(filter.year, filter.month, filter.day, filter.dayEnd, searchLocale, filter.dateFrom, filter.dateTo)?.let { label ->
            add(SearchFieldTerm(label) {
                vm.setContentFilter(filter.copy(year = null, month = null, day = null, dayEnd = null, dateFrom = null, dateTo = null))
            })
        }
    }
    val searchTerms = listOfNotNull(categoryTerm) + filterTerms
    val searchField: @Composable (Modifier) -> Unit = { modifier ->
        AppSearchField(
            query = query,
            onQueryChange = vm::setQuery,
            filtered = searchTerms.isNotEmpty(),
            onBackspaceWhenEmpty = searchTerms.lastOrNull()?.onRemove,
            // With semantic search on the field matches any description, so "by filename"
            // would read as wrong; fall back to it only when semantic search is off.
            placeholder = stringResource(R.string.search_placeholder),
            modifier = modifier,
            onFocusChanged = { searchFocused = it },
        )
    }
    // Tap-to-filter category chips (same row as the timeline). Tapping a category narrows the
    // results immediately, without opening the filter sheet.
    // The shared category rail also renders the People chip + face bar when a person is
    // available, driven through the LocalPeopleRail CompositionLocal exactly like the timeline.
    val peopleActive = peopleExpanded || selectedPersonId != null
    val peopleRail = remember(people, selectedPersonId, peopleActive) {
        PeopleRailData(
            people = people,
            selectedPersonId = selectedPersonId,
            active = peopleActive,
            onToggle = {
                if (peopleActive) {
                    peopleExpanded = false
                    vm.onPersonSelected(null)
                } else {
                    peopleExpanded = true
                }
            },
            onPersonSelected = { id ->
                peopleExpanded = true
                vm.onPersonSelected(if (selectedPersonId == id) null else id)
            },
        )
    }
    val categoryRow: @Composable () -> Unit = {
        CompositionLocalProvider(LocalPeopleRail provides peopleRail) {
            CategoryRail(
                selectedFilter = selectedCategory,
                onFilterSelected = vm::onCategorySelected,
                shape = RoundedCornerShape(14.dp),
            )
        }
    }
    val dateRow: @Composable () -> Unit = {
        // A few year digits typed into the field are offered as the years they could become, to pick one,
        // which turns it into a pill and leaves the field free for words and names. A month or a whole
        // year becomes the pill by itself.
        if (dateSuggestions.isNotEmpty()) {
            val dateLocale = appLocale()
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(dateSuggestions) { suggestion ->
                    FilterChip(
                        label = dateTermLabel(suggestion.year, suggestion.month, null, null, dateLocale).orEmpty(),
                        selected = false,
                        onClick = { vm.applyDateSuggestion(suggestion) },
                    )
                }
            }
        }
    }
    val density = LocalDensity.current
    // What a picker draws over the bottom: its tab bar, or the keyboard once it is up.
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    // The keyboard animates its height, so the field follows the larger of the two: it never dips below the
    // tab bar's top while the keyboard slides in or out, and then snaps back.
    val pickBottomBase = pick?.let { maxOf(it.bottomInset, imeBottom + 8.dp) } ?: 0.dp
    if (pick != null) {
        val scrolled by remember {
            derivedStateOf { resultsGridState.firstVisibleItemIndex > 0 || resultsGridState.firstVisibleItemScrollOffset > 0 }
        }
        val resultsScrolled = scrolled && results.isNotEmpty()
        LaunchedEffect(resultsScrolled) { pick.onScrolled(resultsScrolled) }
        DisposableEffect(Unit) { onDispose { pick.onScrolled(false) } }
    }
    // The field (46dp), the pill row above it while there is one (24dp and its gap), and the gaps around.
    val pickBottom = pickBottomBase + 62.dp + if (searchTerms.isNotEmpty()) 32.dp else 0.dp


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg0),
    ) {
      // Search content fills the screen; the floating header overlays it (added after this
      // Column in the Box so it draws on top). The status-bar inset plus a top pad clears
      // the collapsed header row so the search field never slips under the title pill.
      // The content fills the screen and scrolls up under the floating header. The header is drawn
      // after the content (below) so it overlays it, and its measured height pads the content clear
      // of it so the first row never starts hidden.
      // Seed with an estimate so the very first frame already insets the content, instead of flashing
      // it under the header for the frame before onSizeChanged reports the real height.
      var headerHeightPx by remember { mutableIntStateOf(with(density) { 150.dp.roundToPx() }) }
      val headerPad = with(density) { headerHeightPx.toDp() }

      Box(modifier = Modifier.fillMaxSize()) {
        val isIdle = SearchFilter.nothingAsked(
            query, filter, selectedCategory, personSelected = selectedPersonId != null, scoped = searchScope != null,
        )
        // The people and albums whose name matches the query are rows of the results, so a name that
        // matched no photo still has something to show.
        val hasMatches = showPeopleSuggestions || showAlbumSuggestions || showDestinations
        val phase = when {
            pick != null && results.isEmpty() && isIdle -> 0
            results.isEmpty() && isIdle -> 1
            results.isEmpty() && !hasMatches && semanticSearching -> 2
            results.isEmpty() && !hasMatches -> 3
            else -> 4
        }
        if (phase == 0) {
            // A picker's search offers the people and the albums to narrow to, the way typing their name
            // does, and no recents or months: it is looking for something by name.
            if (people.isEmpty() && albumRow.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = headerPad, bottom = pickBottom),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.search_empty_idle),
                        color = colors.fgMute,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = headerPad + 4.dp, bottom = pickBottom),
                ) {
                    if (people.isNotEmpty()) {
                        item(key = "pick_people_section") {
                            PeopleSuggestionRow(people = people, onOpenPerson = openPerson)
                        }
                    }
                    if (albumRow.isNotEmpty()) {
                        item(key = "pick_albums_section") {
                            AlbumsRow(entries = albumRow, onOpen = openAlbum)
                        }
                    }
                }
            }
        } else if (phase == 1) {
            // Idle empty state — surface "On this day" memories + a month-jump grid
            // so the page is useful without typing anything. Both sections live inside
            // one LazyColumn so they scroll as a unit and benefit from item-level
            // recycling when there are many month buckets.
            // Heavy on a large library — compute off the main thread so the idle page renders
            // immediately and the sections fill in a moment later, instead of blocking the first
            // frame on a full-library walk.
            // Both producers assign `value` below; @Suppress silences a lint false-positive it
            // raises on the `value = withContext { … }` form.
            val searchContext = LocalContext.current
            val onThisDayDays by remember(searchContext) { searchContext.onThisDayWindowDays() }.collectAsState(initial = 0)
            @Suppress("ProduceStateDoesNotAssignValue")
            val onThisDay by produceState(emptyList<Pair<Int, List<GalleryItem>>>(), allItems, onThisDayDays) {
                value = withContext(Dispatchers.Default) { computeOnThisDay(allItems, onThisDayDays) }
            }
            @Suppress("ProduceStateDoesNotAssignValue")
            val monthBuckets by produceState(emptyList<MonthBucket>(), allItems) {
                value = withContext(Dispatchers.Default) { buildMonthBuckets(allItems) }
            }
            val recent = remember(allItems) { allItems.take(6) }
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = idleListState,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(top = headerPad + 4.dp, bottom = 24.dp),
            ) {
                if (recent.isNotEmpty()) {
                    item(key = "recent_section") {
                        RecentRow(
                            items = recent,
                            onPhotoClick = onPhotoClick,
                        )
                    }
                }
                // The user's own albums and the device folders, as the Albums tab lays them out.
                if (albumRow.isNotEmpty()) {
                    item(key = "albums_section") {
                        AlbumsRow(
                            entries = albumRow,
                            onOpen = openAlbum,
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
                topPadding = headerPad + 8.dp,
                bottomPadding = 24.dp + navBottom,
                minItemsToShow = 8,
            )
            }
        } else if (phase == 2) {
            // A content search is running and nothing keyword-matched yet (say "sunset", which no
            // filename matches): show shimmering placeholder tiles rather than a blank "no results"
            // that the matches then pop into once the model returns.
            SemanticSearchSkeletonGrid(topPadding = headerPad)
        } else if (phase == 3) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = headerPad, bottom = pickBottom),
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
                // Full-width rows ahead of the first photo, which the viewer's return position counts.
                val leadingRows = (if (showDestinations) 1 else 0) +
                    (if (showPeopleSuggestions) 1 else 0) + (if (showAlbumSuggestions) 1 else 0)
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
                    onSelectionChange = pick?.onSetSelection ?: vm::setSelection,
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
                    leadingSlots = leadingRows,
                    keyOf = { it.stableId },
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = resultsGridState,
                    contentPadding = PaddingValues(
                        start = if (seamless) 0.dp else 6.dp,
                        end = if (seamless) 0.dp else 6.dp,
                        top = headerPad + 4.dp,
                        bottom = if (pick != null) pickBottom else selectionGridBottomPadding(12.dp, isSelectionMode, gridPadsNavigationBar = true),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 4.dp),
                    verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 4.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (pick == null) Modifier.navigationBarsPadding() else Modifier)
                        .then(dragSelectModifier),
                ) {
                    // The shortcut tiles the name matches, then the people whose name matches, then the albums
                    // and folders whose name matches: rows of the results, so they scroll with the photos
                    // instead of riding the header.
                    if (showDestinations) {
                        item(key = "match_destinations", span = { GridItemSpan(maxLineSpan) }) {
                            DestinationsRow(tiles = destinations, onOpen = onOpenDestination)
                        }
                    }
                    if (showPeopleSuggestions) {
                        item(key = "match_people", span = { GridItemSpan(maxLineSpan) }) {
                            PeopleSuggestionRow(people = peopleSuggestions, onOpenPerson = openPerson)
                        }
                    }
                    if (showAlbumSuggestions) {
                        // The same Albums row the idle page shows, with only the albums whose name matches.
                        item(key = "match_albums", span = { GridItemSpan(maxLineSpan) }) {
                            AlbumsRow(entries = albumSuggestions, onOpen = openAlbum)
                        }
                    }
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
                                } else if (pick != null) {
                                    pick.onToggle(item)
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
                    topPadding = headerPad + 8.dp,
                    bottomPadding = if (pick != null) pickBottom else 24.dp,
                    keyOf = { keyOf(it) },
                )
            }
        }
      }

      // Floating header, drawn after the content so it overlays and lets the grid scroll under it.
      // The whole thing slides away on a downward scroll and returns on scroll up / stop, like the
      // timeline chrome. Its measured height feeds `headerPad` above so the first row clears it; the
      // height sticks while hidden, so the content padding does not jump as the header slides.
      AnimatedVisibility(
        // While the field is focused the header stays mounted, so a results-driven scroll does not
        // dispose the text field mid-typing and drop the keyboard; hide-on-scroll still applies otherwise.
        visible = headerVisible || searchFocused,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
      ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Measure the FULL header (status-bar inset + paddings + content) so the content inset
                // matches the visible bar. onSizeChanged MUST sit above the paddings; below them it
                // reports only the children, leaving the inset short and the content tucked under the bar.
                .onSizeChanged { headerHeightPx = it.height }
                // Scrim under the floating bar so buttons stay legible over photos: solid at the top,
                // still darkening through the category row, fading out just below it. A picker has no bar
                // of its own here, so its header carries no scrim under the picked-photos strip.
                .then(
                    if (pick != null) Modifier
                    else Modifier.background(
                        Brush.verticalGradient(
                            0.0f to colors.bg0,
                            0.55f to colors.bg0.copy(alpha = 0.82f),
                            0.9f to colors.bg0.copy(alpha = 0.4f),
                            1.0f to colors.bg0.copy(alpha = 0f),
                        ),
                    ),
                )
                // A picker draws its own bar over the top, which the header clears; its field sits at the
                // bottom instead of in this row, and it has no back button or filter button.
                .then(
                    if (pick != null) Modifier.padding(top = pick.topInset)
                    else Modifier.statusBarsPadding().padding(top = 8.dp),
                ),
        ) {
            if (pick == null) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Back button matches the search field's surface (not the darker pill) so the two
                // read as one control row.
                IconBubble(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.onboarding_back),
                    onClick = { if (pick == null && isSelectionMode) vm.clearSelection() else onBack() },
                    background = colors.chipUnselectedBg,
                    borderColor = colors.line,
                )
                searchField(Modifier.weight(1f))
            }

            if (pick == null) {
                // The pill row and the rail ease in and out, so the photos below glide up or down with the
                // header instead of jumping.
                AnimatedVisibility(
                    visible = filterTerms.isNotEmpty(),
                    enter = expandVertically(tween(HEADER_EASE_MS)) + fadeIn(tween(HEADER_EASE_MS)),
                    exit = shrinkVertically(tween(HEADER_EASE_MS)) + fadeOut(tween(HEADER_EASE_MS)),
                ) {
                    // Keeps the last pills while the row eases out.
                    val held = remember { object { var terms = filterTerms } }
                    if (filterTerms.isNotEmpty()) held.terms = filterTerms
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        SearchTermRow(held.terms, Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // A picker's search has no category chips: it is looking for something by name. A picked
            // category takes the rail's place as a pill of the same height and indent, the two fading into
            // one another where they sit, so the active filter is always in view and nothing else moves.
            if (pick == null) {
                val heldCategory = remember { object { var term = categoryTerm } }
                if (categoryTerm != null) heldCategory.term = categoryTerm
                Crossfade(
                    targetState = selectedCategory == GalleryFilter.All,
                    animationSpec = tween(HEADER_EASE_MS),
                    label = "categorySlot",
                ) { railShown ->
                    if (railShown) {
                        categoryRow()
                    } else {
                        SearchTermRow(listOfNotNull(heldCategory.term), Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                    }
                }
            }

            dateRow()

            // Breathing room below the header so the content clears the category bar instead of
            // tucking right under it, and the scrim keeps a soft tail.
            Spacer(modifier = Modifier.height(10.dp))
        }
      }

        // The filter button floats at the bottom right, so the field above can run the full width. It takes
        // the accent while a filter the sheet holds is on, and steps aside for the selection drawer.
        val sheetFilterActive = filter.syncStatus != SyncStatusFilter.All || filter.mediaType != MediaType.All ||
            filter.year != null || filter.dateFrom != null || selectedCategory != GalleryFilter.All
        if (pick == null && !isSelectionMode) {
            IconBubble(
                icon = Icons.Filled.FilterList,
                contentDescription = stringResource(R.string.filter_title),
                onClick = { showFilterSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 24.dp, bottom = 36.dp),
                diameter = 52.dp,
                iconSize = 22.dp,
                // Opaque while lit: the tinted fill alone would let the photos show through.
                background = if (sheetFilterActive) colors.accent.copy(alpha = 0.18f).compositeOver(colors.bg0) else PillBg,
                borderColor = if (sheetFilterActive) colors.accent else PillBorder,
                tint = if (sheetFilterActive) colors.accent else FgPrimary,
            )
        }

        // A picker's field sits at the bottom, above its tab bar and the keyboard; everything else of the
        // search stays where it is in the header.
        if (pick != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = pickBottomBase + 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchTermRow(searchTerms, Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    searchField(Modifier.weight(1f))
                    // The same filter sheet the search opens elsewhere: categories, dates and, signed in, the
                    // backed-up state.
                    IconBubble(
                        icon = Icons.Filled.FilterList,
                        contentDescription = stringResource(R.string.filter_title),
                        onClick = { showFilterSheet = true },
                        diameter = 46.dp,
                        iconSize = 20.dp,
                        background = if (sheetFilterActive) colors.accent.copy(alpha = 0.18f).compositeOver(colors.bg0) else PillBg,
                        borderColor = if (sheetFilterActive) colors.accent else PillBorder,
                        tint = if (sheetFilterActive) colors.accent else FgPrimary,
                    )
                }
            }
        }

        // A multi-select delete blocks the screen behind a progress drawer so a second tap can't
        // fire into a half-finished delete.
        val opDeletingLabel = stringResource(R.string.op_deleting)
        eu.akoos.photos.presentation.common.BlockingOperationSheet(
            if (isDeleting) eu.akoos.photos.presentation.common.OperationProgress(0, 0, opDeletingLabel, indeterminate = true) else null,
        )

        // Selection-mode overlay: the shared drawer, so every bulk action sits in one place here
        // exactly as it does on the timeline, album and device-folder surfaces. Its header's Select
        // all takes in every result.
        val selectAllCount = selectAllCountFor(total = results.size, selected = selectedItems.size)
        var showStripPicker by remember { mutableStateOf(false) }
        // Which way the offline row goes, so it names the press rather than the state: a pin while
        // anything pinnable in the selection is still un-pinned, a removal once none is.
        val offlinePinsSelection = remember(selectedItems, offlinePinIds) {
            offlineTurnsOn(offlinePinnableLinkIds(selectedItems), offlinePinIds)
        }
        val sharing = shareState as? SelectionShareState.Working
        val addLabelsAction = rememberAddLabelsAction(
            items = remember(selectedItems) { selectedItems.toList() },
            onDone = { msg ->
                scope.launch { snackbarHostState.showSnackbar(msg) }
                vm.clearSelection()
            },
        )
        val searchSelectionActions = buildList {
            // Rings while cloud originals download for the chooser and takes no second tap meanwhile.
            add(
                SelectionAction(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.sel_label_share),
                    working = sharing != null,
                    progress = sharing?.let { if (it.total > 0) it.done.toFloat() / it.total else 0f },
                    enabled = sharing == null,
                    onClick = { vm.shareSelected() },
                    slot = PrimarySlot.Share,
                )
            )
            // Add-to-album needs a Drive destination, so it stays behind a signed-in session.
            if (isSignedIn) {
                add(
                    SelectionAction(
                        icon = Icons.Default.PhotoAlbum,
                        label = stringResource(R.string.gallery_add_to_album),
                        onClick = { showAddToAlbumSheet = true },
                        slot = PrimarySlot.Organize,
                    )
                )
            }
            // Add-to-person runs on-device and can name a new person, so it follows face recognition
            // alone, for a guest too. Only the account-backed actions in this list stay behind isSignedIn.
            if (faceEnabled) {
                add(
                    SelectionAction(
                        icon = Icons.Default.Person,
                        label = stringResource(R.string.gallery_add_to_person),
                        onClick = { showAddToPersonSheet = true },
                    )
                )
            }
            // Move the device selection into another folder, the logged-out counterpart to
            // Add-to-album, offered only without an account and on the Android 10+ MediaStore floor.
            if (!isSignedIn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(
                    SelectionAction(
                        icon = Icons.AutoMirrored.Filled.DriveFileMove,
                        label = stringResource(R.string.move_to_folder),
                        onClick = { showMoveSheet = true },
                        slot = PrimarySlot.Organize,
                    )
                )
            }
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
            addLabelsAction?.let(::add)
            // Back up the not-yet-uploaded (LocalOnly) photos in the selection. Needs a Drive
            // destination, so it stays behind a signed-in session.
            if (isSignedIn && anyLocalOnly(selectedItems)) {
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
                    slot = PrimarySlot.Delete,
                )
            )
        }
        SelectionDrawer(
            visible = pick == null && isSelectionMode,
            items = remember(selectedItems) { selectedItems.toList() },
            actions = searchSelectionActions,
            onDismiss = { vm.clearSelection() },
            // Scrolling the results collapses the drawer, so reaching past it to carry on through
            // them needs no deliberate pull or tap first.
            contentScrolling = resultsGridState.isScrollInProgress,
            modifier = Modifier.align(Alignment.BottomCenter),
            selectAllCount = selectAllCount,
            onSelectAll = if (selectAllCount != null) {
                { vm.selectAll() }
            } else {
                null
            },
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
                onCreateNew = {
                    showAddToAlbumSheet = false
                    showCreateAlbumInline = true
                },
                onCloudAlbumSelected = { album ->
                    showAddToAlbumSheet = false
                    vm.addSelectedToAlbum(album.linkId) { joined, queued ->
                        val msg = addToAlbumResultText(context.resources, joined, queued, album.name)
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    }
                },
                onDismiss = { showAddToAlbumSheet = false },
            )
        }

        // Add-to-person runs the same face-first flow as the timeline, on-device, so a guest has it too.
        if (showAddToPersonSheet && selectedItems.isNotEmpty()) {
            val personPhotos = remember(selectedItems) { selectedItems.toList() }
            AddToPersonFaceSheet(
                photos = personPhotos,
                onDone = {
                    showAddToPersonSheet = false
                    vm.clearSelection()
                },
                onDismiss = { showAddToPersonSheet = false },
            )
        }

        // Name a brand-new album for the selection, then create it and add the photos to it — the
        // same two steps the timeline's picker runs, so the row means the same thing on both.
        if (showCreateAlbumInline) {
            eu.akoos.photos.presentation.gallery.GalleryNewAlbumDialog(
                onDismiss = { showCreateAlbumInline = false },
                onCreate = { name ->
                    showCreateAlbumInline = false
                    vm.createAlbumThenAddSelected(name) { joined, queued, error ->
                        val msg = error ?: addToAlbumResultText(context.resources, joined, queued, name)
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    }
                },
            )
        }

        // Move-to-folder host: the picker, the new-folder name dialog, the write-consent launcher a
        // foreign-file move needs and the completion snackbar, all logged-out and device-only. The
        // selection is snapshotted in the ViewModel, so the host carries only the picked name.
        MoveToFolderHost(
            targetFolders = moveTargetFolders,
            show = showMoveSheet,
            pendingMoveIntent = pendingMoveIntent,
            moveConfirmation = vm.moveConfirmation,
            onPick = { vm.moveSelectedToFolder(it) },
            onCreate = { vm.createFolderWithPhotos(it) },
            onGranted = { vm.onMovePermissionGranted() },
            onClear = { vm.clearPendingMove() },
            onDismiss = { showMoveSheet = false },
            snackbarHostState = snackbarHostState,
        )

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = filterSheetState,
            containerColor = colors.sheetBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            ContentFilterSheet(
                currentFilter = filter,
                currentCategory = selectedCategory,
                onApply = { vm.setContentFilter(it) },
                onCategorySelected = vm::onCategorySelected,
                onDismiss = { showFilterSheet = false },
                // The sheet holds the categories too, so every filter can be reached from this one place; the
                // media type stays a category of its own (Videos).
                showCategorySection = true,
                showMediaTypeSection = false,
                showRelativeDates = true,
                // Signed out there is only on-device media, so the backed-up / cloud chips are hidden.
                showSyncStatusSection = isSignedIn,
            )
        }
    }
}

/** How long the header's pill row and category rail take to ease in or out. */
private const val HEADER_EASE_MS = 220

private fun keyOf(item: GalleryItem): String = when (item) {
    is GalleryItem.LocalOnly -> "L:" + item.local.uri
    is GalleryItem.Synced    -> "S:" + item.local.uri
    is GalleryItem.CloudOnly -> "C:" + item.cloud.linkId
}

/**
 * Placeholder grid shown while a content (semantic) search runs and nothing has keyword-matched yet, so
 * a query like "sunset" that no filename matches shimmers rather than flashing "no results" and then
 * popping the photos in. A fixed three-column block of shimmer squares, matching the results grid's
 * columns, side padding and spacing so the switch to the real grid is seamless. Non-scrolling: it lives
 * for only the length of one search.
 */
@Composable
private fun SemanticSearchSkeletonGrid(topPadding: Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(start = 6.dp, end = 6.dp, top = topPadding + 4.dp),
    ) {
        repeat(4) { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                repeat(3) {
                    ShimmerSquare(modifier = Modifier.weight(1f), cornerRadius = 10.dp)
                }
            }
            if (row < 3) Spacer(Modifier.height(4.dp))
        }
    }
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
