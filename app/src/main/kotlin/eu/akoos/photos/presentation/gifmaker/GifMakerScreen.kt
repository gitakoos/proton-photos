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

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package eu.akoos.photos.presentation.gifmaker

import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.decoderFallbackRenderersFactory
import eu.akoos.photos.presentation.common.rememberVideoFilmstripFrames
import eu.akoos.photos.presentation.editor.components.VideoFilmstripTrimmer
import eu.akoos.photos.presentation.gallery.FilterChip
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.util.formatVideoTime
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Longest edge of the preview's output canvas. Matches the exporter's own edge so the canvas aspect and the
 * framing ratios are identical, keeping the preview pixel-faithful to the saved GIF.
 */
private const val GIF_PREVIEW_MAX_EDGE = 480

/** The two tools in the GIF maker's bottom dock, mirroring the video editor's tab dock. */
private enum class GifTool(val labelRes: Int, val icon: ImageVector) {
    Duration(R.string.viewer_meta_row_duration, Icons.Default.Timer),
    Aspect(R.string.collage_tab_ratio, Icons.Default.AspectRatio),
}

/**
 * Standalone GIF maker. Loops the chosen sub-range of the source video in a muted preview so the user
 * sees exactly what will be captured, lets them trim a window of up to 10 seconds on a filmstrip range
 * bar, and saves the result to the gallery as an animated GIF. The source is a device video (guest
 * included, no account involved) or a cloud-only video downloaded first the way the video editor's cloud
 * path does. The chrome mirrors the video editor: a top-right Save pill and a bottom tool dock with
 * animated tabs.
 */
@Composable
fun GifMakerScreen(
    onBack: () -> Unit,
    videoUri: String? = null,
    cloudPhoto: CloudPhoto? = null,
    viewModel: GifMakerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Seed the source once: a device video plays from its URI, a cloud-only video downloads first
    // (the account-gated path GifMakerViewModel.loadCloud runs).
    LaunchedEffect(Unit) {
        if (cloudPhoto != null) viewModel.loadCloud(cloudPhoto)
        else if (videoUri != null) viewModel.load(videoUri)
    }

    // A local source resolves to its passed URI immediately (no download, no prepare flash); a cloud
    // source resolves only once loadCloud has written the downloaded file's URI into state.
    val resolvedUri = state.videoUri ?: videoUri
    // A cloud download that fails (or a missing account) surfaces a one-shot failure before any source
    // is resolved: toast and leave, so the prepare spinner never hangs. Once a source exists, the save
    // runs in the background off the screen, so there is no in-screen export outcome to surface.
    LaunchedEffect(state.exportResult, resolvedUri) {
        if (resolvedUri == null) {
            (state.exportResult as? GifExportResult.Failed)?.let {
                Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
                viewModel.consumeExportResult()
                onBack()
            }
        }
    }

    when {
        // Cloud source: wait on the download with a (determinate) spinner rather than flashing content.
        cloudPhoto != null && (state.videoUri == null || state.isDownloading) ->
            GifMakerPreparing(progress = state.downloadProgress, onBack = onBack)
        // A resolved source (a device URI right away, or the downloaded cloud file) gets the working UI.
        resolvedUri != null ->
            GifMakerContent(videoUri = resolvedUri, state = state, viewModel = viewModel, onBack = onBack)
        // Neither a URI nor a cloud photo: nothing to make a GIF from.
        else -> LaunchedEffect(Unit) { onBack() }
    }
}

/**
 * The GIF maker's working UI, shown once a source video URI is resolved (a device file, or a downloaded
 * cloud video). Owns the muted preview player, the filmstrip trim panel, and the export action.
 */
