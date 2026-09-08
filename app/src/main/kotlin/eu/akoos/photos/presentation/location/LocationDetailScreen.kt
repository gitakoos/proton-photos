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

package eu.akoos.photos.presentation.location

import android.app.Activity
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.MultiStripState
import eu.akoos.photos.presentation.common.SelectionAction
import eu.akoos.photos.presentation.common.SelectionDrawer
import eu.akoos.photos.presentation.common.allLocalOnly
import eu.akoos.photos.presentation.common.anyCloudOnly
import eu.akoos.photos.presentation.common.anyLocalOnly
import eu.akoos.photos.presentation.common.anyMetadataEditable
import eu.akoos.photos.presentation.common.favoriteSelectionAction
import eu.akoos.photos.presentation.common.favoriteTurnsOn
import eu.akoos.photos.presentation.common.hasDownloadable
import eu.akoos.photos.presentation.common.offlinePinnableLinkIds
import eu.akoos.photos.presentation.common.offlineTurnsOn
import eu.akoos.photos.presentation.gallery.LocalThumbnailUrls
import eu.akoos.photos.presentation.gallery.MoveToFolderHost
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * The located-photos body behind the full-screen place page ([PlaceCityScreen]): the hero header,
 * the photo grid with drag multi-select, the shared selection drawer with the full action set (share,
 * add-to-album, move-to-folder, favourite, back up, download, offline, edit metadata, strip, hide,
 * delete) and the save-as-album flow. [modifier] shapes the host, [contentTopPadding] clears a
 * floating header, [headerOverlay] draws one on top, and [showDragHandle] adds a pull-down grip.
 * [onEditMetadata] opens the date + place editor for the current selection, matching the timeline.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LocationPhotosContent(
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Bg0,
    contentTopPadding: Dp = 0.dp,
    showDragHandle: Boolean = false,
    onEditMetadata: (items: List<GalleryItem>) -> Unit = {},
    headerOverlay: (@Composable BoxScope.() -> Unit)? = null,
    viewModel: LocationDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedItems by viewModel.selectedItems.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val isSignedIn by viewModel.isSignedIn.collectAsStateWithLifecycle()
    val pendingDeleteIntent by viewModel.pendingDeleteIntent.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val pendingStripIntent by viewModel.pendingStripIntent.collectAsStateWithLifecycle()
    val multiStripState by viewModel.multiStripState.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val offlinePinIds by viewModel.offlinePinIds.collectAsStateWithLifecycle()
    val favoriteState by viewModel.favoriteState.collectAsStateWithLifecycle()

    val isSelectionMode = selectedItems.isNotEmpty()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val showScrollTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 4 } }

    // In selection mode the back button cancels the selection instead of dismissing the sheet -
    // mirrors the gallery, album-detail and device-folder behaviour.
    BackHandler(enabled = isSelectionMode) { viewModel.clearSelection() }

    var showSaveAsAlbumConfirm by remember { mutableStateOf(false) }
    var showAddToAlbumSheet by remember { mutableStateOf(false) }
    val addToAlbumSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The picker's own "New album" row, which names an album and then adds the selection to it.
    var showCreateAlbumInline by remember { mutableStateOf(false) }
    // Move the device selection into another folder, logged-out, device-data only. The shared host
    // owns the picker + its own write-consent launcher; the selection is snapshotted in the VM.
    var showMoveSheet by remember { mutableStateOf(false) }
    val moveTargetFolders by viewModel.moveTargetFolders.collectAsStateWithLifecycle()
    val pendingMoveIntent by viewModel.pendingMoveIntent.collectAsStateWithLifecycle()
    var showDeleteSheet by remember { mutableStateOf(false) }
    var showStripPicker by remember { mutableStateOf(false) }
    // The selection's hide split while its confirmation is up, null when none is. Holding the split
    // rather than a flag is what lets the sheet describe the photos the tap was made on.
    var hideConfirmSplit by remember {
        mutableStateOf<eu.akoos.photos.data.hidden.HiddenFolderRecords.HideSplit?>(null)
    }

    // The VM builds the system-share intent off the UI thread; the screen launches the chooser.
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    LaunchedEffect(Unit) {
        viewModel.shareIntent.collect { intent ->
            runCatching { context.startActivity(android.content.Intent.createChooser(intent, shareChooserTitle)) }
        }
    }
    // Offline-batch result - the pins apply optimistically, this only reports the outcome.
    val offlineRemovedMsg = stringResource(R.string.offline_removed)
    LaunchedEffect(Unit) {
        viewModel.offlineBatchResult.collect { count ->
            when {
                count > 0 -> snackbarHostState.showSnackbar(
                    context.resources.getQuantityString(R.plurals.offline_batch_result, count, count),
                )
                count < 0 -> snackbarHostState.showSnackbar(offlineRemovedMsg)
            }
        }
    }
    // An action that did not do all it said says so; the message already reads for the user.
    LaunchedEffect(Unit) {
        viewModel.actionFailure.collect { snackbarHostState.showSnackbar(it) }
    }
    // A download says it began the moment it does; its progress lives on the Activity screen and the
    // selection clears straight away. Same wording the timeline and search use for the same moment.
    val downloadStartedMsg = stringResource(R.string.download_started_background)
    LaunchedEffect(Unit) {
        viewModel.downloadStarted.collect { snackbarHostState.showSnackbar(downloadStartedMsg) }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val savedAsAlbumFmt = stringResource(R.string.location_saved_as_album_fmt)
    LaunchedEffect(state.saveAsAlbumResult) {
        val r = state.saveAsAlbumResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(savedAsAlbumFmt.format(r.albumName))
        viewModel.clearSaveAsAlbumResult()
    }

    // System trash-dialog launcher for a delete/hide that needs MANAGE_MEDIA on Android 11+.
    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeletePermissionGranted()
        else viewModel.clearPendingDeleteIntent()
    }
    LaunchedEffect(pendingDeleteIntent) {
        val pi = pendingDeleteIntent ?: return@LaunchedEffect
        // The launch itself can throw if the sender was already consumed; drop the pending work so a
        // stale intent can't force-close the screen.
        runCatching {
            deletePermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }.onFailure { viewModel.clearPendingDeleteIntent() }
    }
    // System write-permission launcher for the batch metadata strip (foreign files on Android 11+).
    val stripPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onStripPermissionGranted()
        else viewModel.clearPendingStripIntent()
    }
    LaunchedEffect(pendingStripIntent) {
        val pi = pendingStripIntent ?: return@LaunchedEffect
        runCatching {
            stripPermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }.onFailure { viewModel.clearPendingStripIntent() }
    }
    // Strip result: one snackbar when a batch finishes, then reset so it fires once.
    LaunchedEffect(multiStripState) {
        when (val s = multiStripState) {
            is MultiStripState.Done -> {
                val msg = if (s.skipped > 0)
                    context.getString(R.string.gallery_stripped_with_skipped, s.stripped, s.skipped)
                else context.getString(R.string.gallery_stripped_metadata, s.stripped)
                snackbarHostState.showSnackbar(msg)
                viewModel.resetMultiStripState()
            }
            is MultiStripState.Failed -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.resetMultiStripState()
            }
            else -> Unit
        }
    }

    val videoCount = remember(state.items) {
        state.items.count { item ->
            val mime = when (item) {
                is GalleryItem.LocalOnly -> item.local.mimeType
                is GalleryItem.Synced -> item.local.mimeType
                is GalleryItem.CloudOnly -> item.cloud.mimeType
            }
            mime.startsWith("video/")
        }
    }
    val photoCount = state.items.size - videoCount
    val photosText = pluralStringResource(R.plurals.count_photos_plural, photoCount, photoCount)
    val videosText = pluralStringResource(R.plurals.count_videos_plural, videoCount, videoCount)
    val countLabel = when {
        state.isLoading -> ""
        photoCount > 0 && videoCount > 0 -> "$photosText, $videosText"
        videoCount > 0 -> videosText
        else -> photosText
    }

    // Cover = the newest item's image (the list is sorted newest-first by capture time).
    // A cloud cover reads its decrypted URL from the shared store first; key on that map so the
    // cover repaints when the decrypt lands.
    val thumbUrls = LocalThumbnailUrls.current.value
    val coverModel: Any? = remember(state.items, thumbUrls) {
        state.items.firstOrNull()?.let { item ->
            when (item) {
                is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
                is GalleryItem.Synced -> Uri.parse(item.local.uri)
                is GalleryItem.CloudOnly ->
                    (thumbUrls[item.cloud.linkId] ?: item.cloud.thumbnailUrl)?.let { Uri.parse(it) }
            }
        }
    }

    // The host modifier shapes the surface (a tall sheet band or a full-screen page); the grid fills
    // and scrolls inside, with the selection bar and action dock within these bounds.
    Box(
        modifier = modifier.background(backgroundColor),
    ) {
        val cols = eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns()
        val seamless = eu.akoos.photos.presentation.gallery.rememberSeamlessGrid()
        // Drag-to-select: long-press a photo then drag to sweep a range (shares the timeline gesture).
        // Cells key on the shared keyOf() (LocalOnly/Synced by uri, CloudOnly by linkId); the swept
        // keys map back to the whole GalleryItem the selection holds.
        val selectableKeys = remember(state.items) { state.items.map { keyOf(it) } }
        val keyToIndex = remember(selectableKeys) { selectableKeys.mapIndexed { i, k -> k to i }.toMap() }
        // Armed at the long-press anchor so the cell's release-tap skips toggling the just-selected
        // cell back off (otherwise a stationary long-press would select then immediately deselect).
        val tapGuard = remember { mutableStateOf(false) }
        val dragSelectModifier = eu.akoos.photos.presentation.gallery.rememberDragMultiSelectModifier(
            gridState = gridState,
            items = state.items,
            indexByKey = keyToIndex,
            selected = selectedItems,
            onSelectionChange = viewModel::setSelection,
            tapGuard = tapGuard,
            enabled = !isDeleting,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            // Match the main timeline grid (GalleryGrid): same default columns, 20.dp side inset and
            // 6.dp gap, so located photos render at the same size as the Photos page. Edge-to-edge
            // drops the side inset and rounding and tightens the gap.
            contentPadding = PaddingValues(
                start = if (seamless) 0.dp else 20.dp,
                end = if (seamless) 0.dp else 20.dp,
                top = contentTopPadding,
                bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
            modifier = Modifier.fillMaxSize().then(dragSelectModifier),
        ) {
            // Hero header - cover, place name and count, plus the "Save as album" action.
            item(span = { GridItemSpan(maxLineSpan) }) {
                // Photo tiles bleed to the edge in seamless mode; this header keeps the 20.dp inset.
                Box(modifier = Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp)) {
                eu.akoos.photos.presentation.albums.components.AlbumHeroHeader(
                    coverModel = coverModel,
                    title = state.placeName,
                    photoCountText = countLabel,
                    coverParallax = {
                        if (gridState.firstVisibleItemIndex == 0)
                            gridState.firstVisibleItemScrollOffset.toFloat() else 0f
                    },
                    titleActions = if (state.items.isNotEmpty()) {
                        {
                            // Create a Drive album from this place - a progress ring while the
                            // round-trip runs, otherwise the library-add glyph.
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(PillBg, CircleShape)
                                    .border(0.5.dp, PillBorder, CircleShape)
                                    .clickable(enabled = !state.isSavingAsAlbum) { showSaveAsAlbumConfirm = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.isSavingAsAlbum) {
                                    CircularProgressIndicator(
                                        color = Accent, strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.LibraryAdd,
                                        contentDescription = stringResource(R.string.location_save_as_album),
                                        tint = Accent,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    } else null,
                )
                }
            }

            // Skeleton only an empty grid: swapping photos that are already there for placeholders
            // collapses it and throws the reader back to the first row, and a refresh that finds
            // nothing new would do that for no visible gain.
            if (state.isLoading && state.items.isEmpty()) {
                items(9, span = { GridItemSpan(1) }) {
                    eu.akoos.photos.presentation.common.ShimmerSquare(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 4.dp,
                    )
                }
            } else if (state.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp).fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.albums_no_photos), color = FgMute, fontSize = 14.sp)
                    }
                }
            } else {
                itemsIndexed(
                    items = state.items,
                    key = { _, item -> keyOf(item) },
                ) { index, item ->
                    val isSelected = item in selectedItems
                    val inputs = remember(item, favoriteIds) {
                        photoCellInputsFor(item, favoriteIds = favoriteIds)
                    }
                    PhotoCell(
                        imageData = inputs.imageData,
                        stableKey = inputs.stableKey,
                        isVideo = inputs.isVideo,
                        isLocalVideo = inputs.isLocalVideo,
                        durationMs = inputs.durationMs,
                        isPlaceholder = inputs.isPlaceholder,
                        selected = isSelected,
                        isSelectionMode = isSelectionMode,
                        showCloudBadge = inputs.showCloudBadge,
                        showSyncedBadge = inputs.showSyncedBadge,
                        isFavorite = inputs.isFavorite,
                        typeBadgeRes = inputs.typeBadgeRes,
                        typeBadgeCdRes = inputs.typeBadgeCdRes,
                        columns = cols,
                        cornerRadius = if (seamless) 0.dp else 10.dp,
                        onClick = {
                            // Skip the release-tap that follows a long-press select; it would
                            // otherwise toggle the just-anchored cell back off.
                            if (tapGuard.value) tapGuard.value = false
                            else if (isSelectionMode) viewModel.toggleSelection(item)
                            else onPhotoClick(state.items, index)
                        },
                        // Long-press + drag is handled by the grid-level drag-select; a plain
                        // long-press there selects this single cell and enters selection mode.
                        onLongClick = null,
                    )
                }
            }
        }

        // The sheet's pull-down grip: the cover runs to the very top and a slim handle floats over a
        // faint top scrim so it reads as pull-down-to-dismiss without a colour break. Only the sheet
        // host shows it; the full-screen page relies on its header's back button instead.
        if (showDragHandle && !isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.28f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.8f)),
            )
        }

        // A multi-select delete blocks the screen behind a progress drawer so a second tap can't
        // fire into a half-finished delete.
        val opDeletingLabel = stringResource(R.string.op_deleting)
        eu.akoos.photos.presentation.common.BlockingOperationSheet(
            if (isDeleting) eu.akoos.photos.presentation.common.OperationProgress(0, 0, opDeletingLabel, indeterminate = true) else null,
        )

        // Selection drawer: the shared surface every multi-select uses, so the located-photos bulk
        // actions all sit in one list, in the order every other surface lists them.
        val allSelected = state.items.isNotEmpty() && selectedItems.size == state.items.size
        // Which way the offline row goes, so it names the press rather than the state: a pin while
        // anything pinnable in the selection is still un-pinned, a removal once none is.
        val offlinePinsSelection = remember(selectedItems, offlinePinIds) {
            offlineTurnsOn(offlinePinnableLinkIds(selectedItems), offlinePinIds)
        }
        val locationSelectionActions = buildList {
            add(
                SelectionAction(
                    icon = Icons.Default.SelectAll,
                    label = stringResource(
                        if (allSelected) R.string.gallery_deselect_all else R.string.select_all,
                    ),
                    onClick = { if (allSelected) viewModel.clearSelection() else viewModel.selectAll() },
                )
            )
            add(
                SelectionAction(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.sel_label_share),
                    onClick = { viewModel.shareSelected() },
                )
            )
            // Add-to-album needs a Drive destination, so it stays behind a signed-in session.
            if (isSignedIn) {
                add(
                    SelectionAction(
                        icon = Icons.Default.PhotoAlbum,
                        label = stringResource(R.string.gallery_add_to_album),
                        onClick = { showAddToAlbumSheet = true },
                    )
                )
            }
            // Move the device selection into another folder, the logged-out counterpart to
            // Add-to-album, offered only without an account and on the Android 10+ MediaStore floor.
            if (!isSignedIn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(
                    SelectionAction(
                        icon = Icons.AutoMirrored.Filled.DriveFileMove,
                        label = stringResource(R.string.move_to_folder),
                        onClick = { showMoveSheet = true },
                    )
                )
            }
            // Favourite the whole selection in one press.
            add(
                favoriteSelectionAction(
                    turnsOn = remember(selectedItems, favoriteIds) {
                        favoriteTurnsOn(selectedItems, favoriteIds)
                    },
                    state = favoriteState,
                    onClick = { viewModel.toggleSelectedFavorite() },
                )
            )
            // Back up the not-yet-uploaded (LocalOnly) photos in the selection. Needs a Drive
            // destination, so it stays behind a signed-in session.
            if (isSignedIn && anyLocalOnly(selectedItems)) {
                add(
                    SelectionAction(
                        icon = Icons.Default.CloudUpload,
                        label = stringResource(R.string.sel_label_back_up),
                        onClick = {
                            viewModel.backUpSelected { queued ->
                                if (queued > 0) scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.backup_started))
                                }
                            }
                        },
                    )
                )
            }
            // Download / offline apply to cloud-only photos (no local file yet).
            if (hasDownloadable(selectedItems)) {
                add(
                    SelectionAction(
                        icon = Icons.Default.FileDownload,
                        label = stringResource(R.string.sel_label_download),
                        onClick = {
                            viewModel.downloadSelected { succeeded, failed ->
                                val msg = when {
                                    failed > 0 -> context.getString(R.string.gallery_download_partial, succeeded, failed)
                                    succeeded == 1 -> context.getString(R.string.gallery_download_done_singular)
                                    else -> context.getString(R.string.gallery_download_done, succeeded)
                                }
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        },
                    )
                )
            }
            if (anyCloudOnly(selectedItems)) {
                add(
                    SelectionAction(
                        icon = Icons.Default.OfflinePin,
                        label = stringResource(
                            if (offlinePinsSelection) R.string.offline_make_available
                            else R.string.offline_remove,
                        ),
                        onClick = { viewModel.toggleSelectedOffline() },
                    )
                )
            }
            // The date + place editor opens for any editable photo (a device photo, or a cloud or
            // backed-up image the corrected-copy replace can rewrite), so a mixed selection keeps the
            // entry (the editor writes exactly those photos and names the count).
            if (anyMetadataEditable(selectedItems)) {
                add(
                    SelectionAction(
                        icon = Icons.Default.EditNote,
                        label = stringResource(R.string.metadata_editor_edit_metadata),
                        onClick = { onEditMetadata(selectedItems.toList()) },
                    )
                )
            }
            // The strip stays behind an all-device-only selection: a Synced photo keeps its Drive
            // copy's EXIF, so stripping just the local file is a misleading half-strip.
            if (allLocalOnly(selectedItems)) {
                add(
                    SelectionAction(
                        icon = Icons.Default.PrivacyTip,
                        label = stringResource(R.string.gallery_strip_metadata),
                        onClick = { showStripPicker = true },
                    )
                )
            }
            // Hide any non-empty selection: a photo that lives only on this device moves into the
            // vault, one with a cloud copy is filtered by linkId.
            if (selectedItems.isNotEmpty()) {
                add(
                    SelectionAction(
                        icon = Icons.Default.VisibilityOff,
                        label = stringResource(R.string.sel_label_hide),
                        enabled = !isDeleting,
                        // A hide ends in a permanent removal of the device originals it vaults, so it
                        // is confirmed exactly as the delete beside it is. The split is read at the
                        // tap, so the sheet names what THIS selection will have done to it.
                        onClick = { hideConfirmSplit = viewModel.hideSplitForSelection().takeIf { !it.isEmpty } },
                    )
                )
            }
            add(
                SelectionAction(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(R.string.sel_label_delete),
                    tint = ErrorColor,
                    enabled = !isDeleting,
                    onClick = { showDeleteSheet = true },
                )
            )
        }
        SelectionDrawer(
            visible = isSelectionMode,
            items = remember(selectedItems) { selectedItems.toList() },
            actions = locationSelectionActions,
            onDismiss = { viewModel.clearSelection() },
            // Scrolling the grid collapses the drawer, so reaching past it to carry on through the
            // photos needs no deliberate pull or tap first.
            contentScrolling = gridState.isScrollInProgress,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Jump-to-top pill - appears once scrolled down, hidden during selection so it never
        // collides with the bottom action dock. Mirrors the album / device-folder detail pages.
        AnimatedVisibility(
            visible = showScrollTop && !isSelectionMode,
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
                diameter = 44.dp,
                iconSize = 24.dp,
                background = PillBg,
                borderColor = PillBorder,
                tint = FgPrimary,
            )
        }

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // A full-screen host draws its floating pill header last so it sits above the grid; the sheet
        // passes none and shows the pull-down grip instead.
        headerOverlay?.invoke(this)
    }

    if (showStripPicker) {
        eu.akoos.photos.presentation.gallery.MetadataStripPickerDialog(
            onConfirm = {
                showStripPicker = false
                viewModel.stripMetadataSelected(it)
            },
            onDismiss = { showStripPicker = false },
        )
    }

    // Hide confirmation - the shared sheet every hide surface raises, worded from this selection's
    // own split.
    hideConfirmSplit?.let { split ->
        eu.akoos.photos.presentation.common.HideConfirmSheet(
            split = split,
            title = stringResource(R.string.hide_confirm_title),
            onConfirm = {
                hideConfirmSplit = null
                viewModel.hideSelected()
            },
            onDismiss = { hideConfirmSplit = null },
        )
    }

    // Bulk-delete sheet - reuses the gallery's dialog so options + copy stay identical.
    if (showDeleteSheet && selectedItems.isNotEmpty()) {
        eu.akoos.photos.presentation.gallery.GalleryMultiDeleteDialog(
            selectedItems = selectedItems,
            onDismiss = { showDeleteSheet = false },
            onDelete = { freeUpSpace, deleteFromCloud ->
                showDeleteSheet = false
                viewModel.deleteSelected(freeUpSpace, deleteFromCloud)
            },
        )
    }

    // Add-to-album sheet - reuses the gallery's picker. Cloud-backed selections join the album now;
    // local-only selections back up first and join afterwards.
    if (showAddToAlbumSheet && selectedItems.isNotEmpty()) {
        eu.akoos.photos.presentation.gallery.GalleryAddToAlbumDialog(
            selectedItems = selectedItems,
            cloudAlbums = albums,
            sheetState = addToAlbumSheetState,
            onCreateNew = {
                showAddToAlbumSheet = false
                showCreateAlbumInline = true
            },
            onCloudAlbumSelected = { album ->
                showAddToAlbumSheet = false
                viewModel.addSelectedToAlbum(album.linkId) { joined, queued ->
                    scope.launch {
                        val msg = when {
                            joined > 0 -> context.getString(R.string.gallery_added_to_album, joined, album.name)
                            queued > 0 -> context.getString(R.string.device_folder_added_to_album)
                            else -> context.getString(R.string.album_picker_empty)
                        }
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            },
            onDismiss = { showAddToAlbumSheet = false },
        )
    }

    // Name a brand-new album for the selection, then create it and add the photos to it - the same
    // two steps the timeline's picker runs, so the row means the same thing on both.
    if (showCreateAlbumInline) {
        eu.akoos.photos.presentation.gallery.GalleryNewAlbumDialog(
            onDismiss = { showCreateAlbumInline = false },
            onCreate = { name ->
                showCreateAlbumInline = false
                viewModel.createAlbumThenAddSelected(name) { joined, queued, error ->
                    scope.launch {
                        val msg = when {
                            error != null -> error
                            joined > 0 -> context.getString(R.string.gallery_added_to_album, joined, name)
                            queued > 0 -> context.getString(R.string.device_folder_added_to_album)
                            else -> context.getString(R.string.album_picker_empty)
                        }
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            },
        )
    }

    // Confirm before creating a Drive album from every photo in this place.
    if (showSaveAsAlbumConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.location_save_as_album_confirm_title),
            message = stringResource(R.string.location_save_as_album_confirm_body_fmt, state.placeName),
            confirmLabel = stringResource(R.string.action_save),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showSaveAsAlbumConfirm = false
                viewModel.saveAsAlbum()
            },
            onDismiss = { showSaveAsAlbumConfirm = false },
        )
    }

    // Move-to-folder host: the picker, the new-folder name dialog, its own write-consent launcher and
    // the completion snackbar, all logged-out and device-only. The selection is snapshotted in the
    // ViewModel, so the host carries only the picked name.
    MoveToFolderHost(
        targetFolders = moveTargetFolders,
        show = showMoveSheet,
        pendingMoveIntent = pendingMoveIntent,
        moveConfirmation = viewModel.moveConfirmation,
        onPick = { viewModel.moveSelectedToFolder(it) },
        onCreate = { viewModel.createFolderWithPhotos(it) },
        onGranted = { viewModel.onMovePermissionGranted() },
        onClear = { viewModel.clearPendingMove() },
        onDismiss = { showMoveSheet = false },
        snackbarHostState = snackbarHostState,
    )
}

/** Cell / drag-select key: local uri where a device copy exists, else the cloud linkId, prefixed so
 *  the two id spaces never collide. Shared by the grid cells, the drag sweep and the index map. */
private fun keyOf(item: GalleryItem): String = when (item) {
    is GalleryItem.LocalOnly -> "L:" + item.local.uri
    is GalleryItem.Synced    -> "S:" + item.local.uri
    is GalleryItem.CloudOnly -> "C:" + item.cloud.linkId
}
