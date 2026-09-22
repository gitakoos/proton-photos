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
import org.junit.Test

/**
 * Pins the filter the Albums tab shows on arrival, the rule [nextAlbumsEntryFilterState] answers for
 * the gallery's page effect: resolve once the stored settings are in, keep a settled filter, and
 * resolve again only after the page was left.
 *
 * Each step is one run of that effect, fed the page the pager is on and the settings snapshot (null
 * until the store delivers). Plain JVM assertions, no Compose and no Android.
 */
class AlbumsEntryFilterTest {

    private val all = AlbumDisplayFilter.All
    private val cloud = AlbumDisplayFilter.Cloud
    private val local = AlbumDisplayFilter.Local

    private fun prefs(
        default: AlbumDisplayFilter = all,
        rememberLast: Boolean = false,
        last: AlbumDisplayFilter = all,
    ) = AlbumsFilterPrefs(defaultOrdinal = default.ordinal, rememberLast = rememberLast, lastOrdinal = last.ordinal)

    private fun AlbumsEntryFilterState.step(onAlbumsPage: Boolean, prefs: AlbumsFilterPrefs?) =
        nextAlbumsEntryFilterState(this, onAlbumsPage, prefs)

    @Test
    fun `remember-last on opens on the last pick`() {
        assertEquals(local, resolveAlbumsEntryFilter(prefs(default = cloud, rememberLast = true, last = local)))
    }

    @Test
    fun `remember-last off opens on the configured default`() {
        assertEquals(cloud, resolveAlbumsEntryFilter(prefs(default = cloud, rememberLast = false, last = local)))
    }

    @Test
    fun `a stored ordinal outside the enum is clamped into it`() {
        assertEquals(all, albumDisplayFilterAt(-1))
        assertEquals(cloud, albumDisplayFilterAt(1))
        assertEquals(local, albumDisplayFilterAt(7))
        assertEquals(
            local,
            resolveAlbumsEntryFilter(AlbumsFilterPrefs(defaultOrdinal = 9, rememberLast = false, lastOrdinal = 0)),
        )
        assertEquals(
            all,
            resolveAlbumsEntryFilter(AlbumsFilterPrefs(defaultOrdinal = 2, rememberLast = true, lastOrdinal = -3)),
        )
    }

    @Test
    fun `a first arrival waits for the stored settings rather than resolving placeholders`() {
        val fresh = AlbumsEntryFilterState(all, settled = false)
        // Nothing is decided before the store delivers, so nothing is applied.
        assertEquals(fresh, fresh.step(onAlbumsPage = true, prefs = null))
        // Once the settings are in, the stored filter is applied and settles the stay.
        assertEquals(
            AlbumsEntryFilterState(local, settled = true),
            fresh.step(onAlbumsPage = true, prefs = prefs(rememberLast = true, last = local)),
        )
        assertEquals(
            AlbumsEntryFilterState(cloud, settled = true),
            fresh.step(onAlbumsPage = true, prefs = prefs(default = cloud)),
        )
    }

    @Test
    fun `settings loading while another page shows decide nothing until the first arrival`() {
        val fresh = AlbumsEntryFilterState(all, settled = false)
        assertEquals(fresh, fresh.step(onAlbumsPage = false, prefs = null))
        assertEquals(fresh, fresh.step(onAlbumsPage = false, prefs = prefs(default = local)))
        assertEquals(
            AlbumsEntryFilterState(local, settled = true),
            fresh.step(onAlbumsPage = true, prefs = prefs(default = local)),
        )
    }

    @Test
    fun `returning from a pushed screen keeps the restored filter`() {
        // The gallery comes back on its saved state: the filter it showed, settled, still on Albums.
        val restored = AlbumsEntryFilterState(local, settled = true)
        assertEquals(restored, restored.step(onAlbumsPage = true, prefs = null))
        // Remember-last on: the settings arriving afterwards do not resolve it again.
        assertEquals(restored, restored.step(onAlbumsPage = true, prefs = prefs(rememberLast = true, last = local)))
        // Remember-last off: the filter on screen stays, it is not reset to the default.
        assertEquals(restored, restored.step(onAlbumsPage = true, prefs = prefs(default = all)))
    }

    @Test
    fun `leaving the Albums page lets the next arrival resolve again`() {
        val onScreen = AlbumsEntryFilterState(local, settled = true)
        val away = onScreen.step(onAlbumsPage = false, prefs = prefs(default = all))
        // The filter stays as it was while the page slides out, only unsettled.
        assertEquals(AlbumsEntryFilterState(local, settled = false), away)
        // Remember-last off: back on the tab it resets to the default.
        assertEquals(
            AlbumsEntryFilterState(all, settled = true),
            away.step(onAlbumsPage = true, prefs = prefs(default = all)),
        )
        // Remember-last on: back on the tab it opens on the last pick.
        assertEquals(
            AlbumsEntryFilterState(cloud, settled = true),
            away.step(onAlbumsPage = true, prefs = prefs(default = all, rememberLast = true, last = cloud)),
        )
    }

    @Test
    fun `a pick is never resolved over, even when the settings land after it`() {
        // A pill or sheet pick sets the filter and settles it.
        val picked = AlbumsEntryFilterState(cloud, settled = true)
        assertEquals(picked, picked.step(onAlbumsPage = true, prefs = null))
        // With remember-last off the settings resolve to the default, which must not replace the pick.
        assertEquals(picked, picked.step(onAlbumsPage = true, prefs = prefs(default = all, last = cloud)))
        assertEquals(picked, picked.step(onAlbumsPage = true, prefs = prefs(default = local)))
    }
}