@Composable
private fun GifMakerContent(
    videoUri: String,
    state: GifMakerUiState,
    viewModel: GifMakerViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var activeTool by remember { mutableStateOf(GifTool.Duration) }
    var showQualitySheet by remember { mutableStateOf(false) }

    // Save runs the encode in the background and pops the screen at once: it surfaces in the avatar ring
    // and the Activity screen through the TransferCenter, so there is no in-screen export outcome to show
    // and the screen simply leaves, matching the video and photo editors.
    LaunchedEffect(Unit) {
        viewModel.leave.collect { onBack() }
    }

    // A muted preview player for the source. Looping is handled below against the selected range, so its
    // own repeat mode is off.
    val previewPlayer = remember(videoUri) {
        ExoPlayer.Builder(context, decoderFallbackRenderersFactory(context)).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))
            prepare()
            playWhenReady = true
            volume = 0f
            repeatMode = ExoPlayer.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(previewPlayer) {
        onDispose { previewPlayer.release() }
    }

    // Loop the selected window: poll the position and seek back to the start once it runs past the end
    // (or sits before the start). Keyed only on the player so a range change does not restart the loop;
    // the live start/end are read fresh each tick.
    val liveStart by rememberUpdatedState(state.gifStartMs)
    val liveEnd by rememberUpdatedState(state.gifEndMs)
    LaunchedEffect(previewPlayer) {
        while (true) {
            val pos = previewPlayer.currentPosition
            val start = liveStart
            val end = liveEnd
            if (end > start && (pos >= end || pos < start)) {
                previewPlayer.seekTo(start)
            }
            delay(33)
        }
    }
    // Moving the START of the window scrubs the preview to it, so the user sees the new first frame. The
    // end handle only changes where the loop turns around, so it does not reseek here.
    LaunchedEffect(state.gifStartMs) {
        if (state.durationMs > 0L) previewPlayer.seekTo(state.gifStartMs)
    }

    // Filmstrip frames come from the shared extractor (same source the video editor's trim strip uses), so
    // switching tools does not throw the bitmaps away and re-extract. 320 px keeps the 64dp strip crisp.
    val filmstrip = rememberVideoFilmstripFrames(
        uri = state.videoUri?.let { Uri.parse(it) },
        frameCount = 12,
        targetPx = 320,
        fallbackDurationMs = state.durationMs,
    )
    val filmstripThumbnails = filmstrip.frames
    DisposableEffect(Unit) {
        onDispose {
            filmstrip.release()
            Thread { System.gc() }.start()
        }
    }

    Column(
        Modifier.fillMaxSize().background(Bg0).statusBarsPadding(),
    ) {
        GifTopBar(
            title = stringResource(R.string.gif_maker_title),
            onBack = onBack,
            onSave = { showQualitySheet = true },
        )

        GifCropPreview(
            player = previewPlayer,
            sourceWidth = state.sourceWidth,
            sourceHeight = state.sourceHeight,
            aspect = state.aspect,
            zoom = state.cropZoom,
            panX = state.panX,
            panY = state.panY,
            onGesture = viewModel::applyCropGesture,
            onReset = viewModel::resetCrop,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // ── Bottom tool dock ─────────────────────────────────────────────────
        // Independent pill containers floating over Bg0, matching the video editor: an animated panel
        // that swaps between the tools, then a single PillBgOpaque capsule with circle icon tabs.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).wrapContentHeight(),
            ) {
                AnimatedContent(
                    targetState = activeTool,
                    transitionSpec = {
                        (fadeIn(tween(190)) + slideInVertically(tween(230)) { it / 5 }) togetherWith
                            (fadeOut(tween(150)) + slideOutVertically(tween(190)) { it / 5 })
                    },
                    label = "gifPanel",
                ) { tool ->
                    when (tool) {
                        GifTool.Duration -> GifDurationPanel(
                            state = state,
                            viewModel = viewModel,
                            previewPlayer = previewPlayer,
                            thumbnails = filmstripThumbnails,
                        )
                        GifTool.Aspect -> GifAspectPanel(state = state, viewModel = viewModel)
                    }
                }
            }

            // Bottom tab bar: a single capsule pill with 44dp circle tabs inside.
            Row(
                modifier = Modifier
                    .background(PillBgOpaque, RoundedCornerShape(999.dp))
                    .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GifTool.entries.forEach { tool ->
                    GifToolTab(
                        tool = tool,
                        selected = tool == activeTool,
                        onClick = { activeTool = tool },
                    )
                }
            }
        }
    }

    if (showQualitySheet) {
        GifQualitySheet(
            onDismiss = { showQualitySheet = false },
            onPick = { edgePx ->
                showQualitySheet = false
                viewModel.save(edgePx)
            },
        )
    }
}

