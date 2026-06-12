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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.material3.Icon
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
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.EmptyState
import eu.akoos.photos.presentation.common.ErrorPopup
import eu.akoos.photos.util.computeOnThisDay
import eu.akoos.photos.util.sanitizeErrorMessage
import eu.akoos.photos.presentation.albums.AlbumsScreen
import eu.akoos.photos.presentation.albums.AlbumsViewModel
import eu.akoos.photos.presentation.shared.SharedScreen
import eu.akoos.photos.presentation.shared.SharedViewModel
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

private val pillShape = RoundedCornerShape(999.dp)

private fun formatCount(n: Int): String = when {
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

private fun buildContentFilterSummary(
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
            add(java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(
                java.util.Calendar.getInstance().apply { set(java.util.Calendar.MONTH, filter.month - 1) }.time
            ))
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
    var activeEmailFilter by remember { mutableStateOf<String?>(null) }
    var showEmailFilterSheet by remember { mutableStateOf(false) }

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
    LaunchedEffect(state.items) {
        val memoryLinkIds = computeOnThisDay(state.items)
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
    // Trigger a sync/refresh immediately after the user grants photo permission so
    // photos appear without requiring an app restart.
    LaunchedEffect(state.permissionState) {
        if (state.permissionState == PermissionState.Granted) viewModel.refresh()
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
            title = "Error",
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

    val showOverlays = when (selectedTab) {
        0 -> !isPhotosScrollingDown || gridState.firstVisibleItemIndex == 0
        1 -> !isAlbumsScrollingDown || albumsGridState.firstVisibleItemIndex == 0
        else -> true // Shared tab has no scroll hiding yet
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
                val msg = when {
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
                    cloudAdded > 0 -> context.getString(
                        R.string.gallery_added_to_album, cloudAdded, addToAlbumState.albumName,
                    )
                    // Edge case: nothing added and nothing queued but the op still "succeeded"
                    // (e.g. the photos were already in the picked album). Surface a snackbar anyway.
                    else -> context.getString(R.string.gallery_added_to_album, 0, addToAlbumState.albumName)
                }
                snackbarHostState.showSnackbar(msg)
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
        when (selectedTab) {
            0 -> {
                val pullState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = viewModel::refresh,
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
                                    eu.akoos.photos.presentation.common.ShimmerSquare(
                                        modifier = Modifier.fillMaxWidth(),
                                        cornerRadius = 4.dp,
                                    )
                                }
                            }
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
                                onRequestThumbnail = viewModel::requestThumbnailDecrypt,
                                onCancelThumbnail  = viewModel::cancelThumbnailDecrypt,
                            )
                    }
                }
            }
            1 -> AlbumsScreen(
                topPadding = headerHeightDp,
                gridState = albumsGridState,
                onAlbumClick = onAlbumClick,
                onDeviceFolderClick = onDeviceFolderClick,
            )
            2 -> SharedScreen(
                topPadding = headerHeightDp,
                filter = sharedFilter,
                activeEmailFilter = activeEmailFilter,
                onAlbumClick = onAlbumClick,
            )
        }

        // ── FLOATING HEADER (normal mode) ─────────────────────────────────────
        val isOnlineNow by viewModel.isOnline.collectAsStateWithLifecycle()
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
                onSharedFilterSelected = { filter ->
                    sharedFilter = filter
                    activeEmailFilter = null
                },
                onShowSharedEmailSheet = { showEmailFilterSheet = true },
                onSettingsClick = onSettingsClick,
                onHeaderMeasured = { headerHeightPx = it },
            )
        }

        // ── SELECTION HEADER ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible = state.isSelectionMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            GallerySelectionHeader(
                selectedItems = state.selectedItems,
                selectedCount = state.selectedCount,
                multiDownloadState = multiDownloadState,
                multiShareState = multiShareState,
                multiDeleteState = multiDeleteState,
                multiHideState = state.multiHideState,
                multiStripState = multiStripState,
                addToAlbumState = addToAlbumState,
                onCancel = viewModel::clearSelection,
                onShare = {
                    // Cloud-only photos must be downloaded before sharing; warn first. A selection
                    // of only on-device photos shares immediately.
                    if (state.selectedItems.any { it is GalleryItem.CloudOnly })
                        showShareCloudWarning = true
                    else
                        viewModel.shareSelected()
                },
                onDownload = viewModel::downloadSelected,
                onRequestDelete = { showMultiDeleteSheet = true },
                onRequestAddToAlbum = { showAddToAlbumSheet = true },
                onBackUp = { viewModel.backUpSelected { } },
                onStripMetadata = viewModel::stripMetadataSelected,
                onHideSelected = viewModel::hideSelected,
                onHeaderHeightChanged = { headerHeightPx = it },
            )
        }

        // ── UNIFIED PROGRESS PILL ─────────────────────────────────────────────
        // One surface for multi-download, multi-share and add-to-album, matching the
        // device-folder back-up and album bulk actions. Sits just under the selection header,
        // which stays visible while these run.
        val opDownloadingTpl = stringResource(R.string.op_downloading_fmt)
        val opSharingTpl = stringResource(R.string.op_sharing_fmt)
        val opAddingLabel = stringResource(R.string.op_adding_to_album)
        val dlS = multiDownloadState
        val shS = multiShareState
        val galleryOpProgress = when {
            dlS is MultiDownloadState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    dlS.done, dlS.total, opDownloadingTpl.format(dlS.done, dlS.total))
            shS is MultiShareState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    shS.done, shS.total, opSharingTpl.format(shS.done, shS.total))
            addToAlbumState is AddToAlbumState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(0, 0, opAddingLabel, indeterminate = true)
            else -> null
        }
        eu.akoos.photos.presentation.common.OperationProgressPill(
            progress = galleryOpProgress,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (headerHeightPx > 0) headerHeightDp + 4.dp else 8.dp),
        )

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
                    if (tab == selectedTab) {
                        // Re-tapping the active tab jumps its grid straight to the top — no
                        // animated scroll, the user just wants to be back at the top instantly.
                        when (tab) {
                            0 -> if (gridState.firstVisibleItemIndex > 0)
                                tabScope.launch { gridState.scrollToItem(0) }
                            1 -> if (albumsGridState.firstVisibleItemIndex > 0)
                                tabScope.launch { albumsGridState.scrollToItem(0) }
                        }
                    } else {
                        selectedTab = tab
                    }
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
                        Text("${album.photoCount} photos · Drive",
                            color = appColors.fgMute, fontSize = 12.sp)
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

