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

package eu.akoos.photos.presentation.gallery

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.albums.AlbumCloudBadge
import eu.akoos.photos.presentation.albums.AlbumShareBadge
import eu.akoos.photos.presentation.albums.UnifiedAlbumCard
import eu.akoos.photos.presentation.albums.UnifiedAlbumRow
import eu.akoos.photos.presentation.common.AlbumMembership
import eu.akoos.photos.presentation.common.PickHeader
import eu.akoos.photos.presentation.common.rememberStripCollapse
import eu.akoos.photos.presentation.common.albumMembershipState
import eu.akoos.photos.presentation.common.selectionCloudLinkIds
import eu.akoos.photos.presentation.search.SearchFilter
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors

/**
 * The albums whose name holds [query], compared folded the way the Albums tab's own search compares
 * them, so case and accents are ignored. A blank query keeps every album. The order is kept.
 */
internal fun albumsMatchingName(albums: List<Album>, query: String): List<Album> {
    val needle = SearchFilter.fold(query.trim())
    if (needle.isEmpty()) return albums
    return albums.filter { SearchFilter.fold(it.name).contains(needle) }
}

/**
 * Picks the album the [selectedItems] go into: a full screen that slides in from the side and lists the
 * albums the way the Albums tab does, in the same layout (grid or list, same column count). The selected
 * photos stand at the top as the multi-select drawer shows them, a New album card leads the albums in the
 * same style as they, and the search is the round button at the bottom end that the gallery's dock carries:
 * it opens into a bar in the dock's place, above the keyboard. The cards scroll under the floating header
 * and dissolve into it.
 *
 * [cloudAlbums] arrives in the Albums tab's order. A tap closes the screen and then calls
 * [onCloudAlbumSelected], the back gesture or the arrow calls [onDismiss], and the New album card calls
 * [onCreateNew]; every one of them runs after the slide-out has finished.
 */
@Composable
internal fun GalleryAddToAlbumDialog(
    selectedItems: Set<GalleryItem>,
    cloudAlbums: List<Album>,
    onCreateNew: () -> Unit,
    onCloudAlbumSelected: (Album) -> Unit,
    onDismiss: () -> Unit,
    hiddenAlbumIds: Set<String> = emptySet(),
) {
    val colors = AppColors.current
    // The "already in this album" marks follow from the selection alone, so the screen resolves them
    // itself and every caller gets the indicator without wiring one up. Scoped to the open screen.
    val membershipViewModel: AddToAlbumMembershipViewModel = hiltViewModel()
    val selectionLinkIds = selectionCloudLinkIds(selectedItems)
    val albumMemberIds by remember(selectionLinkIds) {
        membershipViewModel.observeSelectionAlbumMembership(selectionLinkIds)
    }.collectAsStateWithLifecycle(initialValue = emptyMap())

    var query by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    val columns = rememberAlbumColumns()
    val listView = rememberAlbumsViewMode() == AlbumsViewMode.List
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var finished by remember { mutableStateOf(false) }
    fun closeThen(action: () -> Unit) {
        if (pending != null) return
        pending = action
        visible.targetState = false
    }
    // The action runs once the slide-out is over, so the screen underneath is not repainted mid-slide.
    LaunchedEffect(visible.currentState, visible.isIdle) {
        if (!visible.targetState && visible.isIdle && !visible.currentState && !finished) {
            finished = true
            (pending ?: onDismiss).invoke()
        }
    }

    Dialog(
        onDismissRequest = { closeThen(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInHorizontally(tween(280)) { it },
            exit = slideOutHorizontally(tween(220)) { it },
        ) {
            Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
                val shown = remember(cloudAlbums, query) { albumsMatchingName(cloudAlbums, query) }
                val selectedList = remember(selectedItems) { selectedItems.toList() }
                val gridState = rememberLazyGridState()
                val scrolled by remember {
                    derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }
                }
                val collapse = rememberStripCollapse()
                val cardOf: @Composable (Album, Modifier) -> Unit = { album, modifier ->
                    AlbumPickCard(
                        album = album,
                        listView = listView,
                        hidden = album.linkId in hiddenAlbumIds,
                        membership = albumMembershipState(
                            selectionLinkIds,
                            albumMemberIds[album.linkId].orEmpty(),
                        ),
                        onClick = { closeThen { onCloudAlbumSelected(album) } },
                        modifier = modifier,
                    )
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    // The header every picking screen shares: what is being picked for, the picked photos and a rule.
                    PickHeader(
                        title = stringResource(R.string.gallery_add_to_album),
                        scrolled = scrolled,
                        items = selectedList,
                        expanded = collapse.expanded,
                        emptyLabel = "",
                        onItemClick = {},
                        onToggleStrip = { collapse.expanded = !collapse.expanded },
                    )
                    val sidePadding = if (listView) 0.dp else 20.dp
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (listView) 1 else columns),
                        state = gridState,
                        contentPadding = PaddingValues(
                            top = 4.dp,
                            start = sidePadding,
                            end = sidePadding,
                            bottom = 110.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(if (listView) 0.dp else 20.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(collapse.connection),
                    ) {
                        if (query.isBlank()) {
                            item(key = "new_album") {
                                NewAlbumEntry(
                                    listView = listView,
                                    count = selectedItems.size,
                                    onClick = { closeThen(onCreateNew) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        if (cloudAlbums.isNotEmpty() && shown.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "no_match") {
                                Text(
                                    stringResource(R.string.albums_search_no_results),
                                    color = colors.fgMute,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(vertical = 16.dp, horizontal = if (listView) 20.dp else 0.dp),
                                )
                            }
                        }
                        items(shown, key = { it.linkId }) { album ->
                            cardOf(album, Modifier.animateItem())
                        }
                    }
                }

                // The gallery dock's search: the round button at the bottom end, which opens into a bar
                // across the bottom in its place.
                AnimatedContent(
                    targetState = searchActive,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    label = "addToAlbumSearch",
                ) { active ->
                    if (active) {
                        DockSearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            onClose = { searchActive = false },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            DockSearchButton(onClick = { searchActive = true })
                        }
                    }
                }
            }
        }
    }
}

/** The card (or row, in the list layout) that starts a new album from the selection, ahead of the existing ones. */
@Composable
private fun NewAlbumEntry(listView: Boolean, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.albums_new_album)
    val meta = pluralStringResource(R.plurals.count_photos_plural, count, count)
    if (listView) {
        UnifiedAlbumRow(
            coverModel = null,
            glyph = Icons.Default.CreateNewFolder,
            glyphTint = Accent,
            title = title,
            metaText = meta,
            onClick = onClick,
            modifier = modifier,
        )
    } else {
        UnifiedAlbumCard(
            coverModel = null,
            glyph = Icons.Default.CreateNewFolder,
            glyphTint = Accent,
            title = title,
            metaText = meta,
            onClick = onClick,
            modifier = modifier,
        )
    }
}

