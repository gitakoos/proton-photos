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

package eu.akoos.photos.presentation.viewer

import android.app.Activity
import android.content.Intent
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.material.icons.filled.MotionPhotosOn
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.SecureScreenEffect
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
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
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
import eu.akoos.photos.presentation.util.formatVideoTime
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.PhotoMetadata
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val bubbleShape = CircleShape
internal val infoPillShape = RoundedCornerShape(999.dp)

/** Local-side URI of an item that carries one (LocalOnly / Synced), else null. Used to re-pair a
 *  snapshot LocalOnly with the Synced twin it became once its upload landed. */
private fun GalleryItem.localUriOrNull(): String? = when (this) {
    is GalleryItem.LocalOnly -> local.uri
    is GalleryItem.Synced    -> local.uri
    is GalleryItem.CloudOnly -> null
}

/**
 * Reconcile the static [snapshot] handed to the viewer against the [live] timeline so the open
 * viewer reflects a photo finishing upload (LocalOnly → Synced) and any metadata refresh, without
 * losing the user's place.
 *
 * Each snapshot item is re-resolved against [live] by its stable id, or — for a snapshot
 * `LocalOnly` whose upload landed and turned it into a `Synced` (new stable id) — by a local-uri
 * match. The resolved live version is swapped in; an item absent from [live] is kept as-is. The
 * list shape and order are preserved (same size, same positions), so this is safe for every
 * surface the viewer opens from: the caller already hands a filtered list (the gallery strips
 * hidden / timeline-excluded items, albums hand their own membership), and reconciliation never
 * injects an item that wasn't in that list. Returns [snapshot] unchanged until [live] first emits.
 */
