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

package eu.akoos.photos.presentation.editor

import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.util.FitBox
import eu.akoos.photos.util.fitImageInBox

// ─── Video preview (ExoPlayer) ───────────────────────────────────────────────

/**
 * Tints the player view with the chosen colour filter for a LIVE preview, using a view RenderEffect
 * (Android 12+). [matrix] is a 4x5 Android colour matrix, or null to clear the effect. Below API 31 the
 * preview stays ungraded (the filter still bakes in on save); harmless on any device (best-effort).
 */
internal fun applyFilterRenderEffect(view: android.view.View, matrix: FloatArray?) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
    runCatching {
        view.setRenderEffect(
            if (matrix == null) {
                null
            } else {
                android.graphics.RenderEffect.createColorFilterEffect(
                    android.graphics.ColorMatrixColorFilter(matrix),
                )
            },
        )
    }
}

@Composable
internal fun VideoPreview(
    /** Owned by the screen (NOT this composable) so it survives tab swaps to/from Crop —
     *  the previous self-owned remember(uri) released the player on Crop entry and the
     *  user lost their playback position. */
    player: ExoPlayer,
    /** Initial video aspect ratio from the VM. Without this seed, the preview defaults to
     *  16:9 until ExoPlayer's onVideoSizeChanged fires (~200-500 ms later) — the user sees
     *  a "video collapsed → expanded" jump on open. The VM already swaps encoded dims by
     *  the source rotation metadata, so initialAspect matches what the player will report. */
    initialAspect: Float,
    /** Editor-applied rotation in 90° steps; the TextureView is rotated client-side via
     *  graphicsLayer so the user sees the effect immediately (the muxer's orientation
     *  hint applies on save, not in the live preview). TextureView (not SurfaceView) is
     *  used so graphicsLayer rotations actually affect the rendered frames — SurfaceView
     *  renders on its own compositor layer and ignores parent transforms. */
    rotationDegrees: Int = 0,
    /** The committed crop in source pixels, or null. When set, the area outside it is masked with a
     *  grey band right here in the live preview so the final framing is visible on every tool tab,
     *  not only inside the crop tool. */
    cropRect: AndroidRect? = null,
    sourceWidth: Int = 0,
    sourceHeight: Int = 0,
    /** The active colour filter as a 4x5 Android matrix (null = none), previewed live on the video via a
     *  RenderEffect colour filter (Android 12+). The same look is baked into the export on save. */
    filterMatrix: FloatArray? = null,
) {
    // Same VM instance the screen holds (shared NavBackStackEntry store), so the player's
    // reported size can rescue the crop gate when MediaMetadataRetriever read no dimensions.
    val vm: VideoEditorViewModel = hiltViewModel()
    // Track the displayed video aspect ratio. Seed with the VM-provided dims so the
    // preview's layout is correct from the first frame. ExoPlayer's onVideoSizeChanged
    // refines once the demuxer reports the post-auto-rotation video size.
    var videoAspect by remember(player, initialAspect) {
        androidx.compose.runtime.mutableFloatStateOf(initialAspect.coerceAtLeast(0.01f))
    }
    DisposableEffect(player) {
        // The first-ready pause + rewind now lives on the player itself (in the screen's ExoPlayer
        // builder), so it applies under every tab including Crop. Here we only track the video size to
        // keep the crop overlay's aspect correct.
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width.toFloat() / videoSize.height.toFloat()
                    vm.onPlayerVideoSize(videoSize.width, videoSize.height)
                }
            }
        }
        player.addListener(listener)
        // A player prepared before this listener attached already knows its size, and
        // addListener does not replay the event, so seed once from the current value.
        player.videoSize.let { size ->
            if (size.width > 0 && size.height > 0) {
                videoAspect = size.width.toFloat() / size.height.toFloat()
                vm.onPlayerVideoSize(size.width, size.height)
            }
        }
        onDispose { player.removeListener(listener) }
    }

    val sideways = ((rotationDegrees % 360) + 360) % 360 % 180 != 0
    val visibleAspect = if (sideways) 1f / videoAspect else videoAspect

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val containerAspect = containerW / containerH
        // Outer box dimensions in dp, sized to fit the POST-rotation aspect inside the
        // available container. For sideways rotations the inner gets swapped dims so
        // graphicsLayer's rotation lands its visual footprint exactly on the outer.
        val (outerWidthDp, outerHeightDp) = with(LocalDensity.current) {
            if (visibleAspect > containerAspect) {
                val w = containerW; val h = w / visibleAspect
                Pair(w.toDp(), h.toDp())
            } else {
                val h = containerH; val w = h * visibleAspect
                Pair(w.toDp(), h.toDp())
            }
        }
        Box(
            modifier = Modifier.width(outerWidthDp).height(outerHeightDp),
            contentAlignment = Alignment.Center,
        ) {
            val (innerWidthDp, innerHeightDp) = if (sideways) {
                Pair(outerHeightDp, outerWidthDp)
            } else {
                Pair(outerWidthDp, outerHeightDp)
            }
            // Player + crop mask share one graphicsLayer-rotated inner box so the mask tracks the
            // video through a user rotation, the same pattern [CropOverPlayer] uses. requiredWidth/
            // Height sizes the inner at PRE-rotation dims so a sideways turn lands its footprint on
            // the outer bounds.
            Box(
                modifier = Modifier
                    .requiredWidth(innerWidthDp)
                    .requiredHeight(innerHeightDp)
                    .graphicsLayer(rotationZ = rotationDegrees.toFloat()),
            ) {
                // Inflate PlayerView from XML where surface_type="texture_view" is set — gives us
                // PlayerView's mature surface lifecycle AND a TextureView backing so the graphicsLayer
                // rotation propagates to the rendered frames.
                AndroidView(
                    factory = { ctx ->
                        val view = android.view.LayoutInflater.from(ctx)
                            .inflate(R.layout.video_editor_player, null) as androidx.media3.ui.PlayerView
                        view.player = player
                        view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        view
                    },
                    update = { view ->
                        view.player = player
                        applyFilterRenderEffect(view, filterMatrix)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Live crop preview: grey out everything outside the committed crop so the final
                // framing shows on every tab. Purely visual (no gestures); the crop tool owns editing.
                if (cropRect != null && sourceWidth > 0 && sourceHeight > 0) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val fit = fitImageInBox(
                            sourceWidth.toFloat(), sourceHeight.toFloat(),
                            size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f),
                        )
                        val r = fit.toScreen(
                            FitBox(
                                cropRect.left.toFloat(), cropRect.top.toFloat(),
                                cropRect.right.toFloat(), cropRect.bottom.toFloat(),
                            ),
                        )
                        val mask = Color.Black.copy(alpha = 0.5f)
                        drawRect(mask, topLeft = Offset(0f, 0f), size = GSize(size.width, r.top))
                        drawRect(mask, topLeft = Offset(0f, r.bottom), size = GSize(size.width, size.height - r.bottom))
                        drawRect(mask, topLeft = Offset(0f, r.top), size = GSize(r.left, r.bottom - r.top))
                        drawRect(mask, topLeft = Offset(r.right, r.top), size = GSize(size.width - r.right, r.bottom - r.top))
                    }
                }
            }
        }
    }
}

