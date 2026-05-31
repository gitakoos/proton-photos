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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import eu.akoos.photos.domain.entity.CloudPhoto
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
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PillBg
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
    onPhotoClick: (List<CloudPhoto>, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(albumLinkId) { viewModel.load(albumLinkId, albumName, shareId, sharedByEmail, volumeId) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val shareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Back-button intercept in selection mode: cancel selection instead of popping back
    // to the albums grid. Without this, the user accidentally exits the album every
    // time they reach for the system back button to dismiss the multi-select bar.
    androidx.activity.compose.BackHandler(enabled = state.isSelectionMode) {
        viewModel.clearSelection()
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Refresh invitations + members each time the sheet opens — the redesigned sheet shows
    // both groups inline, so stale data right after a state mutation (revoke / remove) would
    // confuse the user. No-op when the album has no shareId yet (sheet just shows the owner).
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

    // ── Invite-batch summary snackbar ─────────────────────────────────────────────
    // Pops once when the Drive-web-style multi-chip share dialog finishes its batch.
    // We craft the message inline because the failure list needs per-email detail —
    // a single string resource can't cover the variable-length failures gracefully.
    val inviteBatchSuccessFmt   = stringResource(R.string.share_invite_summary_success)
    val inviteBatchPartialFmt   = stringResource(R.string.share_invite_summary_partial)
    val inviteBatchAllFailedFmt = stringResource(R.string.share_invite_summary_all_failed)
    LaunchedEffect(state.inviteBatchResult) {
        val r = state.inviteBatchResult ?: return@LaunchedEffect
        val msg = when {
            r.failures.isEmpty() -> inviteBatchSuccessFmt.format(r.successCount)
            r.successCount == 0 -> {
                val emails = r.failures.joinToString(", ") { it.first }
                "${inviteBatchAllFailedFmt.format(r.failures.size)}: $emails"
            }
            else -> {
                val emails = r.failures.joinToString(", ") { it.first }
                "${inviteBatchPartialFmt.format(r.successCount, r.failures.size)} ($emails)"
            }
        }
        snackbarHostState.showSnackbar(msg)
        viewModel.clearInviteBatchResult()
    }

    val coverUpdatedMsg = stringResource(R.string.album_cover_updated)
    LaunchedEffect(state.coverUpdatedTick) {
        // tick == 0 is the initial state; only act on real bumps coming from the VM.
        if (state.coverUpdatedTick > 0) {
            snackbarHostState.showSnackbar(coverUpdatedMsg)
        }
    }

    val enqueuedMsg = stringResource(R.string.album_download_enqueued)
    LaunchedEffect(state.downloadState) {
        when (val ds = state.downloadState) {
            // The worker took over — show a one-shot snackbar so the user knows the
            // download is running in the background and check the notification.
            is AlbumDownloadState.Enqueued -> {
                snackbarHostState.showSnackbar(enqueuedMsg)
                viewModel.resetDownloadState()
            }
            // Legacy code-path: kept for any callers that still produce a Done state
            // synchronously (e.g. a future "tiny album, no notification" optimisation).
            is AlbumDownloadState.Done -> {
                val parts = buildList {
                    if (ds.downloaded > 0) add("downloaded ${ds.downloaded}")
                    if (ds.skipped > 0) add("${ds.skipped} already on device")
                    if (ds.failed > 0) add("${ds.failed} failed")
                }
                val msg = when {
                    parts.isEmpty() -> "Nothing to download"
                    ds.downloaded == 0 && ds.failed == 0 ->
                        "All ${ds.skipped} photo${if (ds.skipped != 1) "s" else ""} already on device"
                    else ->
                        parts.joinToString(", ").replaceFirstChar { it.uppercase() }
                }
                snackbarHostState.showSnackbar(msg)
                viewModel.resetDownloadState()
            }
            else -> Unit
        }
    }

    val coverUrl = coverThumbnailUrl ?: state.photos.firstOrNull()?.thumbnailUrl
    val appColors = AppColors.current
    val pullRefreshState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()

    Box(modifier = Modifier.fillMaxSize().background(appColors.bg0)) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.isLoading && state.photos.isNotEmpty(),
            onRefresh = { viewModel.refresh() },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Hero header — full width, scrolls with content
            item(span = { GridItemSpan(maxLineSpan) }) {
                val headerVideoCount = state.photos.count { it.mimeType.startsWith("video/") }
                val headerPhotoCount = state.photos.size - headerVideoCount
                val photosTextRes = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.count_photos_plural, headerPhotoCount, headerPhotoCount,
                )
                val videosTextRes = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.count_videos_plural, headerVideoCount, headerVideoCount,
                )
                val countLabel = if (state.isLoading) "" else when {
                    headerPhotoCount > 0 && headerVideoCount > 0 -> "$photosTextRes, $videosTextRes"
                    headerVideoCount > 0 -> videosTextRes
                    else -> photosTextRes
                }
                eu.akoos.photos.presentation.albums.components.AlbumHeroHeader(
                    coverModel = coverUrl,
                    title = state.albumName.ifBlank { albumName },
                    photoCountText = countLabel,
                    // Rename is owner-only — receivers of a shared album can't change its name.
                    canRename = !state.isSharedWithMe,
                    onRenameClick = { showRenameDialog = true },
                    extraSlot = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // Sharer avatar (shared-with-me) or accepted members + pending invitees (own album)
                            val sharerEmail = state.sharedByEmail
                            if (state.isSharedWithMe && sharerEmail != null) {
                                AvatarCircle(letter = sharerEmail.first().uppercase(), tint = Color(0xFFB0A0FF))
                            } else {
                                val allEmails = (state.members.map { it.email } + state.invitations.map { it.email }).distinct()
                                allEmails.take(4).forEach { email ->
                                    AvatarCircle(letter = email.first().uppercase(), tint = Accent)
                                }
                            }

                            Spacer(Modifier.weight(1f))

                            // Download all icon button
                            val isDownloading = state.downloadState is AlbumDownloadState.Working
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(PillBg, CircleShape)
                                    .border(0.5.dp, PillBorder, CircleShape)
                                    .clickable(enabled = !isDownloading, onClick = { viewModel.downloadAllPhotos() }),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isDownloading) {
                                    val progress = state.downloadState as AlbumDownloadState.Working
                                    CircularProgressIndicator(
                                        progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                                        color = Accent, strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Icon(Icons.Default.FileDownload, stringResource(R.string.albums_download_all), tint = Accent, modifier = Modifier.size(18.dp))
                                }
                            }

                            // Share / Info icon button
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
                        }
                    },
                )
            }

            when {
                state.isLoading -> {
                    // Photo-grid skeleton — 9 tiles matching the 3-col layout below.
                    items(9, span = { GridItemSpan(1) }) {
                        eu.akoos.photos.presentation.common.ShimmerSquare(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 4.dp,
                        )
                    }
                }
                state.photos.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("No photos in this album", color = FgMute, fontSize = 14.sp)
                    }
                }
                else -> itemsIndexed(state.photos, key = { _, p -> p.linkId }) { index, photo ->
                    PhotoCell(
                        photo = photo,
                        localUri = state.localUriByLinkId[photo.linkId],
                        isSelected = photo.linkId in state.selectedPhotos,
                        isSelectionMode = state.isSelectionMode,
                        // Long-press opens a per-cell context menu unless the user is already
                        // in multi-select (then it falls through to togglePhotoSelection, so
                        // a "long-press to deselect" still feels natural). Shared-with-me
                        // albums skip the context-menu fast-path because none of its actions
                        // are valid for an album the user doesn't own.
                        showLongPressMenu = !state.isSharedWithMe && !state.isSelectionMode,
                        onTap = {
                            if (state.isSelectionMode) viewModel.togglePhotoSelection(photo.linkId)
                            else onPhotoClick(state.photos, index)
                        },
                        onLongPress = { viewModel.togglePhotoSelection(photo.linkId) },
                        onSetAsCover = { viewModel.setPhotoAsCover(photo.linkId) },
                        onRemoveFromAlbum = {
                            // Reuse the existing multi-select bulk-remove path with a
                            // single-photo selection so the VM's error / progress handling
                            // is shared between the menu and the pill button.
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

        // Fixed back button — floats over the hero image
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 10.dp)
                .size(36.dp)
                .background(Color(0x99000000), CircleShape)
                .border(0.5.dp, PillBorder, CircleShape)
                .clickable { if (state.isSelectionMode) viewModel.clearSelection() else onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = if (state.isSelectionMode) stringResource(R.string.gallery_cancel_selection) else stringResource(R.string.close),
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }

        // Selection mode action bar
        if (state.isSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Bg0.copy(alpha = 0.95f))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0x99000000), CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable { viewModel.clearSelection() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel",
                        tint = Color.White, modifier = Modifier.size(18.dp))
                }
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
                // Download selected
                val isDownloading = state.downloadState is AlbumDownloadState.Working
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0x99000000), CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable(enabled = !isDownloading) { viewModel.downloadSelectedPhotos() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(Icons.Default.FileDownload, "Download selected",
                            tint = Accent, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.size(4.dp))
                // Set-as-cover (only when exactly 1 photo selected, own album only). Hidden
                // for shared-with-me because the receiver can't change the owner's cover.
                if (!state.isSharedWithMe && state.selectedCount == 1) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0x99000000), CircleShape)
                            .border(0.5.dp, PillBorder, CircleShape)
                            .clickable { viewModel.setSelectedPhotoAsCover() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PhotoLibrary, stringResource(R.string.album_set_as_cover),
                            tint = Accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.size(4.dp))
                }
                // Remove-from-album (own album only — receiver of a shared album can't change it)
                if (!state.isSharedWithMe) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0x99000000), CircleShape)
                            .border(0.5.dp, PillBorder, CircleShape)
                            .clickable(enabled = !state.isDeletingPhotos) { viewModel.removeSelectedPhotosFromAlbum() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.RemoveCircleOutline, stringResource(R.string.album_remove_from_album),
                            tint = Accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.size(4.dp))
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0x99000000), CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable(enabled = !state.isDeletingPhotos) { showDeleteConfirm = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isDeletingPhotos) {
                        CircularProgressIndicator(color = ErrorColor, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(Icons.Default.DeleteOutline, "Delete selected",
                            tint = ErrorColor, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
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
                invitations = state.invitations,
                members = state.members,
                isLoadingInvitations = state.isLoadingInvitations,
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
            )
        }
    }

    if (showDeleteConfirm) {
        val n = state.selectedCount
        ConfirmDialog(
            title = androidx.compose.ui.res.pluralStringResource(R.plurals.delete_title_plural, n, n),
            message = stringResource(R.string.delete_also_cloud_warning),
            confirmLabel = stringResource(R.string.delete_button_permanently),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showDeleteConfirm = false; viewModel.deleteSelectedPhotos() },
            onDismiss = { showDeleteConfirm = false },
            destructive = true,
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
}

