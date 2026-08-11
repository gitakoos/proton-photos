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

import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.ErrorPopup
import eu.akoos.photos.presentation.editor.components.SaveOptionRow

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Deblur
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PanelChip
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.theme.TrackBg
import eu.akoos.photos.util.ImageFit
import eu.akoos.photos.util.fitImageInBox
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Match the photos page top filter row recipe verbatim — one shared shape token used
// by every pill in the editor so the editor and gallery look like they share a
// component library. Anything bigger than 999.dp is just a fully-rounded capsule.
internal val pillShape = RoundedCornerShape(999.dp)

private enum class Tool(@androidx.annotation.StringRes val labelRes: Int, val icon: ImageVector) {
    Adjust(R.string.editor_tool_adjust, Icons.Default.Tune),
    Filter(R.string.editor_tool_filter, Icons.Default.AutoFixHigh),
    Color(R.string.editor_tool_color, Icons.Default.Tonality),
    Crop(R.string.video_editor_crop, Icons.Default.Crop),
    Redact(R.string.editor_tool_redact, Icons.Default.Brush),
    Draw(R.string.editor_tool_draw, Icons.Default.Draw),
    Text(R.string.editor_tool_text, Icons.Default.TextFields),
    Rotate(R.string.video_editor_rotate, Icons.AutoMirrored.Filled.RotateRight),
}

/** Which slider the Adjust tab is currently exposing in the floating slider pill.
 *  Null until the user picks one of the adjustment chips — null means no slider
 *  pill is shown. */
