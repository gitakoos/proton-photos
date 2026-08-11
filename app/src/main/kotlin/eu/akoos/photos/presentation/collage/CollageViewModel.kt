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

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.util.ProtonPhotosStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import java.util.Collections
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** True when the item is a video: a collage can't use one, since its "full res" is the whole video
 *  file, not a decodable image. Videos are dropped from the selection even though they show a thumb. */
internal fun GalleryItem.isVideo(): Boolean {
    val mime = when (this) {
        is GalleryItem.CloudOnly -> cloud.mimeType
        is GalleryItem.Synced -> cloud.mimeType
        is GalleryItem.LocalOnly -> local.mimeType
    }
    return mime.startsWith("video/", ignoreCase = true)
}

/** A stable key per selected photo, so its preview bitmap survives a template or aspect change. */
internal fun GalleryItem.collageKey(): String = when (this) {
    is GalleryItem.LocalOnly -> "l:${local.uri}"
    is GalleryItem.Synced -> "s:${cloud.linkId}"
    is GalleryItem.CloudOnly -> "c:${cloud.linkId}"
}

/**
 * A photo's pan/zoom within its cell, chosen by the user with a pinch. [scale] is a multiplier on top
 * of the base cover-crop (1f = the plain center-crop). [offsetX]/[offsetY] are the pan as a FRACTION
 * of the cell's own width/height, NOT pixels, so the exact same framing renders into both the on-screen
 * preview and the much larger export canvas; the cell just multiplies the fraction by its real size.
 */
data class CollageCellTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/**
 * A photo's placement on the FREEFORM canvas, chosen by hand (drag to move, pinch to resize, twist to
 * rotate). [cx]/[cy] are the photo centre as a fraction of the canvas, [scale] is its long side as a
 * fraction of the canvas width, [rotation] is in degrees, and [z] is the draw order (higher is on top,
 * bumped when a photo is tapped). Fractions, like the cell transform, so the same placement renders into
 * both the preview and the larger export canvas.
 */
data class FreeformTransform(
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val scale: Float = 0.5f,
    val rotation: Float = 0f,
    val z: Int = 0,
)

/** Result of an export attempt, consumed once by the screen (toast + leave on success). */
enum class CollageExportEvent { SUCCESS, FAILURE }

/** What fills the gaps and the area a zoomed-out photo doesn't cover: a flat colour, or a blurred
 *  copy of each cell's own photo (the "frosted" look). */
enum class CollageBackground { COLOR, BLUR }

/**
 * The collage editor's state. [aspect] `null` is the "Original" choice, which renders at
 * [originalRatio] (the first photo's width / height); a non-null [aspect] renders at its fixed ratio.
 * [previews] holds a downscaled bitmap per photo, keyed by [collageKey], filled in as they decode.
 * [transforms] holds the per-cell pan/zoom, also keyed by [collageKey] so a photo keeps its framing
 * when a swap moves it to another cell. [focusedKey] is the tapped cell (its actions replace the
 * aspect/template rows); [swapSourceKey] is non-null while waiting for the second cell of a swap.
 */
