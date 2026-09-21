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

package eu.akoos.photos.presentation.importer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.ImportAlbumCount
import eu.akoos.photos.data.db.entity.ImportStagedEntity
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.importer.ImportAlbumMode
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.ScrollScrubber
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.common.SelectionAction
import eu.akoos.photos.presentation.common.SelectionDrawer
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.rememberDragMultiSelectModifier
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.viewer.MetaRow
import eu.akoos.photos.presentation.viewer.MetadataSection
import eu.akoos.photos.presentation.viewer.formatMs
import eu.akoos.photos.presentation.viewer.formatMsWithTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * The staged-photo review surface: a thumbnail grid of everything a picked zip staged, before anything
 * is uploaded. A single tap opens a photo's metadata detail so the user can verify its date and place;
 * a long-press starts multi-select, and a drag from there sweeps a contiguous range the way the
 * timeline does. Removed photos stay in the grid dimmed so they can be brought back. The bottom bar
 * confirms the kept set for upload or discards the whole run.
 *
 * The removal selection is hoisted here so the grid cells, the drag-select gesture and the selection
 * drawer edit one set of entry names. Confirm hands the kept rows to the view model; [newCount] is how
 * many of them are genuinely new (not already in Drive), which the confirm button reports. The album
 * toggle appears only when [hasAlbums] is true, and reads / writes [albumMode].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportReviewSection(
    state: ImportUiState.Review,
    newCount: Int,
    reviewAlreadyCount: Int,
    hasAlbums: Boolean,
    albumSummary: List<ImportAlbumCount>,
    albumMode: ImportAlbumMode,
    onAlbumModeChange: (ImportAlbumMode) -> Unit,
    onBack: () -> Unit,
    onExclude: (List<String>) -> Unit,
    onRestore: (List<String>) -> Unit,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
) {
    val colors = AppColors.current
    val rows = state.rows
    val byName = remember(rows) { rows.associateBy { it.entryName } }

    // The grid scroll state is hoisted so the drag-select gesture can hit-test visible cells and
    // auto-scroll at the edges against the same grid the user sees.
    val gridState = rememberLazyGridState()

    // Multi-select state, hoisted so the cells, the drag gesture and the drawer share one selection of
    // entry names. Selection mode is derived from the set, matching every other grid in the app: a
    // non-empty selection is selection mode, emptying it leaves.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selectionMode = selected.isNotEmpty()
    // The photo whose metadata detail is open, or null while the grid is showing.
    var detailEntry by remember { mutableStateOf<ImportStagedEntity?>(null) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    // Confirming the upload is a real, hard-to-take-back action (a large run sends thousands of files), so
    // it goes through its own confirm sheet rather than firing on the first tap.
    var showImportConfirm by remember { mutableStateOf(false) }
    // The album-mode picker opens as the same modal bottom sheet the timeline's filter uses, rather than an
    // inline control, so it reads as one of the app's own drawers.
    var showAlbumSheet by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    // Past a few rows the jump-to-top affordance earns its place on a long import.
    val showScrollTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 4 } }
    // Scrolling with a short tail: the bottom bar drops out of the way while the grid moves and for a beat
    // after it settles, which is also the window the jump-to-top stays up to be tapped. Without the tail the
    // bar would snap back the instant a fling ended.
    var scrollActive by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                scrollActive = true
            } else {
                // A short tail so a fling that briefly stops does not flash the bar back, but short enough
                // that the bar returns promptly once the grid settles rather than lingering hidden.
                delay(200)
                scrollActive = false
            }
        }
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun toggle(entryName: String) {
        selected = if (entryName in selected) selected - entryName else selected + entryName
    }

    // The selectable keys in grid order, and key -> index, feeding the shared drag-select gesture the
    // same shape the gallery hands it. The cell key is the entry name.
    val keys = remember(rows) { rows.map { it.entryName } }
    val indexByKey = remember(rows) {
        HashMap<String, Int>(rows.size).apply {
            rows.forEachIndexed { i, r -> put(r.entryName, i) }
        }
    }
    // Armed at the long-press anchor so the cell's release-tap skips toggling the just-selected cell
    // back off, exactly as the gallery grid guards its own cells.
    val tapGuard = remember { mutableStateOf(false) }
    val dragSelectModifier = rememberDragMultiSelectModifier(
        gridState = gridState,
        items = keys,
        indexByKey = indexByKey,
        selected = selected,
        onSelectionChange = { selected = it },
        tapGuard = tapGuard,
    )

    // Back and the predictive edge-swipe both route through the one dispatcher, so leaving selection
    // is a back handler here rather than a swipe that would pop the whole screen to Settings.
    BackHandler(enabled = selectionMode) { clearSelection() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        val contentTopPad = floatingHeaderContentTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(100.dp),
            modifier = Modifier
                .fillMaxSize()
                .then(dragSelectModifier),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = contentTopPad,
                end = 12.dp,
                // Clear the bottom bar (confirm button + discard row) so the last thumbnails stay reachable.
                bottom = 150.dp + navBottom,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A full-width summary of what a confirm would do, in the same design as the finished-run card,
            // so the figures and albums are legible before the upload. Its key is not an entry name, so the
            // drag-select gesture (which hit-tests by cell key) never treats it as a selectable photo.
            item(span = { GridItemSpan(maxLineSpan) }, key = "summary") {
                ImportReviewSummary(
                    newCount = newCount,
                    alreadyCount = reviewAlreadyCount,
                    albumSummary = albumSummary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(rows, key = { it.entryName }) { row ->
                ImportReviewCell(
                    row = row,
                    selected = row.entryName in selected,
                    selectionMode = selectionMode,
                    onClick = {
                        // Skip the release-tap that follows a long-press anchor; it would otherwise
                        // toggle the just-selected cell back off.
                        if (tapGuard.value) {
                            tapGuard.value = false
                        } else if (selectionMode) {
                            toggle(row.entryName)
                        } else {
                            detailEntry = row
                        }
                    },
                )
            }
        }

        FloatingHeader(
            title = stringResource(R.string.import_review_title),
            onBack = { if (selectionMode) clearSelection() else onBack() },
        )

        // The confirm + discard bar. Hidden while the selection drawer is up so the drawer's slide-out
        // reveals it, and slid out of the way while the grid scrolls so a long review reads unobstructed.
        AnimatedVisibility(
            visible = !selectionMode && !scrollActive,
            // The exact enter/exit the selection drawer uses, so the confirm sheet and the drawer slide in
            // and out with identical motion rather than the default spring.
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ImportReviewConfirmBar(
                newCount = newCount,
                hasAlbums = hasAlbums,
                albumMode = albumMode,
                onOpenAlbumSheet = { showAlbumSheet = true },
                onConfirm = { showImportConfirm = true },
                onDiscard = { showDiscardConfirm = true },
            )
        }

        // Jump-to-top, in the bottom slot the bar vacates while scrolling; the shared IconBubble the album
        // grids use, so it reads as the app's own affordance.
        AnimatedVisibility(
            visible = showScrollTop && scrollActive && !selectionMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            IconBubble(
                icon = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.cd_scroll_to_top),
                onClick = { scope.launch { gridState.animateScrollToItem(0) } },
            )
        }

        // Fast-scroll handle for the long import grid, on the right edge; appears while scrolling and fades
        // on idle. Gated so a short review that fits a screenful does not show it.
        ScrollScrubber(
            gridState = gridState,
            topPadding = contentTopPad + 12.dp,
            bottomPadding = 24.dp,
            minItemsToShow = 24,
        )

        // The multi-select bar is the shared selection drawer every other grid uses: the selected
        // photos ride in its thumbnail strip, Remove excludes them and Restore brings back any that
        // were already removed, and its close bubble clears the selection. The staged rows are adapted
        // to device-only gallery items so the strip loads each cached thumbnail from its file uri.
        val selectedItems = remember(rows, selected) {
            rows.filter { it.entryName in selected }.map { row ->
                GalleryItem.LocalOnly(
                    LocalMediaItem(
                        uri = "file://" + (row.thumbPath ?: ""),
                        dateTaken = row.dateMs ?: 0L,
                        displayName = row.title ?: row.entryName,
                        mimeType = "image/*",
                        sizeBytes = row.sizeBytes,
                        bucketName = null,
                    ),
                )
            }
        }
        val selectionActions = buildList {
            add(
                SelectionAction(
                    icon = Icons.Default.RemoveCircle,
                    label = stringResource(R.string.import_review_remove, selected.size),
                    onClick = { onExclude(selected.toList()); clearSelection() },
                    tint = colors.errorColor,
                ),
            )
            if (selected.any { byName[it]?.excluded == true }) {
                add(
                    SelectionAction(
                        icon = Icons.Default.Restore,
                        label = stringResource(R.string.import_review_restore),
                        onClick = { onRestore(selected.toList()); clearSelection() },
                    ),
                )
            }
        }
        SelectionDrawer(
            visible = selectionMode,
            items = selectedItems,
            actions = selectionActions,
            onDismiss = { clearSelection() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        detailEntry?.let { entry ->
            // Registered inside the detail block on purpose: back dispatch runs handlers in reverse
            // registration order, so the one added last while the detail is open gets first refusal
            // and closes the overlay, while back closes the screen at every other moment.
            BackHandler(enabled = true) { detailEntry = null }
            ImportReviewDetail(row = entry, onClose = { detailEntry = null })
        }
    }

    if (showDiscardConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.import_review_discard_title),
            message = stringResource(R.string.import_review_discard_message),
            confirmLabel = stringResource(R.string.import_review_discard),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showDiscardConfirm = false; onDiscard() },
            onDismiss = { showDiscardConfirm = false },
            destructive = true,
        )
    }

    if (showImportConfirm) {
        val message = if (newCount > 0) {
            pluralStringResource(R.plurals.import_confirm_message, newCount, newCount)
        } else {
            stringResource(R.string.import_confirm_albums_only)
        }
        ConfirmDialog(
            title = stringResource(R.string.import_confirm_title),
            message = message,
            confirmLabel = stringResource(R.string.import_confirm_button),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showImportConfirm = false; onConfirm() },
            onDismiss = { showImportConfirm = false },
        )
    }

    if (showAlbumSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAlbumSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.sheetBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            ImportAlbumModeSheet(
                selected = albumMode,
                onSelect = { mode ->
                    onAlbumModeChange(mode)
                    showAlbumSheet = false
                },
            )
        }
    }
}

