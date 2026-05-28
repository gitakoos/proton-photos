package eu.akoos.photos.presentation.albums

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import eu.akoos.photos.presentation.theme.ErrorColor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.LocalAlbum
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

// Unified entry for mixed grid display
private sealed interface AlbumEntry {
    data class Cloud(val album: Album) : AlbumEntry
    data class Local(val album: LocalAlbum) : AlbumEntry
    /** Local folder that has a matching Drive album (same name) — shows as one card. */
    data class Merged(val local: LocalAlbum, val cloud: Album) : AlbumEntry
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    topPadding: Dp = 0.dp,
    gridState: LazyGridState = rememberLazyGridState(),
    onAlbumClick: (Album) -> Unit = {},
    onLocalAlbumClick: (LocalAlbum) -> Unit = {},
    /** Tap on an [AlbumEntry.Merged] card — passes BOTH the local bucket and the matching
     *  cloud album so NavGraph can route to a detail screen that shows the union of both
     *  sources. Falls back to local-only nav if the host hasn't wired this. */
    onMergedAlbumClick: (LocalAlbum, Album) -> Unit = { local, _ -> onLocalAlbumClick(local) },
    viewModel: AlbumsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val pullRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    var albumToDelete by remember { mutableStateOf<Album?>(null) }

    // Local-album long-press flow state ────────────────────────────────────────
    // [localAlbumSheetFor] drives the bottom sheet that lets the user choose Rename or Delete
    // (only opens for virtual-only albums — bucket-derived folders snackbar instead).
    // [localAlbumRenameFor] / [localAlbumDeleteFor] drive the confirm dialogs that follow.
    // [pendingCloudDelete] drives the "also delete on cloud?" dialog after a virtual-only
    // local delete succeeded and a matching cloud album was found.
    var localAlbumSheetFor by remember { mutableStateOf<LocalAlbum?>(null) }
    var localAlbumRenameFor by remember { mutableStateOf<LocalAlbum?>(null) }
    var localAlbumDeleteFor by remember { mutableStateOf<LocalAlbum?>(null) }
    var pendingCloudDelete by remember {
        mutableStateOf<LocalAlbumActionResult.DoneWithCloudPending?>(null)
    }

