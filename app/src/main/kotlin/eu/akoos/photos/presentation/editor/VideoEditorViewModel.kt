package eu.akoos.photos.presentation.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.ProtonPhotosStorage
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * UI state for the video editor.
 *
 * The two supported operations — trim and rotate — both avoid per-frame decode/encode:
 *  - Trim happens via MediaExtractor + MediaMuxer stream-copy, sample-by-sample, so
 *    the codec bitstream is preserved exactly.
 *  - Rotate is metadata-only via [MediaMuxer.setOrientationHint] — the player applies
 *    the rotation at display time, no pixel work involved.
 *
 * [rotationDegrees] is ADDITIVE to whatever rotation tag the source already carries:
 * a portrait phone clip with a baked-in 90° rotation that the user rotates a further
 * 90° CW ends up at 180° in the output. We read the source's rotation via
 * MediaMetadataRetriever and add ours on save.
 */
data class VideoEditorUiState(
    val sourceUri: String? = null,
    val displayName: String = "",
    val mimeType: String = "video/mp4",
    val durationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val rotationDegrees: Int = 0, // 0, 90, 180, 270 — additive to source rotation metadata
    /**
     * Crop rectangle in SOURCE-VIDEO pixel coordinates. Null = full frame.
     *
     * Setting this triggers the re-encode path on save — stream-copy can't satisfy a
     * crop because the bitstream encodes the full source frame.
     */
    val cropRect: Rect? = null,
    /** Source video dimensions in pixels (post-rotation), populated by loadLocal. */
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /**
     * Local audio file the user picked to replace the original audio track. Null =
     * keep the source's audio. Setting this triggers the audio-swap path on save.
     */
    val audioOverlayUri: String? = null,
    val audioOverlayDisplayName: String? = null,
    val audioOverlayDurationMs: Long = 0L,
    /** Within the overlay music, the slice to play during the saved video. Ignored
     *  when [audioOverlayUri] is null. */
    val audioTrimStartMs: Long = 0L,
    val audioTrimEndMs: Long = 0L,
    /**
     * When true the source video's audio track is dropped from the output. Independent
     * of [audioOverlayUri] — the UI shows the original track and the overlay as two
     * separate rows so the user can mute the original, add music, or do both.
     *  - mute = true, overlay = null  → silent video (audio track removed)
     *  - mute = true, overlay != null → overlay replaces original (current behaviour)
     *  - mute = false, overlay != null → overlay still replaces (no mixer in pipeline yet)
     *  - mute = false, overlay = null → source audio preserved
     */
    val muteOriginalAudio: Boolean = false,
    val isSaving: Boolean = false,
    val isLoading: Boolean = true,
    val saveResult: VideoSaveResult? = null,
    val errorMessage: String? = null,
    /** 0..1 progress during a re-encode save; null when the save is a stream-copy
     *  (which is fast enough that a spinner suffices). */
    val saveProgress: Float? = null,
    /**
     * On Android 11+ a foreign MediaStore URI cannot be opened for write without an
     * explicit user-consent intent. When the first save attempt throws SecurityException
     * we build one via [MediaStore.createWriteRequest] and surface its PendingIntent here
     * so the screen can launch it; the user's choice flows back through
     * [VideoEditorViewModel.onWritePermissionGranted] / [onWritePermissionDenied].
     * Mirrors the PhotoEditor pattern so own-camera videos can actually be overwritten
     * instead of silently falling through to a "Save as Copy".
     */
    val pendingWriteIntent: android.app.PendingIntent? = null,
)

sealed class VideoSaveResult {
    data class Success(val uri: Uri?) : VideoSaveResult()
    data class SuccessAsCopy(val uri: Uri?) : VideoSaveResult()
    data class Failed(val message: String) : VideoSaveResult()
}

enum class VideoSaveMode { Overwrite, Copy }

