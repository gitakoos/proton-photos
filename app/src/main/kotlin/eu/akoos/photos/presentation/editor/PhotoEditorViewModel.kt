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
import android.os.Environment
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
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/** What we're editing — drives the save flow. */
sealed class EditorSource {
    data class Local(val uri: String, val displayName: String, val mimeType: String) : EditorSource()
    data class Cloud(val photo: CloudPhoto) : EditorSource()
}

/** Built-in filter presets — each is a 4x5 ColorMatrix. */
enum class FilterPreset(val displayName: String) {
    None("Original"),
    BlackWhite("B&W"),
    Sepia("Sepia"),
    Vintage("Vintage"),
    Vivid("Vivid"),
    Cool("Cool"),
    Warm("Warm"),
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
    val rotationDegrees: Int = 0, // 0, 90, 180, 270
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val filter: FilterPreset = FilterPreset.None,
    /** Crop in source-bitmap coordinates; null = no crop. */
    val cropRect: Rect? = null,
    /** Black-out / pixelate strokes applied AFTER all color and geometry transforms. */
    val redactStrokes: List<RedactionStroke> = emptyList(),
)

data class EditorUiState(
    val source: EditorSource? = null,
    val originalBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val adjustments: EditorAdjustments = EditorAdjustments(),
    val isSaving: Boolean = false,
    val saveResult: SaveResult? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val pendingWriteIntent: android.app.PendingIntent? = null,
    /**
     * Surfaces the OS consent dialog produced by [MediaStore.createDeleteRequest] when
     * a Synced photo's Overwrite-mode save falls back to "save as copy" — without it
     * the original device file would orphan next to the edit and the gallery would
     * keep showing both. One Allow tap on the system prompt removes the original; on
     * Deny the duplicate persists (but the save still succeeded, so the user just
     * sees "Saved as copy" with no nasty re-edit prompt). Mirrors [pendingWriteIntent]
     * end-to-end (launcher in the screen, resolved by [PhotoEditorViewModel.onDeletePermissionResolved]).
     */
    val pendingDeleteIntent: android.app.PendingIntent? = null,
)

sealed class SaveResult {
    data class Success(val uri: Uri?) : SaveResult()
    /**
     * The Overwrite path fell back to inserting a new file because the source URI is
     * read-only for this app (foreign owner or pending/trashed state). The original photo
     * is untouched; the edit lives at [uri] in our `Pictures/Proton Photos/` folder. The
     * screen surfaces a snackbar so the user knows what happened.
     */
    data class SuccessAsCopy(val uri: Uri?) : SaveResult()
    data class Failed(val message: String) : SaveResult()
}

/**
 * What the user picked in the Save dialog. The available options depend on [EditorSource]:
 *  - Local source → [Overwrite] writes back to the source URI in-place; [Copy] inserts a new MediaStore entry.
 *  - Cloud source → [Overwrite] uploads as new linkId AND trashes the old cloud photo (replacement);
 *                   [Copy] just uploads as new without touching the original.
 */
enum class SaveMode { Overwrite, Copy }

