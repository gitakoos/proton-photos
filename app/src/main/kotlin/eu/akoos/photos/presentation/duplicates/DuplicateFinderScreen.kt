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

package eu.akoos.photos.presentation.duplicates

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.FindDuplicatesUseCase
import eu.akoos.photos.domain.usecase.FindDuplicatesUseCase.DuplicateGroup
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.memories.FloatingMemoriesHeader
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * Phase 1 EXACT-duplicate review screen. Lists groups of byte-identical photos (device and cloud
 * kept separate, synced pairs excluded) and lets the user keep exactly one copy of a group and
 * delete the rest. Keeping exactly one is the whole safety model here: the keep selection always
 * has a value (defaults to the first copy) and can never be emptied, so a group can never be wiped.
 */
@Composable
fun DuplicateFinderScreen(
    onBack: () -> Unit,
    onOpenViewer: (items: List<GalleryItem>, index: Int) -> Unit = { _, _ -> },
    viewModel: DuplicateFinderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // System trash dialog launcher for the device-delete path (Android 11+ asks the user).
    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeletePermissionGranted()
        else viewModel.clearPendingDeleteIntent()
    }
    LaunchedEffect(state.pendingDeleteIntent) {
        val pi = state.pendingDeleteIntent ?: return@LaunchedEffect
        deletePermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }
    val deleteFailed = stringResource(R.string.duplicates_delete_failed)
    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) {
            Toast.makeText(context, deleteFailed, Toast.LENGTH_LONG).show()
            viewModel.consumeError()
        }
    }

    val colors = AppColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        // Reserve room for the floating header: the status-bar inset + the pill-row height, so the
        // first row sits clear of the pills and the rest scrolls under them.
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentTopPad = floatingHeaderContentTopPadding()

        var filter by rememberSaveable { mutableStateOf(DupFilter.ALL) }

        val deviceGroups = state.deviceGroups
        val cloudGroups = state.cloudGroups
        val similarGroups = state.similarDeviceGroups + state.similarCloudGroups
        val hasExact = deviceGroups.isNotEmpty() || cloudGroups.isNotEmpty()
        val hasSimilar = similarGroups.isNotEmpty() || state.scanningSimilar
        val hasAnyContent = hasExact || hasSimilar
        // ALL (the default) shows both kinds; the other two narrow the list to one kind.
        val showExact = filter != DupFilter.SIMILAR
        val showSim = filter != DupFilter.IDENTICAL

        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(top = contentTopPad),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
            }
            // Nothing visible for the active filter → a centered empty state.
            !(showExact && hasExact) && !(showSim && hasSimilar) ->
                DupEmptyState(stringResource(R.string.duplicates_empty), contentTopPad)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, contentTopPad, 16.dp, 24.dp + navBottom),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showExact) {
                    if (deviceGroups.isNotEmpty()) {
                        item("h-device") { SectionLabel(stringResource(R.string.duplicates_section_device)) }
                        items(deviceGroups, key = { "d-" + it.items.first().stableId }) { group ->
                            DuplicateGroupCard(group, similar = false, state.isDeleting, viewModel::deleteExtras,
                                onOpenViewer, viewModel::requestThumbnailDecrypt, viewModel::cancelThumbnailDecrypt)
                        }
                    }
                    if (cloudGroups.isNotEmpty()) {
                        item("h-cloud") { SectionLabel(stringResource(R.string.duplicates_section_cloud)) }
                        items(cloudGroups, key = { "c-" + it.items.first().stableId }) { group ->
                            DuplicateGroupCard(group, similar = false, state.isDeleting, viewModel::deleteExtras,
                                onOpenViewer, viewModel::requestThumbnailDecrypt, viewModel::cancelThumbnailDecrypt)
                        }
                    }
                }
                if (showSim) {
                    if (similarGroups.isNotEmpty()) {
                        item("h-similar") { SectionLabel(stringResource(R.string.duplicates_section_similar)) }
                        item("hint-similar") {
                            Text(
                                stringResource(R.string.duplicates_similar_hint),
                                color = FgMute, fontSize = 12.sp,
                            )
                        }
                        items(similarGroups, key = { "s-" + it.type + "-" + it.items.first().stableId }) { group ->
                            DuplicateGroupCard(group, similar = true, state.isDeleting, viewModel::deleteExtras,
                                onOpenViewer, viewModel::requestThumbnailDecrypt, viewModel::cancelThumbnailDecrypt)
                        }
                    }
                    if (state.scanningSimilar) {
                        item("scan-similar") { ScanningRow() }
                    }
                }
            }
        }

        // Floating pill header (matches Search / Map). The trailing slot carries the type filter:
        // Identical (byte-identical copies) vs Similar (near-duplicates).
        val filterTrailing: (@Composable () -> Unit)? =
            if (!state.isLoading && hasAnyContent) {
                { DupFilterMenu(filter = filter, onChange = { filter = it }) }
            } else {
                null
            }
        FloatingMemoriesHeader(
            title = stringResource(R.string.duplicates_title),
            onBack = onBack,
            trailing = filterTrailing,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = FgMute, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun DupEmptyState(text: String, topPad: Dp) {
    Box(
        Modifier.fillMaxSize().padding(top = topPad),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ContentCopy, null, tint = FgMute, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(16.dp))
            Text(text, color = FgDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 40.dp))
        }
    }
}

