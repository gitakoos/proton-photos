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
import eu.akoos.photos.presentation.albums.AlbumsGridExclusion
import eu.akoos.photos.presentation.albums.DeviceFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the Search screen shows of the albums: the idle page's Albums row, which follows the
 * Albums grid card for card, and the albums and folders a typed name finds, which reach past what the
 * grid leaves out but never past a hidden album. Plain JVM assertions on pure functions, no Compose
 * runtime and no Android.
 */
class SearchAlbumsTest {

    private fun album(
        linkId: String,
        name: String,
        lastActivityTimeMs: Long,
        sharedByEmail: String? = null,
    ) = Album(
        linkId = linkId,
        name = name,
        photoCount = 1,
        coverLinkId = null,
        lastActivityTimeMs = lastActivityTimeMs,
        sharedByEmail = sharedByEmail,
    )

    private fun folder(name: String, itemCount: Int = 1) =
        DeviceFolder(name = name, coverUri = null, itemCount = itemCount)

    private fun cloud(album: Album): SearchAlbum = SearchAlbum.Cloud(album)
    private fun device(folder: DeviceFolder): SearchAlbum = SearchAlbum.Folder(folder)

    // The tab's default order is the most recently touched album first.
    private val summer = album("summer", "Nyár", lastActivityTimeMs = 300)
    private val trip = album("trip", "Road trip", lastActivityTimeMs = 200)
    private val pets = album("pets", "Pets", lastActivityTimeMs = 100)
    private val camera = folder("Camera", itemCount = 40)
    private val screenshots = folder("Screenshots", itemCount = 5)

    // The cache hands the albums over in an order of its own, which neither surface keeps.
    private val source = SearchAlbums.Source(
        cloudAlbums = listOf(pets, trip, summer),
        deviceFolders = listOf(camera, screenshots),
    )

    @Test
    fun `the row lists the cloud albums in the tab's order, then the device folders`() {
        assertEquals(
            listOf(cloud(summer), cloud(trip), cloud(pets), device(camera), device(screenshots)),
            SearchAlbums.row(source),
        )
    }

    @Test
    fun `the row follows the user's own arrangement when the tab does`() {
        val arranged = source.copy(
            sortMode = AlbumSortMode.Custom,
            customOrder = listOf(pets.linkId, summer.linkId, trip.linkId),
        )
        assertEquals(
            listOf(cloud(pets), cloud(summer), cloud(trip), device(camera), device(screenshots)),
            SearchAlbums.row(arranged),
        )
    }

    @Test
    fun `a hidden album is neither shown nor found`() {
        val hidden = source.copy(hiddenAlbumIds = setOf(trip.linkId))
        assertEquals(
            listOf(cloud(summer), cloud(pets), device(camera), device(screenshots)),
            SearchAlbums.row(hidden),
        )
        assertTrue(SearchAlbums.matches(hidden, "road").isEmpty())
        assertTrue(SearchAlbums.matches(hidden, "Road trip").isEmpty())
    }

    @Test
    fun `an album or folder taken off the grid leaves the row but is still found`() {
        val excluded = source.copy(
            excluded = AlbumsGridExclusion.withFolder(
                AlbumsGridExclusion.withAlbum(emptySet(), pets.linkId, exclude = true),
                screenshots.name,
                exclude = true,
            ),
        )
        assertEquals(listOf(cloud(summer), cloud(trip), device(camera)), SearchAlbums.row(excluded))
        assertEquals(listOf(cloud(pets)), SearchAlbums.matches(excluded, "pets"))
        assertEquals(listOf(device(screenshots)), SearchAlbums.matches(excluded, "screen"))
    }

    @Test
    fun `a guest sees and finds the device folders alone`() {
        // Signed out, the view model hands over no cloud albums at all.
        val guest = SearchAlbums.Source(deviceFolders = listOf(camera, screenshots))
        assertEquals(listOf(device(camera), device(screenshots)), SearchAlbums.row(guest))
        assertEquals(listOf(device(camera)), SearchAlbums.matches(guest, "cam"))
        assertTrue(SearchAlbums.matches(guest, "pets").isEmpty())
    }

    @Test
    fun `a folder with no photo left on the device is neither shown nor found`() {
        val vaulted = folder("Vaulted", itemCount = 0)
        val withEmpty = source.copy(deviceFolders = listOf(camera, vaulted, screenshots))
        assertEquals(
            listOf(cloud(summer), cloud(trip), cloud(pets), device(camera), device(screenshots)),
            SearchAlbums.row(withEmpty),
        )
        assertTrue(SearchAlbums.matches(withEmpty, "vaulted").isEmpty())
    }