// ── Hero header ────────────────────────────────────────────────────────────────

@Composable
private fun AvatarCircle(letter: String, tint: Color) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(tint.copy(alpha = 0.2f), CircleShape)
            .border(1.5.dp, Bg0, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Photo cell ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    photo: CloudPhoto,
    localUri: String? = null,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    /** When true, long-press pops the per-cell context menu (Set as cover / Remove from album).
     *  When false, long-press falls through to [onLongPress] (multi-select toggle). The screen
     *  flips this off for shared-with-me albums and while already in multi-select mode. */
    showLongPressMenu: Boolean = false,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSetAsCover: () -> Unit = {},
    onRemoveFromAlbum: () -> Unit = {},
    onRequestThumbnail: (linkId: String) -> Unit = {},
    onCancelThumbnail: (linkId: String) -> Unit = {},
) {
    val imageModel: Any? = when {
        localUri != null -> android.net.Uri.parse(localUri)
        photo.thumbnailUrl != null -> photo.thumbnailUrl
        else -> null
    }

    // Lazy-decrypt: a missing thumbnailUrl on a cloud photo means the sync pass populated
    // the row in metadata-only mode. Enqueue an on-demand decrypt while the cell is
    // visible; cancel when it scrolls away. If a local file URI is in hand we render
    // from MediaStore instead, but we still queue the decrypt so the cloud-only view of
    // the same album (e.g. on a sibling device) prefetches it for the user.
    if (photo.thumbnailUrl == null && localUri == null) {
        androidx.compose.runtime.DisposableEffect(photo.linkId) {
            onRequestThumbnail(photo.linkId)
            onDispose { onCancelThumbnail(photo.linkId) }
        }
    }

    // Per-cell long-press menu state. The menu anchors to the cell because [DropdownMenu]
    // positions relative to its parent — placing it inside the cell's Box means it pops
    // right where the user pressed instead of in a fixed screen corner.
    var menuExpanded by remember { mutableStateOf(false) }
    val appColors = AppColors.current

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(if (isSelected) 8.dp else 6.dp))
            .background(Bg2)
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    if (showLongPressMenu) menuExpanded = true
                    else onLongPress()
                },
            )
            .then(if (isSelected) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier),
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Placeholder while the on-demand decrypt is in progress. Bg2-filled Box from
            // the parent already provides the dark tile; a low-opacity centered photo icon
            // is the visual cue that this slot is loading.
            Icon(
                Icons.Default.Photo,
                contentDescription = null,
                tint = FgDim.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(26.dp),
            )
        }

        // Cloud / synced status — every photo in an album lives on Drive; tint indicates
        // whether the device also has the file locally (green = synced, white = cloud-only).
        // Matches the main gallery grid so the user reads the same icon language everywhere.
        if (!isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    tint = if (localUri != null) StatusSynced else Color.White,
                    modifier = Modifier.size(11.dp),
                )
            }
        }

        // Video play indicator — mirrors the main gallery grid so video items in albums
        // are visually distinct from photos. CloudPhoto carries the mimeType from the
        // Drive listing, so this works for cloud-only and synced items alike.
        if (photo.mimeType.startsWith("video/") && !isSelectionMode) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        if (isSelectionMode) {
            Box(modifier = Modifier.padding(4.dp).size(20.dp).align(Alignment.TopStart)) {
                if (isSelected) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(0.3f), CircleShape)
                            .border(1.5.dp, Color.White.copy(0.8f), CircleShape),
                    )
                }
            }
        }

        // Per-cell long-press context menu. Anchored to the cell Box so the popup appears
        // over the photo the user pressed. Only mounted when the cell is in the
        // long-press-menu mode — saves recompositions when the screen is in multi-select.
        if (showLongPressMenu) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = appColors.cardBg,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, appColors.pillBorder),
            ) {
                // Select is the first menu item so long-press has a discoverable path
                // into multi-select.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gallery_action_select), color = appColors.fgPrimary) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onLongPress()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.album_set_as_cover), color = appColors.fgPrimary) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onSetAsCover()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.album_remove_from_album), color = appColors.fgPrimary) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.RemoveCircleOutline,
                            contentDescription = null,
                            tint = ErrorColor,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onRemoveFromAlbum()
                    },
                )
            }
        }
    }
}

