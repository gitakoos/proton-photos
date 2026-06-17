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

package eu.akoos.photos.presentation.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import coil.imageLoader
import coil.memory.MemoryCache
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import eu.akoos.photos.R
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.ExifHelper
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/** What we're editing — drives the save flow. */
sealed class EditorSource {
    data class Local(val uri: String, val displayName: String, val mimeType: String) : EditorSource()
    data class Cloud(val photo: CloudPhoto) : EditorSource()
    /** Photo opened from a foreign ACTION_EDIT/VIEW intent. Save is forced to copy-to-MediaStore
     *  since the foreign URI may be read-only. */
    data class External(val uri: String, val displayName: String, val mimeType: String) : EditorSource()
}

/** Built-in filter presets, each a 4x5 ColorMatrix; [labelRes] is the chip label. */
enum class FilterPreset(@androidx.annotation.StringRes val labelRes: Int) {
    None(R.string.editor_filter_original),
    BlackWhite(R.string.editor_filter_bw),
    Sepia(R.string.editor_filter_sepia),
    Vintage(R.string.editor_filter_vintage),
    Vivid(R.string.editor_filter_vivid),
    Cool(R.string.editor_filter_cool),
    Warm(R.string.editor_filter_warm),
}

/** Redact stroke mode — what to draw under the user's finger. */
enum class RedactMode { Black, Pixelate }

/**
 * A single redaction stroke. Points are in SOURCE-BITMAP coordinates so they survive
 * preview-area resizing; [brushSize] is the diameter (also in bitmap coordinates).
 */
data class RedactionStroke(
    val points: List<android.graphics.PointF>,
    val brushSize: Float,
    val mode: RedactMode,
)

data class EditorAdjustments(
    /** -100..100; 0 = unchanged. */
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    /** Multiplicative gain on RGB — bumps overall luminance like exposure compensation. */
    val exposure: Int = 0,
    /** Pulls down the bright end of the histogram without compressing midtones. */
    val highlights: Int = 0,
    /** Lifts the dark end of the histogram without crushing midtones. */
    val shadows: Int = 0,
    /** Warm (+) / cool (-) — shifts R up and B down (or vice versa). */
    val temperature: Int = 0,
    /** Green (+) / magenta (-) — shifts G up (or down). */
    val tone: Int = 0,
    val rotationDegrees: Int = 0, // 0, 90, 180, 270
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val filter: FilterPreset = FilterPreset.None,
    /** Crop in display-space coords (the rotated/flipped orientation the user sees), consumed after
     *  the pipeline's rotate step so it stays in its authoring space. null = no crop. */
    val cropRect: Rect? = null,
    /** Black-out / pixelate strokes applied after all color and geometry transforms. */
    val redactStrokes: List<RedactionStroke> = emptyList(),
)

data class EditorUiState(
    val source: EditorSource? = null,
    val originalBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    /**
     * Colour edits + rotation/flip baked in, but WITHOUT crop or redact strokes, in rotated
     * display orientation. The Crop tool renders against this so its rect lands on the right
     * canvas size; the cropped [previewBitmap] would cause out-of-bounds reads.
     */
    val adjustedBitmapNoCrop: Bitmap? = null,
    val adjustments: EditorAdjustments = EditorAdjustments(),
    val isSaving: Boolean = false,
    val saveResult: SaveResult? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    /** Latched after an [EditorSource.External] save so the screen can show "Saved as copy". */
    val savedAsCopy: Boolean = false,
    val pendingWriteIntent: android.app.PendingIntent? = null,
    /** OS delete-consent dialog ([MediaStore.createDeleteRequest]) when a Synced Overwrite falls back
     *  to copy, so the orphaned original can be removed. Mirrors [pendingWriteIntent]. */
    val pendingDeleteIntent: android.app.PendingIntent? = null,
)

sealed class SaveResult {
    data class Success(val uri: Uri?) : SaveResult()
    /** Overwrite fell back to a new file (source URI read-only); original untouched, edit at [uri]. */
    data class SuccessAsCopy(val uri: Uri?) : SaveResult()
    data class Failed(val message: String) : SaveResult()
}

/**
 * Save dialog choice. Local: [Overwrite] writes back in-place, [Copy] inserts a new MediaStore entry.
 * Cloud: [Overwrite] uploads a new linkId and trashes the old one, [Copy] uploads without touching it.
 */
enum class SaveMode { Overwrite, Copy }

