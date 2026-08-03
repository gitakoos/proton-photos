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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.util.FitBox
import eu.akoos.photos.util.fitImageInBox

// ─── Video preview (ExoPlayer) ───────────────────────────────────────────────

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
) {
    // Track the displayed video aspect ratio. Seed with the VM-provided dims so the
    // preview's layout is correct from the first frame. ExoPlayer's onVideoSizeChanged
    // refines once the demuxer reports the post-auto-rotation video size.
    var videoAspect by remember(player, initialAspect) {
        androidx.compose.runtime.mutableFloatStateOf(initialAspect.coerceAtLeast(0.01f))
    }
    DisposableEffect(player) {
        // The player was created with playWhenReady=true so the renderer paints frame 0
        // to the surface immediately on STATE_READY (no black void during prepare). We
        // pause AT that first transition and seek back to 0 — by then frame 0 is already
        // visible. Subsequent STATE_READY transitions (re-buffer mid-playback) are
        // ignored so we don't yank the playhead back to start during normal playback.
        var didFirstPause = false
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width.toFloat() / videoSize.height.toFloat()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY && !didFirstPause) {
                    didFirstPause = true
                    runCatching {
                        player.pause()
                        player.seekTo(0L)
                    }
                }
            }
        }
        player.addListener(listener)
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
            // Inflate PlayerView from XML where surface_type="texture_view" is set —
            // gives us PlayerView's mature surface lifecycle (the raw-TextureView
            // experiments dropped frames / failed to attach on some devices, leaving
            // the play button silently no-op) AND a TextureView backing so Compose's
            // graphicsLayer rotation propagates to the rendered frames.
            //
            // requiredWidth/Height so the inner sizes at PRE-rotation dims even
            // though the parent's bounds are the POST-rotation footprint — sideways
            // rotations need the inner to extend past parent edges (narrower-but-
            // taller or wider-but-shorter) before graphicsLayer pulls the rendered
            // bbox back inside the footprint.
            AndroidView(
                factory = { ctx ->
                    val view = android.view.LayoutInflater.from(ctx)
                        .inflate(R.layout.video_editor_player, null) as androidx.media3.ui.PlayerView
                    view.player = player
                    view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    view
                },
                update = { view -> view.player = player },
                modifier = Modifier
                    .requiredWidth(innerWidthDp)
                    .requiredHeight(innerHeightDp)
                    .graphicsLayer(rotationZ = rotationDegrees.toFloat()),
            )
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
    onCropChange: (AndroidRect) -> Unit,
) {
    val srcW = sourceWidth.coerceAtLeast(1)
    val srcH = sourceHeight.coerceAtLeast(1)

    // Local pending crop — drag commits live to the VM, the local copy keeps the
    // pointer math snappy without round-tripping the StateFlow on every frame. The
    // rect is owned by the drag gestures once seeded: keying the remember on currentCrop
    // would re-create this state on every committed drag from the (normalized,
    // briefly-null-on-reset) cropRect and snap it back to the full frame mid-interaction
    // (the "crop jumps to original size on touch" bug). Like the photo editor, seed once
    // and only re-seed when currentCrop is explicitly cleared (reset / rotation), never
    // during a drag.
    var pending by remember { mutableStateOf<AndroidRect?>(null) }
    LaunchedEffect(srcW, srcH, currentCrop) {
        if (srcW <= 0 || srcH <= 0) return@LaunchedEffect
        when {
            currentCrop == null -> pending = AndroidRect(0, 0, srcW, srcH) // reset / rotation cleared it
            pending == null -> pending = currentCrop                       // first entry with a committed crop
            // else: a drag owns `pending` — do NOT overwrite from currentCrop.
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
                    update = { view -> view.player = player },
                    modifier = Modifier.fillMaxSize(),
                )
                CropHandleCanvas(
                    srcW = srcW, srcH = srcH,
                    pending = pendingRect,
                    onPendingChange = { rect ->
                        pending = rect
                        onCropChange(rect)
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
    onPendingChange: (AndroidRect) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // The pointerInput gesture loop below outlives recomposition: a plain capture of `pending`
    // freezes its FIRST value inside the closure, so a SECOND touch measures handles against the
    // original (full-frame) rect and snaps the crop back to original size. rememberUpdatedState
    // keeps the loop reading the freshest rect — same pattern the photo editor's crop uses.
    val pendingState = androidx.compose.runtime.rememberUpdatedState(pending)
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
                            val minSize = 32 // pixels — keep handles spread apart
                            val newRect = when (h) {
                                CropHandle.TopLeft -> AndroidRect(
                                    srcX.coerceAtMost(p.right - minSize),
                                    srcY.coerceAtMost(p.bottom - minSize),
                                    p.right, p.bottom,
                                )
                                CropHandle.TopRight -> AndroidRect(
                                    p.left,
                                    srcY.coerceAtMost(p.bottom - minSize),
                                    srcX.coerceAtLeast(p.left + minSize),
                                    p.bottom,
                                )
                                CropHandle.BottomLeft -> AndroidRect(
                                    srcX.coerceAtMost(p.right - minSize),
                                    p.top,
                                    p.right,
                                    srcY.coerceAtLeast(p.top + minSize),
                                )
                                CropHandle.BottomRight -> AndroidRect(
                                    p.left,
                                    p.top,
                                    srcX.coerceAtLeast(p.left + minSize),
                                    srcY.coerceAtLeast(p.top + minSize),
                                )
                                // Edge grabs — drag one side, the other three stay put.
                                CropHandle.Top -> AndroidRect(
                                    p.left, srcY.coerceAtMost(p.bottom - minSize), p.right, p.bottom,
                                )
                                CropHandle.Bottom -> AndroidRect(
                                    p.left, p.top, p.right, srcY.coerceAtLeast(p.top + minSize),
                                )
                                CropHandle.Left -> AndroidRect(
                                    srcX.coerceAtMost(p.right - minSize), p.top, p.right, p.bottom,
                                )
                                CropHandle.Right -> AndroidRect(
                                    p.left, p.top, srcX.coerceAtLeast(p.left + minSize), p.bottom,
                                )
                                CropHandle.Inside -> {
                                    // Bodily translate the rect — preserve W×H, clamp
                                    // to source bounds so the rect doesn't leave the
                                    // frame on either axis.
                                    val w = p.width()
                                    val hgt = p.height()
                                    val newLeft = (srcX - insideOffsetSrcX)
                                        .coerceIn(0, srcW - w)
                                    val newTop = (srcY - insideOffsetSrcY)
                                        .coerceIn(0, srcH - hgt)
                                    AndroidRect(newLeft, newTop, newLeft + w, newTop + hgt)
                                }
                            }
                            onPendingChange(newRect)
                            change.consume()
                        },
                        onDragEnd = { grabbedHandle = null },
                        onDragCancel = { grabbedHandle = null },
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

            drawCropMarkers(l, t, r, b)
        }
    }
}

