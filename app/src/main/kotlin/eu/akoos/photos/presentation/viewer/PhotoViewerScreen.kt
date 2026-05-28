package eu.akoos.photos.presentation.viewer

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
import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.blur
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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
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
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.CardBg
import eu.akoos.photos.presentation.theme.CardBorder
import eu.akoos.photos.presentation.theme.DeleteTint
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PanelChip
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.PhotoMetadata
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
    /** Timestamp from the editor pop-back signal — keyed into the page-load effect so the
     *  viewer drops its bitmap cache + re-asks the VM for fresh bytes after a save. */
    editedAt: Long = 0L,
    /** Cloud linkIds whose local-side photo lives in the Hidden vault on this device.
     *  When a viewer item's linkId is in this set we paint a blur + "Hidden" label over
     *  the full-res surface — same UX as the gallery cell, just zoomed up. Empty set =
     *  no item gets the treatment. */
    hiddenCloudLinkIds: Set<String> = emptySet(),
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
    // Live cloud→device twin map. Lets the bottom badge flip from "cloud only" to
    // "synced" the moment downloadToDevice persists a SyncState, instead of waiting
    // for the user to leave + re-enter the viewer (which is what the static `items`
    // snapshot would otherwise require).
    val localUriByLinkId by viewModel.localUriByLinkId.collectAsStateWithLifecycle()

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

    LaunchedEffect(pagerState.settledPage, editedAt) {
        // editedAt bumps when the editor pops back after a save — re-running this effect
        // re-asks the VM for fresh bytes (Coil's memory cache for the URI was nuked in
        // PhotoEditorViewModel.invalidateImageCache, so this reload reads from disk).
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
    // When the editor pops back after a save, drop everything we cached for the touched
    // page — otherwise the bitmap held in this map is the stale one rendered by Coil
    // BEFORE the cache wipe and the user sees the pre-edit version until they page away.
    LaunchedEffect(editedAt) {
        if (editedAt != 0L) {
            pageImageCache.remove(pagerState.settledPage)
        }
    }
    LaunchedEffect(state, pagerState.settledPage) {
        if (state is PhotoViewerViewModel.ViewerState.ShowImage) {
            pageImageCache[pagerState.settledPage] = (state as PhotoViewerViewModel.ViewerState.ShowImage).model
        }
    }

    // Video state — reset when page changes
    var videoStarted  by remember { mutableStateOf(false) }
    var currentPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isVideoPlaying by remember { mutableStateOf(false) }
    // Latches true the first time ExoPlayer reports it's actually playing on this page,
    // so the loading badge keeps showing through download + prepare + first-paint and
    // then disappears for good (ordinary pause/resume after that doesn't bring it back).
    var videoEverPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.settledPage) {
        // Reset playback flags but DO NOT null currentPlayer here. The composable for the
        // new page builds a fresh ExoPlayer in its own remember(uri) block and the previous
        // page's player is released by its DisposableEffect onDispose — nulling here added
        // a third source-of-truth race where the new page would re-bind to `null` after the
        // remember had already set it to the new player, leaving the control pill with no
        // handle and the surface stuck on the previous frame.
        videoStarted  = false
        isVideoPlaying = false
        videoEverPlayed = false
    }
    LaunchedEffect(isVideoPlaying) {
        if (isVideoPlaying) videoEverPlayed = true
    }
    // Auto-start playback once the full-res video URI arrives — matches native gallery
    // behavior where a tapped video begins playing immediately instead of asking for a
    // second tap on a play overlay. Without this, tapping the play pill leaves a black
    // frame because the player is still in the paused first-frame state. The
    // pause/resume toggle still works after auto-start.
    LaunchedEffect(state) {
        if (state is PhotoViewerViewModel.ViewerState.ShowVideo) {
            videoStarted = true
        }
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

    // Slideshow play/pause — saved across rotation so the user doesn't lose their state.
    var isPlaying by rememberSaveable { mutableStateOf(false) }

    // Overlay visibility: tap image to toggle; force-show on page settle unless the
    // slideshow is running (otherwise the chrome would flash visible every 4 seconds
    // when auto-advance moves to the next photo).
    var showOverlays by remember { mutableStateOf(true) }
    LaunchedEffect(pagerState.settledPage) { if (!isPlaying) showOverlays = true }

    // Pause if the user single-taps anywhere on a photo (the same gesture that
    // toggles chrome). We do this by observing [showOverlays] — when it flips from
    // hidden to visible while playing, the user just tapped, so stop the slideshow.
    // (Page-change resets to showOverlays=true don't trip this because we re-hide
    //  the chrome immediately below while playing, never letting it linger visible.)
    LaunchedEffect(showOverlays, isPlaying) {
        if (isPlaying && showOverlays) {
            // Give the user 1 second to see the chrome before re-hiding it. If they
            // tap to pause the slideshow, isPlaying flips to false first and this
            // effect cancels before the delay completes — chrome stays visible.
            delay(1000)
            if (isPlaying) showOverlays = false
        }
    }

    // Tick: every 4 seconds, advance to the next page. Loops back to 0 from the end.
    // For VIDEOS we hold the advance until either the clip ends (player flips out of
    // playing state on its own) or the player hasn't started playing within the first
    // 8 seconds (defensive timeout — covers a stuck-buffering edge case so a single
    // broken file can't freeze the slideshow). Photos still advance on the steady
    // 4-second tick. Key on [settledPage] (not currentPage) so the timer doesn't
    // restart mid-animation when currentPage briefly flips as the pager crosses the
    // threshold.
    LaunchedEffect(isPlaying, pagerState.settledPage) {
        if (!isPlaying) return@LaunchedEffect
        val settledItem = items.getOrNull(pagerState.settledPage)
        val isVideoItem = when (settledItem) {
            is GalleryItem.LocalOnly -> settledItem.local.mimeType.startsWith("video/")
            is GalleryItem.Synced    -> settledItem.local.mimeType.startsWith("video/")
            is GalleryItem.CloudOnly -> settledItem.cloud.mimeType.startsWith("video/")
            null -> false
        }
        if (isVideoItem) {
            // Give the player up to 8 seconds to start; once playing, wait for it to
            // actually stop (clip ended, or buffer underrun → not-playing). Avoids the
            // 4-second forced-skip the user saw mid-clip.
            val startDeadline = System.currentTimeMillis() + 8_000L
            while (isPlaying && !isVideoPlaying && System.currentTimeMillis() < startDeadline) {
                delay(200)
            }
            // Wait while the video keeps playing — exits when isVideoPlaying drops to
            // false (natural end of clip OR REPEAT_MODE_ONE loop boundary; the latter
            // would otherwise lock the slideshow forever, so we bail after a max-watch
            // ceiling tied to slideshow_video_max_secs below).
            val maxWatchMs = 60_000L
            val watchDeadline = System.currentTimeMillis() + maxWatchMs
            while (isPlaying && isVideoPlaying && System.currentTimeMillis() < watchDeadline) {
                delay(200)
            }
        } else {
            delay(4000)
        }
        if (isPlaying) {
            val next = (pagerState.settledPage + 1) % items.size
            pagerState.animateScrollToPage(next)
        }
    }

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
                            // Tap → toggle overlays. While the slideshow is running, a
                            // tap also pauses it (and forces chrome visible so the user
                            // gets immediate feedback that auto-advance stopped).
                            launch {
                                detectTapGestures(onTap = {
                                    if (isPlaying) {
                                        isPlaying = false
                                        showOverlays = true
                                    } else {
                                        showOverlays = !showOverlays
                                    }
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
                // Suppress the thumbnail not just on play-tap but as soon as the full-res
                // video URI lands — once VideoPlayer is in composition the surface paints
                // the first frame on prepare() and the thumbnail just sits on top, producing
                // a "downloaded but won't start" appearance where the video looks broken
                // because the poster never updates after download.
                val showingVideoState = isSettled &&
                    state is PhotoViewerViewModel.ViewerState.ShowVideo
                val suppressThumbForVideo = isSettled && isVideoItemThumb &&
                    (videoStarted || showingVideoState)
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
                            if (stateMatchesPage) {
                                // Render the player as soon as a full-res video URI is
                                // available — gating on `videoStarted` meant the ExoPlayer
                                // wasn't built until the user tapped the pill, but because
                                // that tap fires onPlay → videoStarted=true → only THEN
                                // does the player initialise, the press feels dead for the
                                // second it takes to prepare the MediaSource. Build the
                                // player upfront with playWhenReady gated on videoStarted
                                // so the first frame appears immediately and the play tap
                                // just flips the play/pause state on an already-prepared
                                // source. Also covers downloaded cloud videos that never
                                // started — they were waiting on a tap that the play
                                // overlay never surfaced.
                                VideoPlayer(
                                    uri = s.uri,
                                    autoPlay = videoStarted,
                                    // After an Overwrite-save the URI string is unchanged, so
                                    // a uri-only remember would reuse the ExoPlayer holding
                                    // the pre-edit MediaItem (cached buffers + indexes). Mix
                                    // editedAt into the player's identity so a save forces a
                                    // fresh prepare() against the freshly-written bytes.
                                    reloadKey = editedAt,
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

                    // Video-only download badge. Shown from "download starts" all the way
                    // through "ExoPlayer prepares + first frame paints" — anything in between
                    // looks like a dead player to the user. videoEverPlayed flips true the
                    // first time the player reports isPlaying after a settle; after that the
                    // pill stays hidden through ordinary pause/resume cycles. Without this
                    // continuity the user saw the pill disappear at 100% but the video took
                    // another second to start, leaving an empty black frame in between.
                    val isVideoLoading = isVideoItemThumb && (
                        isDownloading ||
                        (state is PhotoViewerViewModel.ViewerState.ShowVideo && !videoEverPlayed)
                    )
                    // Hidden-on-device overlay — when the currently rendered cloud photo's
                    // linkId is in [hiddenCloudLinkIds], blur the full-res surface and
                    // show a localized "Hidden on this device" label so the user can't
                    // peek at the content from the regular viewer either. Mirrors the
                    // gallery cell treatment, scaled up for the full-screen pager.
                    val pageCloudLinkId: String? = when (item) {
                        is GalleryItem.CloudOnly -> item.cloud.linkId
                        is GalleryItem.Synced -> item.cloud.linkId
                        else -> null
                    }
                    if (pageCloudLinkId != null && pageCloudLinkId in hiddenCloudLinkIds) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                        Modifier.blur(56.dp)
                                    else
                                        Modifier,
                                )
                                .background(Color.Black.copy(alpha = 0.78f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = FgPrimary,
                                    modifier = Modifier.size(40.dp),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.viewer_hidden_label),
                                    color = FgPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    if (isVideoLoading) {
                        val pct = downloadProgress?.let { p ->
                            if (p.totalBytes > 0L) (p.doneBytes * 100 / p.totalBytes).toInt() else null
                        }
                        Row(
                            modifier = Modifier
                                .background(PillBg, RoundedCornerShape(999.dp))
                                .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                color = FgPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                if (pct != null) "Downloading $pct%" else "Downloading…",
                                color = FgPrimary, fontSize = 12.sp,
                            )
                        }
                    }
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

                    // Edit button — images go to PhotoEditor, videos to VideoEditor. NavGraph
                    // routes based on mimeType at click time, so we just need an editable media item.
                    val isEditable = when (settledItem) {
                        is GalleryItem.LocalOnly -> {
                            val m = settledItem.local.mimeType
                            m.startsWith("image/") || m.startsWith("video/")
                        }
                        is GalleryItem.Synced -> {
                            val m = settledItem.local.mimeType
                            m.startsWith("image/") || m.startsWith("video/")
                        }
                        is GalleryItem.CloudOnly -> {
                            val m = settledItem.cloud.mimeType
                            m.startsWith("image/") || m.startsWith("video/")
                        }
                        null -> false
                    }
                    if (settledItem != null && isEditable) {
                        ViewerBubble(onClick = { onEditItem(settledItem) }) {
                            Icon(Icons.Default.Edit, "Edit",
                                tint = FgPrimary, modifier = Modifier.size(18.dp))
                        }
                    }

                    Box {
                        val appColors = eu.akoos.photos.presentation.theme.AppColors.current
                        var menuExpanded by remember { mutableStateOf(false) }
                        ViewerBubble(onClick = { menuExpanded = true }) {
                            val anyInFlight = isDeleting || isSavingToDevice
                            if (anyInFlight) {
                                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.viewer_menu_more),
                                    tint = FgPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            shape = RoundedCornerShape(18.dp),
                            containerColor = appColors.cardBg,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, appColors.pillBorder),
                        ) {
                            if (items.size > 1) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(
                                        if (isPlaying) R.string.viewer_pause_slideshow
                                        else R.string.viewer_play_slideshow,
                                    ), color = FgPrimary) },
                                    leadingIcon = { Icon(
                                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        null,
                                        tint = if (isPlaying) Accent else FgPrimary,
                                        modifier = Modifier.size(20.dp),
                                    ) },
                                    onClick = {
                                        menuExpanded = false
                                        isPlaying = !isPlaying
                                    },
                                )
                            }
                            if (showSaveToDevice && settledItem is GalleryItem.CloudOnly) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.viewer_menu_save_to_device),
                                        color = FgPrimary) },
                                    leadingIcon = { Icon(Icons.Default.FileDownload, null,
                                        tint = FgPrimary, modifier = Modifier.size(20.dp)) },
                                    enabled = !isSavingToDevice,
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.downloadToDevice(settledItem)
                                    },
                                )
                            }
                            if (isLocalItem) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(
                                        if (isHidden) R.string.viewer_menu_unhide
                                        else R.string.viewer_menu_hide,
                                    ), color = FgPrimary) },
                                    leadingIcon = { Icon(Icons.Default.VisibilityOff, null,
                                        tint = if (isHidden) Accent else FgPrimary,
                                        modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        menuExpanded = false
                                        val item = settledItem ?: return@DropdownMenuItem
                                        if (isHidden) {
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
                                            viewModel.hideItem(item)
                                        }
                                    },
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_menu_details),
                                    color = FgPrimary) },
                                leadingIcon = { Icon(Icons.Default.Info, null,
                                    tint = FgPrimary, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    menuExpanded = false
                                    showMetadata = true
                                },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_menu_delete),
                                    color = ErrorColor) },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null,
                                    tint = ErrorColor, modifier = Modifier.size(20.dp)) },
                                enabled = !isDeleting,
                                onClick = {
                                    menuExpanded = false
                                    showDeleteSheet = true
                                },
                            )
                        }
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
                // Upgrade a stale CloudOnly to "synced" once we know the cloud linkId is
                // mirrored to a device file — happens after the user downloads it from this
                // screen but the static `items` snapshot can't reflect it.
                val effectiveSynced = currentItem is GalleryItem.Synced ||
                    (currentItem is GalleryItem.CloudOnly &&
                        localUriByLinkId.containsKey(currentItem.cloud.linkId))
                when {
                    effectiveSynced -> {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = "Backed up, also on device",
                            tint = Color(0xFF30D158),
                            modifier = Modifier.size(13.dp),
                        )
                        Text("·", color = FgMute, fontSize = 13.sp)
                    }
                    currentItem is GalleryItem.CloudOnly -> {
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
    /** Optional bump value mixed into the player's `remember` key — caller passes the
     *  editor's last-save timestamp so an Overwrite (same URI, fresh bytes) builds a new
     *  ExoPlayer instead of reusing the one whose MediaSource still points at the
     *  pre-edit state. */
    reloadKey: Any = Unit,
    /** When false the player is built and prepared (first frame visible) but doesn't
     *  start playback — the pill's play button flips this true via state at the call site.
     *  This split lets the surface fade in immediately on cloud-video download and the
     *  tap-to-play feel instantaneous instead of waiting on prepare(). */
    autoPlay: Boolean = true,
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri, reloadKey) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = autoPlay
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
        }.also { onPlayerReady(it) }
    }
    // Sync playback state when the caller flips autoPlay (user tapped the pill).
    LaunchedEffect(exoPlayer, autoPlay) {
        exoPlayer.playWhenReady = autoPlay
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false   // custom controls in VideoControlPill
                // useController=false alone still leaves the buffering spinner and the
                // brief play/pause flash on tap visible. Disable both so the surface
                // shows the video frame and nothing else — our own pill is the only UI.
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                hideController()
                controllerAutoShow = false
                // The PlayerView's "shutter" view is a solid background that covers the
                // surface until the first frame renders — and on some devices it stays
                // up during pause / seek, looking like an unwanted overlay. Make it
                // transparent so users only ever see the video itself.
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                // Restored to true once single-flight download per linkId landed and
                // eliminated the swipe-back-rebuilds-player path. The cached video now
                // reuses the same ExoPlayer instance, so there's no "kept old frame"
                // hazard — and keeping content on reset removes a flicker where the
                // surface would briefly clear to transparent and the page background
                // would bleed through.
                setKeepContentOnPlayerReset(true)
                // The default artwork (a centered placeholder) shows when audio-only
                // metadata is detected — irrelevant for our case but kill it explicitly.
                useArtwork = false
                setDefaultArtwork(null)
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
