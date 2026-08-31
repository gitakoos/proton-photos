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

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.draw.blur
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.AlbumSortMode
import eu.akoos.photos.domain.usecase.CategorizeItem
import eu.akoos.photos.domain.usecase.CloudMetadataSaveController
import eu.akoos.photos.domain.usecase.CloudSavePhase
import eu.akoos.photos.presentation.common.AlbumMembership
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.albumMembershipState
import eu.akoos.photos.presentation.common.anyLocalOnly
import eu.akoos.photos.presentation.common.deleteConfirmRows
import eu.akoos.photos.presentation.common.deleteRowDescRes
import eu.akoos.photos.presentation.common.deleteRowTitleRes
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.HideConfirmSheet
import eu.akoos.photos.presentation.common.DenseGridWarningDialog
import eu.akoos.photos.presentation.common.EditFieldSheet
import eu.akoos.photos.presentation.common.EmptyState
import eu.akoos.photos.presentation.common.ErrorPopup
import eu.akoos.photos.presentation.common.CloudMetadataSaveDrawer
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.common.SecondaryButton
import eu.akoos.photos.presentation.common.SelectionDrawer
import eu.akoos.photos.presentation.common.message
import eu.akoos.photos.presentation.common.shareOutcome
import eu.akoos.photos.util.sanitizeErrorMessage
import eu.akoos.photos.util.copySensitiveText
import eu.akoos.photos.presentation.albums.AlbumsScreen
import eu.akoos.photos.presentation.albums.AlbumsViewModel
import eu.akoos.photos.presentation.shared.SharedScreen
import eu.akoos.photos.presentation.shared.SharedViewModel
import eu.akoos.photos.presentation.viewer.ManagePublicLinkSheet
import eu.akoos.photos.presentation.viewer.PhotoShareSheet
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Accent2
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.ArcTrack
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.DeleteTint
import eu.akoos.photos.presentation.theme.ErrorChipBg
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.theme.pillShape
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> "${n / 1_000_000}M"
    n >= 1_000 -> buildString {
        val s = n.toString()
        val offset = s.length % 3
        s.forEachIndexed { i, c ->
            if (i > 0 && (i - offset) % 3 == 0) append(' ')
            append(c)
        }
    }
    else -> n.toString()
}

