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
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.SelectionAction
import eu.akoos.photos.presentation.common.SelectionDrawer
import eu.akoos.photos.presentation.common.EmptyState
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import eu.akoos.photos.presentation.common.ScrollScrubber
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.style.TextOverflow
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
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.gallery.FilterChip
import eu.akoos.photos.presentation.gallery.FilterSectionLabel
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateFinderScreen(
    onBack: () -> Unit,
    viewModel: DuplicateFinderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // photoLinkId -> cloud album name, so each duplicate tile can label which album a copy is in. Empty
    // until it resolves (tiles just render without the label until then) and with no signed-in account.
    val albumNames by viewModel.albumNames.collectAsStateWithLifecycle()
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
        var showFilterSheet by remember { mutableStateOf(false) }
        val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val listState = rememberLazyListState()

        // The per-group removal selection is hoisted here so the card and the full-screen review
        // overlay edit ONE set per group. Keyed on the group's own member ids (see [duplicateGroupKey]):
        // a pruned group's key no longer matches, so its selection re-seeds empty and can never name a
        // copy the group no longer holds.
        val groupSelections = remember { mutableStateMapOf<String, Set<String>>() }
        // The group whose copies are open in the full-screen review, or null while it is closed.
        var reviewGroup by remember { mutableStateOf<DuplicateGroup?>(null) }
        // Which copy the review opens on: the tapped tile, or the first copy from the Details button.
        var reviewInitialIndex by remember { mutableStateOf(0) }
        // Full-res facts (uri, size, dimensions) for the cloud copy the review is showing, resolved on
        // demand by the view model so the review can show the real image and its true size/resolution.
        val cloudFullRes by viewModel.cloudFullRes.collectAsStateWithLifecycle()

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

        // Every VISIBLE group the user has ticked copies in, as (group, removeIds). Aggregating from the
        // visible lists (not by iterating groupSelections, which keeps stale entries for groups the
        // current filter hides) is what stops the shared delete bar from touching a hidden group.
        val batchSelections = (deviceGroups + cloudGroups + similarDeviceGroups + similarCloudGroups)
            .mapNotNull { g ->
                val remove = groupSelections[duplicateGroupKey(g)].orEmpty()
                if (remove.isEmpty()) null else g to remove
            }
        // The ticked copies across every visible group, flattened for the shared selection drawer.
        val selectedItems = batchSelections.flatMap { (g, remove) -> g.items.filter { it.stableId in remove } }
        var showBatchConfirm by remember { mutableStateOf(false) }

        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(top = contentTopPad),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
            }
            // Nothing found at all: no filter combination could reveal anything, so offer no rows.
            !hasAnyContent ->
                EmptyState(
                    title = stringResource(R.string.duplicates_empty),
                    icon = Icons.Default.ContentCopy,
                    modifier = Modifier.fillMaxSize().padding(top = contentTopPad),
                )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    16.dp, contentTopPad, 16.dp,
                    // Reserve the selection drawer's resting height (its handle, count row, thumbnail
                    // strip and gaps come to ~156dp) plus a little gap, so the last card's album caption
                    // clears the drawer instead of hiding behind it while a selection is active.
                    (if (batchSelections.isNotEmpty()) 176.dp else 24.dp) + navBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The filter lives in the header sheet (always reachable), so a combination that matches
                // nothing is still recoverable without leaving the screen.
                if (!exactVisible && !similarVisible) {
                    item("empty") {
                        EmptyState(
                            title = stringResource(R.string.duplicates_empty),
                            icon = Icons.Default.ContentCopy,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                        )
                    }
                }
                if (showExact) {
                    if (deviceGroups.isNotEmpty()) {
                        item("h-device") { SectionLabel(stringResource(R.string.duplicates_section_device)) }
                        items(deviceGroups, key = { "d-" + it.items.first().stableId }) { group ->
                            val key = duplicateGroupKey(group)
                            DuplicateGroupCard(
                                modifier = Modifier.animateItem(),
                                group = group,
                                similar = false,
                                albumNames = albumNames,
                                selected = groupSelections[key].orEmpty(),
                                onSelectionChange = { groupSelections[key] = it },
                                onOpenReviewAt = { idx ->
                                    reviewGroup = group
                                    reviewInitialIndex = idx
                                },
                                requestDecrypt = viewModel::requestThumbnailDecrypt,
                                cancelDecrypt = viewModel::cancelThumbnailDecrypt,
                            )
                        }
                    }
                    if (cloudGroups.isNotEmpty()) {
                        item("h-cloud") { SectionLabel(stringResource(R.string.duplicates_section_cloud)) }
                        items(cloudGroups, key = { "c-" + it.items.first().stableId }) { group ->
                            val key = duplicateGroupKey(group)
                            DuplicateGroupCard(
                                modifier = Modifier.animateItem(),
                                group = group,
                                similar = false,
                                albumNames = albumNames,
                                selected = groupSelections[key].orEmpty(),
                                onSelectionChange = { groupSelections[key] = it },
                                onOpenReviewAt = { idx ->
                                    reviewGroup = group
                                    reviewInitialIndex = idx
                                },
                                requestDecrypt = viewModel::requestThumbnailDecrypt,
                                cancelDecrypt = viewModel::cancelThumbnailDecrypt,
                            )
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
                            val key = duplicateGroupKey(group)
                            DuplicateGroupCard(
                                modifier = Modifier.animateItem(),
                                group = group,
                                similar = true,
                                albumNames = albumNames,
                                selected = groupSelections[key].orEmpty(),
                                onSelectionChange = { groupSelections[key] = it },
                                onOpenReviewAt = { idx ->
                                    reviewGroup = group
                                    reviewInitialIndex = idx
                                },
                                requestDecrypt = viewModel::requestThumbnailDecrypt,
                                cancelDecrypt = viewModel::cancelThumbnailDecrypt,
                            )
                        }
                        items(similarCloudGroups, key = { "sc-" + it.items.first().stableId }) { group ->
                            val key = duplicateGroupKey(group)
                            DuplicateGroupCard(
                                modifier = Modifier.animateItem(),
                                group = group,
                                similar = true,
                                albumNames = albumNames,
                                selected = groupSelections[key].orEmpty(),
                                onSelectionChange = { groupSelections[key] = it },
                                onOpenReviewAt = { idx ->
                                    reviewGroup = group
                                    reviewInitialIndex = idx
                                },
                                requestDecrypt = viewModel::requestThumbnailDecrypt,
                                cancelDecrypt = viewModel::cancelThumbnailDecrypt,
                            )
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

        // Floating pill header (matches Search / Map). The trailing filter bubble opens the sync-status
        // and match-type sheet; it carries the accent outline when a non-default filter is active.
        val filtersActive = scope != DupScope.ALL || filter != DupFilter.ALL
        FloatingHeader(
            title = stringResource(R.string.duplicates_title),
            onBack = onBack,
            trailing = {
                IconBubble(
                    icon = Icons.Default.FilterList,
                    contentDescription = stringResource(R.string.filter_title),
                    onClick = { showFilterSheet = true },
                    background = if (filtersActive) Accent.copy(alpha = 0.15f) else PillBg,
                    borderColor = if (filtersActive) Accent else PillBorder,
                    tint = if (filtersActive) Accent else FgPrimary,
                )
            },
        )

        // The standard multi-select drawer shared with every selection surface in the app: the ticked
        // copies ride in its strip, its single Delete action removes them across every group at once,
        // and its close bubble clears the whole selection. This replaces the per-group card delete.
        SelectionDrawer(
            visible = selectedItems.isNotEmpty(),
            items = selectedItems,
            actions = listOf(
                SelectionAction(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(R.string.duplicates_confirm_delete),
                    onClick = { showBatchConfirm = true },
                    enabled = !state.isDeleting,
                ),
            ),
            onDismiss = { groupSelections.clear() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (showBatchConfirm) {
            // A backed-up (Synced) copy carries its device file with it, so the message says so, exactly
            // as the review's own delete does.
            val takesDeviceCopyToo = batchSelections.any { (g, remove) ->
                g.items.any { it.stableId in remove && it is GalleryItem.Synced }
            }
            ConfirmDialog(
                title = stringResource(R.string.duplicates_confirm_title),
                message = stringResource(
                    if (takesDeviceCopyToo) R.string.duplicates_confirm_message_synced
                    else R.string.duplicates_confirm_message
                ),
                confirmLabel = stringResource(R.string.duplicates_confirm_delete),
                dismissLabel = stringResource(R.string.cancel),
                onConfirm = {
                    showBatchConfirm = false
                    viewModel.deleteExtrasBatch(
                        batchSelections.map { (g, remove) ->
                            g to keepIdsForRemoval(g.items.map { it.stableId }.toSet(), remove)
                        },
                    )
                },
                onDismiss = { showBatchConfirm = false },
                destructive = true,
            )
        }

        // Full-screen side-by-side review of one group's copies, drawn last so it covers the list and
        // the header. It shares this group's hoisted selection, so a copy marked here is marked on the
        // card too, and both delete through the same keep-set contract.
        reviewGroup?.let { g ->
            val reviewKey = duplicateGroupKey(g)
            DuplicateGroupReview(
                group = g,
                initialIndex = reviewInitialIndex,
                albumNames = albumNames,
                selected = groupSelections[reviewKey].orEmpty(),
                onSelectionChange = { groupSelections[reviewKey] = it },
                isDeleting = state.isDeleting,
                onDeleteExtras = viewModel::deleteExtras,
                onClose = { reviewGroup = null },
                requestDecrypt = viewModel::requestThumbnailDecrypt,
                cancelDecrypt = viewModel::cancelThumbnailDecrypt,
                cloudFullRes = cloudFullRes,
                onRequestFullRes = { item ->
                    (item as? GalleryItem.CloudOnly)?.let { viewModel.requestCloudFullRes(it.cloud) }
                },
            )
        }

        if (showFilterSheet) {
            ModalBottomSheet(
                onDismissRequest = { showFilterSheet = false },
                sheetState = filterSheetState,
                containerColor = colors.sheetBg,
                scrimColor = Color.Black.copy(alpha = 0.5f),
            ) {
                DuplicateFilterSheet(
                    scope = scope,
                    filter = filter,
                    onScopeChange = { scope = it },
                    onFilterChange = { filter = it },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = FgMute, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
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

/** Stable key for a group's hoisted removal selection: its member ids joined in order. Membership
 *  changing (a delete pruning a copy) changes the key, so the pruned group re-seeds its selection
 *  empty rather than carrying a stale tick for a copy it no longer holds. */
internal fun duplicateGroupKey(group: DuplicateGroup): String =
    group.items.joinToString("|") { it.stableId }

/**
 * The duplicate-list filter sheet, opened from the header filter bubble: a sync-status section (on
 * device / cloud only, the same chips and vocabulary the timeline sync filter uses) and a match-type
 * section (identical / similar). Both are two-chip toggles with no explicit All chip, matching the
 * timeline sheet: tapping the active chip clears back to ALL. State is screen-level and narrows the
 * list at render time, so no view-model round trip is involved.
 */
@Composable
private fun DuplicateFilterSheet(
    scope: DupScope,
    filter: DupFilter,
    onScopeChange: (DupScope) -> Unit,
    onFilterChange: (DupFilter) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilterSectionLabel(stringResource(R.string.filter_sync_label))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                label = stringResource(R.string.filter_sync_local),
                selected = scope == DupScope.DEVICE,
                accentWhenSelected = true,
                trailingClear = true,
                modifier = Modifier.weight(1f),
                onClick = { onScopeChange(if (scope == DupScope.DEVICE) DupScope.ALL else DupScope.DEVICE) },
            )
            FilterChip(
                label = stringResource(R.string.filter_sync_cloud),
                selected = scope == DupScope.CLOUD,
                accentWhenSelected = true,
                trailingClear = true,
                modifier = Modifier.weight(1f),
                onClick = { onScopeChange(if (scope == DupScope.CLOUD) DupScope.ALL else DupScope.CLOUD) },
            )
        }
        FilterSectionLabel(stringResource(R.string.duplicates_filter_match_label))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                label = stringResource(R.string.duplicates_filter_identical),
                selected = filter == DupFilter.IDENTICAL,
                accentWhenSelected = true,
                trailingClear = true,
                modifier = Modifier.weight(1f),
                onClick = { onFilterChange(if (filter == DupFilter.IDENTICAL) DupFilter.ALL else DupFilter.IDENTICAL) },
            )
            FilterChip(
                label = stringResource(R.string.duplicates_filter_similar),
                selected = filter == DupFilter.SIMILAR,
                accentWhenSelected = true,
                trailingClear = true,
                modifier = Modifier.weight(1f),
                onClick = { onFilterChange(if (filter == DupFilter.SIMILAR) DupFilter.ALL else DupFilter.SIMILAR) },
            )
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
 * One duplicate group. The multi-copy "remove" selection is hoisted to the screen and handed in as
 * [selected] / [onSelectionChange], so the full-screen review overlay edits the very same set. It
 * starts EMPTY: a copy is only ever queued for deletion because it was picked by hand, here or in the
 * review. The action below can then only ever delete a STRICT subset of the group, never all of it,
 * because it sends the view model the complement (the keepers) and stays disabled while nothing is
 * ticked. The selection's key follows the group's membership, so pruning a deleted copy re-seeds it and
 * the removal set can never name a copy the group no longer holds.
 */
@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    similar: Boolean,
    albumNames: Map<String, String>,
    selected: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onOpenReviewAt: (index: Int) -> Unit,
    requestDecrypt: (String) -> Unit,
    cancelDecrypt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allIds = remember(group) { group.items.map { it.stableId }.toSet() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PillBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Count on the left, a Details button on the right that opens the full-screen review of the
        // same group so the copies can be compared large and side by side.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (similar) stringResource(R.string.duplicates_similar_count, group.items.size)
                else stringResource(R.string.duplicates_copies, group.items.size),
                color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .border(0.5.dp, PillBorder, RoundedCornerShape(20.dp))
                    .clickable(onClick = { onOpenReviewAt(0) })
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Accent, modifier = Modifier.size(14.dp))
                Text(
                    stringResource(R.string.duplicates_details),
                    color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
        Text(stringResource(R.string.duplicates_pick_remove), color = FgMute, fontSize = 12.sp)

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            group.items.forEachIndexed { index, item ->
                DuplicateCopyThumb(
                    item = item,
                    location = duplicateLocationOf(item, albumNames),
                    removing = item.stableId in selected,
                    onOpen = { onOpenReviewAt(index) },
                    onToggleRemove = {
                        onSelectionChange(toggleDuplicateRemoval(selected, item.stableId, allIds))
                    },
                    requestDecrypt = requestDecrypt,
                    cancelDecrypt = cancelDecrypt,
                )
            }
        }
    }
}

/** The album (cloud) or device-folder name a duplicate copy lives in, shown as a small tile label so
 *  the right copy is easy to pick straight from the group. [isAlbum] selects the leading icon. */
private data class DuplicateLocation(val text: String, val isAlbum: Boolean)

/** Resolves the label for one copy: a cloud album (a photo backed up into one) wins, otherwise the
 *  device folder the file sits in. A cloud-only copy in no album, or a local file with no bucket name,
 *  gets none. [albumNames] is the shared photoLinkId -> album-name map, so this is a plain lookup. */
private fun duplicateLocationOf(item: GalleryItem, albumNames: Map<String, String>): DuplicateLocation? =
    when (item) {
        is GalleryItem.CloudOnly ->
            albumNames[item.cloud.linkId]?.takeIf { it.isNotBlank() }
                ?.let { DuplicateLocation(it, isAlbum = true) }
        is GalleryItem.Synced ->
            albumNames[item.cloud.linkId]?.takeIf { it.isNotBlank() }
                ?.let { DuplicateLocation(it, isAlbum = true) }
                ?: item.local.bucketName?.takeIf { it.isNotBlank() }
                    ?.let { DuplicateLocation(it, isAlbum = false) }
        is GalleryItem.LocalOnly ->
            item.local.bucketName?.takeIf { it.isNotBlank() }
                ?.let { DuplicateLocation(it, isAlbum = false) }
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
    location: DuplicateLocation?,
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
    Column(modifier = Modifier.width(140.dp)) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(198.dp),
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
                aspectRatioOverride = 140f / 198f,
                typeBadgeRes = inputs.typeBadgeRes,
                typeBadgeCdRes = inputs.typeBadgeCdRes,
                // Fixed-size review tiles (not a column grid); keep the big-tile tier so every badge shows.
                columns = 3,
                onClick = onOpen,
            )
            // Only the circle toggles; a tap on the photo opens it, which is how the copies get compared.
            // The touch target runs well past the little circle (the whole top-start corner) so a
            // slightly-off tap still ticks the copy instead of opening it full-screen.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(52.dp)
                    .clickable(onClickLabel = toggleCd, onClick = onToggleRemove),
            )
        }
        // Which album (or device folder) this copy lives in, shown as a caption UNDER the tile: it has
        // the full tile width to read and never covers the cloud/sync badge in the corner, so the copy
        // to keep is obvious from the group. The fixed-height box keeps a copy with no label the same
        // size as its siblings.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (location != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (location.isAlbum) Icons.Default.PhotoAlbum else Icons.Default.Folder,
                        contentDescription = null,
                        tint = FgMute,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        location.text,
                        color = FgPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
    }
}
