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
import android.graphics.Rect
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
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
import eu.akoos.photos.presentation.common.extractFilmstripInto
import eu.akoos.photos.util.ProtonPhotosStorage
import eu.akoos.photos.util.VideoMetadataStripper
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject

private const val TAG = "VideoEditorViewModel"

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
    /** The clips on the video track, in PLAY order. Empty = one full-length clip (the common, never-edited
     *  case). A split inserts the tail after its origin, a reorder moves one, a removed one greys out and
     *  is skipped; the export writes the non-removed clips in this order. */
    val clips: List<VideoClip> = emptyList(),
    /** Every source video behind the timeline, the primary ([PRIMARY_SOURCE_ID]) always first; the "+" on
     *  the cut track appends more. A single-element list is the common single-source case, and every
     *  existing edit path treats it exactly as before. */
    val sources: List<VideoSource> = emptyList(),
    val rotationDegrees: Int = 0, // 0, 90, 180, 270 — additive to source rotation metadata
    /** Crop in source-video pixels (null = full frame). Set → forces the re-encode save path. */
    val cropRect: Rect? = null,
    /** Colour filter applied to the whole clip. Non-[VideoFilter.None] forces the re-encode save path. */
    val filterPreset: VideoFilter = VideoFilter.None,
    /** Colour/light adjustments, each an Int in -100..100 (0 = no change). Any non-zero value forces the
     *  re-encode save path, the same as a filter. Composed with [filterPreset] into one effective matrix
     *  by [effectiveColorMatrix4x5]. */
    val adjBrightness: Int = 0,
    val adjExposure: Int = 0,
    val adjContrast: Int = 0,
    val adjHighlights: Int = 0,
    val adjShadows: Int = 0,
    val adjSaturation: Int = 0,
    val adjTemperature: Int = 0,
    val adjTone: Int = 0,
    val adjFade: Int = 0,
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
    /** The music's start position on the edited timeline: 0 plays it from the beginning, a positive value
     *  delays it so it begins that many ms into the edited video. The music block is then
     *  [audioOffsetMs, audioOffsetMs + (audioTrimEndMs - audioTrimStartMs)] capped to the edited length.
     *  Ignored when [audioOverlayUri] is null. */
    val audioOffsetMs: Long = 0L,
    /** Source audio gain [0..1]: 0 drops the original, 1 keeps it, between attenuates. */
    val originalAudioGain: Float = 1.0f,
    /** Overlay music gain [0..1], ignored when [audioOverlayUri] is null. Both > 0 → the two PCM streams mix. */
    val musicAudioGain: Float = 1.0f,
    /** True when there is an earlier edit state to return to, so the toolbar can offer Undo. */
    val canUndo: Boolean = false,
    /** How many edits can be undone, for the Undo history picker. */
    val undoDepth: Int = 0,
    /** Operation-name labels (string resources) of the undoable edits, most recent first, capped at the
     *  last 10. Index i undoes i+1 steps. Drives the history sheet so each row names the edit, not "Undo N". */
    val undoHistory: List<Int> = emptyList(),
    /** True when an undone edit can be re-applied (the toolbar Redo button). Cleared by any fresh edit. */
    val canRedo: Boolean = false,
    val isSaving: Boolean = false,
    val isLoading: Boolean = true,
    val saveResult: VideoSaveResult? = null,
    val errorMessage: String? = null,
    /** 0..1 progress during a re-encode save; null for stream-copy (fast enough for a spinner). */
    val saveProgress: Float? = null,
    /** True while a cloud video picked via the "+" is downloading to cache before it joins the timeline. */
    val addingSource: Boolean = false,
    /** One-shot transient feedback (source cap reached, an add that could not complete): shown as a toast
     *  then cleared, never the persistent inline error that a save failure uses. */
    val userMessage: String? = null,
    /** Save phase, so the sheet can label the local re-encode vs the progress-less cloud upload. */
    val saveStage: VideoSaveStage = VideoSaveStage.Idle,
    /** How the editor was entered — drives the save dispatch (device copy / cloud upload). */
    val source: VideoEditorSource? = null,
    /** Latched after an [VideoEditorSource.External] save so the screen can show "Saved a copy". */
    val savedAsCopy: Boolean = false,
    /** One-shot result of a still-frame grab, surfaced by the screen as a toast then consumed. */
    val frameGrabResult: FrameGrabResult? = null,
)

/** True once the "+" has appended a second video: the timeline spans more than one source. Drives the
 *  multi-source preview playlist and (until the concat export lands) the guarded save path. */
val VideoEditorUiState.isMultiSource: Boolean get() = sources.size > 1

/** True when the frame's colour is changed at all: a non-identity filter preset OR any non-zero
 *  adjustment. Gates the GL/re-encode save path so a pure-adjustment edit still bakes its pixels. */
fun VideoEditorUiState.hasColorEdits(): Boolean =
    !filterPreset.isIdentity ||
        adjBrightness != 0 || adjExposure != 0 || adjContrast != 0 || adjHighlights != 0 ||
        adjShadows != 0 || adjSaturation != 0 || adjTemperature != 0 || adjTone != 0 || adjFade != 0

/** The filter preset and the nine adjustments composed into one 4x5 Android colour matrix (the preset
 *  applied first, the adjustments after), or null when neither changes the colour. Feeds the live preview
 *  as a ColorMatrix. */
fun VideoEditorUiState.effectiveColorMatrix4x5(): FloatArray? =
    combineColorMatrices4x5(
        filterPreset.matrix4x5(),
        colorAdjustmentMatrix(
            adjBrightness, adjExposure, adjContrast, adjHighlights, adjShadows,
            adjSaturation, adjTemperature, adjTone, adjFade,
        ),
    )

/** The same effective colour transform as [effectiveColorMatrix4x5], converted to the 4x4 column-major
 *  form the GL re-encode shader and Media3's RgbMatrix consume, or null when there is nothing to apply. */
fun VideoEditorUiState.effectiveColorMatrix4x4(): FloatArray? =
    effectiveColorMatrix4x5()?.let { colorMatrix4x5To4x4ColumnMajor(it) }

sealed class VideoSaveResult {
    data class Success(val uri: Uri?) : VideoSaveResult()
    data class Failed(val message: String) : VideoSaveResult()
}