    /**
     * Collect a [LocalAlbumActionResult] flow once and snackbar the outcome. Virtual-only
     * paths never need consent (we only mutate DataStore), so the only async branch is
     * DoneWithCloudPending which stages a follow-up dialog for the cloud-side mirror.
     */
    suspend fun handleLocalAlbumActionFlow(
        actionFlow: Flow<LocalAlbumActionResult>,
        doneMessage: String,
    ) {
        when (val outcome = actionFlow.first()) {
            is LocalAlbumActionResult.Done -> snackbarHostState.showSnackbar(doneMessage)
            is LocalAlbumActionResult.Failed -> snackbarHostState.showSnackbar(outcome.message)
            is LocalAlbumActionResult.DoneWithCloudPending -> {
                snackbarHostState.showSnackbar(doneMessage)
                pendingCloudDelete = outcome
            }
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

    // Build unified list: merge local + cloud by name, then orphans
    val entries: List<AlbumEntry> = buildList {
        val cloudByName = state.visibleCloudAlbums.associateBy { it.name.lowercase() }
        val matchedCloudNames = mutableSetOf<String>()
        for (local in state.visibleLocalAlbums) {
            val cloud = cloudByName[local.name.lowercase()]
            if (cloud != null) {
                add(AlbumEntry.Merged(local, cloud))
                matchedCloudNames += cloud.name.lowercase()
            } else {
                add(AlbumEntry.Local(local))
            }
        }
        // Cloud albums without a matching local folder
        for (cloud in state.visibleCloudAlbums) {
            if (cloud.name.lowercase() !in matchedCloudNames) {
                add(AlbumEntry.Cloud(cloud))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.refresh() },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {},
        ) {
            when {
                state.isLoading && entries.isEmpty() ->
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

                entries.isEmpty() ->
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
                            entries,
                            key = {
                                when (it) {
                                    is AlbumEntry.Cloud   -> "cloud_${it.album.linkId}"
                                    is AlbumEntry.Local   -> "local_${it.album.name}"
                                    is AlbumEntry.Merged  -> "merged_${it.cloud.linkId}"
                                }
                            },
                        ) { entry ->
                            when (entry) {
                                is AlbumEntry.Cloud -> CloudAlbumCard(
                                    album       = entry.album,
                                    onClick     = { onAlbumClick(entry.album) },
                                    onLongClick = { albumToDelete = entry.album },
                                )
                                is AlbumEntry.Local -> {
                                    val bucketGuardMsg = stringResource(
                                        R.string.bucket_album_readonly_msg, "\"${entry.album.name}\"",
                                    )
                                    LocalAlbumCard(
                                        album       = entry.album,
                                        onClick     = { onLocalAlbumClick(entry.album) },
                                        onLongClick = {
                                            // Bucket-derived "albums" (Camera, Screenshots, …) are device
                                            // folders we don't own — refuse rename/delete and tell the user
                                            // how to organize their photos instead.
                                            if (entry.album.isVirtualOnly) {
                                                localAlbumSheetFor = entry.album
                                            } else {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(bucketGuardMsg)
                                                }
                                            }
                                        },
                                    )
                                }
                                is AlbumEntry.Merged -> MergedAlbumCard(
                                    local       = entry.local,
                                    cloud       = entry.cloud,
                                    onClick     = {
                                        // Open a merged detail view that shows BOTH the local
                                        // bucket photos AND the matching cloud album photos
                                        // deduped, so neither side is hidden from the user
                                        // when a local bucket and a cloud album share a name.
                                        onMergedAlbumClick(entry.local, entry.cloud)
                                    },
                                    onLongClick = { albumToDelete = entry.cloud },
                                )
                            }
                        }
                    }
            }
        }

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    // ── Delete Album confirmation dialog ──────────────────────────────────────
    albumToDelete?.let { album ->
        AlertDialog(
            onDismissRequest = { albumToDelete = null },
            containerColor   = AppColors.current.cardBg,
            titleContentColor = AppColors.current.fgPrimary,
            title = { Text("\"${album.name}\"", fontWeight = FontWeight.SemiBold) },
            // AlbumService.deleteAlbum passes deleteAlbumPhotos=0, so only the album container
            // is removed — the photos themselves stay in Proton Drive. Uses a dedicated string
            // (not the in-album multi-delete confirmation, which IS destructive) so the copy
            // accurately describes the container-only delete instead of promising to
            // "permanently delete the original".
            text  = { Text(stringResource(R.string.delete_album_container_warning), color = AppColors.current.fgDim, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAlbum(album.linkId); albumToDelete = null }) {
                    Text(stringResource(R.string.delete_button_permanently), color = ErrorColor, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { albumToDelete = null }) { Text(stringResource(R.string.cancel), color = AppColors.current.fgDim) }
            },
        )
    }

