package eu.akoos.photos.presentation.editor

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PanelBg
import eu.akoos.photos.presentation.theme.PanelChip
import eu.akoos.photos.presentation.theme.TrackBg
import kotlin.math.max
import kotlin.math.min

private enum class VideoTool(val labelRes: Int, val icon: ImageVector) {
    Trim(R.string.video_editor_trim, Icons.Default.ContentCut),
    Crop(R.string.video_editor_crop, Icons.Default.Crop),
    Rotate(R.string.video_editor_rotate, Icons.AutoMirrored.Filled.RotateRight),
    Audio(R.string.video_editor_audio, Icons.Default.MusicNote),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen(
    localUri: String?,
    localDisplayName: String?,
    localMimeType: String?,
    /** Non-null routes the editor through the cloud flow — VM downloads, edits, re-uploads. */
    cloudPhoto: eu.akoos.photos.domain.entity.CloudPhoto? = null,
    /** The album linkId to re-attach the re-uploaded edit to (if the cloud video came from
     *  an album view). Null = sit at the photo timeline root. */
    sourceAlbumLinkId: String? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    vm: VideoEditorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var activeTool by remember { mutableStateOf(VideoTool.Trim) }
    var showSaveSheet by remember { mutableStateOf(false) }
    val saveSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(localUri, cloudPhoto?.linkId) {
        when {
            cloudPhoto != null -> vm.loadCloud(cloudPhoto, sourceAlbumLinkId)
            localUri != null   -> vm.loadLocal(localUri, localDisplayName ?: "video.mp4", localMimeType ?: "video/mp4")
        }
    }

    // Side effects on save completion. LaunchedEffect rather than remember{} so the
    // body runs OUTSIDE composition — calling navController.popBackStack() (via onSaved)
    // during composition leaves the ModalBottomSheet half-dismissed and the user has to
    // re-tap save. Firing the effect after composition lets the sheet animate out as the
    // screen pops normally.
    androidx.compose.runtime.LaunchedEffect(state.saveResult) {
        when (val r = state.saveResult) {
            is VideoSaveResult.Success -> {
                showSaveSheet = false
                vm.consumeSaveResult()
                onSaved()
            }
            is VideoSaveResult.SuccessAsCopy -> {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.editor_saved_as_copy_toast),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                showSaveSheet = false
                vm.consumeSaveResult()
                onSaved()
            }
            is VideoSaveResult.Failed -> {
                // Surface the failure with a toast and dismiss the dialog so the user
                // isn't stuck in an "endless save dialog" loop. The Failed result is
                // also rendered inline below the panel for the next entry.
                if (r.message != "__pending_write_permission__") {
                    android.widget.Toast.makeText(
                        context,
                        "Save failed: ${r.message}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    showSaveSheet = false
                    vm.consumeSaveResult()
                }
            }
            null -> Unit
        }
    }

    // System consent dialog for overwriting foreign MediaStore URIs — same pattern as
    // PhotoEditorScreen. The video VM surfaces a PendingIntent the first time an
    // Overwrite on a camera-roll item throws SecurityException; we launch it and feed
    // the user's choice back into onWritePermission{Granted,Denied}.
    val writePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) vm.onWritePermissionGranted()
        else vm.onWritePermissionDenied()
    }
    androidx.compose.runtime.LaunchedEffect(state.pendingWriteIntent) {
        val pi = state.pendingWriteIntent ?: return@LaunchedEffect
        writePermissionLauncher.launch(
            androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()
        )
    }

    // Hoisted ExoPlayer reference so the Trim / Audio slider drags can call seekTo() and
    // the user sees the frame at the handle position while they fine-tune the in/out
    // points. Captured into the screen's scope, NOT the VM's — the player belongs to the
    // composable and is released by VideoPreview's DisposableEffect when leaving.
    var previewPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    // Save is allowed when we have a source URI to edit — local OR a downloaded
    // cloud file. The cloud branch flips the URI on inside loadCloud once the file
    // lands in cache, so isLoading guards the early window.
    val hasSource = state.sourceUri != null && !state.isLoading

    Column(
        Modifier.fillMaxSize().background(Bg0).statusBarsPadding(),
    ) {
        VideoTopBar(
            title = state.displayName.ifBlank { "" },
            isSaving = state.isSaving,
            onBack = onBack,
            onSave = { if (hasSource) showSaveSheet = true },
        )

        // ── Preview area ─────────────────────────────────────────────────────
        // Crop tab swaps out the ExoPlayer for the first-frame bitmap so the user can
        // drag corners reliably. Pointer events on a video Surface are unreliable —
        // some devices route them to the video stack and we never see them in Compose.
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(color = Accent)
                state.errorMessage != null -> Text(state.errorMessage!!, color = FgMute)
                activeTool == VideoTool.Crop && state.sourceWidth > 0 && state.sourceHeight > 0 ->
                    CropFrameOverlay(
                        sourceUri = Uri.parse(state.sourceUri!!),
                        sourceWidth = state.sourceWidth,
                        sourceHeight = state.sourceHeight,
                        currentCrop = state.cropRect,
                        onCropChange = { vm.setCropRect(it) },
                    )
                else -> VideoPreview(
                    uri = Uri.parse(state.sourceUri!!),
                    rotationDegrees = state.rotationDegrees,
                    onPlayerReady = { previewPlayer = it },
                )
            }
        }

        // ── Play + speed pill, sits above the bottom panel ───────────────────
        if (hasSource && previewPlayer != null) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                VideoEditorPlayPill(player = previewPlayer!!)
            }
        }

        // ── Bottom panel ────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(PanelBg)
                .navigationBarsPadding()
                .padding(top = 14.dp, bottom = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                VideoTool.entries.forEach { tool ->
                    VideoToolTab(
                        tool = tool,
                        selected = tool == activeTool,
                        onClick = { activeTool = tool },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Box(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).wrapContentHeight(),
            ) {
                when (activeTool) {
                    VideoTool.Trim -> TrimPanel(state = state, vm = vm, previewPlayer = previewPlayer)
                    VideoTool.Crop -> CropPanel(state = state, vm = vm)
                    VideoTool.Rotate -> RotatePanel(state = state, vm = vm)
                    VideoTool.Audio -> AudioPanel(state = state, vm = vm, previewPlayer = previewPlayer)
                }
            }

            val saveResult = state.saveResult
            if (saveResult is VideoSaveResult.Failed) {
                Text(
                    text = saveResult.message,
                    color = Color(0xFFFF3B30),
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 18.dp, end = 18.dp),
                )
            }
        }
    }

    if (showSaveSheet && hasSource) {
        ModalBottomSheet(
            onDismissRequest = { if (!state.isSaving) showSaveSheet = false },
            sheetState = saveSheetState,
            containerColor = PanelBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            VideoSaveSheet(
                isSaving = state.isSaving,
                progress = state.saveProgress,
                isCloud = cloudPhoto != null,
                onPicked = { mode -> vm.save(mode) },
                onCancel = { if (!state.isSaving) showSaveSheet = false },
            )
        }
    }
}

