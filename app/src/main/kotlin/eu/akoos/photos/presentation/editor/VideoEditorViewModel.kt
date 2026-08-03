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
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.entity.TimestampSanity
import coil.imageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
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
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.ProtonPhotosStorage
import eu.akoos.photos.util.VideoMetadataStripper
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
    /** How the editor was entered — drives the save dispatch (device copy / cloud upload). */
    val source: VideoEditorSource? = null,
    /** Latched after an [VideoEditorSource.External] save so the screen can show "Saved a copy". */
    val savedAsCopy: Boolean = false,
)

sealed class VideoSaveResult {
    data class Success(val uri: Uri?) : VideoSaveResult()
    data class Failed(val message: String) : VideoSaveResult()
}

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

/**
 * Name a saved edit takes: the source's base name, `_edit_`, the save instant, and always an `.mp4`
 * extension, because what comes out of the muxer is an MP4 whatever went in. The device copy and the
 * cloud copy of one save derive their names from the SAME instant, which is how
 * `ReconcileSyncStateUseCase.byNameAndDate` pairs them without a re-download. Pure so the shape is
 * verified in a test.
 */
internal fun stampedEditName(displayName: String, atMs: Long): String {
    val dotIdx = displayName.lastIndexOf('.')
    val base = if (dotIdx > 0) displayName.substring(0, dotIdx) else displayName
    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
        .format(java.util.Date(atMs))
    return "${base}_edit_$ts.mp4"
}

/**
 * Capture instant stamped into an edited video's mvhd. A backed-up video keeps its cloud sibling's
 * ORIGINAL capture time (seconds, promoted to ms) so the edit lands on the same day as the original
 * on both copies; a new or cloud-only edit has no sibling and falls back to the save instant.
 *
 * The sibling's time goes through [TimestampSanity.effectiveMs], the rule every other date site in
 * the app reads a cloud capture time with, because Drive says "no capture time" with 0 rather than
 * with nothing at all: a bare null check takes that 0 at face value and stamps the edit with the
 * epoch, which is both a wrong date and the far end of the timeline.
 */
internal fun editedVideoCaptureMs(counterpartCaptureTimeSeconds: Long?, editTimestampMs: Long): Long =
    TimestampSanity.effectiveMs(
        primaryMs = (counterpartCaptureTimeSeconds ?: 0L) * 1000L,
        fallbackMs = editTimestampMs,
    )

/**
 * Orientation hint the saved video carries: the source's own rotation plus the turns the user made,
 * wrapped into 0..359 (Kotlin's `%` keeps the sign, so a negative source rotation would otherwise
 * survive into the muxer and play the video upside down).
 */
internal fun normalizedRotation(sourceDegrees: Int, userDegrees: Int): Int =
    ((sourceDegrees + userDegrees) % 360 + 360) % 360