internal fun buildContentFilterSummary(
    filter: ContentFilter,
    photosLabel: String,
    videosLabel: String,
    localLabel: String,
    backedUpLabel: String,
    cloudLabel: String,
): String? {
    if (filter == ContentFilter()) return null
    val parts = buildList {
        when (filter.mediaType) {
            MediaType.PhotosOnly -> add(photosLabel)
            MediaType.VideosOnly -> add(videosLabel)
            else -> {}
        }
        when (filter.syncStatus) {
            SyncStatusFilter.LocalOnly -> add(localLabel)
            SyncStatusFilter.BackedUp  -> add(backedUpLabel)
            SyncStatusFilter.CloudOnly -> add(cloudLabel)
            else -> {}
        }
        if (filter.year != null) add("${filter.year}")
        if (filter.month != null) {
            val monthName = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(
                java.util.Calendar.getInstance().apply { set(java.util.Calendar.MONTH, filter.month - 1) }.time
            )
            add(
                when {
                    filter.day != null && filter.dayEnd != null -> "$monthName ${filter.day}-${filter.dayEnd}"
                    filter.day != null -> "$monthName ${filter.day}"
                    else -> monthName
                }
            )
        }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

/**
 * Where the system back gesture goes from the gallery's top-level pager (#89).
 *
 * The three tabs are pages of one destination rather than separate back-stack entries, so nothing
 * pops them. Standing on a secondary tab, back returns to the configured landing tab. Standing on
 * the landing tab it is left alone and the app exits, which is the right answer for back at the
 * start of the shell.
 *
 * Selection mode is excluded so the selection handler keeps the press and clears the selection
 * instead. That handler is composed after this one and so already wins on registration order; the
 * check here holds the behaviour even if the two are ever reordered.
 *
 * Returns the page to return to, or null when back is not intercepted. [landingTab] is coerced into
 * the pager's range so a stray stored value can't target a page that isn't there.
 */
internal fun galleryBackTarget(
    currentPage: Int,
    landingTab: Int,
    isSelectionMode: Boolean,
): Int? {
    if (isSelectionMode) return null
    return landingTab.coerceIn(0, 2).takeIf { it != currentPage }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onPhotoClick: (items: List<GalleryItem>, index: Int, hiddenCloudLinkIds: Set<String>) -> Unit,
    onAlbumClick: (Album) -> Unit = {},
    /** Opens an owned album with its share drawer already up, from the Albums-tab long-press sheet. */
    onAlbumShareClick: (Album) -> Unit = {},
    /** Opens an album to carry out one action on arrival, from the Albums-tab long-press sheet. */
    onAlbumActionClick: (
        album: Album,
        action: eu.akoos.photos.presentation.albums.AlbumOpenAction,
    ) -> Unit = { _, _ -> },
    onDeviceFolderClick: (bucketName: String) -> Unit = {},
    /** Opens a device folder to carry out one action on arrival, from the Albums-tab long-press sheet. */
    onDeviceFolderActionClick: (
        bucketName: String,
        action: eu.akoos.photos.presentation.folders.DeviceFolderOpenAction,
    ) -> Unit = { _, _ -> },
    onSettingsClick: () -> Unit,
    /** Opens the Activity screen on its Uploads tab, from the avatar's active-upload indicator. */
    onOpenUploads: () -> Unit = {},
    /** Opens the Activity screen on its Downloads tab, from the avatar's active-download indicator. */
    onOpenDownloads: () -> Unit = {},
    onHiddenAlbumClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onCalendarClick: () -> Unit = {},
    /** Opens the Memories screen — reached from the pinned card at the top of the Albums tab. */
    onMemoriesClick: () -> Unit = {},
    /** Opens the Timeline filter screen — reached from the Albums tab's filter button now that
     *  the obsolete All/Backed-up album filter is gone (device + cloud albums show together). */
    onOpenTimelineFilter: () -> Unit = {},
    /** Opens the date + place editor for the current multi-selection. Gated to an all-device-only
     *  selection (matching the Strip action), so every handed item is editable. */
    onEditMetadata: (items: List<GalleryItem>) -> Unit = {},
    onCreateCollage: (items: List<GalleryItem>) -> Unit = {},
    /** Opens the device photo picker for the logged-out "New folder" flow, carrying the folder name
     *  the user just typed. The picked device photos come back via [newFolderPickedItems]. */
    onStartNewFolderPick: (String) -> Unit = {},
    /** The device photos the picker returned for the new folder, paired with [newFolderPickedName].
     *  Consumed once into a move, then cleared via [onNewFolderPickConsumed]. */
    newFolderPickedItems: List<GalleryItem>? = null,
    newFolderPickedName: String? = null,
    onNewFolderPickConsumed: () -> Unit = {},
    /** Non-null when the user tapped the home-screen photo widget. The screen waits for
     *  the items flow to populate, finds the matching item, and forwards to
     *  [onPhotoClick]. [onPendingWidgetPhotoConsumed] is invoked exactly once after
     *  navigation so a back-pop doesn't re-trigger the viewer. */
    pendingWidgetPhotoUri: String? = null,
    onPendingWidgetPhotoConsumed: () -> Unit = {},
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadedCloudLinkIds by viewModel.downloadedCloudLinkIds.collectAsStateWithLifecycle()
    // Fresh per-cell decrypted thumbnail URLs (see GalleryViewModel.thumbnailUrls). The timeline model
    // no longer carries them on the row, so the grid resolves each cloud cell's URL from this map.
    val thumbnailUrls by viewModel.thumbnailUrls.collectAsStateWithLifecycle()
    val albumsViewModel: AlbumsViewModel = hiltViewModel()
    val albumsState by albumsViewModel.uiState.collectAsStateWithLifecycle()
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val sharedUiState by sharedViewModel.uiState.collectAsStateWithLifecycle()
    // Live view of the app-scoped cloud-metadata-save batch. Null once nothing is running or the
    // drawer was sent to the background, so the sheet below mounts only while there is progress to show.
    val cloudSaveVm: CloudSaveDrawerViewModel = hiltViewModel()
    val cloudSaveUi by cloudSaveVm.ui.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val appColors = AppColors.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // People rail: the face bar is open either because the chip is toggled on (this flag) or because a
    // person is selected. Local UI state, the person filter itself lives in the ViewModel.
    var peopleExpanded by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    // Mosaic uses a staggered grid; its scroll state is hoisted here so the screen-level behaviours
    // keyed on scroll position (re-tap scroll-to-top, overlay auto-hide, look-ahead prefetch) follow
    // whichever grid is actually showing. The fixed grid keeps using [gridState] unchanged.
    val staggeredState = rememberLazyStaggeredGridState()
    val mosaicGrid by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.MOSAIC_GRID] ?: false }
    }.collectAsState(initial = false)
    // Opt-in: keep each bottom tab where it was last scrolled when switching between them, so only a
    // re-tap of the already-active tab returns to the top. Off by default, so any tab tap resets to top.
    val keepScrollOnTabSwitch by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.KEEP_SCROLL_ON_TAB_SWITCH] == true }
    }.collectAsStateWithLifecycle(false)
    // The scroll state the visible Photos grid is driven by; everything below observes this so the
    // mosaic path is no longer inert.
    val activeFirstVisibleItemIndex: () -> Int = {
        if (mosaicGrid) staggeredState.firstVisibleItemIndex else gridState.firstVisibleItemIndex
    }
    val activeFirstVisibleItemScrollOffset: () -> Int = {
        if (mosaicGrid) staggeredState.firstVisibleItemScrollOffset else gridState.firstVisibleItemScrollOffset
    }
    val albumsGridState = rememberLazyGridState()
    val tabScope = rememberCoroutineScope()
    // Three top-level tabs (Photos / Albums / Shared), hosted in a pager so they can be swiped
    // between as well as tapped. Seeded from the saved tab so a config change restores the page.
    // A local-only session (no account) drops the cloud-only Shared page, leaving Photos + Albums.
    val pagerState = rememberPagerState(initialPage = selectedTab) { if (state.isSignedIn) 3 else 2 }
    // Two-way sync between the pager and [selectedTab] (which drives the header rail + dock highlight).
    // Settling on a page — by swipe or by the dock's animateScrollToPage — adopts it as the active tab;
    // a tap path updates selectedTab and animates the pager below.
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != selectedTab) selectedTab = pagerState.settledPage
    }
    // ── Landing tab (app open) ────────────────────────────────────────────────
    // Seed the opening tab from the saved preference ONCE per process. The guard is a
    // rememberSaveable flag so it survives config changes: on a rotation [selectedTab] is
    // restored from its own saved value and this effect does not re-run, leaving the user's
    // manual swipes intact. Default 0 (Photos) reproduces the historical behaviour.
    var landingTabApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (landingTabApplied) return@LaunchedEffect
        landingTabApplied = true
        val landing = context.settingsDataStore.data
            .map { (it[SettingsKeys.LANDING_TAB] ?: 0).coerceIn(0, if (state.isSignedIn) 2 else 1) }
            .first()
        if (landing != selectedTab) {
            selectedTab = landing
            pagerState.scrollToPage(landing)
        }
    }
    // The same preference, observed live, as the target the back gesture returns to (#89). Separate
    // from the seeding effect above, which reads it once and must keep its one-shot guard: this one
    // has to stay current, so picking another landing tab in Settings retargets back straight away
    // rather than at the next start. Initial 0 matches the seeding default.
    val landingTab by remember {
        context.settingsDataStore.data.map { (it[SettingsKeys.LANDING_TAB] ?: 0).coerceIn(0, if (state.isSignedIn) 2 else 1) }
    }.collectAsState(initial = 0)
    var sharedFilter by remember { mutableStateOf(SharedFilter.SharedWithMe) }
    var albumFilter by remember { mutableStateOf(AlbumDisplayFilter.All) }
    // Albums-tab inline search: the typed query (kept across a config change) and whether the rail's
    // search bar is open. Hoisted beside the filter so the header rail and the Albums page read one
    // source. Arranging takes the whole rail, so it and the search are mutually exclusive: opening the
    // arrange mode clears an open search, and while it is on the rail hides the search entry.
    var albumQuery by rememberSaveable { mutableStateOf("") }
    var albumSearchActive by remember { mutableStateOf(false) }
    var albumReorderActive by remember { mutableStateOf(false) }
    // Albums-tab view filter: default narrowing, a remember-last toggle, and the last picked value.
    val albumsDefaultFilter by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.ALBUMS_DEFAULT_FILTER] ?: 0 }
    }.collectAsState(initial = 0)
    val albumsRememberLastFilter by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.ALBUMS_REMEMBER_LAST_FILTER] ?: false }
    }.collectAsState(initial = false)
    val albumsLastFilter by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.ALBUMS_LAST_FILTER] ?: 0 }
    }.collectAsState(initial = 0)
    // Only drives the sheet's selection; the grid reads this key on its own side.
    val albumsSortMode by remember {
        context.settingsDataStore.data.map { AlbumSortMode.fromOrdinal(it[SettingsKeys.ALBUMS_SORT_MODE]) }
    }.collectAsState(initial = AlbumSortMode.Default)
    // How many album covers sit per row on the Albums tab (default 2). Seeded from the synchronous
    // boot mirror so returning to the tab opens at the stored size on the first frame instead of
    // flashing 2 columns and letting the cover cards' animateItem reshuffle into the real layout.
    val albumColumns = rememberAlbumColumns()
    // Re-resolve the filter each time the pager reaches the Albums page: last-used when remembering,
    // otherwise the configured default. Leaving and returning therefore resets to the default
    // (remember-last off) or restores the last pick (remember-last on), rather than holding whatever
    // was left on screen from a prior visit. Keyed on currentPage (which advances during the swipe)
    // and resolved from the already-collected state so the target filter is applied before the page
    // settles, with no visible "All" flash from awaiting a fresh DataStore read.
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != 1) return@LaunchedEffect
        val ordinal = if (albumsRememberLastFilter) albumsLastFilter else albumsDefaultFilter
        albumFilter = AlbumDisplayFilter.entries[ordinal.coerceIn(0, AlbumDisplayFilter.entries.lastIndex)]
    }
    var showAlbumsFilterSheet by remember { mutableStateOf(false) }
    val albumsFilterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeEmailFilter by remember { mutableStateOf<String?>(null) }
    var showEmailFilterSheet by remember { mutableStateOf(false) }
    // Bump to ask AlbumsScreen to open its create-album dialog (the Albums-tab "New album" pill).
    var albumCreateSignal by remember { mutableIntStateOf(0) }

    // ── Thumbnail look-ahead ──────────────────────────────────────────────────
    // Feed the decrypt scheduler the rows just past the bottom of the viewport in the
    // scroll direction so they are already warm when the user reaches them. The window
    // is anchored on the last visible cell's stable key (matched back to filteredItems)
    // rather than the raw grid index, so interleaved date headers and the memories card
    // don't skew the offset. Prefetch sits behind the visible band in the scheduler, so
    // this never delays an on-screen cell.
    run {
        // Rows to warm ahead of the viewport — roughly the next two screens at the densest
        // grid zoom, so a steady scroll always meets pre-decrypted thumbnails.
        val prefetchWindow = 60
        val cloudLinkIdOf: (GalleryItem) -> String? = { gi ->
            when (gi) {
                is GalleryItem.CloudOnly -> gi.cloud.linkId
                is GalleryItem.Synced    -> gi.cloud.linkId
                is GalleryItem.LocalOnly -> null
            }
        }
        LaunchedEffect(gridState, staggeredState, mosaicGrid, state.filteredItems) {
            val rendered = state.filteredItems
            if (rendered.isEmpty()) return@LaunchedEffect
            // Stable-key → filteredItems index, for the two cell key shapes the grid emits.
            val indexByLinkId = HashMap<String, Int>(rendered.size)
            rendered.forEachIndexed { idx, gi -> cloudLinkIdOf(gi)?.let { indexByLinkId[it] = idx } }
            var lastAnchor = -1
            snapshotFlow {
                // Anchor on the last visible cell of whichever grid is showing so prefetch warms the
                // rows just past the viewport in mosaic mode too, not only the fixed grid.
                val last = if (mosaicGrid)
                    staggeredState.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String
                else
                    gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String
                last?.removePrefix("cloud_")?.removePrefix("synced_")
            }.collect { anchorLinkId ->
                val anchorIdx = anchorLinkId?.let { indexByLinkId[it] } ?: return@collect
                // Only look ahead while moving forward; reverse scroll keeps warm rows warm.
                if (anchorIdx <= lastAnchor) { lastAnchor = anchorIdx; return@collect }
                lastAnchor = anchorIdx
                val from = anchorIdx + 1
                val to = (anchorIdx + 1 + prefetchWindow).coerceAtMost(rendered.size)
                if (from >= to) return@collect
                val ids = rendered.subList(from, to).mapNotNull(cloudLinkIdOf)
                if (ids.isNotEmpty()) viewModel.prefetchThumbnails(ids)
            }
        }
    }

    // ── "On this day" thumbnails ──────────────────────────────────────────────
    // The memories row renders above the grid and lives outside the scrolling cell list,
    // so its tiles never fire a per-cell decrypt request. Queue their cloud thumbnails at
    // visible priority the moment the row's contents are known so the card fills instead
    // of showing blank tiles. Keyed on the source list so it re-runs when items load.
    LaunchedEffect(state.onThisDayGroups) {
        val memoryLinkIds = state.onThisDayGroups
            .flatMap { (_, yearItems) -> yearItems }
            .mapNotNull { gi ->
                when (gi) {
                    is GalleryItem.CloudOnly -> gi.cloud.linkId
                    is GalleryItem.Synced    -> gi.cloud.linkId
                    is GalleryItem.LocalOnly -> null
                }
            }
        if (memoryLinkIds.isNotEmpty()) viewModel.requestThumbnailsVisible(memoryLinkIds)
    }

    val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        val permanentlyDenied = !granted && context is android.app.Activity &&
            results.keys.none { ActivityCompat.shouldShowRequestPermissionRationale(context, it) }
        viewModel.onPermissionResult(granted, permanentlyDenied)
    }

    // Separate launcher from media so a notification denial doesn't taint the media verdict.
    // Denial isn't blocking — the worker still runs without a visible progress notification.
    var showNotificationRationale by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) showNotificationRationale = true
    }
    val notificationsBlockedMsg = stringResource(R.string.notifications_blocked_snackbar)
    val openSettingsAction = stringResource(R.string.notifications_blocked_open_settings)
    // Back-button intercept on a secondary top-level tab: return to the landing tab rather than
    // leave the app (#89). The tabs are pages of this one destination, so the graph has nothing to
    // pop and back would otherwise exit from Albums or Shared. Settled page, not current, so a
    // half-finished swipe doesn't decide the answer. This composes BEFORE the selection handler on
    // purpose: back dispatch runs the callbacks in reverse registration order, so the later one
    // wins and selection mode keeps the press.
    val backTarget = galleryBackTarget(pagerState.settledPage, landingTab, state.isSelectionMode)
    androidx.activity.compose.BackHandler(enabled = backTarget != null) {
        val target = backTarget ?: return@BackHandler
        // Set the tab and slide the pager, and nothing else. Routing this through the dock's
        // onTabSelected would also force the destination grid to item 0 and throw away the
        // scroll position the tab was left at.
        selectedTab = target
        tabScope.launch { pagerState.animateScrollToPage(target) }
    }
    // Back-button intercept in selection mode: clear the selection instead of letting
    // the OS pop the screen out of the gallery. Without this guard a back press aimed at
    // "cancel selection" throws away the multi-select work, since the drawer's close bubble
    // is not what a back-press user reaches for.
    androidx.activity.compose.BackHandler(enabled = state.isSelectionMode) {
        viewModel.clearSelection()
    }

    LaunchedEffect(showNotificationRationale) {
        if (!showNotificationRationale) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = notificationsBlockedMsg,
            actionLabel = openSettingsAction,
            duration = androidx.compose.material3.SnackbarDuration.Long,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
            runCatching { context.startActivity(intent) }
        }
        showNotificationRationale = false
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(mediaPermissions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Widget-tap routing: once the items flow has populated and there's a pending widget
    // URI to open, find the matching item in the full list and forward to the viewer.
    // Falls back to a single-item viewer if the URI isn't in the list (e.g. the photo is
    // outside the current filter or was deleted between tap and resolution). Cleared
    // exactly once so back-popping doesn't re-trigger.
    LaunchedEffect(pendingWidgetPhotoUri, state.items.size) {
        val uri = pendingWidgetPhotoUri ?: return@LaunchedEffect
        if (state.items.isEmpty()) return@LaunchedEffect
        val idx = state.items.indexOfFirst { item ->
            when (item) {
                is GalleryItem.LocalOnly -> item.local.uri == uri
                is GalleryItem.Synced    -> item.local.uri == uri
                is GalleryItem.CloudOnly -> false
            }
        }
        if (idx >= 0) {
            onPhotoClick(state.items, idx, state.hiddenCloudLinkIds)
        }
        onPendingWidgetPhotoConsumed()
    }
    // Trigger a sync/refresh immediately after the user grants photo permission so photos appear
    // without requiring an app restart. Non-forced: re-entering the gallery re-runs this effect, and
    // a forced refresh each time would pile full library walks onto the refresh mutex.
    LaunchedEffect(state.permissionState) {
        if (state.permissionState == PermissionState.Granted) viewModel.refresh(force = false)
    }
    // One-shot MANAGE_MEDIA prompt on first cold-start with media access. Lets the editor
    // overwrite the original photo directly. Tracked in DataStore so we don't ask again.
    LaunchedEffect(state.permissionState) {
        if (state.permissionState != PermissionState.Granted) return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@LaunchedEffect
        val prompted = runCatching {
            context.settingsDataStore.data.first()[SettingsKeys.MANAGE_MEDIA_PROMPTED] == true
        }.getOrDefault(false)
        if (prompted) return@LaunchedEffect
        if (android.provider.MediaStore.canManageMedia(context)) return@LaunchedEffect
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA)
            )
        }
        runCatching {
            context.settingsDataStore.edit { it[SettingsKeys.MANAGE_MEDIA_PROMPTED] = true }
        }
    }
    // Upload / download / delete failures originate as raw exception messages —
    // route them through the unified [ErrorPopup] so the user can read multi-line
    // backend payloads, copy them out for a bug report, and dismiss explicitly
    // (not via a 4-second auto-snackbar). Snackbars below stay for transient
    // confirmations like "Photo added to album".
    if (state.error != null) {
        ErrorPopup(
            title = stringResource(R.string.gallery_error_title),
            message = sanitizeErrorMessage(state.error),
            onDismiss = viewModel::clearError,
            onCopy = {},
        )
    }

    var headerHeightPx by remember { mutableStateOf(0) }
    val headerHeightDp = with(LocalDensity.current) { headerHeightPx.toDp() }

    // ── Scroll direction detection ────────────────────────────────────────────
    // Tracked via snapshotFlow rather than a side-effecting derivedStateOf: the previous position
    // lives in the collector's local vars (not snapshot state written from inside a derivation),
    // and the flow re-baselines whenever the active grid (mosaic toggle) or the current tab changes.
    // The first emission equals the starting position, so "scrolling down" resets to false on
    // arrival at a tab, and only flips true once that tab is actually scrolled down.
    var isPhotosScrollingDown by remember { mutableStateOf(false) }
    LaunchedEffect(mosaicGrid, pagerState.currentPage) {
        var prevIndex = activeFirstVisibleItemIndex()
        var prevOffset = activeFirstVisibleItemScrollOffset()
        isPhotosScrollingDown = false
        snapshotFlow { activeFirstVisibleItemIndex() to activeFirstVisibleItemScrollOffset() }
            .collect { (idx, off) ->
                isPhotosScrollingDown = idx > prevIndex || (idx == prevIndex && off > prevOffset)
                prevIndex = idx
                prevOffset = off
            }
    }

    var isAlbumsScrollingDown by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) {
        var prevIndex = albumsGridState.firstVisibleItemIndex
        var prevOffset = albumsGridState.firstVisibleItemScrollOffset
        isAlbumsScrollingDown = false
        snapshotFlow { albumsGridState.firstVisibleItemIndex to albumsGridState.firstVisibleItemScrollOffset }
            .collect { (idx, off) ->
                isAlbumsScrollingDown = idx > prevIndex || (idx == prevIndex && off > prevOffset)
                prevIndex = idx
                prevOffset = off
            }
    }

    // The single shared header above the pager auto-hides on the active tab's scroll. It follows the
    // pager's currentPage (which crosses the midpoint mid-swipe), so the header tracks the tab being
    // swiped to rather than waiting for the swipe to settle. This derivedStateOf is now a pure read.
    val showOverlays by remember(mosaicGrid) {
        derivedStateOf {
            when (pagerState.currentPage) {
                // When the grid is replaced by an EmptyState (filtered to nothing, or the whole
                // library deleted) there's no content to scroll back to index 0 — so always keep the
                // header + nav dock visible. Otherwise a scrolled-down delete-all leaves the overlays
                // hidden with no way to bring them back short of restarting the app.
                0 -> state.filteredItems.isEmpty() ||
                    !isPhotosScrollingDown || activeFirstVisibleItemIndex() == 0
                1 -> !isAlbumsScrollingDown || albumsGridState.firstVisibleItemIndex == 0
                else -> true // Shared tab has no scroll hiding yet
            }
        }
    }

    // ── Filter bottom sheet state ─────────────────────────────────────────────
    var showFilterSheet by remember { mutableStateOf(false) }
    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Grouping is controlled exclusively by the photo grid's pinch gesture (see
    // PhotoGrid). Pinching zooms across (cols, grouping) pairs in one motion.

    // ── Media delete permission launcher ──────────────────────────────────────
    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeletePermissionGranted()
        else viewModel.clearPendingDeleteIntent()
    }
    LaunchedEffect(state.pendingDeleteIntent) {
        val pi = state.pendingDeleteIntent ?: return@LaunchedEffect
        // Guard the launch so an OEM that throws on a large or foreign trash IntentSender fails
        // gracefully instead of force-closing.
        runCatching { deletePermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
            .onFailure { viewModel.clearPendingDeleteIntent() }
    }

    // ── Metadata-strip write-permission launcher ──────────────────────────────
    // Foreign files in a batch strip need an Android 10+ write consent; RESULT_OK
    // replays the strip on the deferred URIs (mirrors the delete launcher above).
    val stripPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.retryPendingStrip()
        else viewModel.clearPendingStripIntent()
    }
    LaunchedEffect(state.pendingStripIntent) {
        val pi = state.pendingStripIntent ?: return@LaunchedEffect
        runCatching { stripPermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
            .onFailure { viewModel.clearPendingStripIntent() }
    }

    // ── Move-to-folder write-permission launcher ──────────────────────────────
    // Moving a file the app does not own needs a one-shot system write consent; RESULT_OK replays
    // the move on the deferred URIs (mirrors the delete + strip launchers above). The intent here is
    // an IntentSender straight from the use case, not a PendingIntent, so it is launched directly.
    val pendingMoveIntent by viewModel.pendingMoveIntent.collectAsStateWithLifecycle()
    val moveToFolderPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onMovePermissionGranted()
        else viewModel.clearPendingMove()
    }
    LaunchedEffect(pendingMoveIntent) {
        val sender = pendingMoveIntent ?: return@LaunchedEffect
        runCatching { moveToFolderPermissionLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .onFailure { viewModel.clearPendingMove() }
    }

    // ── Multi-select delete sheet ─────────────────────────────────────────────
    var showMultiDeleteSheet by remember { mutableStateOf(false) }
    // The selection's hide split while its confirmation is up, null when none is. Holding the split
    // rather than a flag is what lets the sheet describe the photos the tap was made on.
    var hideConfirmSplit by remember {
        mutableStateOf<eu.akoos.photos.data.hidden.HiddenFolderRecords.HideSplit?>(null)
    }
    val multiDeleteState = state.multiDeleteState

    LaunchedEffect(multiDeleteState) {
        if (multiDeleteState is MultiDeleteState.Done) {
            // A hide committed via the system-permission dialog signals through this channel;
            // surface any items that couldn't be copied into the vault.
            if (state.hideFailureCount > 0) {
                snackbarHostState.showSnackbar(
                    context.resources.getQuantityString(
                        R.plurals.gallery_hide_partial_failed, state.hideFailureCount, state.hideFailureCount,
                    ),
                )
            }
            viewModel.resetMultiDeleteState()
        }
        if (multiDeleteState is MultiDeleteState.Failed) {
            snackbarHostState.showSnackbar(multiDeleteState.message)
            viewModel.resetMultiDeleteState()
        }
    }

    // Hide has its own state channel, separate from the delete row's spinner; surface its
    // terminal states here. On success, disclose that backed-up photos keep their Drive
    // copies, since hiding only affects this device's gallery.
    val multiHideState = state.multiHideState
    LaunchedEffect(multiHideState) {
        if (multiHideState is MultiDeleteState.Done) {
            if (state.hideFailureCount > 0) {
                snackbarHostState.showSnackbar(
                    context.resources.getQuantityString(
                        R.plurals.gallery_hide_partial_failed, state.hideFailureCount, state.hideFailureCount,
                    ),
                )
            } else if (state.hideCloudNoticePending) {
                snackbarHostState.showSnackbar(context.getString(R.string.hide_cloud_copy_notice))
            }
            viewModel.resetMultiHideState()
        }
        if (multiHideState is MultiDeleteState.Failed) {
            snackbarHostState.showSnackbar(multiHideState.message)
            viewModel.resetMultiHideState()
        }
    }

    val multiDownloadState = state.multiDownloadState
    // A download reports itself the moment it starts. Its progress lives on the Activity screen,
    // so without this the selection clears and nothing on this screen says the work began. Keyed
    // on the transition into Working rather than on the state, which would fire on every tick.
    val downloadStarted = multiDownloadState is MultiDownloadState.Working
    val downloadStartedMsg = stringResource(R.string.download_started_background)
    LaunchedEffect(downloadStarted) {
        if (downloadStarted) snackbarHostState.showSnackbar(downloadStartedMsg)
    }
    LaunchedEffect(multiDownloadState) {
        if (multiDownloadState is MultiDownloadState.Done) {
            val msg = if (multiDownloadState.failed == 0)
                if (multiDownloadState.succeeded == 1)
                    context.getString(R.string.gallery_download_done_singular)
                else
                    context.getString(R.string.gallery_download_done, multiDownloadState.succeeded)
            else
                context.getString(R.string.gallery_download_partial, multiDownloadState.succeeded, multiDownloadState.failed)
            snackbarHostState.showSnackbar(msg)
            viewModel.resetMultiDownloadState()
        }
    }

    val multiShareState = state.multiShareState
    // Photos that could not be prepared never reach the chooser, so the batch says what it managed.
    LaunchedEffect(multiShareState) {
        if (multiShareState is MultiShareState.Done) {
            shareOutcome(multiShareState.shared, multiShareState.failed).message()
                ?.let { snackbarHostState.showSnackbar(it.resolve(context)) }
            viewModel.resetMultiShareState()
        }
    }
    // A mixed selection that includes cloud-only photos has to download those originals before they
    // can leave the app, so we warn first. A pure-local selection shares straight away.
    var showShareCloudWarning by remember { mutableStateOf(false) }
    var showBackUpConfirm by remember { mutableStateOf(false) }
    // Hand the VM-built ACTION_SEND_MULTIPLE intent to the system chooser. One-shot collect;
    // the share pill spinner is driven by multiShareState above, not by this flow.
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    LaunchedEffect(Unit) {
        viewModel.shareIntent.collect { intent ->
            runCatching {
                context.startActivity(Intent.createChooser(intent, shareChooserTitle))
            }
        }
    }

    // Result snackbar for a "Make available offline" batch — the pins themselves apply
    // optimistically, this only reports how many blobs landed. One-shot collect.
    val offlineRemovedMsg = stringResource(R.string.offline_removed)
    LaunchedEffect(Unit) {
        viewModel.offlineBatchResult.collect { count ->
            when {
                count > 0 -> snackbarHostState.showSnackbar(
                    context.resources.getQuantityString(R.plurals.offline_batch_result, count, count),
                )
                count < 0 -> snackbarHostState.showSnackbar(offlineRemovedMsg)
            }
        }
    }

    // A batch favourite says nothing when every heart lands: the tiles and the drawer row show it.
    // Only a write Drive refused reaches here, since the hearts go back with no other explanation.
    LaunchedEffect(Unit) {
        viewModel.favoriteFailure.collect { snackbarHostState.showSnackbar(it) }
    }

    // A completed move to a device folder confirms where the files landed. One-shot collect.
    val movedToFolderTpl = stringResource(R.string.moved_to_folder)
    LaunchedEffect(Unit) {
        viewModel.moveConfirmation.collect { folderName ->
            snackbarHostState.showSnackbar(movedToFolderTpl.format(folderName))
        }
    }

    // ── Unified share drawer (selection) ──────────────────────────────────────
    // The toolbar Share opens the same menu the viewer uses: Send to another app,
    // Share with people (→ add-to-album), and — only for a single cloud-backed photo —
    // a Public link row that hands off to the manage-link sheet.
    var showShareSheet by remember { mutableStateOf(false) }
    var showManageLinkSheet by remember { mutableStateOf(false) }
    val shareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val manageLinkSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val publicLinkState by viewModel.publicLinkState.collectAsStateWithLifecycle()
    val linkCopiedMsg = stringResource(R.string.share_link_copied)
    val passwordSetMsg = stringResource(R.string.share_password_set)
    val passwordRemovedMsg = stringResource(R.string.share_password_removed)
    // Public link is per-photo, so the row is offered only when exactly one cloud-backed photo
    // is selected; otherwise the drawer is Send-to-app + Share-with-people.
    val shareSinglePhotoHasLink = remember(state.selectedItems) {
        viewModel.singleSelectedCloudLinkId() != null
    }
    // The Public link row appears for any single selection (matching the viewer); a local-only
    // photo shows the "back it up first" note because canCreateLink is false for it.
    val shareSingleSelected = remember(state.selectedItems) { state.selectedItems.size == 1 }

    // Fires the actual ACTION_SEND_MULTIPLE share, warning first when the selection includes
    // cloud-only photos that must be downloaded before they can leave the app.
    val launchSelectionShare: () -> Unit = {
        if (state.selectedItems.any { it is GalleryItem.CloudOnly })
            showShareCloudWarning = true
        else
            viewModel.shareSelected()
    }

    // ── Add-to-album multi-action ─────────────────────────────────────────────
    // Drives the picker sheet, the consent dialog and the new-album inline create.
    var showAddToAlbumSheet by remember { mutableStateOf(false) }
    var showAddToPersonSheet by remember { mutableStateOf(false) }
    var showCreateAlbumInline by remember { mutableStateOf(false) }
    val addToAlbumSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val addToPersonSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── Move-to-folder multi-action ───────────────────────────────────────────
    // The target picker (existing folders + New folder) and the typed-name dialog it hands off to.
    var showMoveToFolderSheet by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    val moveTargetFolders by viewModel.moveTargetFolders.collectAsStateWithLifecycle()

    // ── New device folder (logged-out) ────────────────────────────────────────
    // The Albums-tab "New folder" pill opens a name dialog; on confirm the device photo picker opens,
    // and the picked photos come back via newFolderPickedItems to be moved into DCIM/<name>/.
    var showNewFolderNameDialog by remember { mutableStateOf(false) }

    // Move the device photos the picker handed back into the just-named folder, then clear the carrier
    // so a back-pop doesn't replay it. Mirrors the collage pendingAdd handoff; only LocalOnly/Synced
    // carry a device uri to move.
    LaunchedEffect(newFolderPickedItems) {
        val items = newFolderPickedItems
        val name = newFolderPickedName
        if (items != null && name != null) {
            val uris = items.mapNotNull {
                when (it) {
                    is GalleryItem.LocalOnly -> it.local.uri
                    is GalleryItem.Synced -> it.local.uri
                    else -> null
                }
            }
            if (uris.isNotEmpty()) viewModel.createFolderWithPhotos(name, uris)
            onNewFolderPickConsumed()
        }
    }

    // No MediaStore consent dialog: add-to-album is a DataStore append, not a file move.

    val addToAlbumState = state.addToAlbumState
    LaunchedEffect(addToAlbumState) {
        when (addToAlbumState) {
            is AddToAlbumState.Done -> {
                // localMoved carries the count of local-only photos queued to upload, then join
                // the album once backed up (the add is async for those). cloudAdded joined now.
                val cloudAdded = addToAlbumState.cloudAdded
                val queued = addToAlbumState.localMoved
                // The blocking drawer already confirms a clean add, so skip the redundant result
                // snackbar for it; only surface one when there's something more to say.
                val msg: String? = when {
                    // Genuine failures the album couldn't accept — disclose the skip count so the
                    // user doesn't think the missing items disappeared.
                    addToAlbumState.skipped > 0 -> context.resources.getQuantityString(
                        R.plurals.gallery_add_to_album_partial,
                        addToAlbumState.skipped,
                        cloudAdded, addToAlbumState.albumName, addToAlbumState.skipped,
                    )
                    // Anything queued to upload-then-join now drives the live progress sheet, so the
                    // thin "queued" snackbar would be redundant — suppress it for those cases.
                    queued > 0 -> null
                    // Clean success — the drawer was enough, no extra message.
                    cloudAdded > 0 -> null
                    // Edge case: nothing added and nothing queued but the op still "succeeded"
                    // (e.g. the photos were already in the picked album). Surface a snackbar anyway.
                    else -> context.getString(R.string.gallery_added_to_album, 0, addToAlbumState.albumName)
                }
                if (msg != null) snackbarHostState.showSnackbar(msg)
                viewModel.resetAddToAlbumState()
            }
            is AddToAlbumState.Failed -> {
                snackbarHostState.showSnackbar(addToAlbumState.message)
                viewModel.resetAddToAlbumState()
            }
            else -> {}
        }
    }

    // Strip-metadata multi-action — surfaces a result snackbar and clears state on terminal.
    val multiStripState = state.multiStripState
    LaunchedEffect(multiStripState) {
        when (multiStripState) {
            is MultiStripState.Done -> {
                val msg = if (multiStripState.skipped == 0)
                    context.getString(R.string.gallery_stripped_metadata, multiStripState.stripped)
                else
                    context.getString(R.string.gallery_stripped_with_skipped,
                        multiStripState.stripped, multiStripState.skipped)
                snackbarHostState.showSnackbar(msg)
                viewModel.resetMultiStripState()
            }
            is MultiStripState.Failed -> {
                snackbarHostState.showSnackbar(multiStripState.message)
                viewModel.resetMultiStripState()
            }
            else -> {}
        }
    }

    val isOnlineNow by viewModel.isOnline.collectAsStateWithLifecycle()
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()
    val newsUnread by viewModel.newsUnread.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appColors.bg0)
    ) {
        // ── CONTENT ──────────────────────────────────────────────────────────
        // Only the tab content lives in the pager, so a swipe slides the grid / albums / shared list
        // beneath the fixed header and dock. The pager's natural snap supplies the slide transition and
        // the deliberate-swipe threshold (a small nudge settles back, so accidental flicks don't change
        // tabs). Horizontal swipe is disabled in selection mode so the Photos grid's drag-multi-select
        // keeps the horizontal/diagonal drag. Content is inset by the shared header's height so it lays
        // out beneath it, exactly as the floating header expects.
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !state.isSelectionMode,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
        when (page) {
            0 -> {
                val pullState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    state = pullState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {}
                ) {
                    // Coarse phase so the cross-fade fires only on a real skeleton/empty/content change,
                    // never on scroll or a thumbnail update. The grid's scroll state is remembered
                    // outside this block, so a fade never resets the position.
                    val galleryPhase = when {
                        state.isLoading && state.filteredItems.isEmpty() -> 0
                        state.filteredItems.isEmpty() && state.items.isNotEmpty() -> 1
                        state.filteredItems.isEmpty() -> 2
                        else -> 3
                    }
                    Crossfade(targetState = galleryPhase, label = "galleryContent") { phase ->
                    when (phase) {
                        0 ->
                            // Skeleton placeholder grid — matches the 3-col PhotoGrid layout so
                            // there's no visual jump when real content arrives.
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.fillMaxSize().padding(top = headerHeightDp),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                items(24) {
                                    eu.akoos.photos.presentation.common.ShimmerBox(
                                        modifier = Modifier.fillMaxWidth().aspectRatio(0.85f),
                                        cornerRadius = 4.dp,
                                    )
                                }
                            }
                        1 ->
                            // A category / content filter matched nothing. Show a neutral "no
                            // matches" line — NOT the "sync your photos" empty state, which wrongly
                            // implies the whole library is empty when it is only filtered. The
                            // category rail stays in the header so the user can clear the filter.
                            EmptyState(
                                title = stringResource(R.string.search_empty_no_results),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = headerHeightDp),
                            )
                        2 ->
                            EmptyState(
                                title = stringResource(R.string.gallery_empty_title),
                                subtitle = stringResource(R.string.gallery_empty_subtitle),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = headerHeightDp),
                            )
                        else ->
                            PhotoGrid(
                                items              = state.filteredItems,
                                allItems           = state.items,
                                monthGroups        = state.monthGroups,
                                dayGroups          = state.dayGroups,
                                onThisDayGroups    = state.onThisDayGroups,
                                gridState          = gridState,
                                staggeredState     = staggeredState,
                                topContentPadding  = headerHeightDp,
                                permissionState    = state.permissionState,
                                onPermissionGrant  = { permissionLauncher.launch(mediaPermissions) },
                                onPhotoClick       = { items, idx -> onPhotoClick(items, idx, state.hiddenCloudLinkIds) },
                                selectedItems      = state.selectedItems,
                                isSelectionMode    = state.isSelectionMode,
                                // Freeze drag-to-select while a bulk delete is running so a new sweep
                                // can't mutate the selection the in-flight delete is operating on.
                                dragSelectEnabled  = multiDeleteState !is MultiDeleteState.Working,
                                onToggleSelect     = viewModel::toggleSelection,
                                onToggleGroup      = viewModel::toggleGroup,
                                onSelectionChange  = viewModel::setSelection,
                                initialZoomLevel   = state.initialZoomLevel,
                                gridRememberLast   = state.gridRememberLast,
                                gridDefaultColumns = state.gridDefaultColumns,
                                onZoomLevelChanged = viewModel::setZoomLevel,
                                hiddenCloudLinkIds = state.hiddenCloudLinkIds,
                                downloadedCloudLinkIds = downloadedCloudLinkIds,
                                favoriteIds = state.favoriteIds,
                                offlinePinIds = state.offlinePinIds,
                                thumbnailUrls = thumbnailUrls,
                                onRequestThumbnail = viewModel::requestThumbnailDecrypt,
                                onCancelThumbnail  = viewModel::cancelThumbnailDecrypt,
                                denseGridWarningDismissed = state.denseGridWarningDismissed,
                                onDismissDenseGridWarning = viewModel::dismissDenseGridWarning,
                            )
                    }
                    }
                }
            }
            1 -> AlbumsScreen(
                topPadding = headerHeightDp,
                gridState = albumsGridState,
                columns = albumColumns,
                onAlbumClick = onAlbumClick,
                onAlbumShareClick = onAlbumShareClick,
                onAlbumActionClick = onAlbumActionClick,
                onDeviceFolderClick = onDeviceFolderClick,
                onDeviceFolderActionClick = onDeviceFolderActionClick,
                onHideDeviceFolder = viewModel::requestHideFolder,
                onMemoriesClick = onMemoriesClick,
                createRequestSignal = albumCreateSignal,
                displayFilter = albumFilter,
                query = albumQuery,
                onReorderModeChange = { active ->
                    albumReorderActive = active
                    // Search and arrange cannot share the rail; opening arrange closes an open search.
                    if (active) { albumSearchActive = false; albumQuery = "" }
                },
            )
            2 -> SharedScreen(
                topPadding = headerHeightDp,
                filter = sharedFilter,
                activeEmailFilter = activeEmailFilter,
                onAlbumClick = onAlbumClick,
            )
        }
        }

        // ── FLOATING HEADER (normal mode) ─────────────────────────────────────
        // One shared header sits above the pager — the pager swipes only the content beneath it. It is
        // driven by pagerState.currentPage (which flips at the swipe midpoint), so the per-tab rails
        // transition DURING the swipe rather than after it settles. Crossfade swaps the per-tab content
        // and animateContentSize animates the height between the taller Photos header (2 rows + category
        // rail) and the shorter Albums/Shared ones, so the shrink/grow is smooth instead of a snap.
        // People rail data handed to the category rail (inside the header) through a CompositionLocal,
        // so the People chip + face bar render without new header parameters. The bar is open when the
        // chip is toggled on or a person is selected; toggling it off also clears any person filter.
        val peopleActive = peopleExpanded || state.selectedPersonId != null
        val peopleRail = remember(state.people, state.selectedPersonId, peopleActive) {
            PeopleRailData(
                people = state.people,
                selectedPersonId = state.selectedPersonId,
                active = peopleActive,
                onToggle = {
                    if (peopleActive) {
                        peopleExpanded = false
                        viewModel.onPersonSelected(null)
                    } else {
                        peopleExpanded = true
                    }
                },
                onPersonSelected = { id ->
                    peopleExpanded = true
                    viewModel.onPersonSelected(if (state.selectedPersonId == id) null else id)
                },
            )
        }
        AnimatedVisibility(
            visible = showOverlays && !state.isSelectionMode,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(modifier = Modifier.animateContentSize()) {
                Crossfade(targetState = pagerState.currentPage, label = "headerTab") { page ->
                    CompositionLocalProvider(LocalPeopleRail provides peopleRail) {
                    GalleryHeader(
                        selectedTab = page,
                        galleryState = state,
                        albumsState = albumsState,
                        sharedFilter = sharedFilter,
                        activeEmailFilter = activeEmailFilter,
                        isOnlineNow = isOnlineNow,
                        onFilterSelected = viewModel::onFilterSelected,
                        onSearchClick = onSearchClick,
                        onCalendarClick = onCalendarClick,
                        onClearContentFilter = { viewModel.setContentFilter(ContentFilter()) },
                        onHiddenAlbumClick = onHiddenAlbumClick,
                        // The Photos-tab filter icon opens the content-filter drawer (sync status
                        // + date), the same sheet the Search screen shows.
                        onShowAlbumsFilterSheet = { showFilterSheet = true },
                        onNewAlbumClick = { albumCreateSignal++ },
                        onNewLocalFolder = { showNewFolderNameDialog = true },
                        albumFilter = albumFilter,
                        onAlbumFilterSelected = { picked ->
                            albumFilter = picked
                            tabScope.launch {
                                context.settingsDataStore.edit { it[SettingsKeys.ALBUMS_LAST_FILTER] = picked.ordinal }
                            }
                        },
                        onOpenAlbumsFilterSheet = { showAlbumsFilterSheet = true },
                        albumQuery = albumQuery,
                        onAlbumQueryChange = { albumQuery = it },
                        albumSearchActive = albumSearchActive,
                        onAlbumSearchActiveChange = { albumSearchActive = it },
                        // Arranging owns the whole rail, so entering it closes any open search first.
                        albumReorderActive = albumReorderActive,
                        onSharedFilterSelected = { filter ->
                            sharedFilter = filter
                            activeEmailFilter = null
                        },
                        onShowSharedEmailSheet = { showEmailFilterSheet = true },
                        onSettingsClick = onSettingsClick,
                        onOpenUploads = onOpenUploads,
                        onOpenDownloads = onOpenDownloads,
                        // Only the page actually in front reports its height, so the content inset and
                        // the selection-mode grid offset track the visible header, not a fading one.
                        onHeaderMeasured = { if (page == pagerState.currentPage) headerHeightPx = it },
                        updateAvailable = updateAvailable,
                        newsUnread = newsUnread,
                        onUpdateClick = viewModel::openUpdateFromDot,
                    )
                    }
                }
            }
        }

        // ── SELECTION DRAWER (every bulk action, in one place) ────────────────
        // The grid's top inset stays at the FULL browse-header height while selecting: the browse
        // header's last measurement stays in headerHeightPx (AnimatedVisibility stops re-measuring
        // it while it's hidden), so the content holds instead of jumping up.
        val selectionActions = rememberGallerySelectionActions(
            selectedItems = state.selectedItems,
            favoriteIds = state.favoriteIds,
            offlinePinIds = state.offlinePinIds,
            favoriteState = state.favoriteState,
            multiShareState = multiShareState,
            multiDeleteState = multiDeleteState,
            multiDownloadState = multiDownloadState,
            multiStripState = multiStripState,
            addToAlbumState = addToAlbumState,
            allSelected = state.filteredItems.isNotEmpty() &&
                state.selectedItems.size == state.filteredItems.size,
            isSignedIn = state.isSignedIn,
            onSelectAll = {
                val all = state.filteredItems.toSet()
                viewModel.setSelection(if (state.selectedItems.size == all.size) emptySet() else all)
            },
            onShare = {
                // Open the unified share drawer (Send to app / Share with people / Public
                // link) instead of sharing straight to the OS chooser.
                showShareSheet = true
            },
            onHide = {
                // A hide ends in a permanent removal of the device originals it vaults, so it is
                // confirmed exactly as the delete beside it is. The split is read at the tap, so
                // the sheet names what THIS selection will have done to it.
                hideConfirmSplit = viewModel.hideSplitForSelection().takeIf { !it.isEmpty }
            },
            onRequestDelete = { showMultiDeleteSheet = true },
            onDownload = viewModel::downloadSelected,
            onMakeAvailableOffline = viewModel::toggleSelectedOffline,
            onRequestAddToAlbum = { showAddToAlbumSheet = true },
            onRequestAddToPerson = { showAddToPersonSheet = true },
            onToggleFavorite = viewModel::toggleSelectedFavorite,
            onBackUp = { showBackUpConfirm = true },
            onStripMetadata = viewModel::stripMetadataSelected,
            onEditMetadata = { onEditMetadata(state.selectedItems.toList()) },
            onCreateCollage = { onCreateCollage(state.selectedItems.toList()) },
            onRequestMoveToFolder = { showMoveToFolderSheet = true },
        )
        SelectionDrawer(
            visible = state.isSelectionMode,
            items = remember(state.selectedItems) { state.selectedItems.toList() },
            actions = selectionActions,
            onDismiss = viewModel::clearSelection,
            // Whichever Photos grid is on screen: scrolling it collapses the drawer, so reaching
            // past it to carry on through the timeline needs no deliberate pull or tap first.
            contentScrolling = if (mosaicGrid) {
                staggeredState.isScrollInProgress
            } else {
                gridState.isScrollInProgress
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // ── UNIFIED PROGRESS PILL ─────────────────────────────────────────────
        // One surface for the timeline's own long work, matching the device-folder back-up and
        // album bulk actions. Sits at the top edge, clear of the selection drawer, which stays
        // visible while these run. A multi-download is not here: it registers with the
        // TransferCenter, so the Activity screen lists its photos and cancels the batch, and a
        // second copy would just be a pill with no cancel of its own.
        val opSharingTpl = stringResource(R.string.op_sharing_fmt)
        val opAddingLabel = stringResource(R.string.op_adding_to_album)
        val opBackingUpTpl = stringResource(R.string.op_backing_up_fmt)
        val opDeletingLabel = stringResource(R.string.op_deleting)
        val opHidingLabel = stringResource(R.string.op_hiding)
        val folderHidingTpl = stringResource(R.string.device_folder_hiding_fmt)
        val shS = multiShareState
        // Background back-up surfaces as the same pill as the share, so the user sees progress (and
        // can cancel) in-app instead of only from the notification.
        val uploadActive = state.isSyncing && state.uploadTotalCount > 0
        // Hiding a device folder from the Albums tab reports here too: it copies file by file and a
        // folder can hold thousands, so it takes the pill's count and cancel rather than a blocking
        // sheet the user would have to sit through.
        val fh = viewModel.folderHideProgress.collectAsStateWithLifecycle().value
        val galleryOpProgress = when {
            shS is MultiShareState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    shS.done, shS.total, opSharingTpl.format(shS.done, shS.total))
            fh != null ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    fh.done, fh.total, folderHidingTpl.format(fh.done, fh.total))
            uploadActive ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    state.uploadDoneIdx, state.uploadTotalCount,
                    opBackingUpTpl.format(state.uploadDoneIdx, state.uploadTotalCount))
            else -> null
        }
        // Only the folder hide and the back-up are cancellable from the pill (a share finishes
        // quickly and has its own controls). The branches mirror the chain above, so the X always
        // belongs to the operation on show. Mirrors the upload notification's cancel.
        val galleryOpCancel: (() -> Unit)? = when {
            shS is MultiShareState.Working -> null
            fh != null -> { { viewModel.cancelFolderHide() } }
            uploadActive -> { { viewModel.cancelUpload() } }
            else -> null
        }
        // Hold the last non-null progress so the pill can animate OUT cleanly when the work finishes,
        // instead of vanishing the instant it goes null.
        var lastOpProgress by remember { mutableStateOf(galleryOpProgress) }
        if (galleryOpProgress != null) lastOpProgress = galleryOpProgress
        // Rides the same scroll-hide signal as the header and dock, so it never sits orphaned over the
        // grid once the rest of the chrome slides away (issue #98). Kept during selection mode.
        AnimatedVisibility(
            visible = showOverlays && galleryOpProgress != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (headerHeightPx > 0) headerHeightDp + 4.dp else 8.dp),
        ) {
            eu.akoos.photos.presentation.common.OperationProgressPill(
                progress = lastOpProgress,
                onCancel = galleryOpCancel,
            )
        }

        // Foreground bulk actions (delete / hide / move to album) take over the screen with a
        // blocking drawer so a second destructive tap can't land on a half-finished one. Background
        // work (downloads, back-up) stays in the passive pill above.
        val blockingProgress = when {
            multiDeleteState is MultiDeleteState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(0, 0, opDeletingLabel, indeterminate = true)
            multiHideState is MultiDeleteState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(0, 0, opHidingLabel, indeterminate = true)
            addToAlbumState is AddToAlbumState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(0, 0, opAddingLabel, indeterminate = true)
            else -> null
        }
        eu.akoos.photos.presentation.common.BlockingOperationSheet(blockingProgress)

        // ── BOTTOM DOCK ───────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showOverlays && !state.isSelectionMode,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            BottomDock(
                // The live fractional page position drives the sliding highlight, so it follows a swipe.
                position = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                // A local-only session has no account, so the cloud-only Shared tab is omitted.
                showShared = state.isSignedIn,
                onTabSelected = { tab ->
                    // Re-tapping the active tab always returns it to the visual top (item 0). Switching
                    // to a different tab also resets to the top, unless "keep place when switching tabs"
                    // is on, in which case the target page stays where it was last scrolled. At this
                    // point [selectedTab] still holds the previous tab, so tab == selectedTab marks a
                    // re-tap. The Photos tab targets whichever grid is showing (staggered when mosaic is
                    // on), and item 0 is the top in every order, including reversed, where the top is
                    // the oldest photo by design.
                    if (!keepScrollOnTabSwitch || tab == selectedTab) {
                        when (tab) {
                            0 -> tabScope.launch {
                                if (mosaicGrid) staggeredState.scrollToItem(0) else gridState.scrollToItem(0)
                            }
                            1 -> tabScope.launch { albumsGridState.scrollToItem(0) }
                        }
                    }
                    // Drive the pager so a tap slides to the page; the rail and dock highlight both
                    // follow pagerState.currentPage, which flips as the slide crosses the midpoint —
                    // the same point a swipe flips them, so tap and swipe stay in sync.
                    selectedTab = tab
                    tabScope.launch { pagerState.animateScrollToPage(tab) }
                },
            )
        }

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        )

        // ── DEBUG heap readout ────────────────────────────────────────────────
        // Debug-only used/max Java-heap overlay so the maintainer + testers can watch memory while
        // scrolling a large library and confirm the sustained-scroll heap stays flat. Never compiled
        // into a release surface (BuildConfig.DEBUG-guarded, mirroring the Activity Test mode toggle).
        if (eu.akoos.photos.BuildConfig.DEBUG) {
            val heapStat by viewModel.debugHeapStat.collectAsStateWithLifecycle()
            if (heapStat.isNotEmpty()) {
                Text(
                    text = heapStat,
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 2.dp, end = 6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }

    // ── Bottom sheets — extracted to GalleryDialogs.kt for JIT-blob shrink ────
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = filterSheetState,
            containerColor = AppColors.current.sheetBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            ContentFilterSheet(
                currentFilter = state.contentFilter,
                currentCategory = state.selectedFilter,
                onApply = { filter -> viewModel.setContentFilter(filter) },
                onCategorySelected = { cat -> viewModel.onFilterSelected(cat) },
                onDismiss = { showFilterSheet = false },
                // Categories + media type live inline in the timeline rail, so the drawer keeps the
                // sync-status + date pickers only, matching the Search screen's sheet.
                showCategorySection = false,
                showMediaTypeSection = false,
                showDateSection = false,
                // Timeline-only bottom row into the full layout / categories / folders screen.
                onOpenTimelineSettings = onOpenTimelineFilter,
            )
        }
    }
    if (showEmailFilterSheet) {
        GallerySharedEmailFilterDialog(
            availableEmails = sharedUiState.availableEmails,
            activeEmailFilter = activeEmailFilter,
            onEmailSelected = { picked ->
                activeEmailFilter = picked
                showEmailFilterSheet = false
            },
            onDismiss = { showEmailFilterSheet = false },
        )
    }
    if (showAlbumsFilterSheet) {
        AlbumsFilterSheet(
            sheetState = albumsFilterSheetState,
            default = AlbumDisplayFilter.entries[albumsDefaultFilter.coerceIn(0, AlbumDisplayFilter.entries.lastIndex)],
            rememberLast = albumsRememberLastFilter,
            sortMode = albumsSortMode,
            columns = albumColumns,
            onDefaultChange = { picked ->
                // Show it now, not only on the next visit. The sheet writes the filter the Albums
                // tab opens on, and picking one while looking at that tab is a statement about the
                // list on screen as much as about the next open: persisting it alone left the grid
                // unchanged, so the pick read as ignored until the user left the tab and came back,
                // which is when the entry effect re-resolves this key.
                albumFilter = picked
                tabScope.launch {
                    context.settingsDataStore.edit { it[SettingsKeys.ALBUMS_DEFAULT_FILTER] = picked.ordinal }
                }
            },
            onRememberLastChange = { value ->
                tabScope.launch {
                    context.settingsDataStore.edit { it[SettingsKeys.ALBUMS_REMEMBER_LAST_FILTER] = value }
                }
            },
            onSortModeChange = { picked ->
                tabScope.launch {
                    context.settingsDataStore.edit { it[SettingsKeys.ALBUMS_SORT_MODE] = picked.ordinal }
                }
            },
            onColumnsChange = { picked ->
                tabScope.launch {
                    context.settingsDataStore.edit { it[SettingsKeys.ALBUM_GRID_COLUMNS] = picked }
                }
            },
            onDismiss = { showAlbumsFilterSheet = false },
        )
    }
    if (showShareCloudWarning) {
        ConfirmDialog(
            title = stringResource(R.string.gallery_share_cloud_warning_title),
            message = stringResource(R.string.gallery_share_cloud_warning_body),
            confirmLabel = stringResource(R.string.gallery_share_cloud_warning_continue),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showShareCloudWarning = false
                viewModel.shareSelected()
            },
            onDismiss = { showShareCloudWarning = false },
        )
    }
    if (showBackUpConfirm && state.selectedItems.isNotEmpty()) {
        ConfirmSheet(
            title = stringResource(R.string.upload_confirm_title),
            message = stringResource(R.string.upload_confirm_message, state.selectedItems.size),
            confirmLabel = stringResource(R.string.upload_action_short),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showBackUpConfirm = false
                viewModel.backUpSelected { queued ->
                    tabScope.launch {
                        val msg = if (queued > 0) R.string.backup_started
                            else R.string.device_folder_already_backed_up
                        snackbarHostState.showSnackbar(context.getString(msg))
                    }
                }
            },
            onDismiss = { showBackUpConfirm = false },
        )
    }
    hideConfirmSplit?.let { split ->
        HideConfirmSheet(
            split = split,
            title = stringResource(R.string.hide_confirm_title),
            onConfirm = {
                hideConfirmSplit = null
                viewModel.hideSelected()
            },
            onDismiss = { hideConfirmSplit = null },
        )
    }
    // A device folder's card asks the same question, and holds its confirm until the folder has been
    // read: the count comes from a device query rather than from a selection already in hand.
    val folderHideRequest by viewModel.folderHideRequest.collectAsStateWithLifecycle()
    folderHideRequest?.let { request ->
        HideConfirmSheet(
            split = request.split,
            title = stringResource(R.string.device_folder_hide_card),
            onConfirm = { viewModel.confirmHideFolder() },
            onDismiss = { viewModel.dismissHideFolderRequest() },
        )
    }
    if (showMultiDeleteSheet && state.selectedItems.isNotEmpty()) {
        GalleryMultiDeleteDialog(
            selectedItems = state.selectedItems,
            onDismiss = { showMultiDeleteSheet = false },
            onDelete = { freeUpSpace, deleteFromCloud ->
                showMultiDeleteSheet = false
                viewModel.deleteSelected(freeUpSpace, deleteFromCloud)
            },
        )
    }
    if (showAddToAlbumSheet && state.selectedItems.isNotEmpty()) {
        GalleryAddToAlbumDialog(
            selectedItems = state.selectedItems,
            cloudAlbums = albumsState.addableAlbums,
            sheetState = addToAlbumSheetState,
            onCreateNew = {
                showAddToAlbumSheet = false
                showCreateAlbumInline = true
            },
            onCloudAlbumSelected = { album ->
                showAddToAlbumSheet = false
                viewModel.addSelectedToAlbum(
                    albumLinkId = album.linkId,
                    albumName = album.name,
                )
            },
            onDismiss = { showAddToAlbumSheet = false },
            hiddenAlbumIds = state.hiddenAlbumIds,
        )
    }
    if (showAddToPersonSheet && state.selectedItems.isNotEmpty()) {
        GalleryAddToPersonSheet(
            people = state.people,
            sheetState = addToPersonSheetState,
            onPersonSelected = { personId ->
                showAddToPersonSheet = false
                viewModel.addSelectedToPerson(personId)
            },
            onDismiss = { showAddToPersonSheet = false },
        )
    }
    if (showCreateAlbumInline) {
        GalleryNewAlbumDialog(
            onDismiss = { showCreateAlbumInline = false },
            onCreate = { name ->
                showCreateAlbumInline = false
                viewModel.createAlbumThenAddSelected(name)
            },
        )
    }
    // ── Move-to-folder picker + new-folder name dialog ────────────────────────
    if (showMoveToFolderSheet && state.selectedItems.isNotEmpty()) {
        MoveToFolderSheet(
            folders = moveTargetFolders,
            onPick = { name ->
                showMoveToFolderSheet = false
                viewModel.moveSelectedToFolder(name)
            },
            onNewFolder = {
                showMoveToFolderSheet = false
                showNewFolderDialog = true
            },
            onDismiss = { showMoveToFolderSheet = false },
        )
    }
    if (showNewFolderDialog) {
        NewFolderNameDialog(
            onConfirm = { name ->
                showNewFolderDialog = false
                viewModel.moveSelectedToFolder(name)
            },
            onDismiss = { showNewFolderDialog = false },
        )
    }
    // Logged-out "New folder": name the folder in the shared edit sheet, then hand off to the device
    // photo picker. The picked photos come back via newFolderPickedItems and are moved into
    // DCIM/<name>/ by the LaunchedEffect above; the moveConfirmation snackbar reports where they landed.
    if (showNewFolderNameDialog) {
        EditFieldSheet(
            title = stringResource(R.string.new_folder),
            hint = stringResource(R.string.move_to_folder_name_hint),
            initialValue = "",
            singleLine = true,
            confirmLabel = stringResource(R.string.new_folder_create),
            onDismiss = { showNewFolderNameDialog = false },
            onSave = { name ->
                showNewFolderNameDialog = false
                onStartNewFolderPick(name.trim())
            },
            canConfirm = { it.isNotBlank() },
        )
    }

    // ── Unified share drawer ──────────────────────────────────────────────────
    if (showShareSheet && state.selectedItems.isNotEmpty()) {
        PhotoShareSheet(
            sheetState = shareSheetState,
            // Offer the Public link row for any single selection (like the viewer); only a
            // backed-up cloud photo can actually mint a link, a local one shows the back-up note.
            canCreateLink = shareSinglePhotoHasLink,
            showPublicLink = shareSingleSelected && state.isSignedIn,
            showShareWithPeople = state.isSignedIn,
            localUploadEnabled = true,
            onDismiss = { showShareSheet = false },
            onSendToApp = {
                showShareSheet = false
                launchSelectionShare()
            },
            onShareWithPeople = {
                // Proton shares photos with people by adding them to a shared album, so this
                // hands off to the gallery's existing add-to-album picker for the selection.
                showShareSheet = false
                showAddToAlbumSheet = true
            },
            onManagePublicLink = {
                showShareSheet = false
                // Seed the manage-link sheet's state for the single selected cloud photo.
                viewModel.loadPublicLink()
                showManageLinkSheet = true
            },
        )
    }

    // ── Manage public link sheet (single cloud photo) ─────────────────────────
    if (showManageLinkSheet) {
        ManagePublicLinkSheet(
            sheetState = manageLinkSheetState,
            publicLinkState = publicLinkState,
            onDismiss = { showManageLinkSheet = false },
            onCreateLink = { viewModel.createPublicLink() },
            needsUpload = shareSingleSelected && !shareSinglePhotoHasLink,
            onUploadAndCreate = { viewModel.uploadAndCreateSelectedLink() },
            onCopyLink = {
                viewModel.currentPublicLinkUrl()?.let { url ->
                    copySensitiveText(context, "Photo link", url)
                    tabScope.launch { snackbarHostState.showSnackbar(linkCopiedMsg) }
                }
            },
            onRemoveLink = { viewModel.revokePublicLink() },
            onSetPassword = { password ->
                viewModel.setLinkPassword(password)
                val msg = if (password.isNullOrBlank()) passwordRemovedMsg else passwordSetMsg
                tabScope.launch { snackbarHostState.showSnackbar(msg) }
            },
        )
    }

    // ── Cloud metadata save drawer ────────────────────────────────────────────
    // The editor hands its staged cloud edits to the app-scoped controller and closes; this is where
    // the batch's live per-step status shows over the timeline. "Continue in background" hides the
    // drawer while the upload keeps running, still tracked by the Activity transfer list.
    CloudMetadataSaveDrawer(ui = cloudSaveUi, onDismiss = { cloudSaveVm.dismiss() })
}

