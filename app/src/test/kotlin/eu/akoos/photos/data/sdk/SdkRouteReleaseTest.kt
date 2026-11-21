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

package eu.akoos.photos.data.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A route not allowed in a release build stays off there whatever its stored switch says and its switch is
 * not shown; a debug build shows and runs it like any other route.
 */
class SdkRouteReleaseTest {

    private val heldBack = listOf(SdkRoute.TIMELINE, SdkRoute.ALBUM_LIST, SdkRoute.ALBUM_PHOTOS, SdkRoute.SAVE_TO_LIBRARY)

    private val original = listOf(
        SdkRoute.TAGS, SdkRoute.TRASH, SdkRoute.DOWNLOAD, SdkRoute.UPLOAD,
        SdkRoute.ALBUMS, SdkRoute.EVENTS, SdkRoute.SHARED,
    )

    @Test
    fun `exactly the timeline, album list, album photos and save to library routes are held back from release`() {
        assertEquals(heldBack.toSet(), SdkRoute.entries.filterNot { it.releaseAllowed }.toSet())
    }

    @Test
    fun `a held back route is off in a release build even when switched on`() {
        for (route in heldBack) {
            assertFalse(route.name, routeOn(route, stored = true, debug = false))
            assertFalse(route.name, routeOn(route, stored = null, debug = false))
        }
    }

    @Test
    fun `a held back route switched on runs in a debug build`() {
        for (route in heldBack) {
            assertTrue(route.name, routeOn(route, stored = true, debug = true))
            assertFalse(route.name, routeOn(route, stored = false, debug = true))
            assertFalse(route.name, routeOn(route, stored = null, debug = true))
        }
    }

    @Test
    fun `the release settings page leaves out the held back routes and keeps every other one`() {
        val shown = routesShown(debug = false)
        assertTrue(heldBack.none { it in shown })
        assertEquals(SdkRoute.entries.filter { it !in heldBack }, shown)
        assertTrue(shown.containsAll(original + SdkRoute.SHARED_BY_ME + SdkRoute.THUMBNAILS))
    }

    @Test
    fun `the debug settings page shows every route`() {
        assertEquals(SdkRoute.entries, routesShown(debug = true))
    }

    @Test
    fun `the seven original routes stay on by default and visible in a release build`() {
        for (route in original) {
            assertTrue(route.name, route.releaseAllowed)
            assertTrue(route.name, routeOn(route, stored = null, debug = false))
            assertFalse(route.name, routeOn(route, stored = false, debug = false))
        }
    }
}
