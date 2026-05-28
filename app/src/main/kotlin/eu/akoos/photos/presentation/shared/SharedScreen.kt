package eu.akoos.photos.presentation.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.PendingInvitation
import eu.akoos.photos.presentation.gallery.SharedFilter
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
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

    val albums = state.displayedAlbums.let { list ->
        if (activeEmailFilter != null) list.filter { it.sharedByEmail == activeEmailFilter } else list
    }
    val pullRefreshState = rememberPullToRefreshState()
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0)
    ) {
        SnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.refresh() },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {},
        ) {
            val showPendingInvitations = filter == SharedFilter.SharedWithMe && state.pendingInvitations.isNotEmpty()

            when {
                state.isLoading && albums.isEmpty() && !showPendingInvitations ->
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

                albums.isEmpty() && !showPendingInvitations ->
                    EmptySharedState(
                        filter = filter,
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
                    }
            }
        }
    }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Accent.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        inv.inviterEmail.first().uppercase(),
                        color = Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        inv.inviterEmail,
                        color = FgPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.shared_invited_to_album),
                        color = FgMute,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = { onDecline(inv.invitationId) }) {
                    Text(stringResource(R.string.shared_decline), color = ErrorColor, fontSize = 13.sp)
                }
                Spacer(Modifier.size(4.dp))
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Accent)
                        .clickable { onAccept(inv.invitationId) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.shared_accept), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ── Empty state ────────────────────────────────────────────────────────────────

@Composable
private fun EmptySharedState(
    filter: SharedFilter,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.People,
                contentDescription = null,
                tint = FgMute,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (filter == SharedFilter.SharedWithMe)
                    stringResource(R.string.shared_empty_with_me_title)
                else
                    stringResource(R.string.shared_empty_by_me_title),
                color = FgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (filter == SharedFilter.SharedWithMe)
                    stringResource(R.string.shared_empty_with_me_body)
                else
                    stringResource(R.string.shared_empty_by_me_body),
                color = FgDim,
                fontSize = 14.sp,
            )
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
