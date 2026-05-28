package eu.akoos.photos.presentation.settings

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.AppColorsTokens
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBorder

private val cardShape = RoundedCornerShape(12.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    var showDeviceEmptyDialog   by remember { mutableStateOf(false) }
    var showDeviceRestoreDialog by remember { mutableStateOf(false) }

    val deviceRestoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeviceActionCompleted()
    }
    val deviceDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeviceActionCompleted()
    }

    fun launchDeviceRestore() {
        val pi = viewModel.buildRestoreDeviceIntent() ?: return
        deviceRestoreLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }
    fun launchDeviceDeleteForever() {
        val pi = viewModel.buildDeleteDeviceForeverIntent() ?: return
        deviceDeleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(colors.surfaceWeak, CircleShape)
                    .border(0.5.dp, PillBorder, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                    tint = FgDim, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Recently Deleted",
                    color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                )
                if (!state.deviceLoading && state.deviceItems.isNotEmpty()) {
                    Text(
                        "${state.deviceItems.size} photo${if (state.deviceItems.size != 1) "s" else ""}",
                        color = FgMute, fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(36.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.deviceLoading) {
                items(6) {
                    eu.akoos.photos.presentation.common.ShimmerSquare(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 4.dp,
                    )
                }
            } else if (state.apiUnsupported) {
                item(span = { GridItemSpan(3) }) {
                    TrashInfoRow("Device trash requires Android 11+", FgMute)
                }
            } else if (state.deviceItems.isEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🗑️", fontSize = 40.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No recently deleted photos",
                                color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Photos deleted within the last 30 days\nwill appear here",
                                color = FgMute, fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                item(span = { GridItemSpan(3) }) {
                    TrashActionBar(
                        colors           = colors,
                        selectionMode    = state.isDeviceSelectionMode,
                        selectedCount    = state.deviceSelectedCount,
                        totalCount       = state.deviceItems.size,
                        allSelected      = state.deviceAllSelected,
                        onRestoreClick   = { showDeviceRestoreDialog = true },
                        onDeleteClick    = { showDeviceEmptyDialog = true },
                        onSelectAll      = { viewModel.selectAllDevice() },
                        onDeselectAll    = { viewModel.clearDeviceSelection() },
                        restoreLabel     = "Restore",
                        deleteLabel      = "Empty Trash",
                    )
                }
                items(state.deviceItems, key = { "dev_${it.uri}" }) { item ->
                    val selected = item.uri in state.selectedDeviceUris
                    TrashPhotoCell(
                        colors          = colors,
                        selected        = selected,
                        inSelectionMode = state.isDeviceSelectionMode,
                        onClick         = { if (state.isDeviceSelectionMode) viewModel.toggleDeviceSelection(item.uri) },
                        onLongClick     = { viewModel.toggleDeviceSelection(item.uri) },
                    ) {
                        AsyncImage(
                            model = Uri.parse(item.uri),
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    if (showDeviceRestoreDialog) {
        val n = if (state.isDeviceSelectionMode) state.deviceSelectedCount else state.deviceItems.size
        AlertDialog(
            onDismissRequest = { showDeviceRestoreDialog = false },
            containerColor = colors.cardBg,
            titleContentColor = colors.fgPrimary,
            title = { Text("Restore $n photo${if (n != 1) "s" else ""}?", fontWeight = FontWeight.SemiBold) },
            text = { Text("They will be restored to your device gallery.", color = colors.fgDim, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { showDeviceRestoreDialog = false; launchDeviceRestore() }) {
                    Text("Restore", color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceRestoreDialog = false }) { Text("Cancel", color = colors.fgDim) }
            },
        )
    }

    if (showDeviceEmptyDialog) {
        val n = if (state.isDeviceSelectionMode) state.deviceSelectedCount else state.deviceItems.size
        AlertDialog(
            onDismissRequest = { showDeviceEmptyDialog = false },
            containerColor = colors.cardBg,
            titleContentColor = colors.fgPrimary,
            title = { Text("Delete $n photo${if (n != 1) "s" else ""} forever?", fontWeight = FontWeight.SemiBold) },
            text = { Text("Cannot be undone. Photos will be permanently removed from this device.",
                color = colors.fgDim, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { showDeviceEmptyDialog = false; launchDeviceDeleteForever() }) {
                    Text("Delete forever", color = ErrorColor, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceEmptyDialog = false }) { Text("Cancel", color = colors.fgDim) }
            },
        )
    }

}

@Composable
private fun TrashInfoRow(text: String, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 13.sp)
    }
}

@Composable
private fun TrashActionBar(
    colors: AppColorsTokens,
    selectionMode: Boolean,
    selectedCount: Int,
    totalCount: Int,
    allSelected: Boolean,
    onRestoreClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    restoreLabel: String,
    deleteLabel: String,
) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
        Text(
            "$totalCount item${if (totalCount != 1) "s" else ""}",
            color = colors.fgMute, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(colors.cardBg, RoundedCornerShape(10.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(10.dp))
                    .clickable(onClick = onRestoreClick)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selectionMode) "$restoreLabel ($selectedCount)" else restoreLabel,
                    color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(colors.deleteTint, RoundedCornerShape(10.dp))
                    .border(0.5.dp, colors.errorColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .clickable(onClick = onDeleteClick)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selectionMode) "$deleteLabel ($selectedCount)" else deleteLabel,
                    color = colors.errorColor, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Text("$selectedCount selected", color = colors.fgDim, fontSize = 12.sp)
                Text(
                    if (allSelected) "Deselect all" else "Select all",
                    color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { if (allSelected) onDeselectAll() else onSelectAll() },
                )
            } else {
                Text("Long-press to select", color = colors.fgMute, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashPhotoCell(
    colors: AppColorsTokens,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.bg2)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.border(2.dp, colors.accent, RoundedCornerShape(8.dp)) else Modifier),
    ) {
        content()
        if (inSelectionMode) {
            Box(Modifier.padding(4.dp).size(20.dp).align(Alignment.TopStart)) {
                if (selected) {
                    Box(
                        Modifier.fillMaxSize().background(colors.accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                } else {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color.Black.copy(0.3f), CircleShape)
                            .border(1.5.dp, Color.White.copy(0.8f), CircleShape),
                    )
                }
            }
        }
    }
}
