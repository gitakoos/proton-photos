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

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package eu.akoos.photos.presentation.editor

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.ErrorPopup
import eu.akoos.photos.presentation.editor.components.SaveOptionRow
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PanelBg
import eu.akoos.photos.presentation.theme.PanelChip
import eu.akoos.photos.presentation.theme.TrackBg
import eu.akoos.photos.presentation.util.formatVideoTime
import kotlin.math.max
import kotlin.math.min

private enum class VideoTool(val labelRes: Int, val icon: ImageVector) {
    Trim(R.string.video_editor_trim, Icons.Default.ContentCut),
    Crop(R.string.video_editor_crop, Icons.Default.Crop),
    Rotate(R.string.video_editor_rotate, Icons.AutoMirrored.Filled.RotateRight),
    Audio(R.string.video_editor_audio, Icons.Default.MusicNote),
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
    onBack: () -> Unit,
    onSaved: () -> Unit,
    vm: VideoEditorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var activeTool by remember { mutableStateOf(VideoTool.Trim) }
    var showSaveSheet by remember { mutableStateOf(false) }
    val saveSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(localUri, cloudPhoto?.linkId, externalRequest?.uri) {
        when {
            // External entries (system "Open with" / "Edit with" chooser) take priority
            // over the local path so a caller passing both an externalRequest and stale
            // local* params still routes through the always-copy save flow.
            externalRequest != null -> vm.loadExternal(
                externalRequest.uri, externalRequest.displayName, externalRequest.mimeType,
            )
            cloudPhoto != null -> vm.loadCloud(cloudPhoto, sourceAlbumLinkId)
            localUri != null   -> vm.loadLocal(localUri, localDisplayName ?: "video.mp4", localMimeType ?: "video/mp4")
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

    // Side effects on save completion. LaunchedEffect rather than remember{} so the
    // body runs OUTSIDE composition — calling navController.popBackStack() (via onSaved)
    // during composition leaves the ModalBottomSheet half-dismissed and the user has to
    // re-tap save. Firing the effect after composition lets the sheet animate out as the
    // screen pops normally.
    //
    // Gate on pendingDeleteIntent: if the VM surfaced an OS delete-consent dialog
    // (Synced + Overwrite-fallback-Copy case), wait for it to resolve before navigating
    // so the system prompt isn't built while the screen pops out from under it.
    androidx.compose.runtime.LaunchedEffect(state.saveResult, state.pendingDeleteIntent) {
        if (state.pendingDeleteIntent != null) return@LaunchedEffect
        when (state.saveResult) {
            is VideoSaveResult.Success -> {
                showSaveSheet = false
                vm.consumeSaveResult()
                onSaved()
            }
            is VideoSaveResult.SuccessAsCopy -> {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.editor_saved_as_copy_toast),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
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

    // System consent dialog for overwriting foreign MediaStore URIs — same pattern as
    // PhotoEditorScreen. The video VM surfaces a PendingIntent the first time an
    // Overwrite on a camera-roll item throws SecurityException; we launch it and feed
    // the user's choice back into onWritePermission{Granted,Denied}.
    val writePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) vm.onWritePermissionGranted()
        else vm.onWritePermissionDenied()
    }
    androidx.compose.runtime.LaunchedEffect(state.pendingWriteIntent) {
        val pi = state.pendingWriteIntent ?: return@LaunchedEffect
        writePermissionLauncher.launch(
            androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()
        )
    }

    // OS consent dialog for deleting the original device file after a Synced + Overwrite
    // fallback-to-Copy. The VM surfaces createDeleteRequest's PendingIntent; on either
    // Allow or Deny the system has actioned the choice by the callback, so we just clear
    // the pending state and let the saveResult Effect proceed.
    val deletePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { _ -> vm.onDeletePermissionResolved() }
    androidx.compose.runtime.LaunchedEffect(state.pendingDeleteIntent) {
        val pi = state.pendingDeleteIntent ?: return@LaunchedEffect
        deletePermissionLauncher.launch(
            androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()
        )
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
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(sourceUri)))
                prepare()
                // Start with playWhenReady=true so the renderer immediately decodes and
                // paints frame 0 to the surface — the listener below pauses on first
                // STATE_READY, by which point the user sees the still image instead of
                // a black void. Without this, playWhenReady=false leaves the renderer
                // idle and the surface stays black until the user taps play.
                playWhenReady = true
                repeatMode = ExoPlayer.REPEAT_MODE_ONE
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

    // Music overlay preview — a separate ExoPlayer for the picked audio file so the
    // user hears the music alongside the video in the editor (matches what the save
    // pipeline will mix). Sync is best-effort: play/pause follows the video player and
    // we seek the overlay to its trim-start whenever the video position jumps via
    // scrub. Without this the music gain slider was inaudible until after save.
    val overlayPlayer = remember(state.audioOverlayUri) {
        val uri = state.audioOverlayUri ?: return@remember null
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            prepare()
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
    // Follow the video player's play / pause / seek so the two stay in lockstep during
    // preview. The video player is the timeline source of truth; the overlay just
    // mirrors. Seek maps video-position → overlay-position via the user's trim offsets:
    // when the video is at trimStartMs the overlay should be at audioTrimStartMs.
    androidx.compose.runtime.LaunchedEffect(
        previewPlayer, overlayPlayer, state.audioTrimStartMs, state.trimStartMs,
    ) {
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
                if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) {
                    val videoOffset = (newPosition.positionMs - state.trimStartMs).coerceAtLeast(0L)
                    ov.seekTo(state.audioTrimStartMs + videoOffset)
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

    // Save is allowed when we have a source URI to edit — local OR a downloaded
    // cloud file. The cloud branch flips the URI on inside loadCloud once the file
    // lands in cache, so isLoading guards the early window.
    val hasSource = state.sourceUri != null && !state.isLoading

    // Filmstrip thumbnails live at the screen scope so swapping tools (Trim → Crop →
    // Trim) does NOT throw away the extracted bitmaps and re-extract — each re-extract
    // is a 12-frame × ~200 ms MediaMetadataRetriever pass. Hoisting here means the
    // SnapshotStateList outlives the inner `when (activeTool)` branch swap.
    val filmstripThumbnails = remember(state.sourceUri, state.durationMs) {
        androidx.compose.runtime.mutableStateListOf<android.graphics.Bitmap>()
    }
    LaunchedEffect(state.sourceUri, state.durationMs) {
        val uri = state.sourceUri ?: return@LaunchedEffect
        val durationMs = state.durationMs
        if (durationMs <= 0L) return@LaunchedEffect
        filmstripThumbnails.clear()
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            runCatching { retriever.setDataSource(context, Uri.parse(uri)) }
                .onFailure { runCatching { retriever.release() }; return@withContext }
            val count = 12
            // 320 px target keeps the 64dp-tall strip crisp on 3x density displays without
            // burning extra extract time; getScaledFrameAtTime preserves aspect.
            val targetSize = 320
            for (i in 0 until count) {
                val ratio = (i.toFloat() + 0.5f) / count
                val tUs = (ratio * durationMs * 1000L).toLong()
                val bmp = runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            tUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            targetSize, targetSize,
                        )
                    } else {
                        val full = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        full?.let { android.graphics.Bitmap.createScaledBitmap(it, targetSize, targetSize, true) }
                    }
                }.getOrNull()
                if (bmp != null) filmstripThumbnails.add(bmp)
            }
            runCatching { retriever.release() }
        }
    }

    // Unsaved-changes guard: if the user touched anything (trim window, crop, rotation,
    // audio overlay), the back press / Close button should ask for confirmation so they
    // don't lose work. Reset state (everything at defaults) → back navigates immediately.
    val hasUnsavedChanges = state.trimStartMs != 0L ||
        (state.durationMs > 0L && state.trimEndMs != state.durationMs) ||
        state.cropRect != null ||
        state.rotationDegrees != 0 ||
        state.audioOverlayUri != null
    var showDiscardDialog by remember { mutableStateOf(false) }
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
            title = state.displayName.ifBlank { "" },
            isSaving = state.isSaving,
            onBack = confirmedOnBack,
            onSave = { if (hasSource) showSaveSheet = true },
        )

        // ── Preview area ─────────────────────────────────────────────────────
        // Crop tab swaps out the ExoPlayer for the first-frame bitmap so the user can
        // drag corners reliably. Pointer events on a video Surface are unreliable —
        // some devices route them to the video stack and we never see them in Compose.
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
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
                        onCropChange = { vm.setCropRect(it) },
                    )
                else -> if (previewPlayer != null) VideoPreview(
                    player = previewPlayer,
                    initialAspect = if (state.sourceWidth > 0 && state.sourceHeight > 0)
                        state.sourceWidth.toFloat() / state.sourceHeight
                    else 16f / 9f,
                    rotationDegrees = state.rotationDegrees,
                )
            }
        }

        // ── Play + speed pill, sits above the bottom panel ───────────────────
        if (hasSource && previewPlayer != null) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                VideoEditorPlayPill(player = previewPlayer!!)
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
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Tool panel — horizontal-padding only, no outer pill. The Trim /
            // Crop / Rotate / Audio composables render their own pill recipes.
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).wrapContentHeight(),
            ) {
                when (activeTool) {
                    VideoTool.Trim -> TrimPanel(
                        state = state,
                        vm = vm,
                        previewPlayer = previewPlayer,
                        thumbnails = filmstripThumbnails,
                    )
                    VideoTool.Crop -> CropPanel(state = state, vm = vm)
                    VideoTool.Rotate -> RotatePanel(state = state, vm = vm)
                    VideoTool.Audio -> AudioPanel(state = state, vm = vm, previewPlayer = previewPlayer)
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

    val hasCloudCounterpart by vm.hasCloudCounterpart.collectAsStateWithLifecycle()
    if (showSaveSheet && hasSource) {
        ModalBottomSheet(
            onDismissRequest = { if (!state.isSaving) showSaveSheet = false },
            sheetState = saveSheetState,
            containerColor = PanelBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            VideoSaveSheet(
                isSaving = state.isSaving,
                progress = state.saveProgress,
                stage = state.saveStage,
                isCloud = cloudPhoto != null,
                hasCloudCounterpart = hasCloudCounterpart,
                onPicked = { mode -> vm.save(mode) },
                onCancel = { if (!state.isSaving) showSaveSheet = false },
            )
        }
    }
}

// ─── Top bar ─────────────────────────────────────────────────────────────────

@Composable
private fun VideoTopBar(
    title: String,
    isSaving: Boolean,
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
        Box(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.ifBlank { "" },
                color = FgPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        VideoSavePill(isSaving = isSaving, onClick = onSave)
    }
}

@Composable
private fun VideoSavePill(isSaving: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Accent)
            .clickable(enabled = !isSaving, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text(stringResource(R.string.action_save), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(
                if (selected) Accent.copy(alpha = 0.22f) else Color.Transparent,
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = tool.icon,
            contentDescription = label,
            tint = if (selected) Accent else FgDim,
            modifier = Modifier.size(22.dp),
        )
    }
}

// ─── Tool panels ─────────────────────────────────────────────────────────────

@Composable
private fun TrimPanel(
    state: VideoEditorUiState,
    vm: VideoEditorViewModel,
    previewPlayer: ExoPlayer?,
    thumbnails: List<android.graphics.Bitmap>,
) {
    val duration = state.durationMs.coerceAtLeast(1L)
    // Poll the ExoPlayer's currentPosition at ~30 fps so the playhead line on the
    // filmstrip tracks playback smoothly. The remember key includes the player so a new
    // load (URI change) restarts the polling against the fresh instance.
    //
    // LaunchedEffect's closure captures `duration` ONCE per key change — if we'd keyed
    // on the player only, the coerce upper-bound would freeze at the initial duration
    // (1 L from the coerceAtLeast fallback while state.durationMs is 0 during early
    // load). That's how the playhead got stuck pinned to x=0: `currentPosition.coerceIn(
    // 0L, 1L)` is always 0 or 1, no matter how far playback actually advanced. Drop the
    // coerceIn — the Canvas's `playheadMs / durationMs` already clips into [0, w].
    var playheadMs by remember(previewPlayer) { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(previewPlayer) {
        while (true) {
            val p = previewPlayer ?: break
            playheadMs = p.currentPosition
            kotlinx.coroutines.delay(33)
        }
    }
    val sourceUriString = state.sourceUri
    val sourceUri = remember(sourceUriString) { sourceUriString?.let { Uri.parse(it) } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Start / duration / end pills sit ABOVE the filmstrip — start pinned to the left
        // edge of the strip, end pinned to the right, duration centered. The pill row
        // anchors the numbers to the same horizontal extents as the trim handles so they
        // read as part of the timeline rather than detached labels.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimePill(formatVideoTime(withTenths = true, ms =state.trimStartMs))
            TimePill(formatVideoTime(withTenths = true, ms =(state.trimEndMs - state.trimStartMs).coerceAtLeast(0L)), highlight = true)
            TimePill(formatVideoTime(withTenths = true, ms =state.trimEndMs))
        }
        VideoFilmstripTrimmer(
            sourceUri = sourceUri,
            durationMs = duration,
            trimStartMs = state.trimStartMs,
            trimEndMs = state.trimEndMs,
            playheadMs = playheadMs,
            thumbnails = thumbnails,
            onTrimChange = { start, end -> vm.setTrimRange(start, end) },
            onScrubMs = { ms ->
                playheadMs = ms
                previewPlayer?.seekTo(ms)
            },
        )
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

@Composable
private fun CropPanel(state: VideoEditorUiState, vm: VideoEditorViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorPill(
            label = LocalContext.current.getString(R.string.video_editor_reset_crop),
            icon = Icons.Default.Restore,
            selected = false,
            onClick = { vm.setCropRect(null) },
            enabled = state.cropRect != null,
        )
    }
}

@Composable
private fun RotatePanel(state: VideoEditorUiState, vm: VideoEditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorPill(
                label = LocalContext.current.getString(R.string.video_editor_rotate),
                icon = Icons.AutoMirrored.Filled.RotateRight,
                selected = false,
                onClick = { vm.rotate90Cw() },
            )
            // Non-clickable degrees readout pill — same shape, no interaction.
            EditorPill(
                label = "${state.rotationDegrees}°",
                icon = null,
                selected = false,
                onClick = {},
                clickable = false,
            )
        }
    }
}