/**
 * One grid cell. The thumbnail, its selection ring, the inward press-scale, the accent image tint and
 * the top-left check tick are all the shared [PhotoCell] the timeline draws, so the review reads as the
 * same grid rather than a lookalike. The import-specific overlays sit on top: the capture-date badge; an
 * "Already in Drive" chip with a light dim on a row whose bytes Drive already holds, so the user sees
 * which are skips before importing; and, for a removed row, a stronger dim plus a remove marker so the
 * row stays visible to be brought back. The removed state outranks the already-in-Drive one. Long-press
 * belongs to the grid-level drag gesture, so the cell stays tap-only.
 */
@Composable
private fun ImportReviewCell(
    row: ImportStagedEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    val excluded = row.excluded
    // A row already in Drive is dimmed and badged, but only while it is not also removed: the removal
    // dim and marker take over then, so the two states never stack.
    val alreadyInDrive = row.alreadyInDrive && !excluded
    Box {
        PhotoCell(
            imageData = row.thumbPath?.let { "file://$it" },
            stableKey = row.entryName,
            isPlaceholder = row.thumbPath == null,
            selected = selected,
            isSelectionMode = selectionMode,
            onClick = onClick,
            onLongClick = null,
        )

        // A removed row is dimmed in place so the grid still shows it, ready to be brought back. An
        // already-in-Drive row gets a lighter dim so it recedes without reading as removed. The scrim
        // sits over the cell but under the badges and marker, which stay at full strength.
        if (excluded || alreadyInDrive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = if (excluded) 0.45f else 0.35f)),
            )
        }

        // Capture-date badge, over a translucent scrim so it reads on any thumbnail in either theme.
        Text(
            text = row.dateMs?.let { formatMs(it) } ?: stringResource(R.string.import_review_no_date),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        // Already-in-Drive chip, top-left so it clears the date badge and the removal marker. Same badge
        // style as the date pill, so it reads as one of the cell's own labels.
        if (alreadyInDrive) {
            Text(
                text = stringResource(R.string.import_review_already_badge),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accent.copy(alpha = 0.9f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // Removed marker, kept at full opacity so a dimmed cell still shows why it is dimmed.
        if (excluded) {
            Icon(
                Icons.Default.RemoveCircle,
                contentDescription = stringResource(R.string.import_review_removed),
                tint = colors.errorColor,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(2.dp)
                    .size(18.dp),
            )
        }
    }
}

/**
 * The review's own summary, shown as the grid's full-width header in the same soft-row design as the
 * finished-run card: the figures a confirm would produce (new photos to import, and any the run would skip
 * as already in Drive) over the named album section, so the whole outcome is legible before the upload.
 */
@Composable
private fun ImportReviewSummary(
    newCount: Int,
    alreadyCount: Int,
    albumSummary: List<ImportAlbumCount>,
    modifier: Modifier = Modifier,
) {
    val stats = buildList {
        add(ImportStat(Icons.Outlined.CloudUpload, stringResource(R.string.import_stat_to_import), newCount.toString()))
        if (alreadyCount > 0) {
            add(ImportStat(Icons.Outlined.Cloud, stringResource(R.string.import_stat_already), alreadyCount.toString()))
        }
    }
    Column(modifier) {
        SettingsCard {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                ImportStatsColumn(stats)
                if (albumSummary.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    ImportAlbumsSummary(albums = albumSummary, showCounts = true)
                }
            }
        }
    }
}

/**
 * The default bottom bar: the album-mode toggle (only when the export has albums), the confirm button,
 * and the discard action.
 *
 * The confirm button counts the genuinely new photos ([newCount]), not the whole kept set, so a run whose
 * archive overlaps Drive is honest about how many photos it would add. With nothing new and no album mode
 * chosen there is nothing to do, so the button says so and is disabled; with nothing new but an album mode
 * chosen the run still has albums to build, so it stays enabled under an album-action label.
 */
@Composable
private fun ImportReviewConfirmBar(
    newCount: Int,
    hasAlbums: Boolean,
    albumMode: ImportAlbumMode,
    onOpenAlbumSheet: () -> Unit,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    val nothingNew = newCount == 0
    val albumsSelected = albumMode != ImportAlbumMode.NONE
    val confirmLabel = when {
        nothingNew && !albumsSelected -> stringResource(R.string.import_review_nothing_new)
        nothingNew -> stringResource(R.string.import_review_add_to_albums)
        else -> stringResource(R.string.import_review_confirm, newCount)
    }
    val confirmEnabled = !nothingNew || albumsSelected
    // The same surface a modal bottom sheet rests on: rounded top corners, the SheetBg fill and a shadow
    // that lifts it off the grid (no border), via a Material3 Surface rather than a hand-painted strip, so
    // this bottom drawer reads exactly like the sheet the album picker opens.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = colors.sheetBg,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(colors.fgMute.copy(alpha = 0.5f), CircleShape),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
            ) {
                if (hasAlbums) {
                    ImportAlbumModeRow(albumMode = albumMode, onClick = onOpenAlbumSheet)
                    Spacer(Modifier.height(12.dp))
                }
                PrimaryButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.import_review_discard),
                    color = colors.errorColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDiscard)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** The label for one [ImportAlbumMode], shared by the trigger row and the picker sheet. */
