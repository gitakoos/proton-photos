package me.proton.photos.presentation.albums

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import me.proton.photos.R
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.domain.entity.ShareInvitation
import me.proton.photos.domain.entity.ShareMember
import me.proton.photos.presentation.theme.Accent
import me.proton.photos.presentation.theme.AppColors
import me.proton.photos.presentation.theme.Bg0
import me.proton.photos.presentation.theme.Bg2
import me.proton.photos.presentation.theme.ErrorColor
import me.proton.photos.presentation.theme.FgDim
import me.proton.photos.presentation.theme.FgMute
import me.proton.photos.presentation.theme.FgPrimary
import me.proton.photos.presentation.theme.Line2
import me.proton.photos.presentation.theme.PillBg
import me.proton.photos.presentation.theme.PillBorder
import me.proton.photos.presentation.theme.StatusSynced

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

    Box(modifier = Modifier.fillMaxSize().background(appColors.bg0)) {
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
                AlbumHeroHeader(
                    coverUrl = coverUrl,
                    albumName = state.albumName.ifBlank { albumName },
                    photoCount = headerPhotoCount,
                    videoCount = headerVideoCount,
                    isLoading = state.isLoading,
                    invitations = state.invitations,
                    members = state.members,
                    isSharedWithMe = state.isSharedWithMe,
                    sharedByEmail = state.sharedByEmail,
                    downloadState = state.downloadState,
                    onShareClick = { showShareSheet = true },
                    onDownloadAll = { viewModel.downloadAllPhotos() },
                    // Rename is owner-only — receivers of a shared album can't change its name.
                    onRenameClick = if (state.isSharedWithMe) null else { -> showRenameDialog = true },
                    downloadedOnDeviceCount = state.localUriByLinkId.size,
                )
            }

            when {
                state.isLoading -> {
                    // Photo-grid skeleton — 9 tiles matching the 3-col layout below.
                    items(9, span = { GridItemSpan(1) }) {
                        me.proton.photos.presentation.common.ShimmerSquare(
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
                        onTap = {
                            if (state.isSelectionMode) viewModel.togglePhotoSelection(photo.linkId)
                            else onPhotoClick(state.photos, index)
                        },
                        onLongPress = { viewModel.togglePhotoSelection(photo.linkId) },
                    )
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

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
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
                isTogglingPublicLink = state.isTogglingPublicLink,
                publicShareUrl = state.publicShareUrl,
                ownerEmail = state.ownerEmail,
                invitations = state.invitations,
                members = state.members,
                isLoadingInvitations = state.isLoadingInvitations,
                onDismiss = { scope.launch { shareSheetState.hide() }.invokeOnCompletion { showShareSheet = false } },
                onCopyLink = { viewModel.createShareLink() },
                onInviteUser = { email -> viewModel.inviteUser(email) },
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
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = appColors.cardBg,
            titleContentColor = appColors.fgPrimary,
            title = {
                Text(
                    androidx.compose.ui.res.pluralStringResource(R.plurals.delete_title_plural, n, n),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = { Text(stringResource(R.string.delete_also_cloud_warning), color = appColors.fgDim, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; viewModel.deleteSelectedPhotos() }) {
                    Text(stringResource(R.string.delete_button_permanently), color = ErrorColor, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel), color = appColors.fgDim) }
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
}

// ── Hero header ────────────────────────────────────────────────────────────────

@Composable
private fun AlbumHeroHeader(
    coverUrl: String?,
    albumName: String,
    photoCount: Int,
    /** Number of videos in this album — header subtitle splits "X photos, Y videos" when both > 0. */
    videoCount: Int,
    isLoading: Boolean,
    invitations: List<ShareInvitation>,
    members: List<ShareMember>,
    isSharedWithMe: Boolean,
    sharedByEmail: String?,
    downloadState: AlbumDownloadState,
    onShareClick: () -> Unit,
    onDownloadAll: () -> Unit,
    /** Null when the user can't rename this album (shared-with-me) — hides the pencil icon. */
    onRenameClick: (() -> Unit)? = null,
    downloadedOnDeviceCount: Int = 0,
) {
    Column {
        // Cover image — extends to top of screen (behind status bar)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .background(Bg2),
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    albumName,
                    color = AppColors.current.fgPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (onRenameClick != null) {
                    Spacer(Modifier.size(8.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onRenameClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.album_rename),
                            tint = AppColors.current.fgDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (!isLoading) {
                // Hybrid count display — matches gallery date-headers and the multi-select counter.
                val photosText = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.count_photos_plural, photoCount, photoCount,
                )
                val videosText = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.count_videos_plural, videoCount, videoCount,
                )
                val countLabel = when {
                    photoCount > 0 && videoCount > 0 -> "$photosText, $videosText"
                    videoCount > 0 -> videosText
                    else -> photosText
                }
                Text(countLabel, color = AppColors.current.fgMute, fontSize = 14.sp)
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Sharer avatar (shared-with-me) or accepted members + pending invitees (own album)
                if (isSharedWithMe && sharedByEmail != null) {
                    AvatarCircle(letter = sharedByEmail.first().uppercase(), tint = Color(0xFFB0A0FF))
                } else {
                    val allEmails = (members.map { it.email } + invitations.map { it.email }).distinct()
                    allEmails.take(4).forEach { email ->
                        AvatarCircle(letter = email.first().uppercase(), tint = Accent)
                    }
                }

                Spacer(Modifier.weight(1f))

                // Download all icon button
                val isDownloading = downloadState is AlbumDownloadState.Working
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(PillBg, CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable(enabled = !isDownloading, onClick = onDownloadAll),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDownloading) {
                        val progress = downloadState as AlbumDownloadState.Working
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
                        .clickable(onClick = onShareClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isSharedWithMe) Icons.Default.Info else Icons.Default.Share,
                        contentDescription = if (isSharedWithMe) stringResource(R.string.share_shared_with) else stringResource(R.string.albums_share_button),
                        tint = Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        HorizontalDivider(color = PillBorder, thickness = 0.5.dp)
    }
}

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

/** Small icon-and-text pill — used in the album hero header to label cloud / shared / device state. */
@Composable
private fun AlbumStatusPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color,
) {
    Row(
        modifier = Modifier
            .background(Color(0x33FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** One row in the "People with access" list — email + status chip + revoke icon. */
@Composable
private fun AccessRow(
    email: String,
    label: String,
    labelColor: Color,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(letter = email.first().uppercase(), tint = Accent)
        Column(modifier = Modifier.weight(1f)) {
            Text(email, color = FgPrimary, fontSize = 14.sp)
            Text(label, color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Color(0x33FF3B30), CircleShape)
                .clickable(onClick = onRevoke),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PersonRemove, "Remove access",
                tint = ErrorColor, modifier = Modifier.size(15.dp))
        }
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
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val imageModel: Any? = when {
        localUri != null -> android.net.Uri.parse(localUri)
        photo.thumbnailUrl != null -> photo.thumbnailUrl
        else -> null
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(if (isSelected) 8.dp else 6.dp))
            .background(Bg2)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .then(if (isSelected) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier),
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
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
    }
}

// ── Share sheet ────────────────────────────────────────────────────────────────
//
// Redesigned (2026-05-27) to mirror the Drive web share dialog:
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareAlbumSheet(
    sheetState: androidx.compose.material3.SheetState,
    albumName: String,
    isSharing: Boolean,
    isTogglingPublicLink: Boolean,
    publicShareUrl: String?,
    ownerEmail: String,
    invitations: List<ShareInvitation>,
    members: List<ShareMember>,
    isLoadingInvitations: Boolean,
    onDismiss: () -> Unit,
    onCopyLink: () -> Unit,
    onInviteUser: (String) -> Unit,
    onStopSharing: () -> Unit,
    onRevokeInvitation: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onCreatePublicLink: () -> Unit,
    onDisablePublicLink: () -> Unit,
    onChangeMemberPermission: (memberId: String, permissions: Int) -> Unit,
) {
    var inviteEmail by remember { mutableStateOf("") }
    // Permission attached to NEW invites — the row-level dropdown is mirrored by the
    // top-level "can view / can edit" selector that gates the invite text input. We
    // start at 6 (editor) to match the existing inviteToAlbum() default in the data layer.
    var newInvitePermissions by remember { mutableStateOf(6) }
    val appColors = AppColors.current
    val isShareActive = publicShareUrl != null
    val hasActiveShares = isShareActive || members.isNotEmpty() || invitations.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                        .clickable(onClick = onDismiss),
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

            // ── Section 1: People invite ─────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Email input wrapped in the pill background.
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
                                stringResource(R.string.share_add_people_hint),
                                color = FgMute, fontSize = 14.sp,
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = {
                            if (inviteEmail.isNotBlank()) {
                                onInviteUser(inviteEmail.trim())
                                inviteEmail = ""
                            }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = FgPrimary,
                            unfocusedTextColor = FgPrimary,
                            cursorColor = Accent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                // Permission dropdown — applies to new invites; mirrors the per-row chip.
                PermissionDropdown(
                    currentPermissions = newInvitePermissions,
                    onSelect = { newInvitePermissions = it },
                )
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_role_can_view)) },
                onClick = { expanded = false; onSelect(4) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_role_can_edit)) },
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
