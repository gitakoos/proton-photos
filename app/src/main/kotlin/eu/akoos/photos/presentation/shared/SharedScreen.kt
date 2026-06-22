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

package eu.akoos.photos.presentation.shared

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.PendingInvitation
import eu.akoos.photos.domain.entity.SharedPhoto
import eu.akoos.photos.presentation.common.CloudPhotoCell
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.EmptyState
import eu.akoos.photos.presentation.gallery.SharedFilter
import eu.akoos.photos.presentation.viewer.ManagePublicLinkSheet
import eu.akoos.photos.presentation.viewer.PublicLinkState
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedScreen(
    topPadding: Dp = 0.dp,
    filter: SharedFilter,
    activeEmailFilter: String? = null,
    onAlbumClick: (Album) -> Unit = {},
    viewModel: SharedViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Keep ViewModel in sync with the filter from the parent
    androidx.compose.runtime.LaunchedEffect(filter) {
        viewModel.setFilter(filter)
    }

    // Re-pull the shared list on every screen resume — the user navigates back
    // here after opening a shared album in detail, and the cover thumbnail for
    // that album lands in the on-disk cache only once the album's first photo
    // gets decrypted by the lazy scheduler. Without a re-fetch, the Album row
    // here stays frozen with the cover still null even though the cached file
    // is sitting at `cacheDir/thumbnails/thumb_<coverLinkId>.jpg` waiting for
    // `resolveOfflineCoverUrl` to pick it up on the next pass.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val albums = state.displayedAlbums.let { list ->
        if (activeEmailFilter != null) list.filter { it.sharedByEmail == activeEmailFilter } else list
    }
    // Individually shared photos belong to the "Shared by me" tab only, and an email filter
    // (which only applies to shared-WITH-me senders) hides them just like it hides own albums.
    val sharedPhotos = if (filter == SharedFilter.SharedByMe && activeEmailFilter == null)
        state.sharedByMePhotos else emptyList()
    val pullRefreshState = rememberPullToRefreshState()
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // ── Manage public link sheet (a single shared photo) ─────────────────────────
    val publicLinkState by viewModel.publicLinkState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val sheetScope = rememberCoroutineScope()
    val manageSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showManageSheet by remember { mutableStateOf(false) }
    var showRevokeConfirm by remember { mutableStateOf(false) }
    val linkCopiedMsg = stringResource(R.string.share_link_copied)
    val passwordSetMsg = stringResource(R.string.share_password_set)
    val passwordRemovedMsg = stringResource(R.string.share_password_removed)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0)
    ) {
        eu.akoos.photos.presentation.common.ThemedSnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.refresh() },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {},
        ) {
            val showPendingInvitations = filter == SharedFilter.SharedWithMe && state.pendingInvitations.isNotEmpty()
            val isEmpty = albums.isEmpty() && sharedPhotos.isEmpty() && !showPendingInvitations

            when {
                state.isLoading && isEmpty ->
                    // 4 shimmer placeholders in the 2-column grid layout while loading.
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(
                            top = topPadding + 12.dp,
                            start = 20.dp,
                            end = 20.dp,
                            bottom = 120.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(6, key = { idx -> "skeleton_$idx" }) {
                            eu.akoos.photos.presentation.common.ShimmerAlbumCard()
                        }
                    }

                isEmpty ->
                    EmptyState(
                        title = if (filter == SharedFilter.SharedWithMe)
                            stringResource(R.string.shared_empty_with_me_title)
                        else
                            stringResource(R.string.shared_empty_by_me_title),
                        subtitle = if (filter == SharedFilter.SharedWithMe)
                            stringResource(R.string.shared_empty_with_me_body)
                        else
                            stringResource(R.string.shared_empty_by_me_body),
                        icon = Icons.Default.People,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = topPadding),
                    )

                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(
                            top = topPadding + 12.dp,
                            start = 20.dp,
                            end = 20.dp,
                            bottom = 120.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Pending invitations section — only in SharedWithMe tab
                        if (showPendingInvitations) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                PendingInvitationsSection(
                                    invitations = state.pendingInvitations,
                                    onAccept  = { invId -> viewModel.acceptInvitation(invId) },
                                    onDecline = { invId -> viewModel.declineInvitation(invId) },
                                )
                            }
                        }

                        items(
                            albums,
                            key = { "shared_${it.linkId}" },
                        ) { album ->
                            SharedAlbumCard(
                                album = album,
                                onClick = { onAlbumClick(album) },
                            )
                        }

                        // Individually shared photos, beneath any shared albums.
                        if (sharedPhotos.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader(
                                    text = stringResource(R.string.shared_by_me_photos_section),
                                    // Pull the header up a touch when albums precede it so the
                                    // two sections read as one list, not two stacked grids.
                                    topPadding = if (albums.isNotEmpty()) 8.dp else 0.dp,
                                )
                            }
                            items(
                                sharedPhotos,
                                key = { "sharedphoto_${it.linkId}" },
                            ) { photo ->
                                SharedPhotoCell(
                                    photo = photo,
                                    isSelectionMode = state.isSelectionMode,
                                    isSelected = photo.linkId in state.selectedPhotoIds,
                                    onClick = {
                                        if (state.isSelectionMode) {
                                            viewModel.toggleSelection(photo.linkId)
                                        } else {
                                            viewModel.openLinkManager(photo.linkId)
                                            showManageSheet = true
                                        }
                                    },
                                    onLongClick = { viewModel.toggleSelection(photo.linkId) },
                                    onRequestThumbnail = { id -> viewModel.requestThumbnail(id) },
                                    onCancelThumbnail = { id -> viewModel.cancelThumbnail(id) },
                                )
                            }
                        }
                    }
            }
        }

        // Selection top bar — overlays the grid when one or more shared-by-me photos are
        // selected. Close cancels the selection; the trailing action confirms a bulk revoke.
        if (state.isSelectionMode) {
            SharedSelectionBar(
                selectedCount = state.selectedCount,
                isRevoking = state.isRevoking,
                onCancel = { viewModel.clearSelection() },
                onStopSharing = { showRevokeConfirm = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    BackHandler(enabled = state.isSelectionMode) { viewModel.clearSelection() }

    if (showRevokeConfirm) {
        ConfirmDialog(
            title = pluralStringResource(
                R.plurals.shared_stop_sharing_confirm_title,
                state.selectedCount, state.selectedCount,
            ),
            message = stringResource(R.string.shared_stop_sharing_confirm_body),
            confirmLabel = stringResource(R.string.share_stop_sharing),
            dismissLabel = stringResource(R.string.share_invite_cancel),
            destructive = true,
            onConfirm = {
                showRevokeConfirm = false
                viewModel.revokeSelected()
            },
            onDismiss = { showRevokeConfirm = false },
        )
    }

    if (showManageSheet) {
        val mapped = when (val s = publicLinkState) {
            is SharedViewModel.PublicLinkState.None -> PublicLinkState.None
            is SharedViewModel.PublicLinkState.Loading -> PublicLinkState.Loading
            is SharedViewModel.PublicLinkState.Active ->
                PublicLinkState.Active(s.url, s.hasPassword)
            is SharedViewModel.PublicLinkState.Error ->
                PublicLinkState.Error(s.message)
        }
        ManagePublicLinkSheet(
            sheetState = manageSheetState,
            publicLinkState = mapped,
            onDismiss = {
                showManageSheet = false
                viewModel.closeLinkManager()
            },
            onCreateLink = { viewModel.createLink() },
            onCopyLink = {
                viewModel.currentPublicLinkUrl()?.let { url ->
                    clipboard.setText(AnnotatedString(url))
                    sheetScope.launch { snackbarHost.showSnackbar(linkCopiedMsg) }
                }
            },
            onRemoveLink = {
                viewModel.removeLink()
                showManageSheet = false
            },
            onSetPassword = { password ->
                viewModel.setLinkPassword(password)
                val msg = if (password.isNullOrBlank()) passwordRemovedMsg else passwordSetMsg
                sheetScope.launch { snackbarHost.showSnackbar(msg) }
            },
        )
    }
}

// ── Shared-photo grid cell + section header ─────────────────────────────────────

@Composable
private fun SharedSelectionBar(
    selectedCount: Int,
    isRevoking: Boolean,
    onCancel: () -> Unit,
    onStopSharing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Bg0.copy(alpha = 0.95f))
            .border(0.5.dp, PillBorder, RoundedCornerShape(28.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        eu.akoos.photos.presentation.common.IconBubble(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.gallery_cancel_selection),
            onClick = onCancel,
            diameter = 40.dp,
            iconSize = 20.dp,
            tint = FgPrimary,
        )
        Text(
            pluralStringResource(R.plurals.count_photos_plural, selectedCount, selectedCount),
            color = FgPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(horizontal = 12.dp),
        )
        Box(
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(PillBg)
                .border(0.5.dp, PillBorder, RoundedCornerShape(20.dp))
                .clickable(enabled = !isRevoking) { onStopSharing() }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isRevoking) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = ErrorColor,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    stringResource(R.string.share_stop_sharing),
                    color = ErrorColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, topPadding: Dp = 0.dp) {
    Text(
        text,
        color = FgPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = 2.dp),
    )
}

@Composable
private fun SharedPhotoCell(
    photo: SharedPhoto,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRequestThumbnail: (String) -> Unit,
    onCancelThumbnail: (String) -> Unit,
) {
    // Reuse the unified cloud cell so a shared photo tile renders identically to the gallery —
    // lazy thumbnail decrypt, the cloud badge, and the video play overlay all come for free.
    CloudPhotoCell(
        localUri = null,
        cloudThumbnailUrl = photo.thumbnailUrl,
        cloudLinkId = photo.linkId,
        isVideo = photo.isVideo,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        showCloudBadge = true,
        onClick = onClick,
        onLongClick = onLongClick,
        onRequestThumbnail = onRequestThumbnail,
        onCancelThumbnail = onCancelThumbnail,
    )
}

// ── Pending invitations ────────────────────────────────────────────────────────

@Composable
private fun PendingInvitationsSection(
    invitations: List<PendingInvitation>,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PillBg, RoundedCornerShape(14.dp))
            .border(0.5.dp, PillBorder, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Default.MailOutline,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.shared_pending_invitations),
                color = FgPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalDivider(color = PillBorder, thickness = 0.5.dp)

        invitations.forEachIndexed { idx, inv ->
            if (idx > 0) HorizontalDivider(color = PillBorder, thickness = 0.5.dp)
            // Stack the row vertically so a long email + invitation description never
            // collides with the action buttons; the buttons live on their own row at the
            // bottom with end alignment.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Accent.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            inv.inviterEmail.first().uppercase(),
                            color = Accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            inv.inviterEmail,
                            color = FgPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.size(2.dp))
                        Text(
                            stringResource(R.string.shared_invited_to_album),
                            color = FgMute,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Transparent)
                            .border(0.5.dp, PillBorder, RoundedCornerShape(18.dp))
                            .clickable { onDecline(inv.invitationId) }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.shared_decline),
                            color = ErrorColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Accent)
                            .clickable { onAccept(inv.invitationId) }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.shared_accept),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

// ── Album card ────────────────────────────────────────────────────────────────

@Composable
private fun SharedAlbumCard(album: Album, onClick: () -> Unit) {
    // Use the unified card so an album looks identical here, in Albums tab, and in the
    // gallery merged view. Shared-with-me gets a blue pill; my own shared albums use violet.
    val shareBadge = when {
        album.sharedByEmail != null || album.isSharedWithMe ->
            eu.akoos.photos.presentation.albums.AlbumShareBadge.SharedWithMe
        else ->
            eu.akoos.photos.presentation.albums.AlbumShareBadge.SharedByMe
    }
    val metaText = album.sharedByEmail?.let { stringResource(R.string.shared_by_email, it) }
        ?: stringResource(R.string.shared_photo_count, album.photoCount)
    eu.akoos.photos.presentation.albums.UnifiedAlbumCard(
        coverModel = album.coverThumbnailUrl,
        title      = album.name,
        metaText   = metaText,
        shareBadge = shareBadge,
        cloudBadge = eu.akoos.photos.presentation.albums.AlbumCloudBadge.Cloud,
        onClick    = onClick,
    )
}
