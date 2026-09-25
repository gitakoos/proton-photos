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

package eu.akoos.photos.presentation.albums

import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.presentation.gallery.AlbumDisplayFilter

/**
 * The one definition of taking an album or a device folder off the Albums grid, and of what the grid
 * lays out for each view filter.
 *
 * The lightest way to put an album away: only the grid reads it. The album or folder stays exactly
 * as it was, its files stay where they are, backup and the timeline carry on, and every picker and
 * search still finds it. A hidden album or folder is off the lists this rule is handed before it
 * runs, so the two stored sets never meet.
 *
 * Both kinds share one stored set, told apart by a prefix on each entry: [ALBUM_PREFIX] and the cloud
 * album's linkId, or [FOLDER_PREFIX] and the device folder's bucket display name, the identity every
 * per-folder preference uses.
 */
object AlbumsGridExclusion {

    const val ALBUM_PREFIX = "album:"
    const val FOLDER_PREFIX = "folder:"

    /** What the grid lays out after the Collection card: the cloud albums, then the device folders. */
    data class Content(
        val cloudAlbums: List<Album>,
        val deviceFolders: List<DeviceFolder>,
    ) {
        val isEmpty: Boolean get() = cloudAlbums.isEmpty() && deviceFolders.isEmpty()
    }

    /** True while the cloud album [linkId] is off the grid. */
    fun isAlbumExcluded(excluded: Set<String>, linkId: String): Boolean =
        linkId.isNotBlank() && (ALBUM_PREFIX + linkId) in excluded

    /** True while the device folder [bucketName] is off the grid. */
    fun isFolderExcluded(excluded: Set<String>, bucketName: String): Boolean =
        bucketName.isNotEmpty() && (FOLDER_PREFIX + bucketName) in excluded

    /**
     * [excluded] with the cloud album [linkId] taken off the grid, or put back when [exclude] is
     * false. Every other entry carries through untouched, and an album with no id yet leaves the set
     * as it stands rather than storing an entry that would match nothing and never clear.
     */
    fun withAlbum(excluded: Set<String>, linkId: String, exclude: Boolean): Set<String> =
        if (linkId.isBlank()) excluded else updated(excluded, ALBUM_PREFIX + linkId, exclude)

    /** [withAlbum] for the device folder [bucketName]. */
    fun withFolder(excluded: Set<String>, bucketName: String, exclude: Boolean): Set<String> =
        if (bucketName.isEmpty()) excluded else updated(excluded, FOLDER_PREFIX + bucketName, exclude)

    private fun updated(excluded: Set<String>, entry: String, exclude: Boolean): Set<String> =
        if (exclude) excluded + entry else excluded - entry

    /**
     * The cloud cards [filter] lays out from [albums], in the order given: the views that show cloud
     * albums leave the excluded ones out, and the Excluded view shows nothing else. Arranging seeds
     * from this too, so it only ever moves cards the grid is showing.
     */
    fun cloudCards(filter: AlbumDisplayFilter, albums: List<Album>, excluded: Set<String>): List<Album> =
        when (filter) {
            AlbumDisplayFilter.All, AlbumDisplayFilter.Cloud ->
                albums.filterNot { isAlbumExcluded(excluded, it.linkId) }
            AlbumDisplayFilter.Local -> emptyList()
            AlbumDisplayFilter.Excluded -> albums.filter { isAlbumExcluded(excluded, it.linkId) }
        }

    /** The device-folder cards [filter] lays out from [folders], by the same rule as [cloudCards]. */
    fun folderCards(
        filter: AlbumDisplayFilter,
        folders: List<DeviceFolder>,
        excluded: Set<String>,
    ): List<DeviceFolder> = when (filter) {
        AlbumDisplayFilter.All, AlbumDisplayFilter.Local ->
            folders.filterNot { isFolderExcluded(excluded, it.name) }
        AlbumDisplayFilter.Cloud -> emptyList()
        AlbumDisplayFilter.Excluded -> folders.filter { isFolderExcluded(excluded, it.name) }
    }

    /** Everything [filter] lays out, the two kinds together. */
    fun gridContent(
        filter: AlbumDisplayFilter,
        albums: List<Album>,
        folders: List<DeviceFolder>,
        excluded: Set<String>,
    ): Content = Content(cloudCards(filter, albums, excluded), folderCards(filter, folders, excluded))
}
