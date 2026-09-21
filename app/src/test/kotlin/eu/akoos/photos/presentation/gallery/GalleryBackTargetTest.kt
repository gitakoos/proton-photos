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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins where the system back gesture goes from the gallery's top-level pager (#89), the rule
 * [galleryBackTarget] answers for the screen's BackHandler.
 *
 * A null answer means back is not intercepted, so it reaches whoever is next: the selection handler
 * in selection mode, the system otherwise, which exits the app. Plain JVM assertions over three
 * ints, no Compose and no Android.
 */
class GalleryBackTargetTest {

    private val photos = 0
    private val albums = 1
    private val shared = 2

    @Test
    fun `back on the landing tab is not intercepted, so the app exits`() {
        assertNull(galleryBackTarget(currentPage = photos, landingTab = photos, isSelectionMode = false))
    }

    @Test
    fun `back on another tab returns to the landing tab`() {
        assertEquals(photos, galleryBackTarget(currentPage = albums, landingTab = photos, isSelectionMode = false))
        assertEquals(photos, galleryBackTarget(currentPage = shared, landingTab = photos, isSelectionMode = false))
    }

    @Test
    fun `selection mode is not intercepted, so the selection handler gets the press`() {
        // Selection mode clears the selection instead. The selection handler composes later and so
        // already wins on registration order, but the rule refuses the press on its own too.
        assertNull(galleryBackTarget(currentPage = albums, landingTab = photos, isSelectionMode = true))
        assertNull(galleryBackTarget(currentPage = photos, landingTab = photos, isSelectionMode = true))
    }

    @Test
    fun `a landing tab other than Photos is the target, and its own tab still exits`() {
        // Landing on Albums: back from Photos goes to Albums, and back from Albums exits.
        assertEquals(albums, galleryBackTarget(currentPage = photos, landingTab = albums, isSelectionMode = false))
        assertEquals(albums, galleryBackTarget(currentPage = shared, landingTab = albums, isSelectionMode = false))
        assertNull(galleryBackTarget(currentPage = albums, landingTab = albums, isSelectionMode = false))

        // And the same landing on Shared.
        assertEquals(shared, galleryBackTarget(currentPage = photos, landingTab = shared, isSelectionMode = false))
        assertNull(galleryBackTarget(currentPage = shared, landingTab = shared, isSelectionMode = false))
    }

    @Test
    fun `a stored landing index outside the pager is clamped into it`() {
        // A value the pager has no page for can never become a scroll target.
        assertEquals(shared, galleryBackTarget(currentPage = photos, landingTab = 7, isSelectionMode = false))
        assertEquals(photos, galleryBackTarget(currentPage = albums, landingTab = -1, isSelectionMode = false))
        assertNull(galleryBackTarget(currentPage = shared, landingTab = 7, isSelectionMode = false))
    }
}
