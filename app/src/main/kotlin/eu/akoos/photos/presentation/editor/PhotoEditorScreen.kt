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
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.lock.runWhenOpen

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
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemGestures
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
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.filled.FilterFrames
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PanelChip
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.theme.pillShape
import eu.akoos.photos.presentation.theme.TrackBg
import eu.akoos.photos.util.FitBox
import eu.akoos.photos.util.ImageFit
import eu.akoos.photos.util.fitImageInBox
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Tool { Adjust, Filter, Color, Crop, Frame, Markup, Redact, HideFaces, Draw, Text, More }

/**
 * The tabs of the bottom bar, icon only, each opening one of its tools: Transform is the crop with its turn, flip,
 * ratio and straighten controls, Draw groups the tools that mark the photo (picked in a row of names under the
 * panel), and More lists the rest as tiles, each opening its own panel.
 */
private enum class EditorTab(
    @androidx.annotation.StringRes val labelRes: Int,
    val icon: ImageVector,
    val tools: List<Tool>,
) {
    Transform(R.string.editor_tab_transform, Icons.Default.CropRotate, listOf(Tool.Crop)),
    Filters(R.string.editor_tool_filter, Icons.Default.Tonality, listOf(Tool.Filter)),
    Tone(R.string.editor_tool_adjust, Icons.Default.LightMode, listOf(Tool.Adjust)),
    Decorate(R.string.editor_tool_draw, Icons.Default.Draw, listOf(Tool.Markup, Tool.Draw, Tool.Text, Tool.Redact, Tool.HideFaces)),
    More(R.string.editor_tab_more, Icons.Default.Apps, listOf(Tool.More, Tool.Frame, Tool.Color)),
}

private fun Tool.tab(): EditorTab = EditorTab.entries.first { this in it.tools }

@androidx.annotation.StringRes
private fun Tool.labelRes(): Int = when (this) {
    Tool.Adjust -> R.string.editor_tool_adjust
    Tool.Filter -> R.string.editor_tool_filter
    Tool.Color -> R.string.editor_tool_color
    Tool.Crop -> R.string.video_editor_crop
    Tool.Frame -> R.string.editor_tool_frame
    Tool.Redact -> R.string.editor_tool_redact
    Tool.HideFaces -> R.string.editor_hide_faces
    Tool.Draw -> R.string.editor_tool_draw
    Tool.Text -> R.string.editor_tool_text
    Tool.Markup -> R.string.editor_tool_draw
    Tool.More -> R.string.editor_tab_more
}

/**
 * The panel a tool opens from its row of buttons, with a back arrow over it: null is the row itself. Every tool in the
 * Transform and Draw tabs works this way, the buttons first and each setting in a panel of its own.
 */
