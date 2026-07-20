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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

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
                                aspect == CropAspect.Free -> Unit
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Reset clears the committed crop, releases any lock, and snaps the pending rect
            // back to full image. No Apply chip, because drags and ratio chips already commit
            // live (see onCropRectCommit and the chip click above).
            ActionChip(
                label = androidx.compose.ui.res.stringResource(R.string.editor_crop_reset),
                icon = Icons.Default.Restore,
                enabled = true,
                onClick = {
                    onLockedAspectChange(CropAspect.Free)
                    onPendingCropRectChange(android.graphics.Rect(0, 0, dispW, dispH))
                    vm.applyCrop(null)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Interactive crop overlay rendered on top of the FULL uncropped original. Shows a
 * dark semi-transparent mask outside [cropRect], four corner handles, and accepts
 * pointer input for:
 *   - dragging any corner to resize the rect (clamped to bitmap bounds, min size 32 px)
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
    val handleSizePx = with(LocalDensity.current) { 28.dp.toPx() }
    // 56dp hit-target — fingertip ≈ 38dp, so 28dp landed outside the hit-circle on
    // most touches and fell back to pan-inside instead of resize. The visual marker
    // is still drawn at handleSizePx.
    val touchSlopPx = with(LocalDensity.current) { 56.dp.toPx() }
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
                fitRect(
                    bitmap.width.toFloat(), bitmap.height.toFloat(),
                    containerSize.width.toFloat().coerceAtLeast(1f),
                    containerSize.height.toFloat().coerceAtLeast(1f),
                )
            }
            // Convert canvas-space pixel deltas to bitmap-space.
            val accentColor = Accent
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
                                grabbedHandle = pickCropHandle(rect, fit, offset, touchSlopPx)
                                if (grabbedHandle == CropHandle.Inside) {
                                    val bx = ((offset.x - fit.offsetX) / fit.scale)
                                        .coerceIn(0f, bitmap.width.toFloat()).toInt()
                                    val by = ((offset.y - fit.offsetY) / fit.scale)
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
                                val bx = ((change.position.x - fit.offsetX) / fit.scale)
                                    .coerceIn(0f, bitmap.width.toFloat()).toInt()
                                val by = ((change.position.y - fit.offsetY) / fit.scale)
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
                val leftPx = fit.offsetX + rect.left * fit.scale
                val rightPx = fit.offsetX + rect.right * fit.scale
                val topPx = fit.offsetY + rect.top * fit.scale
                val bottomPx = fit.offsetY + rect.bottom * fit.scale
                val maskColor = Color.Black.copy(alpha = 0.55f)
                // Four dark rectangles around the crop rect — top / bottom / left / right.
                // Cheaper and clearer than a PorterDuff masking dance for a static overlay.
                drawRect(maskColor, topLeft = Offset(0f, 0f),
                    size = GSize(size.width, topPx.coerceAtLeast(0f)))
                drawRect(maskColor, topLeft = Offset(0f, bottomPx.coerceAtMost(size.height)),
                    size = GSize(size.width, (size.height - bottomPx).coerceAtLeast(0f)))
                drawRect(maskColor, topLeft = Offset(0f, topPx.coerceAtLeast(0f)),
                    size = GSize(leftPx.coerceAtLeast(0f), (bottomPx - topPx).coerceAtLeast(0f)))
                drawRect(maskColor, topLeft = Offset(rightPx.coerceAtMost(size.width), topPx.coerceAtLeast(0f)),
                    size = GSize((size.width - rightPx).coerceAtLeast(0f), (bottomPx - topPx).coerceAtLeast(0f)))

                // Border + rule-of-thirds gridlines.
                drawRect(
                    color = accentColor,
                    topLeft = Offset(leftPx, topPx),
                    size = GSize(rightPx - leftPx, bottomPx - topPx),
                    style = Stroke(width = 2f),
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

                // Corner handles — small white squares centered on each corner.
                val half = handleSizePx / 2f
                listOf(
                    Offset(leftPx, topPx),
                    Offset(rightPx, topPx),
                    Offset(leftPx, bottomPx),
                    Offset(rightPx, bottomPx),
                ).forEach { c ->
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(c.x - half, c.y - half),
                        size = GSize(handleSizePx, handleSizePx),
                    )
                    drawRect(
                        color = accentColor,
                        topLeft = Offset(c.x - half, c.y - half),
                        size = GSize(handleSizePx, handleSizePx),
                        style = Stroke(width = 2f),
                    )
                }
            }
        }
    }
}

/**
 * Edge-buffer crop-handle picker (ported from the video editor). A touch lands on a corner
 * only when it sits within a tight buffer of two adjacent edges (top+left, top+right,
 * bottom+left, bottom+right); touches well inside the rect become [CropHandle.Inside]
 * (translate), and touches well outside return null.
 */
private fun pickCropHandle(
    rect: android.graphics.Rect,
    fit: FitRect,
    point: Offset,
    touchSlopPx: Float,
): CropHandle? {
    val l = fit.offsetX + rect.left * fit.scale
    val t = fit.offsetY + rect.top * fit.scale
    val r = fit.offsetX + rect.right * fit.scale
    val b = fit.offsetY + rect.bottom * fit.scale
    // Corner zone is tighter than the slop so it doesn't gobble the whole rect on
    // small / portrait crops. 0.45x leaves a comfortable fingertip target while still
    // freeing the rect interior for translation.
    val edgeBufferPx = touchSlopPx * 0.45f
    val nearTop = (point.y - t) in -touchSlopPx..edgeBufferPx
    val nearBottom = (b - point.y) in -touchSlopPx..edgeBufferPx
    val nearLeft = (point.x - l) in -touchSlopPx..edgeBufferPx
    val nearRight = (r - point.x) in -touchSlopPx..edgeBufferPx

    val corner = when {
        nearTop && nearLeft -> CropHandle.TopLeft
        nearTop && nearRight -> CropHandle.TopRight
        nearBottom && nearLeft -> CropHandle.BottomLeft
        nearBottom && nearRight -> CropHandle.BottomRight
        else -> null
    }
    if (corner != null) return corner

    // Single edges — grab a side by its line: near that edge AND within the other axis's span.
    val withinX = point.x in (l - touchSlopPx)..(r + touchSlopPx)
    val withinY = point.y in (t - touchSlopPx)..(b + touchSlopPx)
    val edge = when {
        nearTop && withinX -> CropHandle.Top
        nearBottom && withinX -> CropHandle.Bottom
        nearLeft && withinY -> CropHandle.Left
        nearRight && withinY -> CropHandle.Right
        else -> null
    }
    if (edge != null) return edge

    return if (withinX && withinY) CropHandle.Inside else null
}