@HiltViewModel
class VideoEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val cloudRepo: DrivePhotoRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(VideoEditorUiState())
    val state: StateFlow<VideoEditorUiState> = _state.asStateFlow()

    /** Source video's baked-in orientation tag, captured at load time and reused at save
     *  time so we can write (sourceRotation + userRotation) % 360 into the muxer. */
    private var sourceRotationDegrees: Int = 0

    /** Non-null when the editor was launched on a cloud-only video; we download it to
     *  cache for editing and re-upload on save. Mirrors PhotoEditor's EditorSource.Cloud
     *  but kept simple because the existing local save paths already operate on
     *  file:// URIs — pointing them at the cache file lets the edit pipeline stay
     *  unchanged. */
    private var sourceCloudPhoto: CloudPhoto? = null
    /** Optional album linkId the cloud video lives in. Used to re-attach the re-uploaded
     *  edit to the same album so it doesn't disappear from the album view. */
    private var sourceCloudAlbumLinkId: String? = null

    fun loadLocal(uri: String, displayName: String, mimeType: String) {
        _state.update {
            it.copy(
                sourceUri = uri,
                displayName = displayName,
                mimeType = mimeType,
                isLoading = true,
                errorMessage = null,
                saveResult = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            if (parsed == null) {
                _state.update { it.copy(isLoading = false, errorMessage = "Invalid video URI") }
                return@launch
            }
            // MediaMetadataRetriever needs setDataSource — accepts both file paths and
            // content:// URIs. We pull duration (required) and rotation (so the additive
            // rotate keeps the result upright).
            val retriever = MediaMetadataRetriever()
            val result = runCatching {
                retriever.setDataSource(context, parsed)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                VideoMeta(dur, rot, w, h)
            }
            runCatching { retriever.release() }
            val meta = result.getOrElse {
                _state.update {
                    it.copy(isLoading = false, errorMessage = "Couldn't read video metadata")
                }
                return@launch
            }
            sourceRotationDegrees = ((meta.rotation % 360) + 360) % 360
            _state.update {
                it.copy(
                    durationMs = meta.duration,
                    trimStartMs = 0L,
                    trimEndMs = meta.duration,
                    rotationDegrees = 0,
                    cropRect = null,
                    sourceWidth = meta.width,
                    sourceHeight = meta.height,
                    audioOverlayUri = null,
                    audioOverlayDisplayName = null,
                    audioOverlayDurationMs = 0L,
                    audioTrimStartMs = 0L,
                    audioTrimEndMs = 0L,
                    isLoading = false,
                )
            }
        }
    }

    private data class VideoMeta(val duration: Long, val rotation: Int, val width: Int, val height: Int)

    /**
     * Cloud entry-point. Downloads the encrypted blocks to the on-disk cache, decrypts
     * into a regular .mp4 file, then routes the rest of the pipeline through the existing
     * local-edit code by feeding it the cached file:// URI. The cloud photo is held in
     * [sourceCloudPhoto] so [save] knows to re-upload on success instead of writing a
     * MediaStore entry.
     *
     * [albumLinkId] is optional — when the cloud video lives inside an album, passing it
     * here lets us re-attach the re-uploaded edit so the album view doesn't lose the clip.
     */
    fun loadCloud(photo: CloudPhoto, albumLinkId: String?) {
        sourceCloudPhoto = photo
        sourceCloudAlbumLinkId = albumLinkId
        _state.update {
            it.copy(
                sourceUri = null,
                displayName = photo.displayName,
                mimeType = photo.mimeType,
                isLoading = true,
                errorMessage = null,
                saveResult = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                _state.update { it.copy(isLoading = false, errorMessage = "Not signed in") }
                return@launch
            }
            val downloaded = runCatching { cloudRepo.downloadFullResPhoto(userId, photo) }
            val file = downloaded.getOrNull()
            if (file == null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = downloaded.exceptionOrNull()?.message
                            ?: "Cloud video download failed",
                    )
                }
                return@launch
            }
            val parsed = Uri.fromFile(file)
            val retriever = MediaMetadataRetriever()
            val meta = runCatching {
                retriever.setDataSource(context, parsed)
                VideoMeta(
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L,
                    rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0,
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0,
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0,
                )
            }.getOrElse {
                _state.update { it.copy(isLoading = false, errorMessage = "Couldn't read video metadata") }
                return@launch
            }
            runCatching { retriever.release() }
            sourceRotationDegrees = ((meta.rotation % 360) + 360) % 360
            _state.update {
                it.copy(
                    sourceUri = parsed.toString(),
                    durationMs = meta.duration,
                    trimStartMs = 0L,
                    trimEndMs = meta.duration,
                    rotationDegrees = 0,
                    cropRect = null,
                    sourceWidth = meta.width,
                    sourceHeight = meta.height,
                    audioOverlayUri = null,
                    audioOverlayDisplayName = null,
                    audioOverlayDurationMs = 0L,
                    audioTrimStartMs = 0L,
                    audioTrimEndMs = 0L,
                    muteOriginalAudio = false,
                    isLoading = false,
                )
            }
        }
    }

    fun setTrimStart(ms: Long) {
        _state.update { s ->
            val clamped = ms.coerceIn(0L, s.durationMs)
            // Keep at least 100 ms of clip — picking a zero-length window crashes the muxer
            // because no samples get written between writeSampleData and stop().
            val newStart = if (clamped >= s.trimEndMs) (s.trimEndMs - 100L).coerceAtLeast(0L) else clamped
            s.copy(trimStartMs = newStart)
        }
    }

    fun setTrimEnd(ms: Long) {
        _state.update { s ->
            val clamped = ms.coerceIn(0L, s.durationMs)
            val newEnd = if (clamped <= s.trimStartMs) (s.trimStartMs + 100L).coerceAtMost(s.durationMs) else clamped
            s.copy(trimEndMs = newEnd)
        }
    }

    fun setTrimRange(start: Long, end: Long) {
        _state.update { s ->
            // Caller maps the dragged thumb's value into the matching field — we honour
            // which side moved by clamping each independently. If the user pushes start
            // past end (or end past start), we pin against the OTHER side with a 100 ms
            // gap so the muxer always has at least one keyframe to write. No swap — the
            // previous swap version made handles "jump" past each other when crossed.
            val minGap = 100L
            val clampedStart = start.coerceIn(0L, (s.trimEndMs - minGap).coerceAtLeast(0L))
            val clampedEnd = end.coerceIn((s.trimStartMs + minGap).coerceAtMost(s.durationMs), s.durationMs)
            // When the caller passes the unchanged sibling, keep our local clamp:
            val newStart = if (start == s.trimStartMs) s.trimStartMs else clampedStart
            val newEnd = if (end == s.trimEndMs) s.trimEndMs else clampedEnd
            s.copy(trimStartMs = newStart, trimEndMs = newEnd)
        }
    }

    fun rotate90Cw() {
        _state.update { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
    }

    /**
     * Sets the crop rectangle in source-video pixel coordinates. The Compose overlay
     * supplies coordinates already mapped into the source's coordinate space.
     *
     * Passing null clears the crop (re-enables the cheap stream-copy save path when no
     * other re-encode-only feature is active).
     */
    fun setCropRect(rect: Rect?) {
        _state.update { s ->
            if (rect == null) return@update s.copy(cropRect = null)
            val srcW = s.sourceWidth.coerceAtLeast(1)
            val srcH = s.sourceHeight.coerceAtLeast(1)
            val safe = Rect(
                rect.left.coerceIn(0, srcW - 1),
                rect.top.coerceIn(0, srcH - 1),
                rect.right.coerceIn(1, srcW),
                rect.bottom.coerceIn(1, srcH),
            )
            // Floor to even values so the encoder doesn't reject odd-dimensional input.
            val w = (safe.width() and 1.inv()).coerceAtLeast(16)
            val h = (safe.height() and 1.inv()).coerceAtLeast(16)
            val maxLeft = (srcW - w).coerceAtLeast(0)
            val maxTop = (srcH - h).coerceAtLeast(0)
            val left = safe.left.coerceIn(0, maxLeft)
            val top = safe.top.coerceIn(0, maxTop)
            s.copy(cropRect = Rect(left, top, left + w, top + h))
        }
    }

    /** Picks a music file. Extracts the audio duration so the music-trim slider can
     *  drag over a meaningful range. Resets the music-trim window to the full clip. */
    fun setAudioOverlay(uri: String, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return@launch
            val retriever = MediaMetadataRetriever()
            val duration = runCatching {
                retriever.setDataSource(context, parsed)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }.getOrDefault(0L)
            runCatching { retriever.release() }
            _state.update {
                it.copy(
                    audioOverlayUri = uri,
                    audioOverlayDisplayName = displayName,
                    audioOverlayDurationMs = duration,
                    audioTrimStartMs = 0L,
                    audioTrimEndMs = duration,
                )
            }
        }
    }

    fun clearAudioOverlay() {
        _state.update {
            it.copy(
                audioOverlayUri = null,
                audioOverlayDisplayName = null,
                audioOverlayDurationMs = 0L,
                audioTrimStartMs = 0L,
                audioTrimEndMs = 0L,
            )
        }
    }

    /** Toggles whether the source video's audio survives the save. UI calls this from
     *  the "Original audio" row's mute switch. Strip is honoured by both save paths
     *  (stream-copy drops the audio MediaFormat; re-encode skips the audio extractor). */
    fun setMuteOriginalAudio(muted: Boolean) {
        _state.update { it.copy(muteOriginalAudio = muted) }
    }

    fun setAudioTrimStart(ms: Long) {
        _state.update { s ->
            if (s.audioOverlayUri == null) return@update s
            val clamped = ms.coerceIn(0L, s.audioOverlayDurationMs)
            // Keep at least 100 ms of music — picking a zero-length window writes no
            // samples, and the muxer is fine with that but the file ends up silent for
            // unexpected reasons (which is worse UX than just clamping).
            val newStart = if (clamped >= s.audioTrimEndMs)
                (s.audioTrimEndMs - 100L).coerceAtLeast(0L) else clamped
            s.copy(audioTrimStartMs = newStart)
        }
    }

    fun setAudioTrimEnd(ms: Long) {
        _state.update { s ->
            if (s.audioOverlayUri == null) return@update s
            val clamped = ms.coerceIn(0L, s.audioOverlayDurationMs)
            val newEnd = if (clamped <= s.audioTrimStartMs)
                (s.audioTrimStartMs + 100L).coerceAtMost(s.audioOverlayDurationMs) else clamped
            s.copy(audioTrimEndMs = newEnd)
        }
    }

    fun setAudioTrimRange(start: Long, end: Long) {
        _state.update { s ->
            if (s.audioOverlayUri == null) return@update s
            val clampedStart = start.coerceIn(0L, s.audioOverlayDurationMs)
            val clampedEnd = end.coerceIn(0L, s.audioOverlayDurationMs)
            val (a, b) = if (clampedStart < clampedEnd) clampedStart to clampedEnd
                else clampedEnd to (clampedEnd + 100L).coerceAtMost(s.audioOverlayDurationMs)
            s.copy(audioTrimStartMs = a, audioTrimEndMs = b)
        }
    }

    fun consumeSaveResult() {
        _state.update { it.copy(saveResult = null, saveProgress = null) }
    }

    private var pendingWriteMode: VideoSaveMode? = null

    fun save(mode: VideoSaveMode, allowWriteRequestRecovery: Boolean = true) {
        val s = _state.value
        val sourceUri = s.sourceUri ?: return
        if (s.isSaving) return
        viewModelScope.launch(Dispatchers.IO) {
            // Decide between fast stream-copy and full re-encode. A re-encode is required
            // whenever the user has set a crop OR swapped the audio track — both modify
            // payload that the stream-copy path treats as immutable. Pixel rotation falls
            // through into the muxer's orientation hint, so it does NOT force a re-encode
            // on its own.
            val needsReencode = s.cropRect != null || s.audioOverlayUri != null
            _state.update {
                it.copy(
                    isSaving = true,
                    saveResult = null,
                    saveProgress = if (needsReencode) 0f else null,
                )
            }
            val finalRotation = ((sourceRotationDegrees + s.rotationDegrees) % 360 + 360) % 360
            val cloudPhoto = sourceCloudPhoto
            val saveResult: VideoSaveResult = try {
                when {
                    // Cloud video edit: produce the muxed .mp4 in cache, then upload as a
                    // new linkId. Mirrors PhotoEditor.saveCloud — Overwrite trashes the
                    // original after the new linkId is committed, Copy keeps both.
                    cloudPhoto != null -> saveCloud(s, mode, finalRotation, cloudPhoto, needsReencode)
                    needsReencode -> saveReencoded(s, mode, finalRotation)
                    else -> saveStreamCopy(s, mode, finalRotation, allowWriteRequestRecovery)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                VideoSaveResult.Failed(eu.akoos.photos.util.sanitizeErrorMessage(e.message ?: "Save failed"))
            }
            // pendingWriteIntent suspended the save until the user reacts to the consent
            // dialog — leave isSaving on the state from that branch alone.
            if (_state.value.pendingWriteIntent == null) {
                _state.update { it.copy(isSaving = false, saveResult = saveResult, saveProgress = null) }
            }
        }
    }

    fun onWritePermissionGranted() {
        val mode = pendingWriteMode ?: return
        pendingWriteMode = null
        _state.update { it.copy(pendingWriteIntent = null) }
        save(mode, allowWriteRequestRecovery = false)
    }

    fun onWritePermissionDenied() {
        pendingWriteMode = null
        _state.update {
            it.copy(
                pendingWriteIntent = null,
                isSaving = false,
                saveResult = VideoSaveResult.Failed("Save cancelled"),
                saveProgress = null,
            )
        }
    }

    /**
     * Build a MediaStore consent intent for [sourceUri] and surface it through state. Returns
     * true if the intent was attached (caller should leave state alone and let the screen
     * launch the dialog); false when the URI is stuck in IS_PENDING/IS_TRASHED or
     * createWriteRequest refused — the caller then falls back to "save as copy".
     */
    private fun tryRequestWritePermission(sourceUri: Uri, mode: VideoSaveMode): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        // Items already pending/trashed will get a flat refusal from the consent dialog —
        // skip straight to the copy fallback to spare the user a pointless confirm-cancel.
        val stuck = runCatching {
            context.contentResolver.query(
                sourceUri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED),
                null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) false
                else cursor.getInt(0) != 0 || cursor.getInt(1) != 0
            } ?: false
        }.getOrDefault(false)
        if (stuck) return false
        val request = runCatching {
            MediaStore.createWriteRequest(context.contentResolver, listOf(sourceUri))
        }.getOrNull() ?: return false
        pendingWriteMode = mode
        _state.update { it.copy(pendingWriteIntent = request) }
        return true
    }

    /**
     * Cloud video save: mux the edit into a temp .mp4 (re-encode or stream-copy), upload
     * as a new Drive linkId, and on [VideoSaveMode.Overwrite] trash the original. The
     * album re-attach is best-effort.
     */
    private suspend fun saveCloud(
        s: VideoEditorUiState,
        mode: VideoSaveMode,
        finalRotation: Int,
        cloudPhoto: CloudPhoto,
        needsReencode: Boolean,
    ): VideoSaveResult = withContext(Dispatchers.IO) {
        val sourceUriStr = s.sourceUri ?: error("No cached video URI")
        val sourceUri = Uri.parse(sourceUriStr)
        val tempFile = createTempMuxFile()
        try {
            if (needsReencode) {
                val crop = s.cropRect
                    ?: Rect(0, 0, s.sourceWidth.coerceAtLeast(2), s.sourceHeight.coerceAtLeast(2))
                VideoReencoder(context).transcode(
                    sourceUri = sourceUri,
                    outputFile = tempFile,
                    trimStartUs = s.trimStartMs * 1000L,
                    trimEndUs = s.trimEndMs * 1000L,
                    cropLeft = crop.left,
                    cropTop = crop.top,
                    cropWidth = crop.width(),
                    cropHeight = crop.height(),
                    rotationDegrees = finalRotation,
                    audioOverlayUri = s.audioOverlayUri?.let { Uri.parse(it) },
                    audioTrimStartUs = s.audioTrimStartMs * 1000L,
                    audioTrimEndUs = s.audioTrimEndMs * 1000L,
                    muteOriginalAudio = s.muteOriginalAudio,
                    onProgress = { p -> _state.update { it.copy(saveProgress = p) } },
                )
            } else {
                muxTrimmed(
                    sourceUri = sourceUri,
                    outputFile = tempFile,
                    trimStartMs = s.trimStartMs,
                    trimEndMs = s.trimEndMs,
                    orientationDegrees = finalRotation,
                    stripAudio = s.muteOriginalAudio,
                )
            }
            val userId = accountManager.getPrimaryUserId().first()
                ?: error("Not signed in")
            val displayName = when (mode) {
                VideoSaveMode.Overwrite -> cloudPhoto.displayName
                VideoSaveMode.Copy -> stamp(cloudPhoto.displayName)
            }
            val now = System.currentTimeMillis()
            val outMime = if (s.mimeType.startsWith("video/")) s.mimeType else "video/mp4"
            val uploadUri = Uri.fromFile(tempFile).toString()
            val item = LocalMediaItem(
                uri = uploadUri,
                dateTaken = if (mode == VideoSaveMode.Overwrite) cloudPhoto.captureTime * 1000L else now,
                displayName = displayName,
                mimeType = outMime,
                sizeBytes = tempFile.length(),
                bucketName = null,
                width = s.sourceWidth,
                height = s.sourceHeight,
                duration = (s.trimEndMs - s.trimStartMs).coerceAtLeast(0L),
            )
            val hash = sha256(tempFile)
            val newLinkId = cloudRepo.uploadFile(userId, item, hash, uploadUri)

            sourceCloudAlbumLinkId?.let { albumId ->
                runCatching { cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)) }
            }
            if (mode == VideoSaveMode.Overwrite) {
                runCatching { cloudRepo.deleteFiles(userId, listOf(cloudPhoto.linkId)) }
            }
            VideoSaveResult.Success(Uri.parse("proton://drive/$newLinkId"))
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

    /** Original stream-copy save path — fast, no decode/encode. Used when only trim
     *  and metadata-rotation are active. */
    private suspend fun saveStreamCopy(
        s: VideoEditorUiState,
        mode: VideoSaveMode,
        finalRotation: Int,
        allowWriteRequestRecovery: Boolean = true,
    ): VideoSaveResult {
        val sourceUriStr = s.sourceUri ?: error("No source URI")
        // Stream-copy supports the "remove audio track" toggle by simply not registering
        // the audio MediaFormat on the muxer. (Overlay audio always forces the re-encode
        // path, so we only need to honour mute here.)
        val stripAudio = s.muteOriginalAudio
        return when (mode) {
            VideoSaveMode.Overwrite -> {
                try {
                    val uri = overwriteLocal(
                        sourceUri = Uri.parse(sourceUriStr),
                        trimStartMs = s.trimStartMs,
                        trimEndMs = s.trimEndMs,
                        orientationDegrees = finalRotation,
                        stripAudio = stripAudio,
                    )
                    VideoSaveResult.Success(uri)
                } catch (se: SecurityException) {
                    // First try the user-consent prompt on R+. If accepted we'll re-run save
                    // with allowWriteRequestRecovery=false; either branch falls through here
                    // to the Copy fallback if the prompt isn't available or the URI is stuck.
                    val srcUri = Uri.parse(sourceUriStr)
                    if (allowWriteRequestRecovery &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        tryRequestWritePermission(srcUri, mode)) {
                        // Pending intent surfaced — leave state untouched and return a
                        // sentinel; the outer save() leaves isSaving on so the screen sees
                        // the spinner until the user reacts to the system dialog.
                        return VideoSaveResult.Failed("__pending_write_permission__")
                    }
                    val copyUri = insertLocalCopy(
                        sourceUri = srcUri,
                        displayName = s.displayName,
                        mimeType = s.mimeType,
                        trimStartMs = s.trimStartMs,
                        trimEndMs = s.trimEndMs,
                        orientationDegrees = finalRotation,
                        useOriginalName = true,
                        stripAudio = stripAudio,
                    )
                    VideoSaveResult.SuccessAsCopy(copyUri)
                }
            }
            VideoSaveMode.Copy -> {
                val uri = insertLocalCopy(
                    sourceUri = Uri.parse(sourceUriStr),
                    displayName = s.displayName,
                    mimeType = s.mimeType,
                    trimStartMs = s.trimStartMs,
                    trimEndMs = s.trimEndMs,
                    orientationDegrees = finalRotation,
                    useOriginalName = false,
                    stripAudio = stripAudio,
                )
                VideoSaveResult.Success(uri)
            }
        }
    }

    /**
     * Re-encode save path. Runs the full decode→GL→encode pipeline so a crop or audio
     * swap can take effect. The encoder's input frame is the cropped region, so the
     * output's width/height is the cropped dimensions and no further muxer-orientation
     * hint is needed — pixel rotation is burnt in by the GL matrix.
     */
    private suspend fun saveReencoded(
        s: VideoEditorUiState,
        mode: VideoSaveMode,
        finalRotation: Int,
    ): VideoSaveResult = withContext(Dispatchers.IO) {
        val sourceUriStr = s.sourceUri ?: error("No source URI")
        val sourceUri = Uri.parse(sourceUriStr)
        val tempFile = createTempMuxFile()
        try {
            val crop = s.cropRect ?: Rect(0, 0, s.sourceWidth.coerceAtLeast(2), s.sourceHeight.coerceAtLeast(2))
            VideoReencoder(context).transcode(
                sourceUri = sourceUri,
                outputFile = tempFile,
                trimStartUs = s.trimStartMs * 1000L,
                trimEndUs = s.trimEndMs * 1000L,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropWidth = crop.width(),
                cropHeight = crop.height(),
                rotationDegrees = finalRotation,
                audioOverlayUri = s.audioOverlayUri?.let { Uri.parse(it) },
                audioTrimStartUs = s.audioTrimStartMs * 1000L,
                audioTrimEndUs = s.audioTrimEndMs * 1000L,
                muteOriginalAudio = s.muteOriginalAudio,
                onProgress = { p ->
                    _state.update { it.copy(saveProgress = p) }
                },
            )
            val written = when (mode) {
                VideoSaveMode.Overwrite -> try {
                    val out = context.contentResolver.openOutputStream(sourceUri, "wt")
                        ?: error("openOutputStream returned null for $sourceUri")
                    out.use { o -> tempFile.inputStream().use { it.copyTo(o) } }
                    VideoSaveResult.Success(sourceUri)
                } catch (se: SecurityException) {
                    // Same write-consent dance as the stream-copy path — for own-camera videos
                    // the source URI is foreign to us, so Overwrite needs explicit consent.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        tryRequestWritePermission(sourceUri, mode)) {
                        return@withContext VideoSaveResult.Failed("__pending_write_permission__")
                    }
                    val uri = insertReencodedCopy(tempFile, s.displayName, s.mimeType, useOriginalName = true)
                    VideoSaveResult.SuccessAsCopy(uri)
                }
                VideoSaveMode.Copy -> {
                    val uri = insertReencodedCopy(tempFile, s.displayName, s.mimeType, useOriginalName = false)
                    VideoSaveResult.Success(uri)
                }
            }
            written
        } finally {
            tempFile.delete()
        }
    }

    private fun insertReencodedCopy(
        muxFile: File,
        displayName: String,
        mimeType: String,
        useOriginalName: Boolean,
    ): Uri? {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val outName = if (useOriginalName) displayName else stamp(displayName)
        val outMime = if (mimeType.startsWith("video/")) mimeType else "video/mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outName)
            put(MediaStore.Video.Media.MIME_TYPE, outMime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, ProtonPhotosStorage.DEFAULT_PICTURES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            muxFile.inputStream().use { it.copyTo(out) }
        } ?: error("openOutputStream returned null for $uri")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    /**
     * Streams the trimmed range from the source URI back onto the same URI. Throws
     * SecurityException for foreign MediaStore items (caller falls back to insertLocalCopy).
     *
     * Strategy: write to a temp file in the cache, then re-open the source URI for write
     * and copy bytes over. Writing the muxer output directly into the source's
     * openOutputStream is fragile because MediaMuxer needs a seekable FileDescriptor and
     * many content providers return write-only non-seekable streams.
     */
    private suspend fun overwriteLocal(
        sourceUri: Uri,
        trimStartMs: Long,
        trimEndMs: Long,
        orientationDegrees: Int,
        stripAudio: Boolean = false,
    ): Uri = withContext(Dispatchers.IO) {
        val tempFile = createTempMuxFile()
        try {
            muxTrimmed(
                sourceUri = sourceUri,
                outputFile = tempFile,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                orientationDegrees = orientationDegrees,
                stripAudio = stripAudio,
            )
            // Re-open the source for write. "wt" truncates first so we don't leave the
            // last bytes of the (potentially longer) original dangling.
            context.contentResolver.openOutputStream(sourceUri, "wt")?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            } ?: error("openOutputStream returned null for $sourceUri")
            sourceUri
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Writes the trimmed range into a fresh MediaStore Video entry under Pictures/Proton Photos.
     * The IS_PENDING dance keeps the entry hidden from other apps until the bytes are flushed.
     */
    private suspend fun insertLocalCopy(
        sourceUri: Uri,
        displayName: String,
        mimeType: String,
        trimStartMs: Long,
        trimEndMs: Long,
        orientationDegrees: Int,
        useOriginalName: Boolean,
        stripAudio: Boolean = false,
    ): Uri? = withContext(Dispatchers.IO) {
        val tempFile = createTempMuxFile()
        try {
            muxTrimmed(
                sourceUri = sourceUri,
                outputFile = tempFile,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                orientationDegrees = orientationDegrees,
                stripAudio = stripAudio,
            )
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val outName = if (useOriginalName) displayName else stamp(displayName)
            val outMime = if (mimeType.startsWith("video/")) mimeType else "video/mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outName)
                put(MediaStore.Video.Media.MIME_TYPE, outMime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, ProtonPhotosStorage.DEFAULT_PICTURES)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(collection, values)
                ?: error("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            } ?: error("openOutputStream returned null for $uri")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            uri
        } finally {
            tempFile.delete()
        }
    }

    /**
     * The core MediaExtractor + MediaMuxer pipeline.
     *
     * Steps:
     *  1. Open MediaExtractor on the source URI via ContentResolver.openFileDescriptor.
     *  2. For each track, copy its MediaFormat into a new MediaMuxer; remember the
     *     mapping from source track index → muxer track index.
     *  3. Set the orientation hint on the muxer (must happen BEFORE muxer.start()).
     *  4. Seek source to trimStartMs (SYNC_PREVIOUS so we don't land mid-GOP).
     *  5. Stream samples between trimStartMs..trimEndMs into the muxer. Subtract the
     *     start offset from each sample timestamp so the output starts at 0.
     *  6. Release everything in finally.
     */
    private fun muxTrimmed(
        sourceUri: Uri,
        outputFile: File,
        trimStartMs: Long,
        trimEndMs: Long,
        orientationDegrees: Int,
        stripAudio: Boolean = false,
    ) {
        var pfd: ParcelFileDescriptor? = null
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: error("Could not open source video for reading")
            extractor = MediaExtractor().apply {
                setDataSource(pfd.fileDescriptor)
            }
            val trackCount = extractor.trackCount
            if (trackCount == 0) error("Source video has no tracks")

            // Output is always MP4 — covers H.264/H.265/AAC stream-copy cases and matches
            // the source's container in 99% of phone-shot clips.
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = IntArray(trackCount) { -1 }
            var maxBufferSize = 0
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                // Some containers (rarely) carry tracks the MP4 muxer can't take. Skip them
                // rather than throw; the resulting clip just won't include that track.
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                // Mute-original mode — don't register the audio track on the muxer; the
                // extractor loop below will skip it too.
                if (stripAudio && mime.startsWith("audio/")) continue
                val muxerTrack = muxer.addTrack(format)
                trackMap[i] = muxerTrack
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    val sz = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    if (sz > maxBufferSize) maxBufferSize = sz
                }
            }
            if (trackMap.all { it < 0 }) error("No copyable tracks in source")
            if (maxBufferSize <= 0) maxBufferSize = 1 shl 20 // 1 MB fallback

            muxer.setOrientationHint(orientationDegrees)
            muxer.start()

            val buffer = ByteBuffer.allocate(maxBufferSize)
            val info = android.media.MediaCodec.BufferInfo()

            // Per-track loop: seek each track to its closest preceding sync sample to
            // trimStartMs, then stream samples while pts <= trimEndMs.
            for (srcIdx in 0 until trackCount) {
                val muxIdx = trackMap[srcIdx]
                if (muxIdx < 0) continue
                extractor.selectTrack(srcIdx)
                // SYNC_PREVIOUS keeps the first sample after seek a keyframe — required for
                // video to decode correctly. For audio it lands on the closest packet, fine.
                extractor.seekTo(trimStartMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val startUs = trimStartMs * 1000L
                val endUs = trimEndMs * 1000L
                while (true) {
                    buffer.clear()
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0) break
                    val pts = extractor.sampleTime
                    if (pts > endUs) break
                    if (pts >= startUs) {
                        info.offset = 0
                        info.size = read
                        // Output timestamps rebase to 0 so players don't seek-skip the prefix.
                        info.presentationTimeUs = (pts - startUs).coerceAtLeast(0L)
                        info.flags = extractorFlagsToBufferFlags(extractor.sampleFlags)
                        muxer.writeSampleData(muxIdx, buffer, info)
                    }
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(srcIdx)
            }
        } finally {
            // Order matters: stop() before release() on the muxer or we corrupt the moov atom.
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            runCatching { pfd?.close() }
        }
    }

    /**
     * Translates [MediaExtractor.getSampleFlags] bits into [android.media.MediaCodec.BufferInfo.flags].
     * We only care about SAMPLE_FLAG_SYNC → BUFFER_FLAG_KEY_FRAME — the muxer uses the
     * key-frame flag to populate the SyncSampleBox so seekers can find keyframes.
     */
    private fun extractorFlagsToBufferFlags(extractorFlags: Int): Int {
        var f = 0
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            f = f or android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return f
    }

    private fun createTempMuxFile(): File {
        val dir = File(context.cacheDir, "video_editor").also { it.mkdirs() }
        // .mp4 extension so any consumer that sniffs by suffix (file pickers, share intents)
        // identifies the temp correctly until it's copied over.
        return File.createTempFile("vmux_", ".mp4", dir)
    }

    private fun stamp(displayName: String): String {
        val dotIdx = displayName.lastIndexOf('.')
        val base = if (dotIdx > 0) displayName.substring(0, dotIdx) else displayName
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
            .format(java.util.Date())
        return "${base}_edit_$ts.mp4"
    }
}
