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

package eu.akoos.photos.presentation.folders

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * Browse one device folder's photos and upload selected ones to Drive. Long-press to enter
 * selection mode (uri-keyed); the floating bar's cloud button forces every selected local photo
 * to back up. The per-cell sync badge is rendered by [PhotoCell] and reflects live sync state, so
 * a photo flips to the green-cloud badge once its upload lands.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DeviceFolderDetailScreen(
    bucketName: String,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    onBack: () -> Unit,
    viewModel: DeviceFolderDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(bucketName) { viewModel.load(bucketName) }

    val items by viewModel.items.collectAsStateWithLifecycle()
    val selectedUris by viewModel.selectedUris.collectAsStateWithLifecycle()
    val backupProgress by viewModel.backupProgress.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val isSelectionMode = selectedUris.isNotEmpty()
    // In selection mode the back button cancels the selection instead of leaving the screen —
    // mirrors the gallery and album-detail behaviour.
    BackHandler(enabled = isSelectionMode) { viewModel.clearSelection() }

    var showBackupDialog by remember { mutableStateOf(false) }
    val shareCtx = androidx.compose.ui.platform.LocalContext.current
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    // The VM builds the system-share intent off the UI thread; the screen launches the chooser.
    LaunchedEffect(Unit) {
        viewModel.shareIntent.collect { intent ->
            shareCtx.startActivity(android.content.Intent.createChooser(intent, shareChooserTitle))
        }
    }

    // Add selected to a cloud album — reuses the gallery's picker sheet so the styling and
    // behaviour (cloud-backed photos join now, local-only photos upload then join) stay identical.
    var showAddToAlbumSheet by remember { mutableStateOf(false) }
    val addToAlbumSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val albums by viewModel.albums.collectAsStateWithLifecycle()

    // Bulk delete — reuses the gallery's delete sheet + the system trash-dialog launcher, so a
    // device-folder selection deletes the same way (and with the same options) as the timeline.
    var showDeleteSheet by remember { mutableStateOf(false) }
    val pendingDeleteIntent by viewModel.pendingDeleteIntent.collectAsStateWithLifecycle()
    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeletePermissionGranted()
        else viewModel.clearPendingDeleteIntent()
    }
    LaunchedEffect(pendingDeleteIntent) {
        val pi = pendingDeleteIntent ?: return@LaunchedEffect
        deletePermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    // Cover = the newest item's image (the list is sorted newest-first). Device folders only
    // ever hold local items, so the cover comes from the local URI.
    val coverModel: Any? = remember(items) {
        items.firstOrNull()?.let { item ->
            when (item) {
                is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
                is GalleryItem.Synced -> Uri.parse(item.local.uri)
                is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl?.let { Uri.parse(it) }
            }
        }
    }
    val videoCount = remember(items) {
        items.count { item ->
            val mime = when (item) {
                is GalleryItem.LocalOnly -> item.local.mimeType
                is GalleryItem.Synced -> item.local.mimeType
                is GalleryItem.CloudOnly -> item.cloud.mimeType
            }
            mime.startsWith("video/")
        }
    }
    val photoCount = items.size - videoCount
    val photosText = pluralStringResource(R.plurals.count_photos_plural, photoCount, photoCount)
    val videosText = pluralStringResource(R.plurals.count_videos_plural, videoCount, videoCount)
    val countLabel = when {
        photoCount > 0 && videoCount > 0 -> "$photosText, $videosText"
        videoCount > 0 -> videosText
        else -> photosText
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Hero header — cover, folder name and count, like the cloud-album detail page.
            item(span = { GridItemSpan(maxLineSpan) }) {
                eu.akoos.photos.presentation.albums.components.AlbumHeroHeader(
                    coverModel = coverModel,
                    title = bucketName,
                    photoCountText = countLabel,
                    canRename = false,
                    titleActions = if (items.isNotEmpty()) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable { showBackupDialog = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.CloudUpload,
                                    contentDescription = stringResource(R.string.device_folder_backup_all),
                                    tint = Accent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    } else null,
                )
            }
            if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.albums_no_photos), color = FgMute, fontSize = 14.sp)
                    }
                }
            } else {
                itemsIndexed(
                    items = items,
                    key = { _, item ->
                        when (item) {
                            is GalleryItem.LocalOnly -> item.local.uri
                            is GalleryItem.Synced -> item.local.uri
                            is GalleryItem.CloudOnly -> item.cloud.linkId
                        }
                    },
                ) { index, item ->
                    val itemUri = when (item) {
                        is GalleryItem.LocalOnly -> item.local.uri
                        is GalleryItem.Synced -> item.local.uri
                        is GalleryItem.CloudOnly -> null
                    }
                    val isSelected = itemUri != null && itemUri in selectedUris
                    // Per-cell long-press menu (Select / Share / Back up), mirroring the cloud-album
                    // grid. Anchored to the cell Box so it pops where the user pressed. While already
                    // in selection mode, long-press toggles the photo instead of opening the menu.
                    var cellMenuOpen by remember { mutableStateOf(false) }
                    val cellCtx = androidx.compose.ui.platform.LocalContext.current
                    Box {
                        PhotoCell(
                            item = item,
                            selected = isSelected,
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) {
                                    if (itemUri != null) viewModel.toggleSelection(itemUri)
                                } else onPhotoClick(items, index)
                            },
                            onLongClick = {
                                if (isSelectionMode) {
                                    if (itemUri != null) viewModel.toggleSelection(itemUri)
                                } else if (itemUri != null) cellMenuOpen = true
                            },
                        )
                        if (itemUri != null) {
                            DropdownMenu(
                                expanded = cellMenuOpen,
                                onDismissRequest = { cellMenuOpen = false },
                                shape = RoundedCornerShape(16.dp),
                                containerColor = Bg0,
                                border = BorderStroke(0.5.dp, PillBorder),
                            ) {
                                // Select — the discoverable entry into multi-select.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_action_select), color = FgPrimary) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
                                    },
                                    onClick = { cellMenuOpen = false; viewModel.toggleSelection(itemUri) },
                                )
                                // Share this one photo to other apps.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_action), color = FgPrimary) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, null, tint = Accent, modifier = Modifier.size(20.dp))
                                    },
                                    onClick = { cellMenuOpen = false; viewModel.shareUris(listOf(itemUri)) },
                                )
                                // Back up — only meaningful for a photo that isn't already synced.
                                if (item !is GalleryItem.Synced) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.device_folder_upload_action), color = FgPrimary) },
                                        leadingIcon = {
                                            Icon(Icons.Default.CloudUpload, null, tint = Accent, modifier = Modifier.size(20.dp))
                                        },
                                        onClick = {
                                            cellMenuOpen = false
                                            viewModel.backUpUris(listOf(itemUri)) { outcome ->
                                                scope.launch {
                                                    val msg = if (outcome.queued > 0)
                                                        cellCtx.getString(R.string.device_folder_uploading_fmt, outcome.queued)
                                                    else
                                                        cellCtx.getString(R.string.device_folder_already_backed_up)
                                                    snackbarHostState.showSnackbar(msg)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fixed back button — floats over the grid. Cancels selection while selecting.
        IconBubble(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = if (isSelectionMode)
                stringResource(R.string.gallery_cancel_selection) else stringResource(R.string.close),
            onClick = { if (isSelectionMode) viewModel.clearSelection() else onBack() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 10.dp),
            diameter = 36.dp,
            iconSize = 18.dp,
            background = Color(0x99000000),
            borderColor = PillBorder,
            tint = Color.White,
        )

        // Selection-mode action bar — a single floating pill, matching the gallery and
        // album-detail selection toolbars: cancel, a photo/video count, then the action
        // pills (share to apps, back up to Drive).
        if (isSelectionMode) {
            val selectedItems = items.filter { item ->
                val u = when (item) {
                    is GalleryItem.LocalOnly -> item.local.uri
                    is GalleryItem.Synced    -> item.local.uri
                    is GalleryItem.CloudOnly -> null
                }
                u != null && u in selectedUris
            }
            val selectedVideos = selectedItems.count { item ->
                val mime = when (item) {
                    is GalleryItem.LocalOnly -> item.local.mimeType
                    is GalleryItem.Synced    -> item.local.mimeType
                    is GalleryItem.CloudOnly -> item.cloud.mimeType
                }
                mime.startsWith("video/")
            }
            val selectedPhotos = selectedItems.size - selectedVideos
            val selPhotosText = pluralStringResource(R.plurals.count_photos_plural, selectedPhotos, selectedPhotos)
            val selVideosText = pluralStringResource(R.plurals.count_videos_plural, selectedVideos, selectedVideos)
            val selectionLabel = when {
                selectedPhotos > 0 && selectedVideos > 0 -> "$selPhotosText, $selVideosText"
                selectedVideos > 0 -> selVideosText
                else -> selPhotosText
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Bg0.copy(alpha = 0.95f))
                    .border(0.5.dp, PillBorder, RoundedCornerShape(28.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.gallery_cancel_selection),
                    onClick = { viewModel.clearSelection() },
                    diameter = 36.dp,
                    iconSize = 18.dp,
                    background = PillBg,
                    borderColor = PillBorder,
                    tint = FgPrimary,
                )
                Text(
                    selectionLabel,
                    color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                // Add selected to a cloud album — opens the gallery's picker sheet.
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(PillBg, CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable { showAddToAlbumSheet = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PhotoAlbum,
                        contentDescription = stringResource(R.string.gallery_add_to_album),
                        tint = Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.size(4.dp))
                // Share selected to other apps — device files share their URI directly, no download.
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(PillBg, CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable { viewModel.shareSelected() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.share_action),
                        tint = Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.size(4.dp))
                // Back up selected to Drive. Resolve the snackbar copy in the handler so the count
                // matches what was queued (already-synced selections are skipped, not uploaded).
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(PillBg, CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable {
                            viewModel.uploadSelected { outcome ->
                                // The progress pill covers a started back-up; only speak up when
                                // there was nothing to do (everything already on Drive).
                                if (outcome.queued == 0) scope.launch {
                                    snackbarHostState.showSnackbar(ctx.getString(R.string.device_folder_already_backed_up))
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.CloudUpload,
                        contentDescription = stringResource(R.string.device_folder_upload_action),
                        tint = Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.size(4.dp))
                // Delete selected — same sheet + system trash dialog as the gallery, with the
                // option to also remove the Drive copy of any backed-up photos.
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(PillBg, CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable { showDeleteSheet = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.gallery_delete_selected),
                        tint = ErrorColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Bulk-delete sheet — reuses the gallery's dialog so the options (free up space / also
        // delete from Drive) and copy stay identical across the app.
        if (showDeleteSheet && selectedUris.isNotEmpty()) {
            val deleteItems = items.filter {
                val u = when (it) {
                    is GalleryItem.LocalOnly -> it.local.uri
                    is GalleryItem.Synced -> it.local.uri
                    is GalleryItem.CloudOnly -> null
                }
                u in selectedUris
            }.toSet()
            eu.akoos.photos.presentation.gallery.GalleryMultiDeleteDialog(
                selectedItems = deleteItems,
                onDismiss = { showDeleteSheet = false },
                onDelete = { freeUpSpace, deleteFromCloud ->
                    showDeleteSheet = false
                    viewModel.deleteSelected(freeUpSpace, deleteFromCloud)
                },
            )
        }

        // Add-to-album sheet — reuses the gallery's picker. Cloud-backed selections join the album
        // now; local-only selections back up first and join afterwards. Inline create is off here.
        if (showAddToAlbumSheet && selectedUris.isNotEmpty()) {
            val addCtx = androidx.compose.ui.platform.LocalContext.current
            val addItems = items.filter {
                val u = when (it) {
                    is GalleryItem.LocalOnly -> it.local.uri
                    is GalleryItem.Synced -> it.local.uri
                    is GalleryItem.CloudOnly -> null
                }
                u in selectedUris
            }.toSet()
            eu.akoos.photos.presentation.gallery.GalleryAddToAlbumDialog(
                selectedItems = addItems,
                cloudAlbums = albums,
                sheetState = addToAlbumSheetState,
                onCreateNew = { showAddToAlbumSheet = false },
                onCloudAlbumSelected = { album ->
                    showAddToAlbumSheet = false
                    viewModel.addSelectedToAlbum(album.linkId) { _, _ ->
                        scope.launch {
                            snackbarHostState.showSnackbar(addCtx.getString(R.string.device_folder_added_to_album))
                        }
                    }
                },
                onDismiss = { showAddToAlbumSheet = false },
            )
        }

        // "Back up folder" choice — upload every photo to the timeline, optionally also mirroring
        // the folder as a Drive album. The snackbar reports how many were queued vs already backed up.
        if (showBackupDialog) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            fun runBackup(asMirror: Boolean) {
                showBackupDialog = false
                viewModel.backUpAll(asMirror) { outcome ->
                    // The progress pill covers a started back-up; only speak up when there was
                    // nothing to do (everything already on Drive).
                    if (outcome.queued == 0) scope.launch {
                        snackbarHostState.showSnackbar(ctx.getString(R.string.device_folder_already_backed_up))
                    }
                }
            }
            AlertDialog(
                onDismissRequest = { showBackupDialog = false },
                containerColor = Bg0,
                title = {
                    Text(
                        stringResource(R.string.device_folder_backup_choice_title),
                        color = FgPrimary, fontWeight = FontWeight.SemiBold,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.device_folder_backup_choice_body),
                            color = FgMute, fontSize = 13.sp,
                        )
                        // Two tappable option cards — the row itself is the action, so there is
                        // no separate confirm button. "Album" mirrors the folder (keeps syncing);
                        // "photos only" is a one-time timeline upload.
                        BackupOptionRow(
                            icon = Icons.Default.PhotoAlbum,
                            title = stringResource(R.string.device_folder_backup_mirror),
                            subtitle = stringResource(R.string.device_folder_backup_mirror_sub),
                            onClick = { runBackup(asMirror = true) },
                        )
                        BackupOptionRow(
                            icon = Icons.Default.CloudUpload,
                            title = stringResource(R.string.device_folder_backup_timeline),
                            subtitle = stringResource(R.string.device_folder_backup_timeline_sub),
                            onClick = { runBackup(asMirror = false) },
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showBackupDialog = false }) {
                        Text(stringResource(R.string.cancel), color = FgMute)
                    }
                },
            )
        }

        // Live back-up progress — the shared floating pill, driven by the folder's own sync-state
        // stream so it tracks real upload completion (encrypt + upload), then auto-hides.
        backupProgress?.let { p ->
            eu.akoos.photos.presentation.common.OperationProgressPill(
                progress = eu.akoos.photos.presentation.common.OperationProgress(
                    done = p.done,
                    total = p.total,
                    label = stringResource(R.string.device_folder_backing_up_fmt, p.done, p.total),
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp),
            )
        }

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * One tappable choice row in the "Back up folder" dialog: an accent-tinted icon bubble, a title
 * and a one-line explanation. The whole row is the action — there is no separate confirm button.
 */
@Composable
private fun BackupOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(0.5.dp, PillBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(PillBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = FgMute, fontSize = 12.sp)
        }
    }
}
