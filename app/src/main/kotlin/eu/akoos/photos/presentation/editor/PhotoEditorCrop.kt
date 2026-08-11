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

package eu.akoos.photos.presentation.editor

import eu.akoos.photos.R

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.util.FitBox
import eu.akoos.photos.util.fitImageInBox
import kotlin.math.min

@Composable
internal fun CropPanel(
    state: EditorUiState,
    vm: PhotoEditorViewModel,
    lockedAspect: CropAspect,
    onLockedAspectChange: (CropAspect) -> Unit,
    onPendingCropRectChange: (android.graphics.Rect?) -> Unit,
) {
    // Full-image bounds and ratio math run against the DISPLAYED crop bitmap (rotation
    // baked in), so its width/height match the rect's display space after a turn. Falls
    // back to the raw original for the brief pre-render window (same dimensions at 0°).
    val disp = state.adjustedBitmapNoCrop ?: state.originalBitmap ?: return
    val dispW = disp.width
    val dispH = disp.height

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Aspect ratio chips — same pill recipe as the photos page filter row.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(CropAspect.entries.toList()) { aspect ->
                // The lit chip is the one the user tapped, and it stays lit while that shape is
                // locked, even after the rect is dragged smaller or moved. Comparing rects would
                // unlight it the moment the frame was nudged.
                val isSelected = aspect == lockedAspect
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .background(
                            if (isSelected) Accent.copy(alpha = 0.18f) else PillBg,
                            pillShape,
                        )
                        .then(if (!isSelected) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
                        .clickable {
                            // Tapping a chip locks the shape AND shows it, with no Apply step.
                            // Free only releases the lock and leaves the rect where the user put
                            // it, so it commits nothing and spawns no undo entry. Original is the
                            // whole image, committed as null so the pipeline skips a full-size crop.
                            val ratio = aspect.lockRatio(dispW, dispH)
                            onLockedAspectChange(aspect)
                            when {
                                // Free releases the lock AND clears any crop back to the full frame, so it
                                // doubles as the reset (there is no separate reset chip).
                                aspect == CropAspect.Free -> {
                                    onPendingCropRectChange(android.graphics.Rect(0, 0, dispW, dispH))
                                    vm.applyCrop(null)
                                }
                                aspect == CropAspect.Original -> {
                                    onPendingCropRectChange(android.graphics.Rect(0, 0, dispW, dispH))
                                    vm.applyCrop(null)
                                }
                                ratio != null -> {
                                    val newRect = centeredCrop(dispW, dispH, ratio).toRect()
                                    onPendingCropRectChange(newRect)
                                    vm.applyCrop(newRect)
                                }
                            }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        // Numeric ratios (1:1, 16:9, …) stay as glyph labels; only the word
                        // entries are translatable.
                        when (aspect) {
                            CropAspect.Free -> stringResource(R.string.editor_crop_free)
                            CropAspect.Original -> stringResource(R.string.editor_filter_original)
                            else -> aspect.label
                        },
                        color = if (isSelected) Accent else FgPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * Asks the system to hold back its edge gesture over the crop handles that sit in one of its edge
 * strips, so a handle near the side of the display can be grabbed instead of being read as a back
 * swipe. Shared by the photo and video crop overlays; [cropGestureExclusions] decides which
 * handles qualify and holds the 200dp-per-edge arithmetic.
 *
 * The boxes draw nothing and carry no pointer input, so they never enter a hit test and the crop
 * Canvas keeps every touch it has today. Their position is a plain layout offset rather than the
 * modifier's rect-lambda overload: only a layout pass refreshes an exclusion, and a `fillMaxSize`
 * Canvas does not relayout when the rect drawn inside it moves, so a lambda would freeze at the
 * rect's first position.
 */
@Composable
internal fun BoxScope.CropGestureExclusions(
    leftPx: Float,
    topPx: Float,
    rightPx: Float,
    bottomPx: Float,
    containerWidthPx: Float,
    slopPx: Float,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestureInsets = WindowInsets.systemGestures
    val rects = cropGestureExclusions(
        leftPx = leftPx,
        topPx = topPx,
        rightPx = rightPx,
        bottomPx = bottomPx,
        containerWidthPx = containerWidthPx,
        leftInsetPx = gestureInsets.getLeft(density, layoutDirection).toFloat(),
        rightInsetPx = gestureInsets.getRight(density, layoutDirection).toFloat(),
        slopPx = slopPx,
    )
    for (r in rects) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(
                    x = with(density) { r.left.toDp() },
                    y = with(density) { r.top.toDp() },
                )
                .size(
                    width = with(density) { r.width.toDp() },
                    height = with(density) { r.height.toDp() },
                )
                .systemGestureExclusion(),
        )
    }
}

/**
 * Thickness of the crop rectangle's own outline, in dp. One value for both overlays so the frame
 * reads the same whether a photo or a video is being cropped.
 */
internal const val CROP_FRAME_STROKE_DP = 2f

/**
 * Thickness of a crop marker, in dp. Twice the frame, so a marker reads as a grip at a glance
 * instead of as a slightly heavier stretch of the same line.
 */
private const val CROP_MARKER_STROKE_DP = 4f

/**
 * Length of one leg of a corner bracket, in dp. Long enough to give the eye a direction to pull
 * from, short enough that the four brackets never close up into a second border.
 */
private const val CROP_MARKER_ARM_DP = 22f

/**
 * Length of the stroke that marks the middle of a side, in dp. A side offers one grab against a
 * corner's two, so its marker runs a little longer to carry the same weight.
 */
private const val CROP_EDGE_MARKER_DP = 28f

/**
 * How far the dark backing stroke reaches past the light one on each side, in dp. Enough to hold an
 * edge against a white photo, small enough to stay a rim rather than a shadow.
 */
private const val CROP_MARKER_BACKING_DP = 1f

/** Backing under every marker; the crop's own mask darkens only what lies OUTSIDE the rect. */
private val CropMarkerBacking = Color.Black.copy(alpha = 0.45f)

/**
 * Corner brackets and side markers for a crop rectangle, in the overlay's own pixel space.
 *
 * Each marker is a plain stroke laid INSIDE the rect with its outer edge on the rect's line, so a
 * corner reads as a thickened piece of the frame rather than a badge parked over it. A darker,
 * slightly wider stroke goes down first across the whole set: a light marker on its own vanishes
 * over a bright photo, and the mask only covers what is outside the crop.
 *
 * Drawing only. What is grabbable comes from [pickCropHandle] and its own slop, which is far wider
 * than anything drawn here.
 */
internal fun DrawScope.drawCropMarkers(left: Float, top: Float, right: Float, bottom: Float) {
    val spanX = right - left
    val spanY = bottom - top
    if (spanX <= 0f || spanY <= 0f) return

    val stroke = CROP_MARKER_STROKE_DP.dp.toPx()
    val inset = stroke / 2f
    // Both legs of a bracket share one length so the L stays square. Every marker gives way on a
    // crop too small to hold it: a quarter of the span each keeps a visible gap between a corner
    // and the side marker next to it instead of letting them merge into a solid edge.
    val arm = min(CROP_MARKER_ARM_DP.dp.toPx(), min(spanX, spanY) / 4f)
    val edgeX = min(CROP_EDGE_MARKER_DP.dp.toPx(), spanX / 4f)
    val edgeY = min(CROP_EDGE_MARKER_DP.dp.toPx(), spanY / 4f)

    val innerLeft = left + inset
    val innerTop = top + inset
    val innerRight = right - inset
    val innerBottom = bottom - inset
    val midX = (left + right) / 2f
    val midY = (top + bottom) / 2f

    val segments = listOf(
        Offset(left, innerTop) to Offset(left + arm, innerTop),
        Offset(innerLeft, top) to Offset(innerLeft, top + arm),
        Offset(right - arm, innerTop) to Offset(right, innerTop),
        Offset(innerRight, top) to Offset(innerRight, top + arm),
        Offset(left, innerBottom) to Offset(left + arm, innerBottom),
        Offset(innerLeft, bottom - arm) to Offset(innerLeft, bottom),
        Offset(right - arm, innerBottom) to Offset(right, innerBottom),
        Offset(innerRight, bottom - arm) to Offset(innerRight, bottom),
        Offset(midX - edgeX / 2f, innerTop) to Offset(midX + edgeX / 2f, innerTop),
        Offset(midX - edgeX / 2f, innerBottom) to Offset(midX + edgeX / 2f, innerBottom),
        Offset(innerLeft, midY - edgeY / 2f) to Offset(innerLeft, midY + edgeY / 2f),
        Offset(innerRight, midY - edgeY / 2f) to Offset(innerRight, midY + edgeY / 2f),
    )

    // The backing goes down in full first, so a neighbouring marker's rim never lands on top of a
    // light stroke that is already drawn.
    val backingWidth = stroke + 2f * CROP_MARKER_BACKING_DP.dp.toPx()
    for ((a, b) in segments) drawLine(CropMarkerBacking, a, b, strokeWidth = backingWidth)
    for ((a, b) in segments) drawLine(Color.White, a, b, strokeWidth = stroke)
}

/**
 * Interactive crop overlay rendered on top of the FULL uncropped original. Shows a
 * dark semi-transparent mask outside [cropRect], corner brackets and side markers, and
 * accepts pointer input for:
 *   - dragging a corner or a side to resize the rect (clamped to bitmap bounds, min size 32 px)
 *   - dragging inside the rect to translate it (clamped to bitmap bounds)
 * A non-null [lockedRatio] holds the rect's shape through every resize; see [resizeCrop].
 * All math is in bitmap-pixel coordinates; the on-screen scale is recomputed from the
 * [Image]'s fit-rect each composition.
 */
@Composable
internal fun CropPreview(
    bitmap: Bitmap,
    cropRect: android.graphics.Rect?,
    lockedRatio: Float?,
    onCropRectChanged: (android.graphics.Rect) -> Unit,
    onCropRectCommit: (android.graphics.Rect) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // The grab area is deliberately larger than the drawn marker; see the slop constant.
    val touchSlopPx = with(LocalDensity.current) { CROP_HANDLE_TOUCH_SLOP_DP.dp.toPx() }
    val minCropPx = 32f // bitmap-space minimum crop size — prevents zero-area rects

    // The pointerInput gesture loop below outlives recomposition: a plain capture
    // of the rect would freeze its FIRST value inside the closure, so every new
    // touch would measure handles against the original rect and snap the crop
    // back. rememberUpdatedState keeps the loop reading the freshest rect.
    val rectState = androidx.compose.runtime.rememberUpdatedState(
        cropRect ?: android.graphics.Rect(0, 0, bitmap.width, bitmap.height),
    )
    val rect = rectState.value
    // Same reason as the rect: the lock changes when a chip is tapped, and the gesture loop
    // must read the current one rather than the one that was set when it started.
    val lockState = androidx.compose.runtime.rememberUpdatedState(lockedRatio)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        if (containerSize.width > 0 && containerSize.height > 0) {
            val fit = remember(bitmap.width, bitmap.height, containerSize) {
                fitImageInBox(
                    bitmap.width.toFloat(), bitmap.height.toFloat(),
                    containerSize.width.toFloat().coerceAtLeast(1f),
                    containerSize.height.toFloat().coerceAtLeast(1f),
                )
            }
            // Convert canvas-space pixel deltas to bitmap-space.
            val accentColor = Accent
            val screenRect = fit.toScreen(
                FitBox(
                    rect.left.toFloat(), rect.top.toFloat(),
                    rect.right.toFloat(), rect.bottom.toFloat(),
                ),
            )

            // Declared before the Canvas so the Canvas is drawn last and hit-tested first; these
            // carry no pointer input either way.
            CropGestureExclusions(
                leftPx = screenRect.left,
                topPx = screenRect.top,
                rightPx = screenRect.right,
                bottomPx = screenRect.bottom,
                containerWidthPx = containerSize.width.toFloat(),
                slopPx = touchSlopPx,
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap, fit) {
                        // Track which handle is grabbed so a fast move past another corner
                        // doesn't snap to it mid-drag. An inside grab translates the rect; we
                        // remember the finger's bitmap-space offset from the rect's top-left so
                        // the translation stays anchored to the finger instead of jumping the
                        // corner under it. Mirrors the video editor's crop mechanics.
                        var grabbedHandle: CropHandle? = null
                        var insideOffsetX = 0
                        var insideOffsetY = 0
                        detectDragGestures(
                            onDragStart = { offset ->
                                val rect = rectState.value
                                grabbedHandle = pickCropHandle(
                                    box = rect.toCropBox(),
                                    scale = fit.scale,
                                    offsetX = fit.offsetX,
                                    offsetY = fit.offsetY,
                                    pointX = offset.x,
                                    pointY = offset.y,
                                    touchSlopPx = touchSlopPx,
                                )
                                if (grabbedHandle == CropHandle.Inside) {
                                    val bx = fit.toImageX(offset.x)
                                        .coerceIn(0f, bitmap.width.toFloat()).toInt()
                                    val by = fit.toImageY(offset.y)
                                        .coerceIn(0f, bitmap.height.toFloat()).toInt()
                                    insideOffsetX = bx - rect.left
                                    insideOffsetY = by - rect.top
                                }
                            },
                            onDrag = { change, _ ->
                                val h = grabbedHandle ?: return@detectDragGestures
                                val rect = rectState.value
                                change.consume()
                                // Work in absolute bitmap-space from the finger position
                                // rather than accumulating deltas — keeps the grabbed corner
                                // pinned under the finger even on fast drags.
                                val bx = fit.toImageX(change.position.x)
                                    .coerceIn(0f, bitmap.width.toFloat()).toInt()
                                val by = fit.toImageY(change.position.y)
                                    .coerceIn(0f, bitmap.height.toFloat()).toInt()
                                val r = resizeCrop(
                                    box = rect.toCropBox(),
                                    handle = h,
                                    bx = bx,
                                    by = by,
                                    ratio = lockState.value,
                                    boundsW = bitmap.width,
                                    boundsH = bitmap.height,
                                    minPx = minCropPx.toInt(),
                                    insideOffsetX = insideOffsetX,
                                    insideOffsetY = insideOffsetY,
                                ).toRect()
                                onCropRectChanged(r)
                            },
                            // Commit the freshest rect on release so the crop applies without
                            // an Apply tap. Only when a handle was actually grabbed — a stray
                            // tap outside the rect must not re-commit and spawn an undo entry.
                            onDragEnd = {
                                if (grabbedHandle != null) onCropRectCommit(rectState.value)
                                grabbedHandle = null
                            },
                            onDragCancel = {
                                if (grabbedHandle != null) onCropRectCommit(rectState.value)
                                grabbedHandle = null
                            },
                        )
                    },
            ) {
                val leftPx = screenRect.left
                val rightPx = screenRect.right
                val topPx = screenRect.top
                val bottomPx = screenRect.bottom
                // Image bounds in screen space: the dim mask stays INSIDE the photo, so the letterbox
                // around it keeps the app background (matching every other tool) instead of going black.
                val imgL = fit.toScreenX(0f)
                val imgT = fit.toScreenY(0f)
                val imgR = fit.toScreenX(bitmap.width.toFloat())
                val imgB = fit.toScreenY(bitmap.height.toFloat())
                val imgW = (imgR - imgL).coerceAtLeast(0f)
                val maskColor = Color.Black.copy(alpha = 0.55f)
                // Four dark rectangles around the crop rect (top / bottom / left / right), clipped to the image.
                drawRect(maskColor, topLeft = Offset(imgL, imgT),
                    size = GSize(imgW, (topPx - imgT).coerceAtLeast(0f)))
                drawRect(maskColor, topLeft = Offset(imgL, bottomPx.coerceAtMost(imgB)),
                    size = GSize(imgW, (imgB - bottomPx).coerceAtLeast(0f)))
                drawRect(maskColor, topLeft = Offset(imgL, topPx.coerceAtLeast(imgT)),
                    size = GSize((leftPx - imgL).coerceAtLeast(0f), (bottomPx - topPx).coerceAtLeast(0f)))
                drawRect(maskColor, topLeft = Offset(rightPx.coerceAtMost(imgR), topPx.coerceAtLeast(imgT)),
                    size = GSize((imgR - rightPx).coerceAtLeast(0f), (bottomPx - topPx).coerceAtLeast(0f)))

                // Border + rule-of-thirds gridlines.
                drawRect(
                    color = accentColor,
                    topLeft = Offset(leftPx, topPx),
                    size = GSize(rightPx - leftPx, bottomPx - topPx),
                    style = Stroke(width = CROP_FRAME_STROKE_DP.dp.toPx()),
                )
                val w = rightPx - leftPx
                val h = bottomPx - topPx
                val gridColor = Color.White.copy(alpha = 0.35f)
                for (i in 1..2) {
                    drawLine(
                        gridColor,
                        Offset(leftPx + w * i / 3f, topPx),
                        Offset(leftPx + w * i / 3f, bottomPx),
                        strokeWidth = 1f,
                    )
                    drawLine(
                        gridColor,
                        Offset(leftPx, topPx + h * i / 3f),
                        Offset(rightPx, topPx + h * i / 3f),
                        strokeWidth = 1f,
                    )
                }

                drawCropMarkers(leftPx, topPx, rightPx, bottomPx)
            }
        }
    }
}