/** The duplicate-list filter modes. ALL (the default) shows exact and similar together. */
private enum class DupFilter { ALL, IDENTICAL, SIMILAR }

/** A filter icon in the header's trailing slot that opens a small menu to switch the list between
 *  All, Identical (byte-identical copies) and Similar (near-duplicates). The active mode is checked. */
@Composable
private fun DupFilterMenu(filter: DupFilter, onChange: (DupFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBubble(
            icon = Icons.Default.FilterList,
            contentDescription = stringResource(R.string.filter_title),
            onClick = { open = true },
            tint = Accent,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DupFilterItem(stringResource(R.string.gallery_filter_all), filter == DupFilter.ALL) {
                onChange(DupFilter.ALL); open = false
            }
            DupFilterItem(stringResource(R.string.duplicates_filter_identical), filter == DupFilter.IDENTICAL) {
                onChange(DupFilter.IDENTICAL); open = false
            }
            DupFilterItem(stringResource(R.string.duplicates_filter_similar), filter == DupFilter.SIMILAR) {
                onChange(DupFilter.SIMILAR); open = false
            }
        }
    }
}

@Composable
private fun DupFilterItem(label: String, active: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = if (active) {
            { Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(18.dp)) }
        } else null,
        onClick = onClick,
    )
}

/** A small inline row shown while the on-the-fly perceptual pass is still computing. */
@Composable
private fun ScanningRow() {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.duplicates_scanning), color = FgDim, fontSize = 13.sp)
    }
}

/**
 * One duplicate group. Holds the multi-copy "keep" selection (defaults to the first/oldest copy) so
 * the action below can only ever delete (group − the kept copies) — never the whole group. The keep
 * set is never allowed to empty, and "Delete the others" is disabled when every copy is kept.
 */
