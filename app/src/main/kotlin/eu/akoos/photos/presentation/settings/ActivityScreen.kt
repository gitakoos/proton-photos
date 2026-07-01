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

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsPillHeader
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

private enum class ActivityTab { Active, History }

/**
 * View of the background transfers, reached from the Sync status card. Two tabs: Active shows what is
 * running right now (backup upload, album/gallery downloads, offline pinning) plus the photos still
 * waiting to upload; History shows the recent finished transfers from [TransferCenter].
 */
@Composable
fun ActivityScreen(
    onBack: () -> Unit,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    var tab by rememberSaveable { mutableStateOf(ActivityTab.Active) }

    // The photos in flight right now, so the queue grid below the progress panel doesn't repeat
    // a photo that the panel is already showing as uploading.
    val activeUploadUris = state.uploadEvents
        .filter { it.status == UploadEventStatus.Uploading || it.status == UploadEventStatus.Encrypting }
        .map { it.uri }
        .toSet()
    val queuedUris = state.pendingUris.filterNot { it in activeUploadUris }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentTop = floatingHeaderContentTopPadding()

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = contentTop, bottom = navBottom + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ── Active / History switch ──────────────────────────────────────────
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    ActivityTabSwitch(tab = tab, onTab = { tab = it })
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (tab == ActivityTab.Active) {
                // Upload progress (only while a batch runs). The file list is expanded up front on
                // this dedicated screen, and the queue grid that follows sits close so the two read
                // as one upload story rather than two separate blocks.
                if (state.isUploading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionLabel(stringResource(R.string.activity_uploading))
                            Spacer(Modifier.height(8.dp))
                            SettingsCard {
                                Spacer(Modifier.height(12.dp))
                                SyncProgressPanel(
                                    done = state.uploadDone,
                                    total = state.uploadTotal,
                                    events = state.uploadEvents,
                                    bytesPerSecond = null,
                                    initiallyExpanded = true,
                                )
                            }
                            Spacer(Modifier.height(if (queuedUris.isNotEmpty()) 12.dp else 24.dp))
                        }
                    }
                }

                // Downloads: album jobs (WorkManager, cancelable) + gallery batches (TransferCenter).
                if (state.downloads.isNotEmpty() || state.galleryDownloads.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionLabel(stringResource(R.string.activity_downloading))
                            Spacer(Modifier.height(8.dp))
                            SettingsCard {
                                state.downloads.forEachIndexed { i, dl ->
                                    DownloadRow(dl, onCancel = { viewModel.cancelDownload(dl.id) })
                                    if (i < state.downloads.lastIndex || state.galleryDownloads.isNotEmpty()) RowDivider()
                                }
                                state.galleryDownloads.forEachIndexed { i, t ->
                                    TransferRow(stringResource(R.string.activity_photos), t.done, t.total)
                                    if (i < state.galleryDownloads.lastIndex) RowDivider()
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }

                // Offline pinning batches.
                if (state.offlineTransfers.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionLabel(stringResource(R.string.activity_offline))
                            Spacer(Modifier.height(8.dp))
                            SettingsCard {
                                state.offlineTransfers.forEachIndexed { i, t ->
                                    TransferRow(stringResource(R.string.activity_photos), t.done, t.total)
                                    if (i < state.offlineTransfers.lastIndex) RowDivider()
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }

                // Upload queue thumbnails (issue #16): the photos still waiting, minus the ones the
                // progress panel above already shows as uploading, so nothing appears twice.
                if (queuedUris.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionLabel(stringResource(R.string.activity_pending, queuedUris.size))
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    items(queuedUris, key = { it }) { uri ->
                        AsyncImage(
                            model = Uri.parse(uri),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.bg2),
                        )
                    }
                }

                if (!state.hasActivity) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyLabel(stringResource(R.string.activity_empty))
                    }
                }
            } else {
                // History tab: recent finished transfers, newest first.
                if (state.history.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyLabel(stringResource(R.string.activity_history_empty))
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SettingsCard {
                                state.history.forEachIndexed { i, e ->
                                    HistoryRow(e)
                                    if (i < state.history.lastIndex) RowDivider()
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.activity_history_clear),
                                color = FgMute,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable { viewModel.clearHistory() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        SettingsPillHeader(title = stringResource(R.string.activity_title), onBack = onBack)
    }
}

/** One running album download: the album name, a done/total count, a cancel button, and a bar. */
@Composable
private fun DownloadRow(dl: ActivityViewModel.Download, onCancel: () -> Unit) {
    val colors = AppColors.current
    val fraction = if (dl.total > 0) (dl.done.toFloat() / dl.total).coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                dl.albumName,
                color = FgPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Text("${dl.done} / ${dl.total}", color = FgMute, fontSize = 12.sp)
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = FgMute,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancel)
                    .padding(4.dp)
                    .size(18.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.line2),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(colors.accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** A non-cancelable transfer (gallery download / offline pin): a label, a done/total count, a bar. */
@Composable
private fun TransferRow(label: String, done: Int, total: Int) {
    val colors = AppColors.current
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = FgPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Text("$done / $total", color = FgMute, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.line2),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(colors.accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** One finished transfer: a kind icon, a localized label, and a relative timestamp. */
@Composable
private fun HistoryRow(e: TransferCenter.HistoryEntry) {
    val colors = AppColors.current
    val icon: ImageVector = when (e.kind) {
        TransferCenter.Kind.UPLOAD.name -> Icons.Default.CloudUpload
        TransferCenter.Kind.OFFLINE.name -> Icons.Default.DownloadForOffline
        else -> Icons.Default.CloudDownload
    }
    val label = when (e.kind) {
        TransferCenter.Kind.UPLOAD.name -> stringResource(R.string.activity_hist_upload, e.count)
        TransferCenter.Kind.OFFLINE.name -> stringResource(R.string.activity_hist_offline, e.count)
        else -> if (!e.name.isNullOrBlank()) {
            stringResource(R.string.activity_hist_download_named, e.count, e.name)
        } else {
            stringResource(R.string.activity_hist_download, e.count)
        }
    }
    val ago = DateUtils.getRelativeTimeSpanString(
        e.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    val canExpand = e.uris.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canExpand) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = FgMute, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                color = FgPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Text(ago, color = FgMute, fontSize = 12.sp)
            if (canExpand) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = FgMute,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp),
                )
            }
        }
        if (expanded && canExpand) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                e.uris.forEach { u ->
                    AsyncImage(
                        model = Uri.parse(u),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.bg2),
                    )
                }
            }
        }
    }
}

/** Centered segmented control that flips the screen between the Active and History views. */
@Composable
private fun ActivityTabSwitch(tab: ActivityTab, onTab: (ActivityTab) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(PillBg, RoundedCornerShape(20.dp))
                .border(0.5.dp, PillBorder, RoundedCornerShape(20.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ActivitySegment(stringResource(R.string.activity_tab_active), tab == ActivityTab.Active) {
                onTab(ActivityTab.Active)
            }
            ActivitySegment(stringResource(R.string.activity_tab_history), tab == ActivityTab.History) {
                onTab(ActivityTab.History)
            }
        }
    }
}

@Composable
private fun ActivitySegment(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    Text(
        label,
        color = if (selected) Color.White else colors.fgPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) Accent else Color.Transparent, RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
    )
}

/** Centered muted message used by both tabs when there is nothing to show. */
@Composable
private fun EmptyLabel(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(240.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = FgMute, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
