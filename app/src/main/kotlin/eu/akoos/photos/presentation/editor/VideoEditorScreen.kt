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

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package eu.akoos.photos.presentation.editor

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.albums.AlbumPhotoPickerViewModel
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.ErrorPopup
import eu.akoos.photos.presentation.common.decoderFallbackRenderersFactory
import eu.akoos.photos.presentation.common.rememberVideoFilmstripFrames
import eu.akoos.photos.presentation.editor.components.SaveOptionRow
import eu.akoos.photos.presentation.editor.components.VideoClipTimeline
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.common.ActionSheetRow
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PanelChip
import eu.akoos.photos.presentation.theme.TrackBg
import eu.akoos.photos.presentation.theme.pillShape
import eu.akoos.photos.presentation.util.formatVideoTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Multi-source preview playback (a dynamic ExoPlayer playlist of clipped items) is OFF: it repeatedly
 * tripped an internal ExoPlayer assertion (updatePlaybackInfo IllegalStateException) that crashed the
 * editor with two videos on the timeline. With it off the preview plays the primary source and the whole
 * timeline (trim / split / reorder / delete / add) still edits normally; a robust multi-source preview is
 * a later, separate piece. Flip to true to re-enable the playlist path.
 */
private const val MULTI_SOURCE_PREVIEW_ENABLED = false

// The cut timeline's filmstrip densifies as the user pinch-zooms in, so a long clip is not shown as a
// dozen frames stretched wide. The live zoom maps to a frame count in a few tiers; the extractor re-keys
// on the count and re-extracts. Zoom OUT falls back to the base count, so the un-zoomed case is unchanged.
// The cap is 32: at 320px a decoded ARGB frame is ~410 KB, so ~13 MB per source at the top tier.
private val FILMSTRIP_FRAME_TIERS = intArrayOf(12, 18, 24, 32)
// Zoom at which the count steps UP a tier (index = the tier being left). The step-DOWN thresholds sit a
// little lower, so a small wobble at a boundary does not flip tiers and thrash re-extraction (hysteresis).
private val FILMSTRIP_ZOOM_STEP_UP = floatArrayOf(1.8f, 3f, 5f)
private val FILMSTRIP_ZOOM_STEP_DOWN = floatArrayOf(1.6f, 2.7f, 4.5f)

/** The filmstrip frame count for a given pinch [zoom], stepping from the [current] count with hysteresis
 *  (see the tier tables). Returns the base tier when zoomed out, so the common case stays at 12. */
private fun filmstripFrameCountFor(zoom: Float, current: Int): Int {
    var tier = FILMSTRIP_FRAME_TIERS.indexOf(current).coerceAtLeast(0)
    while (tier < FILMSTRIP_FRAME_TIERS.lastIndex && zoom >= FILMSTRIP_ZOOM_STEP_UP[tier]) tier++
    while (tier > 0 && zoom < FILMSTRIP_ZOOM_STEP_DOWN[tier - 1]) tier--
    return FILMSTRIP_FRAME_TIERS[tier]
}

