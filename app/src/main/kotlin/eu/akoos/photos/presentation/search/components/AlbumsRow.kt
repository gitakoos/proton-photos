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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.albums.AlbumCloudBadge
import eu.akoos.photos.presentation.albums.AlbumShareBadge
import eu.akoos.photos.presentation.albums.UnifiedAlbumCard
import eu.akoos.photos.presentation.search.SearchAlbum
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.LocalGifAutoplayCovers

/** Width of one card in the Albums row, narrow enough that the next card peeks in and the row reads
 *  as scrollable. */
private val AlbumRowCardWidth = 120.dp

/**
 * "Albums" section of the Search page: the user's own cloud albums in the Albums tab's order, then the
 * device folders, as the cards the Albums tab draws, in a row that scrolls sideways. The idle page lists
 * them all and a typed query lists the ones whose name matches, in this same row. Tapping one opens it.
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
                shareBadge = when {
                    album.isSharedWithMe -> AlbumShareBadge.SharedWithMe
                    album.isShared -> AlbumShareBadge.SharedByMe
                    else -> AlbumShareBadge.None
                },
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