@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    similar: Boolean,
    isDeleting: Boolean,
    onDeleteExtras: (DuplicateGroup, Set<String>) -> Unit,
    onOpenViewer: (items: List<GalleryItem>, index: Int) -> Unit,
    requestDecrypt: (String) -> Unit,
    cancelDecrypt: (String) -> Unit,
) {
    var keepIds by remember(group) { mutableStateOf(setOf(group.items.first().stableId)) }
    var showConfirm by remember(group) { mutableStateOf(false) }
    val nothingToDelete = keepIds.size == group.items.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PillBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (similar) stringResource(R.string.duplicates_similar_count, group.items.size)
            else stringResource(R.string.duplicates_copies, group.items.size),
            color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
        )
        Text(stringResource(R.string.duplicates_pick_keep), color = FgMute, fontSize = 12.sp)

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            group.items.forEachIndexed { index, item ->
                DuplicateCopyThumb(
                    item = item,
                    kept = item.stableId in keepIds,
                    onOpen = { onOpenViewer(group.items, index) },
                    onToggleKeep = {
                        val id = item.stableId
                        keepIds = if (id in keepIds) {
                            // Never empty the keep set — ignore a toggle that would remove the last one.
                            if (keepIds.size > 1) keepIds - id else keepIds
                        } else {
                            keepIds + id
                        }
                    },
                    requestDecrypt = requestDecrypt,
                    cancelDecrypt = cancelDecrypt,
                )
            }
        }

        TextButton(
            onClick = { showConfirm = true },
            enabled = !isDeleting && !nothingToDelete,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(
                stringResource(R.string.duplicates_delete_others),
                color = if (isDeleting || nothingToDelete) FgMute else Accent,
            )
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.duplicates_confirm_title)) },
            text = { Text(stringResource(R.string.duplicates_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onDeleteExtras(group, keepIds)
                }) { Text(stringResource(R.string.duplicates_confirm_delete), color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = FgDim)
                }
            },
        )
    }
}

/**
 * A single copy tile. Tapping the thumbnail opens it full-screen in the viewer; the small corner
 * chip toggles whether this copy is kept. Kept copies carry the accent border and a check.
 */
@Composable
private fun DuplicateCopyThumb(
    item: GalleryItem,
    kept: Boolean,
    onOpen: () -> Unit,
    onToggleKeep: () -> Unit,
    requestDecrypt: (String) -> Unit,
    cancelDecrypt: (String) -> Unit,
) {
    val inputs = photoCellInputsFor(item)
    // Cloud thumbnails decrypt on demand — request per visible tile like the gallery cells do.
    val pendingLinkId = (item as? GalleryItem.CloudOnly)?.cloud?.takeIf { it.thumbnailUrl == null }?.linkId
    if (pendingLinkId != null) {
        LaunchedEffect(pendingLinkId) {
            delay(120)
            requestDecrypt(pendingLinkId)
        }
        DisposableEffect(pendingLinkId) {
            onDispose { cancelDecrypt(pendingLinkId) }
        }
    }
    val viewLargeCd = stringResource(R.string.duplicates_view_large)
    Box(
        modifier = Modifier
            .width(116.dp)
            .height(164.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = if (kept) 2.dp else 0.dp,
                color = if (kept) Accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClickLabel = viewLargeCd, onClick = onOpen),
    ) {
        PhotoCell(
            imageData = inputs.imageData,
            stableKey = inputs.stableKey,
            isVideo = inputs.isVideo,
            isPlaceholder = inputs.isPlaceholder,
            showCloudBadge = false,
            showSyncedBadge = false,
            isFavorite = false,
            isOffline = false,
            // Fill the taller review tile instead of PhotoCell's default 0.85 grid shape.
            aspectRatioOverride = 116f / 164f,
            typeBadgeRes = inputs.typeBadgeRes,
            typeBadgeCdRes = inputs.typeBadgeCdRes,
            onClick = onOpen,
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (kept) Accent else Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onToggleKeep)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                if (kept) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                null, tint = Color.White, modifier = Modifier.size(12.dp),
            )
            Text(stringResource(R.string.duplicates_keep), color = Color.White, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold)
        }

        // Source badge — where this copy lives: a white cloud (Drive only), a GREEN cloud (on Drive
        // AND device, i.e. Synced), or a phone (device only). Mirrors the gallery's cloud badges.
        val badgeIcon = if (item is GalleryItem.LocalOnly) Icons.Default.PhoneAndroid else Icons.Default.Cloud
        val badgeTint = if (item is GalleryItem.Synced) Color(0xFF30D158) else Color.White
        val badgeLabel = when (item) {
            is GalleryItem.Synced -> R.string.duplicates_badge_synced
            is GalleryItem.LocalOnly -> R.string.duplicates_badge_device
            else -> R.string.duplicates_badge_cloud
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(badgeIcon, null, tint = badgeTint, modifier = Modifier.size(11.dp))
            Text(
                stringResource(badgeLabel),
                color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