/** One-shot outcome of grabbing the current frame as a JPEG. */
sealed class FrameGrabResult {
    data class Success(val uri: Uri?) : FrameGrabResult()
    data class Failed(val message: String) : FrameGrabResult()
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

/**
 * One kept sub-range of the source timeline, in milliseconds. The editor holds an ordered list of
 * these: a split carves one into two adjacent ranges, a remove drops one and leaves a gap, and the
 * lossless export writes them back to back into a single continuous clip.
 */
data class VideoSegment(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Shortest kept range the editor allows, also the minimum room a split must leave on each side, so a
 * stream-copy window always spans at least one sync sample and a chip stays tappable.
 */
internal const val MIN_SEGMENT_MS = 200L

/**
 * Merge ranges that touch or overlap into one. Used by the audio-silence maths and by the export to
 * collapse a run of clips that are back-to-back in the source into one lossless window. Pure.
 */
internal fun coalesceSegments(segments: List<VideoSegment>): List<VideoSegment> {
    if (segments.size <= 1) return segments
    val sorted = segments.sortedBy { it.startMs }
    val out = ArrayList<VideoSegment>(sorted.size)
    var current = sorted.first()
    for (i in 1 until sorted.size) {
        val next = sorted[i]
        current = if (next.startMs <= current.endMs) {
            VideoSegment(current.startMs, maxOf(current.endMs, next.endMs))
        } else {
            out.add(current)
            next
        }
    }
    out.add(current)
    return out
}

/** The video the editor opened. The "+" on the cut track appends more sources, each with a fresh id. */
const val PRIMARY_SOURCE_ID: Int = 0

/** Most videos the timeline may combine at once (the primary plus what "+" appends). The concat holds
 *  every source open and writes their sum to cache, so this bounds the worst-case memory and disk. */
internal const val MAX_VIDEO_SOURCES: Int = 10

/** Free-space headroom the multi-source concat preflight keeps beyond the summed source bytes, so a
 *  combine never fills the cache volume to the brim (150 MB). */
private const val LOW_SPACE_MARGIN_BYTES: Long = 150L * 1024L * 1024L

/**
 * One source video behind the clip track. The editor starts with just the primary ([PRIMARY_SOURCE_ID]);
 * the "+" appends more. A clip's [VideoClip.startMs]/[endMs] are ms into ITS source's own timeline, so two
 * clips from different sources each measure from their own zero. [width]/[height] are post-rotation
 * effective dims, [rotationDegrees] the source's own rotation tag — both feed the export's common canvas.
 */
data class VideoSource(
    val id: Int,
    val uri: String,
    val durationMs: Long,
    val displayName: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    /** Stable key of the gallery item this source came from (cloud linkId, else local uri), so the source
     *  manager can pre-select it and match a picker deselect back to the right source. Null for a source
     *  with no gallery identity, e.g. an External primary. */
    val galleryKey: String? = null,
)

/** Duration of the source [sourceId] names, falling back to the first source (then 0) if it is gone. Pure. */
internal fun List<VideoSource>.durationOf(sourceId: Int): Long =
    (firstOrNull { it.id == sourceId } ?: firstOrNull())?.durationMs ?: 0L

/** URI of the source [sourceId] names, or the first source's, or null. Pure. */
internal fun List<VideoSource>.uriOf(sourceId: Int): String? =
    (firstOrNull { it.id == sourceId } ?: firstOrNull())?.uri

/**
 * One clip on the video track: a sub-range [startMs]..[endMs] of the source [sourceId] names, a stable
 * [id] (so a reorder or a selection survives edits that renumber the list) and a [removed] flag (greyed,
 * struck through, and skipped on playback but kept visible). The editor holds these in PLAY order: a split
 * inserts the tail right after its origin, a reorder moves one in the list, and the export writes the
 * non-removed clips in exactly this order. [sourceId] defaults to the primary so every single-source path
 * is unchanged.
 */
data class VideoClip(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val removed: Boolean = false,
    val sourceId: Int = PRIMARY_SOURCE_ID,
    /** The FIXED slot (trim territory) this clip owns in its source: the kept range [startMs]..[endMs]
     *  trims WITHIN [slotStartMs]..[slotEndMs], and the slot itself never moves — a split divides the
     *  parent's slot at the cut point, so trimming one half never shifts the other. Defaults ([0]..[MAX])
     *  mean "the whole source" (clamped to the real duration where used). */
    val slotStartMs: Long = 0L,
    val slotEndMs: Long = Long.MAX_VALUE,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * The clips a state resolves to: the explicit list clamped to the source, or one full-length clip when
 * the list is empty (just loaded, never edited). Order is PRESERVED, never sorted, so a reorder sticks.
 * Pure.
 */
internal fun resolveClips(clips: List<VideoClip>, sources: List<VideoSource>): List<VideoClip> {
    val primary = sources.firstOrNull()
    val primaryDur = (primary?.durationMs ?: 0L).coerceAtLeast(MIN_SEGMENT_MS)
    val primaryId = primary?.id ?: PRIMARY_SOURCE_ID
    if (clips.isEmpty()) return listOf(VideoClip(0L, 0L, primaryDur, sourceId = primaryId))
    val out = clips.mapNotNull { c ->
        val dur = sources.durationOf(c.sourceId).coerceAtLeast(MIN_SEGMENT_MS)
        // Clamp the active [startMs, endMs] into the clip's OWN fixed slot (an unset slotEndMs = MAX means
        // "the whole source"). The slot is the fixed trim territory; the active range lives inside it. The
        // slot values themselves are left as the clip carries them (a split set them; downstream coerces).
        val slotLo = c.slotStartMs.coerceIn(0L, dur)
        val slotHi = c.slotEndMs.coerceIn(slotLo, dur)
        val s = c.startMs.coerceIn(slotLo, slotHi)
        val e = c.endMs.coerceIn(slotLo, slotHi)
        if (e - s >= MIN_SEGMENT_MS) c.copy(startMs = s, endMs = e) else null
    }
    return out.ifEmpty { listOf(VideoClip(0L, 0L, primaryDur, sourceId = primaryId)) }
}

/** Total played length: the non-removed clips summed. Pure. */
internal fun editedDurationMs(clips: List<VideoClip>): Long =
    clips.filter { !it.removed }.sumOf { it.durationMs }

/**
 * The music block on the edited timeline for a given offset and music-file trim, in ms: it starts at
 * [offsetMs] and runs for the trimmed music length ([trimEndMs] - [trimStartMs]), with its end clamped to
 * [editedTotalMs] so the music never plays past the last frame. Returns null when the block would be
 * zero-length (empty trim) or when the offset sits at or past the end of the edited video (no room left to
 * play any music). Pure. Feeds both the preview gate and the export-side placement maths.
 */
internal fun audioBlockRangeMs(
    offsetMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    editedTotalMs: Long,
): LongRange? {
    val musicLen = (trimEndMs - trimStartMs).coerceAtLeast(0L)
    if (musicLen <= 0L) return null
    val start = offsetMs.coerceAtLeast(0L)
    if (start >= editedTotalMs) return null
    val end = (start + musicLen).coerceAtMost(editedTotalMs)
    if (end <= start) return null
    return start..end
}

/**
 * The export windows: the non-removed clips in play order as source ranges, with a run that is also
 * back-to-back in the source merged, so an un-reordered split still exports as one lossless copy while a
 * reorder or a removed gap stays as separate windows. Pure.
 */
internal fun clipWindows(clips: List<VideoClip>): List<VideoSegment> {
    val kept = clips.filter { !it.removed && it.durationMs > 0L }
    if (kept.isEmpty()) return emptyList()
    val out = ArrayList<VideoSegment>(kept.size)
    var current = VideoSegment(kept.first().startMs, kept.first().endMs)
    var currentSource = kept.first().sourceId
    for (i in 1 until kept.size) {
        val c = kept[i]
        // Only merge a run that is the SAME source AND back-to-back in it; two different videos can never
        // collapse into one lossless window (different bitstreams).
        current = if (c.sourceId == currentSource && c.startMs == current.endMs) {
            VideoSegment(current.startMs, c.endMs)
        } else {
            out.add(current)
            currentSource = c.sourceId
            VideoSegment(c.startMs, c.endMs)
        }
    }
    out.add(current)
    return out
}

/**
 * Split the non-removed clip under the edited-time [editedMs] into two at that point, inserting the tail
 * right after it with id [newId]. No-op when the point is outside every clip or too close to a boundary
 * to leave [MIN_SEGMENT_MS] on both sides. Pure.
 */
internal fun splitClipsAtEdited(clips: List<VideoClip>, editedMs: Long, newId: Long): List<VideoClip> {
    var acc = 0L
    var split = false
    val out = ArrayList<VideoClip>(clips.size + 1)
    for (c in clips) {
        if (split || c.removed) { out.add(c); continue }
        val end = acc + c.durationMs
        if (editedMs >= acc + MIN_SEGMENT_MS && editedMs <= end - MIN_SEGMENT_MS) {
            val at = c.startMs + (editedMs - acc)
            // Divide the parent's FIXED slot at the cut point: the head owns [slotStart, at], the tail owns
            // [at, slotEnd]. The boundary `at` never moves again, so trimming one half stays inside its own
            // slot and never shifts the other half or the timeline.
            out.add(c.copy(endMs = at, slotEndMs = at))
            out.add(VideoClip(newId, at, c.endMs, false, c.sourceId, slotStartMs = at, slotEndMs = c.slotEndMs))
            split = true
        } else {
            out.add(c)
        }
        acc = end
    }
    return out
}

/** True when [splitClipsAtEdited] would split at edited-time [editedMs]. Pure. */
internal fun canSplitClipsAt(clips: List<VideoClip>, editedMs: Long): Boolean {
    var acc = 0L
    for (c in clips) {
        if (c.removed) continue
        val end = acc + c.durationMs
        if (editedMs >= acc + MIN_SEGMENT_MS && editedMs <= end - MIN_SEGMENT_MS) return true
        acc = end
    }
    return false
}

/**
 * Result of deleting the clip with [id]: the new (clips, sources), or null for a no-op. A clip on the
 * PRIMARY source ([primaryId]) is greyed out (removed = true, keeps its slot) and never the last one
 * still playing, so the preview/export always has something. A clip on an ADDED source is dropped
 * outright, and when it was that source's last clip the [VideoSource] leaves the registry too, so the
 * track shrinks back and `isMultiSource` can flip off. Pure so the branching is unit-tested.
 */
internal fun removeClipResult(
    clips: List<VideoClip>,
    sources: List<VideoSource>,
    id: Long,
    primaryId: Int,
): Pair<List<VideoClip>, List<VideoSource>>? {
    val target = clips.firstOrNull { it.id == id } ?: return null
    if (target.sourceId == primaryId) {
        if (clips.count { !it.removed } <= 1) return null
        val next = clips.map { if (it.id == id) it.copy(removed = true) else it }
        return if (next == clips) null else next to sources
    }
    val filtered = clips.filter { it.id != id }
    // Never leave zero playing clips (e.g. every primary clip greyed + this the only live one).
    if (filtered.none { !it.removed }) return null
    val sourceStillUsed = filtered.any { it.sourceId == target.sourceId }
    val newSources = if (sourceStillUsed) sources else sources.filter { it.id != target.sourceId }
    return filtered to newSources
}

/**
 * New start for the clip at [index] after dragging its start handle. Lower bound is the clip's OWN fixed
 * slot start ([VideoClip.slotStartMs], the split point for a tail half, else 0); upper bound is this clip's
 * own end minus [MIN_SEGMENT_MS]. The slot is fixed, so this depends on no sibling: trimming a split half
 * stays inside its own territory and never moves the other half. Reversible within the bounds. Pure.
 */
internal fun clampClipStart(clips: List<VideoClip>, index: Int, newMs: Long): Long {
    val c = clips[index]
    val hi = (c.endMs - MIN_SEGMENT_MS).coerceAtLeast(0L)
    val lo = c.slotStartMs.coerceIn(0L, hi)
    return newMs.coerceIn(lo, hi)
}

/**
 * New end for the clip at [index] after dragging its end handle. Upper bound is the clip's OWN fixed slot
 * end ([VideoClip.slotEndMs], the split point for a head half, else the source [durationMs]); lower bound is
 * this clip's own start plus [MIN_SEGMENT_MS]. Fixed slot, no sibling dependency, so a split half's end
 * trim never shifts the other half. Pure.
 */
internal fun clampClipEnd(clips: List<VideoClip>, index: Int, newMs: Long, durationMs: Long): Long {
    val c = clips[index]
    val hi = c.slotEndMs.coerceIn(0L, durationMs)
    return newMs.coerceIn((c.startMs + MIN_SEGMENT_MS).coerceAtMost(hi), hi)
}

/** Map an edited-time position to a play index + source ms among the kept clips (play order). Pure. */
internal fun editedToClipPos(keptClips: List<VideoClip>, editedMs: Long): Pair<Int, Long> {
    if (keptClips.isEmpty()) return 0 to 0L
    var acc = 0L
    keptClips.forEachIndexed { i, c ->
        if (editedMs < acc + c.durationMs) {
            return i to (c.startMs + (editedMs - acc)).coerceIn(c.startMs, c.endMs)
        }
        acc += c.durationMs
    }
    return keptClips.lastIndex to keptClips.last().endMs
}

/** Move the clip at [from] to index [to] in play order. Pure. */
internal fun moveClipAt(clips: List<VideoClip>, from: Int, to: Int): List<VideoClip> {
    if (from !in clips.indices || to !in clips.indices || from == to) return clips
    val out = clips.toMutableList()
    out.add(to, out.removeAt(from))
    return out
}

/**
 * A restore point for the undo stack: every field the user can edit, so undo returns all of them at
 * once. The non-edit fields (source, dimensions, save flags) are deliberately left out, so undoing an
 * edit never resurrects a stale save result or reloads the source.
 */
private data class EditSnapshot(
    val trimStartMs: Long,
    val trimEndMs: Long,
    val clips: List<VideoClip>,
    val sources: List<VideoSource>,
    val rotationDegrees: Int,
    val cropRect: Rect?,
    val filterPreset: VideoFilter,
    val adjBrightness: Int,
    val adjExposure: Int,
    val adjContrast: Int,
    val adjHighlights: Int,
    val adjShadows: Int,
    val adjSaturation: Int,
    val adjTemperature: Int,
    val adjTone: Int,
    val adjFade: Int,
    val audioOverlayUri: String?,
    val audioOverlayDisplayName: String?,
    val audioOverlayDurationMs: Long,
    val audioTrimStartMs: Long,
    val audioTrimEndMs: Long,
    val audioOffsetMs: Long,
    val originalAudioGain: Float,
    val musicAudioGain: Float,
)

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

    // ── Edit history (undo) ─────────────────────────────────────────────────────────────────────
    // A stack of edit states. The bottom is the state as loaded; each finished edit pushes a new top.
    // Undo drops the top and restores the one beneath. Continuous drags push a single entry (the host
    // calls beginEdit at the drag's start and endEdit at its end); one-shot edits push at completion.
    private val undoStack = ArrayDeque<EditSnapshot>()

    // Parallel to [undoStack]: the operation label (a string resource) that produced each entry, so the
    // history sheet can name each edit. The baseline (bottom) carries 0 (no label). [pendingLabel] is set
    // by each mutator to its own operation name and consumed by [endEdit] when it pushes a new entry, so a
    // continuous drag (trim, volume) or a one-shot (split, rotate, filter) both land under the right name
    // without the screen having to pass a label through every gesture handler.
    private val undoLabels = ArrayDeque<Int>()
    private var pendingLabel: Int = 0
    // Snapshots (and their labels) popped by undo, so Redo can re-apply them. Any fresh edit clears these.
    private val redoStack = ArrayDeque<EditSnapshot>()
    private val redoLabels = ArrayDeque<Int>()

    private fun snapshotOf(s: VideoEditorUiState) = EditSnapshot(
        s.trimStartMs, s.trimEndMs, s.clips, s.sources, s.rotationDegrees, s.cropRect, s.filterPreset,
        s.adjBrightness, s.adjExposure, s.adjContrast, s.adjHighlights, s.adjShadows,
        s.adjSaturation, s.adjTemperature, s.adjTone, s.adjFade,
        s.audioOverlayUri, s.audioOverlayDisplayName, s.audioOverlayDurationMs,
        s.audioTrimStartMs, s.audioTrimEndMs, s.audioOffsetMs,
        s.originalAudioGain, s.musicAudioGain,
    )

    private fun refreshCanUndo() {
        val can = undoStack.size > 1
        val depth = (undoStack.size - 1).coerceAtLeast(0)
        // Undoable steps' labels, most recent first, capped at the last 10 (skip the baseline at index 0).
        val history = undoLabels.toList().drop(1).asReversed().take(10)
        _state.update { it.copy(canUndo = can, undoDepth = depth, undoHistory = history, canRedo = redoStack.isNotEmpty()) }
    }

    private fun applySnapshot(prev: EditSnapshot) {
        _state.update {
            it.copy(
                trimStartMs = prev.trimStartMs,
                trimEndMs = prev.trimEndMs,
                clips = prev.clips,
                sources = prev.sources,
                rotationDegrees = prev.rotationDegrees,
                cropRect = prev.cropRect,
                filterPreset = prev.filterPreset,
                adjBrightness = prev.adjBrightness,
                adjExposure = prev.adjExposure,
                adjContrast = prev.adjContrast,
                adjHighlights = prev.adjHighlights,
                adjShadows = prev.adjShadows,
                adjSaturation = prev.adjSaturation,
                adjTemperature = prev.adjTemperature,
                adjTone = prev.adjTone,
                adjFade = prev.adjFade,
                audioOverlayUri = prev.audioOverlayUri,
                audioOverlayDisplayName = prev.audioOverlayDisplayName,
                audioOverlayDurationMs = prev.audioOverlayDurationMs,
                audioTrimStartMs = prev.audioTrimStartMs,
                audioTrimEndMs = prev.audioTrimEndMs,
                audioOffsetMs = prev.audioOffsetMs,
                originalAudioGain = prev.originalAudioGain,
                musicAudioGain = prev.musicAudioGain,
            )
        }
    }

    /** Seed the history with the current (as-loaded) state as the baseline restore point. */
    private fun resetHistory() {
        undoStack.clear()
        undoLabels.clear()
        redoStack.clear()
        redoLabels.clear()
        undoStack.addLast(snapshotOf(_state.value))
        undoLabels.addLast(0)
        pendingLabel = 0
        refreshCanUndo()
    }

    /** Capture the pre-edit baseline once, so the first edit of a fresh clip has something to return to. */
    fun beginEdit() {
        if (undoStack.isEmpty()) {
            undoStack.addLast(snapshotOf(_state.value))
            undoLabels.addLast(0)
        }
    }

    /** Record the current state as a restore point, unless nothing changed since the last one. Tags the new
     *  entry with [pendingLabel], the name of whichever operation last mutated state. */
    fun endEdit() {
        val snap = snapshotOf(_state.value)
        if (undoStack.isEmpty()) {
            undoStack.addLast(snap)
            undoLabels.addLast(0)
        } else if (undoStack.last() != snap) {
            undoStack.addLast(snap)
            undoLabels.addLast(pendingLabel)
            // A fresh edit forks history: whatever was undone can no longer be redone.
            redoStack.clear()
            redoLabels.clear()
        }
        refreshCanUndo()
    }

    /** Restore the previous edit state. */
    fun undo() = undoSteps(1)

    /** Step back [n] edits at once (for the history list: pick how far to rewind). Each popped state moves
     *  onto the redo stack so Redo can walk back up. */
    fun undoSteps(n: Int) {
        if (n <= 0 || undoStack.size < 2) return
        repeat(n) {
            if (undoStack.size >= 2) {
                redoStack.addLast(undoStack.removeLast())
                if (undoLabels.size >= 2) redoLabels.addLast(undoLabels.removeLast())
            }
        }
        applySnapshot(undoStack.last())
        refreshCanUndo()
    }

    /** Re-apply the most recently undone edit. */
    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(redoStack.removeLast())
        if (redoLabels.isNotEmpty()) undoLabels.addLast(redoLabels.removeLast())
        applySnapshot(undoStack.last())
        refreshCanUndo()
    }

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

    // The source key the editor already shows. A return trip to the video picker (a real nav destination)
    // disposes and recomposes this screen, so its load effect fires again with the same args; reloading
    // would reset state (clips + sources) and wipe every "+" addition. Skipping a reload for the key already
    // loaded keeps the added sources, while a genuinely different source still loads.
    private var loadedSourceKey: String? = null

    // Filmstrip thumbnails for the "+"-added (non-primary) sources, held here so they SURVIVE opening the
    // in-app video picker: it is a real nav destination that disposes and recomposes the editor, and the
    // added clips are not in the shared FilmstripCache, so a screen-owned map would re-extract them all on
    // return. The screen's collector fills this; the primary strip stays in the screen's cached hook.
    val extraSourceFrames = androidx.compose.runtime.mutableStateMapOf<Int, androidx.compose.runtime.snapshots.SnapshotStateList<android.graphics.Bitmap?>>()

    override fun onCleared() {
        super.onCleared()
        for (strip in extraSourceFrames.values) for (bmp in strip) { if (bmp != null && !bmp.isRecycled) bmp.recycle() }
        extraSourceFrames.clear()
    }

    /** Extract the filmstrip for a "+"-added timeline [sourceId] into [extraSourceFrames]. Runs on
     *  viewModelScope, NOT the screen's scope, so opening the in-app picker (a nav destination that
     *  disposes and recomposes the editor) cannot cancel the decode mid-way and strand the clip on
     *  placeholder cells for the rest of the session: the decode finishes even while the screen is
     *  gone and the strip is complete on return. Idempotent per source: a strip already present (it
     *  is filling or filled) is skipped. Extras stay at the base 12 frames / 320px; only the primary
     *  densifies on zoom, and the draw reads each source's own frame count. */
    fun ensureExtraSourceFilmstrip(sourceId: Int, uri: String, durationMs: Long) {
        if (extraSourceFrames.containsKey(sourceId)) return
        val target = androidx.compose.runtime.mutableStateListOf<android.graphics.Bitmap?>()
            .apply { repeat(12) { add(null) } }
        extraSourceFrames[sourceId] = target
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return@launch
            runCatching { extractFilmstripInto(context, parsed, 12, 320, durationMs, target) }
        }
    }