private enum class Adjustment { Brightness, Exposure, Contrast, Highlights, Shadows, Saturation, Vibrance, Tone, Temperature, Sharpen, Grain, Vignette, Fade }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditorScreen(
    localUri: String? = null,
    localDisplayName: String? = null,
    localMimeType: String? = null,
    /** The device photo's original DATE_TAKEN (ms). A Copy inherits it so the edit sorts next to the
     *  original instead of jumping to the top of the timeline. Null for a foreign "Open with" file. */
    localCaptureTimeMs: Long? = null,
    cloudPhoto: CloudPhoto? = null,
    /** Non-null when the editor was opened on a Synced photo (device + cloud). The local
     *  save path then propagates the edit up to Drive too so the cloud version doesn't
     *  stay stale. cloudPhoto remains null in this case because the EDIT SOURCE is the
     *  device file — the cloud counterpart is just a side-effect target. */
    syncedCloudCounterpart: CloudPhoto? = null,
    /** If the photo was opened from an album, the new cloud uploads are added to it too. */
    sourceAlbumLinkId: String? = null,
    /** Non-null when the editor was launched from a system "Open with" / "Edit with"
     *  chooser. Takes precedence over [localUri] / [cloudPhoto]; the save flow is
     *  forced to copy-to-MediaStore so the foreign original is never mutated. */
    externalRequest: eu.akoos.photos.navigation.ExternalEditRequest? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    vm: PhotoEditorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var activeTool by remember { mutableStateOf(Tool.Adjust) }
    // Adjust tab exposes ONE slider at a time — the user picks which adjustment
    // (Brightness / Exposure / Contrast / Highlights / Shadows / Saturation / Tone /
    // Temperature) via the pill row, and only then does the floating slider pill appear
    // above the row. Null = no slider shown.
    var activeAdjustment by remember { mutableStateOf<Adjustment?>(null) }
    // Pen tool: current stroke colour + width (screen dp), used by the Draw overlay and panel.
    var drawColor by remember { mutableStateOf(DRAW_COLORS.first()) }
    var drawWidth by remember { mutableFloatStateOf(8f) }
    // Colour tool: Curves vs HSL sub-mode, the curve channel, and the selected HSL band.
    var colorMode by remember { mutableStateOf(ColorMode.Curves) }
    var curveChannel by remember { mutableStateOf(CurveChannel.RGB) }
    var hslBandIndex by remember { mutableIntStateOf(0) }
    // Text tool: which overlay is open for inline editing (typed directly on the photo), if any.
    var editingTextId by remember { mutableStateOf<Int?>(null) }
    // Resetting back to null whenever the tool changes prevents a "stale" slider
    // pill flashing when the user pops between tabs.
    androidx.compose.runtime.LaunchedEffect(activeTool) {
        if (activeTool != Tool.Adjust) activeAdjustment = null
        // Leaving the Text tool: close any open inline editor first, then bake the text back into the
        // preview (in the tool the overlay draws it live, so a move is a cheap redraw, not a re-bake).
        if (activeTool != Tool.Text) {
            val id = editingTextId
            editingTextId = null
            if (id != null) vm.commitTextEdit(id)
        }
        vm.setTextToolActive(activeTool == Tool.Text)
    }
    var showSaveSheet by remember { mutableStateOf(false) }
    // Press-and-hold the preview to peek the untouched original (before/after compare).
    var comparing by remember { mutableStateOf(false) }
    val saveSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Draft rect for the free-form crop. Stays null until the user enters the Crop
    // tool, at which point it seeds from the committed cropRect (or full-image bounds).
    // The user drags corners / pan the rect and each change commits live via vm.applyCrop;
    // pendingCropRect just mirrors that committed value for the overlay. Snapped chips and
    // Reset also seed this rect.
    var pendingCropRect by remember { mutableStateOf<android.graphics.Rect?>(null) }
    // The shape the crop is locked to. Deliberately NOT in EditorAdjustments: adjustments are
    // undo/redo state, so a lock stored there would push an undo entry on every chip tap.
    // It outlives a tool switch on purpose, because the rect does too (it is restored from the
    // committed cropRect above): clearing only the lock would show the user a locked-looking
    // frame with Free lit, and the next drag would then break the shape they can see.
    var lockedAspect by remember { mutableStateOf(CropAspect.Free) }
    // A quarter turn swaps the display's axes, so the locked shape turns with it and 4:3 becomes
    // 3:4. Driven off rotationDegrees rather than the rotate button, so undo and redo of a turn
    // carry the lock back too. A half turn leaves the shape alone.
    var lastLockRotation by remember { mutableStateOf(state.adjustments.rotationDegrees) }
    androidx.compose.runtime.LaunchedEffect(state.adjustments.rotationDegrees) {
        val now = state.adjustments.rotationDegrees
        if (((now - lastLockRotation) / 90) % 2 != 0) lockedAspect = lockedAspect.turned()
        lastLockRotation = now
    }
    // Seed pendingCropRect whenever the user enters the Crop tool, and clear it when
    // they leave. Full-image bounds come from the DISPLAYED crop bitmap (rotation already
    // baked in, so width/height match the rect's display space). adjustedBitmapNoCrop may
    // not be ready yet — handled by the LaunchedEffect re-running on it.
    val cropDisplayBitmap = state.adjustedBitmapNoCrop ?: state.originalBitmap
    androidx.compose.runtime.LaunchedEffect(activeTool, cropDisplayBitmap) {
        if (activeTool == Tool.Crop) {
            val disp = cropDisplayBitmap
            if (disp != null && pendingCropRect == null) {
                pendingCropRect = state.adjustments.cropRect
                    ?: android.graphics.Rect(0, 0, disp.width, disp.height)
            }
        } else {
            pendingCropRect = null
        }
    }
    // Inform the VM about the source album so save() can re-attach the new linkId.
    // LaunchedEffect over the older remember{...; Unit} hack — Compose lint flags the
    // dropped-Unit form as fragile (future runtime tweaks could stop firing the side
    // effect when the result is unused).
    androidx.compose.runtime.LaunchedEffect(sourceAlbumLinkId) {
        vm.setSourceAlbumLinkId(sourceAlbumLinkId)
    }
    // Inform the VM about the cloud counterpart (Synced case) so save() can propagate
    // the edit to Drive after the local file is written.
    androidx.compose.runtime.LaunchedEffect(syncedCloudCounterpart?.linkId) {
        vm.setCloudCounterpart(syncedCloudCounterpart)
    }

    // Load source once. externalRequest wins when present so the system "Edit with"
    // chooser entry path doesn't fight with a stale localUri left in scope.
    androidx.compose.runtime.LaunchedEffect(externalRequest?.uri, localUri, cloudPhoto?.linkId) {
        when {
            externalRequest != null -> vm.loadExternal(
                uri = externalRequest.uri,
                displayName = externalRequest.displayName,
                mimeType = externalRequest.mimeType,
            )
            cloudPhoto != null -> vm.loadCloud(cloudPhoto)
            localUri != null   -> vm.loadLocal(localUri, localDisplayName ?: "photo.jpg", localMimeType ?: "image/jpeg", localCaptureTimeMs)
        }
    }

    // System consent dialog for overwriting foreign MediaStore URIs.
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
    // Allow or Deny the system has actioned the choice by the time we get the callback,
    // so we just clear the pending state and let the saveResult Effect run as usual.
    val deletePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { _ -> vm.onDeletePermissionResolved() }
    androidx.compose.runtime.LaunchedEffect(state.pendingDeleteIntent) {
        val pi = state.pendingDeleteIntent ?: return@LaunchedEffect
        deletePermissionLauncher.launch(
            androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()
        )
    }

    // Navigate away after a successful save. SuccessAsCopy gets a toast so the user knows
    // the original wasn't replaced. Gate on pendingDeleteIntent: if the VM surfaced an
    // OS delete-consent dialog (Synced + fallback case), we must wait for it to resolve
    // before navigating, otherwise the screen pops back while the system prompt is still
    // building and the user never sees the Allow/Deny choice.
    val saveContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(state.saveResult, state.pendingDeleteIntent) {
        if (state.pendingDeleteIntent != null) return@LaunchedEffect
        when (state.saveResult) {
            is SaveResult.Success -> {
                vm.consumeSaveResult()
                onSaved()
            }
            is SaveResult.SuccessAsCopy -> {
                android.widget.Toast.makeText(
                    saveContext,
                    saveContext.getString(
                        (state.saveResult as? SaveResult.SuccessAsCopy)?.messageRes
                            ?: R.string.editor_saved_as_copy_toast,
                    ),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                vm.consumeSaveResult()
                onSaved()
            }
            else -> { /* Failed / null — let the existing Failed-message UI render */ }
        }
    }

    // External-source save feedback. The Save / SuccessAsCopy branches above handle the
    // device-owned URI case; External is a separate flag the VM latches on its forced
    // copy-to-MediaStore save so the toast fires reliably even though the SaveResult
    // path is Success (we DID succeed at writing — just always as a fresh copy).
    androidx.compose.runtime.LaunchedEffect(state.savedAsCopy) {
        if (state.savedAsCopy) {
            android.widget.Toast.makeText(
                saveContext,
                saveContext.getString(R.string.editor_saved_as_copy),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }


    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()

    // Unsaved-changes guard: canUndo == true means the user has applied at least one
    // edit that's not yet saved. Back press / Close goes through a confirmation dialog
    // so an accidental swipe doesn't lose the work.
    var showDiscardDialog by remember { mutableStateOf(false) }
    val confirmedOnBack: () -> Unit = remember(canUndo, onBack) {
        { if (canUndo) showDiscardDialog = true else onBack() }
    }
    androidx.activity.compose.BackHandler(enabled = canUndo) {
        showDiscardDialog = true
    }

    Column(Modifier.fillMaxSize().background(Bg0).statusBarsPadding()) {
        TopBar(
            isSaving = state.isSaving,
            canUndo = canUndo,
            canRedo = canRedo,
            onBack = confirmedOnBack,
            // Reset wipes the committed adjustments, so the crop overlay's own draft state has to
            // go with them or the frame and the lit chip would outlive the crop they describe.
            onReset = {
                vm.resetAll()
                pendingCropRect = null
                lockedAspect = CropAspect.Free
            },
            onUndo = { vm.undo() },
            onRedo = { vm.redo() },
            onSave = { showSaveSheet = true },
        )

        // ── Preview area ─────────────────────────────────────────────────────
        // While Crop is the active tool we ignore previewBitmap and render the uncropped
        // (but rotation- and colour-adjusted) bitmap instead, with a draggable crop overlay
        // on top. Releasing a drag commits the rect live via vm.applyCrop() — no Apply step —
        // so switching tabs resumes the regular previewBitmap path already showing the crop.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .then(
                    // Press-and-hold to peek the original, but only in the tools whose preview is a
                    // static image; Crop and Redact own the pointer for their own drag gestures.
                    if (state.originalBitmap != null &&
                        (activeTool == Tool.Adjust || activeTool == Tool.Filter || activeTool == Tool.Rotate)
                    ) {
                        Modifier.pointerInput(activeTool) {
                            detectTapGestures(onPress = {
                                comparing = true
                                tryAwaitRelease()
                                comparing = false
                            })
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(color = Accent)
                state.errorMessage != null -> {
                    // Surface load / decode failures in the unified [ErrorPopup] so
                    // the message is copyable and explicitly dismissable. The VM
                    // doesn't expose a clearError hook today; the dialog's scrim
                    // still consumes outside-taps so the user can dismiss it (state
                    // remains until they navigate back via the top-bar Back button).
                    // Message already comes pre-sanitized for save errors and as
                    // localized error strings for load errors — safe to pass as-is.
                    ErrorPopup(
                        title = stringResource(R.string.editor_error_title),
                        message = state.errorMessage!!,
                        onDismiss = {},
                        onCopy = {},
                    )
                }
                activeTool == Tool.Crop && state.originalBitmap != null -> CropPreview(
                    // Use adjustedBitmapNoCrop so colour edits AND any rotation / flip the
                    // user applied stay visible while they pick a crop rectangle — the Crop
                    // tab then shows the same picture the other tabs do, and the rect lives in
                    // that rotated display space (which the pipeline crops in, post-rotation).
                    // Fall back to originalBitmap for the brief window between load and the
                    // first adjust-render — identical dimensions at 0° rotation. previewBitmap
                    // is unsuitable because it has crop already applied; that would put the
                    // rect on the wrong canvas size and produce out-of-bounds reads → crash.
                    bitmap = state.adjustedBitmapNoCrop ?: state.originalBitmap!!,
                    cropRect = pendingCropRect,
                    lockedRatio = cropDisplayBitmap?.let { lockedAspect.lockRatio(it.width, it.height) },
                    // During the drag only the lightweight overlay rect updates (no re-render).
                    onCropRectChanged = { pendingCropRect = it },
                    // On release the rect commits to crop state, so other tabs and save reflect
                    // it without a separate Apply press. Committing here instead of per-tick
                    // keeps it to one render + one undo entry per gesture. A full-image rect
                    // commits null so the pipeline skips a pointless full-size createBitmap().
                    onCropRectCommit = {
                        val disp = cropDisplayBitmap
                        val full = disp != null && it.left == 0 && it.top == 0 &&
                            it.right == disp.width && it.bottom == disp.height
                        vm.applyCrop(if (full) null else it)
                    },
                )
                activeTool == Tool.Draw && state.previewBitmap != null -> DrawOverlay(
                    bitmap = state.previewBitmap!!,
                    color = drawColor,
                    widthDp = drawWidth,
                    committedStrokeCount = state.adjustments.drawStrokes.size,
                    drawStrokes = state.adjustments.drawStrokes,
                    textItems = state.adjustments.textItems,
                    onStrokeFinished = { stroke -> vm.addDrawStroke(stroke) },
                )
                activeTool == Tool.Text && state.previewBitmap != null -> TextOverlay(
                    bitmap = state.previewBitmap!!,
                    items = state.adjustments.textItems,
                    drawStrokes = state.adjustments.drawStrokes,
                    selectedId = state.selectedTextId,
                    editingId = editingTextId,
                    onSelect = { vm.selectTextItem(it) },
                    onNudge = { id, dcx, dcy, zoom, rot -> vm.nudgeTextItemLive(id, dcx, dcy, zoom, rot) },
                    onMoveFinished = { vm.commitTextMove() },
                    onRequestEdit = { id -> vm.beginTextEdit(id); editingTextId = id },
                    onTextChange = { id, text -> vm.setTextItemTextLive(id, text) },
                    onEditDone = {
                        val id = editingTextId
                        editingTextId = null
                        if (id != null) vm.commitTextEdit(id)
                    },
                )
                state.previewBitmap != null -> ImageWithRedactOverlay(
                    bitmap = state.previewBitmap!!,
                    redactActive = activeTool == Tool.Redact,
                    brushDp = state.redactBrushDp,
                    committedStrokeCount = state.adjustments.redactStrokes.size,
                    drawStrokes = state.adjustments.drawStrokes,
                    textItems = state.adjustments.textItems,
                    onStrokeFinished = { stroke -> vm.addRedactStroke(stroke) },
                )
            }
            // While the preview is held, the untouched original is drawn over the top with a label,
            // so the user can compare against everything applied so far.
            if (comparing && state.originalBitmap != null) {
                Image(
                    bitmap = state.originalBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    stringResource(R.string.editor_compare_original),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), pillShape)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }

        // ── Bottom area ──────────────────────────────────────────────────────
        // Three independent pill containers stacked vertically over the same Bg0 the
        // preview sits on, rather than a single rounded panel. Each pill stands on its
        // own surrounded by empty space, identical recipe to the photos page filter
        // row (PillBg / PillBorder / pillShape).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Slider pill — Adjust tab + an adjustment selected. Hidden otherwise.
            // Keep the last picked adjustment so the pill can animate OUT with the right content after
            // activeAdjustment goes null (tapping the chip again or leaving the tool).
            var lastAdj by remember { mutableStateOf(Adjustment.Brightness) }
            activeAdjustment?.let { lastAdj = it }
            AnimatedVisibility(
                visible = activeTool == Tool.Adjust && activeAdjustment != null,
                enter = fadeIn(tween(180)) + expandVertically(tween(200)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(160)),
            ) {
                val adj = lastAdj
                val value = when (adj) {
                    Adjustment.Brightness  -> state.adjustments.brightness
                    Adjustment.Exposure    -> state.adjustments.exposure
                    Adjustment.Contrast    -> state.adjustments.contrast
                    Adjustment.Highlights  -> state.adjustments.highlights
                    Adjustment.Shadows     -> state.adjustments.shadows
                    Adjustment.Saturation  -> state.adjustments.saturation
                    Adjustment.Vibrance    -> state.adjustments.vibrance
                    Adjustment.Tone        -> state.adjustments.tone
                    Adjustment.Temperature -> state.adjustments.temperature
                    Adjustment.Sharpen     -> state.adjustments.sharpen
                    Adjustment.Grain       -> state.adjustments.grain
                    Adjustment.Vignette    -> state.adjustments.vignette
                    Adjustment.Fade        -> state.adjustments.fade
                }
                val label = stringResource(
                    when (adj) {
                        Adjustment.Brightness  -> R.string.editor_adj_brightness
                        Adjustment.Exposure    -> R.string.editor_adj_exposure
                        Adjustment.Contrast    -> R.string.editor_adj_contrast
                        Adjustment.Highlights  -> R.string.editor_adj_highlights
                        Adjustment.Shadows     -> R.string.editor_adj_shadows
                        Adjustment.Saturation  -> R.string.editor_adj_saturation
                        Adjustment.Vibrance    -> R.string.editor_adj_vibrance
                        Adjustment.Tone        -> R.string.editor_adj_tone
                        Adjustment.Temperature -> R.string.editor_adj_temperature
                        Adjustment.Sharpen     -> R.string.editor_adj_sharpen
                        Adjustment.Grain       -> R.string.editor_adj_grain
                        Adjustment.Vignette    -> R.string.editor_adj_vignette
                        Adjustment.Fade        -> R.string.editor_adj_fade
                    }
                )
                val onChange: (Int) -> Unit = when (adj) {
                    Adjustment.Brightness  -> { v -> vm.updateBrightness(v) }
                    Adjustment.Exposure    -> { v -> vm.updateExposure(v) }
                    Adjustment.Contrast    -> { v -> vm.updateContrast(v) }
                    Adjustment.Highlights  -> { v -> vm.updateHighlights(v) }
                    Adjustment.Shadows     -> { v -> vm.updateShadows(v) }
                    Adjustment.Saturation  -> { v -> vm.updateSaturation(v) }
                    Adjustment.Vibrance    -> { v -> vm.updateVibrance(v) }
                    Adjustment.Tone        -> { v -> vm.updateTone(v) }
                    Adjustment.Temperature -> { v -> vm.updateTemperature(v) }
                    Adjustment.Sharpen     -> { v -> vm.updateSharpen(v) }
                    Adjustment.Grain       -> { v -> vm.updateGrain(v) }
                    Adjustment.Vignette    -> { v -> vm.updateVignette(v) }
                    Adjustment.Fade        -> { v -> vm.updateFade(v) }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp),
                ) {
                    SliderRow(
                        label = label,
                        value = value,
                        onChange = onChange,
                        onChangeFinished = { vm.finalizeAdjustments() },
                    )
                }
            }

            // Panel — varies per tool. Lives in horizontal-padding so the inner pills
            // don't kiss the screen edges, but does not have its own outer pill:
            // adjustment chips are individual loose capsules, and the filter / crop /
            // redact / rotate panels render their own pill recipes inside.
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).wrapContentHeight(),
            ) {
                AnimatedContent(
                    targetState = activeTool,
                    transitionSpec = {
                        (fadeIn(tween(190)) + slideInVertically(tween(230)) { it / 5 }) togetherWith
                            (fadeOut(tween(150)) + slideOutVertically(tween(190)) { it / 5 })
                    },
                    label = "editor_panel",
                ) { tool ->
                    when (tool) {
                        Tool.Adjust -> AdjustPanel(
                        active = activeAdjustment,
                        adjustments = state.adjustments,
                        onSelect = { next ->
                            // Tap-to-toggle: tapping the active chip hides the slider again.
                            activeAdjustment = if (activeAdjustment == next) null else next
                        },
                        onReset = { adj ->
                            vm.resetAdjustment { a ->
                                when (adj) {
                                    Adjustment.Brightness  -> a.copy(brightness = 0)
                                    Adjustment.Exposure    -> a.copy(exposure = 0)
                                    Adjustment.Contrast    -> a.copy(contrast = 0)
                                    Adjustment.Highlights  -> a.copy(highlights = 0)
                                    Adjustment.Shadows     -> a.copy(shadows = 0)
                                    Adjustment.Saturation  -> a.copy(saturation = 0)
                                    Adjustment.Vibrance    -> a.copy(vibrance = 0)
                                    Adjustment.Tone        -> a.copy(tone = 0)
                                    Adjustment.Temperature -> a.copy(temperature = 0)
                                    Adjustment.Sharpen     -> a.copy(sharpen = 0)
                                    Adjustment.Grain       -> a.copy(grain = 0)
                                    Adjustment.Vignette    -> a.copy(vignette = 0)
                                    Adjustment.Fade        -> a.copy(fade = 0)
                                }
                            }
                        },
                        onAutoFix = { vm.autoFix() },
                    )
                    Tool.Filter -> FilterPanel(state, vm)
                    Tool.Color -> ColorPanel(
                        state = state,
                        vm = vm,
                        mode = colorMode,
                        onMode = { colorMode = it },
                        curveChannel = curveChannel,
                        onCurveChannel = { curveChannel = it },
                        hslBandIndex = hslBandIndex,
                        onHslBand = { hslBandIndex = it },
                    )
                    Tool.Crop   -> CropPanel(
                        state = state,
                        vm = vm,
                        lockedAspect = lockedAspect,
                        onLockedAspectChange = { lockedAspect = it },
                        onPendingCropRectChange = { pendingCropRect = it },
                    )
                    Tool.Redact -> RedactPanel(state, vm)
                    Tool.Draw -> DrawPanel(
                        state = state,
                        color = drawColor,
                        onColor = { drawColor = it },
                        width = drawWidth,
                        onWidth = { drawWidth = it },
                        vm = vm,
                    )
                    Tool.Text -> TextPanel(
                        state = state,
                        onAdd = { editingTextId = vm.addEmptyTextItem() },
                        onEdit = { item -> vm.beginTextEdit(item.id); editingTextId = item.id },
                        vm = vm,
                    )
                    Tool.Rotate -> RotatePanel(state = state, vm = vm)
                    }
                }
            }

            val saveResult = state.saveResult
            if (saveResult is SaveResult.Failed) {
                Text(
                    text = saveResult.message,
                    color = Color(0xFFFF3B30),
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp),
                )
            }

            // Bottom tab bar — a single pill containing the 5 tab tap targets. Matches
            // GalleryScreen.BottomDock: PillBgOpaque + 0.5.dp PillBorder + pillShape
            // + 4.dp inner padding. Each tab inside is a 44.dp circle that fills with
            // Accent.copy(alpha = 0.22f) when selected.
            // Horizontally scrollable so the tool set can grow past the screen width without the
            // circles squashing together; padding keeps the first/last tab clear of the pill ends.
            Row(
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .background(PillBgOpaque, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tool.entries.forEach { tool ->
                    ToolTab(tool = tool, selected = tool == activeTool, onClick = { activeTool = tool })
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
    if (showSaveSheet && state.source != null) {
        ModalBottomSheet(
            onDismissRequest = { showSaveSheet = false },
            sheetState = saveSheetState,
            containerColor = Bg2,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            SaveSheet(
                source = state.source!!,
                hasCloudCounterpart = hasCloudCounterpart,
                onPicked = { mode, fmt, q, dim ->
                    showSaveSheet = false
                    vm.save(mode, quality = q, format = fmt, maxDim = dim)
                },
                onCancel = { showSaveSheet = false },
            )
        }
    }
}

@Composable
private fun SaveSheet(
    source: EditorSource,
    hasCloudCounterpart: Boolean,
    onPicked: (SaveMode, Bitmap.CompressFormat, Int, Int?) -> Unit,
    onCancel: () -> Unit,
) {
    val isCloud = source is EditorSource.Cloud
    // Synced photo = device-source + has cloud counterpart. The edit fans out to both
    // sides on save, so the subtitle has to mention "both" instead of just the
    // device-side phrasing.
    val isSynced = !isCloud && hasCloudCounterpart
    // Export options for a COPY. The format picker is offered only for a device-only, non-synced photo:
    // a cloud or synced copy stays JPEG so reconcile pairs the two sides by an identical name.
    val formatPickable = !isCloud && !isSynced
    // Default a copy to the source's own format (a PNG stays a lossless PNG, a WebP stays WebP) instead of
    // silently re-encoding it as JPEG; the user can still switch in the picker. HEIC/RAW/GIF fall to JPEG.
    val defaultFormat = when (source) {
        is EditorSource.Local -> copyDefaultFormat(source.mimeType, source.displayName)
        is EditorSource.External -> copyDefaultFormat(source.mimeType, source.displayName)
        is EditorSource.Cloud -> Bitmap.CompressFormat.JPEG
    }
    var format by remember { mutableStateOf(defaultFormat) }
    var quality by remember { mutableIntStateOf(92) }
    var maxDim by remember { mutableStateOf<Int?>(null) }
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
            stringResource(
                when {
                    isCloud  -> R.string.editor_save_sheet_cloud
                    isSynced -> R.string.editor_save_sheet_synced
                    else     -> R.string.editor_save_sheet_device
                }
            ),
            color = FgMute, fontSize = 13.sp,
        )
        Spacer(Modifier.height(18.dp))

        // ── Export options (apply to a saved Copy) ──
        if (formatPickable) {
            ExportLabel(stringResource(R.string.editor_export_format))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportChip("JPEG", format == Bitmap.CompressFormat.JPEG) { format = Bitmap.CompressFormat.JPEG }
                ExportChip("PNG", format == Bitmap.CompressFormat.PNG) { format = Bitmap.CompressFormat.PNG }
                ExportChip("WebP", format == webpFormat) { format = webpFormat }
            }
            Spacer(Modifier.height(14.dp))
        }
        ExportLabel(stringResource(R.string.editor_export_size))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportChip(stringResource(R.string.editor_export_size_full), maxDim == null) { maxDim = null }
            ExportChip("2048", maxDim == 2048) { maxDim = 2048 }
            ExportChip("1024", maxDim == 1024) { maxDim = 1024 }
        }
        // JPEG and WebP are lossy, so they take a quality; PNG is lossless and hides the slider.
        if (format != Bitmap.CompressFormat.PNG) {
            Spacer(Modifier.height(14.dp))
            ExportLabel(stringResource(R.string.editor_export_quality, quality))
            Slider(
                value = quality.toFloat(),
                onValueChange = { quality = it.roundToInt().coerceIn(50, 100) },
                valueRange = 50f..100f,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            )
        }
        Spacer(Modifier.height(14.dp))

        // A new Copy is the only save option for a cloud-backed photo: the original always
        // survives and a fresh file is uploaded. Overwrite of a foreign device URI (camera roll,
        // screenshots) may need a one-time MediaStore write consent, and an un-overwritable format
        // (RAW/HEIC/GIF) is coerced to a JPEG copy with a toast.
        SaveOptionRow(
            icon = if (isCloud) Icons.Default.CloudUpload else Icons.Default.ContentCopy,
            title = stringResource(if (isCloud) R.string.editor_save_as_new_copy else R.string.video_editor_save_copy),
            subtitle = stringResource(
                when {
                    isCloud  -> R.string.editor_save_copy_subtitle_cloud
                    isSynced -> R.string.editor_save_copy_subtitle_synced
                    else     -> R.string.editor_save_copy_subtitle_device
                }
            ),
            onClick = { onPicked(SaveMode.Copy, format, quality, maxDim) },
        )
        // Overwrite is offered ONLY for a device-only photo (a genuine in-place file write). A
        // cloud-backed photo (cloud-only or synced) is never overwritten in place: the Photos
        // backend refuses a second revision on a photo link, so the only cloud save is a new copy.
        if (!isCloud && !isSynced) {
            Spacer(Modifier.height(12.dp))
            SaveOptionRow(
                icon = Icons.Default.Save,
                title = stringResource(R.string.editor_save_overwrite),
                subtitle = stringResource(R.string.editor_save_overwrite_subtitle_device),
                // Overwrite keeps the source format and full resolution; only the JPEG quality applies.
                onClick = { onPicked(SaveMode.Overwrite, Bitmap.CompressFormat.JPEG, quality, null) },
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PanelChip)
                .clickable(onClick = onCancel),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.cancel), color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** WebP compress format for this device; the lossy enum value arrived in API 30. */
@Suppress("DEPRECATION")
private val webpFormat: Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
    else Bitmap.CompressFormat.WEBP

@Composable
private fun ExportLabel(text: String) {
    Text(text, color = FgDim, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun ExportChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(pillShape)
            .background(if (selected) Accent.copy(alpha = 0.18f) else PillBg, pillShape)
            .then(if (!selected) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (selected) Accent else FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ─── Top bar ─────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    isSaving: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.onboarding_back),
                tint = FgPrimary, modifier = Modifier.size(20.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Undo/Redo are placed left of Reset/Save so the user's hand doesn't cross the
            // Save pill while iterating on edits. Tint follows the enabled state, no
            // background change — keeps the bar visually quiet when both are disabled.
            IconBubble(onClick = onUndo, enabled = canUndo) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    stringResource(R.string.cd_editor_undo),
                    tint = if (canUndo) FgPrimary else FgDim,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconBubble(onClick = onRedo, enabled = canRedo) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo,
                    stringResource(R.string.cd_editor_redo),
                    tint = if (canRedo) FgPrimary else FgDim,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconBubble(onClick = onReset) {
                Icon(Icons.Default.Restore, stringResource(R.string.cd_editor_reset),
                    tint = FgDim, modifier = Modifier.size(18.dp))
            }
            SavePill(isSaving = isSaving, onClick = onSave)
        }
    }
}

@Composable
private fun SavePill(isSaving: Boolean, onClick: () -> Unit) {
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
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        } else {
            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text(stringResource(R.string.action_save), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun IconBubble(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    // Same recipe as the photos page header icon buttons (search, grouping, etc.):
    // PillBg fill + 0.5.dp PillBorder + CircleShape. Keeps the top bar visually
    // contiguous with the bottom tab dock and gallery filter rail.
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(PillBg, CircleShape)
            .border(0.5.dp, PillBorder, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 44.dp circle tap target inside the tab bar pill. Icon-only so the bar stays
 *  a single thin pill the same height as the photos page BottomDock. Selected
 *  state mirrors that screen: Accent.copy(alpha = 0.22f) fill + accent-tinted
 *  icon. Unselected = transparent + dim icon. */
@Composable
private fun ToolTab(tool: Tool, selected: Boolean, onClick: () -> Unit) {
    // The selection fills in and the icon springs up a touch, so switching tools reads as a deliberate
    // move rather than an instant swap.
    val bgAlpha by animateFloatAsState(if (selected) 0.22f else 0f, tween(200), label = "tab_bg")
    val tint by animateColorAsState(if (selected) Accent else FgDim, tween(200), label = "tab_tint")
    val scale by animateFloatAsState(
        if (selected) 1f else 0.88f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tab_scale",
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
            contentDescription = stringResource(tool.labelRes),
            tint = tint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

// ─── Image preview + redact overlay ──────────────────────────────────────────

@Composable
private fun ImageWithRedactOverlay(
    bitmap: Bitmap,
    redactActive: Boolean,
    brushDp: Float,
    committedStrokeCount: Int,
    drawStrokes: List<DrawStroke>,
    textItems: List<TextItem>,
    onStrokeFinished: (RedactionStroke) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var currentStrokeCanvas by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // Keep the in-progress stroke drawn until its committed copy has baked into the bitmap (the stroke
    // count grows), so the mark never blinks out in the gap while the re-render is still in flight. The
    // drag guard stops a fast next stroke being wiped by the previous one's bake landing mid-draw.
    var isDragging by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(committedStrokeCount) {
        if (!isDragging) currentStrokeCanvas = emptyList()
    }
    val density = LocalDensity.current
    // Screen-dp brush size (from the Redact panel), scaled to bitmap coordinates for the stroke.
    val brushSizePx = with(density) { brushDp.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        val fit = remember(bitmap.width, bitmap.height, containerSize) {
            fitImageInBox(bitmap.width.toFloat(), bitmap.height.toFloat(),
                containerSize.width.toFloat().coerceAtLeast(1f),
                containerSize.height.toFloat().coerceAtLeast(1f))
        }
        // The pen + text overlays live here as a Compose layer (they are stripped from the baked preview),
        // so adjusting a slider or applying a filter never re-bakes them.
        if (drawStrokes.isNotEmpty() || textItems.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                paintEditorOverlays(drawStrokes, textItems, bitmap, fit)
            }
        }

        if (redactActive) {
            val accentColor = Accent
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(redactActive, bitmap) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                currentStrokeCanvas = listOf(offset)
                            },
                            onDragEnd = {
                                isDragging = false
                                if (currentStrokeCanvas.isNotEmpty()) {
                                    val bmpPoints = currentStrokeCanvas.map { o ->
                                        val bx = fit.toImageX(o.x).coerceIn(0f, bitmap.width.toFloat())
                                        val by = fit.toImageY(o.y).coerceIn(0f, bitmap.height.toFloat())
                                        PointF(bx, by)
                                    }
                                    val bmpBrush = (brushSizePx / fit.scale).coerceAtLeast(4f)
                                    onStrokeFinished(
                                        RedactionStroke(
                                            points = bmpPoints,
                                            brushSize = bmpBrush,
                                            mode = currentRedactMode,
                                        ),
                                    )
                                }
                                // Not cleared here: the LaunchedEffect drops it once the stroke bakes into
                                // the bitmap, so the mark never blinks out between release and the re-render.
                            },
                            onDragCancel = {
                                isDragging = false
                                currentStrokeCanvas = emptyList()
                            },
                            onDrag = { change, _ ->
                                currentStrokeCanvas = currentStrokeCanvas + change.position
                                change.consume()
                            },
                        )
                    },
            ) {
                if (currentStrokeCanvas.isNotEmpty()) {
                    val path = Path().apply {
                        moveTo(currentStrokeCanvas.first().x, currentStrokeCanvas.first().y)
                        currentStrokeCanvas.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        path = path,
                        color = if (currentRedactMode == RedactMode.Black) Color.Black.copy(alpha = 0.9f)
                                else accentColor.copy(alpha = 0.55f),
                        style = Stroke(width = brushSizePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}

private var currentRedactMode: RedactMode = RedactMode.Black

/** Pen colour swatches (ARGB). */
private val DRAW_COLORS = listOf(
    0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(),
    0xFF00C7BE.toInt(), 0xFF007AFF.toInt(), 0xFF5856D6.toInt(), 0xFFAF52DE.toInt(),
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
)

/** Paints the committed pen strokes and text overlays on top of the fitted image, mirroring the VM's
 *  applyDrawStrokes + applyTextItems bake exactly. They are stripped from the baked preview and live as
 *  this Compose layer, so a slider or filter never re-bakes them. [excludeTextId] skips an inline edit. */
private fun DrawScope.paintEditorOverlays(
    drawStrokes: List<DrawStroke>,
    textItems: List<TextItem>,
    bitmap: Bitmap,
    fit: ImageFit,
    excludeTextId: Int? = null,
) {
    for (stroke in drawStrokes) {
        if (stroke.points.isEmpty()) continue
        val strokeColor = Color(stroke.color)
        val wPx = (stroke.widthFraction * bitmap.width * fit.scale).coerceAtLeast(1f)
        if (stroke.points.size == 1) {
            val p = stroke.points.first()
            drawCircle(
                strokeColor, radius = wPx / 2f,
                center = Offset(fit.toScreenX(p.x * bitmap.width), fit.toScreenY(p.y * bitmap.height)),
            )
        } else {
            val path = Path().apply {
                val p0 = stroke.points.first()
                moveTo(fit.toScreenX(p0.x * bitmap.width), fit.toScreenY(p0.y * bitmap.height))
                stroke.points.drop(1).forEach {
                    lineTo(fit.toScreenX(it.x * bitmap.width), fit.toScreenY(it.y * bitmap.height))
                }
            }
            drawPath(path, strokeColor, style = Stroke(width = wPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    if (textItems.isNotEmpty()) {
        drawIntoCanvas { canvas ->
            for (item in textItems) {
                if (item.id == excludeTextId || item.text.isBlank()) continue
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = item.color
                    textSize = (item.sizeFraction * bitmap.height * fit.scale).coerceAtLeast(6f)
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, android.graphics.Color.argb(140, 0, 0, 0))
                }
                val lines = item.text.split("\n")
                val lineH = paint.textSize * 1.2f
                val totalH = lineH * lines.size
                val cxScreen = fit.toScreenX(item.cx * bitmap.width)
                val cyScreen = fit.toScreenY(item.cy * bitmap.height)
                canvas.nativeCanvas.save()
                val angle = snapTextAngle(item.rotation)
                if (angle != 0f) canvas.nativeCanvas.rotate(angle, cxScreen, cyScreen)
                var baseline = cyScreen - totalH / 2f + paint.textSize
                for (line in lines) {
                    canvas.nativeCanvas.drawText(line, cxScreen, baseline, paint)
                    baseline += lineH
                }
                canvas.nativeCanvas.restore()
            }
        }
    }
}

/** Freehand pen overlay: captures a stroke in NORMALISED image coordinates (0..1) so it bakes correctly
 *  at any preview or save resolution. Mirrors the redact overlay's flicker-free commit (the live stroke
 *  is kept until its baked copy lands, guarded by the drag flag). */
@Composable
private fun DrawOverlay(
    bitmap: Bitmap,
    color: Int,
    widthDp: Float,
    committedStrokeCount: Int,
    drawStrokes: List<DrawStroke>,
    textItems: List<TextItem>,
    onStrokeFinished: (DrawStroke) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var isDragging by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(committedStrokeCount) {
        if (!isDragging) current = emptyList()
    }
    val density = LocalDensity.current
    val widthPx = with(density) { widthDp.dp.toPx() }
    Box(
        modifier = Modifier.fillMaxSize().onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        val fit = remember(bitmap.width, bitmap.height, containerSize) {
            fitImageInBox(bitmap.width.toFloat(), bitmap.height.toFloat(),
                containerSize.width.toFloat().coerceAtLeast(1f), containerSize.height.toFloat().coerceAtLeast(1f))
        }
        val strokeColor = Color(color)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { o -> isDragging = true; current = listOf(o) },
                        onDragEnd = {
                            isDragging = false
                            if (current.isNotEmpty()) {
                                val pts = current.map { o ->
                                    val bx = (fit.toImageX(o.x) / bitmap.width).coerceIn(0f, 1f)
                                    val by = (fit.toImageY(o.y) / bitmap.height).coerceIn(0f, 1f)
                                    PointF(bx, by)
                                }
                                val wf = (widthPx / fit.scale / bitmap.width).coerceAtLeast(0.001f)
                                onStrokeFinished(DrawStroke(points = pts, color = color, widthFraction = wf))
                            }
                        },
                        onDragCancel = { isDragging = false; current = emptyList() },
                        onDrag = { change, _ -> current = current + change.position; change.consume() },
                    )
                },
        ) {
            // Committed pen strokes + text as the live layer (stripped from the baked preview).
            paintEditorOverlays(drawStrokes, textItems, bitmap, fit)
            if (current.isNotEmpty()) {
                val path = Path().apply {
                    moveTo(current.first().x, current.first().y)
                    current.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path = path,
                    color = strokeColor,
                    style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/** Text overlay interaction (the text is baked into [bitmap]): drag the selected item to move it, tap to
 *  select the nearest item or deselect, with a box around the selection. Positions are the image's
 *  normalised space, so a move on the small preview lands identically on the full-res save. */
@Composable
private fun TextOverlay(
    bitmap: Bitmap,
    items: List<TextItem>,
    drawStrokes: List<DrawStroke>,
    selectedId: Int?,
    editingId: Int?,
    onSelect: (Int?) -> Unit,
    onNudge: (Int, Float, Float, Float, Float) -> Unit,
    onMoveFinished: () -> Unit,
    onRequestEdit: (Int) -> Unit,
    onTextChange: (Int, String) -> Unit,
    onEditDone: () -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier.fillMaxSize().onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        val fit = remember(bitmap.width, bitmap.height, containerSize) {
            fitImageInBox(bitmap.width.toFloat(), bitmap.height.toFloat(),
                containerSize.width.toFloat().coerceAtLeast(1f), containerSize.height.toFloat().coerceAtLeast(1f))
        }
        val accent = Accent
        // Read the latest items/selection/fit inside the long-lived gesture without re-keying it. Keying
        // pointerInput on `items` restarted the gesture every drag tick (the list changes as the text
        // moves), which is why a drag only nudged once and then needed re-grabbing.
        val itemsState = androidx.compose.runtime.rememberUpdatedState(items)
        val selectedIdState = androidx.compose.runtime.rememberUpdatedState(selectedId)
        val fitState = androidx.compose.runtime.rememberUpdatedState(fit)
        // The preview bitmap is a NEW object on every re-render, so the gesture must read it through a
        // state holder and be keyed on Unit; keying pointerInput on `bitmap` restarted the gesture each
        // drag tick (the text re-bakes into a fresh bitmap), which is why a drag stuck after one nudge.
        val bitmapState = androidx.compose.runtime.rememberUpdatedState(bitmap)
        val editingIdState = androidx.compose.runtime.rememberUpdatedState(editingId)
        fun hitTest(x: Float, y: Float): Int? {
            val f = fitState.value
            val bm = bitmapState.value
            // Topmost first (later items draw on top), box-based so pressing a text grabs THAT one.
            for (item in itemsState.value.asReversed()) {
                val fontPx = (item.sizeFraction * bm.height * f.scale).coerceAtLeast(6f)
                val lines = item.text.ifBlank { " " }.split("\n")
                val boxH = fontPx * 1.2f * lines.size + fontPx * 0.5f
                val boxW = fontPx * lines.maxOf { it.length }.coerceAtLeast(1) * 0.62f + fontPx * 0.6f
                val cx = f.toScreenX(item.cx * bm.width)
                val cy = f.toScreenY(item.cy * bm.height)
                val hx = maxOf(boxW / 2f, 44f)
                val hy = maxOf(boxH / 2f, 44f)
                if (x in (cx - hx)..(cx + hx) && y in (cy - hy)..(cy + hy)) return item.id
            }
            return null
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Grab the text UNDER the finger (not a pre-selected one), so with several texts you
                    // just press the one you want: a drag moves it, a tap opens it to edit, and pressing
                    // empty space deselects. Disabled while a field is already open.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (editingIdState.value != null) return@awaitEachGesture
                        val hitId = hitTest(down.position.x, down.position.y)
                        if (hitId != null) {
                            onSelect(hitId)
                            var moved = false
                            do {
                                val event = awaitPointerEvent()
                                val f = fitState.value
                                val bm = bitmapState.value
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                val rot = event.calculateRotation()
                                if (pan != Offset.Zero || zoom != 1f || rot != 0f) {
                                    onNudge(hitId, pan.x / f.scale / bm.width, pan.y / f.scale / bm.height, zoom, rot)
                                    moved = true
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (moved) onMoveFinished() else onRequestEdit(hitId)
                        } else {
                            onSelect(null)
                        }
                    }
                },
        ) {
            // Committed pen strokes + text as the live layer (stripped from the baked preview); the text
            // being inline-edited is skipped, since the BasicTextField shows it instead.
            paintEditorOverlays(drawStrokes, items, bitmap, fit, excludeTextId = editingId)
            val sel = items.firstOrNull { it.id == selectedId && it.id != editingId } ?: return@Canvas
            val cx = fit.toScreenX(sel.cx * bitmap.width)
            val cy = fit.toScreenY(sel.cy * bitmap.height)
            val fontPx = sel.sizeFraction * bitmap.height * fit.scale
            val lines = sel.text.split("\n")
            val boxH = fontPx * 1.2f * lines.size + fontPx * 0.5f
            val boxW = fontPx * lines.maxOf { it.length }.coerceAtLeast(1) * 0.62f + fontPx * 0.5f
            val l = cx - boxW / 2f
            val t = cy - boxH / 2f
            val rr = cx + boxW / 2f
            val bb = cy + boxH / 2f
            // Same resizable-frame look as the crop overlay, turned with the text: a faint full outline
            // plus accent corner brackets, so it reads as "drag to move, pinch to resize, twist to rotate".
            val selAngle = snapTextAngle(sel.rotation)
            rotate(selAngle, pivot = Offset(cx, cy)) {
                drawRect(
                    color = accent.copy(alpha = 0.5f),
                    topLeft = Offset(l, t),
                    size = GSize(boxW, boxH),
                    style = Stroke(width = 1.5f),
                )
                val arm = (minOf(boxW, boxH) * 0.3f).coerceIn(12f, 44f)
                fun corner(px: Float, py: Float, dx: Float, dy: Float) {
                    drawLine(accent, Offset(px, py), Offset(px + dx * arm, py), strokeWidth = 4f, cap = StrokeCap.Round)
                    drawLine(accent, Offset(px, py), Offset(px, py + dy * arm), strokeWidth = 4f, cap = StrokeCap.Round)
                }
                corner(l, t, 1f, 1f)
                corner(rr, t, -1f, 1f)
                corner(l, bb, 1f, -1f)
                corner(rr, bb, -1f, -1f)
                // A full-width rule through the centre while the angle is magnetised onto a 45-degree detent,
                // so the snap to horizontal / diagonal / vertical is visible during a twist.
                val onDetent = kotlin.math.abs(selAngle - (selAngle / 45f).roundToInt() * 45f) < 0.01f
                if (onDetent) {
                    val len = size.width + size.height
                    drawLine(accent.copy(alpha = 0.6f), Offset(cx - len, cy), Offset(cx + len, cy), strokeWidth = 1f)
                }
            }
        }
        // Inline editor: a field at the selected text's position, styled to match, so tapping a text edits
        // it in place with no popup. A tap outside finishes; the VM drops it if left blank.
        val editItem = items.firstOrNull { it.id == editingId }
        if (editItem != null) {
            val focus = remember { FocusRequester() }
            androidx.compose.runtime.LaunchedEffect(editingId) { runCatching { focus.requestFocus() } }
            val sy = fit.toScreenY(editItem.cy * bitmap.height)
            val fontSp = with(LocalDensity.current) {
                (editItem.sizeFraction * bitmap.height * fit.scale).coerceIn(28f, 120f).toSp()
            }
            Box(Modifier.fillMaxSize().pointerInput(editingId) { detectTapGestures(onTap = { onEditDone() }) })
            BasicTextField(
                value = editItem.text,
                onValueChange = { onTextChange(editItem.id, it) },
                textStyle = TextStyle(
                    color = Color(editItem.color),
                    fontSize = fontSp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(accent),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(0, (sy - containerSize.height / 2f).roundToInt()) }
                    .fillMaxWidth(0.92f)
                    .focusRequester(focus),
            )
        }
    }
}

// ─── Tool panels ────────────────────────────────────────────────────────────

/** Adjust tab — loose individual capsule pills (LazyRow). One Auto-Fix pill that
 *  fires `vm.autoFix()` instantly and three adjustment selector pills. Picking a
 *  selector toggles the floating slider pill above; tapping the active one again
 *  hides it. Visual recipe is the same PillBg / PillBorder / pillShape used on
 *  the photos page filter row. */
@Composable
private fun AdjustPanel(
    active: Adjustment?,
    adjustments: EditorAdjustments,
    onSelect: (Adjustment) -> Unit,
    onReset: (Adjustment) -> Unit,
    onAutoFix: () -> Unit,
) {
    // (adjustment, label, icon, current value); a non-zero value lights the "modified" dot.
    val chips = listOf(
        AdjChip(Adjustment.Brightness, R.string.editor_adj_brightness, Icons.Default.BrightnessMedium, adjustments.brightness),
        AdjChip(Adjustment.Exposure, R.string.editor_adj_exposure, Icons.Default.AutoFixHigh, adjustments.exposure),
        AdjChip(Adjustment.Contrast, R.string.editor_adj_contrast, Icons.Default.Contrast, adjustments.contrast),
        AdjChip(Adjustment.Highlights, R.string.editor_adj_highlights, Icons.Default.Tune, adjustments.highlights),
        AdjChip(Adjustment.Shadows, R.string.editor_adj_shadows, Icons.Default.Block, adjustments.shadows),
        AdjChip(Adjustment.Saturation, R.string.editor_adj_saturation, Icons.Default.Palette, adjustments.saturation),
        AdjChip(Adjustment.Vibrance, R.string.editor_adj_vibrance, Icons.Default.WaterDrop, adjustments.vibrance),
        AdjChip(Adjustment.Tone, R.string.editor_adj_tone, Icons.Default.SwapHoriz, adjustments.tone),
        AdjChip(Adjustment.Temperature, R.string.editor_adj_temperature, Icons.Default.Brush, adjustments.temperature),
        AdjChip(Adjustment.Sharpen, R.string.editor_adj_sharpen, Icons.Default.Deblur, adjustments.sharpen),
        AdjChip(Adjustment.Grain, R.string.editor_adj_grain, Icons.Default.Grain, adjustments.grain),
        AdjChip(Adjustment.Vignette, R.string.editor_adj_vignette, Icons.Default.Vignette, adjustments.vignette),
        AdjChip(Adjustment.Fade, R.string.editor_adj_fade, Icons.Default.Gradient, adjustments.fade),
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "auto_fix") {
            AdjustCapsulePill(
                label = stringResource(R.string.editor_auto_fix),
                icon = Icons.Default.AutoFixHigh,
                selected = false,
                accentIcon = true,
                onClick = onAutoFix,
            )
        }
        items(chips, key = { it.adj.name }) { chip ->
            AdjustCapsulePill(
                label = stringResource(chip.labelRes),
                icon = chip.icon,
                selected = active == chip.adj,
                modified = chip.value != 0,
                onClick = { onSelect(chip.adj) },
                onReset = { onReset(chip.adj) },
            )
        }
    }
}

/** One Adjust chip's static metadata plus its current value (non-zero lights the "modified" dot). */
private data class AdjChip(
    val adj: Adjustment,
    @androidx.annotation.StringRes val labelRes: Int,
    val icon: ImageVector,
    val value: Int,
)

/** Individual capsule pill matching the photos page filter row recipe verbatim.
 *  Selected = Accent.copy(alpha = 0.18f) fill (no border, like the gallery's
 *  active filter pill). Unselected = PillBg + 0.5.dp PillBorder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AdjustCapsulePill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accentIcon: Boolean = false,
    modified: Boolean = false,
    onReset: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(38.dp)
            .background(if (selected) Accent.copy(alpha = 0.18f) else PillBg, pillShape)
            .then(if (!selected) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
            .then(
                // Long-press a chip to reset that one adjustment.
                if (onReset != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onReset)
                else Modifier.clickable(onClick = onClick),
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            null,
            tint = when {
                selected   -> Accent
                accentIcon -> Accent
                else       -> FgDim
            },
            modifier = Modifier.size(14.dp),
        )
        Text(
            label,
            color = if (selected) Accent else FgPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        // Non-zero adjustment marker, so applied edits are visible without opening each slider.
        if (modified) {
            Box(Modifier.size(6.dp).background(Accent, CircleShape))
        }
    }
}

@Composable
private fun FilterPanel(state: EditorUiState, vm: PhotoEditorViewModel) {
    val original = state.originalBitmap
    // The chip thumbnails can pull from a downsampled copy so a 12MP photo doesn't have to
    // re-decode at full resolution six times in a LazyRow. Cached on the bitmap reference so
    // a new photo invalidates it automatically.
    val thumb = remember(original) {
        original?.let {
            val maxEdge = 160f
            val scale = (maxEdge / maxOf(it.width, it.height)).coerceAtMost(1f)
            if (scale >= 1f) it
            else Bitmap.createScaledBitmap(
                it,
                (it.width * scale).toInt().coerceAtLeast(1),
                (it.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(FilterPreset.entries.toList()) { filter ->
            FilterThumb(
                filter = filter,
                source = thumb,
                selected = state.adjustments.filter == filter,
                onClick = { vm.selectFilter(filter) },
            )
        }
    }
}

@Composable
private fun RedactPanel(state: EditorUiState, vm: PhotoEditorViewModel) {
    var modeState by remember { mutableStateOf(currentRedactMode) }
    // One combined pill (same recipe as the bottom tab dock) so the sub-menu reads as a single control
    // rather than scattered chips. Undo lives in the top bar (it steps the whole history), so no Clear.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PillBgOpaque, pillShape)
            .border(0.5.dp, PillBorder, pillShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillSegment(
            label = stringResource(R.string.editor_redact_blackout),
            icon = Icons.Default.Block,
            selected = modeState == RedactMode.Black,
            onClick = { currentRedactMode = RedactMode.Black; modeState = RedactMode.Black },
            modifier = Modifier.weight(1f),
        )
        PillSegment(
            label = stringResource(R.string.editor_redact_pixelate),
            icon = Icons.Default.GridOn,
            selected = modeState == RedactMode.Pixelate,
            onClick = { currentRedactMode = RedactMode.Pixelate; modeState = RedactMode.Pixelate },
            modifier = Modifier.weight(1f),
        )
        BrushSizeButton(current = state.redactBrushDp, onPick = vm::setRedactBrush, modifier = Modifier.weight(1f))
    }
}

/** A single segment inside a combined sub-menu pill: an icon + label with a rounded accent fill when
 *  selected, matching the bottom tab dock. Never wraps to a second line. */
@Composable
private fun PillSegment(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .background(if (selected) Accent.copy(alpha = 0.22f) else Color.Transparent, pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Accent else FgDim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = if (selected) Accent else FgPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Brush-size chip that opens a vertical popup of size dots (largest at the top). */
@Composable
private fun BrushSizeButton(current: Float, onPick: (Float) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val sizes = listOf(8f, 20f, 36f, 56f, 80f)
    Box(modifier) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .fillMaxWidth()
                .clip(pillShape)
                .clickable { open = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size((current / 80f * 12f + 5f).dp).background(FgPrimary, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.editor_redact_brush),
                color = FgPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, softWrap = false,
            )
        }
        if (open) {
            // Anchor the popup directly above the button (using its measured height) instead of a fixed
            // offset, so it never floats out over the photo.
            val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
            val provider = remember(gapPx) {
                object : androidx.compose.ui.window.PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: androidx.compose.ui.unit.IntRect,
                        windowSize: IntSize,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
                        val y = anchorBounds.top - popupContentSize.height - gapPx
                        return IntOffset(x.coerceAtLeast(0), y.coerceAtLeast(0))
                    }
                }
            }
            androidx.compose.ui.window.Popup(
                popupPositionProvider = provider,
                onDismissRequest = { open = false },
            ) {
                Column(
                    modifier = Modifier
                        .background(Bg2, RoundedCornerShape(18.dp))
                        .border(0.5.dp, PillBorder, RoundedCornerShape(18.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    sizes.reversed().forEach { s ->
                        val sel = s == current
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(if (sel) Accent.copy(alpha = 0.18f) else Color.Transparent, CircleShape)
                                .clickable { onPick(s); open = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size((s / 80f * 16f + 5f).dp).background(if (sel) Accent else FgPrimary, CircleShape))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawPanel(
    state: EditorUiState,
    color: Int,
    onColor: (Int) -> Unit,
    width: Float,
    onWidth: (Float) -> Unit,
    vm: PhotoEditorViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DRAW_COLORS.forEach { c ->
                val selected = c == color
                Box(
                    Modifier
                        .size(28.dp)
                        .background(Color(c), CircleShape)
                        .border(if (selected) 2.dp else 0.5.dp, if (selected) Accent else PillBorder, CircleShape)
                        .clickable { onColor(c) },
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.editor_draw_width), color = FgMute, fontSize = 12.sp)
            Slider(
                value = width,
                onValueChange = onWidth,
                valueRange = 2f..40f,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = TrackBg),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TextPanel(
    state: EditorUiState,
    onAdd: () -> Unit,
    onEdit: (TextItem) -> Unit,
    vm: PhotoEditorViewModel,
) {
    val selected = state.adjustments.textItems.firstOrNull { it.id == state.selectedTextId }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selected == null) {
            ActionChip(
                label = stringResource(R.string.editor_text_add),
                icon = Icons.Default.TextFields,
                enabled = true,
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.editor_text_hint), color = FgMute, fontSize = 12.sp)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DRAW_COLORS.forEach { c ->
                    val sel = c == selected.color
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(Color(c), CircleShape)
                            .border(if (sel) 2.dp else 0.5.dp, if (sel) Accent else PillBorder, CircleShape)
                            .clickable { vm.setTextItemColor(selected.id, c) },
                    )
                }
            }
            Text(stringResource(R.string.editor_text_resize_hint), color = FgMute, fontSize = 12.sp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PillBgOpaque, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillSegment(
                    label = stringResource(R.string.editor_text_edit),
                    icon = Icons.Default.Edit,
                    selected = false,
                    onClick = { onEdit(selected) },
                    modifier = Modifier.weight(1f),
                )
                PillSegment(
                    label = stringResource(R.string.editor_text_delete),
                    icon = Icons.Default.Delete,
                    selected = false,
                    onClick = { vm.removeTextItem(selected.id) },
                    modifier = Modifier.weight(1f),
                )
                PillSegment(
                    label = stringResource(R.string.editor_text_add),
                    icon = Icons.Default.Add,
                    selected = false,
                    onClick = onAdd,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private enum class ColorMode { Curves, HSL }

/** The Colour tool: a Curves / HSL sub-toggle over a tone-curve graph or the per-band HSL sliders. */
@Composable
private fun ColorPanel(
    state: EditorUiState,
    vm: PhotoEditorViewModel,
    mode: ColorMode,
    onMode: (ColorMode) -> Unit,
    curveChannel: CurveChannel,
    onCurveChannel: (CurveChannel) -> Unit,
    hslBandIndex: Int,
    onHslBand: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(PillBgOpaque, pillShape).border(0.5.dp, PillBorder, pillShape).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PillSegment(stringResource(R.string.editor_color_curves), Icons.Default.Gradient, mode == ColorMode.Curves, { onMode(ColorMode.Curves) }, Modifier.weight(1f))
            PillSegment(stringResource(R.string.editor_color_hsl), Icons.Default.Palette, mode == ColorMode.HSL, { onMode(ColorMode.HSL) }, Modifier.weight(1f))
        }
        when (mode) {
            ColorMode.Curves -> CurvesSection(state, vm, curveChannel, onCurveChannel)
            ColorMode.HSL -> HslSection(state, vm, hslBandIndex, onHslBand)
        }
    }
}

/** Text-only segment for the curve channel picker, tinted by the channel it selects. */
@Composable
private fun ChannelSegment(label: String, tint: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.height(34.dp).background(if (selected) tint.copy(alpha = 0.22f) else Color.Transparent, pillShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) tint else FgDim, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CurvesSection(state: EditorUiState, vm: PhotoEditorViewModel, channel: CurveChannel, onChannel: (CurveChannel) -> Unit) {
    val points = when (channel) {
        CurveChannel.RGB -> state.adjustments.curveRgb
        CurveChannel.R -> state.adjustments.curveR
        CurveChannel.G -> state.adjustments.curveG
        CurveChannel.B -> state.adjustments.curveB
    }
    val lineColor = when (channel) {
        CurveChannel.RGB -> FgPrimary
        CurveChannel.R -> Color(0xFFFF453A)
        CurveChannel.G -> Color(0xFF32D74B)
        CurveChannel.B -> Color(0xFF0A84FF)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(PillBgOpaque, pillShape).border(0.5.dp, PillBorder, pillShape).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChannelSegment("RGB", FgPrimary, channel == CurveChannel.RGB, { onChannel(CurveChannel.RGB) }, Modifier.weight(1f))
            ChannelSegment("R", Color(0xFFFF453A), channel == CurveChannel.R, { onChannel(CurveChannel.R) }, Modifier.weight(1f))
            ChannelSegment("G", Color(0xFF32D74B), channel == CurveChannel.G, { onChannel(CurveChannel.G) }, Modifier.weight(1f))
            ChannelSegment("B", Color(0xFF0A84FF), channel == CurveChannel.B, { onChannel(CurveChannel.B) }, Modifier.weight(1f))
        }
        CurveGraph(points = points, lineColor = lineColor, onChange = { vm.setCurve(channel, it) }, onFinish = { vm.finalizeAdjustments() })
        ActionChip(
            label = stringResource(R.string.cd_editor_reset),
            icon = Icons.Default.Restore,
            enabled = points != IDENTITY_CURVE,
            onClick = { vm.resetCurve(channel) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A draggable tone-curve graph. Drag a control point to move it (ends move only up/down); a press on
 *  empty space adds a point there. The line is sampled through `curveValueAt`, matching the save LUT. */
@Composable
private fun CurveGraph(points: List<CurvePoint>, lineColor: Color, onChange: (List<CurvePoint>) -> Unit, onFinish: () -> Unit) {
    val gridColor = FgDim.copy(alpha = 0.25f)
    var dragIndex by remember { mutableIntStateOf(-1) }
    val ptsState = androidx.compose.runtime.rememberUpdatedState(points)
    // pointerInput(Unit) starts once and captures the FIRST onChange/onFinish forever. When the channel
    // switches, the parent passes a NEW onChange bound to the new channel, but the long-lived gesture
    // would keep calling the old one (still bound to RGB), so every channel edited the RGB curve. Reach
    // the latest lambdas through a state holder instead (same fix as SliderRow).
    val latestOnChange = androidx.compose.runtime.rememberUpdatedState(onChange)
    val latestOnFinish = androidx.compose.runtime.rememberUpdatedState(onFinish)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f)
            .clip(RoundedCornerShape(12.dp))
            .background(PillBg)
            .border(0.5.dp, PillBorder, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val pts = ptsState.value
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val nearest = pts.indices.minByOrNull { hypot(pts[it].x * w - o.x, (1f - pts[it].y) * h - o.y) }
                        val nd = nearest?.let { hypot(pts[it].x * w - o.x, (1f - pts[it].y) * h - o.y) } ?: Float.MAX_VALUE
                        dragIndex = if (nearest != null && nd < 64f) {
                            nearest
                        } else {
                            val nx = (o.x / w).coerceIn(0f, 1f)
                            val ny = (1f - o.y / h).coerceIn(0f, 1f)
                            val added = CurvePoint(nx, ny)
                            val newPts = (pts + added).sortedBy { it.x }
                            latestOnChange.value(newPts)
                            // Reference identity (sortedBy keeps the same objects), so a coincident value
                            // never resolves to the wrong point.
                            newPts.indexOfFirst { it === added }
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val idx = dragIndex
                        val pts = ptsState.value.toMutableList()
                        if (idx !in pts.indices) return@detectDragGestures
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val ny = (1f - change.position.y / h).coerceIn(0f, 1f)
                        val nx = when (idx) {
                            0 -> 0f
                            pts.size - 1 -> 1f
                            else -> (change.position.x / w).coerceIn(pts[idx - 1].x + 0.02f, pts[idx + 1].x - 0.02f)
                        }
                        pts[idx] = CurvePoint(nx, ny)
                        latestOnChange.value(pts)
                    },
                    onDragEnd = { dragIndex = -1; latestOnFinish.value() },
                    onDragCancel = { dragIndex = -1; latestOnFinish.value() },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        for (k in 1..2) {
            drawLine(gridColor, Offset(w * k / 3f, 0f), Offset(w * k / 3f, h), 1f)
            drawLine(gridColor, Offset(0f, h * k / 3f), Offset(w, h * k / 3f), 1f)
        }
        drawLine(gridColor.copy(alpha = 0.4f), Offset(0f, h), Offset(w, 0f), 1f)
        val sorted = points.sortedBy { it.x }
        val path = Path()
        val steps = 72
        for (s in 0..steps) {
            val x = s / steps.toFloat()
            val px = x * w
            val py = (1f - curveValueAt(sorted, x)) * h
            if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5f))
        for (p in sorted) {
            val c = Offset(p.x * w, (1f - p.y) * h)
            drawCircle(Color.White, radius = 6f, center = c)
            drawCircle(lineColor, radius = 4f, center = c)
        }
    }
}

/** Representative swatch for each of the eight HSL bands (red, orange, yellow, green, aqua, blue, purple, magenta). */
private val HSL_SWATCHES = listOf(
    0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF00C7BE, 0xFF007AFF, 0xFF8E5CE6, 0xFFFF2D92,
).map { it.toInt() }

@Composable
private fun HslSection(state: EditorUiState, vm: PhotoEditorViewModel, bandIndex: Int, onBand: (Int) -> Unit) {
    val bands = state.adjustments.hslBands
    val band = bands.getOrElse(bandIndex) { HslBand() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HSL_SWATCHES.forEachIndexed { i, c ->
                val selected = i == bandIndex
                val modified = bands.getOrNull(i)?.let { it.hue != 0 || it.sat != 0 || it.light != 0 } ?: false
                Box(
                    Modifier
                        .weight(1f)
                        .height(30.dp)
                        .background(Color(c), RoundedCornerShape(8.dp))
                        .border(if (selected) 2.dp else 0.5.dp, if (selected) FgPrimary else PillBorder, RoundedCornerShape(8.dp))
                        .clickable { onBand(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (modified) Box(Modifier.size(5.dp).background(Color.White, CircleShape))
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            SliderRow(stringResource(R.string.editor_hsl_hue), band.hue, { vm.setHslBand(bandIndex, band.copy(hue = it)) }, { vm.finalizeAdjustments() })
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            SliderRow(stringResource(R.string.editor_hsl_sat), band.sat, { vm.setHslBand(bandIndex, band.copy(sat = it)) }, { vm.finalizeAdjustments() })
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            SliderRow(stringResource(R.string.editor_hsl_light), band.light, { vm.setHslBand(bandIndex, band.copy(light = it)) }, { vm.finalizeAdjustments() })
        }
        ActionChip(
            label = stringResource(R.string.cd_editor_reset),
            icon = Icons.Default.Restore,
            enabled = bands.any { it.hue != 0 || it.sat != 0 || it.light != 0 },
            onClick = { vm.resetHsl() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RotatePanel(state: EditorUiState, vm: PhotoEditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 90-degree turns + flips + a reset for the fine geometry, scrolled so long labels keep their width.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionTile(stringResource(R.string.video_editor_rotate), Icons.AutoMirrored.Filled.RotateRight) { vm.rotate90Cw() }
            ActionTile(stringResource(R.string.editor_flip_h), Icons.Default.Flip) { vm.toggleFlipH() }
            ActionTile(stringResource(R.string.editor_flip_v), Icons.Default.Flip) { vm.toggleFlipV() }
            ActionTile(stringResource(R.string.editor_geo_reset), Icons.Default.Restore) { vm.resetGeometry() }
        }
        GeoSlider(stringResource(R.string.editor_straighten), state.adjustments.straighten, vm::updateStraighten) { vm.finalizeAdjustments() }
        GeoSlider(stringResource(R.string.editor_perspective_v), state.adjustments.perspectiveV, vm::updatePerspectiveV) { vm.finalizeAdjustments() }
        GeoSlider(stringResource(R.string.editor_perspective_h), state.adjustments.perspectiveH, vm::updatePerspectiveH) { vm.finalizeAdjustments() }
    }
}

/** A labelled bipolar slider for the fine geometry controls (straighten, keystone). */
@Composable
private fun GeoSlider(label: String, value: Int, onChange: (Int) -> Unit, onFinished: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = FgDim, fontSize = 12.sp, maxLines = 1, softWrap = false, modifier = Modifier.width(76.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            onValueChangeFinished = onFinished,
            valueRange = -100f..100f,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = TrackBg),
            modifier = Modifier.weight(1f),
        )
    }
}

// ─── Reusable components ────────────────────────────────────────────────────

/** Clean custom slider: 4dp track, 18dp thumb, no Material stop indicator. */
@Composable
private fun SliderRow(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    onChangeFinished: () -> Unit = {},
) {
    val density = LocalDensity.current
    val trackHeightPx = with(density) { 2.dp.toPx() }
    val thumbRadiusPx = with(density) { 7.dp.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val normalized = (value + 100) / 200f // 0..1
    val accentColor = Accent
    val fgDimColor = FgDim
    val trackColor = TrackBg
    // pointerInput(Unit) captures the FIRST `onChange` lambda forever — the gesture
    // pipeline starts once and never reruns. When the parent swaps `onChange` because
    // the active adjustment changed (e.g. Brightness → Exposure), the captured lambda
    // still points at vm.updateBrightness — so the slider visually shows Exposure's
    // value (it reads `value` on recomposition) but every drag updates Brightness.
    // rememberUpdatedState lets the long-lived gesture loop reach the latest lambda
    // without re-keying the pointerInput on every recomposition.
    val latestOnChange = androidx.compose.runtime.rememberUpdatedState(onChange)
    val latestOnChangeFinished = androidx.compose.runtime.rememberUpdatedState(onChangeFinished)
    // The active adjustment is already shown in the pill row below; the slider only
    // needs the current numeric value centered above its track so the user can read it
    // at a glance during a drag.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value.toString(),
            color = FgDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val pct = (o.x / size.width).coerceIn(0f, 1f)
                            latestOnChange.value((pct * 200 - 100).toInt())
                        },
                        onDrag = { change, _ ->
                            val pct = (change.position.x / size.width).coerceIn(0f, 1f)
                            latestOnChange.value((pct * 200 - 100).toInt())
                            change.consume()
                        },
                        onDragEnd = { latestOnChangeFinished.value() },
                        onDragCancel = { latestOnChangeFinished.value() },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { o ->
                            val pct = (o.x / size.width).coerceIn(0f, 1f)
                            latestOnChange.value((pct * 200 - 100).toInt())
                            latestOnChangeFinished.value()
                        },
                    )
                },
        ) {
            val cy = size.height / 2f
            // inactive track
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, cy - trackHeightPx / 2f),
                size = GSize(size.width, trackHeightPx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeightPx / 2f),
            )
            // active fill — from CENTER outward (since this is a -100..100 bipolar slider)
            val center = size.width / 2f
            val thumbX = normalized * size.width
            val left = min(center, thumbX)
            val right = max(center, thumbX)
            drawRoundRect(
                color = accentColor,
                topLeft = Offset(left, cy - trackHeightPx / 2f),
                size = GSize(right - left, trackHeightPx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeightPx / 2f),
            )
            // center tick
            drawCircle(color = fgDimColor.copy(alpha = 0.4f), radius = trackHeightPx / 2f,
                center = Offset(center, cy))
            // thumb
            drawCircle(color = accentColor, radius = thumbRadiusPx, center = Offset(thumbX, cy))
            drawCircle(color = Color.White, radius = thumbRadiusPx - 3f, center = Offset(thumbX, cy))
            drawCircle(color = accentColor, radius = thumbRadiusPx - 6f, center = Offset(thumbX, cy))
        }
    }
}

@Composable
private fun FilterThumb(filter: FilterPreset, source: Bitmap?, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        // Filter swatches are square thumbnails (a circle hides too much of the
        // preview to read), so they keep a rounded-rect shape — but use the same
        // PillBg + PillBorder tokens so they sit on the editor background the same
        // way the gallery filter pills do.
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PillBg)
                .border(0.5.dp, PillBorder, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (source != null) {
                // Apply the filter's ColorMatrix at draw time so the chip previews what
                // selecting it would do to the photo — instead of showing the unmodified
                // source under every label. Stays cheap because the source is already a
                // ~160px thumbnail and Compose runs the matrix on the GPU.
                val colorFilter = remember(filter) { composeColorFilterFor(filter) }
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
            stringResource(filter.labelRes),
            color = if (selected) Accent else FgMute,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Rotate / Flip pills — match the photos page filter row recipe so the bar above
 *  the tab dock reads as the same kind of control regardless of which tool is
 *  active. Horizontal capsule shape keeps the row uniform with the adjustment chips. */
@Composable
private fun ActionTile(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(38.dp)
            .background(PillBg, pillShape)
            .border(0.5.dp, PillBorder, pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, label, tint = FgDim, modifier = Modifier.size(14.dp))
        Text(
            label,
            color = FgPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .background(if (selected) Accent.copy(alpha = 0.18f) else PillBg, pillShape)
            .then(if (!selected) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = if (selected) Accent else FgDim, modifier = Modifier.size(16.dp))
        Text(
            label,
            color = if (selected) Accent else FgPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun ActionChip(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .background(PillBg, pillShape)
            .border(0.5.dp, PillBorder, pillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            null,
            tint = if (enabled) FgDim else FgDim.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            color = if (enabled) FgPrimary else FgDim.copy(alpha = 0.4f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Compose-side mirror of [PhotoEditorViewModel.filterMatrix]. The ViewModel matrix is the
 * authoritative one (used when bitmaps are rendered for save). This duplicate is used only
 * by filter-chip thumbnails so they can preview the effect via [ColorFilter.colorMatrix]
 * without driving a bitmap recompute per chip. Keep the rows in sync if the ViewModel
 * presets change.
 */
private fun composeColorFilterFor(filter: FilterPreset): ColorFilter? = when (filter) {
    FilterPreset.None -> null
    FilterPreset.BlackWhite -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    FilterPreset.Sepia -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    FilterPreset.Vintage -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        0.9f, 0.1f, 0.1f, 0f, 20f,
        0.1f, 0.85f, 0.1f, 0f, 10f,
        0.1f, 0.2f, 0.7f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    FilterPreset.Vivid -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        1.3f, -0.1f, -0.1f, 0f, 0f,
        -0.1f, 1.3f, -0.1f, 0f, 0f,
        -0.1f, -0.1f, 1.3f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    FilterPreset.Cool -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        0.9f, 0f, 0.1f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0.1f, 0f, 1.1f, 0f, 10f,
        0f, 0f, 0f, 1f, 0f,
    )))
    FilterPreset.Warm -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        1.1f, 0f, 0f, 0f, 10f,
        0f, 1.0f, 0f, 0f, 5f,
        0f, 0f, 0.9f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    FilterPreset.Noir -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        0.389f, 0.763f, 0.148f, 0f, -35f,
        0.389f, 0.763f, 0.148f, 0f, -35f,
        0.389f, 0.763f, 0.148f, 0f, -35f,
        0f, 0f, 0f, 1f, 0f,
    )))
    FilterPreset.Chrome -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        1.18f, 0f, 0f, 0f, -8f,
        0f, 1.16f, 0f, 0f, -6f,
        0f, 0f, 1.22f, 0f, 2f,
        0f, 0f, 0f, 1f, 0f,
    )))
    FilterPreset.Matte -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        0.88f, 0f, 0f, 0f, 22f,
        0f, 0.88f, 0f, 0f, 20f,
        0f, 0f, 0.86f, 0f, 16f,
        0f, 0f, 0f, 1f, 0f,
    )))
    FilterPreset.Dramatic -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        1.25f, -0.06f, -0.06f, 0f, -20f,
        -0.06f, 1.25f, -0.06f, 0f, -20f,
        -0.06f, -0.06f, 1.25f, 0f, -20f,
        0f, 0f, 0f, 1f, 0f,
    )))
    FilterPreset.Fresh -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        1.06f, 0f, 0f, 0f, 8f,
        0f, 1.1f, 0f, 0f, 12f,
        0f, 0f, 1.08f, 0f, 12f,
        0f, 0f, 0f, 1f, 0f,
    )))
}