private enum class VideoTool(val labelRes: Int, val icon: ImageVector) {
    Trim(R.string.video_editor_trim, Icons.Default.ContentCut),
    Crop(R.string.video_editor_crop, Icons.Default.Crop),
    Rotate(R.string.video_editor_rotate, Icons.AutoMirrored.Filled.RotateRight),
    Filter(R.string.editor_tool_filter, Icons.Default.AutoFixHigh),
    Adjust(R.string.editor_tool_adjust, Icons.Default.Tune),
    Audio(R.string.video_editor_audio_lane, Icons.AutoMirrored.Filled.VolumeUp),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen(
    localUri: String?,
    localDisplayName: String?,
    localMimeType: String?,
    /** Non-null routes the editor through the cloud flow — VM downloads, edits, re-uploads. */
    cloudPhoto: eu.akoos.photos.domain.entity.CloudPhoto? = null,
    /** The album linkId to re-attach the re-uploaded edit to (if the video came from an
     *  album view). Null = sit at the photo timeline root. */
    sourceAlbumLinkId: String? = null,
    /** Non-null when the editor was opened on a Synced video (device + cloud). The local
     *  save path then propagates the edit up to Drive too so the cloud version doesn't
     *  stay stale. cloudPhoto remains null in this case because the EDIT SOURCE is the
     *  device file — the cloud counterpart is just a side-effect target. */
    syncedCloudCounterpart: eu.akoos.photos.domain.entity.CloudPhoto? = null,
    /** Non-null when the editor was launched via Intent.ACTION_EDIT / ACTION_VIEW from
     *  outside the app (system "Open with" / "Edit with" chooser). Routes the load
     *  through [VideoEditorViewModel.loadExternal] so the save flow forces a fresh
     *  MediaStore copy in the editor's default video output directory — the foreign URI
     *  is never overwritten. Defaulted to null so the existing internal call sites
     *  compile unchanged. */
    externalRequest: eu.akoos.photos.navigation.ExternalEditRequest? = null,
    /** Stable key ([AlbumPhotoPickerViewModel.stableKeyOf]) of the gallery item the editor opened on, so
     *  the primary source carries a gallery identity the source manager can pre-select and lock. Null for
     *  an External entry, which has no gallery item. */
    primaryGalleryKey: String? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    /** The source manager's confirmed selection, as the FULL set of videos that should be on the timeline:
     *  keys not yet a source are added, sources whose key is no longer selected are removed. Null means no
     *  pending pick (skip); a non-null list (even empty) is a returned selection, so an empty one still
     *  removes every deselected added source. */
    pendingAddVideos: List<GalleryItem>? = null,
    /** Clears [pendingAddVideos] in the nav scope once the effect has reconciled it, so a later edit does
     *  not re-apply the same selection. */
    onPendingAddVideosConsumed: () -> Unit = {},
    /** Opens the source manager (the in-app video picker) for the "+" menu's "Add video". Passes the keys
     *  already on the timeline as [preselected] and the primary's key as [locked] so the picker seeds its
     *  selection and blocks deselecting the primary. The confirmed selection returns via [pendingAddVideos].
     *  Defaulted to a no-op so external call sites compile unchanged. */
    onAddVideoRequested: (preselected: List<String>, locked: List<String>) -> Unit = { _, _ -> },
    vm: VideoEditorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var activeTool by remember { mutableStateOf(VideoTool.Trim) }
    // Selected clip id, hoisted here so a tap anywhere outside the track (e.g. the preview) clears it and
    // hides the clip-action pill; the cut track (TrimPanel) drives it too.
    var selectedClipId by remember { mutableStateOf<Long?>(null) }
    // The shape the crop is locked to. Screen-level (not VM state) so tapping a chip never spawns
    // an undo entry, and it outlives a tool switch on purpose because the committed crop does too.
    var lockedAspect by remember { mutableStateOf(CropAspect.Free) }
    // A quarter turn swaps the displayed axes, so the locked shape turns with it and 4:3 becomes
    // 3:4. Driven off rotationDegrees, the same signal the crop overlay rotates on, so the lit chip
    // keeps matching what the user sees. A half turn leaves the shape alone. Unlike the photo editor
    // the crop rect itself is NOT transposed here: both the player and the crop Canvas ride one
    // graphicsLayer, so the rect already follows the turn on screen.
    var lastLockRotation by remember { mutableStateOf(state.rotationDegrees) }
    androidx.compose.runtime.LaunchedEffect(state.rotationDegrees) {
        val now = state.rotationDegrees
        if (((now - lastLockRotation) / 90) % 2 != 0) lockedAspect = lockedAspect.turned()
        lastLockRotation = now
    }
    var showSaveSheet by remember { mutableStateOf(false) }
    val saveSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(localUri, cloudPhoto?.linkId, externalRequest?.uri) {
        when {
            // External entries (system "Open with" / "Edit with" chooser) take priority
            // over the local path so a caller passing both an externalRequest and stale
            // local* params still routes through the always-copy save flow.
            externalRequest != null -> vm.loadExternal(
                externalRequest.uri, externalRequest.displayName, externalRequest.mimeType, galleryKey = primaryGalleryKey,
            )
            cloudPhoto != null -> vm.loadCloud(cloudPhoto, sourceAlbumLinkId, galleryKey = primaryGalleryKey)
            localUri != null   -> vm.loadLocal(localUri, localDisplayName ?: "video.mp4", localMimeType ?: "video/mp4", galleryKey = primaryGalleryKey)
        }
    }

    // Inform the VM about the source album so save()'s cloud fan-out re-attaches the new
    // linkId to the same album the source lived in. Mirrors PhotoEditorScreen's wiring.
    androidx.compose.runtime.LaunchedEffect(sourceAlbumLinkId) {
        vm.setSourceAlbumLinkId(sourceAlbumLinkId)
    }
    // Inform the VM about the cloud counterpart (Synced case) so save() can propagate the
    // edit to Drive after the local file is written.
    androidx.compose.runtime.LaunchedEffect(syncedCloudCounterpart?.linkId) {
        vm.setCloudCounterpart(syncedCloudCounterpart)
    }

    // The source manager's confirmed selection, reconciled against the timeline as a diff. Null is the
    // no-pick sentinel (initial composition, or after a consume), so it never wipes on first frame; a
    // non-null list is a real return, applied even when empty so a deselect-all still removes the added
    // sources. Keys use [AlbumPhotoPickerViewModel.stableKeyOf] so they match the picker's own keyspace.
    // Additions: a device-backed pick (LocalOnly / Synced) plays from its own file with no download and no
    // account; a cloud-only pick routes through the VM's download path (account-only). Removals drop each
    // added source no longer selected, in ONE batch update so the strip redraws once; the primary is
    // protected in the VM (removeVideoSources no-ops on it).
    androidx.compose.runtime.LaunchedEffect(pendingAddVideos) {
        val picked = pendingAddVideos ?: return@LaunchedEffect
        val selectedKeys = picked.map { AlbumPhotoPickerViewModel.stableKeyOf(it) }.toSet()
        val existingKeys = state.sources.mapNotNull { it.galleryKey }.toSet()
        picked.forEach { item ->
            val key = AlbumPhotoPickerViewModel.stableKeyOf(item)
            if (key !in existingKeys) {
                when (item) {
                    is GalleryItem.LocalOnly -> vm.addVideoSource(item.local.uri, item.local.displayName, galleryKey = key)
                    is GalleryItem.Synced -> vm.addVideoSource(item.local.uri, item.local.displayName, galleryKey = key)
                    is GalleryItem.CloudOnly -> vm.addCloudVideoSource(item.cloud)
                }
            }
        }
        val removeIds = state.sources
            .filter { it.id != PRIMARY_SOURCE_ID && it.galleryKey != null && it.galleryKey !in selectedKeys }
            .map { it.id }.toSet()
        if (removeIds.isNotEmpty()) vm.removeVideoSources(removeIds)
        onPendingAddVideosConsumed()
    }

    // Side effects on save completion. LaunchedEffect rather than remember{} so the
    // body runs OUTSIDE composition — calling navController.popBackStack() (via onSaved)
    // during composition leaves the ModalBottomSheet half-dismissed and the user has to
    // re-tap save. Firing the effect after composition lets the sheet animate out as the
    // screen pops normally.
    androidx.compose.runtime.LaunchedEffect(state.saveResult) {
        when (state.saveResult) {
            is VideoSaveResult.Success -> {
                showSaveSheet = false
                vm.consumeSaveResult()
                onSaved()
            }
            is VideoSaveResult.Failed -> {
                // Surface a Toast with the error so the user sees WHAT failed — the
                // previous flow dismissed the sheet (`showSaveSheet = false`) before the
                // failure message could be rendered, leaving them with no feedback at
                // all. The failure message also stays in state for the inline label
                // below the panel; we leave `vm.consumeSaveResult()` deliberately
                // un-called so the inline error persists until the next save attempt.
                android.widget.Toast.makeText(
                    context,
                    (state.saveResult as VideoSaveResult.Failed).message,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                showSaveSheet = false
            }
            null -> Unit
        }
    }

    // Transient one-shot feedback (source cap reached, an add that could not complete): a toast, then
    // cleared, so it never lingers as inline red text the way a save failure does.
    androidx.compose.runtime.LaunchedEffect(state.userMessage) {
        state.userMessage?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            vm.consumeUserMessage()
        }
    }

    // External-entry "Saved a copy" feedback. For Intent.ACTION_EDIT / ACTION_VIEW
    // entries the save flow is forced to a fresh MediaStore copy in the editor's default
    // video output directory (the foreign URI is never overwritten); the user needs to
    // know that's what just happened. The flag is set inside the VM after a successful
    // External save and consumed here so it raises the Toast exactly once.
    androidx.compose.runtime.LaunchedEffect(state.savedAsCopy) {
        if (state.savedAsCopy) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.editor_saved_as_copy),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            vm.consumeSavedAsCopy()
        }
    }

    // One-shot frame-grab feedback: a still was written to the gallery, or the grab failed.
    androidx.compose.runtime.LaunchedEffect(state.frameGrabResult) {
        when (val r = state.frameGrabResult) {
            is FrameGrabResult.Success -> {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.video_editor_frame_saved),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                vm.consumeFrameGrabResult()
            }
            is FrameGrabResult.Failed -> {
                android.widget.Toast.makeText(
                    context, r.message, android.widget.Toast.LENGTH_LONG,
                ).show()
                vm.consumeFrameGrabResult()
            }
            null -> Unit
        }
    }

    // Hoisted ExoPlayer at the SCREEN level so it survives tab swaps. The previous
    // version owned the player inside VideoPreview's remember(uri); the Crop tab unmounts
    // VideoPreview to show a static first-frame canvas, which fired the DisposableEffect's
    // onDispose → player.release(). Coming back to Trim/Rotate/Audio re-created the player
    // from scratch — the user saw a black surface for a beat and had to tap play again.
    // Owning the player here keeps it alive across the tab `when` switch; VideoPreview
    // just attaches its PlayerView to the existing instance.
    val sourceUri = state.sourceUri
    val previewPlayer = remember(sourceUri) {
        if (sourceUri == null) {
            null
        } else {
            // Cap the preview decode to 1080p. A very-high-res source (8K, 7680x4320)
            // would otherwise ask MediaCodec for a decoder at native resolution, which
            // exceeds most devices' hardware ceiling; the renderer then fails silently and
            // the surface stays black. The track selector picks a decode size the device
            // can actually handle so the preview plays. The save/re-encode path is separate
            // and still works at full resolution.
            val trackSelector = DefaultTrackSelector(context).apply {
                parameters = buildUponParameters().setMaxVideoSize(1920, 1080).build()
            }
            ExoPlayer.Builder(context, decoderFallbackRenderersFactory(context)).setTrackSelector(trackSelector).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(sourceUri)))
                prepare()
                // Start with playWhenReady=true so the renderer immediately decodes and
                // paints frame 0 to the surface — the listener below pauses on first
                // STATE_READY, by which point the user sees the still image instead of
                // a black void. Without this, playWhenReady=false leaves the renderer
                // idle and the surface stays black until the user taps play.
                playWhenReady = true
                repeatMode = ExoPlayer.REPEAT_MODE_ONE
                // Frame-accurate seeks so playhead scrubbing lands on the exact frame; scrubbing
                // mode (toggled during a playhead drag, below) keeps the rapid seeks smooth.
                setSeekParameters(SeekParameters.EXACT)
                // Pause + rewind at the FIRST ready, at the player itself rather than inside a preview
                // panel — so it holds whichever tab is showing when the source finishes loading (the Crop
                // panel installs no such listener, so a source that became ready there used to auto-play
                // with sound). Only the first ready; later readys (a re-buffer, a multi-source item swap)
                // must not yank playback.
                var didFirstReadyPause = false
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == androidx.media3.common.Player.STATE_READY && !didFirstReadyPause) {
                            didFirstReadyPause = true
                            pause()
                            seekTo(0L)
                        }
                    }
                })
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(previewPlayer) {
        onDispose { previewPlayer?.release() }
    }
    // Real-time preview gain — without this the volume slider only changes the saved
    // bytes and the user hears the same playback in the editor regardless of the
    // value, leading to "the slider does nothing" reports. ExoPlayer.volume accepts
    // [0..1] which maps 1:1 onto our originalAudioGain field.
    androidx.compose.runtime.LaunchedEffect(previewPlayer, state.originalAudioGain) {
        previewPlayer?.volume = state.originalAudioGain.coerceIn(0f, 1f)
    }
    // Loop mode depends on source count. Single source: REPEAT_MODE_ONE loops the one file seamlessly.
    // Multi-source: REPEAT_MODE_OFF, because the poll drives every clip transition (including the loop back
    // to clip 0 and the swaps between sources) itself; a repeating item would loop the first source forever
    // and the playhead would never reach the added video.
    androidx.compose.runtime.LaunchedEffect(previewPlayer, state.isMultiSource) {
        previewPlayer?.repeatMode =
            if (state.isMultiSource) ExoPlayer.REPEAT_MODE_OFF else ExoPlayer.REPEAT_MODE_ONE
    }

    // Multi-source preview: a single ExoPlayer can't hold two different files, so once the "+" has added a
    // second video the preview plays a PLAYLIST of clipped media items (one per kept clip, in play order),
    // looping the whole list. Rebuilt (debounced) when the clip structure changes; the common single-source
    // case keeps the original single-item + poll-seek path untouched. Save stays guarded until the concat
    // export lands, so this is preview-only.
    LaunchedEffect(previewPlayer) {
        if (!MULTI_SOURCE_PREVIEW_ENABLED) return@LaunchedEffect
        val p = previewPlayer ?: return@LaunchedEffect
        var appliedMulti = false
        var appliedSig: List<Int>? = null
        snapshotFlow {
            val kept = resolveClips(state.clips, state.sources).filter { !it.removed }
            // Rebuild the playlist ONLY when the structure changes (a source added / removed / reordered),
            // never on a trim: re-preparing the player on every trim frame tripped an internal ExoPlayer
            // assertion (the updatePlaybackInfo crash). A trim leaves the clipping as last built — the
            // multi-source preview is best-effort.
            state.isMultiSource to kept.map { it.sourceId }
        }.collectLatest { (multi, sig) ->
            if (!multi) {
                if (appliedMulti) {
                    // Back to one source (the add was undone): restore the single full item so the
                    // single-source poll-seek path drives playback again.
                    val uri = state.sources.firstOrNull()?.uri ?: state.sourceUri
                    if (uri != null) {
                        runCatching {
                            p.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                            p.repeatMode = ExoPlayer.REPEAT_MODE_ONE
                            p.prepare()
                        }
                    }
                    appliedMulti = false
                    appliedSig = null
                }
                return@collectLatest
            }
            if (sig == appliedSig) return@collectLatest
            kotlinx.coroutines.delay(250)
            val keptNow = resolveClips(state.clips, state.sources).filter { !it.removed }
            val items = keptNow.mapNotNull { c ->
                val uri = state.sources.uriOf(c.sourceId) ?: return@mapNotNull null
                val srcDur = state.sources.durationOf(c.sourceId)
                val base = MediaItem.fromUri(Uri.parse(uri))
                val start = c.startMs.coerceAtLeast(0L)
                // Clamp the end to the real source duration (a reported length can run a few ms past the
                // container, and endPositionMs >= the true duration trips a ClippingMediaSource assertion);
                // a full-span clip skips clipping entirely.
                val end = if (srcDur > 0L) c.endMs.coerceIn(start + 1L, srcDur) else c.endMs
                val isFull = start <= 0L && (srcDur <= 0L || end >= srcDur)
                if (isFull) {
                    base
                } else {
                    base.buildUpon()
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(start)
                                .setEndPositionMs(end)
                                .build(),
                        )
                        .build()
                }
            }
            if (items.isNotEmpty()) {
                runCatching {
                    val resumeIndex = p.currentMediaItemIndex.coerceIn(0, items.lastIndex)
                    p.setMediaItems(items, resumeIndex, 0L)
                    p.repeatMode = ExoPlayer.REPEAT_MODE_ALL
                    p.prepare()
                }
                appliedMulti = true
                appliedSig = sig
            }
        }
    }

    // Music overlay preview — a separate ExoPlayer for the picked audio file so the
    // user hears the music alongside the video in the editor (matches what the save
    // pipeline will mix). Sync is best-effort: play/pause follows the video player and
    // we seek the overlay to its trim-start whenever the video position jumps via
    // scrub. Without this the music gain slider was inaudible until after save.
    val overlayPlayer = remember(state.audioOverlayUri) {
        if (state.audioOverlayUri == null) null
        else ExoPlayer.Builder(context, decoderFallbackRenderersFactory(context)).build().apply {
            playWhenReady = false
            repeatMode = ExoPlayer.REPEAT_MODE_OFF
        }
    }
    androidx.compose.runtime.DisposableEffect(overlayPlayer) {
        onDispose { overlayPlayer?.release() }
    }
    androidx.compose.runtime.LaunchedEffect(overlayPlayer, state.musicAudioGain) {
        overlayPlayer?.volume = state.musicAudioGain.coerceIn(0f, 1f)
    }
    // Preview only the chosen slice, not the whole file: clip the overlay item to [trim start, end].
    // Debounced so a trim-handle drag doesn't re-prepare the decoder every frame.
    androidx.compose.runtime.LaunchedEffect(
        overlayPlayer, state.audioOverlayUri, state.audioTrimStartMs, state.audioTrimEndMs,
    ) {
        val ov = overlayPlayer ?: return@LaunchedEffect
        val uri = state.audioOverlayUri ?: return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        val end = state.audioTrimEndMs.coerceAtLeast(state.audioTrimStartMs + 1L)
        ov.setMediaItem(
            MediaItem.fromUri(Uri.parse(uri)).buildUpon()
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(state.audioTrimStartMs.coerceAtLeast(0L))
                        .setEndPositionMs(end)
                        .build(),
                )
                .build(),
        )
        ov.prepare()
        // Start the music immediately if the video is already playing: the sync listener below only
        // reacts to a play/pause CHANGE, so music added mid-playback used to stay silent until the user
        // toggled play. Match the current state right after (re)preparing the overlay.
        if (previewPlayer?.playWhenReady == true) ov.play() else ov.pause()
    }
    // Follow the video player's play / pause / seek so the two stay in lockstep during
    // preview. The video player is the timeline source of truth; the overlay just
    // mirrors. Seek maps video-position → overlay-position via the user's trim offsets:
    // when the video is at trimStartMs the overlay should be at audioTrimStartMs.
    androidx.compose.runtime.LaunchedEffect(previewPlayer, overlayPlayer) {
        // Keyed only on the two players: the listener body reads neither trim value, so keying on them
        // tore down and re-added the listener on every trim-drag frame (a play/pause in the teardown gap
        // could miss the overlay).
        val pv = previewPlayer ?: return@LaunchedEffect
        val ov = overlayPlayer ?: return@LaunchedEffect
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) ov.play() else ov.pause()
            }
            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int,
            ) {
                // The overlay item is clipped to the chosen slice, so its own positions are 0-based from
                // the slice start. Restart the slice on a video seek AND on the loop point: the single-source
                // preview repeats the video (REPEAT_MODE_ONE), but the music overlay is REPEAT_MODE_OFF and
                // would otherwise end once and stay silent forever after the first loop: the "music
                // disappears and the volume slider does nothing" report. A loop fires AUTO_TRANSITION, so
                // treat it like a seek back to the start: rewind the music and resume it with the video. This
                // also matches the export, which plays the music once from the start of each pass.
                if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK ||
                    reason == androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                ) {
                    ov.seekTo(0L)
                    if (pv.isPlaying) ov.play()
                }
            }
        }
        pv.addListener(listener)
        // try/finally — the previous `awaitCancellation().also { ... }` block never ran
        // its cleanup because awaitCancellation throws CancellationException on scope
        // cancel, which short-circuits the `.also` lambda. The listener then leaked into
        // the next composition pass and kept observing the dead player.
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            pv.removeListener(listener)
        }
    }
    // Offset gate: the music block occupies [audioOffsetMs, audioOffsetMs + sliceLen) on the timeline, so
    // during playback the overlay is audible only inside that block and its slice-local position tracks
    // (videoPosition - offset). Keyed on the two players alone so a trim / offset drag never tears it down;
    // the live offset and trim are read through rememberUpdatedState so each poll tick sees fresh values
    // (a Unit-keyed effect would otherwise capture the values from first composition). This folds together
    // with the discontinuity listener above: on a loop the video wraps to 0 and this gate re-mutes the
    // music until the playhead reaches the offset again.
    val liveAudioOffsetMs by androidx.compose.runtime.rememberUpdatedState(state.audioOffsetMs)
    val liveAudioTrimStartMs by androidx.compose.runtime.rememberUpdatedState(state.audioTrimStartMs)
    val liveAudioTrimEndMs by androidx.compose.runtime.rememberUpdatedState(state.audioTrimEndMs)
    androidx.compose.runtime.LaunchedEffect(previewPlayer, overlayPlayer) {
        val pv = previewPlayer ?: return@LaunchedEffect
        val ov = overlayPlayer ?: return@LaunchedEffect
        while (true) {
            // Only steer the overlay while the video plays; when the video pauses the existing
            // onIsPlayingChanged mirroring pauses the overlay, and this loop leaves it alone.
            if (pv.isPlaying) {
                val offset = liveAudioOffsetMs.coerceAtLeast(0L)
                val sliceLen = (liveAudioTrimEndMs - liveAudioTrimStartMs).coerceAtLeast(0L)
                val videoPos = pv.currentPosition
                when {
                    sliceLen <= 0L -> {
                        // No music slice to place.
                    }
                    videoPos < offset -> {
                        // Before the block: keep the music silent and rewound to its slice start.
                        if (ov.isPlaying) ov.pause()
                        if (ov.currentPosition != 0L) ov.seekTo(0L)
                    }
                    videoPos < offset + sliceLen -> {
                        // Inside the block: the overlay is clipped to its slice (0-based), so its position
                        // should track (videoPos - offset). Correct only a real drift so ordinary 1x-vs-1x
                        // jitter does not cause a constant stream of re-seeks.
                        val expected = (videoPos - offset).coerceIn(0L, sliceLen)
                        if (kotlin.math.abs(ov.currentPosition - expected) > 120L) ov.seekTo(expected)
                        if (!ov.isPlaying) ov.play()
                    }
                    else -> {
                        // Past the block's end: the music has finished for this pass, so hold it paused.
                        if (ov.isPlaying) ov.pause()
                    }
                }
            }
            kotlinx.coroutines.delay(120L)
        }
    }

    // Save is allowed when we have a source URI to edit — local OR a downloaded
    // cloud file. The cloud branch flips the URI on inside loadCloud once the file
    // lands in cache, so isLoading guards the early window.
    val hasSource = state.sourceUri != null && !state.isLoading

    // The cut timeline reports its live pinch-zoom here; a debounced tier map ([filmstripFrameCountFor])
    // turns that into how many frames the PRIMARY strip extracts, so zooming in re-extracts a denser strip
    // instead of stretching a dozen frames wide. Held at the screen scope because the primary extractor
    // lives here. timelineZoom is read only by the debounce collector below (never during composition), so
    // reporting a zoom on every pinch frame does not recompose the screen; only a settled tier change writes
    // filmstripFrameCount, which re-keys the hook.
    var timelineZoom by remember { mutableFloatStateOf(1f) }
    var filmstripFrameCount by remember { mutableIntStateOf(FILMSTRIP_FRAME_TIERS.first()) }
    LaunchedEffect(Unit) {
        snapshotFlow { timelineZoom }.collectLatest { z ->
            // collectLatest cancels this block when a newer zoom arrives, so the delay only elapses ~300 ms
            // after a pinch settles: a debounce that keeps a pinch from thrashing the extractor.
            kotlinx.coroutines.delay(300L)
            val next = filmstripFrameCountFor(z, filmstripFrameCount)
            if (next != filmstripFrameCount) filmstripFrameCount = next
        }
    }

    // Filmstrip frames come from the shared extractor so swapping tools (Trim to Crop to
    // Trim) does NOT throw away the bitmaps and re-extract; the hook fills each slot as it
    // decodes and caches the finished strip, so the inner `when (activeTool)` branch swap
    // reads back an already-warm strip. 320 px keeps the 64dp-tall strip crisp on 3x density.
    // The extractor sources the clip length itself and keys on the URI, so the strip fills as
    // soon as the source lands; state.durationMs is only a fallback if the container omits it.
    // The trim UI still takes its window from state.durationMs, which is unaffected here.
    // frameCount is the zoom-adaptive count above; the hook re-keys and re-extracts when it changes.
    val filmstrip = rememberVideoFilmstripFrames(
        uri = state.sourceUri?.let { Uri.parse(it) },
        frameCount = filmstripFrameCount,
        targetPx = 320,
        fallbackDurationMs = state.durationMs,
    )
    val filmstripThumbnails = filmstrip.frames
    // Free the extracted frames the moment the editor leaves composition instead of waiting
    // for the nav entry to pop and GC to run. release() evicts this clip from the shared cache
    // and recycles its frames; nothing else holds a reference once this screen is gone.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { filmstrip.release() }
    }

    // Extra timeline sources (added via the "+" on the cut track) each get their own filmstrip, kept in a
    // screen-owned map rather than the shared LRU cache: several strips are on screen at once, so the LRU
    // would recycle one still being drawn. The primary keeps the cached hook above. A long-lived collector
    // extracts each newly-added source once (its child coroutine is not cancelled when the list grows), and
    // the frames are recycled when the editor leaves.
    // Extras stay at the base count: recycling a denser strip while it is on screen is exactly the hazard
    // that keeps them out of the LRU, so only the primary densifies on zoom. The draw reads each source's
    // own frame-list size, so a primary at a higher count beside base-count extras still tiles correctly.
    val primarySourceId = state.sources.firstOrNull()?.id ?: PRIMARY_SOURCE_ID
    // Held in the ViewModel so the added-source strips survive a trip to the in-app picker (a nav
    // destination that disposes and recomposes this screen); the collector below still fills them.
    val extraSourceFrames = vm.extraSourceFrames
    LaunchedEffect(Unit) {
        snapshotFlow { state.sources.map { it.id to it.uri } }.collect { list ->
            for ((id, uri) in list) {
                if (id == primarySourceId) continue
                // Delegated to the ViewModel so the decode runs on viewModelScope and survives a trip
                // to the in-app picker (a nav destination that disposes this screen). A screen-scoped
                // launch was cancelled mid-decode, and because the target list survives in the VM the
                // strip stayed stuck on placeholder cells for the session. Idempotent per source, so
                // re-running this on return is a no-op for a strip already filling or filled.
                vm.ensureExtraSourceFilmstrip(id, uri, state.sources.durationOf(id))
            }
            // Recycle + drop strips whose source left the timeline (e.g. undoing a "+"), so their ~5 MB of
            // ARGB frames don't stay resident for the whole session and pile up on repeated add/undo.
            val present = list.mapTo(HashSet()) { it.first }
            val stale = extraSourceFrames.keys.filter { it != primarySourceId && it !in present }
            for (id in stale) {
                extraSourceFrames.remove(id)?.forEach { bmp -> if (bmp != null && !bmp.isRecycled) bmp.recycle() }
            }
        }
    }
    // Recycling of the extra-source strips lives in the ViewModel (onCleared), NOT here: they must survive
    // this screen being disposed and recomposed by a trip to the in-app picker, so they are not recycled on
    // dispose. onCleared runs when the editor's back-stack entry is truly gone.
    // Per-source filmstrip lookup for the clip timeline: the primary from the cached hook, extras from the map.
    val thumbnailsOfSource: (Int) -> List<android.graphics.Bitmap?> = { sourceId ->
        if (sourceId == primarySourceId) filmstripThumbnails else extraSourceFrames[sourceId] ?: emptyList()
    }

    // Unsaved-changes guard: if the user touched anything (trim window, crop, rotation,
    // audio overlay), the back press / Close button should ask for confirmation so they
    // don't lose work. Reset state (everything at defaults) → back navigates immediately.
    val hasUnsavedChanges = state.trimStartMs != 0L ||
        (state.durationMs > 0L && state.trimEndMs != state.durationMs) ||
        state.cropRect != null ||
        state.hasColorEdits() ||
        state.rotationDegrees != 0 ||
        state.clips.isNotEmpty() ||
        state.audioOverlayUri != null ||
        // Muting or attenuating the original track is a real, saveable edit (gain 0 drops the track on the
        // copy path, a partial gain forces re-encode); without this, tapping Mute then Back lost it silently.
        state.originalAudioGain < 0.999f
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showUndoSheet by remember { mutableStateOf(false) }
    val confirmedOnBack: () -> Unit = remember(hasUnsavedChanges, onBack) {
        {
            if (hasUnsavedChanges) showDiscardDialog = true else onBack()
        }
    }
    androidx.activity.compose.BackHandler(enabled = hasUnsavedChanges) {
        showDiscardDialog = true
    }

    Column(
        Modifier.fillMaxSize().background(Bg0).statusBarsPadding(),
    ) {
        VideoTopBar(
            isSaving = state.isSaving,
            canGrabFrame = hasSource && previewPlayer != null,
            undoDepth = state.undoDepth,
            canRedo = state.canRedo,
            onUndoMenu = { showUndoSheet = true },
            onRedo = { vm.redo() },
            onFrameGrab = {
                // currentPosition is ms; the retriever seeks in us. Reading it here (main thread)
                // is cheap; the grab + JPEG write run off-thread in the VM. Pass the URI the player
                // actually holds so a multi-source preview grabs the frame from the shown source.
                val posUs = (previewPlayer?.currentPosition ?: 0L).coerceAtLeast(0L) * 1000L
                val loadedUri = previewPlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
                vm.grabCurrentFrame(posUs, loadedUri)
            },
            onBack = confirmedOnBack,
            onSave = { if (hasSource) showSaveSheet = true },
        )

        // ── Preview area ─────────────────────────────────────────────────────
        // Crop tab swaps out the ExoPlayer for the first-frame bitmap so the user can
        // drag corners reliably. Pointer events on a video Surface are unreliable —
        // some devices route them to the video stack and we never see them in Compose.
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                // Tapping the preview (outside the track) clears the clip selection, so the clip-action
                // pill hides — the requested "tap anywhere else to deselect".
                .pointerInput(Unit) {
                    detectTapGestures { selectedClipId = null }
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(color = Accent)
                state.errorMessage != null -> {
                    // Match PhotoEditorScreen: surface load / decode / save errors in
                    // the unified [ErrorPopup] so the user can copy the failure for
                    // a bug report. VM has no clearError hook today — the scrim still
                    // consumes outside-taps, and the existing top-bar Back is the
                    // explicit recovery path.
                    ErrorPopup(
                        title = stringResource(R.string.editor_error_title_video),
                        message = state.errorMessage!!,
                        onDismiss = {},
                        onCopy = {},
                    )
                }
                activeTool == VideoTool.Crop && state.sourceWidth > 0 && state.sourceHeight > 0 && previewPlayer != null ->
                    CropOverPlayer(
                        player = previewPlayer,
                        sourceWidth = state.sourceWidth,
                        sourceHeight = state.sourceHeight,
                        rotationDegrees = state.rotationDegrees,
                        currentCrop = state.cropRect,
                        lockedRatio = sourceLockRatio(
                            lockedAspect,
                            state.sourceWidth.coerceAtLeast(1),
                            state.sourceHeight.coerceAtLeast(1),
                            state.rotationDegrees,
                        ),
                        onCropChange = { vm.setCropRect(it) },
                        onEditBegin = { vm.beginEdit() },
                        onEditCommit = { vm.endEdit() },
                        filterMatrix = state.effectiveColorMatrix4x5(),
                    )
                else -> if (previewPlayer != null) VideoPreview(
                    player = previewPlayer,
                    initialAspect = if (state.sourceWidth > 0 && state.sourceHeight > 0)
                        state.sourceWidth.toFloat() / state.sourceHeight
                    else 16f / 9f,
                    rotationDegrees = state.rotationDegrees,
                    cropRect = state.cropRect,
                    sourceWidth = state.sourceWidth,
                    sourceHeight = state.sourceHeight,
                    filterMatrix = state.effectiveColorMatrix4x5(),
                )
            }
            if (state.addingSource) {
                // A cloud video picked from "+" is downloading before it joins the timeline. A small
                // non-blocking spinner over the preview keeps the current edit visible and the player
                // alive, unlike the full isLoading state used only at first load.
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .background(
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f),
                            androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        )
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Accent)
                }
            }
        }

        // ── Play + speed pill, sits above the bottom panel ───────────────────
        // The Trim tab folds play + time + speed into one transport row inside its own panel, so the
        // shared pill only shows for the other tools (Crop / Rotate / Filter).
        if (hasSource && previewPlayer != null && activeTool != VideoTool.Trim) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Every non-Trim tool shows the same position / length readout in the shared pill (the Trim
                // tab has its own transport). The total comes from the edited timeline so it stays correct
                // even before the player reports a duration, instead of flashing 0:00 / 0:00.
                val pillTotalMs = editedDurationMs(state.clips).takeIf { it > 0L } ?: state.durationMs
                VideoEditorPlayPill(player = previewPlayer!!, showTime = true, totalMs = pillTotalMs)
            }
        }

        // ── Bottom area ──────────────────────────────────────────────────────
        // Independent pill containers floating over Bg0, matching PhotoEditor's
        // recipe. No outer panel wrapper — each row stands on its own with 8dp
        // spacing. The tool panel uses its own pill recipes inside; the tab dock
        // is a single PillBgOpaque capsule with circle icon tabs.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Keep the system back / edge-swipe gesture out of the whole control area: dragging a
                // timeline clip, an audio block, or a slider that starts near the screen edge must not be
                // hijacked into a back navigation mid-edit.
                .systemGestureExclusion()
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Tool panel — horizontal-padding only, no outer pill. The Trim /
            // Crop / Rotate / Audio composables render their own pill recipes.
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).wrapContentHeight(),
            ) {
                AnimatedContent(
                    targetState = activeTool,
                    transitionSpec = {
                        (fadeIn(tween(190)) + slideInVertically(tween(230)) { it / 5 }) togetherWith
                            (fadeOut(tween(150)) + slideOutVertically(tween(190)) { it / 5 })
                    },
                    label = "videoPanel",
                ) { tool ->
                    when (tool) {
                        VideoTool.Trim -> TrimPanel(
                            state = state,
                            vm = vm,
                            previewPlayer = previewPlayer,
                            thumbnailsOf = thumbnailsOfSource,
                            selectedId = selectedClipId,
                            onSelectClip = { selectedClipId = it },
                            onTimelineZoom = { timelineZoom = it },
                            onAddVideoRequested = {
                                // Always opens the manager: at the cap the user deselects one to make room.
                                onAddVideoRequested(
                                    state.sources.mapNotNull { it.galleryKey },
                                    state.sources.firstOrNull { it.id == PRIMARY_SOURCE_ID }?.galleryKey
                                        ?.let { listOf(it) } ?: emptyList(),
                                )
                            },
                        )
                        VideoTool.Crop -> CropPanel(
                            state = state,
                            vm = vm,
                            lockedAspect = lockedAspect,
                            onLockedAspectChange = { lockedAspect = it },
                        )
                        VideoTool.Rotate -> RotatePanel(state = state, vm = vm)
                        VideoTool.Filter -> FilterPanel(state = state, vm = vm, thumbnails = filmstripThumbnails)
                        VideoTool.Adjust -> AdjustPanel(state = state, vm = vm)
                        VideoTool.Audio -> AudioPanel(state = state, vm = vm, previewPlayer = previewPlayer)
                    }
                }
            }

            val saveResult = state.saveResult
            if (saveResult is VideoSaveResult.Failed) {
                Text(
                    text = saveResult.message,
                    color = Color(0xFFFF3B30),
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp),
                )
            }

            // Bottom tab bar — single capsule pill with 44dp circle tabs inside.
            // Matches PhotoEditor's tab bar + GalleryScreen.BottomDock.
            Row(
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .background(PillBgOpaque, RoundedCornerShape(999.dp))
                    .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VideoTool.entries.forEach { tool ->
                    VideoToolTab(
                        tool = tool,
                        selected = tool == activeTool,
                        onClick = { activeTool = tool },
                    )
                }
            }
        }
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            title = stringResource(R.string.editor_discard_changes_title),
            message = stringResource(R.string.editor_discard_changes_message),
            confirmLabel = stringResource(R.string.editor_discard_changes_confirm),
            dismissLabel = stringResource(R.string.editor_discard_changes_keep),
            onConfirm = {
                showDiscardDialog = false
                onBack()
            },
            onDismiss = { showDiscardDialog = false },
            destructive = true,
        )
    }

    // Edit history: the last 10 edits by operation name, most recent first. Tapping one rewinds through
    // that edit (and everything after it), so each row names what it reverts rather than a bare step count.
    if (showUndoSheet && state.undoHistory.isNotEmpty()) {
        EditorActionSheet(
            title = stringResource(R.string.video_editor_history_title),
            actions = state.undoHistory.mapIndexed { index, labelRes ->
                EditorSheetAction(undoActionIcon(labelRes), stringResource(labelRes)) {
                    showUndoSheet = false
                    vm.undoSteps(index + 1)
                }
            },
            onDismiss = { showUndoSheet = false },
        )
    }

    val hasCloudCounterpart by vm.hasCloudCounterpart.collectAsStateWithLifecycle()
    if (showSaveSheet && hasSource) {
        ModalBottomSheet(
            onDismissRequest = { if (!state.isSaving) showSaveSheet = false },
            sheetState = saveSheetState,
            containerColor = SheetBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            VideoSaveSheet(
                isSaving = state.isSaving,
                progress = state.saveProgress,
                stage = state.saveStage,
                isCloud = cloudPhoto != null,
                hasCloudCounterpart = hasCloudCounterpart,
                onPicked = { vm.save() },
                onCancel = { if (!state.isSaving) showSaveSheet = false },
            )
        }
    }
}

