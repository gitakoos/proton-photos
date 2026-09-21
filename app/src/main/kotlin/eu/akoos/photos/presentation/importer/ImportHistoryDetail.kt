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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.domain.usecase.UndoResult
import eu.akoos.photos.presentation.common.CloudPhotoCell
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.DestructiveButton
import eu.akoos.photos.presentation.common.EmptyState
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.viewer.formatMsWithTime

/**
 * The full-screen Recent-imports detail: a grid of the photos one finished run put on Drive, over a
 * summary of what it brought in, with a safe "Undo this import" action. The undo trashes only the
 * photos whose bytes still match what the run uploaded, so anything changed since is left in place; the
 * grid and the action follow the run's ledger, so removed photos leave the grid and the summary line
 * reports the outcome. Back closes the overlay rather than the import screen.
 *
 * [detail] is null only while the run's data resolves. The run's thumbnails are warmed for the overlay's
 * lifetime and cancelled on leave; a freshly-imported run already carries local thumbnails, so it paints
 * at once and the warm only matters for an older run whose cached previews have been reclaimed.
 */
@Composable
fun ImportHistoryDetail(
    detail: ImportDetailData?,
    undoInFlight: Boolean,
    undoResult: UndoResult?,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    onRequestThumbnail: (String) -> Unit,
    onCancelThumbnail: (String) -> Unit,
    onWarmThumbnails: (List<String>) -> Unit,
    onCoolThumbnails: (List<String>) -> Unit,
) {
    val colors = AppColors.current
    var showUndoConfirm by remember { mutableStateOf(false) }

    val rows = detail?.rows.orEmpty()
    val photos = detail?.photos.orEmpty()
    val alreadyIds = detail?.alreadyInDriveLinkIds.orEmpty()
    // Every link the grid shows: the rows an undo has not trashed, deduped rows included. Drives the warm
    // set so both fresh imports and already-present matches decrypt their thumbnails.
    val shownLinkIds = remember(rows) { rows.filterNot { it.undone }.map { it.linkId } }
    // The rows an undo can still act on: real uploads not yet trashed. A deduped row points at a photo the
    // run never created, so it never enables the undo action; a run left with only deduped photos offers
    // no undo bar.
    val undoableLinkIds = remember(rows) {
        rows.filterNot { it.undone || it.alreadyInDrive }.map { it.linkId }
    }
    val hasLiveRows = undoableLinkIds.isNotEmpty()
    val showBar = hasLiveRows || undoInFlight || undoResult != null

    // Warm the run's thumbnails while the overlay is open, cancel them on leave. Keyed on the shown set so
    // an undo that shrinks it releases the trashed rows' decrypts.
    DisposableEffect(shownLinkIds) {
        onWarmThumbnails(shownLinkIds)
        onDispose { onCoolThumbnails(shownLinkIds) }
    }

    // Registered here so back dispatch (reverse registration order) hands this overlay first refusal
    // while it is open, closing it rather than leaving the import screen.
    BackHandler(enabled = true) { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        val contentTopPad = floatingHeaderContentTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        when {
            detail == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
            }
            photos.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = contentTopPad, bottom = navBottom),
            ) {
                ImportDetailSummary(detail, modifier = Modifier.padding(horizontal = 20.dp))
                EmptyState(
                    title = stringResource(R.string.import_detail_empty),
                    icon = Icons.Default.Inventory2,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    top = contentTopPad,
                    end = 12.dp,
                    // Clear the undo bar so the last thumbnails stay reachable when it is shown.
                    bottom = (if (showBar) 130.dp else 24.dp) + navBottom,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "summary") {
                    ImportDetailSummary(detail, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                items(photos, key = { it.linkId }) { photo ->
                    Box {
                        CloudPhotoCell(
                            localUri = null,
                            cloudThumbnailUrl = photo.thumbnailUrl,
                            cloudLinkId = photo.linkId,
                            isVideo = photo.mimeType.startsWith("video/"),
                            isSelectionMode = false,
                            isSelected = false,
                            // Read-only preview grid: no long-press, and a tap does nothing.
                            onLongClick = null,
                            onClick = {},
                            onRequestThumbnail = onRequestThumbnail,
                            onCancelThumbnail = onCancelThumbnail,
                        )
                        // A photo the run did not upload because Drive already held it: mark the cell so it
                        // reads as skipped rather than freshly imported.
                        if (photo.linkId in alreadyIds) {
                            AlreadyInDriveBadge(modifier = Modifier.align(Alignment.BottomStart))
                        }
                    }
                }
            }
        }

        FloatingHeader(title = stringResource(R.string.import_detail_title), onBack = onClose)

        if (showBar) {
            ImportUndoBar(
                canUndo = hasLiveRows,
                undoInFlight = undoInFlight,
                undoResult = undoResult,
                onUndo = { showUndoConfirm = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showUndoConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.import_undo_confirm_title),
            message = stringResource(R.string.import_undo_confirm_message),
            confirmLabel = stringResource(R.string.import_undo),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showUndoConfirm = false; onUndo() },
            onDismiss = { showUndoConfirm = false },
            destructive = true,
        )
    }
}