// ─── Top bar ─────────────────────────────────────────────────────────────────

@Composable
private fun VideoTopBar(
    title: String,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, null,
                tint = FgPrimary, modifier = Modifier.size(20.dp),
            )
        }
        Box(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.ifBlank { "" },
                color = FgPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        VideoSavePill(isSaving = isSaving, onClick = onSave)
    }
}

@Composable
private fun VideoSavePill(isSaving: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Accent)
            .clickable(enabled = !isSaving, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text("Save", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun IconBubble(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(PanelChip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun VideoToolTab(tool: VideoTool, selected: Boolean, onClick: () -> Unit) {
    val label = LocalContext.current.getString(tool.labelRes)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = tool.icon,
            contentDescription = label,
            tint = if (selected) Accent else FgDim,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = if (selected) Accent else FgDim,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ─── Video preview (ExoPlayer) ───────────────────────────────────────────────

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPreview(
    uri: Uri,
    /** Editor-applied rotation in 90° steps; the player view is rotated client-side via
     *  graphicsLayer so the user sees the effect immediately (the muxer's orientation hint
     *  applies on save, not in the live preview surface). */
    rotationDegrees: Int = 0,
    /** Bubbled up to the screen so the trim slider can seek the live preview as the user
     *  drags handles — without this hand-off the slider just updates state numbers while
     *  the on-screen frame stays stuck on whatever was playing. */
    onPlayerReady: (ExoPlayer) -> Unit = {},
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
        }.also { onPlayerReady(it) }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                // Our trim slider acts as the editor's playhead — the native PlayerView
                // controls (play/pause overlay + seekbar) just duplicated that and ate
                // touches that were meant for the crop overlay. The Compose layer below
                // handles tap-to-pause and trim-scrub explicitly.
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                hideController()
                controllerAutoShow = false
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
                useArtwork = false
                setDefaultArtwork(null)
            }
        },
        update = { view -> view.player = player },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(rotationZ = rotationDegrees.toFloat()),
    )
}

// ─── Crop overlay ────────────────────────────────────────────────────────────

/**
 * Renders the source video's first frame as a bitmap and overlays four draggable
 * corner handles. The user's crop rect lives in source-pixel coordinates; we convert
 * between screen space and source space using a fit-rect (letterbox-aware).
 *
 * Gestures: a drag starts by picking the closest handle; subsequent moves drag that
 * handle while preserving a minimum size. The dark semi-opaque mask paints everything
 * OUTSIDE the crop rect to make the focus clear.
 */
@Composable
private fun CropFrameOverlay(
    sourceUri: Uri,
    sourceWidth: Int,
    sourceHeight: Int,
    currentCrop: AndroidRect?,
    onCropChange: (AndroidRect) -> Unit,
) {
    val context = LocalContext.current
    val firstFrame: Bitmap? = remember(sourceUri) {
        val retriever = MediaMetadataRetriever()
        val bmp = runCatching {
            retriever.setDataSource(context, sourceUri)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull()
        runCatching { retriever.release() }
        bmp
    }

    val srcW = sourceWidth.coerceAtLeast(1)
    val srcH = sourceHeight.coerceAtLeast(1)

    // Local pending crop — the panel "Apply" button commits to the VM; while the user
    // is fine-tuning, we keep gestures snappy without hammering the StateFlow.
    var pending by remember(currentCrop, srcW, srcH) {
        mutableStateOf(currentCrop ?: AndroidRect(0, 0, srcW, srcH))
    }
    LaunchedEffect(pending, currentCrop) {
        // Re-derive when external state changes (e.g. reset).
        if (currentCrop != null && currentCrop != pending) pending = currentCrop
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier.fillMaxSize().onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (firstFrame != null) {
            Image(
                bitmap = firstFrame.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        // Compute the fit-rect of the source frame inside the container. All crop math
        // happens via this transform so the handles stay anchored to the video frame
        // even when the container resizes.
        val fit = remember(srcW, srcH, containerSize) {
            fitRectFor(srcW.toFloat(), srcH.toFloat(),
                containerSize.width.toFloat().coerceAtLeast(1f),
                containerSize.height.toFloat().coerceAtLeast(1f))
        }

        val density = LocalDensity.current
        val handleRadiusPx = with(density) { 14.dp.toPx() }
        // Same widening as the photo crop tool — 28.dp was tighter than a fingertip so
        // most touches that aimed at the top/bottom edge fell outside the hit-circle and
        // failed to grab any handle, leaving the user able to drag only inside the rect
        // (no resize). 56.dp gives a comfortable slop while keeping the marker compact.
        val touchRadiusPx = with(density) { 56.dp.toPx() }

        val accent = Accent
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(srcW, srcH, fit) {
                    // Track which handle is being dragged so a fast move past another
                    // corner doesn't snap to the wrong one.
                    var grabbedHandle: Handle? = null
                    detectDragGestures(
                        onDragStart = { offset ->
                            grabbedHandle = pickClosestHandle(pending, fit, offset, touchRadiusPx)
                        },
                        onDrag = { change, _ ->
                            val h = grabbedHandle ?: return@detectDragGestures
                            val srcX = ((change.position.x - fit.offsetX) / fit.scale)
                                .coerceIn(0f, srcW.toFloat()).toInt()
                            val srcY = ((change.position.y - fit.offsetY) / fit.scale)
                                .coerceIn(0f, srcH.toFloat()).toInt()
                            val minSize = 32 // pixels — keep handles spread apart
                            val newRect = when (h) {
                                Handle.TopLeft -> AndroidRect(
                                    srcX.coerceAtMost(pending.right - minSize),
                                    srcY.coerceAtMost(pending.bottom - minSize),
                                    pending.right, pending.bottom,
                                )
                                Handle.TopRight -> AndroidRect(
                                    pending.left,
                                    srcY.coerceAtMost(pending.bottom - minSize),
                                    srcX.coerceAtLeast(pending.left + minSize),
                                    pending.bottom,
                                )
                                Handle.BottomLeft -> AndroidRect(
                                    srcX.coerceAtMost(pending.right - minSize),
                                    pending.top,
                                    pending.right,
                                    srcY.coerceAtLeast(pending.top + minSize),
                                )
                                Handle.BottomRight -> AndroidRect(
                                    pending.left,
                                    pending.top,
                                    srcX.coerceAtLeast(pending.left + minSize),
                                    srcY.coerceAtLeast(pending.top + minSize),
                                )
                            }
                            pending = newRect
                            onCropChange(newRect)
                            change.consume()
                        },
                        onDragEnd = { grabbedHandle = null },
                        onDragCancel = { grabbedHandle = null },
                    )
                },
        ) {
            // Crop rect in screen coords
            val l = fit.offsetX + pending.left * fit.scale
            val t = fit.offsetY + pending.top * fit.scale
            val r = fit.offsetX + pending.right * fit.scale
            val b = fit.offsetY + pending.bottom * fit.scale

            // Semi-opaque mask outside the crop
            val maskColor = Color.Black.copy(alpha = 0.55f)
            // Top
            drawRect(maskColor, topLeft = Offset(0f, 0f), size = GSize(size.width, t))
            // Bottom
            drawRect(maskColor, topLeft = Offset(0f, b), size = GSize(size.width, size.height - b))
            // Left
            drawRect(maskColor, topLeft = Offset(0f, t), size = GSize(l, b - t))
            // Right
            drawRect(maskColor, topLeft = Offset(r, t), size = GSize(size.width - r, b - t))

            // Border around the crop
            val strokeWidthPx = 2f * density.density
            drawLine(accent, Offset(l, t), Offset(r, t), strokeWidth = strokeWidthPx)
            drawLine(accent, Offset(r, t), Offset(r, b), strokeWidth = strokeWidthPx)
            drawLine(accent, Offset(r, b), Offset(l, b), strokeWidth = strokeWidthPx)
            drawLine(accent, Offset(l, b), Offset(l, t), strokeWidth = strokeWidthPx)

            // Corner squares (white, accent border)
            for ((cx, cy) in listOf(l to t, r to t, l to b, r to b)) {
                drawRect(
                    color = Color.White,
                    topLeft = Offset(cx - handleRadiusPx / 2f, cy - handleRadiusPx / 2f),
                    size = GSize(handleRadiusPx, handleRadiusPx),
                )
                drawRect(
                    color = accent,
                    topLeft = Offset(cx - handleRadiusPx / 2f, cy - handleRadiusPx / 2f),
                    size = GSize(handleRadiusPx, handleRadiusPx),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f * density.density),
                )
            }
        }
    }
}

private enum class Handle { TopLeft, TopRight, BottomLeft, BottomRight }

private fun pickClosestHandle(
    rect: AndroidRect,
    fit: VideoFit,
    point: Offset,
    touchRadiusPx: Float,
): Handle? {
    val l = fit.offsetX + rect.left * fit.scale
    val t = fit.offsetY + rect.top * fit.scale
    val r = fit.offsetX + rect.right * fit.scale
    val b = fit.offsetY + rect.bottom * fit.scale
    data class Candidate(val handle: Handle, val pos: Offset)
    val candidates = listOf(
        Candidate(Handle.TopLeft, Offset(l, t)),
        Candidate(Handle.TopRight, Offset(r, t)),
        Candidate(Handle.BottomLeft, Offset(l, b)),
        Candidate(Handle.BottomRight, Offset(r, b)),
    )
    val nearest = candidates.minByOrNull { c ->
        val dx = c.pos.x - point.x
        val dy = c.pos.y - point.y
        dx * dx + dy * dy
    } ?: return null
    val dx = nearest.pos.x - point.x
    val dy = nearest.pos.y - point.y
    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
    return if (dist <= touchRadiusPx * 1.5f) nearest.handle else null
}

/**
 * Letterbox transform from source-pixel coords into the container's drawing area.
 * Kept private to this file under a unique name so it doesn't collide with the
 * identically-named helper in PhotoEditorScreen.
 */
private data class VideoFit(val scale: Float, val offsetX: Float, val offsetY: Float)

private fun fitRectFor(srcW: Float, srcH: Float, boxW: Float, boxH: Float): VideoFit {
    val scale = min(boxW / srcW, boxH / srcH)
    val drawnW = srcW * scale
    val drawnH = srcH * scale
    return VideoFit(scale, (boxW - drawnW) / 2f, (boxH - drawnH) / 2f)
}

// ─── Tool panels ─────────────────────────────────────────────────────────────

@Composable
private fun TrimPanel(state: VideoEditorUiState, vm: VideoEditorViewModel, previewPlayer: ExoPlayer?) {
    val duration = state.durationMs.coerceAtLeast(1L)
    // Poll the ExoPlayer's currentPosition at ~30 fps so the playhead line on the
    // filmstrip tracks playback smoothly. The remember key includes the player so a new
    // load (URI change) restarts the polling against the fresh instance.
    var playheadMs by remember(previewPlayer) { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(previewPlayer) {
        while (true) {
            val p = previewPlayer ?: break
            playheadMs = p.currentPosition.coerceIn(0L, duration)
            kotlinx.coroutines.delay(33)
        }
    }
    val sourceUriString = state.sourceUri
    val sourceUri = remember(sourceUriString) { sourceUriString?.let { Uri.parse(it) } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        VideoFilmstripTrimmer(
            sourceUri = sourceUri,
            durationMs = duration,
            trimStartMs = state.trimStartMs,
            trimEndMs = state.trimEndMs,
            playheadMs = playheadMs,
            onTrimChange = { start, end -> vm.setTrimRange(start, end) },
            onScrubMs = { ms ->
                playheadMs = ms
                previewPlayer?.seekTo(ms)
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    LocalContext.current.getString(R.string.video_editor_start),
                    color = FgMute, fontSize = 11.sp,
                )
                Text(
                    formatMs(state.trimStartMs),
                    color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    LocalContext.current.getString(R.string.video_editor_end),
                    color = FgMute, fontSize = 11.sp,
                )
                Text(
                    formatMs(state.trimEndMs),
                    color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CropPanel(state: VideoEditorUiState, vm: VideoEditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Drag the corners on the preview to set the crop.",
            color = FgMute, fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ActionChip(
                label = LocalContext.current.getString(R.string.video_editor_reset_crop),
                icon = Icons.Default.Restore,
                enabled = state.cropRect != null,
                onClick = { vm.setCropRect(null) },
                modifier = Modifier.weight(1f),
            )
            ActionChip(
                label = LocalContext.current.getString(R.string.video_editor_apply_crop),
                icon = Icons.Default.Check,
                enabled = state.cropRect != null,
                onClick = { /* Drag already commits; this chip is a visual confirm. */ },
                modifier = Modifier.weight(1f),
            )
        }
        if (state.cropRect != null) {
            Text(
                "${state.cropRect.width()} × ${state.cropRect.height()} px",
                color = FgDim, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun RotatePanel(state: VideoEditorUiState, vm: VideoEditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionTile(
                label = LocalContext.current.getString(R.string.video_editor_rotate),
                icon = Icons.AutoMirrored.Filled.RotateRight,
                onClick = { vm.rotate90Cw() },
            )
        }
        Text(
            text = "${state.rotationDegrees}°",
            color = FgMute, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun AudioPanel(state: VideoEditorUiState, vm: VideoEditorViewModel, previewPlayer: ExoPlayer?) {
    val context = LocalContext.current
    // Picker for an arbitrary audio file. GetContent shows the system file picker filtered
    // to audio/* — works for MediaStore items, SAF documents, and a number of cloud
    // providers that expose audio_provider URIs.
    val pickAudio = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = resolveDisplayName(context, uri) ?: "music"
        // Persist read perms so a re-encode that happens later still has access.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        vm.setAudioOverlay(uri.toString(), displayName)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── Row 1: original audio track (always shown, with mute toggle) ──
        // Sits above the overlay row so the user reads "original" first — matches the
        // order audio engineers think of mixers in. The toggle's effect at save time is
        // documented on VideoEditorUiState.muteOriginalAudio.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (state.muteOriginalAudio) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                null,
                tint = if (state.muteOriginalAudio) FgMute else Accent,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    context.getString(R.string.video_editor_audio_original),
                    color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    if (state.muteOriginalAudio)
                        context.getString(R.string.video_editor_audio_original_muted)
                    else
                        context.getString(R.string.video_editor_audio_original_on),
                    color = FgMute, fontSize = 11.sp,
                )
            }
            // Pill toggle — clicking flips the mute state.
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (state.muteOriginalAudio) Accent.copy(alpha = 0.25f) else PanelChip)
                    .clickable { vm.setMuteOriginalAudio(!state.muteOriginalAudio) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    context.getString(
                        if (state.muteOriginalAudio) R.string.video_editor_audio_muted
                        else R.string.video_editor_audio_mute
                    ),
                    color = FgPrimary, fontSize = 12.sp,
                )
            }
        }

        // ── Row 2: overlay music (add when empty, edit + remove when set) ──
        if (state.audioOverlayUri == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                ActionTile(
                    label = context.getString(R.string.video_editor_add_music),
                    icon = Icons.Default.MusicNote,
                    onClick = { pickAudio.launch("audio/*") },
                )
            }
            Text(
                context.getString(R.string.video_editor_audio_overlay_hint),
                color = FgMute, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(20.dp))
                Text(
                    state.audioOverlayDisplayName ?: "music",
                    color = FgPrimary, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(PanelChip)
                        .clickable { vm.clearAudioOverlay() }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Default.Close, null, tint = FgPrimary, modifier = Modifier.size(14.dp))
                    Text(
                        context.getString(R.string.video_editor_remove_music),
                        color = FgPrimary, fontSize = 12.sp,
                    )
                }
            }

            val totalAudio = state.audioOverlayDurationMs.coerceAtLeast(1L)
            RangeTrimSlider(
                durationMs = totalAudio,
                startMs = state.audioTrimStartMs,
                endMs = state.audioTrimEndMs,
                onChange = { start, end -> vm.setAudioTrimRange(start, end) },
                // Audio scrubbing maps the music's own timeline (0..audioDuration) onto
                // the video's playback head — for now scrub to the video's trim start
                // plus the audio offset so the user hears the slice they're picking in
                // context of where it lands in the final clip.
                onScrubMs = { audioMs ->
                    val videoOffset = state.trimStartMs + (audioMs - state.audioTrimStartMs).coerceAtLeast(0L)
                    previewPlayer?.seekTo(videoOffset.coerceIn(0L, state.durationMs))
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(context.getString(R.string.video_editor_music_start),
                        color = FgMute, fontSize = 11.sp)
                    Text(formatMs(state.audioTrimStartMs),
                        color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(context.getString(R.string.video_editor_music_end),
                        color = FgMute, fontSize = 11.sp)
                    Text(formatMs(state.audioTrimEndMs),
                        color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun resolveDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }.getOrNull()
}

/**
 * Dual-thumb trim slider drawn on a Compose Canvas. Used by both the video Trim panel
 * AND the Audio panel's music-trim row — they're functionally identical, just with
 * different total-duration scaling.
 */
@Composable
private fun RangeTrimSlider(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    onChange: (start: Long, end: Long) -> Unit,
    /** Called with the currently-dragged-handle's timestamp (in ms). Hosts use this to seek
     *  a live preview player to the cut point. Null = no scrub side-effect. */
    onScrubMs: ((Long) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val trackHeightPx = with(density) { 4.dp.toPx() }
    val thumbRadiusPx = with(density) { 10.dp.toPx() }
    val activeColor = Accent
    val trackColor = TrackBg
    var widthPx by remember { mutableFloatStateOf(0f) }
    var draggingStart by remember { mutableStateOf(true) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val startX = startMs.toFloat() / durationMs * w
                        val endX = endMs.toFloat() / durationMs * w
                        draggingStart = kotlin.math.abs(offset.x - startX) <=
                            kotlin.math.abs(offset.x - endX)
                        val pct = (offset.x / w).coerceIn(0f, 1f)
                        val ms = (pct * durationMs).toLong()
                        if (draggingStart) onChange(ms, endMs) else onChange(startMs, ms)
                        onScrubMs?.invoke(ms)
                    },
                    onDrag = { change, _ ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val pct = (change.position.x / w).coerceIn(0f, 1f)
                        val ms = (pct * durationMs).toLong()
                        if (draggingStart) onChange(ms, endMs) else onChange(startMs, ms)
                        onScrubMs?.invoke(ms)
                        change.consume()
                    },
                )
            },
    ) {
        val w = size.width
        val cy = size.height / 2f
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, cy - trackHeightPx / 2f),
            size = GSize(w, trackHeightPx),
            cornerRadius = CornerRadius(trackHeightPx / 2f),
        )
        val startX = (startMs.toFloat() / durationMs * w).coerceIn(0f, w)
        val endX = (endMs.toFloat() / durationMs * w).coerceIn(0f, w)
        val left = min(startX, endX)
        val right = max(startX, endX)
        drawRoundRect(
            color = activeColor,
            topLeft = Offset(left, cy - trackHeightPx / 2f),
            size = GSize(right - left, trackHeightPx),
            cornerRadius = CornerRadius(trackHeightPx / 2f),
        )
        for (x in listOf(startX, endX)) {
            drawCircle(color = activeColor, radius = thumbRadiusPx, center = Offset(x, cy))
            drawCircle(color = Color.White, radius = thumbRadiusPx - 3f, center = Offset(x, cy))
            drawCircle(color = activeColor, radius = thumbRadiusPx - 6f, center = Offset(x, cy))
        }
    }
}

// Pill capsule used by the editor's play+speed pill. Local definition because the
// viewer's identical val is file-private — duplicating one line saves an export.
private val editorPillShape = RoundedCornerShape(999.dp)

// ─── Editor play + speed pill ────────────────────────────────────────────────

/**
 * Single pill that contains the play/pause icon plus a speed control. The speed control
 * collapses to a small chip showing the current speed; tapping expands it horizontally
 * into a 4-button selector for 1/4× / 1/2× / 1× / 2×. The user picks → the chip
 * collapses again and the player's playback parameters update.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoEditorPlayPill(player: ExoPlayer) {
    var isPlaying by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            kotlinx.coroutines.delay(80)
        }
    }
    var speed by remember { mutableStateOf(1f) }
    var speedExpanded by remember { mutableStateOf(false) }
    val speedOptions = listOf(0.25f, 0.5f, 1f, 2f)

    Row(
        modifier = Modifier
            .background(PillBg, editorPillShape)
            .border(0.5.dp, PillBorder, editorPillShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Play/Pause
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).clickable {
                if (player.isPlaying) player.pause() else player.play()
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                null, tint = FgPrimary, modifier = Modifier.size(22.dp),
            )
        }
        // Speed control: collapsed pill shows current rate, tap expands into 4 chips.
        if (!speedExpanded) {
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(PanelChip)
                    .clickable { speedExpanded = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatSpeed(speed), color = FgPrimary, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium)
            }
        } else {
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(PanelChip)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                speedOptions.forEach { s ->
                    val selected = s == speed
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .height(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (selected) Accent else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable {
                                speed = s
                                player.setPlaybackSpeed(s)
                                speedExpanded = false
                            }
                            .padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            formatSpeed(s),
                            color = if (selected) androidx.compose.ui.graphics.Color.White else FgPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private fun formatSpeed(s: Float): String = when (s) {
    0.25f -> "¼×"
    0.5f -> "½×"
    1f -> "1×"
    2f -> "2×"
    else -> "${s}×"
}

// ─── Video filmstrip + playhead trim slider ──────────────────────────────────

/**
 * Trim widget that looks and feels like a tiny video editor — a strip of evenly-spaced
 * frame thumbnails as the track, two heavy bar-shaped handles at the edges of the
 * selected range, a darker mask over the trimmed-off ends, and a thin white playhead
 * line that follows the live ExoPlayer position. Dragging the playhead seeks the
 * preview; dragging an edge handle moves the trim in/out point.
 *
 * Thumbnails are extracted once per [sourceUri] via MediaMetadataRetriever — 12 frames
 * evenly across the full source duration. We deliberately don't reshuffle the strip when
 * the user moves the trim range; that would re-extract on every drag and tank the UX.
 */
@Composable
private fun VideoFilmstripTrimmer(
    sourceUri: Uri?,
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    playheadMs: Long,
    onTrimChange: (start: Long, end: Long) -> Unit,
    onScrubMs: (Long) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val handleWidthPx = with(density) { 14.dp.toPx() }
    // Generous hit-slop around each trim bar — 48dp is the platform minimum touch target
    // and it gives the user a comfortable margin to grab the start/end edges. The
    // playhead is only picked up when the touch is well inside the trimmed window so
    // the bars always win when the gesture starts near an edge.
    val touchSlopPx = with(density) { 48.dp.toPx() }
    val stripHeight = 64.dp

    // Frame thumbnail extraction. Runs once per source — keeps the strip stable while
    // the user fine-tunes the trim window. 12 evenly-spaced frames balances detail with
    // extraction cost on mid-range devices.
    var thumbnails by remember(sourceUri) { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(sourceUri, durationMs) {
        if (sourceUri == null || durationMs <= 0L) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val frames = mutableListOf<android.graphics.Bitmap>()
            runCatching { retriever.setDataSource(context, sourceUri) }
                .onFailure { runCatching { retriever.release() }; return@withContext }
            val count = 12
            for (i in 0 until count) {
                // Sample at the middle of each segment so the first/last frames aren't
                // black (some clips have a fade-in that yields an empty preview).
                val ratio = (i.toFloat() + 0.5f) / count
                val tUs = (ratio * durationMs * 1000L).toLong()
                val bmp = runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            tUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            96, 96,
                        )
                    } else {
                        // Android 8.0 path — getScaledFrameAtTime arrived in API 27. Pull the
                        // full frame and downscale to 96×96 ourselves so the filmstrip still
                        // populates instead of falling through to a blank row.
                        val full = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        full?.let { android.graphics.Bitmap.createScaledBitmap(it, 96, 96, true) }
                    }
                }.getOrNull()
                if (bmp != null) frames += bmp
            }
            runCatching { retriever.release() }
            thumbnails = frames
        }
    }

    var grabbed by remember { mutableStateOf<Grabbed?>(null) }
    var canvasWidthPx by remember { mutableFloatStateOf(1f) }
    // Capture composable colors out of the Canvas draw scope (Canvas's body is *not*
    // composable, so we can't read Accent there).
    val accentColor = Accent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(stripHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(TrackBg)
            .onSizeChanged { canvasWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(durationMs, trimStartMs, trimEndMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val startX = trimStartMs.toFloat() / durationMs * w
                        val endX = trimEndMs.toFloat() / durationMs * w
                        val dStart = kotlin.math.abs(offset.x - startX)
                        val dEnd = kotlin.math.abs(offset.x - endX)
                        // Edge bars always win when the touch is anywhere within their
                        // hit-slop, including from inside the active range — this fixes
                        // the "I'm trying to drag the bar but it scrubs instead" feel.
                        // Whichever bar is closer takes the gesture.
                        grabbed = when {
                            dStart < touchSlopPx && dStart <= dEnd -> Grabbed.Start
                            dEnd < touchSlopPx && dEnd < dStart -> Grabbed.End
                            // Tap inside the active range, well clear of either bar → scrub.
                            offset.x > startX + touchSlopPx && offset.x < endX - touchSlopPx
                                -> Grabbed.Playhead
                            else -> null
                        }
                        if (grabbed == Grabbed.Playhead) {
                            val pct = (offset.x / w).coerceIn(0f, 1f)
                            onScrubMs((pct * durationMs).toLong().coerceIn(trimStartMs, trimEndMs))
                        }
                    },
                    onDrag = { change, _ ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val pct = (change.position.x / w).coerceIn(0f, 1f)
                        val ms = (pct * durationMs).toLong()
                        when (grabbed) {
                            Grabbed.Start -> onTrimChange(ms, trimEndMs)
                            Grabbed.End -> onTrimChange(trimStartMs, ms)
                            Grabbed.Playhead -> onScrubMs(ms.coerceIn(trimStartMs, trimEndMs))
                            null -> Unit
                        }
                        change.consume()
                    },
                    onDragEnd = { grabbed = null },
                    onDragCancel = { grabbed = null },
                )
            },
    ) {
        // Filmstrip — divide the available width into N slots and draw each thumbnail
        // into its slot. A small horizontal padding gap mirrors a real-world editor.
        if (thumbnails.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxSize()) {
                thumbnails.forEach { bmp ->
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val startX = (trimStartMs.toFloat() / durationMs * w).coerceIn(0f, w)
            val endX = (trimEndMs.toFloat() / durationMs * w).coerceIn(0f, w)
            // Dim the trimmed-off portions so the active range pops visually.
            val mask = Color.Black.copy(alpha = 0.55f)
            drawRect(mask, topLeft = Offset(0f, 0f), size = GSize(startX, h))
            drawRect(mask, topLeft = Offset(endX, 0f), size = GSize(w - endX, h))
            // Accent-coloured outline around the selected range — top/bottom bars and
            // the two edge handles. The bar handles are wider than the slider thumbs so
            // a fingertip naturally lands on them without precision dragging.
            val barW = handleWidthPx
            drawRect(accentColor, topLeft = Offset(startX, 0f), size = GSize(barW, h))
            drawRect(accentColor, topLeft = Offset(endX - barW, 0f), size = GSize(barW, h))
            drawRect(accentColor, topLeft = Offset(startX, 0f), size = GSize(endX - startX, 3f))
            drawRect(accentColor, topLeft = Offset(startX, h - 3f), size = GSize(endX - startX, 3f))
            // Playhead line — thin, bright, with a small triangle on top so it's spotted
            // immediately when the strip is busy.
            val playX = (playheadMs.toFloat() / durationMs * w).coerceIn(0f, w)
            drawRect(
                color = Color.White,
                topLeft = Offset(playX - 1.5f, 0f),
                size = GSize(3f, h),
            )
        }
    }
}

