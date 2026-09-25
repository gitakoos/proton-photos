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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the Albums grid lays out once albums and folders can be taken off it: every normal view
 * leaves them out, the Excluded view shows them alone, and one more tap always puts a card back.
 * Also pins that the Collection card and the offset every arrange index goes through stay out of the
 * Excluded view. Plain JVM assertions on pure functions, no Compose runtime and no Android.
 */
class AlbumsGridExclusionTest {

    private fun album(linkId: String) = Album(
        linkId = linkId,
        name = "Album $linkId",
        photoCount = 1,
        coverLinkId = null,
        lastActivityTimeMs = 0L,
    )

    private fun folder(name: String) = DeviceFolder(name = name, coverUri = null, itemCount = 1)

    private val trip = album("trip")
    private val pets = album("pets")
    private val work = album("work")
    private val camera = folder("Camera")
    private val screenshots = folder("Screenshots")

    private val albums = listOf(trip, pets, work)
    private val folders = listOf(camera, screenshots)

    // Pets and Screenshots are off the grid.
    private val excluded = AlbumsGridExclusion.withFolder(
        AlbumsGridExclusion.withAlbum(emptySet(), pets.linkId, exclude = true),
        screenshots.name,
        exclude = true,
    )

    private fun content(filter: AlbumDisplayFilter, stored: Set<String> = excluded) =
        AlbumsGridExclusion.gridContent(filter, albums, folders, stored)

    @Test
    fun `entries carry a prefix per kind`() {
        assertEquals(setOf("album:pets", "folder:Screenshots"), excluded)
    }

    @Test
    fun `an excluded album and folder leave every normal view`() {
        assertEquals(listOf(trip, work), content(AlbumDisplayFilter.All).cloudAlbums)
        assertEquals(listOf(camera), content(AlbumDisplayFilter.All).deviceFolders)
        assertEquals(listOf(trip, work), content(AlbumDisplayFilter.Cloud).cloudAlbums)
        assertTrue(content(AlbumDisplayFilter.Cloud).deviceFolders.isEmpty())
        assertTrue(content(AlbumDisplayFilter.Local).cloudAlbums.isEmpty())
        assertEquals(listOf(camera), content(AlbumDisplayFilter.Local).deviceFolders)
    }

    @Test
    fun `the Excluded view shows only the excluded ones, of both kinds`() {
        val view = content(AlbumDisplayFilter.Excluded)
        assertEquals(listOf(pets), view.cloudAlbums)
        assertEquals(listOf(screenshots), view.deviceFolders)
    }

    @Test
    fun `with nothing excluded the views are the ones the tab always had`() {
        assertEquals(albums, content(AlbumDisplayFilter.All, emptySet()).cloudAlbums)
        assertEquals(folders, content(AlbumDisplayFilter.All, emptySet()).deviceFolders)
        assertEquals(albums, content(AlbumDisplayFilter.Cloud, emptySet()).cloudAlbums)
        assertTrue(content(AlbumDisplayFilter.Cloud, emptySet()).deviceFolders.isEmpty())
        assertTrue(content(AlbumDisplayFilter.Local, emptySet()).cloudAlbums.isEmpty())
        assertEquals(folders, content(AlbumDisplayFilter.Local, emptySet()).deviceFolders)
        // Nothing to list, so the Excluded view falls through to its empty-state line.
        assertTrue(content(AlbumDisplayFilter.Excluded, emptySet()).isEmpty)
    }

    @Test
    fun `every card is either on the grid or in the Excluded view, never both`() {
        val all = content(AlbumDisplayFilter.All)
        val out = content(AlbumDisplayFilter.Excluded)
        assertEquals(albums.toSet(), (all.cloudAlbums + out.cloudAlbums).toSet())
        assertEquals(albums.size, all.cloudAlbums.size + out.cloudAlbums.size)
        assertEquals(folders.toSet(), (all.deviceFolders + out.deviceFolders).toSet())
        assertEquals(folders.size, all.deviceFolders.size + out.deviceFolders.size)
    }

    @Test
    fun `the views keep the order they are handed`() {
        // The grid order is the sort the user picked, which the rule only narrows.
        val reversed = albums.reversed()
        assertEquals(
            listOf(work, trip),
            AlbumsGridExclusion.cloudCards(AlbumDisplayFilter.All, reversed, excluded),
        )
    }