@Composable
private fun AudioPanel(state: VideoEditorUiState, vm: VideoEditorViewModel, previewPlayer: ExoPlayer?) {
    val context = LocalContext.current
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ── Row 1: original audio track, with a volume slider ─────────────────
        // Row 1: original audio with a 0-100% gain slider. Save pipeline mixes
        // source + overlay sample-by-sample; gain=0 omits the track.
        AudioTrackRow(
            label = context.getString(R.string.video_editor_audio_original),
            gain = state.originalAudioGain,
            onGainChange = vm::setOriginalAudioGain,
            trailing = null,
        )

        // ── Row 2: overlay music — pick or show + volume + trim slider ──
        if (state.audioOverlayUri == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                EditorPill(
                    label = context.getString(R.string.video_editor_add_music),
                    icon = Icons.Default.MusicNote,
                    selected = false,
                    onClick = { pickAudio.launch("audio/*") },
                )
            }
        } else {
            AudioTrackRow(
                label = state.audioOverlayDisplayName ?: "music",
                gain = state.musicAudioGain,
                onGainChange = vm::setMusicAudioGain,
                trailing = {
                    // Compact X button — the explicit "Remove music" label was visual
                    // weight in a row that already names the file; an icon-only target is
                    // enough and matches the dismiss bubbles elsewhere in the editor.
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(PanelChip)
                            .clickable { vm.clearAudioOverlay() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = context.getString(R.string.video_editor_remove_music),
                            tint = FgPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                },
            )

            val totalAudio = state.audioOverlayDurationMs.coerceAtLeast(1L)
            RangeTrimSlider(
                durationMs = totalAudio,
                startMs = state.audioTrimStartMs,
                endMs = state.audioTrimEndMs,
                onChange = { start, end -> vm.setAudioTrimRange(start, end) },
                onScrubMs = { audioMs ->
                    val videoOffset = state.trimStartMs + (audioMs - state.audioTrimStartMs).coerceAtLeast(0L)
                    previewPlayer?.seekTo(videoOffset.coerceIn(0L, state.durationMs))
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(context.getString(R.string.video_editor_music_start),
                        color = FgMute, fontSize = 11.sp)
                    Text(formatVideoTime(withTenths = true, ms =state.audioTrimStartMs),
                        color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(context.getString(R.string.video_editor_music_end),
                        color = FgMute, fontSize = 11.sp)
                    Text(formatVideoTime(withTenths = true, ms =state.audioTrimEndMs),
                        color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * Single row of the audio panel: speaker icon, label, gain slider, current percent, and
 * an optional trailing slot (used for the "Remove music" pill on the overlay row).
 * Mute and full are the natural endpoints of the slider — at 0 the icon dims and the
 * pipeline drops that track from the mix.
 */
@Composable
private fun AudioTrackRow(
    label: String,
    gain: Float,
    onGainChange: (Float) -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    val muted = gain <= 0.001f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (muted) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                null,
                tint = if (muted) FgMute else Accent,
                modifier = Modifier.size(20.dp),
            )
            Text(
                label,
                color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                "${(gain * 100f).toInt()}%",
                color = if (muted) FgMute else FgPrimary,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            if (trailing != null) trailing()
        }
        androidx.compose.material3.Slider(
            value = gain,
            onValueChange = onGainChange,
            valueRange = 0f..1f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = PanelChip,
            ),
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
private fun VideoEditorPlayPill(player: ExoPlayer) {
    var isPlaying by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            kotlinx.coroutines.delay(80)
        }
    }
    var speed by remember { mutableStateOf(1f) }
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
        // Speed control: collapsed pill shows current rate, tap expands into 4 chips.
        if (!speedExpanded) {
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

private fun formatSpeed(s: Float): String = when (s) {
    0.25f -> "¼×"
    0.5f -> "½×"
    1f -> "1×"
    2f -> "2×"
    else -> "${s}×"
}

// ─── Video filmstrip + playhead trim slider ──────────────────────────────────

/**
 * Trim widget that looks and feels like a tiny video editor — a strip of evenly-spaced
 * frame thumbnails as the track, two heavy bar-shaped handles at the edges of the
 * selected range, a darker mask over the trimmed-off ends, and a thin white playhead
 * line that follows the live ExoPlayer position. Dragging the playhead seeks the
 * preview; dragging an edge handle moves the trim in/out point.
 *
 * Thumbnails are extracted once per [sourceUri] via MediaMetadataRetriever — 12 frames
 * evenly across the full source duration. We deliberately don't reshuffle the strip when
 * the user moves the trim range; that would re-extract on every drag and tank the UX.
 */
@Composable
private fun VideoFilmstripTrimmer(
    sourceUri: Uri?,
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    playheadMs: Long,
    /** Hoisted at the screen scope; this composable just renders. Hoisting prevents the
     *  strip from resetting on a tab-switch round trip since the extraction state no longer
     *  dies with this composable. */
    thumbnails: List<android.graphics.Bitmap>,
    onTrimChange: (start: Long, end: Long) -> Unit,
    onScrubMs: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val handleWidthPx = with(density) { 14.dp.toPx() }
    // Generous hit-slop around each trim bar — 48dp is the platform minimum touch target
    // and it gives the user a comfortable margin to grab the start/end edges. The
    // playhead is only picked up when the touch is well inside the trimmed window so
    // the bars always win when the gesture starts near an edge.
    val touchSlopPx = with(density) { 48.dp.toPx() }
    val stripHeight = 64.dp

    // Thumbnails are passed in from the screen scope so they survive activeTool tab
    // swaps — extracting them inside this composable means switching to Crop/Rotate/
    // Audio and back re-runs the 12-frame MediaMetadataRetriever pass every time.

    var grabbed by remember { mutableStateOf<Grabbed?>(null) }
    var canvasWidthPx by remember { mutableFloatStateOf(1f) }
    // Capture composable colors out of the Canvas draw scope (Canvas's body is *not*
    // composable, so we can't read Accent there).
    val accentColor = Accent

    // pointerInput's lambda captures its closure ONCE per key change — recompositions
    // don't refresh the captured props. Without these State proxies the gesture handlers
    // see stale `trimStartMs`/`trimEndMs` after the first drag: the user trims start to
    // 5 s, releases, then taps the bar to drag it back — but the picker still thinks
    // start is at x=0 (stale) and routes the touch to Playhead instead of Start. Using
    // a State<Long> reference whose `value` is always the latest snapshot fixes that
    // without re-keying the gesture loop (which would restart drags mid-motion).
    val latestTrimStart by androidx.compose.runtime.rememberUpdatedState(trimStartMs)
    val latestTrimEnd by androidx.compose.runtime.rememberUpdatedState(trimEndMs)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(stripHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(TrackBg)
            .onSizeChanged { canvasWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            // Key on `durationMs` only — including the trim values here would restart
            // the gesture pipeline on every drag step (the user types a tiny drag →
            // onTrimChange fires → trim* updates → pointerInput resets → user has to
            // release-and-regrab to keep dragging). The handlers read latestTrimStart /
            // latestTrimEnd via rememberUpdatedState so they always see fresh values
            // without restarting the gesture loop.
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val startX = latestTrimStart.toFloat() / durationMs * w
                        val endX = latestTrimEnd.toFloat() / durationMs * w
                        val dStart = kotlin.math.abs(offset.x - startX)
                        val dEnd = kotlin.math.abs(offset.x - endX)
                        // Edge bars win inside their hit-slop even from within the active
                        // range. Whichever bar is closer takes the gesture.
                        grabbed = when {
                            dStart < touchSlopPx && dStart <= dEnd -> Grabbed.Start
                            dEnd < touchSlopPx && dEnd < dStart -> Grabbed.End
                            // Tap inside the active range, well clear of either bar → scrub.
                            offset.x > startX + touchSlopPx && offset.x < endX - touchSlopPx
                                -> Grabbed.Playhead
                            else -> null
                        }
                        if (grabbed == Grabbed.Playhead) {
                            val pct = (offset.x / w).coerceIn(0f, 1f)
                            onScrubMs((pct * durationMs).toLong().coerceIn(latestTrimStart, latestTrimEnd))
                        }
                    },
                    onDrag = { change, _ ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val pct = (change.position.x / w).coerceIn(0f, 1f)
                        val ms = (pct * durationMs).toLong()
                        when (grabbed) {
                            Grabbed.Start -> onTrimChange(ms, latestTrimEnd)
                            Grabbed.End -> onTrimChange(latestTrimStart, ms)
                            Grabbed.Playhead -> onScrubMs(ms.coerceIn(latestTrimStart, latestTrimEnd))
                            null -> Unit
                        }
                        change.consume()
                    },
                    onDragEnd = { grabbed = null },
                    onDragCancel = { grabbed = null },
                )
            },
    ) {
        // Filmstrip — always render all 12 slots; arriving thumbnails fill their slot, the
        // rest stay as track-coloured placeholders. A fixed 12-slot row keeps the layout
        // from jumping (laying out only as frames arrive would resize slots from full-width
        // to halves to thirds as each lands); only the bitmap inside each slot pops in.
        val slotCount = 12
        Row(modifier = Modifier.fillMaxSize()) {
            for (i in 0 until slotCount) {
                val bmp = thumbnails.getOrNull(i)
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(modifier = Modifier.weight(1f).fillMaxSize().background(TrackBg))
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val startX = (trimStartMs.toFloat() / durationMs * w).coerceIn(0f, w)
            val endX = (trimEndMs.toFloat() / durationMs * w).coerceIn(0f, w)
            // Dim the trimmed-off portions so the active range pops visually.
            val mask = Color.Black.copy(alpha = 0.55f)
            drawRect(mask, topLeft = Offset(0f, 0f), size = GSize(startX, h))
            drawRect(mask, topLeft = Offset(endX, 0f), size = GSize(w - endX, h))
            // Accent-coloured outline around the selected range — top/bottom bars and
            // the two edge handles. The bar handles are wider than the slider thumbs so
            // a fingertip naturally lands on them without precision dragging.
            val barW = handleWidthPx
            drawRect(accentColor, topLeft = Offset(startX, 0f), size = GSize(barW, h))
            drawRect(accentColor, topLeft = Offset(endX - barW, 0f), size = GSize(barW, h))
            drawRect(accentColor, topLeft = Offset(startX, 0f), size = GSize(endX - startX, 3f))
            drawRect(accentColor, topLeft = Offset(startX, h - 3f), size = GSize(endX - startX, 3f))
            // Playhead line — thin, bright, with a small triangle on top so it's spotted
            // immediately when the strip is busy.
            val playX = (playheadMs.toFloat() / durationMs * w).coerceIn(0f, w)
            drawRect(
                color = Color.White,
                topLeft = Offset(playX - 1.5f, 0f),
                size = GSize(3f, h),
            )
        }
    }
}

private enum class Grabbed { Start, End, Playhead }

// ─── Save sheet ──────────────────────────────────────────────────────────────

@Composable
private fun VideoSaveSheet(
    isSaving: Boolean,
    progress: Float?,
    stage: VideoSaveStage,
    isCloud: Boolean,
    hasCloudCounterpart: Boolean,
    onPicked: (VideoSaveMode) -> Unit,
    onCancel: () -> Unit,
) {
    // Synced video = device-source + cloud counterpart. The edit fans out to both sides
    // on save, so the subtitle mentions both instead of the device-only phrasing.
    val isSynced = !isCloud && hasCloudCounterpart
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 24.dp),
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

        // Both Encoding and Uploading phases get a progress bar now — the upload leg has
        // per-block byte progress wired through from PhotoUploadService so the sheet shows
        // a live count instead of a silent 100 % sit during the (sometimes long) cloud
        // upload. Two sequential 0→100 % runs is clearer than mystery latency.
        if (isSaving && progress != null && stage != VideoSaveStage.Idle) {
            Text(
                LocalContext.current.getString(
                    when (stage) {
                        VideoSaveStage.Encoding -> R.string.video_editor_reencoding
                        VideoSaveStage.Encrypting -> R.string.video_editor_encrypting
                        VideoSaveStage.Uploading -> R.string.video_editor_uploading
                        VideoSaveStage.Idle -> R.string.video_editor_reencoding // never reached
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
            // leg after a re-encode completed. Show a spinner with a context-aware
            // label so the user always sees what's currently happening.
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
            // Per user request: videos only support Save-as-Copy. The Overwrite path
            // for video involves either MediaStore consent for foreign URIs (device
            // case) or trashing the cloud original after a re-encode upload — both
            // surfaced as failure modes (read-error/SSL retries, half-saved Drive
            // entries) that left the user unsure whether the original survived. Copy
            // is unambiguous: the source is never touched, the edit lands next to it.
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
                onClick = { onPicked(VideoSaveMode.Copy) },
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
    val bg = if (selected) Accent.copy(alpha = 0.18f) else PillBg
    val fg = when {
        !enabled -> FgDim.copy(alpha = 0.4f)
        selected -> Accent
        else -> FgPrimary
    }
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

