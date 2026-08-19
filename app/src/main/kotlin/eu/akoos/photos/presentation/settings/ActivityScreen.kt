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
import androidx.compose.material.icons.filled.BatteryAlert
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
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.presentation.theme.PillBorder

enum class ActivityTab { Uploads, Downloads, History }

/**
 * View of the background transfers, reached from the Sync status card. Three tabs: Uploads shows the
 * backup upload in flight plus the photos still waiting to upload; Downloads shows the album/gallery
 * downloads and offline pinning; History shows the recent finished transfers from [TransferCenter].
 *
 * [initialTab] selects which tab is shown on open, so the avatar's upload / download indicators can
 * land the user straight on the matching tab. Defaults to Uploads for the plain Settings entry.
 */
@Composable
fun ActivityScreen(
    onBack: () -> Unit,
    initialTab: ActivityTab = ActivityTab.Uploads,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }

    // The photos in flight right now, so the queue grid below the progress panel doesn't repeat
    // a photo that the panel is already showing as uploading.
    val activeUploadUris = state.uploadEvents
        .filter { it.status == UploadEventStatus.Uploading || it.status == UploadEventStatus.Encrypting }
        .map { it.uri }
        .toSet()
    // After a user stop the still-pending photos are suppressed from the active-transfer card (they
    // stay pending in the DB for a later auto-backup); the item in transit keeps showing via
    // uploadEvents until it finishes. Cleared automatically when a new batch starts uploading.
    val queuedUris = if (state.uploadStopped) emptyList()
        else state.pendingUris.filterNot { it in activeUploadUris }

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
            // ── Uploads / Downloads / History switch ─────────────────────────────
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    ActivityTabSwitch(tab = tab, onTab = { tab = it })
                    // Debug-only preview: fills all three tabs with sample transfers so the cards can
                    // be inspected without a real upload/download (which finishes before this opens).
                    if (BuildConfig.DEBUG) {
                        val testOn by viewModel.testMode.collectAsStateWithLifecycle()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (testOn) "Test mode: ON" else "Test mode",
                            color = if (testOn) colors.accent else colors.fgMute,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { viewModel.toggleTestMode() }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (tab == ActivityTab.Uploads) {
                // One row per photo: thumbnail on the left, a status pill saying what is happening to
                // the file, and a live bar, all in this screen's card style. The ones encrypting or
                // uploading come first, then the ones still waiting; each drops out as it finishes.
                val activeUploads = state.uploadEvents.filter {
                    it.status == UploadEventStatus.Uploading || it.status == UploadEventStatus.Encrypting
                }
                // Why nothing is moving, above the queue rather than below it. The upload workers
                // require the battery not to be low, so under that floor the system never starts
                // them and no progress is reported at all: this screen is where the photos are
                // visibly waiting, so it is where the reason belongs.
                if (state.backupHeldByBattery) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SettingsCard {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.BatteryAlert,
                                        contentDescription = null,
                                        tint = colors.fgMute,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        stringResource(R.string.activity_waiting_battery),
                                        color = colors.fgDim,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
                val hasBackupCard = activeUploads.isNotEmpty() || queuedUris.isNotEmpty()
                if (hasBackupCard || state.uploadTransfers.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionLabel(stringResource(R.string.activity_uploading))
                            Spacer(Modifier.height(8.dp))
                            // Backup pipeline: the encrypting/uploading photos and the queued ones, in
                            // one card with a cancel for the whole burst.
                            if (hasBackupCard) {
                                SettingsCard {
                                    BatchCancelHeader(
                                        label = stringResource(R.string.activity_uploading),
                                        done = state.uploadDone,
                                        total = state.uploadDone + activeUploads.size + queuedUris.size,
                                        cancelable = true,
                                        onCancel = { viewModel.cancelUpload() },
                                    )
                                    RowDivider()
                                    activeUploads.forEachIndexed { i, evt ->
                                        val frac = if (evt.sizeBytes > 0L) {
                                            (evt.doneBytes.toFloat() / evt.sizeBytes).coerceIn(0f, 1f)
                                        } else {
                                            null
                                        }
                                        val label = if (evt.status == UploadEventStatus.Encrypting) {
                                            stringResource(R.string.upload_status_encrypting)
                                        } else {
                                            stringResource(R.string.upload_status_uploading)
                                        }
                                        TransferPhotoRow(uri = evt.uri, stateLabel = label, progress = frac)
                                        if (i < activeUploads.lastIndex || queuedUris.isNotEmpty()) RowDivider()
                                    }
                                    queuedUris.forEachIndexed { i, uri ->
                                        TransferPhotoRow(
                                            uri = uri,
                                            stateLabel = stringResource(R.string.upload_status_queued),
                                            progress = null,
                                        )
                                        if (i < queuedUris.lastIndex) RowDivider()
                                    }
                                }
                            }
                            // TransferCenter upload batches: a metadata-edit batch (named, many photos)
                            // or the editor's single save-copy upload. One card per batch with the batch's
                            // real count and its photos in flight, mirroring the Downloads thumbnail cards.
                            state.uploadTransfers.forEach { t ->
                                // Per-photo rows for a metadata batch: each photo shows its OWN status
                                // (keyed by its linkId in itemKeys) and drops off when it finishes. Other
                                // upload transfers (the editor's save-copy) fall back to the remaining
                                // thumbnails under one "uploading" label.
                                val rows: List<Pair<String, String?>> = if (t.itemKeys.isNotEmpty()) {
                                    t.items.indices.mapNotNull { i ->
                                        val key = t.itemKeys.getOrNull(i) ?: return@mapNotNull null
                                        val status = t.itemStatus[key] ?: return@mapNotNull null
                                        t.items[i] to status
                                    }
                                } else {
                                    t.items.drop(t.done).map { it to null }
                                }
                                Spacer(Modifier.height(8.dp))
                                SettingsCard {
                                    BatchCancelHeader(
                                        label = t.name ?: stringResource(R.string.activity_uploading),
                                        done = t.done,
                                        total = t.total,
                                        cancelable = t.cancelable,
                                        onCancel = { viewModel.cancelTransfer(t.id) },
                                    )
                                    if (rows.isNotEmpty()) {
                                        RowDivider()
                                        rows.forEachIndexed { i, (uri, status) ->
                                            TransferPhotoRow(
                                                uri = uri,
                                                stateLabel = status
                                                    ?: stringResource(R.string.upload_status_uploading),
                                                progress = null,
                                            )
                                            if (i < rows.lastIndex) RowDivider()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyLabel(stringResource(R.string.activity_empty))
                    }
                }
            } else if (tab == ActivityTab.Downloads) {
                // Downloads: album jobs (WorkManager, cancelable) + gallery batches (TransferCenter).
                if (state.downloads.isNotEmpty() || state.galleryDownloads.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionLabel(stringResource(R.string.activity_downloading))
                            Spacer(Modifier.height(8.dp))
                            // Album downloads plus any gallery batch that has no per-photo thumbnails
                            // fall back to a single labelled progress row, kept in one shared card.
                            val simpleGalleryDownloads = state.galleryDownloads.filter { it.items.isEmpty() }
                            if (state.downloads.isNotEmpty() || simpleGalleryDownloads.isNotEmpty()) {
                                SettingsCard {
                                    state.downloads.forEachIndexed { i, dl ->
                                        DownloadRow(dl, onCancel = { viewModel.cancelDownload(dl.id) })
                                        if (i < state.downloads.lastIndex || simpleGalleryDownloads.isNotEmpty()) RowDivider()
                                    }
                                    simpleGalleryDownloads.forEachIndexed { i, t ->
                                        TransferRow(stringResource(R.string.activity_photos), t.done, t.total)
                                        if (i < simpleGalleryDownloads.lastIndex) RowDivider()
                                    }
                                }
                            }
                            // Gallery selection batches with thumbnails: one card per batch, listing
                            // the photos still in flight as individual rows. The first t.done photos
                            // are already saved and drop off the front.
                            state.galleryDownloads.filter { it.items.isNotEmpty() }.forEach { t ->
                                val remaining = t.items.drop(t.done)
                                if (remaining.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    SettingsCard {
                                        // Batch header: how many photos are left, plus a cancel that
                                        // stops the whole download at once.
                                        BatchCancelHeader(
                                            label = stringResource(R.string.activity_downloading_state),
                                            done = t.done,
                                            total = t.total,
                                            cancelable = t.cancelable,
                                            onCancel = { viewModel.cancelTransfer(t.id) },
                                        )
                                        RowDivider()
                                        remaining.forEachIndexed { i, item ->
                                            TransferPhotoRow(
                                                uri = item,
                                                stateLabel = stringResource(R.string.activity_downloading_state),
                                                progress = null,
                                            )
                                            if (i < remaining.lastIndex) RowDivider()
                                        }
                                    }
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

                if (state.downloads.isEmpty() && state.galleryDownloads.isEmpty() && state.offlineTransfers.isEmpty()) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Live album cover (the most recent photo saved to the device), with a placeholder box so
        // the row keeps a thumbnail slot like the per-photo rows before the first cover is available.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.bg2),
            contentAlignment = Alignment.Center,
        ) {
            if (dl.coverUri != null) {
                AsyncImage(
                    model = Uri.parse(dl.coverUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = colors.fgMute,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    dl.albumName,
                    color = FgPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text("${dl.done} / ${dl.total}", color = FgMute, fontSize = 12.sp)
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
        TransferCenter.Kind.UPLOAD.name -> if (!e.name.isNullOrBlank()) {
            e.name
        } else {
            stringResource(R.string.activity_hist_upload, e.count)
        }
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

/** One photo in a transfer batch: thumbnail on the left, a status pill, an optional live bar, and an
 *  optional cancel button. Shared by the Uploads tab and the per-photo gallery downloads. */
@Composable
private fun TransferPhotoRow(
    uri: String,
    stateLabel: String,
    progress: Float?,
    onCancel: (() -> Unit)? = null,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = Uri.parse(uri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.bg2),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Status pill: what is happening to this file right now.
            Text(
                stateLabel,
                color = colors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.pillBg)
                    .border(0.5.dp, colors.pillBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            if (progress != null) {
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
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(colors.accent, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        if (onCancel != null) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = colors.fgMute,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancel)
                    .padding(4.dp)
                    .size(18.dp),
            )
        }
    }
}

/** Top row of an upload/download batch card: the operation label, the done/total count, and a cancel
 *  for the whole batch. Shared by both tabs so they read identically. */
@Composable
private fun BatchCancelHeader(label: String, done: Int, total: Int, cancelable: Boolean, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = FgPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text("$done / $total", color = FgMute, fontSize = 12.sp)
        if (cancelable) {
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
    }
}

/** Centered segmented control that flips the screen between the Uploads, Downloads and History views. */
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
            ActivitySegment(stringResource(R.string.activity_tab_uploads), tab == ActivityTab.Uploads) {
                onTab(ActivityTab.Uploads)
            }
            ActivitySegment(stringResource(R.string.activity_tab_downloads), tab == ActivityTab.Downloads) {
                onTab(ActivityTab.Downloads)
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

/** Centered muted message used by the tabs when there is nothing to show. */
@Composable
private fun EmptyLabel(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(240.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = FgMute, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