internal fun reconcileViewerItems(
    snapshot: List<GalleryItem>,
    live: List<GalleryItem>,
): List<GalleryItem> {
    if (live.isEmpty() || snapshot.isEmpty()) return snapshot
    val byStableId = live.associateBy { it.stableId }
    val byLocalUri = live.asSequence()
        .mapNotNull { item -> item.localUriOrNull()?.let { it to item } }
        .toMap()

    fun resolve(item: GalleryItem): GalleryItem =
        byStableId[item.stableId]
            ?: item.localUriOrNull()?.let { byLocalUri[it] }
            ?: item

    return snapshot.map(::resolve)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    items: List<GalleryItem>,
    initialIndex: Int,
    onBack: () -> Unit,
    showSaveToDevice: Boolean = true,
    sourceAlbumLinkId: String? = null,
    /** True for a shared-with-me album: suppresses every mutating affordance (delete, set cover,
     *  rename, edit) since the backend rejects them from the wrong share id. Save + Info stay. */
    isReadOnlyAlbum: Boolean = false,
    /** Editor pop-back timestamp, keyed into the page-load effect so the viewer drops its bitmap
     *  cache and re-reads fresh bytes after a save. */
    editedAt: Long = 0L,
    /** Cloud linkIds whose local twin is in the Hidden vault; those items get a blur + "Hidden"
     *  overlay over the full-res surface. */
    hiddenCloudLinkIds: Set<String> = emptySet(),
    /** Hidden-vault session: marks the viewer window FLAG_SECURE so full-res hidden photos stay
     *  out of screenshots and the recent-apps preview, like the vault grid itself. */
    secure: Boolean = false,
    onEditItem: (GalleryItem) -> Unit = {},
    viewModel: PhotoViewerViewModel = hiltViewModel(),
) {
    if (items.isEmpty()) { onBack(); return }

    if (secure) SecureScreenEffect()

    // Live reconciliation: the caller hands a static snapshot captured at click time, so a photo
    // finishing upload (LocalOnly → Synced) or any metadata refresh would never reflect here. Swap
    // each snapshot item for its live version in place (same order + size), keeping items not in
    // the live merge as-is. See [reconcileViewerItems].
    val liveItems by viewModel.liveItems.collectAsStateWithLifecycle()
    val reconciled = remember(items, liveItems) { reconcileViewerItems(items, liveItems) }
    // Render off the reconciled list; every per-page lookup below reads from `items` so alias it.
    @Suppress("NAME_SHADOWING") val items = reconciled

    val clampedInitial = initialIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = clampedInitial) { items.size }
    // Identity of the page the user is settled on, remembered so that — should a future live
    // change ever alter the list length — we can re-find the photo by key and keep the user on it.
    // With the in-place swap the index is stable, so the scroll-back below is normally inert; the
    // pager `key` (further down) is what makes a LocalOnly → Synced swap rebind cleanly.
    var anchorKey by remember { mutableStateOf(items.getOrNull(clampedInitial)?.stableId) }
    LaunchedEffect(pagerState.settledPage) {
        items.getOrNull(pagerState.settledPage)?.stableId?.let { anchorKey = it }
    }
    LaunchedEffect(items) {
        val key = anchorKey ?: return@LaunchedEffect
        val newIndex = items.indexOfFirst { it.stableId == key }
        if (newIndex >= 0 && newIndex != pagerState.currentPage) {
            pagerState.scrollToPage(newIndex)
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val fullResBlockedByMetered by viewModel.fullResBlockedByMetered.collectAsStateWithLifecycle()
    // The metered "full quality" hint is dismissable per viewer session — once X'd it stays gone
    // while the user keeps browsing on the same metered link.
    var meteredHintDismissed by remember { mutableStateOf(false) }
    val metadata by viewModel.metadata.collectAsStateWithLifecycle()
    val detailsPlace by viewModel.detailsPlace.collectAsStateWithLifecycle()
    val cloudVideoMeta by viewModel.cloudVideoMeta.collectAsStateWithLifecycle()
    val cloudFullResSize by viewModel.cloudFullResSize.collectAsStateWithLifecycle()
    val isStrippingMetadata by viewModel.isStrippingMetadata.collectAsStateWithLifecycle()
    val photoTags by viewModel.currentPhotoTags.collectAsStateWithLifecycle()
    val isHidden by viewModel.isHidden.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val isMotionPhoto by viewModel.isMotionPhoto.collectAsStateWithLifecycle()
    val motionVideoFile by viewModel.motionVideoFile.collectAsStateWithLifecycle()
    val isExtractingMotion by viewModel.isExtractingMotion.collectAsStateWithLifecycle()
    val isPanorama by viewModel.isPanorama.collectAsStateWithLifecycle()
    val isPanoramaMode by viewModel.isPanoramaMode.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val isAddingToAlbum by viewModel.isAddingToAlbum.collectAsStateWithLifecycle()
    val isSavingToDevice by viewModel.isSavingToDevice.collectAsStateWithLifecycle()
    val isSharing by viewModel.isSharing.collectAsStateWithLifecycle()
    // Live cloud→device twin map: flips the bottom badge to "synced" once a download persists a
    // SyncState, which the static `items` snapshot can't reflect.
    val localUriByLinkId by viewModel.localUriByLinkId.collectAsStateWithLifecycle()

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

    LaunchedEffect(Unit) { viewModel.loadAlbums() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val transientError by viewModel.transientError.collectAsStateWithLifecycle()
    LaunchedEffect(transientError) {
        val msg = transientError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearTransientError()
    }
    // These collectors share one repeatOnLifecycle(STARTED) wrap so they pause together when the
    // viewer is backgrounded (otherwise a mid-background emit snackbars against a hidden host).
    val addedToAlbumTemplate = stringResource(R.string.viewer_added_to_album)
    val coverUpdatedMessage = stringResource(R.string.album_cover_updated)
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    val shareContext = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            launch {
                viewModel.addToAlbumDone.collect { albumName ->
                    snackbarHostState.showSnackbar(String.format(addedToAlbumTemplate, albumName))
                }
            }
            launch {
                viewModel.setCoverDone.collect {
                    snackbarHostState.showSnackbar(coverUpdatedMessage)
                }
            }
            // In the STARTED block so a backgrounded viewer doesn't pop the chooser over another screen.
            launch {
                viewModel.shareIntent.collect { intent ->
                    shareContext.startActivity(Intent.createChooser(intent, shareChooserTitle))
                }
            }
        }
    }

    LaunchedEffect(pagerState.settledPage, editedAt) {
        // editedAt bumps after an editor save, re-running this effect to re-read fresh bytes
        // (the URI's Coil memory cache was nuked in invalidateImageCache, so this reads from disk).
        when (val item = items.getOrNull(pagerState.settledPage)) {
            is GalleryItem.LocalOnly  -> viewModel.loadLocal(item.local.uri, item.local.mimeType)
            is GalleryItem.Synced     -> viewModel.loadLocal(item.local.uri, item.local.mimeType)
            is GalleryItem.CloudOnly  -> viewModel.loadCloud(item.cloud)
            null -> {}
        }
    }

    // Keyed on `state` (not just the page) so detection runs once the still is actually showing —
    // a cloud image only becomes a file:// after the full-res download lands.
    LaunchedEffect(state, pagerState.settledPage) {
        val item = items.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
        val s = state as? PhotoViewerViewModel.ViewerState.ShowImage ?: return@LaunchedEffect
        val mime = when (item) {
            is GalleryItem.LocalOnly -> item.local.mimeType
            is GalleryItem.Synced    -> item.local.mimeType
            is GalleryItem.CloudOnly -> item.cloud.mimeType
        }
        if (!mime.startsWith("image/")) return@LaunchedEffect
        // Only a real on-disk/content URI is probeable — the CDN thumbnail URL (a remote
        // String model) isn't a motion-photo source, so skip until the full-res lands.
        val model = s.model
        if (model is Uri) {
            viewModel.detectMotionPhoto(
                uri = model.toString(),
                itemKey = s.itemKey,
            )
        }
    }
    // Stop inline motion playback (and delete the extracted temp) when the viewer leaves the
    // composition, so a clip can't outlive the screen.
    DisposableEffect(Unit) { onDispose { viewModel.stopMotionPhoto() } }

    // Panorama probe (image items only); the VM scans off-thread, guarded against a mid-scan swipe.
    LaunchedEffect(pagerState.settledPage) {
        val item = items.getOrNull(pagerState.settledPage)
        val (uri, itemKey, isImage) = when (item) {
            is GalleryItem.LocalOnly -> Triple(
                Uri.parse(item.local.uri), item.local.uri, item.local.mimeType.startsWith("image/"),
            )
            is GalleryItem.Synced -> Triple(
                Uri.parse(item.local.uri), item.local.uri, item.local.mimeType.startsWith("image/"),
            )
            is GalleryItem.CloudOnly -> Triple(
                null, item.cloud.linkId, item.cloud.mimeType.startsWith("image/"),
            )
            null -> Triple(null, null, false)
        }
        // For a cloud-only photo there's no local Uri to scan; detectPanorama still inspects
        // the Panoramas server tag in that branch.
        if (isImage) viewModel.detectPanorama(item, uri, itemKey)
    }

    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // Pan distance accumulated past the image edge while zoomed; crossing the threshold pages.
    var edgeOverpan by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pagerState.settledPage) { scale = 1f; offset = Offset.Zero; edgeOverpan = 0f }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        if (scale > 1f) {
            // graphicsLayer scales around center, so the reachable pan is ±(viewport*(scale-1)/2).
            val maxX = (containerSize.width  * (scale - 1f)) / 2f
            val maxY = (containerSize.height * (scale - 1f)) / 2f
            val unclamped = offset + panChange
            offset = Offset(
                unclamped.x.coerceIn(-maxX, maxX),
                unclamped.y.coerceIn(-maxY, maxY),
            )
            // Edge-paging: a pure pan (no active pinch) pushing past the horizontal
            // bound accumulates; crossing the threshold advances the pager the same
            // direction the finger travels. Any in-bounds pan resets the accumulator
            // so casual panning never triggers it.
            if (kotlin.math.abs(zoomChange - 1f) < 0.001f) {
                when {
                    unclamped.x < -maxX -> edgeOverpan += (-maxX - unclamped.x)
                    unclamped.x >  maxX -> edgeOverpan -= (unclamped.x - maxX)
                    else                -> edgeOverpan = 0f
                }
                val threshold = 140f
                if (edgeOverpan > threshold && pagerState.currentPage < items.lastIndex) {
                    edgeOverpan = 0f
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                } else if (edgeOverpan < -threshold && pagerState.currentPage > 0) {
                    edgeOverpan = 0f
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }
            }
        } else {
            offset = Offset.Zero
        }
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
    // Flipped true the instant a back is initiated, BEFORE the pop animation runs, so the live
    // PlayerView surface is torn out of the composition immediately and the already-drawn
    // thumbnail/background fades instead of a lingering video frame. The route's popExitTransition
    // would otherwise keep the surface alive through its 180ms fade.
    var exiting by remember { mutableStateOf(false) }
    // Pause the current player and drop the video surface a frame before the back navigation, so
    // the fade animates over a still rather than a playing surface.
    val startExit = {
        currentPlayer?.let { runCatching { it.playWhenReady = false } }
        exiting = true
        onBack()
    }
    // Route the system/gesture back through the same teardown so a hardware back doesn't leave the
    // playing surface to linger through the pop fade either.
    androidx.activity.compose.BackHandler(enabled = !exiting) { startExit() }
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

    // Overlay visibility: tap image to toggle. Deliberately NOT reset on page change —
    // once the user hides the chrome they stay in immersive browsing across swipes
    // until they tap again.
    var showOverlays by remember { mutableStateOf(true) }

    // Pause if the user single-taps anywhere on a photo (the same gesture that
    // toggles chrome). We do this by observing [showOverlays] — when it flips from
    // hidden to visible while playing, the user just tapped, so stop the slideshow.
    // (Overlay visibility persists across page changes, so only a real tap can flip
    //  it to visible while the slideshow runs.)
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
            // 4-second forced-skip mid-clip.
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

    // Unified single-photo share drawer (Send to app / Share with people / Public link).
    var showShareSheet by remember { mutableStateOf(false) }
    val shareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The dedicated public-link management sheet, opened from the drawer's "Public link" row.
    var showManageLinkSheet by remember { mutableStateOf(false) }
    val manageLinkSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val publicLinkState by viewModel.publicLinkState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val linkCopiedMsg = stringResource(R.string.share_link_copied)
    val passwordSetMsg = stringResource(R.string.share_password_set)
    val passwordRemovedMsg = stringResource(R.string.share_password_removed)

    // Android 11+ system trash dialog launcher
    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeletePermissionGranted()
        else viewModel.resetDeleteState()
    }

    // Android 10+ write-permission dialog launcher for stripping metadata from a
    // non-app-owned file (mirrors the delete launcher above).
    val stripState by viewModel.stripState.collectAsStateWithLifecycle()
    val stripPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.retryPendingStrip()
        else viewModel.resetStripState()
    }
    // Android 11+ write-permission dialog for renaming a non-app-owned file in place.
    val renamePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.retryPendingRename()
        else viewModel.resetRenameState()
    }
    LaunchedEffect(stripState) {
        val ss = stripState
        if (ss is PhotoViewerViewModel.StripState.NeedsPermission) {
            stripPermissionLauncher.launch(
                IntentSenderRequest.Builder(ss.pendingIntent.intentSender).build()
            )
        }
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
            is PhotoViewerViewModel.DeleteState.Failed -> {
                // Surface the failure as a themed snackbar (same in-app pattern as the
                // other viewer feedback) and reset the state so the delete-overlay
                // spinner doesn't get pinned to the screen forever (the "Not signed in"
                // case in particular ended up with a permanent Failed overlay that the
                // user couldn't dismiss). The reset moves us back to Idle so the next
                // user action can proceed.
                snackbarHostState.showSnackbar(ds.message)
                viewModel.resetDeleteState()
            }
            else -> {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0)
            .onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        // Gesture lambdas live in a key-less pointerInput so the detectors are installed
        // exactly once per page composition. Keying the pointerInput on scale/showMetadata
        // (the previous setup) cancelled and re-installed the tap detector on every pinch
        // step, and the first tap after a zoom landed in the restart window and was
        // silently dropped — the chrome only reacted to the second tap.
        val currentScale  by rememberUpdatedState(scale)
        val metadataShown by rememberUpdatedState(showMetadata)
        val slideshowOn   by rememberUpdatedState(isPlaying)

        // ── Pager ──────────────────────────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Key each page slot to its item's stable identity so a live reconcile rebinds per-page
            // state (video flags, painted-thumb gate) to the photo rather than the position. When a
            // LocalOnly is swapped for the Synced it became, its stable id changes (uri → linkId)
            // and that one page rebuilds cleanly against the now-backed-up item while the user stays
            // on it.
            key = { page -> items.getOrNull(page)?.stableId ?: page },
            // Page-swipe is suppressed while zoomed (scale > 1f) and while panorama mode
            // is active, so the panorama's own horizontal drag doesn't fight the pager.
            userScrollEnabled = scale == 1f && !isPanoramaMode,
        ) { page ->
            val item      = items.getOrNull(page)
            val isSettled = page == pagerState.settledPage

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Theme-reactive page background: pure black viewer chrome in dark
                    // mode, white in light mode. Bg0 also covers the one-frame window
                    // between the thumb hiding and the full-res draw, so no off-theme
                    // flash can show through.
                    .background(Bg0)
                    .pointerInput(Unit) {
                        coroutineScope {
                            launch {
                                detectTapGestures(
                                    // Tap → toggle overlays. While the slideshow is running,
                                    // a tap pauses it and forces the chrome visible so the
                                    // user gets immediate feedback that auto-advance stopped.
                                    onTap = {
                                        if (slideshowOn) {
                                            isPlaying = false
                                            showOverlays = true
                                        } else {
                                            showOverlays = !showOverlays
                                        }
                                    },
                                    // Double tap → zoom toward the tapped point; double tap
                                    // again → reset to fit.
                                    onDoubleTap = { tap ->
                                        if (currentScale > 1f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            val newScale = 2.5f
                                            val cx = containerSize.width / 2f
                                            val cy = containerSize.height / 2f
                                            val maxX = (containerSize.width  * (newScale - 1f)) / 2f
                                            val maxY = (containerSize.height * (newScale - 1f)) / 2f
                                            scale = newScale
                                            offset = Offset(
                                                ((cx - tap.x) * (newScale - 1f)).coerceIn(-maxX, maxX),
                                                ((cy - tap.y) * (newScale - 1f)).coerceIn(-maxY, maxY),
                                            )
                                        }
                                    },
                                )
                            }
                            // Swipe up → open details (only when not zoomed)
                            launch {
                                detectVerticalDragGestures { _, dragAmount ->
                                    if (currentScale <= 1f && !metadataShown && dragAmount < -40f) {
                                        showMetadata = true
                                        showOverlays = true
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Videos: Coil's VideoFrameDecoder grabs a frame via MediaMetadataRetriever, which
                // ignores the MP4 rotation atom → a sideways full-screen poster flash before the
                // player paints. Use the correctly-oriented cloud thumbnail when there is one
                // (Synced/CloudOnly); for a not-yet-uploaded local video draw no poster at all (the
                // themed background covers the brief pre-first-frame gap) rather than flash the
                // rotation-broken frame. Photos keep their local-URI poster (Coil honours EXIF).
                val thumbModel: Any? = when (item) {
                    is GalleryItem.LocalOnly ->
                        if (item.local.mimeType.startsWith("video/")) null
                        else Uri.parse(item.local.uri)
                    is GalleryItem.Synced ->
                        if (item.local.mimeType.startsWith("video/")) item.cloud.thumbnailUrl
                        else Uri.parse(item.local.uri)
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
                // Keep the thumbnail drawn UNDER the player until the first decoded frame
                // actually paints (videoEverPlayed latches on the first reported isPlaying).
                // The default SurfaceView is opaque black and punches a hole through the Compose
                // layer, so on a light theme — and on any cold open — there's a black flash
                // through download → prepare → first-paint; the poster covers it. Suppressing the
                // moment ShowVideo lands (the old behaviour) re-opened that gap, so gate on the
                // painted-a-frame signal instead.
                val suppressThumbForVideo = isSettled && isVideoItemThumb && videoEverPlayed
                // Identity of the item currently bound to this page (same expression the
                // settled-block uses below for stateMatchesPage). Lifted here so the thumb
                // gate can check whether the full-res image is actually rendering for *this*
                // page before we hide the placeholder.
                val currentItemKey: String? = when (item) {
                    is GalleryItem.LocalOnly -> item.local.uri
                    is GalleryItem.Synced    -> item.local.uri
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                    null -> null
                }
                val stateMatchesPage = state.itemKey == null || state.itemKey == currentItemKey
                // True once the settled full-res image has actually painted for this page.
                // Re-armed per item so each new photo holds its thumb underneath until the
                // full-res frame is up. Keeps the thumb drawn through the full-res decode +
                // crossfade so the Bg0 background never shows through on a cold open.
                var fullResPainted by remember(currentItemKey) { mutableStateOf(false) }
                // Hide the thumb once the full-res image has painted for this settled page so
                // it stops peeking through at the edges when the user pinch-zooms and pans —
                // the full-res layer is graphicsLayer-translated, the thumb is not, and at
                // any non-centered scale>1f the thumb would otherwise show through behind.
                val suppressThumbForLoadedImage = isSettled &&
                    state is PhotoViewerViewModel.ViewerState.ShowImage &&
                    stateMatchesPage &&
                    fullResPainted
                if (thumbModel != null && !suppressThumbForVideo && !suppressThumbForLoadedImage) {
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
                    // currentItemKey + stateMatchesPage are hoisted above the thumb block so
                    // both the placeholder gate and the full-res renderer use the same
                    // page-identity check. The stateMatchesPage guard handles the one-frame
                    // window during a swipe A → B where settledPage flips to B but `state`
                    // still references A's loaded image — without it, B would flash A.
                    when (val s = state) {
                        is PhotoViewerViewModel.ViewerState.ShowImage ->
                            if (stateMatchesPage) {
                                val motionFile = motionVideoFile
                                if (isMotionPhoto && motionFile != null) {
                                    // Inline motion-photo playback: the extracted embedded clip
                                    // plays once over the still through the shared VideoPlayer,
                                    // then onEnded drops us back to the image.
                                    VideoPlayer(
                                        uri = Uri.fromFile(motionFile),
                                        autoPlay = true,
                                        onEnded = { viewModel.stopMotionPhoto() },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else if (isPanoramaMode) {
                                    // Immersive panorama: the still fills viewport height and
                                    // overflows horizontally (FillHeight crops width), and a
                                    // horizontal drag scrolls along the strip. Kept on a dedicated
                                    // offset + gesture so the normal pinch-zoom transform (with its
                                    // edge-paging) is left untouched.
                                    PanoramaPager(
                                        model = s.model,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    // Fade the full-res in over the thumb (which stays drawn
                                    // until onState reports Success) so a cold open never shows
                                    // the background through a one-frame gap. The transform stays
                                    // on this element so pinch-zoom is unaffected.
                                    val imageContext = LocalContext.current
                                    val fullResRequest = remember(s.model) {
                                        ImageRequest.Builder(imageContext)
                                            .data(s.model)
                                            .crossfade(true)
                                            .build()
                                    }
                                    AsyncImage(
                                        model = fullResRequest,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        onState = { st ->
                                            if (st is AsyncImagePainter.State.Success) fullResPainted = true
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .transformable(state = transformState, canPan = { scale > 1f })
                                            .graphicsLayer(
                                                scaleX = scale, scaleY = scale,
                                                translationX = offset.x, translationY = offset.y,
                                            ),
                                    )
                                }
                            }
                        is PhotoViewerViewModel.ViewerState.ShowVideo -> {
                            // Drop the player the frame a back starts (`exiting`) so the live
                            // surface swaps out for the already-drawn thumbnail/background before
                            // the route's pop fade runs — otherwise the playing surface lingers
                            // through the 180ms animation.
                            if (stateMatchesPage && !exiting) {
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
                            if (stateMatchesPage) Text(s.message ?: stringResource(R.string.viewer_error_loading_photo), color = ErrorColor, fontSize = 14.sp)
                    }

                    // Video-only download badge. Shown from "download starts" all the way
                    // through "ExoPlayer prepares + first frame paints" — anything in between
                    // looks like a dead player to the user. videoEverPlayed flips true the
                    // first time the player reports isPlaying after a settle; after that the
                    // pill stays hidden through ordinary pause/resume cycles. Without this
                    // continuity the pill disappears at 100% but the video takes another
                    // second to start, leaving an empty black frame in between.
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
                            // "Downloading…" only when there's a cloud download in flight;
                            // otherwise it's the player decoding a local file, where the right
                            // word is "Loading". Otherwise the user sees a Downloading flash
                            // on every device-local video which is misleading.
                            val statusText = when {
                                pct != null -> stringResource(R.string.viewer_downloading_pct, pct)
                                isDownloading -> stringResource(R.string.viewer_downloading)
                                else -> stringResource(R.string.viewer_loading)
                            }
                            Text(
                                statusText,
                                color = FgPrimary, fontSize = 12.sp,
                            )
                        }
                    }

                    // Motion-photo "play" and panorama "view" affordances live in the bottom
                    // control pills (next to the video pill) so the open still stays
                    // unobstructed while browsing. Only the panorama exit chip stays
                    // top-anchored here, so it is reachable while panning with the bottom
                    // chrome hidden.
                    if (isPanoramaMode && stateMatchesPage) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(top = 64.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(PillBg, RoundedCornerShape(999.dp))
                                    .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
                                    .clickable { viewModel.exitPanorama() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cd_exit_panorama),
                                    tint = FgPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    stringResource(R.string.viewer_exit_panorama),
                                    color = FgPrimary, fontSize = 12.sp,
                                )
                            }
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
                ViewerBubble(onClick = startExit) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.onboarding_back),
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

                    // Favorite button — hidden for shared-with-me viewers because the
                    // favorite flag is a node-level tag on the OWNER's photo, not a
                    // private bookmark on the recipient side. Writing it from the
                    // recipient would either fail or mutate the owner's library.
                    if (settledItem != null && !isReadOnlyAlbum) {
                        ViewerBubble(onClick = { viewModel.toggleFavorite(settledItem) }) {
                            Icon(
                                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                stringResource(
                                    if (isFavorite) R.string.cd_favorite_remove
                                    else R.string.cd_favorite_add,
                                ),
                                tint = if (isFavorite) Color(0xFFFF3B30) else FgDim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    // Add to album — available for any item with at least a local OR cloud
                    // representation. Cloud / Synced → cloud album add (addPhotosToAlbum API).
                    // LocalOnly / Synced → local virtual-album membership (DataStore write,
                    // no file move so DATE_TAKEN survives Android Q+ MediaProvider restrictions).
                    // Add to album — hidden for shared-with-me viewers. The recipient
                    // could in theory pin someone else's photo into one of their own
                    // albums, but the underlying call needs cloud-side share access
                    // and our path doesn't bridge across the recipient/owner volume
                    // boundary.
                    if (settledItem != null && !isReadOnlyAlbum) {
                        ViewerBubble(onClick = { showAddToAlbumSheet = true }) {
                            if (isAddingToAlbum) {
                                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Default.LibraryAdd, stringResource(R.string.gallery_add_to_album),
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
                    if (settledItem != null && isEditable && !isReadOnlyAlbum) {
                        ViewerBubble(onClick = { onEditItem(settledItem) }) {
                            Icon(Icons.Default.Edit, stringResource(R.string.cd_viewer_edit),
                                tint = FgPrimary, modifier = Modifier.size(18.dp))
                        }
                    }

                    Box {
                        val appColors = eu.akoos.photos.presentation.theme.AppColors.current
                        var menuExpanded by remember { mutableStateOf(false) }
                        ViewerBubble(onClick = { menuExpanded = true }) {
                            val anyInFlight = isDeleting || isSavingToDevice || isSharing
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
                            // Share → open the unified share drawer (Send to another app /
                            // Share with people / Public link) instead of jumping straight to
                            // the OS sheet. Available across all three subtypes; a shared-album
                            // guest can still use "Send to another app" (copies bytes out, no
                            // album grant). Opening the drawer kicks off the public-link lookup.
                            if (settledItem != null) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_action),
                                        color = FgPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Share, null,
                                        tint = FgPrimary, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.loadPublicLink(settledItem)
                                        showShareSheet = true
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
                            // "Back up to Drive" — force-upload a not-yet-backed-up local photo.
                            // Only LocalOnly qualifies; Synced / CloudOnly are already on Drive.
                            if (settledItem is GalleryItem.LocalOnly) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.device_folder_upload_action),
                                        color = FgPrimary) },
                                    leadingIcon = { Icon(Icons.Default.CloudUpload, null,
                                        tint = Accent, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.backUpItem(settledItem)
                                    },
                                )
                            }
                            // "Set as album cover" — only visible when the viewer was opened
                            // from an album (sourceAlbumLinkId != null) AND the item is a cloud
                            // photo (Synced/CloudOnly carry a Drive linkId — LocalOnly doesn't).
                            // No-op when the user owns the album but the cover is unchanged,
                            // because setAlbumCover is idempotent server-side.
                            val isCloudItemForCover = settledItem is GalleryItem.Synced ||
                                settledItem is GalleryItem.CloudOnly
                            if (sourceAlbumLinkId != null && isCloudItemForCover && !isReadOnlyAlbum) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.album_set_as_album_cover),
                                        color = FgPrimary) },
                                    leadingIcon = { Icon(
                                        Icons.Default.PhotoLibrary,
                                        null,
                                        tint = Accent,
                                        modifier = Modifier.size(20.dp),
                                    ) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.setCurrentAsAlbumCover(settledItem, sourceAlbumLinkId)
                                    },
                                )
                            }
                            // Hide is only offered for LocalOnly items — Synced photos
                            // already have a Drive copy that hiding the local file alone
                            // wouldn't protect, and CloudOnly has no local file to move.
                            // Unhide stays available for any already-hidden item so the
                            // user can recover regardless of how it was hidden originally.
                            val canShowHideToggle = (!isHidden && settledItem is GalleryItem.LocalOnly) ||
                                (isHidden && isLocalItem)
                            if (canShowHideToggle) {
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
                            // Shared-with-me viewers stay strictly read-only — no
                            // delete affordance for someone else's photo.
                            if (!isReadOnlyAlbum) {
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
            // Full-quality-paused hint — sits with the bottom controls and is dismissable, shown
            // only while the Wi-Fi-only-for-fullres preference skipped the auto-download on a
            // metered link. The VM flag already tracks the settled item.
            if (fullResBlockedByMetered && !meteredHintDismissed) {
                Row(
                    modifier = Modifier
                        .background(PillBg, RoundedCornerShape(999.dp))
                        .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
                        .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.viewer_wifi_for_full_quality),
                        color = FgPrimary, fontSize = 12.sp,
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = FgPrimary.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { meteredHintDismissed = true },
                    )
                }
            }

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

            // Motion-photo control pill — play or stop the embedded clip from the bottom
            // chrome (mirrors the video pill) so the open still stays unobstructed while
            // browsing. Fades with the chrome like every other bottom affordance.
            if (isMotionPhoto && !isVideoItem &&
                state is PhotoViewerViewModel.ViewerState.ShowImage) {
                val motionKey = when (settledItem) {
                    is GalleryItem.CloudOnly -> settledItem.cloud.linkId
                    is GalleryItem.Synced    -> settledItem.cloud.linkId
                    is GalleryItem.LocalOnly -> settledItem.local.displayName
                    null -> ""
                }
                val motionPlaying = motionVideoFile != null
                Row(
                    modifier = Modifier
                        .background(PillBg, infoPillShape)
                        .border(0.5.dp, PillBorder, infoPillShape)
                        .clickable(enabled = !isExtractingMotion) {
                            if (motionPlaying) viewModel.stopMotionPhoto()
                            else viewModel.playMotionPhoto(motionKey)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isExtractingMotion) {
                        CircularProgressIndicator(
                            color = FgPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(
                            Icons.Default.MotionPhotosOn,
                            contentDescription = null,
                            tint = if (motionPlaying) Accent else FgPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        stringResource(
                            if (motionPlaying) R.string.cd_stop_motion_photo
                            else R.string.cd_play_motion_photo,
                        ),
                        color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Panorama control pill — enter the immersive pan view from the bottom chrome.
            if (isPanorama && !isMotionPhoto && !isPanoramaMode && !isVideoItem &&
                state is PhotoViewerViewModel.ViewerState.ShowImage) {
                Row(
                    modifier = Modifier
                        .background(PillBg, infoPillShape)
                        .border(0.5.dp, PillBorder, infoPillShape)
                        .clickable { viewModel.enterPanorama() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Panorama,
                        contentDescription = null,
                        tint = FgPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.viewer_view_panorama),
                        color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
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
                            contentDescription = stringResource(R.string.cd_status_backed_up_device),
                            tint = Color(0xFF30D158),
                            modifier = Modifier.size(13.dp),
                        )
                        Text("·", color = FgMute, fontSize = 13.sp)
                    }
                    currentItem is GalleryItem.CloudOnly -> {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = stringResource(R.string.cd_status_cloud_only),
                            // Theme-reactive tint: the pill background turns near-white in
                            // light mode, where a fixed white glyph disappears entirely.
                            tint = FgPrimary,
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

        // Bottom-anchored themed snackbar — keeps add-to-album / set-as-cover
        // confirmations + delete-failure errors inside the app's visual language
        // instead of the OS Toast popup that ignored our theme + sat below the
        // navigation bar.
        eu.akoos.photos.presentation.common.ThemedSnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        )
    }

    // ── Metadata sheet ─────────────────────────────────────────────────────────
    if (showMetadata) {
        val item = items.getOrNull(pagerState.settledPage)
        LaunchedEffect(item) {
            if (item != null) viewModel.loadDetailsPlace(item)
        }
        ModalBottomSheet(
            onDismissRequest = { showMetadata = false },
            sheetState = metadataSheetState,
            containerColor = Bg2,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            PhotoMetadataSheet(
                item = item,
                exif = metadata,
                place = detailsPlace,
                cloudVideoMeta = cloudVideoMeta,
                isStripping = isStrippingMetadata,
                cloudSizeFallback = cloudFullResSize,
                photoTags = photoTags,
                onToggleTag = { tagId, add -> item?.let { viewModel.setPhotoTag(it, tagId, add) } },
                onStripFields = { config ->
                    val uri = when (item) {
                        is GalleryItem.LocalOnly -> item.local.uri
                        is GalleryItem.Synced -> item.local.uri
                        else -> null
                    }
                    if (uri != null) viewModel.stripMetadataFromLocal(uri, config)
                },
                onRenameClick = {
                    // Rename is a node-level mutation we don't grant guests of a
                    // shared-with-me album. The metadata sheet's button is hidden
                    // when no callback fires, and this stays a no-op on that path.
                    if (!isReadOnlyAlbum) {
                        showMetadata = false
                        showRenameDialog = true
                    }
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

    // Close the dialog and reset state when the rename finishes. Drop the renamed item's
    // cached bytes so the stale original (old name / trashed cloud copy) stops showing
    // immediately — same invalidation the editor does after a save.
    LaunchedEffect(renameState) {
        val rs = renameState
        if (rs is PhotoViewerViewModel.RenameState.Done) {
            items.getOrNull(pagerState.settledPage)?.let { viewModel.invalidateAfterRename(it) }
            showRenameDialog = false
            viewModel.resetRenameState()
        } else if (rs is PhotoViewerViewModel.RenameState.NeedsPermission) {
            renamePermissionLauncher.launch(
                IntentSenderRequest.Builder(rs.pendingIntent.intentSender).build()
            )
        }
    }

    // ── Add to Album sheet ────────────────────────────────────────────────────────
    if (showAddToAlbumSheet) {
        val settledItem = items.getOrNull(pagerState.settledPage)
        val currentPhotoAlbumIds by viewModel.currentPhotoAlbumIds.collectAsStateWithLifecycle()
        val hasCloud = settledItem is GalleryItem.Synced || settledItem is GalleryItem.CloudOnly
        // Refresh membership for the current photo every time the sheet opens — fast on cache
        // hit (5-min TTL in AlbumService) and self-heals if the user removed the photo from an
        // album on Drive web between sheet opens.
        LaunchedEffect(showAddToAlbumSheet, settledItem) {
            if (settledItem != null && hasCloud) viewModel.loadCurrentPhotoAlbumIds(settledItem)
        }
        AddToAlbumSheet(
            sheetState = addToAlbumSheetState,
            cloudAlbums = albums,
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
        )
    }

    // ── Share drawer ────────────────────────────────────────────────────────────
    if (showShareSheet) {
        val settledItem = items.getOrNull(pagerState.settledPage)
        // Public link only exists for cloud-backed photos; a LocalOnly item shows the
        // "back up first" note instead of opening the manage-link sheet.
        val canCreateLink = settledItem is GalleryItem.Synced ||
            settledItem is GalleryItem.CloudOnly
        PhotoShareSheet(
            sheetState = shareSheetState,
            canCreateLink = canCreateLink,
            localUploadEnabled = true,
            onDismiss = { showShareSheet = false },
            onSendToApp = {
                showShareSheet = false
                settledItem?.let { viewModel.shareItem(it) }
            },
            onShareWithPeople = {
                // Proton shares photos with people by adding them to a shared album, so
                // this hands off to the viewer's existing add-to-album sheet.
                showShareSheet = false
                showAddToAlbumSheet = true
            },
            onManagePublicLink = {
                // Hand off to the dedicated manage-link sheet. The public-link lookup was
                // already kicked off when the drawer opened (loadPublicLink), so the manage
                // sheet renders the current state immediately.
                showShareSheet = false
                showManageLinkSheet = true
            },
        )
    }

    // ── Manage public link sheet ─────────────────────────────────────────────────
    if (showManageLinkSheet) {
        val settledItem = items.getOrNull(pagerState.settledPage)
        ManagePublicLinkSheet(
            sheetState = manageLinkSheetState,
            publicLinkState = publicLinkState,
            onDismiss = { showManageLinkSheet = false },
            onCreateLink = { viewModel.createPublicLink() },
            needsUpload = settledItem is GalleryItem.LocalOnly,
            onUploadAndCreate = { settledItem?.let { viewModel.uploadAndCreateViewedLink(it) } },
            onCopyLink = {
                viewModel.currentPublicLinkUrl()?.let { url ->
                    clipboard.setText(AnnotatedString(url))
                    scope.launch { snackbarHostState.showSnackbar(linkCopiedMsg) }
                }
            },
            onRemoveLink = { viewModel.revokePublicLink() },
            onSetPassword = { password ->
                viewModel.setLinkPassword(password)
                // Confirm the change; a failure still surfaces in the sheet's Error state.
                val msg = if (password.isNullOrBlank()) passwordRemovedMsg else passwordSetMsg
                scope.launch { snackbarHostState.showSnackbar(msg) }
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
