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

package eu.akoos.photos.presentation.albums

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import eu.akoos.photos.domain.entity.GalleryItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import eu.akoos.photos.presentation.gallery.ScrollDateLabel
import eu.akoos.photos.presentation.gallery.TimelineScrubber
import eu.akoos.photos.presentation.gallery.TimelineGrouping
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import eu.akoos.photos.presentation.common.CloudMetadataSaveDrawer
import eu.akoos.photos.presentation.common.ConfirmDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.presentation.gallery.CloudSaveDrawerViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.presentation.viewer.ManagePublicLinkSheet
import eu.akoos.photos.presentation.viewer.PhotoShareSheet
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.presentation.common.EditFieldSheet
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.ReturnToViewerPhoto
import eu.akoos.photos.presentation.common.SelectionAction
import eu.akoos.photos.presentation.common.SelectionDrawer
import eu.akoos.photos.presentation.common.anyMetadataEditable
import eu.akoos.photos.presentation.common.favoriteSelectionAction
import eu.akoos.photos.presentation.common.favoriteTurnsOnForCloudPhotos
import eu.akoos.photos.presentation.common.offlineTurnsOn
import eu.akoos.photos.domain.entity.ShareInvitation
import eu.akoos.photos.domain.entity.ShareMember
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.theme.StatusSynced
import eu.akoos.photos.presentation.util.monthYearFormat
import eu.akoos.photos.util.copySensitiveText

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumLinkId: String,
    albumName: String,
    shareId: String? = null,
    sharedByEmail: String? = null,
    volumeId: String? = null,
    coverThumbnailUrl: String? = null,
    /** True when the entry point asked to share this album rather than browse it, so the share drawer
     *  opens with the screen. [onShareSheetRequestConsumed] retires the request as soon as it is
     *  acted on, so coming back from the viewer does not raise the drawer again. */
    openShareSheet: Boolean = false,
    onShareSheetRequestConsumed: () -> Unit = {},
    /** An action the Albums grid asked for on this album, carried out once its members are in hand.
     *  Null on a plain open. */
    openAction: AlbumOpenAction? = null,
    /** Hands [openAction] back the moment it runs, so it lands exactly once. */
    onOpenActionConsumed: () -> Unit = {},
    /** Passes the full album photo list AND the index of the clicked photo so the viewer can swipe through siblings. */
    onPhotoClick: (List<GalleryItem>, Int) -> Unit,
    /** Opens the same viewer on the album's first photo with its slideshow already running. */
    onSlideshowClick: (List<GalleryItem>) -> Unit = {},
    /** Owner-only: opens the photo picker to add more photos. Carries the album's current cloud
     *  member linkIds so the picker pre-filters out photos already in the album. */
    onAddPhotosClick: (Set<String>) -> Unit = {},
    /** Opens the metadata editor over the supplied album photos. A multi-select carries the current
     *  selection; a whole-album long-press carries every member, each one set for all. */
    onEditMetadata: (List<GalleryItem>) -> Unit = {},
    onBack: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(albumLinkId) { viewModel.load(albumLinkId, albumName, shareId, sharedByEmail, volumeId) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // The selection's hide split while its confirmation is up, null when none is. Holding the split
    // rather than a flag is what lets the sheet describe the photos the tap was made on.
    var hideConfirmSplit by remember {
        mutableStateOf<eu.akoos.photos.data.hidden.HiddenFolderRecords.HideSplit?>(null)
    }
    // System trash-dialog launcher for deletes that remove the on-device copy (Android 11+).
    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeletePermissionGranted()
        else viewModel.clearPendingDeleteIntent()
    }
    LaunchedEffect(state.pendingDeleteIntent) {
        val pi = state.pendingDeleteIntent ?: return@LaunchedEffect
        // Some OEMs throw when the trash sender is launched, and a sender already spent across a
        // configuration change does too. Unguarded that took the app down and left the pending hide
        // neither committed nor rolled back, which the other screens already avoid.
        runCatching { deletePermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
            .onFailure { viewModel.clearPendingDeleteIntent() }
    }
    var showShareSheet by remember { mutableStateOf(false) }
    var showRenameSheet by remember { mutableStateOf(false) }
    var showSaveToLibraryConfirm by remember { mutableStateOf(false) }
    var showAlbumOverflow by remember { mutableStateOf(false) }
    var showLeaveAlbumConfirm by remember { mutableStateOf(false) }
    // Confirm the album actions that apply immediately, so a single tap can't trigger them by accident.
    var showDownloadAllConfirm by remember { mutableStateOf(false) }
    var showSetCoverConfirm by remember { mutableStateOf(false) }
    var showRemoveFromAlbumConfirm by remember { mutableStateOf(false) }
    // Warn before sharing when the selection has cloud-only photos (they download first).
    var showShareCloudWarning by remember { mutableStateOf(false) }
    val shareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val albumActionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Unified photo-selection share drawer + its manage-link sheet — same as the timeline.
    var showPhotoShareSheet by remember { mutableStateOf(false) }
    val photoShareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showManageLinkSheet by remember { mutableStateOf(false) }
    val manageLinkSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val publicLinkState by viewModel.publicLinkState.collectAsStateWithLifecycle()
    // Add-to-person drawer for the album selection.
    var showAddToPersonSheet by remember { mutableStateOf(false) }
    val addToPersonSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val albumPeople by viewModel.people.collectAsStateWithLifecycle()

    // The cloud metadata save drawer's live view, shared with the timeline through the app-scoped
    // controller, so a save started in this album shows its progress here rather than only on the feed.
    val cloudSaveVm: CloudSaveDrawerViewModel = hiltViewModel()
    val cloudSaveUi by cloudSaveVm.ui.collectAsStateWithLifecycle()

    // In selection mode, system back cancels the selection instead of popping the album.
    androidx.activity.compose.BackHandler(enabled = state.isSelectionMode) {
        viewModel.clearSelection()
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // After a hide moves backed-up photos to the vault, reassure the user their Drive copies are
    // untouched — the same notice the timeline shows. Mirrors the gallery's hideCloudNoticePending.
    val hideCloudNotice = stringResource(R.string.hide_cloud_copy_notice)
    LaunchedEffect(state.hideCloudNoticePending) {
        if (state.hideCloudNoticePending) {
            snackbarHostState.showSnackbar(hideCloudNotice)
            viewModel.clearHideCloudNotice()
        }
    }

    // Hand the VM-built share intent to the system chooser.
    val shareCtx = LocalContext.current
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    LaunchedEffect(Unit) {
        viewModel.shareIntent.collect { intent ->
            runCatching { shareCtx.startActivity(Intent.createChooser(intent, shareChooserTitle)) }
        }
    }

    LaunchedEffect(openShareSheet) {
        if (!openShareSheet) return@LaunchedEffect
        showShareSheet = true
        onShareSheetRequestConsumed()
    }

    // Hands the album to the viewer already playing, opening on the first photo of the current sort
    // order. Shared by the drawer's row and by a slideshow the Albums grid asked for.
    fun playSlideshow() {
        onSlideshowClick(
            state.photos.map { p ->
                AlbumPhotoItems.galleryItem(
                    p,
                    state.localItemByLinkId[p.linkId],
                    state.localUriByLinkId[p.linkId],
                )
            },
        )
    }

    // Opens the picker carrying this album's current members, which it hides so nothing already
    // here is offered again.
    fun addPhotos() = onAddPhotosClick(state.photos.map { it.linkId }.toSet())

    // Opens the metadata editor over the whole album, each photo typed the same way the selection's
    // items are, so the editor's own per-item rules decide what is writable. An empty album has
    // nothing to edit and is left alone.
    fun editAllMetadata() {
        if (state.photos.isEmpty()) return
        onEditMetadata(
            state.photos.map { p ->
                AlbumPhotoItems.galleryItem(
                    p,
                    state.localItemByLinkId[p.linkId],
                    state.localUriByLinkId[p.linkId],
                )
            },
        )
    }

    // An action asked for from the Albums grid arrives as an intent rather than as a raised drawer:
    // the grid offers those rows itself, so raising this screen's drawer would swap a sheet for its
    // twin. Each of them reads the album's members, which land after the screen mounts, so the
    // intent waits for them and then goes through this screen's own handlers, leaving the download
    // its confirmation, its progress pill and its cancel. The host's clear survives this screen
    // being disposed and rebuilt on the way back from the viewer; the latch covers the frames
    // before that clear is read.
    var openActionRun by remember { mutableStateOf(false) }
    LaunchedEffect(openAction, state.photos.size, state.isLoading) {
        val run = AlbumOpenIntent.shouldRun(
            action = openAction,
            photoCount = state.photos.size,
            photosLoaded = !state.isLoading,
            alreadyRun = openActionRun,
        )
        if (!run) return@LaunchedEffect
        openActionRun = true
        onOpenActionConsumed()
        when (openAction) {
            AlbumOpenAction.DownloadAll -> showDownloadAllConfirm = true
            AlbumOpenAction.Slideshow -> playSlideshow()
            AlbumOpenAction.AddPhotos -> addPhotos()
            AlbumOpenAction.EditMetadata -> editAllMetadata()
            null -> Unit
        }
    }

    // Refresh invitations + members on each sheet open so a prior revoke/remove isn't shown stale.
    LaunchedEffect(showShareSheet) {
        if (showShareSheet && !state.isSharedWithMe) viewModel.loadInvitations()
    }

    val linkCopiedMsg = stringResource(R.string.album_link_copied)
    LaunchedEffect(state.shareLink) {
        val link = state.shareLink ?: return@LaunchedEffect
        copySensitiveText(shareCtx, "Album link", link)
        snackbarHostState.showSnackbar(linkCopiedMsg)
        viewModel.clearShareLink()
    }

    // Invite-batch summary snackbar. Plurals resolved here (pluralStringResource is @Composable) for the effect below.
    val inviteSentCount = (state.inviteBatchResult?.successCount ?: 0).coerceAtLeast(1)
    val inviteSentMsg = androidx.compose.ui.res.pluralStringResource(
        R.plurals.share_invite_sent, inviteSentCount, inviteSentCount,
    )
    val inviteBatchPartialFmt   = stringResource(R.string.share_invite_summary_partial)
    val inviteBatchAllFailedFmt = stringResource(R.string.share_invite_summary_all_failed)
    LaunchedEffect(state.inviteBatchResult) {
        val r = state.inviteBatchResult ?: return@LaunchedEffect
        // Gate on !showShareSheet: while the sheet is open its inline banner shows the result, the snackbar only after close.
        when {
            r.failures.isEmpty() && !showShareSheet -> {
                snackbarHostState.showSnackbar(inviteSentMsg)
                viewModel.clearInviteBatchResult()
            }
            r.failures.isNotEmpty() && !showShareSheet -> {
                val lines = r.failures.joinToString("\n") { "${it.first}: ${it.second}" }
                val header = if (r.successCount == 0)
                    inviteBatchAllFailedFmt.format(r.failures.size)
                else
                    inviteBatchPartialFmt.format(r.successCount, r.failures.size)
                snackbarHostState.showSnackbar("$header\n$lines")
                viewModel.clearInviteBatchResult()
            }
            // Sheet open — the inline banner renders the result (dismissed via onDismissInviteResult()).
        }
    }

    val coverUpdatedMsg = stringResource(R.string.album_cover_updated)
    LaunchedEffect(state.coverUpdatedTick) {
        // tick == 0 is initial; only act on real bumps.
        if (state.coverUpdatedTick > 0) {
            snackbarHostState.showSnackbar(coverUpdatedMsg)
        }
    }

    val saveSuccessFmt = stringResource(R.string.shared_save_success_fmt)
    val savePartialFmt = stringResource(R.string.shared_save_partial_fmt)
    LaunchedEffect(state.saveToLibraryResult) {
        val r = state.saveToLibraryResult ?: return@LaunchedEffect
        val msg = if (r.failedCount == 0) {
            saveSuccessFmt.format(r.copiedCount)
        } else {
            savePartialFmt.format(r.copiedCount, r.totalRequested)
        }
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSaveToLibraryResult()
    }

    val saveCancelledFmt = stringResource(R.string.save_to_library_cancelled_fmt)
    LaunchedEffect(state.saveCancelledAt) {
        val pair = state.saveCancelledAt ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(saveCancelledFmt.format(pair.first, pair.second))
        viewModel.clearSaveCancelledAt()
    }

    // Saving for offline downloads each photo, so it announces its start like any other download.
    val offlineStartedMsg = stringResource(R.string.download_started_background)
    LaunchedEffect(Unit) {
        viewModel.offlineStarted.collect { snackbarHostState.showSnackbar(offlineStartedMsg) }
    }

    // Outcome of a finished album download. The progress ring lives on the Activity screen, so
    // without this the work ends in silence and a total failure looks exactly like a success.
    val downloadDoneSingular = stringResource(R.string.gallery_download_done_singular)
    val downloadDoneFmt = stringResource(R.string.gallery_download_done)
    val downloadPartialFmt = stringResource(R.string.gallery_download_partial)
    LaunchedEffect(Unit) {
        viewModel.downloadResult.collect { r ->
            val msg = when {
                r.failed > 0 -> downloadPartialFmt.format(r.saved, r.failed)
                r.saved == 1 -> downloadDoneSingular
                else -> downloadDoneFmt.format(r.saved)
            }
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Offline pin/un-pin outcome snackbar — mirrors the timeline's batch result.
    val offlineRemovedMsg = stringResource(R.string.offline_removed)
    LaunchedEffect(Unit) {
        viewModel.offlineResult.collect { count ->
            when {
                count > 0 -> snackbarHostState.showSnackbar(
                    shareCtx.resources.getQuantityString(R.plurals.offline_batch_result, count, count),
                )
                count < 0 -> snackbarHostState.showSnackbar(offlineRemovedMsg)
            }
        }
    }

    // Worker took over: a one-shot message points at the Activity screen, which lists the job
    // whether or not the download notification is switched on. Driven by an event, not by the
    // Enqueued state: that state is routinely collapsed by the progress value the work observer
    // publishes immediately after it, so a collector can miss it entirely.
    val enqueuedMsg = stringResource(R.string.download_started_background)
    LaunchedEffect(Unit) {
        viewModel.downloadStarted.collect { snackbarHostState.showSnackbar(enqueuedMsg) }
    }
    LaunchedEffect(state.downloadState) {
        if (state.downloadState is AlbumDownloadState.Enqueued) viewModel.resetDownloadState()
    }

    // Prefer a cover chosen in this session (set by runSetCover) so the header flips immediately,
    // then the nav-arg cover, then the first photo as a fallback for a brand-new album.
    val coverUrl = state.coverThumbnailUrl ?: coverThumbnailUrl ?: state.photos.firstOrNull()?.thumbnailUrl
    val appColors = AppColors.current
    val pullRefreshState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    val gridState = rememberLazyGridState()
    val showScrollTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 4 } }
    // Group photos by month, on the same capture time the rest of the app groups that photo by: a
    // member whose Drive captureTime is sub-floor reads its device twin's date. The twin comes from
    // the map the ViewModel already holds, so the lambda only does a hash lookup per row.
    // withIndex() preserves each photo's position so the viewer opens the right one.
    val photoGroups = remember(state.photos, state.localItemByLinkId) {
        val fmt = monthYearFormat()
        state.photos.withIndex().groupBy {
            val ms = AlbumPhotoItems.captureTimeMs(it.value, state.localItemByLinkId[it.value.linkId])
            fmt.format(java.util.Date(ms))
        }
    }
    // Scrubber shares the timeline handle. The grid keys photos by raw linkId (see items() below),
    // so wrap each cloud photo as a gallery item and map back to that same key.
    val scrubberItems = remember(state.photos, state.localItemByLinkId, state.localUriByLinkId) {
        state.photos.map {
            AlbumPhotoItems.galleryItem(it, state.localItemByLinkId[it.linkId], state.localUriByLinkId[it.linkId])
        }
    }
    val scrubberTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp
    // Opt-in floating day pill while the grid scrolls; default off. Shares the scrubber's date mapping.
    val showScrollDate by remember {
        shareCtx.settingsDataStore.data.map { it[SettingsKeys.SHOW_SCROLL_DATE] ?: false }
    }.collectAsState(initial = false)
    // Which direction the overflow's sort entries tick. The ViewModel reads the same preference for
    // the order itself, so this only has to say which one is active.
    val photoSortMode by remember {
        shareCtx.settingsDataStore.data.map { AlbumPhotoSortMode.fromOrdinal(it[SettingsKeys.ALBUM_PHOTO_SORT_MODE]) }
    }.collectAsState(initial = AlbumPhotoSortMode.Default)
    // Yields the pill while the scrubber bubble is being dragged so the two don't overlap.
    var scrubberDragging by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(appColors.bg0)) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.isLoading && state.photos.isNotEmpty(),
            onRefresh = { viewModel.refresh() },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize(),
        ) {
        val cols = eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns()
        val seamless = eu.akoos.photos.presentation.gallery.rememberSeamlessGrid()
        // Drag-to-select: long-press a photo then drag to sweep a range (shares the timeline gesture).
        // Cells are keyed by linkId, so the swept indices map back to the selected linkIds.
        val selectableLinkIds = remember(state.photos) { state.photos.map { it.linkId } }
        val linkIdToIndex = remember(state.photos) { state.photos.mapIndexed { i, p -> p.linkId to i }.toMap() }
        // Armed at the long-press anchor so the cell's release-tap skips toggling the just-selected
        // cell back off (otherwise a stationary long-press would select then immediately deselect).
        val tapGuard = remember { mutableStateOf(false) }
        val dragSelectModifier = eu.akoos.photos.presentation.gallery.rememberDragMultiSelectModifier(
            gridState = gridState,
            items = selectableLinkIds,
            indexByKey = linkIdToIndex,
            selected = state.selectedPhotos,
            onSelectionChange = viewModel::setSelectedPhotos,
            tapGuard = tapGuard,
        )
        // Land back on the photo the viewer closed on. The hero header is the one slot ahead of the
        // photos, and each month bucket carries its own header. Photos open as Synced or CloudOnly,
        // and both report the cloud linkId back.
        val returnGroups = remember(photoGroups) { photoGroups.values.toList() }
        ReturnToViewerPhoto(
            gridState = gridState,
            groups = returnGroups,
            headerPerGroup = true,
            leadingSlots = 1,
            keyOf = { it.value.linkId },
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            // Match the main timeline grid (GalleryGrid): same default columns, 20.dp side inset and
            // 6.dp gap, so album photos render at the same size and spacing as the Photos page.
            // Edge-to-edge drops the side inset and rounding and tightens the gap.
            contentPadding = PaddingValues(
                start = if (seamless) 0.dp else 20.dp,
                end = if (seamless) 0.dp else 20.dp,
                bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
            modifier = Modifier.fillMaxSize().then(dragSelectModifier),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val headerVideoCount = state.photos.count { it.mimeType.startsWith("video/") }
                val headerPhotoCount = state.photos.size - headerVideoCount
                val photosTextRes = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.count_photos_plural, headerPhotoCount, headerPhotoCount,
                )
                val videosTextRes = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.count_videos_plural, headerVideoCount, headerVideoCount,
                )
                // Running progress shows in the OperationProgressPill below, so this stays a plain count.
                val countLabel = when {
                    state.isLoading -> ""
                    headerPhotoCount > 0 && headerVideoCount > 0 -> "$photosTextRes, $videosTextRes"
                    headerVideoCount > 0 -> videosTextRes
                    else -> photosTextRes
                }
                // Photo tiles bleed to the edge in seamless mode; this cover header keeps the 20.dp inset.
                Box(modifier = Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp)) {
                eu.akoos.photos.presentation.albums.components.AlbumHeroHeader(
                    coverModel = coverUrl,
                    title = state.albumName.ifBlank { albumName },
                    photoCountText = countLabel,
                    coverParallax = {
                        if (gridState.firstVisibleItemIndex == 0)
                            gridState.firstVisibleItemScrollOffset.toFloat() else 0f
                    },
                    metaLeading = {
                        // Sharer avatar (shared-with-me) or members + pending invitees (own album).
                        val sharerEmail = state.sharedByEmail
                        if (state.isSharedWithMe && sharerEmail != null) {
                            // Periwinkle is the dark-mode pick; light mode falls back to accent.
                            val sharerTint = if (AppColors.current.isLight) Accent else Color(0xFFB0A0FF)
                            AvatarCircle(letter = sharerEmail.first().uppercase(), tint = sharerTint, size = 24.dp)
                        } else {
                            val allEmails = (state.members.map { it.email } + state.invitations.map { it.email }).distinct()
                            if (allEmails.isEmpty() && state.isLoadingInvitations) {
                                // Placeholder circles while the fetch is in flight.
                                repeat(2) {
                                    eu.akoos.photos.presentation.common.ShimmerBox(
                                        modifier = Modifier.size(24.dp),
                                        cornerRadius = 12.dp,
                                    )
                                }
                            } else {
                                // Cap at 2 — the meta line stays a glance, not a roster.
                                allEmails.take(2).forEach { email ->
                                    AvatarCircle(letter = email.first().uppercase(), tint = Accent, size = 24.dp)
                                }
                            }
                        }
                    },
                    titleActions = {
                        // One control at rest. Adding, sharing, downloading, renaming, the
                        // slideshow, the sort order and leaving all live in the drawer it opens, so
                        // the count and the button share a single row; a running worker's cancel
                        // rides the progress pill rather than a header pill of its own.
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(PillBg, CircleShape)
                                .border(0.5.dp, PillBorder, CircleShape)
                                .clickable(onClick = { showAlbumOverflow = true }),
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

            when {
                // Only skeleton an EMPTY grid. Reloading one that already has photos (returning from
                // the viewer re-runs load(), and so does every undo and pull-to-refresh) must leave
                // them in place: swapping them for nine placeholders collapses the grid, and a
                // collapsed grid clamps its restored scroll offset to the top, which is what threw
                // the user back to the first row. The refresh already shows through the pull-to-
                // refresh spinner, which is gated on exactly this pair of conditions.
                state.isLoading && state.photos.isEmpty() -> {
                    items(9, span = { GridItemSpan(1) }) {
                        eu.akoos.photos.presentation.common.ShimmerSquare(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 4.dp,
                        )
                    }
                }
                state.photos.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp).fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.albums_no_photos), color = FgMute, fontSize = 14.sp)
                    }
                }
                else -> photoGroups.forEach { (label, entries) ->
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
                            // Tri-state group selector (selecting only) — toggles every photo in the day.
                            if (state.isSelectionMode) {
                                val groupLinkIds = entries.map { it.value.linkId }
                                val selectedInGroup = groupLinkIds.count { it in state.selectedPhotos }
                                val allSelected = groupLinkIds.isNotEmpty() && selectedInGroup == groupLinkIds.size
                                val partiallySelected = selectedInGroup > 0 && !allSelected
                                Box(
                                    modifier = Modifier
                                        .padding(end = 12.dp)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .clickable { viewModel.toggleGroupSelection(groupLinkIds) }
                                        .then(
                                            if (allSelected || partiallySelected)
                                                Modifier.background(appColors.accent, CircleShape)
                                            else Modifier
                                                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                                                .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    when {
                                        allSelected -> Icon(
                                            Icons.Default.Check, stringResource(R.string.cd_select_month),
                                            tint = Color.White, modifier = Modifier.size(14.dp),
                                        )
                                        partiallySelected -> Box(
                                            modifier = Modifier.size(8.dp).background(Color.White, CircleShape),
                                        )
                                    }
                                }
                            }
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
                    items(entries, key = { it.value.linkId }) { entry ->
                        val index = entry.index
                        val photo = entry.value
                        PhotoCell(
                            photo = photo,
                            localUri = state.localUriByLinkId[photo.linkId],
                            isSelected = photo.linkId in state.selectedPhotos,
                            isSelectionMode = state.isSelectionMode,
                            isOffline = photo.linkId in state.offlinePinIds,
                            columns = cols,
                            seamless = seamless,
                            // Long-press enters multi-select directly; cover/remove live in the drawer.
                            showLongPressMenu = false,
                            onTap = {
                                // Skip the release-tap that follows a long-press select; it would
                                // otherwise toggle the just-anchored cell back off.
                                if (tapGuard.value) {
                                    tapGuard.value = false
                                } else if (state.isSelectionMode) viewModel.togglePhotoSelection(photo.linkId)
                                else {
                                    // Wrap as Synced where a local copy exists so the viewer offers device + cloud + both,
                                    // over the device file itself where the merged library paired one, since that is where
                                    // the details sheet reads the name, size and folder, and the categoriser the dimensions.
                                    val viewerItems = state.photos.map { p ->
                                        AlbumPhotoItems.galleryItem(
                                            p, state.localItemByLinkId[p.linkId], state.localUriByLinkId[p.linkId],
                                        )
                                    }
                                    onPhotoClick(viewerItems, index)
                                }
                            },
                            // Long-press + drag is handled by the grid-level drag-select; a plain
                            // long-press there selects this single cell, and the tap-guard keeps the
                            // release-tap from undoing it.
                            onLongPress = null,
                            onSetAsCover = { viewModel.setPhotoAsCover(photo.linkId) },
                            onRemoveFromAlbum = {
                                // Reuse the bulk-remove path with a one-photo selection to share error/progress handling.
                                viewModel.togglePhotoSelection(photo.linkId)
                                viewModel.removeSelectedPhotosFromAlbum()
                            },
                            onRequestThumbnail = viewModel::requestThumbnailDecrypt,
                            onCancelThumbnail = viewModel::cancelThumbnailDecrypt,
                        )
                    }
                }
            }
        }
        }

        // Fast-scroll scrubber over the photo grid — the same handle as the timeline. The scrubber
        // groups by day, so the drag tooltip reads "d MMMM yyyy" even though the section headers are month.
        if (state.photos.isNotEmpty()) {
            TimelineScrubber(
                gridState = gridState,
                items = scrubberItems,
                grouping = TimelineGrouping.Day,
                topPadding = scrubberTopInset,
                bottomPadding = 24.dp,
                // Both wrappings a member can take report the cloud linkId as their stable id, which
                // is exactly the key the grid cells above use.
                keyOf = { it.stableId },
                onDraggingChange = { scrubberDragging = it },
            )
        }

        // Opt-in floating day pill, top-centre while the grid scrolls, yielding while the scrubber drags.
        if (showScrollDate) {
            ScrollDateLabel(
                gridState = gridState,
                items = scrubberItems,
                grouping = TimelineGrouping.Day,
                topPadding = scrubberTopInset,
                suppressed = scrubberDragging,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // Fixed back button — floats over the hero image
        IconBubble(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = if (state.isSelectionMode) stringResource(R.string.gallery_cancel_selection) else stringResource(R.string.close),
            onClick = { if (state.isSelectionMode) viewModel.clearSelection() else onBack() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 10.dp),
            diameter = 40.dp,
            iconSize = 18.dp,
            background = PillBg,
            borderColor = PillBorder,
            tint = appColors.fgPrimary,
        )


        // Jump-to-top pill: appears once scrolled down, and only outside selection mode so it
        // never collides with the selection drawer. Tap eases back to the album header.
        AnimatedVisibility(
            visible = showScrollTop && !state.isSelectionMode,
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
                tint = appColors.fgPrimary,
            )
        }

        // Selection drawer: the shared surface every multi-select uses, so an album's bulk actions
        // all sit in one list, in the order every other surface lists them.
        val isDownloadingSel = state.downloadState is AlbumDownloadState.Working
        // Download and offline only apply to cloud-only photos; Synced ones already live on the
        // device, so gate both like the gallery does. Keep Download listed while a download is
        // mid-flight so its cancel control stays reachable.
        val anyCloudOnlySelected = state.selectedPhotos.any { it !in state.localUriByLinkId }
        // Which way the offline row goes, so it names the press rather than the state: a pin while
        // anything pinnable in the selection is still un-pinned, a removal once none is.
        val offlinePinsSelection = offlineTurnsOn(
            state.selectedPhotos.filter { it !in state.localUriByLinkId },
            state.offlinePinIds,
        )
        // Taking a copy is not offered for an album someone else shared: those photos live on the
        // owner's volume, and the supported route to a copy is saving the album into your own
        // library, which the header action already offers. Same line the hide and cover affordances
        // draw.
        val canTakeCopies = !state.isSharedWithMe
        val allAlbumSelected = state.photos.isNotEmpty() &&
            state.selectedPhotos.size == state.photos.size
        val isSharingPhotos = state.shareState is AlbumShareState.Working
        val selectedAlbumPhotos = remember(state.photos, state.selectedPhotos) {
            state.photos.filter { it.linkId in state.selectedPhotos }
        }
        val selectedAlbumItems = remember(selectedAlbumPhotos, state.localItemByLinkId, state.localUriByLinkId) {
            selectedAlbumPhotos.map {
                AlbumPhotoItems.galleryItem(
                    it, state.localItemByLinkId[it.linkId], state.localUriByLinkId[it.linkId],
                )
            }
        }
        val albumSelectionActions = buildList {
            add(
                SelectionAction(
                    icon = Icons.Default.SelectAll,
                    label = stringResource(
                        if (allAlbumSelected) R.string.gallery_deselect_all else R.string.select_all,
                    ),
                    onClick = {
                        val all = state.photos.map { it.linkId }.toSet()
                        viewModel.setSelectedPhotos(if (allAlbumSelected) emptySet() else all)
                    },
                )
            )
            add(
                SelectionAction(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.sel_label_share),
                    enabled = !isSharingPhotos,
                    working = isSharingPhotos,
                    progress = (state.shareState as? AlbumShareState.Working)?.let {
                        if (it.total > 0) it.done.toFloat() / it.total else 0f
                    },
                    onClick = { showPhotoShareSheet = true },
                )
            )
            // Edit date + place, placed right after Share so it is reachable without scrolling the
            // action row. The editor writes a device copy in place and re-uploads a corrected copy for a
            // cloud image, so it shows off an own album whenever the selection holds something it can
            // change. It stays off a shared-with-me album, where those fields belong to the owner.
            if (!state.isSharedWithMe && anyMetadataEditable(selectedAlbumItems)) {
                add(
                    SelectionAction(
                        icon = Icons.Default.EditNote,
                        label = stringResource(R.string.metadata_editor_edit_metadata),
                        onClick = { onEditMetadata(selectedAlbumItems) },
                    )
                )
            }
            add(
                SelectionAction(
                    icon = Icons.Default.PersonAdd,
                    label = stringResource(R.string.gallery_add_to_person),
                    onClick = { showAddToPersonSheet = true },
                )
            )
            // Favourite the selection. An album is where a set worth favouriting is already
            // gathered, and the cells carry the heart, so the press shows on the grid it came
            // from. Off a shared-with-me album: tag 0 belongs to the owner's photo, the same
            // line hide, delete and cover draw.
            if (!state.isSharedWithMe) {
                add(
                    favoriteSelectionAction(
                        turnsOn = favoriteTurnsOnForCloudPhotos(selectedAlbumPhotos),
                        state = state.favoriteState,
                        onClick = { viewModel.toggleSelectedFavorite() },
                    )
                )
            }
            // Download selected. While the worker runs this row becomes the cancel control: a
            // determinate ring tracks progress and the label reads "Cancel".
            if (canTakeCopies && (anyCloudOnlySelected || isDownloadingSel)) {
                val dl = state.downloadState as? AlbumDownloadState.Working
                add(
                    SelectionAction(
                        icon = Icons.Default.FileDownload,
                        label = stringResource(R.string.sel_label_download),
                        working = isDownloadingSel,
                        progress = dl?.let { if (it.total > 0) it.done.toFloat() / it.total else 0f },
                        workingIcon = Icons.Default.Close,
                        workingLabel = stringResource(R.string.cancel),
                        clickableWhileWorking = true,
                        onClick = {
                            if (isDownloadingSel) viewModel.cancelDownload()
                            else viewModel.downloadSelectedPhotos()
                        },
                    )
                )
            }
            // Make available offline pins the full-res copy into the app so album photos open
            // with no connection. A tap toggles: pins the selection, or removes it if all pinned.
            if (canTakeCopies && anyCloudOnlySelected) {
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
            if (!state.isSharedWithMe && state.selectedCount == 1) {
                add(
                    SelectionAction(
                        icon = Icons.Default.PhotoLibrary,
                        label = stringResource(R.string.album_set_as_cover),
                        onClick = { showSetCoverConfirm = true },
                    )
                )
            }
            // The destructive hide + delete affordances stay off shared-with-me albums. Even an
            // editor recipient can't hide or delete someone else's photo from someone else's album
            // through this surface, the backend rejects it.
            if (!state.isSharedWithMe) {
                // Hide selected: a backed-up member moves into the app's Hidden vault, a cloud-only
                // member hides client-side by linkId. Mirrors the timeline / search / folder hide.
                add(
                    SelectionAction(
                        icon = Icons.Default.VisibilityOff,
                        label = stringResource(R.string.sel_label_hide),
                        enabled = !state.isDeletingPhotos,
                        // Confirmed exactly as the delete beside it is. The split is read at the tap,
                        // so the sheet names what THIS selection will have done to it.
                        onClick = {
                            hideConfirmSplit = viewModel.hideSplitForSelection().takeIf { !it.isEmpty }
                        },
                    )
                )
            }
            // Removing is an edit, so it follows the same right as adding rather than plain
            // ownership. An editor on a shared album may take photos back out of it, including
            // ones another member added: album membership records no contributor, so "only your
            // own" is not answerable, and the official client draws the same line.
            if (state.canAddPhotos) {
                add(
                    SelectionAction(
                        icon = Icons.Default.RemoveCircleOutline,
                        // Named in full, so the row beside Delete cannot be read as destroying the
                        // photo: this one takes it out of the album and leaves it in the library.
                        label = stringResource(R.string.album_remove_from_album),
                        // Taking a photo out of the album is destructive, so it reads red like delete.
                        tint = ErrorColor,
                        enabled = !state.isDeletingPhotos,
                        onClick = { showRemoveFromAlbumConfirm = true },
                    )
                )
            }
            if (!state.isSharedWithMe) {
                add(
                    SelectionAction(
                        icon = Icons.Default.DeleteOutline,
                        label = stringResource(R.string.sel_label_delete),
                        tint = ErrorColor,
                        enabled = !state.isDeletingPhotos,
                        working = state.isDeletingPhotos,
                        onClick = { showDeleteConfirm = true },
                    )
                )
            }
        }
        SelectionDrawer(
            visible = state.isSelectionMode,
            items = selectedAlbumItems,
            actions = albumSelectionActions,
            onDismiss = { viewModel.clearSelection() },
            // Scrolling the album grid collapses the drawer, so reaching past it to carry on
            // through the photos needs no deliberate pull or tap first.
            contentScrolling = gridState.isScrollInProgress,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Unified progress pill: one surface for save-to-library and multi-share, matching the
        // device-folder back-up and gallery bulk actions. Sits at the top edge, clear of the
        // selection drawer the share runs from. The download and the offline pin are absent: both
        // register with the TransferCenter, so the Activity screen reports them and cancels them,
        // and the download's own drawer row already doubles as its cancel here.
        val savingTpl = stringResource(R.string.shared_save_progress_fmt)
        val preparingLabel = stringResource(R.string.share_preparing)
        val sharingTpl = stringResource(R.string.op_sharing_fmt)
        val shState = state.shareState
        val opProgress = when {
            state.isSavingToLibrary && state.savingTotal > 0 ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    state.savingCopied, state.savingTotal,
                    savingTpl.format(state.savingCopied, state.savingTotal),
                )
            // The copy announces itself before it knows how many photos it will move, and this pill
            // is the only progress surface, so it takes over from the very first tick.
            state.isSavingToLibrary ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    0, 0, preparingLabel, indeterminate = true,
                )
            shState is AlbumShareState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    shState.done, shState.total, sharingTpl.format(shState.done, shState.total),
                )
            else -> null
        }
        // Cancel travels with the progress it stops, so the save offers its on the surface already
        // reporting it instead of a header pill. The branches mirror the chain above, so the X
        // always belongs to the operation on show.
        val opCancel: (() -> Unit)? = when {
            state.isSavingToLibrary -> viewModel::cancelSaveToLibrary
            else -> null
        }
        eu.akoos.photos.presentation.common.OperationProgressPill(
            progress = opProgress,
            onCancel = opCancel,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
        )

        // Delete / remove-from-album take over with a blocking drawer so a second tap can't fire
        // into a half-finished bulk action; the pill above stays for background downloads/shares.
        val opDeletingLabel = stringResource(R.string.op_deleting)
        val opRemovingLabel = stringResource(R.string.op_removing_from_album)
        val opHidingLabel = stringResource(R.string.op_hiding)
        val albumBusyProgress = if (state.isDeletingPhotos) {
            val label = when (state.busyOp) {
                AlbumBusyOp.Removing -> opRemovingLabel
                AlbumBusyOp.Hiding -> opHidingLabel
                else -> opDeletingLabel
            }
            eu.akoos.photos.presentation.common.OperationProgress(0, 0, label, indeterminate = true)
        } else null
        eu.akoos.photos.presentation.common.BlockingOperationSheet(albumBusyProgress)

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    // Add the album selection to a person, mirroring the timeline's add-to-person.
    if (showAddToPersonSheet && state.selectedCount > 0) {
        eu.akoos.photos.presentation.gallery.GalleryAddToPersonSheet(
            people = albumPeople,
            sheetState = addToPersonSheetState,
            onPersonSelected = { personId ->
                showAddToPersonSheet = false
                viewModel.addSelectedToPerson(personId)
            },
            onDismiss = { showAddToPersonSheet = false },
        )
    }

    // Unified share drawer for a photo selection (Send to app / Public link) — mirrors the timeline
    // so an album selection shares the same way. "Share with people" is hidden (already in an album).
    if (showPhotoShareSheet && state.selectedCount > 0) {
        PhotoShareSheet(
            sheetState = photoShareSheetState,
            canCreateLink = true,
            // Publishing a photo is the album owner's call and Drive refuses the request from a
            // guest, so an album shared with this user offers no link to create. The viewer's own
            // menu already draws this line. Sending a copy to another app stays either way: that
            // moves bytes the guest can already see and grants nobody access to the album.
            showPublicLink = state.selectedCount == 1 && !state.isSharedWithMe,
            showShareWithPeople = false,
            onDismiss = { showPhotoShareSheet = false },
            onSendToApp = {
                showPhotoShareSheet = false
                val needsDownload = state.selectedPhotos.any { state.localUriByLinkId[it] == null }
                if (needsDownload) showShareCloudWarning = true else viewModel.shareSelected()
            },
            onShareWithPeople = { showPhotoShareSheet = false },
            onManagePublicLink = {
                showPhotoShareSheet = false
                viewModel.loadPublicLink()
                showManageLinkSheet = true
            },
        )
    }

    if (showManageLinkSheet) {
        ManagePublicLinkSheet(
            sheetState = manageLinkSheetState,
            publicLinkState = publicLinkState,
            onDismiss = { showManageLinkSheet = false },
            onCreateLink = { viewModel.createSelectedPhotoLink() },
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

    if (showAlbumOverflow) {
        // The copy row reports the work it started: a determinate ring where the operation knows its
        // count, a spinner until then, and no second tap while either runs.
        val copyBusy = if (state.isSharedWithMe) {
            state.isSavingToLibrary
        } else {
            state.downloadState is AlbumDownloadState.Working
        }
        val copyFraction: Float? = if (state.isSharedWithMe) {
            state.savingTotal.takeIf { it > 0 }?.let { state.savingCopied.toFloat() / it }
        } else {
            (state.downloadState as? AlbumDownloadState.Working)
                ?.takeIf { it.total > 0 }
                ?.let { it.done.toFloat() / it.total }
        }
        AlbumActionsSheet(
            sheetState = albumActionsSheetState,
            isSharedWithMe = state.isSharedWithMe,
            // The album's own sharingShareId, carried in as shareId when the screen opens and
            // cleared here when the share is deleted.
            isShared = state.shareId != null,
            canAddPhotos = state.canAddPhotos,
            hasPhotos = state.photos.isNotEmpty(),
            copyBusy = copyBusy,
            copyFraction = copyFraction,
            sortMode = photoSortMode,
            isHiddenFromTimeline = state.isHiddenFromTimeline,
            onDismiss = { showAlbumOverflow = false },
            onAddPhotos = { addPhotos() },
            onShareOrInfo = { showShareSheet = true },
            onCopy = {
                if (state.isSharedWithMe) showSaveToLibraryConfirm = true
                else showDownloadAllConfirm = true
            },
            onEditMetadata = { editAllMetadata() },
            onRename = { showRenameSheet = true },
            onToggleHiddenFromTimeline = { viewModel.toggleHiddenFromTimeline() },
            onHide = { viewModel.hideAlbum() },
            onSlideshow = { playSlideshow() },
            onSortSelected = { mode ->
                scope.launch {
                    shareCtx.settingsDataStore.edit {
                        it[SettingsKeys.ALBUM_PHOTO_SORT_MODE] = mode.ordinal
                    }
                }
            },
            onLeaveAlbum = { showLeaveAlbumConfirm = true },
        )
    }

    // The cloud metadata save drawer follows the batch onto this screen, so a whole-album or in-album
    // edit shows its progress here instead of only on the timeline it closed back through.
    CloudMetadataSaveDrawer(ui = cloudSaveUi, onDismiss = { cloudSaveVm.dismiss() })

    if (showShareSheet) {
        if (state.isSharedWithMe) {
            SharedWithMeInfoSheet(
                sheetState = shareSheetState,
                sharedByEmail = state.sharedByEmail ?: "",
                onDismiss = { scope.launch { shareSheetState.hide() }.invokeOnCompletion { showShareSheet = false } },
            )
        } else {
            ShareAlbumSheet(
                sheetState = shareSheetState,
                albumName = state.albumName.ifBlank { albumName },
                isSharing = state.isSharing,
                isInvitingBatch = state.isInvitingBatch,
                isTogglingPublicLink = state.isTogglingPublicLink,
                publicShareUrl = state.publicShareUrl,
                ownerEmail = state.ownerEmail,
                hasShareRecord = state.shareId != null,
                invitations = state.invitations,
                externalInvitations = state.externalInvitations,
                members = state.members,
                isLoadingInvitations = state.isLoadingInvitations,
                inviteBatchResult = state.inviteBatchResult,
                onDismiss = { scope.launch { shareSheetState.hide() }.invokeOnCompletion { showShareSheet = false } },
                onCopyLink = { viewModel.createShareLink() },
                onInviteUsers = { emails, message, perms -> viewModel.inviteUsers(emails, message, perms) },
                onStopSharing = {
                    viewModel.deleteShare()
                    scope.launch { shareSheetState.hide() }.invokeOnCompletion { showShareSheet = false }
                },
                onRevokeInvitation = { invitationId -> viewModel.revokeInvitation(invitationId) },
                onRevokeExternalInvitation = { invitationId -> viewModel.revokeExternalInvitation(invitationId) },
                onRemoveMember = { memberId -> viewModel.removeMember(memberId) },
                onCreatePublicLink = { viewModel.createPublicLink() },
                onDisablePublicLink = { viewModel.disablePublicLink() },
                onChangeMemberPermission = { memberId, perm -> viewModel.changeMemberPermission(memberId, perm) },
                onChangeInvitationPermission = { invitationId, perm -> viewModel.changeInvitationPermission(invitationId, perm) },
                onDismissInviteResult = { viewModel.clearInviteBatchResult() },
            )
        }
    }

    if (showShareCloudWarning) {
        ConfirmDialog(
            title = stringResource(R.string.gallery_share_cloud_warning_title),
            message = stringResource(R.string.gallery_share_cloud_warning_body),
            confirmLabel = stringResource(R.string.gallery_share_cloud_warning_continue),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showShareCloudWarning = false
                viewModel.shareSelected()
            },
            onDismiss = { showShareCloudWarning = false },
        )
    }
    if (showSaveToLibraryConfirm) {
        // Confirms the cross-share copy before kicking off the round-trip. The
        // body explains both what the action does (copy to the recipient's own
        // library) and the storage-cost framing, so the user understands they
        // are duplicating photos into their own quota rather than just opening
        // a viewer. Photo count comes from the live state — the list is in
        // hand long before this dialog can pop.
        ConfirmDialog(
            title = stringResource(R.string.save_to_library_confirm_title),
            message = stringResource(
                R.string.save_to_library_confirm_body_fmt,
                state.photos.size,
            ),
            confirmLabel = stringResource(R.string.action_save),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showSaveToLibraryConfirm = false
                viewModel.saveSharedAlbumToOwnLibrary()
            },
            onDismiss = { showSaveToLibraryConfirm = false },
        )
    }
    if (showDownloadAllConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.album_download_all_confirm_title),
            message = stringResource(R.string.album_download_all_confirm_body),
            confirmLabel = stringResource(R.string.albums_download_all),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showDownloadAllConfirm = false
                viewModel.downloadAllPhotos()
            },
            onDismiss = { showDownloadAllConfirm = false },
        )
    }
    if (showSetCoverConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.album_set_cover_confirm_title),
            message = stringResource(R.string.album_set_cover_confirm_body),
            confirmLabel = stringResource(R.string.album_set_as_cover),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showSetCoverConfirm = false
                viewModel.setSelectedPhotoAsCover()
            },
            onDismiss = { showSetCoverConfirm = false },
        )
    }
    if (showRemoveFromAlbumConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.album_remove_confirm_title),
            message = stringResource(R.string.album_remove_confirm_body),
            confirmLabel = stringResource(R.string.action_remove),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showRemoveFromAlbumConfirm = false
                viewModel.removeSelectedPhotosFromAlbum()
            },
            onDismiss = { showRemoveFromAlbumConfirm = false },
            destructive = true,
        )
    }

    // Hide confirmation — the shared sheet every hide surface raises, worded from this selection's
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

    if (showDeleteConfirm && state.selectedPhotos.isNotEmpty()) {
        // Same sheet as the gallery and device folders: green-cloud (Synced) photos get the
        // device / cloud / both choice, cloud-only photos get accurate "moved to trash" copy.
        val deleteItems = remember(
            state.selectedPhotos, state.photos, state.localItemByLinkId, state.localUriByLinkId,
        ) {
            val byId = state.photos.associateBy { it.linkId }
            state.selectedPhotos.mapNotNull { linkId ->
                val photo = byId[linkId] ?: return@mapNotNull null
                AlbumPhotoItems.galleryItem(
                    photo, state.localItemByLinkId[linkId], state.localUriByLinkId[linkId],
                )
            }.toSet()
        }
        eu.akoos.photos.presentation.gallery.GalleryMultiDeleteDialog(
            selectedItems = deleteItems,
            onDismiss = { showDeleteConfirm = false },
            onDelete = { freeUpSpace, deleteFromCloud ->
                showDeleteConfirm = false
                viewModel.deleteSelectedPhotos(freeUpSpace, deleteFromCloud)
            },
        )
    }

    if (showRenameSheet) {
        // Pre-filled with the current album name. Empty or unchanged input is a no-op
        // (ViewModel.renameAlbum guards both cases) — the sheet still dismisses cleanly.
        EditFieldSheet(
            title = stringResource(R.string.album_rename),
            hint = stringResource(R.string.albums_create_album_hint),
            initialValue = state.albumName,
            singleLine = true,
            confirmLabel = stringResource(R.string.album_rename_confirm),
            canConfirm = { AlbumRenameInput.isAcceptable(it, state.albumName) },
            onDismiss = { showRenameSheet = false },
            onSave = { viewModel.renameAlbum(it) },
        )
    }

    // Leave-album confirmation dialog. Mounted off the overflow menu on
    // shared-with-me albums. Destructive styling matches "Delete album" since
    // the entry disappears from the user's library in both flows.
    if (showLeaveAlbumConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.leave_album_confirm_title),
            message = stringResource(R.string.leave_album_confirm_body),
            confirmLabel = stringResource(R.string.leave_album_confirm_action),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showLeaveAlbumConfirm = false
                viewModel.leaveSharedAlbum()
            },
            onDismiss = { showLeaveAlbumConfirm = false },
            destructive = true,
        )
    }

    // Once the leave round-trip completes successfully, pop back so the user
    // lands on the Shared tab instead of staring at a now-orphaned detail.
    androidx.compose.runtime.LaunchedEffect(state.leaveAlbumDone) {
        if (state.leaveAlbumDone) onBack()
    }

    // Hiding takes the album off every grid, so the screen showing it leaves the same way the leave
    // above does rather than holding a card the user can no longer reach.
    androidx.compose.runtime.LaunchedEffect(state.hideAlbumDone) {
        if (state.hideAlbumDone) onBack()
    }
}