private enum class Grabbed { Start, End, Playhead }

// ─── Reusable bits ───────────────────────────────────────────────────────────

@Composable
private fun ActionTile(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .background(PanelChip)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(icon, label, tint = FgPrimary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = FgPrimary, fontSize = 12.sp)
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PanelChip)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = if (enabled) FgPrimary else FgDim.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp))
        Text(label, color = if (enabled) FgPrimary else FgDim.copy(alpha = 0.4f), fontSize = 13.sp)
    }
}

// ─── Save sheet ──────────────────────────────────────────────────────────────

@Composable
private fun VideoSaveSheet(
    isSaving: Boolean,
    progress: Float?,
    isCloud: Boolean,
    onPicked: (VideoSaveMode) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 24.dp),
    ) {
        Text(
            "Save edits",
            color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            // Cloud edits upload back to Proton Drive; local edits save to the device's
            // MediaStore. The subtitle here orients the user before they pick a mode.
            if (isCloud) "Choose how the edit lands back in your Proton Drive."
            else "Choose how to save the edited video to your device.",
            color = FgMute, fontSize = 13.sp,
        )
        Spacer(Modifier.height(18.dp))

        // While saving with a re-encode we hide the option rows behind a progress bar so
        // the user can't accidentally pick the other mode mid-encode.
        if (isSaving && progress != null) {
            Text(
                LocalContext.current.getString(R.string.video_editor_reencoding),
                color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = Accent,
                trackColor = TrackBg,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${(progress * 100).toInt()}%",
                color = FgMute, fontSize = 12.sp,
            )
        } else if (isSaving) {
            // Stream-copy path — fast enough that a spinner suffices.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("Saving…", color = FgPrimary, fontSize = 14.sp)
            }
        } else {
            // Per user request: videos only support Save-as-Copy. The Overwrite path
            // for video involves either MediaStore consent for foreign URIs (device
            // case) or trashing the cloud original after a re-encode upload — both
            // surfaced as failure modes (read-error/SSL retries, half-saved Drive
            // entries) that left the user unsure whether the original survived. Copy
            // is unambiguous: the source is never touched, the edit lands next to it.
            SaveOptionRow(
                icon = Icons.Default.ContentCopy,
                title = LocalContext.current.getString(R.string.video_editor_save_copy),
                subtitle = if (isCloud)
                    "Uploads as a new file in Proton Drive — keeps the original."
                else
                    "Creates a new file in Pictures/Proton Photos.",
                onClick = { onPicked(VideoSaveMode.Copy) },
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PanelChip)
                .clickable(enabled = !isSaving, onClick = onCancel)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                if (isSaving) "Please wait…" else "Cancel",
                color = if (isSaving) FgDim else FgPrimary,
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SaveOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Accent.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = FgMute, fontSize = 12.sp)
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    val tenths = (ms % 1000) / 100
    return "%d:%02d.%d".format(m, s, tenths)
}