private enum class ToolOption {
    Ratio, Straighten,
    TextColor, TextBackground,
    FacesStyle, FacesStrength,
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
    // AI-features gate: with the master switch or the per-feature face switch off, the Hide-faces tool
    // is dropped from the tool row entirely, so its face model can never be fetched from here. Every
    // other tool is unaffected.
    val aiFeaturesEnabled by vm.aiFeaturesEnabled.collectAsStateWithLifecycle()
    val faceEnabled by vm.faceEnabled.collectAsStateWithLifecycle()
    val visibleTools = remember(aiFeaturesEnabled, faceEnabled) {
        Tool.entries.filter { it != Tool.HideFaces || (aiFeaturesEnabled && faceEnabled) }
    }
    var activeTool by remember { mutableStateOf(Tool.Crop) }
    val activeTab = activeTool.tab()
    // Each tab reopens on the tool it was last left on; More always opens on its tiles.
    val tabMemory = remember { mutableMapOf<EditorTab, Tool>() }
    val openTool: (Tool) -> Unit = { tool ->
        activeTool = tool
        tabMemory[tool.tab()] = tool
    }
    var toolOption by remember { mutableStateOf<ToolOption?>(null) }
    // The eyedropper of the Frame tool: while on, a press on the photo picks the colour under the finger.
    var framePicking by remember { mutableStateOf(false) }
    // Pen tool: current stroke colour + width (screen dp), used by the Draw overlay and panel.
    var drawColor by remember { mutableStateOf(DRAW_COLORS.first()) }
    var drawWidth by remember { mutableFloatStateOf(8f) }
    // Colour tool: Curves vs HSL sub-mode, the curve channel, and the selected HSL band.
    var colorMode by remember { mutableStateOf(ColorMode.Curves) }
    var curveChannel by remember { mutableStateOf(CurveChannel.RGB) }
    var hslBandIndex by remember { mutableIntStateOf(0) }
    // Text tool: which overlay is open for inline editing (typed directly on the photo), if any.
    var editingTextId by remember { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.LaunchedEffect(activeTool) {
        if (activeTool != Tool.Frame) framePicking = false
        toolOption = null
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
    // not be ready yet, which the LaunchedEffect covers by re-running on its size.
    // While the tool is open the rect also follows the committed crop, so an Undo or Redo shows
    // on screen. settledCropRect is the rect last seeded, or last committed by the overlay, and
    // only a rect still showing it is re-seeded: a frame the overlay is moving, or one a chip has
    // just set, is never pulled back to a committed crop that has not caught up with it yet.
    val cropDisplayBitmap = state.adjustedBitmapNoCrop ?: state.originalBitmap
    val committedCrop = state.adjustments.cropRect
    var settledCropRect by remember { mutableStateOf<android.graphics.Rect?>(null) }
    // The size of the bitmap the pending rect was made for. A turn or a flip swaps the displayed bitmap a render
    // after the edit, so a rect from the old one is not drawn on the new one: the overlay shows the whole image
    // for that moment instead of jumping.
    var pendingBasis by remember { mutableStateOf(0 to 0) }
    androidx.compose.runtime.LaunchedEffect(
        activeTool, committedCrop, cropDisplayBitmap?.width, cropDisplayBitmap?.height,
    ) {
        if (activeTool == Tool.Crop) {
            val disp = cropDisplayBitmap ?: return@LaunchedEffect
            val seed = committedCrop ?: android.graphics.Rect(0, 0, disp.width, disp.height)
            if (pendingCropRect == null || pendingCropRect == settledCropRect) {
                pendingCropRect = seed
                pendingBasis = disp.width to disp.height
            }
            settledCropRect = seed
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

    // System consent dialog for overwriting foreign MediaStore URIs. A save can raise it after the app
    // lock came up, and the system dialog would open above the lock, so it waits for the unlock.
    val appLock = LocalAppLock.current
    val writePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) vm.onWritePermissionGranted()
        else vm.onWritePermissionDenied()
    }
    androidx.compose.runtime.LaunchedEffect(state.pendingWriteIntent) {
        val pi = state.pendingWriteIntent ?: return@LaunchedEffect
        appLock.runWhenOpen {
            writePermissionLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()
            )
        }
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
        appLock.runWhenOpen {
            deletePermissionLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()
            )
        }
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
    // The panels a tool opens (the ratio row, the straighten dials, Frame, Color) close on Back, on a tap on the
    // photo and with their arrow, back to the Transform pill or the More tiles. Declared after the discard guard
    // above, so Back reaches it first.
    val inMarkupTool = activeTool.tab() == EditorTab.Decorate && activeTool != Tool.Markup
    val panelOpen = toolOption != null || activeTool == Tool.Frame || activeTool == Tool.Color || inMarkupTool
    // A tap on the photo closes the panels that leave it alone; the drawing tools use the photo themselves.
    val tapCloses = toolOption != null || activeTool == Tool.Frame || activeTool == Tool.Color
    val closePanel: () -> Unit = {
        when {
            framePicking -> framePicking = false
            toolOption != null -> toolOption = null
            inMarkupTool -> openTool(Tool.Markup)
            else -> openTool(Tool.More)
        }
    }
    androidx.activity.compose.BackHandler(enabled = panelOpen) { closePanel() }

    Column(Modifier.fillMaxSize().background(Bg0).statusBarsPadding()) {
        TopBar(
            // Busy while a Hide-faces detection runs too, so a save cannot start before the covers land
            // (the save itself also waits for one it finds in flight).
            isSaving = state.isSaving || state.hideFacesState is HideFacesState.Detecting,
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
                    if (tapCloses && !framePicking) {
                        Modifier.pointerInput(activeTool, toolOption) {
                            detectTapGestures(onTap = { closePanel() })
                        }
                    } else {
                        Modifier
                    },
                )
                .then(
                    // Press-and-hold to peek the original, but only in the tools whose preview is a
                    // static image; Crop and Redact own the pointer for their own drag gestures.
                    if (state.originalBitmap != null &&
                        (activeTool == Tool.Adjust || activeTool == Tool.Filter || activeTool == Tool.More)
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
                activeTool == Tool.Crop && toolOption != ToolOption.Straighten && state.originalBitmap != null -> CropPreview(
                    // Use adjustedBitmapNoCrop so colour edits AND any rotation / flip the
                    // user applied stay visible while they pick a crop rectangle — the Crop
                    // tab then shows the same picture the other tabs do, and the rect lives in
                    // that rotated display space (which the pipeline crops in, post-rotation).
                    // Fall back to originalBitmap for the brief window between load and the
                    // first adjust-render — identical dimensions at 0° rotation. previewBitmap
                    // is unsuitable because it has crop already applied; that would put the
                    // rect on the wrong canvas size and produce out-of-bounds reads → crash.
                    bitmap = state.adjustedBitmapNoCrop ?: state.originalBitmap!!,
                    cropRect = pendingCropRect?.takeIf {
                        pendingBasis == ((cropDisplayBitmap?.width ?: 0) to (cropDisplayBitmap?.height ?: 0))
                    },
                    lockedRatio = cropDisplayBitmap?.let { lockedAspect.lockRatio(it.width, it.height) },
                    // During the drag only the lightweight overlay rect updates (no re-render).
                    onCropRectChanged = {
                        pendingCropRect = it
                        pendingBasis = (cropDisplayBitmap?.width ?: 0) to (cropDisplayBitmap?.height ?: 0)
                    },
                    // On release the rect commits to crop state, so other tabs and save reflect
                    // it without a separate Apply press. Committing here instead of per-tick
                    // keeps it to one render + one undo entry per gesture. A full-image rect
                    // commits null so the pipeline skips a pointless full-size createBitmap().
                    onCropRectCommit = {
                        val disp = cropDisplayBitmap
                        val full = disp != null && it.left == 0 && it.top == 0 &&
                            it.right == disp.width && it.bottom == disp.height
                        settledCropRect = it
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
                activeTool == Tool.Frame && state.previewBitmap != null -> {
                    val photo = framePhotoSize(state)
                    FramePreview(
                        bitmap = state.previewBitmap!!,
                        frame = state.adjustments.frame,
                        photoW = photo.width,
                        photoH = photo.height,
                        drawStrokes = state.adjustments.drawStrokes,
                        textItems = state.adjustments.textItems,
                        onFrameLive = { vm.setFrameLive(it) },
                        onFrameCommit = { vm.finalizeAdjustments() },
                        picking = framePicking,
                        onPicked = { argb ->
                            state.adjustments.frame?.let { vm.setFrame(it.withFill(FrameFill.Solid(argb))) }
                            framePicking = false
                        },
                    )
                }
                state.previewBitmap != null -> {
                    // The preview and the edit it shows land together, so a stroke drawn on this bitmap maps
                    // through this edit's frame and crop onto the photo.
                    val shown = state.previewBitmap!!
                    val shownEdit = state.previewAdjustments
                    ImageWithRedactOverlay(
                        bitmap = shown,
                        redactActive = activeTool == Tool.Redact,
                        brushDp = state.redactBrushDp,
                        bakedStrokes = shownEdit.redactStrokes,
                        drawStrokes = state.adjustments.drawStrokes,
                        textItems = state.adjustments.textItems,
                        onStrokeFinished = { stroke ->
                            vm.addRedactStroke(stroke, shown.width, shown.height, shownEdit)
                        },
                    )
                }
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
                // Keep the system back / edge-swipe gesture out of the control area: dragging an adjustment
                // slider or a crop handle that starts near the screen edge must not turn into a back nav.
                // The Frame tool keeps only its tool row out (below): the system honours 200dp of these per
                // screen edge, lowest first, and this whole area would leave none for a drag on the framed
                // photo. Its own panel is chips and swatches, with no slider to guard.
                .then(if (activeTool == Tool.Frame) Modifier else Modifier.systemGestureExclusion())
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Panel — varies per tool. Lives in horizontal-padding so the inner pills
            // don't kiss the screen edges, but does not have its own outer pill:
            // adjustment chips are individual loose capsules, and the filter / crop /
            // redact / rotate panels render their own pill recipes inside.
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).wrapContentHeight(),
            ) {
                AnimatedContent(
                    targetState = activeTool to toolOption,
                    // One thing at a time: the old panel fades out, the box then eases to the new height, and
                    // the new panel fades in as that settles, so the photo above is never pulled two ways.
                    transitionSpec = {
                        (
                            (fadeIn(tween(170, delayMillis = 110)) + slideInVertically(tween(210, delayMillis = 110)) { it / 12 }) togetherWith
                                fadeOut(tween(90))
                            ) using SizeTransform(clip = false) { _, _ -> tween(210, delayMillis = 60) }
                    },
                    label = "editor_panel",
                ) { (tool, panel) ->
                    when (tool) {
                    Tool.Adjust -> AdjustPanel(adjustments = state.adjustments, vm = vm)
                    Tool.Filter -> FilterPanel(state, vm)
                    Tool.Color -> PanelWithBack(Tool.Color.labelRes(), onBack = { openTool(Tool.More) }) {
                        ColorPanel(
                            state = state,
                            vm = vm,
                            mode = colorMode,
                            onMode = { colorMode = it },
                            curveChannel = curveChannel,
                            onCurveChannel = { curveChannel = it },
                            hslBandIndex = hslBandIndex,
                            onHslBand = { hslBandIndex = it },
                        )
                    }
                    Tool.Crop -> when (panel) {
                        ToolOption.Ratio -> PanelWithBack(R.string.editor_transform_ratio, onBack = { toolOption = null }) {
                            CropPanel(
                                state = state,
                                vm = vm,
                                lockedAspect = lockedAspect,
                                onLockedAspectChange = { lockedAspect = it },
                                onPendingCropRectChange = {
                                    pendingCropRect = it
                                    pendingBasis = (cropDisplayBitmap?.width ?: 0) to (cropDisplayBitmap?.height ?: 0)
                                },
                            )
                        }
                        ToolOption.Straighten -> StraightenPanel(state, vm, onBack = { toolOption = null })
                        else -> TransformPill(
                            onFlipH = { vm.toggleFlipH() },
                            onFlipV = { vm.toggleFlipV() },
                            onRotate = { vm.rotate90Cw() },
                            onRatio = { toolOption = ToolOption.Ratio },
                            onStraighten = { toolOption = ToolOption.Straighten },
                        )
                    }
                    Tool.Frame -> PanelWithBack(Tool.Frame.labelRes(), onBack = { openTool(Tool.More) }) {
                        FramePanel(state, vm, picking = framePicking, onPickToggle = { framePicking = !framePicking })
                    }
                    Tool.More -> MorePanel(onOpen = openTool)
                    Tool.Markup -> MarkupPill(
                        tools = visibleTools.filter { it in EditorTab.Decorate.tools && it != Tool.Markup },
                        onPick = openTool,
                    )
                    Tool.Redact -> ToolShell(Tool.Redact, panel, { openTool(Tool.Markup) }) {
                        RedactTool(state, vm)
                    }
                    Tool.HideFaces -> ToolShell(Tool.HideFaces, panel, { openTool(Tool.Markup) }) {
                        FacesTool(state, vm, panel) { toolOption = it }
                    }
                    Tool.Draw -> ToolShell(Tool.Draw, panel, { openTool(Tool.Markup) }) {
                        DrawTool(
                            color = drawColor,
                            onColor = { drawColor = it },
                            width = drawWidth,
                            onWidth = { drawWidth = it },
                        )
                    }
                    Tool.Text -> ToolShell(Tool.Text, panel, { openTool(Tool.Markup) }) {
                        TextTool(
                            state = state,
                            onAdd = { editingTextId = vm.addEmptyTextItem() },
                            onEdit = { item -> vm.beginTextEdit(item.id); editingTextId = item.id },
                            vm = vm,
                            option = panel,
                            onOption = { toolOption = it },
                        )
                    }
                    }
                }
            }

            // A benign / inline save failure (e.g. a user-cancelled overwrite) stays as a quiet hint
            // above the tab bar; a blocking failure (no internet on a cloud edit) uses the dialog below.
            val inlineFailure = state.saveResult as? SaveResult.Failed
            if (inlineFailure != null && !inlineFailure.asDialog) {
                Text(
                    text = inlineFailure.message,
                    color = Color(0xFFFF3B30),
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp),
                )
            }

            // Bottom tab bar: a single pill of icon-only tabs sharing the width, the selected one in the accent.
            // Matches GalleryScreen.BottomDock: PillBgOpaque + 0.5.dp PillBorder + pillShape + inner padding.
            Row(
                modifier = Modifier
                    // An exclusion of its own, so the row keeps it while the Frame tool lifts the one above.
                    .systemGestureExclusion()
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .background(PillBgOpaque, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorTab.entries.forEach { tab ->
                    ToolTab(
                        tab = tab,
                        selected = tab == activeTab,
                        onClick = {
                            // More and Draw open on their list of tools, the others where they were left.
                            val selector = tab == EditorTab.More || tab == EditorTab.Decorate
                            openTool(if (selector) tab.tools.first() else tabMemory[tab] ?: tab.tools.first())
                        },
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

    // A blocking save failure flagged for the dialog (a cloud edit attempted with no internet). Shown
    // as the app's own bottom-sheet dialog rather than inline text; dismissing clears the result and
    // leaves the editor open with the edit intact, so the user can reconnect and save again, or discard.
    // It is a window of its own, so under the app lock it waits, the result kept, and shows on the unlock.
    val failedResult = (state.saveResult as? SaveResult.Failed)?.takeIf { it.asDialog }
    if (failedResult != null && !appLock.isLocked) {
        ConfirmDialog(
            title = stringResource(R.string.editor_save_failed),
            message = failedResult.message,
            confirmLabel = stringResource(android.R.string.ok),
            dismissLabel = null,
            onConfirm = { vm.consumeSaveResult() },
            onDismiss = { vm.consumeSaveResult() },
        )
    }

    // Raised by the Detect-faces action itself, so it answers something the user just asked for rather
    // than interrupting. Accepting persists consent and re-runs detection; the failure case is shown
    // inline in the panel instead. Held back under the app lock like the save failure above.
    if (state.hideFacesState is HideFacesState.NeedsConsent && !appLock.isLocked) {
        ConfirmDialog(
            title = stringResource(R.string.editor_face_model_title),
            message = stringResource(R.string.editor_face_model_body),
            confirmLabel = stringResource(R.string.sel_label_download),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { vm.allowFaceModelDownload() },
            onDismiss = { vm.dismissHideFacesPrompt() },
        )
    }

    val hasCloudCounterpart by vm.hasCloudCounterpart.collectAsStateWithLifecycle()
    if (showSaveSheet && state.source != null) {
        ModalBottomSheet(
            onDismissRequest = { showSaveSheet = false },
            sheetState = saveSheetState,
            containerColor = SheetBg,
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
    // Icon-only confirm: a round accent button with just a checkmark (a spinner while saving), no label,
    // matching the video editor's top-bar confirm so the two editors read as one design.
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Accent)
            .clickable(enabled = !isSaving, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSaving) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        } else {
            Icon(Icons.Default.Check, stringResource(R.string.action_save), tint = Color.White, modifier = Modifier.size(20.dp))
        }
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
private fun ToolTab(tab: EditorTab, selected: Boolean, onClick: () -> Unit) {
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
            .size(48.dp)
            .background(Accent.copy(alpha = bgAlpha), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = stringResource(tab.labelRes),
            tint = tint,
            modifier = Modifier
                .size(24.dp)
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
    bakedStrokes: List<RedactionStroke>,
    drawStrokes: List<DrawStroke>,
    textItems: List<TextItem>,
    onStrokeFinished: (RedactionStroke) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var currentStrokeCanvas by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // Keep the in-progress stroke drawn until a preview baking it lands (the strokes baked into the
    // bitmap change), so the mark never blinks out in the gap while the re-render is still in flight.
    // The drag guard stops a fast next stroke being wiped by the previous one's bake landing mid-draw.
    var isDragging by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(bakedStrokes) {
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
                if (item.hasBackground()) {
                    val box = textBackdrop(paint.textSize, lines.maxOf { paint.measureText(it) }, lines.size, item.cornerRadius)
                    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = textBackgroundArgb(item)
                    }
                    canvas.nativeCanvas.drawRoundRect(
                        cxScreen - box.halfWidth, cyScreen - box.halfHeight,
                        cxScreen + box.halfWidth, cyScreen + box.halfHeight,
                        box.radius, box.radius, fill,
                    )
                }
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
                            // A tap on a text picks it, which brings up its menu; a tap on the picked one opens it
                            // to type in.
                            val wasSelected = selectedIdState.value == hitId
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
                            if (moved) onMoveFinished() else if (wasSelected) onRequestEdit(hitId)
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

/**
 * The preview while the Frame tool is open: the framed photo with the pen and text layer over it. With a frame on,
 * a pinch resizes the photo inside it and a drag moves it, both measured from where the gesture began
 * ([afterPreviewGesture]). Near the middle of the frame the photo snaps onto it ([snappedToCentre]) with a light
 * tick, and a guide line crosses the frame on that axis while the finger stays down. Each move renders live
 * through [onFrameLive], and the gesture ends in one commit through [onFrameCommit]; a touch that moved nothing
 * commits nothing.
 */
@Composable
private fun FramePreview(
    bitmap: Bitmap,
    frame: FrameSpec?,
    photoW: Int,
    photoH: Int,
    drawStrokes: List<DrawStroke>,
    textItems: List<TextItem>,
    onFrameLive: (FrameSpec) -> Unit,
    onFrameCommit: () -> Unit,
    picking: Boolean = false,
    onPicked: (Int) -> Unit = {},
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // Where the finger is while the eyedropper is down, for the loupe.
    var pickAt by remember { mutableStateOf<Offset?>(null) }
    // The axes a gesture holds the photo snapped on, across and down, for the guide lines.
    var guideX by remember { mutableStateOf(false) }
    var guideY by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val guideColor = Accent
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
        // The pen + text overlays live here as a Compose layer (they are stripped from the baked preview).
        if (drawStrokes.isNotEmpty() || textItems.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                paintEditorOverlays(drawStrokes, textItems, bitmap, fit)
            }
        }
        if (frame != null && containerSize.width > 0 && containerSize.height > 0) {
            // Where the photo shows, through the full-size canvas fitted to the preview: the same measure the
            // gesture turns a drag with, which a render still in flight cannot skew.
            val layout = frameCanvas(photoW, photoH, frame)
            val canvasFit = fitImageInBox(
                layout.width.toFloat(), layout.height.toFloat(),
                containerSize.width.toFloat(), containerSize.height.toFloat(),
            )
            val photo = layout.photo
            val photoOnScreen = canvasFit.toScreen(FitBox(photo.left, photo.top, photo.right, photo.bottom))
            FrameGestureExclusions(photo = photoOnScreen, containerWidthPx = containerSize.width.toFloat())
            if (guideX || guideY) {
                val edges = canvasFit.toScreen(FitBox(0f, 0f, layout.width.toFloat(), layout.height.toFloat()))
                // Through the photo's centre, which the snap has put on the frame's middle, edge to edge of the frame.
                Canvas(Modifier.fillMaxSize()) {
                    val hairline = 1.dp.toPx()
                    val cx = (photoOnScreen.left + photoOnScreen.right) / 2f
                    val cy = (photoOnScreen.top + photoOnScreen.bottom) / 2f
                    if (guideX) drawLine(guideColor, Offset(cx, edges.top), Offset(cx, edges.bottom), hairline)
                    if (guideY) drawLine(guideColor, Offset(edges.left, cy), Offset(edges.right, cy), hairline)
                }
            }
            // The loop outlives recomposition, and every tick commits a new frame, so it reads the frame and the
            // callbacks through these instead of restarting on them.
            val frameState = androidx.compose.runtime.rememberUpdatedState(frame)
            val onLiveState = androidx.compose.runtime.rememberUpdatedState(onFrameLive)
            val onCommitState = androidx.compose.runtime.rememberUpdatedState(onFrameCommit)
            if (!picking) Box(
                Modifier
                    .fillMaxSize()
                    // Keyed on the photo's size alone, which only a crop or a turn changes, so the preview
                    // re-rendering under a gesture never restarts it.
                    .pointerInput(photoW, photoH) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val start = frameState.value
                            val slopPx = viewConfiguration.touchSlop
                            val snapPx = FRAME_SNAP_DP.dp.toPx()
                            // A photo that starts on the middle, or with no room yet to leave it, counts as
                            // snapped already, so only a snap the gesture makes ticks.
                            val startSnap = start.snappedToCentre(photoW, photoH, size.width, size.height, snapPx)
                            var snappedX = start.offsetX == 0f || startSnap.snappedX
                            var snappedY = start.offsetY == 0f || startSnap.snappedY
                            var zoom = 1f
                            var pan = Offset.Zero
                            var engaged = false
                            var shown = start
                            var live = false
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    // Both count only the fingers down on either side of this event, so a
                                    // finger landing or lifting adds no step.
                                    val step = event.calculateZoom()
                                    if (step.isFinite() && step > 0f) zoom *= step
                                    pan += event.calculatePan()
                                    // A second finger makes a pinch at once; a lone finger takes the photo once
                                    // it is past the slop, and the photo catches up with it.
                                    if (!engaged) engaged = pressed.size >= 2 || pan.getDistance() > slopPx
                                    if (engaged) {
                                        val snap = start
                                            .afterPreviewGesture(
                                                zoom, pan.x, pan.y, photoW, photoH, size.width, size.height,
                                            )
                                            .snappedToCentre(photoW, photoH, size.width, size.height, snapPx)
                                        if ((snap.snappedX && !snappedX) || (snap.snappedY && !snappedY)) {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        snappedX = snap.snappedX
                                        snappedY = snap.snappedY
                                        guideX = snappedX
                                        guideY = snappedY
                                        val next = snap.spec
                                        if (next != shown) {
                                            onLiveState.value(next)
                                            shown = next
                                            live = true
                                        }
                                        pressed.forEach { it.consume() }
                                    }
                                } while (pressed.isNotEmpty())
                            } finally {
                                guideX = false
                                guideY = false
                                // Also when the tool closes under the fingers, so the live frames always end in
                                // one undo entry and a full render.
                                if (live) onCommitState.value()
                            }
                        }
                    },
            )
            if (picking) {
                val bitmapState = androidx.compose.runtime.rememberUpdatedState(bitmap)
                val fitState = androidx.compose.runtime.rememberUpdatedState(fit)
                val onPickedState = androidx.compose.runtime.rememberUpdatedState(onPicked)
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                pickAt = down.position
                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.pressed }
                                        if (change != null) {
                                            pickAt = change.position
                                            change.consume()
                                        }
                                    } while (event.changes.any { it.pressed })
                                    val p = pickAt
                                    val b = bitmapState.value
                                    val f = fitState.value
                                    if (p != null && !b.isRecycled) {
                                        val x = f.toImageX(p.x).toInt()
                                        val y = f.toImageY(p.y).toInt()
                                        if (x in 0 until b.width && y in 0 until b.height) {
                                            onPickedState.value(b.getPixel(x, y) or (0xFF shl 24))
                                        }
                                    }
                                } finally {
                                    pickAt = null
                                }
                            }
                        },
                )
                if (pickAt != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val p = pickAt ?: return@Canvas
                        val b = bitmapState.value
                        val f = fitState.value
                        if (b.isRecycled || b.width < LOUPE_CELLS || b.height < LOUPE_CELLS) return@Canvas
                        val ix = f.toImageX(p.x).toInt().coerceIn(0, b.width - 1)
                        val iy = f.toImageY(p.y).toInt().coerceIn(0, b.height - 1)
                        val half = LOUPE_CELLS / 2
                        val sx = (ix - half).coerceIn(0, b.width - LOUPE_CELLS)
                        val sy = (iy - half).coerceIn(0, b.height - LOUPE_CELLS)
                        val r = 46.dp.toPx()
                        val cx = p.x.coerceIn(r, size.width - r)
                        val cy = if (p.y - r * 2.4f > 0f) p.y - r * 1.7f else p.y + r * 1.7f
                        val cell = 2f * r / LOUPE_CELLS
                        val left = cx - r
                        val top = cy - r
                        val clip = androidx.compose.ui.graphics.Path().apply {
                            addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r))
                        }
                        clipPath(clip) {
                            drawImage(
                                image = b.asImageBitmap(),
                                srcOffset = androidx.compose.ui.unit.IntOffset(sx, sy),
                                srcSize = IntSize(LOUPE_CELLS, LOUPE_CELLS),
                                dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                                dstSize = IntSize((2f * r).toInt(), (2f * r).toInt()),
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                            )
                        }
                        drawCircle(Color.White, r, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                        drawRect(
                            Color.White,
                            topLeft = Offset(left + (ix - sx) * cell, top + (iy - sy) * cell),
                            size = GSize(cell, cell),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                        )
                        drawRect(
                            Color.Black,
                            topLeft = Offset(left + (ix - sx) * cell - 1.dp.toPx(), top + (iy - sy) * cell - 1.dp.toPx()),
                            size = GSize(cell + 2.dp.toPx(), cell + 2.dp.toPx()),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}

/** Pixels across the eyedropper loupe's window on the preview, the middle one the colour it picks. */
private const val LOUPE_CELLS = 11

/**
 * Asks the system to hold back its edge gesture beside the sides of the framed [photo] that sit in its edge strips,
 * so a drag that starts on the photo's edge moves the photo; [frameGestureExclusions] decides where. Built like
 * [CropGestureExclusions]: boxes that draw nothing and take no touch, placed by a layout offset so every move of
 * the photo refreshes them.
 */
@Composable
private fun BoxScope.FrameGestureExclusions(photo: FitBox, containerWidthPx: Float) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestureInsets = WindowInsets.systemGestures
    val rects = frameGestureExclusions(
        photo = photo,
        containerWidthPx = containerWidthPx,
        leftInsetPx = gestureInsets.getLeft(density, layoutDirection).toFloat(),
        rightInsetPx = gestureInsets.getRight(density, layoutDirection).toFloat(),
        maxHeightPx = with(density) { SYSTEM_GESTURE_EXCLUSION_BUDGET_DP.dp.toPx() },
    )
    for (r in rects) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(
                    x = with(density) { r.left.toDp() },
                    y = with(density) { r.top.toDp() },
                )
                .size(
                    width = with(density) { (r.right - r.left).toDp() },
                    height = with(density) { (r.bottom - r.top).toDp() },
                )
                .systemGestureExclusion(),
        )
    }
}

