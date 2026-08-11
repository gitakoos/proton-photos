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

package eu.akoos.photos.presentation.collage

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg1
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import kotlin.math.roundToInt

/** The house pill capsule, matching the shape the gallery and editor use for their action pills. */
private val pillShape = RoundedCornerShape(999.dp)

/** The bottom tab that decides which control row is shown: the layouts, the output shape, or the border. */
private enum class CollageTab { LAYOUT, ASPECT, BORDER }

/** The border/grout colour swatches offered on the Border tab (ARGB). */
private val GROUT_COLORS = listOf(
    0xFFFFFFFFL, 0xFF000000L, 0xFF9E9E9EL, 0xFFF44336L, 0xFFFF9800L,
    0xFFFFEB3BL, 0xFF4CAF50L, 0xFF2196F3L, 0xFF9C27B0L,
)

/**
 * Full-screen collage editor. Arranges the chosen photos by the current [CollageTemplate] at the
 * chosen [CollageAspect]; tap a cell to focus it (pinch to reframe, then delete or swap), add more
 * with the picker, name it, and save the full-resolution result as a new photo.
 */
@Composable
fun CollageScreen(
    items: List<GalleryItem>,
    onClose: () -> Unit,
    onRequestAddPhotos: () -> Unit = {},
    pendingAdd: List<GalleryItem> = emptyList(),
    onPendingAddConsumed: () -> Unit = {},
    viewModel: CollageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var bottomTab by remember { mutableStateOf(CollageTab.LAYOUT) }
    var showSaveConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    BackHandler { showDiscardConfirm = true }
    LaunchedEffect(items) { viewModel.load(items) }
    LaunchedEffect(pendingAdd) {
        if (pendingAdd.isNotEmpty()) {
            viewModel.onPickerResult(pendingAdd)
            onPendingAddConsumed()
        }
    }
    // Entering freeform, jump to the background tab: it is the only per-photo choice freeform offers.
    LaunchedEffect(state.isFreeform) {
        if (state.isFreeform) bottomTab = CollageTab.BORDER
    }

    LaunchedEffect(state.exportEvent) {
        when (state.exportEvent) {
            CollageExportEvent.SUCCESS -> {
                Toast.makeText(context, context.getString(R.string.collage_saved), Toast.LENGTH_SHORT).show()
                viewModel.consumeExportEvent()
                onClose()
            }
            CollageExportEvent.FAILURE -> {
                Toast.makeText(context, context.getString(R.string.collage_save_failed), Toast.LENGTH_SHORT).show()
                viewModel.consumeExportEvent()
            }
            null -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(Bg0)) {
        Column(Modifier.fillMaxSize()) {
            CollageTopBar(
                name = state.name,
                onName = viewModel::setName,
                exporting = state.isExporting,
                progress = state.exportProgress,
                canAddMore = state.canAddMore,
                onAdd = {
                    viewModel.clearReplaceTarget()
                    onRequestAddPhotos()
                },
                onClose = { showDiscardConfirm = true },
                onSave = { showSaveConfirm = true },
            )

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .then(
                        if (state.focusedKey != null) Modifier.clickable { viewModel.clearFocus() } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val template = state.template
                if (template != null) {
                    val ratio = state.renderRatio
                    val boxRatio = maxWidth.value / maxHeight.value
                    val w: Dp
                    val h: Dp
                    if (boxRatio > ratio) {
                        h = maxHeight; w = maxHeight * ratio
                    } else {
                        w = maxWidth; h = maxWidth / ratio
                    }
                    if (state.isFreeform) {
                        CollageFreeformCanvas(
                            state = state,
                            canvasWidth = w,
                            canvasHeight = h,
                            background = state.background,
                            onTransform = viewModel::nudgeFreeform,
                            onTap = viewModel::onFreeformTap,
                            modifier = Modifier.width(w).height(h),
                        )
                    } else {
                        CollageCells(
                            template = template,
                            state = state,
                            canvasWidth = w,
                            canvasHeight = h,
                            canRemove = state.canDelete,
                            background = state.background,
                            onSelect = viewModel::onCellTapped,
                            onSwap = viewModel::swapItems,
                            onRemove = viewModel::removeItem,
                            onTransform = viewModel::nudgeCellTransform,
                            modifier = Modifier.width(w).height(h),
                        )
                    }
                }
                if (state.focusedKey != null) {
                    Row(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LabeledPill(
                            icon = Icons.Default.SwapHoriz,
                            label = stringResource(R.string.collage_swap),
                            onClick = {
                                viewModel.requestReplace()
                                onRequestAddPhotos()
                            },
                        )
                        LabeledPill(
                            icon = Icons.Default.Delete,
                            label = stringResource(R.string.collage_remove),
                            destructive = true,
                            enabled = state.canDelete,
                            onClick = viewModel::deleteFocusedCell,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (bottomTab) {
                    CollageTab.LAYOUT -> {
                        if (state.overGridCap > 0) {
                            Text(
                                stringResource(R.string.collage_trim_for_grid, state.overGridCap),
                                color = FgDim,
                                fontSize = 12.5.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                            )
                        }
                        TemplateRow(
                            templates = state.templates,
                            selected = state.template,
                            onPick = viewModel::setTemplate,
                        )
                    }
                    CollageTab.ASPECT -> AspectRow(selected = state.aspect, onPick = viewModel::setAspect)
                    CollageTab.BORDER -> {
                        BackgroundToggle(background = state.background, onSelect = viewModel::setBackground)
                        // Freeform photos are placed by hand, so a between-cells gap does not apply.
                        if (!state.isFreeform) {
                            SpacingSlider(value = state.spacing, onChange = viewModel::setSpacing)
                        }
                        if (state.background == CollageBackground.COLOR) {
                            GroutColorRow(selected = state.groutColor, onPick = viewModel::setGroutColor)
                        }
                    }
                }
                CollageTabBar(active = bottomTab, onSelect = { bottomTab = it })
            }
        }

        if (showSaveConfirm) {
            ConfirmDialog(
                title = stringResource(R.string.collage_save_confirm),
                message = null,
                confirmLabel = stringResource(R.string.collage_save),
                dismissLabel = stringResource(R.string.cancel),
                onConfirm = {
                    showSaveConfirm = false
                    viewModel.export()
                },
                onDismiss = { showSaveConfirm = false },
            )
        }
        if (showDiscardConfirm) {
            ConfirmDialog(
                title = stringResource(R.string.collage_discard),
                message = stringResource(R.string.collage_discard_msg),
                confirmLabel = stringResource(R.string.collage_discard_confirm),
                dismissLabel = stringResource(R.string.cancel),
                onConfirm = {
                    showDiscardConfirm = false
                    onClose()
                },
                onDismiss = { showDiscardConfirm = false },
                destructive = true,
            )
        }
    }
}

@Composable
private fun CollageTopBar(
    name: String,
    onName: (String) -> Unit,
    exporting: Boolean,
    progress: Float,
    canAddMore: Boolean,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBubble(Icons.Default.Close, stringResource(R.string.close), onClick = onClose)
        IconBubble(
            Icons.Default.Add,
            stringResource(R.string.collage_add),
            onClick = onAdd,
            enabled = canAddMore,
            tint = if (canAddMore) FgPrimary else FgMute,
        )
        BasicTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            textStyle = TextStyle(color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    inner()
                    Icon(Icons.Default.Edit, null, tint = FgMute, modifier = Modifier.size(16.dp))
                }
            },
        )
        if (exporting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "${(progress * 100).roundToInt()}%",
                    color = FgDim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
        } else {
            IconBubble(Icons.Default.Check, stringResource(R.string.collage_save), onClick = onSave, tint = Accent)
        }
    }
}

/** Lays each template cell out by its fractional rect and fills it with the photo, cover-cropped and
 *  offset by the cell's pan/zoom. Each cell takes a pinch to reframe, a tap to select it (its swap and
 *  remove actions appear at the top), and a long-press drag to swap onto another cell or off to remove. */
@Composable
private fun CollageCells(
    template: CollageTemplate,
    state: CollageUiState,
    canvasWidth: Dp,
    canvasHeight: Dp,
    canRemove: Boolean,
    background: CollageBackground,
    onSelect: (String) -> Unit,
    onSwap: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onTransform: (String, Float, Float, Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gap = minOf(canvasWidth, canvasHeight) * state.spacing
    val canvasWpx = with(density) { canvasWidth.toPx() }
    val canvasHpx = with(density) { canvasHeight.toPx() }
    val gapPx = with(density) { gap.toPx() }
    // Cell rectangles in collage-local px, so a long-press drag can find the cell it is dropped on.
    // Remembered so it is stable across the recompositions a drag triggers (a new list every frame
    // would restart the gesture and lose the drop).
    val cellRects = remember(template, state.items, canvasWpx, canvasHpx, gapPx) {
        template.cells.mapIndexedNotNull { i, c ->
            val item = state.items.getOrNull(i) ?: return@mapIndexedNotNull null
            val l = canvasWpx * c.left + gapPx / 2f
            val t = canvasHpx * c.top + gapPx / 2f
            item.collageKey() to Rect(l, t, l + (canvasWpx * c.width - gapPx), t + (canvasHpx * c.height - gapPx))
        }
    }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    val hoverKey = dragKey?.let { dk -> cellRects.firstOrNull { it.second.contains(dragPos) && it.first != dk }?.first }
    val draggingOutside = dragKey != null && canRemove && cellRects.none { it.second.contains(dragPos) }
    val firstBitmap = state.items.firstOrNull()?.let { state.previews[it.collageKey()] }
    Box(modifier.background(Color(state.groutColor))) {
        if (background == CollageBackground.BLUR && firstBitmap != null) {
            val bg = firstBitmap.asImageBitmap()
            // A blurred cover of the first photo behind everything: it fills the gaps and shows behind
            // any zoomed-out photo, replacing the flat colour.
            Canvas(Modifier.fillMaxSize().blur(28.dp)) {
                val iw = bg.width.toFloat()
                val ih = bg.height.toFloat()
                if (iw > 0f && ih > 0f) {
                    val cover = maxOf(size.width / iw, size.height / ih)
                    val dw = iw * cover
                    val dh = ih * cover
                    drawImage(
                        image = bg,
                        dstOffset = IntOffset(
                            ((size.width - dw) / 2f).roundToInt(),
                            ((size.height - dh) / 2f).roundToInt(),
                        ),
                        dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
                    )
                }
            }
        }
        template.cells.forEachIndexed { index, c ->
            val item = state.items.getOrNull(index) ?: return@forEachIndexed
            val key = item.collageKey()
            val bitmap = state.previews[key]
            val transform = state.transforms[key] ?: CollageCellTransform()
            val cellW = (canvasWidth * c.width - gap).coerceAtLeast(1.dp)
            val cellH = (canvasHeight * c.height - gap).coerceAtLeast(1.dp)
            val cellWpx = with(density) { cellW.toPx() }
            val cellHpx = with(density) { cellH.toPx() }
            val cellLeftPx = canvasWpx * c.left + gapPx / 2f
            val cellTopPx = canvasHpx * c.top + gapPx / 2f
            val imageAspect = if (bitmap != null && bitmap.height > 0) {
                bitmap.width.toFloat() / bitmap.height.toFloat()
            } else {
                1f
            }
            val cellAspect = if (cellHpx > 0f) cellWpx / cellHpx else 1f

            Box(
                Modifier
                    .offset(x = canvasWidth * c.left + gap / 2, y = canvasHeight * c.top + gap / 2)
                    .size(width = cellW, height = cellH)
                    .clipToBounds()
                    .pointerInput(key, bitmap) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            onTransform(key, zoom, pan.x / cellWpx, pan.y / cellHpx, imageAspect, cellAspect)
                        }
                    }
                    .pointerInput(key) {
                        detectTapGestures { onSelect(key) }
                    }
                    .pointerInput(key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { off ->
                                dragKey = key
                                dragPos = Offset(cellLeftPx + off.x, cellTopPx + off.y)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragPos += amount
                            },
                            onDragEnd = {
                                val from = dragKey
                                if (from != null) {
                                    val onCell = cellRects.firstOrNull { it.second.contains(dragPos) }?.first
                                    when {
                                        onCell == null -> onRemove(from) // dropped outside the collage → remove
                                        onCell != from -> onSwap(from, onCell) // dropped on another cell → swap
                                    }
                                }
                                dragKey = null
                            },
                            onDragCancel = { dragKey = null },
                        )
                    },
            ) {
                if (bitmap != null) {
                    val image = bitmap.asImageBitmap()
                    // A viewport into the FULL photo, matching the export's draw exactly. Dimmed while it
                    // is the one being dragged, so its lifted copy reads as the live one.
                    Canvas(Modifier.fillMaxSize().alpha(if (key == dragKey) 0.3f else 1f)) {
                        val iw = image.width.toFloat()
                        val ih = image.height.toFloat()
                        if (iw > 0f && ih > 0f) {
                            val cover = maxOf(size.width / iw, size.height / ih)
                            val total = cover * transform.scale
                            val drawW = iw * total
                            val drawH = ih * total
                            val cx = size.width / 2f + transform.offsetX * size.width
                            val cy = size.height / 2f + transform.offsetY * size.height
                            drawImage(
                                image = image,
                                dstOffset = IntOffset((cx - drawW / 2f).roundToInt(), (cy - drawH / 2f).roundToInt()),
                                dstSize = IntSize(drawW.roundToInt().coerceAtLeast(1), drawH.roundToInt().coerceAtLeast(1)),
                            )
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize().background(Bg2))
                }
                if (key == hoverKey || key == state.focusedKey) {
                    Box(Modifier.fillMaxSize().border(3.dp, Accent))
                }
            }
        }

        if (draggingOutside) {
            Box(
                Modifier.fillMaxSize().background(ErrorColor.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.collage_drop_remove),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // The lifted copy of the dragged photo, following the finger until it drops on a cell.
        val dk = dragKey
        val dragBmp = dk?.let { state.previews[it] }
        if (dk != null && dragBmp != null) {
            val previewPx = minOf(canvasWpx, canvasHpx) * 0.4f
            val previewDp = with(density) { previewPx.toDp() }
            val image = dragBmp.asImageBitmap()
            Box(
                Modifier
                    .offset { IntOffset((dragPos.x - previewPx / 2f).roundToInt(), (dragPos.y - previewPx / 2f).roundToInt()) }
                    .size(previewDp)
                    .clipToBounds()
                    .border(2.dp, Accent),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val iw = image.width.toFloat()
                    val ih = image.height.toFloat()
                    if (iw > 0f && ih > 0f) {
                        val cover = maxOf(size.width / iw, size.height / ih)
                        val drawW = iw * cover
                        val drawH = ih * cover
                        drawImage(
                            image = image,
                            dstOffset = IntOffset(
                                ((size.width - drawW) / 2f).roundToInt(),
                                ((size.height - drawH) / 2f).roundToInt(),
                            ),
                            dstSize = IntSize(drawW.roundToInt().coerceAtLeast(1), drawH.roundToInt().coerceAtLeast(1)),
                        )
                    }
                }
            }
        }
    }
}

/** The freeform canvas: every photo placed, scaled and rotated by hand over the chosen background. A
 *  pinch on a photo moves / resizes / rotates it; a tap selects it (and raises it) for the top bar. */
@Composable
private fun CollageFreeformCanvas(
    state: CollageUiState,
    canvasWidth: Dp,
    canvasHeight: Dp,
    background: CollageBackground,
    onTransform: (String, Float, Float, Float, Float) -> Unit,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val canvasWpx = with(density) { canvasWidth.toPx() }
    val canvasHpx = with(density) { canvasHeight.toPx() }
    val firstBitmap = state.items.firstOrNull()?.let { state.previews[it.collageKey()] }
    Box(modifier.background(Color(state.groutColor)).clipToBounds()) {
        if (background == CollageBackground.BLUR && firstBitmap != null) {
            val bg = firstBitmap.asImageBitmap()
            Canvas(Modifier.fillMaxSize().blur(28.dp)) {
                val iw = bg.width.toFloat()
                val ih = bg.height.toFloat()
                if (iw > 0f && ih > 0f) {
                    val cover = maxOf(size.width / iw, size.height / ih)
                    val dw = iw * cover
                    val dh = ih * cover
                    drawImage(
                        image = bg,
                        dstOffset = IntOffset(
                            ((size.width - dw) / 2f).roundToInt(),
                            ((size.height - dh) / 2f).roundToInt(),
                        ),
                        dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
                    )
                }
            }
        }
        state.items
            .sortedBy { state.freeformTransforms[it.collageKey()]?.z ?: 0 }
            .forEach { item ->
                val key = item.collageKey()
                val t = state.freeformTransforms[key] ?: FreeformTransform()
                val bitmap = state.previews[key]
                val aspect = if (bitmap != null && bitmap.height > 0) {
                    bitmap.width.toFloat() / bitmap.height.toFloat()
                } else {
                    1f
                }
                val longSide = canvasWidth * t.scale
                val w = if (aspect >= 1f) longSide else longSide * aspect
                val h = if (aspect >= 1f) longSide / aspect else longSide
                val wPx = with(density) { w.toPx() }
                val hPx = with(density) { h.toPx() }
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (canvasWpx * t.cx - wPx / 2f).roundToInt(),
                                (canvasHpx * t.cy - hPx / 2f).roundToInt(),
                            )
                        }
                        .size(width = w, height = h)
                        .pointerInput(key, bitmap) {
                            detectTransformGestures(panZoomLock = false) { _, pan, zoom, rotation ->
                                onTransform(key, pan.x / canvasWpx, pan.y / canvasHpx, zoom, rotation)
                            }
                        }
                        .pointerInput(key) {
                            detectTapGestures { onTap(key) }
                        },
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().rotate(t.rotation),
                            contentScale = ContentScale.FillBounds,
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(Bg2))
                    }
                    if (key == state.focusedKey) {
                        Box(Modifier.fillMaxSize().rotate(t.rotation).border(2.dp, Accent))
                    }
                }
            }
    }
}