data class CollageUiState(
    val items: List<GalleryItem> = emptyList(),
    val templates: List<CollageTemplate> = emptyList(),
    val template: CollageTemplate? = null,
    val aspect: CollageAspect? = null,
    val originalRatio: Float = 1f,
    val previews: Map<String, Bitmap> = emptyMap(),
    val transforms: Map<String, CollageCellTransform> = emptyMap(),
    val freeformTransforms: Map<String, FreeformTransform> = emptyMap(),
    val spacing: Float = 0f,
    val groutColor: Int = android.graphics.Color.WHITE,
    val background: CollageBackground = CollageBackground.COLOR,
    val name: String = "Collage",
    val focusedKey: String? = null,
    val swapSourceKey: String? = null,
    val replaceTargetKey: String? = null,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportEvent: CollageExportEvent? = null,
) {
    /** width / height the canvas should render at. */
    val renderRatio: Float get() = (aspect?.ratio ?: originalRatio).coerceIn(0.2f, 5f)

    /** True while the second cell of a swap is being awaited. */
    val inSwapMode: Boolean get() = swapSourceKey != null

    /** The current layout places photos by hand on a free canvas rather than into fixed cells. */
    val isFreeform: Boolean get() = template?.freeform == true

    /** Most photos this layout takes: freeform holds more than a grid's fixed cells. */
    val photoCap: Int get() = if (isFreeform) COLLAGE_FREEFORM_MAX else COLLAGE_MAX_PHOTOS

    /** How many photos are over the grid cap, so the layout row can prompt trimming down to a grid. */
    val overGridCap: Int get() = (items.size - COLLAGE_MAX_PHOTOS).coerceAtLeast(0)

    /** Delete is blocked once only the minimum remains, so a collage never drops below two photos. */
    val canDelete: Boolean get() = items.size > COLLAGE_MIN_PHOTOS

    /** Room for more photos, so the "+" affordance disables at the cap. */
    val canAddMore: Boolean get() = items.size < photoCap
}

/**
 * Backs the collage editor. Holds the chosen photos (capped at [COLLAGE_MAX_PHOTOS]), the current
 * layout + output aspect, a downscaled preview bitmap per photo, and the per-cell pan/zoom. Previews
 * decode ONE AT A TIME off [CollageBitmapSource] so the editor never holds several large bitmaps at
 * once, matching the app's tight-heap discipline. Export renders full-resolution ONE photo at a time
 * into a single output bitmap, then saves it as a new device photo.
 */