/**
 * The photo's full-resolution size before its frame, as the render cuts it: the crop, or else the whole image
 * turned by the edit's quarter turns. A frame's canvas is measured on it for a pinch, a drag or a ratio pick. Read
 * from the original rather than a rendered bitmap, so it never trails a render still in flight.
 */
private fun framePhotoSize(state: EditorUiState): IntSize {
    val crop = state.adjustments.cropRect
    if (crop != null) return IntSize(crop.width(), crop.height())
    val orig = state.originalBitmap ?: return IntSize.Zero
    return if (state.adjustments.rotationDegrees % 180 != 0) IntSize(orig.height, orig.width)
    else IntSize(orig.width, orig.height)
}

// ─── Tool panels ────────────────────────────────────────────────────────────

/** Adjust tab: a name over a row of round icons, the leading one the Auto-fix, and a ruler under it. The
 *  selected adjustment's name sits above the row, its icon round filled and showing the value once it is off
 *  zero; a dot marks an unselected round that carries an edit. Setters route through updateAdjustmentsFast for
 *  the live preview and fold into one undo entry on release via finalizeAdjustments. Long-pressing a round
 *  resets that single adjustment, and Reset clears all 13 (shown only while one is off-zero). */
@Composable
private fun AdjustPanel(
    adjustments: EditorAdjustments,
    vm: PhotoEditorViewModel,
) {
    var selected by remember { mutableStateOf(Adjustment.Brightness) }
    // (adjustment, label, icon, current value); a non-zero value lights the round's "modified" dot.
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
    val selectedChip = chips.first { it.adj == selected }
    val value = selectedChip.value
    val onChange: (Int) -> Unit = when (selected) {
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
    val anyEdited = chips.any { it.value != 0 }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The selected adjustment's name, and a reset that clears every adjustment in one gesture (one undo
        // entry). The row keeps its height whether or not Reset shows, so the panel does not jolt.
        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(selectedChip.labelRes),
                color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (anyEdited) {
                Text(
                    stringResource(R.string.filter_reset),
                    color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable {
                            vm.resetAdjustment { a ->
                                a.copy(
                                    brightness = 0, exposure = 0, contrast = 0, highlights = 0,
                                    shadows = 0, saturation = 0, vibrance = 0, tone = 0,
                                    temperature = 0, sharpen = 0, grain = 0, vignette = 0, fade = 0,
                                )
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        val circlesState = androidx.compose.foundation.lazy.rememberLazyListState()
        val resetOne: (Adjustment) -> Unit = { adj ->
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
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyRow(
                state = circlesState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                item(key = "auto_fix") {
                    ToolCircle(
                        icon = Icons.Default.AutoFixHigh,
                        label = stringResource(R.string.editor_auto_fix),
                        selected = false,
                        accentIcon = true,
                        onClick = { vm.autoFix() },
                    )
                }
                items(chips, key = { it.adj.name }) { chip ->
                    ToolCircle(
                        icon = chip.icon,
                        label = stringResource(chip.labelRes),
                        selected = selected == chip.adj,
                        modified = chip.value != 0,
                        valueText = chip.value.takeIf { it != 0 }?.let { if (it > 0) "+$it" else it.toString() },
                        // A tap on the round already open takes that adjustment back to zero; a long press does
                        // the same from any round.
                        onClick = {
                            if (selected == chip.adj) {
                                if (chip.value != 0) resetOne(chip.adj)
                            } else {
                                selected = chip.adj
                            }
                        },
                        onLongClick = { resetOne(chip.adj) },
                    )
                }
            }
            LazyDots(circlesState)
        }
        // The live preview tints per tick through updateAdjustmentsFast, and the drag folds into one undo
        // entry on release via finalizeAdjustments.
        RulerSlider(
            value = value.toFloat(),
            valueRange = -100f..100f,
            onChange = { onChange(it.roundToInt()) },
            onFinished = { vm.finalizeAdjustments() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One Adjust round's static metadata plus its current value (non-zero lights the "modified" dot). */
private data class AdjChip(
    val adj: Adjustment,
    @androidx.annotation.StringRes val labelRes: Int,
    val icon: ImageVector,
    val value: Int,
)

/**
 * A round tool button, 56dp: PillBg with a PillBorder hairline, or FgPrimary filled with the icon in the page
 * colour while [selected]. A selected round shows [valueText] in the icon's place once there is one, and an
 * unselected one a small accent dot while [modified]. [accentIcon] lights the icon for a button that acts rather
 * than selects. Long-pressing (when [onLongClick] is set) is the round's own reset.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolCircle(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modified: Boolean = false,
    accentIcon: Boolean = false,
    valueText: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (selected) FgPrimary else PillBg, CircleShape)
            .then(if (!selected) Modifier.border(0.5.dp, PillBorder, CircleShape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected && valueText != null) {
            Text(valueText, color = Bg0, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        } else {
            Icon(
                icon,
                label,
                tint = if (selected) Bg0 else if (accentIcon) Accent else FgDim,
                modifier = Modifier.size(24.dp),
            )
        }
        if (modified && !selected) {
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(6.dp).background(Accent, CircleShape))
        }
    }
}

/**
 * A ruler for a value: ticks across the width, every fifth longer, the span between zero (or the low end) and the
 * value in the accent and a marker at the value. A press or a drag anywhere sets the value under the finger, and
 * [onFinished] ends the gesture, so one drag is one undo entry where the caller folds it that way.
 */
@Composable
internal fun RulerSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // The gesture loops start once, so they read the newest lambdas and range through these.
    val latestOnChange = androidx.compose.runtime.rememberUpdatedState(onChange)
    val latestOnFinished = androidx.compose.runtime.rememberUpdatedState(onFinished)
    val latestRange = androidx.compose.runtime.rememberUpdatedState(valueRange)
    val accent = Accent
    val tickColor = FgMute.copy(alpha = 0.55f)
    fun valueAt(x: Float, width: Float): Float {
        val r = latestRange.value
        return r.start + (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f) * (r.endInclusive - r.start)
    }
    Canvas(
        modifier = modifier
            .height(36.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { o -> latestOnChange.value(valueAt(o.x, size.width.toFloat())) },
                    onDrag = { change, _ ->
                        latestOnChange.value(valueAt(change.position.x, size.width.toFloat()))
                        change.consume()
                    },
                    onDragEnd = { latestOnFinished.value() },
                    onDragCancel = { latestOnFinished.value() },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { o ->
                        latestOnChange.value(valueAt(o.x, size.width.toFloat()))
                        latestOnFinished.value()
                    },
                )
            },
    ) {
        val span = valueRange.endInclusive - valueRange.start
        if (span <= 0f) return@Canvas
        val frac = ((value - valueRange.start) / span).coerceIn(0f, 1f)
        val zeroFrac = if (valueRange.start < 0f) (-valueRange.start / span).coerceIn(0f, 1f) else 0f
        val lo = min(zeroFrac, frac)
        val hi = max(zeroFrac, frac)
        val pad = 4.dp.toPx()
        val usable = size.width - 2f * pad
        val cy = size.height / 2f
        val steps = 40
        for (i in 0..steps) {
            val f = i / steps.toFloat()
            val x = pad + usable * f
            val major = i % 5 == 0
            val half = size.height * (if (major) 0.26f else 0.16f)
            val lit = f in lo..hi && hi > lo
            drawLine(
                color = if (lit) accent else tickColor,
                start = Offset(x, cy - half),
                end = Offset(x, cy + half),
                strokeWidth = (if (major) 2.dp else 1.5.dp).toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        val mx = pad + usable * frac
        drawLine(
            color = accent,
            start = Offset(mx, 3.dp.toPx()),
            end = Offset(mx, size.height - 3.dp.toPx()),
            strokeWidth = 3.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/**
 * The panel a tool opens from More or from Transform's pill: a back arrow and the tool's name over [content].
 */
@Composable
internal fun PanelWithBack(
    @androidx.annotation.StringRes titleRes: Int,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    // A panel that has only just come up ignores its arrow for a moment, so a second tap meant for the panel that
    // just closed cannot land on this one's arrow and close it too.
    var armed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(350)
        armed = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { if (armed) onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.onboarding_back),
                    tint = FgDim,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                stringResource(titleRes),
                color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        content()
    }
}

/** One button of an [OptionPill]: [swatch] draws a colour dot in the icon's place, [turn] turns the icon. */
internal data class PillAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val active: Boolean = false,
    val swatch: Int? = null,
    val turn: Float = 0f,
    /** A colour setting: its round is filled with [swatch], plain while there is none. */
    val colour: Boolean = false,
)

/**
 * A tool's row of buttons in one pill, the way the Transform tab shows its flips, turn, ratio and straighten: a button
 * either acts at once or opens its setting in a panel of its own. A lit one is a setting that is on, a dimmed one is
 * not available yet.
 */
@Composable
internal fun OptionPill(items: List<PillAction>) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .background(PillBgOpaque, pillShape)
                .border(0.5.dp, PillBorder, pillShape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { a ->
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (a.active) Accent.copy(alpha = 0.22f) else Color.Transparent, CircleShape)
                        .alpha(if (a.enabled) 1f else 0.38f)
                        .clickable(enabled = a.enabled, onClick = a.onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    if (a.swatch != null) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .background(Color(a.swatch), CircleShape)
                                .border(1.dp, PillBorder, CircleShape)
                                .semantics { contentDescription = a.label },
                        )
                    } else {
                        Icon(
                            a.icon,
                            a.label,
                            tint = if (a.active) Accent else FgPrimary,
                            modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = a.turn },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A tool's row of round buttons, the buttons of an [OptionPill] as the rounds the Adjust tab uses: one that acts or
 * opens a setting, a lit one that is on, a dimmed one that is not available yet, and a colour setting filled with its
 * colour.
 */
@Composable
private fun OptionRow(items: List<PillAction>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { a ->
            val click: () -> Unit = if (a.enabled) a.onClick else ({})
            Box(Modifier.alpha(if (a.enabled) 1f else 0.38f)) {
                if (a.colour) {
                    ColorCircle(a.swatch?.let { Color(it) }, a.icon, a.label, selected = false, onClick = click)
                } else {
                    ToolCircle(a.icon, a.label, selected = a.active, onClick = click)
                }
            }
        }
    }
}

/**
 * A round tool button filled with a colour, [fill], or plain while there is none, with its [icon] over it in black or
 * white, whichever reads on that colour. Selected, it takes an accent ring.
 */
@Composable
private fun ColorCircle(fill: Color?, icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = when {
        fill == null -> FgDim
        fill.luminance() > 0.5f -> Color.Black
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(fill ?: PillBg, CircleShape)
            .border(if (selected) 2.dp else 0.5.dp, if (selected) Accent else PillBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Transform's pill: flip across, flip down, turn a quarter, and the two that open a panel, the ratio and straighten. */
@Composable
private fun TransformPill(
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    onRotate: () -> Unit,
    onRatio: () -> Unit,
    onStraighten: () -> Unit,
) {
    OptionPill(
        listOf(
            PillAction(Icons.Default.Flip, stringResource(R.string.editor_flip_h), onFlipH),
            PillAction(Icons.Default.Flip, stringResource(R.string.editor_flip_v), onFlipV, turn = 90f),
            PillAction(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.video_editor_rotate), onRotate),
            PillAction(Icons.Default.AspectRatio, stringResource(R.string.editor_transform_ratio), onRatio),
            PillAction(Icons.Default.Straighten, stringResource(R.string.editor_straighten), onStraighten),
        ),
    )
}

/** Which of the straighten dials is open. */
private enum class GeoDial { Straighten, Vertical, Horizontal }

/**
 * Transform's straighten panel: three rounds, Straighten, Vertical and Horizontal keystone, over a ruler for the
 * selected one, its name above. The value shows in the selected round once it is off zero, and Reset takes all
 * three back. Live ticks render through the fast path and a release folds into one undo entry.
 */
@Composable
private fun StraightenPanel(state: EditorUiState, vm: PhotoEditorViewModel, onBack: () -> Unit) {
    var dial by remember { mutableStateOf(GeoDial.Straighten) }
    val a = state.adjustments
    val value = when (dial) {
        GeoDial.Straighten -> a.straighten
        GeoDial.Vertical -> a.perspectiveV
        GeoDial.Horizontal -> a.perspectiveH
    }
    val anyEdited = a.straighten != 0 || a.perspectiveV != 0 || a.perspectiveH != 0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.onboarding_back),
                    tint = FgDim,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                stringResource(
                    when (dial) {
                        GeoDial.Straighten -> R.string.editor_straighten
                        GeoDial.Vertical -> R.string.editor_perspective_v
                        GeoDial.Horizontal -> R.string.editor_perspective_h
                    },
                ),
                color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (anyEdited) {
                Text(
                    stringResource(R.string.editor_geo_reset),
                    color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { vm.resetGeometry() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        ) {
            ToolCircle(
                icon = Icons.Default.Straighten,
                label = stringResource(R.string.editor_straighten),
                selected = dial == GeoDial.Straighten,
                modified = a.straighten != 0,
                valueText = a.straighten.takeIf { it != 0 }?.toString(),
                onClick = {
                    if (dial == GeoDial.Straighten) {
                        if (a.straighten != 0) { vm.updateStraighten(0); vm.finalizeAdjustments() }
                    } else dial = GeoDial.Straighten
                },
            )
            ToolCircle(
                icon = Icons.Default.SwapVert,
                label = stringResource(R.string.editor_perspective_v),
                selected = dial == GeoDial.Vertical,
                modified = a.perspectiveV != 0,
                valueText = a.perspectiveV.takeIf { it != 0 }?.toString(),
                onClick = {
                    if (dial == GeoDial.Vertical) {
                        if (a.perspectiveV != 0) { vm.updatePerspectiveV(0); vm.finalizeAdjustments() }
                    } else dial = GeoDial.Vertical
                },
            )
            ToolCircle(
                icon = Icons.Default.SwapHoriz,
                label = stringResource(R.string.editor_perspective_h),
                selected = dial == GeoDial.Horizontal,
                modified = a.perspectiveH != 0,
                valueText = a.perspectiveH.takeIf { it != 0 }?.toString(),
                onClick = {
                    if (dial == GeoDial.Horizontal) {
                        if (a.perspectiveH != 0) { vm.updatePerspectiveH(0); vm.finalizeAdjustments() }
                    } else dial = GeoDial.Horizontal
                },
            )
        }
        RulerSlider(
            value = value.toFloat(),
            valueRange = -100f..100f,
            onChange = {
                val v = it.roundToInt()
                when (dial) {
                    GeoDial.Straighten -> vm.updateStraighten(v)
                    GeoDial.Vertical -> vm.updatePerspectiveV(v)
                    GeoDial.Horizontal -> vm.updatePerspectiveH(v)
                }
            },
            onFinished = { vm.finalizeAdjustments() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A tool of the Draw tab: its own panel under the photo, a back arrow and the tool's name over its buttons, until a
 * setting opens in a panel of its own, which carries its own arrow. Back leads to the list of tools.
 */
@Composable
private fun ToolShell(tool: Tool, option: ToolOption?, onBack: () -> Unit, content: @Composable () -> Unit) {
    if (option == null) PanelWithBack(tool.labelRes(), onBack = onBack, content = content) else content()
}

/** The Draw tab's list of tools in one pill, as Transform lists its actions: the tool is picked here. */
@Composable
private fun MarkupPill(tools: List<Tool>, onPick: (Tool) -> Unit) {
    OptionPill(
        tools.map { tool ->
            PillAction(
                icon = when (tool) {
                    Tool.Draw -> Icons.Default.Draw
                    Tool.Text -> Icons.Default.TextFields
                    Tool.Redact -> Icons.Default.Brush
                    else -> Icons.Default.Face
                },
                label = stringResource(tool.labelRes()),
                onClick = { onPick(tool) },
            )
        },
    )
}

/** The More tab: one tile per extra tool, each opening its own panel with a back arrow. */
@Composable
private fun MorePanel(onOpen: (Tool) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            ToolTile(Icons.Default.FilterFrames, stringResource(R.string.editor_tool_frame)) { onOpen(Tool.Frame) }
        }
        item {
            ToolTile(Icons.Default.Tune, stringResource(R.string.editor_tool_color)) { onOpen(Tool.Color) }
        }
    }
}

@Composable
private fun ToolTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .height(64.dp)
            .background(PillBg, RoundedCornerShape(16.dp))
            .border(0.5.dp, PillBorder, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = FgPrimary, modifier = Modifier.size(22.dp))
        Text(label, color = FgPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(FilterPreset.entries.toList()) { filter ->
                FilterThumb(
                    filter = filter,
                    source = thumb,
                    selected = state.adjustments.filter == filter,
                    onClick = { vm.selectFilter(filter) },
                )
            }
        }
        LazyDots(listState)
    }
}

/** Names in a row, the picked one in the accent: a small choice that does not need a button each. */
@Composable
private fun NameRow(names: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEachIndexed { i, name ->
            val on = i == selected
            Text(
                name,
                color = if (on) Accent else FgPrimary,
                fontSize = 15.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onPick(i) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The Text tool: a pill of Add, Edit, the text colour, the background and Delete, the four on the selected text
 * dimmed while there is none. The colour opens a colour picker in a panel of its own; the background opens one with
 * its opacity and corner rulers. A text has no background until the picker or the opacity gives it one. Live ticks
 * change the text on the overlay with no render, and a release folds the drag into one undo entry.
 */
@Composable
private fun TextTool(
    state: EditorUiState,
    onAdd: () -> Unit,
    onEdit: (TextItem) -> Unit,
    vm: PhotoEditorViewModel,
    option: ToolOption?,
    onOption: (ToolOption?) -> Unit,
) {
    val selected = state.adjustments.textItems.firstOrNull { it.id == state.selectedTextId }
    androidx.compose.runtime.LaunchedEffect(selected == null) {
        if (selected == null && (option == ToolOption.TextColor || option == ToolOption.TextBackground)) onOption(null)
    }
    val id = selected?.id
    when {
        option == ToolOption.TextColor && selected != null ->
            PanelWithBack(R.string.editor_text_color, onBack = { onOption(null) }) {
                ColorPicker(
                    argb = selected.color,
                    onChange = { c -> vm.updateTextItemLive(selected.id) { it.copy(color = c) } },
                    onFinished = { vm.commitTextMove() },
                )
            }
        option == ToolOption.TextBackground && selected != null ->
            PanelWithBack(R.string.editor_text_background, onBack = { onOption(null) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorPicker(
                        argb = selected.background,
                        onChange = { c ->
                            // A first colour makes the box show, so the pick is seen at once.
                            vm.updateTextItemLive(selected.id) {
                                it.copy(background = c, backgroundOpacity = if (it.hasBackground()) it.backgroundOpacity else 1f)
                            }
                        },
                        onFinished = { vm.commitTextMove() },
                    )
                    RulerRow(
                        icon = Icons.Default.Opacity,
                        label = stringResource(R.string.editor_text_opacity),
                        value = selected.backgroundOpacity * 100f,
                        onChange = { p -> vm.updateTextItemLive(selected.id) { it.copy(backgroundOpacity = p / 100f) } },
                        onFinished = { vm.commitTextMove() },
                    )
                    RulerRow(
                        icon = Icons.Default.RoundedCorner,
                        label = stringResource(R.string.editor_text_corners),
                        value = selected.cornerRadius * 100f,
                        onChange = { p -> vm.updateTextItemLive(selected.id) { it.copy(cornerRadius = p / 100f) } },
                        onFinished = { vm.commitTextMove() },
                    )
                }
            }
        else -> OptionRow(
            listOf(
                PillAction(Icons.Default.Add, stringResource(R.string.editor_text_add), onAdd),
                PillAction(
                    Icons.Default.Edit, stringResource(R.string.editor_text_edit),
                    { selected?.let(onEdit) }, enabled = selected != null,
                ),
                PillAction(
                    Icons.Default.TextFields, stringResource(R.string.editor_text_color),
                    { onOption(ToolOption.TextColor) }, enabled = selected != null, swatch = selected?.color, colour = true,
                ),
                PillAction(
                    Icons.Default.FormatColorFill, stringResource(R.string.editor_text_background),
                    { onOption(ToolOption.TextBackground) }, enabled = selected != null,
                    swatch = selected?.takeIf { it.hasBackground() }?.background, colour = true,
                ),
                PillAction(
                    Icons.Default.Delete, stringResource(R.string.editor_text_delete),
                    { id?.let { vm.removeTextItem(it) } }, enabled = selected != null,
                ),
            ).let { all -> if (selected == null) all.take(1) else all },
        )
    }
}

/** The Draw tool: the pen colour picker and the pen width ruler, both always in view. */
@Composable
private fun DrawTool(color: Int, onColor: (Int) -> Unit, width: Float, onWidth: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorPicker(argb = color, onChange = onColor, onFinished = {})
        RulerRow(
            icon = Icons.Default.LineWeight,
            label = stringResource(R.string.editor_draw_width),
            value = width,
            range = 2f..40f,
            onChange = onWidth,
            onFinished = {},
        )
    }
}

/**
 * The Redact tool: Blackout and Pixelate, the lit one the mode a stroke is painted in, with the brush size ruler
 * under them, always in view.
 */
@Composable
private fun RedactTool(state: EditorUiState, vm: PhotoEditorViewModel) {
    var modeState by remember { mutableStateOf(currentRedactMode) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OptionRow(
            listOf(
                PillAction(
                    Icons.Default.Block, stringResource(R.string.editor_redact_blackout),
                    { currentRedactMode = RedactMode.Black; modeState = RedactMode.Black },
                    active = modeState == RedactMode.Black,
                ),
                PillAction(
                    Icons.Default.GridOn, stringResource(R.string.editor_redact_pixelate),
                    { currentRedactMode = RedactMode.Pixelate; modeState = RedactMode.Pixelate },
                    active = modeState == RedactMode.Pixelate,
                ),
            ),
        )
        RulerRow(
            icon = Icons.Default.Brush,
            label = stringResource(R.string.editor_redact_brush),
            value = state.redactBrushDp,
            range = 8f..80f,
            onChange = { vm.setRedactBrush(it) },
            onFinished = {},
        )
    }
}

/**
 * The Hide-faces tool: a pill of Detect, which finds and covers every face and is only ever run by a tap, the cover
 * style, its strength and Clear. Style, strength and Clear stay dimmed until a face is covered; style and strength
 * open in a panel of their own. A status line under the pill shows the busy spinner, how many faces are covered, the
 * not-found note or a failure.
 */
@Composable
private fun FacesTool(
    state: EditorUiState,
    vm: PhotoEditorViewModel,
    option: ToolOption?,
    onOption: (ToolOption?) -> Unit,
) {
    val hf = state.hideFacesState
    val detecting = hf is HideFacesState.Detecting
    val hasCovers = state.adjustments.faceCovers.isNotEmpty()
    val style = state.adjustments.faceCoverStyle
    when (option) {
        ToolOption.FacesStyle -> PanelWithBack(R.string.editor_hide_faces, onBack = { onOption(null) }) {
            NameRow(
                names = listOf(
                    stringResource(R.string.editor_hide_faces_style_pixelate),
                    stringResource(R.string.editor_hide_faces_style_blur),
                ),
                selected = if (style == FaceCoverStyle.Blur) 1 else 0,
                onPick = { vm.setFaceCoverStyle(if (it == 1) FaceCoverStyle.Blur else FaceCoverStyle.Pixelate) },
            )
        }
        ToolOption.FacesStrength -> PanelWithBack(R.string.editor_hide_faces_strength, onBack = { onOption(null) }) {
            RulerSlider(
                value = state.adjustments.faceCoverStrength * 100f,
                valueRange = 0f..100f,
                onChange = { vm.setFaceCoverStrength(it / 100f) },
                onFinished = { vm.finalizeAdjustments() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionRow(
                listOf(
                    PillAction(
                        Icons.Default.Face, stringResource(R.string.editor_hide_faces_detect),
                        { vm.detectFaces() }, enabled = !detecting,
                    ),
                    PillAction(
                        if (style == FaceCoverStyle.Blur) Icons.Default.Deblur else Icons.Default.GridView,
                        stringResource(
                            if (style == FaceCoverStyle.Blur) R.string.editor_hide_faces_style_blur
                            else R.string.editor_hide_faces_style_pixelate,
                        ),
                        { onOption(ToolOption.FacesStyle) }, enabled = hasCovers && !detecting,
                    ),
                    PillAction(
                        Icons.Default.Tune, stringResource(R.string.editor_hide_faces_strength),
                        { onOption(ToolOption.FacesStrength) }, enabled = hasCovers && !detecting,
                    ),
                    PillAction(
                        Icons.Default.Delete, stringResource(R.string.editor_hide_faces_clear),
                        { vm.clearFaceCovers() }, enabled = hasCovers && !detecting,
                    ),
                ),
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val status = hideFacesStatus(hf, state.adjustments.faceCovers.size)) {
                    HideFacesStatus.Busy ->
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    is HideFacesStatus.Covered ->
                        Text(
                            pluralStringResource(R.plurals.editor_hide_faces_count, status.count, status.count),
                            color = FgMute, fontSize = 12.sp,
                        )
                    HideFacesStatus.NoFaces ->
                        Text(stringResource(R.string.editor_hide_faces_none), color = FgMute, fontSize = 12.sp)
                    is HideFacesStatus.Failed ->
                        Text(status.message, color = Color(0xFFFF3B30), fontSize = 12.sp)
                    HideFacesStatus.None -> Unit
                }
            }
        }
    }
}

/** An icon, a ruler for [value] over [range] and the number, one row of a style panel. */
@Composable
private fun RulerRow(
    icon: ImageVector,
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, label, tint = FgDim, modifier = Modifier.size(22.dp))
        RulerSlider(
            value = value,
            valueRange = range,
            onChange = onChange,
            onFinished = onFinished,
            modifier = Modifier.weight(1f),
        )
        Text(
            value.roundToInt().toString(),
            color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(28.dp),
        )
    }
}

/**
 * The Frame tool: a row of shapes, the border ruler and a row of fills. Off takes the frame away, and any other shape
 * puts the photo back in the middle behind an even border, in the fill already on or white for a first frame. The
 * fills run the blur of the photo, the eyedropper, then white, black and the pen colours; the eyedropper lets a press
 * on the photo pick the colour under the finger, and shows the picked colour once one is on. The border and the fills
 * stay dimmed until there is a frame. What is lit is read back from the frame itself, so it follows an undo, and every
 * pick is one undo entry.
 */
@Composable
private fun FramePanel(state: EditorUiState, vm: PhotoEditorViewModel, picking: Boolean, onPickToggle: () -> Unit) {
    val frame = state.adjustments.frame
    val shape = frameShapeOf(frame)
    val fills = remember { frameFills(DRAW_COLORS) }
    val blurSource = state.adjustedBitmapNoCrop ?: state.originalBitmap
    val blurLook = remember(blurSource) { frameBlurLook(blurSource) }
    val blurLabel = stringResource(R.string.collage_bg_blur)
    val idle = if (frame == null) Modifier.alpha(0.38f) else Modifier
    val shapesState = androidx.compose.foundation.lazy.rememberLazyListState()
    val fillsState = androidx.compose.foundation.lazy.rememberLazyListState()
    val customFill = (frame?.fill as? FrameFill.Solid)?.takeIf { it !in fills }
    // A pick that lands on the frame already on changes nothing, so it spends no render.
    val pick: (FrameSpec?) -> Unit = { next -> if (next != frame) vm.setFrame(next) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyRow(
                state = shapesState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(FrameShape.entries) { s ->
                    OptionChip(
                        label = when (s) {
                            FrameShape.Off -> stringResource(R.string.editor_frame_none)
                            FrameShape.Original -> stringResource(R.string.editor_filter_original)
                            FrameShape.Polaroid -> stringResource(R.string.editor_frame_polaroid)
                            // Numeric ratios stay as glyph labels.
                            else -> s.ratio?.label.orEmpty()
                        },
                        selected = s == shape,
                        onClick = { pick(frame.withShape(s)) },
                    )
                }
            }
            LazyDots(shapesState)
        }
        FrameBorderRow(
            frame = frame,
            onChange = { vm.setFrameLive(it) },
            onFinished = { vm.finalizeAdjustments() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyRow(
                state = fillsState,
                modifier = Modifier.fillMaxWidth().then(idle),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    FrameSwatch(
                        fill = FrameFill.Blur,
                        selected = frame?.fill == FrameFill.Blur,
                        enabled = frame != null,
                        blurLook = blurLook,
                        blurLabel = blurLabel,
                        onClick = { pick(frame.withFill(FrameFill.Blur)) },
                    )
                }
                item {
                    EyedropperSwatch(
                        picked = customFill?.argb,
                        selected = picking || customFill != null,
                        enabled = frame != null,
                        onClick = onPickToggle,
                    )
                }
                items(fills.filter { it != FrameFill.Blur }) { fill ->
                    FrameSwatch(
                        fill = fill,
                        selected = frame?.fill == fill,
                        enabled = frame != null,
                        blurLook = blurLook,
                        blurLabel = blurLabel,
                        onClick = { pick(frame.withFill(fill)) },
                    )
                }
            }
            LazyDots(fillsState)
        }
    }
}

/**
 * The eyedropper swatch, round like the other fills: the picked colour once one is on the frame, with the dropper
 * over it, and an accent ring while it is on that colour or waiting for a press on the photo.
 */
@Composable
private fun EyedropperSwatch(picked: Int?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val fill = picked?.let { Color(it) }
    val iconTint = when {
        fill == null -> FgDim
        fill.luminance() > 0.5f -> Color.Black
        else -> Color.White
    }
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(fill ?: PillBg)
            .border(if (selected) 2.dp else 0.5.dp, if (selected) Accent else PillBorder, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Colorize, null, tint = iconTint, modifier = Modifier.size(16.dp))
    }
}

/**
 * Page dots under a row that scrolls, as many as it has pages and the page in view lit, so a row that goes on past
 * the edge can be seen to. Nothing shows while the whole row fits.
 */
@Composable
internal fun LazyDots(state: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty() || info.totalItemsCount <= 0) return
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (viewport <= 0f) return
    val pitch = if (visible.size > 1) {
        (visible.last().offset - visible.first().offset).toFloat() / (visible.size - 1)
    } else {
        visible.first().size.toFloat()
    }
    if (pitch <= 0f) return
    val content = pitch * info.totalItemsCount
    val pages = kotlin.math.ceil(content / viewport).toInt().coerceIn(1, 6)
    if (pages <= 1) return
    val first = visible.first()
    val scrolled = first.index * pitch - first.offset
    val frac = (scrolled / (content - viewport).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val active = (frac * (pages - 1)).roundToInt()
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(pages) { i ->
            Box(Modifier.size(6.dp).background(if (i == active) FgPrimary else FgMute.copy(alpha = 0.45f), CircleShape))
        }
    }
}

/**
 * The border slider: how wide the margin round the photo is, in percent of its shorter side. It stays dimmed until
 * there is a frame, and each tick renders on the fast path, the whole drag folding into one undo entry. It keeps the
 * system's edge gesture out of its own row so a drag that starts near the screen edge moves the thumb.
 */
@Composable
private fun FrameBorderRow(frame: FrameSpec?, onChange: (FrameSpec) -> Unit, onFinished: () -> Unit) {
    val range = frame?.borderRange() ?: (0f..FRAME_MAX_BORDER)
    val percent = (frame?.borderValue() ?: 0f) * 100f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .systemGestureExclusion()
            .then(if (frame == null) Modifier.alpha(0.38f) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RulerSlider(
            value = percent,
            valueRange = (range.start * 100f)..(range.endInclusive * 100f),
            onChange = { if (frame != null) onChange(frame.withBorder(it / 100f)) },
            onFinished = onFinished,
            enabled = frame != null,
            modifier = Modifier.weight(1f),
        )
        Text(
            percent.roundToInt().toString(),
            color = if (frame != null) Accent else FgMute,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(24.dp),
        )
    }
}

/**
 * One fill swatch, round like the Draw tool's colours and lit the same way, with an accent ring. The blur shows
 * [blurLook], the photo blurred and dimmed as that fill draws it, and is the one swatch with a name to read out.
 */
@Composable
private fun FrameSwatch(
    fill: FrameFill,
    selected: Boolean,
    enabled: Boolean,
    blurLook: ImageBitmap?,
    blurLabel: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .then(
                when (fill) {
                    is FrameFill.Solid -> Modifier.background(Color(fill.argb))
                    FrameFill.Blur -> Modifier.background(FgMute).semantics { contentDescription = blurLabel }
                },
            )
            .border(if (selected) 2.dp else 0.5.dp, if (selected) Accent else PillBorder, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        if (fill == FrameFill.Blur) {
            if (blurLook != null) {
                Image(
                    bitmap = blurLook,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(Modifier.matchParentSize().background(Color(FRAME_BLUR_SCRIM)))
        }
    }
}

/**
 * The Blur swatch's picture: [source] shrunk to a few pixels and softened the way the fill softens the photo, for
 * the swatch to show round. Null while there is no photo, or none that can still be read.
 */
private fun frameBlurLook(source: Bitmap?): ImageBitmap? {
    if (source == null || source.isRecycled || source.width <= 0 || source.height <= 0) return null
    val k = FRAME_SWATCH_BLUR_PX / minOf(source.width, source.height).toFloat()
    val w = (source.width * k).roundToInt().coerceIn(1, 4 * FRAME_SWATCH_BLUR_PX)
    val h = (source.height * k).roundToInt().coerceIn(1, 4 * FRAME_SWATCH_BLUR_PX)
    return runCatching {
        val small = shrinkAveraged(source, w, h)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== source) small.recycle()
        Bitmap.createBitmap(softenArgb(pixels, w, h, 1, FRAME_BLUR_PASSES), w, h, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }.getOrNull()
}

/** Pixels across the short side of the Blur swatch's picture: few enough that a 28dp circle shows it soft. */
private const val FRAME_SWATCH_BLUR_PX = 12

/** One option chip, in the crop panel's ratio chip recipe: an accent fill and label while lit, a hairline border
 *  otherwise. */
@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(38.dp)
            .clip(pillShape)
            .background(if (selected) Accent.copy(alpha = 0.18f) else PillBg, pillShape)
            .then(if (!selected) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Accent else FgPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
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
