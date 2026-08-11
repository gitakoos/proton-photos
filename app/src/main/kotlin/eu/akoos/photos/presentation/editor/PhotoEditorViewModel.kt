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

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
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
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.hidden.HiddenVaultRecords
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.data.repository.drive.UploadXAttrMetadata
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.MotionPhotoUtil
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/** What we're editing — drives the save flow. */
sealed class EditorSource {
    /** [captureTimeMs] is the device photo's original DATE_TAKEN (ms); a Copy inherits it so the edit
     *  sorts next to the original instead of jumping to the top of the timeline. Null when unknown. */
    data class Local(val uri: String, val displayName: String, val mimeType: String, val captureTimeMs: Long? = null) : EditorSource()
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
    Noir(R.string.editor_filter_noir),
    Chrome(R.string.editor_filter_chrome),
    Matte(R.string.editor_filter_matte),
    Dramatic(R.string.editor_filter_dramatic),
    Fresh(R.string.editor_filter_fresh),
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

/**
 * A freehand pen stroke. Unlike a redaction stroke its geometry is NORMALISED: [points] are 0..1
 * fractions of the framed image and [widthFraction] is a fraction of the image width, so it renders
 * correctly at any resolution (the 720px preview and the full-res save alike) with no full-res fallback.
 */
data class DrawStroke(
    val points: List<android.graphics.PointF>,
    val color: Int,
    val widthFraction: Float,
)

/**
 * A text overlay. [cx]/[cy] are the NORMALISED centre (0..1) and [sizeFraction] is the font size as a
 * fraction of the image height, so it bakes identically at any resolution. [id] is a stable handle for
 * selecting, moving, re-editing and deleting one item among several.
 */
data class TextItem(
    val id: Int,
    val text: String,
    val cx: Float,
    val cy: Float,
    val color: Int,
    val sizeFraction: Float,
    /** Rotation in degrees, applied around the text centre. Snaps to 45-degree detents while dragging. */
    val rotation: Float = 0f,
)

/** One HSL colour band (red..magenta). [hue]/[sat]/[light] are -100..100 and only affect pixels near
 *  the band's hue, blended into the neighbours. */
data class HslBand(val hue: Int = 0, val sat: Int = 0, val light: Int = 0)

/** A tone-curve control point in 0..1 (x = input level, y = output level). A plain class (not
 *  android PointF) so the LUT maths is unit-testable off-device. */
data class CurvePoint(val x: Float, val y: Float)

/** The neutral straight-line curve (input maps to itself). */
val IDENTITY_CURVE: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

/** Which tone curve is being edited: the RGB master (all channels) or one colour channel. */
enum class CurveChannel { RGB, R, G, B }

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
    /** Radial edge shading: positive darkens the corners, negative lightens them. Drawn on the framed image. */
    val vignette: Int = 0,
    /** Faded / matte look: positive lifts the black point and eases contrast, negative deepens it (punch). */
    val fade: Int = 0,
    /** Vibrance: boosts saturation weighted toward less-saturated pixels (positive) so already-vivid
     *  areas and skin are protected; negative pulls colour out. Per-pixel, applied after the colour matrix. */
    val vibrance: Int = 0,
    /** Unsharp sharpening (positive) via a blurred difference; negative softens. Per-pixel. */
    val sharpen: Int = 0,
    /** Film grain: adds monochrome noise. Per-pixel, applied last of the colour effects. */
    val grain: Int = 0,
    val rotationDegrees: Int = 0, // 0, 90, 180, 270
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    /** Fine straighten rotation, -100..100 mapped to about -15..+15 degrees, auto-cropped so no empty
     *  corners show. Applied after the 90-degree rotation, before crop. */
    val straighten: Int = 0,
    /** Vertical keystone, -100..100: positive narrows the top (converge up), negative narrows the bottom. */
    val perspectiveV: Int = 0,
    /** Horizontal keystone, -100..100: positive narrows the left, negative narrows the right. */
    val perspectiveH: Int = 0,
    val filter: FilterPreset = FilterPreset.None,
    /** Crop in display-space coords (the rotated/flipped orientation the user sees), consumed after
     *  the pipeline's rotate step so it stays in its authoring space. null = no crop. */
    val cropRect: Rect? = null,
    /** Black-out / pixelate strokes applied after all color and geometry transforms. */
    val redactStrokes: List<RedactionStroke> = emptyList(),
    /** Per-colour HSL: eight bands (red, orange, yellow, green, aqua, blue, purple, magenta). */
    val hslBands: List<HslBand> = List(8) { HslBand() },
    /** Tone curves: a master applied to every channel, then a per-channel curve. */
    val curveRgb: List<CurvePoint> = IDENTITY_CURVE,
    val curveR: List<CurvePoint> = IDENTITY_CURVE,
    val curveG: List<CurvePoint> = IDENTITY_CURVE,
    val curveB: List<CurvePoint> = IDENTITY_CURVE,
    /** Freehand pen strokes (normalised), drawn on top of everything. */
    val drawStrokes: List<DrawStroke> = emptyList(),
    /** Text overlays (normalised), drawn last. */
    val textItems: List<TextItem> = emptyList(),
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
    /** Redact brush diameter in screen dp, adjustable from the Redact panel (default 28). */
    val redactBrushDp: Float = 28f,
    /** The text overlay currently selected for moving / editing / colour+size in the Text tool. */
    val selectedTextId: Int? = null,
    /** True while the Text tool is active: the preview is then rendered WITHOUT baking the text (the
     *  overlay draws it live), so moving a text is a cheap overlay redraw instead of a full re-render. */
    val textToolActive: Boolean = false,
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
    /** Overwrite fell back to a new file (source read-only, or its format cannot be overwritten in
     *  place); original untouched, edit at [uri]. [messageRes] is the toast explaining which case. */
    data class SuccessAsCopy(val uri: Uri?, val messageRes: Int = R.string.editor_saved_as_copy_toast) : SaveResult()
    data class Failed(val message: String) : SaveResult()
}

/**
 * Save dialog choice. Local: [Overwrite] writes back in-place, [Copy] inserts a new MediaStore entry.
 * Cloud: [Overwrite] uploads a new linkId and trashes the old one, [Copy] uploads without touching it.
 */
enum class SaveMode { Overwrite, Copy }

/**
 * Whether an in-place Overwrite must fall back to a fresh Copy. True when the source container cannot
 * be reproduced by `Bitmap.compress` ([overwritable] false: RAW/DNG, HEIC, GIF, ...), OR when the
 * source is a Motion Photo, because overwriting a Motion Photo in place writes only the still and
 * truncates the appended video, destroying the motion. Pure so the decision is verified in a test.
 */
internal fun overwriteCoercesToCopy(overwritable: Boolean, isMotionPhoto: Boolean): Boolean =
    !overwritable || isMotionPhoto

/**
 * The pixel size an export downscales to: the longest edge capped at [maxDim], aspect preserved, and
 * never upscaled (a smaller image is returned unchanged). Backs the Full / 2048 / 1024 export-size
 * choice, and is pure so the resize maths is verified in a test.
 */
internal fun exportTargetSize(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxDim) return width to height
    val scale = maxDim.toFloat() / longest
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/** The output level (0..1) a tone curve maps [x] (0..1) to: linear between its sorted control points,
 *  flat beyond the ends. Pure so the curve maths is unit-tested off-device. */
internal fun curveValueAt(points: List<CurvePoint>, x: Float): Float {
    if (points.isEmpty()) return x
    val pts = points.sortedBy { it.x }
    if (x <= pts.first().x) return pts.first().y
    if (x >= pts.last().x) return pts.last().y
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        if (x in a.x..b.x) {
            val t = if (b.x > a.x) (x - a.x) / (b.x - a.x) else 0f
            return a.y + (b.y - a.y) * t
        }
    }
    return x
}

/** A 256-entry lookup table (0..255 -> 0..255) for a tone curve. */
internal fun buildCurveLut(points: List<CurvePoint>): IntArray =
    IntArray(256) { i -> (curveValueAt(points, i / 255f) * 255f).roundToInt().coerceIn(0, 255) }

/** True when a curve is the neutral straight line (nothing to apply). */
internal fun isIdentityCurve(points: List<CurvePoint>): Boolean =
    points.size == 2 && points[0].x == 0f && points[0].y == 0f && points[1].x == 1f && points[1].y == 1f

/** Centre hue (degrees) of each HSL band: red, orange, yellow, green, aqua, blue, purple, magenta. */
val HSL_BAND_CENTERS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 280f, 320f)

/** Shortest distance between two hues on the 0..360 wheel. */
internal fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/** How strongly an HSL band centred at [centerDeg] affects a pixel at [hue]: 1 at the centre, ramping to
 *  0 by 60 degrees away, so adjacent bands blend. */
internal fun hslBandWeight(hue: Float, centerDeg: Float): Float =
    (1f - hueDistance(hue, centerDeg) / 60f).coerceAtLeast(0f)

/** The angle a rotated text overlay actually draws at: the raw angle, but pulled to the nearest 45-degree
 *  detent when within ~5 degrees of it (a magnet at horizontal / diagonal / vertical). Applied only at
 *  draw time, so the stored raw angle keeps accumulating and a twist can pass through a detent. */
internal fun snapTextAngle(deg: Float): Float {
    val norm = ((deg % 360f) + 360f) % 360f
    val nearest = (norm / 45f).roundToInt() * 45f
    return if (kotlin.math.abs(norm - nearest) <= 5f) (nearest % 360f) else norm
}

/**
 * The format a COPY defaults to: the source's own, when `Bitmap.compress` can write it, so editing a PNG
 * keeps a lossless PNG (with its transparency) and a WebP stays WebP instead of silently turning into a
 * lossy JPEG. A format the encoder cannot produce (HEIC, RAW, GIF) falls back to JPEG. The user can still
 * override this in the picker.
 */
internal fun copyDefaultFormat(mimeType: String, displayName: String): Bitmap.CompressFormat {
    val mime = mimeType.lowercase()
    val ext = displayName.substringAfterLast('.', "").lowercase()
    return when {
        mime == "image/png" || ext == "png" -> Bitmap.CompressFormat.PNG
        mime == "image/webp" || ext == "webp" ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }
}