// ─── Top bar ─────────────────────────────────────────────────────────────────

@Composable
private fun VideoTopBar(
    isSaving: Boolean,
    canGrabFrame: Boolean,
    undoDepth: Int,
    canRedo: Boolean,
    onUndoMenu: () -> Unit,
    onRedo: () -> Unit,
    onFrameGrab: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.onboarding_back),
                tint = FgPrimary, modifier = Modifier.size(20.dp),
            )
        }
        // No filename in the top bar — it added clutter without helping; the back arrow and the actions
        // just sit at the two ends with open space between.
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Undo history: tapping opens the shared action sheet to pick how many edits back to rewind.
            // Appears once there is something to undo.
            if (undoDepth > 0) {
                IconBubble(onClick = onUndoMenu) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        stringResource(R.string.cd_editor_undo),
                        tint = FgPrimary, modifier = Modifier.size(20.dp),
                    )
                }
            }
            // Redo the most recently undone edit. Appears only when the redo stack has something.
            if (canRedo) {
                IconBubble(onClick = onRedo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Redo,
                        stringResource(R.string.cd_editor_redo),
                        tint = FgPrimary, modifier = Modifier.size(20.dp),
                    )
                }
            }
            // Grab the current frame as a still JPEG. Only offered once a source is loaded so the
            // retriever has bytes to seek.
            if (canGrabFrame) {
                IconBubble(onClick = onFrameGrab) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        stringResource(R.string.video_editor_grab_frame),
                        tint = FgPrimary, modifier = Modifier.size(20.dp),
                    )
                }
            }
            VideoSavePill(isSaving = isSaving, onClick = onSave)
        }
    }
}

