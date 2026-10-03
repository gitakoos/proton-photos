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
 */

package eu.akoos.photos.presentation.albums

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTilesTest {

    private val default = listOf(
        LibraryTile.Collection, LibraryTile.Hidden, LibraryTile.Shared, LibraryTile.Map, LibraryTile.Calendar,
        LibraryTile.People, LibraryTile.Offline,
    )

    @Test
    fun `with nothing saved the tiles come in the default order`() {
        assertEquals(default, resolveTileOrder(null))
        assertEquals(default, resolveTileOrder(""))
    }

    @Test
    fun `a saved order leads and the tiles it leaves out follow in their default place`() {
        assertEquals(
            listOf(
                LibraryTile.Calendar, LibraryTile.Map, LibraryTile.Collection, LibraryTile.Hidden, LibraryTile.Shared,
                LibraryTile.People, LibraryTile.Offline,
            ),
            resolveTileOrder("Calendar,Map"),
        )
    }

    @Test
    fun `unknown names and repeats in a saved order are ignored`() {
        assertEquals(
            listOf(
                LibraryTile.Offline, LibraryTile.Collection, LibraryTile.Hidden, LibraryTile.Shared, LibraryTile.Map,
                LibraryTile.Calendar, LibraryTile.People,
            ),
            resolveTileOrder("Offline,Gone,Offline,Collection"),
        )
    }
}
