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

package eu.akoos.photos.presentation.settings

import android.app.Activity
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.CloudTrashItem
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.EmptyState
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.AppColorsTokens
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

private val pillShape = RoundedCornerShape(999.dp)

/** Which trash bucket the user is currently viewing. The header pill toggles between
 *  these; selection state and action pills always apply to the active tab only. */
enum class TrashTab { Device, Cloud }

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
    var showCloudEmptyDialog    by remember { mutableStateOf(false) }
    var showCloudRestoreDialog  by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(TrashTab.Device) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.loadCloudTrash() }

    LaunchedEffect(state.cloud.toastMessage) {
        val msg = state.cloud.toastMessage
        if (msg != null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeCloudToast()
        }
    }

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
            IconBubble(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.onboarding_back),
                onClick = onBack,
                diameter = 40.dp,
                iconSize = 18.dp,
                background = colors.surfaceWeak,
                borderColor = PillBorder,
                tint = FgDim,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.trash_title),
                color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(36.dp))
        }

        // ── Pill rail: tab toggle + action pills ──────────────────────────────────
        val activeIsDevice = selectedTab == TrashTab.Device
        val activeHasItems = if (activeIsDevice) {
            !state.device.apiUnsupported && state.device.items.isNotEmpty()
        } else {
            state.cloud.items.isNotEmpty()
        }
        val activeSelectionMode = if (activeIsDevice) state.device.isSelectionMode else state.cloud.isSelectionMode
        val activeSelectedCount = if (activeIsDevice) state.device.selectedCount else state.cloud.selectedCount

        // ── Pill rail — same padding/spacing as the Photos + Shared rails ─────
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 8.dp),
        ) {
            // Toggle pill — flips between Device and Cloud, mirroring the Shared albums pattern.
            // Fixed-width inner row so swapping labels (Device ↔ Cloud) doesn't reflow the
            // siblings to the right.
            item(key = "trash_tab_toggle") {
                Row(
                    modifier = Modifier
                        .height(38.dp)
                        .background(colors.chipSelectedBg, pillShape)
                        .clickable {
                            selectedTab = if (activeIsDevice) TrashTab.Cloud else TrashTab.Device
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (activeIsDevice) Icons.Default.PhoneAndroid else Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(
                            if (activeIsDevice) R.string.trash_section_device else R.string.trash_section_cloud,
                        ),
                        color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                    Icon(
                        Icons.Default.SwapHoriz, contentDescription = null,
                        tint = FgDim, modifier = Modifier.size(14.dp),
                    )
                }
            }
            // Restore pill — icon-only, confirmation dialog gates the action for both tabs.
            item(key = "trash_restore") {
                TrashPillButton(
                    icon = Icons.Default.Restore,
                    contentDescription = stringResource(R.string.trash_restore),
                    enabled = activeHasItems,
                    accent = colors.accent,
                    onClick = {
                        if (activeIsDevice) showDeviceRestoreDialog = true
                        else showCloudRestoreDialog = true
                    },
                )
            }
            // Empty trash pill — icon-only, destructive red variant.
            item(key = "trash_empty") {
                TrashPillButton(
                    icon = Icons.Default.DeleteForever,
                    contentDescription = stringResource(R.string.trash_empty),
                    enabled = activeHasItems,
                    accent = colors.errorColor,
                    onClick = {
                        if (activeIsDevice) showDeviceEmptyDialog = true
                        else showCloudEmptyDialog = true
                    },
                )
            }
            // Select-all toggle pill — only present in selection mode, appended to the end
            // of the rail so the grid below never shifts when selection toggles on/off.
            if (activeHasItems && activeSelectionMode) {
                item(key = "trash_select_all") {
                    val allSel = if (activeIsDevice) state.device.allSelected else state.cloud.allSelected
                    TrashPillButton(
                        icon = if (allSel) Icons.Default.Deselect else Icons.Default.SelectAll,
                        contentDescription = if (allSel) stringResource(R.string.gallery_deselect_all) else stringResource(R.string.select_all),
                        enabled = true,
                        accent = colors.accent,
                        onClick = {
                            if (activeIsDevice) {
                                if (allSel) viewModel.clearDeviceSelection() else viewModel.selectAllDevice()
                            } else {
                                if (allSel) viewModel.clearCloudSelection() else viewModel.selectAllCloud()
                            }
                        },
                    )
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (activeIsDevice) {
                // ── Device tab content ────────────────────────────────────────
                when {
                    state.device.isLoading -> {
                        items(6) {
                            eu.akoos.photos.presentation.common.ShimmerSquare(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 4.dp,
                            )
                        }
                    }
                    state.device.apiUnsupported -> {
                        item(span = { GridItemSpan(3) }) {
                            TrashInfoRow(stringResource(R.string.trash_device_unsupported), FgMute)
                        }
                    }
                    state.device.items.isEmpty() -> {
                        item(span = { GridItemSpan(3) }) {
                            EmptyState(
                                title = stringResource(R.string.trash_device_empty_title),
                                subtitle = stringResource(R.string.trash_device_empty_subtitle),
                                icon = Icons.Outlined.Delete,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
                            )
                        }
                    }
                    else -> {
                        items(state.device.items, key = { "dev_${it.uri}" }) { item ->
                            val selected = item.uri in state.device.selectedUris
                            TrashPhotoCell(
                                colors          = colors,
                                selected        = selected,
                                inSelectionMode = state.device.isSelectionMode,
                                onClick         = { if (state.device.isSelectionMode) viewModel.toggleDeviceSelection(item.uri) },
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
            } else {
                // ── Cloud tab content ─────────────────────────────────────────
                when {
                    state.cloud.isLoading && state.cloud.items.isEmpty() -> {
                        items(6, key = { idx -> "cloud_shimmer_$idx" }) {
                            eu.akoos.photos.presentation.common.ShimmerSquare(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 4.dp,
                            )
                        }
                    }
                    state.cloud.errorMessage != null && state.cloud.items.isEmpty() -> {
                        item(span = { GridItemSpan(3) }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 32.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    stringResource(R.string.trash_cloud_error),
                                    color = colors.fgMute, fontSize = 13.sp,
                                )
                                Spacer(Modifier.height(10.dp))
                                Box(
                                    modifier = Modifier
                                        .background(colors.cardBg, RoundedCornerShape(10.dp))
                                        .border(0.5.dp, colors.cardBorder, RoundedCornerShape(10.dp))
                                        .clickable { viewModel.loadCloudTrash(forceRefresh = true) }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.trash_cloud_retry),
                                        color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                    state.cloud.items.isEmpty() -> {
                        item(span = { GridItemSpan(3) }) {
                            EmptyState(
                                title = stringResource(R.string.trash_cloud_empty),
                                icon = Icons.Outlined.Delete,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
                            )
                        }
                    }
                    else -> {
                        items(state.cloud.items, key = { "cloud_${it.linkId}" }) { item ->
                            val selected = item.linkId in state.cloud.selectedLinkIds
                            val decryptedUri = state.cloud.decryptedThumbnails[item.linkId]
                            TrashPhotoCell(
                                colors          = colors,
                                selected        = selected,
                                inSelectionMode = state.cloud.isSelectionMode,
                                onClick         = { if (state.cloud.isSelectionMode) viewModel.toggleCloudSelection(item.linkId) },
                                onLongClick     = { viewModel.toggleCloudSelection(item.linkId) },
                            ) {
                                CloudTrashThumbnail(
                                    item = item,
                                    decryptedUri = decryptedUri,
                                    onRequestDecrypt = { viewModel.requestCloudThumbnail(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeviceRestoreDialog) {
        val n = if (state.device.isSelectionMode) state.device.selectedCount else state.device.items.size
        ConfirmDialog(
            title = pluralStringResource(R.plurals.trash_restore_title, n, n),
            message = stringResource(R.string.trash_restore_device_message),
            confirmLabel = stringResource(R.string.trash_restore),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showDeviceRestoreDialog = false; launchDeviceRestore() },
            onDismiss = { showDeviceRestoreDialog = false },
        )
    }

    if (showDeviceEmptyDialog) {
        val n = if (state.device.isSelectionMode) state.device.selectedCount else state.device.items.size
        ConfirmDialog(
            title = pluralStringResource(R.plurals.trash_delete_forever_title, n, n),
            message = stringResource(R.string.trash_delete_forever_message),
            confirmLabel = stringResource(R.string.trash_delete_forever_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showDeviceEmptyDialog = false; launchDeviceDeleteForever() },
            onDismiss = { showDeviceEmptyDialog = false },
            destructive = true,
        )
    }

    if (showCloudRestoreDialog) {
        val n = if (state.cloud.isSelectionMode) state.cloud.selectedCount else state.cloud.items.size
        ConfirmDialog(
            title = pluralStringResource(R.plurals.trash_restore_title, n, n),
            message = stringResource(R.string.trash_restore_cloud_message),
            confirmLabel = stringResource(R.string.trash_restore),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showCloudRestoreDialog = false
                viewModel.restoreSelectedCloud()
            },
            onDismiss = { showCloudRestoreDialog = false },
        )
    }

    if (showCloudEmptyDialog) {
        val n = if (state.cloud.isSelectionMode) state.cloud.selectedCount else state.cloud.items.size
        ConfirmDialog(
            title = stringResource(R.string.trash_cloud_empty_confirm_title),
            message = stringResource(R.string.trash_cloud_empty_confirm_message, n),
            confirmLabel = stringResource(R.string.trash_cloud_empty_confirm_action),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showCloudEmptyDialog = false
                viewModel.emptyCloudSelected()
            },
            onDismiss = { showCloudEmptyDialog = false },
            destructive = true,
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


/**
 * Cloud-trash grid cell content. When the ViewModel has already decrypted this entry's
 * thumbnail to a local file, render the JPEG via Coil. Otherwise show a placeholder
 * icon + year-month label and kick off a debounced decrypt request — same lazy pattern
 * the main gallery uses for cloud-only photos.
 */
@Composable
private fun CloudTrashThumbnail(
    item: CloudTrashItem,
    decryptedUri: String?,
    onRequestDecrypt: () -> Unit,
) {
    val colors = AppColors.current
    if (decryptedUri != null) {
        AsyncImage(
            model = Uri.parse(decryptedUri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    // Debounce so a fast scroll past the cell doesn't burn a decrypt job we'll never
    // see the result of. 120 ms matches the gallery's cell observation window.
    LaunchedEffect(item.linkId) {
        kotlinx.coroutines.delay(120)
        onRequestDecrypt()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg2),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Image,
            contentDescription = null,
            tint = colors.fgMute,
            modifier = Modifier.size(28.dp),
        )
        val ts = item.captureTime
        if (ts != null) {
            val label = remember(ts) {
                val ms = if (ts < 1_000_000_000_000L) ts * 1000L else ts
                java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault()).format(java.util.Date(ms))
            }
            Text(
                label,
                color = colors.fgMute, fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }
    }
}

/**
 * Pill-shaped action button styled to sit on the trash screen's top rail next to the
 * tab toggle. Matches the Shared albums filter rail visual language: 38 dp height,
 * full pill rounding, accent-tinted icon + label. Disabled state dims everything and
 * suppresses clicks.
 */
@Composable
private fun TrashPillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(PillBg, pillShape)
            .border(0.5.dp, PillBorder, pillShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = contentDescription,
            tint = accent.copy(alpha = alpha),
            modifier = Modifier.size(18.dp),
        )
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