@HiltViewModel
class PhotoEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: eu.akoos.photos.domain.repository.SyncStateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** When set, cloud uploads from save() are also added to this album so the edited copy
     *  appears in the same album the source photo lived in. */
    private var sourceAlbumLinkId: String? = null
    fun setSourceAlbumLinkId(linkId: String?) { sourceAlbumLinkId = linkId }

    // Undo/redo: pre-mutation EditorAdjustments snapshots, soft-capped at 30. The slider drag is a
    // draft (updateAdjustmentsFast doesn't push); finalizeAdjustments pushes it on release.
    private val undoStack: ArrayDeque<EditorAdjustments> = ArrayDeque()
    private val redoStack: ArrayDeque<EditorAdjustments> = ArrayDeque()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** Snapshot the adjustments at the moment a slider drag began — used so the whole
     *  drag (many updateAdjustmentsFast ticks) becomes ONE undo entry on release. */
    private var sliderUndoSnapshot: EditorAdjustments? = null

    private fun pushUndo(previous: EditorAdjustments) {
        undoStack.addLast(previous)
        while (undoStack.size > 30) undoStack.removeFirst()
        if (redoStack.isNotEmpty()) redoStack.clear()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private fun clearUndoStacks() {
        undoStack.clear()
        redoStack.clear()
        sliderUndoSnapshot = null
        _canUndo.value = false
        _canRedo.value = false
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val orig = _state.value.originalBitmap ?: return
        val current = _state.value.adjustments
        val previous = undoStack.removeLast()
        redoStack.addLast(current)
        while (redoStack.size > 30) redoStack.removeFirst()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        viewModelScope.launch(Dispatchers.Default) {
            val newPreview = applyAdjustments(orig, previous)
            val noCropPreview = applyColorOnly(orig, previous)
            _state.update { it.copy(
                adjustments = previous,
                previewBitmap = newPreview,
                adjustedBitmapNoCrop = noCropPreview,
            ) }
        }
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val orig = _state.value.originalBitmap ?: return
        val current = _state.value.adjustments
        val next = redoStack.removeLast()
        undoStack.addLast(current)
        while (undoStack.size > 30) undoStack.removeFirst()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        viewModelScope.launch(Dispatchers.Default) {
            val newPreview = applyAdjustments(orig, next)
            val noCropPreview = applyColorOnly(orig, next)
            _state.update { it.copy(
                adjustments = next,
                previewBitmap = newPreview,
                adjustedBitmapNoCrop = noCropPreview,
            ) }
        }
    }


    /** Downsampled (max 720px) copy of the source for fast per-tick slider/filter-chip previews. */
    private var previewSourceSmall: Bitmap? = null
    /** Most-recent slider-drag render job; cancelled on the next tick to keep only one in flight. */
    private var sliderRenderJob: kotlinx.coroutines.Job? = null

    /** Build (or return cached) downsampled source for fast previews. Max edge 720px → ~5 ms/tick. */
    private fun ensureSmallSource(src: Bitmap): Bitmap {
        previewSourceSmall?.let { return it }
        val maxEdge = 720f
        val scale = (maxEdge / maxOf(src.width, src.height)).coerceAtMost(1f)
        val small = if (scale >= 1f) src
            else Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        previewSourceSmall = small
        return small
    }

    /** On slider release: full-res re-render so the saved output matches the preview, and the whole
     *  drag is folded into one undo entry (snapshot taken at drag start, dropped if it was a no-op).
     *  Preview bitmaps are never recycled here — Compose may still draw the old one one frame past
     *  the state update, and recycling it throws "trying to use a recycled bitmap"; GC handles it. */
    fun finalizeAdjustments() {
        val orig = _state.value.originalBitmap ?: return
        val adj = _state.value.adjustments
        val snap = sliderUndoSnapshot
        sliderUndoSnapshot = null
        if (snap != null && snap != adj) pushUndo(snap)
        viewModelScope.launch(Dispatchers.Default) {
            val full = applyAdjustments(orig, adj)
            val noCropFull = applyColorOnly(orig, adj)
            _state.update { it.copy(
                previewBitmap = full,
                adjustedBitmapNoCrop = noCropFull,
            ) }
        }
    }

    /** Set for a Synced photo (device + cloud); local saves consult it to also replace the cloud copy. */
    private var cloudCounterpart: CloudPhoto? = null

    /** Mirrors [cloudCounterpart] presence so the save dialog can show a "device + cloud" subtitle. */
    private val _hasCloudCounterpart = MutableStateFlow(false)

    fun setCloudCounterpart(photo: CloudPhoto?) {
        cloudCounterpart = photo
        _hasCloudCounterpart.value = photo != null
    }

    val hasCloudCounterpart: StateFlow<Boolean> = _hasCloudCounterpart.asStateFlow()

    /** Longest-edge cap on decode: a 50 MP photo as ARGB_8888 (~200 MB) exceeds the ~100 MB a hardware
     *  Canvas can draw ("too large bitmap"). ~12 MP photos pass through untouched. */
    private val editorMaxDim = 4096

    private fun editorSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > editorMaxDim) sample *= 2
        return sample
    }

    /** Decode [uri] downsampled so its longest edge stays within [editorMaxDim]. Falls back to a
     *  plain decode if the bounds pass reports no dimensions. */
    private fun decodeDownsampled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = editorSampleSize(bounds.outWidth, bounds.outHeight)
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** File variant of [decodeDownsampled] for the downloaded cloud full-res. */
    private fun decodeDownsampled(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return BitmapFactory.decodeFile(path)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = editorSampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    fun loadLocal(uri: String, displayName: String, mimeType: String) {
        // Drop the previous photo's cached small-source, else the slider renders against the old downscale.
        previewSourceSmall = null
        clearUndoStacks()
        _state.update { it.copy(source = EditorSource.Local(uri, displayName, mimeType), isLoading = true, errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = runCatching { decodeDownsampled(Uri.parse(uri)) }.getOrNull()
            if (bmp == null) {
                _state.update { it.copy(isLoading = false,
                    errorMessage = context.getString(R.string.editor_error_load_local)) }
                return@launch
            }
            // BitmapFactory ignores EXIF orientation; bake it into pixels (save re-encodes without EXIF,
            // so baking here avoids double-rotation).
            val oriented = ExifHelper.applyOrientation(bmp, ExifHelper.readOrientation(context, uri))
            _state.update { it.copy(
                originalBitmap = oriented,
                previewBitmap = oriented,
                adjustedBitmapNoCrop = oriented,
                isLoading = false,
            ) }
        }
    }

    /** Like [loadLocal] but tags the source External so [save] always writes a fresh copy, never in-place. */
    fun loadExternal(uri: String, displayName: String, mimeType: String) {
        previewSourceSmall = null
        clearUndoStacks()
        _state.update { it.copy(
            source = EditorSource.External(uri, displayName, mimeType),
            isLoading = true,
            errorMessage = null,
            savedAsCopy = false,
        ) }
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = runCatching { decodeDownsampled(Uri.parse(uri)) }.getOrNull()
            if (bmp == null) {
                _state.update { it.copy(
                    isLoading = false,
                    errorMessage = context.getString(R.string.editor_external_load_error),
                ) }
                return@launch
            }
            // Honour EXIF orientation — see loadLocal.
            val oriented = ExifHelper.applyOrientation(bmp, ExifHelper.readOrientation(context, uri))
            _state.update { it.copy(originalBitmap = oriented, previewBitmap = oriented, isLoading = false) }
        }
    }

    fun loadCloud(photo: CloudPhoto) {
        previewSourceSmall = null
        clearUndoStacks()
        _state.update { it.copy(source = EditorSource.Cloud(photo), isLoading = true, errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                _state.update { it.copy(isLoading = false,
                    errorMessage = context.getString(R.string.editor_error_not_signed_in)) }
                return@launch
            }
            val file = runCatching { cloudRepo.downloadFullResPhoto(userId, photo) }.getOrNull()
            if (file == null || !file.exists()) {
                _state.update { it.copy(isLoading = false,
                    errorMessage = context.getString(R.string.editor_error_download_failed)) }
                return@launch
            }
            val bmp = runCatching { decodeDownsampled(file.absolutePath) }.getOrNull()
            if (bmp == null) {
                _state.update { it.copy(isLoading = false,
                    errorMessage = context.getString(R.string.editor_error_decode_failed)) }
                return@launch
            }
            // Honour EXIF orientation off the downloaded full-res file — see loadLocal.
            val oriented = ExifHelper.applyOrientation(bmp, ExifHelper.readOrientation(file))
            _state.update { it.copy(
                originalBitmap = oriented,
                previewBitmap = oriented,
                adjustedBitmapNoCrop = oriented,
                isLoading = false,
            ) }
        }
    }

    fun updateBrightness(v: Int) = updateAdjustmentsFast { it.copy(brightness = v.coerceIn(-100, 100)) }
    fun updateContrast(v: Int) = updateAdjustmentsFast { it.copy(contrast = v.coerceIn(-100, 100)) }
    fun updateSaturation(v: Int) = updateAdjustmentsFast { it.copy(saturation = v.coerceIn(-100, 100)) }
    fun updateExposure(v: Int) = updateAdjustmentsFast { it.copy(exposure = v.coerceIn(-100, 100)) }
    fun updateHighlights(v: Int) = updateAdjustmentsFast { it.copy(highlights = v.coerceIn(-100, 100)) }
    fun updateShadows(v: Int) = updateAdjustmentsFast { it.copy(shadows = v.coerceIn(-100, 100)) }
    fun updateTemperature(v: Int) = updateAdjustmentsFast { it.copy(temperature = v.coerceIn(-100, 100)) }
    fun updateTone(v: Int) = updateAdjustmentsFast { it.copy(tone = v.coerceIn(-100, 100)) }
    /** 90° CW turn. The display-space crop rect ([EditorAdjustments.cropRect]) must be carried into the
     *  new orientation, else it cuts the wrong region: (L,T,R,B) → (oldDisplayH-B, L, oldDisplayH-T, R). */
    fun rotate90Cw() {
        val orig = _state.value.originalBitmap
        updateAdjustments { adj ->
            val rotated = adj.copy(rotationDegrees = (adj.rotationDegrees + 90) % 360)
            val rect = adj.cropRect
            if (orig == null || rect == null) return@updateAdjustments rotated
            val oldH = displayHeight(orig, adj)
            rotated.copy(cropRect = Rect(oldH - rect.bottom, rect.left, oldH - rect.top, rect.right))
        }
    }
    /** Mirrors the rect horizontally within the current display width — the flip is applied
     *  in the rotated frame's axes, so display-space mirroring is correct at any rotation. */
    fun toggleFlipH() {
        val orig = _state.value.originalBitmap
        updateAdjustments { adj ->
            val flipped = adj.copy(flipHorizontal = !adj.flipHorizontal)
            val rect = adj.cropRect
            if (orig == null || rect == null) return@updateAdjustments flipped
            val w = displayWidth(orig, adj)
            flipped.copy(cropRect = Rect(w - rect.right, rect.top, w - rect.left, rect.bottom))
        }
    }
    fun toggleFlipV() {
        val orig = _state.value.originalBitmap
        updateAdjustments { adj ->
            val flipped = adj.copy(flipVertical = !adj.flipVertical)
            val rect = adj.cropRect
            if (orig == null || rect == null) return@updateAdjustments flipped
            val h = displayHeight(orig, adj)
            flipped.copy(cropRect = Rect(rect.left, h - rect.bottom, rect.right, h - rect.top))
        }
    }
    fun selectFilter(filter: FilterPreset) = updateAdjustments { it.copy(filter = filter) }
    fun applyCrop(rect: Rect?) = updateAdjustments { it.copy(cropRect = rect) }

    fun addRedactStroke(stroke: RedactionStroke) = updateAdjustments {
        it.copy(redactStrokes = it.redactStrokes + stroke)
    }
    fun undoLastRedactStroke() = updateAdjustments {
        it.copy(redactStrokes = if (it.redactStrokes.isEmpty()) it.redactStrokes else it.redactStrokes.dropLast(1))
    }
    fun clearRedactStrokes() = updateAdjustments { it.copy(redactStrokes = emptyList()) }

    fun resetAll() {
        clearUndoStacks()
        updateAdjustmentsNoUndo { EditorAdjustments() }
    }

    /** Clears the error popup so a reused NavBackStackEntry doesn't re-show it without a new failure. */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** One-tap auto-fix: reads mean luminance + spread off a 100×100 downsample and picks
     *  brightness/contrast/saturation/tonal deltas. Committed via [updateAdjustments] (undoable). */
    fun autoFix() {
        val orig = _state.value.originalBitmap ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val sample = Bitmap.createScaledBitmap(orig, 100, 100, true)
            val pixels = IntArray(100 * 100)
            sample.getPixels(pixels, 0, 100, 0, 0, 100, 100)
            var sum = 0L
            var minL = 255
            var maxL = 0
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Rec. 601 luma — cheap and good enough for histogram heuristics.
                val luma = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
                sum += luma
                if (luma < minL) minL = luma
                if (luma > maxL) maxL = luma
            }
            val mean = (sum / pixels.size.toLong()).toInt()
            val spread = maxL - minL
            val brightnessDelta = when {
                mean < 100 -> 15
                mean > 155 -> -10
                else -> 0
            }
            val contrastDelta = if (spread >= 220) 0 else 12
            val saturationDelta = 10
            // Conservative tonal nudges: raise exposure on dark scenes, pull blown highlights down,
            // lift crushed shadows. Temperature/tone stay 0 — auto-WB off one luma histogram is unreliable.
            val exposureDelta = if (mean < 90) 8 else 0
            val highlightsDelta = if (maxL > 240) -15 else 0
            val shadowsDelta = if (minL < 15) 15 else 0
            withContext(Dispatchers.Main) {
                updateAdjustments {
                    it.copy(
                        brightness = brightnessDelta.coerceIn(-100, 100),
                        contrast = contrastDelta.coerceIn(-100, 100),
                        saturation = saturationDelta.coerceIn(-100, 100),
                        exposure = exposureDelta.coerceIn(-100, 100),
                        highlights = highlightsDelta.coerceIn(-100, 100),
                        shadows = shadowsDelta.coerceIn(-100, 100),
                    )
                }
            }
        }
    }

    private fun updateAdjustments(transform: (EditorAdjustments) -> EditorAdjustments) {
        val orig = _state.value.originalBitmap ?: return
        val previous = _state.value.adjustments
        val newAdj = transform(previous)
        if (newAdj != previous) pushUndo(previous)
        viewModelScope.launch(Dispatchers.Default) {
            val newPreview = applyAdjustments(orig, newAdj)
            val noCropPreview = applyColorOnly(orig, newAdj)
            _state.update { it.copy(
                adjustments = newAdj,
                previewBitmap = newPreview,
                adjustedBitmapNoCrop = noCropPreview,
            ) }
            // No eager recycle of the old previewBitmap — see finalizeAdjustments.
        }
    }

    /** [updateAdjustments] without the undo push — for [resetAll] (already cleared stacks) and undo/redo. */
    private fun updateAdjustmentsNoUndo(transform: (EditorAdjustments) -> EditorAdjustments) {
        val orig = _state.value.originalBitmap ?: return
        val newAdj = transform(_state.value.adjustments)
        viewModelScope.launch(Dispatchers.Default) {
            val newPreview = applyAdjustments(orig, newAdj)
            val noCropPreview = applyColorOnly(orig, newAdj)
            _state.update { it.copy(
                adjustments = newAdj,
                previewBitmap = newPreview,
                adjustedBitmapNoCrop = noCropPreview,
            ) }
        }
    }

    /**
     * Fast slider variant: renders against the 720px downsample so onDrag keeps up; save always
     * re-renders full-res from [EditorUiState.originalBitmap]. Cancels the prior in-flight render.
     */
    private fun updateAdjustmentsFast(transform: (EditorAdjustments) -> EditorAdjustments) {
        val orig = _state.value.originalBitmap ?: return
        val previous = _state.value.adjustments
        // Snapshot the pre-drag state on the first tick only; finalizeAdjustments pushes it on release.
        if (sliderUndoSnapshot == null) sliderUndoSnapshot = previous
        val newAdj = transform(previous)
        sliderRenderJob?.cancel()
        sliderRenderJob = viewModelScope.launch(Dispatchers.Default) {
            val small = ensureSmallSource(orig)
            val newPreview = applyAdjustments(small, newAdj)
            _state.update { it.copy(adjustments = newAdj, previewBitmap = newPreview) }
        }
    }

    /** Source dimensions after [adj]'s rotation (swapped on 90°/270°) — the display space the crop rect lives in. */
    private fun displayWidth(source: Bitmap, adj: EditorAdjustments): Int =
        if (adj.rotationDegrees % 180 != 0) source.height else source.width
    private fun displayHeight(source: Bitmap, adj: EditorAdjustments): Int =
        if (adj.rotationDegrees % 180 != 0) source.width else source.height

    /** Applies [adj]'s rotation/flips, or returns [source] unchanged (don't treat the result as owned). */
    private fun rotateAndFlip(source: Bitmap, adj: EditorAdjustments): Bitmap {
        val matrix = Matrix().apply {
            if (adj.rotationDegrees != 0) postRotate(adj.rotationDegrees.toFloat())
            val sx = if (adj.flipHorizontal) -1f else 1f
            val sy = if (adj.flipVertical) -1f else 1f
            if (sx != 1f || sy != 1f) postScale(sx, sy)
        }
        return if (matrix.isIdentity) source
            else Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Colour matrix + rotation/flip, but not crop or redact strokes, in display orientation — what the
     * Crop overlay renders. May return [source] (don't treat as owned). Run on a background dispatcher.
     */
    private fun applyColorOnly(source: Bitmap, adj: EditorAdjustments): Bitmap {
        val oriented = rotateAndFlip(source, adj)
        val colorMatrix = buildColorMatrix(adj) ?: return oriented
        val out = Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(oriented, 0f, 0f, paint)
        return out
    }

    /**
     * Renders all adjustments onto a fresh bitmap. Heavy — call from a background dispatcher.
     * [recycleIntermediates] frees each transient stage; only the off-screen save path may set it,
     * since preview callers' bitmaps may still be held by Compose's draw pipeline.
     */
    private fun applyAdjustments(
        source: Bitmap,
        adj: EditorAdjustments,
        recycleIntermediates: Boolean = false,
    ): Bitmap {
        fun recycle(bmp: Bitmap, result: Bitmap) {
            // Never recycle the shared source or a bitmap that survives as the result.
            if (recycleIntermediates && bmp !== source && bmp !== result && !bmp.isRecycled) {
                bmp.recycle()
            }
        }

        // 1. rotate + flip BEFORE crop, so the display-space crop rect is consumed in its authoring
        //    space; cropping first would put the rect on the pre-rotation bitmap and cut the wrong region.
        val rotated = rotateAndFlip(source, adj)
        recycle(source, rotated)

        // 2. crop — clamped against the ROTATED bitmap's dimensions (display space).
        val cropped = adj.cropRect?.let {
            val safe = Rect(
                it.left.coerceIn(0, rotated.width - 1),
                it.top.coerceIn(0, rotated.height - 1),
                it.right.coerceIn(1, rotated.width),
                it.bottom.coerceIn(1, rotated.height),
            )
            if (safe.width() > 0 && safe.height() > 0)
                Bitmap.createBitmap(rotated, safe.left, safe.top, safe.width(), safe.height())
            else rotated
        } ?: rotated
        recycle(rotated, cropped)

        // 3. color matrix (brightness, contrast, saturation, filter)
        val colorMatrix = buildColorMatrix(adj)
        val colored = if (colorMatrix == null) cropped else {
            val out = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(colorMatrix)
            }
            canvas.drawBitmap(cropped, 0f, 0f, paint)
            out
        }
        recycle(cropped, colored)

        // 4. redaction strokes — drawn LAST so they cover the final visible content
        val final = if (adj.redactStrokes.isEmpty()) colored
            else applyRedactStrokes(colored, adj.redactStrokes, recycleIntermediates)
        recycle(colored, final)

        // Preview calls (recycleIntermediates=false) leave transients to GC — see finalizeAdjustments.
        return final
    }

    /**
     * Burns each stroke into [src] and returns a new bitmap.
     *
     * - [RedactMode.Black]: draws a solid black brush stroke.
     * - [RedactMode.Pixelate]: builds a heavily downscaled+upscaled copy of the bitmap,
     *   then masks it through the stroke path so only the stroke area shows the mosaic.
     */
    private fun applyRedactStrokes(
        src: Bitmap,
        strokes: List<RedactionStroke>,
        recycleIntermediates: Boolean = false,
    ): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        // Group strokes by mode so we can prepare the pixelated bitmap once.
        val pixelated by lazy {
            val downscale = 24 // larger = chunkier mosaic
            val small = Bitmap.createScaledBitmap(src, (w / downscale).coerceAtLeast(1), (h / downscale).coerceAtLeast(1), false)
            val up = Bitmap.createScaledBitmap(small, w, h, false)
            // Off the save path leave `small` to GC — see finalizeAdjustments.
            if (recycleIntermediates && small !== up && !small.isRecycled) small.recycle()
            up
        }

        for (stroke in strokes) {
            val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = stroke.brushSize
                color = android.graphics.Color.BLACK
            }
            val path = android.graphics.Path()
            stroke.points.forEachIndexed { idx, p ->
                if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            // Single-tap (one point) — render a filled circle instead of an empty path.
            if (stroke.points.size == 1) {
                val p = stroke.points[0]
                if (stroke.mode == RedactMode.Black) {
                    canvas.drawCircle(p.x, p.y, stroke.brushSize / 2f, pathPaint.apply { style = Paint.Style.FILL })
                } else {
                    val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
                    Canvas(maskBmp).drawCircle(p.x, p.y, stroke.brushSize / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK })
                    drawPixelatedThroughMask(canvas, pixelated, maskBmp)
                    // Off the save path leave the mask to GC — see finalizeAdjustments.
                    if (recycleIntermediates && !maskBmp.isRecycled) maskBmp.recycle()
                }
                continue
            }
            when (stroke.mode) {
                RedactMode.Black -> canvas.drawPath(path, pathPaint)
                RedactMode.Pixelate -> {
                    val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
                    Canvas(maskBmp).drawPath(path, pathPaint)
                    drawPixelatedThroughMask(canvas, pixelated, maskBmp)
                    if (recycleIntermediates && !maskBmp.isRecycled) maskBmp.recycle()
                }
            }
        }
        // Free `pixelated` on the save path; guard on a Pixelate stroke so we don't force the lazy here.
        if (recycleIntermediates && strokes.any { it.mode == RedactMode.Pixelate } &&
            pixelated !== out && !pixelated.isRecycled) {
            pixelated.recycle()
        }
        return out
    }

    private fun drawPixelatedThroughMask(canvas: Canvas, pixelated: Bitmap, mask: Bitmap) {
        val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val saveCount = canvas.saveLayer(null, layerPaint)
        canvas.drawBitmap(mask, 0f, 0f, null)
        val xfer = Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(pixelated, 0f, 0f, xfer)
        canvas.restoreToCount(saveCount)
    }

    private fun buildColorMatrix(adj: EditorAdjustments): ColorMatrix? {
        if (adj.brightness == 0 && adj.contrast == 0 && adj.saturation == 0
            && adj.exposure == 0 && adj.highlights == 0 && adj.shadows == 0
            && adj.temperature == 0 && adj.tone == 0
            && adj.filter == FilterPreset.None) {
            return null
        }
        val brightness = adj.brightness * 1.5f       // -150..150 range on 0..255 channel
        val contrast = 1f + adj.contrast / 100f       // 0..2 multiplier
        val saturation = 1f + adj.saturation / 100f   // 0..2 multiplier
        val translate = (1f - contrast) * 128f + brightness

        val mAdjust = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        ))
        val mSat = ColorMatrix().apply { setSaturation(saturation) }
        val mFilter = filterMatrix(adj.filter)

        val combined = ColorMatrix()
        combined.postConcat(mSat)
        combined.postConcat(mAdjust)

        // Exposure: multiplicative RGB gain (1 + exposure/100) — proportional, unlike additive brightness.
        if (adj.exposure != 0) {
            val expScale = 1f + adj.exposure / 100f
            val mExposure = ColorMatrix(floatArrayOf(
                expScale, 0f, 0f, 0f, 0f,
                0f, expScale, 0f, 0f, 0f,
                0f, 0f, expScale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ))
            combined.postConcat(mExposure)
        }

        // Highlights: scale RGB by (1 - h/200) plus a small offset — pulls brights down without crushing
        // midtones. ColorMatrix approximation of a real per-pixel highlight curve.
        if (adj.highlights != 0) {
            val hScale = 1f - adj.highlights / 200f  // -0.5..+0.5 → 1.5..0.5 scale
            val hOffset = -adj.highlights * 0.3f      // tiny additive push back
            val mHigh = ColorMatrix(floatArrayOf(
                hScale, 0f, 0f, 0f, hOffset,
                0f, hScale, 0f, 0f, hOffset,
                0f, 0f, hScale, 0f, hOffset,
                0f, 0f, 0f, 1f, 0f,
            ))
            combined.postConcat(mHigh)
        }

        // Shadows: opposite of highlights — positive scale + offset lift the dark end.
        if (adj.shadows != 0) {
            val sScale = 1f + adj.shadows / 200f      // -0.5..+0.5 → 0.5..1.5 scale
            val sOffset = adj.shadows * 0.3f          // additive lift on darks
            val mShadow = ColorMatrix(floatArrayOf(
                sScale, 0f, 0f, 0f, sOffset,
                0f, sScale, 0f, 0f, sOffset,
                0f, 0f, sScale, 0f, sOffset,
                0f, 0f, 0f, 1f, 0f,
            ))
            combined.postConcat(mShadow)
        }

        // Temperature: warm (+) shifts R up / B down, cool (-) the reverse; 0.5 scale (+100 → ±50).
        if (adj.temperature != 0) {
            val t = adj.temperature * 0.5f
            val mTemp = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, t,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, -t,
                0f, 0f, 0f, 1f, 0f,
            ))
            combined.postConcat(mTemp)
        }

        // Tone: green (+) / magenta (-) shifts only G; 0.5 scale to match temperature.
        if (adj.tone != 0) {
            val g = adj.tone * 0.5f
            val mTone = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, g,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ))
            combined.postConcat(mTone)
        }

        mFilter?.let { combined.postConcat(it) }
        return combined
    }

    private fun filterMatrix(filter: FilterPreset): ColorMatrix? = when (filter) {
        FilterPreset.None -> null
        FilterPreset.BlackWhite -> ColorMatrix().apply { setSaturation(0f) }
        FilterPreset.Sepia -> ColorMatrix(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        FilterPreset.Vintage -> ColorMatrix(floatArrayOf(
            0.9f, 0.1f, 0.1f, 0f, 20f,
            0.1f, 0.85f, 0.1f, 0f, 10f,
            0.1f, 0.2f, 0.7f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        FilterPreset.Vivid -> ColorMatrix(floatArrayOf(
            1.3f, -0.1f, -0.1f, 0f, 0f,
            -0.1f, 1.3f, -0.1f, 0f, 0f,
            -0.1f, -0.1f, 1.3f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        FilterPreset.Cool -> ColorMatrix(floatArrayOf(
            0.9f, 0f, 0.1f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0.1f, 0f, 1.1f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f,
        ))
        FilterPreset.Warm -> ColorMatrix(floatArrayOf(
            1.1f, 0f, 0f, 0f, 10f,
            0f, 1.0f, 0f, 0f, 5f,
            0f, 0f, 0.9f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    /**
     * Saves the edited bitmap per [mode] and [EditorSource]. Local: Overwrite writes back to the
     * source URI, Copy inserts a new MediaStore entry. Cloud: Overwrite uploads a new linkId then
     * trashes the original, Copy uploads without touching it.
     */
    private var pendingWriteMode: SaveMode? = null
    private var pendingWriteQuality: Int = 92

    fun save(mode: SaveMode, quality: Int = 92, allowWriteRequestRecovery: Boolean = true) {
        val s = _state.value
        val source = s.source ?: return
        val orig = s.originalBitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isSaving = true, saveResult = null) }
            // Re-render full-res from the original, not the 720px slider preview (which would degrade the
            // save). Off-screen, so intermediates are safe to recycle.
            val bitmap = applyAdjustments(orig, s.adjustments, recycleIntermediates = true)
            // One timestamp shared by the device save AND the cloud upload so they get the same filename +
            // DATE_TAKEN second; reconcile's byNameAndDate then pairs them as Synced without a download.
            val editTimestampMs = System.currentTimeMillis()
            val saveResult: SaveResult = try {
                val uri = when (source) {
                    is EditorSource.Local -> saveLocal(bitmap, source, mode, quality, editTimestampMs)
                    is EditorSource.Cloud -> saveCloud(bitmap, source, mode, quality, editTimestampMs)
                    is EditorSource.External -> {
                        // Always a fresh MediaStore copy (foreign URI, no overwrite; device-only, no upload).
                        // Forge a Local-shaped value so [insertLocalCopy] can read displayName/uri.
                        val pseudoLocal = EditorSource.Local(source.uri, source.displayName, source.mimeType)
                        val resultUri = insertLocalCopy(
                            bitmap = bitmap,
                            source = pseudoLocal,
                            quality = quality,
                            useOriginalName = false,
                            editTimestampMs = editTimestampMs,
                        )
                        if (resultUri != null) {
                            _state.update { it.copy(savedAsCopy = true) }
                        }
                        resultUri
                    }
                }
                // Invalidate Coil caches on every local save — Overwrite reuses the URI (stale bytes),
                // Copy's notifyChange wakes MediaStore observers. Cloud uploads have no existing key.
                if (uri != null && source is EditorSource.Local) {
                    invalidateImageCache(uri)
                }
                // Synced photo: also push the edit to the cloud counterpart. The MediaStore insert already
                // fired the sync observer, so an UPLOADING placeholder row claims it (Reconcile/SyncWorker
                // skip UPLOADING) — else SyncWorker would race and upload a duplicate.
                val counterpart = cloudCounterpart
                if (source is EditorSource.Local && counterpart != null && uri != null) {
                    val userId = accountManager.getPrimaryUserId().first()
                    if (userId != null) {
                        val savedUriStr = uri.toString()
                        val placeholderState = eu.akoos.photos.domain.entity.SyncState(
                            localUri = savedUriStr,
                            cloudFileId = null,
                            localHash = "",
                            cloudHash = null,
                            status = eu.akoos.photos.domain.entity.SyncStatus.UPLOADING,
                            lastSyncAttemptMs = System.currentTimeMillis(),
                            lastSyncSuccessMs = null,
                            backedUpAtMs = null,
                            sizeBytes = 0L,
                        )
                        runCatching { syncStateRepo.upsert(placeholderState, userId) }
                        val fanoutResult = runCatching {
                            uploadEditAsCloudReplacement(bitmap, counterpart, mode, quality, editTimestampMs, userId)
                        }
                        val newLinkId = fanoutResult.getOrNull()
                        if (fanoutResult.isSuccess && newLinkId != null) {
                            runCatching {
                                syncStateRepo.upsert(
                                    placeholderState.copy(
                                        cloudFileId = newLinkId,
                                        status = eu.akoos.photos.domain.entity.SyncStatus.SYNCED,
                                        lastSyncSuccessMs = System.currentTimeMillis(),
                                        backedUpAtMs = System.currentTimeMillis(),
                                    ),
                                    userId,
                                )
                            }
                        } else {
                            runCatching {
                                syncStateRepo.upsert(
                                    placeholderState.copy(status = eu.akoos.photos.domain.entity.SyncStatus.LOCAL_ONLY),
                                    userId,
                                )
                            }
                        }
                    }
                }
                SaveResult.Success(uri)
            } catch (e: SecurityException) {
                // Foreign MediaStore URI — try createWriteRequest for one-shot overwrite consent.
                // IS_PENDING/IS_TRASHED items skip to copy (the consent dialog refuses them).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && allowWriteRequestRecovery
                    && source is EditorSource.Local
                    && mode == SaveMode.Overwrite
                ) {
                    val srcUri = Uri.parse(source.uri)
                    val stuck = isItemPendingOrTrashed(srcUri)
                    if (!stuck) {
                        val request = runCatching {
                            MediaStore.createWriteRequest(context.contentResolver, listOf(srcUri))
                        }.getOrNull()
                        if (request != null) {
                            pendingWriteMode = mode
                            pendingWriteQuality = quality
                            _state.update {
                                it.copy(isSaving = false, pendingWriteIntent = request)
                            }
                            return@launch
                        }
                    }
                }
                if (source is EditorSource.Local && mode == SaveMode.Overwrite) {
                    runCatching {
                        insertLocalCopy(bitmap, source, quality, useOriginalName = true)
                    }.fold(
                        onSuccess = { uri ->
                            // Synced Overwrite that fell back to Copy strands the original next to the edit.
                            // Quiet delete works for app-owned files; foreign URIs need OS consent
                            // (createDeleteRequest, launched by the screen). Sync re-pairs by hash afterwards.
                            if (cloudCounterpart != null) {
                                val srcUri = Uri.parse(source.uri)
                                val rowsDeleted = runCatching {
                                    context.contentResolver.delete(srcUri, null, null)
                                }.getOrDefault(0)
                                if (rowsDeleted == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    val deleteRequest = runCatching {
                                        MediaStore.createDeleteRequest(
                                            context.contentResolver,
                                            listOf(srcUri),
                                        )
                                    }.getOrNull()
                                    if (deleteRequest != null) {
                                        _state.update { it.copy(pendingDeleteIntent = deleteRequest) }
                                    }
                                }
                            }
                            SaveResult.SuccessAsCopy(uri)
                        },
                        onFailure = { e2 -> SaveResult.Failed(eu.akoos.photos.util.sanitizeErrorMessage(e2.message ?: e.message)) },
                    )
                } else {
                    SaveResult.Failed(eu.akoos.photos.util.sanitizeErrorMessage(e.message ?: context.getString(R.string.editor_no_permission)))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SaveResult.Failed(eu.akoos.photos.util.sanitizeErrorMessage(e.message ?: context.getString(R.string.editor_save_failed)))
            }
            _state.update { it.copy(isSaving = false, saveResult = saveResult) }
        }
    }

    fun onWritePermissionGranted() {
        val mode = pendingWriteMode ?: return
        val quality = pendingWriteQuality
        pendingWriteMode = null
        _state.update { it.copy(pendingWriteIntent = null) }
        save(mode, quality, allowWriteRequestRecovery = false)
    }

    /** Clears the pending delete intent once the OS consent dialog closes (Allow or Deny). */
    fun onDeletePermissionResolved() {
        _state.update { it.copy(pendingDeleteIntent = null) }
    }

    fun onWritePermissionDenied() {
        pendingWriteMode = null
        _state.update {
            it.copy(
                pendingWriteIntent = null,
                isSaving = false,
                saveResult = SaveResult.Failed(context.getString(R.string.editor_save_cancelled)),
            )
        }
    }

    private fun saveLocal(bitmap: Bitmap, source: EditorSource.Local, mode: SaveMode, quality: Int, editTimestampMs: Long): Uri? {
        return when (mode) {
            SaveMode.Overwrite -> overwriteLocal(bitmap, source, quality)
            SaveMode.Copy      -> insertLocalCopy(bitmap, source, quality, editTimestampMs = editTimestampMs)
        }
    }

    // Throws SecurityException on foreign URIs (caller recovers). No IS_PENDING dance — on a foreign
    // URI it traps the file in pending state and blocks the write.
    private fun overwriteLocal(bitmap: Bitmap, source: EditorSource.Local, quality: Int): Uri {
        val srcUri = Uri.parse(source.uri)
        context.contentResolver.openOutputStream(srcUri, "wt")?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        } ?: error("openOutputStream returned null for $srcUri")
        return srcUri
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private fun invalidateImageCache(uri: Uri) {
        // Coil 2 keys are Key(key, extras); plain remove(Key(key)) leaves sized/transformed variants.
        // Scan the live key set and drop every entry matching the primary string, else the viewer
        // keeps serving a pre-edit variant.
        val key = uri.toString()
        val loader = context.imageLoader
        val mc = loader.memoryCache
        if (mc != null) {
            runCatching {
                val toRemove = mc.keys.filter { it.key == key }
                toRemove.forEach { mc.remove(it) }
            }
        }
        runCatching { loader.diskCache?.remove(key) }
        runCatching { context.contentResolver.notifyChange(uri, null) }
    }

    private fun isItemPendingOrTrashed(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED),
                null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val pending = cursor.getInt(0)
                val trashed = cursor.getInt(1)
                pending != 0 || trashed != 0
            } ?: false
        }.getOrDefault(false)
    }

    private fun insertLocalCopy(
        bitmap: Bitmap,
        source: EditorSource.Local,
        quality: Int,
        /** Keep the original name (the Overwrite-fallback path); otherwise stamp `_edit_<ts>` for a distinct copy. */
        useOriginalName: Boolean = false,
        /** Shared timestamp for the filename AND explicit DATE_TAKEN; pass the same Long to the cloud
         *  upload so reconcile's second-precision byNameAndDate match pairs them. */
        editTimestampMs: Long = System.currentTimeMillis(),
    ): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val newName = if (useOriginalName) source.displayName else stamp(source.displayName, editTimestampMs)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, newName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            // Explicit DATE_TAKEN (ms) so reconcile.byNameAndDate finds the cloud sibling; DATE_MODIFIED is seconds.
            put(MediaStore.Images.Media.DATE_TAKEN, editTimestampMs)
            put(MediaStore.Images.Media.DATE_MODIFIED, editTimestampMs / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, eu.akoos.photos.util.ProtonPhotosStorage.DEFAULT_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        } ?: error("openOutputStream returned null")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    private suspend fun saveCloud(bitmap: Bitmap, source: EditorSource.Cloud, mode: SaveMode, quality: Int, editTimestampMs: Long): Uri? {
        val cacheDir = File(context.cacheDir, "editor").also { it.mkdirs() }
        val tempFile = File(cacheDir, "edit_${editTimestampMs}.jpg")
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        try {
            val userId = accountManager.getPrimaryUserId().first()
                ?: error("Not signed in")
            val tempUri = Uri.fromFile(tempFile)
            // Overwrite keeps the name; Copy stamps a distinct one (same editTimestampMs as the device copy).
            val displayName = when (mode) {
                SaveMode.Overwrite -> source.photo.displayName
                SaveMode.Copy      -> stamp(source.photo.displayName, editTimestampMs)
            }
            val item = LocalMediaItem(
                uri = tempUri.toString(),
                dateTaken = if (mode == SaveMode.Overwrite) source.photo.captureTime * 1000L else editTimestampMs,
                displayName = displayName,
                mimeType = "image/jpeg",
                sizeBytes = tempFile.length(),
                bucketName = null,
                width = bitmap.width,
                height = bitmap.height,
                duration = 0L,
            )
            val hash = sha1(tempFile)
            val newLinkId = cloudRepo.uploadFile(userId, item, hash, tempUri.toString())

            // Re-attach the new linkId to the source album (when there is one) so the edited
            // copy lives in the same album as the source did. Best-effort — never fails the save.
            sourceAlbumLinkId?.let { albumId ->
                runCatching { cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)) }
            }

            if (mode == SaveMode.Overwrite) {
                runCatching { cloudRepo.deleteFiles(userId, listOf(source.photo.linkId)) }
            }
            return Uri.parse("proton://drive/$newLinkId")
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Synced-photo helper: after a local save, also push the edit to Drive. Overwrite uploads + trashes
     * the old linkId; Copy uploads a new linkId and leaves the old one.
     */
    private suspend fun uploadEditAsCloudReplacement(
        bitmap: Bitmap,
        cloud: CloudPhoto,
        mode: SaveMode,
        quality: Int,
        /** Same instant as the device-side insertLocalCopy so both share a name + captureTime second
         *  and reconcile's byNameAndDate pairs them as Synced. */
        editTimestampMs: Long,
        userId: me.proton.core.domain.entity.UserId,
    ): String {
        val cacheDir = File(context.cacheDir, "editor").also { it.mkdirs() }
        val tempFile = File(cacheDir, "synced_${editTimestampMs}.jpg")
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        try {
            val tempUri = Uri.fromFile(tempFile)
            val displayName = when (mode) {
                SaveMode.Overwrite -> cloud.displayName
                SaveMode.Copy      -> stamp(cloud.displayName, editTimestampMs)
            }
            val item = LocalMediaItem(
                uri = tempUri.toString(),
                dateTaken = if (mode == SaveMode.Overwrite) cloud.captureTime * 1000L else editTimestampMs,
                displayName = displayName,
                mimeType = "image/jpeg",
                sizeBytes = tempFile.length(),
                bucketName = null,
                width = bitmap.width,
                height = bitmap.height,
                duration = 0L,
            )
            val hash = sha1(tempFile)
            val newLinkId = cloudRepo.uploadFile(userId, item, hash, tempUri.toString())
            sourceAlbumLinkId?.let { albumId ->
                runCatching { cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)) }
            }
            if (mode == SaveMode.Overwrite) {
                runCatching { cloudRepo.deleteFiles(userId, listOf(cloud.linkId)) }
            }
            return newLinkId
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Hex SHA-1 of the file's plaintext. Must stay SHA-1 (not SHA-256): Drive pins the digest algorithm,
     * and Drive web rejects a differently-derived ContentHash ("Cannot build photo payload...").
     */
    private fun sha1(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Appends a "_edit_<ts>" suffix to the filename. Pass the SAME [atMs] to the device + cloud
     * saves so they get identical names (independent format(Date()) calls drift) and reconcile pairs them.
     */
    private fun stamp(displayName: String, atMs: Long = System.currentTimeMillis()): String {
        val dotIdx = displayName.lastIndexOf('.')
        val base = if (dotIdx > 0) displayName.substring(0, dotIdx) else displayName
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
            .format(java.util.Date(atMs))
        return "${base}_edit_$ts.jpg"
    }

    fun consumeSaveResult() {
        _state.update { it.copy(saveResult = null) }
    }
}
