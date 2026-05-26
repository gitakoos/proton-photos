package me.proton.photos.presentation.editor

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
import me.proton.photos.R
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.domain.entity.LocalMediaItem
import me.proton.photos.domain.repository.DrivePhotoRepository
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
)

sealed class SaveResult {
    data class Success(val uri: Uri?) : SaveResult()
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

    fun loadLocal(uri: String, displayName: String, mimeType: String) {
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

    fun updateBrightness(v: Int) = updateAdjustments { it.copy(brightness = v.coerceIn(-100, 100)) }
    fun updateContrast(v: Int) = updateAdjustments { it.copy(contrast = v.coerceIn(-100, 100)) }
    fun updateSaturation(v: Int) = updateAdjustments { it.copy(saturation = v.coerceIn(-100, 100)) }
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

    fun resetAll() = updateAdjustments { EditorAdjustments() }

    private fun updateAdjustments(transform: (EditorAdjustments) -> EditorAdjustments) {
        val orig = _state.value.originalBitmap ?: return
        val newAdj = transform(_state.value.adjustments)
        viewModelScope.launch(Dispatchers.Default) {
            val oldPreview = _state.value.previewBitmap
            val newPreview = applyAdjustments(orig, newAdj)
            _state.update { it.copy(adjustments = newAdj, previewBitmap = newPreview) }
            // Recycle the now-orphaned previous preview unless it's the source itself
            // (no-op path when adjustments are at default). A 12 MP photo is ~48 MB of
            // native heap per intermediate; without this every slider tick leaked one.
            if (oldPreview != null && oldPreview !== orig && oldPreview !== newPreview) {
                oldPreview.recycle()
            }
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
        if (rotated !== cropped && cropped !== source) cropped.recycle()

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
        if (colored !== rotated && rotated !== source) rotated.recycle()

        // 4. redaction strokes — drawn LAST so they cover the final visible content
        val final = if (adj.redactStrokes.isEmpty()) colored
            else applyRedactStrokes(colored, adj.redactStrokes).also {
                if (it !== colored && colored !== source) colored.recycle()
            }
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
            Bitmap.createScaledBitmap(small, w, h, false).also { small.recycle() }
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
                    maskBmp.recycle()
                }
                continue
            }
            when (stroke.mode) {
                RedactMode.Black -> canvas.drawPath(path, pathPaint)
                RedactMode.Pixelate -> {
                    val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
                    Canvas(maskBmp).drawPath(path, pathPaint)
                    drawPixelatedThroughMask(canvas, pixelated, maskBmp)
                    maskBmp.recycle()
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
    fun save(mode: SaveMode, quality: Int = 92) {
        val s = _state.value
        val source = s.source ?: return
        val bitmap = s.previewBitmap ?: s.originalBitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isSaving = true, saveResult = null) }
            val result = runCatching {
                when (source) {
                    is EditorSource.Local -> saveLocal(bitmap, source, mode, quality)
                    is EditorSource.Cloud -> saveCloud(bitmap, source, mode, quality)
                }
            }
            _state.update {
                it.copy(
                    isSaving = false,
                    saveResult = result.fold(
                        onSuccess = { uri -> SaveResult.Success(uri) },
                        onFailure = { e -> SaveResult.Failed(e.message ?: "Save failed") },
                    ),
                )
            }
        }
    }

    private fun saveLocal(bitmap: Bitmap, source: EditorSource.Local, mode: SaveMode, quality: Int): Uri? {
        return when (mode) {
            SaveMode.Overwrite -> overwriteLocal(bitmap, source, quality)
            SaveMode.Copy      -> insertLocalCopy(bitmap, source, quality)
        }
    }

    /** Writes the edited bytes back to the source MediaStore URI in place. */
    private fun overwriteLocal(bitmap: Bitmap, source: EditorSource.Local, quality: Int): Uri? {
        val srcUri = Uri.parse(source.uri)
        // On Q+ use IS_PENDING so partial writes aren't visible to other readers mid-write.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(srcUri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
        }
        try {
            context.contentResolver.openOutputStream(srcUri, "wt")?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            } ?: error("openOutputStream returned null for $srcUri")
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(srcUri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                        // Drop the DATE_MODIFIED so gallery sorters refresh.
                        put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    }, null, null)
            }
        }
        return srcUri
    }

    private fun insertLocalCopy(bitmap: Bitmap, source: EditorSource.Local, quality: Int): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val newName = stamp(source.displayName)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, newName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, me.proton.photos.util.ProtonPhotosStorage.DEFAULT_PICTURES)
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

    private suspend fun saveCloud(bitmap: Bitmap, source: EditorSource.Cloud, mode: SaveMode, quality: Int): Uri? {
        val cacheDir = File(context.cacheDir, "editor").also { it.mkdirs() }
        val tempFile = File(cacheDir, "edit_${System.currentTimeMillis()}.jpg")
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        try {
            val userId = accountManager.getPrimaryUserId().first()
                ?: error("Not signed in")
            val tempUri = Uri.fromFile(tempFile)
            val now = System.currentTimeMillis()
            // Overwrite keeps the original filename; Copy stamps a new one so both
            // versions are distinguishable in the cloud listing.
            val displayName = when (mode) {
                SaveMode.Overwrite -> source.photo.displayName
                SaveMode.Copy      -> stamp(source.photo.displayName)
            }
            val item = LocalMediaItem(
                uri = tempUri.toString(),
                dateTaken = if (mode == SaveMode.Overwrite) source.photo.captureTime * 1000L else now,
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
                // Trash the original — the user wanted the edit to replace it.
                runCatching { cloudRepo.deleteFiles(userId, listOf(source.photo.linkId)) }
            }
            return Uri.parse("proton://drive/$newLinkId")
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

    private fun stamp(displayName: String): String {
        val dotIdx = displayName.lastIndexOf('.')
        val base = if (dotIdx > 0) displayName.substring(0, dotIdx) else displayName
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
            .format(java.util.Date())
        return "${base}_edit_$ts.jpg"
    }

    fun consumeSaveResult() {
        _state.update { it.copy(saveResult = null) }
    }
}
