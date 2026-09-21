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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.data.db.entity.ImportHistoryEntity
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.viewer.formatMsWithTime

/**
 * Picks a Google Takeout / Ente `.zip` and walks it through the import queue: staging progress, a
 * reviewable thumbnail grid, and (once wired) the upload. The heavy lifting is the workers'; this
 * screen only launches the picker and renders [ImportUiState]. Reached from a Settings row shown only
 * when signed in, so no account gate is repeated here.
 */
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val detailRunId by viewModel.detailRunId.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val undoInFlight by viewModel.undoInFlight.collectAsStateWithLifecycle()
    val undoResult by viewModel.undoResult.collectAsStateWithLifecycle()
    val albumMode by viewModel.albumMode.collectAsStateWithLifecycle()
    val newCount by viewModel.newCount.collectAsStateWithLifecycle()
    val reviewAlreadyCount by viewModel.reviewAlreadyCount.collectAsStateWithLifecycle()
    val hasAlbums by viewModel.hasAlbums.collectAsStateWithLifecycle()
    val albumSummary by viewModel.albumSummary.collectAsStateWithLifecycle()
    val doneAlbums by viewModel.doneAlbums.collectAsStateWithLifecycle()
    val colors = AppColors.current

    // Zip filter with a permissive fallback: some providers report an export as octet-stream, so the
    // concrete zip types are hints and `*/*` keeps the file selectable everywhere.
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onZipPicked) }
    val launchPicker: () -> Unit = {
        pickLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
    }

    // The review grid draws its own floating header and full-height grid, so it stands outside the
    // scrolling sub-page scaffold the other states share.
    when (val s = state) {
        is ImportUiState.Review -> {
            ImportReviewSection(
                state = s,
                newCount = newCount,
                reviewAlreadyCount = reviewAlreadyCount,
                hasAlbums = hasAlbums,
                albumSummary = albumSummary,
                albumMode = albumMode,
                onAlbumModeChange = viewModel::setAlbumMode,
                onBack = onBack,
                onExclude = viewModel::excludeSelected,
                onRestore = viewModel::restoreSelected,
                onConfirm = viewModel::confirmImport,
                onDiscard = viewModel::discardImport,
            )
            return
        }
        else -> Unit
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsSubPageScaffold(title = stringResource(R.string.import_title), onBack = onBack) {
            when (val s = state) {
                is ImportUiState.Idle -> {
                    SettingsCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.import_explainer),
                                color = colors.fgDim,
                                fontSize = 13.5.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(20.dp))
                            PrimaryButton(
                                label = stringResource(R.string.import_choose_zip),
                                onClick = launchPicker,
                                icon = Icons.Default.Download,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (history.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        ImportHistorySection(
                            history,
                            onOpen = viewModel::openHistoryDetail,
                            onDelete = viewModel::deleteHistory,
                        )
                    }
                }

                is ImportUiState.Staging -> ImportProgressCard(
                    done = s.done,
                    total = s.total,
                    titleRes = R.string.import_staging_title,
                    onCancel = viewModel::cancelImport,
                )

                is ImportUiState.Uploading -> ImportProgressCard(
                    done = s.done,
                    total = s.total,
                    titleRes = R.string.import_uploading_title,
                    onCancel = viewModel::cancelImport,
                )

                is ImportUiState.Done -> {
                    // Everything the confirmed set held was already in Drive: say so plainly under the title
                    // rather than lead with an "imported 0" figure. The figures then read as soft rows in the
                    // same design as the albums, each shown only when it carries a count.
                    val allPresent = s.imported == 0 && s.failed == 0 && s.alreadyInDrive > 0
                    val stats = buildList {
                        if (s.imported > 0) {
                            add(ImportStat(Icons.Outlined.CloudDone, stringResource(R.string.import_stat_imported), s.imported.toString()))
                        }
                        if (s.alreadyInDrive > 0) {
                            add(ImportStat(Icons.Outlined.Cloud, stringResource(R.string.import_stat_already), s.alreadyInDrive.toString()))
                        }
                        if (s.skipped > 0) {
                            add(ImportStat(Icons.Outlined.RemoveCircleOutline, stringResource(R.string.import_stat_skipped), s.skipped.toString()))
                        }
                        if (s.failed > 0) {
                            add(ImportStat(Icons.Outlined.ErrorOutline, stringResource(R.string.import_stat_failed), s.failed.toString()))
                        }
                    }
                    SettingsCard {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.import_done_title),
                                color = colors.fgPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (allPresent) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.import_done_all_present),
                                    color = colors.fgDim,
                                    fontSize = 13.5.sp,
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            ImportStatsColumn(stats)
                            // The albums the run built, as a proper named section. Counts show only when the
                            // run actually filed photos in, so an empty-albums run lists the shells by name
                            // without a misleading photo count.
                            if (doneAlbums.isNotEmpty()) {
                                Spacer(Modifier.height(14.dp))
                                ImportAlbumsSummary(
                                    albums = doneAlbums,
                                    showCounts = s.photosAddedToAlbums > 0,
                                )
                            }
                        }
                    }
                    // Open the run's photo grid, so the finished import shows which photos went up, not only
                    // the albums and counts. Reuses the Recent-imports detail (grid, per-photo already-in-
                    // Drive marks, and the safe undo) for the run that just completed.
                    if (s.runId.isNotEmpty() && (s.imported > 0 || s.alreadyInDrive > 0)) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.import_done_view_photos),
                            color = colors.accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.openHistoryDetail(s.runId) }
                                .padding(vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        label = stringResource(R.string.import_done_button),
                        // Return to the import home rather than popping to Settings, so the picker and
                        // the refreshed Recent-imports list show.
                        onClick = viewModel::reset,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is ImportUiState.Failed -> {
                    SettingsCard {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.import_failed_title),
                                color = colors.fgPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.import_failed_msg),
                                color = colors.fgDim,
                                fontSize = 13.5.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        label = stringResource(R.string.import_choose_zip),
                        onClick = launchPicker,
                        icon = Icons.Default.Download,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Review is handled above, outside the scaffold.
                is ImportUiState.Review -> Unit
            }
        }

        // Done and Failed are terminal cards: the back gesture returns to the import home (Idle) rather
        // than popping to Settings, matching the on-screen button, so a finished run never strands the
        // user. Disabled in the other states so Idle's back still leaves the screen.
        BackHandler(enabled = state is ImportUiState.Done || state is ImportUiState.Failed) {
            viewModel.reset()
        }

        // The Recent-imports detail overlays the home when a history row is tapped. Its own back handler
        // is registered while it is open, so back closes the overlay before this screen's.
        detailRunId?.let { runId ->
            ImportHistoryDetail(
                detail = detail,
                undoInFlight = undoInFlight,
                undoResult = undoResult,
                onUndo = { viewModel.undoImport(runId) },
                onClose = viewModel::closeHistoryDetail,
                onRequestThumbnail = viewModel::requestThumbnailDecrypt,
                onCancelThumbnail = viewModel::cancelThumbnailDecrypt,
                onWarmThumbnails = viewModel::warmRunThumbnails,
                onCoolThumbnails = viewModel::cancelRunThumbnails,
            )
        }
    }
}