@Composable
private fun albumModeLabel(mode: ImportAlbumMode): String = when (mode) {
    ImportAlbumMode.NONE -> stringResource(R.string.import_album_mode_none)
    ImportAlbumMode.SHELLS_ONLY -> stringResource(R.string.import_album_mode_shells)
    ImportAlbumMode.WITH_PHOTOS -> stringResource(R.string.import_album_mode_photos)
}

/**
 * The trigger for the album-mode choice: a soft-filled row naming the section and the current mode, with a
 * chevron, that opens the modal picker. Making the choice in a modal sheet rather than an inline control
 * means it uses the same drawer the rest of the app does.
 */
@Composable
private fun ImportAlbumModeRow(albumMode: ImportAlbumMode, onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceWeak)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.import_album_mode_label),
            color = colors.fgPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = albumModeLabel(albumMode),
            color = colors.fgDim,
            fontSize = 13.sp,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            tint = colors.fgMute,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The album-mode picker's content inside a [ModalBottomSheet]: the three modes as tappable rows with a check
 * on the current one. Tapping selects and closes. The sheet chrome (rounded top, SheetBg, scrim, handle) is
 * the modal sheet's own, so this reads as the same drawer the timeline's filter uses.
 */
@Composable
private fun ImportAlbumModeSheet(selected: ImportAlbumMode, onSelect: (ImportAlbumMode) -> Unit) {
    val colors = AppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.import_album_mode_label),
            color = colors.fgPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        listOf(ImportAlbumMode.NONE, ImportAlbumMode.SHELLS_ONLY, ImportAlbumMode.WITH_PHOTOS).forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 8.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = albumModeLabel(mode),
                    color = colors.fgPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                if (mode == selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * The full-screen metadata detail for one staged photo: the cached thumbnail large, then the date,
 * location, description, file name and source the review verifies before upload. Reuses the viewer's
 * metadata primitives so the surface reads like the rest of the app.
 */
@Composable
private fun ImportReviewDetail(
    row: ImportStagedEntity,
    onClose: () -> Unit,
) {
    val colors = AppColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        val contentTopPad = floatingHeaderContentTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(contentTopPad))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.cardBg),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = row.thumbPath
                if (thumb != null) {
                    AsyncImage(
                        model = File(thumb),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    )
                } else {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = null,
                        tint = colors.fgMute,
                        modifier = Modifier.size(48.dp).padding(vertical = 40.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            MetadataSection(label = stringResource(R.string.import_review_meta_section)) {
                MetaRow(
                    label = stringResource(R.string.import_review_detail_date),
                    value = row.dateMs?.let { formatMsWithTime(it) }
                        ?: stringResource(R.string.import_review_no_date),
                )
                val lat = row.lat
                val lng = row.lng
                if (lat != null && lng != null) {
                    MetaRow(
                        label = stringResource(R.string.import_review_detail_location),
                        value = String.format(Locale.US, "%.5f, %.5f", lat, lng),
                    )
                }
                row.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    MetaRow(label = stringResource(R.string.import_review_detail_description), value = desc)
                }
                MetaRow(
                    label = stringResource(R.string.import_review_detail_filename),
                    value = row.title?.takeIf { it.isNotBlank() } ?: row.entryName,
                )
                MetaRow(
                    label = stringResource(R.string.import_review_detail_source),
                    value = stringResource(R.string.import_review_source),
                )
            }
            Spacer(Modifier.height(32.dp + navBottom))
        }

        FloatingHeader(
            title = row.title?.takeIf { it.isNotBlank() } ?: row.entryName,
            onBack = onClose,
        )
    }
}
