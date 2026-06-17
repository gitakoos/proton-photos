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
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import coil.imageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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
 * UI state for the video editor. Trim is MediaExtractor + MediaMuxer stream-copy (bitstream preserved);
 * rotate is metadata-only via [MediaMuxer.setOrientationHint]. [rotationDegrees] is ADDITIVE to the
 * source's rotation tag (read via MediaMetadataRetriever, added on save).
 */
data class VideoEditorUiState(
    val sourceUri: String? = null,
    val displayName: String = "",
    val mimeType: String = "video/mp4",
    val durationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val rotationDegrees: Int = 0, // 0, 90, 180, 270 — additive to source rotation metadata
    /** Crop in source-video pixels (null = full frame). Set → forces the re-encode save path. */
    val cropRect: Rect? = null,
    /** Source video dimensions in pixels (post-rotation), populated by loadLocal. */
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /** Picked overlay audio (null = keep source audio). Set → forces the audio-swap save path. */
    val audioOverlayUri: String? = null,
    val audioOverlayDisplayName: String? = null,
    val audioOverlayDurationMs: Long = 0L,
    /** Within the overlay music, the slice to play during the saved video. Ignored
     *  when [audioOverlayUri] is null. */
    val audioTrimStartMs: Long = 0L,
    val audioTrimEndMs: Long = 0L,
    /** Source audio gain [0..1]: 0 drops the original, 1 keeps it, between attenuates. */
    val originalAudioGain: Float = 1.0f,
    /** Overlay music gain [0..1], ignored when [audioOverlayUri] is null. Both > 0 → the two PCM streams mix. */
    val musicAudioGain: Float = 1.0f,
    val isSaving: Boolean = false,
    val isLoading: Boolean = true,
    val saveResult: VideoSaveResult? = null,
    val errorMessage: String? = null,
    /** 0..1 progress during a re-encode save; null for stream-copy (fast enough for a spinner). */
    val saveProgress: Float? = null,
    /** Save phase, so the sheet can label the local re-encode vs the progress-less cloud upload. */
    val saveStage: VideoSaveStage = VideoSaveStage.Idle,
    /** R+ MediaStore write-consent intent ([MediaStore.createWriteRequest]) when an Overwrite hits
     *  SecurityException on a foreign URI, so the screen can launch it instead of silently copying. */
    val pendingWriteIntent: android.app.PendingIntent? = null,
    /** OS delete-consent intent when a Synced Overwrite falls back to copy, to remove the orphaned
     *  original. Mirrors [pendingWriteIntent]. */
    val pendingDeleteIntent: android.app.PendingIntent? = null,
    /** How the editor was entered — drives the save dispatch (overwrite / cloud upload / always-copy). */
    val source: VideoEditorSource? = null,
    /** Latched after an [VideoEditorSource.External] save so the screen can show "Saved a copy". */
    val savedAsCopy: Boolean = false,
)

sealed class VideoSaveResult {
    data class Success(val uri: Uri?) : VideoSaveResult()
    data class SuccessAsCopy(val uri: Uri?) : VideoSaveResult()
    data class Failed(val message: String) : VideoSaveResult()
}

enum class VideoSaveMode { Overwrite, Copy }

/** Save phase, driving the bottom sheet's progress copy. */
enum class VideoSaveStage { Idle, Encoding, Encrypting, Uploading }

/**
 * How the editor was entered, so the save flow knows whether the source URI is writable.
 * [Local] is overwritable (R+ consent for foreign-owner rows); [Cloud] uploads a new linkId;
 * [External] (foreign ACTION_EDIT/VIEW) is forced to a fresh copy — the foreign URI may be read-only.
 */
sealed class VideoEditorSource {
    data class Local(val uri: String, val displayName: String, val mimeType: String) : VideoEditorSource()
    data class Cloud(val photo: CloudPhoto) : VideoEditorSource()
    data class External(val uri: String, val displayName: String, val mimeType: String) : VideoEditorSource()
}

