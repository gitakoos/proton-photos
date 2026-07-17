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
import androidx.compose.foundation.lazy.rememberLazyListState
import eu.akoos.photos.presentation.common.ScrollScrubber
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.FindDuplicatesUseCase
import eu.akoos.photos.domain.usecase.FindDuplicatesUseCase.DuplicateGroup
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
 * kept separate, synced pairs excluded) and lets the user tick the copies to delete. At least one
 * copy of every group always survives: the removal set can never cover a whole group, and the card
 * hands the view model the complement (the keepers), which it refuses to act on when that is empty.
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
        // Guard the launch: on some OEMs a large or foreign trash IntentSender can throw right here,
        // which would otherwise force-close instead of failing gracefully. Fall back to the normal
        // "delete failed" path so the user sees a toast, not a crash.
        runCatching { deletePermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
            .onFailure { viewModel.onDeleteLaunchFailed() }
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
        var scope by rememberSaveable { mutableStateOf(DupScope.ALL) }
        val listState = rememberLazyListState()

        // Both filters narrow at RENDER time only: they pick which of the lists the view model has
        // already grouped to draw. Re-deriving groups per filter change would rebuild the whole
        // library in memory and blow the heap on large libraries, so never do that here.
        val showDevice = scope != DupScope.CLOUD
        val showCloud = scope != DupScope.DEVICE
        val deviceGroups = if (showDevice) state.deviceGroups else emptyList()
        val cloudGroups = if (showCloud) state.cloudGroups else emptyList()
        val similarDeviceGroups = if (showDevice) state.similarDeviceGroups else emptyList()
        val similarCloudGroups = if (showCloud) state.similarCloudGroups else emptyList()
        // ALL (the default) shows both kinds; the other two narrow the list to one kind.
        val showExact = filter != DupFilter.SIMILAR
        val showSim = filter != DupFilter.IDENTICAL
        val exactVisible = showExact && (deviceGroups.isNotEmpty() || cloudGroups.isNotEmpty())
        val similarVisible = showSim &&
            (similarDeviceGroups.isNotEmpty() || similarCloudGroups.isNotEmpty() || state.scanningSimilar)
        val hasAnyContent = state.deviceGroups.isNotEmpty() || state.cloudGroups.isNotEmpty() ||
            state.similarDeviceGroups.isNotEmpty() || state.similarCloudGroups.isNotEmpty() ||
            state.scanningSimilar

        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(top = contentTopPad),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
            }
            // Nothing found at all: no filter combination could reveal anything, so offer no rows.
            !hasAnyContent ->
                DupEmptyState(
                    stringResource(R.string.duplicates_empty),
                    Modifier.fillMaxSize().padding(top = contentTopPad),
                )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, contentTopPad, 16.dp, 24.dp + navBottom),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("filters") {
                    DupFilterRows(
                        filter = filter,
                        scope = scope,
                        onFilterChange = { filter = it },
                        onScopeChange = { scope = it },
                    )
                }
                // The filter rows stay above this, so a combination that matches nothing is still
                // recoverable without leaving the screen.
                if (!exactVisible && !similarVisible) {
                    item("empty") {
                        DupEmptyState(
                            stringResource(R.string.duplicates_empty),
                            Modifier.fillMaxWidth().padding(vertical = 56.dp),
                        )
                    }
                }
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
                    if (similarDeviceGroups.isNotEmpty() || similarCloudGroups.isNotEmpty()) {
                        item("h-similar") { SectionLabel(stringResource(R.string.duplicates_section_similar)) }
                        item("hint-similar") {
                            Text(
                                stringResource(R.string.duplicates_similar_hint),
                                color = FgMute, fontSize = 12.sp,
                            )
                        }
                        items(similarDeviceGroups, key = { "sd-" + it.items.first().stableId }) { group ->
                            DuplicateGroupCard(group, similar = true, state.isDeleting, viewModel::deleteExtras,
                                onOpenViewer, viewModel::requestThumbnailDecrypt, viewModel::cancelThumbnailDecrypt)
                        }
                        items(similarCloudGroups, key = { "sc-" + it.items.first().stableId }) { group ->
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

        // Fast-scroll grabber for a long duplicate list (position only; the list is grouped by kind,
        // not by date, so there is no date axis here). Reuses the albums and folder scrubber.
        ScrollScrubber(
            listState = listState,
            topPadding = contentTopPad,
            bottomPadding = 24.dp + navBottom,
            minItemsToShow = 12,
        )

        // Floating pill header (matches Search / Map).
        FloatingMemoriesHeader(
            title = stringResource(R.string.duplicates_title),
            onBack = onBack,
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
private fun DupEmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier,
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

/** Which side of the library the list is narrowed to. ALL (the default) shows device and cloud. */
private enum class DupScope { ALL, DEVICE, CLOUD }

/**
 * Tick or untick [id] for removal. Unticking always succeeds; ticking is refused when it would cover
 * every copy in [allIds], so the returned set is always a STRICT subset of the group. That single
 * guard is what keeps [keepIdsForRemoval] non-empty, and with it a group can never be wiped out.
 */
internal fun toggleDuplicateRemoval(current: Set<String>, id: String, allIds: Set<String>): Set<String> =
    if (id in current) current - id
    else (current + id).takeIf { it.size < allIds.size } ?: current

/** The copies to KEEP, i.e. the ones left unticked. This complement is what the view model's delete
 *  contract takes; it refuses an empty keep set, which [toggleDuplicateRemoval] can never produce. */
internal fun keepIdsForRemoval(allIds: Set<String>, removeIds: Set<String>): Set<String> = allIds - removeIds

/** The two choice rows at the head of the list: which kind of duplicate to show, and which side of
 *  the library to show it from. */
@Composable
private fun DupFilterRows(
    filter: DupFilter,
    scope: DupScope,
    onFilterChange: (DupFilter) -> Unit,
    onScopeChange: (DupScope) -> Unit,
) {
    val all = stringResource(R.string.gallery_filter_all)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DupSegmentedRow(
            listOf(
                DupSegment(all, filter == DupFilter.ALL) { onFilterChange(DupFilter.ALL) },
                DupSegment(
                    stringResource(R.string.duplicates_filter_identical),
                    filter == DupFilter.IDENTICAL,
                ) { onFilterChange(DupFilter.IDENTICAL) },
                DupSegment(
                    stringResource(R.string.duplicates_filter_similar),
                    filter == DupFilter.SIMILAR,
                ) { onFilterChange(DupFilter.SIMILAR) },
            ),
        )
        DupSegmentedRow(
            listOf(
                DupSegment(all, scope == DupScope.ALL) { onScopeChange(DupScope.ALL) },
                DupSegment(
                    stringResource(R.string.duplicates_badge_device),
                    scope == DupScope.DEVICE,
                    Icons.Default.PhoneAndroid,
                ) { onScopeChange(DupScope.DEVICE) },
                DupSegment(
                    stringResource(R.string.duplicates_badge_cloud),
                    scope == DupScope.CLOUD,
                    Icons.Default.Cloud,
                ) { onScopeChange(DupScope.CLOUD) },
            ),
        )
    }
}

/** One option in a [DupSegmentedRow]. The scope row passes the same icons the copy tiles carry, so
 *  the two read as one vocabulary. */
private class DupSegment(
    val label: String,
    val selected: Boolean,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
)

/** A single-choice segmented row: the selected option takes the accent fill, the rest sit flat on
 *  the pill. */
@Composable
private fun DupSegmentedRow(segments: List<DupSegment>) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(PillBg)
            .border(0.5.dp, PillBorder, RoundedCornerShape(20.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            val fg = if (segment.selected) Color.White else FgPrimary
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(17.dp))
                    .background(
                        if (segment.selected) Accent else Color.Transparent,
                        RoundedCornerShape(17.dp),
                    )
                    .clickable(onClick = segment.onClick)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (segment.icon != null) {
                    Icon(segment.icon, null, tint = fg, modifier = Modifier.size(13.dp))
                }
                Text(segment.label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
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
 * One duplicate group. Holds the multi-copy "remove" selection, which starts EMPTY: a copy is only
 * ever queued for deletion because it was picked here by hand. The action below can then only ever
 * delete a STRICT subset of the group, never all of it, because the card sends the view model the
 * complement (the keepers) and the button stays disabled while nothing is ticked. Both sets are
 * keyed on [group], so pruning a deleted copy re-seeds them together and the removal set can never
 * name a copy the group no longer holds.
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
    val allIds = remember(group) { group.items.map { it.stableId }.toSet() }
    // Nothing is ticked up front: a copy is only ever deleted because it was picked here by hand.
    var removeIds by remember(group) { mutableStateOf(emptySet<String>()) }
    var showConfirm by remember(group) { mutableStateOf(false) }
    val nothingToDelete = removeIds.isEmpty()

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
        Text(stringResource(R.string.duplicates_pick_remove), color = FgMute, fontSize = 12.sp)

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            group.items.forEachIndexed { index, item ->
                DuplicateCopyThumb(
                    item = item,
                    removing = item.stableId in removeIds,
                    onOpen = { onOpenViewer(group.items, index) },
                    onToggleRemove = {
                        removeIds = toggleDuplicateRemoval(removeIds, item.stableId, allIds)
                    },
                    requestDecrypt = requestDecrypt,
                    cancelDecrypt = cancelDecrypt,
                )
            }
        }

        val canDelete = !isDeleting && !nothingToDelete
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(20.dp))
                .background(if (canDelete) Accent.copy(alpha = 0.15f) else Color.Transparent)
                .border(0.5.dp, PillBorder, RoundedCornerShape(20.dp))
                // Clipped before the click so the press ripple fills the pill, not an inner box.
                .clickable(enabled = canDelete) { showConfirm = true }
                .padding(horizontal = 20.dp, vertical = 9.dp),
        ) {
            Text(
                if (nothingToDelete) stringResource(R.string.duplicates_confirm_delete)
                else stringResource(R.string.duplicates_delete_selected, removeIds.size),
                color = if (canDelete) Accent else FgMute,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
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
                    onDeleteExtras(group, keepIdsForRemoval(allIds, removeIds))
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
 * A single copy tile. Tapping the photo opens it full-screen in the viewer, which is how the copies
 * get compared; tapping the corner circle ticks this copy for removal. The cell draws its own
 * selection circle and cloud badge and insets its duration pill around them, so the tile reads
 * exactly like every other grid in the app and nothing has to be hand-placed around the badge.
 */
@Composable
private fun DuplicateCopyThumb(
    item: GalleryItem,
    removing: Boolean,
    onOpen: () -> Unit,
    onToggleRemove: () -> Unit,
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
    // The circle's click label names what the tap DOES, so it flips with the state.
    val toggleCd = stringResource(if (removing) R.string.duplicates_keep else R.string.duplicates_remove)
    Box(
        modifier = Modifier
            .width(116.dp)
            .height(164.dp),
    ) {
        PhotoCell(
            imageData = inputs.imageData,
            stableKey = inputs.stableKey,
            isVideo = inputs.isVideo,
            isLocalVideo = inputs.isLocalVideo,
            durationMs = inputs.durationMs,
            isPlaceholder = inputs.isPlaceholder,
            // The cell's own selection circle and cloud badges keep this tile reading exactly like
            // every other grid in the app, and it insets its duration pill to clear the badge itself.
            selected = removing,
            isSelectionMode = true,
            showCloudBadge = item is GalleryItem.CloudOnly,
            showSyncedBadge = item is GalleryItem.Synced,
            isFavorite = false,
            isOffline = false,
            // Fill the taller review tile instead of PhotoCell's default 0.85 grid shape.
            aspectRatioOverride = 116f / 164f,
            typeBadgeRes = inputs.typeBadgeRes,
            typeBadgeCdRes = inputs.typeBadgeCdRes,
            // Fixed-size review tiles (not a column grid); keep the big-tile tier so every badge shows.
            columns = 3,
            onClick = onOpen,
        )
        // Only the circle toggles; a tap on the photo opens it, which is how the copies get compared.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = toggleCd, onClick = onToggleRemove),
        )
    }
}