/** The gap-between-photos slider (Collage mode). */
@Composable
private fun SpacingSlider(value: Float, onChange: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.collage_spacing), color = FgDim, fontSize = 12.5.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..CollageViewModel.MAX_SPACING,
            steps = 5,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            modifier = Modifier.weight(1f),
        )
    }
}

/** A pill with an icon and a label, in the app's selectable-pill style. */
@Composable
private fun LabeledPill(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> FgMute
        destructive -> ErrorColor
        selected -> Accent
        else -> FgPrimary
    }
    Row(
        modifier = Modifier
            .height(38.dp)
            .background(if (selected) Accent.copy(alpha = 0.18f) else PillBg, pillShape)
            .then(if (!selected) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** The output-shape row: "Original" (the first photo's own ratio) then the fixed aspect ratios. */
@Composable
private fun AspectRow(selected: CollageAspect?, onPick: (CollageAspect?) -> Unit) {
    val choices: List<CollageAspect?> = listOf(null) + CollageAspect.values().toList()
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        items(choices) { aspect ->
            val label = aspect?.let { "${it.ratioW}:${it.ratioH}" } ?: stringResource(R.string.collage_aspect_original)
            PillButton(selected = aspect == selected, onClick = { onPick(aspect) }) {
                Text(
                    label,
                    color = if (aspect == selected) Accent else FgPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** The layout row (a diagram per candidate template) with an add-photos button pinned at the end. */
@Composable
private fun TemplateRow(
    templates: List<CollageTemplate>,
    selected: CollageTemplate?,
    onPick: (CollageTemplate) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        items(templates) { template ->
            val isSelected = template.id == selected?.id
            PillButton(selected = isSelected, onClick = { onPick(template) }) {
                TemplateThumb(template, tint = if (isSelected) Accent else FgDim)
            }
        }
    }
}

/** The shared selectable-pill shell (the app's standard filter-pill recipe). */
@Composable
private fun PillButton(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .height(38.dp)
            .background(if (selected) Accent.copy(alpha = 0.18f) else PillBg, pillShape)
            .then(if (!selected) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** A small diagram of a template: each cell as a filled rect, or scattered cards for freeform. */
@Composable
private fun TemplateThumb(template: CollageTemplate, tint: Color) {
    Canvas(Modifier.size(width = 22.dp, height = 20.dp)) {
        val w = size.width
        val h = size.height
        if (template.freeform) {
            // A few overlapping cards → "place them yourself".
            val cardW = w * 0.46f
            val cardH = h * 0.52f
            listOf(Offset(0.30f, 0.32f), Offset(0.52f, 0.46f), Offset(0.40f, 0.62f)).forEach { p ->
                val cx = p.x * w
                val cy = p.y * h
                drawRect(
                    color = tint.copy(alpha = 0.5f),
                    topLeft = Offset(cx - cardW / 2f, cy - cardH / 2f),
                    size = Size(cardW, cardH),
                )
            }
        } else {
            val gap = 1.5f
            template.cells.forEach { c ->
                val left = c.left * w + gap
                val top = c.top * h + gap
                val right = c.right * w - gap
                val bottom = c.bottom * h - gap
                if (right > left && bottom > top) {
                    drawRect(color = tint, topLeft = Offset(left, top), size = Size(right - left, bottom - top))
                }
            }
        }
    }
}

/** The bottom tabs that switch the control row above them (layouts / output shape / border). One pill
 *  capsule of icon-only accent circles, spread evenly, the same recipe as the photo editor's tool bar. */
@Composable
private fun CollageTabBar(active: CollageTab, onSelect: (CollageTab) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .background(PillBgOpaque, pillShape)
            .border(0.5.dp, PillBorder, pillShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabChip(Icons.Default.GridView, stringResource(R.string.collage_tab_layout), active == CollageTab.LAYOUT) {
            onSelect(CollageTab.LAYOUT)
        }
        TabChip(Icons.Default.Crop, stringResource(R.string.collage_tab_ratio), active == CollageTab.ASPECT) {
            onSelect(CollageTab.ASPECT)
        }
        TabChip(Icons.Default.Palette, stringResource(R.string.collage_tab_border), active == CollageTab.BORDER) {
            onSelect(CollageTab.BORDER)
        }
    }
}

@Composable
private fun TabChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(if (selected) Accent.copy(alpha = 0.22f) else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Accent else FgDim, modifier = Modifier.size(22.dp))
    }
}

/** The Colour / Blur background switch on the Border tab. */
@Composable
private fun BackgroundToggle(background: CollageBackground, onSelect: (CollageBackground) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        LabeledPill(
            Icons.Default.Palette,
            stringResource(R.string.collage_bg_color),
            selected = background == CollageBackground.COLOR,
        ) { onSelect(CollageBackground.COLOR) }
        LabeledPill(
            Icons.Default.BlurOn,
            stringResource(R.string.collage_bg_blur),
            selected = background == CollageBackground.BLUR,
        ) { onSelect(CollageBackground.BLUR) }
    }
}

/** The border colour swatches (Border tab). */
@Composable
private fun GroutColorRow(selected: Int, onPick: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        items(GROUT_COLORS) { value ->
            val argb = value.toInt()
            val isSelected = argb == selected
            Box(
                Modifier
                    .size(30.dp)
                    .background(Color(value), CircleShape)
                    .border(if (isSelected) 2.dp else 0.5.dp, if (isSelected) Accent else PillBorder, CircleShape)
                    .clickable { onPick(argb) },
            )
        }
    }
}
