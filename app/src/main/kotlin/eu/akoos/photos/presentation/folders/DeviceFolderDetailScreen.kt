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

package eu.akoos.photos.presentation.folders

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import eu.akoos.photos.presentation.albums.AlbumRenameInput
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.EditFieldSheet
import eu.akoos.photos.presentation.common.HideConfirmSheet
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.ReturnToViewerPhoto
import eu.akoos.photos.presentation.common.SecureScreenEffect
import eu.akoos.photos.presentation.common.SelectionAction
import eu.akoos.photos.presentation.common.SelectionDrawer
import eu.akoos.photos.presentation.common.favoriteSelectionAction
import eu.akoos.photos.presentation.common.favoriteTurnsOn
import eu.akoos.photos.presentation.common.anyMetadataEditable
import eu.akoos.photos.presentation.gallery.LocalThumbnailUrls
import eu.akoos.photos.presentation.gallery.MetadataStripPickerDialog
import eu.akoos.photos.presentation.gallery.MoveToFolderHost
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.ScrollDateLabel
import eu.akoos.photos.presentation.gallery.TimelineGrouping
import eu.akoos.photos.presentation.gallery.TimelineScrubber
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.map
import eu.akoos.photos.presentation.viewer.ManagePublicLinkSheet
import eu.akoos.photos.presentation.viewer.PhotoShareSheet
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PrivacyTip
import eu.akoos.photos.presentation.common.MultiStripState
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.util.monthYearFormat
import eu.akoos.photos.util.copySensitiveText

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
    /** Opens the date + place editor for the current selection, matching the timeline's entry. */
    onEditMetadata: (items: List<GalleryItem>) -> Unit = {},
    /** Opens the same viewer on the folder's first photo with its slideshow already running. */
    onSlideshowClick: (List<GalleryItem>) -> Unit = {},
    /** An action the Albums grid asked for on this folder, carried out once its photos are in hand.
     *  Null on a plain open. */
    openAction: DeviceFolderOpenAction? = null,
    /** Hands [openAction] back the moment it runs, so it lands exactly once. */
    onOpenActionConsumed: () -> Unit = {},
    /** True where the vault's card for the folder opened this screen rather than the Albums grid's.
     *  The two cards hold disjoint photos, so this is what says which of them is being looked at. */
    fromVault: Boolean = false,
    onBack: () -> Unit,
    viewModel: DeviceFolderDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(bucketName, fromVault) { viewModel.load(bucketName, fromVault) }

    // Reached from the vault, this screen's thumbnails are hidden photos, so it keeps itself out of
    // screenshots and the recent-apps preview exactly as the vault grid does. The Albums grid's copy
    // of the same folder holds photos that are on the device in plain sight, and stays capturable.
    if (fromVault) SecureScreenEffect()

    val items by viewModel.items.collectAsStateWithLifecycle()
    val selectedUris by viewModel.selectedUris.collectAsStateWithLifecycle()
    // The folder's vaulted photos. They sit in the grid with the rest, but nothing that needs a device
    // file is offered on them — they are revealed instead.
    val vaultedUris by viewModel.vaultedUris.collectAsStateWithLifecycle()
    val pairedVaultUris by viewModel.pairedVaultUris.collectAsStateWithLifecycle()
    val offlinePinIds by viewModel.offlinePinIds.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val favoriteState by viewModel.favoriteState.collectAsStateWithLifecycle()
    val backupProgress by viewModel.backupProgress.collectAsStateWithLifecycle()
    val folderVault by viewModel.folderVault.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val showScrollTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 4 } }
    val folderCtx = androidx.compose.ui.platform.LocalContext.current
    // Opt-in floating day pill while the grid scrolls; default off. Shares the scrubber's date mapping.
    val showScrollDate by remember {
        folderCtx.settingsDataStore.data.map { it[SettingsKeys.SHOW_SCROLL_DATE] ?: false }
    }.collectAsState(initial = false)
    // Yields the pill while the scrubber bubble is being dragged so the two don't overlap.
    var scrubberDragging by remember { mutableStateOf(false) }
    val scrubberTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp

    val isSelectionMode = selectedUris.isNotEmpty()
    // Leaving mid-hide would clear this ViewModel and cancel the copy loop outright, skipping the
    // journal and the delete of the originals it had already copied: the very step the stop is a
    // polled flag rather than a job cancel to protect. Back therefore raises that same flag, which
    // ends the loop within a photo and lets the next press through.
    BackHandler(enabled = folderVault != null) { viewModel.cancelFolderVault() }
    // In selection mode the back button cancels the selection instead of leaving the screen —
    // mirrors the gallery and album-detail behaviour. Registered after the stop above, so a press
    // while both apply clears the selection first (back dispatch runs handlers in reverse order).
    BackHandler(enabled = isSelectionMode) { viewModel.clearSelection() }

    var showFolderOverflow by remember { mutableStateOf(false) }
    val folderActionsSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isMirroredAsAlbum by viewModel.isMirroredAsAlbum.collectAsStateWithLifecycle()
    val isExcludedFromBackup by viewModel.isExcludedFromBackup.collectAsStateWithLifecycle()
    val isHiddenFromTimeline by viewModel.isHiddenFromTimeline.collectAsStateWithLifecycle()
    val isHiddenCard by viewModel.isHiddenCard.collectAsStateWithLifecycle()
    val isSignedIn by viewModel.isSignedIn.collectAsStateWithLifecycle()
    // Which direction the drawer's sort entries tick. The ViewModel reads the same preference for
    // the order itself, so this only has to say which one is active.
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()

    // Move the selection into another device folder, and rename this folder in place. Both are
    // logged-out, device-data actions; the picker's targets and the move consent ride the shared host.
    var showMoveSheet by remember { mutableStateOf(false) }
    var showRenameSheet by remember { mutableStateOf(false) }
    val moveTargetFolders by viewModel.moveTargetFolders.collectAsStateWithLifecycle()
    val pendingMoveIntent by viewModel.pendingMoveIntent.collectAsStateWithLifecycle()

    // Back up every photo here, speaking up only when there was nothing to send: a started back-up
    // already shows in the progress pill. Shared by the drawer's rows and by a back-up the Albums
    // grid asked for, so both report the same way.
    val alreadyBackedUpMsg = stringResource(R.string.device_folder_already_backed_up)
    fun backUpFolder(asMirror: Boolean) {
        viewModel.backUpAll(asMirror) { outcome ->
            if (outcome.queued == 0) scope.launch { snackbarHostState.showSnackbar(alreadyBackedUpMsg) }
        }
    }

    // An action asked for from the Albums grid arrives as an intent rather than as a raised drawer:
    // the grid opens this screen's own drawer, so raising it here would dismiss a sheet and put the
    // identical one back up. Each action runs on the folder's photos, which land after the screen
    // mounts, so the intent waits for them. The host's clear survives this screen being disposed and
    // rebuilt on the way back from the viewer; the latch covers the frames before that clear is read.
    var openActionRun by remember { mutableStateOf(false) }
    LaunchedEffect(openAction, items.size) {
        if (!DeviceFolderOpenIntent.shouldRun(openAction, items.size, openActionRun)) return@LaunchedEffect
        openActionRun = true
        onOpenActionConsumed()
        when (openAction) {
            DeviceFolderOpenAction.BackUpToTimeline -> backUpFolder(asMirror = false)
            DeviceFolderOpenAction.BackUpAndMirror -> backUpFolder(asMirror = true)
            DeviceFolderOpenAction.Slideshow -> onSlideshowClick(items)
            null -> Unit
        }
    }

    var showUploadConfirm by remember { mutableStateOf(false) }
    var showSetCoverConfirm by remember { mutableStateOf(false) }
    // The hide split while its confirmation is up, null when none is. Holding the split rather than
    // a flag is what lets the sheet describe the photos the tap was made on. The selection's hide and
    // the whole folder's are separate taps, so each keeps its own.
    var hideConfirmSplit by remember {
        mutableStateOf<eu.akoos.photos.data.hidden.HiddenFolderRecords.HideSplit?>(null)
    }
    var folderHideConfirmSplit by remember {
        mutableStateOf<eu.akoos.photos.data.hidden.HiddenFolderRecords.HideSplit?>(null)
    }
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
    // The picker's own "New album" row, which names an album and then adds the selection to it.
    var showCreateAlbumInline by remember { mutableStateOf(false) }
    // What an album add landed on, said the same way whichever row started it. A run that reaches
    // neither an album nor the upload queue says so rather than claiming an add that never happened.
    val addedToAlbumMsg = stringResource(R.string.device_folder_added_to_album)
    val nothingToAddMsg = stringResource(R.string.album_picker_empty)
    fun showAddToAlbumOutcome(joined: Int, queued: Int, error: String?) {
        val msg = error ?: if (joined > 0 || queued > 0) addedToAlbumMsg else nothingToAddMsg
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }
    // Unified share drawer + manage-link sheet for the selection (Send to app / Public link).
    var showPhotoShareSheet by remember { mutableStateOf(false) }
    val photoShareSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showManageLinkSheet by remember { mutableStateOf(false) }
    val manageLinkSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val publicLinkState by viewModel.publicLinkState.collectAsStateWithLifecycle()
    val linkCopiedMsg = stringResource(R.string.album_link_copied)
    val albums by viewModel.albums.collectAsStateWithLifecycle()

    // Bulk delete — reuses the gallery's delete sheet + the system trash-dialog launcher, so a
    // device-folder selection deletes the same way (and with the same options) as the timeline.
    var showDeleteSheet by remember { mutableStateOf(false) }
    val pendingDeleteIntent by viewModel.pendingDeleteIntent.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val pendingStripIntent by viewModel.pendingStripIntent.collectAsStateWithLifecycle()
    val multiStripState by viewModel.multiStripState.collectAsStateWithLifecycle()
    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeletePermissionGranted()
        else viewModel.clearPendingDeleteIntent()
    }
    LaunchedEffect(pendingDeleteIntent) {
        val pi = pendingDeleteIntent ?: return@LaunchedEffect
        // Guard the launch so a stale or already-consumed sender can't force-close the screen.
        runCatching {
            deletePermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }.onFailure { viewModel.clearPendingDeleteIntent() }
    }
    // Metadata-strip write-permission launcher (foreign files on Android 11+) + result snackbar.
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
    LaunchedEffect(multiStripState) {
        when (val s = multiStripState) {
            is MultiStripState.Done -> {
                val msg = if (s.skipped > 0)
                    folderCtx.getString(R.string.gallery_stripped_with_skipped, s.stripped, s.skipped)
                else folderCtx.getString(R.string.gallery_stripped_metadata, s.stripped)
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
    // A hide that refused to start says why; the message already reads for the user.
    LaunchedEffect(Unit) {
        viewModel.hideFailure.collect { snackbarHostState.showSnackbar(it) }
    }
    // A batch favourite that landed says nothing: the hearts and the drawer row already show it.
    // Only a write Drive refused reaches here.
    LaunchedEffect(Unit) {
        viewModel.favoriteFailure.collect { snackbarHostState.showSnackbar(it) }
    }
    // A completed folder rename confirms the new name; the screen stays on the same photos.
    val folderRenamedTpl = stringResource(R.string.folder_renamed)
    LaunchedEffect(Unit) {
        viewModel.folderRenameConfirmation.collect { newName ->
            snackbarHostState.showSnackbar(folderRenamedTpl.format(newName))
        }
    }

    // A multi-select delete blocks the screen behind a progress drawer so a second tap can't fire
    // into a half-finished delete.
    val opDeletingLabel = stringResource(R.string.op_deleting)
    eu.akoos.photos.presentation.common.BlockingOperationSheet(
        if (isDeleting) eu.akoos.photos.presentation.common.OperationProgress(0, 0, opDeletingLabel, indeterminate = true) else null,
    )

    // Cover = the photo pinned for this folder, else the first item of the current sort order, the
    // way the cloud album's hero falls back. A pinned photo the folder no longer holds — deleted,
    // hidden, or moved out — falls back the same way rather than leaving the hero blank. Device
    // folders only ever hold
    // local items, so the cover comes from the local URI; the cloud branch resolves the shared store
    // URL for parity with the other detail heroes.
    val thumbUrls = LocalThumbnailUrls.current.value
    val pinnedCoverUri by viewModel.pinnedCoverUri.collectAsStateWithLifecycle()
    val coverModel: Any? = remember(items, thumbUrls, pinnedCoverUri) {
        val pinned = pinnedCoverUri?.let { uri ->
            items.firstOrNull { item ->
                when (item) {
                    is GalleryItem.LocalOnly -> item.local.uri == uri
                    is GalleryItem.Synced -> item.local.uri == uri
                    is GalleryItem.CloudOnly -> false
                }
            }
        }
        (pinned ?: items.firstOrNull())?.let { item ->
            when (item) {
                is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
                is GalleryItem.Synced -> Uri.parse(item.local.uri)
                is GalleryItem.CloudOnly ->
                    (thumbUrls[item.cloud.linkId] ?: item.cloud.thumbnailUrl)?.let { Uri.parse(it) }
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
        val cols = eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns()
        val seamless = eu.akoos.photos.presentation.gallery.rememberSeamlessGrid()
        // Drag-to-select: long-press a photo then drag to sweep a range (shares the timeline gesture).
        // Cells are keyed by local uri (cloud-only by linkId); the swept keys map to selected uris.
        val selectableKeys = remember(items) {
            items.map { item ->
                when (item) {
                    is GalleryItem.LocalOnly -> item.local.uri
                    is GalleryItem.Synced -> item.local.uri
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                }
            }
        }
        val keyToIndex = remember(selectableKeys) { selectableKeys.mapIndexed { i, k -> k to i }.toMap() }
        // Armed at the long-press anchor so the cell's release-tap skips toggling the just-selected
        // cell back off (otherwise a stationary long-press would select then immediately deselect).
        val tapGuard = remember { mutableStateOf(false) }
        // Group by month like the cloud album. withIndex() keeps each item's original position so
        // onPhotoClick still opens the right one and the drag-select keys stay aligned.
        val photoGroups = remember(items) {
            val fmt = monthYearFormat()
            items.withIndex().groupBy { fmt.format(java.util.Date(it.value.captureTimeMs)) }
        }
        val dragSelectModifier = eu.akoos.photos.presentation.gallery.rememberDragMultiSelectModifier(
            gridState = gridState,
            items = selectableKeys,
            indexByKey = keyToIndex,
            selected = selectedUris,
            onSelectionChange = viewModel::setSelectedUris,
            tapGuard = tapGuard,
        )
        // Land back on the photo the viewer closed on. The hero header is the one slot ahead of the
        // photos, and each month bucket carries its own header. The cells key a Synced photo by its
        // local uri, but the viewer is handed the items themselves, so match on the gallery identity.
        val returnGroups = remember(photoGroups) { photoGroups.values.toList() }
        ReturnToViewerPhoto(
            gridState = gridState,
            groups = returnGroups,
            headerPerGroup = true,
            leadingSlots = 1,
            keyOf = { it.value.stableId },
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            // Match the main timeline grid (GalleryGrid): same default columns, 20.dp side inset and
            // 6.dp gap, so device-folder photos render at the same size as the Photos page. Edge-to-edge
            // drops the side inset and rounding and tightens the gap.
            contentPadding = PaddingValues(
                start = if (seamless) 0.dp else 20.dp,
                end = if (seamless) 0.dp else 20.dp,
                bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
            modifier = Modifier.fillMaxSize().then(dragSelectModifier),
        ) {
            // Hero header — cover, folder name and count, like the cloud-album detail page.
            item(span = { GridItemSpan(maxLineSpan) }) {
                // Photo tiles bleed to the edge in seamless mode; this header keeps the 20.dp inset.
                Box(modifier = Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp)) {
                eu.akoos.photos.presentation.albums.components.AlbumHeroHeader(
                    coverModel = coverModel,
                    title = bucketName,
                    photoCountText = countLabel,
                    titleActions = {
                        // One control at rest, like the cloud album's. Backing up, mirroring as an
                        // album, excluding from back-up and hiding from the timeline all live in the
                        // drawer it opens; a running back-up's cancel rides the progress pill rather
                        // than a header pill of its own. It stands on an empty folder too, where the
                        // preferences are the only route to putting photos back in view; the drawer
                        // drops the rows that need photos instead.
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(PillBg, CircleShape)
                                .border(0.5.dp, PillBorder, CircleShape)
                                .clickable { showFolderOverflow = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.albums_more_actions),
                                tint = FgPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
                }
            }
            if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp).fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.albums_no_photos), color = FgMute, fontSize = 14.sp)
                    }
                }
            } else {
                photoGroups.forEach { (label, entries) ->
                    // Month section header — same styling as the cloud-album detail grid.
                    item(span = { GridItemSpan(maxLineSpan) }, key = "hdr_$label") {
                        Row(
                            // Keep the label clear of the screen edge in seamless mode, where the
                            // tiles below bleed to 0.
                            modifier = Modifier.fillMaxWidth().padding(
                                start = if (seamless) 20.dp else 4.dp,
                                end = if (seamless) 20.dp else 4.dp,
                                top = 24.dp,
                                bottom = 10.dp,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                color = FgPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.44).sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    items(
                        entries,
                        key = { entry ->
                            when (val item = entry.value) {
                                is GalleryItem.LocalOnly -> item.local.uri
                                is GalleryItem.Synced -> item.local.uri
                                is GalleryItem.CloudOnly -> item.cloud.linkId
                            }
                        },
                    ) { entry ->
                        val index = entry.index
                        val item = entry.value
                        val itemUri = when (item) {
                            is GalleryItem.LocalOnly -> item.local.uri
                            is GalleryItem.Synced -> item.local.uri
                            is GalleryItem.CloudOnly -> null
                        }
                        val isSelected = itemUri != null && itemUri in selectedUris
                        // Long-press enters selection directly, matching the timeline and album grids.
                        // The old per-cell Select / Share / Back-up menu is gone; in selection mode the
                        // toolbar carries Share + Back up, so a long-press + the toolbar covers the same
                        // actions without the extra "Select" tap.
                        val inputs = remember(item, favoriteIds, offlinePinIds, pairedVaultUris) {
                            photoCellInputsFor(
                                item,
                                favoriteIds = favoriteIds,
                                offlinePinIds = offlinePinIds,
                                pairedVaultUris = pairedVaultUris,
                            )
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
                            isOffline = inputs.isOffline,
                            typeBadgeRes = inputs.typeBadgeRes,
                            typeBadgeCdRes = inputs.typeBadgeCdRes,
                            columns = cols,
                            cornerRadius = if (seamless) 0.dp else 10.dp,
                            onClick = {
                                // Skip the release-tap that follows a long-press select; it would
                                // otherwise toggle the just-anchored cell back off.
                                if (tapGuard.value) {
                                    tapGuard.value = false
                                } else if (isSelectionMode) {
                                    if (itemUri != null) viewModel.toggleSelection(itemUri)
                                } else onPhotoClick(items, index)
                            },
                            // Long-press + drag is handled by the grid-level drag-select; a plain
                            // long-press there selects this single cell and enters selection mode.
                            onLongClick = null,
                        )
                    }
                }
            }
        }

        // Fast-scroll scrubber over the photo grid — the same handle as the timeline. The scrubber
        // groups by day, so the drag tooltip reads "d MMMM yyyy" even though the section headers are month.
        if (items.isNotEmpty()) {
            TimelineScrubber(
                gridState = gridState,
                items = items,
                grouping = TimelineGrouping.Day,
                topPadding = scrubberTopInset,
                bottomPadding = 24.dp,
                keyOf = {
                    when (it) {
                        is GalleryItem.LocalOnly -> it.local.uri
                        is GalleryItem.Synced -> it.local.uri
                        is GalleryItem.CloudOnly -> it.cloud.linkId
                    }
                },
                onDraggingChange = { scrubberDragging = it },
            )
        }

        // Opt-in floating day pill, top-centre while the grid scrolls, yielding while the scrubber drags.
        if (showScrollDate) {
            ScrollDateLabel(
                gridState = gridState,
                items = items,
                grouping = TimelineGrouping.Day,
                topPadding = scrubberTopInset,
                suppressed = scrubberDragging,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // Fixed back button, floating over the grid. Cancels selection while selecting, and stops a
        // running vault move the same cooperative way the system back does, so neither route out
        // kills the copy loop half-way.
        IconBubble(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = if (isSelectionMode)
                stringResource(R.string.gallery_cancel_selection) else stringResource(R.string.close),
            onClick = {
                when {
                    isSelectionMode -> viewModel.clearSelection()
                    folderVault != null -> viewModel.cancelFolderVault()
                    else -> onBack()
                }
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 10.dp),
            diameter = 40.dp,
            iconSize = 18.dp,
            background = PillBg,
            borderColor = PillBorder,
            tint = FgPrimary,
        )

        // Selection drawer: the shared surface every multi-select uses, so a folder's bulk actions
        // all sit in one list, in the order every other surface lists them.
        // Both list-wide scans below answer nothing outside selection mode, so they are skipped
        // there rather than walking a folder of thousands on every recomposition.
        val selectedItems = if (!isSelectionMode) emptyList() else items.filter { item ->
            val u = when (item) {
                is GalleryItem.LocalOnly -> item.local.uri
                is GalleryItem.Synced    -> item.local.uri
                is GalleryItem.CloudOnly -> null
            }
            u != null && u in selectedUris
        }
        // The half of the selection that still has a device file. Every action but the reveal
        // works on a file, so each is offered on these alone and disappears for an all-vaulted
        // selection rather than opening a sheet that would act on nothing.
        val selectedDeviceItems = selectedItems.filter { item ->
            when (item) {
                is GalleryItem.LocalOnly -> item.local.uri !in vaultedUris
                is GalleryItem.Synced -> item.local.uri !in vaultedUris
                is GalleryItem.CloudOnly -> true
            }
        }
        val anyVaultedSelected = selectedUris.any { it in vaultedUris }
        // The photo a cover may be pinned to, or null when the selection names none. Shared with
        // the confirmation and the write, so all three answer the same question.
        val coverCandidate = FolderCoverSelection.pinnable(selectedUris, vaultedUris)
        // Toggles every selectable photo in the folder (cloud-only entries have no uri and stay
        // excluded): select all, or clear once everything selectable is selected.
        val selectableUris = if (!isSelectionMode) emptyList() else items.mapNotNull { item ->
            when (item) {
                is GalleryItem.LocalOnly -> item.local.uri
                is GalleryItem.Synced -> item.local.uri
                is GalleryItem.CloudOnly -> null
            }
        }
        val allFolderSelected = selectableUris.isNotEmpty() && selectedUris.size == selectableUris.size
        val anyDeviceOnlySelected = selectedDeviceItems.any { it is GalleryItem.LocalOnly }
        var showStripPicker by remember { mutableStateOf(false) }
        val folderSelectionActions = buildList {
            add(
                SelectionAction(
                    icon = Icons.Default.SelectAll,
                    label = stringResource(
                        if (allFolderSelected) R.string.gallery_deselect_all else R.string.select_all,
                    ),
                    onClick = {
                        viewModel.setSelectedUris(if (allFolderSelected) emptySet() else selectableUris.toSet())
                    },
                )
            )
            // Share selected to other apps - device files share their URI directly, no download.
            if (selectedDeviceItems.isNotEmpty()) {
                add(
                    SelectionAction(
                        icon = Icons.Default.Share,
                        label = stringResource(R.string.sel_label_share),
                        onClick = { showPhotoShareSheet = true },
                    )
                )
            }
            // Add selected to a cloud album - opens the gallery's picker sheet. An album add
            // uploads the photo, so it is offered on the photos that still have a device file
            // and disappears for an all-vaulted selection. The upload needs a Proton account.
            if (selectedDeviceItems.isNotEmpty() && isSignedIn) {
                add(
                    SelectionAction(
                        icon = Icons.Default.PhotoAlbum,
                        label = stringResource(R.string.gallery_add_to_album),
                        onClick = { showAddToAlbumSheet = true },
                    )
                )
            }
            // Move selected into another device folder, the logged-out counterpart of "Add to album",
            // relocating the files under DCIM/ with no cloud involved. Held to the scoped-storage move's
            // Android 10+ floor and offered only on photos that still have a device file.
            if (!isSignedIn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && selectedDeviceItems.isNotEmpty()) {
                add(
                    SelectionAction(
                        icon = Icons.AutoMirrored.Filled.DriveFileMove,
                        label = stringResource(R.string.move_to_folder),
                        onClick = { showMoveSheet = true },
                    )
                )
            }
            // Favourite the selection. Offered on every selected photo, vaulted ones included:
            // the heart is recorded per photo wherever the photo lives, and the viewer already
            // offers it on a vaulted photo the same way.
            add(
                favoriteSelectionAction(
                    turnsOn = remember(selectedItems, favoriteIds) {
                        favoriteTurnsOn(selectedItems, favoriteIds)
                    },
                    state = favoriteState,
                    onClick = { viewModel.toggleSelectedFavorite() },
                )
            )
            // Back up selected to Drive. Shown only when the selection holds a device-only photo
            // that still needs backing up; an all-Synced selection is already on Drive, so the
            // action would upload nothing. A snackbar confirms either way since the progress
            // pill alone is easy to miss.
            if (anyDeviceOnlySelected && isSignedIn) {
                add(
                    SelectionAction(
                        icon = Icons.Default.CloudUpload,
                        label = stringResource(R.string.sel_label_back_up),
                        onClick = { showUploadConfirm = true },
                    )
                )
            }
            // Pin one photo as the folder's cover, the way a cloud album's list offers it. One
            // photo is one cover, and a hidden photo is never the folder's public face, so it
            // appears only on a single selection of a photo that is still on the device.
            if (coverCandidate != null) {
                add(
                    SelectionAction(
                        icon = Icons.Default.PhotoLibrary,
                        label = stringResource(R.string.album_set_as_cover),
                        onClick = { showSetCoverConfirm = true },
                    )
                )
            }
            // A folder of scanned photos is where wrong dates get fixed in bulk, so the date +
            // The date + place editor opens for any editable item (a device photo, or a backed-up
            // image the corrected-copy replace can rewrite) and names the count it lands on.
            if (anyMetadataEditable(selectedDeviceItems)) {
                add(
                    SelectionAction(
                        icon = Icons.Default.EditNote,
                        label = stringResource(R.string.metadata_editor_edit_metadata),
                        onClick = { onEditMetadata(selectedDeviceItems) },
                    )
                )
                // The strip stays behind an all-device-only selection: a Synced photo keeps its
                // Drive copy's EXIF, so stripping just the local file is a misleading half-strip.
                if (selectedDeviceItems.all { it is GalleryItem.LocalOnly }) {
                    add(
                        SelectionAction(
                            icon = Icons.Default.PrivacyTip,
                            label = stringResource(R.string.gallery_strip_metadata),
                            onClick = { showStripPicker = true },
                        )
                    )
                }
            }
            // Reveal selected: every vaulted photo in the selection returns to the device, and the
            // folder stops being hidden once the vault holds nothing more of it.
            if (anyVaultedSelected) {
                add(
                    SelectionAction(
                        icon = Icons.Default.Visibility,
                        label = stringResource(R.string.sel_label_unhide),
                        onClick = { viewModel.unhideSelected() },
                    )
                )
            }
            // Hide selected: a photo that lives only on this device moves into the vault, one
            // with a cloud copy is filtered by linkId. Mirrors the timeline's hide action.
            if (selectedDeviceItems.isNotEmpty()) {
                add(
                    SelectionAction(
                        icon = Icons.Default.VisibilityOff,
                        label = stringResource(R.string.sel_label_hide),
                        enabled = !isDeleting,
                        // A hide ends in a permanent removal of the device originals it vaults, so it
                        // is confirmed exactly as the delete beside it is. The split is read at the
                        // tap, so the sheet names what THIS selection will have done to it.
                        onClick = {
                            hideConfirmSplit = viewModel.hideSplitForSelection().takeIf { !it.isEmpty }
                        },
                    )
                )
            }
            // Delete selected - same sheet + system trash dialog as the gallery, with the option
            // to also remove the Drive copy of any backed-up photos.
            if (selectedDeviceItems.isNotEmpty()) {
                add(
                    SelectionAction(
                        icon = Icons.Default.DeleteOutline,
                        label = stringResource(R.string.sel_label_delete),
                        tint = ErrorColor,
                        onClick = { showDeleteSheet = true },
                    )
                )
            }
        }
        SelectionDrawer(
            visible = isSelectionMode,
            items = selectedItems,
            actions = folderSelectionActions,
            onDismiss = { viewModel.clearSelection() },
            // Scrolling the folder grid collapses the drawer, so reaching past it to carry on
            // through the photos needs no deliberate pull or tap first.
            contentScrolling = gridState.isScrollInProgress,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (showStripPicker) {
            MetadataStripPickerDialog(
                onConfirm = {
                    showStripPicker = false
                    viewModel.stripMetadataSelected(it)
                },
                onDismiss = { showStripPicker = false },
            )
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
                u in selectedUris && u !in vaultedUris
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
        if (showPhotoShareSheet && selectedUris.isNotEmpty()) {
            PhotoShareSheet(
                sheetState = photoShareSheetState,
                canCreateLink = false,
                // The public link is a Drive share, so it needs a signed-in account.
                showPublicLink = selectedUris.size == 1 && isSignedIn,
                showShareWithPeople = false,
                localUploadEnabled = true,
                onDismiss = { showPhotoShareSheet = false },
                onSendToApp = { showPhotoShareSheet = false; viewModel.shareSelected() },
                onShareWithPeople = { showPhotoShareSheet = false },
                onManagePublicLink = {
                    showPhotoShareSheet = false
                    viewModel.resetPublicLinkState()
                    showManageLinkSheet = true
                },
            )
        }

        if (showManageLinkSheet) {
            ManagePublicLinkSheet(
                sheetState = manageLinkSheetState,
                publicLinkState = publicLinkState,
                needsUpload = selectedUris.size == 1,
                onUploadAndCreate = { viewModel.uploadAndCreateSelectedLink() },
                onDismiss = { showManageLinkSheet = false },
                onCreateLink = { viewModel.uploadAndCreateSelectedLink() },
                onCopyLink = {
                    viewModel.currentPublicLinkUrl()?.let { url ->
                        copySensitiveText(shareCtx, "Photo link", url)
                        scope.launch { snackbarHostState.showSnackbar(linkCopiedMsg) }
                    }
                },
                onRemoveLink = { viewModel.revokePublicLink() },
                onSetPassword = { password -> viewModel.setLinkPassword(password) },
            )
        }

        if (showAddToAlbumSheet && selectedUris.isNotEmpty()) {
            val addItems = items.filter {
                val u = when (it) {
                    is GalleryItem.LocalOnly -> it.local.uri
                    is GalleryItem.Synced -> it.local.uri
                    is GalleryItem.CloudOnly -> null
                }
                u in selectedUris && u !in vaultedUris
            }.toSet()
            eu.akoos.photos.presentation.gallery.GalleryAddToAlbumDialog(
                selectedItems = addItems,
                cloudAlbums = albums,
                sheetState = addToAlbumSheetState,
                onCreateNew = {
                    showAddToAlbumSheet = false
                    showCreateAlbumInline = true
                },
                onCloudAlbumSelected = { album ->
                    showAddToAlbumSheet = false
                    viewModel.addSelectedToAlbum(album.linkId) { joined, queued ->
                        showAddToAlbumOutcome(joined, queued, error = null)
                    }
                },
                onDismiss = { showAddToAlbumSheet = false },
            )
        }

        // Name a brand-new album for the selection, then create it and add the photos to it — the
        // same two steps the timeline's picker runs, so the row means the same thing on both.
        if (showCreateAlbumInline) {
            eu.akoos.photos.presentation.gallery.GalleryNewAlbumDialog(
                onDismiss = { showCreateAlbumInline = false },
                onCreate = { name ->
                    showCreateAlbumInline = false
                    viewModel.createAlbumThenAddSelected(name) { joined, queued, error ->
                        showAddToAlbumOutcome(joined, queued, error)
                    }
                },
            )
        }

        // Move-to-folder host: the picker, the new-folder name dialog, the write-consent launcher a
        // foreign-file move needs and the completion snackbar, all logged-out and device-only. The
        // selection is snapshotted in the ViewModel, so the host carries only the picked name.
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

        // Hide confirmations — the shared sheet every hide surface raises, worded from the split the
        // hide will really run on. The selection's and the whole folder's are separate taps.
        hideConfirmSplit?.let { split ->
            HideConfirmSheet(
                split = split,
                title = stringResource(R.string.hide_confirm_title),
                onConfirm = {
                    hideConfirmSplit = null
                    viewModel.hideSelected()
                },
                onDismiss = { hideConfirmSplit = null },
            )
        }
        folderHideConfirmSplit?.let { split ->
            HideConfirmSheet(
                split = split,
                title = stringResource(R.string.device_folder_hide_card),
                onConfirm = {
                    folderHideConfirmSplit = null
                    viewModel.toggleHiddenCard()
                },
                onDismiss = { folderHideConfirmSplit = null },
            )
        }

        // Confirm before pinning the selected photo as this folder's cover, matching the album's.
        if (showSetCoverConfirm && FolderCoverSelection.pinnable(selectedUris, vaultedUris) != null) {
            val coverUpdatedMsg = stringResource(R.string.album_cover_updated)
            ConfirmSheet(
                title = stringResource(R.string.device_folder_set_cover_confirm_title),
                message = stringResource(R.string.device_folder_set_cover_confirm_body),
                confirmLabel = stringResource(R.string.album_set_as_cover),
                dismissLabel = stringResource(R.string.cancel),
                onConfirm = {
                    showSetCoverConfirm = false
                    viewModel.setSelectedAsFolderCover {
                        scope.launch { snackbarHostState.showSnackbar(coverUpdatedMsg) }
                    }
                },
                onDismiss = { showSetCoverConfirm = false },
            )
        }

        // Confirm before uploading the current selection (single or multiple) to Drive.
        if (showUploadConfirm && selectedUris.isNotEmpty()) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            ConfirmSheet(
                title = stringResource(R.string.upload_confirm_title),
                message = stringResource(R.string.upload_confirm_message, selectedUris.size),
                confirmLabel = stringResource(R.string.upload_action_short),
                dismissLabel = stringResource(R.string.cancel),
                onConfirm = {
                    showUploadConfirm = false
                    viewModel.uploadSelected { outcome ->
                        scope.launch {
                            val msg = if (outcome.queued > 0) R.string.backup_started
                                else R.string.device_folder_already_backed_up
                            snackbarHostState.showSnackbar(ctx.getString(msg))
                        }
                    }
                },
                onDismiss = { showUploadConfirm = false },
            )
        }

        // One pill for the folder's two long operations, the same surface every other screen uses
        // for bulk work. A folder can hold thousands and both the back-up and the vault copy file by
        // file, so the user watches the count and stops it from here rather than waiting on a
        // blocked screen. [DeviceFolderProgress] picks which one is on show when both run, and the
        // cancel branches mirror the progress branches, so the X always stops what is reported.
        val backingUpTpl = stringResource(R.string.op_backing_up_fmt)
        val hidingTpl = stringResource(R.string.device_folder_hiding_fmt)
        val restoringTpl = stringResource(R.string.device_folder_restoring_fmt)
        val folderOp = DeviceFolderProgress.operation(
            vaultRunning = folderVault != null,
            vaultRestoring = folderVault?.restoring == true,
            backupRunning = backupProgress != null,
        )
        val folderOpProgress = when (folderOp) {
            DeviceFolderOperation.Hiding, DeviceFolderOperation.Restoring -> folderVault?.let { fv ->
                val tpl = if (fv.restoring) restoringTpl else hidingTpl
                eu.akoos.photos.presentation.common.OperationProgress(
                    fv.done, fv.total, tpl.format(fv.done, fv.total),
                )
            }
            DeviceFolderOperation.BackingUp -> backupProgress?.let { bp ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    bp.done, bp.total, backingUpTpl.format(bp.done, bp.total),
                )
            }
            null -> null
        }
        val folderOpCancel: (() -> Unit)? = when (folderOp) {
            DeviceFolderOperation.Hiding, DeviceFolderOperation.Restoring -> viewModel::cancelFolderVault
            DeviceFolderOperation.BackingUp -> viewModel::cancelBackup
            null -> null
        }
        eu.akoos.photos.presentation.common.OperationProgressPill(
            progress = folderOpProgress,
            onCancel = folderOpCancel,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
        )

        // Jump-to-top pill: appears once scrolled down, hidden during selection so it never
        // collides with the selection drawer. Mirrors the cloud-album detail page.
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
                diameter = 40.dp,
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
    }

    // Folder drawer — back up (stream only, or also as an album) plus the per-folder preferences.
    // An empty folder keeps the drawer and loses only the two rows with nothing to run on: its
    // preferences are exactly what an emptied-by-a-filter folder needs, so gating the whole drawer
    // on photos would put them out of reach from the one screen that is about this folder.
    if (showFolderOverflow) {
        val bp = backupProgress
        val hasPhotos = items.isNotEmpty()
        DeviceFolderActionsSheet(
            sheetState = folderActionsSheetState,
            backupBusy = bp != null,
            backupFraction = bp?.takeIf { it.total > 0 }?.let { it.done.toFloat() / it.total },
            isMirroredAsAlbum = isMirroredAsAlbum,
            isExcludedFromBackup = isExcludedFromBackup,
            isHiddenFromTimeline = isHiddenFromTimeline,
            isHiddenCard = isHiddenCard,
            isSignedIn = isSignedIn,
            sortMode = sortMode,
            onDismiss = { showFolderOverflow = false },
            onBackUp = if (hasPhotos) ({ asMirror -> backUpFolder(asMirror) }) else null,
            onToggleMirrorAsAlbum = viewModel::toggleMirrorAsAlbum,
            onToggleExcludedFromBackup = viewModel::toggleExcludedFromBackup,
            onToggleHiddenFromTimeline = viewModel::toggleHiddenFromTimeline,
            onToggleHiddenCard = {
                // Only the hiding direction is confirmed: the same row reveals the folder when this
                // screen was opened from the vault, and that puts photos back rather than away. A
                // folder with nothing left to move is only marked hidden, so it needs no sheet.
                val split = if (fromVault) null else viewModel.folderHideSplitPreview().takeIf { !it.isEmpty }
                if (split == null) viewModel.toggleHiddenCard() else folderHideConfirmSplit = split
            },
            // Hands the folder to the viewer already playing, opening on the first photo of the
            // current sort order.
            onSlideshow = if (hasPhotos) ({ onSlideshowClick(items) }) else null,
            onSortSelected = viewModel::setSortMode,
            // Logged-out only; the row renders behind the same gate in the sheet.
            onRename = { showRenameSheet = true },
        )
    }

    // Rename this folder in place: the same field the Albums grid's device-folder rename uses,
    // prefilled with the current name. The rename relocates the folder's photos into a new DCIM
    // directory and the screen follows them, so it never pops back to the grid.
    if (showRenameSheet) {
        EditFieldSheet(
            title = stringResource(R.string.folder_rename),
            hint = stringResource(R.string.albums_create_album_hint),
            initialValue = bucketName,
            singleLine = true,
            confirmLabel = stringResource(R.string.album_rename_confirm),
            canConfirm = { AlbumRenameInput.isAcceptable(it, bucketName) },
            onDismiss = { showRenameSheet = false },
            onSave = { entered ->
                showRenameSheet = false
                viewModel.renameFolder(entered)
            },
        )
    }
}