    @Test
    fun `albums shared with the user stay off both surfaces`() {
        val shared = album("shared", "Pets of a friend", lastActivityTimeMs = 999, sharedByEmail = "friend@example.com")
        val withShared = source.copy(cloudAlbums = source.cloudAlbums + shared)
        assertFalse(cloud(shared) in SearchAlbums.row(withShared))
        assertEquals(listOf(cloud(pets)), SearchAlbums.matches(withShared, "pets"))
    }

    @Test
    fun `an album shared with the user is found by name after the rest, but not listed in the row`() {
        val sharedPets = album("sp", "Pets of a friend", lastActivityTimeMs = 999, sharedByEmail = "friend@example.com")
        val withShared = source.copy(sharedAlbums = listOf(sharedPets))
        assertFalse(cloud(sharedPets) in SearchAlbums.row(withShared))
        assertEquals(listOf(cloud(pets), cloud(sharedPets)), SearchAlbums.matches(withShared, "pets"))
    }

    @Test
    fun `a shared album with the exact name leads the partial matches`() {
        val sharedExact = album("sp", "Pets", lastActivityTimeMs = 1, sharedByEmail = "friend@example.com")
        val petsTwo = album("pets2", "Pets 2", lastActivityTimeMs = 900)
        val both = source.copy(cloudAlbums = source.cloudAlbums + petsTwo, sharedAlbums = listOf(sharedExact))
        // Exact names lead, the user's own album ahead of the shared one, then the name starting with it.
        assertEquals(listOf(cloud(pets), cloud(sharedExact), cloud(petsTwo)), SearchAlbums.matches(both, "pets"))
    }

    @Test
    fun `a guest has no shared albums to find`() {
        val guest = SearchAlbums.Source(deviceFolders = listOf(camera))
        assertTrue(SearchAlbums.matches(guest, "friend").isEmpty())
    }

    @Test
    fun `a blank query finds nothing`() {
        for (query in listOf("", " ", "   ", "\t\n")) {
            assertTrue("'$query'", SearchAlbums.matches(source, query).isEmpty())
        }
    }

    @Test
    fun `case and accents are ignored both ways`() {
        assertEquals(listOf(cloud(summer)), SearchAlbums.matches(source, "nyar"))
        assertEquals(listOf(cloud(summer)), SearchAlbums.matches(source, "NYÁR"))
        assertEquals(listOf(device(screenshots)), SearchAlbums.matches(source, "scréen"))
    }

    @Test
    fun `an exact name leads, then names starting with the query, then names containing it`() {
        // The tab order runs against the ranking on purpose: the newest album is the weakest match.
        val onTheBeach = album("on", "On the beach", lastActivityTimeMs = 500)
        val beachHouse = album("house", "Beach house", lastActivityTimeMs = 400)
        val beach = album("beach", "Beach", lastActivityTimeMs = 50)
        val summerBeach = folder("Summer beach", itemCount = 30)
        val beaches = folder("Beaches 2023", itemCount = 20)
        val beachFolder = folder("Beach", itemCount = 10)
        val ranked = SearchAlbums.Source(
            cloudAlbums = listOf(beach, beachHouse, onTheBeach),
            deviceFolders = listOf(summerBeach, beaches, beachFolder),
        )
        // Within each group the tab's order holds: the cloud albums, then the folders.
        assertEquals(
            listOf(
                cloud(beach), device(beachFolder),
                cloud(beachHouse), device(beaches),
                cloud(onTheBeach), device(summerBeach),
            ),
            SearchAlbums.matches(ranked, "beach"),
        )
    }

    @Test
    fun `the query is trimmed before it is compared`() {
        // Newer than Pets, so it leads the tab, but only Pets is the exact name.
        val petsTwo = album("pets2", "Pets 2", lastActivityTimeMs = 900)
        val withTwo = source.copy(cloudAlbums = source.cloudAlbums + petsTwo)
        val expected = listOf(cloud(pets), cloud(petsTwo))
        assertEquals(expected, SearchAlbums.matches(withTwo, "pets"))
        assertEquals(expected, SearchAlbums.matches(withTwo, "  pets  "))
        assertEquals(expected, SearchAlbums.matches(withTwo, "\tPets\n"))
    }

    @Test
    fun `an album and a folder sharing a name are both found and keep apart as list keys`() {
        val cameraAlbum = album("cam", "Camera", lastActivityTimeMs = 1)
        assertNotEquals(cloud(cameraAlbum).key, device(camera).key)
        val both = source.copy(cloudAlbums = listOf(cameraAlbum))
        assertEquals(listOf(cloud(cameraAlbum), device(camera)), SearchAlbums.matches(both, "camera"))
    }
}
