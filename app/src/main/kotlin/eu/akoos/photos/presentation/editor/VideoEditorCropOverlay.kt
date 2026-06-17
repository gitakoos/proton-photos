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
import kotlin.math.max
import kotlin.math.min

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
 * Renders the source video's first frame as a bitmap and overlays four draggable
 * corner handles. The user's crop rect lives in source-pixel coordinates; we convert
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
            fitRectFor(srcW.toFloat(), srcH.toFloat(),
                containerSize.width.toFloat().coerceAtLeast(1f),
                containerSize.height.toFloat().coerceAtLeast(1f))
        }

        val density = LocalDensity.current
        val handleRadiusPx = with(density) { 14.dp.toPx() }
        // Kept tight so corners get picked only when the touch sits close to an edge.
        // The edge-buffer logic in pickClosestHandle does the heavy lifting — corner
        // candidates only arise when the touch is near TWO adjacent edges. Touches
        // anywhere else inside the rect translate the rect bodily, which fixes the
        // "can drag horizontally but not vertically on portrait" bug.
        val touchRadiusPx = with(density) { 48.dp.toPx() }

        val accent = Accent
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(srcW, srcH, fit) {
                    // Track which handle is being dragged so a fast move past another
                    // corner doesn't snap to the wrong one. Inside-the-rect drags
                    // translate the rect; we remember the touch's source-pixel offset
                    // from the rect's top-left so the translation stays anchored to the
                    // finger position instead of snapping the corner under it.
                    var grabbedHandle: Handle? = null
                    var insideOffsetSrcX = 0
                    var insideOffsetSrcY = 0
                    detectDragGestures(
                        onDragStart = { offset ->
                            val p = pendingState.value
                            grabbedHandle = pickClosestHandle(p, fit, offset, touchRadiusPx)
                            if (grabbedHandle == Handle.Inside) {
                                val srcX = ((offset.x - fit.offsetX) / fit.scale)
                                    .coerceIn(0f, srcW.toFloat()).toInt()
                                val srcY = ((offset.y - fit.offsetY) / fit.scale)
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
                            val srcX = ((change.position.x - fit.offsetX) / fit.scale)
                                .coerceIn(0f, srcW.toFloat()).toInt()
                            val srcY = ((change.position.y - fit.offsetY) / fit.scale)
                                .coerceIn(0f, srcH.toFloat()).toInt()
                            val minSize = 32 // pixels — keep handles spread apart
                            val newRect = when (h) {
                                Handle.TopLeft -> AndroidRect(
                                    srcX.coerceAtMost(p.right - minSize),
                                    srcY.coerceAtMost(p.bottom - minSize),
                                    p.right, p.bottom,
                                )
                                Handle.TopRight -> AndroidRect(
                                    p.left,
                                    srcY.coerceAtMost(p.bottom - minSize),
                                    srcX.coerceAtLeast(p.left + minSize),
                                    p.bottom,
                                )
                                Handle.BottomLeft -> AndroidRect(
                                    srcX.coerceAtMost(p.right - minSize),
                                    p.top,
                                    p.right,
                                    srcY.coerceAtLeast(p.top + minSize),
                                )
                                Handle.BottomRight -> AndroidRect(
                                    p.left,
                                    p.top,
                                    srcX.coerceAtLeast(p.left + minSize),
                                    srcY.coerceAtLeast(p.top + minSize),
                                )
                                // Edge grabs — drag one side, the other three stay put.
                                Handle.Top -> AndroidRect(
                                    p.left, srcY.coerceAtMost(p.bottom - minSize), p.right, p.bottom,
                                )
                                Handle.Bottom -> AndroidRect(
                                    p.left, p.top, p.right, srcY.coerceAtLeast(p.top + minSize),
                                )
                                Handle.Left -> AndroidRect(
                                    srcX.coerceAtMost(p.right - minSize), p.top, p.right, p.bottom,
                                )
                                Handle.Right -> AndroidRect(
                                    p.left, p.top, srcX.coerceAtLeast(p.left + minSize), p.bottom,
                                )
                                Handle.Inside -> {
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

private enum class Handle { TopLeft, TopRight, BottomLeft, BottomRight, Top, Bottom, Left, Right, Inside }

/**
 * Edge-buffer handle picker. A touch is assigned to a corner ONLY when it sits within a
 * tight buffer (`edgeBufferPx`) of two adjacent edges of the rect — top+left, top+right,
 * bottom+left, bottom+right. Touches well inside the rect become Inside (translate).
 * Touches well outside the rect return null.
 *
 * The old "closest corner within touchRadius" heuristic broke on portrait crops because
 * every touch above the rect's vertical midpoint fell closer to a top corner than the
 * bottom — claiming the gesture for resize and giving the user the perception that
 * vertical drags did nothing.
 */
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
    // Edge buffer is tighter than the slop — corner zone shouldn't gobble the whole
    // rect on small/portrait crops. 0.45× gives roughly 16dp of corner reach at the
    // 36dp slop, which is still a comfortable fingertip target.
    val edgeBufferPx = touchRadiusPx * 0.45f
    val nearTop = (point.y - t) in -touchRadiusPx..edgeBufferPx
    val nearBottom = (b - point.y) in -touchRadiusPx..edgeBufferPx
    val nearLeft = (point.x - l) in -touchRadiusPx..edgeBufferPx
    val nearRight = (r - point.x) in -touchRadiusPx..edgeBufferPx

    val corner = when {
        nearTop && nearLeft -> Handle.TopLeft
        nearTop && nearRight -> Handle.TopRight
        nearBottom && nearLeft -> Handle.BottomLeft
        nearBottom && nearRight -> Handle.BottomRight
        else -> null
    }
    if (corner != null) return corner

    // Single edges — grab a side by its line: near that edge AND within the other axis's span.
    val withinX = point.x in (l - touchRadiusPx)..(r + touchRadiusPx)
    val withinY = point.y in (t - touchRadiusPx)..(b + touchRadiusPx)
    val edge = when {
        nearTop && withinX -> Handle.Top
        nearBottom && withinX -> Handle.Bottom
        nearLeft && withinY -> Handle.Left
        nearRight && withinY -> Handle.Right
        else -> null
    }
    if (edge != null) return edge

    // Inside the rect (with a small grace margin) → translate.
    return if (withinX && withinY) Handle.Inside else null
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