@HiltViewModel
class PhotoEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: eu.akoos.photos.domain.repository.SyncStateRepository,
    // Reports the background edit-upload to the Activity monitor + avatar ring, the same surface the
    // gallery download / offline pin loops use. The upload itself is unchanged; this only tracks it.
    private val transferCenter: eu.akoos.photos.data.transfer.TransferCenter,
    // A vaulted photo is an app-private file with no MediaStore row: a copy of one has to be written
    // and recorded by the vault, or it leaves the hidden area.
    private val hiddenStorage: eu.akoos.photos.data.hidden.HiddenStorageManager,
    private val hiddenVaultEditor: eu.akoos.photos.data.hidden.HiddenVaultEditor,
    // Application-lifetime scope for the cloud upload that outlives the editor: it must keep running
    // after save() returns and the screen navigates away (viewModelScope is cancelled at that point).
    @eu.akoos.photos.di.AppScope private val appScope: CoroutineScope,
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
        // Commit the target immediately so a following edit builds on it; render async under the render
        // token so a slow undo render can't land after a newer one (a filter tapped right after) and undo it.
        _state.update { it.copy(adjustments = previous) }
        val gen = ++renderGen
        viewModelScope.launch(Dispatchers.Default) {
            val newPreview = applyAdjustments(orig, forPreview(previous))
            val noCropPreview = applyColorOnly(orig, previous)
            if (gen == renderGen) {
                _state.update { it.copy(previewBitmap = newPreview, adjustedBitmapNoCrop = noCropPreview) }
            }
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
        _state.update { it.copy(adjustments = next) }
        val gen = ++renderGen
        viewModelScope.launch(Dispatchers.Default) {
            val newPreview = applyAdjustments(orig, forPreview(next))
            val noCropPreview = applyColorOnly(orig, next)
            if (gen == renderGen) {
                _state.update { it.copy(previewBitmap = newPreview, adjustedBitmapNoCrop = noCropPreview) }
            }
        }
    }


    /** Downsampled (max 720px) copy of the source for fast per-tick slider/filter-chip previews. */
    private var previewSourceSmall: Bitmap? = null
    /** Most-recent slider-drag render job; cancelled on the next tick to keep only one in flight. */
    private var sliderRenderJob: kotlinx.coroutines.Job? = null

    /** Monotonic render token. Every render path stamps its request and only writes the result if it is
     *  still the latest, so an older render (a slider release) can't land after and overwrite a newer one
     *  (a chip reset) once they race on the Default dispatcher. Bumped on the main thread only. */
    @Volatile private var renderGen = 0L

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

    /** Scale a full-res crop rect down to the small preview source, so a smooth small-source render still
     *  crops in the right place. [sc] is the small source's scale factor (smallWidth / originalWidth). */
    private fun scaleCropForSmall(adj: EditorAdjustments, sc: Float): EditorAdjustments {
        val crop = adj.cropRect ?: return adj
        if (sc >= 1f) return adj
        return adj.copy(
            cropRect = Rect(
                (crop.left * sc).roundToInt(),
                (crop.top * sc).roundToInt(),
                (crop.right * sc).roundToInt(),
                (crop.bottom * sc).roundToInt(),
            ),
        )
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
        val gen = ++renderGen
        viewModelScope.launch(Dispatchers.Default) {
            val full = applyAdjustments(orig, forPreview(adj))
            val noCropFull = applyColorOnly(orig, adj)
            if (gen == renderGen) {
                _state.update { it.copy(
                    previewBitmap = full,
                    adjustedBitmapNoCrop = noCropFull,
                ) }
            }
        }
    }

    /** Absolute path of the Cloud source's downloaded full-res original (set by [loadCloud]). The save
     *  path reads its EXIF to re-inject the original's metadata into the edited cloud copy. Null for a
     *  Local/External source, or before the cloud download finishes. */
    private var cloudOriginalFilePath: String? = null

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

    /** Outcome of a guarded source decode: an oriented bitmap, a plain failure (the caller keeps its
     *  own specific error), or a near-OOM the guard already surfaced with the low-memory message. */
    private sealed class DecodeOutcome {
        data class Ok(val bitmap: Bitmap) : DecodeOutcome()
        object Failed : DecodeOutcome()
        object OutOfMemory : DecodeOutcome()
    }

    /**
     * Runs [decode] (the editor's largest single Java-heap allocation: the full-res source decode plus
     * its orientation bake) behind a targeted OutOfMemoryError guard. A very large photo decoded to
     * ARGB_8888 can exceed the heap cap; when it does, the image cache is dropped FIRST to give the
     * failure path headroom, a numbers-only diagnostics line is recorded, and the editor shows a
     * friendly low-memory error instead of crashing. Only OutOfMemoryError is caught as the recovery
     * path; any other failure falls through as [DecodeOutcome.Failed] so the caller keeps its own
     * load/download/decode message.
     */
    private fun guardedDecode(decode: () -> Bitmap?): DecodeOutcome {
        return try {
            decode()?.let { DecodeOutcome.Ok(it) } ?: DecodeOutcome.Failed
        } catch (oom: OutOfMemoryError) {
            // Free the image cache FIRST so the error path itself has headroom to run.
            context.imageLoader.memoryCache?.clear()
            eu.akoos.photos.util.PerfDiagnostics.recordOom("editor-decode")
            _state.update { it.copy(
                isLoading = false,
                errorMessage = context.getString(R.string.editor_error_low_memory),
            ) }
            DecodeOutcome.OutOfMemory
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            DecodeOutcome.Failed
        }
    }

    fun loadLocal(uri: String, displayName: String, mimeType: String, captureTimeMs: Long? = null) {
        // Drop the previous photo's cached small-source, else the slider renders against the old downscale.
        previewSourceSmall = null
        // A Local source has no cloud original; clear any path left from a prior Cloud load.
        cloudOriginalFilePath = null
        clearUndoStacks()
        _state.update { it.copy(source = EditorSource.Local(uri, displayName, mimeType, captureTimeMs), isLoading = true, errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            // BitmapFactory ignores EXIF orientation; bake it into pixels (save re-encodes without EXIF,
            // so baking here avoids double-rotation). Both the decode and the bake are guarded together.
            val outcome = guardedDecode {
                decodeDownsampled(Uri.parse(uri))?.let {
                    ExifHelper.applyOrientation(it, ExifHelper.readOrientation(context, uri))
                }
            }
            val oriented = when (outcome) {
                is DecodeOutcome.Ok -> outcome.bitmap
                DecodeOutcome.OutOfMemory -> return@launch // low-memory error already surfaced
                DecodeOutcome.Failed -> {
                    _state.update { it.copy(isLoading = false,
                        errorMessage = context.getString(R.string.editor_error_load_local)) }
                    return@launch
                }
            }
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
        cloudOriginalFilePath = null
        clearUndoStacks()
        _state.update { it.copy(
            source = EditorSource.External(uri, displayName, mimeType),
            isLoading = true,
            errorMessage = null,
            savedAsCopy = false,
        ) }
        viewModelScope.launch(Dispatchers.IO) {
            // Honour EXIF orientation; see loadLocal. Decode + bake guarded together.
            val outcome = guardedDecode {
                decodeDownsampled(Uri.parse(uri))?.let {
                    ExifHelper.applyOrientation(it, ExifHelper.readOrientation(context, uri))
                }
            }
            val oriented = when (outcome) {
                is DecodeOutcome.Ok -> outcome.bitmap
                DecodeOutcome.OutOfMemory -> return@launch // low-memory error already surfaced
                DecodeOutcome.Failed -> {
                    _state.update { it.copy(
                        isLoading = false,
                        errorMessage = context.getString(R.string.editor_external_load_error),
                    ) }
                    return@launch
                }
            }
            _state.update { it.copy(originalBitmap = oriented, previewBitmap = oriented, isLoading = false) }
        }
    }

    fun loadCloud(photo: CloudPhoto) {
        previewSourceSmall = null
        // Reset until the fresh download lands, so a failed download can't leave a stale original path.
        cloudOriginalFilePath = null
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
            // Keep the full-res original on hand so the save path can re-inject its EXIF (capture time,
            // camera, GPS) into the edited cloud copy, which Bitmap.compress would otherwise drop.
            cloudOriginalFilePath = file.absolutePath
            // Honour EXIF orientation off the downloaded full-res file; see loadLocal. This full-res
            // decode + bake is the editor's largest allocation, so it runs behind the OOM guard.
            val outcome = guardedDecode {
                decodeDownsampled(file.absolutePath)?.let {
                    ExifHelper.applyOrientation(it, ExifHelper.readOrientation(file))
                }
            }
            val oriented = when (outcome) {
                is DecodeOutcome.Ok -> outcome.bitmap
                DecodeOutcome.OutOfMemory -> return@launch // low-memory error already surfaced
                DecodeOutcome.Failed -> {
                    _state.update { it.copy(isLoading = false,
                        errorMessage = context.getString(R.string.editor_error_decode_failed)) }
                    return@launch
                }
            }
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
    fun updateVignette(v: Int) = updateAdjustmentsFast { it.copy(vignette = v.coerceIn(-100, 100)) }
    fun updateFade(v: Int) = updateAdjustmentsFast { it.copy(fade = v.coerceIn(-100, 100)) }
    fun updateVibrance(v: Int) = updateAdjustmentsFast { it.copy(vibrance = v.coerceIn(-100, 100)) }
    fun updateSharpen(v: Int) = updateAdjustmentsFast { it.copy(sharpen = v.coerceIn(-100, 100)) }
    fun updateGrain(v: Int) = updateAdjustmentsFast { it.copy(grain = v.coerceIn(-100, 100)) }

    /** Live curve edit (a graph drag): renders on the fast path. [points] are the channel's control points. */
    fun setCurve(channel: CurveChannel, points: List<CurvePoint>) = updateAdjustmentsFast { adj ->
        when (channel) {
            CurveChannel.RGB -> adj.copy(curveRgb = points)
            CurveChannel.R -> adj.copy(curveR = points)
            CurveChannel.G -> adj.copy(curveG = points)
            CurveChannel.B -> adj.copy(curveB = points)
        }
    }

    /** Reset one channel's curve to the straight line (a full-res render + one undo entry). */
    fun resetCurve(channel: CurveChannel) = updateAdjustments { adj ->
        when (channel) {
            CurveChannel.RGB -> adj.copy(curveRgb = IDENTITY_CURVE)
            CurveChannel.R -> adj.copy(curveR = IDENTITY_CURVE)
            CurveChannel.G -> adj.copy(curveG = IDENTITY_CURVE)
            CurveChannel.B -> adj.copy(curveB = IDENTITY_CURVE)
        }
    }

    /** Live HSL edit (a band slider): renders on the fast path. */
    fun setHslBand(index: Int, band: HslBand) = updateAdjustmentsFast { adj ->
        if (index !in adj.hslBands.indices) return@updateAdjustmentsFast adj
        adj.copy(
            hslBands = adj.hslBands.toMutableList().also {
                it[index] = HslBand(band.hue.coerceIn(-100, 100), band.sat.coerceIn(-100, 100), band.light.coerceIn(-100, 100))
            },
        )
    }

    /** Reset every HSL band (a full-res render + one undo entry). */
    fun resetHsl() = updateAdjustments { it.copy(hslBands = List(8) { HslBand() }) }
    fun updateStraighten(v: Int) = updateAdjustmentsFast { it.copy(straighten = v.coerceIn(-100, 100)) }
    fun updatePerspectiveV(v: Int) = updateAdjustmentsFast { it.copy(perspectiveV = v.coerceIn(-100, 100)) }
    fun updatePerspectiveH(v: Int) = updateAdjustmentsFast { it.copy(perspectiveH = v.coerceIn(-100, 100)) }
    fun resetGeometry() = updateAdjustments { it.copy(straighten = 0, perspectiveV = 0, perspectiveH = 0) }
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

    /** Set the redact brush diameter (screen dp). No re-render: it only sizes the next stroke. */
    fun setRedactBrush(dp: Float) = _state.update { it.copy(redactBrushDp = dp.coerceIn(8f, 80f)) }

    fun addDrawStroke(stroke: DrawStroke) = updateAdjustments { it.copy(drawStrokes = it.drawStrokes + stroke) }
    fun undoLastDrawStroke() = updateAdjustments {
        it.copy(drawStrokes = if (it.drawStrokes.isEmpty()) it.drawStrokes else it.drawStrokes.dropLast(1))
    }
    fun clearDrawStrokes() = updateAdjustments { it.copy(drawStrokes = emptyList()) }

    // ── Text overlays ─────────────────────────────────────────────────────────
    private var textIdCounter = 0

    /** Add a text overlay at the centre and select it. Blank text is ignored. */
    fun addTextItem(text: String) {
        if (text.isBlank()) return
        val id = ++textIdCounter
        updateAdjustments {
            it.copy(textItems = it.textItems + TextItem(id, text, 0.5f, 0.5f, android.graphics.Color.WHITE, 0.08f))
        }
        _state.update { it.copy(selectedTextId = id) }
    }

    fun updateTextItemText(id: Int, text: String) {
        if (text.isBlank()) { removeTextItem(id); return }
        updateAdjustments { it.copy(textItems = it.textItems.map { t -> if (t.id == id) t.copy(text = text) else t }) }
    }

    /** Live drag of a text overlay: fast small-source render, finalised on release. */
    fun moveTextItem(id: Int, cx: Float, cy: Float) = updateAdjustmentsFast {
        it.copy(textItems = it.textItems.map { t -> if (t.id == id) t.copy(cx = cx.coerceIn(0f, 1f), cy = cy.coerceIn(0f, 1f)) else t })
    }

    /** One transform tick on a text overlay: pan it by [dcx]/[dcy] (fractions) and scale by [zoom]
     *  (pinch). One finger drags, two fingers resize. Fast small-source render, finalised on release. */
    fun nudgeTextItem(id: Int, dcx: Float, dcy: Float, zoom: Float) = updateAdjustmentsFast {
        it.copy(textItems = it.textItems.map { t ->
            if (t.id == id) t.copy(
                cx = (t.cx + dcx).coerceIn(0f, 1f),
                cy = (t.cy + dcy).coerceIn(0f, 1f),
                sizeFraction = (t.sizeFraction * zoom).coerceIn(0.02f, 0.35f),
            ) else t
        })
    }

    fun setTextItemColor(id: Int, color: Int) = updateAdjustments {
        it.copy(textItems = it.textItems.map { t -> if (t.id == id) t.copy(color = color) else t })
    }

    fun setTextItemSize(id: Int, sizeFraction: Float) = updateAdjustmentsFast {
        it.copy(textItems = it.textItems.map { t -> if (t.id == id) t.copy(sizeFraction = sizeFraction.coerceIn(0.02f, 0.35f)) else t })
    }

    fun removeTextItem(id: Int) {
        updateAdjustments { it.copy(textItems = it.textItems.filterNot { t -> t.id == id }) }
        _state.update { if (it.selectedTextId == id) it.copy(selectedTextId = null) else it }
    }

    fun selectTextItem(id: Int?) = _state.update { it.copy(selectedTextId = id) }

    /** Enter/leave the Text tool. Re-renders the preview so the baked text clears (entering: the overlay
     *  draws it live) or reappears (leaving). Not an undo step; it is only a preview-mode toggle. */
    fun setTextToolActive(active: Boolean) {
        if (_state.value.textToolActive == active) return
        _state.update { it.copy(textToolActive = active) }
        updateAdjustmentsNoUndo { it }
    }

    /** Strip the pen strokes and text overlays from EVERY preview render, so they live as a Compose layer
     *  over the image (drawn by the overlays) instead of being re-baked on every slider tick or filter tap.
     *  The redact strokes stay baked (their pixelate mode needs the pixels). Save keeps the full pipeline. */
    private fun forPreview(adj: EditorAdjustments): EditorAdjustments =
        adj.copy(drawStrokes = emptyList(), textItems = emptyList())

    /** Live drag/pinch of a text overlay, WITHOUT a re-render: the overlay draws the text, so only the
     *  state changes. [commitTextMove] pushes one undo entry when the gesture ends. */
    private var textMoveSnapshot: EditorAdjustments? = null
    fun nudgeTextItemLive(id: Int, dcx: Float, dcy: Float, zoom: Float, dRotation: Float) = _state.update { s ->
        if (textMoveSnapshot == null) textMoveSnapshot = s.adjustments
        s.copy(adjustments = s.adjustments.copy(textItems = s.adjustments.textItems.map { t ->
            if (t.id == id) t.copy(
                cx = (t.cx + dcx).coerceIn(0f, 1f),
                cy = (t.cy + dcy).coerceIn(0f, 1f),
                sizeFraction = (t.sizeFraction * zoom).coerceIn(0.02f, 0.35f),
                // Store the RAW accumulated angle; the 45-degree snap is applied at DRAW time only, so a
                // slow twist keeps accumulating and can escape a detent instead of being pinned to it.
                rotation = t.rotation + dRotation,
            ) else t
        }))
    }
    fun commitTextMove() {
        val snap = textMoveSnapshot ?: return
        textMoveSnapshot = null
        if (snap != _state.value.adjustments) pushUndo(snap)
    }

    // ── Inline text editing (typed directly on the photo, no dialog) ──────────────
    private var textEditSnapshot: EditorAdjustments? = null

    /** Create an empty text at the centre and open it for inline editing. Returns its id. */
    fun addEmptyTextItem(): Int {
        val id = ++textIdCounter
        textEditSnapshot = _state.value.adjustments
        _state.update { s ->
            s.copy(
                adjustments = s.adjustments.copy(
                    textItems = s.adjustments.textItems + TextItem(id, "", 0.5f, 0.5f, android.graphics.Color.WHITE, 0.08f),
                ),
                selectedTextId = id,
            )
        }
        return id
    }

    /** Snapshot before editing an existing text so the whole edit folds into one undo entry. */
    fun beginTextEdit(id: Int) {
        textEditSnapshot = _state.value.adjustments
        _state.update { it.copy(selectedTextId = id) }
    }

    /** Live inline text change: a plain state update (the overlay field shows it), no heavy re-render. */
    fun setTextItemTextLive(id: Int, text: String) = _state.update { s ->
        s.copy(adjustments = s.adjustments.copy(textItems = s.adjustments.textItems.map { if (it.id == id) it.copy(text = text) else it }))
    }

    /** Finish inline editing: drop the text if left blank, and push one undo entry for the whole edit. */
    fun commitTextEdit(id: Int) {
        val snap = textEditSnapshot
        textEditSnapshot = null
        val item = _state.value.adjustments.textItems.firstOrNull { it.id == id }
        if (item != null && item.text.isBlank()) {
            _state.update { s ->
                s.copy(
                    adjustments = s.adjustments.copy(textItems = s.adjustments.textItems.filterNot { it.id == id }),
                    selectedTextId = if (s.selectedTextId == id) null else s.selectedTextId,
                )
            }
        }
        if (snap != null && snap != _state.value.adjustments) pushUndo(snap)
    }

    /** Reset a single adjustment to its neutral value (a long-press on its chip), one undo entry. */
    fun resetAdjustment(transform: (EditorAdjustments) -> EditorAdjustments) = updateAdjustments(transform)

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
            // Gentle, highlight-safe auto-fix. It corrects luminance through EXPOSURE (a multiplicative gain
            // that rolls off near white) rather than BRIGHTNESS (a flat offset that clips the highlights and
            // was blowing photos out), always pulls a blown top back down, lifts crushed shadows, and adds a
            // little contrast/saturation only when the photo is genuinely flat. A good photo is barely moved.
            val brightnessDelta = 0
            val exposureDelta = when {
                mean < 85 -> 10
                mean < 110 -> 5
                mean > 175 -> -8
                else -> 0
            }
            val contrastDelta = if (spread < 140) 8 else 0
            val saturationDelta = if (spread < 200) 6 else 3
            // Temperature/tone stay 0: auto-WB off one luma histogram is unreliable.
            val highlightsDelta = if (maxL > 232) -14 else 0
            val shadowsDelta = if (minL < 22) 12 else 0
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
        val gen = ++renderGen
        viewModelScope.launch(Dispatchers.Default) {
            // Instant screen-res preview first, so a filter / rotate / crop / auto-fix shows immediately
            // instead of after the slow full-res pass on a big photo, with the crop scaled down to the
            // small source. Only redact strokes skip it (their points are in full-res coordinates).
            if (newAdj.redactStrokes.isEmpty()) {
                val small = ensureSmallSource(orig)
                val sc = if (orig.width > 0) small.width.toFloat() / orig.width.toFloat() else 1f
                val smallPreview = applyAdjustments(small, scaleCropForSmall(forPreview(newAdj), sc))
                if (gen == renderGen) {
                    _state.update { it.copy(adjustments = newAdj, previewBitmap = smallPreview) }
                }
            }
            val newPreview = applyAdjustments(orig, forPreview(newAdj))
            val noCropPreview = applyColorOnly(orig, newAdj)
            if (gen == renderGen) {
                _state.update { it.copy(
                    adjustments = newAdj,
                    previewBitmap = newPreview,
                    adjustedBitmapNoCrop = noCropPreview,
                ) }
            }
            // No eager recycle of the old previewBitmap — see finalizeAdjustments.
        }
    }

    /** [updateAdjustments] without the undo push — for [resetAll] (already cleared stacks) and undo/redo. */
    private fun updateAdjustmentsNoUndo(transform: (EditorAdjustments) -> EditorAdjustments) {
        val orig = _state.value.originalBitmap ?: return
        val newAdj = transform(_state.value.adjustments)
        val gen = ++renderGen
        viewModelScope.launch(Dispatchers.Default) {
            val newPreview = applyAdjustments(orig, forPreview(newAdj))
            val noCropPreview = applyColorOnly(orig, newAdj)
            if (gen == renderGen) {
                _state.update { it.copy(
                    adjustments = newAdj,
                    previewBitmap = newPreview,
                    adjustedBitmapNoCrop = noCropPreview,
                ) }
            }
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
        // Commit the VALUE immediately so the slider / curve graph follows the finger without waiting for
        // the render; the preview catches up async below. Without this the value only landed inside the
        // render coroutine, so a heavy per-pixel effect (HSL, curves) made the control itself stutter by
        // the render duration. No render-gen guard on this path: the live control needs every tick, and
        // one render in flight (the job cancel) is enough; the gen guard is only for the full-res paths.
        _state.update { it.copy(adjustments = newAdj) }
        sliderRenderJob?.cancel()
        sliderRenderJob = viewModelScope.launch(Dispatchers.Default) {
            // Smooth small-source render, with the crop scaled down to match, so a live slider stays fast
            // even after a crop. Only redact strokes force the full-res path (their points are in full-res
            // coordinates and the small source would drift them); that case is rare.
            val newPreview = if (newAdj.redactStrokes.isEmpty()) {
                val small = ensureSmallSource(orig)
                val sc = if (orig.width > 0) small.width.toFloat() / orig.width.toFloat() else 1f
                applyAdjustments(small, scaleCropForSmall(forPreview(newAdj), sc))
            } else {
                applyAdjustments(orig, forPreview(newAdj))
            }
            _state.update { it.copy(previewBitmap = newPreview) }
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

    /** Fine straighten + vertical/horizontal keystone, drawn onto a SAME-SIZE bitmap so the crop and
     *  colour steps after it are unaffected. Returns [source] untouched when nothing is set. The straighten
     *  auto-crops (scales to cover the rotated frame); the keystone scales up so the narrowed edge still
     *  reaches the frame, and the widened edge is simply cropped by the output bounds. */
    private fun applyGeometry(source: Bitmap, adj: EditorAdjustments): Bitmap {
        if (adj.straighten == 0 && adj.perspectiveV == 0 && adj.perspectiveH == 0) return source
        val w = source.width
        val h = source.height
        val angle = adj.straighten / 100f * 15f
        val pv = adj.perspectiveV / 100f * 0.28f * w / 2f
        val ph = adj.perspectiveH / 100f * 0.28f * h / 2f
        val topInset = if (pv > 0) pv else 0f
        val botInset = if (pv < 0) -pv else 0f
        val leftInset = if (ph > 0) ph else 0f
        val rightInset = if (ph < 0) -ph else 0f
        val src = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val dst = floatArrayOf(
            topInset, leftInset,
            w - topInset, rightInset,
            w - botInset, h - rightInset,
            botInset, h - leftInset,
        )
        val m = Matrix()
        m.setPolyToPoly(src, 0, dst, 0, 4)
        val maxInset = maxOf(topInset, botInset, leftInset, rightInset)
        val perspScale = if (maxInset > 0f) (w / (w - 2f * maxInset)).coerceIn(1f, 1.7f) else 1f
        val rad = Math.toRadians(Math.abs(angle.toDouble()))
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        val rotScale = maxOf((w * cos + h * sin) / w, (w * sin + h * cos) / h)
        val post = Matrix().apply {
            postRotate(angle, w / 2f, h / 2f)
            postScale(perspScale * rotScale, perspScale * rotScale, w / 2f, h / 2f)
        }
        m.postConcat(post)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /**
     * Colour matrix + rotation/flip, but not crop or redact strokes, in display orientation — what the
     * Crop overlay renders. May return [source] (don't treat as owned). Run on a background dispatcher.
     */
    private fun applyColorOnly(source: Bitmap, adj: EditorAdjustments): Bitmap {
        val oriented = applyGeometry(rotateAndFlip(source, adj), adj)
        val colorMatrix = buildColorMatrix(adj) ?: return oriented
        val out = Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(oriented, 0f, 0f, paint)
        return out
    }

    /** Radial edge shading: darkens (strength > 0) or lightens (< 0) the corners, clear in the centre out
     *  to mid-radius then ramping to the edge. Draws onto [canvas] in place, so pass an owned bitmap. */
    private fun drawVignette(canvas: Canvas, w: Int, h: Int, strength: Int) {
        if (strength == 0) return
        val cx = w / 2f
        val cy = h / 2f
        val radius = hypot(cx, cy).coerceAtLeast(1f)
        val edgeAlpha = (abs(strength) / 100f * 0.7f * 255f).toInt().coerceIn(0, 255)
        val edgeColor = if (strength > 0) Color.argb(edgeAlpha, 0, 0, 0) else Color.argb(edgeAlpha, 255, 255, 255)
        val shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, edgeColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    /**
     * Per-pixel colour effects (vibrance, sharpen, grain) on an owned copy of [source]. Every pass is
     * BANDED (a slab of rows at a time) so a full-resolution save never allocates a whole-image pixel
     * array; the preview runs on the 720px source where it is trivially cheap. Returns [source] when all
     * three are zero. Sharpen is guarded: a low-memory failure degrades to no sharpening, not a crash.
     */
    private fun applyPixelEffects(source: Bitmap, adj: EditorAdjustments): Bitmap {
        val hasCurve = !isIdentityCurve(adj.curveRgb) || !isIdentityCurve(adj.curveR) ||
            !isIdentityCurve(adj.curveG) || !isIdentityCurve(adj.curveB)
        val hasHsl = adj.hslBands.any { it.hue != 0 || it.sat != 0 || it.light != 0 }
        if (adj.vibrance == 0 && adj.sharpen == 0 && adj.grain == 0 && !hasCurve && !hasHsl) return source
        val target = try {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: OutOfMemoryError) {
            return source
        } ?: return source
        if (adj.sharpen != 0) {
            try {
                sharpenInto(source, target, adj.sharpen)
            } catch (e: OutOfMemoryError) {
                // Keep the copied pixels (the rest can still run); just skip sharpening.
            }
        }
        if (adj.vibrance != 0 || adj.grain != 0 || hasCurve || hasHsl) {
            applyPerPixelColor(target, adj, hasCurve, hasHsl)
        }
        return target
    }

    /** Rows to process per band so a pass holds about two million pixels at a time, never the whole image. */
    private fun bandRowsFor(width: Int, height: Int): Int = (2_000_000 / width.coerceAtLeast(1)).coerceIn(1, height)

    /** Tone curves + per-colour HSL + vibrance + film grain in ONE banded in-place pass, in that order
     *  (tone, then colour band, then vibrance, then grain). Curves and HSL are opt-in via the flags so a
     *  photo with neither pays nothing for them. */
    private fun applyPerPixelColor(bmp: Bitmap, adj: EditorAdjustments, hasCurve: Boolean, hasHsl: Boolean) {
        val w = bmp.width
        val h = bmp.height
        val vib = adj.vibrance / 100f
        val grainAmp = abs(adj.grain) / 100f * 40f // up to +/-40 of monochrome noise
        val rnd = java.util.Random(0x5EED_1234L) // fixed seed: grain is stable across re-renders (no flicker)
        // Per-channel curve = the channel's own curve composed onto the master (RGB) curve.
        val master = if (hasCurve) buildCurveLut(adj.curveRgb) else null
        val lutR = if (hasCurve) composeLut(buildCurveLut(adj.curveR), master!!) else null
        val lutG = if (hasCurve) composeLut(buildCurveLut(adj.curveG), master!!) else null
        val lutB = if (hasCurve) composeLut(buildCurveLut(adj.curveB), master!!) else null
        val hslLuts = if (hasHsl) buildHslLuts(adj.hslBands) else null
        val rows = bandRowsFor(w, h)
        val band = IntArray(w * rows)
        var y = 0
        while (y < h) {
            val n = minOf(rows, h - y)
            bmp.getPixels(band, 0, w, 0, y, w, n)
            for (i in 0 until w * n) {
                val p = band[i]
                val a = (p ushr 24) and 0xFF
                var r = (p ushr 16) and 0xFF
                var g = (p ushr 8) and 0xFF
                var b = p and 0xFF
                if (hasCurve) {
                    r = lutR!![r]; g = lutG!![g]; b = lutB!![b]
                }
                if (hslLuts != null) {
                    val packed = hslAdjustPixel(r, g, b, hslLuts[0], hslLuts[1], hslLuts[2])
                    r = (packed ushr 16) and 0xFF; g = (packed ushr 8) and 0xFF; b = packed and 0xFF
                }
                if (vib != 0f) {
                    val gray = 0.299f * r + 0.587f * g + 0.114f * b
                    val sat = (maxOf(r, g, b) - minOf(r, g, b)) / 255f
                    val boost = 1f + vib * (1f - sat)
                    r = (gray + (r - gray) * boost).roundToInt().coerceIn(0, 255)
                    g = (gray + (g - gray) * boost).roundToInt().coerceIn(0, 255)
                    b = (gray + (b - gray) * boost).roundToInt().coerceIn(0, 255)
                }
                if (grainAmp != 0f) {
                    val noise = ((rnd.nextFloat() - 0.5f) * 2f * grainAmp).roundToInt()
                    r = (r + noise).coerceIn(0, 255)
                    g = (g + noise).coerceIn(0, 255)
                    b = (b + noise).coerceIn(0, 255)
                }
                band[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(band, 0, w, 0, y, w, n)
            y += n
        }
    }

    /** Compose two 256-LUTs: apply [first] (the master) then [second] (the channel). */
    private fun composeLut(second: IntArray, first: IntArray): IntArray = IntArray(256) { i -> second[first[i]] }

    /** Apply the HSL band adjustments to one pixel; returns packed RGB (no alpha). A near-gray pixel has
     *  no stable hue, so it is returned unchanged. */
    /** Precompute the per-hue HSL adjustment (hue shift in degrees, saturation multiplier, lightness add)
     *  from the 8 bands ONCE per render, so the pixel loop is one lookup instead of an 8-band blend. Index
     *  is the whole-degree hue 0..359. This is what makes an HSL drag smooth. */
    private fun buildHslLuts(bands: List<HslBand>): Array<FloatArray> {
        val hueShift = FloatArray(360)
        val satMul = FloatArray(360)
        val lightAdd = FloatArray(360)
        for (deg in 0 until 360) {
            var hs = 0f
            var sm = 0f
            var la = 0f
            for (i in bands.indices) {
                val bd = bands[i]
                if (bd.hue == 0 && bd.sat == 0 && bd.light == 0) continue
                val wgt = hslBandWeight(deg.toFloat(), HSL_BAND_CENTERS[i])
                if (wgt <= 0f) continue
                hs += wgt * (bd.hue / 100f) * 30f
                sm += wgt * (bd.sat / 100f)
                la += wgt * (bd.light / 100f) * 0.3f
            }
            hueShift[deg] = hs
            satMul[deg] = sm
            lightAdd[deg] = la
        }
        return arrayOf(hueShift, satMul, lightAdd)
    }

    private fun hslAdjustPixel(r0: Int, g0: Int, b0: Int, hueShiftLut: FloatArray, satMulLut: FloatArray, lightAddLut: FloatArray): Int {
        val rf = r0 / 255f
        val gf = g0 / 255f
        val bf = b0 / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min
        if (delta < 1e-4f) return (r0 shl 16) or (g0 shl 8) or b0
        val l = (max + min) / 2f
        val s = if (l > 0.5f) delta / (2f - max - min) else delta / (max + min)
        var hue = when (max) {
            rf -> ((gf - bf) / delta) % 6f
            gf -> (bf - rf) / delta + 2f
            else -> (rf - gf) / delta + 4f
        } * 60f
        if (hue < 0f) hue += 360f
        val hi = hue.toInt().coerceIn(0, 359)
        val hueShift = hueShiftLut[hi]
        val satMul = satMulLut[hi]
        val lightAdd = lightAddLut[hi]
        if (hueShift == 0f && satMul == 0f && lightAdd == 0f) return (r0 shl 16) or (g0 shl 8) or b0
        var nh = (hue + hueShift) % 360f
        if (nh < 0f) nh += 360f
        val ns = (s * (1f + satMul)).coerceIn(0f, 1f)
        val nl = (l + lightAdd).coerceIn(0f, 1f)
        val c = (1f - abs(2f * nl - 1f)) * ns
        val x = c * (1f - abs((nh / 60f) % 2f - 1f))
        val m = nl - c / 2f
        val rr: Float
        val gg: Float
        val bb: Float
        when {
            nh < 60f -> { rr = c; gg = x; bb = 0f }
            nh < 120f -> { rr = x; gg = c; bb = 0f }
            nh < 180f -> { rr = 0f; gg = c; bb = x }
            nh < 240f -> { rr = 0f; gg = x; bb = c }
            nh < 300f -> { rr = x; gg = 0f; bb = c }
            else -> { rr = c; gg = 0f; bb = x }
        }
        val ri = ((rr + m) * 255f).roundToInt().coerceIn(0, 255)
        val gi = ((gg + m) * 255f).roundToInt().coerceIn(0, 255)
        val bi = ((bb + m) * 255f).roundToInt().coerceIn(0, 255)
        return (ri shl 16) or (gi shl 8) or bi
    }

    /** Unsharp mask: blur (downscale then upscale) and push each pixel away from the blurred value. Reads
     *  [source] (unmodified) and writes [target], banded, so band edges stay correct. */
    private fun sharpenInto(source: Bitmap, target: Bitmap, sharpen: Int) {
        val w = source.width
        val h = source.height
        val amt = sharpen / 100f
        val ds = 3
        val small = Bitmap.createScaledBitmap(source, (w / ds).coerceAtLeast(1), (h / ds).coerceAtLeast(1), true)
        val blurred = Bitmap.createScaledBitmap(small, w, h, true)
        if (small !== blurred && !small.isRecycled) small.recycle()
        val rows = bandRowsFor(w, h)
        val srcBand = IntArray(w * rows)
        val blurBand = IntArray(w * rows)
        var y = 0
        while (y < h) {
            val n = minOf(rows, h - y)
            source.getPixels(srcBand, 0, w, 0, y, w, n)
            blurred.getPixels(blurBand, 0, w, 0, y, w, n)
            for (i in 0 until w * n) {
                val sp = srcBand[i]
                val bp = blurBand[i]
                val a = (sp ushr 24) and 0xFF
                val r = sharpenChannel((sp ushr 16) and 0xFF, (bp ushr 16) and 0xFF, amt)
                val g = sharpenChannel((sp ushr 8) and 0xFF, (bp ushr 8) and 0xFF, amt)
                val b = sharpenChannel(sp and 0xFF, bp and 0xFF, amt)
                srcBand[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            target.setPixels(srcBand, 0, w, 0, y, w, n)
            y += n
        }
        if (!blurred.isRecycled) blurred.recycle()
    }

    private fun sharpenChannel(orig: Int, blur: Int, amt: Float): Int =
        (orig + amt * (orig - blur)).roundToInt().coerceIn(0, 255)

    /** Burns each normalised pen stroke into a copy of [src]. Points and width are fractions of the
     *  framed image, so a stroke drawn on the small preview renders identically at full-res on save. */
    private fun applyDrawStrokes(src: Bitmap, strokes: List<DrawStroke>): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = (stroke.widthFraction * w).coerceAtLeast(1f)
                color = stroke.color
            }
            if (stroke.points.size == 1) {
                val p = stroke.points[0]
                canvas.drawCircle(p.x * w, p.y * h, paint.strokeWidth / 2f, paint.apply { style = Paint.Style.FILL })
            } else {
                val path = android.graphics.Path()
                stroke.points.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h)
                }
                canvas.drawPath(path, paint)
            }
        }
        return out
    }

    /** Bakes each text overlay onto a copy of [src]. Centre and size are fractions of the framed image,
     *  so text placed on the small preview lands identically on the full-res save. Splits on newlines. */
    private fun applyTextItems(src: Bitmap, items: List<TextItem>): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        for (item in items) {
            if (item.text.isBlank()) continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = item.color
                textSize = (item.sizeFraction * h).coerceAtLeast(8f)
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                // A soft shadow keeps light text legible over a bright photo.
                setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, Color.argb(140, 0, 0, 0))
            }
            val cxPx = item.cx * w
            val cyPx = item.cy * h
            val lines = item.text.split("\n")
            val lineH = paint.textSize * 1.2f
            val totalH = lineH * lines.size
            canvas.save()
            val angle = snapTextAngle(item.rotation)
            if (angle != 0f) canvas.rotate(angle, cxPx, cyPx)
            var baseline = cyPx - totalH / 2f + paint.textSize
            for (line in lines) {
                canvas.drawText(line, cxPx, baseline, paint)
                baseline += lineH
            }
            canvas.restore()
        }
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

        // 1b. straighten + keystone geometry (same size as [rotated]), before crop so the crop rect,
        //     authored on this straightened image (see applyColorOnly), lands on the matching pixels.
        val geo = applyGeometry(rotated, adj)
        recycle(rotated, geo)

        // 2. crop, clamped against the geometry bitmap's dimensions (display space).
        val cropped = adj.cropRect?.let {
            val safe = Rect(
                it.left.coerceIn(0, geo.width - 1),
                it.top.coerceIn(0, geo.height - 1),
                it.right.coerceIn(1, geo.width),
                it.bottom.coerceIn(1, geo.height),
            )
            if (safe.width() > 0 && safe.height() > 0)
                Bitmap.createBitmap(geo, safe.left, safe.top, safe.width(), safe.height())
            else geo
        } ?: geo
        recycle(geo, cropped)

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

        // 3b. per-pixel colour effects (vibrance, sharpen, grain), between the colour matrix and vignette.
        val toned = applyPixelEffects(colored, adj)
        recycle(colored, toned)

        // 3c. vignette: radial edge shading on the framed image, before redaction so marks stay on top.
        val vignetted = if (adj.vignette == 0) toned else {
            val out = Bitmap.createBitmap(toned.width, toned.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(toned, 0f, 0f, null)
            drawVignette(canvas, toned.width, toned.height, adj.vignette)
            out
        }
        recycle(toned, vignetted)

        // 4. redaction strokes cover the final visible content
        val redacted = if (adj.redactStrokes.isEmpty()) vignetted
            else applyRedactStrokes(vignetted, adj.redactStrokes, recycleIntermediates)
        recycle(vignetted, redacted)

        // 5. pen strokes (normalised geometry, any resolution)
        val drawn = if (adj.drawStrokes.isEmpty()) redacted else applyDrawStrokes(redacted, adj.drawStrokes)
        recycle(redacted, drawn)

        // 6. text overlays, drawn last, on top of everything.
        val final = if (adj.textItems.isEmpty()) drawn else applyTextItems(drawn, adj.textItems)
        recycle(drawn, final)

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
            && adj.temperature == 0 && adj.tone == 0 && adj.fade == 0
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

        // Fade / matte: positive lifts the black point and eases contrast for a washed film look; negative
        // deepens blacks and adds contrast (punch). Contrast scale and lift folded into one matrix.
        if (adj.fade != 0) {
            val f = adj.fade / 100f              // -1..1
            val fadeContrast = 1f - f * 0.2f     // fade -> 0.8x, punch -> 1.2x
            val fadeLift = f * 30f               // fade -> +30 lift, punch -> -30 deepen
            val mFade = ColorMatrix(floatArrayOf(
                fadeContrast, 0f, 0f, 0f, fadeLift,
                0f, fadeContrast, 0f, 0f, fadeLift,
                0f, 0f, fadeContrast, 0f, fadeLift,
                0f, 0f, 0f, 1f, 0f,
            ))
            combined.postConcat(mFade)
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
        FilterPreset.Noir -> ColorMatrix(floatArrayOf(
            0.389f, 0.763f, 0.148f, 0f, -35f,
            0.389f, 0.763f, 0.148f, 0f, -35f,
            0.389f, 0.763f, 0.148f, 0f, -35f,
            0f, 0f, 0f, 1f, 0f,
        ))
        FilterPreset.Chrome -> ColorMatrix(floatArrayOf(
            1.18f, 0f, 0f, 0f, -8f,
            0f, 1.16f, 0f, 0f, -6f,
            0f, 0f, 1.22f, 0f, 2f,
            0f, 0f, 0f, 1f, 0f,
        ))
        FilterPreset.Matte -> ColorMatrix(floatArrayOf(
            0.88f, 0f, 0f, 0f, 22f,
            0f, 0.88f, 0f, 0f, 20f,
            0f, 0f, 0.86f, 0f, 16f,
            0f, 0f, 0f, 1f, 0f,
        ))
        FilterPreset.Dramatic -> ColorMatrix(floatArrayOf(
            1.25f, -0.06f, -0.06f, 0f, -20f,
            -0.06f, 1.25f, -0.06f, 0f, -20f,
            -0.06f, -0.06f, 1.25f, 0f, -20f,
            0f, 0f, 0f, 1f, 0f,
        ))
        FilterPreset.Fresh -> ColorMatrix(floatArrayOf(
            1.06f, 0f, 0f, 0f, 8f,
            0f, 1.1f, 0f, 0f, 12f,
            0f, 0f, 1.08f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    /**
     * Saves the edited bitmap per [mode] and [EditorSource]. Local: Overwrite writes back to the
     * source URI, Copy inserts a new MediaStore entry. Cloud and synced sources always save as a new
     * copy (the SaveSheet only offers Overwrite for a device-only photo): the Photos backend refuses
     * a second revision on a photo link, so the original is left in place and a fresh file uploads.
     */
    private var pendingWriteMode: SaveMode? = null
    private var pendingWriteQuality: Int = 92

    fun save(
        mode: SaveMode,
        quality: Int = 92,
        /** Export container for a COPY (device/external). Overwrite/cloud/synced/vault ignore it. */
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        /** Longest-edge cap for the exported pixels; null keeps the full edited resolution. */
        maxDim: Int? = null,
        allowWriteRequestRecovery: Boolean = true,
    ) {
        val s = _state.value
        val source = s.source ?: return
        val orig = s.originalBitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isSaving = true, saveResult = null) }
            // Never overwrite a container that Bitmap.compress cannot reproduce (RAW/DNG, HEIC, GIF, ...)
            // OR a Motion Photo (a JPEG with an MP4 appended after the still): an in-place rewrite writes
            // JPEG bytes over the file and destroys the original or truncates its video. Coerce such an
            // Overwrite to a fresh JPEG Copy and tell the user the original was kept. External sources
            // already always copy, so they are unaffected.
            val (sourceMime, sourceName) = when (source) {
                is EditorSource.Local -> source.mimeType to source.displayName
                is EditorSource.Cloud -> source.photo.mimeType to source.photo.displayName
                is EditorSource.External -> source.mimeType to source.displayName
            }
            val overwritable = overwriteFormatFor(sourceMime, sourceName) != null
            // Only a JPEG primary can hide a Motion Photo trailer, and only a device file is overwritten
            // in place; the check is a bounded XMP prefix read, cheap and off the main thread here.
            val isMotionOverwrite = mode == SaveMode.Overwrite && overwritable &&
                source is EditorSource.Local && localSourceIsMotionPhoto(source)
            val coercedToCopy = mode == SaveMode.Overwrite &&
                (source is EditorSource.Local || source is EditorSource.Cloud) &&
                overwriteCoercesToCopy(overwritable, isMotionOverwrite)
            val effectiveMode = if (coercedToCopy) SaveMode.Copy else mode
            // Pick the coerce message: an unwritable format, versus a writable but Motion Photo source.
            val coerceMessageRes = if (overwritable) R.string.editor_overwrite_motion_coerced_to_copy
                else R.string.editor_overwrite_coerced_to_copy
            // Re-render full-res from the original, not the 720px slider preview (which would degrade the
            // save). Off-screen, so intermediates are safe to recycle. Then apply the export size choice.
            val rendered = applyAdjustments(orig, s.adjustments, recycleIntermediates = true)
            val bitmap = if (maxDim != null) downscaleToMax(rendered, maxDim) else rendered
            // One timestamp shared by the device save AND the cloud upload so they get the same filename;
            // reconcile's byNameAndDate then pairs them as Synced without a download. Only the FILENAME
            // stamp uses the edit instant; the saved DATE is the original capture time (below).
            val editTimestampMs = System.currentTimeMillis()
            // The DATE stamped on a Copy: inherit the original photo's capture time so the edit sorts next
            // to the original instead of jumping to the top of the timeline. Cloud captureTime is seconds;
            // a Local source carries its DATE_TAKEN (or is queried from MediaStore), falling back to now.
            // The device copy and the cloud copy both use THIS value so they share the same capture second
            // and reconcile still pairs them. Overwrite paths ignore it (they keep the source captureTime).
            val originalCaptureMs: Long = when (source) {
                is EditorSource.Cloud -> source.photo.captureTime * 1000L
                is EditorSource.Local -> source.captureTimeMs ?: queryDateTakenMs(Uri.parse(source.uri)) ?: editTimestampMs
                is EditorSource.External -> editTimestampMs
            }
            val saveResult: SaveResult = try {
                // Cloud-only edit: there is no local file to write, so the whole save is the network
                // upload. Run it in appScope (survives navigation) and return an optimistic Success right
                // away. This upload is best-effort: there is no local sync_state row to reconcile against
                // (unlike a Synced edit), so a background failure is NOT retried, only logged in the
                // transfer center. The user can re-edit to try again.
                if (source is EditorSource.Cloud) {
                    appScope.launch {
                        val thumbUri = "file://" + File(context.cacheDir, "thumbnails/thumb_${source.photo.linkId}.jpg").absolutePath
                        val tid = transferCenter.start(TransferCenter.Kind.UPLOAD, total = 1, items = listOf(thumbUri))
                        try {
                            val ok = runCatching {
                                saveCloud(bitmap, source, effectiveMode, quality, editTimestampMs, originalCaptureMs)
                            }.isSuccess
                            transferCenter.progress(tid, 1)
                            if (ok) transferCenter.log(
                                TransferCenter.Kind.UPLOAD, count = 1,
                                name = context.getString(R.string.activity_hist_edited), uris = listOf(thumbUri),
                            )
                        } finally {
                            transferCenter.finish(tid)
                        }
                    }
                    // A coerced overwrite still succeeds, just as a fresh JPEG copy, reported through the
                    // success-as-copy channel (toast) so the user learns the original format was kept.
                    val optimistic = if (coercedToCopy) SaveResult.SuccessAsCopy(null, coerceMessageRes)
                        else SaveResult.Success(null)
                    _state.update { it.copy(isSaving = false, saveResult = optimistic) }
                    return@launch
                }
                val uri = when (source) {
                    is EditorSource.Local -> saveLocal(bitmap, source, effectiveMode, quality, editTimestampMs, originalCaptureMs, format)
                    is EditorSource.Cloud -> null // handled above
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
                            dateTakenMs = originalCaptureMs,
                            format = format,
                        )
                        if (resultUri != null) {
                            _state.update { it.copy(savedAsCopy = true) }
                        }
                        resultUri
                    }
                }
                // Mirror the upload strip onto the on-device copy when the user asked for it (strip-on-upload
                // plus mirror-to-local), matching the backup. Covers both the Local Overwrite/Copy write and
                // the External copy; the device file was saved with full EXIF just above, so this removes the
                // configured fields in place. Off by default, so the device copy otherwise keeps full EXIF.
                if (uri != null && (source is EditorSource.Local || source is EditorSource.External)) {
                    mirrorStripLocalCopyIfEnabled(uri)
                }
                // Invalidate Coil caches on every local save — Overwrite reuses the URI (stale bytes),
                // Copy's notifyChange wakes MediaStore observers. Cloud uploads have no existing key.
                if (uri != null && source is EditorSource.Local) {
                    invalidateImageCache(uri)
                }
                // Synced photo: also push the edit to the cloud counterpart, but do NOT await the network
                // upload here (that is the 10-15s freeze). Seed the UPLOADING placeholder inline (the
                // MediaStore insert already fired the sync observer, so this row claims it and Reconcile/
                // SyncWorker skip UPLOADING, else SyncWorker would race and upload a duplicate), then run
                // the upload in appScope so the editor returns immediately. On success the row goes SYNCED;
                // on ANY failure it drops to LOCAL_ONLY (never left stuck at UPLOADING) so the normal
                // SyncWorker retries it, no lost edit.
                val counterpart = cloudCounterpart
                if (source is EditorSource.Local && counterpart != null && uri != null) {
                    val userId = accountManager.getPrimaryUserId().first()
                    if (userId != null) {
                        val savedUriStr = uri.toString()
                        // The device original, whose EXIF (GPS/camera/capture time) seeds the cloud copy's
                        // re-injected metadata and xAttr. Captured before the appScope handoff below.
                        val originalDeviceUri = source.uri
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
                        // Mark the seeded row queued=EDITOR so a failed edit upload that demotes to
                        // LOCAL_ONLY stays eligible under the future queue selector. The upsert above
                        // round-trips a domain SyncState, whose mapper carries no queue columns, so this
                        // separate DAO write is what sets queued/queueSource; it runs after the upsert so
                        // it lands on the just-seeded row.
                        runCatching {
                            syncStateRepo.markQueued(
                                savedUriStr, eu.akoos.photos.domain.entity.QueueSource.EDITOR,
                                System.currentTimeMillis(),
                            )
                        }
                        appScope.launch {
                            // Track the replacement upload on the Activity monitor + avatar ring for its
                            // duration; the saved local copy's URI is the row thumbnail. finish() runs in
                            // the finally so the ring clears whether the upload succeeds, fails, or cancels.
                            val tid = transferCenter.start(TransferCenter.Kind.UPLOAD, total = 1, items = listOf(savedUriStr))
                            try {
                                val uploadResult = uploadEditAsCloudReplacement(
                                    bitmap, counterpart, effectiveMode, quality, editTimestampMs, originalCaptureMs, userId,
                                    originalDeviceUri,
                                )
                                val newLinkId = uploadResult.linkId
                                transferCenter.progress(tid, 1)
                                transferCenter.log(
                                    TransferCenter.Kind.UPLOAD, count = 1,
                                    name = context.getString(R.string.activity_hist_edited), uris = listOf(savedUriStr),
                                )
                                runCatching {
                                    syncStateRepo.upsert(
                                        placeholderState.copy(
                                            cloudFileId = newLinkId,
                                            // Store the uploaded plaintext's bare sha1 so reconcile can
                                            // map it to the cloud ContentHash and keep the pair Synced
                                            // once this fresh linkId is later demoted to LOCAL_ONLY.
                                            localHash = uploadResult.contentSha1,
                                            status = eu.akoos.photos.domain.entity.SyncStatus.SYNCED,
                                            lastSyncSuccessMs = System.currentTimeMillis(),
                                            backedUpAtMs = System.currentTimeMillis(),
                                        ),
                                        userId,
                                    )
                                    // RULE 1: the edit is backed up, so clear the queued flag (the upsert
                                    // already reset the queue columns, this keeps the intent explicitly
                                    // satisfied and mirrors the SYNCED-write handling elsewhere).
                                    syncStateRepo.clearQueuedForSynced(savedUriStr)
                                }
                            } catch (t: Throwable) {
                                // Land the row at LOCAL_ONLY so the background SyncWorker retries it instead
                                // of leaving a stuck UPLOADING row. Re-throw cancellation so scope teardown
                                // (should the app scope ever be cancelled) is not swallowed.
                                if (t is kotlinx.coroutines.CancellationException) throw t
                                runCatching {
                                    syncStateRepo.upsert(
                                        placeholderState.copy(status = eu.akoos.photos.domain.entity.SyncStatus.LOCAL_ONLY),
                                        userId,
                                    )
                                    // Re-stamp queued=EDITOR after the reset so the demoted edit stays
                                    // eligible for the background retry under the queue selector.
                                    // The upsert cleared the queue columns, so this per-row UPDATE runs
                                    // after it to land the flag on the now-LOCAL_ONLY row.
                                    syncStateRepo.markQueued(
                                        savedUriStr, eu.akoos.photos.domain.entity.QueueSource.EDITOR,
                                        System.currentTimeMillis(),
                                    )
                                }
                            } finally {
                                transferCenter.finish(tid)
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
                    && effectiveMode == SaveMode.Overwrite
                ) {
                    val srcUri = Uri.parse(source.uri)
                    val stuck = isItemPendingOrTrashed(srcUri)
                    if (!stuck) {
                        val request = runCatching {
                            MediaStore.createWriteRequest(context.contentResolver, listOf(srcUri))
                        }.getOrNull()
                        if (request != null) {
                            pendingWriteMode = effectiveMode
                            pendingWriteQuality = quality
                            _state.update {
                                it.copy(isSaving = false, pendingWriteIntent = request)
                            }
                            return@launch
                        }
                    }
                }
                if (source is EditorSource.Local && effectiveMode == SaveMode.Overwrite) {
                    runCatching {
                        insertLocalCopy(bitmap, source, quality, useOriginalName = true)
                    }.fold(
                        onSuccess = { uri ->
                            // Same mirror-to-local strip as the normal Copy path: the overwrite fell back to
                            // a fresh device copy (written with full EXIF), so strip it in place when enabled.
                            uri?.let { mirrorStripLocalCopyIfEnabled(it) }
                            // Synced Overwrite that fell back to Copy strands the original next to the edit.
                            // Quiet delete works for app-owned files; foreign URIs need OS consent
                            // (createDeleteRequest, launched by the screen). Sync re-pairs by hash afterwards.
                            // Defensive/unreachable under the current SaveSheet gating: Overwrite is offered
                            // ONLY for a device-only photo (!isCloud && !isSynced), so a Synced photo
                            // (cloudCounterpart != null) never reaches this Overwrite path. Kept so that if
                            // that gating ever changes, the stranded device original is still cleaned up
                            // rather than left as a silent duplicate next to the edit.
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
            // A coerced overwrite still succeeded, just as a fresh JPEG copy. Report it through the
            // neutral success-as-copy channel (a toast, then navigate away) with a format-specific
            // message, rather than the error popup, so a successful save never reads as an error.
            val finalResult = if (coercedToCopy && saveResult is SaveResult.Success) {
                SaveResult.SuccessAsCopy(saveResult.uri, coerceMessageRes)
            } else {
                saveResult
            }
            _state.update { it.copy(isSaving = false, saveResult = finalResult) }
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

    /**
     * The [Bitmap.CompressFormat] that can faithfully OVERWRITE a source of [mimeType] in place, or
     * null when the container cannot be reproduced by [Bitmap.compress] (RAW/DNG, HEIC/HEIF, GIF, BMP,
     * TIFF, ...). A null result means "do not clobber the original", so [save] diverts that case to a
     * fresh JPEG copy instead. When the mime is blank or a generic wildcard (an image wildcard or
     * an octet-stream) the file extension from [displayName] is consulted as a fallback.
     */
    /** Cheap Motion Photo screen on a device source: a bounded XMP prefix read (no full-file copy) via
     *  the same detector the backup uses. Only a JPEG primary carries a motion trailer, so any other
     *  format is skipped. A read error is treated as "not a motion photo" so a save is never blocked. */
    private fun localSourceIsMotionPhoto(source: EditorSource.Local): Boolean {
        val mime = source.mimeType.trim().lowercase(java.util.Locale.ROOT)
        if (mime != "image/jpeg" && mime != "image/jpg") return false
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(source.uri))?.use { MotionPhotoUtil.hasMotionXmp(it) }
        }.getOrNull() == true
    }

    private fun overwriteFormatFor(mimeType: String, displayName: String): Bitmap.CompressFormat? {
        fun formatForMime(mime: String): Bitmap.CompressFormat? = when (mime) {
            "image/jpeg", "image/jpg" -> Bitmap.CompressFormat.JPEG
            "image/png" -> Bitmap.CompressFormat.PNG
            "image/webp" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> null
        }
        val mime = mimeType.trim().lowercase(java.util.Locale.ROOT)
        formatForMime(mime)?.let { return it }
        // Generic / blank mime: fall back to the extension on the display name.
        if (mime.isEmpty() || mime == "image/*" || mime == "application/octet-stream" || mime == "*/*") {
            val ext = displayName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            return when (ext) {
                "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                "png" -> Bitmap.CompressFormat.PNG
                "webp" ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                    else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                else -> null
            }
        }
        return null
    }

    /** MIME string to stamp for an overwrite in the matched [format], so the container label stays honest. */
    private fun mimeForFormat(format: Bitmap.CompressFormat): String = when (format) {
        Bitmap.CompressFormat.PNG -> "image/png"
        Bitmap.CompressFormat.JPEG -> "image/jpeg"
        else -> "image/webp" // WEBP / WEBP_LOSSY / WEBP_LOSSLESS
    }

    private fun extensionForFormat(format: Bitmap.CompressFormat): String = when (format) {
        Bitmap.CompressFormat.PNG -> "png"
        Bitmap.CompressFormat.JPEG -> "jpg"
        else -> "webp"
    }

    /** Downscale [bitmap] so its longest edge is at most [maxDim] (export size choice); returns the same
     *  bitmap when it already fits, so "Original" and small photos pay nothing. */
    private fun downscaleToMax(bitmap: Bitmap, maxDim: Int): Bitmap {
        if (maxOf(bitmap.width, bitmap.height) <= maxDim) return bitmap
        val (w, h) = exportTargetSize(bitmap.width, bitmap.height, maxDim)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private suspend fun saveLocal(bitmap: Bitmap, source: EditorSource.Local, mode: SaveMode, quality: Int, editTimestampMs: Long, dateTakenMs: Long, format: Bitmap.CompressFormat): Uri? {
        return when (mode) {
            SaveMode.Overwrite -> overwriteLocal(bitmap, source, quality)
            // A copy of a vaulted photo stays in the vault. The MediaStore insert below would put the
            // edited pixels of a photo the user hid into a plain visible file in the camera folder, which is
            // the hide undone rather than a copy of it.
            SaveMode.Copy      ->
                if (hiddenStorage.isHiddenUri(source.uri))
                    insertVaultCopy(bitmap, source, quality, editTimestampMs = editTimestampMs, dateTakenMs = dateTakenMs)
                else
                    insertLocalCopy(bitmap, source, quality, editTimestampMs = editTimestampMs, dateTakenMs = dateTakenMs, format = format)
        }
    }

    /**
     * Writes the edited pixels as a SECOND vaulted photo, returning its `file://` uri, and leaves the
     * one that was edited exactly as it is.
     *
     * The vault's own writer produces the file, so the copy is named the way every hidden photo is —
     * a private code carrying its capture time, which is what the grid sorts on and what a reveal
     * rebuilds DATE_TAKEN from — and the vault then records it as a hidden photo of its own, giving it
     * the same name, folder and reveal every other one has.
     */
    private suspend fun insertVaultCopy(
        bitmap: Bitmap,
        source: EditorSource.Local,
        quality: Int,
        editTimestampMs: Long,
        dateTakenMs: Long,
    ): Uri? {
        val copyUri = hiddenStorage.create("jpg", dateTakenMs) { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        } ?: error("Could not write the copy into the hidden area")
        // Re-inject the source's EXIF so the copy is a metadata-complete twin except for the pixels,
        // matching what a copy of any other device photo keeps.
        val originalExif = ExifHelper.readExifSnapshot(context, source.uri)
        if (originalExif != null) {
            runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(copyUri), "rw")?.use { pfd ->
                    ExifHelper.copyExifPreservingOrientation(
                        originalExif, pfd.fileDescriptor, bitmap.width, bitmap.height,
                    )
                }
            }
        }
        hiddenVaultEditor.adoptCopy(
            sourceUri = source.uri,
            copyUri = copyUri,
            displayName = HiddenVaultRecords.recordedName(stamp(source.displayName, editTimestampMs), "jpg"),
        )
        return Uri.parse(copyUri)
    }

    // Throws SecurityException on foreign URIs (caller recovers). No IS_PENDING dance — on a foreign
    // URI it traps the file in pending state and blocks the write. Only reached for jpeg/png/webp
    // sources; save() coerces any other source format to a fresh Copy before this runs.
    private fun overwriteLocal(bitmap: Bitmap, source: EditorSource.Local, quality: Int): Uri {
        val srcUri = Uri.parse(source.uri)
        val format = overwriteFormatFor(source.mimeType, source.displayName) ?: Bitmap.CompressFormat.JPEG
        // Capture the original EXIF BEFORE the overwrite clobbers the file (source == destination here),
        // so it can be re-injected onto the edited bytes below. The local copy always keeps the full
        // original EXIF (strip-on-upload governs only the uploaded cloud copy, not the on-device file).
        val originalExif = ExifHelper.readExifSnapshot(context, source.uri)
        context.contentResolver.openOutputStream(srcUri, "wt")?.use { out ->
            bitmap.compress(format, quality, out)
        } ?: error("openOutputStream returned null for $srcUri")
        // Re-inject the original's EXIF onto the just-written file, forcing NORMAL orientation and the
        // edited pixel size. Never fails the save (the helper swallows unsupported-format / IO errors).
        if (originalExif != null) {
            runCatching {
                context.contentResolver.openFileDescriptor(srcUri, "rw")?.use { pfd ->
                    ExifHelper.copyExifPreservingOrientation(
                        originalExif, pfd.fileDescriptor, bitmap.width, bitmap.height,
                    )
                }
            }
        }
        // Keep the MediaStore MIME label in sync with the bytes just written (a PNG source stays PNG,
        // a WebP stays WebP); the pixels changed but the container did not.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeForFormat(format))
                }
                context.contentResolver.update(srcUri, values, null, null)
            }
        }
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

    /** Reads MediaStore DATE_TAKEN (ms) for a content [uri], or null when the column is missing/blank.
     *  Fallback for a Local source whose capture time was not plumbed in from the caller. */
    private fun queryDateTakenMs(uri: Uri): Long? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DATE_TAKEN),
                null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                if (cursor.isNull(0)) return@use null
                cursor.getLong(0).takeIf { it > 0L }
            }
        }.getOrNull()
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
        /** Shared timestamp for the FILENAME only; pass the same Long to the cloud upload so both copies
         *  carry an identical `_edit_<ts>` name and reconcile's byNameAndDate match pairs them. */
        editTimestampMs: Long = System.currentTimeMillis(),
        /** The DATE stamped on the copy: the original photo's capture time (ms), so the edit sorts next
         *  to the original. Defaults to [editTimestampMs] for the Overwrite-fallback path. */
        dateTakenMs: Long = editTimestampMs,
        /** Output container for the copy (the export format choice). Only the copy path exposes it; the
         *  Overwrite-fallback keeps JPEG. Cloud/synced copies stay JPEG so reconcile pairs them by name. */
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    ): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val newName = if (useOriginalName) source.displayName
            else stamp(source.displayName, editTimestampMs, extensionForFormat(format))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, newName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeForFormat(format))
            // Explicit DATE_TAKEN (ms) so reconcile.byNameAndDate finds the cloud sibling; DATE_MODIFIED is seconds.
            put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMs)
            put(MediaStore.Images.Media.DATE_MODIFIED, dateTakenMs / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, eu.akoos.photos.util.ProtonPhotosStorage.DEFAULT_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(format, quality, out)
        } ?: error("openOutputStream returned null")
        // Re-inject the original's EXIF (capture time, camera, GPS) into the fresh copy while it is still
        // pending, so the published file is a metadata-complete twin of the original except the pixels.
        // A local copy keeps the full original EXIF (strip-on-upload governs only the cloud copy). The
        // source may be a foreign External URI whose EXIF can't be read; the helper tolerates that.
        val originalExif = ExifHelper.readExifSnapshot(context, source.uri)
        if (originalExif != null) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    ExifHelper.copyExifPreservingOrientation(
                        originalExif, pfd.fileDescriptor, bitmap.width, bitmap.height,
                    )
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    private suspend fun saveCloud(bitmap: Bitmap, source: EditorSource.Cloud, mode: SaveMode, quality: Int, editTimestampMs: Long, originalCaptureMs: Long): Uri? {
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
            // Read the strip settings up front: strip-timestamp must floor the capture time BEFORE it
            // feeds both the LocalMediaItem (Drive captureTime) and the xAttr, matching the backup.
            // Mirror-to-local is unused here (this path only writes the CLOUD copy), so it is ignored.
            val (stripOnUpload, _, stripConfig) = readStripSettings()
            // A cloud source always saves as a Copy (Overwrite is not offered for it), so the edit
            // inherits the ORIGINAL capture time and sorts next to the original. Under strip-timestamp,
            // floor to now so the cloud metadata (captureTime + xAttr capture time) cannot reveal when
            // the shot was taken; UploadPendingUseCase floors the same way. The Overwrite branch below
            // is unreachable here and kept only for symmetry with the device-save path.
            val dateTaken = when {
                mode == SaveMode.Overwrite -> source.photo.captureTime * 1000L
                stripOnUpload && stripConfig.stripTimestamp -> System.currentTimeMillis()
                else -> originalCaptureMs
            }
            val item = LocalMediaItem(
                uri = tempUri.toString(),
                dateTaken = dateTaken,
                displayName = displayName,
                mimeType = "image/jpeg",
                sizeBytes = tempFile.length(),
                bucketName = null,
                width = bitmap.width,
                height = bitmap.height,
                duration = 0L,
            )
            // Re-inject the Cloud original's EXIF into the temp, honouring the upload strip settings, and
            // mirror the surviving GPS/camera into the xAttr so Proton's map + camera UI keep working.
            // MUST run before sha1() below: the EXIF write changes the bytes, and Drive's ContentHash is
            // computed from exactly these bytes; hashing before the write would make the upload mismatch.
            val originalExif = cloudOriginalFilePath?.let { ExifHelper.readExifSnapshot(File(it)) }
            if (originalExif != null) {
                ExifHelper.copyExifPreservingOrientation(originalExif, tempFile, bitmap.width, bitmap.height)
            }
            if (stripOnUpload) {
                // Reuse the exact backup strip so the cloud file matches the xAttr gating below.
                runCatching { ExifHelper.stripFieldsInPlace(context, tempUri.toString(), stripConfig) }
            }
            val xAttr = buildEditXAttr(
                originalExif = originalExif,
                captureTimeMs = dateTaken,
                displayWidth = bitmap.width,
                displayHeight = bitmap.height,
                stripOnUpload = stripOnUpload,
                stripConfig = stripConfig,
            )
            val hash = sha1(tempFile)
            val newLinkId = cloudRepo.uploadFile(userId, item, hash, tempUri.toString(), xAttr)

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
        /** Same instant as the device-side insertLocalCopy so both share the `_edit_<ts>` name and
         *  reconcile's byNameAndDate pairs them as Synced. */
        editTimestampMs: Long,
        /** The original photo's capture time (ms). A Copy inherits it so the edit sorts next to the
         *  original; the SAME value drives the device copy's DATE_TAKEN so both land on one second. */
        originalCaptureMs: Long,
        userId: me.proton.core.domain.entity.UserId,
        /** The device original's content URI, whose EXIF (GPS/camera) is re-injected into the uploaded
         *  copy and mirrored into the xAttr so Proton keeps the map location + camera info. */
        originalSourceUri: String,
    ): CloudUploadResult {
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
            // Read the strip settings up front: strip-timestamp must floor the capture time BEFORE it
            // feeds both the LocalMediaItem (Drive captureTime) and the xAttr, matching the backup.
            // Mirror-to-local is unused here (this path only writes the CLOUD copy), so it is ignored.
            val (stripOnUpload, _, stripConfig) = readStripSettings()
            // A synced source always saves as a Copy (Overwrite is not offered for it), so the edit
            // inherits the ORIGINAL capture time (same value as the device copy) and the two pair up on
            // one second and sort together. Under strip-timestamp, floor to now so the cloud metadata
            // cannot reveal the real shot time; UploadPendingUseCase floors the same way. The Overwrite
            // branch below is unreachable here and kept only for symmetry with the device-save path.
            val dateTaken = when {
                mode == SaveMode.Overwrite -> cloud.captureTime * 1000L
                stripOnUpload && stripConfig.stripTimestamp -> System.currentTimeMillis()
                else -> originalCaptureMs
            }
            val item = LocalMediaItem(
                uri = tempUri.toString(),
                dateTaken = dateTaken,
                displayName = displayName,
                mimeType = "image/jpeg",
                sizeBytes = tempFile.length(),
                bucketName = null,
                width = bitmap.width,
                height = bitmap.height,
                duration = 0L,
            )
            // Re-inject the device original's EXIF into the temp, honouring the upload strip settings, and
            // mirror the surviving GPS/camera into the xAttr so Proton's map + camera UI keep working.
            // MUST run before sha1() below: the EXIF write changes the bytes Drive's ContentHash covers.
            val originalExif = ExifHelper.readExifSnapshot(context, originalSourceUri)
            if (originalExif != null) {
                ExifHelper.copyExifPreservingOrientation(originalExif, tempFile, bitmap.width, bitmap.height)
            }
            if (stripOnUpload) {
                runCatching { ExifHelper.stripFieldsInPlace(context, tempUri.toString(), stripConfig) }
            }
            val xAttr = buildEditXAttr(
                originalExif = originalExif,
                captureTimeMs = dateTaken,
                displayWidth = bitmap.width,
                displayHeight = bitmap.height,
                stripOnUpload = stripOnUpload,
                stripConfig = stripConfig,
            )
            // Bare sha1 of exactly the bytes uploaded (after EXIF re-inject + any strip). This is the
            // value the device sync_state row must carry as localHash: reconcile maps it through
            // cloudContentHash() to the cloud copy's HMAC ContentHash to re-pair the two as Synced.
            // The on-device file keeps full EXIF and may hash differently, so the UPLOAD's sha1 is the
            // one that pairs. Drive's wire ContentHash is derived from this same digest, unchanged.
            val hash = sha1(tempFile)
            val newLinkId = cloudRepo.uploadFile(userId, item, hash, tempUri.toString(), xAttr)
            sourceAlbumLinkId?.let { albumId ->
                runCatching { cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)) }
            }
            if (mode == SaveMode.Overwrite) {
                runCatching { cloudRepo.deleteFiles(userId, listOf(cloud.linkId)) }
            }
            return CloudUploadResult(newLinkId, hash)
        } finally {
            tempFile.delete()
        }
    }

    /** New Drive linkId plus the bare sha1 of the uploaded plaintext, so the device sync_state row
     *  can store the SAME hash the cloud ContentHash was derived from and reconcile re-pairs them. */
    private data class CloudUploadResult(val linkId: String, val contentSha1: String)

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
    private fun stamp(displayName: String, atMs: Long = System.currentTimeMillis(), ext: String = "jpg"): String {
        val dotIdx = displayName.lastIndexOf('.')
        val base = if (dotIdx > 0) displayName.substring(0, dotIdx) else displayName
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
            .format(java.util.Date(atMs))
        return "${base}_edit_$ts.$ext"
    }

    /**
     * Reads the upload metadata-strip settings the backup pipeline consults ([UploadPendingUseCase]),
     * so the edited CLOUD copy honours the same choices: strip-on-upload plus the per-field flags. Also
     * reads mirror-to-local, which (when both it and strip-on-upload are on) tells the caller to strip the
     * ON-DEVICE copy too, matching the backup. Returns (stripOnUpload, mirrorStripToLocal, config); an
     * all-false config with both flags off when the store can't be read.
     */
    private suspend fun readStripSettings(): Triple<Boolean, Boolean, MetadataStripConfig> {
        val prefs = runCatching { context.settingsDataStore.data.first() }.getOrNull()
            ?: return Triple(false, false, MetadataStripConfig())
        val stripOnUpload = prefs[SettingsKeys.STRIP_ON_UPLOAD] ?: false
        val mirrorStripToLocal = prefs[SettingsKeys.MIRROR_STRIP_TO_LOCAL] ?: false
        // Authorship rides on the software setting exactly as in [UploadPendingUseCase]: the upload
        // strip is driven by one preference, so both tag groups move together here too.
        val stripSoftwareInfo = prefs[SettingsKeys.STRIP_SOFTWARE_INFO] ?: false
        val config = MetadataStripConfig(
            stripGps = prefs[SettingsKeys.STRIP_GPS] ?: false,
            stripCameraInfo = prefs[SettingsKeys.STRIP_CAMERA_INFO] ?: false,
            stripTimestamp = prefs[SettingsKeys.STRIP_TIMESTAMP] ?: false,
            stripSoftwareInfo = stripSoftwareInfo,
            stripAuthorship = stripSoftwareInfo,
        )
        return Triple(stripOnUpload, mirrorStripToLocal, config)
    }

    /**
     * Mirrors the upload strip onto the just-saved ON-DEVICE copy at [savedUri], matching the backup
     * ([UploadPendingUseCase]): only when strip-on-upload AND mirror-to-local are both enabled and the
     * config removes at least one field. The device copy is written with full original EXIF first, so
     * this runs afterwards to remove exactly the configured fields (GPS/timestamp/camera/software).
     * Best-effort: the strip is wrapped so a foreign or unsupported URI never fails the save, and the
     * default (mirror-to-local off) leaves the device copy's full EXIF untouched.
     */
    private suspend fun mirrorStripLocalCopyIfEnabled(savedUri: Uri) {
        val (stripOnUpload, mirrorStripToLocal, stripConfig) = readStripSettings()
        if (stripOnUpload && mirrorStripToLocal && !stripConfig.isNoOp) {
            runCatching { ExifHelper.stripFieldsInPlace(context, savedUri.toString(), stripConfig) }
        }
    }

    /**
     * Builds the photo xAttr (Location + Camera + display dimensions) for an edited cloud copy from the
     * ORIGINAL photo's [originalExif], mirroring [UploadPendingUseCase.buildXAttrMetadata]'s image branch
     * so Proton's map + camera UI keep working. The edited bitmap is always upright (rotation baked into
     * pixels), so orientation is NORMAL and the bitmap's own [displayWidth]/[displayHeight] are reported
     * as-is (a crop already changed them, no width/height swap). GPS and camera are gated by the same
     * strip settings the backup uses, so the cloud copy never carries data the file strip removed.
     */
    private fun buildEditXAttr(
        originalExif: ExifInterface?,
        captureTimeMs: Long,
        displayWidth: Int,
        displayHeight: Int,
        stripOnUpload: Boolean,
        stripConfig: MetadataStripConfig,
    ): UploadXAttrMetadata {
        val stripGps = stripOnUpload && stripConfig.stripGps
        val stripCamera = stripOnUpload && stripConfig.stripCameraInfo
        val latLong = originalExif?.latLong
        val model = originalExif?.getAttribute(ExifInterface.TAG_MODEL)
        // SubjectArea → [Top,Left,Bottom,Right], matching UploadPendingUseCase.readSubjectCoordinates
        // (Rectangle.fromCenter). Gated with the camera info, so a strip that drops the camera block
        // drops this too. runCatching keeps a malformed tag from ever failing the save.
        val subjectCoords: IntArray? = if (!stripCamera) runCatching {
            val raw = originalExif?.getAttribute(ExifInterface.TAG_SUBJECT_AREA)
                ?.takeUnless { it.isEmpty() } ?: return@runCatching null
            val a = raw.split(",").map { it.trim().toInt() }
            val (cx, cy, w, h) = when (a.size) {
                3 -> listOf(a[0], a[1], a[2], a[2])
                4 -> listOf(a[0], a[1], a[2], a[3])
                else -> return@runCatching null
            }
            intArrayOf(cy - h / 2, cx - w / 2, cy + h / 2, cx + w / 2)
        }.getOrNull() else null
        // ISO_INSTANT (e.g. 2023-01-15T10:30:00Z), matching Drive Android's DateTimeFormatter.
        val captureTimeIso = captureTimeMs.takeIf { it > 0L }?.let { ms ->
            java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(ms))
        }
        return UploadXAttrMetadata(
            latitude = if (!stripGps) latLong?.get(0) else null,
            longitude = if (!stripGps) latLong?.get(1) else null,
            // The edit is baked upright, so the cloud copy is a NORMAL-orientation JPEG.
            cameraOrientation = ExifInterface.ORIENTATION_NORMAL,
            cameraCaptureTimeIso = captureTimeIso,
            cameraDevice = if (!stripCamera) model else null,
            subjectCoordinates = subjectCoords,
            displayWidth = displayWidth.takeIf { it > 0 },
            displayHeight = displayHeight.takeIf { it > 0 },
        )
    }

    fun consumeSaveResult() {
        _state.update { it.copy(saveResult = null) }
    }

    /**
     * Frees the full-res display bitmaps once the editor screen has left composition, instead of
     * waiting for GC. Safe here (unlike mid-edit): nothing draws them anymore, so a recycle can't
     * race Compose's one-frame-late draw. viewModelScope is cancelled by now, so any in-VM save is
     * gone. The originalBitmap instance is the exception: a no-op Save (identity adjustments, no crop
     * or redact) hands that SAME instance to an appScope upload that OUTLIVES the VM, so its bytes may
     * still be compressing. That instance is only dropped, never recycled; the previews and the small
     * source are VM-private and are recycled when they are distinct from originalBitmap.
     */
    override fun onCleared() {
        super.onCleared()
        val s = _state.value
        val original = s.originalBitmap
        fun releaseIfPrivate(bmp: Bitmap?) {
            if (bmp != null && bmp !== original && !bmp.isRecycled) bmp.recycle()
        }
        releaseIfPrivate(s.previewBitmap)
        releaseIfPrivate(s.adjustedBitmapNoCrop)
        releaseIfPrivate(previewSourceSmall)
        previewSourceSmall = null
        _state.update {
            it.copy(originalBitmap = null, previewBitmap = null, adjustedBitmapNoCrop = null)
        }
    }
}