@HiltViewModel
class CollageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bitmapSource: CollageBitmapSource,
    private val accountManager: AccountManager,
) : ViewModel() {

    private val _state = MutableStateFlow(CollageUiState())
    val state: StateFlow<CollageUiState> = _state.asStateFlow()

    private var loaded = false

    /** Load the selected photos once (survives recomposition / config changes via the guard). */
    fun load(selected: List<GalleryItem>) {
        if (loaded) return
        loaded = true
        val items = selected.filterNot { it.isVideo() }.take(COLLAGE_MAX_PHOTOS)
        val templates = templatesFor(items.size)
        _state.update { it.copy(items = items, templates = templates, template = templates.firstOrNull()) }
        loadPreviews(items, firstIsCover = true)
    }

    /** The grid layouts for [count] photos plus the freeform layout, which is always available. */
    private fun templatesFor(count: Int): List<CollageTemplate> =
        collageTemplatesFor(count) + freeformCollageTemplate()

    /** Set the output aspect; `null` selects "Original" (the first photo's own ratio). */
    fun setAspect(aspect: CollageAspect?) = _state.update { it.copy(aspect = aspect) }

    /** Pick a layout. Switching to freeform seeds a hand-placement for any photo that lacks one. */
    fun setTemplate(template: CollageTemplate) = _state.update { s ->
        if (template.freeform) {
            s.copy(template = template, freeformTransforms = ensureFreeform(s.items, s.freeformTransforms))
        } else {
            s.copy(template = template)
        }
    }

    /** Keep each photo's existing hand placement, and scatter any new one into a loose tilted grid so
     *  nothing lands exactly on top of another. */
    private fun ensureFreeform(
        items: List<GalleryItem>,
        existing: Map<String, FreeformTransform>,
    ): Map<String, FreeformTransform> {
        val n = items.size.coerceAtLeast(1)
        val cols = ceil(sqrt(n.toFloat())).toInt().coerceAtLeast(1)
        val rows = ceil(n.toFloat() / cols).toInt().coerceAtLeast(1)
        var maxZ = existing.values.maxOfOrNull { it.z } ?: 0
        val result = LinkedHashMap<String, FreeformTransform>()
        items.forEachIndexed { i, item ->
            val key = item.collageKey()
            val cur = existing[key]
            if (cur != null) {
                result[key] = cur
            } else {
                val col = i % cols
                val row = i / cols
                result[key] = FreeformTransform(
                    cx = (col + 0.5f) / cols,
                    cy = (row + 0.5f) / rows,
                    scale = (0.9f / cols).coerceIn(0.3f, 0.6f),
                    rotation = ((i % 3) - 1) * 6f,
                    z = ++maxZ,
                )
            }
        }
        return result
    }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    /** Gap between cells, as a fraction of the shorter output side (0 = edge-to-edge). */
    fun setSpacing(value: Float) = _state.update { it.copy(spacing = value.coerceIn(0f, MAX_SPACING)) }

    /** The colour of the gaps between cells and the outer border (an ARGB int). */
    fun setGroutColor(color: Int) = _state.update { it.copy(groutColor = color) }

    /** Switch the background between a flat colour and a blurred copy of each photo. */
    fun setBackground(background: CollageBackground) = _state.update { it.copy(background = background) }

    /** Swap the photos in two cells (the drop of a long-press drag). */
    fun swapItems(fromKey: String, toKey: String) = _state.update { s ->
        if (fromKey == toKey) return@update s
        val i = s.items.indexOfFirst { it.collageKey() == fromKey }
        val j = s.items.indexOfFirst { it.collageKey() == toKey }
        if (i < 0 || j < 0) return@update s
        s.copy(items = s.items.toMutableList().also { Collections.swap(it, i, j) })
    }

    /** A long-press arms this cell for a swap: the next tapped cell trades places with it. */
    fun armSwap(key: String) = _state.update { it.copy(swapSourceKey = key, focusedKey = null) }

    /** The per-cell swap button: first tap arms the cell, a second on another cell trades places,
     *  a second on the same cell cancels. */
    fun onSwapIcon(key: String) = _state.update { s ->
        val from = s.swapSourceKey
        if (from == null) return@update s.copy(swapSourceKey = key)
        if (from == key) return@update s.copy(swapSourceKey = null)
        val i = s.items.indexOfFirst { it.collageKey() == from }
        val j = s.items.indexOfFirst { it.collageKey() == key }
        if (i < 0 || j < 0) return@update s.copy(swapSourceKey = null)
        s.copy(items = s.items.toMutableList().also { Collections.swap(it, i, j) }, swapSourceKey = null)
    }

    // ── Cell focus / swap ─────────────────────────────────────────────────────

    /** A cell tap: in swap mode it completes (or cancels) the swap, otherwise it focuses the cell. */
    fun onCellTapped(key: String) = _state.update { s ->
        val swapFrom = s.swapSourceKey
        if (swapFrom != null) {
            if (swapFrom == key) return@update s.copy(swapSourceKey = null) // tapped the source again → cancel
            val i = s.items.indexOfFirst { it.collageKey() == swapFrom }
            val j = s.items.indexOfFirst { it.collageKey() == key }
            if (i < 0 || j < 0) return@update s.copy(swapSourceKey = null, focusedKey = null)
            val swapped = s.items.toMutableList().also { Collections.swap(it, i, j) }
            s.copy(items = swapped, swapSourceKey = null, focusedKey = null)
        } else {
            s.copy(focusedKey = key)
        }
    }

    /** Arm a swap: the next cell tapped trades places with the focused one. */
    fun beginSwap() = _state.update { s ->
        val key = s.focusedKey ?: return@update s
        s.copy(swapSourceKey = key)
    }

    /** Clear any focus and cancel a pending swap or armed replace (tapping the background, or Done). */
    fun clearFocus() = _state.update { it.copy(focusedKey = null, swapSourceKey = null, replaceTargetKey = null) }

    /** Disarm a pending replace so a following photo pick adds rather than replaces (the top + button). */
    fun clearReplaceTarget() = _state.update { it.copy(replaceTargetKey = null) }

    /**
     * Accumulate a pinch onto the focused cell's transform. [zoom] is multiplicative; [panFractionX]/
     * [panFractionY] are the drag as a fraction of the cell size. The pan is clamped so the scaled
     * photo always covers the cell (no empty corner shows), which also snaps pan back to zero at 1x.
     */
    fun nudgeCellTransform(
        key: String,
        zoom: Float,
        panFractionX: Float,
        panFractionY: Float,
        imageAspect: Float,
        cellAspect: Float,
    ) = _state.update { s ->
        val cur = s.transforms[key] ?: CollageCellTransform()
        val newScale = (cur.scale * zoom).coerceIn(MIN_CELL_SCALE, MAX_CELL_SCALE)
        // The photo covers the cell, so even at 1x it overflows on one axis and can pan there; zoom
        // widens the range on both. Clamp so the cell is never left with an uncovered (grout) edge.
        val safeImg = if (imageAspect > 0f) imageAspect else 1f
        val maxX = (maxOf(1f, safeImg / cellAspect) * newScale - 1f).coerceAtLeast(0f) / 2f
        val maxY = (maxOf(1f, cellAspect / safeImg) * newScale - 1f).coerceAtLeast(0f) / 2f
        val nx = (cur.offsetX + panFractionX).coerceIn(-maxX, maxX)
        val ny = (cur.offsetY + panFractionY).coerceIn(-maxY, maxY)
        s.copy(transforms = s.transforms + (key to CollageCellTransform(newScale, nx, ny)))
    }

    /** Move / resize / rotate a freeform photo. [panFractionX]/[panFractionY] shift its centre as a
     *  fraction of the canvas, [zoom] scales it, [rotationDelta] twists it (degrees). */
    fun nudgeFreeform(
        key: String,
        panFractionX: Float,
        panFractionY: Float,
        zoom: Float,
        rotationDelta: Float,
    ) = _state.update { s ->
        val cur = s.freeformTransforms[key] ?: FreeformTransform()
        val next = cur.copy(
            cx = (cur.cx + panFractionX).coerceIn(0f, 1f),
            cy = (cur.cy + panFractionY).coerceIn(0f, 1f),
            scale = (cur.scale * zoom).coerceIn(FREEFORM_MIN_SCALE, FREEFORM_MAX_SCALE),
            rotation = cur.rotation + rotationDelta,
        )
        s.copy(freeformTransforms = s.freeformTransforms + (key to next))
    }

    /** A freeform tap: focus the photo (for the top swap/remove bar) and raise it above the others. */
    fun onFreeformTap(key: String) = _state.update { s ->
        val maxZ = s.freeformTransforms.values.maxOfOrNull { it.z } ?: 0
        val cur = s.freeformTransforms[key]
        val ff = if (cur != null && cur.z != maxZ) {
            s.freeformTransforms + (key to cur.copy(z = maxZ + 1))
        } else {
            s.freeformTransforms
        }
        s.copy(focusedKey = key, freeformTransforms = ff)
    }

    // ── Add / delete ──────────────────────────────────────────────────────────

    /** Remove [key]'s photo, recompute the layout for the smaller count. No-op at the minimum. */
    fun removeItem(key: String) = _state.update { s ->
        if (s.items.size <= COLLAGE_MIN_PHOTOS) return@update s
        val remaining = s.items.filterNot { it.collageKey() == key }
        val templates = templatesFor(remaining.size)
        s.copy(
            items = remaining,
            templates = templates,
            template = pickTemplate(s.template, templates),
            previews = s.previews - key,
            transforms = s.transforms - key,
            freeformTransforms = s.freeformTransforms - key,
            focusedKey = null,
            swapSourceKey = null,
        )
    }

    /** Remove the focused photo, recompute the layout for the smaller count, and clear focus. */
    fun deleteFocusedCell() = _state.update { s ->
        val key = s.focusedKey ?: return@update s
        if (s.items.size <= COLLAGE_MIN_PHOTOS) return@update s
        val remaining = s.items.filterNot { it.collageKey() == key }
        val templates = templatesFor(remaining.size)
        s.copy(
            items = remaining,
            templates = templates,
            template = pickTemplate(s.template, templates),
            previews = s.previews - key,
            transforms = s.transforms - key,
            freeformTransforms = s.freeformTransforms - key,
            focusedKey = null,
            swapSourceKey = null,
        )
    }

    /** Add picked photos (skipping duplicates and anything past the cap), recompute the layout, and
     *  decode previews for the ones actually added. */
    fun addItems(newItems: List<GalleryItem>) {
        val s = _state.value
        val existing = s.items.map { it.collageKey() }.toSet()
        val room = (s.photoCap - s.items.size).coerceAtLeast(0)
        val toAdd = newItems.filterNot { it.isVideo() }.filter { it.collageKey() !in existing }.take(room)
        if (toAdd.isEmpty()) return
        val merged = s.items + toAdd
        val templates = templatesFor(merged.size)
        _state.update {
            val ff = if (it.isFreeform) ensureFreeform(merged, it.freeformTransforms) else it.freeformTransforms
            it.copy(
                items = merged,
                templates = templates,
                template = pickTemplate(it.template, templates),
                freeformTransforms = ff,
            )
        }
        loadPreviews(toAdd, firstIsCover = false)
    }

    /** Mark the focused cell as the one a following photo pick should replace (rather than add to). */
    fun requestReplace() = _state.update { it.copy(replaceTargetKey = it.focusedKey) }

    /** Route a photo pick: replace the armed cell if one is waiting, otherwise add the photos. */
    fun onPickerResult(picked: List<GalleryItem>) {
        val target = _state.value.replaceTargetKey
        val newItem = picked.firstOrNull { !it.isVideo() }
        if (target != null && newItem != null) replaceItem(target, newItem) else addItems(picked)
    }

    /** Swap [key]'s photo for an externally picked [newItem], keeping the same cell and layout. */
    fun replaceItem(key: String, newItem: GalleryItem) {
        val s = _state.value
        val idx = s.items.indexOfFirst { it.collageKey() == key }
        if (idx < 0 || newItem.isVideo()) {
            _state.update { it.copy(focusedKey = null, replaceTargetKey = null) }
            return
        }
        // Already in the collage: nothing to replace with, just drop the focus.
        if (s.items.any { it.collageKey() == newItem.collageKey() }) {
            _state.update { it.copy(focusedKey = null, replaceTargetKey = null) }
            return
        }
        val newItems = s.items.toMutableList().also { it[idx] = newItem }
        val newKey = newItem.collageKey()
        _state.update {
            // In freeform the replacement takes the old photo's exact placement, so it lands in place.
            val ff = it.freeformTransforms[key]
                ?.let { t -> (it.freeformTransforms - key) + (newKey to t) }
                ?: it.freeformTransforms
            it.copy(
                items = newItems,
                previews = it.previews - key,
                transforms = it.transforms - key,
                freeformTransforms = ff,
                focusedKey = null,
                replaceTargetKey = null,
            )
        }
        loadPreviews(listOf(newItem), firstIsCover = idx == 0)
    }

    // ── Export ──────────────────────────────────────────────────────────────────

    /** Render the collage full-resolution and save it as a new device photo. */
    fun export() {
        val s = _state.value
        val template = s.template ?: return
        if (s.isExporting || s.items.isEmpty()) return
        _state.update { it.copy(isExporting = true, exportProgress = 0f) }
        viewModelScope.launch {
            val ok = runCatching {
                val userId = accountManager.getPrimaryUserId().first()
                withTimeoutOrNull(EXPORT_TIMEOUT_MS) {
                    renderAndSave(s.items, template, s.renderRatio, s.transforms, s.freeformTransforms, s.spacing, s.groutColor, s.background, s.name, userId)
                } ?: run { Log.w(TAG, "export: timed out after ${EXPORT_TIMEOUT_MS}ms"); false }
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "export: failed", e)
                false
            }
            _state.update {
                it.copy(
                    isExporting = false,
                    exportEvent = if (ok) CollageExportEvent.SUCCESS else CollageExportEvent.FAILURE,
                )
            }
        }
    }

    /** Acknowledge the one-shot export event so it does not fire again on recomposition. */
    fun consumeExportEvent() = _state.update { it.copy(exportEvent = null) }

    // ── Internals ───────────────────────────────────────────────────────────────

    /** Keep the current layout when one of the same id still exists for the new count, else the
     *  balanced default. A count change never keeps the old id (its cell count no longer matches),
     *  so this reduces to the default there while a re-pick at the same count is preserved. */
    private fun pickTemplate(current: CollageTemplate?, candidates: List<CollageTemplate>): CollageTemplate? =
        candidates.firstOrNull { it.id == current?.id } ?: candidates.firstOrNull()

    /** Decode previews for [items] one at a time. When [firstIsCover] the first item also seeds the
     *  "Original" aspect ratio from its own dimensions. */
    private fun loadPreviews(items: List<GalleryItem>, firstIsCover: Boolean) {
        viewModelScope.launch {
            items.forEachIndexed { index, item ->
                val bmp = bitmapSource.preview(item, PREVIEW_MAX_PX) ?: return@forEachIndexed
                _state.update { s ->
                    val ratio = if (firstIsCover && index == 0 && bmp.height > 0) {
                        bmp.width.toFloat() / bmp.height.toFloat()
                    } else {
                        s.originalRatio
                    }
                    s.copy(previews = s.previews + (item.collageKey() to bmp), originalRatio = ratio)
                }
            }
        }
    }

    /** Draw every cell into one output bitmap, decoding each original ONE AT A TIME and recycling it
     *  before the next, then persist the result. Returns true on a successful save. */
    private suspend fun renderAndSave(
        items: List<GalleryItem>,
        template: CollageTemplate,
        ratio: Float,
        transforms: Map<String, CollageCellTransform>,
        freeform: Map<String, FreeformTransform>,
        spacing: Float,
        groutColor: Int,
        background: CollageBackground,
        name: String,
        userId: UserId?,
    ): Boolean = withContext(Dispatchers.IO) {
        val (outW, outH) = outputSize(ratio)
        val out = try {
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return@withContext false
        }
        val canvas = android.graphics.Canvas(out)
        // The grout shows in the gaps and as the outer border when spacing is on; when it is off the
        // cells cover it edge-to-edge, so it also just fills any hairline seam.
        canvas.drawColor(groutColor)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // A missing UserId only blocks cloud-only downloads; local decodes ignore it, so pass a
        // harmless empty id rather than skipping the whole export.
        val uid = userId ?: UserId("")
        // Blur background: a blurred cover of the first photo behind everything, so the gaps and any
        // zoomed-out cell sit on a frosted backdrop instead of the flat colour.
        if (background == CollageBackground.BLUR) {
            val firstBmp = items.firstOrNull()?.let { bitmapSource.fullRes(it, uid, 1600) }
            if (firstBmp != null) {
                drawBlurredCover(canvas, paint, firstBmp, 0f, 0f, outW.toFloat(), outH.toFloat())
                firstBmp.recycle()
            }
        }
        if (template.freeform) {
            // Draw each hand-placed photo whole, back to front, one full-res decode at a time.
            val ordered = items.sortedBy { freeform[it.collageKey()]?.z ?: 0 }
            ordered.forEachIndexed { drawIndex, item ->
                val t = freeform[item.collageKey()] ?: FreeformTransform()
                val need = (t.scale * outW).roundToInt().coerceIn(256, EXPORT_MAX_PX)
                val bmp = bitmapSource.fullRes(item, uid, need) ?: return@forEachIndexed
                drawFreeformItem(canvas, paint, bmp, t, outW.toFloat(), outH.toFloat())
                bmp.recycle()
                _state.update { it.copy(exportProgress = (drawIndex + 1f) / items.size.coerceAtLeast(1)) }
            }
        } else {
            val gap = spacing * minOf(outW, outH)
            template.cells.forEachIndexed { index, cell ->
                val item = items.getOrNull(index) ?: return@forEachIndexed
                val left = cell.left * outW + gap / 2f
                val top = cell.top * outH + gap / 2f
                val cellW = cell.width * outW - gap
                val cellH = cell.height * outH - gap
                if (cellW < 1f || cellH < 1f) return@forEachIndexed
                val transform = transforms[item.collageKey()] ?: CollageCellTransform()
                val need = (maxOf(cellW, cellH) * transform.scale).roundToInt().coerceIn(256, EXPORT_MAX_PX)
                val bmp = bitmapSource.fullRes(item, uid, need)
                if (bmp == null) {
                    Log.w(TAG, "export: cell $index fullRes returned null")
                    return@forEachIndexed
                }
                drawCell(canvas, paint, bmp, left, top, cellW, cellH, transform)
                bmp.recycle()
                _state.update { it.copy(exportProgress = (index + 1f) / template.cellCount) }
            }
        }
        val saved = runCatching { saveToMediaStore(out, name) }.isSuccess
        out.recycle()
        saved
    }

    /** Fills the cell with a heavily blurred copy of the photo (a downscale then upscale, a cheap blur
     *  that needs no API-31 RenderEffect), so a zoomed-out photo sits on its own frosted backdrop. */
    private fun drawBlurredCover(
        canvas: android.graphics.Canvas,
        paint: Paint,
        bmp: Bitmap,
        left: Float,
        top: Float,
        cellW: Float,
        cellH: Float,
    ) {
        val iw = bmp.width.toFloat()
        val ih = bmp.height.toFloat()
        if (iw <= 0f || ih <= 0f) return
        // Downscale to a working size relative to the DESTINATION (not the source), then upscale to
        // cover. This keeps recognisable soft shapes instead of the pixelated smear a fixed 1/24 source
        // downscale produced (which read as a blurred thumbnail), and holds a steady blur strength no
        // matter the source or output resolution.
        val targetLong = (maxOf(cellW, cellH) / 10f).coerceIn(96f, 320f)
        val srcScale = (targetLong / maxOf(iw, ih)).coerceAtMost(1f)
        val tw = (iw * srcScale).roundToInt().coerceAtLeast(2)
        val th = (ih * srcScale).roundToInt().coerceAtLeast(2)
        val tiny = runCatching { Bitmap.createScaledBitmap(bmp, tw, th, true) }.getOrNull() ?: return
        val cover = maxOf(cellW / iw, cellH / ih)
        val drawW = iw * cover
        val drawH = ih * cover
        val matrix = Matrix().apply {
            setScale(drawW / tw, drawH / th)
            postTranslate(left + cellW / 2f - drawW / 2f, top + cellH / 2f - drawH / 2f)
        }
        canvas.save()
        canvas.clipRect(left, top, left + cellW, top + cellH)
        canvas.drawBitmap(tiny, matrix, paint)
        canvas.restore()
        tiny.recycle()
    }

    /** Draw a freeform photo whole (never cropped): its long side is [t].scale of the canvas width,
     *  centred at ([t].cx, [t].cy) as canvas fractions and rotated by [t].rotation, matching the
     *  on-screen placement exactly. */
    private fun drawFreeformItem(
        canvas: android.graphics.Canvas,
        paint: Paint,
        bmp: Bitmap,
        t: FreeformTransform,
        outW: Float,
        outH: Float,
    ) {
        val iw = bmp.width.toFloat()
        val ih = bmp.height.toFloat()
        if (iw <= 0f || ih <= 0f) return
        val longSide = t.scale * outW
        val aspect = iw / ih
        val w = if (aspect >= 1f) longSide else longSide * aspect
        val h = if (aspect >= 1f) longSide / aspect else longSide
        val cx = t.cx * outW
        val cy = t.cy * outH
        val dst = android.graphics.RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.save()
        canvas.rotate(t.rotation, cx, cy)
        canvas.drawBitmap(bmp, null, dst, paint)
        canvas.restore()
    }

    /** Center-crop [bmp] to cover the cell, then apply the cell's pan/zoom, matching the on-screen
     *  viewport draw exactly (scale about the cell centre, then translate). */
    private fun drawCell(
        canvas: android.graphics.Canvas,
        paint: Paint,
        bmp: Bitmap,
        left: Float,
        top: Float,
        cellW: Float,
        cellH: Float,
        transform: CollageCellTransform,
    ) {
        val iw = bmp.width.toFloat()
        val ih = bmp.height.toFloat()
        if (iw <= 0f || ih <= 0f) return
        val cover = maxOf(cellW / iw, cellH / ih)
        val total = cover * transform.scale
        val drawW = iw * total
        val drawH = ih * total
        val centerX = left + cellW / 2f + transform.offsetX * cellW
        val centerY = top + cellH / 2f + transform.offsetY * cellH
        val matrix = Matrix().apply {
            setScale(total, total)
            postTranslate(centerX - drawW / 2f, centerY - drawH / 2f)
        }
        canvas.save()
        canvas.clipRect(left, top, left + cellW, top + cellH)
        canvas.drawBitmap(bmp, matrix, paint)
        canvas.restore()
    }

    /** Output pixel size for [ratio], long side bounded to [EXPORT_MAX_PX] to stay OOM-safe. */
    private fun outputSize(ratio: Float): Pair<Int, Int> = if (ratio >= 1f) {
        EXPORT_MAX_PX to (EXPORT_MAX_PX / ratio).roundToInt().coerceAtLeast(1)
    } else {
        (EXPORT_MAX_PX * ratio).roundToInt().coerceAtLeast(1) to EXPORT_MAX_PX
    }

    /** Insert a fresh JPEG into the device gallery (Pictures), mirroring the editor's local-copy save. */
    private fun saveToMediaStore(bitmap: Bitmap, name: String): Uri {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileNameFor(name))
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.Images.Media.DATE_MODIFIED, now / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, ProtonPhotosStorage.DEFAULT_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(collection, values) ?: error("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, EXPORT_QUALITY, out)
        } ?: error("openOutputStream returned null")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        runCatching { context.contentResolver.notifyChange(uri, null) }
        return uri
    }

    /** A filesystem-safe `<name>.jpg`, falling back to a default when the field is blank. */
    private fun fileNameFor(name: String): String {
        val base = name.trim().ifBlank { "Collage" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (base.endsWith(".jpg", true) || base.endsWith(".jpeg", true)) base else "$base.jpg"
    }

    companion object {
        /** Long-side cap for a preview bitmap: big enough to look sharp on screen, small enough that
         *  several fit the heap at once (~3 MB each at ARGB_8888). */
        const val PREVIEW_MAX_PX = 1024

        /** Long-side cap for the exported collage: bounds the single output bitmap and each decoded
         *  source so the render stays within the app's tight heap. */
        const val EXPORT_MAX_PX = 2560
        const val EXPORT_QUALITY = 92

        const val MIN_CELL_SCALE = 0.3f
        const val MAX_CELL_SCALE = 4f

        /** A freeform photo's long side as a fraction of the canvas width: from a small stamp to just
         *  past full width. */
        const val FREEFORM_MIN_SCALE = 0.12f
        const val FREEFORM_MAX_SCALE = 1.5f

        /** Widest gap between cells, as a fraction of the shorter side. */
        const val MAX_SPACING = 0.06f

        private const val TAG = "CollageViewModel"

        /** Cap the whole export so a stuck full-res download fails gracefully instead of spinning. */
        private const val EXPORT_TIMEOUT_MS = 90_000L
    }
}
