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

/** The filter at [ordinal], clamped into the enum so a stray stored value still names a real filter. */
internal fun albumDisplayFilterAt(ordinal: Int): AlbumDisplayFilter =
    AlbumDisplayFilter.entries[ordinal.coerceIn(0, AlbumDisplayFilter.entries.lastIndex)]

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
