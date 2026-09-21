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

package eu.akoos.photos.presentation.gifmaker

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.service.GifExportService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/** Longest GIF window the exporter samples; a drag beyond this pins the span rather than growing it. */
private const val GIF_MAX_MS = 10_000L

/** Shortest GIF window, so the two range handles keep a usable gap. */
private const val GIF_MIN_MS = 500L

/** Zoom bounds for the crop: 1 is the whole fitted frame, [GIF_MAX_ZOOM] is the tightest crop. */
private const val GIF_MIN_ZOOM = 1f
private const val GIF_MAX_ZOOM = 6f

/**
 * Longest-edge tiers (px) for the GIF canvas, chosen at save time. A GIF is capped at 256 colours per
 * frame whatever the size, so a larger edge buys sharpness, not colour depth, at a steep file-size cost:
 * [GIF_EDGE_MAX] can run to tens of megabytes for a full 10s clip.
 */
const val GIF_EDGE_STANDARD = 480
const val GIF_EDGE_HIGH = 720
const val GIF_EDGE_MAX = 1080

/**
 * One-shot failure surfaced before the GIF maker can open its editing UI: a cloud source whose download
 * failed, or no account to fetch a cloud source at all. The screen shows it as a toast and leaves. The save
 * itself runs in the background off the screen, so its progress, completion, and any failure surface through
 * the transfer monitor (avatar ring + Activity screen) and the export service's own notification, not here.
 */
sealed class GifExportResult {
    data class Failed(val message: String) : GifExportResult()
}

/**
 * State of the GIF maker screen. [gifStartMs]..[gifEndMs] is the window turned into a GIF, seeded on
 * [load] and always kept ordered and within [GIF_MIN_MS]..[GIF_MAX_MS] by [setGifRange]. [aspect] plus
 * [cropZoom]/[panX]/[panY] frame every exported pixel: the same crop the preview shows. [exportResult] is a
 * one-shot pre-export failure (see [GifExportResult]) the screen shows then clears with
 * [consumeExportResult]; the encode's own progress and completion surface through the transfer monitor.
 */
data class GifMakerUiState(
    val videoUri: String? = null,
    val displayName: String = "video",
    val durationMs: Long = 0L,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val gifStartMs: Long = 0L,
    val gifEndMs: Long = 0L,
    val aspect: GifAspect = GifAspect.ORIGINAL,
    val cropZoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    /** True while a cloud-only source video downloads before it can be turned into a GIF. */
    val isDownloading: Boolean = false,
    /** 0..1 download progress for a cloud source, or null before the total is known / for a local source. */
    val downloadProgress: Float? = null,
    val exportResult: GifExportResult? = null,
) {
    /** Length of the selected window, never negative. */
    val selectedMs: Long get() = (gifEndMs - gifStartMs).coerceAtLeast(0L)
}

/**
 * Backs the standalone GIF maker. Reads the source video's duration and display size once, seeds the trim
 * range and holds the aspect/crop the preview shows. [save] pops the screen at once and hands the encode +
 * save to [GifExportService], a foreground service that outlives the screen and survives the app being
 * backgrounded or swiped from Recents; the service tracks the work on the transfer monitor (avatar ring +
 * Activity screen) and shows a foreground progress notification. Routing is decided here and carried out
 * there: a cloud-sourced GIF is uploaded to the Proton cloud (a new linkId); a device-sourced GIF is written
 * to the local gallery, which needs no account (guest mode).
 */