@Composable
private fun VideoSavePill(isSaving: Boolean, onClick: () -> Unit) {
    // Icon-only confirm: a round accent button with just a checkmark (a spinner while saving), no label.
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Accent)
            .clickable(enabled = !isSaving, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Icon(
                Icons.Default.Check,
                stringResource(R.string.action_save),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun IconBubble(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(PanelChip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun VideoToolTab(tool: VideoTool, selected: Boolean, onClick: () -> Unit) {
    val label = LocalContext.current.getString(tool.labelRes)
    // The selection fills in and the icon springs up a touch, so switching tools reads as a
    // deliberate move rather than an instant swap.
    val bgAlpha by animateFloatAsState(if (selected) 0.22f else 0f, tween(200), label = "video_tab_bg")
    val tint by animateColorAsState(if (selected) Accent else FgDim, tween(200), label = "video_tab_tint")
    val scale by animateFloatAsState(
        if (selected) 1f else 0.88f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "video_tab_scale",
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Accent.copy(alpha = bgAlpha), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = tool.icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

// ─── Tool panels ─────────────────────────────────────────────────────────────

@Composable
private fun TrimPanel(
    state: VideoEditorUiState,
    vm: VideoEditorViewModel,
    previewPlayer: ExoPlayer?,
    /** Per-source filmstrip lookup (primary from the cached hook, extra "+" sources from the screen map). */
    thumbnailsOf: (sourceId: Int) -> List<android.graphics.Bitmap?>,
    /** Selected clip id, hoisted to the screen so a tap on the preview (outside the track) can clear it. */
    selectedId: Long?,
    onSelectClip: (Long?) -> Unit,
    /** The cut timeline's live pinch-zoom, reported up so the screen can extract a denser primary
     *  filmstrip as the user zooms in (debounced and tiered there). */
    onTimelineZoom: (Float) -> Unit,
    /** Opens the in-app video picker for the "+" menu's "Add video". */
    onAddVideoRequested: () -> Unit,
) {
    val duration = state.durationMs.coerceAtLeast(1L)
    // The clips in play order, and the played (edited) length.
    val clips = resolveClips(state.clips, state.sources)
    val editedTotal = editedDurationMs(clips)
    // Playhead in EDITED time (position along the played result). The poll drives clip-order playback on
    // the single source player by seeking from one clip to the next; isScrubbing pauses it during a drag.
    var playheadEditedMs by remember(previewPlayer) { mutableStateOf(0L) }
    var isScrubbing by remember(previewPlayer) { mutableStateOf(false) }
    var playIndex by remember(previewPlayer) { mutableStateOf(0) }
    // A programmatic seek (seekEdited) is async: for a while the player still reports its OLD position. If
    // the poll read that stale position it would judge the playhead out of the freshly-targeted clip and
    // resync it to that clip's start (the "seek jumps to the clip start" report, only for non-first clips
    // where the target is far from the old position). This holds the target source ms until the player
    // settles onto it, and the poll skips its position logic until then. The tick cap avoids a permanent
    // freeze if a seek can never land (e.g. a target past the loaded source's real end).
    var pendingSeekMs by remember(previewPlayer) { mutableStateOf<Long?>(null) }
    var seekSettleTicks by remember(previewPlayer) { mutableStateOf(0) }
    // Which source's file the single ExoPlayer currently holds. A single player can't hold two files, so a
    // multi-source timeline is previewed by SWAPPING the media item to whichever source the playhead is on
    // (in the poll below and in seekEdited), rather than a live clipped playlist (that crashed). Starts on
    // the primary, which is what the player was created with.
    var currentPreviewSourceId by remember(previewPlayer) { mutableStateOf(PRIMARY_SOURCE_ID) }
    val liveClips by androidx.compose.runtime.rememberUpdatedState(clips)
    // Fresh multi-source flag + sources for the poll loop (its LaunchedEffect only re-keys on the player).
    val liveMulti by androidx.compose.runtime.rememberUpdatedState(state.isMultiSource)
    val liveSources by androidx.compose.runtime.rememberUpdatedState(state.sources)
    // The player is hoisted (survives tab swaps) but this preview-source tracking is remembered in the
    // panel, so on returning to the Trim tab currentPreviewSourceId would re-init to PRIMARY while the
    // player may still hold an added source the poll last swapped to — the poll then never swaps back
    // until the playhead lands on a differently-sourced clip. Reconcile it from the item actually loaded.
    androidx.compose.runtime.LaunchedEffect(previewPlayer) {
        val loadedUri = previewPlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
        if (loadedUri != null) {
            state.sources.firstOrNull { it.uri == loadedUri }?.let { currentPreviewSourceId = it.id }
        }
    }
    androidx.compose.runtime.LaunchedEffect(previewPlayer) {
        while (true) {
            val p = previewPlayer ?: break
            if (!isScrubbing) {
                val kept = liveClips.filter { !it.removed }
                if (kept.isNotEmpty()) {
                    if (liveMulti && MULTI_SOURCE_PREVIEW_ENABLED) {
                        // Playlist mode: ExoPlayer plays the clipped items back to back (REPEAT_MODE_ALL) and
                        // handles the boundaries itself, so we only READ the position back into edited time.
                        val idx = p.currentMediaItemIndex.coerceIn(0, kept.lastIndex)
                        playIndex = idx
                        val before = kept.take(idx).sumOf { it.durationMs }
                        playheadEditedMs = before + p.currentPosition.coerceIn(0L, kept[idx].durationMs)
                        kotlinx.coroutines.delay(33)
                        continue
                    }
                    val idx = playIndex.coerceIn(0, kept.lastIndex)
                    val cur = kept[idx]
                    // Multi-source: if the playhead's clip is on a source the player isn't holding, swap the
                    // media item to it and seek to the clip, then let it settle. Single-source never trips
                    // this (every clip is the primary). This is the multi-source preview, replacing the live
                    // clipped playlist that crashed.
                    if (cur.sourceId != currentPreviewSourceId) {
                        val uri = liveSources.uriOf(cur.sourceId)
                        if (uri != null) {
                            runCatching {
                                p.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                                p.prepare()
                                p.seekTo(cur.startMs.coerceAtLeast(0L))
                            }
                            currentPreviewSourceId = cur.sourceId
                            pendingSeekMs = cur.startMs.coerceAtLeast(0L)
                            seekSettleTicks = 0
                            kotlinx.coroutines.delay(33)
                            continue
                        }
                        // No uri (shouldn't happen): note the id so we don't retry every tick, then fall
                        // through and track best-effort on whatever is loaded.
                        currentPreviewSourceId = cur.sourceId
                    }
                    // Wait out an in-flight seek before trusting the player's position (see pendingSeekMs).
                    val pending = pendingSeekMs
                    if (pending != null) {
                        if (kotlin.math.abs(p.currentPosition - pending) <= 100L || seekSettleTicks > 15) {
                            pendingSeekMs = null; seekSettleTicks = 0
                        } else {
                            seekSettleTicks++
                            kotlinx.coroutines.delay(33)
                            continue
                        }
                    }
                    val pos = p.currentPosition
                    when {
                        // Paused by the user (playWhenReady false): the playhead is positioned by seekEdited
                        // (a tap or a scrub), whose seek is async, so reading the still-settling / stale
                        // position here would drag the playhead off the tapped frame (it snapped back to the
                        // clip start via the resync branch below). While the user isn't asking to play we
                        // trust the seeked playheadEditedMs. Keyed on playWhenReady, not isPlaying, so a
                        // source that has ENDED while the user still wants to play advances below.
                        !p.playWhenReady -> Unit
                        // Reached the end of the current clip: advance to the next clip in play order
                        // (looping). A next clip on a DIFFERENT source is loaded by the swap above on the next
                        // tick; a same-source jump seeks here, unless the two are source-contiguous (a plain
                        // split), where we let playback run straight through to avoid a stall.
                        pos >= cur.endMs -> {
                            val nextIdx = (idx + 1) % kept.size
                            val next = kept[nextIdx]
                            playIndex = nextIdx
                            if (next.sourceId == cur.sourceId &&
                                (nextIdx == 0 || kotlin.math.abs(next.startMs - cur.endMs) > 40L)) {
                                // Keyframe-aligned seek for the join: an EXACT seek decodes from the previous
                                // keyframe up to the target, and that decode burst is the visible hitch at a
                                // cut. CLOSEST_SYNC jumps to the nearest keyframe (near-instant), which is
                                // fine mid-playback at a cut (the exact cut point is honoured by the save,
                                // not the preview). User seeks / trims below stay EXACT for frame accuracy.
                                p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                                p.seekTo(next.startMs)
                                // Let this jump settle before the resync branch runs: otherwise the next
                                // tick reads the still-stale (pre-seek) position, judges it outside the new
                                // clip, and seeks AGAIN — a double seek that makes the stall at the cut worse.
                                pendingSeekMs = next.startMs
                                seekSettleTicks = 0
                            }
                        }
                        // Drifted outside the current clip (e.g. the source looped): resync to its start.
                        pos < cur.startMs - 250L || pos > cur.endMs + 250L -> p.seekTo(cur.startMs)
                        else -> {
                            val before = kept.take(idx).sumOf { it.durationMs }
                            playheadEditedMs = before + (pos - cur.startMs).coerceIn(0L, cur.durationMs)
                        }
                    }
                }
            }
            kotlinx.coroutines.delay(33)
        }
    }
    val canSplit = canSplitClipsAt(clips, playheadEditedMs)

    // Maps an edited-time position to a clip index + source ms, for scrubbing / seeking.
    fun seekEdited(editedMs: Long) {
        // Frame-accurate for a user scrub/seek (the poll's join jumps flip this to CLOSEST_SYNC for speed).
        previewPlayer?.setSeekParameters(SeekParameters.EXACT)
        val kept = clips.filter { !it.removed }
        val (idx, srcMs) = editedToClipPos(kept, editedMs)
        playIndex = idx
        playheadEditedMs = editedMs
        val target = srcMs.coerceAtLeast(0L)
        val srcId = kept.getOrNull(idx)?.sourceId ?: PRIMARY_SOURCE_ID
        if (srcId != currentPreviewSourceId) {
            // Seeking into a clip on another source: swap the player onto that file first, then seek. The
            // poll settles onto the target via pendingSeekMs before it trusts the position again.
            val uri = state.sources.uriOf(srcId)
            if (uri != null) {
                runCatching {
                    previewPlayer?.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                    previewPlayer?.prepare()
                    previewPlayer?.seekTo(target)
                }
                currentPreviewSourceId = srcId
            }
        } else {
            previewPlayer?.seekTo(target)
        }
        pendingSeekMs = target
        seekSettleTicks = 0
    }

    // While a clip's own start/end handle is dragged: show the cut frame from THAT clip's source (swapping
    // the player onto it if the clip is a different, added source) and pin the playhead to the cut point on
    // the clip being trimmed. Without the source swap the preview showed the primary video's frame while
    // trimming an added clip; without moving the playhead here it stayed on whatever clip it was on and
    // drifted oddly as this clip's duration (and the edited-time layout after it) changed under it.
    fun seekTrimFrame(clipIndex: Int, isStart: Boolean, sourceMs: Long) {
        // Frame-accurate so the trim shows the exact cut frame (undo the poll's CLOSEST_SYNC join mode).
        previewPlayer?.setSeekParameters(SeekParameters.EXACT)
        val clip = clips.getOrNull(clipIndex) ?: return
        val target = sourceMs.coerceAtLeast(0L)
        if (clip.sourceId != currentPreviewSourceId) {
            val uri = state.sources.uriOf(clip.sourceId)
            if (uri != null) {
                runCatching {
                    previewPlayer?.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                    previewPlayer?.prepare()
                    previewPlayer?.seekTo(target)
                }
                currentPreviewSourceId = clip.sourceId
            }
        } else {
            previewPlayer?.seekTo(target)
        }
        pendingSeekMs = target
        seekSettleTicks = 0
        // Pin the playhead to the cut point, on the clip being trimmed (kept clips only). The start handle
        // sits at the clip's edited start; the end handle at that start plus the clip's new duration.
        if (!clip.removed) {
            val kept = clips.filter { !it.removed }
            val keptIdx = kept.indexOfFirst { it.id == clip.id }
            if (keptIdx >= 0) {
                val before = kept.take(keptIdx).sumOf { it.durationMs }
                playheadEditedMs = if (isStart) before else before + (target - clip.startMs).coerceAtLeast(0L)
                playIndex = keptIdx
            }
        }
    }

    val context = LocalContext.current
    // "+" can also add music, so a track can be placed straight from the cut track. Same picker the Audio
    // tab uses; the read permission is persisted so a later re-encode still has access.
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val displayName = resolveDisplayName(context, uri) ?: "music"
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            vm.setAudioOverlay(uri.toString(), displayName)
        }
    }
    var addMenuOpen by remember { mutableStateOf(false) }
    val appColors = eu.akoos.photos.presentation.theme.AppColors.current
    // Reorder overlay: the lifted clip floats above the track, following the finger.
    val density = LocalDensity.current
    var reorderFrac by remember { mutableStateOf<Float?>(null) }
    var timelineWidthPx by remember { mutableFloatStateOf(1f) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Top control row morphs on selection: with no clip selected it is the play + time + speed
        // transport pill; tapping a clip replaces it (animated) with that clip's actions — Split /
        // Duplicate / Delete — and deselecting brings the transport back, so the cut controls only appear
        // in context.
        val selClip = clips.firstOrNull { it.id == selectedId }
        AnimatedContent(
            targetState = selClip,
            // Key only on WHETHER a clip is selected, not which: switching between clips updates the pill
            // in place instead of re-animating it, and re-tapping the same clip does nothing.
            contentKey = { it != null },
            transitionSpec = {
                (fadeIn(tween(170)) + slideInVertically(tween(200)) { it / 4 }) togetherWith
                    (fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 4 })
            },
            label = "trimTopRow",
        ) { sel ->
            if (sel == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    val timeReadout: @Composable () -> Unit = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatVideoTime(withTenths = true, ms = playheadEditedMs.coerceIn(0L, editedTotal)),
                                color = FgPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "  /  " + formatVideoTime(withTenths = true, ms = editedTotal.coerceAtLeast(0L)),
                                color = FgMute, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (previewPlayer != null) {
                        VideoEditorPlayPill(player = previewPlayer, center = timeReadout)
                    } else {
                        TimePill(formatVideoTime(withTenths = true, ms = editedTotal.coerceAtLeast(0L)))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    ClipActionsPill(
                        canSplit = canSplit,
                        onSplit = { vm.splitAtPlayhead(playheadEditedMs) },
                        onDuplicate = { vm.duplicateClip(sel.id) },
                        removed = sel.removed,
                        // Clearing the selection lets the pill morph back to the transport row; an added
                        // source's clip is now removed outright (not greyed), so nothing stays selected.
                        // The poll re-syncs the preview onto a remaining source on its next tick.
                        onDelete = { vm.removeClip(sel.id); onSelectClip(null) },
                        onRestore = { vm.restoreClip(sel.id) },
                    )
                }
            }
        }
        // Cut track: the "+" adds another video on the left (reference gallery layout), the clip rail fills
        // the rest. The reorder + volume overlays float over the rail Box, so they key off its measured width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Left rail mirrors the timeline's two rows: the "+" aligns with the 54dp clip strip, and when
            // music is present a mute tile sits below it (6dp gap + 30dp) aligned with the audio lane.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    AddClipButton(onClick = { addMenuOpen = true })
                    androidx.compose.material3.DropdownMenu(
                        expanded = addMenuOpen,
                        onDismissRequest = { addMenuOpen = false },
                        shape = RoundedCornerShape(18.dp),
                        containerColor = appColors.cardBg,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, appColors.pillBorder),
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(context.getString(R.string.video_editor_add_video), color = FgPrimary) },
                            leadingIcon = { Icon(Icons.Default.Add, null, tint = Accent, modifier = Modifier.size(20.dp)) },
                            onClick = { addMenuOpen = false; onAddVideoRequested() },
                        )
                        // With a track already added this REPLACES it (single music track for now), so the
                        // label reads "Replace" instead of "Add" and does not imply a second track is possible.
                        val hasMusic = state.audioOverlayUri != null
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(
                                    context.getString(
                                        if (hasMusic) R.string.video_editor_replace_music
                                        else R.string.video_editor_add_music,
                                    ),
                                    color = FgPrimary,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (hasMusic) Icons.Default.SwapHoriz else Icons.Default.MusicNote,
                                    null, tint = Accent, modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = { addMenuOpen = false; pickAudio.launch("audio/*") },
                        )
                    }
                }
                if (state.audioOverlayUri != null) {
                    val musicMuted = state.musicAudioGain <= 0.001f
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 30.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PanelChip)
                            .clickable { vm.setMusicAudioGain(if (musicMuted) 1f else 0f) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (musicMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = context.getString(R.string.video_editor_audio),
                            tint = if (musicMuted) FgMute else Accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).onSizeChanged { timelineWidthPx = it.width.toFloat().coerceAtLeast(1f) }) {
                VideoClipTimeline(
                    referenceDurationMs = state.sources.firstOrNull()?.durationMs ?: duration,
                    clips = clips,
                    playheadEditedMs = playheadEditedMs,
                    selectedId = selectedId,
                    thumbnailsOf = thumbnailsOf,
                    sourceDurationOf = { state.sources.durationOf(it) },
                    onEdgeDrag = { index, isStart, sourceMs -> vm.setClipEdge(index, isStart, newMs = sourceMs) },
                    // Seek the preview to the dragged handle's frame ON THAT CLIP'S SOURCE (swapping the
                    // player onto an added source if needed) and pin the playhead to the cut point, so the
                    // cut is visible on the right video and the playhead does not drift onto another clip.
                    onEdgeSeek = { index, isStart, sourceMs -> seekTrimFrame(index, isStart, sourceMs) },
                    onScrubStart = {
                        isScrubbing = true
                        vm.beginEdit()
                        previewPlayer?.setScrubbingModeEnabled(true)
                    },
                    onScrubEnd = {
                        isScrubbing = false
                        vm.endEdit()
                        previewPlayer?.setScrubbingModeEnabled(false)
                    },
                    onScrubEditedMs = { editedMs -> seekEdited(editedMs.coerceIn(0L, editedTotal)) },
                    // Tap = select + seek, but DON'T change play/pause state: if the preview is playing it
                    // keeps playing from the tapped point (the only way to seek while zoomed in), if paused
                    // it stays paused. The seek settles via pendingSeekMs so a running preview doesn't fight it.
                    onSelect = { id -> onSelectClip(id) },
                    onReorder = { from, to -> vm.moveClip(from, to) },
                    onReorderFraction = { frac, _ -> reorderFrac = frac },
                    // Music lane under the clips: shown only when an overlay is loaded. Dragging the block
                    // moves where the music starts; dragging its edges trims which slice of the file plays.
                    // The drag is bracketed as one undo step via beginEdit / endEdit.
                    audioName = state.audioOverlayDisplayName?.takeIf { state.audioOverlayUri != null },
                    audioOffsetMs = state.audioOffsetMs,
                    audioTrimStartMs = state.audioTrimStartMs,
                    audioTrimEndMs = state.audioTrimEndMs,
                    onAudioMove = { newLeftMs -> vm.setAudioOffset(newLeftMs) },
                    onAudioTrimLeft = { newLeftMs -> vm.setAudioLeftEdge(newLeftMs) },
                    onAudioTrimRight = { newRightMs ->
                        vm.setAudioTrim(
                            state.audioTrimStartMs,
                            (state.audioTrimStartMs + (newRightMs - state.audioOffsetMs))
                                .coerceAtLeast(state.audioTrimStartMs + 1),
                        )
                    },
                    onAudioEditStart = { vm.beginEdit() },
                    onAudioEditEnd = { vm.endEdit() },
                    onZoomChange = onTimelineZoom,
                )
                // The lifted clip: a bordered accent chip floating just above the track under the finger.
                reorderFrac?.let { f ->
                    val chipHalfPx = with(density) { 28.dp.toPx() }
                    val topPx = with(density) { -18.dp.toPx() }
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((f * timelineWidthPx - chipHalfPx).toInt(), topPx.toInt()) }
                            .size(56.dp, 54.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Accent)
                            .border(2.dp, Color.White, RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
    }
}

/**
 * Time-readout pill above the filmstrip. Two-tone treatment: regular start/end pills
 * use the dim panel-chip background; the centered duration pill uses the accent tint so
 * the eye lands on the trimmed clip's length at a glance.
 */
@Composable
private fun TimePill(text: String, highlight: Boolean = false) {
    Box(
        modifier = Modifier
            .background(
                if (highlight) Accent.copy(alpha = 0.22f) else PillBgOpaque,
                RoundedCornerShape(999.dp),
            )
            .border(
                0.5.dp,
                if (highlight) Accent.copy(alpha = 0.45f) else PillBorder,
                RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (highlight) Accent else FgPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Source-space width/height ratio a locked [aspect] maps to, accounting for the user's rotation.
 *
 * The crop rect lives in source pixels while the overlay's graphicsLayer rotates it on screen, so a
 * ratio the user reads as "16:9" (a display shape) becomes its reciprocal in source pixels after a
 * quarter turn. Computing the ratio against the DISPLAYED dimensions and inverting when sideways
 * lets a tapped chip always show the shape the user expects, whichever way the clip is turned. Free
 * returns null (no lock).
 */
private fun sourceLockRatio(aspect: CropAspect, srcW: Int, srcH: Int, rotationDegrees: Int): Float? {
    val sideways = ((rotationDegrees % 360) + 360) % 360 % 180 != 0
    val dispW = if (sideways) srcH else srcW
    val dispH = if (sideways) srcW else srcH
    return aspect.lockRatio(dispW, dispH)?.let { if (sideways) 1f / it else it }
}

@Composable
private fun CropPanel(
    state: VideoEditorUiState,
    vm: VideoEditorViewModel,
    lockedAspect: CropAspect,
    onLockedAspectChange: (CropAspect) -> Unit,
) {
    val srcW = state.sourceWidth.coerceAtLeast(1)
    val srcH = state.sourceHeight.coerceAtLeast(1)
    // Aspect ratio chips, the same pill recipe the photo editor's crop panel uses.
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(CropAspect.entries.toList()) { aspect ->
            // The lit chip is the one the user tapped, and it stays lit while that shape is locked,
            // even after the rect is dragged smaller or moved. Comparing rects would unlight it the
            // moment the frame was nudged.
            val isSelected = aspect == lockedAspect
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .background(
                        if (isSelected) Accent.copy(alpha = 0.18f) else PillBg,
                        pillShape,
                    )
                    .then(if (!isSelected) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
                    .clickable {
                        // Tapping a chip locks the shape AND shows it, with no Apply step. Free and
                        // Original clear the crop back to the full frame (committed as null so the
                        // save keeps the fast stream-copy path); Free additionally releases the lock
                        // so drags are free-form again, while Original leaves the frame ratio locked.
                        onLockedAspectChange(aspect)
                        when (aspect) {
                            CropAspect.Free, CropAspect.Original -> vm.setCropRect(null)
                            else -> {
                                val ratio = sourceLockRatio(aspect, srcW, srcH, state.rotationDegrees)
                                if (ratio != null) vm.setCropRect(centeredCrop(srcW, srcH, ratio).toRect())
                            }
                        }
                        vm.endEdit()
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // Numeric ratios (1:1, 16:9, …) stay as glyph labels; only the word entries
                    // are translatable.
                    when (aspect) {
                        CropAspect.Free -> stringResource(R.string.editor_crop_free)
                        CropAspect.Original -> stringResource(R.string.editor_filter_original)
                        else -> aspect.label
                    },
                    color = if (isSelected) Accent else FgPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/** Colour-filter presets, the same looks and chips the photo editor's filter row uses. Selecting a
 *  chip previews the filter live and bakes it into the export. */
@Composable
private fun FilterPanel(
    state: VideoEditorUiState,
    vm: VideoEditorViewModel,
    thumbnails: List<android.graphics.Bitmap?>,
) {
    // Preview each preset on a real frame from this clip, the same read as the photo editor's filter row:
    // a mid-clip frame that has actually decoded, so the swatch shows the look rather than a text label.
    // Falls back to the first available frame, or an empty swatch until the strip fills.
    val source = remember(thumbnails.toList()) {
        thumbnails.getOrNull(thumbnails.size / 2) ?: thumbnails.firstOrNull { it != null }
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(VideoFilter.entries.toList()) { preset ->
            VideoFilterThumb(
                preset = preset,
                source = source,
                selected = preset == state.filterPreset,
                onClick = { vm.setFilter(preset) },
            )
        }
    }
}

/** One filter swatch: a mid-clip frame drawn through the preset's colour matrix so the chip shows the
 *  look it would apply, mirroring the photo editor's [FilterThumb]. A check overlay marks the selection. */
@Composable
private fun VideoFilterThumb(
    preset: VideoFilter,
    source: android.graphics.Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PillBg)
                .border(0.5.dp, PillBorder, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (source != null) {
                val colorFilter = remember(preset) {
                    preset.matrix4x5()?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
                }
                Image(
                    bitmap = source.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter,
                )
            }
            if (selected) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            stringResource(preset.labelRes),
            color = if (selected) Accent else FgMute,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Colour/light adjustments panel: the same nine sliders as the photo editor's Adjust tab, styled in the
 *  video editor's pill recipe. A horizontally scrollable chip row picks one adjustment; the slider below
 *  drives its -100..100 value and the live preview tints immediately through [effectiveColorMatrix4x5]. */
@Composable
private fun AdjustPanel(state: VideoEditorUiState, vm: VideoEditorViewModel) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(VideoAdjustment.Brightness) }
    val value = selected.value(state)
    val edited = state.hasColorEdits()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(VideoAdjustment.entries.toList(), key = { it.name }) { adj ->
                AdjustChip(
                    label = context.getString(adj.labelRes),
                    icon = adj.icon,
                    selected = adj == selected,
                    edited = adj.value(state) != 0,
                    onClick = { selected = adj },
                )
            }
        }
        // The selected adjustment's name, a signed value readout, and a reset that clears every adjustment
        // at once. Reset only shows while something is edited; the row is a fixed height so revealing it
        // does not grow the row and jolt the whole panel up a few dp.
        Row(
            modifier = Modifier.fillMaxWidth().height(30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                context.getString(selected.labelRes),
                color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                formatAdjustmentValue(value),
                color = if (value != 0) Accent else FgMute,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
            if (edited) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { vm.resetColorAdjustments() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Default.Restore, null, tint = FgDim, modifier = Modifier.size(15.dp))
                    Text(
                        context.getString(R.string.filter_reset),
                        color = FgPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        // House-style slider, the same recipe as the Audio panel's volume slider.
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { selected.set(vm, it.roundToInt()) },
            valueRange = -100f..100f,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = PanelChip,
            ),
        )
    }
}

/** The nine video colour/light adjustments, each bound to a state field and its VM setter. Order and icons
 *  match the photo editor's Adjust row. */
private enum class VideoAdjustment(
    @androidx.annotation.StringRes val labelRes: Int,
    val icon: ImageVector,
    val value: (VideoEditorUiState) -> Int,
    val set: (VideoEditorViewModel, Int) -> Unit,
) {
    Brightness(R.string.editor_adj_brightness, Icons.Default.BrightnessMedium, { it.adjBrightness }, { vm, v -> vm.setAdjBrightness(v) }),
    Exposure(R.string.editor_adj_exposure, Icons.Default.AutoFixHigh, { it.adjExposure }, { vm, v -> vm.setAdjExposure(v) }),
    Contrast(R.string.editor_adj_contrast, Icons.Default.Contrast, { it.adjContrast }, { vm, v -> vm.setAdjContrast(v) }),
    Highlights(R.string.editor_adj_highlights, Icons.Default.Tune, { it.adjHighlights }, { vm, v -> vm.setAdjHighlights(v) }),
    Shadows(R.string.editor_adj_shadows, Icons.Default.Block, { it.adjShadows }, { vm, v -> vm.setAdjShadows(v) }),
    Saturation(R.string.editor_adj_saturation, Icons.Default.Palette, { it.adjSaturation }, { vm, v -> vm.setAdjSaturation(v) }),
    Temperature(R.string.editor_adj_temperature, Icons.Default.Brush, { it.adjTemperature }, { vm, v -> vm.setAdjTemperature(v) }),
    Tone(R.string.editor_adj_tone, Icons.Default.SwapHoriz, { it.adjTone }, { vm, v -> vm.setAdjTone(v) }),
    Fade(R.string.editor_adj_fade, Icons.Default.Gradient, { it.adjFade }, { vm, v -> vm.setAdjFade(v) }),
}

/** One adjustment chip: the video-editor pill recipe (PillBg + PillBorder, an accent fill when selected).
 *  The icon lights accent when that adjustment is off-zero, and an unselected edited chip carries a dot. */
@Composable
private fun AdjustChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    edited: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(38.dp)
            .background(if (selected) Accent.copy(alpha = 0.22f) else PillBg, editorPillShape)
            .then(if (!selected) Modifier.border(0.5.dp, PillBorder, editorPillShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            null,
            tint = if (selected || edited) Accent else FgDim,
            modifier = Modifier.size(15.dp),
        )
        Text(
            label,
            color = if (selected) Accent else FgPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
        if (edited && !selected) {
            Box(Modifier.size(5.dp).background(Accent, CircleShape))
        }
    }
}

/** Signed readout for an adjustment value: "+35", "0", "-12". */
private fun formatAdjustmentValue(v: Int): String = if (v > 0) "+$v" else v.toString()

@Composable
private fun RotatePanel(state: VideoEditorUiState, vm: VideoEditorViewModel) {
    val context = LocalContext.current
    // One combined capsule (same recipe as the bottom tab dock) so the rotate action, the live
    // degrees readout and reset read as a single control rather than scattered pills. Reset steps
    // back to 0 through the existing rotate action, so no new pipeline path is introduced.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PillBgOpaque, RoundedCornerShape(999.dp))
            .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoPillSegment(
            label = context.getString(R.string.video_editor_rotate),
            icon = Icons.AutoMirrored.Filled.RotateRight,
            selected = false,
            onClick = { vm.rotate90Cw() },
            modifier = Modifier.weight(1f),
        )
        // Live degrees readout, decorative. Lights up in accent while the clip is rotated.
        VideoPillSegment(
            label = "${state.rotationDegrees}°",
            icon = null,
            selected = state.rotationDegrees != 0,
            onClick = {},
            clickable = false,
        )
        VideoPillSegment(
            label = context.getString(R.string.filter_reset),
            icon = Icons.Default.Restore,
            selected = false,
            onClick = { repeat(((360 - state.rotationDegrees) / 90) % 4) { vm.rotate90Cw() } },
            enabled = state.rotationDegrees != 0,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One segment inside a combined sub-menu pill: an icon + label with a rounded accent fill when
 *  selected, matching the bottom tab dock. Replicated locally because the photo editor's PillSegment
 *  is file-private. Never wraps to a second line; pass clickable=false for a decorative readout. */
@Composable
private fun VideoPillSegment(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    clickable: Boolean = true,
) {
    val tint = when {
        !enabled -> FgDim.copy(alpha = 0.4f)
        selected -> Accent
        else -> FgDim
    }
    val textColor = when {
        !enabled -> FgDim.copy(alpha = 0.4f)
        selected -> Accent
        else -> FgPrimary
    }
    Row(
        modifier = modifier
            .height(40.dp)
            .background(
                if (selected) Accent.copy(alpha = 0.22f) else Color.Transparent,
                RoundedCornerShape(999.dp),
            )
            .then(if (clickable) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = textColor,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun AudioPanel(state: VideoEditorUiState, vm: VideoEditorViewModel, previewPlayer: ExoPlayer?) {
    val context = LocalContext.current
    val appColors = eu.akoos.photos.presentation.theme.AppColors.current
    // Picker for an arbitrary audio file. GetContent shows the system file picker filtered
    // to audio/* — works for MediaStore items, SAF documents, and a number of cloud
    // providers that expose audio_provider URIs.
    val pickAudio = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = resolveDisplayName(context, uri) ?: "music"
        // Persist read perms so a re-encode that happens later still has access.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        vm.setAudioOverlay(uri.toString(), displayName)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // Video's own sound: a mute toggle + a volume slider (no percentage, kept clean).
        AudioTrackRow(
            label = context.getString(R.string.video_editor_audio_original),
            gain = state.originalAudioGain,
            onGainChange = vm::setOriginalAudioGain,
            onToggleMute = { vm.toggleMute() },
        )

        // Background music: add it, then a mute + volume slider; the "›" opens replace / remove.
        if (state.audioOverlayUri == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { pickAudio.launch("audio/*") }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(20.dp))
                Text(
                    context.getString(R.string.video_editor_add_music),
                    color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
            }
        } else {
            var menuOpen by remember { mutableStateOf(false) }
            AudioTrackRow(
                label = state.audioOverlayDisplayName ?: "music",
                secondary = formatVideoTime(withTenths = false, ms = state.audioOverlayDurationMs),
                gain = state.musicAudioGain,
                onGainChange = vm::setMusicAudioGain,
                // The speaker icon toggles the music between muted and full.
                onToggleMute = { vm.setMusicAudioGain(if (state.musicAudioGain <= 0.001f) 1f else 0f) },
                trailing = {
                    Box {
                        Box(
                            modifier = Modifier.size(30.dp).clip(CircleShape).clickable { menuOpen = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowRight,
                                contentDescription = context.getString(R.string.video_editor_remove_music),
                                tint = FgMute,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = RoundedCornerShape(18.dp),
                            containerColor = appColors.cardBg,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, appColors.pillBorder),
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(context.getString(R.string.video_editor_replace_music), color = FgPrimary) },
                                leadingIcon = { Icon(Icons.Default.SwapHoriz, null, tint = Accent, modifier = Modifier.size(20.dp)) },
                                onClick = { menuOpen = false; pickAudio.launch("audio/*") },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(context.getString(R.string.video_editor_remove_music), color = eu.akoos.photos.presentation.theme.ErrorColor) },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = eu.akoos.photos.presentation.theme.ErrorColor, modifier = Modifier.size(20.dp)) },
                                onClick = { menuOpen = false; vm.clearAudioOverlay() },
                            )
                        }
                    }
                },
            )
        }
    }
}

/**
 * One audio track in the panel, laid out like the Samsung Gallery audio editor: a header line (the
 * [label], an optional [secondary] such as the music length, and an optional [trailing] control such as
 * the "›" replace/remove menu), then a mute speaker toggle and a volume slider. No percentage: the
 * slider position is the level; the speaker icon dims when muted and the pipeline drops that track.
 */
@Composable
private fun AudioTrackRow(
    label: String,
    gain: Float,
    onGainChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    secondary: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val muted = gain <= 0.001f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label,
                color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Text(secondary, color = FgMute, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            if (trailing != null) trailing()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onToggleMute() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    null,
                    tint = if (muted) FgMute else Accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            androidx.compose.material3.Slider(
                value = gain,
                onValueChange = onGainChange,
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = PanelChip,
                ),
            )
        }
    }
}

/**
 * Compact one-tap mute chip sitting in the source-audio row. Reads the pill/chip recipe: an accent
 * fill with a VolumeOff icon when muted, the dim panel-chip with VolumeUp when the track is kept.
 */
@Composable
private fun MuteToggle(muted: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (muted) Accent.copy(alpha = 0.22f) else PanelChip)
            .then(
                if (muted) Modifier.border(0.5.dp, Accent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = context.getString(R.string.video_editor_mute),
            tint = if (muted) Accent else FgDim,
            modifier = Modifier.size(15.dp),
        )
        Text(
            context.getString(R.string.video_editor_mute),
            color = if (muted) Accent else FgPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun resolveDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }.getOrNull()
}

/**
 * Dual-thumb trim slider drawn on a Compose Canvas. Used by both the video Trim panel
 * AND the Audio panel's music-trim row — they're functionally identical, just with
 * different total-duration scaling.
 */
@Composable
private fun RangeTrimSlider(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    onChange: (start: Long, end: Long) -> Unit,
    /** Called with the currently-dragged-handle's timestamp (in ms). Hosts use this to seek
     *  a live preview player to the cut point. Null = no scrub side-effect. */
    onScrubMs: ((Long) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val trackHeightPx = with(density) { 4.dp.toPx() }
    val thumbRadiusPx = with(density) { 10.dp.toPx() }
    val activeColor = Accent
    val trackColor = TrackBg
    var widthPx by remember { mutableFloatStateOf(0f) }
    var draggingStart by remember { mutableStateOf(true) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            // The end thumbs sit within a few dp of the display, where the system's edge back
            // gesture would claim the touch first. A 36dp row is far inside the platform's
            // per-edge budget, so the whole track can stand aside at once.
            .systemGestureExclusion()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val startX = startMs.toFloat() / durationMs * w
                        val endX = endMs.toFloat() / durationMs * w
                        draggingStart = kotlin.math.abs(offset.x - startX) <=
                            kotlin.math.abs(offset.x - endX)
                        val pct = (offset.x / w).coerceIn(0f, 1f)
                        val ms = (pct * durationMs).toLong()
                        if (draggingStart) onChange(ms, endMs) else onChange(startMs, ms)
                        onScrubMs?.invoke(ms)
                    },
                    onDrag = { change, _ ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val pct = (change.position.x / w).coerceIn(0f, 1f)
                        val ms = (pct * durationMs).toLong()
                        if (draggingStart) onChange(ms, endMs) else onChange(startMs, ms)
                        onScrubMs?.invoke(ms)
                        change.consume()
                    },
                )
            },
    ) {
        val w = size.width
        val cy = size.height / 2f
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, cy - trackHeightPx / 2f),
            size = GSize(w, trackHeightPx),
            cornerRadius = CornerRadius(trackHeightPx / 2f),
        )
        val startX = (startMs.toFloat() / durationMs * w).coerceIn(0f, w)
        val endX = (endMs.toFloat() / durationMs * w).coerceIn(0f, w)
        val left = min(startX, endX)
        val right = max(startX, endX)
        drawRoundRect(
            color = activeColor,
            topLeft = Offset(left, cy - trackHeightPx / 2f),
            size = GSize(right - left, trackHeightPx),
            cornerRadius = CornerRadius(trackHeightPx / 2f),
        )
        for (x in listOf(startX, endX)) {
            drawCircle(color = activeColor, radius = thumbRadiusPx, center = Offset(x, cy))
            drawCircle(color = Color.White, radius = thumbRadiusPx - 3f, center = Offset(x, cy))
            drawCircle(color = activeColor, radius = thumbRadiusPx - 6f, center = Offset(x, cy))
        }
    }
}

