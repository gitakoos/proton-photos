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

package eu.akoos.photos.presentation.search.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import eu.akoos.photos.R
import eu.akoos.photos.presentation.albums.AlbumCloudBadge
import eu.akoos.photos.presentation.albums.AlbumShareBadge
import eu.akoos.photos.presentation.albums.UnifiedAlbumCard
import eu.akoos.photos.presentation.search.SearchAlbum
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.LocalGifAutoplayCovers
import eu.akoos.photos.presentation.theme.LocalStaticImageLoader

/** Width of one card in the Albums row, narrow enough that the next card peeks in and the row reads
 *  as scrollable. */
private val AlbumRowCardWidth = 120.dp

/** A suggestion tile's width and cover edge, a person tile's, so the two header rows share one size. */
private val SuggestionTileWidth = 64.dp
private val SuggestionCoverSize = 56.dp
private val SuggestionCoverShape = RoundedCornerShape(12.dp)

/**
 * "Albums" section of the Search page's idle state: the user's own cloud albums in the Albums tab's
 * order, then the device folders, as the cards the Albums tab draws. Tapping one opens it.
 *
 * Covers stay still here whatever the cover autoplay setting says, and a cloud cover is its thumbnail
 * rather than a resolved GIF, so the row downloads nothing. Hidden entirely when [entries] is empty.
 */
@Composable
fun AlbumsRow(
    entries: List<SearchAlbum>,
    onOpen: (SearchAlbum) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val colors = AppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.gallery_tab_albums),
            color = colors.fgPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        CompositionLocalProvider(LocalGifAutoplayCovers provides false) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = { it.key }) { entry ->
                    SearchAlbumCard(entry = entry, onClick = { onOpen(entry) })
                }
            }
        }
    }
}

/** One card of [AlbumsRow]: the Albums tab's card for either kind, at a fixed width. */
@Composable
private fun SearchAlbumCard(entry: SearchAlbum, onClick: () -> Unit) {
    val cardModifier = Modifier.width(AlbumRowCardWidth)
    when (entry) {
        is SearchAlbum.Cloud -> {
            val album = entry.album
            UnifiedAlbumCard(
                coverModel = album.coverThumbnailUrl,
                title = album.name,
                metaText = pluralStringResource(R.plurals.count_items_plural, album.photoCount, album.photoCount),
                // Only the user's own albums reach this row, so a shared one is always shared by them.
                shareBadge = if (album.isShared) AlbumShareBadge.SharedByMe else AlbumShareBadge.None,
                cloudBadge = AlbumCloudBadge.Cloud,
                onClick = onClick,
                modifier = cardModifier,
            )
        }
        is SearchAlbum.Folder -> {
            val folder = entry.folder
            UnifiedAlbumCard(
                coverModel = folder.coverUri?.let(Uri::parse),
                coverIsGif = folder.coverIsGif,
                title = folder.name,
                metaText = pluralStringResource(R.plurals.count_photos_plural, folder.itemCount, folder.itemCount),
                isDeviceFolder = true,
                onClick = onClick,
                modifier = cardModifier,
            )
        }
    }
}

/**
 * The albums and device folders whose name matches the search query, for the floating header right
 * after the people row. Each tile is a person tile's size: a small still cover with the name under
 * it, and a corner glyph telling a device folder from a cloud album. Tapping one opens it.
 */
@Composable
fun AlbumSuggestionRow(
    entries: List<SearchAlbum>,
    onOpen: (SearchAlbum) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    Column(modifier = modifier.padding(top = 10.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.gallery_tab_albums),
            color = colors.fgDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        // Lazy, since a short query can match a long list and nothing else lists the rest.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries, key = { it.key }) { entry ->
                AlbumSuggestionTile(entry = entry, onClick = { onOpen(entry) })
            }
        }
    }
}

@Composable
private fun AlbumSuggestionTile(entry: SearchAlbum, onClick: () -> Unit) {
    val colors = AppColors.current
    val context = LocalContext.current
    val isFolder = entry is SearchAlbum.Folder
    val cover: Any? = when (entry) {
        is SearchAlbum.Cloud -> entry.album.coverThumbnailUrl
        is SearchAlbum.Folder -> entry.folder.coverUri?.let(Uri::parse)
    }
    // A GIF cover draws its still first frame, as every cover on this page does. A device folder's
    // content:// cover hides its extension, so the folder carries the answer.
    val coverIsGif = (entry as? SearchAlbum.Folder)?.folder?.coverIsGif == true ||
        cover?.toString()?.endsWith(".gif", ignoreCase = true) == true
    val loader = if (coverIsGif) LocalStaticImageLoader.current ?: context.imageLoader else context.imageLoader
    Column(
        modifier = Modifier
            .width(SuggestionTileWidth)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(SuggestionCoverSize)
                .clip(SuggestionCoverShape)
                .background(colors.bg2),
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    imageLoader = loader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(2.dp),
            ) {
                Icon(
                    imageVector = if (isFolder) Icons.Default.Folder else Icons.Default.Cloud,
                    contentDescription = if (isFolder) stringResource(R.string.album_card_folder_badge) else null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.height(16.dp), contentAlignment = Alignment.Center) {
            Text(
                text = entry.name,
                color = colors.fgDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
