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

/** The Albums-tab filter settings, read from the store as one snapshot. */
internal data class AlbumsFilterPrefs(
    val defaultOrdinal: Int,
    val rememberLast: Boolean,
    val lastOrdinal: Int,
)

/**
 * The Albums view filter, and whether it is decided for the current stay on the Albums page: resolved
 * on arrival, or picked from the pill or the sheet.
 */
internal data class AlbumsEntryFilterState(
    val filter: AlbumDisplayFilter,
    val settled: Boolean,
)

/**
 * The filter at [ordinal], clamped into the ones the tab may open on, so a stray stored value still
 * names a real filter. The Excluded view sits past that range: it is only ever picked by hand, never
 * stored as the default or the last pick.
 */
internal fun albumDisplayFilterAt(ordinal: Int): AlbumDisplayFilter =
    AlbumDisplayFilter.entries[ordinal.coerceIn(0, AlbumDisplayFilter.Local.ordinal)]

/** The filter an arrival on the Albums page opens on: the last pick while remember-last is on, otherwise
 *  the configured default. */
internal fun resolveAlbumsEntryFilter(prefs: AlbumsFilterPrefs): AlbumDisplayFilter =
    albumDisplayFilterAt(if (prefs.rememberLast) prefs.lastOrdinal else prefs.defaultOrdinal)

/**
 * The Albums filter once the pager's page changes or the settings finish loading.
 *
 * Leaving the Albums page unsettles the filter without changing it, so the page slides out as it was
 * and the next arrival resolves afresh: the default with remember-last off, the last pick with it on.
 * An unsettled arrival waits for [prefs], null until the store delivers, rather than resolving from
 * placeholder values. A settled filter is kept whatever the settings hold. That covers the filter the
 * gallery restores when a screen pushed over it pops, and a pick, which a settings load landing after
 * it must never undo.
 */
internal fun nextAlbumsEntryFilterState(
    current: AlbumsEntryFilterState,
    onAlbumsPage: Boolean,
    prefs: AlbumsFilterPrefs?,
): AlbumsEntryFilterState = when {
    !onAlbumsPage -> current.copy(settled = false)
    current.settled -> current
    prefs == null -> current
    else -> AlbumsEntryFilterState(resolveAlbumsEntryFilter(prefs), settled = true)
}

/**
 * The Albums filter once the stored settings arrive, given [seenDefault], the default the gallery last
 * saw. A new default is picked on the Albums settings page, away from the tab, and shows on the tab at
 * once as a settled pick rather than waiting for the next arrival, so the pick never reads as ignored.
 * Anything else keeps [current]: the first delivery ([seenDefault] null) is a sighting rather than a
 * change, and with remember-last on the last pick owns the tab, so a default has nothing to show.
 */
internal fun afterAlbumsDefaultChange(
    current: AlbumsEntryFilterState,
    seenDefault: AlbumDisplayFilter?,
    prefs: AlbumsFilterPrefs,
): AlbumsEntryFilterState {
    val default = albumDisplayFilterAt(prefs.defaultOrdinal)
    return if (seenDefault == null || seenDefault == default || prefs.rememberLast) {
        current
    } else {
        AlbumsEntryFilterState(default, settled = true)
    }
}