// Pill capsule used by the editor's play+speed pill. Local definition because the
// viewer's identical val is file-private — duplicating one line saves an export.
private val editorPillShape = RoundedCornerShape(999.dp)

// ─── Editor play + speed pill ────────────────────────────────────────────────

/**
 * Single pill that contains the play/pause icon plus a speed control. The speed control
 * collapses to a small chip showing the current speed; tapping expands it horizontally
 * into a 4-button selector for 1/4× / 1/2× / 1× / 2×. The user picks → the chip
 * collapses again and the player's playback parameters update.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoEditorPlayPill(
    player: ExoPlayer,
    showTime: Boolean = false,
    totalMs: Long = 0L,
    center: (@Composable () -> Unit)? = null,
) {
    var isPlaying by remember { mutableStateOf(false) }
    // Live position for the built-in time readout (used by the tools that do not supply their own
    // [center]). The total length comes from [totalMs] (the edited timeline), so the player only drives
    // the moving position.
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            if (showTime) {
                // Hold the last known values while the player is transiently unready: a source swap can
                // leave currentPosition at 0 and duration UNSET for a beat, which flashed "0:00 / 0:00".
                val p = player.currentPosition
                if (p > 0L || player.playbackState == androidx.media3.common.Player.STATE_READY) {
                    positionMs = p.coerceAtLeast(0L)
                }
                val d = player.duration
                if (d > 0L) durationMs = d
            }
            kotlinx.coroutines.delay(80)
        }
    }
    // Seed from the player's ACTUAL speed, not a hardcoded 1x: the pill leaves and re-enters composition
    // when the top row morphs to the clip actions and back, and a hardcoded reset left the chip showing 1x
    // while the player kept playing (and pitching audio) at the old rate — the "audio didn't reset" report.
    var speed by remember { mutableStateOf(player.playbackParameters.speed) }
    var speedExpanded by remember { mutableStateOf(false) }
    val speedOptions = listOf(0.25f, 0.5f, 1f, 2f)

    Row(
        modifier = Modifier
            .background(PillBg, editorPillShape)
            .border(0.5.dp, PillBorder, editorPillShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Play/Pause
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).clickable {
                if (player.isPlaying) player.pause() else player.play()
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                null, tint = FgPrimary, modifier = Modifier.size(22.dp),
            )
        }
        // Optional middle slot: the Trim tab drops its current / total time readout here so play, time
        // and speed read as one transport control. Tools that don't supply one but ask for [showTime]
        // (the Audio tab) get the same readout built from the player's own position / duration, so the
        // music tab shows where you are in the video and how long it is, matching the cutter.
        if (center != null) {
            center()
        } else if (showTime) {
            // Total comes from the edited timeline (state) so it is known even before the player reports a
            // duration; fall back to the player's own duration, then to the position, so it is never 0:00.
            val total = when {
                totalMs > 0L -> totalMs
                durationMs > 0L -> durationMs
                else -> positionMs
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatVideoTime(withTenths = true, ms = positionMs.coerceIn(0L, total)),
                    color = FgPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "  /  " + formatVideoTime(withTenths = true, ms = total.coerceAtLeast(0L)),
                    color = FgMute, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
        // Speed control: collapsed pill shows current rate, tap expands into 4 chips.
        // AnimatedContent crossfades the two states and springs the pill's width so the
        // expansion grows and collapses smoothly instead of snapping.
        AnimatedContent(
            targetState = speedExpanded,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
            label = "video_speed",
        ) { expanded ->
            if (!expanded) {
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PanelChip)
                        .clickable { speedExpanded = true }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(formatSpeed(speed), color = FgPrimary, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium)
                }
            } else {
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PanelChip)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    speedOptions.forEach { s ->
                        val selected = s == speed
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .height(22.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (selected) Accent else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable {
                                    speed = s
                                    player.setPlaybackSpeed(s)
                                    speedExpanded = false
                                }
                                .padding(horizontal = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                formatSpeed(s),
                                color = if (selected) androidx.compose.ui.graphics.Color.White else FgPrimary,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSpeed(s: Float): String = when (s) {
    0.25f -> "¼×"
    0.5f -> "½×"
    1f -> "1×"
    2f -> "2×"
    else -> "${s}×"
}

// ─── Save sheet ──────────────────────────────────────────────────────────────

@Composable
private fun VideoSaveSheet(
    isSaving: Boolean,
    progress: Float?,
    stage: VideoSaveStage,
    isCloud: Boolean,
    hasCloudCounterpart: Boolean,
    onPicked: () -> Unit,
    onCancel: () -> Unit,
) {
    // Synced video = device-source + cloud counterpart. The edit fans out to both sides
    // on save, so the subtitle mentions both instead of the device-only phrasing.
    val isSynced = !isCloud && hasCloudCounterpart
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            stringResource(R.string.editor_save_edits),
            color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            // Cloud edits upload back to Proton Drive; local edits save to the device's
            // MediaStore. The subtitle here orients the user before they pick a mode.
            stringResource(
                when {
                    isCloud  -> R.string.editor_save_sheet_cloud_video
                    isSynced -> R.string.editor_save_sheet_synced_video
                    else     -> R.string.editor_save_sheet_device_video
                }
            ),
            color = FgMute, fontSize = 13.sp,
        )
        Spacer(Modifier.height(18.dp))

        // The encode/encrypt phases report deterministic byte progress, so they get a
        // determinate bar. The CDN-upload phase's byte progress is too coarse to track
        // smoothly (it sits near 100 % then races), so it falls through to the
        // indeterminate spinner below — a moving spinner reads as "still working" rather
        // than a frozen bar.
        if (isSaving && progress != null && stage != VideoSaveStage.Idle &&
            stage != VideoSaveStage.Uploading) {
            Text(
                LocalContext.current.getString(
                    when (stage) {
                        VideoSaveStage.Encrypting -> R.string.video_editor_encrypting
                        else -> R.string.video_editor_reencoding
                    }
                ),
                color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = Accent,
                trackColor = TrackBg,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${(progress * 100).toInt()}%",
                color = FgMute, fontSize = 12.sp,
            )
        } else if (isSaving) {
            // Either pure stream-copy save (no progress reported) or the cloud upload
            // leg. Show an indeterminate spinner with a context-aware label so the user
            // always sees motion while the phase runs.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(
                        when (stage) {
                            VideoSaveStage.Uploading -> R.string.video_editor_uploading
                            else -> R.string.editor_saving
                        }
                    ),
                    color = FgPrimary, fontSize = 14.sp,
                )
            }
        } else {
            // Videos save as a copy only. Writing back over the source meant either MediaStore
            // consent for foreign URIs (device case) or trashing the cloud original after a
            // re-encode upload, both of which surfaced failure modes (read-error/SSL retries,
            // half-saved Drive entries) that left the user unsure whether the original survived.
            // A copy is unambiguous: the source is never touched, the edit lands next to it.
            SaveOptionRow(
                icon = Icons.Default.ContentCopy,
                title = stringResource(R.string.video_editor_save_copy),
                subtitle = stringResource(
                    when {
                        isCloud  -> R.string.editor_save_copy_subtitle_cloud_video
                        isSynced -> R.string.editor_save_copy_subtitle_synced
                        else     -> R.string.editor_save_copy_subtitle_device
                    }
                ),
                onClick = onPicked,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PanelChip)
                .clickable(enabled = !isSaving, onClick = onCancel)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(if (isSaving) R.string.editor_please_wait else R.string.cancel),
                color = if (isSaving) FgDim else FgPrimary,
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ─── Unified editor pill ─────────────────────────────────────────────────────

/**
 * Single horizontal pill — 38dp tall, icon + text in one row, optional accent fill when
 * selected. The canonical pill shape across every video editor panel. Pass
 * `clickable = false` for read-only readouts (e.g. the degrees indicator next to the
 * Rotate button) so the pill stays decorative.
 */