    @Test
    fun `an album and a folder sharing a name never match each other`() {
        val sameName = folder("trip")
        val onlyFolder = AlbumsGridExclusion.withFolder(emptySet(), "trip", exclude = true)
        assertFalse(AlbumsGridExclusion.isAlbumExcluded(onlyFolder, trip.linkId))
        assertTrue(AlbumsGridExclusion.isFolderExcluded(onlyFolder, sameName.name))

        val onlyAlbum = AlbumsGridExclusion.withAlbum(emptySet(), "trip", exclude = true)
        assertTrue(AlbumsGridExclusion.isAlbumExcluded(onlyAlbum, trip.linkId))
        assertFalse(AlbumsGridExclusion.isFolderExcluded(onlyAlbum, sameName.name))
    }

    @Test
    fun `putting a card back restores it and leaves every other entry`() {
        val back = AlbumsGridExclusion.withAlbum(excluded, pets.linkId, exclude = false)
        assertEquals(setOf("folder:Screenshots"), back)
        assertEquals(albums, content(AlbumDisplayFilter.All, back).cloudAlbums)

        val folderBack = AlbumsGridExclusion.withFolder(back, screenshots.name, exclude = false)
        assertTrue(folderBack.isEmpty())
        assertEquals(folders, content(AlbumDisplayFilter.All, folderBack).deviceFolders)
    }

    @Test
    fun `excluding twice or putting back what is on the grid changes nothing`() {
        assertEquals(excluded, AlbumsGridExclusion.withAlbum(excluded, pets.linkId, exclude = true))
        assertEquals(excluded, AlbumsGridExclusion.withAlbum(excluded, trip.linkId, exclude = false))
        assertEquals(excluded, AlbumsGridExclusion.withFolder(excluded, camera.name, exclude = false))
    }

    @Test
    fun `a card with no id stores nothing`() {
        // An entry for a blank id would match no card and could never be cleared.
        assertSame(excluded, AlbumsGridExclusion.withAlbum(excluded, "", exclude = true))
        assertSame(excluded, AlbumsGridExclusion.withAlbum(excluded, "  ", exclude = true))
        assertSame(excluded, AlbumsGridExclusion.withFolder(excluded, "", exclude = true))
        assertFalse(AlbumsGridExclusion.isAlbumExcluded(setOf("album:"), ""))
        assertFalse(AlbumsGridExclusion.isFolderExcluded(setOf("folder:"), ""))
    }

    @Test
    fun `a stored entry matching no card takes nothing else off the grid`() {
        // An entry left by a deleted album, or by another account on this phone, is inert.
        val stale = setOf("album:gone", "folder:Gone")
        assertEquals(albums, content(AlbumDisplayFilter.All, stale).cloudAlbums)
        assertEquals(folders, content(AlbumDisplayFilter.All, stale).deviceFolders)
        assertTrue(content(AlbumDisplayFilter.Excluded, stale).isEmpty)
    }

    @Test
    fun `arranging starts from the cloud cards the normal views lay out`() {
        // Arrange mode maps each grid cell to a slot in its seed, so the seed has to be exactly the
        // cloud cards on screen, and those are the same in the two views that show cloud albums.
        val seed = AlbumsGridExclusion.cloudCards(AlbumDisplayFilter.All, albums, excluded)
        assertEquals(content(AlbumDisplayFilter.All).cloudAlbums, seed)
        assertEquals(seed, AlbumsGridExclusion.cloudCards(AlbumDisplayFilter.Cloud, albums, excluded))
        assertFalse(pets in seed)
    }

    @Test
    fun `the Collection card and its offset stay out of the Excluded view`() {
        assertFalse(albumsCollectionCardShown(showCollection = true, searching = false, filter = AlbumDisplayFilter.Excluded))
        assertEquals(0, albumsCloudSegmentStart(showCollection = true, searching = false, filter = AlbumDisplayFilter.Excluded))
        for (filter in listOf(AlbumDisplayFilter.All, AlbumDisplayFilter.Cloud, AlbumDisplayFilter.Local)) {
            assertTrue("$filter", albumsCollectionCardShown(showCollection = true, searching = false, filter = filter))
            assertEquals("$filter", 1, albumsCloudSegmentStart(showCollection = true, searching = false, filter = filter))
        }
    }

    @Test
    fun `the offset counts the card exactly when the grid emits it, in every view`() {
        for (filter in AlbumDisplayFilter.entries) {
            for (showCollection in listOf(true, false)) {
                for (searching in listOf(true, false)) {
                    val expected = if (albumsCollectionCardShown(showCollection, searching, filter)) 1 else 0
                    assertEquals(
                        "filter=$filter showCollection=$showCollection searching=$searching",
                        expected,
                        albumsCloudSegmentStart(showCollection, searching, filter),
                    )
                }
            }
        }
    }
}
