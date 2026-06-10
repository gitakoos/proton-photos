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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.theme.ErrorColor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import eu.akoos.photos.domain.entity.Album
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    topPadding: Dp = 0.dp,
    gridState: LazyGridState = rememberLazyGridState(),
    onAlbumClick: (Album) -> Unit = {},
    viewModel: AlbumsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val pullRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    var albumToDelete by remember { mutableStateOf<Album?>(null) }

    // Cloud-album long-press surfaces a Rename + Delete bottom sheet. Holding the in-flight
    // Album object directly so we can read the current name + linkId without a second lookup.
    var cloudAlbumSheetFor by remember { mutableStateOf<Album?>(null) }
    var cloudAlbumRenameFor by remember { mutableStateOf<Album?>(null) }

    /** Collect an [AlbumActionResult] flow once and snackbar the outcome. */
    suspend fun handleAlbumActionFlow(
        actionFlow: Flow<AlbumActionResult>,
        doneMessage: String,
    ) {
        when (val outcome = actionFlow.first()) {
            is AlbumActionResult.Done -> snackbarHostState.showSnackbar(doneMessage)
            is AlbumActionResult.Failed -> snackbarHostState.showSnackbar(outcome.message)
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(state.createAlbumError) {
        state.createAlbumError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearCreateAlbumError()
        }
    }

    val albums = state.visibleCloudAlbums

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.refresh() },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {},
        ) {
            when {
                state.isLoading && albums.isEmpty() ->
                    // Skeleton placeholder grid — must use the SAME paddings, spacings, and
                    // header structure as the real grid below, otherwise the transition into
                    // real content reflows visibly (placeholders shift to new positions when
                    // the cards arrive).
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
                        // Render the real "New Album" row even while loading — the button
                        // isn't data-dependent and rendering the header pre-empts the layout
                        // shift you'd otherwise get when the first real entry appears.
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            NewAlbumRow(
                                isCreating = state.isCreatingAlbum,
                                onClick    = { showCreateDialog = true },
                                modifier   = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        items(6) {
                            eu.akoos.photos.presentation.common.ShimmerAlbumCard()
                        }
                    }

                albums.isEmpty() ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // "New Album" button even when empty
                        NewAlbumRow(
                            isCreating = state.isCreatingAlbum,
                            onClick    = { showCreateDialog = true },
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(stringResource(R.string.albums_empty_title), color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.albums_empty_subtitle), color = FgDim, fontSize = 14.sp)
                    }

                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
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
                        // ── "New Album" header ─────────────────────────────────
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            NewAlbumRow(
                                isCreating = state.isCreatingAlbum,
                                onClick    = { showCreateDialog = true },
                                modifier   = Modifier.padding(bottom = 4.dp),
                            )
                        }

                        items(
                            albums,
                            key = { "cloud_${it.linkId}" },
                        ) { album ->
                            CloudAlbumCard(
                                album       = album,
                                onClick     = { onAlbumClick(album) },
                                onLongClick = { cloudAlbumSheetFor = album },
                            )
                        }
                    }
            }
        }

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    // ── Delete Album confirmation dialog ──────────────────────────────────────
    // AlbumService.deleteAlbum passes deleteAlbumPhotos=0, so only the album container
    // is removed — the photos themselves stay in Proton Drive. Uses a dedicated string
    // (not the in-album multi-delete confirmation, which IS destructive) so the copy
    // accurately describes the container-only delete instead of promising to
    // "permanently delete the original".
    albumToDelete?.let { album ->
        ConfirmDialog(
            title = "\"${album.name}\"",
            message = stringResource(R.string.delete_album_container_warning),
            confirmLabel = stringResource(R.string.delete_button_permanently),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { viewModel.deleteAlbum(album.linkId); albumToDelete = null },
            onDismiss = { albumToDelete = null },
            destructive = true,
        )
    }

    // ── Create Album dialog ────────────────────────────────────────────────────
    if (showCreateDialog) {
        var albumName by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { showCreateDialog = false; albumName = "" },
            containerColor = AppColors.current.cardBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.albums_new_album), color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = albumName,
                    onValueChange = { albumName = it },
                    placeholder = { Text(stringResource(R.string.albums_create_album_hint), color = FgMute) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Accent,
                        unfocusedBorderColor = Line2,
                        focusedTextColor     = FgPrimary,
                        unfocusedTextColor   = FgPrimary,
                        cursorColor          = Accent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (albumName.trim().isNotEmpty()) {
                            viewModel.createAlbum(albumName)
                            showCreateDialog = false
                            albumName = ""
                        }
                    }),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showCreateDialog = false; albumName = "" }) {
                        Text(stringResource(R.string.cancel), color = FgDim)
                    }
                    TextButton(
                        onClick = {
                            viewModel.createAlbum(albumName)
                            showCreateDialog = false
                            albumName = ""
                        },
                        enabled = albumName.trim().isNotEmpty(),
                    ) { Text(stringResource(R.string.albums_create_album), color = Accent, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    // ── Cloud-album long-press action sheet ──────────────────────────────────
    // Rename + Delete sheet — delete maps to the existing albumToDelete confirm path,
    // rename opens the dialog below which calls renameCloudAlbum via the action-flow helper.
    cloudAlbumSheetFor?.let { album ->
        CloudAlbumActionSheet(
            album = album,
            onDismiss = { cloudAlbumSheetFor = null },
            onRename = {
                cloudAlbumSheetFor = null
                cloudAlbumRenameFor = album
            },
            onDelete = {
                cloudAlbumSheetFor = null
                albumToDelete = album
            },
        )
    }

    // ── Cloud-album rename dialog ────────────────────────────────────────────
    cloudAlbumRenameFor?.let { album ->
        var newName by remember(album.linkId) { mutableStateOf(album.name) }
        AlertDialog(
            onDismissRequest = { cloudAlbumRenameFor = null },
            containerColor = AppColors.current.cardBg,
            titleContentColor = AppColors.current.fgPrimary,
            title = { Text(stringResource(R.string.album_rename), fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val target = newName.trim()
                        cloudAlbumRenameFor = null
                        scope.launch {
                            handleAlbumActionFlow(
                                actionFlow = viewModel.renameCloudAlbum(album.linkId, album.name, target),
                                doneMessage = "Renamed to \"$target\"",
                            )
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppColors.current.fgPrimary,
                        unfocusedTextColor = AppColors.current.fgPrimary,
                        cursorColor = Accent,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = AppColors.current.fgDim,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newName.trim() != album.name,
                    onClick = {
                        val target = newName.trim()
                        cloudAlbumRenameFor = null
                        scope.launch {
                            handleAlbumActionFlow(
                                actionFlow = viewModel.renameCloudAlbum(album.linkId, album.name, target),
                                doneMessage = "Renamed to \"$target\"",
                            )
                        }
                    },
                ) { Text(stringResource(R.string.album_rename_confirm), color = Accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { cloudAlbumRenameFor = null }) {
                    Text(stringResource(R.string.cancel), color = AppColors.current.fgDim)
                }
            },
        )
    }

}

/**
 * Bottom sheet that opens on long-press of a cloud album card. Two rows: Rename + Delete.
 * Cloud rename is wired through `AlbumsViewModel.renameCloudAlbum` which round-trips through
 * `DrivePhotoRepository.renameAlbum`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudAlbumActionSheet(
    album: Album,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.cardBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "\"${album.name}\"",
                color = colors.fgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
            AlbumActionRow(
                icon = Icons.Default.Edit,
                label = stringResource(R.string.album_rename),
                tint = Accent,
                onClick = onRename,
            )
            Spacer(Modifier.height(8.dp))
            AlbumActionRow(
                icon = Icons.Default.DeleteOutline,
                label = stringResource(R.string.delete_button_permanently),
                tint = ErrorColor,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun AlbumActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PillBg)
            .border(0.5.dp, PillBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = AppColors.current.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

// ── "New Album" row ────────────────────────────────────────────────────────────

@Composable
private fun NewAlbumRow(
    isCreating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.cardBg)
            .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = !isCreating, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Accent.copy(alpha = 0.15f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (isCreating) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(Icons.Default.Add, stringResource(R.string.albums_new_album), tint = Accent, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            if (isCreating) "${stringResource(R.string.albums_create_album)}…" else stringResource(R.string.albums_new_album),
            color = if (isCreating) FgMute else Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── Album cards ────────────────────────────────────────────────────────────────

private fun shareBadgeOf(album: Album): AlbumShareBadge = when {
    album.isSharedWithMe -> AlbumShareBadge.SharedWithMe
    album.isShared       -> AlbumShareBadge.SharedByMe
    else                 -> AlbumShareBadge.None
}

@Composable
private fun CloudAlbumCard(album: Album, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    // Cloud Album entity has no per-mime-type breakdown, so we only have a total to show.
    // Using the media-neutral count_items_plural keeps "1 item" / "N items" pluralisation
    // correct without promising a photos-vs-videos split we can't compute without per-album
    // child fetches.
    UnifiedAlbumCard(
        coverModel  = album.coverThumbnailUrl,
        title       = album.name,
        metaText    = androidx.compose.ui.res.pluralStringResource(
            R.plurals.count_items_plural, album.photoCount, album.photoCount,
        ),
        shareBadge  = shareBadgeOf(album),
        cloudBadge  = AlbumCloudBadge.Cloud,
        onClick     = onClick,
        onLongClick = onLongClick,
    )
}