    // ── Create Album dialog ────────────────────────────────────────────────────
    if (showCreateDialog) {
        var albumName by remember { mutableStateOf("") }
        var createCloud by remember { mutableStateOf(true) } // default = cloud (existing behavior)
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

                // Cloud vs Local toggle. Dedicated strings (NOT the filter "Backed up" label)
                // so the dialog reads as "where to create" rather than "filter by state".
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlbumKindChip(
                        label = stringResource(R.string.albums_new_kind_cloud),
                        selected = createCloud,
                        onClick = { createCloud = true },
                    )
                    AlbumKindChip(
                        label = stringResource(R.string.albums_new_kind_local),
                        selected = !createCloud,
                        onClick = { createCloud = false },
                    )
                }

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
                            if (createCloud) viewModel.createAlbum(albumName)
                            else viewModel.createLocalAlbum(albumName)
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
                            if (createCloud) viewModel.createAlbum(albumName)
                            else viewModel.createLocalAlbum(albumName)
                            showCreateDialog = false
                            albumName = ""
                        },
                        enabled = albumName.trim().isNotEmpty(),
                    ) { Text(stringResource(R.string.albums_create_album), color = Accent, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    // ── Local-album long-press action sheet ──────────────────────────────────
    localAlbumSheetFor?.let { album ->
        LocalAlbumActionSheet(
            album = album,
            onDismiss = { localAlbumSheetFor = null },
            onRename  = {
                localAlbumSheetFor = null
                localAlbumRenameFor = album
            },
            onDelete  = {
                localAlbumSheetFor = null
                localAlbumDeleteFor = album
            },
        )
    }

    // ── Rename local-album dialog ────────────────────────────────────────────
    localAlbumRenameFor?.let { album ->
        var newName by remember(album.name) { mutableStateOf(album.name) }
        AlertDialog(
            onDismissRequest = { localAlbumRenameFor = null },
            containerColor    = AppColors.current.cardBg,
            titleContentColor = AppColors.current.fgPrimary,
            title = { Text(stringResource(R.string.album_rename), fontWeight = FontWeight.SemiBold) },
            text  = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val target = newName
                        localAlbumRenameFor = null
                        scope.launch {
                            handleLocalAlbumActionFlow(
                                actionFlow = viewModel.renameLocalAlbum(album.name, target),
                                doneMessage = "Renamed to \"${eu.akoos.photos.util.ProtonPhotosStorage.sanitize(target)}\"",
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
                        val target = newName
                        localAlbumRenameFor = null
                        scope.launch {
                            handleLocalAlbumActionFlow(
                                actionFlow = viewModel.renameLocalAlbum(album.name, target),
                                doneMessage = "Renamed to \"${eu.akoos.photos.util.ProtonPhotosStorage.sanitize(target)}\"",
                            )
                        }
                    },
                ) { Text(stringResource(R.string.album_rename_confirm), color = Accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { localAlbumRenameFor = null }) {
                    Text(stringResource(R.string.cancel), color = AppColors.current.fgDim)
                }
            },
        )
    }

    // ── Delete local-album confirmation ──────────────────────────────────────
    localAlbumDeleteFor?.let { album ->
        AlertDialog(
            onDismissRequest = { localAlbumDeleteFor = null },
            containerColor    = AppColors.current.cardBg,
            titleContentColor = AppColors.current.fgPrimary,
            title = { Text("\"${album.name}\"", fontWeight = FontWeight.SemiBold) },
            text  = {
                // Virtual-only delete: we only drop the album marker + membership entries.
                // The underlying photos stay in their original device folders.
                val msg = if (album.itemCount > 0) {
                    "This will remove the album. Your ${album.itemCount} photo" +
                        (if (album.itemCount != 1) "s" else "") +
                        " stay on your device in their original folders."
                } else stringResource(R.string.albums_no_photos)
                Text(msg, color = AppColors.current.fgDim, fontSize = 13.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    localAlbumDeleteFor = null
                    scope.launch {
                        handleLocalAlbumActionFlow(
                            actionFlow = viewModel.deleteLocalAlbum(album.name),
                            doneMessage = "Deleted \"${album.name}\"",
                        )
                    }
                }) {
                    Text(stringResource(R.string.delete_button_permanently), color = ErrorColor, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { localAlbumDeleteFor = null }) {
                    Text(stringResource(R.string.cancel), color = AppColors.current.fgDim)
                }
            },
        )
    }

    // ── "Also delete on Drive?" follow-up after a virtual-only delete ───────
    pendingCloudDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                viewModel.cancelCloudDeleteForLocalAlbum()
                pendingCloudDelete = null
            },
            containerColor    = AppColors.current.cardBg,
            titleContentColor = AppColors.current.fgPrimary,
            title = { Text("Also delete on Drive?", fontWeight = FontWeight.SemiBold) },
            text  = {
                Text(
                    "A cloud album named \"${pending.albumName}\" exists too. Delete it from " +
                        "Proton Drive as well? Photos in it stay on Drive — only the album " +
                        "reference is removed.",
                    color = AppColors.current.fgDim, fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmCloudDeleteForLocalAlbum(pending.cloudLinkId)
                    pendingCloudDelete = null
                }) {
                    Text(
                        stringResource(R.string.delete_button_permanently),
                        color = ErrorColor, fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.cancelCloudDeleteForLocalAlbum()
                    pendingCloudDelete = null
                }) {
                    Text("Keep on Drive", color = AppColors.current.fgDim)
                }
            },
        )
    }
}