/** One album as the Albums tab's card, with a lock for a hidden album and the selection's membership mark. */
@Composable
private fun AlbumPickCard(
    album: Album,
    listView: Boolean,
    hidden: Boolean,
    membership: AlbumMembership,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    val shareBadge = when {
        album.isSharedWithMe -> AlbumShareBadge.SharedWithMe
        album.isShared -> AlbumShareBadge.SharedByMe
        else -> AlbumShareBadge.None
    }
    val metaText = pluralStringResource(R.plurals.count_items_plural, album.photoCount, album.photoCount)
    // A list row keeps its marks at the end of the row, a card keeps them on the cover's corners.
    val markEnd = if (listView) Alignment.CenterEnd else Alignment.TopEnd
    val markStart = if (listView) Alignment.CenterStart else Alignment.TopStart
    Box(modifier = modifier) {
        if (listView) {
            UnifiedAlbumRow(
                coverModel = album.coverThumbnailUrl,
                title = album.name,
                metaText = metaText,
                shareBadge = shareBadge,
                cloudBadge = AlbumCloudBadge.Cloud,
                onClick = onClick,
            )
        } else {
            UnifiedAlbumCard(
                coverModel = album.coverThumbnailUrl,
                title = album.name,
                metaText = metaText,
                shareBadge = shareBadge,
                cloudBadge = AlbumCloudBadge.Cloud,
                onClick = onClick,
            )
        }
        if (hidden) {
            Icon(
                Icons.Default.Lock,
                contentDescription = stringResource(R.string.timeline_filter_hidden_album),
                tint = Color.White,
                modifier = Modifier
                    .align(markStart)
                    .padding(8.dp)
                    .size(22.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(11.dp))
                    .padding(4.dp),
            )
        }
        when (membership) {
            is AlbumMembership.All -> Box(
                modifier = Modifier
                    .align(markEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .background(Accent, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_status_all_selected_in_album),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            is AlbumMembership.Some -> {
                // The visible "3 / 5" is digits only; the spoken form carries the meaning.
                val fractionCd = stringResource(
                    R.string.cd_status_some_selected_in_album,
                    membership.inAlbum, membership.total,
                )
                Box(
                    modifier = Modifier
                        .align(markEnd)
                        .padding(8.dp)
                        .background(colors.pageBg.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clearAndSetSemantics { contentDescription = fractionCd },
                ) {
                    Text(
                        stringResource(
                            R.string.gallery_album_picker_in_album_fraction,
                            membership.inAlbum, membership.total,
                        ),
                        color = Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            is AlbumMembership.None -> Unit
        }
    }
}
