package me.proton.photos.presentation.editor

import me.proton.photos.R

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
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
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.presentation.theme.Accent
import me.proton.photos.presentation.theme.Bg0
import me.proton.photos.presentation.theme.FgDim
import me.proton.photos.presentation.theme.FgMute
import me.proton.photos.presentation.theme.FgPrimary
import me.proton.photos.presentation.theme.PanelBg
import me.proton.photos.presentation.theme.PanelChip
import me.proton.photos.presentation.theme.TrackBg
import kotlin.math.max
import kotlin.math.min

private enum class Tool(val label: String, val icon: ImageVector) {
    Adjust("Adjust", Icons.Default.Tune),
    Filter("Filter", Icons.Default.AutoFixHigh),
    Crop("Crop", Icons.Default.Crop),
    Redact("Redact", Icons.Default.Brush),
    Rotate("Rotate", Icons.AutoMirrored.Filled.RotateRight),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditorScreen(
    localUri: String? = null,
    localDisplayName: String? = null,
    localMimeType: String? = null,
    cloudPhoto: CloudPhoto? = null,
    /** If the photo was opened from an album, the new cloud uploads are added to it too. */
    sourceAlbumLinkId: String? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    vm: PhotoEditorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var activeTool by remember { mutableStateOf(Tool.Adjust) }
    var showSaveSheet by remember { mutableStateOf(false) }
    val saveSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Inform the VM about the source album so save() can re-attach the new linkId.
    remember(sourceAlbumLinkId) { vm.setSourceAlbumLinkId(sourceAlbumLinkId); Unit }

    // Load source once.
    remember(localUri, cloudPhoto?.linkId) {
        when {
            cloudPhoto != null -> vm.loadCloud(cloudPhoto)
            localUri != null   -> vm.loadLocal(localUri, localDisplayName ?: "photo.jpg", localMimeType ?: "image/jpeg")
        }
        Unit
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

    // Navigate away after a successful save. SuccessAsCopy gets a toast so the user knows
    // the original wasn't replaced — without it the user would think Overwrite "did nothing"
    // because the photo on screen (the original) looks the same after navigating back.
    val saveContext = androidx.compose.ui.platform.LocalContext.current
    remember(state.saveResult) {
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
        Unit
    }


    Column(Modifier.fillMaxSize().background(Bg0).statusBarsPadding()) {
        TopBar(
            isSaving = state.isSaving,
            onBack = onBack,
            onReset = { vm.resetAll() },
            onSave = { showSaveSheet = true },
        )

        // ── Preview area ─────────────────────────────────────────────────────
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(color = Accent)
                state.errorMessage != null -> Text(state.errorMessage!!, color = FgMute)
                state.previewBitmap != null -> ImageWithRedactOverlay(
                    bitmap = state.previewBitmap!!,
                    redactActive = activeTool == Tool.Redact,
                    onStrokeFinished = { stroke -> vm.addRedactStroke(stroke) },
                )
            }
        }

        // ── Bottom sheet panel ──────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(PanelBg)
                .navigationBarsPadding()
                .padding(top = 14.dp, bottom = 14.dp),
        ) {
            // Tool tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Tool.entries.forEach { tool ->
                    ToolTab(tool = tool, selected = tool == activeTool, onClick = { activeTool = tool })
                }
            }

            Spacer(Modifier.height(14.dp))

            // Tool panel — wrap content height for a tighter feel
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).wrapContentHeight(),
            ) {
                when (activeTool) {
                    Tool.Adjust -> AdjustPanel(state, vm)
                    Tool.Filter -> FilterPanel(state, vm)
                    Tool.Crop   -> CropPanel(state, vm)
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
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 18.dp, end = 18.dp),
                )
            }
        }
    }

    if (showSaveSheet && state.source != null) {
        ModalBottomSheet(
            onDismissRequest = { showSaveSheet = false },
            sheetState = saveSheetState,
            containerColor = PanelBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            SaveSheet(
                source = state.source!!,
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
private fun SaveSheet(source: EditorSource, onPicked: (SaveMode) -> Unit, onCancel: () -> Unit) {
    val isCloud = source is EditorSource.Cloud
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 24.dp),
    ) {
        Text(
            "Save edits",
            color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (isCloud) "Choose how to save the edited photo to your Drive."
                else "Choose how to save the edited photo to your device.",
            color = FgMute, fontSize = 13.sp,
        )
        Spacer(Modifier.height(18.dp))

        SaveOptionRow(
            icon = if (isCloud) Icons.Default.SwapHoriz else Icons.Default.SaveAlt,
            title = if (isCloud) "Replace cloud photo" else "Overwrite original",
            subtitle = if (isCloud)
                "Uploads the edit, then moves the original to Recently Deleted."
            else
                "Saves on top of the original file on this device.",
            onClick = { onPicked(SaveMode.Overwrite) },
        )
        Spacer(Modifier.height(10.dp))
        SaveOptionRow(
            icon = if (isCloud) Icons.Default.CloudUpload else Icons.Default.ContentCopy,
            title = if (isCloud) "Save as new copy" else "Save as copy",
            subtitle = if (isCloud)
                "Uploads as a new photo. The original stays in Drive."
            else
                "Creates a new file in Pictures/Proton Photos.",
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
private fun TopBar(isSaving: Boolean, onBack: () -> Unit, onReset: () -> Unit, onSave: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FgPrimary, modifier = Modifier.size(20.dp))
        }
        Text("Edit", color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun ToolTab(tool: Tool, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = tool.icon,
            contentDescription = tool.label,
            tint = if (selected) Accent else FgDim,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            tool.label,
            color = if (selected) Accent else FgDim,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
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

@Composable
private fun AdjustPanel(state: EditorUiState, vm: PhotoEditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SliderRow("Brightness", state.adjustments.brightness, onChange = { vm.updateBrightness(it) }, onChangeFinished = { vm.finalizeAdjustments() })
        SliderRow("Contrast", state.adjustments.contrast, onChange = { vm.updateContrast(it) }, onChangeFinished = { vm.finalizeAdjustments() })
        SliderRow("Saturation", state.adjustments.saturation, onChange = { vm.updateSaturation(it) }, onChangeFinished = { vm.finalizeAdjustments() })
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
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
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
private fun CropPanel(state: EditorUiState, vm: PhotoEditorViewModel) {
    val orig = state.originalBitmap ?: return
    val current = state.adjustments.cropRect
    val selected = CropAspect.entries.firstOrNull { aspect ->
        if (aspect.ratio == null) current == null
        else current != null && current == centeredCrop(orig.width, orig.height, aspect.ratio)
    } ?: CropAspect.Original

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(CropAspect.entries.toList()) { aspect ->
            val isSelected = aspect == selected
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) Accent.copy(alpha = 0.22f) else PanelChip)
                    .clickable {
                        if (aspect.ratio == null) vm.applyCrop(null)
                        else vm.applyCrop(centeredCrop(orig.width, orig.height, aspect.ratio))
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    aspect.label,
                    color = if (isSelected) Accent else FgPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
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
    val trackHeightPx = with(density) { 4.dp.toPx() }
    val thumbRadiusPx = with(density) { 9.dp.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val normalized = (value + 100) / 200f // 0..1
    val accentColor = Accent
    val fgDimColor = FgDim
    val trackColor = TrackBg
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(value.toString(), color = FgDim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val pct = (o.x / size.width).coerceIn(0f, 1f)
                            onChange((pct * 200 - 100).toInt())
                        },
                        onDrag = { change, _ ->
                            val pct = (change.position.x / size.width).coerceIn(0f, 1f)
                            onChange((pct * 200 - 100).toInt())
                            change.consume()
                        },
                        onDragEnd = { onChangeFinished() },
                        onDragCancel = { onChangeFinished() },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { o ->
                            val pct = (o.x / size.width).coerceIn(0f, 1f)
                            onChange((pct * 200 - 100).toInt())
                            onChangeFinished()
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
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PanelChip),
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

@Composable
private fun ActionTile(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .background(PanelChip)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(icon, label, tint = FgPrimary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = FgPrimary, fontSize = 12.sp)
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
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.22f) else PanelChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = if (selected) Accent else FgPrimary, modifier = Modifier.size(18.dp))
        Text(label, color = if (selected) Accent else FgPrimary, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
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
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PanelChip)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = if (enabled) FgPrimary else FgDim.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
        Text(label, color = if (enabled) FgPrimary else FgDim.copy(alpha = 0.4f), fontSize = 13.sp)
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