/** Bottom sheet that opens on long-press of a local-album card. Two rows: Rename + Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalAlbumActionSheet(
    album: LocalAlbum,
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
            if (album.itemCount > 0) {
                val countText = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.count_photos_plural, album.itemCount, album.itemCount,
                )
                Text(countText, color = colors.fgMute, fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 16.dp))
            } else {
                Spacer(Modifier.height(16.dp))
            }

            LocalAlbumActionRow(
                icon = Icons.Default.Edit,
                label = stringResource(R.string.album_rename),
                tint = Accent,
                onClick = onRename,
            )
            Spacer(Modifier.height(8.dp))
            LocalAlbumActionRow(
                icon = Icons.Default.DeleteOutline,
                label = stringResource(R.string.delete_button_permanently),
                tint = ErrorColor,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun LocalAlbumActionRow(
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

@Composable
private fun AlbumKindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = 0.22f) else colors.chipSelectedBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) Accent else FgPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
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
    // Using count_photos_plural keeps "1 photo" / "N photos" pluralisation correct without
    // promising a photos-vs-videos split we can't compute without per-album child fetches.
    UnifiedAlbumCard(
        coverModel  = album.coverThumbnailUrl,
        title       = album.name,
        metaText    = androidx.compose.ui.res.pluralStringResource(
            R.plurals.count_photos_plural, album.photoCount, album.photoCount,
        ),
        shareBadge  = shareBadgeOf(album),
        cloudBadge  = AlbumCloudBadge.Cloud,
        onClick     = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
private fun LocalAlbumCard(album: LocalAlbum, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    val backedUp = stringResource(R.string.albums_filter_backed_up)
    val photoCount = androidx.compose.ui.res.pluralStringResource(
        R.plurals.count_photos_plural, album.itemCount, album.itemCount,
    )
    val metaText = when {
        album.isFullyBackedUp -> "$backedUp · ${album.itemCount}"
        album.hasAnyBackedUp  -> "${album.backedUpCount}/${album.itemCount} · $backedUp"
        else                  -> photoCount
    }
    val cloudBadge = when {
        album.isFullyBackedUp -> AlbumCloudBadge.LocallyBackedUpFull
        album.hasAnyBackedUp  -> AlbumCloudBadge.LocallyBackedUpPart
        else                  -> AlbumCloudBadge.None
    }
    UnifiedAlbumCard(
        coverModel  = album.coverUri?.let { Uri.parse(it) },
        title       = album.name,
        metaText    = metaText,
        shareBadge  = AlbumShareBadge.None,
        cloudBadge  = cloudBadge,
        // Bucket-derived (non-virtual) albums map to real device folders the user can't safely
        // rename or delete from us — flag them so the user has a hint when they long-press and
        // get the "device folder" snackbar.
        isDeviceFolder = !album.isVirtualOnly,
        onClick     = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
private fun MergedAlbumCard(
    local: LocalAlbum,
    cloud: Album,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val coverModel: Any? = when {
        local.coverUri != null          -> Uri.parse(local.coverUri)
        cloud.coverThumbnailUrl != null -> cloud.coverThumbnailUrl
        else                            -> null
    }
    // Merged-view count must include the cloud side too — using only the local bucket
    // count (e.g. "1 photos") understated when the cloud album had many more.
    // max(local, cloud) is a tight lower bound of the true union for the common case
    // where the device's bucket is a subset of the cloud album (typical post-backup
    // state). It does overstate slightly when both sides have items the other doesn't,
    // but never understates.
    val mergedCount = maxOf(local.itemCount, cloud.photoCount)
    val metaText = when {
        local.isFullyBackedUp -> "Backed up · $mergedCount"
        local.hasAnyBackedUp  -> "${local.backedUpCount}/$mergedCount backed up"
        else                  -> "$mergedCount photos"
    }
    UnifiedAlbumCard(
        coverModel  = coverModel,
        title       = cloud.name,
        metaText    = metaText,
        shareBadge  = shareBadgeOf(cloud),
        cloudBadge  = AlbumCloudBadge.Cloud,
        onClick     = onClick,
        onLongClick = onLongClick,
    )
}