/**
 * Save-time GIF resolution chooser. The longest-edge options are plain pixel counts (no localisation), with a
 * translated title; a larger edge is sharper but a much bigger file, since a GIF is 256 colours per frame at
 * any size. Picking one closes the sheet and starts the background save at that resolution.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GifQualitySheet(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Bg2,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.gif_quality_title),
                color = FgPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            GifQualityRow("480 px", onClick = { onPick(GIF_EDGE_STANDARD) })
            GifQualityRow("720 px", onClick = { onPick(GIF_EDGE_HIGH) })
            GifQualityRow("1080 px", onClick = { onPick(GIF_EDGE_MAX) })
        }
    }
}

@Composable
private fun GifQualityRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PillBgOpaque)
            .border(0.5.dp, PillBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(label, color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Shown while a cloud-only source video downloads (and before its URI is resolved): a back affordance and
 * a centered spinner that becomes determinate once the download reports a total, so the wait reads as
 * progress rather than a hang. Mirrors the viewer's download indicator wording.
 */
@Composable
private fun GifMakerPreparing(progress: Float?, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Bg0).statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBubble(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.onboarding_back),
                onClick = onBack,
                iconSize = 20.dp,
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        color = Accent,
                    )
                } else {
                    CircularProgressIndicator(color = Accent)
                }
                Text(
                    stringResource(R.string.viewer_downloading),
                    color = FgDim,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

// ─── Top bar ─────────────────────────────────────────────────────────────────

@Composable
private fun GifTopBar(
    title: String,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.onboarding_back),
            onClick = onBack,
            iconSize = 20.dp,
        )
        Box(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                color = FgPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        GifSavePill(onClick = onSave)
    }
}

