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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalAlbum
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBorder

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LocalAlbumDetailScreen(
    localAlbum: LocalAlbum,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    onBack: () -> Unit,
    /** When the user opened a Merged album card (local bucket + matching Drive album), this
     *  is the cloud album's linkId so [LocalAlbumDetailViewModel] can pull in CloudOnly photos
     *  too. Null for purely local albums — preserves the legacy "local-only" behavior. */
    cloudAlbumLinkId: String? = null,
    viewModel: LocalAlbumDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(localAlbum.name, cloudAlbumLinkId) {
        viewModel.loadAlbum(localAlbum.name, cloudAlbumLinkId)
    }

    val galleryItems by viewModel.items.collectAsState()
    val selectedUris by viewModel.selectedUris.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val pullRefreshState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()

    // Surface the remove-from-album result so the user knows what happened — bucket members
    // can't be removed and we want to tell them why, not silently no-op.
    LaunchedEffect(Unit) {
        viewModel.removeMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val displayItems = galleryItems.ifEmpty {
        localAlbum.items.map { GalleryItem.LocalOnly(it) }
    }

    val coverUri = localAlbum.items.firstOrNull()?.uri
    val isSelectionMode = selectedUris.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0),
    ) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                start = 2.dp,
                end = 2.dp,
                bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Hero header — full width, scrolls with content
            item(span = { GridItemSpan(maxLineSpan) }) {
                LocalAlbumHeroHeader(
                    coverUri = coverUri,
                    albumName = localAlbum.name,
                    photoCount = displayItems.size,
                )
            }

            if (displayItems.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No photos in this album", color = FgMute, fontSize = 14.sp)
                    }
                }
            } else {
                itemsIndexed(
                    items = displayItems,
                    key = { _, item ->
                        when (item) {
                            is GalleryItem.LocalOnly -> item.local.uri
                            is GalleryItem.Synced   -> item.local.uri
                            is GalleryItem.CloudOnly -> item.cloud.linkId
                        }
                    },
                ) { index, item ->
                    // Lazy-decrypt: when a CloudOnly item lacks a thumbnailUrl (sync persisted
                    // metadata-only on this row), queue an on-demand decrypt while the cell is
                    // on-screen. The DAO update + Flow re-emit lands the new URL into the item
                    // and recomposition flips the cell from placeholder to AsyncImage.
                    val pendingCloudLinkId: String? = when (item) {
                        is GalleryItem.CloudOnly -> if (item.cloud.thumbnailUrl == null) item.cloud.linkId else null
                        is GalleryItem.Synced    -> if (item.cloud.thumbnailUrl == null) item.cloud.linkId else null
                        is GalleryItem.LocalOnly -> null
                    }
                    if (pendingCloudLinkId != null) {
                        androidx.compose.runtime.DisposableEffect(pendingCloudLinkId) {
                            viewModel.requestThumbnailDecrypt(pendingCloudLinkId)
                            onDispose { viewModel.cancelThumbnailDecrypt(pendingCloudLinkId) }
                        }
                    }

                    val imageUri: Uri? = when (item) {
                        is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
                        is GalleryItem.Synced   -> Uri.parse(item.local.uri)
                        is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl?.let { Uri.parse(it) }
                    }
                    val itemUri = when (item) {
                        is GalleryItem.LocalOnly -> item.local.uri
                        is GalleryItem.Synced -> item.local.uri
                        is GalleryItem.CloudOnly -> null
                    }
                    val isSelected = itemUri != null && itemUri in selectedUris

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(if (isSelected) 8.dp else 4.dp))
                            .background(Bg2)
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) {
                                        if (itemUri != null) viewModel.toggleSelection(itemUri)
                                    } else onPhotoClick(displayItems, index)
                                },
                                onLongClick = {
                                    if (itemUri != null) viewModel.toggleSelection(itemUri)
                                },
                            )
                            .then(
                                if (isSelected) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp))
                                else Modifier,
                            ),
                    ) {
                        if (imageUri != null) {
                            AsyncImage(
                                model = imageUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            // CloudOnly placeholder while the on-demand thumbnail decrypt is
                            // in flight. Bg2 background from the parent Box; centered low-opacity
                            // photo icon as the visual cue.
                            Icon(
                                Icons.Default.Photo,
                                contentDescription = null,
                                tint = FgDim.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(26.dp),
                            )
                        }
                        // Per-cell cloud state badge. Mirrors PhotoCell in GalleryScreen so a
                        // merged album view tells the user what's where at a glance:
                        //   Synced    → green cloud (file is here AND on Drive)
                        //   CloudOnly → white cloud (only in Drive, not on this device)
                        //   LocalOnly → no badge (just on the device, not backed up)
                        if (!isSelectionMode) {
                            when (item) {
                                is GalleryItem.Synced -> Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(5.dp)
                                        .size(20.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Cloud,
                                        contentDescription = "Backed up, also on device",
                                        tint = Color(0xFF30D158),
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                                is GalleryItem.CloudOnly -> Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(5.dp)
                                        .size(20.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Cloud,
                                        contentDescription = "Only in Drive",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                                is GalleryItem.LocalOnly -> { /* no badge */ }
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
            }
        }
        }

        // Fixed back button — floats over hero image. In selection mode it cancels selection.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 10.dp)
                .size(36.dp)
                .background(Color(0x99000000), CircleShape)
                .border(0.5.dp, PillBorder, CircleShape)
                .clickable {
                    if (isSelectionMode) viewModel.clearSelection() else onBack()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = if (isSelectionMode) stringResource(R.string.gallery_cancel_selection) else stringResource(R.string.close),
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }

        // Selection-mode action bar — mirrors AlbumDetailScreen layout (back, count, remove).
        if (isSelectionMode) {
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
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "Cancel",
                        tint = Color.White, modifier = Modifier.size(18.dp),
                    )
                }
                val selectedCount = selectedUris.size
                val countText = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.count_photos_plural, selectedCount, selectedCount,
                )
                Text(
                    countText,
                    color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0x99000000), CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable { viewModel.removeSelectedFromAlbum() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.RemoveCircleOutline,
                        contentDescription = stringResource(R.string.album_remove_from_album),
                        tint = Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun LocalAlbumHeroHeader(
    coverUri: String?,
    albumName: String,
    photoCount: Int,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .background(Bg2),
        ) {
            if (coverUri != null) {
                AsyncImage(
                    model = Uri.parse(coverUri),
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
            Text(albumName, color = FgPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("$photoCount photos", color = FgMute, fontSize = 14.sp)
        }

        HorizontalDivider(color = PillBorder, thickness = 0.5.dp)
    }
}