// ── Add-to-album picker sheet ─────────────────────────────────────────────────
//
// Bottom sheet with a [+ New album] row followed by the user's cloud albums.
// Mirrors the styling AlbumDetailScreen uses for its own bottom sheets.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryAddToAlbumPickerSheet(
    cloudAlbums: List<Album>,
    selectionHasCloud: Boolean,
    onCreateNew: () -> Unit,
    onCloudAlbumSelected: (Album) -> Unit,
    onDismiss: () -> Unit,
    hiddenAlbumIds: Set<String> = emptySet(),
    selectionCloudLinkIds: Set<String> = emptySet(),
    albumMemberIds: Map<String, Set<String>> = emptyMap(),
) {
    val appColors = AppColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        Text(
            stringResource(R.string.gallery_add_to_album),
            color = appColors.fgPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )

        // + New album
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCreateNew() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, null, tint = Accent, modifier = Modifier.size(22.dp))
            }
            Text(
                stringResource(R.string.albums_new_album),
                color = Accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalDivider(color = Line2, thickness = 0.5.dp,
            modifier = Modifier.padding(horizontal = 20.dp))

        // Cloud albums — shown whenever any album exists. Local-only photos have no Drive
        // linkId yet; tapping an album backs them up first, then joins them (handled in the
        // add path), so the rows stay tappable and an inline note explains the two steps.
        if (cloudAlbums.isNotEmpty()) {
            Text(stringResource(R.string.gallery_picker_drive_albums),
                color = appColors.fgMute, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (!selectionHasCloud) {
                Text(stringResource(R.string.gallery_add_to_album_local_note),
                    color = appColors.fgMute, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            // Scroll the album rows within a bounded height so a large album collection stays
            // fully reachable. A LazyColumn participates in the bottom sheet's nested scroll,
            // so the inner list scrolls instead of the sheet swallowing the drag.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                items(cloudAlbums) { album ->
                    // Derived from the live selection, so ticking another photo re-reads the row.
                    val membership = albumMembershipState(
                        selectionCloudLinkIds,
                        albumMemberIds[album.linkId].orEmpty(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCloudAlbumSelected(album) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (album.coverThumbnailUrl != null) {
                            AsyncImage(
                                model = album.coverThumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Bg0),
                            )
                        } else {
                            Box(modifier = Modifier
                                .size(44.dp)
                                .background(Bg0, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Cloud, null, tint = appColors.fgMute,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    album.name, color = appColors.fgPrimary, fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                // A client-side hidden album stays a valid target; the lock just
                                // tells the user this row is one of their hidden albums.
                                if (album.linkId in hiddenAlbumIds) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = stringResource(R.string.timeline_filter_hidden_album),
                                        tint = appColors.fgMute,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                            Text(
                                stringResource(
                                    // Someone else's album reads "Shared" instead of "Drive", so a
                                    // row that adds to another person's album is never mistaken for
                                    // one of your own.
                                    if (album.isSharedWithMe) R.string.gallery_album_picker_count_shared
                                    else R.string.gallery_album_picker_count_drive,
                                    androidx.compose.ui.res.pluralStringResource(
                                        R.plurals.count_photos_plural, album.photoCount, album.photoCount,
                                    ),
                                ),
                                color = appColors.fgMute, fontSize = 12.sp)
                        }
                        // Accent check for a fully-covered album, a fraction when the album holds
                        // part of the selection; nothing at all in the common not-yet-added case.
                        // Mirrors the viewer sheet's membership tile.
                        when (membership) {
                            is AlbumMembership.All -> Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(Accent, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_status_all_selected_in_album),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            is AlbumMembership.Some -> {
                                // The visible "3 / 5" is digits only; the spoken form carries the meaning.
                                val fractionCd = stringResource(
                                    R.string.cd_status_some_selected_in_album,
                                    membership.inAlbum, membership.total,
                                )
                                Box(
                                    modifier = Modifier
                                        .background(Accent.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        // The row merges its children, so replace the bare digits
                                        // rather than let them read out alongside the spoken form.
                                        .clearAndSetSemantics { contentDescription = fractionCd },
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.gallery_album_picker_in_album_fraction,
                                            membership.inAlbum, membership.total,
                                        ),
                                        color = Accent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            is AlbumMembership.None -> Unit
                        }
                    }
                }
            }
        }

        // Empty state — shown only when no album exists. The + New row above is still tappable.
        if (cloudAlbums.isEmpty()) {
            Text(
                stringResource(R.string.gallery_no_albums_yet),
                color = appColors.fgMute,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        }
    }
}


// ── Multi-select delete sheet ─────────────────────────────────────────────────

@Composable
internal fun MultiDeleteSheet(
    selectedItems: Set<GalleryItem>,
    onDismiss: () -> Unit,
    onDelete: (freeUpSpace: Boolean, deleteFromCloud: Boolean) -> Unit,
) {
    val hasLocal = selectedItems.any { it is GalleryItem.LocalOnly || it is GalleryItem.Synced }
    val hasCloud = selectedItems.any { it is GalleryItem.Synced || it is GalleryItem.CloudOnly }
    val n = selectedItems.size

    val colors = AppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            androidx.compose.ui.res.pluralStringResource(R.plurals.delete_title_plural, n, n),
            color = colors.fgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        )

        val rows = deleteConfirmRows(hasLocal, hasCloud)
        if (rows.size > 1) {
            Text(
                stringResource(R.string.delete_multi_mixed_msg),
                color = colors.fgDim, fontSize = 14.sp,
            )
        }
        rows.forEach { row ->
            val bg = if (row.destructive) DeleteTint else colors.cardBg
            val borderColor = if (row.destructive) ErrorColor.copy(alpha = 0.3f) else colors.cardBorder
            val titleColor = if (row.destructive) ErrorColor else colors.fgPrimary
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, borderColor, RoundedCornerShape(12.dp))
                    .clickable { onDelete(row.freeUpSpace, row.deleteFromCloud) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    stringResource(deleteRowTitleRes(row.kind)),
                    color = titleColor, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(deleteRowDescRes(row.kind)),
                    color = colors.fgMute, fontSize = 12.sp,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.cardBg, RoundedCornerShape(12.dp))
                .clickable(onClick = onDismiss)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.cancel), color = colors.fgDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}
