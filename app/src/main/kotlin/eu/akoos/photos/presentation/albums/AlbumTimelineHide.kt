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
 */
object AlbumTimelineHide {

    /** True while [albumLinkId]'s photos are kept out of the main feed. */
    fun isExcluded(excludedAlbumIds: Set<String>, albumLinkId: String): Boolean =
        albumLinkId.isNotBlank() && albumLinkId in excludedAlbumIds

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