    fun loadLocal(uri: String, displayName: String, mimeType: String, galleryKey: String? = null) {
        if (loadedSourceKey == "local:$uri") return
        loadedSourceKey = "local:$uri"
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
                    filterPreset = VideoFilter.None,
                    adjBrightness = 0,
                    adjExposure = 0,
                    adjContrast = 0,
                    adjHighlights = 0,
                    adjShadows = 0,
                    adjSaturation = 0,
                    adjTemperature = 0,
                    adjTone = 0,
                    adjFade = 0,
                    clips = emptyList(),
                    sources = listOf(
                        VideoSource(PRIMARY_SOURCE_ID, uri, meta.duration, displayName, effW, effH, sourceRotationDegrees, galleryKey),
                    ),
                    sourceWidth = effW,
                    sourceHeight = effH,
                    audioOverlayUri = null,
                    audioOverlayDisplayName = null,
                    audioOverlayDurationMs = 0L,
                    audioTrimStartMs = 0L,
                    audioTrimEndMs = 0L,
                    audioOffsetMs = 0L,
                    originalAudioGain = 1.0f,
                    musicAudioGain = 1.0f,
                    isLoading = false,
                )
            }
            resetHistory()
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
    fun loadExternal(uri: String, displayName: String, mimeType: String, galleryKey: String? = null) {
        if (loadedSourceKey == "external:$uri") return
        loadedSourceKey = "external:$uri"
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
                    filterPreset = VideoFilter.None,
                    adjBrightness = 0,
                    adjExposure = 0,
                    adjContrast = 0,
                    adjHighlights = 0,
                    adjShadows = 0,
                    adjSaturation = 0,
                    adjTemperature = 0,
                    adjTone = 0,
                    adjFade = 0,
                    clips = emptyList(),
                    sources = listOf(
                        VideoSource(PRIMARY_SOURCE_ID, uri, meta.duration, displayName, effW, effH, sourceRotationDegrees, galleryKey),
                    ),
                    sourceWidth = effW,
                    sourceHeight = effH,
                    audioOverlayUri = null,
                    audioOverlayDisplayName = null,
                    audioOverlayDurationMs = 0L,
                    audioTrimStartMs = 0L,
                    audioTrimEndMs = 0L,
                    audioOffsetMs = 0L,
                    originalAudioGain = 1.0f,
                    musicAudioGain = 1.0f,
                    isLoading = false,
                )
            }
            resetHistory()
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
    fun loadCloud(photo: CloudPhoto, albumLinkId: String?, galleryKey: String? = null) {
        if (loadedSourceKey == "cloud:${photo.linkId}") return
        loadedSourceKey = "cloud:${photo.linkId}"
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
                    filterPreset = VideoFilter.None,
                    adjBrightness = 0,
                    adjExposure = 0,
                    adjContrast = 0,
                    adjHighlights = 0,
                    adjShadows = 0,
                    adjSaturation = 0,
                    adjTemperature = 0,
                    adjTone = 0,
                    adjFade = 0,
                    clips = emptyList(),
                    sources = listOf(
                        VideoSource(PRIMARY_SOURCE_ID, parsed.toString(), meta.duration, photo.displayName, effW, effH, sourceRotationDegrees, galleryKey),
                    ),
                    sourceWidth = effW,
                    sourceHeight = effH,
                    audioOverlayUri = null,
                    audioOverlayDisplayName = null,
                    audioOverlayDurationMs = 0L,
                    audioTrimStartMs = 0L,
                    audioTrimEndMs = 0L,
                    audioOffsetMs = 0L,
                    originalAudioGain = 1.0f,
                    musicAudioGain = 1.0f,
                    isLoading = false,
                )
            }
            resetHistory()
        }
    }

    /**
     * Fills the source dimensions from the live ExoPlayer's reported size when
     * [MediaMetadataRetriever] read them as 0. Some containers carry no width/height in
     * their metadata while the decoder still resolves a real size, and the crop tool stays
     * hidden as long as the dimensions are 0. The player reports post-rotation dimensions,
     * the same space [loadLocal] stores, so the values map straight onto the source fields.
     * Only a missing dimension is filled, so a good retriever read is never overwritten.
     */
    fun onPlayerVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        _state.update { s ->
            if (s.sourceWidth > 0 && s.sourceHeight > 0) s
            else s.copy(sourceWidth = width, sourceHeight = height)
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

    /** Ids handed to clips a split creates; 0 is reserved for the implicit single clip. */
    private var nextClipId = 1L

    /** Ids handed to sources the "+" appends; [PRIMARY_SOURCE_ID] (0) is the video the editor opened. */
    private var nextSourceId = PRIMARY_SOURCE_ID + 1

    /** Sets the clips and re-derives the trim span (min start .. max end of the kept clips), which the
     *  re-encode/crop path and the audio window still read as the overall edited range. */
    private fun VideoEditorUiState.withClips(newClips: List<VideoClip>): VideoEditorUiState {
        val ref = newClips.filter { !it.removed }.ifEmpty { newClips }
        val lo = ref.minOfOrNull { it.startMs } ?: trimStartMs
        val hi = ref.maxOfOrNull { it.endMs } ?: trimEndMs
        return copy(clips = newClips, trimStartMs = lo, trimEndMs = hi)
    }

    /**
     * Split the clip under the edited-time [editedMs] (the concatenated preview's position) into two
     * clips at that point. A no-op when the point is too close to a boundary. Both halves stay in play.
     */
    fun splitAtPlayhead(editedMs: Long) {
        // Compute the new id outside update and bump it once after: MutableStateFlow.update may run its
        // lambda more than once (CAS retry), and a `nextClipId++` inside would burn ids on a retry.
        val newId = nextClipId
        var used = false
        _state.update { s ->
            val resolved = resolveClips(s.clips, s.sources)
            val split = splitClipsAtEdited(resolved, editedMs, newId)
            if (split.size == resolved.size) s else { used = true; s.withClips(split) }
        }
        if (used) {
            nextClipId++
            pendingLabel = R.string.video_editor_split
            endEdit()
        }
    }

    /**
     * Delete the clip with [id]. A PRIMARY-source clip is greyed (kept on the track, skipped in playback
     * and export), never the last one playing. A clip on an ADDED ("+") source is removed from the track
     * outright, and when it was that source's last clip the source itself leaves [VideoEditorUiState.sources]
     * so the timeline shrinks back and multi-source turns off. See [removeClipResult].
     */
    fun removeClip(id: Long) {
        _state.update { s ->
            val resolved = resolveClips(s.clips, s.sources)
            val primaryId = s.sources.firstOrNull()?.id ?: PRIMARY_SOURCE_ID
            val res = removeClipResult(resolved, s.sources, id, primaryId) ?: return@update s
            s.copy(sources = res.second).withClips(res.first)
        }
        pendingLabel = R.string.video_editor_remove_section
        endEdit()
    }

    /** Bring a greyed clip with [id] back into play. */
    fun restoreClip(id: Long) {
        _state.update { s ->
            val resolved = resolveClips(s.clips, s.sources)
            val next = resolved.map { if (it.id == id) it.copy(removed = false) else it }
            if (next == resolved) s else s.withClips(next)
        }
        pendingLabel = R.string.video_editor_restore_section
        endEdit()
    }

    /** Insert a copy of the clip with [id] right after it in play order (same source range, fresh id). */
    fun duplicateClip(id: Long) {
        val newId = nextClipId
        var used = false
        _state.update { s ->
            val resolved = resolveClips(s.clips, s.sources)
            val idx = resolved.indexOfFirst { it.id == id }
            if (idx < 0) return@update s
            used = true
            val copy = resolved[idx].copy(id = newId, removed = false)
            s.withClips(resolved.toMutableList().also { it.add(idx + 1, copy) })
        }
        if (used) {
            nextClipId++
            pendingLabel = R.string.video_editor_duplicate
            endEdit()
        }
    }

    /**
     * Drag clip [index]'s own start or end handle to [newMs] (source ms), trimming just that clip. Since
     * clips can be reordered, each clip's source range is independent — clamped only to the source and to
     * [MIN_SEGMENT_MS], never to a neighbour.
     */
    fun setClipEdge(index: Int, isStart: Boolean, newMs: Long) {
        _state.update { s ->
            val resolved = resolveClips(s.clips, s.sources)
            if (index !in resolved.indices) return@update s
            val c = resolved[index]
            val edited = if (isStart) c.copy(startMs = clampClipStart(resolved, index, newMs))
                else c.copy(endMs = clampClipEnd(resolved, index, newMs, s.sources.durationOf(c.sourceId)))
            s.withClips(resolved.toMutableList().also { it[index] = edited })
        }
        pendingLabel = R.string.video_editor_trim
    }

    /** Move the clip at play-order [from] to [to], the CapCut-style reorder. */
    fun moveClip(from: Int, to: Int) {
        _state.update { s ->
            val resolved = resolveClips(s.clips, s.sources)
            val next = moveClipAt(resolved, from, to)
            if (next == resolved) s else s.withClips(next)
        }
        pendingLabel = R.string.video_editor_reorder
        endEdit()
    }

    /**
     * Append a picked video as a NEW source on the timeline (the "+" on the cut track): probe its
     * duration/dims, register it in [VideoEditorUiState.sources], and add one full-length clip of it after
     * the current clips so the preview plays the videos back to back. The multi-source concat export lands
     * separately; until then [save] guards the multi-source case. Undoable via the edit history.
     */
    fun addVideoSource(uri: String, displayName: String, galleryKey: String? = null) {
        viewModelScope.launch {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            if (parsed == null) {
                _state.update { it.copy(userMessage = context.getString(R.string.editor_invalid_video_uri)) }
                return@launch
            }
            // Cap how many videos combine at once. The count includes the primary, so once the timeline
            // already holds the maximum a further "+" is refused rather than growing the concat unbounded.
            if (_state.value.sources.size >= MAX_VIDEO_SOURCES) {
                _state.update { it.copy(userMessage = context.getString(R.string.video_editor_max_sources, MAX_VIDEO_SOURCES)) }
                return@launch
            }
            val meta = withContext(Dispatchers.IO) {
                // Best-effort durable grant so the picked file survives the session (matches the music picker).
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        parsed, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val retriever = MediaMetadataRetriever()
                val m = runCatching {
                    retriever.setDataSource(context, parsed)
                    VideoMeta(
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                        rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                    )
                }.getOrNull()
                runCatching { retriever.release() }
                m
            }
            if (meta == null || meta.duration < MIN_SEGMENT_MS) {
                _state.update { it.copy(userMessage = context.getString(R.string.editor_video_metadata_failed)) }
                return@launch
            }
            val rot = ((meta.rotation % 360) + 360) % 360
            val effW = if (rot % 180 != 0) meta.height else meta.width
            val effH = if (rot % 180 != 0) meta.width else meta.height
            val newSourceId = nextSourceId
            val newClipId = nextClipId
            _state.update { s ->
                val newSources = s.sources + VideoSource(newSourceId, uri, meta.duration, displayName, effW, effH, rot, galleryKey)
                val appended = resolveClips(s.clips, newSources) + VideoClip(newClipId, 0L, meta.duration, false, newSourceId, slotStartMs = 0L, slotEndMs = meta.duration)
                s.copy(sources = newSources).withClips(appended)
            }
            nextSourceId++
            nextClipId++
            pendingLabel = R.string.video_editor_add_video
            endEdit()
        }
    }

    /**
     * Append a cloud video as a NEW timeline source (the in-app picker's "+" cloud path): download the
     * full-res file the same way [loadCloud] does (signed-in guard, [addingSource] while the bytes come
     * down), then hand the local copy to [addVideoSource] so the probe / append / source-cap logic
     * stays in one place. Account-only: a guest never reaches a cloud item, so a null primary user only
     * surfaces the not-signed-in message without disturbing the loaded editor. Failures land as a
     * [VideoSaveResult.Failed] (toast + inline) rather than a full-screen error so the current edit
     * survives an add that could not complete.
     */
    fun addCloudVideoSource(photo: CloudPhoto) {
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                _state.update { it.copy(userMessage = context.getString(R.string.viewer_not_signed_in)) }
                return@launch
            }
            _state.update { it.copy(addingSource = true) }
            val downloaded = runCatching { cloudRepo.downloadFullResPhoto(userId, photo) }
            val file = downloaded.getOrNull()
            _state.update { it.copy(addingSource = false) }
            if (file == null) {
                _state.update {
                    it.copy(
                        userMessage = downloaded.exceptionOrNull()?.message
                            ?: context.getString(R.string.editor_cloud_video_download_failed),
                    )
                }
                return@launch
            }
            addVideoSource(Uri.fromFile(file).toString(), photo.displayName, galleryKey = photo.linkId)
        }
    }

    /**
     * Remove the source [sourceId] from the timeline (the source manager deselecting a video): drop the
     * source and every clip that played it, then re-resolve so the trim span and preview snap back to the
     * shorter timeline. A no-op on the primary ([PRIMARY_SOURCE_ID]), which the manager keeps locked, so
     * the editor always has something to play. Undoable via the edit history, like [addVideoSource].
     */
    fun removeVideoSources(sourceIds: Set<Int>) {
        val targets = sourceIds - PRIMARY_SOURCE_ID
        if (targets.isEmpty()) return
        var changed = false
        _state.update { s ->
            if (s.sources.none { it.id in targets }) return@update s
            changed = true
            val newSources = s.sources.filterNot { it.id in targets }
            val newClips = s.clips.filterNot { it.sourceId in targets }
            s.copy(sources = newSources).withClips(resolveClips(newClips, newSources))
        }
        if (changed) {
            pendingLabel = R.string.video_editor_remove_section
            endEdit()
        }
    }

    /** Windows the export writes, in play order: the non-removed clips, with a source-contiguous run
     *  merged so an un-reordered split still exports as one lossless copy. */
    private fun VideoEditorUiState.exportWindows(): List<VideoSegment> =
        clipWindows(resolveClips(clips, sources))

    fun rotate90Cw() {
        _state.update { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
        pendingLabel = R.string.video_editor_rotate
        endEdit()
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
        pendingLabel = if (rect == null) R.string.video_editor_reset_crop else R.string.video_editor_crop
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

    /** Sets the colour filter; a non-None filter is baked in at save time (re-encode path). */
    fun setFilter(preset: VideoFilter) {
        _state.update { it.copy(filterPreset = preset) }
        pendingLabel = R.string.editor_tool_filter
        endEdit()
    }

    // Colour/light adjustment sliders. Each mutates one field (clamped to -100..100) and brackets an undo
    // step the same way setFilter does: tag the entry with the generic "Adjust" label, then endEdit dedups
    // so an unchanged value adds nothing. A non-zero value forces the GL/re-encode save path via hasColorEdits.
    private fun updateAdjustment(update: (VideoEditorUiState) -> VideoEditorUiState) {
        _state.update(update)
        pendingLabel = R.string.editor_tool_adjust
        endEdit()
    }

    fun setAdjBrightness(value: Int) = updateAdjustment { it.copy(adjBrightness = value.coerceIn(-100, 100)) }
    fun setAdjExposure(value: Int) = updateAdjustment { it.copy(adjExposure = value.coerceIn(-100, 100)) }
    fun setAdjContrast(value: Int) = updateAdjustment { it.copy(adjContrast = value.coerceIn(-100, 100)) }
    fun setAdjHighlights(value: Int) = updateAdjustment { it.copy(adjHighlights = value.coerceIn(-100, 100)) }
    fun setAdjShadows(value: Int) = updateAdjustment { it.copy(adjShadows = value.coerceIn(-100, 100)) }
    fun setAdjSaturation(value: Int) = updateAdjustment { it.copy(adjSaturation = value.coerceIn(-100, 100)) }
    fun setAdjTemperature(value: Int) = updateAdjustment { it.copy(adjTemperature = value.coerceIn(-100, 100)) }
    fun setAdjTone(value: Int) = updateAdjustment { it.copy(adjTone = value.coerceIn(-100, 100)) }
    fun setAdjFade(value: Int) = updateAdjustment { it.copy(adjFade = value.coerceIn(-100, 100)) }

    /** Zeroes every colour/light adjustment in one undoable step (the filter preset is left untouched). */
    fun resetColorAdjustments() = updateAdjustment {
        it.copy(
            adjBrightness = 0, adjExposure = 0, adjContrast = 0, adjHighlights = 0, adjShadows = 0,
            adjSaturation = 0, adjTemperature = 0, adjTone = 0, adjFade = 0,
        )
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
                    audioOffsetMs = 0L,
                )
            }
            pendingLabel = R.string.video_editor_add_music
            endEdit()
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
                audioOffsetMs = 0L,
            )
        }
        pendingLabel = R.string.video_editor_remove_music
        endEdit()
    }

    /** Source-audio loudness, 0..2 (1 = original, above 1 boosts, below quietens; the mix scales the PCM
     *  and clamps). Anything but exactly 0 or 1 takes the re-encode path at save time. */
    fun setOriginalAudioGain(gain: Float) {
        _state.update { it.copy(originalAudioGain = gain.coerceIn(0f, 2f)) }
        pendingLabel = R.string.video_editor_volume
    }

    /** Remembered source-audio gain so un-mute restores what the slider held; defaults to full. */
    private var gainBeforeMute: Float = 1f

    /**
     * One-tap source-audio mute. Muting pins the gain to exactly 0, which [save] routes through the
     * lossless stream-copy strip (muxTrimmed with stripAudio), never a re-encode; un-mute restores the
     * pre-mute gain, or full when the remembered value was itself a mute.
     */
    fun toggleMute() {
        _state.update { s ->
            if (s.originalAudioGain <= 0.001f) {
                val restored = gainBeforeMute.coerceIn(0f, 1f).let { if (it <= 0.001f) 1f else it }
                s.copy(originalAudioGain = restored)
            } else {
                gainBeforeMute = s.originalAudioGain
                s.copy(originalAudioGain = 0f)
            }
        }
        pendingLabel = R.string.video_editor_mute
        endEdit()
    }

    /** Overlay-music loudness 0..2, same semantics as [setOriginalAudioGain]. When both gains are > 0 and
     *  an overlay is picked the save pipeline mixes the two PCM streams. */
    fun setMusicAudioGain(gain: Float) {
        _state.update { it.copy(musicAudioGain = gain.coerceIn(0f, 2f)) }
        pendingLabel = R.string.video_editor_volume
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
        pendingLabel = R.string.video_editor_trim
    }

    /**
     * Moves the music's start position on the edited timeline. Clamped to
     * [0, editedTotal - MIN_SEGMENT_MS] so a placed music block always has room to play at least one
     * segment before the last frame. The edited total is the summed length of the kept clips (an
     * un-edited video resolves to one full-length clip). Continuous like [setMusicAudioGain] /
     * [setAudioTrimRange]: it only tags the pending undo label, and the host brackets the drag with
     * [beginEdit] / [endEdit].
     */
    fun setAudioOffset(ms: Long) {
        _state.update { s ->
            if (s.audioOverlayUri == null) return@update s
            val editedTotal = editedDurationMs(resolveClips(s.clips, s.sources))
            val hi = (editedTotal - MIN_SEGMENT_MS).coerceAtLeast(0L)
            s.copy(audioOffsetMs = ms.coerceIn(0L, hi))
        }
        pendingLabel = R.string.video_editor_audio
    }

    /** Adjusts which slice of the music file plays: the in/out points in absolute overlay-file ms. Each
     *  edge keeps at least MIN_SEGMENT_MS of audio and stays inside the file's duration. Bracket the drag
     *  with beginEdit / endEdit like the other timeline handles so it is one undo step. */
    fun setAudioTrim(startMs: Long, endMs: Long) {
        _state.update { s ->
            if (s.audioOverlayUri == null) return@update s
            val dur = s.audioOverlayDurationMs.coerceAtLeast(MIN_SEGMENT_MS)
            val newStart = startMs.coerceIn(0L, (endMs - MIN_SEGMENT_MS).coerceAtLeast(0L))
            val newEnd = endMs.coerceIn((newStart + MIN_SEGMENT_MS).coerceAtMost(dur), dur)
            s.copy(audioTrimStartMs = newStart, audioTrimEndMs = newEnd)
        }
        pendingLabel = R.string.video_editor_audio
    }

    /** Drags the music block's LEFT edge to [newLeftMs] on the edited timeline: trims the audio in-point
     *  and shifts the offset by the same amount so the block's right edge (and the audio under it) stays
     *  put, the way a timeline clip's head-trim behaves. */
    fun setAudioLeftEdge(newLeftMs: Long) {
        _state.update { s ->
            if (s.audioOverlayUri == null) return@update s
            val delta = newLeftMs - s.audioOffsetMs
            val newTrimStart = (s.audioTrimStartMs + delta)
                .coerceIn(0L, (s.audioTrimEndMs - MIN_SEGMENT_MS).coerceAtLeast(0L))
            val appliedDelta = newTrimStart - s.audioTrimStartMs
            val newOffset = (s.audioOffsetMs + appliedDelta).coerceAtLeast(0L)
            s.copy(audioTrimStartMs = newTrimStart, audioOffsetMs = newOffset)
        }
        pendingLabel = R.string.video_editor_audio
    }

    /**
     * Music slice in microseconds, capped so the overlay never outlives the video window: the
     * effective length is min(manual music-trim length, trim length). The audio-trim handles still
     * choose a shorter slice; this cap only shortens a slice longer than the video, so it never
     * overrides the manual choice.
     */
    private fun VideoEditorUiState.effectiveAudioTrimUs(): Pair<Long, Long> {
        // The played video length is the sum of the kept clips, not the source span.
        val videoWindowMs = editedDurationMs(resolveClips(clips, sources)).coerceAtLeast(0L)
        val musicSliceMs = (audioTrimEndMs - audioTrimStartMs).coerceAtLeast(0L)
        val startUs = audioTrimStartMs * 1000L
        val endUs = (audioTrimStartMs + minOf(musicSliceMs, videoWindowMs)) * 1000L
        return startUs to endUs
    }

    fun consumeUserMessage() {
        _state.update { it.copy(userMessage = null) }
    }

    fun consumeSaveResult() {
        _state.update { it.copy(saveResult = null, saveProgress = null) }
    }

    /** Clears the one-shot frame-grab result once the screen has shown its toast. */
    fun consumeFrameGrabResult() {
        _state.update { it.copy(frameGrabResult = null) }
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
     * Grab the frame at [positionUs] from the source video and write it to the gallery as a JPEG.
     * MediaMetadataRetriever reads the source at full resolution, so the still is not bounded by the
     * 1080p preview decode. A bare JPEG compress carries no EXIF, so the still holds no location, the
     * same guarantee the video exports give. An 8K frame can exhaust the heap; that surfaces the
     * low-memory string rather than crashing.
     */
    fun grabCurrentFrame(positionUs: Long, sourceUriOverride: String? = null) {
        // [positionUs] is in the currently-PREVIEWED source's timeline. In a multi-source edit the player
        // may hold an added source, so grab from whatever file is loaded ([sourceUriOverride]); fall back
        // to the primary for the single-source case.
        val sourceUriStr = sourceUriOverride ?: _state.value.sourceUri ?: return
        val sourceDisplayName = _state.value.displayName
        viewModelScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val result: FrameGrabResult = try {
                retriever.setDataSource(context, Uri.parse(sourceUriStr))
                val bitmap = retriever.getFrameAtTime(
                    positionUs.coerceAtLeast(0L),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: error("No frame at position")
                val uri = try {
                    insertFrameJpeg(bitmap, sourceDisplayName)
                } finally {
                    bitmap.recycle()
                }
                FrameGrabResult.Success(uri)
            } catch (oom: OutOfMemoryError) {
                FrameGrabResult.Failed(context.getString(R.string.editor_error_low_memory))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                FrameGrabResult.Failed(context.getString(R.string.video_editor_frame_failed))
            } finally {
                runCatching { retriever.release() }
            }
            _state.update { it.copy(frameGrabResult = result) }
        }
    }

    /**
     * Writes [bitmap] into a fresh MediaStore image entry under DCIM/Camera. The compressed JPEG holds
     * no EXIF, so no location travels with the still. Mirrors the editor's local-copy MediaStore dance.
     */
    private fun insertFrameJpeg(bitmap: Bitmap, sourceDisplayName: String): Uri? {
        val now = System.currentTimeMillis()
        val base = sourceDisplayName.substringBeforeLast('.', sourceDisplayName).ifBlank { "video" }
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
            .format(java.util.Date(now))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${base}_frame_$ts.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.Images.Media.DATE_MODIFIED, now / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, ProtonPhotosStorage.DEFAULT_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: error("openOutputStream returned null for $uri")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        runCatching { context.contentResolver.notifyChange(uri, null) }
        return uri
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
        // A REORDERED single-source timeline + a re-encode edit: the single-pass GL re-encode renders the
        // decoder's frames in SOURCE order, so a play order differing from source order can't be produced in
        // one pass. Cut/removed sections stay source-ordered and DO re-encode. Multi-source export goes
        // through Transformer (which honours play order), so it is exempt. A pure reorder with no re-encode
        // still saves losslessly (muxTrimmed writes the windows in order).
        if (!s.isMultiSource) {
            val w = clipWindows(resolveClips(s.clips, s.sources))
            val reordered = w.zipWithNext().any { (a, b) -> b.startMs < a.startMs }
            val partialGain = s.originalAudioGain > 0.001f && s.originalAudioGain < 0.999f
            val forcesReencode = s.cropRect != null || s.hasColorEdits() ||
                s.audioOverlayUri != null || partialGain
            if (reordered && forcesReencode) {
                _state.update {
                    it.copy(saveResult = VideoSaveResult.Failed(context.getString(R.string.video_editor_reencode_sections_pending)))
                }
                return
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Decide between fast stream-copy and full re-encode. A re-encode is required
            // whenever the user has set a crop OR swapped the audio track — both modify
            // payload that the stream-copy path treats as immutable. Pixel rotation falls
            // through into the muxer's orientation hint, so it does NOT force a re-encode
            // on its own.
            // Partial source-audio gain (anything other than full-on or full-off) requires
            // PCM-level processing — stream-copy mux can't attenuate samples on its own.
            val partialOriginalGain = s.originalAudioGain > 0.001f && s.originalAudioGain < 0.999f
            val needsReencode = s.cropRect != null || s.hasColorEdits() ||
                s.audioOverlayUri != null || partialOriginalGain
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
                // Preflight the multi-source concat's disk need before any concat runs. The combined video
                // is muxed whole into cache before it reaches MediaStore or the cloud, so a source set
                // larger than the free space would fail mid-write. Sum the source files (best-effort) and
                // refuse up front when the cache volume can't hold them plus a safety margin; a zero sum
                // means no size could be read, so the check is skipped rather than blocking on an unknown.
                // Any syncedTempFile created above is removed by this block's finally.
                if (s.isMultiSource) {
                    val requiredBytes = multiSourceSourceBytes(s)
                    val availableBytes = runCatching {
                        android.os.StatFs(context.cacheDir.path).availableBytes
                    }.getOrDefault(Long.MAX_VALUE)
                    if (requiredBytes > 0L && availableBytes < requiredBytes + LOW_SPACE_MARGIN_BYTES) {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveResult = VideoSaveResult.Failed(context.getString(R.string.video_editor_low_space)),
                                saveProgress = null,
                                saveStage = VideoSaveStage.Idle,
                            )
                        }
                        return@launch
                    }
                }
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
                    when {
                        s.isMultiSource -> saveMultiSource(s, editTimestampMs, deviceCaptureMs)
                        needsReencode -> saveReencoded(s, finalRotation, editTimestampMs, deviceCaptureMs)
                        else -> saveStreamCopy(s, finalRotation, editTimestampMs, deviceCaptureMs)
                    }
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
        // Multi-source (a "+" added a second video): concatenate the kept clips across the sources with
        // Media3 Transformer. The single-source dispatch below is untouched.
        if (s.isMultiSource) {
            concatMultiSourceToTemp(
                context = context,
                clipsInPlayOrder = resolveClips(s.clips, s.sources).filter { !it.removed },
                sources = s.sources,
                outputFile = outputFile,
                canvasWidth = s.sourceWidth,
                canvasHeight = s.sourceHeight,
                overlayUri = s.audioOverlayUri?.let { Uri.parse(it) },
                overlayStartMs = s.audioTrimStartMs,
                overlayEndMs = s.audioTrimEndMs,
                overlayOffsetMs = s.audioOffsetMs,
                sourceGain = s.originalAudioGain,
                musicGain = s.musicAudioGain,
                colorMatrix4x4 = s.effectiveColorMatrix4x4(),
                cropRect = s.cropRect,
                cropSourceWidth = s.sourceWidth,
                cropSourceHeight = s.sourceHeight,
                onProgress = { p -> _state.update { it.copy(saveProgress = p) } },
            )
            return@withContext
        }
        // Three-way dispatch:
        //   • No re-encode at all (trim + rotate only) → muxTrimmed (existing fast path)
        //   • Audio edits but no crop → video stream-copy + audio mix-encode
        //     This preserves HDR (the 8-bit GL pipeline downsamples BT.2020/PQ source
        //     to washed-out SDR), keeps the video lossless, and only re-encodes audio.
        //   • Crop set → full GL re-encode (loses HDR — unavoidable without a 10-bit
        //     RGBA pipeline + HEVC main10 encoder).
        // A crop OR a colour filter needs the GL re-encode (only it can bake pixels); an audio-only edit
        // stays a lossless video stream-copy with a re-mixed audio track.
        val needsGl = s.cropRect != null || s.hasColorEdits()
        // Cut / reordered sections: the re-encode must skip the removed gaps too. The GL transcode is the
        // one path that can (it renders only the kept windows onto a continuous timeline); the video
        // stream-copy + audio-mix path can't drop interior video ranges, so route a multi-window edit
        // through GL even when the only re-encode reason is audio (a small quality cost for correctness).
        val windows = s.exportWindows()
        val multiWindow = windows.size > 1
        val keptWindowsUs = windows.map { it.startMs * 1000L..it.endMs * 1000L }
        val (musicStartUs, musicEndUs) = s.effectiveAudioTrimUs()
        when {
            needsReencode && (needsGl || multiWindow) -> {
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
                    colorMatrix = s.effectiveColorMatrix4x4(),
                    audioOverlayUri = s.audioOverlayUri?.let { Uri.parse(it) },
                    audioTrimStartUs = musicStartUs,
                    audioTrimEndUs = musicEndUs,
                    overlayOffsetUs = s.audioOffsetMs * 1000L,
                    originalAudioGain = s.originalAudioGain,
                    musicAudioGain = s.musicAudioGain,
                    keptWindowsUs = keptWindowsUs,
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
                    audioTrimStartUs = musicStartUs,
                    audioTrimEndUs = musicEndUs,
                    overlayOffsetUs = s.audioOffsetMs * 1000L,
                    originalAudioGain = s.originalAudioGain,
                    musicAudioGain = s.musicAudioGain,
                    onProgress = { p -> _state.update { it.copy(saveProgress = p) } },
                    isActive = { isActive },
                )
            }
            else -> muxTrimmedOrReencode(
                s = s,
                sourceUri = sourceUri,
                outputFile = outputFile,
                finalRotation = finalRotation,
                windows = s.exportWindows(),
                // Stream-copy strips source audio entirely when the gain slider is at 0
                // and there's no overlay to bring in. Partial gain / overlay cases force
                // re-encode (handled by needsReencode) so they never reach this branch.
                stripAudio = s.originalAudioGain <= 0.001f,
                onProgress = { p -> _state.update { it.copy(saveProgress = p) } },
                isActive = { isActive },
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
        if (s.isMultiSource) {
            // Multi-source (added-video) concat via Transformer, same as the synced/device paths.
            concatMultiSourceToTemp(
                context = context,
                clipsInPlayOrder = resolveClips(s.clips, s.sources).filter { !it.removed },
                sources = s.sources,
                outputFile = tempFile,
                canvasWidth = s.sourceWidth,
                canvasHeight = s.sourceHeight,
                overlayUri = s.audioOverlayUri?.let { Uri.parse(it) },
                overlayStartMs = s.audioTrimStartMs,
                overlayEndMs = s.audioTrimEndMs,
                overlayOffsetMs = s.audioOffsetMs,
                sourceGain = s.originalAudioGain,
                musicGain = s.musicAudioGain,
                colorMatrix4x4 = s.effectiveColorMatrix4x4(),
                cropRect = s.cropRect,
                cropSourceWidth = s.sourceWidth,
                cropSourceHeight = s.sourceHeight,
                onProgress = { p -> _state.update { it.copy(saveProgress = p) } },
            )
            return@withContext tempFile
        }
        if (needsReencode) {
            val crop = cropInSourcePixels(s)
            val (musicStartUs, musicEndUs) = s.effectiveAudioTrimUs()
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
                colorMatrix = s.effectiveColorMatrix4x4(),
                audioOverlayUri = s.audioOverlayUri?.let { Uri.parse(it) },
                audioTrimStartUs = musicStartUs,
                audioTrimEndUs = musicEndUs,
                overlayOffsetUs = s.audioOffsetMs * 1000L,
                originalAudioGain = s.originalAudioGain,
                musicAudioGain = s.musicAudioGain,
                keptWindowsUs = s.exportWindows().map { it.startMs * 1000L..it.endMs * 1000L },
                onProgress = { p -> _state.update { it.copy(saveProgress = p) } },
                isActive = { isActive },
            )
        } else {
            muxTrimmedOrReencode(
                s = s,
                sourceUri = sourceUri,
                outputFile = tempFile,
                finalRotation = finalRotation,
                windows = s.exportWindows(),
                // Stream-copy strips source audio entirely when the gain slider is at 0
                // and there's no overlay to bring in. Partial gain / overlay cases force
                // re-encode (handled by needsReencode) so they never reach this branch.
                stripAudio = s.originalAudioGain <= 0.001f,
                onProgress = { p -> _state.update { it.copy(saveProgress = p) } },
                isActive = { isActive },
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
            s = s,
            sourceUri = Uri.parse(sourceUriStr),
            displayName = s.displayName,
            mimeType = s.mimeType,
            windows = s.exportWindows(),
            orientationDegrees = finalRotation,
            captureTimestampMs = captureTimestampMs,
            stripAudio = stripAudio,
            editTimestampMs = editTimestampMs,
        )
        return VideoSaveResult.Success(uri)
    }

    /**
     * Multi-source save path (device leg): a "+" added a second video, so concatenate the kept clips across
     * the sources with Media3 Transformer ([concatMultiSourceToTemp]) into a temp, stamp the capture time,
     * then insert the copy. Mirrors [saveReencoded]'s temp -> stamp -> insert flow.
     */
    private suspend fun saveMultiSource(
        s: VideoEditorUiState,
        editTimestampMs: Long,
        captureTimestampMs: Long,
    ): VideoSaveResult = withContext(Dispatchers.IO) {
        val tempFile = createTempMuxFile()
        try {
            concatMultiSourceToTemp(
                context = context,
                clipsInPlayOrder = resolveClips(s.clips, s.sources).filter { !it.removed },
                sources = s.sources,
                outputFile = tempFile,
                canvasWidth = s.sourceWidth,
                canvasHeight = s.sourceHeight,
                overlayUri = s.audioOverlayUri?.let { Uri.parse(it) },
                overlayStartMs = s.audioTrimStartMs,
                overlayEndMs = s.audioTrimEndMs,
                overlayOffsetMs = s.audioOffsetMs,
                sourceGain = s.originalAudioGain,
                musicGain = s.musicAudioGain,
                colorMatrix4x4 = s.effectiveColorMatrix4x4(),
                cropRect = s.cropRect,
                cropSourceWidth = s.sourceWidth,
                cropSourceHeight = s.sourceHeight,
                onProgress = { p -> _state.update { it.copy(saveProgress = p) } },
            )
            eu.akoos.photos.util.Mp4CreationTime.stamp(tempFile, captureTimestampMs)
            VideoSaveResult.Success(
                insertReencodedCopy(tempFile, s.displayName, s.mimeType, editTimestampMs),
            )
        } finally {
            tempFile.delete()
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
        finalRotation: Int,
        editTimestampMs: Long,
        captureTimestampMs: Long,
    ): VideoSaveResult = withContext(Dispatchers.IO) {
        val sourceUriStr = s.sourceUri ?: error("No source URI")
        val sourceUri = Uri.parse(sourceUriStr)
        val tempFile = createTempMuxFile()
        try {
            val crop = cropInSourcePixels(s)
            val (musicStartUs, musicEndUs) = s.effectiveAudioTrimUs()
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
                colorMatrix = s.effectiveColorMatrix4x4(),
                audioOverlayUri = s.audioOverlayUri?.let { Uri.parse(it) },
                audioTrimStartUs = musicStartUs,
                audioTrimEndUs = musicEndUs,
                overlayOffsetUs = s.audioOffsetMs * 1000L,
                originalAudioGain = s.originalAudioGain,
                musicAudioGain = s.musicAudioGain,
                keptWindowsUs = s.exportWindows().map { it.startMs * 1000L..it.endMs * 1000L },
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
        s: VideoEditorUiState,
        sourceUri: Uri,
        displayName: String,
        mimeType: String,
        windows: List<VideoSegment>,
        orientationDegrees: Int,
        captureTimestampMs: Long,
        stripAudio: Boolean = false,
        editTimestampMs: Long = System.currentTimeMillis(),
    ): Uri? = withContext(Dispatchers.IO) {
        val tempFile = createTempMuxFile()
        try {
            muxTrimmedOrReencode(
                s = s,
                sourceUri = sourceUri,
                outputFile = tempFile,
                finalRotation = orientationDegrees,
                windows = windows,
                stripAudio = stripAudio,
                onProgress = { p -> _state.update { it.copy(saveProgress = p) } },
                isActive = { isActive },
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
     * Trims via the lossless stream-copy muxer, falling back to a re-encode when the muxer rejects the
     * stream. Some sources (B-frame HEVC clips are the common case) hand the platform MP4 muxer a
     * non-monotonic decode-order timeline it refuses to write; on any failure the partial output is
     * deleted and the same kept [windows] are re-encoded through [VideoReencoder.transcode], which lays
     * down a clean keyframe-led timeline. The lossless path stays first; only a thrown error re-encodes.
     */
    private fun muxTrimmedOrReencode(
        s: VideoEditorUiState,
        sourceUri: Uri,
        outputFile: File,
        finalRotation: Int,
        windows: List<VideoSegment>,
        stripAudio: Boolean,
        onProgress: (Float) -> Unit,
        isActive: () -> Boolean,
    ) {
        try {
            muxTrimmed(
                sourceUri = sourceUri,
                outputFile = outputFile,
                windows = windows,
                orientationDegrees = finalRotation,
                stripAudio = stripAudio,
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Stream-copy trim rejected by the muxer; re-encoding instead", e)
            outputFile.delete()
            val crop = cropInSourcePixels(s)
            val (musicStartUs, musicEndUs) = s.effectiveAudioTrimUs()
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
                colorMatrix = s.effectiveColorMatrix4x4(),
                audioOverlayUri = s.audioOverlayUri?.let { Uri.parse(it) },
                audioTrimStartUs = musicStartUs,
                audioTrimEndUs = musicEndUs,
                overlayOffsetUs = s.audioOffsetMs * 1000L,
                originalAudioGain = s.originalAudioGain,
                musicAudioGain = s.musicAudioGain,
                keptWindowsUs = windows.map { it.startMs * 1000L..it.endMs * 1000L },
                onProgress = onProgress,
                isActive = isActive,
            )
        }
    }

    /**
     * The core MediaExtractor + MediaMuxer stream-copy pipeline. Writes one or more kept [windows] of
     * the source into a single MP4, losslessly (the bitstream is copied, never re-encoded).
     *
     * Steps:
     *  1. Open MediaExtractor on the source URI via ContentResolver.openFileDescriptor.
     *  2. For each track, copy its MediaFormat into a new MediaMuxer; remember the
     *     mapping from source track index → muxer track index.
     *  3. Set the orientation hint on the muxer (must happen BEFORE muxer.start()).
     *  4. Per track, copy each kept window in order, seeking to the window's preceding sync sample
     *     (SYNC_PREVIOUS so we don't land mid-GOP) and rebasing each sample's timestamp past the kept
     *     duration already written, so several windows join into one continuous timeline for BOTH the
     *     video and the audio track.
     *  5. Release everything in finally (stop() before release() on the muxer or the moov atom rots).
     *
     * Cut points are keyframe-aligned: a stream copy can only begin at a sync sample, so each window's
     * in-point snaps to the nearest earlier keyframe. Frame-exact cuts would need a re-encode, which
     * this path deliberately avoids to keep the export lossless.
     */
    private fun muxTrimmed(
        sourceUri: Uri,
        outputFile: File,
        windows: List<VideoSegment>,
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

            // A missing list would mean "no kept range", which can't happen from the save paths
            // (they resolve to at least one window); fall back to copying the whole track so an
            // empty list never yields a partial file.
            val keep = windows.ifEmpty { listOf(VideoSegment(0L, Long.MAX_VALUE / 2000L)) }

            for (srcIdx in 0 until trackCount) {
                val muxIdx = trackMap[srcIdx]
                if (muxIdx < 0) continue
                extractor.selectTrack(srcIdx)
                if (keep.size == 1) {
                    // Single kept window: byte-for-byte the original trim path, so a never-split
                    // clip saves exactly as before.
                    val startUs = keep[0].startMs * 1000L
                    val endUs = keep[0].endMs * 1000L
                    // SYNC_PREVIOUS keeps the first sample after seek a keyframe, required for
                    // video to decode correctly. For audio it lands on the closest packet, fine.
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
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
                } else {
                    // Several kept windows: copy each in order, aligning every window's START (not the
                    // keyframe before it) to the output timeline. Each window mirrors the single-window
                    // path: skip the leading GOP (pts < winStart) so audio and video both begin at the cut
                    // point and stay in A/V sync, offset by winStart, and keep the true intra-window PTS so
                    // B-frame reordering survives. Because every kept pts >= winStart, each output pts is
                    // >= writtenUs, so windows never overlap and need no monotonic clamp (which previously
                    // rewrote B-frame timestamps and, by basing on the keyframe, drifted A/V per cut).
                    var writtenUs = 0L      // output pts where the NEXT window begins (past all written so far)
                    var lastOutUs = -1L     // greatest output pts emitted for this track
                    for (win in keep) {
                        val winStartUs = win.startMs * 1000L
                        val winEndUs = win.endMs * 1000L
                        extractor.seekTo(winStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        while (true) {
                            buffer.clear()
                            val read = try {
                                extractor.readSampleData(buffer, 0)
                            } catch (e: IllegalArgumentException) {
                                val grown = buffer.capacity() * 2
                                if (grown > (100 shl 20)) throw e
                                buffer = ByteBuffer.allocate(grown)
                                continue
                            }
                            if (read < 0) break
                            val pts = extractor.sampleTime
                            if (pts > winEndUs) break
                            if (pts >= winStartUs) {
                                info.offset = 0
                                info.size = read
                                val outUs = (writtenUs + (pts - winStartUs)).coerceAtLeast(0L)
                                info.presentationTimeUs = outUs
                                info.flags = extractorFlagsToBufferFlags(extractor.sampleFlags)
                                muxer.writeSampleData(muxIdx, buffer, info)
                                if (outUs > lastOutUs) lastOutUs = outUs
                            }
                            if (!extractor.advance()) break
                        }
                        // The next window resumes just past the greatest output pts written for this one.
                        if (lastOutUs >= 0L) writtenUs = lastOutUs + 1
                    }
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
     * Best-effort total bytes of every registered source's own file, used only to preflight the
     * multi-source concat's disk need. A `file://` source is measured on disk; any other is measured
     * through its content descriptor. A source whose size can't be read contributes 0, so the caller
     * skips the check when the whole sum is 0 rather than blocking a save on an unknown.
     */
    private fun multiSourceSourceBytes(s: VideoEditorUiState): Long =
        s.sources.sumOf { src ->
            runCatching {
                val uri = Uri.parse(src.uri)
                if (uri.scheme == "file") {
                    uri.path?.let { File(it).length() } ?: 0L
                } else {
                    context.contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize.coerceAtLeast(0L) } ?: 0L
                }
            }.getOrDefault(0L)
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