// ─── Crop overlay ────────────────────────────────────────────────────────────

/**
 * Renders the source video's first frame as a bitmap and overlays draggable corner
 * brackets and side markers. The user's crop rect lives in source-pixel coordinates; we convert
 * between screen space and source space using a fit-rect (letterbox-aware).
 *
 * Gestures: a drag starts by picking the closest handle; subsequent moves drag that
 * handle while preserving a minimum size. The dark semi-opaque mask paints everything
 * OUTSIDE the crop rect to make the focus clear.
 */
/**
 * Crop tab content — the same live ExoPlayer as the other tabs PLUS the crop handle
 * overlay layered on top. Both the PlayerView and the crop Canvas live inside a single
 * graphicsLayer-rotated inner box so user rotation propagates to both at once and the
 * Canvas's pointer events get auto-untransformed by Compose into the unrotated crop
 * coord space — no manual handle-coord rotation needed.
 *
 * Mirrors [VideoPreview]'s outer/inner sizing pattern for the rotated-aspect fit. Crop
 * math runs in POST-source-rotation coords (srcW × srcH) — same as before; [VideoEditorViewModel.cropInSourcePixels]
 * still inverts only the source's baked rotation when handing the rect to VideoReencoder.
 */
@Composable
internal fun CropOverPlayer(
    player: ExoPlayer,
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int = 0,
    currentCrop: AndroidRect?,
    /** Source-space width/height ratio the crop is locked to (null = free-form). Held through
     *  every handle drag by [resizeCrop], the same shape lock the photo overlay uses. */
    lockedRatio: Float? = null,
    onCropChange: (AndroidRect) -> Unit,
    /** Fired when a crop handle drag begins / ends, so the host can bracket it as one undo step. */
    onEditBegin: () -> Unit = {},
    onEditCommit: () -> Unit = {},
    /** Active colour filter as a 4x5 Android matrix (null = none), previewed live via a RenderEffect. */
    filterMatrix: FloatArray? = null,
) {
    val srcW = sourceWidth.coerceAtLeast(1)
    val srcH = sourceHeight.coerceAtLeast(1)

    // Local pending crop — drag commits live to the VM, the local copy keeps the
    // pointer math snappy without round-tripping the StateFlow on every frame. A live
    // handle drag OWNS this rect and commits it to the VM each frame, so it must not be
    // resynced from currentCrop mid-drag (that was the "crop jumps to original size on
    // touch" bug). But when NO drag is in flight, an external change to currentCrop — an
    // aspect chip tap, first entry, a reset — has to reshape the drawn frame immediately;
    // the old rule resynced only when currentCrop went null, so a chip's fresh rect stayed
    // invisible until the user touched the frame.
    var pending by remember { mutableStateOf<AndroidRect?>(null) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(srcW, srcH, currentCrop, dragging) {
        if (srcW <= 0 || srcH <= 0) return@LaunchedEffect
        when {
            currentCrop == null -> pending = AndroidRect(0, 0, srcW, srcH) // reset / rotation / Free / Original
            !dragging -> pending = currentCrop                             // chip tap, first entry — reflect now
            // else: a live drag owns `pending`; its per-frame commits keep currentCrop in step.
        }
    }
    val pendingRect = pending ?: AndroidRect(0, 0, srcW, srcH)

    val sideways = ((rotationDegrees % 360) + 360) % 360 % 180 != 0
    val videoAspect = srcW.toFloat() / srcH.toFloat()
    val visibleAspect = if (sideways) 1f / videoAspect else videoAspect

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val containerAspect = containerW / containerH
        // Outer fits the POST-user-rotation visible aspect inside the available area.
        val (outerWidthDp, outerHeightDp) = with(LocalDensity.current) {
            if (visibleAspect > containerAspect) {
                val w = containerW; val h = w / visibleAspect
                Pair(w.toDp(), h.toDp())
            } else {
                val h = containerH; val w = h * visibleAspect
                Pair(w.toDp(), h.toDp())
            }
        }
        Box(
            modifier = Modifier.width(outerWidthDp).height(outerHeightDp),
            contentAlignment = Alignment.Center,
        ) {
            // Inner = pre-rotation aspect. For sideways rotations its layout dims swap
            // vs the outer, then graphicsLayer pulls the rendered footprint back to the
            // outer's bounds. Same trick VideoPreview uses.
            val (innerWidthDp, innerHeightDp) = if (sideways) {
                Pair(outerHeightDp, outerWidthDp)
            } else {
                Pair(outerWidthDp, outerHeightDp)
            }
            Box(
                modifier = Modifier
                    .requiredWidth(innerWidthDp)
                    .requiredHeight(innerHeightDp)
                    .graphicsLayer(rotationZ = rotationDegrees.toFloat()),
            ) {
                AndroidView(
                    factory = { ctx ->
                        val view = android.view.LayoutInflater.from(ctx)
                            .inflate(R.layout.video_editor_player, null) as androidx.media3.ui.PlayerView
                        view.player = player
                        view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        view
                    },
                    update = { view ->
                        view.player = player
                        applyFilterRenderEffect(view, filterMatrix)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                CropHandleCanvas(
                    srcW = srcW, srcH = srcH,
                    pending = pendingRect,
                    lockedRatio = lockedRatio,
                    onPendingChange = { rect ->
                        pending = rect
                        onCropChange(rect)
                    },
                    onDraggingChange = { d ->
                        dragging = d
                        if (d) onEditBegin() else onEditCommit()
                    },
                )
            }
        }
    }
}

/**
 * Just the crop handles + mask, rendered as a Canvas sibling to the player surface.
 * Lives inside CropOverPlayer's rotated inner box so the same graphicsLayer rotation
 * applies. Coord math operates in source-rotation pixels (srcW × srcH) — Compose remaps
 * pointer events through the parent graphicsLayer for us, so we never have to rotate
 * handle positions by hand.
 */
@Composable
private fun CropHandleCanvas(
    srcW: Int,
    srcH: Int,
    pending: AndroidRect,
    lockedRatio: Float?,
    onPendingChange: (AndroidRect) -> Unit,
    /** Fired true when a handle drag begins and false when it ends, so the parent can hold off
     *  resyncing its pending rect from the VM while the finger owns it. */
    onDraggingChange: (Boolean) -> Unit = {},
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // The pointerInput gesture loop below outlives recomposition: a plain capture of `pending`
    // freezes its FIRST value inside the closure, so a SECOND touch measures handles against the
    // original (full-frame) rect and snaps the crop back to original size. rememberUpdatedState
    // keeps the loop reading the freshest rect — same pattern the photo editor's crop uses.
    val pendingState = androidx.compose.runtime.rememberUpdatedState(pending)
    // Same reason as the rect: the lock changes when a chip is tapped, and the gesture loop must
    // read the current one rather than the one that was set when it started.
    val lockState = androidx.compose.runtime.rememberUpdatedState(lockedRatio)
    Box(modifier = Modifier.fillMaxSize().onSizeChanged { containerSize = it }) {
        val fit = remember(srcW, srcH, containerSize) {
            fitImageInBox(srcW.toFloat(), srcH.toFloat(),
                containerSize.width.toFloat().coerceAtLeast(1f),
                containerSize.height.toFloat().coerceAtLeast(1f))
        }
        val screenRect = fit.toScreen(
            FitBox(
                pending.left.toFloat(), pending.top.toFloat(),
                pending.right.toFloat(), pending.bottom.toFloat(),
            ),
        )

        val density = LocalDensity.current
        // The grab area is deliberately larger than the drawn marker; see the slop constant.
        val touchRadiusPx = with(density) { CROP_HANDLE_TOUCH_SLOP_DP.dp.toPx() }

        val accent = Accent

        // Declared before the Canvas so the Canvas is drawn last and hit-tested first; these
        // carry no pointer input either way.
        if (containerSize.width > 0) {
            CropGestureExclusions(
                leftPx = screenRect.left,
                topPx = screenRect.top,
                rightPx = screenRect.right,
                bottomPx = screenRect.bottom,
                containerWidthPx = containerSize.width.toFloat(),
                slopPx = touchRadiusPx,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(srcW, srcH, fit) {
                    // Track which handle is being dragged so a fast move past another
                    // corner doesn't snap to the wrong one. Inside-the-rect drags
                    // translate the rect; we remember the touch's source-pixel offset
                    // from the rect's top-left so the translation stays anchored to the
                    // finger position instead of snapping the corner under it.
                    var grabbedHandle: CropHandle? = null
                    var insideOffsetSrcX = 0
                    var insideOffsetSrcY = 0
                    detectDragGestures(
                        onDragStart = { offset ->
                            val p = pendingState.value
                            grabbedHandle = pickCropHandle(
                                box = p.toCropBox(),
                                scale = fit.scale,
                                offsetX = fit.offsetX,
                                offsetY = fit.offsetY,
                                pointX = offset.x,
                                pointY = offset.y,
                                touchSlopPx = touchRadiusPx,
                            )
                            if (grabbedHandle != null) onDraggingChange(true)
                            if (grabbedHandle == CropHandle.Inside) {
                                val srcX = fit.toImageX(offset.x)
                                    .coerceIn(0f, srcW.toFloat()).toInt()
                                val srcY = fit.toImageY(offset.y)
                                    .coerceIn(0f, srcH.toFloat()).toInt()
                                insideOffsetSrcX = srcX - p.left
                                insideOffsetSrcY = srcY - p.top
                            }
                        },
                        onDrag = { change, _ ->
                            val h = grabbedHandle ?: return@detectDragGestures
                            // Read the LIVE rect each frame (not the captured one) so a resize/move
                            // builds on the current crop instead of snapping back to the original.
                            val p = pendingState.value
                            val srcX = fit.toImageX(change.position.x)
                                .coerceIn(0f, srcW.toFloat()).toInt()
                            val srcY = fit.toImageY(change.position.y)
                                .coerceIn(0f, srcH.toFloat()).toInt()
                            // Shared geometry: a null lock is free-form, a non-null lock holds the
                            // shape through the drag. Same call the photo overlay makes, so a video
                            // handle and a photo handle behave identically.
                            val newRect = resizeCrop(
                                box = p.toCropBox(),
                                handle = h,
                                bx = srcX,
                                by = srcY,
                                ratio = lockState.value,
                                boundsW = srcW,
                                boundsH = srcH,
                                minPx = 32,
                                insideOffsetX = insideOffsetSrcX,
                                insideOffsetY = insideOffsetSrcY,
                            ).toRect()
                            onPendingChange(newRect)
                            change.consume()
                        },
                        onDragEnd = { grabbedHandle = null; onDraggingChange(false) },
                        onDragCancel = { grabbedHandle = null; onDraggingChange(false) },
                    )
                },
        ) {
            // Crop rect in screen coords
            val l = screenRect.left
            val t = screenRect.top
            val r = screenRect.right
            val b = screenRect.bottom

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
            val strokeWidthPx = CROP_FRAME_STROKE_DP.dp.toPx()
            drawLine(accent, Offset(l, t), Offset(r, t), strokeWidth = strokeWidthPx)
            drawLine(accent, Offset(r, t), Offset(r, b), strokeWidth = strokeWidthPx)
            drawLine(accent, Offset(r, b), Offset(l, b), strokeWidth = strokeWidthPx)
            drawLine(accent, Offset(l, b), Offset(l, t), strokeWidth = strokeWidthPx)

            // Rule-of-thirds gridlines in the same tone the photo crop uses, so the frame
            // reads at the full-frame default where the border sits flush with the video edge.
            val gridColor = Color.White.copy(alpha = 0.35f)
            val gridW = r - l
            val gridH = b - t
            for (i in 1..2) {
                drawLine(
                    gridColor,
                    Offset(l + gridW * i / 3f, t),
                    Offset(l + gridW * i / 3f, b),
                    strokeWidth = 1f,
                )
                drawLine(
                    gridColor,
                    Offset(l, t + gridH * i / 3f),
                    Offset(r, t + gridH * i / 3f),
                    strokeWidth = 1f,
                )
            }

            drawCropMarkers(l, t, r, b)
        }
    }
}