@HiltViewModel
class GifMakerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    // Cloud entry point mirrors the video editor: the same account gate and the same repo download
    // turn a cloud-only video into an on-disk file the local GIF pipeline can read.
    private val accountManager: AccountManager,
    private val cloudRepo: DrivePhotoRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GifMakerUiState())
    val state: StateFlow<GifMakerUiState> = _state.asStateFlow()

    // One-shot "the save has started, leave the screen now" signal. Buffered so an emit from [save] is
    // never dropped if the screen is mid-recomposition when Save is tapped.
    private val _leave = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val leave: SharedFlow<Unit> = _leave.asSharedFlow()

    private var loaded = false

    // Non-null when the source is a cloud video: the finished GIF is uploaded to the Proton cloud (a new
    // linkId) instead of the device gallery, mirroring how the photo and video editors send a cloud-item
    // edit back to the cloud. Set by [loadCloud], read by [save].
    private var sourceCloudPhoto: CloudPhoto? = null

    // One-shot guard: [save] pops the screen immediately and hands the work to the export service, so a fast
    // double-tap on Save could start two exports. The view model is destroyed on the pop, so a plain flag suffices.
    private var saveStarted = false

    /** Read the source's duration + display dimensions once, and seed the range to the first 10 seconds. */
    fun load(uriStr: String) {
        if (loaded) return
        loaded = true
        _state.update { it.copy(videoUri = uriStr) }
        viewModelScope.launch(Dispatchers.IO) {
            val uri = Uri.parse(uriStr)
            val retriever = MediaMetadataRetriever()
            val meta = try {
                retriever.setDataSource(context, uri)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val rot = (((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0) % 360) + 360) % 360
                // Display dims: a sideways rotation swaps width and height, so the canvas matches what
                // the preview shows.
                val dispW = if (rot % 180 != 0) rawH else rawW
                val dispH = if (rot % 180 != 0) rawW else rawH
                Triple(dur, dispW, dispH)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
            val name = resolveDisplayName(uri)
            _state.update { s ->
                if (meta == null) {
                    s.copy(displayName = name)
                } else {
                    val (dur, w, h) = meta
                    s.copy(
                        durationMs = dur,
                        sourceWidth = w,
                        sourceHeight = h,
                        displayName = name,
                        gifStartMs = 0L,
                        gifEndMs = minOf(dur, GIF_MAX_MS),
                    )
                }
            }
        }
    }

    /**
     * Cloud entry point, mirroring the video editor's [loadCloud]. Account-gated: with no primary user
     * the cloud video cannot be fetched. It downloads (and decrypts) the full-res video into the shared
     * `fullres` cache via [DrivePhotoRepository.downloadFullResPhoto] exactly the way the editor does,
     * feeding the download's byte progress into [GifMakerUiState.downloadProgress] so the screen shows a
     * percentage rather than an opaque spinner, then continues like [load]: reads the cached file's
     * duration + display size and seeds the range. The downloaded file is a shared cache hit whose
     * eviction the cache pruner owns, so it is never deleted here.
     */
    fun loadCloud(photo: CloudPhoto) {
        if (loaded) return
        loaded = true
        sourceCloudPhoto = photo
        _state.update { it.copy(displayName = photo.displayName, isDownloading = true, downloadProgress = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                _state.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        exportResult = GifExportResult.Failed(context.getString(R.string.viewer_not_signed_in)),
                    )
                }
                return@launch
            }
            val file = try {
                cloudRepo.downloadFullResPhoto(userId, photo) { done, total ->
                    _state.update {
                        it.copy(downloadProgress = if (total > 0L) (done.toFloat() / total).coerceIn(0f, 1f) else null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        exportResult = GifExportResult.Failed(context.getString(R.string.editor_cloud_video_download_failed)),
                    )
                }
                return@launch
            }
            val uri = Uri.fromFile(file)
            val retriever = MediaMetadataRetriever()
            val meta = try {
                retriever.setDataSource(context, uri)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val rot = (((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0) % 360) + 360) % 360
                // Display dims: a sideways rotation swaps width and height, matching [load].
                val dispW = if (rot % 180 != 0) rawH else rawW
                val dispH = if (rot % 180 != 0) rawW else rawH
                Triple(dur, dispW, dispH)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
            _state.update { s ->
                if (meta == null) {
                    s.copy(videoUri = uri.toString(), isDownloading = false, downloadProgress = null)
                } else {
                    val (dur, w, h) = meta
                    s.copy(
                        videoUri = uri.toString(),
                        durationMs = dur,
                        sourceWidth = w,
                        sourceHeight = h,
                        gifStartMs = 0L,
                        gifEndMs = minOf(dur, GIF_MAX_MS),
                        isDownloading = false,
                        downloadProgress = null,
                    )
                }
            }
        }
    }

    /**
     * Clamps the GIF window and caps its span to [GIF_MAX_MS] (10s) with a [GIF_MIN_MS] floor, all within
     * [0, duration]. Dragging the far handle more than 10s from the near one pins the span at 10s rather
     * than growing it, so the moving handle stops instead of jumping.
     */
    fun setGifRange(start: Long, end: Long) {
        _state.update { s ->
            val duration = s.durationMs.coerceAtLeast(0L)
            // Clamp each handle against the OTHER side's current value: the start sits no closer than
            // GIF_MIN_MS below the end and no further than GIF_MAX_MS below it; the end mirrors that above
            // the start, also bounded by the clip duration. Both ranges stay ordered (min <= max) so
            // coerceIn never sees an empty range.
            val clampedStart = start.coerceIn(
                (s.gifEndMs - GIF_MAX_MS).coerceAtLeast(0L),
                (s.gifEndMs - GIF_MIN_MS).coerceAtLeast(0L),
            )
            val clampedEnd = end.coerceIn(
                (s.gifStartMs + GIF_MIN_MS).coerceAtMost(duration),
                minOf(s.gifStartMs + GIF_MAX_MS, duration),
            )
            val newStart = if (start == s.gifStartMs) s.gifStartMs else clampedStart
            val newEnd = if (end == s.gifEndMs) s.gifEndMs else clampedEnd
            s.copy(gifStartMs = newStart, gifEndMs = newEnd)
        }
    }

    /** Sets the export aspect. Switching aspect resets the crop, since the framing is defined per aspect. */
    fun setAspect(aspect: GifAspect) {
        _state.update { s ->
            if (s.aspect == aspect) s
            else s.copy(aspect = aspect, cropZoom = 1f, panX = 0f, panY = 0f)
        }
    }

    /**
     * Folds a pinch/drag gesture into the crop: [zoomFactor] multiplies the current zoom (clamped to
     * [GIF_MIN_ZOOM]..[GIF_MAX_ZOOM]) and the pan fractions are added to the normalised pan (clamped to
     * -1..1, the range [gifFraming] treats as valid; the helper scales it by the zoom's overshoot so a
     * gesture can never pull a source edge inside the frame).
     */
    fun applyCropGesture(zoomFactor: Float, panDxFraction: Float, panDyFraction: Float) {
        _state.update { s ->
            val newZoom = (s.cropZoom * zoomFactor).coerceIn(GIF_MIN_ZOOM, GIF_MAX_ZOOM)
            val newPanX = (s.panX + panDxFraction).coerceIn(-1f, 1f)
            val newPanY = (s.panY + panDyFraction).coerceIn(-1f, 1f)
            s.copy(cropZoom = newZoom, panX = newPanX, panY = newPanY)
        }
    }

    /** Returns the crop to the full fitted frame with no pan. */
    fun resetCrop() {
        _state.update { it.copy(cropZoom = 1f, panX = 0f, panY = 0f) }
    }

    /** Clears the one-shot export result once the screen has shown its feedback. */
    fun consumeExportResult() {
        _state.update { it.copy(exportResult = null) }
    }

    /**
     * Saves the current selection as a GIF and pops the screen at once. The encode + save is handed to
     * [GifExportService], a foreground service that outlives this view model and survives the app being
     * backgrounded or swiped from Recents; it tracks the work on the transfer monitor (avatar ring + Activity
     * screen), shows a foreground progress notification, and surfaces a failure as a dismissible notice.
     * Routing is decided here: a cloud source is uploaded to the Proton cloud (a new linkId) so it lands in the
     * timeline with the account's other photos; a device source is written to the local gallery (guest-friendly,
     * no account). [saveStarted] guards a fast double-tap, since the view model is destroyed on the pop.
     */
    fun save(maxEdgePx: Int) {
        val s = _state.value
        if (saveStarted) return
        val sourceUriStr = s.videoUri ?: return
        saveStarted = true
        val startMs = s.gifStartMs.coerceAtLeast(0L)
        val endMs = s.gifEndMs.coerceIn(startMs, s.durationMs.coerceAtLeast(startMs))
        val displayName = s.displayName
        val aspect = s.aspect
        val zoom = s.cropZoom
        val panX = s.panX
        val panY = s.panY
        val cloud = sourceCloudPhoto
        // The saved GIF inherits the source's capture time so it sorts next to the original, cloud or local.
        val dateTakenMs = cloud?.captureTimeMs ?: queryLocalDateTakenMs(sourceUriStr) ?: System.currentTimeMillis()
        // A brief, non-blocking hint that the save continues after the screen closes; the foreground service's
        // notification and the avatar ring carry the rest. save() is a UI callback, so this shows on the main thread.
        Toast.makeText(context, context.getString(R.string.gif_maker_progress), Toast.LENGTH_SHORT).show()
        GifExportService.start(
            context,
            sourceUri = sourceUriStr,
            startMs = startMs,
            endMs = endMs,
            aspect = aspect,
            zoom = zoom,
            panX = panX,
            panY = panY,
            maxEdgePx = maxEdgePx,
            isCloud = cloud != null,
            dateTakenMs = dateTakenMs,
            displayName = displayName,
        )
        _leave.tryEmit(Unit)
    }

    /** The source's display name for the saved GIF's base name, falling back to the last path segment. */
    private fun resolveDisplayName(uri: Uri): String {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
        return fromResolver ?: uri.lastPathSegment ?: "video"
    }

    /**
     * The device source video's DATE_TAKEN in millis, so a device-sourced GIF sorts next to the original in
     * the gallery. Null when unknown (a file uri, or a source with no date), leaving the caller to fall back.
     */
    private fun queryLocalDateTakenMs(uriStr: String): Long? = runCatching {
        context.contentResolver.query(
            Uri.parse(uriStr), arrayOf(MediaStore.Video.Media.DATE_TAKEN), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else null
            } else {
                null
            }
        }
    }.getOrNull()
}
