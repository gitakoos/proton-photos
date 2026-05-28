package eu.akoos.photos.presentation.editor

import eu.akoos.photos.R

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PanelBg
import eu.akoos.photos.presentation.theme.PanelChip
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.theme.TrackBg
import kotlin.math.max
import kotlin.math.min

// Match the photos page top filter row recipe verbatim — one shared shape token used
// by every pill in the editor so the editor and gallery look like they share a
// component library. Anything bigger than 999.dp is just a fully-rounded capsule.
private val pillShape = RoundedCornerShape(999.dp)

private enum class Tool(val label: String, val icon: ImageVector) {
    Adjust("Adjust", Icons.Default.Tune),
    Filter("Filter", Icons.Default.AutoFixHigh),
    Crop("Crop", Icons.Default.Crop),
    Redact("Redact", Icons.Default.Brush),
    Rotate("Rotate", Icons.AutoMirrored.Filled.RotateRight),
}

/** Which slider the Adjust tab is currently exposing in the floating slider pill.
 *  Null until the user picks one of the adjustment chips — that's the "no pill"
 *  state requested in the spec. */
private enum class Adjustment { Brightness, Exposure, Contrast, Highlights, Shadows, Saturation, Tone, Temperature }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditorScreen(
    localUri: String? = null,
    localDisplayName: String? = null,
    localMimeType: String? = null,
    cloudPhoto: CloudPhoto? = null,
    /** Non-null when the editor was opened on a Synced photo (device + cloud). The local
     *  save path then propagates the edit up to Drive too so the cloud version doesn't
     *  stay stale. cloudPhoto remains null in this case because the EDIT SOURCE is the
     *  device file — the cloud counterpart is just a side-effect target. */
    syncedCloudCounterpart: CloudPhoto? = null,
    /** If the photo was opened from an album, the new cloud uploads are added to it too. */
    sourceAlbumLinkId: String? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    vm: PhotoEditorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var activeTool by remember { mutableStateOf(Tool.Adjust) }
    // Adjust tab now exposes ONE slider at a time — the user picks which adjustment
    // (Brightness / Contrast / Saturation) via the pill row, and only then does the
    // floating slider pill appear above the row. Null = no slider shown.
    var activeAdjustment by remember { mutableStateOf<Adjustment?>(null) }
    // Resetting back to null whenever the tool changes prevents a "stale" slider
    // pill flashing when the user pops between tabs.
    androidx.compose.runtime.LaunchedEffect(activeTool) {
        if (activeTool != Tool.Adjust) activeAdjustment = null
    }
    var showSaveSheet by remember { mutableStateOf(false) }
    val saveSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Draft rect for the free-form crop. Stays null until the user enters the Crop
    // tool, at which point it seeds from the committed cropRect (or full-image bounds).
    // The user can drag corners / pan the rect; only on "Apply" does vm.applyCrop(...)
    // commit. Snapped chips also seed this rect.
    var pendingCropRect by remember { mutableStateOf<android.graphics.Rect?>(null) }
    // Seed pendingCropRect whenever the user enters the Crop tool, and clear it when
    // they leave. originalBitmap may not be ready yet — handled by the LaunchedEffect.
    androidx.compose.runtime.LaunchedEffect(activeTool, state.originalBitmap) {
        if (activeTool == Tool.Crop) {
            val orig = state.originalBitmap
            if (orig != null && pendingCropRect == null) {
                pendingCropRect = state.adjustments.cropRect
                    ?: android.graphics.Rect(0, 0, orig.width, orig.height)
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

    // Load source once.
    androidx.compose.runtime.LaunchedEffect(localUri, cloudPhoto?.linkId) {
        when {
            cloudPhoto != null -> vm.loadCloud(cloudPhoto)
            localUri != null   -> vm.loadLocal(localUri, localDisplayName ?: "photo.jpg", localMimeType ?: "image/jpeg")
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
                    saveContext.getString(R.string.editor_saved_as_copy_toast),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                vm.consumeSaveResult()
                onSaved()
            }
            else -> { /* Failed / null — let the existing Failed-message UI render */ }
        }
    }


    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Bg0).statusBarsPadding()) {
        TopBar(
            isSaving = state.isSaving,
            canUndo = canUndo,
            canRedo = canRedo,
            onBack = onBack,
            onReset = { vm.resetAll() },
            onUndo = { vm.undo() },
            onRedo = { vm.redo() },
            onSave = { showSaveSheet = true },
        )

        // ── Preview area ─────────────────────────────────────────────────────
        // While Crop is the active tool we ignore previewBitmap and render the FULL
        // uncropped original instead, with a draggable crop overlay on top. The user
        // hits "Apply" in CropPanel to commit the rect via vm.applyCrop(); after that
        // they normally switch tabs and the regular previewBitmap path resumes.
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(color = Accent)
                state.errorMessage != null -> Text(state.errorMessage!!, color = FgMute)
                activeTool == Tool.Crop && state.originalBitmap != null -> CropPreview(
                    // Use originalBitmap because the crop rect coordinates are in the
                    // ORIGINAL image's pixel space. previewBitmap has the user's existing
                    // crop already applied, so handing it to the crop overlay would put
                    // the rect on the wrong canvas size and produce out-of-bounds reads
                    // → crash. The trade-off: in-progress brightness/saturation tweaks
                    // are not visible in the crop view (a separate "pre-crop preview"
                    // bitmap is needed for that, deferred).
                    bitmap = state.originalBitmap!!,
                    cropRect = pendingCropRect,
                    onCropRectChanged = { pendingCropRect = it },
                )
                state.previewBitmap != null -> ImageWithRedactOverlay(
                    bitmap = state.previewBitmap!!,
                    redactActive = activeTool == Tool.Redact,
                    onStrokeFinished = { stroke -> vm.addRedactStroke(stroke) },
                )
            }
        }

        // ── Bottom area ──────────────────────────────────────────────────────
        // NO single big rounded panel — three independent pill containers stacked
        // vertically over the same Bg0 the preview sits on. Each pill stands on its
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
            if (activeTool == Tool.Adjust && activeAdjustment != null) {
                val adj = activeAdjustment!!
                val value = when (adj) {
                    Adjustment.Brightness  -> state.adjustments.brightness
                    Adjustment.Exposure    -> state.adjustments.exposure
                    Adjustment.Contrast    -> state.adjustments.contrast
                    Adjustment.Highlights  -> state.adjustments.highlights
                    Adjustment.Shadows     -> state.adjustments.shadows
                    Adjustment.Saturation  -> state.adjustments.saturation
                    Adjustment.Tone        -> state.adjustments.tone
                    Adjustment.Temperature -> state.adjustments.temperature
                }
                val label = when (adj) {
                    Adjustment.Brightness  -> "Brightness"
                    Adjustment.Exposure    -> "Exposure"
                    Adjustment.Contrast    -> "Contrast"
                    Adjustment.Highlights  -> "Highlights"
                    Adjustment.Shadows     -> "Shadows"
                    Adjustment.Saturation  -> "Saturation"
                    Adjustment.Tone        -> "Tone"
                    Adjustment.Temperature -> "Temperature"
                }
                val onChange: (Int) -> Unit = when (adj) {
                    Adjustment.Brightness  -> { v -> vm.updateBrightness(v) }
                    Adjustment.Exposure    -> { v -> vm.updateExposure(v) }
                    Adjustment.Contrast    -> { v -> vm.updateContrast(v) }
                    Adjustment.Highlights  -> { v -> vm.updateHighlights(v) }
                    Adjustment.Shadows     -> { v -> vm.updateShadows(v) }
                    Adjustment.Saturation  -> { v -> vm.updateSaturation(v) }
                    Adjustment.Tone        -> { v -> vm.updateTone(v) }
                    Adjustment.Temperature -> { v -> vm.updateTemperature(v) }
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
            // don't kiss the screen edges, but does NOT have its own outer pill (per
            // spec: adjustment chips are individual loose capsules; filter / crop /
            // redact / rotate panels render their own pill recipes inside).
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).wrapContentHeight(),
            ) {
                when (activeTool) {
                    Tool.Adjust -> AdjustPanel(
                        active = activeAdjustment,
                        onSelect = { next ->
                            // Tap-to-toggle: tapping the active chip hides the slider again.
                            activeAdjustment = if (activeAdjustment == next) null else next
                        },
                        onAutoFix = { vm.autoFix() },
                    )
                    Tool.Filter -> FilterPanel(state, vm)
                    Tool.Crop   -> CropPanel(
                        state = state,
                        vm = vm,
                        pendingCropRect = pendingCropRect,
                        onPendingCropRectChange = { pendingCropRect = it },
                    )
                    Tool.Redact -> RedactPanel(state, vm)
                    Tool.Rotate -> RotatePanel(vm)
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

            // Bottom tab bar — ONE pill containing the 5 tab tap targets. Matches
            // GalleryScreen.BottomDock: PillBgOpaque + 0.5.dp PillBorder + pillShape
            // + 4.dp inner padding. Each tab inside is a 44.dp circle that fills with
            // Accent.copy(alpha = 0.22f) when selected.
            Row(
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .background(PillBgOpaque, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tool.entries.forEach { tool ->
                    ToolTab(tool = tool, selected = tool == activeTool, onClick = { activeTool = tool })
                }
            }
        }
    }

    val hasCloudCounterpart by vm.hasCloudCounterpart.collectAsStateWithLifecycle()
    if (showSaveSheet && state.source != null) {
        ModalBottomSheet(
            onDismissRequest = { showSaveSheet = false },
            sheetState = saveSheetState,
            containerColor = PanelBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            SaveSheet(
                source = state.source!!,
                hasCloudCounterpart = hasCloudCounterpart,
                onPicked = { mode ->
                    showSaveSheet = false
                    vm.save(mode)
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
    onPicked: (SaveMode) -> Unit,
    onCancel: () -> Unit,
) {
    val isCloud = source is EditorSource.Cloud
    // Synced photo = device-source + has cloud counterpart. The edit fans out to both
    // sides on save, so the subtitle has to mention "both" instead of just the
    // device-side phrasing.
    val isSynced = !isCloud && hasCloudCounterpart
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 24.dp),
    ) {
        Text(
            "Save edits",
            color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                isCloud  -> "Choose how to save the edited photo to your Drive."
                isSynced -> "Choose how to save the edited photo. Backed-up originals stay in Drive."
                else     -> "Choose how to save the edited photo to your device."
            },
            color = FgMute, fontSize = 13.sp,
        )
        Spacer(Modifier.height(18.dp))

        // Unified with the video editor: only "Save as Copy" is offered. Overwrite for
        // photos used to fall back to "save as copy" when MediaStore refused write
        // permission on foreign URIs anyway, leaving a duplicate next to the original
        // in 60-70% of real-world tries (camera roll, screenshots). Skipping straight
        // to Copy makes the outcome predictable: original always survives, edit lands
        // as a new file next to it (device) or as a new linkId in Drive (cloud).
        SaveOptionRow(
            icon = if (isCloud) Icons.Default.CloudUpload else Icons.Default.ContentCopy,
            title = if (isCloud) "Save as new copy" else "Save as copy",
            subtitle = when {
                isCloud  -> "Uploads as a new photo. The original stays in Drive."
                isSynced -> "Creates a new file in Pictures/Proton Photos AND uploads a paired copy to Drive."
                else     -> "Creates a new file in Pictures/Proton Photos."
            },
            onClick = { onPicked(SaveMode.Copy) },
        )
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
            Text("Cancel", color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SaveOptionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).background(Accent.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = FgMute, fontSize = 12.sp)
        }
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FgPrimary, modifier = Modifier.size(20.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Undo/Redo are placed left of Reset/Save so the user's hand doesn't cross the
            // Save pill while iterating on edits. Tint follows the enabled state, no
            // background change — keeps the bar visually quiet when both are disabled.
            IconBubble(onClick = onUndo, enabled = canUndo) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    "Undo",
                    tint = if (canUndo) FgPrimary else FgDim,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconBubble(onClick = onRedo, enabled = canRedo) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo,
                    "Redo",
                    tint = if (canRedo) FgPrimary else FgDim,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconBubble(onClick = onReset) {
                Icon(Icons.Default.Restore, "Reset", tint = FgDim, modifier = Modifier.size(18.dp))
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
        Text("Save", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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

/** 44.dp circle tap target inside the tab bar pill. Icon-only — labels were
 *  dropped to keep the bar a single thin pill the same height as the photos
 *  page BottomDock. Selected state mirrors that screen: Accent.copy(alpha = 0.22f)
 *  fill + accent-tinted icon. Unselected = transparent + dim icon. */
@Composable
private fun ToolTab(tool: Tool, selected: Boolean, onClick: () -> Unit) {
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
            contentDescription = tool.label,
            tint = if (selected) Accent else FgDim,
            modifier = Modifier.size(22.dp),
        )
    }
}

// ─── Image preview + redact overlay ──────────────────────────────────────────

@Composable
private fun ImageWithRedactOverlay(
    bitmap: Bitmap,
    redactActive: Boolean,
    onStrokeFinished: (RedactionStroke) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var currentStrokeCanvas by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val density = LocalDensity.current
    // Bitmap-coordinate brush size (32 px in screen → scaled to bitmap)
    val brushDpScreen = 28f
    val brushSizePx = with(density) { brushDpScreen.dp.toPx() }

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

        if (redactActive) {
            val fit = remember(bitmap.width, bitmap.height, containerSize) {
                fitRect(bitmap.width.toFloat(), bitmap.height.toFloat(),
                    containerSize.width.toFloat().coerceAtLeast(1f),
                    containerSize.height.toFloat().coerceAtLeast(1f))
            }
            val accentColor = Accent
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(redactActive, bitmap) {
                        detectDragGestures(
                            onDragStart = { offset -> currentStrokeCanvas = listOf(offset) },
                            onDragEnd = {
                                if (currentStrokeCanvas.isNotEmpty()) {
                                    val bmpPoints = currentStrokeCanvas.map { o ->
                                        val bx = ((o.x - fit.offsetX) / fit.scale).coerceIn(0f, bitmap.width.toFloat())
                                        val by = ((o.y - fit.offsetY) / fit.scale).coerceIn(0f, bitmap.height.toFloat())
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
                                currentStrokeCanvas = emptyList()
                            },
                            onDragCancel = { currentStrokeCanvas = emptyList() },
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

private data class FitRect(val scale: Float, val offsetX: Float, val offsetY: Float)

private fun fitRect(bmpW: Float, bmpH: Float, boxW: Float, boxH: Float): FitRect {
    val scale = min(boxW / bmpW, boxH / bmpH)
    val drawnW = bmpW * scale
    val drawnH = bmpH * scale
    return FitRect(scale, (boxW - drawnW) / 2f, (boxH - drawnH) / 2f)
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
    onSelect: (Adjustment) -> Unit,
    onAutoFix: () -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "auto_fix") {
            AdjustCapsulePill(
                label = androidx.compose.ui.res.stringResource(R.string.editor_auto_fix),
                icon = Icons.Default.AutoFixHigh,
                selected = false,
                accentIcon = true,
                onClick = onAutoFix,
            )
        }
        item(key = "brightness") {
            AdjustCapsulePill(
                label = "Brightness",
                icon = Icons.Default.BrightnessMedium,
                selected = active == Adjustment.Brightness,
                onClick = { onSelect(Adjustment.Brightness) },
            )
        }
        item(key = "exposure") {
            AdjustCapsulePill(
                label = "Exposure",
                icon = Icons.Default.AutoFixHigh,
                selected = active == Adjustment.Exposure,
                onClick = { onSelect(Adjustment.Exposure) },
            )
        }
        item(key = "contrast") {
            AdjustCapsulePill(
                label = "Contrast",
                icon = Icons.Default.Contrast,
                selected = active == Adjustment.Contrast,
                onClick = { onSelect(Adjustment.Contrast) },
            )
        }
        item(key = "highlights") {
            AdjustCapsulePill(
                label = "Highlights",
                icon = Icons.Default.Tune,
                selected = active == Adjustment.Highlights,
                onClick = { onSelect(Adjustment.Highlights) },
            )
        }
        item(key = "shadows") {
            AdjustCapsulePill(
                label = "Shadows",
                icon = Icons.Default.Block,
                selected = active == Adjustment.Shadows,
                onClick = { onSelect(Adjustment.Shadows) },
            )
        }
        item(key = "saturation") {
            AdjustCapsulePill(
                label = "Saturation",
                icon = Icons.Default.Palette,
                selected = active == Adjustment.Saturation,
                onClick = { onSelect(Adjustment.Saturation) },
            )
        }
        item(key = "tone") {
            AdjustCapsulePill(
                label = "Tone",
                icon = Icons.Default.SwapHoriz,
                selected = active == Adjustment.Tone,
                onClick = { onSelect(Adjustment.Tone) },
            )
        }
        item(key = "temperature") {
            AdjustCapsulePill(
                label = "Temperature",
                icon = Icons.Default.Brush,
                selected = active == Adjustment.Temperature,
                onClick = { onSelect(Adjustment.Temperature) },
            )
        }
    }
}

/** Individual capsule pill matching the photos page filter row recipe verbatim.
 *  Selected = Accent.copy(alpha = 0.18f) fill (no border, like the gallery's
 *  active filter pill). Unselected = PillBg + 0.5.dp PillBorder. */
@Composable
private fun AdjustCapsulePill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accentIcon: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(38.dp)
            .background(if (selected) Accent.copy(alpha = 0.18f) else PillBg, pillShape)
            .then(if (!selected) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
            .clickable(onClick = onClick)
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Draw over faces or text to hide them",
            color = FgMute, fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ModeChip(
                label = "Black-out",
                icon = Icons.Default.Block,
                selected = modeState == RedactMode.Black,
                onClick = { currentRedactMode = RedactMode.Black; modeState = RedactMode.Black },
                modifier = Modifier.weight(1f),
            )
            ModeChip(
                label = "Pixelate",
                icon = Icons.Default.GridOn,
                selected = modeState == RedactMode.Pixelate,
                onClick = { currentRedactMode = RedactMode.Pixelate; modeState = RedactMode.Pixelate },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ActionChip(
                label = "Undo",
                icon = Icons.AutoMirrored.Filled.Undo,
                enabled = state.adjustments.redactStrokes.isNotEmpty(),
                onClick = { vm.undoLastRedactStroke() },
                modifier = Modifier.weight(1f),
            )
            ActionChip(
                label = "Clear",
                icon = Icons.Default.Restore,
                enabled = state.adjustments.redactStrokes.isNotEmpty(),
                onClick = { vm.clearRedactStrokes() },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RotatePanel(vm: PhotoEditorViewModel) {
    // Center the three actions horizontally so the panel doesn't read as a left-
    // anchored list against the wider editor frame.
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ActionTile("Rotate", Icons.AutoMirrored.Filled.RotateRight) { vm.rotate90Cw() }
        ActionTile("Flip H", Icons.Default.Flip) { vm.toggleFlipH() }
        ActionTile("Flip V", Icons.Default.Flip) { vm.toggleFlipV() }
    }
}

private enum class CropAspect(val label: String, val ratio: Float?) {
    Original("Original", null),
    OneToOne("1:1", 1f),
    FourThree("4:3", 4f / 3f),
    ThreeFour("3:4", 3f / 4f),
    SixteenNine("16:9", 16f / 9f),
    NineSixteen("9:16", 9f / 16f),
}

private fun centeredCrop(srcW: Int, srcH: Int, aspect: Float): android.graphics.Rect {
    val srcRatio = srcW.toFloat() / srcH
    return if (srcRatio > aspect) {
        val newW = (srcH * aspect).toInt().coerceAtLeast(1)
        val xOffset = (srcW - newW) / 2
        android.graphics.Rect(xOffset, 0, xOffset + newW, srcH)
    } else {
        val newH = (srcW / aspect).toInt().coerceAtLeast(1)
        val yOffset = (srcH - newH) / 2
        android.graphics.Rect(0, yOffset, srcW, yOffset + newH)
    }
}

@Composable
private fun CropPanel(
    state: EditorUiState,
    vm: PhotoEditorViewModel,
    pendingCropRect: android.graphics.Rect?,
    onPendingCropRectChange: (android.graphics.Rect?) -> Unit,
) {
    val orig = state.originalBitmap ?: return
    // The chip "selected" indicator compares against the pending rect (what's currently
    // showing in the overlay), not the committed cropRect, so tapping a ratio chip
    // updates the preview immediately and the chip lights up. Original = full-image.
    val pending = pendingCropRect ?: android.graphics.Rect(0, 0, orig.width, orig.height)
    val fullImage = pending.left == 0 && pending.top == 0
        && pending.right == orig.width && pending.bottom == orig.height
    val selected = CropAspect.entries.firstOrNull { aspect ->
        if (aspect.ratio == null) fullImage
        else pending == centeredCrop(orig.width, orig.height, aspect.ratio)
    } ?: if (fullImage) CropAspect.Original else null

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Aspect ratio chips — same pill recipe as the photos page filter row.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(CropAspect.entries.toList()) { aspect ->
                val isSelected = aspect == selected
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .background(
                            if (isSelected) Accent.copy(alpha = 0.18f) else PillBg,
                            pillShape,
                        )
                        .then(if (!isSelected) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
                        .clickable {
                            val newRect = if (aspect.ratio == null)
                                android.graphics.Rect(0, 0, orig.width, orig.height)
                            else
                                centeredCrop(orig.width, orig.height, aspect.ratio)
                            onPendingCropRectChange(newRect)
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        aspect.label,
                        color = if (isSelected) Accent else FgPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Reset = clear the committed crop (and the pending rect snaps back to full).
            ActionChip(
                label = androidx.compose.ui.res.stringResource(R.string.editor_crop_reset),
                icon = Icons.Default.Restore,
                enabled = true,
                onClick = {
                    onPendingCropRectChange(android.graphics.Rect(0, 0, orig.width, orig.height))
                    vm.applyCrop(null)
                },
                modifier = Modifier.weight(1f),
            )
            // Apply = commit the current pending rect via vm.applyCrop(). If the user
            // happens to have it set to the full image we commit null instead so the
            // bitmap pipeline doesn't pointlessly createBitmap() at full size.
            ActionChip(
                label = androidx.compose.ui.res.stringResource(R.string.editor_crop_apply),
                icon = Icons.Default.Check,
                enabled = true,
                onClick = {
                    val rect = pendingCropRect
                    if (rect == null || (rect.left == 0 && rect.top == 0
                            && rect.right == orig.width && rect.bottom == orig.height)) {
                        vm.applyCrop(null)
                    } else {
                        vm.applyCrop(rect)
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Interactive crop overlay rendered on top of the FULL uncropped original. Shows a
 * dark semi-transparent mask outside [cropRect], four corner handles, and accepts
 * pointer input for:
 *   - dragging any corner to resize the rect (clamped to bitmap bounds, min size 32 px)
 *   - dragging inside the rect to translate it (clamped to bitmap bounds)
 * All math is in bitmap-pixel coordinates; the on-screen scale is recomputed from the
 * [Image]'s fit-rect each composition.
 */
@Composable
private fun CropPreview(
    bitmap: Bitmap,
    cropRect: android.graphics.Rect?,
    onCropRectChanged: (android.graphics.Rect) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val handleSizePx = with(LocalDensity.current) { 28.dp.toPx() }
    // 56dp hit-target — fingertip ≈ 38dp, so 28dp landed outside the hit-circle on
    // most touches and fell back to pan-inside instead of resize. The visual marker
    // is still drawn at handleSizePx.
    val touchSlopPx = with(LocalDensity.current) { 56.dp.toPx() }
    val minCropPx = 32f // bitmap-space minimum crop size — prevents zero-area rects

    val rect = cropRect ?: android.graphics.Rect(0, 0, bitmap.width, bitmap.height)

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

        if (containerSize.width > 0 && containerSize.height > 0) {
            val fit = remember(bitmap.width, bitmap.height, containerSize) {
                fitRect(
                    bitmap.width.toFloat(), bitmap.height.toFloat(),
                    containerSize.width.toFloat().coerceAtLeast(1f),
                    containerSize.height.toFloat().coerceAtLeast(1f),
                )
            }
            // Convert canvas-space pixel deltas to bitmap-space.
            val accentColor = Accent
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap, fit) {
                        // dragMode = which corner is being dragged, or "inside" for pan
                        var dragMode: Int = -1 // 0..3 = TL/TR/BL/BR, 4 = inside, -1 = none
                        var startRect = rect
                        detectDragGestures(
                            onDragStart = { offset ->
                                startRect = rect
                                // Resolve which handle (or inside) the user grabbed by
                                // comparing the touch point against the four corner
                                // screen positions; within touchSlopPx counts as a hit.
                                val corners = listOf(
                                    Offset(
                                        fit.offsetX + startRect.left * fit.scale,
                                        fit.offsetY + startRect.top * fit.scale,
                                    ),
                                    Offset(
                                        fit.offsetX + startRect.right * fit.scale,
                                        fit.offsetY + startRect.top * fit.scale,
                                    ),
                                    Offset(
                                        fit.offsetX + startRect.left * fit.scale,
                                        fit.offsetY + startRect.bottom * fit.scale,
                                    ),
                                    Offset(
                                        fit.offsetX + startRect.right * fit.scale,
                                        fit.offsetY + startRect.bottom * fit.scale,
                                    ),
                                )
                                val nearest = corners.withIndex()
                                    .minByOrNull { (_, c) ->
                                        val dx = c.x - offset.x
                                        val dy = c.y - offset.y
                                        dx * dx + dy * dy
                                    }
                                val nearestDist = nearest?.let {
                                    val dx = it.value.x - offset.x
                                    val dy = it.value.y - offset.y
                                    kotlin.math.sqrt(dx * dx + dy * dy)
                                } ?: Float.MAX_VALUE
                                dragMode = if (nearestDist <= touchSlopPx) {
                                    nearest!!.index
                                } else {
                                    // Pan only when the touch is inside the rect.
                                    val leftPx = fit.offsetX + startRect.left * fit.scale
                                    val rightPx = fit.offsetX + startRect.right * fit.scale
                                    val topPx = fit.offsetY + startRect.top * fit.scale
                                    val bottomPx = fit.offsetY + startRect.bottom * fit.scale
                                    if (offset.x in leftPx..rightPx && offset.y in topPx..bottomPx) 4 else -1
                                }
                            },
                            onDrag = { change, drag ->
                                if (dragMode == -1) return@detectDragGestures
                                change.consume()
                                // Convert the screen-space drag delta to bitmap-space.
                                val dxBmp = drag.x / fit.scale
                                val dyBmp = drag.y / fit.scale
                                val r = android.graphics.Rect(rect)
                                when (dragMode) {
                                    0 -> { // TL
                                        r.left = (r.left + dxBmp).toInt().coerceIn(0, r.right - minCropPx.toInt())
                                        r.top  = (r.top  + dyBmp).toInt().coerceIn(0, r.bottom - minCropPx.toInt())
                                    }
                                    1 -> { // TR
                                        r.right = (r.right + dxBmp).toInt().coerceIn(r.left + minCropPx.toInt(), bitmap.width)
                                        r.top   = (r.top   + dyBmp).toInt().coerceIn(0, r.bottom - minCropPx.toInt())
                                    }
                                    2 -> { // BL
                                        r.left   = (r.left   + dxBmp).toInt().coerceIn(0, r.right - minCropPx.toInt())
                                        r.bottom = (r.bottom + dyBmp).toInt().coerceIn(r.top + minCropPx.toInt(), bitmap.height)
                                    }
                                    3 -> { // BR
                                        r.right  = (r.right  + dxBmp).toInt().coerceIn(r.left + minCropPx.toInt(), bitmap.width)
                                        r.bottom = (r.bottom + dyBmp).toInt().coerceIn(r.top + minCropPx.toInt(), bitmap.height)
                                    }
                                    4 -> { // pan inside — clamp the whole rect to bitmap bounds
                                        val w = r.width()
                                        val h = r.height()
                                        val newLeft = (r.left + dxBmp).toInt().coerceIn(0, bitmap.width - w)
                                        val newTop  = (r.top  + dyBmp).toInt().coerceIn(0, bitmap.height - h)
                                        r.set(newLeft, newTop, newLeft + w, newTop + h)
                                    }
                                }
                                onCropRectChanged(r)
                            },
                            onDragEnd = { dragMode = -1 },
                            onDragCancel = { dragMode = -1 },
                        )
                    },
            ) {
                val leftPx = fit.offsetX + rect.left * fit.scale
                val rightPx = fit.offsetX + rect.right * fit.scale
                val topPx = fit.offsetY + rect.top * fit.scale
                val bottomPx = fit.offsetY + rect.bottom * fit.scale
                val maskColor = Color.Black.copy(alpha = 0.55f)
                // Four dark rectangles around the crop rect — top / bottom / left / right.
                // Cheaper and clearer than a PorterDuff masking dance for a static overlay.
                drawRect(maskColor, topLeft = Offset(0f, 0f),
                    size = GSize(size.width, topPx.coerceAtLeast(0f)))
                drawRect(maskColor, topLeft = Offset(0f, bottomPx.coerceAtMost(size.height)),
                    size = GSize(size.width, (size.height - bottomPx).coerceAtLeast(0f)))
                drawRect(maskColor, topLeft = Offset(0f, topPx.coerceAtLeast(0f)),
                    size = GSize(leftPx.coerceAtLeast(0f), (bottomPx - topPx).coerceAtLeast(0f)))
                drawRect(maskColor, topLeft = Offset(rightPx.coerceAtMost(size.width), topPx.coerceAtLeast(0f)),
                    size = GSize((size.width - rightPx).coerceAtLeast(0f), (bottomPx - topPx).coerceAtLeast(0f)))

                // Border + rule-of-thirds gridlines.
                drawRect(
                    color = accentColor,
                    topLeft = Offset(leftPx, topPx),
                    size = GSize(rightPx - leftPx, bottomPx - topPx),
                    style = Stroke(width = 2f),
                )
                val w = rightPx - leftPx
                val h = bottomPx - topPx
                val gridColor = Color.White.copy(alpha = 0.35f)
                for (i in 1..2) {
                    drawLine(
                        gridColor,
                        Offset(leftPx + w * i / 3f, topPx),
                        Offset(leftPx + w * i / 3f, bottomPx),
                        strokeWidth = 1f,
                    )
                    drawLine(
                        gridColor,
                        Offset(leftPx, topPx + h * i / 3f),
                        Offset(rightPx, topPx + h * i / 3f),
                        strokeWidth = 1f,
                    )
                }

                // Corner handles — small white squares centered on each corner.
                val half = handleSizePx / 2f
                listOf(
                    Offset(leftPx, topPx),
                    Offset(rightPx, topPx),
                    Offset(leftPx, bottomPx),
                    Offset(rightPx, bottomPx),
                ).forEach { c ->
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(c.x - half, c.y - half),
                        size = GSize(handleSizePx, handleSizePx),
                    )
                    drawRect(
                        color = accentColor,
                        topLeft = Offset(c.x - half, c.y - half),
                        size = GSize(handleSizePx, handleSizePx),
                        style = Stroke(width = 2f),
                    )
                }
            }
        }
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
            filter.displayName,
            color = if (selected) Accent else FgMute,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Rotate / Flip pills — match the photos page filter row recipe so the bar above
 *  the tab dock reads as the same kind of control regardless of which tool is
 *  active. Horizontal capsule rather than the old tall square tile. */
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
private fun ActionChip(
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
}
