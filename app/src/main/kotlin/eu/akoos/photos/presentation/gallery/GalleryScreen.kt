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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
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
import eu.akoos.photos.domain.usecase.CategorizeItem
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.DenseGridWarningDialog
import eu.akoos.photos.presentation.common.EmptyState
import eu.akoos.photos.presentation.common.ErrorPopup
import eu.akoos.photos.util.sanitizeErrorMessage
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal val pillShape = RoundedCornerShape(999.dp)

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
            else -> {}
        }
        if (filter.year != null) add("${filter.year}")
        if (filter.month != null) {
            val monthName = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(
                java.util.Calendar.getInstance().apply { set(java.util.Calendar.MONTH, filter.month - 1) }.time
            )
            add(if (filter.day != null) "$monthName ${filter.day}" else monthName)
        }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onPhotoClick: (items: List<GalleryItem>, index: Int, hiddenCloudLinkIds: Set<String>) -> Unit,
    onAlbumClick: (Album) -> Unit = {},
    onDeviceFolderClick: (bucketName: String) -> Unit = {},
    onSettingsClick: () -> Unit,
    onHiddenAlbumClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onCalendarClick: () -> Unit = {},
    /** Opens the Memories screen — reached from the pinned card at the top of the Albums tab. */
    onMemoriesClick: () -> Unit = {},
    /** Opens the Timeline filter screen — reached from the Albums tab's filter button now that
     *  the obsolete All/Backed-up album filter is gone (device + cloud albums show together). */
    onOpenTimelineFilter: () -> Unit = {},
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
    val albumsViewModel: AlbumsViewModel = hiltViewModel()
    val albumsState by albumsViewModel.uiState.collectAsStateWithLifecycle()
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val sharedUiState by sharedViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val appColors = AppColors.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()
    val albumsGridState = rememberLazyGridState()
    val tabScope = rememberCoroutineScope()
    var sharedFilter by remember { mutableStateOf(SharedFilter.SharedWithMe) }
    var albumFilter by remember { mutableStateOf(AlbumDisplayFilter.All) }
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
        LaunchedEffect(gridState, state.filteredItems) {
            val rendered = state.filteredItems
            if (rendered.isEmpty()) return@LaunchedEffect
            // Stable-key → filteredItems index, for the two cell key shapes the grid emits.
            val indexByLinkId = HashMap<String, Int>(rendered.size)
            rendered.forEachIndexed { idx, gi -> cloudLinkIdOf(gi)?.let { indexByLinkId[it] = idx } }
            var lastAnchor = -1
            snapshotFlow {
                val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String
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
    // Back-button intercept in selection mode: clear the selection instead of letting
    // the OS pop the screen out of the gallery. Without this guard the user lost their
    // multi-select work every time they hit the system back button looking for a
    // "cancel selection" affordance (the actual cancel pill is in the selection header
    // but isn't discoverable for a back-press user).
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
    var previousIndex by remember { mutableIntStateOf(0) }
    var previousOffset by remember { mutableIntStateOf(0) }
    val isPhotosScrollingDown by remember {
        derivedStateOf {
            val currentIndex = gridState.firstVisibleItemIndex
            val currentOffset = gridState.firstVisibleItemScrollOffset
            (currentIndex > previousIndex || (currentIndex == previousIndex && currentOffset > previousOffset))
                .also { previousIndex = currentIndex; previousOffset = currentOffset }
        }
    }

    var previousAlbumsIndex by remember { mutableIntStateOf(0) }
    var previousAlbumsOffset by remember { mutableIntStateOf(0) }
    val isAlbumsScrollingDown by remember {
        derivedStateOf {
            val currentIndex = albumsGridState.firstVisibleItemIndex
            val currentOffset = albumsGridState.firstVisibleItemScrollOffset
            (currentIndex > previousAlbumsIndex || (currentIndex == previousAlbumsIndex && currentOffset > previousAlbumsOffset))
                .also { previousAlbumsIndex = currentIndex; previousAlbumsOffset = currentOffset }
        }
    }

    // derivedStateOf so reading the live scroll index here doesn't recompose the whole screen on
    // every scrolled pixel — only when the overlay-visibility boolean actually flips. Reading
    // firstVisibleItemIndex directly in composition was the dominant scroll-jank source.
    val showOverlays by remember(selectedTab) {
        derivedStateOf {
            when (selectedTab) {
                0 -> !isPhotosScrollingDown || gridState.firstVisibleItemIndex == 0
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
        deletePermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
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
        stripPermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    // ── Multi-select delete sheet ─────────────────────────────────────────────
    var showMultiDeleteSheet by remember { mutableStateOf(false) }
    val multiDeleteState = state.multiDeleteState

    LaunchedEffect(multiDeleteState) {
        if (multiDeleteState is MultiDeleteState.Done) {
            viewModel.resetMultiDeleteState()
        }
        if (multiDeleteState is MultiDeleteState.Failed) {
            snackbarHostState.showSnackbar(multiDeleteState.message)
            viewModel.resetMultiDeleteState()
        }
    }

    // Hide has its own state channel (separate spinner on the selection bar);
    // surface its terminal states here. On success, disclose that backed-up
    // photos keep their Drive copies — hiding only affects this device's gallery.
    val multiHideState = state.multiHideState
    LaunchedEffect(multiHideState) {
        if (multiHideState is MultiDeleteState.Done) {
            if (state.hideCloudNoticePending) {
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

    // ── Unified share drawer (selection) ──────────────────────────────────────
    // The toolbar Share opens the same menu the viewer uses: Send to another app,
    // Share with people (→ add-to-album), and — only for a single cloud-backed photo —
    // a Public link row that hands off to the manage-link sheet.
    var showShareSheet by remember { mutableStateOf(false) }
    var showManageLinkSheet by remember { mutableStateOf(false) }
    val shareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val manageLinkSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val publicLinkState by viewModel.publicLinkState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
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
    var showCreateAlbumInline by remember { mutableStateOf(false) }
    val addToAlbumSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                    addToAlbumState.skipped > 0 -> context.getString(
                        R.string.gallery_add_to_album_partial,
                        cloudAdded, addToAlbumState.albumName, addToAlbumState.skipped,
                    )
                    // Some joined now, the rest follow after they back up.
                    cloudAdded > 0 && queued > 0 -> context.getString(
                        R.string.gallery_add_to_album_added_and_queued, cloudAdded, queued,
                    )
                    // Selection was all local-only — nothing joined yet, all will after backup.
                    queued > 0 -> context.getString(
                        R.string.gallery_add_to_album_queued_only, queued, addToAlbumState.albumName,
                    )
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appColors.bg0)
    ) {
        // ── CONTENT ──────────────────────────────────────────────────────────
        // Adjacent-tab slide: the whole content area eases horizontally on tab change
        // (forward = enter from the right, back = from the left). The floating header and
        // bottom dock are siblings, so they stay put while the content slides beneath them.
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                val forward = targetState > initialState
                if (forward) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                } else {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "tabContent",
            modifier = Modifier.fillMaxSize(),
        ) { tab ->
        when (tab) {
            0 -> {
                val pullState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    state = pullState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {}
                ) {
                    when {
                        state.isLoading && state.filteredItems.isEmpty() ->
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
                        state.filteredItems.isEmpty() && state.items.isNotEmpty() ->
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
                        state.filteredItems.isEmpty() ->
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
                                onThisDayGroups    = state.onThisDayGroups,
                                gridState          = gridState,
                                topContentPadding  = headerHeightDp,
                                permissionState    = state.permissionState,
                                onPermissionGrant  = { permissionLauncher.launch(mediaPermissions) },
                                onPhotoClick       = { items, idx -> onPhotoClick(items, idx, state.hiddenCloudLinkIds) },
                                selectedItems      = state.selectedItems,
                                isSelectionMode    = state.isSelectionMode,
                                onLongPress        = viewModel::toggleSelection,
                                onToggleSelect     = viewModel::toggleSelection,
                                onToggleGroup      = viewModel::toggleGroup,
                                grouping           = state.timelineGrouping,
                                onGroupingChanged  = viewModel::setTimelineGrouping,
                                hiddenCloudLinkIds = state.hiddenCloudLinkIds,
                                downloadedCloudLinkIds = downloadedCloudLinkIds,
                                favoriteIds = state.favoriteIds,
                                onRequestThumbnail = viewModel::requestThumbnailDecrypt,
                                onCancelThumbnail  = viewModel::cancelThumbnailDecrypt,
                                denseGridWarningDismissed = state.denseGridWarningDismissed,
                                onDismissDenseGridWarning = viewModel::dismissDenseGridWarning,
                            )
                    }
                }
            }
            1 -> AlbumsScreen(
                topPadding = headerHeightDp,
                gridState = albumsGridState,
                onAlbumClick = onAlbumClick,
                onDeviceFolderClick = onDeviceFolderClick,
                onMemoriesClick = onMemoriesClick,
                createRequestSignal = albumCreateSignal,
                displayFilter = albumFilter,
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
        val isOnlineNow by viewModel.isOnline.collectAsStateWithLifecycle()
        val updateBannerVersion by viewModel.updateBannerVersion.collectAsStateWithLifecycle()
        AnimatedVisibility(
            visible = showOverlays && !state.isSelectionMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            GalleryHeader(
                selectedTab = selectedTab,
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
                // The Albums-tab filter button opens the Timeline filter screen.
                onShowAlbumsFilterSheet = onOpenTimelineFilter,
                onNewAlbumClick = { albumCreateSignal++ },
                albumFilter = albumFilter,
                onAlbumFilterSelected = { albumFilter = it },
                onSharedFilterSelected = { filter ->
                    sharedFilter = filter
                    activeEmailFilter = null
                },
                onShowSharedEmailSheet = { showEmailFilterSheet = true },
                onSettingsClick = onSettingsClick,
                onHeaderMeasured = { headerHeightPx = it },
                updateBannerVersion = updateBannerVersion,
                onUpdateBannerOpen = viewModel::openUpdateFromBanner,
                onUpdateBannerDismiss = viewModel::dismissUpdateBanner,
            )
        }

        // ── SELECTION HEADER (top: cancel + count + share + delete) ───────────
        AnimatedVisibility(
            visible = state.isSelectionMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            GallerySelectionHeader(
                selectedItems = state.selectedItems,
                selectedCount = state.selectedCount,
                multiShareState = multiShareState,
                multiDeleteState = multiDeleteState,
                onCancel = viewModel::clearSelection,
                onShare = {
                    // Open the unified share drawer (Send to app / Share with people / Public
                    // link) instead of sharing straight to the OS chooser.
                    showShareSheet = true
                },
                onRequestDelete = { showMultiDeleteSheet = true },
                onHeaderHeightChanged = { headerHeightPx = it },
            )
        }

        // ── SELECTION BOTTOM DOCK (add to album / back up / download / more) ──
        AnimatedVisibility(
            visible = state.isSelectionMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            GallerySelectionBottomBar(
                selectedItems = state.selectedItems,
                multiDownloadState = multiDownloadState,
                multiHideState = state.multiHideState,
                multiStripState = multiStripState,
                addToAlbumState = addToAlbumState,
                onDownload = viewModel::downloadSelected,
                onRequestAddToAlbum = { showAddToAlbumSheet = true },
                onBackUp = { showBackUpConfirm = true },
                onStripMetadata = viewModel::stripMetadataSelected,
                onHideSelected = viewModel::hideSelected,
            )
        }

        // ── UNIFIED PROGRESS PILL ─────────────────────────────────────────────
        // One surface for multi-download, multi-share and add-to-album, matching the
        // device-folder back-up and album bulk actions. Sits just under the selection header,
        // which stays visible while these run.
        val opDownloadingTpl = stringResource(R.string.op_downloading_fmt)
        val opSharingTpl = stringResource(R.string.op_sharing_fmt)
        val opAddingLabel = stringResource(R.string.op_adding_to_album)
        val opBackingUpTpl = stringResource(R.string.op_backing_up_fmt)
        val opDeletingLabel = stringResource(R.string.op_deleting)
        val opHidingLabel = stringResource(R.string.op_hiding)
        val dlS = multiDownloadState
        val shS = multiShareState
        // Background back-up surfaces as the same pill as downloads/shares, so the user sees
        // progress (and can cancel) in-app instead of only from the notification.
        val uploadActive = state.isSyncing && state.uploadTotalCount > 0
        val galleryOpProgress = when {
            dlS is MultiDownloadState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    dlS.done, dlS.total, opDownloadingTpl.format(dlS.done, dlS.total))
            shS is MultiShareState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    shS.done, shS.total, opSharingTpl.format(shS.done, shS.total))
            uploadActive ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    state.uploadDoneIdx, state.uploadTotalCount,
                    opBackingUpTpl.format(state.uploadDoneIdx, state.uploadTotalCount))
            else -> null
        }
        // Only the back-up is cancellable from the pill (downloads/shares finish quickly + have
        // their own controls). Mirrors the upload notification's cancel.
        val galleryOpCancel: (() -> Unit)? =
            if (uploadActive && dlS !is MultiDownloadState.Working &&
                shS !is MultiShareState.Working && addToAlbumState !is AddToAlbumState.Working
            ) {
                { viewModel.cancelUpload() }
            } else null
        eu.akoos.photos.presentation.common.OperationProgressPill(
            progress = galleryOpProgress,
            onCancel = galleryOpCancel,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (headerHeightPx > 0) headerHeightDp + 4.dp else 8.dp),
        )

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
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            BottomDock(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    // Always land at the top: re-tapping the active tab or switching to another both
                    // reset that tab's scroll, so a page never reopens half-scrolled where you left it.
                    when (tab) {
                        0 -> tabScope.launch { gridState.scrollToItem(0) }
                        1 -> tabScope.launch { albumsGridState.scrollToItem(0) }
                    }
                    selectedTab = tab
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
    }

    // ── Bottom sheets — extracted to GalleryDialogs.kt for JIT-blob shrink ────
    if (showFilterSheet) {
        GalleryContentFilterDialog(
            currentFilter = state.contentFilter,
            currentCategory = state.selectedFilter,
            sheetState = filterSheetState,
            onApply = { filter -> viewModel.setContentFilter(filter) },
            onCategorySelected = { cat -> viewModel.onFilterSelected(cat) },
            onDismiss = { showFilterSheet = false },
        )
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
            cloudAlbums = albumsState.albums,
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

    // ── Unified share drawer ──────────────────────────────────────────────────
    if (showShareSheet && state.selectedItems.isNotEmpty()) {
        PhotoShareSheet(
            sheetState = shareSheetState,
            // Offer the Public link row for any single selection (like the viewer); only a
            // backed-up cloud photo can actually mint a link, a local one shows the back-up note.
            canCreateLink = shareSinglePhotoHasLink,
            showPublicLink = shareSingleSelected,
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
                    clipboard.setText(AnnotatedString(url))
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
            // fully reachable (the sheet itself doesn't grow past the screen, so an un-scrolled
            // list cut everything past ~7 albums off the bottom).
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                cloudAlbums.forEach { album ->
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
                            Text(album.name, color = appColors.fgPrimary, fontSize = 15.sp,
                                fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(
                                    R.string.gallery_album_picker_count_drive,
                                    androidx.compose.ui.res.pluralStringResource(
                                        R.plurals.count_photos_plural, album.photoCount, album.photoCount,
                                    ),
                                ),
                                color = appColors.fgMute, fontSize = 12.sp)
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
            .padding(horizontal = 20.dp)
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            androidx.compose.ui.res.pluralStringResource(R.plurals.delete_title_plural, n, n),
            color = colors.fgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        )

        if (hasLocal && hasCloud) {
            Text(
                stringResource(R.string.delete_multi_mixed_msg),
                color = colors.fgDim, fontSize = 14.sp,
            )
        }

        if (hasLocal) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                    .clickable { onDelete(true, false) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    if (hasCloud) stringResource(R.string.delete_multi_remove_device)
                    else stringResource(R.string.delete_multi_move_trash),
                    color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    if (hasCloud) stringResource(R.string.delete_multi_remove_device_desc)
                    else stringResource(R.string.delete_multi_move_trash_desc),
                    color = colors.fgMute, fontSize = 12.sp,
                )
            }
        }

        // Middle option (mixed selections only): drop the cloud copies, keep all local files.
        // Mirrors the per-photo viewer dialog so the user has a consistent "remove just one side"
        // choice everywhere. Single-side selections don't need this row because there's nothing
        // to keep on the local side.
        if (hasLocal && hasCloud) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                    .clickable { onDelete(false, true) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    stringResource(R.string.delete_multi_remove_cloud),
                    color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.delete_multi_remove_cloud_desc),
                    color = colors.fgMute, fontSize = 12.sp,
                )
            }
        }

        if (hasCloud) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DeleteTint, RoundedCornerShape(12.dp))
                    .border(0.5.dp, ErrorColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .clickable { onDelete(hasLocal, true) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    if (hasLocal) stringResource(R.string.delete_multi_move_trash_everywhere)
                    else stringResource(R.string.delete_multi_drive_trash),
                    color = ErrorColor, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    if (hasLocal) stringResource(R.string.delete_multi_move_trash_everywhere_desc)
                    else stringResource(R.string.delete_multi_drive_trash_desc),
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
