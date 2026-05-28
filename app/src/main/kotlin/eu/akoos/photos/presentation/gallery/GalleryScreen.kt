package eu.akoos.photos.presentation.gallery

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
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
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.albums.AlbumsFilter
import eu.akoos.photos.presentation.albums.AlbumsScreen
import eu.akoos.photos.presentation.albums.AlbumsViewModel
import eu.akoos.photos.domain.entity.LocalAlbum
import eu.akoos.photos.presentation.shared.SharedScreen
import eu.akoos.photos.presentation.shared.SharedViewModel
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Accent2
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.ArcTrack
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg1
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
import eu.akoos.photos.presentation.theme.StatusSynced
import androidx.compose.material.icons.filled.DateRange
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
    onLocalAlbumClick: (LocalAlbum) -> Unit = {},
    onMergedAlbumClick: (LocalAlbum, Album) -> Unit = { _, _ -> },
    onSettingsClick: () -> Unit,
    onHiddenAlbumClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
    var sharedFilter by remember { mutableStateOf(SharedFilter.SharedWithMe) }
    var activeEmailFilter by remember { mutableStateOf<String?>(null) }
    var showEmailFilterSheet by remember { mutableStateOf(false) }

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
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
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

    var showAlbumsFilterSheet by remember { mutableStateOf(false) }
    val albumsFilterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── Grouping sheet state ──────────────────────────────────────────────────
    var showGroupingSheet by remember { mutableStateOf(false) }
    val groupingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

    // ── Add-to-album multi-action ─────────────────────────────────────────────
    // Drives the picker sheet, the consent dialog and the new-album inline create.
    var showAddToAlbumSheet by remember { mutableStateOf(false) }
    var showCreateAlbumInline by remember { mutableStateOf(false) }
    val addToAlbumSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Add-to-album used to require an Android Q+ MediaStore write-consent dialog (we moved
    // files between buckets). The virtual-album pivot dropped that — every leg now runs inline.

    val addToAlbumState = state.addToAlbumState
    LaunchedEffect(addToAlbumState) {
        when (addToAlbumState) {
            is AddToAlbumState.Done -> {
                val totalAdded = addToAlbumState.cloudAdded + addToAlbumState.localMoved
                val msg = if (totalAdded > 0) {
                    context.getString(R.string.gallery_added_to_album, totalAdded, addToAlbumState.albumName)
                } else {
                    // Edge case: both legs reported 0 added but the operation still "succeeded"
                    // (e.g. user picked an existing album the photos were already in). Fall back
                    // to the localised "added to" template with 0 so we still surface a snackbar.
                    context.getString(R.string.gallery_added_to_album, 0, addToAlbumState.albumName)
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
                            EmptyState(topPadding = headerHeightDp)
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
                                grouping           = state.timelineGrouping,
                                hiddenCloudLinkIds = state.hiddenCloudLinkIds,
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
                onLocalAlbumClick = onLocalAlbumClick,
                onMergedAlbumClick = onMergedAlbumClick,
            )
            2 -> SharedScreen(
                topPadding = headerHeightDp,
                filter = sharedFilter,
                activeEmailFilter = activeEmailFilter,
                onAlbumClick = onAlbumClick,
            )
        }

        // ── FLOATING HEADER (normal mode) ─────────────────────────────────────
        AnimatedVisibility(
            visible = showOverlays && !state.isSelectionMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { headerHeightPx = it.size.height },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(top = 10.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (selectedTab) {
                        0 -> {
                            FilterRail(
                                selectedFilter = state.selectedFilter,
                                totalCount = state.items.size,
                                onFilterSelected = viewModel::onFilterSelected,
                                contentFilter = state.contentFilter,
                                onSearchClick = onSearchClick,
                                onClearContentFilter = { viewModel.setContentFilter(ContentFilter()) },
                                grouping = state.timelineGrouping,
                                onShowGroupingSheet = { showGroupingSheet = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        1 -> {
                            AlbumsFilterRail(
                                selectedFilter = albumsState.albumsFilter,
                                onFilterSelected = albumsViewModel::setFilter,
                                onHiddenAlbumClick = onHiddenAlbumClick,
                                onShowFilterSheet = { showAlbumsFilterSheet = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        2 -> {
                            SharedFilterRail(
                                selectedFilter = sharedFilter,
                                onFilterSelected = { filter ->
                                    sharedFilter = filter
                                    activeEmailFilter = null
                                },
                                activeEmailFilter = activeEmailFilter,
                                onFilterClick = { showEmailFilterSheet = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    val isOnlineNow by viewModel.isOnline.collectAsStateWithLifecycle()
                    AvatarButton(
                        initial         = state.userInitial,
                        storageFraction = state.storageFraction,
                        isSyncing       = state.isSyncing || albumsState.isLoading,
                        isOffline       = !isOnlineNow,
                        onClick         = onSettingsClick,
                    )
                }

            }
        }

        // ── SELECTION HEADER ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible = state.isSelectionMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // No translucent grey strip behind the pills — a full-width bg0 wash
                    // would make the selection bar look heavier than the regular photo
                    // chips on the same screen. Floating pills match the rest of the top
                    // chrome.
                    .onGloballyPositioned { headerHeightPx = it.size.height }
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Cancel
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(PillBg, CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable { viewModel.clearSelection() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.gallery_cancel_selection),
                        tint = appColors.fgPrimary, modifier = Modifier.size(20.dp))
                }
                // Split the selection counter by media type — gallery items can be local-only,
                // synced or cloud-only, but in every case we only care about photo-vs-video.
                // Reusing the same helper-style logic as AlbumDetailScreen for visual parity.
                val selectedPhotosCount = state.selectedItems.count { item ->
                    val mt: String = when (item) {
                        is eu.akoos.photos.domain.entity.GalleryItem.LocalOnly -> item.local.mimeType
                        is eu.akoos.photos.domain.entity.GalleryItem.Synced    -> item.local.mimeType
                        is eu.akoos.photos.domain.entity.GalleryItem.CloudOnly -> item.cloud.mimeType
                    }
                    !mt.startsWith("video/")
                }
                val selectedVideosCount = state.selectedCount - selectedPhotosCount
                val selPhotosText = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.count_photos_plural, selectedPhotosCount, selectedPhotosCount,
                )
                val selVideosText = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.count_videos_plural, selectedVideosCount, selectedVideosCount,
                )
                val selectionLabel = when {
                    selectedPhotosCount > 0 && selectedVideosCount > 0 -> "$selPhotosText, $selVideosText"
                    selectedVideosCount > 0 -> selVideosText
                    else -> selPhotosText
                }
                Text(
                    selectionLabel,
                    color = appColors.fgPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Three primary actions stay outside the dropdown — they're the ones
                    // users reach for most often during a bulk select. Less-frequent
                    // operations (Strip metadata, Hide) collapse into the More menu so the
                    // bar fits even when the selection-count text is long.
                    //
                    // Download — hidden when the entire selection is local-only since there's
                    // nothing on the cloud side to download.
                    val onlyLocalSelected = state.selectedItems.isNotEmpty() &&
                        state.selectedItems.all { it is GalleryItem.LocalOnly }
                    val isDownloading = multiDownloadState is MultiDownloadState.Working
                    if (!onlyLocalSelected) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(PillBg, CircleShape)
                                .border(0.5.dp, PillBorder, CircleShape)
                                .clickable(enabled = !isDownloading) { viewModel.downloadSelected() },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isDownloading) {
                                val progress = multiDownloadState as MultiDownloadState.Working
                                CircularProgressIndicator(
                                    progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                                    color = Accent, strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Icon(Icons.Default.FileDownload, stringResource(R.string.gallery_download_selected),
                                    tint = appColors.accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    // Add to album — opens picker bottom sheet
                    val isAddingToAlbum = addToAlbumState is AddToAlbumState.Working
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(PillBg, CircleShape)
                            .border(0.5.dp, PillBorder, CircleShape)
                            .clickable(enabled = !isAddingToAlbum) { showAddToAlbumSheet = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isAddingToAlbum) {
                            CircularProgressIndicator(
                                color = Accent, strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(Icons.Default.PhotoAlbum, stringResource(R.string.gallery_add_to_album),
                                tint = appColors.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                    // Delete
                    val isWorking = multiDeleteState is MultiDeleteState.Working
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(PillBg, CircleShape)
                            .border(0.5.dp, PillBorder, CircleShape)
                            .clickable(enabled = !isWorking) { showMultiDeleteSheet = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isWorking) {
                            CircularProgressIndicator(
                                color = ErrorColor, strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(Icons.Default.DeleteOutline, stringResource(R.string.gallery_delete_selected),
                                tint = appColors.errorColor, modifier = Modifier.size(20.dp))
                        }
                    }
                    // More menu — Strip metadata + Hide live here. Same DropdownMenu styling
                    // as the viewer's overflow so the visual language matches everywhere.
                    Box {
                        var moreExpanded by remember { mutableStateOf(false) }
                        val isStripping = state.multiStripState is MultiStripState.Working
                        val isHiding = multiDeleteState is MultiDeleteState.Working
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(PillBg, CircleShape)
                                .border(0.5.dp, PillBorder, CircleShape)
                                .clickable { moreExpanded = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isStripping || isHiding) {
                                CircularProgressIndicator(
                                    color = Accent, strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Icon(Icons.Default.MoreVert, "More",
                                    tint = appColors.fgPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = moreExpanded,
                            onDismissRequest = { moreExpanded = false },
                            shape = RoundedCornerShape(18.dp),
                            containerColor = appColors.cardBg,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, appColors.pillBorder),
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.gallery_strip_metadata),
                                    color = appColors.fgPrimary) },
                                leadingIcon = { Icon(Icons.Default.PrivacyTip, null,
                                    tint = appColors.fgPrimary, modifier = Modifier.size(20.dp)) },
                                enabled = !isStripping,
                                onClick = {
                                    moreExpanded = false
                                    viewModel.stripMetadataSelected()
                                },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Hide selected",
                                    color = appColors.fgPrimary) },
                                leadingIcon = { Icon(Icons.Default.VisibilityOff, null,
                                    tint = appColors.fgPrimary, modifier = Modifier.size(20.dp)) },
                                enabled = !isHiding,
                                onClick = {
                                    moreExpanded = false
                                    viewModel.hideSelected()
                                },
                            )
                        }
                    }
                }
            }
        }

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
                onTabSelected = { selectedTab = it },
            )
        }

        SnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        )
    }

    // ── Content filter bottom sheet ───────────────────────────────────────────
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = filterSheetState,
            containerColor = Bg2,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            ContentFilterSheet(
                currentFilter = state.contentFilter,
                currentCategory = state.selectedFilter,
                onApply = { filter -> viewModel.setContentFilter(filter) },
                onCategorySelected = { cat -> viewModel.onFilterSelected(cat) },
                onDismiss = { showFilterSheet = false },
            )
        }
    }

    // ── Albums filter bottom sheet ────────────────────────────────────────────
    if (showAlbumsFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAlbumsFilterSheet = false },
            sheetState = albumsFilterSheetState,
            containerColor = Bg2,
        ) {
            AlbumsFilterSheet(
                currentFilter = albumsState.albumsFilter,
                onApply = { albumsViewModel.setFilter(it) },
                onDismiss = { showAlbumsFilterSheet = false },
            )
        }
    }

    // ── Grouping picker sheet ─────────────────────────────────────────────────
    if (showGroupingSheet) {
        ModalBottomSheet(
            onDismissRequest = { showGroupingSheet = false },
            sheetState = groupingSheetState,
            containerColor = Bg2,
        ) {
            GroupingPickerSheet(
                current = state.timelineGrouping,
                onSelect = { grouping ->
                    viewModel.setTimelineGrouping(grouping)
                    showGroupingSheet = false
                },
                onDismiss = { showGroupingSheet = false },
            )
        }
    }

    // ── Shared email filter sheet ─────────────────────────────────────────────
    if (showEmailFilterSheet) {
        val availableEmails = sharedUiState.availableEmails
        ModalBottomSheet(
            onDismissRequest = { showEmailFilterSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Bg2,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.share_filter_by_person), color = FgPrimary,
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp))
                if (availableEmails.isEmpty()) {
                    Text(stringResource(R.string.share_no_shared), color = FgMute, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (activeEmailFilter == null) Accent.copy(0.15f) else PillBg,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { activeEmailFilter = null; showEmailFilterSheet = false }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.share_all), color = if (activeEmailFilter == null) Accent else FgPrimary,
                            fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        if (activeEmailFilter == null) {
                            Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    availableEmails.forEach { email ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (activeEmailFilter == email) Accent.copy(0.15f) else PillBg,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable { activeEmailFilter = email; showEmailFilterSheet = false }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(email, color = if (activeEmailFilter == email) Accent else FgPrimary,
                                fontSize = 15.sp)
                            if (activeEmailFilter == email) {
                                Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // ── Multi-select delete sheet ─────────────────────────────────────────────
    if (showMultiDeleteSheet && state.selectedItems.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { showMultiDeleteSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Bg2,
        ) {
            MultiDeleteSheet(
                selectedItems = state.selectedItems,
                onDismiss     = { showMultiDeleteSheet = false },
                onDelete      = { freeUpSpace, deleteFromCloud ->
                    showMultiDeleteSheet = false
                    viewModel.deleteSelected(freeUpSpace, deleteFromCloud)
                },
            )
        }
    }

    // ── Add-to-album picker sheet ─────────────────────────────────────────────
    if (showAddToAlbumSheet && state.selectedItems.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { showAddToAlbumSheet = false },
            sheetState = addToAlbumSheetState,
            containerColor = Bg2,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            // Pull together both album sources from the existing AlbumsViewModel so the picker
            // matches what the Albums tab shows. Local buckets show as moves-to; cloud albums
            // show as cloud adds (plus a local move when the selection has a local file).
            val anyCloudBacked = state.selectedItems.any {
                it is GalleryItem.Synced || it is GalleryItem.CloudOnly
            }
            val anyLocal = state.selectedItems.any {
                it is GalleryItem.LocalOnly || it is GalleryItem.Synced
            }
            GalleryAddToAlbumPickerSheet(
                cloudAlbums = albumsState.albums,
                localAlbums = albumsState.localAlbums,
                selectionHasCloud = anyCloudBacked,
                selectionHasLocal = anyLocal,
                onCreateNew = {
                    showAddToAlbumSheet = false
                    showCreateAlbumInline = true
                },
                onCloudAlbumSelected = { album ->
                    showAddToAlbumSheet = false
                    viewModel.addSelectedToAlbum(
                        albumLinkId = album.linkId,
                        albumName = album.name,
                        // When the selection has any local file, also move it into the matching
                        // bucket — that's the "any photo, anywhere" intuition the maintainer wants.
                        targetIsLocalBucket = anyLocal,
                    )
                },
                onLocalAlbumSelected = { local ->
                    showAddToAlbumSheet = false
                    // Local-bucket-only target. No cloud add even if selection has cloud items —
                    // the user picked a local bucket explicitly.
                    viewModel.addSelectedToAlbum(
                        albumLinkId = null,
                        albumName = local.name,
                        targetIsLocalBucket = true,
                    )
                },
                onDismiss = { showAddToAlbumSheet = false },
            )
        }
    }

    // ── New-album inline create (launched from the picker's "+ New album" row) ─
    if (showCreateAlbumInline) {
        var newAlbumName by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { showCreateAlbumInline = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = appColors.cardBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.albums_new_album), color = appColors.fgPrimary,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = newAlbumName,
                    onValueChange = { newAlbumName = it },
                    placeholder = { Text(stringResource(R.string.albums_create_album_hint), color = FgMute) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Accent,
                        unfocusedBorderColor = Line2,
                        focusedTextColor     = appColors.fgPrimary,
                        unfocusedTextColor   = appColors.fgPrimary,
                        cursorColor          = Accent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newAlbumName.trim().isNotEmpty()) {
                            showCreateAlbumInline = false
                            viewModel.createAlbumThenAddSelected(newAlbumName)
                        }
                    }),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showCreateAlbumInline = false }) {
                        Text(stringResource(R.string.cancel), color = appColors.fgDim)
                    }
                    TextButton(
                        enabled = newAlbumName.trim().isNotEmpty(),
                        onClick = {
                            showCreateAlbumInline = false
                            viewModel.createAlbumThenAddSelected(newAlbumName)
                        },
                    ) {
                        Text(stringResource(R.string.albums_create_album), color = Accent,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ── Add-to-album picker sheet ─────────────────────────────────────────────────
//
// Bottom sheet with three section: [+ New album] row, cloud albums, local-only buckets.
// Mirrors the styling AlbumDetailScreen uses for its own bottom sheets.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryAddToAlbumPickerSheet(
    cloudAlbums: List<Album>,
    localAlbums: List<LocalAlbum>,
    selectionHasCloud: Boolean,
    selectionHasLocal: Boolean,
    onCreateNew: () -> Unit,
    onCloudAlbumSelected: (Album) -> Unit,
    onLocalAlbumSelected: (LocalAlbum) -> Unit,
    onDismiss: () -> Unit,
) {
    val appColors = AppColors.current
    // De-dup: when a local bucket and a cloud album share a name we keep the cloud entry
    // (cloud picker has additive semantics — selection lands in both) and drop the local copy.
    val cloudNames = cloudAlbums.map { it.name.lowercase() }.toSet()
    val localOnly = localAlbums.filter { it.name.lowercase() !in cloudNames }

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

        // Cloud albums — when the selection has any cloud-backed item we show these.
        // For pure-local selections we still expose them so the user can pull a local-only
        // photo into a Drive album by uploading later (today the add becomes a no-op on the
        // cloud leg, but it still moves the local copy into the matching bucket).
        if (cloudAlbums.isNotEmpty() && selectionHasCloud) {
            Text("Drive albums",
                color = appColors.fgMute, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
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

        // Local buckets — only when there's a local file in the selection.
        if (localOnly.isNotEmpty() && selectionHasLocal) {
            HorizontalDivider(color = Line2, thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Text("On this device",
                color = appColors.fgMute, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            localOnly.forEach { album ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLocalAlbumSelected(album) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val cover = album.coverUri
                    if (cover != null) {
                        AsyncImage(
                            model = android.net.Uri.parse(cover),
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
                            Icon(Icons.Default.Phone, null, tint = appColors.fgMute,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(album.name, color = appColors.fgPrimary, fontSize = 15.sp,
                            fontWeight = FontWeight.Medium)
                        Text("${album.itemCount} photos · Device",
                            color = appColors.fgMute, fontSize = 12.sp)
                    }
                }
            }
        }

        // Empty state — nothing to pick from. The + New row above is still tappable.
        if ((cloudAlbums.isEmpty() || !selectionHasCloud) &&
            (localOnly.isEmpty() || !selectionHasLocal)
        ) {
            Text(
                "No albums yet — create one above.",
                color = appColors.fgMute,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        }
    }
}

// ── Albums filter rail ────────────────────────────────────────────────────────

@Composable
private fun AlbumsFilterRail(
    selectedFilter: AlbumsFilter,
    onFilterSelected: (AlbumsFilter) -> Unit,
    onHiddenAlbumClick: () -> Unit = {},
    onShowFilterSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        // Active filter pill
        item(key = "active_filter") {
            val isFiltered = selectedFilter != AlbumsFilter.All
            val label = when (selectedFilter) {
                AlbumsFilter.All -> stringResource(R.string.gallery_filter_all)
                AlbumsFilter.Local -> stringResource(R.string.albums_filter_local)
                AlbumsFilter.BackedUp -> stringResource(R.string.albums_filter_backed_up)
            }
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .background(if (isFiltered) Accent.copy(alpha = 0.18f) else colors.filterPillBg, pillShape)
                    .then(if (!isFiltered) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
                    .clickable(enabled = isFiltered) { onFilterSelected(AlbumsFilter.All) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!isFiltered) Icon(Icons.Default.Check, null, tint = FgPrimary, modifier = Modifier.size(12.dp))
                Text(label, color = if (isFiltered) Accent else FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
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
        // Filter button
        item(key = "filter_button") {
            val isFiltered = selectedFilter != AlbumsFilter.All
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(if (isFiltered) Accent.copy(alpha = 0.18f) else PillBg, pillShape)
                    .then(if (!isFiltered) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
                    .clickable { onShowFilterSheet() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.FilterList, "Filter", tint = if (isFiltered) Accent else FgDim, modifier = Modifier.size(18.dp))
                if (isFiltered) {
                    Box(Modifier.size(8.dp).align(Alignment.TopEnd).background(Accent, CircleShape))
                }
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
private fun AvatarButton(initial: String, storageFraction: Float, isSyncing: Boolean, isOffline: Boolean = false, onClick: () -> Unit) {
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
private fun FilterRail(
    selectedFilter: GalleryFilter,
    totalCount: Int,
    onFilterSelected: (GalleryFilter) -> Unit,
    contentFilter: ContentFilter,
    onSearchClick: () -> Unit,
    onClearContentFilter: () -> Unit,
    grouping: TimelineGrouping = TimelineGrouping.Month,
    onShowGroupingSheet: () -> Unit = {},
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
                Icon(Icons.Default.Search, "Search",
                    tint = FgDim, modifier = Modifier.size(18.dp))
            }
        }

        // ── Grouping button ───────────────────────────────────────────────────
        item(key = "grouping_button") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(PillBg, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .clickable { onShowGroupingSheet() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.DateRange, stringResource(R.string.filter_timeline_grouping), tint = FgDim, modifier = Modifier.size(15.dp))
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
    // on narrow screens (S22-class 6.1" / smaller-screen Pixels). The text shrinks to
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
    grouping: TimelineGrouping = TimelineGrouping.Month,
    hiddenCloudLinkIds: Set<String> = emptySet(),
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
    val dateFormat = remember(grouping) {
        when (grouping) {
            TimelineGrouping.Day -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
            TimelineGrouping.Month -> SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            TimelineGrouping.Year -> SimpleDateFormat("yyyy", Locale.getDefault())
        }
    }
    val grouped = remember(items, grouping) {
        items.groupBy { item -> dateFormat.format(Date(item.captureTimeMs)) }.entries.toList()
    }

    // Discrete column counts the grid can land on. Phone screens look balanced at 3-5;
    // 2 is for "show me detail", 6 is for "fit a year on one screen".
    val columnSteps = listOf(2, 3, 4, 5, 6)
    var columnCount by rememberSaveable { mutableIntStateOf(3) }
    // Two-finger pinch detector that does NOT eat single-finger drags — the previous
    // Modifier.transformable consumed every gesture including vertical scrolls, so the
    // grid's own LazyVerticalGrid scroll competed with pinch and felt "twitchy". This
    // one only activates when the second finger goes down, so scrolling stays smooth
    // and pinch is isolated to a deliberate two-finger gesture.
    // Two-finger pinch detector that does NOT consume single-finger touches. Uses raw
    // pointerInput so the gesture stream is fully under our control: while only one
    // finger is down, events flow through to the LazyVerticalGrid for normal scrolling;
    // a second finger entering switches to pinch mode and consumes those frames. Once
    // both fingers leave we go back to scroll-passthrough on the next gesture.
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
            val startDist = kotlin.math.max(
                1f,
                kotlin.math.hypot(
                    (firstDown.position.x - second.position.x).toDouble(),
                    (firstDown.position.y - second.position.y).toDouble(),
                ).toFloat(),
            )
            var snappedThisGesture = false
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
                val ratio = curDist / startDist
                if (!snappedThisGesture) {
                    val idx = columnSteps.indexOf(columnCount).coerceAtLeast(0)
                    when {
                        ratio >= 1.30f && idx > 0 -> {
                            columnCount = columnSteps[idx - 1]; snappedThisGesture = true
                        }
                        ratio <= 1f / 1.30f && idx < columnSteps.lastIndex -> {
                            columnCount = columnSteps[idx + 1]; snappedThisGesture = true
                        }
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
            item(span = { GridItemSpan(columnCount) }) {
                MonthHeader(month = month, photoCount = monthPhotos, videoCount = monthVideos)
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
        TimelineScrubber(
            gridState = gridState,
            items = items,
            grouping = grouping,
            topPadding = topContentPadding + 8.dp,
            bottomPadding = 120.dp,
        )
    }
}

// ── "On this day" memories ───────────────────────────────────────────────────
//
// Groups items whose capture date falls on TODAY's month + day in a PREVIOUS year
// (current year excluded). Returns a list of (year, items) pairs sorted most-recent-first.
// Returning an empty list signals "no memories today" — caller hides the carousel entirely.
private fun computeOnThisDay(items: List<GalleryItem>): List<Pair<Int, List<GalleryItem>>> {
    if (items.isEmpty()) return emptyList()
    val today = Calendar.getInstance()
    val todayMonth = today.get(Calendar.MONTH)
    val todayDay = today.get(Calendar.DAY_OF_MONTH)
    val todayYear = today.get(Calendar.YEAR)
    val cal = Calendar.getInstance()
    val matches = items.filter { item ->
        cal.timeInMillis = item.captureTimeMs
        cal.get(Calendar.MONTH) == todayMonth &&
            cal.get(Calendar.DAY_OF_MONTH) == todayDay &&
            cal.get(Calendar.YEAR) != todayYear
    }
    if (matches.isEmpty()) return emptyList()
    return matches
        .groupBy { item ->
            cal.timeInMillis = item.captureTimeMs
            cal.get(Calendar.YEAR)
        }
        .entries
        .sortedByDescending { it.key }
        .map { it.key to it.value }
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
    Box(
        modifier = Modifier
            .size(90.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Bg2)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = imageModel,
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
private fun MonthHeader(month: String, photoCount: Int, videoCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = month,
            color = FgPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.44).sp,
            modifier = Modifier.weight(1f),
        )
        // Use the plurals-aware count strings so "1 photo" / "1 video" render correctly
        // instead of the previously-shown "1 photos" / "1 videos". Mixed case is a simple
        // localised join of the two plural results — most romance + germanic languages
        // accept comma+space, and the comma is invariant.
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
    // that the sync pass populated in metadata-only mode (v1.3 lazy decrypt).
    // Becoming visible enqueues an on-demand decrypt; leaving the viewport
    // cancels it. The DAO updates the row when the decrypt completes and the
    // Flow-based observation re-emits the new thumbnailUrl into [item], which
    // triggers recomposition and AsyncImage renders the freshly-cached file.
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
        when (item) {
            is GalleryItem.LocalOnly -> { /* no badge */ }
            is GalleryItem.Synced    -> SyncedCloudBadge()
            is GalleryItem.CloudOnly -> CloudBadge()
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
                        Icon(Icons.Default.Check, "Selected",
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
            contentDescription = "Only in Drive",
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
            contentDescription = "Backed up, also on device",
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
            contentDescription = "Hidden on this device",
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
            contentDescription = "Only on device",
            tint = FgMute,
            modifier = Modifier.size(11.dp),
        )
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(topPadding: Dp = 0.dp) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = topPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.gallery_empty_title),
                color = FgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.gallery_empty_subtitle),
                color = FgDim,
                fontSize = 14.sp,
            )
        }
    }
}

// ── Multi-select delete sheet ─────────────────────────────────────────────────

@Composable
private fun MultiDeleteSheet(
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

// ── Albums Filter Sheet ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsFilterSheet(
    currentFilter: AlbumsFilter,
    onApply: (AlbumsFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.albums_filter_title),
            color = FgPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )

        FilterSectionLabel(stringResource(R.string.albums_filter_type))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                AlbumsFilter.All to stringResource(R.string.gallery_filter_all),
                AlbumsFilter.Local to stringResource(R.string.albums_filter_local),
                AlbumsFilter.BackedUp to stringResource(R.string.albums_filter_backed_up),
            ).forEach { (status, label) ->
                FilterChip(
                    label = label,
                    selected = currentFilter == status,
                    onClick = { onApply(status); onDismiss() },
                )
            }
        }
    }
}

// ── Grouping picker sheet ─────────────────────────────────────────────────────

@Composable
private fun GroupingPickerSheet(
    current: TimelineGrouping,
    onSelect: (TimelineGrouping) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        TimelineGrouping.Day to stringResource(R.string.filter_group_day),
        TimelineGrouping.Month to stringResource(R.string.filter_group_month),
        TimelineGrouping.Year to stringResource(R.string.filter_group_year),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.filter_group_by),
            color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val colors = AppColors.current
        options.forEach { (grouping, label) ->
            val isSelected = current == grouping
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) colors.chipSelectedBg else colors.chipUnselectedBg,
                        RoundedCornerShape(12.dp),
                    )
                    .border(
                        0.5.dp,
                        if (isSelected) Accent.copy(alpha = 0.5f) else colors.cardBorder,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(grouping) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = if (isSelected) FgPrimary else FgDim,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ── Shared filter rail ────────────────────────────────────────────────────────

enum class SharedFilter { SharedWithMe, SharedByMe }

@Composable
private fun SharedFilterRail(
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