@Composable
private fun EditorPill(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    clickable: Boolean = true,
) {
    // Palette-aware background — `PillBg` follows the user's theme tokens; the previous
    // `PanelChip` was a fixed dark color that ignored light theme / non-default palettes.
    // Colours animate so selecting / enabling a pill eases in rather than flipping.
    val bg by animateColorAsState(
        if (selected) Accent.copy(alpha = 0.18f) else PillBg,
        tween(200), label = "pill_bg",
    )
    val fg by animateColorAsState(
        when {
            !enabled -> FgDim.copy(alpha = 0.4f)
            selected -> Accent
            else -> FgPrimary
        },
        tween(200), label = "pill_fg",
    )
    val borderMod = if (!selected) {
        Modifier.border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .height(38.dp)
            .background(bg, RoundedCornerShape(999.dp))
            .then(borderMod)
            .then(
                if (clickable) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        }
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * The clip-action controls as a single pill (the same shell as the transport pill it replaces on
 * selection): Split, Duplicate and Delete/Restore as compact icon + label items, so the actions read as
 * one control rather than three big tiles.
 */
@Composable
private fun ClipActionsPill(
    canSplit: Boolean,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    removed: Boolean,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(PillBgOpaque)
            .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ClipActionPillItem(
            icon = Icons.Default.ContentCut,
            label = stringResource(R.string.video_editor_split),
            enabled = canSplit,
            onClick = onSplit,
        )
        ClipActionPillItem(
            icon = Icons.Default.ContentCopy,
            label = stringResource(R.string.video_editor_duplicate),
            onClick = onDuplicate,
        )
        if (removed) {
            ClipActionPillItem(
                icon = Icons.Default.Restore,
                label = stringResource(R.string.video_editor_restore),
                onClick = onRestore,
            )
        } else {
            ClipActionPillItem(
                icon = Icons.Default.DeleteOutline,
                label = stringResource(R.string.video_editor_delete),
                destructive = true,
                onClick = onDelete,
            )
        }
    }
}

/** One icon + label item inside [ClipActionsPill]. */
@Composable
private fun ClipActionPillItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> FgDim.copy(alpha = 0.4f)
        destructive -> Color(0xFFFF453A)
        else -> FgPrimary
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(15.dp))
        Text(label, color = tint, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The "+" tile at the left of the cut track: picks another video to append as a new timeline source.
 *  Sized to the slim clip rail so the two read as one control, matching the reference gallery editor. */
@Composable
private fun AddClipButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 54.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PanelChip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = stringResource(R.string.video_editor_add_video),
            tint = Accent,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** One row in an [EditorActionSheet]. */
internal data class EditorSheetAction(
    val icon: ImageVector,
    val title: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Icon for a history row, keyed off the operation's label resource so each edit reads at a glance. */
private fun undoActionIcon(labelRes: Int): ImageVector = when (labelRes) {
    R.string.video_editor_split, R.string.video_editor_split_music, R.string.video_editor_trim ->
        Icons.Default.ContentCut
    R.string.video_editor_remove_section, R.string.video_editor_remove_music -> Icons.Default.DeleteOutline
    R.string.video_editor_restore_section -> Icons.Default.Restore
    R.string.video_editor_silence_section -> Icons.AutoMirrored.Filled.VolumeOff
    R.string.video_editor_crop, R.string.video_editor_reset_crop -> Icons.Default.Crop
    R.string.video_editor_rotate -> Icons.AutoMirrored.Filled.RotateRight
    R.string.editor_tool_filter -> Icons.Default.Tune
    R.string.video_editor_add_music -> Icons.Default.MusicNote
    R.string.video_editor_mute -> Icons.AutoMirrored.Filled.VolumeOff
    R.string.video_editor_volume -> Icons.AutoMirrored.Filled.VolumeUp
    R.string.video_editor_reorder -> Icons.Default.SwapHoriz
    else -> Icons.AutoMirrored.Filled.Undo
}

/**
 * A bottom action sheet built from the app's shared [ActionSheetRow] on the standard [SheetBg], so the
 * editor's clip / music / undo menus read like every other action drawer in the app rather than a raw
 * dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorActionSheet(actions: List<EditorSheetAction>, onDismiss: () -> Unit, title: String? = null) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    color = FgMute,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            actions.forEach { a ->
                ActionSheetRow(
                    icon = a.icon,
                    title = a.title,
                    destructive = a.destructive,
                    onClick = a.onClick,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