/** The run summary at the top of the detail: the archive's file name over a line of when it ran and
 *  its counts. Reuses the completed-run summary string so the wording matches the Done card. */
@Composable
private fun ImportDetailSummary(detail: ImportDetailData, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    val entry = detail.entry
    // Derived from the run's own ledger so the split holds even when the capped history no longer carries
    // this run's summary row: imported counts the rows the run uploaded, alreadyInDrive the deduped rows.
    val alreadyCount = detail.alreadyInDriveLinkIds.size
    val importedCount = detail.rows.count { !it.alreadyInDrive }
    // The history row folds already-in-Drive matches into its skipped total, so subtract them back out for
    // a true skipped figure and give the deduped matches their own row instead.
    val skippedCount = if (entry != null) (entry.skipped - alreadyCount).coerceAtLeast(0) else 0
    val stats = buildList {
        if (entry != null) {
            add(ImportStat(Icons.Outlined.Schedule, stringResource(R.string.import_stat_date), formatMsWithTime(entry.importedAt)))
        }
        if (importedCount > 0) {
            add(ImportStat(Icons.Outlined.CloudDone, stringResource(R.string.import_stat_imported), importedCount.toString()))
        }
        if (alreadyCount > 0) {
            add(ImportStat(Icons.Outlined.Cloud, stringResource(R.string.import_stat_already), alreadyCount.toString()))
        }
        if (skippedCount > 0) {
            add(ImportStat(Icons.Outlined.RemoveCircleOutline, stringResource(R.string.import_stat_skipped), skippedCount.toString()))
        }
        if (entry != null && entry.failed > 0) {
            add(ImportStat(Icons.Outlined.ErrorOutline, stringResource(R.string.import_stat_failed), entry.failed.toString()))
        }
    }
    Column(modifier) {
        SettingsCard {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = entry?.fileName ?: stringResource(R.string.import_detail_title),
                    color = colors.fgPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                ImportStatsColumn(stats)
                // The albums this run built, by name, so the detail records which albums it created rather
                // than only how many photos it brought in.
                if (detail.albums.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    ImportAlbumsSummary(albums = detail.albums, showCounts = true)
                }
            }
        }
    }
}

/**
 * The corner chip on a detail cell whose photo the run did not upload because Drive already held an
 * identical copy. Kept small and anchored in a corner so it marks the cell without covering it; the accent
 * fill with a white label matches the app's badge treatment and stays legible over any thumbnail.
 */
@Composable
private fun AlreadyInDriveBadge(modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Text(
        text = stringResource(R.string.import_detail_already_badge),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(6.dp)
            .background(colors.accent, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/**
 * The bottom bar of the detail. It shows the undo outcome once a run has been reversed, a running note
 * while an undo is in flight, or the destructive "Undo this import" button when the run still has photos
 * on Drive to remove. Matched to the review screen's bottom bar: a page-coloured strip over a hairline.
 */
@Composable
private fun ImportUndoBar(
    canUndo: Boolean,
    undoInFlight: Boolean,
    undoResult: UndoResult?,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.pageBg),
    ) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.line2))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                undoResult != null -> Text(
                    text = stringResource(
                        R.string.import_undo_result,
                        undoResult.undone,
                        undoResult.keptChanged,
                        undoResult.failed,
                    ),
                    color = colors.fgDim,
                    fontSize = 13.5.sp,
                )
                undoInFlight -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    CircularProgressIndicator(
                        color = colors.errorColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.import_undo_running),
                        color = colors.fgDim,
                        fontSize = 13.5.sp,
                    )
                }
                canUndo -> DestructiveButton(
                    label = stringResource(R.string.import_undo),
                    onClick = onUndo,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