@HiltViewModel
class VideoEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: eu.akoos.photos.domain.repository.SyncStateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(VideoEditorUiState())
    val state: StateFlow<VideoEditorUiState> = _state.asStateFlow()

    /** Source video's baked-in orientation tag, captured at load time and reused at save
     *  time so we can write (sourceRotation + userRotation) % 360 into the muxer. */
    private var sourceRotationDegrees: Int = 0

    /** Non-null for a cloud-only video: downloaded to cache for editing, re-uploaded on save.
     *  Local save paths run on the cache file:// URI unchanged. */
    private var sourceCloudPhoto: CloudPhoto? = null
    /** Optional album linkId the cloud video lives in. Used to re-attach the re-uploaded
     *  edit to the same album so it doesn't disappear from the album view. */
    private var sourceCloudAlbumLinkId: String? = null
    /** Settable from the screen so a Synced-video edit can re-attach its newly-uploaded
     *  cloud copy to the same album the source lived in. Mirrors PhotoEditor's path. */
    fun setSourceAlbumLinkId(linkId: String?) {
        if (linkId != null) sourceCloudAlbumLinkId = linkId
    }

    /** Drive twin of a Synced device video; local saves consult it to also push the edit to Drive. */
    private var cloudCounterpart: CloudPhoto? = null

    /** Mirrors [cloudCounterpart] presence so the save sheet can show a "device + cloud" subtitle. */
    private val _hasCloudCounterpart = MutableStateFlow(false)

    fun setCloudCounterpart(photo: CloudPhoto?) {
        cloudCounterpart = photo
        _hasCloudCounterpart.value = photo != null
    }

    val hasCloudCounterpart: StateFlow<Boolean> = _hasCloudCounterpart.asStateFlow()

    fun loadLocal(uri: String, displayName: String, mimeType: String) {
        _state.update {
            it.copy(
                sourceUri = uri,
                displayName = displayName,
                mimeType = mimeType,
                isLoading = true,
                errorMessage = null,
                saveResult = null,
                source = VideoEditorSource.Local(uri, displayName, mimeType),
                savedAsCopy = false,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            if (parsed == null) {
                _state.update { it.copy(isLoading = false, errorMessage = context.getString(R.string.editor_invalid_video_uri)) }
                return@launch
            }
            // Pull duration + rotation (for the additive rotate) via MediaMetadataRetriever.
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
                    it.copy(isLoading = false, errorMessage = context.getString(R.string.editor_video_metadata_failed))
                }
                return@launch
            }
            sourceRotationDegrees = ((meta.rotation % 360) + 360) % 360
            // MediaMetadataRetriever reports ENCODED dims (ignoring VIDEO_ROTATION), so swap into
            // post-rotation effective dims, else the crop overlay clamps to the wrong orientation.
            val effW = if (sourceRotationDegrees % 180 != 0) meta.height else meta.width
            val effH = if (sourceRotationDegrees % 180 != 0) meta.width else meta.height
            _state.update {
                it.copy(
                    durationMs = meta.duration,
                    trimStartMs = 0L,
                    trimEndMs = meta.duration,
                    rotationDegrees = 0,
                    cropRect = null,
                    sourceWidth = effW,
                    sourceHeight = effH,
                    audioOverlayUri = null,
                    audioOverlayDisplayName = null,
                    audioOverlayDurationMs = 0L,
                    audioTrimStartMs = 0L,
                    audioTrimEndMs = 0L,
                    originalAudioGain = 1.0f,
                    musicAudioGain = 1.0f,
                    isLoading = false,
                )
            }
        }
    }

    /**
     * Loads an externally-supplied video (system "Open with" / "Edit with" chooser
     * entry, ACTION_EDIT / ACTION_VIEW from a foreign app). Identical to [loadLocal]
     * except the source is tagged [VideoEditorSource.External] so [save] always writes
     * a fresh MediaStore copy via the editor's default video copy path rather than
     * attempting an in-place overwrite — the foreign URI may be read-only, may belong to
     * another app's MediaStore row, or may be backed by a transient grant that
     * disappears at process death, and we never mutate files we did not create.
     */
    fun loadExternal(uri: String, displayName: String, mimeType: String) {
        _state.update {
            it.copy(
                sourceUri = uri,
                displayName = displayName,
                mimeType = mimeType,
                isLoading = true,
                errorMessage = null,
                saveResult = null,
                source = VideoEditorSource.External(uri, displayName, mimeType),
                savedAsCopy = false,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            if (parsed == null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = context.getString(R.string.editor_external_load_error),
                    )
                }
                return@launch
            }
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
                    it.copy(
                        isLoading = false,
                        errorMessage = context.getString(R.string.editor_external_load_error),
                    )
                }
                return@launch
            }
            sourceRotationDegrees = ((meta.rotation % 360) + 360) % 360
            // Encoded dims swap into post-rotation effective dims so the crop overlay
            // clamps to the orientation the user actually sees — matches [loadLocal].
            val effW = if (sourceRotationDegrees % 180 != 0) meta.height else meta.width
            val effH = if (sourceRotationDegrees % 180 != 0) meta.width else meta.height
            _state.update {
                it.copy(
                    durationMs = meta.duration,
                    trimStartMs = 0L,
                    trimEndMs = meta.duration,
                    rotationDegrees = 0,
                    cropRect = null,
                    sourceWidth = effW,
                    sourceHeight = effH,
                    audioOverlayUri = null,
                    audioOverlayDisplayName = null,
                    audioOverlayDurationMs = 0L,
                    audioTrimStartMs = 0L,
                    audioTrimEndMs = 0L,
                    originalAudioGain = 1.0f,
                    musicAudioGain = 1.0f,
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
                source = VideoEditorSource.Cloud(photo),
                savedAsCopy = false,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                _state.update { it.copy(isLoading = false, errorMessage = context.getString(R.string.viewer_not_signed_in)) }
                return@launch
            }
            val downloaded = runCatching { cloudRepo.downloadFullResPhoto(userId, photo) }
            val file = downloaded.getOrNull()
            if (file == null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = downloaded.exceptionOrNull()?.message
                            ?: context.getString(R.string.editor_cloud_video_download_failed),
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
                _state.update { it.copy(isLoading = false, errorMessage = context.getString(R.string.editor_video_metadata_failed)) }
                return@launch
            }
            runCatching { retriever.release() }
            sourceRotationDegrees = ((meta.rotation % 360) + 360) % 360
            // See the loadLocal counterpart — encoded dims swap to effective dims so
            // the crop overlay clamps to the orientation the user sees.
            val effW = if (sourceRotationDegrees % 180 != 0) meta.height else meta.width
            val effH = if (sourceRotationDegrees % 180 != 0) meta.width else meta.height
            _state.update {
                it.copy(
                    sourceUri = parsed.toString(),
                    durationMs = meta.duration,
                    trimStartMs = 0L,
                    trimEndMs = meta.duration,
                    rotationDegrees = 0,
                    cropRect = null,
                    sourceWidth = effW,
                    sourceHeight = effH,
                    audioOverlayUri = null,
                    audioOverlayDisplayName = null,
                    audioOverlayDurationMs = 0L,
                    audioTrimStartMs = 0L,
                    audioTrimEndMs = 0L,
                    originalAudioGain = 1.0f,
                    musicAudioGain = 1.0f,
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
    /**
     * Convert the user's crop rect (which lives in POST-rotation display coordinates —
     * what they see in the editor) into PRE-rotation source-pixel coordinates that
     * [VideoReencoder] / [CropMatrix] expect.
     *
     * Without this conversion the saved video crops the wrong region. Concretely, for a
     * portrait phone clip the source stream is 1920×1080 landscape with an orientation
     * tag of 90°; the editor shows it as 1080×1920 portrait and the user drags handles
     * in that 1080×1920 space. VideoReencoder decodes the raw 1920×1080 stream and
     * applies the crop rect against THOSE dimensions, so a crop of (100, 200, 300, 400)
     * in display coords cuts an entirely different region of the source — or coerces to
     * an invalid rect and the encoder spits out garbage. The observable symptom is that
     * the crop has no visible effect on the saved video.
     *
     * The rotation transforms below are derived from "post = source rotated `sourceRotation`
     * CW", so to invert we rotate `sourceRotation` CCW. Only 0/90/180/270 are valid
     * MediaStore orientation tags.
     */
    private fun cropInSourcePixels(s: VideoEditorUiState): Rect {
        val effRect = s.cropRect ?: Rect(
            0, 0,
            s.sourceWidth.coerceAtLeast(2),
            s.sourceHeight.coerceAtLeast(2),
        )
        val postW = s.sourceWidth.coerceAtLeast(1)
        val postH = s.sourceHeight.coerceAtLeast(1)
        return when (((sourceRotationDegrees % 360) + 360) % 360) {
            90  -> Rect(effRect.top, postW - effRect.right, effRect.bottom, postW - effRect.left)
            180 -> Rect(postW - effRect.right, postH - effRect.bottom, postW - effRect.left, postH - effRect.top)
            270 -> Rect(postH - effRect.bottom, effRect.left, postH - effRect.top, effRect.right)
            else -> effRect
        }
    }

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

    /** Source-audio loudness. 0 = drop original, 1 = unattenuated, intermediate = scaled
     *  via PCM gain at save time (forces re-encode if not exactly 0 or 1). */
    fun setOriginalAudioGain(gain: Float) {
        _state.update { it.copy(originalAudioGain = gain.coerceIn(0f, 1f)) }
    }

    /** Overlay-music loudness, same semantics as [setOriginalAudioGain]. When both gains
     *  are > 0 and an overlay is picked the save pipeline mixes the two PCM streams. */
    fun setMusicAudioGain(gain: Float) {
        _state.update { it.copy(musicAudioGain = gain.coerceIn(0f, 1f)) }
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

    /** Clears the [VideoEditorUiState.savedAsCopy] flag once the screen has shown the
     *  "Saved a copy" feedback, so a subsequent save doesn't re-fire the toast. */
    fun consumeSavedAsCopy() {
        _state.update { it.copy(savedAsCopy = false) }
    }

    /** Clears the error popup state so the screen can hide it. Called from the
     *  ErrorPopup's OK action — the screen itself usually pops the back stack right
     *  after, but if the user lands here again (NavBackStackEntry reuse) the cleared
     *  state ensures the popup doesn't re-appear without a new failure. */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private var pendingWriteMode: VideoSaveMode? = null

    fun save(mode: VideoSaveMode, allowWriteRequestRecovery: Boolean = true) {
        val s = _state.value
        val sourceUri = s.sourceUri ?: return
        if (s.isSaving) return
        // External entries (system "Open with" / "Edit with" chooser) always land as a
        // fresh MediaStore copy in the editor's default video output directory. The
        // foreign URI may be read-only, owned by another app, or backed by a transient
        // grant we will lose at process death — overwriting it ranges from impossible to
        // outright destructive of a file we did not create. Force Copy mode here so the
        // caller's choice (if any) cannot bypass this rule.
        val effectiveMode = if (s.source is VideoEditorSource.External) VideoSaveMode.Copy else mode
        viewModelScope.launch(Dispatchers.IO) {
            // Decide between fast stream-copy and full re-encode. A re-encode is required
            // whenever the user has set a crop OR swapped the audio track — both modify
            // payload that the stream-copy path treats as immutable. Pixel rotation falls
            // through into the muxer's orientation hint, so it does NOT force a re-encode
            // on its own.
            // Partial source-audio gain (anything other than full-on or full-off) requires
            // PCM-level processing — stream-copy mux can't attenuate samples on its own.
            val partialOriginalGain = s.originalAudioGain > 0.001f && s.originalAudioGain < 0.999f
            val needsReencode = s.cropRect != null || s.audioOverlayUri != null || partialOriginalGain
            _state.update {
                it.copy(
                    isSaving = true,
                    saveResult = null,
                    saveProgress = if (needsReencode) 0f else null,
                    // Re-encode → Encoding; pure stream-copy → straight to Uploading-ish
                    // (it'll be over fast enough that even Idle would be defensible, but
                    // labelling it consistently keeps the sheet copy stable).
                    saveStage = if (needsReencode) VideoSaveStage.Encoding else VideoSaveStage.Uploading,
                )
            }
            val finalRotation = ((sourceRotationDegrees + s.rotationDegrees) % 360 + 360) % 360
            val cloudPhoto = sourceCloudPhoto
            val counterpart = cloudCounterpart
            // Synced video edit = device file + cloud sibling, both need the edited bytes.
            // For this case we build the muxed tempFile ONCE up front and feed it to BOTH
            // the local MediaStore write and the cloud upload. The previous flow ran the
            // transcode (or stream-copy mux) twice — once inside the local save path, once
            // inside the cloud-fanout path — which doubled CPU + battery on every save AND
            // doubled the cryptoLock-holding encrypt phase that starves gallery decrypts.
            val isSynced = cloudPhoto == null && counterpart != null
            // Single timestamp shared across the device save AND any cloud-counterpart
            // upload for Synced videos. Used to derive both the stamped filename and the
            // DATE_TAKEN / captureTime metadata so ReconcileSyncStateUseCase.byNameAndDate
            // pairs the two fresh copies as Synced without needing a re-download.
            // Computing System.currentTimeMillis() independently per save path would drift
            // by a few ms — different filenames, different captureTime seconds, no match.
            val editTimestampMs = System.currentTimeMillis()
            val syncedTempFile: File? = if (isSynced) createTempMuxFile() else null
            val saveResult: VideoSaveResult = try {
                if (syncedTempFile != null) {
                    produceEditedTempFile(s, syncedTempFile, finalRotation, needsReencode)
                }
                val result = when {
                    // Cloud video edit: produce the muxed .mp4 in cache, then upload as a
                    // new linkId. Mirrors PhotoEditor.saveCloud — Overwrite trashes the
                    // original after the new linkId is committed, Copy keeps both.
                    cloudPhoto != null -> saveCloud(s, mode, finalRotation, cloudPhoto, needsReencode, editTimestampMs)
                    syncedTempFile != null -> saveLocalFromExistingFile(
                        s, mode, editTimestampMs, allowWriteRequestRecovery, syncedTempFile,
                    )
                    needsReencode -> saveReencoded(s, effectiveMode, finalRotation, editTimestampMs, allowWriteRequestRecovery)
                    else -> saveStreamCopy(s, effectiveMode, finalRotation, editTimestampMs, allowWriteRequestRecovery)
                }
                val savedUri: Uri? = when (result) {
                    is VideoSaveResult.Success -> result.uri
                    is VideoSaveResult.SuccessAsCopy -> result.uri
                    is VideoSaveResult.Failed -> null
                }
                // Synced video path: push the SAME bytes that just landed locally up to
                // the cloud counterpart. The MediaStore insert above fired the OS-level
                // content observer that BackgroundSyncService listens on — without the
                // UPLOADING placeholder row below, SyncWorker would race this fanout and
                // upload the same edited bytes a second time (duplicate Drive entry).
                // Reconcile and SyncWorker both skip rows in UPLOADING state so the
                // editor owns the row until the fanout completes (or fails and demotes).
                if (syncedTempFile != null && counterpart != null && savedUri != null &&
                    result !is VideoSaveResult.Failed) {
                    val userId = accountManager.getPrimaryUserId().first()
                    if (userId != null) {
                        val savedUriStr = savedUri.toString()
                        val placeholderState = SyncState(
                            localUri = savedUriStr,
                            cloudFileId = null,
                            localHash = "",
                            cloudHash = null,
                            status = SyncStatus.UPLOADING,
                            lastSyncAttemptMs = System.currentTimeMillis(),
                            lastSyncSuccessMs = null,
                            backedUpAtMs = null,
                            sizeBytes = syncedTempFile.length(),
                        )
                        runCatching { syncStateRepo.upsert(placeholderState, userId) }
                        // Sheet label transitions from "Encoding…" to "Uploading…" so the
                        // user knows we've moved past the local re-encode into the cloud
                        // leg (which has no progress bar). Without this flip the sheet
                        // sat at 100 % silently for the upload duration and users thought
                        // the save had stalled.
                        _state.update { it.copy(saveStage = VideoSaveStage.Uploading, saveProgress = null) }
                        val fanoutResult = runCatching {
                            uploadExistingFileToCloud(s, mode, counterpart, editTimestampMs, syncedTempFile, userId)
                        }
                        val newLinkId = fanoutResult.getOrNull()
                        if (fanoutResult.isSuccess && newLinkId != null) {
                            runCatching {
                                syncStateRepo.upsert(
                                    placeholderState.copy(
                                        cloudFileId = newLinkId,
                                        status = SyncStatus.SYNCED,
                                        lastSyncSuccessMs = System.currentTimeMillis(),
                                        backedUpAtMs = System.currentTimeMillis(),
                                    ),
                                    userId,
                                )
                            }
                        } else {
                            // Upload failed — demote so SyncWorker eventually retries.
                            runCatching {
                                syncStateRepo.upsert(
                                    placeholderState.copy(status = SyncStatus.LOCAL_ONLY),
                                    userId,
                                )
                            }
                        }
                    }
                }
                // Defensive Coil invalidation — the saved URI may not actually be in the
                // image cache for videos (thumbnails live under MediaStore URIs that may
                // or may not match), but the call is a safe no-op when the key is absent
                // and unifies the post-save state with the photo path.
                if (savedUri != null && cloudPhoto == null) {
                    invalidateImageCache(savedUri)
                }
                result
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                VideoSaveResult.Failed(eu.akoos.photos.util.sanitizeErrorMessage(e.message ?: context.getString(R.string.editor_save_failed)))
            } finally {
                syncedTempFile?.delete()
            }
            // pendingWriteIntent suspended the save until the user reacts to the consent
            // dialog — leave isSaving on the state from that branch alone.
            if (_state.value.pendingWriteIntent == null) {
                val markCopied = s.source is VideoEditorSource.External &&
                    (saveResult is VideoSaveResult.Success || saveResult is VideoSaveResult.SuccessAsCopy)
                _state.update {
                    it.copy(
                        isSaving = false,
                        saveResult = saveResult,
                        saveProgress = null,
                        saveStage = VideoSaveStage.Idle,
                        // Latch the "Saved a copy" hint for External entries — the screen's
                        // LaunchedEffect(savedAsCopy) raises the post-save feedback so the user
                        // sees that the foreign original was left untouched.
                        savedAsCopy = it.savedAsCopy || markCopied,
                    )
                }
            }
        }
    }

    /**
     * Runs the edit pipeline (transcode for re-encode, mux for stream-copy) into the
     * supplied [outputFile]. Extracted so the Synced video path can build the edited
     * bytes ONCE and reuse them for both the local MediaStore write and the cloud upload.
     */
    private suspend fun produceEditedTempFile(
        s: VideoEditorUiState,
        outputFile: File,
        finalRotation: Int,
        needsReencode: Boolean,
    ) = withContext(Dispatchers.IO) {
        val sourceUri = Uri.parse(s.sourceUri ?: error("No source URI"))
        // Three-way dispatch:
        //   • No re-encode at all (trim + rotate only) → muxTrimmed (existing fast path)
        //   • Audio edits but no crop → video stream-copy + audio mix-encode
        //     This preserves HDR (the 8-bit GL pipeline downsamples BT.2020/PQ source
        //     to washed-out SDR), keeps the video lossless, and only re-encodes audio.
        //   • Crop set → full GL re-encode (loses HDR — unavoidable without a 10-bit
        //     RGBA pipeline + HEVC main10 encoder).
        val hasCrop = s.cropRect != null
        when {
            needsReencode && hasCrop -> {
                val crop = cropInSourcePixels(s)
                VideoReencoder(context).transcode(
                    sourceUri = sourceUri,
                    outputFile = outputFile,
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
                    originalAudioGain = s.originalAudioGain,
                    musicAudioGain = s.musicAudioGain,
                    onProgress = { p -> _state.update { it.copy(saveProgress = p) } },
                    isActive = { isActive },
                )
            }
            needsReencode -> {
                VideoReencoder(context).streamCopyVideoWithMixedAudio(
                    sourceUri = sourceUri,
                    outputFile = outputFile,
                    trimStartUs = s.trimStartMs * 1000L,
                    trimEndUs = s.trimEndMs * 1000L,
                    rotationDegrees = finalRotation,
                    audioOverlayUri = s.audioOverlayUri?.let { Uri.parse(it) },
                    audioTrimStartUs = s.audioTrimStartMs * 1000L,
                    audioTrimEndUs = s.audioTrimEndMs * 1000L,
                    originalAudioGain = s.originalAudioGain,
                    musicAudioGain = s.musicAudioGain,
                    onProgress = { p -> _state.update { it.copy(saveProgress = p) } },
                    isActive = { isActive },
                )
            }
            else -> muxTrimmed(
                sourceUri = sourceUri,
                outputFile = outputFile,
                trimStartMs = s.trimStartMs,
                trimEndMs = s.trimEndMs,
                orientationDegrees = finalRotation,
                // Stream-copy strips source audio entirely when the gain slider is at 0
                // and there's no overlay to bring in. Partial gain / overlay cases force
                // re-encode (handled by needsReencode) so they never reach this branch.
                stripAudio = s.originalAudioGain <= 0.001f,
            )
        }
    }

    /**
     * Synced video local-save path that reads from a pre-built [tempFile] (no second
     * encode). Mirrors [saveStreamCopy] / [saveReencoded] for MediaStore semantics:
     * Overwrite tries to write to the source URI (with R+ consent-prompt fallback),
     * Copy inserts a fresh entry under Pictures/Proton Photos.
     */
    private suspend fun saveLocalFromExistingFile(
        s: VideoEditorUiState,
        mode: VideoSaveMode,
        editTimestampMs: Long,
        allowWriteRequestRecovery: Boolean,
        tempFile: File,
    ): VideoSaveResult = withContext(Dispatchers.IO) {
        val sourceUri = Uri.parse(s.sourceUri ?: error("No source URI"))
        when (mode) {
            VideoSaveMode.Overwrite -> try {
                context.contentResolver.openOutputStream(sourceUri, "wt")?.use { out ->
                    tempFile.inputStream().use { it.copyTo(out) }
                } ?: error("openOutputStream returned null for $sourceUri")
                VideoSaveResult.Success(sourceUri)
            } catch (se: SecurityException) {
                if (allowWriteRequestRecovery &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    tryRequestWritePermission(sourceUri, mode)) {
                    return@withContext VideoSaveResult.Success(null)
                }
                val uri = insertReencodedCopy(
                    tempFile, s.displayName, s.mimeType,
                    useOriginalName = true, editTimestampMs = editTimestampMs,
                )
                if (cloudCounterpart != null) {
                    maybeSurfaceOrphanDeleteIntent(sourceUri)
                }
                VideoSaveResult.SuccessAsCopy(uri)
            }
            VideoSaveMode.Copy -> {
                val uri = insertReencodedCopy(
                    tempFile, s.displayName, s.mimeType,
                    useOriginalName = false, editTimestampMs = editTimestampMs,
                )
                VideoSaveResult.Success(uri)
            }
        }
    }

    /**
     * Synced video cloud-fanout that uploads from a pre-built [tempFile] (no second
     * encode). Returns the new Drive linkId so the caller can pin the local URI's
     * SyncState row to it, preventing reconcile from racing and uploading a duplicate.
     */
    private suspend fun uploadExistingFileToCloud(
        s: VideoEditorUiState,
        mode: VideoSaveMode,
        cloud: CloudPhoto,
        editTimestampMs: Long,
        tempFile: File,
        userId: me.proton.core.domain.entity.UserId,
    ): String = withContext(Dispatchers.IO) {
        val displayName = when (mode) {
            VideoSaveMode.Overwrite -> cloud.displayName
            VideoSaveMode.Copy -> stamp(cloud.displayName, editTimestampMs)
        }
        val outMime = if (s.mimeType.startsWith("video/")) s.mimeType else "video/mp4"
        val uploadUri = Uri.fromFile(tempFile).toString()
        val item = LocalMediaItem(
            uri = uploadUri,
            dateTaken = if (mode == VideoSaveMode.Overwrite) cloud.captureTime * 1000L else editTimestampMs,
            displayName = displayName,
            mimeType = outMime,
            sizeBytes = tempFile.length(),
            bucketName = null,
            width = s.sourceWidth,
            height = s.sourceHeight,
            duration = (s.trimEndMs - s.trimStartMs).coerceAtLeast(0L),
        )
        val hash = sha1(tempFile)
        val newLinkId = cloudRepo.uploadFile(userId, item, hash, uploadUri) { phase, doneBytes, totalBytes ->
            // Distinguish the encrypt and CDN-PUT phases on the sheet so the user sees
            // two distinct progress bars (Encrypting → Uploading) instead of a single
            // mystery "0% sit then 100% race" cycle that happened to share a label.
            val frac = (doneBytes.toFloat() / totalBytes.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            val newStage = when (phase) {
                eu.akoos.photos.data.repository.drive.UploadPhase.Encrypting -> VideoSaveStage.Encrypting
                eu.akoos.photos.data.repository.drive.UploadPhase.Uploading -> VideoSaveStage.Uploading
            }
            _state.update { it.copy(saveProgress = frac, saveStage = newStage) }
        }
        sourceCloudAlbumLinkId?.let { albumId ->
            runCatching { cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)) }
        }
        if (mode == VideoSaveMode.Overwrite) {
            runCatching { cloudRepo.deleteFiles(userId, listOf(cloud.linkId)) }
        }
        newLinkId
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
                saveResult = VideoSaveResult.Failed(context.getString(R.string.editor_save_cancelled)),
                saveProgress = null,
            )
        }
    }

    /** Called once the OS delete-consent dialog closes (regardless of Allow/Deny — the
     *  system has already actioned the choice by then). Clears the pending intent so the
     *  screen's saveResult Effect proceeds with toast + navigation. */
    fun onDeletePermissionResolved() {
        _state.update { it.copy(pendingDeleteIntent = null) }
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
        editTimestampMs: Long,
    ): VideoSaveResult = withContext(Dispatchers.IO) {
        val sourceUriStr = s.sourceUri ?: error("No cached video URI")
        val sourceUri = Uri.parse(sourceUriStr)
        val tempFile = createTempMuxFile()
        try {
            if (needsReencode) {
                val crop = cropInSourcePixels(s)
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
                    originalAudioGain = s.originalAudioGain,
                musicAudioGain = s.musicAudioGain,
                    onProgress = { p -> _state.update { it.copy(saveProgress = p) } },
                    isActive = { isActive },
                )
            } else {
                muxTrimmed(
                    sourceUri = sourceUri,
                    outputFile = tempFile,
                    trimStartMs = s.trimStartMs,
                    trimEndMs = s.trimEndMs,
                    orientationDegrees = finalRotation,
                    // Stream-copy strips source audio entirely when the gain slider is at 0
                // and there's no overlay to bring in. Partial gain / overlay cases force
                // re-encode (handled by needsReencode) so they never reach this branch.
                stripAudio = s.originalAudioGain <= 0.001f,
                )
            }
            val userId = accountManager.getPrimaryUserId().first()
                ?: error("Not signed in")
            val displayName = when (mode) {
                VideoSaveMode.Overwrite -> cloudPhoto.displayName
                VideoSaveMode.Copy -> stamp(cloudPhoto.displayName, editTimestampMs)
            }
            val outMime = if (s.mimeType.startsWith("video/")) s.mimeType else "video/mp4"
            val uploadUri = Uri.fromFile(tempFile).toString()
            val item = LocalMediaItem(
                uri = uploadUri,
                dateTaken = if (mode == VideoSaveMode.Overwrite) cloudPhoto.captureTime * 1000L else editTimestampMs,
                displayName = displayName,
                mimeType = outMime,
                sizeBytes = tempFile.length(),
                bucketName = null,
                width = s.sourceWidth,
                height = s.sourceHeight,
                duration = (s.trimEndMs - s.trimStartMs).coerceAtLeast(0L),
            )
            val hash = sha1(tempFile)
            // Flip to Uploading just before the upload starts so the sheet stops sitting
            // at 100 % silently while the cloud leg runs.
            _state.update { it.copy(saveStage = VideoSaveStage.Encrypting, saveProgress = 0f) }
            val newLinkId = cloudRepo.uploadFile(userId, item, hash, uploadUri) { phase, doneBytes, totalBytes ->
                val frac = (doneBytes.toFloat() / totalBytes.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                val newStage = when (phase) {
                    eu.akoos.photos.data.repository.drive.UploadPhase.Encrypting -> VideoSaveStage.Encrypting
                    eu.akoos.photos.data.repository.drive.UploadPhase.Uploading -> VideoSaveStage.Uploading
                }
                _state.update { it.copy(saveProgress = frac, saveStage = newStage) }
            }

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

    /**
     * Hex-encodes the SHA-1 of the file's plaintext bytes. The upload pipeline
     * feeds this into `Common.Digests.SHA1` of the encrypted xAttr blob AND into
     * the `HMAC-SHA256(rootNodeHashKey, ...)` that produces the wire ContentHash.
     * See `PhotoEditorViewModel.sha1` for the cross-client rationale.
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

    /** Original stream-copy save path — fast, no decode/encode. Used when only trim
     *  and metadata-rotation are active. */
    private suspend fun saveStreamCopy(
        s: VideoEditorUiState,
        mode: VideoSaveMode,
        finalRotation: Int,
        editTimestampMs: Long,
        allowWriteRequestRecovery: Boolean = true,
    ): VideoSaveResult {
        val sourceUriStr = s.sourceUri ?: error("No source URI")
        // Stream-copy supports the "remove audio track" toggle by simply not registering
        // the audio MediaFormat on the muxer. (Overlay audio always forces the re-encode
        // path, so we only need to honour mute here.)
        val stripAudio = s.originalAudioGain <= 0.001f
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
                        // Pending intent already attached via state — leave isSaving on so
                        // the screen sees the spinner until the user reacts to the system
                        // dialog. The outer save() short-circuits on pendingWriteIntent.
                        return VideoSaveResult.Success(null)
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
                        editTimestampMs = editTimestampMs,
                    )
                    // Synced + Overwrite + fallback-to-Copy: the original device file is
                    // otherwise stranded next to the edit. Mirrors PhotoEditorViewModel —
                    // quiet delete first (works for app-owned files), createDeleteRequest
                    // otherwise (camera roll, screenshots).
                    if (cloudCounterpart != null) {
                        maybeSurfaceOrphanDeleteIntent(srcUri)
                    }
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
                    editTimestampMs = editTimestampMs,
                )
                VideoSaveResult.Success(uri)
            }
        }
    }

    /**
     * Synced-only orphan cleanup. Tries a quiet delete first (works for files this app
     * owns); falls back to [MediaStore.createDeleteRequest] on R+ for foreign-owner URIs,
     * surfacing the consent intent through state for the screen to launch.
     */
    private fun maybeSurfaceOrphanDeleteIntent(srcUri: Uri) {
        val rowsDeleted = runCatching {
            context.contentResolver.delete(srcUri, null, null)
        }.getOrDefault(0)
        if (rowsDeleted == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val deleteRequest = runCatching {
                MediaStore.createDeleteRequest(context.contentResolver, listOf(srcUri))
            }.getOrNull()
            if (deleteRequest != null) {
                _state.update { it.copy(pendingDeleteIntent = deleteRequest) }
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
        editTimestampMs: Long,
        allowWriteRequestRecovery: Boolean = true,
    ): VideoSaveResult = withContext(Dispatchers.IO) {
        val sourceUriStr = s.sourceUri ?: error("No source URI")
        val sourceUri = Uri.parse(sourceUriStr)
        val tempFile = createTempMuxFile()
        try {
            val crop = cropInSourcePixels(s)
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
                originalAudioGain = s.originalAudioGain,
                musicAudioGain = s.musicAudioGain,
                onProgress = { p ->
                    _state.update { it.copy(saveProgress = p) }
                },
                isActive = { isActive },
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
                    if (allowWriteRequestRecovery &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        tryRequestWritePermission(sourceUri, mode)) {
                        // Pending intent attached to state — short-circuit out so the screen
                        // can launch the system prompt.
                        return@withContext VideoSaveResult.Success(null)
                    }
                    val uri = insertReencodedCopy(
                        tempFile, s.displayName, s.mimeType,
                        useOriginalName = true, editTimestampMs = editTimestampMs,
                    )
                    // Synced + Overwrite + fallback-to-Copy: surface delete consent for
                    // the orphaned original device file so it doesn't sit next to the edit.
                    if (cloudCounterpart != null) {
                        maybeSurfaceOrphanDeleteIntent(sourceUri)
                    }
                    VideoSaveResult.SuccessAsCopy(uri)
                }
                VideoSaveMode.Copy -> {
                    val uri = insertReencodedCopy(
                        tempFile, s.displayName, s.mimeType,
                        useOriginalName = false, editTimestampMs = editTimestampMs,
                    )
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
        editTimestampMs: Long = System.currentTimeMillis(),
    ): Uri? {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val outName = if (useOriginalName) displayName else stamp(displayName, editTimestampMs)
        val outMime = if (mimeType.startsWith("video/")) mimeType else "video/mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outName)
            put(MediaStore.Video.Media.MIME_TYPE, outMime)
            // Explicit DATE_TAKEN so reconcile.byNameAndDate finds the cloud sibling —
            // see the editTimestampMs doc-comment in [save]. DATE_MODIFIED is in seconds
            // (the MediaStore legacy unit); DATE_TAKEN is in milliseconds.
            put(MediaStore.Video.Media.DATE_TAKEN, editTimestampMs)
            put(MediaStore.Video.Media.DATE_MODIFIED, editTimestampMs / 1000L)
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
        editTimestampMs: Long = System.currentTimeMillis(),
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
            val outName = if (useOriginalName) displayName else stamp(displayName, editTimestampMs)
            val outMime = if (mimeType.startsWith("video/")) mimeType else "video/mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outName)
                put(MediaStore.Video.Media.MIME_TYPE, outMime)
                // Explicit DATE_TAKEN so reconcile.byNameAndDate pairs this with the
                // freshly-uploaded cloud counterpart on the next sync pass — same trick
                // as PhotoEditorViewModel.insertLocalCopy. DATE_MODIFIED is in seconds.
                put(MediaStore.Video.Media.DATE_TAKEN, editTimestampMs)
                put(MediaStore.Video.Media.DATE_MODIFIED, editTimestampMs / 1000L)
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

            var buffer = ByteBuffer.allocate(maxBufferSize)
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
                    val read = try {
                        extractor.readSampleData(buffer, 0)
                    } catch (e: IllegalArgumentException) {
                        // Some 4K/HEVC keyframes exceed the reported KEY_MAX_INPUT_SIZE, so
                        // readSampleData throws instead of truncating. Grow the buffer and retry
                        // rather than failing the trim, the same way VideoMetadataStripper does.
                        val grown = buffer.capacity() * 2
                        if (grown > (100 shl 20)) throw e
                        buffer = ByteBuffer.allocate(grown)
                        continue
                    }
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

    /**
     * Synced-video helper. After a successful local save, ALSO write the muxed file up to
     * Drive as a new linkId so the cloud counterpart of the Synced item reflects the
     * change. Mirrors PhotoEditorViewModel.uploadEditAsCloudReplacement; the mode semantics
     * are:
     *   • Overwrite — upload + trash the old linkId (cloud "replace original")
     *   • Copy      — upload as a new linkId, leave the old one alone (cloud "keep both")
     */
    private suspend fun uploadEditAsCloudReplacement(
        s: VideoEditorUiState,
        mode: VideoSaveMode,
        finalRotation: Int,
        cloud: CloudPhoto,
        editTimestampMs: Long,
    ) = withContext(Dispatchers.IO) {
        val sourceUriStr = s.sourceUri ?: return@withContext
        val sourceUri = Uri.parse(sourceUriStr)
        val needsReencode = s.cropRect != null || s.audioOverlayUri != null
        val tempFile = createTempMuxFile()
        try {
            // Re-mux the same edits the local save just applied so the cloud copy stays
            // byte-equivalent to the device file. Stream-copy when possible, re-encode when
            // a crop or audio swap requires it.
            if (needsReencode) {
                val crop = cropInSourcePixels(s)
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
                    originalAudioGain = s.originalAudioGain,
                musicAudioGain = s.musicAudioGain,
                    onProgress = { /* Cloud-fanout progress not surfaced — local save already
                                      finished and the user sees a "Saved" state. */ },
                    isActive = { isActive },
                )
            } else {
                muxTrimmed(
                    sourceUri = sourceUri,
                    outputFile = tempFile,
                    trimStartMs = s.trimStartMs,
                    trimEndMs = s.trimEndMs,
                    orientationDegrees = finalRotation,
                    // Stream-copy strips source audio entirely when the gain slider is at 0
                // and there's no overlay to bring in. Partial gain / overlay cases force
                // re-encode (handled by needsReencode) so they never reach this branch.
                stripAudio = s.originalAudioGain <= 0.001f,
                )
            }
            val userId = accountManager.getPrimaryUserId().first() ?: return@withContext
            val displayName = when (mode) {
                VideoSaveMode.Overwrite -> cloud.displayName
                VideoSaveMode.Copy -> stamp(cloud.displayName, editTimestampMs)
            }
            val outMime = if (s.mimeType.startsWith("video/")) s.mimeType else "video/mp4"
            val uploadUri = Uri.fromFile(tempFile).toString()
            val item = LocalMediaItem(
                uri = uploadUri,
                dateTaken = if (mode == VideoSaveMode.Overwrite) cloud.captureTime * 1000L else editTimestampMs,
                displayName = displayName,
                mimeType = outMime,
                sizeBytes = tempFile.length(),
                bucketName = null,
                width = s.sourceWidth,
                height = s.sourceHeight,
                duration = (s.trimEndMs - s.trimStartMs).coerceAtLeast(0L),
            )
            val hash = sha1(tempFile)
            _state.update { it.copy(saveStage = VideoSaveStage.Encrypting, saveProgress = 0f) }
            val newLinkId = cloudRepo.uploadFile(userId, item, hash, uploadUri) { phase, doneBytes, totalBytes ->
                val frac = (doneBytes.toFloat() / totalBytes.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                val newStage = when (phase) {
                    eu.akoos.photos.data.repository.drive.UploadPhase.Encrypting -> VideoSaveStage.Encrypting
                    eu.akoos.photos.data.repository.drive.UploadPhase.Uploading -> VideoSaveStage.Uploading
                }
                _state.update { it.copy(saveProgress = frac, saveStage = newStage) }
            }
            sourceCloudAlbumLinkId?.let { albumId ->
                runCatching { cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)) }
            }
            if (mode == VideoSaveMode.Overwrite) {
                runCatching { cloudRepo.deleteFiles(userId, listOf(cloud.linkId)) }
            }
        } finally {
            tempFile.delete()
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private fun invalidateImageCache(uri: Uri) {
        // Mirrors PhotoEditorViewModel.invalidateImageCache — same Coil 2 cache key dance.
        // For videos this is mostly a no-op (the video bytes themselves aren't decoded by
        // Coil) but any thumbnail entry that hit the cache under the saved URI is purged
        // here too, and the notifyChange wakes MediaStore observers up immediately.
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

    private fun stamp(displayName: String, atMs: Long = System.currentTimeMillis()): String {
        val dotIdx = displayName.lastIndexOf('.')
        val base = if (dotIdx > 0) displayName.substring(0, dotIdx) else displayName
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
            .format(java.util.Date(atMs))
        return "${base}_edit_$ts.mp4"
    }
}