/**
 * The in-progress card shared by staging and upload: an indeterminate bar with a scanning note until
 * the entry count is known, then a determinate bar with the running `done / total`.
 */
@Composable
private fun ImportProgressCard(done: Int, total: Int, titleRes: Int, onCancel: () -> Unit) {
    val colors = AppColors.current
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(titleRes),
                color = colors.fgPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            if (total <= 0) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.trackBg,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.import_scanning),
                    color = colors.fgDim,
                    fontSize = 13.sp,
                )
            } else {
                LinearProgressIndicator(
                    progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.trackBg,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.import_progress_count, done, total),
                    color = colors.fgPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            // In-app stop, so the run can be halted without reaching for the notification. The worker
            // cancels cooperatively and leaves a partial history row for whatever it already sent.
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.cancel),
                color = colors.errorColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The "Recent imports" list under the picker: a compact header over a card of one row per finished
 * run, newest first. A row with an upload ledger (a [ImportHistoryEntity.runId]) opens its detail on
 * tap; an older row that predates the ledger carries none and stays inert.
 */
@Composable
private fun ImportHistorySection(
    entries: List<ImportHistoryEntity>,
    onOpen: (String) -> Unit,
    onDelete: (Long, String?) -> Unit,
) {
    val colors = AppColors.current
    // The run a delete would remove, held while its confirm sheet is open so the tap does not act until
    // the user confirms. Deleting forgets the local record and its undo option; nothing on Drive changes.
    var pendingDelete by remember { mutableStateOf<ImportHistoryEntity?>(null) }
    Text(
        text = stringResource(R.string.import_history_title),
        color = colors.fgMute,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
    SettingsCard {
        Column(Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = colors.line, thickness = 0.5.dp)
                ImportHistoryRow(entry, onOpen, onDelete = { pendingDelete = entry })
            }
        }
    }
    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.import_history_delete_title),
            message = stringResource(R.string.import_history_delete_message),
            confirmLabel = stringResource(R.string.import_history_delete_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                onDelete(entry.id, entry.runId)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }
}

/** One finished run: the archive's file name over a secondary line of when it ran and its counts, with a
 *  trailing delete that forgets the run's local record. Tapping the text of a run that recorded an upload
 *  ledger opens its detail; a ledger-less older run does not open but can still be deleted. */
@Composable
private fun ImportHistoryRow(
    entry: ImportHistoryEntity,
    onOpen: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppColors.current
    val counts = stringResource(R.string.import_history_row, entry.uploaded)
    val summary = if (entry.skipped > 0 || entry.failed > 0) {
        counts + ", " + stringResource(R.string.import_history_row_extra, entry.skipped, entry.failed)
    } else {
        counts
    }
    val runId = entry.runId
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (runId != null) Modifier.clickable { onOpen(runId) } else Modifier)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        ) {
            Text(
                text = entry.fileName,
                color = colors.fgPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = formatMsWithTime(entry.importedAt) + " · " + summary,
                color = colors.fgDim,
                fontSize = 12.5.sp,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.padding(end = 4.dp)) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = stringResource(R.string.import_history_delete_title),
                tint = colors.fgMute,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