@HiltViewModel
class VideoEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: eu.akoos.photos.domain.repository.SyncStateRepository,
    // Reports the background edit-upload to the Activity monitor + avatar ring, the same surface the
    // photo editor's edit-upload uses. The upload itself is unchanged; this only tracks it.
    private val transferCenter: eu.akoos.photos.data.transfer.TransferCenter,
    // Application-lifetime scope for the cloud re-upload that outlives the editor: it must keep
    // running after save() returns and the screen navigates away (viewModelScope is cancelled then).
    @eu.akoos.photos.di.AppScope private val appScope: CoroutineScope,
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

    /**
     * Save the edit as a new file. A video edit never writes back over its source: the save sheet
     * offers copy only, so the device leg inserts a fresh MediaStore entry and the cloud leg uploads
     * a new linkId, leaving the original in place either way.
     */
    fun save() {
        val s = _state.value
        val sourceUri = s.sourceUri ?: return
        if (s.isSaving) return
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
            val finalRotation = normalizedRotation(sourceRotationDegrees, s.rotationDegrees)
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
            // The ORIGINAL capture time to stamp into the edited file's mvhd (read from the stable
            // [counterpart] snapshot), so a synced edit keeps the cloud sibling's date on both the
            // device copy and the cloud upload and the two pair by name+date after a reinstall. New
            // or cloud-only edits have no counterpart and fall back to the edit time.
            val captureTimestampMs = editedVideoCaptureMs(counterpart?.captureTime, editTimestampMs)
            val syncedTempFile: File? = if (isSynced) createTempMuxFile() else null
            // Set when the cloud upload is handed to [appScope], which then owns the temp file's
            // cleanup. The viewModelScope finally must NOT delete a handed-off file, else the
            // background upload reads a file that was pulled out from under it.
            var syncedTempHandedOff = false
            val saveResult: VideoSaveResult = try {
                // Cloud video edit: mux the .mp4 in cache HERE (viewModelScope, so the local
                // re-encode finishes before returning), then run the network upload in appScope
                // and return an optimistic Success so the editor closes immediately. A background
                // upload failure surfaces through the normal sync status, not here.
                if (cloudPhoto != null) {
                    val cloudTempFile = muxCloudEditToTemp(s, finalRotation, needsReencode)
                    // Stamp the cloud copy's mvhd with the same capture time uploadCloudEdit sends, so
                    // a later download (which reads DATE_TAKEN from the mvhd) restores the original
                    // date instead of the mux time.
                    eu.akoos.photos.util.Mp4CreationTime.stamp(cloudTempFile, editTimestampMs)
                    val userId = accountManager.getPrimaryUserId().first()
                    if (userId == null) {
                        cloudTempFile.delete()
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveResult = VideoSaveResult.Failed(context.getString(R.string.viewer_not_signed_in)),
                                saveProgress = null,
                                saveStage = VideoSaveStage.Idle,
                            )
                        }
                        return@launch
                    }
                    _state.update { it.copy(saveStage = VideoSaveStage.Uploading, saveProgress = null) }
                    appScope.launch {
                        // Track the edit-upload on the Activity monitor + avatar ring for its duration;
                        // the muxed temp file's URI is the row's video-frame thumbnail. finish() runs in
                        // the finally so the ring clears whether the upload succeeds, fails, or cancels.
                        val uploadUri = Uri.fromFile(cloudTempFile).toString()
                        val tid = transferCenter.start(TransferCenter.Kind.UPLOAD, total = 1, items = listOf(uploadUri))
                        try {
                            uploadCloudEdit(s, cloudPhoto, editTimestampMs, cloudTempFile, userId)
                            transferCenter.progress(tid, 1)
                            transferCenter.log(
                                TransferCenter.Kind.UPLOAD, count = 1,
                                name = context.getString(R.string.activity_hist_edited), uris = listOf(uploadUri),
                            )
                        } catch (t: Throwable) {
                            // appScope is long-lived; a cancellation here is scope teardown, not a
                            // save failure, so re-throw it rather than swallowing.
                            if (t is kotlinx.coroutines.CancellationException) throw t
                        } finally {
                            transferCenter.finish(tid)
                            cloudTempFile.delete()
                        }
                    }
                    // Optimistic: the editor closes now; the linkId create and album re-attach
                    // both run in the appScope block above.
                    _state.update {
                        it.copy(
                            isSaving = false,
                            saveResult = VideoSaveResult.Success(null),
                            saveProgress = null,
                            saveStage = VideoSaveStage.Idle,
                        )
                    }
                    return@launch
                }
                if (syncedTempFile != null) {
                    produceEditedTempFile(s, syncedTempFile, finalRotation, needsReencode)
                    // Stamp the cloud sibling's ORIGINAL capture time into the edited file's mvhd
                    // BEFORE it is written to MediaStore or uploaded. MediaStore derives DATE_TAKEN
                    // from the mvhd on scan (a post-insert DATE_TAKEN write is ignored), so without
                    // this the device copy shows today; the mux otherwise stamps the edit time.
                    // Done before the sha1 below so the device copy and the cloud upload share it.
                    eu.akoos.photos.util.Mp4CreationTime.stamp(syncedTempFile, captureTimestampMs)
                    // Honour "mirror strip to local": when the user asked to strip metadata AND mirror
                    // it onto the device file, strip the on-device edited copy in place too, so the
                    // local file carries no more than the cloud copy. No-op otherwise.
                    mirrorStripVideoLocal(syncedTempFile, captureTimestampMs)
                }
                // Bare sha1 of the finished edited bytes, computed ONCE. The device MediaStore
                // copy and the cloud upload are the SAME syncedTempFile, so this single digest
                // seeds the device sync_state row AND drives the cloud upload's ContentHash.
                // Reconcile later maps this bare sha1 to the cloud HMAC to re-pair the two as
                // Synced; a blank localHash here would strand the edit as a split device/cloud pair.
                val syncedLocalHash: String? = syncedTempFile?.let { sha1(it) }
                val result = if (syncedTempFile != null) {
                    saveLocalFromExistingFile(s, editTimestampMs, syncedTempFile)
                } else {
                    // Device-only: no cloud sibling carries this video's date, so the source itself is
                    // the only place the ORIGINAL capture instant lives, and the paths below stamp it
                    // into the edited bytes the way the synced branch stamps [captureTimestampMs].
                    // Read inside this branch so the cloud and synced saves never pay for it.
                    val deviceCaptureMs = deviceSourceCaptureMs(Uri.parse(sourceUri), editTimestampMs)
                    if (needsReencode) saveReencoded(
                        s, finalRotation, editTimestampMs, deviceCaptureMs,
                    ) else saveStreamCopy(
                        s, finalRotation, editTimestampMs, deviceCaptureMs,
                    )
                }
                val savedUri: Uri? = when (result) {
                    is VideoSaveResult.Success -> result.uri
                    is VideoSaveResult.Failed -> null
                }
                // Synced video path: push the SAME bytes that just landed locally up to
                // the cloud counterpart, but do NOT await the network upload here (that is the
                // freeze). Seed the UPLOADING placeholder inline (the MediaStore insert above
                // fired the OS-level content observer BackgroundSyncService listens on; without
                // this row SyncWorker would race the fanout and upload the same edited bytes a
                // second time), then run the upload in appScope so the editor returns immediately.
                // On success the row goes SYNCED; on ANY failure it drops to LOCAL_ONLY (never
                // left stuck at UPLOADING) so the normal SyncWorker retries it, no lost edit.
                if (syncedTempFile != null && counterpart != null && savedUri != null &&
                    result !is VideoSaveResult.Failed) {
                    val userId = accountManager.getPrimaryUserId().first()
                    if (userId != null) {
                        val savedUriStr = savedUri.toString()
                        val placeholderState = SyncState(
                            localUri = savedUriStr,
                            cloudFileId = null,
                            localHash = syncedLocalHash.orEmpty(),
                            cloudHash = null,
                            status = SyncStatus.UPLOADING,
                            lastSyncAttemptMs = System.currentTimeMillis(),
                            lastSyncSuccessMs = null,
                            backedUpAtMs = null,
                            sizeBytes = syncedTempFile.length(),
                        )
                        runCatching { syncStateRepo.upsert(placeholderState, userId) }
                        // appScope owns the temp file from here; clear the viewModelScope finally's
                        // claim so the background upload can still read it after save() returns.
                        syncedTempHandedOff = true
                        appScope.launch {
                            // Track the replacement upload on the Activity monitor + avatar ring for its
                            // duration; the saved device copy's URI is the row's video-frame thumbnail.
                            // finish() runs in the finally so the ring clears on success, failure, or cancel.
                            val tid = transferCenter.start(TransferCenter.Kind.UPLOAD, total = 1, items = listOf(savedUriStr))
                            try {
                                val newLinkId = uploadExistingFileToCloud(
                                    s, counterpart, editTimestampMs, syncedTempFile,
                                    syncedLocalHash.orEmpty(), userId,
                                )
                                transferCenter.progress(tid, 1)
                                transferCenter.log(
                                    TransferCenter.Kind.UPLOAD, count = 1,
                                    name = context.getString(R.string.activity_hist_edited), uris = listOf(savedUriStr),
                                )
                                runCatching {
                                    syncStateRepo.upsert(
                                        placeholderState.copy(
                                            cloudFileId = newLinkId,
                                            localHash = syncedLocalHash.orEmpty(),
                                            status = SyncStatus.SYNCED,
                                            lastSyncSuccessMs = System.currentTimeMillis(),
                                            backedUpAtMs = System.currentTimeMillis(),
                                        ),
                                        userId,
                                    )
                                }
                            } catch (t: Throwable) {
                                // Demote to LOCAL_ONLY so the background SyncWorker retries instead
                                // of leaving a stuck UPLOADING row. Re-throw cancellation so scope
                                // teardown is not swallowed.
                                if (t is kotlinx.coroutines.CancellationException) throw t
                                runCatching {
                                    syncStateRepo.upsert(
                                        placeholderState.copy(status = SyncStatus.LOCAL_ONLY),
                                        userId,
                                    )
                                }
                            } finally {
                                transferCenter.finish(tid)
                                syncedTempFile.delete()
                            }
                        }
                    }
                }
                // Defensive Coil invalidation — the saved URI may not actually be in the
                // image cache for videos (thumbnails live under MediaStore URIs that may
                // or may not match), but the call is a safe no-op when the key is absent
                // and unifies the post-save state with the photo path. Cloud-only edits
                // returned above, so anything here is a device-backed save with a real URI.
                if (savedUri != null) {
                    invalidateImageCache(savedUri)
                }
                result
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                VideoSaveResult.Failed(eu.akoos.photos.util.sanitizeErrorMessage(e.message ?: context.getString(R.string.editor_save_failed)))
            } finally {
                if (!syncedTempHandedOff) syncedTempFile?.delete()
            }
            val markCopied = s.source is VideoEditorSource.External && saveResult is VideoSaveResult.Success
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
     * a fresh entry under DCIM/Camera, source untouched.
     */
    private suspend fun saveLocalFromExistingFile(
        s: VideoEditorUiState,
        editTimestampMs: Long,
        tempFile: File,
    ): VideoSaveResult = withContext(Dispatchers.IO) {
        val uri = insertReencodedCopy(tempFile, s.displayName, s.mimeType, editTimestampMs)
        VideoSaveResult.Success(uri)
    }

    /**
     * Synced video cloud-fanout that uploads from a pre-built [tempFile] (no second
     * encode). Returns the new Drive linkId so the caller can pin the local URI's
     * SyncState row to it, preventing reconcile from racing and uploading a duplicate.
     */
    private suspend fun uploadExistingFileToCloud(
        s: VideoEditorUiState,
        cloud: CloudPhoto,
        editTimestampMs: Long,
        tempFile: File,
        // Bare sha1 of [tempFile], computed once by the caller and reused for the device row too.
        contentSha1: String,
        userId: me.proton.core.domain.entity.UserId,
    ): String = withContext(Dispatchers.IO) {
        val displayName = stampedEditName(cloud.displayName, editTimestampMs)
        val outMime = if (s.mimeType.startsWith("video/")) s.mimeType else "video/mp4"
        // Honour strip-on-upload for the cloud copy only: this may produce a separate stripped file
        // (GPS atom gone, timestamp floored to now) while [tempFile] stays intact for the device copy.
        val (uploadFile, captureMs) = stripCopyForCloudUpload(tempFile, cloud.captureTime * 1000L)
        val uploadUri = Uri.fromFile(uploadFile).toString()
        val item = LocalMediaItem(
            uri = uploadUri,
            // Keep the ORIGINAL capture time on the edited cloud copy so it matches the device copy's
            // DATE_TAKEN and the mvhd the mux preserves. When the timestamp is stripped, [captureMs] is
            // floored to now to match the stripped bytes.
            dateTaken = captureMs,
            displayName = displayName,
            mimeType = outMime,
            sizeBytes = uploadFile.length(),
            bucketName = null,
            width = s.sourceWidth,
            height = s.sourceHeight,
            duration = (s.trimEndMs - s.trimStartMs).coerceAtLeast(0L),
        )
        val hash = sha1(uploadFile)
        val newLinkId = try {
            cloudRepo.uploadFile(userId, item, hash, uploadUri) { phase, doneBytes, totalBytes ->
                // Distinguish the encrypt and CDN-PUT phases on the sheet so the user sees
                // two distinct progress bars (Encrypting to Uploading) instead of a single
                // mystery "0% sit then 100% race" cycle that happened to share a label.
                val frac = (doneBytes.toFloat() / totalBytes.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                val newStage = when (phase) {
                    eu.akoos.photos.data.repository.drive.UploadPhase.Encrypting -> VideoSaveStage.Encrypting
                    eu.akoos.photos.data.repository.drive.UploadPhase.Uploading -> VideoSaveStage.Uploading
                }
                _state.update { it.copy(saveProgress = frac, saveStage = newStage) }
            }
        } finally {
            // The stripped copy is throwaway; the caller-owned [tempFile] must survive for the device row.
            if (uploadFile !== tempFile) uploadFile.delete()
        }
        sourceCloudAlbumLinkId?.let { albumId ->
            runCatching { cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)) }
        }
        newLinkId
    }

    /**
     * Cloud video save, local leg: mux the edit into a fresh temp .mp4 (re-encode or
     * stream-copy) and return it. Runs in viewModelScope so the local re-encode finishes
     * before [save] returns; the caller hands the file to [uploadCloudEdit] on appScope and
     * owns its cleanup.
     */
    private suspend fun muxCloudEditToTemp(
        s: VideoEditorUiState,
        finalRotation: Int,
        needsReencode: Boolean,
    ): File = withContext(Dispatchers.IO) {
        val sourceUriStr = s.sourceUri ?: error("No cached video URI")
        val sourceUri = Uri.parse(sourceUriStr)
        val tempFile = createTempMuxFile()
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
        tempFile
    }

    /**
     * Cloud video save, network leg: upload the pre-muxed [tempFile] as a new Drive linkId and
     * re-attach it to the source album (best-effort), leaving the original in place. Runs in
     * appScope so it survives the editor closing. The caller owns the temp file's cleanup.
     */
    private suspend fun uploadCloudEdit(
        s: VideoEditorUiState,
        cloudPhoto: CloudPhoto,
        editTimestampMs: Long,
        tempFile: File,
        userId: me.proton.core.domain.entity.UserId,
    ): String = withContext(Dispatchers.IO) {
        val displayName = stampedEditName(cloudPhoto.displayName, editTimestampMs)
        val outMime = if (s.mimeType.startsWith("video/")) s.mimeType else "video/mp4"
        // Honour strip-on-upload for the cloud copy only: may produce a separate stripped file while
        // [tempFile] stays intact (the caller owns its cleanup).
        val (uploadFile, captureMs) = stripCopyForCloudUpload(tempFile, editTimestampMs)
        val uploadUri = Uri.fromFile(uploadFile).toString()
        val item = LocalMediaItem(
            uri = uploadUri,
            dateTaken = captureMs,
            displayName = displayName,
            mimeType = outMime,
            sizeBytes = uploadFile.length(),
            bucketName = null,
            width = s.sourceWidth,
            height = s.sourceHeight,
            duration = (s.trimEndMs - s.trimStartMs).coerceAtLeast(0L),
        )
        val hash = sha1(uploadFile)
        val newLinkId = try {
            cloudRepo.uploadFile(userId, item, hash, uploadUri) { _, _, _ ->
                // Runs in the background after the editor closed, so there is no sheet to drive.
            }
        } finally {
            // The stripped copy is throwaway; the caller-owned [tempFile] must survive for its own cleanup.
            if (uploadFile !== tempFile) uploadFile.delete()
        }
        sourceCloudAlbumLinkId?.let { albumId ->
            runCatching { cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)) }
        }
        newLinkId
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
        finalRotation: Int,
        editTimestampMs: Long,
        captureTimestampMs: Long,
    ): VideoSaveResult {
        val sourceUriStr = s.sourceUri ?: error("No source URI")
        // Stream-copy supports the "remove audio track" toggle by simply not registering
        // the audio MediaFormat on the muxer. (Overlay audio always forces the re-encode
        // path, so we only need to honour mute here.)
        val stripAudio = s.originalAudioGain <= 0.001f
        val uri = insertLocalCopy(
            sourceUri = Uri.parse(sourceUriStr),
            displayName = s.displayName,
            mimeType = s.mimeType,
            trimStartMs = s.trimStartMs,
            trimEndMs = s.trimEndMs,
            orientationDegrees = finalRotation,
            captureTimestampMs = captureTimestampMs,
            stripAudio = stripAudio,
            editTimestampMs = editTimestampMs,
        )
        return VideoSaveResult.Success(uri)
    }

    /**
     * Re-encode save path. Runs the full decode→GL→encode pipeline so a crop or audio
     * swap can take effect. The encoder's input frame is the cropped region, so the
     * output's width/height is the cropped dimensions and no further muxer-orientation
     * hint is needed — pixel rotation is burnt in by the GL matrix.
     */
    private suspend fun saveReencoded(
        s: VideoEditorUiState,
        finalRotation: Int,
        editTimestampMs: Long,
        captureTimestampMs: Long,
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
            // The encoder stamps its own run time into the output's mvhd, which is the box MediaStore
            // derives DATE_TAKEN from on scan, so the source's capture instant goes back in before the
            // bytes leave the cache.
            eu.akoos.photos.util.Mp4CreationTime.stamp(tempFile, captureTimestampMs)
            VideoSaveResult.Success(
                insertReencodedCopy(tempFile, s.displayName, s.mimeType, editTimestampMs),
            )
        } finally {
            tempFile.delete()
        }
    }

    private fun insertReencodedCopy(
        muxFile: File,
        displayName: String,
        mimeType: String,
        editTimestampMs: Long = System.currentTimeMillis(),
    ): Uri? {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val outName = stampedEditName(displayName, editTimestampMs)
        val outMime = if (mimeType.startsWith("video/")) mimeType else "video/mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outName)
            put(MediaStore.Video.Media.MIME_TYPE, outMime)
            // DATE_TAKEN is a seed only: the media scanner overrides it from the file's mvhd, which
            // every caller stamps with the original capture time (the cloud sibling's for a synced
            // edit, the source's own for a device-only one), so the device copy keeps the original
            // date. DATE_MODIFIED is in seconds (the MediaStore legacy unit); DATE_TAKEN is in ms.
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
     * Writes the trimmed range into a fresh MediaStore Video entry under DCIM/Camera.
     * The IS_PENDING dance keeps the entry hidden from other apps until the bytes are flushed.
     */
    private suspend fun insertLocalCopy(
        sourceUri: Uri,
        displayName: String,
        mimeType: String,
        trimStartMs: Long,
        trimEndMs: Long,
        orientationDegrees: Int,
        captureTimestampMs: Long,
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
            // The muxer writes its own run time into the output's mvhd, and the media scanner
            // derives DATE_TAKEN from that box (overriding the column seeded below), so the source's
            // capture instant goes in here for the copy to land beside its original.
            eu.akoos.photos.util.Mp4CreationTime.stamp(tempFile, captureTimestampMs)
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val outName = stampedEditName(displayName, editTimestampMs)
            val outMime = if (mimeType.startsWith("video/")) mimeType else "video/mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outName)
                put(MediaStore.Video.Media.MIME_TYPE, outMime)
                // DATE_TAKEN is a seed only: the media scanner overrides it from the mvhd stamped
                // above, so the copy ends up on the source's capture date whichever wins. DATE_MODIFIED
                // is in seconds (the MediaStore legacy unit) and stays the instant the file was made.
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
     * The capture instant a device-only edit inherits from [sourceUri]: the date MediaStore reports
     * for it (which is what the grid sorts the original by), then the one the file's own container
     * records, then [fallbackMs] when neither is usable. The container is the second reading rather
     * than the first because the column is what every other surface shows, and it is read at all
     * because a video whose column never took a date still carries its capture time in the mvhd.
     * Never throws: an unreadable source costs the inherited date, not the save.
     */
    private fun deviceSourceCaptureMs(sourceUri: Uri, fallbackMs: Long): Long =
        queryDateTakenMs(sourceUri) ?: mvhdCaptureMs(sourceUri) ?: fallbackMs

    /** MediaStore's DATE_TAKEN (ms) for [uri], or null when the row or the column carries none. */
    private fun queryDateTakenMs(uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Video.Media.DATE_TAKEN), null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null
            else cursor.getLong(0).takeIf { it > 0L }
        }
    }.getOrNull()

    /** The capture instant [uri]'s container records in its mvhd, or null when it records none. Read
     *  through the descriptor the resolver hands out, because under scoped storage that is the only
     *  route to a MediaStore item's bytes. */
    private fun mvhdCaptureMs(uri: Uri): Long? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use {
            eu.akoos.photos.util.Mp4CreationTime.read(it.fileDescriptor)
        }
    }.getOrNull()

    /**
     * When strip-on-upload is enabled, produce a separate cloud-upload copy of a muxed video with the
     * user's privacy settings applied: the GPS location atom removed (stripGps) and, for stripTimestamp,
     * the mvhd stamped with "now" so the bytes carry no real capture time. The input [tempFile] is left
     * intact (the device copy keeps full metadata, mirroring the image editor). Returns the file to
     * upload and the capture time to send as the cloud photo.captureTime (floored to now when stripping
     * the timestamp). When stripping is off, returns [tempFile] and [originalCaptureMs] unchanged.
     */
    private suspend fun stripCopyForCloudUpload(tempFile: File, originalCaptureMs: Long): Pair<File, Long> =
        withContext(Dispatchers.IO) {
            val prefs = runCatching { context.settingsDataStore.data.first() }.getOrNull()
            val stripOnUpload = prefs?.get(SettingsKeys.STRIP_ON_UPLOAD) ?: false
            val stripGps = stripOnUpload && (prefs?.get(SettingsKeys.STRIP_GPS) ?: false)
            val stripTimestamp = stripOnUpload && (prefs?.get(SettingsKeys.STRIP_TIMESTAMP) ?: false)
            if (!stripGps && !stripTimestamp) return@withContext tempFile to originalCaptureMs
            val captureMs = if (stripTimestamp) System.currentTimeMillis() else originalCaptureMs
            val out = createTempMuxFile()
            val remuxed = stripGps && VideoMetadataStripper.remuxWithoutLocation(
                context, Uri.fromFile(tempFile).toString(), out,
            )
            if (!remuxed) {
                // No GPS strip requested, or the remux failed: copy the bytes so we can stamp without
                // touching the device copy.
                tempFile.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            // Stamp the mvhd AFTER any remux (MediaMuxer resets the creation time): the ORIGINAL when only
            // GPS was stripped, or "now" when the timestamp is stripped.
            eu.akoos.photos.util.Mp4CreationTime.stamp(out, captureMs)
            out to captureMs
        }

    /**
     * When the user enabled strip-on-upload AND "mirror to local", strip the on-device edited copy in
     * place so it carries no more metadata than the cloud copy: remove the GPS location atom for
     * stripGps and re-stamp the mvhd (floored to now for stripTimestamp, else [originalCaptureMs],
     * since a GPS remux resets the mvhd). Mirrors the backup path's mirror-strip. No-op unless both
     * strip-on-upload and mirror-to-local are on with at least one strip flag.
     */
    private suspend fun mirrorStripVideoLocal(file: File, originalCaptureMs: Long) =
        withContext(Dispatchers.IO) {
            val prefs = runCatching { context.settingsDataStore.data.first() }.getOrNull()
                ?: return@withContext
            val stripOnUpload = prefs[SettingsKeys.STRIP_ON_UPLOAD] ?: false
            val mirror = prefs[SettingsKeys.MIRROR_STRIP_TO_LOCAL] ?: false
            if (!stripOnUpload || !mirror) return@withContext
            val stripGps = prefs[SettingsKeys.STRIP_GPS] ?: false
            val stripTimestamp = prefs[SettingsKeys.STRIP_TIMESTAMP] ?: false
            if (!stripGps && !stripTimestamp) return@withContext
            if (stripGps) {
                val tmp = createTempMuxFile()
                val ok = runCatching {
                    VideoMetadataStripper.remuxWithoutLocation(context, Uri.fromFile(file).toString(), tmp)
                }.getOrDefault(false)
                if (ok) {
                    runCatching { tmp.inputStream().use { i -> file.outputStream().use { o -> i.copyTo(o) } } }
                }
                tmp.delete()
            }
            eu.akoos.photos.util.Mp4CreationTime.stamp(
                file, if (stripTimestamp) System.currentTimeMillis() else originalCaptureMs,
            )
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
}