@HiltViewModel
class PhotoEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val cloudRepo: DrivePhotoRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** When set, cloud uploads from save() are also added to this album so the edited copy
     *  appears in the same album the source photo lived in. */
    private var sourceAlbumLinkId: String? = null
    fun setSourceAlbumLinkId(linkId: String?) { sourceAlbumLinkId = linkId }

    // ── Undo / redo stacks ────────────────────────────────────────────────────
    // Snapshots of EditorAdjustments captured BEFORE a mutating call. Soft cap = 30.
    // The fast slider path (updateAdjustmentsFast) is DRAFT — it does not push here.
    // Only finalizeAdjustments() (slider release) pushes a slider edit, and the other
    // mutating calls (rotate90Cw, toggleFlipH, toggleFlipV, applyCrop, selectFilter,
    // addRedactStroke, undoLastRedactStroke, clearRedactStrokes) push directly.
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
            _state.update { it.copy(adjustments = previous, previewBitmap = newPreview) }
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
            _state.update { it.copy(adjustments = next, previewBitmap = newPreview) }
        }
    }


    /** Downsampled (max 720px on the long edge) copy of [EditorUiState.originalBitmap]. While
     *  the user is dragging an Adjust slider we re-render this small bitmap on every tick —
     *  20×-100× faster than rendering the full-res photo for every onDrag event. Filter chip
     *  previews also derive from this bitmap. Built lazily on first slider touch. */
    private var previewSourceSmall: Bitmap? = null
    /** Most-recent slider-drag render job. Cancelled when the next tick fires so we never have
     *  more than one bitmap recompute in flight. */
    private var sliderRenderJob: kotlinx.coroutines.Job? = null

    /** Build (or return cached) downsampled copy of the source bitmap for fast slider previews
     *  and filter chip thumbnails. Max edge 720px keeps the per-tick render under ~5 ms on
     *  mid-range hardware. */
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

    /** Called when the user releases a slider. Triggers a final full-res render so the saved
     *  output matches what the user sees at preview time. Until called we keep the cheap
     *  downsampled preview on screen, even after the gesture ends — switching is invisible at
     *  display resolution but commits a faithful bitmap for [save].
     *
     *  We intentionally do NOT recycle the previous previewBitmap here. Compose's draw pass
     *  may still hold a reference to it for one frame past the state.update, and calling
     *  recycle() on that frame's bitmap throws "Canvas: trying to use a recycled bitmap" on
     *  the UI thread. The orphaned bitmap will be GC'd shortly.
     *
     *  Undo behavior: the entire slider drag is folded into ONE undo entry, captured at the
     *  START of the drag in [updateAdjustmentsFast] and pushed here on release. If the drag
     *  ended up being a no-op (same values as before) we drop the snapshot. */
    fun finalizeAdjustments() {
        val orig = _state.value.originalBitmap ?: return
        val adj = _state.value.adjustments
        val snap = sliderUndoSnapshot
        sliderUndoSnapshot = null
        if (snap != null && snap != adj) pushUndo(snap)
        viewModelScope.launch(Dispatchers.Default) {
            val full = applyAdjustments(orig, adj)
            _state.update { it.copy(previewBitmap = full) }
        }
    }

    /**
     * Set when the editor was opened on a Synced photo (device + cloud). The local-save
     * paths consult this on success to ALSO replace the cloud version — without that, an
     * Overwrite would only update the device file and the cloud counterpart stays stale.
     */
    private var cloudCounterpart: CloudPhoto? = null

    /** Mirrored into UiState so the save dialog can adjust its subtitle when a Synced
     *  edit will fan out to both device and cloud — the previous "creates a new file
     *  in Pictures/Proton Photos" text was misleading for Synced photos because it
     *  omitted the cloud-counterpart upload that also kicks off. */
    private val _hasCloudCounterpart = MutableStateFlow(false)

    fun setCloudCounterpart(photo: CloudPhoto?) {
        cloudCounterpart = photo
        _hasCloudCounterpart.value = photo != null
    }

    /** Exposed for PhotoEditorScreen.SaveSheet so it can show a "both device + cloud"
     *  subtitle instead of the device-only one. */
    val hasCloudCounterpart: StateFlow<Boolean> = _hasCloudCounterpart.asStateFlow()

    fun loadLocal(uri: String, displayName: String, mimeType: String) {
        // Drop any cached small-source bitmap from a previous photo — otherwise the slider
        // path would render the new photo's adjustments against the OLD photo's downscale.
        previewSourceSmall = null
        clearUndoStacks()
        _state.update { it.copy(source = EditorSource.Local(uri, displayName, mimeType), isLoading = true, errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            if (bmp == null) {
                _state.update { it.copy(isLoading = false,
                    errorMessage = context.getString(R.string.editor_error_load_local)) }
                return@launch
            }
            _state.update { it.copy(originalBitmap = bmp, previewBitmap = bmp, isLoading = false) }
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
            val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            if (bmp == null) {
                _state.update { it.copy(isLoading = false,
                    errorMessage = context.getString(R.string.editor_error_decode_failed)) }
                return@launch
            }
            _state.update { it.copy(originalBitmap = bmp, previewBitmap = bmp, isLoading = false) }
        }
    }

    fun updateBrightness(v: Int) = updateAdjustmentsFast { it.copy(brightness = v.coerceIn(-100, 100)) }
    fun updateContrast(v: Int) = updateAdjustmentsFast { it.copy(contrast = v.coerceIn(-100, 100)) }
    fun updateSaturation(v: Int) = updateAdjustmentsFast { it.copy(saturation = v.coerceIn(-100, 100)) }
    fun rotate90Cw() = updateAdjustments { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
    fun toggleFlipH() = updateAdjustments { it.copy(flipHorizontal = !it.flipHorizontal) }
    fun toggleFlipV() = updateAdjustments { it.copy(flipVertical = !it.flipVertical) }
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

    /**
     * One-tap auto-fix. Inspects the mean luminance + spread of a 100×100 downsample
     * of [EditorUiState.originalBitmap] and picks brightness / contrast / saturation
     * deltas that bring the image to a tuned baseline. Result is committed via
     * [updateAdjustments] (so it goes through the normal undo path) — call [resetAll]
     * to undo.
     */
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
            withContext(Dispatchers.Main) {
                updateAdjustments {
                    it.copy(
                        brightness = brightnessDelta.coerceIn(-100, 100),
                        contrast = contrastDelta.coerceIn(-100, 100),
                        saturation = saturationDelta.coerceIn(-100, 100),
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
            _state.update { it.copy(adjustments = newAdj, previewBitmap = newPreview) }
            // No eager recycle on the previous previewBitmap: Compose's draw pipeline may
            // still hold a reference to it for one frame past the state update, and
            // recycling it then crashes the UI thread with "Canvas: trying to use a
            // recycled bitmap". Orphaned previews get GC'd shortly.
        }
    }

    /** Variant of [updateAdjustments] that bypasses the undo stack — used by [resetAll]
     *  which already cleared the stacks itself, and by [undo]/[redo] internals. */
    private fun updateAdjustmentsNoUndo(transform: (EditorAdjustments) -> EditorAdjustments) {
        val orig = _state.value.originalBitmap ?: return
        val newAdj = transform(_state.value.adjustments)
        viewModelScope.launch(Dispatchers.Default) {
            val newPreview = applyAdjustments(orig, newAdj)
            _state.update { it.copy(adjustments = newAdj, previewBitmap = newPreview) }
        }
    }

    /**
     * Fast slider variant: renders against a 720px downsampled bitmap so the on-screen preview
     * keeps up with onDrag events without recomputing a 12MP buffer per tick. The save path
     * always re-renders from [EditorUiState.originalBitmap], so the lower-res preview only
     * affects what the user *sees during the gesture* — the saved bytes are still full-res.
     *
     * Cancels any previous in-flight slider render so we never have more than one bitmap
     * recompute in flight at a time. Like [updateAdjustments] we do NOT recycle the previous
     * preview here — Compose may still be drawing with it.
     */
    private fun updateAdjustmentsFast(transform: (EditorAdjustments) -> EditorAdjustments) {
        val orig = _state.value.originalBitmap ?: return
        val previous = _state.value.adjustments
        // Capture the pre-drag adjustments on the FIRST tick — undo treats a whole drag as
        // one entry, pushed in finalizeAdjustments() on release. Subsequent ticks within the
        // same drag must NOT overwrite this snapshot.
        if (sliderUndoSnapshot == null) sliderUndoSnapshot = previous
        val newAdj = transform(previous)
        sliderRenderJob?.cancel()
        sliderRenderJob = viewModelScope.launch(Dispatchers.Default) {
            val small = ensureSmallSource(orig)
            val newPreview = applyAdjustments(small, newAdj)
            _state.update { it.copy(adjustments = newAdj, previewBitmap = newPreview) }
        }
    }

    /**
     * Renders all current adjustments onto a fresh bitmap derived from [source].
     * Heavy operation — always call from a background dispatcher.
     *
     * Intermediates (`cropped`, `rotated`, `colored`) are recycled before return when
     * they're not aliases of [source] or the final result. A 12 MP edit goes through up
     * to 3 intermediate bitmaps at ~48 MB each; leaking them shows up as native-heap
     * pressure that the GC can't release.
     */
    private fun applyAdjustments(source: Bitmap, adj: EditorAdjustments): Bitmap {
        // 1. crop
        val cropped = adj.cropRect?.let {
            val safe = Rect(
                it.left.coerceIn(0, source.width - 1),
                it.top.coerceIn(0, source.height - 1),
                it.right.coerceIn(1, source.width),
                it.bottom.coerceIn(1, source.height),
            )
            if (safe.width() > 0 && safe.height() > 0)
                Bitmap.createBitmap(source, safe.left, safe.top, safe.width(), safe.height())
            else source
        } ?: source

        // 2. rotate + flip
        val matrix = Matrix().apply {
            if (adj.rotationDegrees != 0) postRotate(adj.rotationDegrees.toFloat())
            val sx = if (adj.flipHorizontal) -1f else 1f
            val sy = if (adj.flipVertical) -1f else 1f
            if (sx != 1f || sy != 1f) postScale(sx, sy)
        }
        val rotated = if (matrix.isIdentity) cropped
            else Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)

        // 3. color matrix (brightness, contrast, saturation, filter)
        val colorMatrix = buildColorMatrix(adj)
        val colored = if (colorMatrix == null) rotated else {
            val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(colorMatrix)
            }
            canvas.drawBitmap(rotated, 0f, 0f, paint)
            out
        }

        // 4. redaction strokes — drawn LAST so they cover the final visible content
        val final = if (adj.redactStrokes.isEmpty()) colored
            else applyRedactStrokes(colored, adj.redactStrokes)

        // Intermediate bitmaps (cropped, rotated, colored when they differ from `final`) are
        // intentionally NOT recycled here. Even though these locals leave scope when this
        // function returns, Compose's draw pipeline can still hold a reference for one frame
        // past a state update, and recycling an intermediate that's also referenced by the
        // in-flight render crashes the UI thread with "Canvas: trying to use a recycled
        // bitmap". Leaving them to GC costs a slug of native heap that the next mark-compact
        // cycle cleans up — measurable but not painful for a single editor session.
        return final
    }

    /**
     * Burns each stroke into [src] and returns a new bitmap.
     *
     * - [RedactMode.Black]: draws a solid black brush stroke.
     * - [RedactMode.Pixelate]: builds a heavily downscaled+upscaled copy of the bitmap,
     *   then masks it through the stroke path so only the stroke area shows the mosaic.
     */
    private fun applyRedactStrokes(src: Bitmap, strokes: List<RedactionStroke>): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        // Group strokes by mode so we can prepare the pixelated bitmap once.
        val pixelated by lazy {
            val downscale = 24 // larger = chunkier mosaic
            val small = Bitmap.createScaledBitmap(src, (w / downscale).coerceAtLeast(1), (h / downscale).coerceAtLeast(1), false)
            // The intermediate `small` is no longer recycled. See applyAdjustments() for the
            // rationale: explicit Bitmap.recycle() in the editor render path can race with
            // Compose's in-flight draw and crash with "trying to use a recycled bitmap".
            Bitmap.createScaledBitmap(small, w, h, false)
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
                    // No maskBmp.recycle() — see applyAdjustments() rationale; leaving to GC.
                }
                continue
            }
            when (stroke.mode) {
                RedactMode.Black -> canvas.drawPath(path, pathPaint)
                RedactMode.Pixelate -> {
                    val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
                    Canvas(maskBmp).drawPath(path, pathPaint)
                    drawPixelatedThroughMask(canvas, pixelated, maskBmp)
                }
            }
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
        if (adj.brightness == 0 && adj.contrast == 0 && adj.saturation == 0 && adj.filter == FilterPreset.None) {
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
     * Saves the edited bitmap according to [mode].
     *
     * Local source:
     *   • [SaveMode.Overwrite] — writes the edited bytes back to the source URI (in place).
     *     MediaStore IS_PENDING dance ensures the change is visible immediately.
     *   • [SaveMode.Copy] — inserts a new MediaStore entry under Pictures/Proton Photos.
     *
     * Cloud source:
     *   • [SaveMode.Overwrite] — uploads the edit as a NEW Drive linkId, then trashes the
     *     original linkId. The web client will then show only the edited version.
     *   • [SaveMode.Copy] — uploads the edit as a new linkId; the original stays untouched.
     */
    private var pendingWriteMode: SaveMode? = null
    private var pendingWriteQuality: Int = 92

    fun save(mode: SaveMode, quality: Int = 92, allowWriteRequestRecovery: Boolean = true) {
        val s = _state.value
        val source = s.source ?: return
        val orig = s.originalBitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isSaving = true, saveResult = null) }
            // Always re-render from the full-resolution original — the on-screen preview may
            // be a 720px downsampled bitmap from the fast slider path, and saving that would
            // silently degrade the user's photo. applyAdjustments is the same logic the live
            // preview uses, so the saved bytes match what the user sees.
            val bitmap = applyAdjustments(orig, s.adjustments)
            // Single timestamp shared across the device save AND the cloud upload for
            // Synced photos. Used to derive both the stamped filename and the
            // DATE_TAKEN / captureTime metadata so ReconcileSyncStateUseCase's
            // byNameAndDate match can pair the two fresh copies as Synced (green-cloud)
            // without waiting for a manual download. Computing System.currentTimeMillis()
            // independently in each save path drifts by a few ms — different filenames,
            // different captureTime seconds, no match.
            val editTimestampMs = System.currentTimeMillis()
            val saveResult: SaveResult = try {
                val uri = when (source) {
                    is EditorSource.Local -> saveLocal(bitmap, source, mode, quality, editTimestampMs)
                    is EditorSource.Cloud -> saveCloud(bitmap, source, mode, quality, editTimestampMs)
                }
                // Invalidate Coil caches for the saved URI on EVERY successful local save —
                // Overwrite reuses the original URI (stale bytes in cache), Copy creates a
                // new URI (no stale risk, but a notifyChange wakes MediaStore observers up
                // immediately). Cloud uploads create a brand-new linkId, so there's no
                // existing key to nuke; the cloud listing refresh on viewer return picks it up.
                if (uri != null && source is EditorSource.Local) {
                    invalidateImageCache(uri)
                }
                // Synced photo path: if a cloud counterpart was registered, also push the
                // edited bitmap up so the cloud version doesn't stay stale. Failure here
                // doesn't fail the whole save — the device file is already on disk; we
                // just couldn't sync. Best-effort, logged for diagnostics only.
                val counterpart = cloudCounterpart
                if (source is EditorSource.Local && counterpart != null && uri != null) {
                    runCatching {
                        uploadEditAsCloudReplacement(bitmap, counterpart, mode, quality, editTimestampMs)
                    }
                }
                SaveResult.Success(uri)
            } catch (e: SecurityException) {
                // Foreign MediaStore URI — try createWriteRequest for one-shot consent so the
                // user can actually overwrite the original. IS_PENDING/IS_TRASHED items skip
                // straight to copy because the consent dialog refuses them.
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
                            // Synced + Overwrite + fallback-to-Copy = the original device
                            // file is otherwise stranded next to the edit. Quiet delete
                            // succeeds for app-owned files; foreign URIs (camera roll,
                            // screenshots) need OS consent — surface createDeleteRequest
                            // and the screen launches it. On Allow the original is gone,
                            // sync logic re-pairs the new device file with the new cloud
                            // linkId by hash, and the green-cloud badge stays accurate.
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
                    SaveResult.Failed(eu.akoos.photos.util.sanitizeErrorMessage(e.message ?: "No permission to save"))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SaveResult.Failed(eu.akoos.photos.util.sanitizeErrorMessage(e.message ?: "Save failed"))
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

    /** Called once the OS delete-consent dialog closes (regardless of Allow/Deny — the
     *  system has already actioned the choice by then). Clears the pending intent so the
     *  screen's saveResult Effect proceeds with toast + navigation. */
    fun onDeletePermissionResolved() {
        _state.update { it.copy(pendingDeleteIntent = null) }
    }

    fun onWritePermissionDenied() {
        pendingWriteMode = null
        _state.update {
            it.copy(
                pendingWriteIntent = null,
                isSaving = false,
                saveResult = SaveResult.Failed("Save cancelled"),
            )
        }
    }

    private fun saveLocal(bitmap: Bitmap, source: EditorSource.Local, mode: SaveMode, quality: Int, editTimestampMs: Long): Uri? {
        return when (mode) {
            SaveMode.Overwrite -> overwriteLocal(bitmap, source, quality)
            SaveMode.Copy      -> insertLocalCopy(bitmap, source, quality, editTimestampMs = editTimestampMs)
        }
    }

    // Throws SecurityException on foreign MediaStore URIs (caller handles via createWriteRequest
    // recovery + copy fallback). No IS_PENDING dance — setting it on a foreign URI traps the
    // file in pending state and blocks the very write we are about to do.
    private fun overwriteLocal(bitmap: Bitmap, source: EditorSource.Local, quality: Int): Uri {
        val srcUri = Uri.parse(source.uri)
        context.contentResolver.openOutputStream(srcUri, "wt")?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        } ?: error("openOutputStream returned null for $srcUri")
        return srcUri
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private fun invalidateImageCache(uri: Uri) {
        // Coil 2 memory-caches under MemoryCache.Key(key, extras). The plain remove(Key(key))
        // only strips the bare entry; variants requested with a Size or transformations land
        // under the same key but with a non-empty extras map and survive. Scan the live key
        // set and nuke every entry whose primary string matches — otherwise the viewer keeps
        // serving the pre-edit bitmap from one of those sized variants.
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
        /** When true, save with the original display name (used as the auto-fallback path
         *  from [save] when Overwrite isn't possible). Otherwise stamp `_edit_YYYYMMDD_HHMMSS`
         *  onto the name so a manual "Save as Copy" produces a clearly-distinct file. */
        useOriginalName: Boolean = false,
        /** Shared timestamp used to derive both the stamped filename AND the explicit
         *  DATE_TAKEN row value. Letting MediaStore auto-pick DATE_TAKEN (= now) made it
         *  drift by a few ms vs the cloud upload's captureTime, so reconcile's
         *  byNameAndDate match (second-precision) sometimes landed on different seconds.
         *  Passing the exact same Long to insertLocalCopy AND uploadEditAsCloudReplacement
         *  guarantees they share the same name + DATE_TAKEN second. */
        editTimestampMs: Long = System.currentTimeMillis(),
    ): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val newName = if (useOriginalName) source.displayName else stamp(source.displayName, editTimestampMs)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, newName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            // Explicit DATE_TAKEN so reconcile.byNameAndDate finds the cloud sibling — see
            // editTimestampMs doc-comment above. DATE_MODIFIED is in seconds (the MediaStore
            // legacy unit); DATE_TAKEN is in milliseconds.
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
            // Overwrite keeps the original filename; Copy stamps a new one so both
            // versions are distinguishable in the cloud listing. The stamp uses the
            // same editTimestampMs the caller picked so a synced edit-copy round-trip
            // ends up at the SAME filename + captureTime on device + cloud.
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
            val hash = sha256(tempFile)
            val newLinkId = cloudRepo.uploadFile(userId, item, hash, tempUri.toString())

            // Re-attach the new linkId to the source album (when there is one) so the edited
            // copy lives in the same album as the source did. Best-effort — never fails the save.
            sourceAlbumLinkId?.let { albumId ->
                runCatching { cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)) }
            }

            if (mode == SaveMode.Overwrite) {
                // Trash the original — Overwrite semantics require the edit to replace it.
                runCatching { cloudRepo.deleteFiles(userId, listOf(source.photo.linkId)) }
            }
            return Uri.parse("proton://drive/$newLinkId")
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Synced-photo helper. After a successful local save, ALSO write the edit up to
     * Drive as a new linkId so the cloud counterpart of the Synced item reflects the
     * change. The mode semantics are:
     *   • Overwrite — upload + trash the old linkId (cloud "replace original")
     *   • Copy      — upload as a new linkId, leave the old one alone (cloud "keep both")
     */
    private suspend fun uploadEditAsCloudReplacement(
        bitmap: Bitmap,
        cloud: CloudPhoto,
        mode: SaveMode,
        quality: Int,
        /** Same instant the matching device-side insertLocalCopy used, so the freshly
         *  uploaded cloud linkId carries the SAME displayName + captureTime second as
         *  the new device file. Reconcile's byNameAndDate then pairs them automatically
         *  — the user gets the green-cloud Synced badge without having to download the
         *  cloud copy back first. */
        editTimestampMs: Long,
    ) {
        val cacheDir = File(context.cacheDir, "editor").also { it.mkdirs() }
        val tempFile = File(cacheDir, "synced_${editTimestampMs}.jpg")
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        try {
            val userId = accountManager.getPrimaryUserId().first() ?: return
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
            val hash = sha256(tempFile)
            val newLinkId = cloudRepo.uploadFile(userId, item, hash, tempUri.toString())
            sourceAlbumLinkId?.let { albumId ->
                runCatching { cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)) }
            }
            if (mode == SaveMode.Overwrite) {
                runCatching { cloudRepo.deleteFiles(userId, listOf(cloud.linkId)) }
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Stamp a "_edit_<ts>" suffix onto the source filename, where <ts> is a formatted
     * timestamp the caller chooses. Default = now, but for save flows that need the
     * device-copy AND cloud-copy to share an identical filename (so [ReconcileSyncStateUseCase]
     * can pair them via byNameAndDate later), pass the SAME timestamp to both calls:
     * each independent SimpleDateFormat#format(Date()) would otherwise drift by a few
     * milliseconds and produce different filenames that look unrelated to reconcile.
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
