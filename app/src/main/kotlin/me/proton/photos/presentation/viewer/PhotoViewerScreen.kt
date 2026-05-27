package me.proton.photos.presentation.viewer

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.VisibilityOff
import me.proton.photos.domain.entity.Album
import me.proton.photos.domain.entity.GalleryItem
import me.proton.photos.presentation.theme.Accent
import me.proton.photos.presentation.theme.Bg0
import me.proton.photos.presentation.theme.Bg2
import me.proton.photos.presentation.theme.CardBg
import me.proton.photos.presentation.theme.CardBorder
import me.proton.photos.presentation.theme.DeleteTint
import me.proton.photos.presentation.theme.ErrorColor
import me.proton.photos.presentation.theme.FgDim
import me.proton.photos.presentation.theme.FgMute
import me.proton.photos.presentation.theme.FgPrimary
import me.proton.photos.presentation.theme.Line2
import me.proton.photos.presentation.theme.PanelChip
import me.proton.photos.presentation.theme.PillBg
import me.proton.photos.presentation.theme.PillBorder
import me.proton.photos.util.MetadataStripConfig
import me.proton.photos.util.PhotoMetadata
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val bubbleShape = CircleShape
private val infoPillShape = RoundedCornerShape(999.dp)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    items: List<GalleryItem>,
    initialIndex: Int,
    onBack: () -> Unit,
    showSaveToDevice: Boolean = true,
    sourceAlbumLinkId: String? = null,
    onEditItem: (GalleryItem) -> Unit = {},
    viewModel: PhotoViewerViewModel = hiltViewModel(),
) {
    if (items.isEmpty()) { onBack(); return }

    val clampedInitial = initialIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = clampedInitial) { items.size }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val metadata by viewModel.metadata.collectAsStateWithLifecycle()
    val isStrippingMetadata by viewModel.isStrippingMetadata.collectAsStateWithLifecycle()
    val isHidden by viewModel.isHidden.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val isAddingToAlbum by viewModel.isAddingToAlbum.collectAsStateWithLifecycle()
    val isSavingToDevice by viewModel.isSavingToDevice.collectAsStateWithLifecycle()

    // Check hide + favorite status when settled page changes
    LaunchedEffect(pagerState.settledPage) {
        val item = items.getOrNull(pagerState.settledPage)
        val localUri = when (item) {
            is GalleryItem.LocalOnly -> item.local.uri
            is GalleryItem.Synced -> item.local.uri
            else -> null
        }
        if (localUri != null) viewModel.checkIfHidden(localUri)
        if (item != null) viewModel.checkIfFavorite(item)
    }

    // Load albums once for Add to Album feature
    LaunchedEffect(Unit) { viewModel.loadAlbums() }
    val scope = rememberCoroutineScope()

    // Surface previously-silent failures (add-to-album / save / load albums) as a Toast so
    // the user actually sees that something went wrong.
    val transientError by viewModel.transientError.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(transientError) {
        val msg = transientError ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        viewModel.clearTransientError()
    }
    // Success feedback for "Add to album" — previously silent. Mirrors the
    // AddToAlbumState.Done snackbar in GalleryScreen.
    LaunchedEffect(Unit) {
        viewModel.addToAlbumDone.collect { albumName ->
            android.widget.Toast.makeText(
                context,
                "Added to \"$albumName\"",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        when (val item = items.getOrNull(pagerState.settledPage)) {
            is GalleryItem.LocalOnly  -> viewModel.loadLocal(item.local.uri, item.local.mimeType)
            is GalleryItem.Synced     -> viewModel.loadLocal(item.local.uri, item.local.mimeType)
            is GalleryItem.CloudOnly  -> viewModel.loadCloud(item.cloud)
            null -> {}
        }
    }

    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(pagerState.settledPage) { scale = 1f; offset = Offset.Zero }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale  = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }

    // Per-page full-res image cache: keeps the last loaded image so non-settled pages
    // don't visually drop quality to thumbnail while the exit animation is still playing.
    val pageImageCache = remember { mutableMapOf<Int, Any>() }
    LaunchedEffect(state, pagerState.settledPage) {
        if (state is PhotoViewerViewModel.ViewerState.ShowImage) {
            pageImageCache[pagerState.settledPage] = (state as PhotoViewerViewModel.ViewerState.ShowImage).model
        }
    }

    // Video state — reset when page changes
    var videoStarted  by remember { mutableStateOf(false) }
    var currentPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isVideoPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.settledPage) {
        videoStarted  = false
        isVideoPlaying = false
        currentPlayer = null   // VideoPlayer's DisposableEffect releases it
    }
    // Poll the actual ExoPlayer isPlaying flag so the bottom bar reacts to pause/resume.
    LaunchedEffect(currentPlayer) {
        val p = currentPlayer
        if (p == null) { isVideoPlaying = false; return@LaunchedEffect }
        while (true) {
            isVideoPlaying = p.isPlaying
            delay(200)
        }
    }

    // Overlay visibility: tap image to toggle; resets to visible on page change
    var showOverlays by remember { mutableStateOf(true) }
    LaunchedEffect(pagerState.settledPage) { showOverlays = true }

    var showMetadata by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val renameState by viewModel.renameState.collectAsStateWithLifecycle()
    val metadataSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showDeleteSheet by remember { mutableStateOf(false) }
    var showAddToAlbumSheet by remember { mutableStateOf(false) }
    val addToAlbumSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val deleteState by viewModel.deleteState.collectAsStateWithLifecycle()

    // Android 11+ system trash dialog launcher
    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeletePermissionGranted()
        else viewModel.resetDeleteState()
    }

    // Handle delete state changes
    LaunchedEffect(deleteState) {
        when (val ds = deleteState) {
            is PhotoViewerViewModel.DeleteState.Done -> {
                viewModel.resetDeleteState()
                onBack()
            }
            is PhotoViewerViewModel.DeleteState.NeedsPermission -> {
                // Launch the Android system "Move to trash" dialog
                deletePermissionLauncher.launch(
                    IntentSenderRequest.Builder(ds.pendingIntent.intentSender).build()
                )
            }
            else -> {}
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Bg0),
        contentAlignment = Alignment.Center,
    ) {
        // ── Pager ──────────────────────────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = scale == 1f,
        ) { page ->
            val item      = items.getOrNull(page)
            val isSettled = page == pagerState.settledPage

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(scale, showMetadata) {
                        coroutineScope {
                            // Tap → toggle overlays
                            launch {
                                detectTapGestures(onTap = {
                                    showOverlays = !showOverlays
                                })
                            }
                            // Swipe up → open details (only when not zoomed)
                            launch {
                                if (scale <= 1f && !showMetadata) {
                                    detectVerticalDragGestures { _, dragAmount ->
                                        if (dragAmount < -40f) {
                                            showMetadata = true
                                            showOverlays = true
                                        }
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val thumbModel: Any? = when (item) {
                    is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
                    is GalleryItem.Synced    -> Uri.parse(item.local.uri)
                    is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl
                    null -> null
                }
                // Skip the AsyncImage poster for local videos once the user has tapped Play.
                // Reason: for video URIs Coil uses VideoFrameDecoder → MediaMetadataRetriever
                // which spins up a hardware decoder instance for poster extraction. That
                // contends with ExoPlayer's MediaCodec init at the exact moment we want a
                // clean first-frame paint, causing visible startup stutter on real devices.
                // While videoStarted is still false we keep the poster (user is staring at
                // a frozen still); once playback begins PlayerView's surface paints over it
                // anyway so the AsyncImage was just dead memory + decoder contention.
                val isVideoItemThumb = when (item) {
                    is GalleryItem.LocalOnly -> item.local.mimeType.startsWith("video/")
                    is GalleryItem.Synced    -> item.local.mimeType.startsWith("video/")
                    is GalleryItem.CloudOnly -> item.cloud.mimeType.startsWith("video/")
                    null -> false
                }
                val suppressThumbForVideo = isSettled && isVideoItemThumb && videoStarted
                if (thumbModel != null && !suppressThumbForVideo) {
                    AsyncImage(
                        model = thumbModel,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Non-settled pages: show cached full-res so they don't visually
                // downgrade to thumbnail during the exit swipe animation.
                if (!isSettled) {
                    val cached = pageImageCache[page]
                    if (cached != null) {
                        AsyncImage(
                            model = cached,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (isSettled) {
                    // Identity of the item currently bound to this page. Match against
                    // [state.itemKey] before rendering: when the user swipes A → B, there's
                    // a one-frame window where `settledPage = B` but `state` still references
                    // A's loaded image (loadCloud runs in a coroutine, recomposition happens
                    // first). Without this guard the new page flashes the previous photo.
                    val currentItemKey: String? = when (item) {
                        is GalleryItem.LocalOnly -> item.local.uri
                        is GalleryItem.Synced    -> item.local.uri
                        is GalleryItem.CloudOnly -> item.cloud.linkId
                        null -> null
                    }
                    val stateMatchesPage = state.itemKey == null || state.itemKey == currentItemKey
                    when (val s = state) {
                        is PhotoViewerViewModel.ViewerState.ShowImage ->
                            if (stateMatchesPage) {
                                AsyncImage(
                                    model = s.model,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .transformable(state = transformState, canPan = { scale > 1f })
                                        .graphicsLayer(
                                            scaleX = scale, scaleY = scale,
                                            translationX = offset.x, translationY = offset.y,
                                        ),
                                )
                            }
                        is PhotoViewerViewModel.ViewerState.ShowVideo -> {
                            if (stateMatchesPage && videoStarted) {
                                VideoPlayer(
                                    uri = s.uri,
                                    onPlayerReady = { currentPlayer = it },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            // No play overlay — play button is in the VideoControlPill below filmstrip
                        }
                        is PhotoViewerViewModel.ViewerState.Loading ->
                            if (thumbModel == null)
                                CircularProgressIndicator(color = FgDim, strokeWidth = 2.dp)
                        is PhotoViewerViewModel.ViewerState.Error ->
                            if (stateMatchesPage) Text(s.message ?: "Error loading photo", color = ErrorColor, fontSize = 14.sp)
                    }

                    // (Per user feedback the visible "Downloading X% / N MB" overlay was
                    // distracting during fast viewer swipes — the thumbnail is already on
                    // screen, the full-res just silently replaces it once downloadFullResPhoto
                    // completes. If we ever need to bring back a progress indicator, make it a
                    // tiny corner spinner instead of a centred backdrop.)
                }
            }
        }

        // ── Top bar (fades with overlays) ─────────────────────────────────────
        AnimatedVisibility(
            visible = showOverlays,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewerBubble(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                        tint = FgPrimary, modifier = Modifier.size(20.dp))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val isDeleting = deleteState is PhotoViewerViewModel.DeleteState.Working
                    val settledItem = items.getOrNull(pagerState.settledPage)
                    val isLocalItem = settledItem is GalleryItem.LocalOnly || settledItem is GalleryItem.Synced
                    val isCloudItem = settledItem is GalleryItem.Synced || settledItem is GalleryItem.CloudOnly

                    // Favorite button
                    if (settledItem != null) {
                        ViewerBubble(onClick = { viewModel.toggleFavorite(settledItem) }) {
                            Icon(
                                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                if (isFavorite) "Remove from favorites" else "Add to favorites",
                                tint = if (isFavorite) Color(0xFFFF3B30) else FgDim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    // Add to album — available for any item with at least a local OR cloud
                    // representation. Cloud / Synced → cloud album add (addPhotosToAlbum API).
                    // LocalOnly / Synced → local virtual-album membership (DataStore write,
                    // no file move so DATE_TAKEN survives Android Q+ MediaProvider restrictions).
                    if (settledItem != null) {
                        ViewerBubble(onClick = { showAddToAlbumSheet = true }) {
                            if (isAddingToAlbum) {
                                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Default.LibraryAdd, "Add to album",
                                    tint = FgDim, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Download to device — for cloud-only items NOT opened from an album
                    // (album-level download is handled by the "Download all" button in the album header)
                    if (showSaveToDevice && settledItem is GalleryItem.CloudOnly) {
                        ViewerBubble(onClick = {
                            if (!isSavingToDevice) viewModel.downloadToDevice(settledItem)
                        }) {
                            if (isSavingToDevice) {
                                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Default.FileDownload, "Save to device",
                                    tint = FgDim, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Edit button — for images only (videos need a different editor path).
                    val isImage = when (settledItem) {
                        is GalleryItem.LocalOnly -> settledItem.local.mimeType.startsWith("image/")
                        is GalleryItem.Synced    -> settledItem.local.mimeType.startsWith("image/")
                        is GalleryItem.CloudOnly -> settledItem.cloud.mimeType.startsWith("image/")
                        null -> false
                    }
                    if (settledItem != null && isImage) {
                        ViewerBubble(onClick = { onEditItem(settledItem) }) {
                            Icon(Icons.Default.Edit, "Edit",
                                tint = FgPrimary, modifier = Modifier.size(18.dp))
                        }
                    }

                    // Hide / unhide button — only for local items (LocalOnly/Synced both
                    // guarantee non-null). The label flips based on [isHidden]; previously the
                    // onClick always called hideItem, which on an already-hidden item ran the
                    // delete flow on a file:// URI to app-private storage and crashed
                    // `MediaStore.createTrashRequest` (it only accepts content:// MediaStore URIs).
                    if (isLocalItem) {
                        ViewerBubble(onClick = {
                            val item = settledItem ?: return@ViewerBubble
                            if (isHidden) {
                                // Restore the private file back to MediaStore + drop it from the
                                // hidden set. After this completes the user is taken back to the
                                // hidden album list (the cached items in the viewer are stale).
                                val uri = when (item) {
                                    is GalleryItem.LocalOnly -> item.local.uri
                                    is GalleryItem.Synced    -> item.local.uri
                                    is GalleryItem.CloudOnly -> null
                                }
                                val name = when (item) {
                                    is GalleryItem.LocalOnly -> item.local.displayName
                                    is GalleryItem.Synced    -> item.local.displayName
                                    is GalleryItem.CloudOnly -> null
                                }
                                if (uri != null) {
                                    viewModel.unhideHiddenItem(uri, name)
                                    onBack()
                                }
                            } else {
                                // Real cross-app hide: copy to app-private storage + delete the
                                // original MediaStore entry via DeletePhotoUseCase.
                                viewModel.hideItem(item)
                            }
                        }) {
                            Icon(
                                Icons.Default.VisibilityOff, if (isHidden) "Unhide" else "Hide",
                                tint = if (isHidden) Accent else FgDim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    ViewerBubble(onClick = { if (!isDeleting) showDeleteSheet = true }) {
                        if (isDeleting) {
                            CircularProgressIndicator(color = ErrorColor, strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Default.DeleteOutline, "Delete",
                                tint = ErrorColor, modifier = Modifier.size(20.dp))
                        }
                    }
                    ViewerBubble(onClick = { showMetadata = true }) {
                        Icon(Icons.Default.MoreVert, "Details",
                            tint = FgPrimary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // ── Bottom section — hidden when overlays are off ────────────────────
        AnimatedVisibility(
            visible = showOverlays,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Video control pill — above filmstrip, visible as soon as item is a video
            val settledItem = items.getOrNull(pagerState.settledPage)
            val isVideoItem = when (settledItem) {
                is GalleryItem.LocalOnly -> settledItem.local.mimeType.startsWith("video/")
                is GalleryItem.Synced    -> settledItem.local.mimeType.startsWith("video/")
                is GalleryItem.CloudOnly -> settledItem.cloud.mimeType.startsWith("video/")
                null -> false
            }
            if (isVideoItem) {
                VideoControlPill(
                    player       = currentPlayer,
                    videoStarted = videoStarted,
                    onPlay       = { videoStarted = true },
                )
            }

            // Filmstrip — always, unchanged
            Filmstrip(
                items = items,
                currentPage = pagerState.currentPage,
                onThumbnailClick = { idx ->
                    scope.launch { pagerState.animateScrollToPage(idx) }
                },
            )

            // Info pill — always, unchanged
            val currentItem = items.getOrNull(pagerState.currentPage)
            Row(
                modifier = Modifier
                    .background(PillBg, infoPillShape)
                    .border(0.5.dp, PillBorder, infoPillShape)
                    .clickable { showMetadata = true }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Cloud/device badge — sits to the LEFT of the position counter, with a thin
                // separator. Mirrors the gallery cell badges so the user can tell at a glance
                // whether the currently viewed photo is in cloud + on device (green cloud),
                // cloud-only (white cloud), or device-only (no badge — no point showing
                // anything since the user is obviously looking at it).
                when (currentItem) {
                    is GalleryItem.Synced -> {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = "Backed up, also on device",
                            tint = Color(0xFF30D158),
                            modifier = Modifier.size(13.dp),
                        )
                        Text("·", color = FgMute, fontSize = 13.sp)
                    }
                    is GalleryItem.CloudOnly -> {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = "Only in Drive",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp),
                        )
                        Text("·", color = FgMute, fontSize = 13.sp)
                    }
                    else -> { /* LocalOnly — no badge */ }
                }
                Text(
                    "${pagerState.currentPage + 1} / ${items.size}",
                    color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
                if (currentItem != null) {
                    Text("·", color = FgMute, fontSize = 13.sp)
                    Text(formatItemDate(currentItem), color = FgDim, fontSize = 13.sp)
                }
                Text("›", color = FgMute, fontSize = 15.sp)
            }
        }
        } // AnimatedVisibility
    }

    // ── Metadata sheet ─────────────────────────────────────────────────────────
    if (showMetadata) {
        val item = items.getOrNull(pagerState.settledPage)
        ModalBottomSheet(
            onDismissRequest = { showMetadata = false },
            sheetState = metadataSheetState,
            containerColor = Bg2,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            PhotoMetadataSheet(
                item = item,
                exif = metadata,
                isStripping = isStrippingMetadata,
                onStripFields = { config ->
                    val uri = when (item) {
                        is GalleryItem.LocalOnly -> item.local.uri
                        is GalleryItem.Synced -> item.local.uri
                        else -> null
                    }
                    if (uri != null) viewModel.stripMetadataFromLocal(uri, config)
                },
                onRenameClick = {
                    showMetadata = false
                    showRenameDialog = true
                },
            )
        }
    }

    // ── Rename dialog ─────────────────────────────────────────────────────────
    if (showRenameDialog) {
        val item = items.getOrNull(pagerState.settledPage)
        if (item != null) {
            val currentName = when (item) {
                is GalleryItem.LocalOnly -> item.local.displayName
                is GalleryItem.Synced    -> item.local.displayName
                is GalleryItem.CloudOnly -> item.cloud.displayName
            }
            RenameDialog(
                currentName = currentName,
                isCloud = item is GalleryItem.CloudOnly,
                isWorking = renameState is PhotoViewerViewModel.RenameState.Working,
                errorMessage = (renameState as? PhotoViewerViewModel.RenameState.Failed)?.message,
                onDismiss = {
                    showRenameDialog = false
                    viewModel.resetRenameState()
                },
                onConfirm = { newName, replaceOriginal ->
                    viewModel.renameItem(item, newName, replaceOriginal, sourceAlbumLinkId)
                },
            )
        }
    }

    // Close the dialog and reset state when the rename finishes.
    LaunchedEffect(renameState) {
        if (renameState is PhotoViewerViewModel.RenameState.Done) {
            showRenameDialog = false
            viewModel.resetRenameState()
        }
    }

    // ── Add to Album sheet ────────────────────────────────────────────────────────
    if (showAddToAlbumSheet) {
        val settledItem = items.getOrNull(pagerState.settledPage)
        val localAlbumNames by viewModel.localAlbumNames.collectAsStateWithLifecycle()
        val currentPhotoAlbumIds by viewModel.currentPhotoAlbumIds.collectAsStateWithLifecycle()
        val hasLocal = settledItem is GalleryItem.LocalOnly || settledItem is GalleryItem.Synced
        val hasCloud = settledItem is GalleryItem.Synced || settledItem is GalleryItem.CloudOnly
        // Refresh membership for the current photo every time the sheet opens — fast on cache
        // hit (5-min TTL in AlbumService) and self-heals if the user removed the photo from an
        // album on Drive web between sheet opens.
        LaunchedEffect(showAddToAlbumSheet, settledItem) {
            if (settledItem != null && hasCloud) viewModel.loadCurrentPhotoAlbumIds(settledItem)
        }
        AddToAlbumSheet(
            sheetState = addToAlbumSheetState,
            cloudAlbums = if (hasCloud) albums else emptyList(),
            localAlbumNames = if (hasLocal) localAlbumNames else emptyList(),
            currentPhotoAlbumIds = currentPhotoAlbumIds,
            onDismiss = { showAddToAlbumSheet = false },
            onCloudAlbumPicked = { albumLinkId ->
                if (settledItem != null) {
                    // Tap-to-remove when the photo is already in this album, otherwise add.
                    if (albumLinkId in currentPhotoAlbumIds) {
                        viewModel.removeFromAlbum(albumLinkId, settledItem)
                    } else {
                        viewModel.addToAlbum(albumLinkId, settledItem)
                    }
                }
                showAddToAlbumSheet = false
            },
            onLocalAlbumPicked = { albumName ->
                if (settledItem != null) viewModel.addToLocalAlbum(albumName, settledItem)
                showAddToAlbumSheet = false
            },
        )
    }

    // ── Delete confirmation sheet ───────────────────────────────────────────────
    if (showDeleteSheet) {
        val item = items.getOrNull(pagerState.settledPage)
        if (item != null) {
            val deleteSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showDeleteSheet = false },
                sheetState = deleteSheetState,
                containerColor = Bg2,
                scrimColor = Color.Black.copy(alpha = 0.5f),
            ) {
                DeleteConfirmSheet(
                    item    = item,
                    onDismiss = { showDeleteSheet = false },
                    onDelete  = { freeUpSpace, deleteFromCloud ->
                        showDeleteSheet = false
                        viewModel.deleteItem(item, freeUpSpace, deleteFromCloud)
                    },
                )
            }
        }
    }
}

// ── Filmstrip ──────────────────────────────────────────────────────────────────

@Composable
private fun Filmstrip(
    items: List<GalleryItem>,
    currentPage: Int,
    onThumbnailClick: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val density   = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val viewportPx = constraints.maxWidth
        val itemPx     = with(density) { 54.dp.roundToPx() }

        LaunchedEffect(currentPage) {
            listState.animateScrollToItem(
                index        = currentPage,
                scrollOffset = -(viewportPx / 2 - itemPx / 2),
            )
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            userScrollEnabled = false,
        ) {
            itemsIndexed(items) { index, item ->
                val isCurrent = index == currentPage
                val thumbModel: Any? = when (item) {
                    is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
                    is GalleryItem.Synced    -> Uri.parse(item.local.uri)
                    is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl
                }
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Bg2)
                        .then(
                            if (isCurrent)
                                Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                            else
                                Modifier.alpha(0.45f)
                        )
                        .clickable { onThumbnailClick(index) },
                ) {
                    if (thumbModel != null) {
                        AsyncImage(
                            model = thumbModel,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

// ── Bubble button ──────────────────────────────────────────────────────────────

@Composable
private fun ViewerBubble(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(PillBg, bubbleShape)
            .border(0.5.dp, PillBorder, bubbleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ── Metadata sheet ─────────────────────────────────────────────────────────────

@Composable
private fun PhotoMetadataSheet(
    item: GalleryItem?,
    exif: PhotoMetadata?,
    isStripping: Boolean,
    onStripFields: (MetadataStripConfig) -> Unit,
    onRenameClick: () -> Unit = {},
) {
    if (item == null) return
    val isLocal = item is GalleryItem.LocalOnly || item is GalleryItem.Synced
    var showStripConfirm by remember { mutableStateOf(false) }
    var pendingStripConfig by remember { mutableStateOf<MetadataStripConfig?>(null) }

    androidx.compose.foundation.rememberScrollState().let { scrollState ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Details",
                color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )

            // ── File info ──────────────────────────────────────────────────
            MetadataSection("File Info") {
                when (item) {
                    is GalleryItem.LocalOnly -> {
                        MetaRow("File", item.local.displayName, onEdit = onRenameClick)
                        MetaRow("Date", formatMs(item.local.dateTaken))
                        MetaRow("Size", formatBytes(item.local.sizeBytes))
                        MetaRow("Type", item.local.mimeType)
                        item.local.bucketName?.let { MetaRow("Album", it) }
                        MetaRow("Source", "On device only")
                    }
                    is GalleryItem.Synced -> {
                        MetaRow("File", item.local.displayName, onEdit = onRenameClick)
                        MetaRow("Date", formatMs(item.local.dateTaken))
                        MetaRow("Size", formatBytes(item.local.sizeBytes))
                        MetaRow("Type", item.local.mimeType)
                        item.local.bucketName?.let { MetaRow("Album", it) }
                        MetaRow("Source", "Backed up to Proton Drive")
                    }
                    is GalleryItem.CloudOnly -> {
                        MetaRow("File", item.cloud.displayName, onEdit = onRenameClick)
                        MetaRow("Date", formatMs(item.cloud.captureTime * 1000L))
                        MetaRow("Size", formatBytes(item.cloud.sizeBytes))
                        MetaRow("Type", item.cloud.mimeType)
                        MetaRow("Source", "Proton Drive")
                    }
                }
            }

            // ── EXIF — Camera ──────────────────────────────────────────────
            if (exif != null && (exif.make != null || exif.model != null || exif.focalLength != null)) {
                MetadataSection(
                    label = "Camera",
                    actionLabel = if (isLocal) "Strip" else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        pendingStripConfig = MetadataStripConfig(stripCameraInfo = true)
                        showStripConfirm = true
                    },
                ) {
                    exif.make?.let { MetaRow("Make", it) }
                    exif.model?.let { MetaRow("Model", it) }
                    exif.lensModel?.let { MetaRow("Lens", it) }
                    exif.focalLength?.let { MetaRow("Focal length", "${it}mm") }
                    exif.aperture?.let { MetaRow("Aperture", "f/$it") }
                    exif.exposureTime?.let { MetaRow("Exposure", it) }
                    exif.isoSpeed?.let { MetaRow("ISO", it) }
                    exif.flash?.let { MetaRow("Flash", if (it and 0x01 != 0) "Fired" else "No flash") }
                    exif.whiteBalance?.let { MetaRow("White balance", if (it == 0) "Auto" else "Manual") }
                }
            }

            // ── EXIF — Location ────────────────────────────────────────────
            if (exif != null && (exif.gpsLatitude != null || exif.gpsLongitude != null)) {
                MetadataSection(
                    label = "Location",
                    actionLabel = if (isLocal) "Strip" else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        pendingStripConfig = MetadataStripConfig(stripGps = true)
                        showStripConfirm = true
                    },
                ) {
                    exif.gpsLatitude?.let {
                        MetaRow("Latitude", "%.6f°".format(it))
                    }
                    exif.gpsLongitude?.let {
                        MetaRow("Longitude", "%.6f°".format(it))
                    }
                    exif.gpsAltitude?.let {
                        MetaRow("Altitude", "%.1f m".format(it))
                    }
                }
            }

            // ── EXIF — Date & Time ─────────────────────────────────────────
            if (exif != null && (exif.dateTime != null || exif.dateTimeOriginal != null)) {
                MetadataSection(
                    label = "Date & Time",
                    actionLabel = if (isLocal) "Strip" else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        pendingStripConfig = MetadataStripConfig(stripTimestamp = true)
                        showStripConfirm = true
                    },
                ) {
                    exif.dateTimeOriginal?.let { MetaRow("Taken", it) }
                    exif.dateTime?.let { MetaRow("Modified", it) }
                }
            }

            // ── EXIF — Software ────────────────────────────────────────────
            if (exif != null && (exif.software != null || exif.artist != null || exif.copyright != null)) {
                MetadataSection(
                    label = "Software & Author",
                    actionLabel = if (isLocal) "Strip" else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        pendingStripConfig = MetadataStripConfig(stripSoftwareInfo = true)
                        showStripConfirm = true
                    },
                ) {
                    exif.software?.let { MetaRow("Software", it) }
                    exif.artist?.let { MetaRow("Artist", it) }
                    exif.copyright?.let { MetaRow("Copyright", it) }
                }
            }

            // ── Image dimensions ───────────────────────────────────────────
            if (exif != null && exif.width != null && exif.height != null) {
                MetadataSection("Image") {
                    MetaRow("Dimensions", "${exif.width} × ${exif.height}")
                    exif.orientation?.let {
                        val orientLabel = when (it) {
                            1 -> "Normal"
                            3 -> "Rotated 180°"
                            6 -> "Rotated 90° CW"
                            8 -> "Rotated 90° CCW"
                            else -> "$it"
                        }
                        MetaRow("Orientation", orientLabel)
                    }
                }
            }

            // ── Strip all — quick action for local items ───────────────────
            if (isLocal && exif != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DeleteTint, RoundedCornerShape(12.dp))
                        .border(0.5.dp, ErrorColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .clickable(enabled = !isStripping) {
                            pendingStripConfig = MetadataStripConfig(
                                stripGps = true,
                                stripCameraInfo = true,
                                stripTimestamp = false,
                                stripSoftwareInfo = true,
                            )
                            showStripConfirm = true
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Strip all private metadata",
                            color = ErrorColor, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Removes GPS, camera info and software fields from this file",
                            color = FgMute, fontSize = 11.5.sp,
                        )
                    }
                    if (isStripping) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ErrorColor,
                        )
                    }
                }
            }
        }
    }

    if (showStripConfirm && pendingStripConfig != null) {
        val config = pendingStripConfig!!
        val what = buildList {
            if (config.stripGps) add("GPS location")
            if (config.stripCameraInfo) add("camera info")
            if (config.stripTimestamp) add("timestamps")
            if (config.stripSoftwareInfo) add("software info")
        }.joinToString(", ")
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showStripConfirm = false },
            containerColor = Bg2,
            titleContentColor = FgPrimary,
            textContentColor = FgDim,
            title = { Text("Strip metadata?") },
            text = { Text("This will permanently remove $what from the file on your device. This cannot be undone.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showStripConfirm = false
                    onStripFields(config)
                    pendingStripConfig = null
                }) {
                    Text("Strip", color = ErrorColor, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showStripConfirm = false }) {
                    Text("Cancel", color = FgDim)
                }
            },
        )
    }
}

@Composable
private fun MetadataSection(
    label: String,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label.uppercase(),
                color = FgMute, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            if (actionLabel != null) {
                Text(
                    actionLabel,
                    color = if (actionEnabled) ErrorColor else FgMute,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(enabled = actionEnabled, onClick = onAction),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBg, RoundedCornerShape(12.dp))
                .border(0.5.dp, Line2, RoundedCornerShape(12.dp)),
        ) {
            Column { content() }
        }
    }
}

// ── Rename dialog ──────────────────────────────────────────────────────────────

@Composable
private fun RenameDialog(
    currentName: String,
    isCloud: Boolean,
    isWorking: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (newName: String, replaceOriginal: Boolean) -> Unit,
) {
    var text by remember(currentName) {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = currentName,
                selection = androidx.compose.ui.text.TextRange(0, splitName(currentName).first.length),
            ),
        )
    }
    val trimmed = text.text.trim()
    val unchanged = trimmed == currentName
    val canSubmit = !isWorking && trimmed.isNotEmpty() && !unchanged

    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!isWorking) onDismiss() }) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Bg2)
                .padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Rename file", color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (isCloud) "Choose how to apply the new name to your cloud photo."
                else "Choose how to apply the new name on this device.",
                color = FgMute, fontSize = 13.sp,
            )

            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                enabled = !isWorking,
                label = { Text("File name") },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = FgPrimary,
                    unfocusedTextColor = FgPrimary,
                    focusedLabelColor = Accent,
                    unfocusedLabelColor = FgMute,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = FgMute.copy(alpha = 0.4f),
                    cursorColor = Accent,
                ),
            )

            if (errorMessage != null) {
                Text(errorMessage, color = ErrorColor, fontSize = 12.sp)
            }

            // Two primary actions stacked, then a Cancel.
            RenameOptionButton(
                title = if (isCloud) "Rename in cloud" else "Rename original",
                subtitle = if (isCloud)
                    "Uploads under the new name, then moves the original to Recently Deleted."
                else
                    "Updates the file in place on your device.",
                accent = true,
                enabled = canSubmit,
                onClick = { onConfirm(trimmed, /* replaceOriginal = */ true) },
            )
            RenameOptionButton(
                title = "Save as copy",
                subtitle = if (isCloud)
                    "Uploads as a new photo. The original stays in Drive."
                else
                    "Creates a new file in Pictures/Proton Photos.",
                accent = false,
                enabled = canSubmit,
                onClick = { onConfirm(trimmed, /* replaceOriginal = */ false) },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !isWorking, onClick = onDismiss),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (isWorking) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Text("Cancel", color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun RenameOptionButton(
    title: String,
    subtitle: String,
    accent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (accent) Accent.copy(alpha = if (enabled) 1f else 0.4f)
             else PanelChip.let { if (enabled) it else it.copy(alpha = 0.6f) }
    val fg = if (accent) Color.White else FgPrimary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

/** Returns (basename, ".ext") — used to pre-select the basename in the rename field. */
private fun splitName(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) to name.substring(dot)
           else name to ""
}

@Composable
private fun MetaRow(label: String, value: String, onEdit: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onEdit != null) it.clickable(onClick = onEdit) else it }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = FgMute, fontSize = 13.sp, modifier = Modifier.weight(0.4f))
        Row(
            modifier = Modifier.weight(0.6f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onEdit != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = Accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ── Delete confirmation sheet ──────────────────────────────────────────────────

@Composable
private fun DeleteConfirmSheet(
    item: GalleryItem,
    onDismiss: () -> Unit,
    onDelete: (freeUpSpace: Boolean, deleteFromCloud: Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when (item) {
                is GalleryItem.LocalOnly  -> "Delete photo?"
                is GalleryItem.Synced     -> "Remove photo?"
                is GalleryItem.CloudOnly  -> "Delete from Proton Drive?"
            },
            color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        )

        when (item) {
            is GalleryItem.LocalOnly -> {
                Text(
                    "This will move the photo to your device's Recently Deleted folder.",
                    color = FgDim, fontSize = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                DeleteButton("Move to trash") { onDelete(true, false) }
            }

            is GalleryItem.Synced -> {
                Text(
                    "This photo is backed up to Proton Drive. Choose what to do:",
                    color = FgDim, fontSize = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                // Option A – keep backup, remove local copy
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            CardBg,
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        )
                        .border(
                            0.5.dp,
                            CardBorder,
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        )
                        .clickable { onDelete(true, false) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text("Remove from device", color = FgPrimary, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                    Text("Keep the backup in Proton Drive", color = FgMute, fontSize = 12.sp)
                }
                // Option B – keep local copy, remove from cloud
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            CardBg,
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        )
                        .border(
                            0.5.dp,
                            CardBorder,
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        )
                        .clickable { onDelete(false, true) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text("Remove from cloud", color = FgPrimary, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                    Text("Keep the photo on this device, move the backup to Drive trash",
                        color = FgMute, fontSize = 12.sp)
                }
                // Option C – delete everywhere
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            DeleteTint,
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        )
                        .border(
                            0.5.dp,
                            ErrorColor.copy(alpha = 0.3f),
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        )
                        .clickable { onDelete(true, true) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text("Delete everywhere", color = ErrorColor, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                    Text("Moves to device trash AND Proton Drive trash",
                        color = FgMute, fontSize = 12.sp)
                }
            }

            is GalleryItem.CloudOnly -> {
                Text(
                    "This will move the photo to Proton Drive's trash. You can recover it from Recently Deleted.",
                    color = FgDim, fontSize = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                DeleteButton("Move to Drive trash") { onDelete(false, true) }
            }
        }

        // Cancel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    CardBg,
                    androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                )
                .border(
                    0.5.dp,
                    CardBorder,
                    androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onDismiss)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Cancel", color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DeleteButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                DeleteTint,
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .border(
                0.5.dp,
                ErrorColor.copy(alpha = 0.3f),
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ErrorColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Video player ───────────────────────────────────────────────────────────────

/**
 * Plays a video file or content URI using ExoPlayer (Media3).
 * Released automatically when the composable leaves the composition.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPlayer(
    uri: Uri,
    onPlayerReady: (ExoPlayer) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true   // auto-play because user already tapped the play button
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
        }.also { onPlayerReady(it) }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false   // custom controls in VideoControlBar
            }
        },
        update = { view -> view.player = exoPlayer },
        modifier = modifier,
    )
}

// ── Video control pill — everything inline in one pill ────────────────────────

@Composable
private fun VideoControlPill(
    player: ExoPlayer?,
    videoStarted: Boolean,
    onPlay: () -> Unit,
) {
    var isPlaying  by remember { mutableStateOf(false) }
    var isMuted    by remember { mutableStateOf(false) }
    var currentMs  by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isSeeking  by remember { mutableStateOf(false) }
    var seekRatio  by remember { mutableStateOf(0f) }
    var trackWidthPx by remember { mutableStateOf(1f) }

    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            isPlaying  = player.isPlaying
            durationMs = player.duration.coerceAtLeast(0)
            if (!isSeeking) currentMs = player.currentPosition
            delay(200)
        }
    }

    val progress = when {
        isSeeking -> seekRatio
        durationMs > 0 -> (currentMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }

    // Single pill: [▶/⏸] [seek bar ~90dp] [time] [🔊]
    Row(
        modifier = Modifier
            .background(PillBg, infoPillShape)
            .border(0.5.dp, PillBorder, infoPillShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Play / Pause
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).clickable {
                when {
                    !videoStarted -> onPlay()
                    isPlaying     -> player?.pause()
                    else          -> player?.play()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (videoStarted && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                null, tint = Color.White, modifier = Modifier.size(20.dp),
            )
        }

        // Compact seek bar inside pill
        Canvas(
            modifier = Modifier
                .width(90.dp)
                .height(20.dp)
                .onGloballyPositioned { trackWidthPx = it.size.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(player) {
                    if (player == null) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            isSeeking = true
                            seekRatio = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            seekRatio = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            player.seekTo((seekRatio * durationMs).toLong())
                            isSeeking = false
                        },
                        onDragCancel = { isSeeking = false },
                    )
                }
                .pointerInput(player) {
                    if (player == null) return@pointerInput
                    detectTapGestures { offset ->
                        val ratio = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                        player.seekTo((ratio * durationMs).toLong())
                    }
                },
        ) {
            val cy = size.height / 2f
            drawLine(Color.White.copy(alpha = 0.25f),
                Offset(0f, cy), Offset(size.width, cy),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            val px = size.width * progress
            if (px > 0f) {
                drawLine(Color.White, Offset(0f, cy), Offset(px, cy),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(px, cy))
        }

        // Time
        Text(
            if (durationMs > 0) "${formatVideoTime(currentMs)} / ${formatVideoTime(durationMs)}"
            else "0:00",
            color = FgPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )

        // Mute — always visible
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).clickable {
                isMuted = !isMuted
                player?.volume = if (isMuted) 0f else 1f
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                null, tint = Color.White.copy(alpha = if (videoStarted) 1f else 0.4f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun formatVideoTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun formatItemDate(item: GalleryItem): String = formatMs(item.captureTimeMs)

private fun formatMs(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000     -> "%.0f KB".format(bytes / 1_000.0)
    else               -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToAlbumSheet(
    sheetState: androidx.compose.material3.SheetState,
    cloudAlbums: List<Album>,
    localAlbumNames: List<String>,
    currentPhotoAlbumIds: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onCloudAlbumPicked: (String) -> Unit,
    onLocalAlbumPicked: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Bg2,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                "Add to Album",
                color = FgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
            if (cloudAlbums.isEmpty() && localAlbumNames.isEmpty()) {
                Text(
                    "No albums yet. Create one from the Albums tab.",
                    color = FgMute,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }

            // Cloud albums (Drive) — shown when the item has a cloud counterpart.
            if (cloudAlbums.isNotEmpty()) {
                Text(
                    "DRIVE ALBUMS",
                    color = FgMute,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                cloudAlbums.forEach { album ->
                    val isMember = album.linkId in currentPhotoAlbumIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCloudAlbumPicked(album.linkId) }
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
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Bg0),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Bg0, RoundedCornerShape(8.dp)),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(album.name, color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (isMember) "Tap to remove from album"
                                else "${album.photoCount} photos",
                                color = if (isMember) Accent else FgMute,
                                fontSize = 12.sp,
                            )
                        }
                        // Member indicator: filled accent-coloured check tile on the trailing
                        // edge of the row. Doubles as the "tap removes" affordance because the
                        // whole row's onClick handles both add + remove based on this state.
                        if (isMember) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(Accent, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Photo is in this album",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Line2, thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 20.dp))
                }
            }

            // Local albums — shown when the item has a local counterpart.
            // Picks here write a virtual-membership entry; the file does NOT move on disk
            // (preserves DATE_TAKEN, which Android Q+ MediaProvider would otherwise nuke on
            // a RELATIVE_PATH update against a file our app doesn't own).
            if (localAlbumNames.isNotEmpty()) {
                Text(
                    "ON THIS DEVICE",
                    color = FgMute,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                localAlbumNames.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLocalAlbumPicked(name) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Bg0, RoundedCornerShape(8.dp)),
                        )
                        Text(name, color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    HorizontalDivider(color = Line2, thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}
