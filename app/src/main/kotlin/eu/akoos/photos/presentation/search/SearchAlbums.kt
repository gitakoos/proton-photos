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

package eu.akoos.photos.presentation.search

import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.usecase.AlbumSortMode
import eu.akoos.photos.domain.usecase.sortAlbums
import eu.akoos.photos.presentation.albums.AlbumsGridExclusion
import eu.akoos.photos.presentation.albums.DeviceFolder
import eu.akoos.photos.presentation.albums.DeviceFolderCards

/** One card on the Search screen's album surfaces: one of the user's own cloud albums, or a device folder. */
sealed interface SearchAlbum {
    val name: String

    /** List key, unique across both kinds, since an album and a folder may share a name. */
    val key: String

    data class Cloud(val album: Album) : SearchAlbum {
        override val name: String get() = album.name
        override val key: String get() = "cloud_${album.linkId}"
    }

    data class Folder(val folder: DeviceFolder) : SearchAlbum {
        override val name: String get() = folder.name
        override val key: String get() = "devfolder_${folder.name}"
    }
}

/**
 * What the Search screen shows of the albums: the idle page's Albums row, and the albums and folders
 * a typed query finds. Both draw on the cards the Albums tab lays out, in its order: the user's own
 * cloud albums, then the device folders. Albums shared with the user have their own tab and stay off
 * the idle row, but a typed name finds them after the rest.
 */
internal object SearchAlbums {

    /**
     * Everything the two surfaces are drawn from. [cloudAlbums] and [deviceFolders] arrive as the album
     * cache and the device listing hold them, [cloudAlbums] empty for a guest; the rest are the stored
     * album settings the Albums tab reads too.
     */
    data class Source(
        val cloudAlbums: List<Album> = emptyList(),
        val deviceFolders: List<DeviceFolder> = emptyList(),
        /** The albums other people shared with the user, which only a typed name reaches. */
        val sharedAlbums: List<Album> = emptyList(),
        val hiddenAlbumIds: Set<String> = emptySet(),
        /** The albums and folders taken off the grid, as [AlbumsGridExclusion] stores them. */
        val excluded: Set<String> = emptySet(),
        val sortMode: AlbumSortMode = AlbumSortMode.Default,
        val customOrder: List<String> = emptyList(),
    )

    /**
     * The idle page's Albums row: the Albums grid's cards in its order, the cloud albums then the
     * device folders. What the grid leaves out stays out: a hidden album, an album or folder taken off
     * the grid, and a folder with no photo left on the device. The Collection card is a shortcut rather
     * than an album, so it has no place here.
     */
    fun row(source: Source): List<SearchAlbum> = candidates(source).filterNot { entry ->
        when (entry) {
            is SearchAlbum.Cloud -> AlbumsGridExclusion.isAlbumExcluded(source.excluded, entry.album.linkId)
            is SearchAlbum.Folder -> AlbumsGridExclusion.isFolderExcluded(source.excluded, entry.folder.name)
        }
    }

    /**
     * The albums and folders whose name holds [query], compared folded the way the Albums tab's own
     * search compares them, so case and accents are ignored and "nyar" finds "Nyár". An exact name
     * leads, then the names starting with the query, then the ones merely containing it, each group in
     * the Albums tab's order.
     *
     * An album or folder taken off the grid is still found, since that exclusion is about the grid
     * alone. A hidden album never is. An album shared with the user is found too, after the user's own
     * albums and the folders. A blank query finds nothing.
     */
    fun matches(source: Source, query: String): List<SearchAlbum> {
        val needle = SearchFilter.fold(query.trim())
        if (needle.isEmpty()) return emptyList()
        val exact = ArrayList<SearchAlbum>()
        val startsWith = ArrayList<SearchAlbum>()
        val contains = ArrayList<SearchAlbum>()
        for (entry in candidates(source) + sharedWithMe(source)) {
            val name = SearchFilter.fold(entry.name.trim())
            when {
                name == needle -> exact += entry
                name.startsWith(needle) -> startsWith += entry
                name.contains(needle) -> contains += entry
            }
        }
        return exact + startsWith + contains
    }

    /** The albums shared with the user, newest activity first as the cache lists them, less any hidden. */
    private fun sharedWithMe(source: Source): List<SearchAlbum> =
        source.sharedAlbums.filter { it.linkId !in source.hiddenAlbumIds }.map { SearchAlbum.Cloud(it) }

    /** Every card either surface may show, in the Albums tab's order: the user's own cloud albums sorted
     *  as the grid sorts them, less the hidden ones, then the folders that still hold a photo. */
    private fun candidates(source: Source): List<SearchAlbum> {
        val own = source.cloudAlbums.filter { !it.isSharedWithMe && it.linkId !in source.hiddenAlbumIds }
        return sortAlbums(own, source.sortMode, source.customOrder).map { SearchAlbum.Cloud(it) } +
            DeviceFolderCards.visible(source.deviceFolders).map { SearchAlbum.Folder(it) }
    }
}