// ── Share sheet ────────────────────────────────────────────────────────────────
//
// Mirrors the Drive web share dialog:
//   ┌────────────────────────────────────────────────────────────────────┐
//   │ Share <albumName>                                              [×] │
//   ├────────────────────────────────────────────────────────────────────┤
//   │  ┌──────────────────────────────────────────┐  ┌────────────────┐  │
//   │  │ Add people or groups to share…           │  │ can edit  ▾    │  │
//   │  └──────────────────────────────────────────┘  └────────────────┘  │
//   │                                                                    │
//   │  Who has access                                                    │
//   │  ────────────────────────────────────────────────────────────────  │
//   │   ⓞ owner@…                                       [owner]          │
//   │   ⓡ friend@…                          [can edit ▾]    [×]          │
//   │   ⓟ pending@…                          [pending]      [×]          │
//   ├────────────────────────────────────────────────────────────────────┤
//   │  Public link                                       [○ Not active]  │
//   │  Anyone on the Internet with the link              [can view ▾]    │
//   │  ┌────────────────────────────┐  ┌────────────────┐                │
//   │  │ https://drive.proton.me/…  │  │   Copy link    │                │
//   │  └────────────────────────────┘  └────────────────┘                │
//   ├────────────────────────────────────────────────────────────────────┤
//   │              Stop sharing  (red, footer)                            │
//   └────────────────────────────────────────────────────────────────────┘
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ShareAlbumSheet(
    sheetState: androidx.compose.material3.SheetState,
    albumName: String,
    isSharing: Boolean,
    isInvitingBatch: Boolean,
    isTogglingPublicLink: Boolean,
    publicShareUrl: String?,
    ownerEmail: String,
    invitations: List<ShareInvitation>,
    members: List<ShareMember>,
    isLoadingInvitations: Boolean,
    onDismiss: () -> Unit,
    onCopyLink: () -> Unit,
    onInviteUsers: (emails: List<String>, message: String, permissions: Int) -> Unit,
    onStopSharing: () -> Unit,
    onRevokeInvitation: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onCreatePublicLink: () -> Unit,
    onDisablePublicLink: () -> Unit,
    onChangeMemberPermission: (memberId: String, permissions: Int) -> Unit,
) {
    // ── Local state for the Drive-web-style invite popup ────────────────────────
    var inviteEmail by remember { mutableStateOf("") }
    // Pending chips — the user adds emails here BEFORE tapping Share.
    val pendingEmails = remember { mutableStateListOf<String>() }
    // Optional message attached to the invite batch. Not currently sent to the backend
    // (the repo signature doesn't take a message yet), but typed in the UI for parity
    // with Drive web and so we can plumb it through later without redesigning.
    var inviteMessage by remember { mutableStateOf("") }
    // Permission attached to NEW invites — the row-level dropdown is mirrored by the
    // top-level "can view / can edit" selector that gates the invite text input. We
    // start at 6 (editor) to match the existing inviteToAlbum() default in the data layer.
    var newInvitePermissions by remember { mutableStateOf(6) }

    // Validation surface for the email field — shows the inline error message when the
    // user has typed something AND it doesn't parse as a valid address. Empty input is
    // treated as "no chip yet" (not an error).
    val trimmedEmail = inviteEmail.trim()
    val isValidEmail = trimmedEmail.isNotEmpty() &&
        android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()
    val isDuplicate = isValidEmail && trimmedEmail in pendingEmails
    val canAdd = isValidEmail && !isDuplicate && !isInvitingBatch

    // Reset the input when the sheet is dismissed so the next open is fresh. We can't
    // tap into the SheetState dismiss reliably from inside ModalBottomSheet, so we key
    // on the chip list being empty + a focus-free state and reset in the dismiss callback.
    val resetInputs = {
        inviteEmail = ""
        pendingEmails.clear()
        inviteMessage = ""
    }

    val appColors = AppColors.current
    val isShareActive = publicShareUrl != null
    val hasActiveShares = isShareActive || members.isNotEmpty() || invitations.isNotEmpty()
    val hasPendingInvites = pendingEmails.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = { resetInputs(); onDismiss() },
        sheetState = sheetState,
        containerColor = appColors.cardBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // ── Header — "Share <albumName>" + × close ────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.share_sheet_title, albumName),
                    color = FgPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable {
                            resetInputs()
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.share_close),
                        tint = FgMute,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // ── Section 1: Drive-web-style invite popup ──────────────────────
            //
            // Two-step flow:
            //   1. Type email → tap "Add" → email becomes a chip in the "Will invite"
            //      row above. Repeat for multiple recipients.
            //   2. Tap the bottom "Share" button to fire one inviteToAlbum() per chip.
            //
            // "Will invite" chip strip — only rendered while there are pending chips
            // so the share dialog doesn't open with an empty stripe.
            if (hasPendingInvites) {
                Text(
                    stringResource(R.string.share_invite_will_invite),
                    color = FgDim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pendingEmails.forEach { email ->
                        androidx.compose.material3.AssistChip(
                            onClick = { /* no-op; close icon handles removal */ },
                            // Disabled while the batch is in flight so the user can't
                            // half-modify the list mid-invite.
                            enabled = !isInvitingBatch,
                            label = { Text(email, fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.PersonAdd, null,
                                    tint = Accent, modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.share_remove_member),
                                    tint = FgMute,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .clickable(enabled = !isInvitingBatch) {
                                            pendingEmails.remove(email)
                                        },
                                )
                            },
                            colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                containerColor = PillBg,
                                labelColor = FgPrimary,
                            ),
                            border = androidx.compose.material3.AssistChipDefaults.assistChipBorder(
                                enabled = !isInvitingBatch,
                                borderColor = PillBorder,
                                borderWidth = 0.5.dp,
                            ),
                        )
                    }
                }
            }

            // Email input row — pill input + Add button + permission selector.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(PillBg, RoundedCornerShape(12.dp))
                        .border(0.5.dp, PillBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.PersonAdd, null, tint = Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = inviteEmail,
                        onValueChange = { inviteEmail = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.share_invite_email_hint),
                                color = FgMute, fontSize = 14.sp,
                            )
                        },
                        singleLine = true,
                        enabled = !isInvitingBatch,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            // Done over Send — the IME action only adds to the chip list, not
                            // submits the whole invite, so Done is more intention-matching and
                            // avoids losing the input on invalid emails.
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            if (canAdd) {
                                pendingEmails.add(trimmedEmail)
                                inviteEmail = ""
                            }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedTextColor = FgPrimary,
                            unfocusedTextColor = FgPrimary,
                            disabledTextColor = FgDim,
                            cursorColor = Accent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    // "Add" button shows up only when the input is non-empty so the row
                    // doesn't look cluttered while the user hasn't started typing yet.
                    if (inviteEmail.isNotBlank()) {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                if (canAdd) {
                                    pendingEmails.add(trimmedEmail)
                                    inviteEmail = ""
                                }
                            },
                            enabled = canAdd,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Text(
                                stringResource(R.string.share_invite_add),
                                color = if (canAdd) Accent else FgMute,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                // Permission dropdown — applies to all chips queued in the current batch.
                PermissionDropdown(
                    currentPermissions = newInvitePermissions,
                    onSelect = { newInvitePermissions = it },
                )
            }

            // Inline validation under the email input — shows AFTER the user types
            // something that isn't a valid email, OR when the typed email duplicates
            // a chip already in the list. Hidden in the empty-input state so the
            // dialog opens cleanly.
            if (inviteEmail.isNotBlank() && !isValidEmail) {
                Text(
                    stringResource(R.string.share_invite_email_invalid),
                    color = ErrorColor, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 14.dp, top = 6.dp),
                )
            } else if (isDuplicate) {
                Text(
                    stringResource(R.string.share_invite_email_duplicate),
                    color = ErrorColor, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 14.dp, top = 6.dp),
                )
            }

            // Optional message field — only revealed once at least one chip exists,
            // so the entry-point UI stays small for the common "just type one email
            // and share" case. Matches Drive web which also hides the message field
            // until a recipient is added.
            if (hasPendingInvites) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = inviteMessage,
                    onValueChange = { inviteMessage = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.share_invite_message_hint),
                            color = FgMute, fontSize = 13.sp,
                        )
                    },
                    enabled = !isInvitingBatch,
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PillBorder,
                        unfocusedBorderColor = PillBorder,
                        focusedContainerColor = PillBg,
                        unfocusedContainerColor = PillBg,
                        focusedTextColor = FgPrimary,
                        unfocusedTextColor = FgPrimary,
                        cursorColor = Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                // Primary Share + secondary Cancel — fixed-position footer for the
                // invite popup, distinct from the "Stop sharing" footer at the bottom
                // of the sheet (which acts on the whole album share, not just the batch).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            inviteEmail = ""
                            pendingEmails.clear()
                            inviteMessage = ""
                        },
                        enabled = !isInvitingBatch,
                    ) {
                        Text(
                            stringResource(R.string.share_invite_cancel),
                            color = FgDim, fontSize = 14.sp,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                if (isInvitingBatch) Accent.copy(alpha = 0.6f) else Accent,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable(enabled = !isInvitingBatch && pendingEmails.isNotEmpty()) {
                                val snapshot = pendingEmails.toList()
                                val msg = inviteMessage.trim()
                                onInviteUsers(snapshot, msg, newInvitePermissions)
                                // Optimistically clear the popup so the sheet returns
                                // to its idle state. The VM holds the result for the
                                // top-level LaunchedEffect to surface as a snackbar.
                                pendingEmails.clear()
                                inviteEmail = ""
                                inviteMessage = ""
                            }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isInvitingBatch) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = Color.White, strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    stringResource(R.string.share_invite_send),
                                    color = Color.White, fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.share_invite_send),
                                color = Color.White, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // ── "Who has access" subheader + member list ─────────────────────
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.share_who_has_access),
                color = FgDim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Owner row — always rendered first when we know the owner email. Has no remove
            // button and an immutable "owner" chip per the Drive web design.
            if (ownerEmail.isNotEmpty()) {
                OwnerRow(email = ownerEmail)
            }

            if (isLoadingInvitations) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                }
            }

            // Accepted members — clickable permission chip + remove × icon.
            members.forEach { member ->
                MemberRow(
                    email = member.email,
                    permissions = member.permissions,
                    onChangePermission = { perm -> onChangeMemberPermission(member.memberId, perm) },
                    onRemove = { onRemoveMember(member.memberId) },
                )
            }

            // Pending invites — amber chip + revoke × icon (no permission change while pending).
            invitations.forEach { inv ->
                PendingInvitationRow(
                    email = inv.email,
                    onRevoke = { onRevokeInvitation(inv.invitationId) },
                )
            }

            // ── Section 2: Public link card ──────────────────────────────────
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PillBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, PillBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                // Toggle row — "Public link" label + Active/Not active + Switch.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.share_public_link),
                        color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(
                            if (isShareActive) R.string.share_public_link_active
                            else R.string.share_public_link_inactive,
                        ),
                        color = if (isShareActive) Accent else FgMute,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    if (isTogglingPublicLink) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Switch(
                            checked = isShareActive,
                            onCheckedChange = { newState ->
                                if (newState) onCreatePublicLink() else onDisablePublicLink()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Accent,
                                uncheckedThumbColor = FgMute,
                                uncheckedTrackColor = Line2,
                                uncheckedBorderColor = Line2,
                            ),
                        )
                    }
                }

                // Audience line + read-only URL + Copy button, only shown when active.
                if (isShareActive) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.share_public_link_audience),
                            color = FgMute, fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        // Audience permission dropdown — view-only / view+edit. Stub for now
                        // because we don't have an API to change it on an existing public URL.
                        var audienceMenuOpen by remember { mutableStateOf(false) }
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { audienceMenuOpen = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.share_role_can_view),
                                    color = FgPrimary, fontSize = 12.sp,
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown, null,
                                    tint = FgMute, modifier = Modifier.size(16.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = audienceMenuOpen,
                                onDismissRequest = { audienceMenuOpen = false },
                                shape = RoundedCornerShape(16.dp),
                                containerColor = appColors.cardBg,
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, appColors.pillBorder),
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_role_can_view)) },
                                    onClick = { audienceMenuOpen = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Read-only URL preview — single line, truncates on overflow.
                        Text(
                            publicShareUrl ?: "",
                            color = FgPrimary, fontSize = 13.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .background(appColors.cardBg, RoundedCornerShape(8.dp))
                                .border(0.5.dp, Line2, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                        Box(
                            modifier = Modifier
                                .background(Accent, RoundedCornerShape(8.dp))
                                .clickable(enabled = !isSharing, onClick = onCopyLink)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            if (isSharing) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            } else {
                                Text(
                                    stringResource(R.string.share_copy_link_button),
                                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }

            // ── Footer — "Stop sharing" (revokes URL + all member access) ────
            if (hasActiveShares) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSharing, onClick = onStopSharing)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSharing) {
                        CircularProgressIndicator(color = ErrorColor, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text(
                            stringResource(R.string.share_stop_sharing),
                            color = ErrorColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Share-sheet sub-rows ───────────────────────────────────────────────────────

/** Owner row — non-removable, displays the "owner" chip. */
@Composable
private fun OwnerRow(email: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(letter = email.first().uppercase(), tint = Accent)
        Text(email, color = FgPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .background(Line2, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(stringResource(R.string.share_role_owner), color = FgDim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Accepted member row — clickable permission chip + remove × icon. */
@Composable
private fun MemberRow(
    email: String,
    permissions: Int,
    onChangePermission: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarCircle(letter = email.first().uppercase(), tint = Accent)
        Text(email, color = FgPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        PermissionDropdown(
            currentPermissions = permissions,
            onSelect = onChangePermission,
            compact = true,
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, stringResource(R.string.share_remove_member), tint = FgMute, modifier = Modifier.size(16.dp))
        }
    }
}

/** Pending invitation row — amber chip + revoke × icon. */
@Composable
private fun PendingInvitationRow(email: String, onRevoke: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarCircle(letter = email.first().uppercase(), tint = Color(0xFFFCD34D))
        Text(email, color = FgPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .background(Color(0x33FCD34D), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(stringResource(R.string.share_role_pending), color = Color(0xFFFCD34D), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onRevoke),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, stringResource(R.string.share_remove_member), tint = FgMute, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Drive-permission dropdown — "can view" (4) / "can edit" (6). Used both at the top of
 * the sheet (gating new invites) and inline on each member row. Pass `compact = true`
 * for the inline form which uses smaller padding and font.
 */
@Composable
private fun PermissionDropdown(
    currentPermissions: Int,
    onSelect: (Int) -> Unit,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val labelRes = if (currentPermissions >= 6) R.string.share_role_can_edit else R.string.share_role_can_view
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (compact) Color.Transparent else PillBg)
                .then(if (compact) Modifier else Modifier.border(0.5.dp, PillBorder, RoundedCornerShape(8.dp)))
                .clickable { expanded = true }
                .padding(
                    horizontal = if (compact) 6.dp else 12.dp,
                    vertical = if (compact) 4.dp else 10.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(labelRes),
                color = FgPrimary,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = if (compact) FontWeight.Normal else FontWeight.Medium,
            )
            Icon(Icons.Default.ArrowDropDown, null, tint = FgMute,
                modifier = Modifier.size(if (compact) 16.dp else 18.dp))
        }
        val menuColors = AppColors.current
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = menuColors.cardBg,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, menuColors.pillBorder),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_role_can_view), color = menuColors.fgPrimary) },
                onClick = { expanded = false; onSelect(4) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_role_can_edit), color = menuColors.fgPrimary) },
                onClick = { expanded = false; onSelect(6) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedWithMeInfoSheet(
    sheetState: androidx.compose.material3.SheetState,
    sharedByEmail: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.current.cardBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("Shared with you", color = FgPrimary, fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PillBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, PillBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Default.Info, null, tint = Accent, modifier = Modifier.size(20.dp))
                Column {
                    Text("Shared by", color = FgMute, fontSize = 12.sp)
                    Text(sharedByEmail, color = FgPrimary, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