// ── Albums filter rail ────────────────────────────────────────────────────────

@Composable
internal fun AlbumsFilterRail(
    onHiddenAlbumClick: () -> Unit = {},
    onShowFilterSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        // Hidden chip
        item(key = "hidden") {
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .background(PillBg, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .clickable { onHiddenAlbumClick() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Default.Lock, null, tint = FgDim, modifier = Modifier.size(13.dp))
                Text(stringResource(R.string.gallery_filter_hidden), color = FgDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        // Timeline-filter button — opens the Timeline filter screen (hide albums / device folders
        // from the Photos timeline).
        item(key = "filter_button") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(PillBg, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .clickable { onShowFilterSheet() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.FilterList, stringResource(R.string.settings_timeline_filter),
                    tint = FgDim, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Storage color helper ──────────────────────────────────────────────────────

/** Returns green / amber / red based on storage fill ratio. */
private fun storageArcColor(fraction: Float): Color = when {
    fraction < 0.70f -> Color(0xFF30D158)   // green
    fraction < 0.90f -> Color(0xFFFF9F0A)   // amber
    else             -> Color(0xFFFF453A)   // red
}

// ── Avatar button ─────────────────────────────────────────────────────────────

@Composable
internal fun AvatarButton(initial: String, storageFraction: Float, isSyncing: Boolean, isOffline: Boolean = false, onClick: () -> Unit) {
    // Animate the arc smoothly when storage data first loads
    val animatedFraction by animateFloatAsState(
        targetValue = storageFraction,
        animationSpec = tween(durationMillis = 800),
        label = "storage_arc",
    )
    val arcColor   = storageArcColor(storageFraction)
    val trackColor = ArcTrack
    val pillBgColor = PillBg

    // Spinning sync arc — rotates continuously while isSyncing is true
    val infiniteTransition = rememberInfiniteTransition(label = "sync_spin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
        ),
        label = "spin_angle",
    )

    Box(
        modifier = Modifier
            .size(46.dp)
            .drawBehind {
                val strokePx = 2.6.dp.toPx()
                val half     = strokePx / 2f
                val arcRect  = Size(size.width - strokePx, size.height - strokePx)

                // Full-circle track
                drawArc(
                    color      = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter  = false,
                    topLeft    = androidx.compose.ui.geometry.Offset(half, half),
                    size       = arcRect,
                    style      = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                if (isSyncing) {
                    // Spinning arc overlay — replaces the static storage arc during sync
                    drawArc(
                        color      = Color(0xFF60AFFF),
                        startAngle = spinAngle - 90f,
                        sweepAngle = 270f,
                        useCenter  = false,
                        topLeft    = androidx.compose.ui.geometry.Offset(half, half),
                        size       = arcRect,
                        style      = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                } else if (animatedFraction > 0f) {
                    // Static storage progress arc
                    drawArc(
                        color      = arcColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedFraction,
                        useCenter  = false,
                        topLeft    = androidx.compose.ui.geometry.Offset(half, half),
                        size       = arcRect,
                        style      = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
                // Inner fill (PillBg)
                drawCircle(
                    color  = pillBgColor,
                    radius = size.minDimension / 2f - strokePx,
                )
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Avatar gradient circle
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    Brush.linearGradient(
                        listOf(Accent, Accent2),
                        start = Offset(0f, 0f),
                        end   = Offset(80f, 80f),
                    ),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text       = initial.ifEmpty { "?" },
                color      = Color.White,
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // Gear badge
        Box(
            modifier = Modifier
                .size(14.dp)
                .align(Alignment.BottomEnd)
                .background(Bg2, CircleShape)
                .border(1.dp, PillBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = FgDim,
                modifier = Modifier.size(8.dp),
            )
        }
        // Offline badge — small red dot at the TopEnd corner. Kept distinct from
        // the gear badge at BottomEnd so the two don't visually collide.
        if (isOffline) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.TopEnd)
                    .background(ErrorColor, CircleShape)
                    .border(1.5.dp, Color.White, CircleShape),
            )
        }
    }
}

// ── Filter rail ───────────────────────────────────────────────────────────────

@Composable
internal fun FilterRail(
    selectedFilter: GalleryFilter,
    totalCount: Int,
    onFilterSelected: (GalleryFilter) -> Unit,
    contentFilter: ContentFilter,
    onSearchClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onClearContentFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isContentFilterActive = contentFilter != ContentFilter()
    val isFavoritesActive = selectedFilter == GalleryFilter.Favorites
    val photosLabel = stringResource(R.string.gallery_filter_photos)
    val videosLabel = stringResource(R.string.filter_type_videos)
    val localLabel = stringResource(R.string.filter_sync_local)
    val backedUpLabel = stringResource(R.string.filter_sync_backedup)
    val allLabel = stringResource(R.string.gallery_filter_all)
    val favoritesLabel = stringResource(R.string.gallery_filter_favorites)
    val filterSummary = remember(contentFilter, photosLabel, videosLabel, localLabel, backedUpLabel) {
        buildContentFilterSummary(contentFilter, photosLabel, videosLabel, localLabel, backedUpLabel)
    }

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        // ── All pill — shows content filter summary when active ───────────────
        item(key = "all") {
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .background(
                        if (isContentFilterActive) Accent.copy(alpha = 0.18f) else AppColors.current.filterPillBg,
                        pillShape
                    )
                    .clickable(enabled = isContentFilterActive) { onClearContentFilter() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!isContentFilterActive) {
                    Icon(Icons.Default.Check, null, tint = FgPrimary, modifier = Modifier.size(12.dp))
                }
                Text(
                    filterSummary ?: allLabel,
                    color = if (isContentFilterActive) Accent else FgPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (totalCount > 0) {
                    Text(
                        formatCount(totalCount),
                        color = if (isContentFilterActive) Accent.copy(alpha = 0.7f) else FgDim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // ── Favorites chip ────────────────────────────────────────────────────
        // Icon-only by user request — the rest of the rail has labels but the heart
        // is universal and the label was eating filter-rail real estate on narrow phones.
        // Still uses [favoritesLabel] as the accessibility contentDescription so screen
        // readers and the long-press tooltip still surface the meaning.
        item(key = "favorites") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(if (isFavoritesActive) Accent.copy(alpha = 0.18f) else PillBg, pillShape)
                    .then(if (!isFavoritesActive) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
                    .clickable { onFilterSelected(if (isFavoritesActive) GalleryFilter.All else GalleryFilter.Favorites) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isFavoritesActive) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    favoritesLabel,
                    tint = if (isFavoritesActive) Accent else FgDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // ── Search button ─────────────────────────────────────────────────────
        item(key = "search_button") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(PillBg, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .clickable { onSearchClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Search, stringResource(R.string.search_title),
                    tint = FgDim, modifier = Modifier.size(18.dp))
            }
        }

        // ── Calendar button ───────────────────────────────────────────────────
        item(key = "calendar_button") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(PillBg, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .clickable { onCalendarClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.CalendarMonth, stringResource(R.string.calendar_title),
                    tint = FgDim, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Bottom dock ───────────────────────────────────────────────────────────────

@Composable
private fun BottomDock(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .background(PillBgOpaque, pillShape)
            .border(0.5.dp, PillBorder, pillShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DockTab(
            icon = Icons.Default.Photo,
            label = stringResource(R.string.gallery_tab_photos),
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
        )
        DockTab(
            icon = Icons.Default.Collections,
            label = stringResource(R.string.gallery_tab_albums),
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
        )
        DockTab(
            icon = Icons.Default.Share,
            label = stringResource(R.string.gallery_tab_shared),
            selected = selectedTab == 2,
            onClick = { onTabSelected(2) },
        )
    }
}

@Composable
private fun DockTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Compact padding + maxLines/softWrap=false so the labels never wrap onto two lines
    // on narrow screens (6.1"-class and smaller screens). The text shrinks to
    // ellipsis if a localised label is unusually long instead of breaking the pill.
    Row(
        modifier = Modifier
            .background(
                if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) Accent else FgDim,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = if (selected) Accent else FgDim,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Photo grid ────────────────────────────────────────────────────────────────

@Composable
private fun PhotoGrid(
    items: List<GalleryItem>,
    allItems: List<GalleryItem>,
    gridState: LazyGridState,
    topContentPadding: Dp,
    permissionState: PermissionState,
    onPermissionGrant: () -> Unit,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    selectedItems: Set<GalleryItem> = emptySet(),
    isSelectionMode: Boolean = false,
    onLongPress: (GalleryItem) -> Unit = {},
    onToggleSelect: (GalleryItem) -> Unit = {},
    onToggleGroup: (List<GalleryItem>) -> Unit = {},
    grouping: TimelineGrouping = TimelineGrouping.Month,
    onGroupingChanged: (TimelineGrouping) -> Unit = {},
    hiddenCloudLinkIds: Set<String> = emptySet(),
    downloadedCloudLinkIds: Set<String> = emptySet(),
    onRequestThumbnail: (linkId: String) -> Unit = {},
    onCancelThumbnail: (linkId: String) -> Unit = {},
) {
    // "On this day" memories — items from previous years that fall on today's calendar date.
    // Independent of the active content filter so memories still surface when the user has
    // applied e.g. a year/month filter that would otherwise hide them. Grouped by year,
    // most-recent-first; null when there are none (carousel is hidden entirely).
    val onThisDayByYear: List<Pair<Int, List<GalleryItem>>> by remember(allItems) {
        derivedStateOf { computeOnThisDay(allItems) }
    }
    val context = LocalContext.current

    // ── Pinch-to-zoom levels ──────────────────────────────────────────────────
    // Six discrete (cols, grouping) levels that pinch cycles through in one motion.
    // Most-zoomed-out = 6 cols + no grouping (densest "thumbnail wall"); most-zoomed-in
    // = 1 col + year headers (one giant tile per row with chunky temporal context).
    // The cross-over from "no grouping" to grouped views happens between L2 and L3,
    // which intentionally matches the spot where individual tiles get big enough that
    // a date header above them stops feeling like clutter.
    val zoomLevels = remember {
        // Mapping reflects how the user navigates: big tiles need fine-grained markers
        // (day) because the user is browsing close-up; small tiles need broad markers
        // (none / year) because the user is scrolling through history at speed.
        listOf(
            6 to TimelineGrouping.None,    // L0 — 6 cols, no grouping (densest thumbnail wall)
            5 to TimelineGrouping.Year,    // L1 — 5 cols, year headers
            4 to TimelineGrouping.Month,   // L2 — 4 cols, month headers
            3 to TimelineGrouping.Month,   // L3 — 3 cols, month headers (still wide)
            2 to TimelineGrouping.Day,     // L4 — 2 cols, day headers, larger tiles
            1 to TimelineGrouping.Day,     // L5 — 1 col, biggest tile + day headers
        )
    }
    // Initial level is derived from the persisted grouping. Default for Month is L3
    // (3 cols + month headers) — that's the "monthly browsing" baseline the user
    // expects on first launch. None → 4 cols flat, Year → 5 cols year-headers,
    // Day → 2 cols big-tile day-headers — picked so pinch in either direction is
    // symmetric from the most-common starting point.
    val initialLevel = remember(grouping) {
        when (grouping) {
            TimelineGrouping.None  -> 2
            TimelineGrouping.Year  -> 1
            TimelineGrouping.Month -> 3
            TimelineGrouping.Day   -> 4
        }
    }
    var levelIndex by rememberSaveable { mutableIntStateOf(initialLevel) }
    val (columnCount, effectiveGrouping) = zoomLevels[levelIndex]

    @Suppress("RememberReturnType")
    val dateFormat = remember(effectiveGrouping) {
        // None still needs a (never-rendered) formatter to keep the type concrete; the
        // grouped loop short-circuits the header below so it's only used as a sentinel.
        val pattern = when (effectiveGrouping) {
            TimelineGrouping.None -> "yyyy"
            TimelineGrouping.Day -> "d MMMM yyyy"
            TimelineGrouping.Month -> "MMMM yyyy"
            TimelineGrouping.Year -> "yyyy"
        }
        SimpleDateFormat(pattern, Locale.getDefault())
    }
    val grouped = remember(items, effectiveGrouping) {
        if (effectiveGrouping == TimelineGrouping.None) {
            // Single flat bucket — no header row will be emitted for it (the header loop
            // below skips MonthHeader entirely in None mode). Wrapping in a one-element
            // map keeps the same Map.Entry shape the for-loop already destructures.
            mapOf("" to items).entries.toList()
        } else {
            items.groupBy { item -> dateFormat.format(Date(item.captureTimeMs)) }.entries.toList()
        }
    }

    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Two-finger pinch detector that does NOT eat single-finger drags. It only activates
    // once the second finger goes down, which leaves the grid's own LazyVerticalGrid
    // scroll free to handle vertical drags without competing for the gesture. Pinch is
    // isolated to a deliberate two-finger interaction.
    //
    // Each gesture commits exactly one level step in either direction once the distance
    // ratio crosses ±30%. Cycling through every level needs multiple gestures, which
    // matches users' expectation that one pinch = one zoom step and keeps the snap from
    // feeling jittery while the fingers are still moving.
    val pinchModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            var second: androidx.compose.ui.input.pointer.PointerInputChange? = null
            // Wait for either: (a) a second finger to enter → pinch mode, or
            //                  (b) the first finger to lift → bail out so this gesture
            //                      stays a regular drag for the grid.
            while (second == null) {
                val event = awaitPointerEvent()
                if (event.changes.none { p -> p.pressed }) return@awaitEachGesture
                second = event.changes.firstOrNull { p -> p.id != firstDown.id && p.pressed }
            }
            // Reference distance is reset to the current distance AFTER each snap, so the
            // user can keep zooming in / out continuously during one gesture (every +/-30%
            // since the last snap fires a new step) without lifting and re-pinching.
            var refDist = kotlin.math.max(
                1f,
                kotlin.math.hypot(
                    (firstDown.position.x - second.position.x).toDouble(),
                    (firstDown.position.y - second.position.y).toDouble(),
                ).toFloat(),
            )
            while (true) {
                val event = awaitPointerEvent()
                val p1 = event.changes.firstOrNull { p -> p.id == firstDown.id }
                val p2 = event.changes.firstOrNull { p -> p.id == second.id }
                if (p1 == null || p2 == null || !p1.pressed || !p2.pressed) break
                val curDist = kotlin.math.max(
                    1f,
                    kotlin.math.hypot(
                        (p1.position.x - p2.position.x).toDouble(),
                        (p1.position.y - p2.position.y).toDouble(),
                    ).toFloat(),
                )
                val ratio = curDist / refDist
                // Pinch-OUT (fingers spread, ratio > 1) zooms IN — bigger tiles, finer
                // day-level navigation. Pinch-IN (ratio < 1) zooms OUT — smaller tiles,
                // broader year-level overview. levelIndex grows as columns SHRINK in
                // the zoomLevels list (L0=6 cols flat, L5=1 col day-grouped), so
                // pinch-out increments toward L5. After each snap refDist is reset so
                // the same gesture can roll through multiple levels.
                when {
                    ratio >= 1.30f && levelIndex < zoomLevels.lastIndex -> {
                        levelIndex += 1
                        onGroupingChanged(zoomLevels[levelIndex].second)
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                        refDist = curDist
                    }
                    ratio <= 1f / 1.30f && levelIndex > 0 -> {
                        levelIndex -= 1
                        onGroupingChanged(zoomLevels[levelIndex].second)
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                        refDist = curDist
                    }
                }
                // Consume so the grid doesn't try to scroll while both fingers are down.
                p1.consume(); p2.consume()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        state = gridState,
        contentPadding = PaddingValues(
            top = topContentPadding + 8.dp,
            bottom = 120.dp,
            start = 20.dp,
            end = 20.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxSize()
            .then(pinchModifier),
    ) {
        if (permissionState == PermissionState.Denied ||
            permissionState == PermissionState.PermanentlyDenied
        ) {
            item(span = { GridItemSpan(columnCount) }) {
                PermissionBanner(
                    permanent = permissionState == PermissionState.PermanentlyDenied,
                    onAction = {
                        if (permissionState == PermissionState.PermanentlyDenied) {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            ).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } else {
                            onPermissionGrant()
                        }
                    },
                )
            }
        }

        // "On this day" memories carousel — full-width row above the photo grid. Hidden
        // entirely when there are no matching items, so first-time users / users with no
        // historical photos on today's date never see an empty placeholder.
        if (onThisDayByYear.isNotEmpty()) {
            item(span = { GridItemSpan(columnCount) }, key = "on_this_day") {
                OnThisDayCarousel(
                    yearGroups = onThisDayByYear,
                    onPhotoClick = onPhotoClick,
                )
            }
        }

        for ((month, monthItems) in grouped) {
            // Split month-group count by media type so the header reads
            // "11 photos, 1 video" instead of an undifferentiated "12 photos".
            val monthVideos = monthItems.count { item ->
                val mt = when (item) {
                    is GalleryItem.LocalOnly -> item.local.mimeType
                    is GalleryItem.Synced    -> item.local.mimeType
                    is GalleryItem.CloudOnly -> item.cloud.mimeType
                }
                mt.startsWith("video/")
            }
            val monthPhotos = monthItems.size - monthVideos
            // None-grouping levels (L0..L2) skip the header entirely so the user gets a
            // truly flat thumbnail wall. The single placeholder "bucket" produced above
            // is still iterated to render its items.
            if (effectiveGrouping != TimelineGrouping.None) {
                val selectedInGroup = monthItems.count { it in selectedItems }
                item(span = { GridItemSpan(columnCount) }) {
                    MonthHeader(
                        month = month,
                        photoCount = monthPhotos,
                        videoCount = monthVideos,
                        isSelectionMode = isSelectionMode,
                        selectedInGroup = selectedInGroup,
                        groupSize = monthItems.size,
                        onToggleGroup = { onToggleGroup(monthItems) },
                    )
                }
            }
            items(monthItems, key = { item ->
                when (item) {
                    is GalleryItem.LocalOnly -> "local_${item.local.uri}"
                    is GalleryItem.Synced    -> "synced_${item.local.uri}"
                    is GalleryItem.CloudOnly -> "cloud_${item.cloud.linkId}"
                }
            }) { item ->
                val cloudId = when (item) {
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                    is GalleryItem.Synced    -> item.cloud.linkId
                    is GalleryItem.LocalOnly -> null
                }
                PhotoCell(
                    item              = item,
                    selected          = item in selectedItems,
                    isSelectionMode   = isSelectionMode,
                    isHiddenOnDevice  = cloudId != null && cloudId in hiddenCloudLinkIds,
                    isDownloaded      = cloudId != null && cloudId in downloadedCloudLinkIds,
                    onClick           = {
                        if (isSelectionMode) onToggleSelect(item)
                        else onPhotoClick(items, items.indexOf(item))
                    },
                    onLongClick = { onLongPress(item) },
                    onRequestThumbnail = onRequestThumbnail,
                    onCancelThumbnail  = onCancelThumbnail,
                )
            }
        }
    }

        // Timeline scrubber sidebar — fades in while scrolling, draggable to seek.
        // Reads the effective (pinch-controlled) grouping so its tooltip format matches
        // the headers the user is currently seeing.
        TimelineScrubber(
            gridState = gridState,
            items = items,
            grouping = effectiveGrouping,
            topPadding = topContentPadding + 8.dp,
            bottomPadding = 120.dp,
        )
    }
}

@Composable
private fun OnThisDayCarousel(
    yearGroups: List<Pair<Int, List<GalleryItem>>>,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
) {
    val appColors = AppColors.current
    val now = remember { Calendar.getInstance().get(Calendar.YEAR) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.gallery_on_this_day),
            color = appColors.fgPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.44).sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            yearGroups.forEach { (year, yearItems) ->
                val yearsAgo = (now - year).coerceAtLeast(1)
                items(yearItems, key = { item ->
                    val id = when (item) {
                        is GalleryItem.LocalOnly -> "local_${item.local.uri}"
                        is GalleryItem.Synced    -> "synced_${item.local.uri}"
                        is GalleryItem.CloudOnly -> "cloud_${item.cloud.linkId}"
                    }
                    "otd_${year}_$id"
                }) { item ->
                    OnThisDayTile(
                        item = item,
                        yearsAgo = yearsAgo,
                        onClick = {
                            onPhotoClick(yearItems, yearItems.indexOf(item))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnThisDayTile(
    item: GalleryItem,
    yearsAgo: Int,
    onClick: () -> Unit,
) {
    val imageModel: Any? = when (item) {
        is GalleryItem.LocalOnly -> android.net.Uri.parse(item.local.uri)
        is GalleryItem.Synced    -> android.net.Uri.parse(item.local.uri)
        is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl
    }
    val mimeType = when (item) {
        is GalleryItem.LocalOnly -> item.local.mimeType
        is GalleryItem.Synced    -> item.local.mimeType
        is GalleryItem.CloudOnly -> item.cloud.mimeType
    }
    val isVideo = mimeType.startsWith("video/")
    val yearsAgoLabel = androidx.compose.ui.res.pluralStringResource(
        R.plurals.count_years_ago_plural, yearsAgo, yearsAgo,
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    val tileRequest = remember(imageModel) {
        ImageRequest.Builder(context).data(imageModel).size(384).build()
    }
    Box(
        modifier = Modifier
            .size(90.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Bg2)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = tileRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Subtle bottom gradient so the years-ago caption stays legible over bright photos.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    ),
                ),
        )
        Text(
            text = yearsAgoLabel,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        if (isVideo) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionBanner(permanent: Boolean, onAction: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(ErrorChipBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                text = if (permanent)
                    stringResource(R.string.permission_permanently_denied_banner)
                else
                    stringResource(R.string.permission_denied_banner),
                color = ErrorColor,
                fontSize = 13.sp,
            )
            TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                Text(
                    text = if (permanent)
                        stringResource(R.string.permission_open_settings)
                    else
                        stringResource(R.string.permission_grant_button),
                    color = Accent,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(
    month: String,
    photoCount: Int,
    videoCount: Int,
    isSelectionMode: Boolean = false,
    selectedInGroup: Int = 0,
    groupSize: Int = 0,
    onToggleGroup: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading tri-state circle — only while selecting. Tapping it selects/deselects the
        // whole date group. Mirrors the per-cell indicator styling so the two read as one
        // selection language: filled accent + check when every item is in, accent with a
        // hollow centre when partial, dim bordered ring when none.
        if (isSelectionMode) {
            val allSelected = groupSize > 0 && selectedInGroup == groupSize
            val partiallySelected = selectedInGroup > 0 && !allSelected
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleGroup)
                    .then(
                        if (allSelected || partiallySelected)
                            Modifier.background(Accent, CircleShape)
                        else Modifier
                            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                            .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    allSelected -> Icon(
                        Icons.Default.Check, stringResource(R.string.gallery_deselect_all),
                        tint = Color.White, modifier = Modifier.size(14.dp),
                    )
                    partiallySelected -> Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color.White, CircleShape),
                    )
                }
            }
        }
        Text(
            text = month,
            color = FgPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.44).sp,
            modifier = Modifier.weight(1f),
        )
        // Use the plurals-aware count strings so "1 photo" / "1 video" render correctly
        // instead of "1 photos" / "1 videos". Mixed case is a simple localised join of the two
        // plural results — most romance + germanic languages accept comma+space, and the
        // comma is invariant.
        val photosText = androidx.compose.ui.res.pluralStringResource(
            R.plurals.count_photos_plural, photoCount, photoCount,
        )
        val videosText = androidx.compose.ui.res.pluralStringResource(
            R.plurals.count_videos_plural, videoCount, videoCount,
        )
        val countLabel = when {
            photoCount > 0 && videoCount > 0 -> "$photosText, $videosText"
            videoCount > 0 -> videosText
            else -> photosText
        }
        Text(
            text = countLabel,
            color = FgMute,
            fontSize = 12.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoCell(
    item: GalleryItem,
    selected: Boolean = false,
    isSelectionMode: Boolean = false,
    isHiddenOnDevice: Boolean = false,
    isDownloaded: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onRequestThumbnail: (linkId: String) -> Unit = {},
    onCancelThumbnail: (linkId: String) -> Unit = {},
) {
    val imageModel: Any? = when (item) {
        is GalleryItem.LocalOnly -> android.net.Uri.parse(item.local.uri)
        is GalleryItem.Synced    -> android.net.Uri.parse(item.local.uri)
        is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl
    }

    // ── Lazy thumbnail wiring ────────────────────────────────────────────────
    //
    // Cloud-only and Synced cells whose thumbnailUrl is missing represent rows
    // that the sync pass populated in metadata-only mode: rows decrypt on
    // visibility, cancel on leave. The DAO updates the row when the decrypt
    // completes and the Flow-based observation re-emits the new thumbnailUrl
    // into [item], which triggers recomposition and AsyncImage renders the
    // freshly-cached file.
    //
    // Synced cells already render the local file URI, so a missing thumbnailUrl
    // is invisible to the user — we still queue the decrypt though, so the
    // cloud-only view (or another device viewing the same row) gets it ready.
    val cloudLinkId: String? = when (item) {
        is GalleryItem.CloudOnly -> item.cloud.linkId.takeIf { item.cloud.thumbnailUrl == null }
        is GalleryItem.Synced    -> item.cloud.linkId.takeIf { item.cloud.thumbnailUrl == null }
        is GalleryItem.LocalOnly -> null
    }
    if (cloudLinkId != null) {
        // 120ms debounce: during fast flings hundreds of cells fly in and out per second.
        // Without the gate every one of them launched a DAO lookup + scheduler request, then
        // immediately got cancelled when the cell rolled past — pure overhead. Waiting
        // 120ms before queueing the work means a fast-scroll cell is disposed BEFORE the
        // request fires (the LaunchedEffect coroutine cancels with the cell), so the
        // request path only runs for cells the user actually pauses on.
        androidx.compose.runtime.LaunchedEffect(cloudLinkId) {
            kotlinx.coroutines.delay(120)
            onRequestThumbnail(cloudLinkId)
        }
        androidx.compose.runtime.DisposableEffect(cloudLinkId) {
            onDispose { onCancelThumbnail(cloudLinkId) }
        }
    }

    val isAwaitingThumbnail = imageModel == null && (item is GalleryItem.CloudOnly)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Bg2)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (selected) Modifier.border(2.5.dp, Accent, RoundedCornerShape(10.dp))
                else Modifier
            ),
    ) {
        if (isAwaitingThumbnail) {
            // Placeholder while the on-demand decrypt is in progress. The Bg2-filled Box
            // (from the parent) already provides the dark tile background; the centered
            // photo icon is the visual cue that this slot is "loading", at low opacity
            // so it reads as a hint rather than competing with surrounding tiles.
            Icon(
                Icons.Default.Photo,
                contentDescription = null,
                tint = FgDim.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
            )
        } else {
            AsyncImage(
                model              = imageModel,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
                    .then(if (selected) Modifier.background(Accent.copy(alpha = 0.15f)) else Modifier),
            )
        }

        // Status badge — shown only for cloud-related states.
        //   LocalOnly  = no badge (device-only photos need no indicator)
        //   Synced     = green cloud (backed up AND on device)
        //   CloudOnly  = white cloud (only in Drive — not on device)
        // A CloudOnly cell upgrades to the green badge once its linkId has a SYNCED local copy:
        // the user downloaded it but the static item snapshot still reads CloudOnly. Mirrors the
        // same upgrade the photo viewer applies.
        when (item) {
            is GalleryItem.LocalOnly -> { /* no badge */ }
            is GalleryItem.Synced    -> SyncedCloudBadge()
            is GalleryItem.CloudOnly -> if (isDownloaded) SyncedCloudBadge() else CloudBadge()
        }

        // Hidden-eye overlay — drawn on the cell when its cloud linkId is referenced by a
        // SyncState row with status HIDDEN. A heavy black scrim PLUS a real RenderEffect
        // blur (Android 12+) ensures none of the underlying content is readable even by
        // squinting — a plain dim still showed recognisable shapes and colours. Pre-S
        // devices fall back to the scrim alone (Compose's blur modifier no-ops on older
        // platforms without the BlurEffect API).
        if (isHiddenOnDevice) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            Modifier.blur(28.dp)
                        else
                            Modifier,
                    )
                    .background(Color.Black.copy(alpha = 0.7f)),
            )
            HiddenEyeBadge()
        }

        // Video play indicator — shown for any video item when not in selection mode
        val itemMimeType = when (item) {
            is GalleryItem.LocalOnly -> item.local.mimeType
            is GalleryItem.Synced    -> item.local.mimeType
            is GalleryItem.CloudOnly -> item.cloud.mimeType
        }
        if (itemMimeType.startsWith("video/") && !isSelectionMode) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        // Selection circle
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .padding(5.dp)
                    .size(22.dp)
                    .align(Alignment.TopStart),
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Check, stringResource(R.string.cd_status_selected),
                            tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                            .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                    )
                }
            }
        }
    }
}

/** White cloud — photo exists only in Drive, not on this device. */
@Composable
private fun BoxScope.CloudBadge() {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(5.dp)
            .size(20.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Cloud,
            contentDescription = stringResource(R.string.cd_status_cloud_only),
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** Green cloud — backed up to Drive AND still on this device. Safe to remove from device. */
@Composable
private fun BoxScope.SyncedCloudBadge() {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(5.dp)
            .size(20.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Cloud,
            contentDescription = stringResource(R.string.cd_status_backed_up_device),
            tint = Color(0xFF30D158),
            modifier = Modifier.size(12.dp),
        )
    }
}

/** Crossed-out eye — this cloud photo's local twin lives in the Hidden vault. Visible only
 *  from this device, since HIDDEN_PHOTO_URIS / SyncStatus.HIDDEN are per-installation state.
 *  Placed in the top-end corner so the bottom-end cloud / device badge can coexist. */
@Composable
private fun BoxScope.HiddenEyeBadge() {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(5.dp)
            .size(20.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.VisibilityOff,
            contentDescription = stringResource(R.string.cd_status_hidden_local),
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** Phone icon — only on this device, NOT backed up. Do NOT delete without backup! */
@Composable
private fun BoxScope.DeviceBadge() {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(5.dp)
            .size(20.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Phone,
            contentDescription = stringResource(R.string.cd_status_device_only),
            tint = FgMute,
            modifier = Modifier.size(11.dp),
        )
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

// ── Content filter bottom sheet ───────────────────────────────────────────────

private val filterChipShape = RoundedCornerShape(10.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentFilterSheet(
    currentFilter: ContentFilter,
    currentCategory: GalleryFilter,
    onApply: (ContentFilter) -> Unit,
    onCategorySelected: (GalleryFilter) -> Unit,
    onDismiss: () -> Unit,
    showCategorySection: Boolean = true,
) {
    var mediaType by remember { mutableStateOf(currentFilter.mediaType) }
    var syncStatus by remember { mutableStateOf(currentFilter.syncStatus) }
    var year by remember { mutableStateOf(currentFilter.year) }
    var month by remember { mutableStateOf(currentFilter.month) }
    var category by remember { mutableStateOf(currentCategory) }

    fun applyNow(
        mt: MediaType = mediaType,
        ss: SyncStatusFilter = syncStatus,
        y: Int? = year,
        m: Int? = month,
    ) = onApply(ContentFilter(mediaType = mt, syncStatus = ss, year = y, month = m))

    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val years = remember { (currentYear downTo currentYear - 10).toList() }
    val anyMonthLabel = stringResource(R.string.filter_any)
    val months = remember(anyMonthLabel) {
        listOf(null to anyMonthLabel) + (1..12).map { m ->
            m to SimpleDateFormat("MMMM", Locale.getDefault()).format(
                Calendar.getInstance().apply { set(Calendar.MONTH, m - 1) }.time
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Handle drag indicator
        Text(
            text = stringResource(R.string.filter_title),
            color = FgPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )

        // ── Categories (server-assigned PhotoTag) ────────────────────────────
        // Matches the Drive web Photos page categories: Favorites, Screenshots, Videos,
        // Live Photos, Motion Photos, Selfies, Portraits, Bursts, Panoramas, RAW.
        // These are tag-id 0..9 — server populates them automatically; local-only items
        // (not yet backed up) cannot be filtered by category.
        if (showCategorySection) {
            FilterSectionLabel(stringResource(R.string.gallery_filter_categories))
            val categories = listOf(
                GalleryFilter.All          to stringResource(R.string.gallery_filter_all),
                GalleryFilter.Favorites    to stringResource(R.string.gallery_filter_favorites),
                GalleryFilter.Screenshots  to stringResource(R.string.gallery_filter_screenshots),
                GalleryFilter.Videos       to stringResource(R.string.filter_type_videos),
                GalleryFilter.LivePhotos   to stringResource(R.string.gallery_filter_live_photos),
                GalleryFilter.MotionPhotos to stringResource(R.string.gallery_filter_motion_photos),
                GalleryFilter.Selfies      to stringResource(R.string.gallery_filter_selfies),
                GalleryFilter.Portraits    to stringResource(R.string.gallery_filter_portraits),
                GalleryFilter.Bursts       to stringResource(R.string.gallery_filter_bursts),
                GalleryFilter.Panoramas    to stringResource(R.string.gallery_filter_panoramas),
                GalleryFilter.Raw          to stringResource(R.string.gallery_filter_raw),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { (cat, label) ->
                    item(key = "cat_${cat.name}") {
                        FilterChip(
                            label = label,
                            selected = category == cat,
                            onClick = {
                                category = cat
                                onCategorySelected(cat)
                            },
                        )
                    }
                }
            }
        }

        // ── Media type ───────────────────────────────────────────────────────
        FilterSectionLabel(stringResource(R.string.filter_type_label))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                MediaType.All to stringResource(R.string.gallery_filter_all),
                MediaType.PhotosOnly to stringResource(R.string.gallery_tab_photos),
                MediaType.VideosOnly to stringResource(R.string.filter_type_videos),
            ).forEach { (type, label) ->
                FilterChip(
                    label = label,
                    selected = mediaType == type,
                    onClick = { mediaType = type; applyNow(mt = type) },
                )
            }
        }

        // ── Sync status ──────────────────────────────────────────────────────
        FilterSectionLabel(stringResource(R.string.filter_sync_label))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                SyncStatusFilter.All to stringResource(R.string.gallery_filter_all),
                SyncStatusFilter.LocalOnly to stringResource(R.string.filter_sync_local),
                SyncStatusFilter.BackedUp to stringResource(R.string.filter_sync_backedup),
            ).forEach { (status, label) ->
                FilterChip(
                    label = label,
                    selected = syncStatus == status,
                    onClick = { syncStatus = status; applyNow(ss = status) },
                )
            }
        }

        // ── Year ─────────────────────────────────────────────────────────────
        FilterSectionLabel(stringResource(R.string.filter_year_label))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "year_any") {
                FilterChip(
                    label = stringResource(R.string.filter_any),
                    selected = year == null,
                    onClick = { year = null; applyNow(y = null) },
                )
            }
            years.forEach { y ->
                item(key = "year_$y") {
                    FilterChip(
                        label = "$y",
                        selected = year == y,
                        onClick = { year = y; applyNow(y = y) },
                    )
                }
            }
        }

        // ── Month ────────────────────────────────────────────────────────────
        FilterSectionLabel(stringResource(R.string.filter_month_label))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            months.forEach { (m, label) ->
                item(key = "month_${m ?: "any"}") {
                    FilterChip(
                        label = label,
                        selected = month == m,
                        onClick = { month = m; applyNow(m = m) },
                    )
                }
            }
        }

        // ── Reset ─────────────────────────────────────────────────────────────
        val colors = AppColors.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .background(colors.chipUnselectedBg, RoundedCornerShape(14.dp))
                .border(0.5.dp, PillBorder, RoundedCornerShape(14.dp))
                .clickable {
                    mediaType = MediaType.All; syncStatus = SyncStatusFilter.All; year = null; month = null
                    category = GalleryFilter.All
                    onCategorySelected(GalleryFilter.All)
                    applyNow(mt = MediaType.All, ss = SyncStatusFilter.All, y = null, m = null)
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕  ${stringResource(R.string.filter_reset)}", color = FgDim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun FilterSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = FgMute,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    )
}

@Composable
internal fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    Box(
        modifier = Modifier
            .background(
                if (selected) colors.chipSelectedBg else colors.chipUnselectedBg,
                filterChipShape,
            )
            .then(
                if (!selected) Modifier.border(0.5.dp, PillBorder, filterChipShape) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) FgPrimary else FgDim,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

// ── Shared filter rail ────────────────────────────────────────────────────────

enum class SharedFilter { SharedWithMe, SharedByMe }

@Composable
internal fun SharedFilterRail(
    selectedFilter: SharedFilter,
    onFilterSelected: (SharedFilter) -> Unit,
    activeEmailFilter: String? = null,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        // Single toggle pill — tap flips between "with me" and "by me". Direction icon
        // makes the active scope obvious (incoming arrow for with-me, outgoing for by-me).
        item(key = "shared_toggle") {
            val isWithMe = selectedFilter == SharedFilter.SharedWithMe
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .background(AppColors.current.chipSelectedBg, pillShape)
                    .clickable {
                        onFilterSelected(
                            if (isWithMe) SharedFilter.SharedByMe else SharedFilter.SharedWithMe,
                        )
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (isWithMe) Icons.AutoMirrored.Filled.CallReceived
                    else Icons.AutoMirrored.Filled.CallMade,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    stringResource(
                        if (isWithMe) R.string.share_shared_with_me
                        else R.string.share_shared_by_me,
                    ),
                    color = FgPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = FgDim,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        // Filter button — highlighted when an email filter is active
        item(key = "filter_button") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(if (activeEmailFilter != null) Accent.copy(alpha = 0.15f) else PillBg, pillShape)
                    .border(0.5.dp, if (activeEmailFilter != null) Accent else PillBorder, pillShape)
                    .clickable { onFilterClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.FilterList, stringResource(R.string.share_filter_account),
                    tint = if (activeEmailFilter != null) Accent else FgDim,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}