@Composable
private fun GifSavePill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.action_save), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GifToolTab(tool: GifTool, selected: Boolean, onClick: () -> Unit) {
    val label = LocalContext.current.getString(tool.labelRes)
    // The selection fills in and the icon springs up a touch, so switching tools reads as a
    // deliberate move rather than an instant swap.
    val bgAlpha by animateFloatAsState(if (selected) 0.22f else 0f, tween(200), label = "gif_tab_bg")
    val tint by animateColorAsState(if (selected) Accent else FgDim, tween(200), label = "gif_tab_tint")
    val scale by animateFloatAsState(
        if (selected) 1f else 0.88f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "gif_tab_scale",
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Accent.copy(alpha = bgAlpha), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = tool.icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

// ─── Tool panels ─────────────────────────────────────────────────────────────

/**
 * Duration panel: the start / accent-duration / end time readout above the shared filmstrip range bar,
 * mirroring the video editor's trim panel. [allowWindowDrag] lets the user press-hold the selection and
 * slide the whole window at a fixed span. The 10-second span cap lives in the view model, so the far
 * handle simply stops there.
 */
@Composable
private fun GifDurationPanel(
    state: GifMakerUiState,
    viewModel: GifMakerViewModel,
    previewPlayer: ExoPlayer,
    thumbnails: List<Bitmap?>,
) {
    val duration = state.durationMs.coerceAtLeast(1L)
    // Poll the player position at ~30 fps so the playhead line tracks playback. Suppress the poll while a
    // scrub is in flight so it does not fight the live seek. Keyed on the player so a fresh load restarts it.
    var playheadMs by remember(previewPlayer) { mutableStateOf(0L) }
    var isScrubbing by remember(previewPlayer) { mutableStateOf(false) }
    LaunchedEffect(previewPlayer) {
        while (true) {
            if (!isScrubbing) playheadMs = previewPlayer.currentPosition
            delay(33)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GifTimePill(formatVideoTime(withTenths = true, ms = state.gifStartMs))
            GifTimePill(formatVideoTime(withTenths = true, ms = state.selectedMs), highlight = true)
            GifTimePill(formatVideoTime(withTenths = true, ms = state.gifEndMs))
        }
        VideoFilmstripTrimmer(
            durationMs = duration,
            trimStartMs = state.gifStartMs,
            trimEndMs = state.gifEndMs,
            playheadMs = playheadMs,
            thumbnails = thumbnails,
            onTrimChange = { start, end -> viewModel.setGifRange(start, end) },
            onScrubStart = { isScrubbing = true },
            onScrubEnd = { isScrubbing = false },
            onScrubMs = { ms ->
                playheadMs = ms
                previewPlayer.seekTo(ms)
            },
            allowWindowDrag = true,
        )
    }
}

/** Aspect panel: the output shape, the source's own ratio or a 1:1 square. Switching resets the crop. */
@Composable
private fun GifAspectPanel(state: GifMakerUiState, viewModel: GifMakerViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        FilterChip(
            label = stringResource(R.string.gif_maker_aspect_original),
            selected = state.aspect == GifAspect.ORIGINAL,
            onClick = { viewModel.setAspect(GifAspect.ORIGINAL) },
            accentWhenSelected = true,
        )
        FilterChip(
            label = stringResource(R.string.gif_maker_aspect_square),
            selected = state.aspect == GifAspect.SQUARE,
            onClick = { viewModel.setAspect(GifAspect.SQUARE) },
            accentWhenSelected = true,
        )
    }
}

/**
 * Time-readout pill above the filmstrip. Two-tone treatment: regular start/end pills use the dim
 * panel-chip background; the centered duration pill uses the accent tint so the eye lands on the
 * selected clip's length at a glance. Mirrors the video editor's TimePill.
 */
@Composable
private fun GifTimePill(text: String, highlight: Boolean = false) {
    Box(
        modifier = Modifier
            .background(
                if (highlight) Accent.copy(alpha = 0.22f) else PillBgOpaque,
                RoundedCornerShape(999.dp),
            )
            .border(
                0.5.dp,
                if (highlight) Accent.copy(alpha = 0.45f) else PillBorder,
                RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (highlight) Accent else FgPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * WYSIWYG crop preview. It presents the exported GIF's framing: the output canvas from [gifCanvasFor] is fit
 * into the box as a bright window, the source video is drawn behind it transformed exactly as [gifFraming]
 * maps source pixels onto that canvas, and everything outside the window is dimmed so the kept area reads
 * against the cropped-out area. Pinch to zoom and drag to pan feed [onGesture] (the pan is normalised by the
 * box size); black inside the window mirrors the exporter's black padding. [onReset] clears the crop and is
 * offered once it has moved.
 */
@Composable
private fun GifCropPreview(
    player: ExoPlayer,
    sourceWidth: Int,
    sourceHeight: Int,
    aspect: GifAspect,
    zoom: Float,
    panX: Float,
    panY: Float,
    onGesture: (Float, Float, Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Bg0),
    ) {
        val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        if (sourceWidth <= 0 || sourceHeight <= 0) {
            // Source size not read yet: fall back to the plain fitted player until it arrives.
            GifPlayerView(player = player, modifier = Modifier.fillMaxSize())
            return@BoxWithConstraints
        }

        val density = LocalDensity.current
        val (canvasW, canvasH) = gifCanvasFor(sourceWidth, sourceHeight, aspect, GIF_PREVIEW_MAX_EDGE)
        val framing = gifFraming(sourceWidth, sourceHeight, canvasW, canvasH, zoom, panX, panY)

        // Recover the whole source frame's placement on the canvas from the clamped framing, so the preview
        // draws with the SAME geometry the exporter uses (pixel-faithful WYSIWYG). canvasPerSrc is the shared
        // scale in canvas pixels per source pixel; the full frame's top-left may fall outside the canvas.
        val srcRegionW = (framing.srcRight - framing.srcLeft).coerceAtLeast(0.001f)
        val canvasPerSrc = (framing.dstRight - framing.dstLeft) / srcRegionW
        val fullLeftC = framing.dstLeft - framing.srcLeft * canvasPerSrc
        val fullTopC = framing.dstTop - framing.srcTop * canvasPerSrc
        val fullWidthC = sourceWidth * canvasPerSrc
        val fullHeightC = sourceHeight * canvasPerSrc

        // Fit the canvas aspect inside the box: the bright output window.
        val canvasAspect = canvasW.toFloat() / canvasH.toFloat()
        val fitByHeight = boxW / boxH > canvasAspect
        val winW = if (fitByHeight) boxH * canvasAspect else boxW
        val winH = if (fitByHeight) boxH else boxW / canvasAspect
        val winLeft = (boxW - winW) / 2f
        val winTop = (boxH - winH) / 2f
        val displayScale = winW / canvasW.toFloat()

        // The source frame rectangle in screen (box) pixels.
        val vLeft = winLeft + fullLeftC * displayScale
        val vTop = winTop + fullTopC * displayScale
        val vW = fullWidthC * displayScale
        val vH = fullHeightC * displayScale

        // 1) Black canvas backdrop, so any window area the frame does not cover reads as the exporter's
        //    black padding (the square letterbox bars at zoom 1).
        Box(
            modifier = Modifier
                .offset { IntOffset(winLeft.roundToInt(), winTop.roundToInt()) }
                .size(with(density) { winW.toDp() }, with(density) { winH.toDp() })
                .background(Color.Black),
        )
        // 2) The source video, scaled and positioned to match the canvas mapping; overflow is clipped by the
        //    rounded box and dimmed by the scrim below.
        GifPlayerView(
            player = player,
            modifier = Modifier
                .offset { IntOffset(vLeft.roundToInt(), vTop.roundToInt()) }
                .requiredWidth(with(density) { vW.toDp() })
                .requiredHeight(with(density) { vH.toDp() }),
        )
        // 3) Dim everything outside the window; the window stays bright. Mirrors the video editor crop mask.
        val scrim = Color.Black.copy(alpha = 0.55f)
        val accent = Accent
        val borderPx = with(density) { 1.5.dp.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val right = winLeft + winW
            val bottom = winTop + winH
            drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, winTop.coerceAtLeast(0f)))
            drawRect(
                scrim,
                topLeft = Offset(0f, bottom),
                size = Size(size.width, (size.height - bottom).coerceAtLeast(0f)),
            )
            drawRect(scrim, topLeft = Offset(0f, winTop), size = Size(winLeft.coerceAtLeast(0f), winH))
            drawRect(
                scrim,
                topLeft = Offset(right, winTop),
                size = Size((size.width - right).coerceAtLeast(0f), winH),
            )
            drawRect(
                color = accent,
                topLeft = Offset(winLeft, winTop),
                size = Size(winW, winH),
                style = Stroke(width = borderPx),
            )
        }
        // 4) Pinch to zoom, drag to pan. The pan tracks the finger 1:1: a drag moves the frame by the same
        //    number of pixels, by normalising the delta against the current overshoot (how far the scaled
        //    frame spills past the window) rather than the box size, which made a zoomed drag crawl.
        //    rememberUpdatedState keeps the latest overshoot readable inside the long-lived gesture lambda
        //    (pointerInput is keyed only on the box size, so it is not torn down mid-gesture as zoom changes).
        val overshootXpx by rememberUpdatedState(vW - winW)
        val overshootYpx by rememberUpdatedState(vH - winH)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(boxW, boxH) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        // Pan fraction only when the axis actually overflows the window; otherwise a drag at
                        // zoom 1 would silently max out the stored pan. 2x because pan -1..1 spans the whole
                        // overshoot while the offset is applied as pan * overshoot / 2.
                        val ox = overshootXpx
                        val oy = overshootYpx
                        val fx = if (ox > 1f) 2f * pan.x / ox else 0f
                        val fy = if (oy > 1f) 2f * pan.y / oy else 0f
                        onGesture(gestureZoom, fx, fy)
                    }
                },
        )
        // 5) Reset the crop, offered once it has moved off the default framing.
        if (zoom != 1f || panX != 0f || panY != 0f) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                FilterChip(
                    label = stringResource(R.string.video_editor_reset_crop),
                    selected = false,
                    onClick = onReset,
                )
            }
        }
    }
}

/** The muted source preview surface: a texture-backed PlayerView with no controls. */
@Composable
private fun GifPlayerView(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.video_editor_player, null) as PlayerView).apply {
                this.player = player
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view -> view.player = player },
        modifier = modifier,
    )
}
