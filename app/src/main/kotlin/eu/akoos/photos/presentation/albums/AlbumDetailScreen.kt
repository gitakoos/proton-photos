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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import eu.akoos.photos.presentation.gallery.TimelineScrubber
import eu.akoos.photos.presentation.gallery.TimelineGrouping
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import eu.akoos.photos.presentation.common.ConfirmDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.presentation.viewer.ManagePublicLinkSheet
import eu.akoos.photos.presentation.viewer.PhotoShareSheet
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.domain.entity.ShareInvitation
import eu.akoos.photos.domain.entity.ShareMember
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.CardBg
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.theme.StatusSynced

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumLinkId: String,
    albumName: String,
    shareId: String? = null,
    sharedByEmail: String? = null,
    volumeId: String? = null,
    coverThumbnailUrl: String? = null,
    /** Passes the full album photo list AND the index of the clicked photo so the viewer can swipe through siblings. */
    onPhotoClick: (List<GalleryItem>, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(albumLinkId) { viewModel.load(albumLinkId, albumName, shareId, sharedByEmail, volumeId) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // System trash-dialog launcher for deletes that remove the on-device copy (Android 11+).
    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeletePermissionGranted()
        else viewModel.clearPendingDeleteIntent()
    }
    LaunchedEffect(state.pendingDeleteIntent) {
        val pi = state.pendingDeleteIntent ?: return@LaunchedEffect
        deletePermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }
    var showShareSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSaveToLibraryConfirm by remember { mutableStateOf(false) }
    var showSharedAlbumOverflow by remember { mutableStateOf(false) }
    var showLeaveAlbumConfirm by remember { mutableStateOf(false) }
    // Warn before sharing when the selection has cloud-only photos (they download first).
    var showShareCloudWarning by remember { mutableStateOf(false) }
    val shareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // Unified photo-selection share drawer + its manage-link sheet — same as the timeline.
    var showPhotoShareSheet by remember { mutableStateOf(false) }
    val photoShareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showManageLinkSheet by remember { mutableStateOf(false) }
    val manageLinkSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val publicLinkState by viewModel.publicLinkState.collectAsStateWithLifecycle()

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

    // Hand the VM-built share intent to the system chooser.
    val shareCtx = LocalContext.current
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    LaunchedEffect(Unit) {
        viewModel.shareIntent.collect { intent ->
            runCatching { shareCtx.startActivity(Intent.createChooser(intent, shareChooserTitle)) }
        }
    }

    // Refresh invitations + members on each sheet open so a prior revoke/remove isn't shown stale.
    LaunchedEffect(showShareSheet) {
        if (showShareSheet && !state.isSharedWithMe) viewModel.loadInvitations()
    }

    val linkCopiedMsg = stringResource(R.string.album_link_copied)
    LaunchedEffect(state.shareLink) {
        val link = state.shareLink ?: return@LaunchedEffect
        clipboard.setText(AnnotatedString(link))
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
                val lines = r.failures.joinToString("\n") { "${it.first} — ${it.second}" }
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

    val enqueuedMsg = stringResource(R.string.album_download_enqueued)
    LaunchedEffect(state.downloadState) {
        when (state.downloadState) {
            // Worker took over — one-shot snackbar pointing the user to the notification.
            is AlbumDownloadState.Enqueued -> {
                snackbarHostState.showSnackbar(enqueuedMsg)
                viewModel.resetDownloadState()
            }
            else -> Unit
        }
    }

    val coverUrl = coverThumbnailUrl ?: state.photos.firstOrNull()?.thumbnailUrl
    val appColors = AppColors.current
    val pullRefreshState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    val gridState = rememberLazyGridState()
    val showScrollTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 4 } }
    // Group photos by month like the timeline. withIndex() preserves each photo's position so the viewer opens the right one.
    val photoGroups = remember(state.photos) {
        val fmt = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
        state.photos.withIndex().groupBy { fmt.format(java.util.Date(it.value.captureTimeMs)) }
    }
    // Scrubber shares the timeline handle. The grid keys photos by raw linkId (see items() below),
    // so wrap each cloud photo as a gallery item and map back to that same key.
    val scrubberItems = remember(state.photos) { state.photos.map { GalleryItem.CloudOnly(it) } }
    val scrubberTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp

    Box(modifier = Modifier.fillMaxSize().background(appColors.bg0)) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.isLoading && state.photos.isNotEmpty(),
            onRefresh = { viewModel.refresh() },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
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
                eu.akoos.photos.presentation.albums.components.AlbumHeroHeader(
                    coverModel = coverUrl,
                    title = state.albumName.ifBlank { albumName },
                    photoCountText = countLabel,
                    // Rename is owner-only.
                    canRename = !state.isSharedWithMe,
                    onRenameClick = { showRenameDialog = true },
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
                                // Cap at 2 — the meta row shares its width with the trailing action buttons.
                                allEmails.take(2).forEach { email ->
                                    AvatarCircle(letter = email.first().uppercase(), tint = Accent, size = 24.dp)
                                }
                            }
                        }
                    },
                    titleActions = {
                            // Primary action: owner downloads the album; shared-with-me saves it into their own library.
                            val isDownloading = state.downloadState is AlbumDownloadState.Working
                            val onAction: () -> Unit = if (state.isSharedWithMe) {
                                { showSaveToLibraryConfirm = true }
                            } else {
                                { viewModel.downloadAllPhotos() }
                            }
                            val isInFlight = if (state.isSharedWithMe) state.isSavingToLibrary else isDownloading
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(PillBg, CircleShape)
                                    .border(0.5.dp, PillBorder, CircleShape)
                                    .clickable(enabled = !isInFlight, onClick = onAction),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    isInFlight && state.isSharedWithMe -> {
                                        // Progress arc once the save reports a total; indeterminate spinner before the first tick.
                                        val total = state.savingTotal
                                        val done = state.savingCopied
                                        if (total > 0) {
                                            CircularProgressIndicator(
                                                progress = { done.toFloat() / total.toFloat() },
                                                color = Accent, strokeWidth = 2.dp,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        } else {
                                            CircularProgressIndicator(
                                                color = Accent, strokeWidth = 2.dp,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                    isInFlight -> {
                                        val progress = state.downloadState as AlbumDownloadState.Working
                                        CircularProgressIndicator(
                                            progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                                            color = Accent, strokeWidth = 2.dp,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    state.isSharedWithMe -> Icon(
                                        Icons.Default.LibraryAdd,
                                        stringResource(R.string.shared_save_to_library),
                                        tint = Accent, modifier = Modifier.size(18.dp),
                                    )
                                    else -> Icon(
                                        Icons.Default.FileDownload,
                                        stringResource(R.string.albums_download_all),
                                        tint = Accent, modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            // Cancel pill — mounted only while a save-to-library copy is in flight.
                            if (state.isSavingToLibrary) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(PillBg, CircleShape)
                                        .border(0.5.dp, PillBorder, CircleShape)
                                        .clickable(onClick = { viewModel.cancelSaveToLibrary() }),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.save_to_library_action_cancel),
                                        tint = ErrorColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            // Cancel pill — mounted only while the download worker runs.
                            if (isDownloading) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(PillBg, CircleShape)
                                        .border(0.5.dp, PillBorder, CircleShape)
                                        .clickable(onClick = { viewModel.cancelDownload() }),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.cancel),
                                        tint = ErrorColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            // Share (owner) / Info (shared-with-me) — both open via showShareSheet.
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(PillBg, CircleShape)
                                    .border(0.5.dp, PillBorder, CircleShape)
                                    .clickable(onClick = { showShareSheet = true }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (state.isSharedWithMe) Icons.Default.Info else Icons.Default.Share,
                                    contentDescription = if (state.isSharedWithMe) stringResource(R.string.share_shared_with) else stringResource(R.string.albums_share_button),
                                    tint = Accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            // Overflow menu — shared-with-me only; hosts "Leave album".
                            if (state.isSharedWithMe) {
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(PillBg, CircleShape)
                                            .border(0.5.dp, PillBorder, CircleShape)
                                            .clickable(onClick = { showSharedAlbumOverflow = true }),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = stringResource(R.string.albums_more_actions),
                                            tint = FgPrimary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showSharedAlbumOverflow,
                                        onDismissRequest = { showSharedAlbumOverflow = false },
                                        modifier = Modifier.background(CardBg),
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(R.string.leave_album),
                                                    color = ErrorColor,
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.ExitToApp,
                                                    contentDescription = null,
                                                    tint = ErrorColor,
                                                )
                                            },
                                            onClick = {
                                                showSharedAlbumOverflow = false
                                                showLeaveAlbumConfirm = true
                                            },
                                        )
                                    }
                                }
                            }
                    },
                )
            }

            when {
                state.isLoading -> {
                    items(9, span = { GridItemSpan(1) }) {
                        eu.akoos.photos.presentation.common.ShimmerSquare(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 4.dp,
                        )
                    }
                }
                state.photos.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.albums_no_photos), color = FgMute, fontSize = 14.sp)
                    }
                }
                else -> photoGroups.forEach { (label, entries) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "hdr_$label") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Tri-state group selector (selecting only) — toggles every photo in the month.
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
                            // Long-press enters multi-select directly; cover/remove live in the selection dock.
                            showLongPressMenu = false,
                            onTap = {
                                if (state.isSelectionMode) viewModel.togglePhotoSelection(photo.linkId)
                                else {
                                    // Wrap as Synced where a local copy exists so the viewer offers device + cloud + both.
                                    val viewerItems = state.photos.map { p ->
                                        val uri = state.localUriByLinkId[p.linkId]
                                        if (uri != null) GalleryItem.Synced(
                                            p,
                                            eu.akoos.photos.domain.entity.LocalMediaItem(
                                                uri = uri, dateTaken = p.captureTimeMs, displayName = "",
                                                mimeType = p.mimeType, sizeBytes = 0L, bucketName = null,
                                            ),
                                        ) else GalleryItem.CloudOnly(p)
                                    }
                                    onPhotoClick(viewerItems, index)
                                }
                            },
                            onLongPress = { viewModel.togglePhotoSelection(photo.linkId) },
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

        // Fast-scroll scrubber over the photo grid — the same handle as the timeline. Albums group
        // by month, so the drag tooltip reads "MMMM yyyy".
        if (state.photos.isNotEmpty()) {
            TimelineScrubber(
                gridState = gridState,
                items = scrubberItems,
                grouping = TimelineGrouping.Month,
                topPadding = scrubberTopInset,
                bottomPadding = 24.dp,
                keyOf = { (it as? GalleryItem.CloudOnly)?.cloud?.linkId ?: "" },
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
            diameter = 36.dp,
            iconSize = 18.dp,
            background = Color(0x99000000),
            borderColor = PillBorder,
            tint = Color.White,
        )

        // Selection mode action bar — a single containing pill so the controls
        // stay readable over the hero image without washing the whole top edge.
        if (state.isSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(appColors.bg0.copy(alpha = 0.95f))
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
                    tint = appColors.fgPrimary,
                )
                // Split the selection counter by media type so users see "5 photos, 2 videos"
                // instead of an undifferentiated "7 selected" — particularly useful when about
                // to delete or download, since the action takes the same time regardless of
                // type but the storage / network cost is very different for videos.
                val selectedPhotosCount = state.photos.count {
                    it.linkId in state.selectedPhotos && !it.mimeType.startsWith("video/")
                }
                val selectedVideosCount = state.photos.count {
                    it.linkId in state.selectedPhotos && it.mimeType.startsWith("video/")
                }
                val selPhotosText = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.count_photos_plural, selectedPhotosCount, selectedPhotosCount,
                )
                val selVideosText = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.count_videos_plural, selectedVideosCount, selectedVideosCount,
                )
                val selectionLabel = when {
                    selectedPhotosCount > 0 && selectedVideosCount > 0 -> "$selPhotosText, $selVideosText"
                    selectedVideosCount > 0 -> selVideosText
                    else -> selPhotosText
                }
                Text(
                    selectionLabel,
                    color = appColors.fgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                // Share selected to other apps. Album photos live on Drive — a cloud-only one
                // downloads first (warn before that), one already on the device shares directly.
                // The ring tracks how many photos in the batch have been resolved.
                val isSharingPhotos = state.shareState is AlbumShareState.Working
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(PillBg, CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable(enabled = !isSharingPhotos) { showPhotoShareSheet = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSharingPhotos) {
                        val p = state.shareState as AlbumShareState.Working
                        CircularProgressIndicator(
                            progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                            color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(Icons.Default.Share, stringResource(R.string.share_action),
                            tint = Accent, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.size(4.dp))
                // Hide the destructive delete affordance on shared-with-me albums.
                // Even when the recipient is the inviter's editor, deletion of someone
                // else's photo from someone else's album is a permission we don't grant
                // through this surface — the action would route through the wrong share
                // and the backend rejects it anyway. Download stays available.
                if (!state.isSharedWithMe) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(PillBg, CircleShape)
                            .border(0.5.dp, PillBorder, CircleShape)
                            .clickable(enabled = !state.isDeletingPhotos) { showDeleteConfirm = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isDeletingPhotos) {
                            CircularProgressIndicator(color = ErrorColor, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Default.DeleteOutline, stringResource(R.string.gallery_delete_selected),
                                tint = ErrorColor, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // Jump-to-top pill — appears once scrolled down, and only outside selection mode so it
        // never collides with the bottom action dock. Tap eases back to the album header.
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
                diameter = 44.dp,
                iconSize = 24.dp,
                background = PillBgOpaque,
                borderColor = PillBorder,
                tint = appColors.fgPrimary,
            )
        }

        // Bottom action dock for selection — the same floating pill as the gallery's selection
        // dock, holding the secondary actions (download / set as cover / remove) so the top bar
        // stays cancel + count + share + delete. Matches the gallery's new split layout.
        if (state.isSelectionMode) {
            val isDownloadingSel = state.downloadState is AlbumDownloadState.Working
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .background(PillBgOpaque, RoundedCornerShape(999.dp))
                    .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Download selected. While the worker runs this cell becomes the cancel
                // control: a determinate ring tracks progress and a tap cancels.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable {
                            if (isDownloadingSel) viewModel.cancelDownload()
                            else viewModel.downloadSelectedPhotos()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDownloadingSel) {
                        val dl = state.downloadState as AlbumDownloadState.Working
                        CircularProgressIndicator(
                            progress = { if (dl.total > 0) dl.done.toFloat() / dl.total else 0f },
                            color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp),
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = ErrorColor, modifier = Modifier.size(12.dp),
                        )
                    } else {
                        Icon(Icons.Default.FileDownload, stringResource(R.string.gallery_download_selected),
                            tint = Accent, modifier = Modifier.size(20.dp))
                    }
                }
                // Set as cover — only with exactly one photo selected, own album only.
                if (!state.isSharedWithMe && state.selectedCount == 1) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { viewModel.setSelectedPhotoAsCover() }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PhotoLibrary, stringResource(R.string.album_set_as_cover),
                            tint = Accent, modifier = Modifier.size(20.dp))
                    }
                }
                // Remove from album — own album only.
                if (!state.isSharedWithMe) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = !state.isDeletingPhotos) { viewModel.removeSelectedPhotosFromAlbum() }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.RemoveCircleOutline, stringResource(R.string.album_remove_from_album),
                            tint = Accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Unified progress pill — one surface for save-to-library, multi-download and multi-share,
        // matching the device-folder back-up and gallery bulk actions. Offset below the selection
        // bar while selecting (download / share run there); sits at the top otherwise.
        val savingTpl = stringResource(R.string.shared_save_progress_fmt)
        val downloadingTpl = stringResource(R.string.op_downloading_fmt)
        val sharingTpl = stringResource(R.string.op_sharing_fmt)
        val dlState = state.downloadState
        val shState = state.shareState
        val opProgress = when {
            state.isSavingToLibrary && state.savingTotal > 0 ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    state.savingCopied, state.savingTotal,
                    savingTpl.format(state.savingCopied, state.savingTotal),
                )
            dlState is AlbumDownloadState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    dlState.done, dlState.total, downloadingTpl.format(dlState.done, dlState.total),
                )
            shState is AlbumShareState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    shState.done, shState.total, sharingTpl.format(shState.done, shState.total),
                )
            else -> null
        }
        eu.akoos.photos.presentation.common.OperationProgressPill(
            progress = opProgress,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = if (state.isSelectionMode) 64.dp else 8.dp),
        )

        // Delete / remove-from-album take over with a blocking drawer so a second tap can't fire
        // into a half-finished bulk action; the pill above stays for background downloads/shares.
        val opDeletingLabel = stringResource(R.string.op_deleting)
        val opRemovingLabel = stringResource(R.string.op_removing_from_album)
        val albumBusyProgress = if (state.isDeletingPhotos) {
            val label = if (state.busyOp == AlbumBusyOp.Removing) opRemovingLabel else opDeletingLabel
            eu.akoos.photos.presentation.common.OperationProgress(0, 0, label, indeterminate = true)
        } else null
        eu.akoos.photos.presentation.common.BlockingOperationSheet(albumBusyProgress)

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    // Unified share drawer for a photo selection (Send to app / Public link) — mirrors the timeline
    // so an album selection shares the same way. "Share with people" is hidden (already in an album).
    if (showPhotoShareSheet && state.selectedCount > 0) {
        PhotoShareSheet(
            sheetState = photoShareSheetState,
            canCreateLink = true,
            showPublicLink = state.selectedCount == 1,
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
                    clipboard.setText(AnnotatedString(url))
                    scope.launch { snackbarHostState.showSnackbar(linkCopiedMsg) }
                }
            },
            onRemoveLink = { viewModel.revokePublicLink() },
            onSetPassword = { password -> viewModel.setLinkPassword(password) },
        )
    }

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

    if (showDeleteConfirm && state.selectedPhotos.isNotEmpty()) {
        // Same sheet as the gallery and device folders: green-cloud (Synced) photos get the
        // device / cloud / both choice, cloud-only photos get accurate "moved to trash" copy.
        val deleteItems = remember(state.selectedPhotos, state.photos, state.localUriByLinkId) {
            val byId = state.photos.associateBy { it.linkId }
            state.selectedPhotos.mapNotNull { linkId ->
                val photo = byId[linkId] ?: return@mapNotNull null
                val uri = state.localUriByLinkId[linkId]
                if (uri != null) GalleryItem.Synced(
                    photo,
                    eu.akoos.photos.domain.entity.LocalMediaItem(
                        uri = uri, dateTaken = photo.captureTimeMs, displayName = "",
                        mimeType = photo.mimeType, sizeBytes = 0L, bucketName = null,
                    ),
                ) else GalleryItem.CloudOnly(photo)
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

    if (showRenameDialog) {
        // Pre-fill with the current album name. Empty or unchanged input is a no-op
        // (ViewModel.renameAlbum guards both cases) — dialog still dismisses cleanly.
        var newName by remember(state.albumName) { mutableStateOf(state.albumName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = appColors.cardBg,
            titleContentColor = appColors.fgPrimary,
            title = { Text(stringResource(R.string.album_rename), fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
                    keyboardActions = KeyboardActions(onDone = {
                        showRenameDialog = false
                        viewModel.renameAlbum(newName)
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = appColors.fgPrimary,
                        unfocusedTextColor = appColors.fgPrimary,
                        cursorColor = Accent,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = appColors.fgDim,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newName.trim() != state.albumName,
                    onClick = { showRenameDialog = false; viewModel.renameAlbum(newName) },
                ) {
                    Text(stringResource(R.string.album_rename_confirm), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.cancel), color = appColors.fgDim) }
            },
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
}
