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

import androidx.compose.ui.geometry.Offset
import eu.akoos.photos.presentation.albums.albumsArrangeOffered
import eu.akoos.photos.presentation.albums.arrangeDragStep
import eu.akoos.photos.presentation.settings.AlbumsSettingsRow
import eu.akoos.photos.presentation.settings.albumsSettingsRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Albums tab's grid-or-list choice: how a stored value reads back, which rows the filter
 * sheet and the Albums settings page lay out in each layout, and that arranging runs in both layouts,
 * a list row held to its column.
 *
 * Plain JVM assertions on pure functions, no Compose runtime and no Android.
 */
class AlbumsViewModeTest {

    @Test
    fun `no stored value opens the grid`() {
        assertEquals(AlbumsViewMode.Grid, AlbumsViewMode.Default)
        assertEquals(AlbumsViewMode.Grid, AlbumsViewMode.fromOrdinal(null))
    }

    @Test
    fun `a stored ordinal reads back as its mode`() {
        for (mode in AlbumsViewMode.entries) {
            assertEquals(mode, AlbumsViewMode.fromOrdinal(mode.ordinal))
        }
        assertEquals(AlbumsViewMode.List, AlbumsViewMode.fromOrdinal(1))
    }

    @Test
    fun `a stray stored ordinal falls back to the grid`() {
        for (ordinal in listOf(-1, 2, 7, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals("ordinal $ordinal", AlbumsViewMode.Grid, AlbumsViewMode.fromOrdinal(ordinal))
        }
    }

    @Test
    fun `the sheet holds which albums show, the view and the sort, then the way to the settings page`() {
        assertEquals(
            listOf(AlbumsSheetRow.Show, AlbumsSheetRow.View, AlbumsSheetRow.Sort, AlbumsSheetRow.MoreSettings),
            albumsSheetRows(),
        )
        assertEquals(AlbumsSheetRow.entries.toList(), albumsSheetRows())
    }

    @Test
    fun `in the grid the settings page leads with the cover size`() {
        assertEquals(
            listOf(
                AlbumsSettingsRow.CoverSize,
                AlbumsSettingsRow.DefaultFilter,
                AlbumsSettingsRow.RememberLast,
                AlbumsSettingsRow.ShowCollection,
                AlbumsSettingsRow.Shortcuts,
            ),
            albumsSettingsRows(AlbumsViewMode.Grid),
        )
    }

    @Test
    fun `in the list the settings page leaves out the cover size`() {
        assertEquals(
            listOf(
                AlbumsSettingsRow.DefaultFilter,
                AlbumsSettingsRow.RememberLast,
                AlbumsSettingsRow.ShowCollection,
                AlbumsSettingsRow.Shortcuts,
            ),
            albumsSettingsRows(AlbumsViewMode.List),
        )
    }

    @Test
    fun `the grid reaches every page row and the list every one but the cover size`() {
        val grid = albumsSettingsRows(AlbumsViewMode.Grid).toSet()
        val list = albumsSettingsRows(AlbumsViewMode.List).toSet()
        assertEquals(AlbumsSettingsRow.entries.toSet(), grid)
        assertEquals(grid - AlbumsSettingsRow.CoverSize, list)
    }

    @Test
    fun `arranging is offered in every normal view and never in the Excluded one`() {
        // The rule takes no layout, so the grid and the list offer it alike.
        for (filter in listOf(AlbumDisplayFilter.All, AlbumDisplayFilter.Cloud, AlbumDisplayFilter.Local)) {
            assertTrue("$filter", albumsArrangeOffered(filter))
        }
        assertFalse(albumsArrangeOffered(AlbumDisplayFilter.Excluded))
    }

    @Test
    fun `a card in the grid follows the finger both ways`() {
        for (drag in listOf(Offset(12f, -7f), Offset(-30f, 0f), Offset(0f, 45f), Offset.Zero)) {
            assertEquals("$drag", drag, arrangeDragStep(drag, AlbumsViewMode.Grid))
        }
    }

    @Test
    fun `a row in the list follows the finger up and down only`() {
        assertEquals(Offset(0f, -7f), arrangeDragStep(Offset(12f, -7f), AlbumsViewMode.List))
        assertEquals(Offset(0f, 45f), arrangeDragStep(Offset(0f, 45f), AlbumsViewMode.List))
        // A sideways swipe moves the row nowhere, so its centre stays inside the one column.
        assertEquals(Offset.Zero, arrangeDragStep(Offset(-30f, 0f), AlbumsViewMode.List))
    }
}
