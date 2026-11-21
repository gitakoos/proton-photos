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

/**
 * The one definition of an album's timeline exclusion, the weaker of the two hides an album offers.
 *
 * Excluding an album keeps its photos out of the main feed and nowhere else: the card stays on the
 * Albums grid, and search, the map, the calendar, memories and every picker still find the photos.
 * The stronger hide is a separate stored set, so neither ever reads or writes the other's ids and an
 * album can be in either, both, or neither.
 *
 * Three surfaces flip the same set — the album's own drawer, the drawer on its card, and the Settings
 * picker — so the rule lives here rather than once per caller.
 *
 * Beside the set sits the hide-all switch, which means every album excluded. It is the form the shared
 * backup carries between devices, so it stays on exactly while every album is excluded.
 */
object AlbumTimelineHide {

    /** The stored exclusion: the per-album set and the hide-all switch. */
    data class Stored(val excludedAlbumIds: Set<String>, val hideAll: Boolean)

    /** True while [albumLinkId]'s photos are kept out of the main feed. */
    fun isExcluded(excludedAlbumIds: Set<String>, albumLinkId: String, hideAll: Boolean = false): Boolean =
        albumLinkId.isNotBlank() && (hideAll || albumLinkId in excludedAlbumIds)

    /**
     * [stored] after flipping [albumLinkId]. While the hide-all switch is on every album counts as
     * excluded, so the flip takes this one album out: the switch goes off and every other album of
     * [allAlbumIds] stays excluded one by one.
     */
    fun toggled(stored: Stored, albumLinkId: String, allAlbumIds: Set<String>): Stored = when {
        albumLinkId.isBlank() -> stored
        stored.hideAll -> Stored((stored.excludedAlbumIds + allAlbumIds) - albumLinkId, hideAll = false)
        else -> stored.copy(excludedAlbumIds = toggled(stored.excludedAlbumIds, albumLinkId))
    }

    /**
     * [excludedAlbumIds] after flipping [albumLinkId]. Every other id carries through untouched, so
     * one album's choice can never move another's, and an album with no id yet leaves the set as it
     * stands rather than storing a blank that would match nothing and never clear.
     */
    fun toggled(excludedAlbumIds: Set<String>, albumLinkId: String): Set<String> = when {
        albumLinkId.isBlank() -> excludedAlbumIds
        albumLinkId in excludedAlbumIds -> excludedAlbumIds - albumLinkId
        else -> excludedAlbumIds + albumLinkId
    }
}
