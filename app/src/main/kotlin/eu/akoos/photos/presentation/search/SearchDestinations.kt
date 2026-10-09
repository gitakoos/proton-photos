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

import eu.akoos.photos.presentation.albums.LibraryTile

/**
 * The shortcut tiles a typed query names, so "shared" or "map" offers the way into that page the way the
 * Albums tab's tile row does. Names are compared folded, case and accents ignored, in the language the
 * tiles are drawn in.
 */
internal object SearchDestinations {

    /** A one-letter query would match half the tiles, so it names none. */
    private const val MIN_QUERY_LENGTH = 2

    /**
     * The tiles among [foldedNames] (each tile with its pre-folded label, in the order to offer them)
     * that [query] names: an exact label first, then a label starting with the query, then one with a
     * word starting with it ("albums" finds a label "Shared albums"). Each group keeps the given order.
     */
    fun match(query: String, foldedNames: List<Pair<LibraryTile, String>>): List<LibraryTile> {
        val needle = SearchFilter.fold(query.trim())
        if (needle.length < MIN_QUERY_LENGTH) return emptyList()
        val exact = ArrayList<LibraryTile>()
        val startsWith = ArrayList<LibraryTile>()
        val wordStart = ArrayList<LibraryTile>()
        for ((tile, name) in foldedNames) {
            when {
                name == needle -> exact += tile
                name.startsWith(needle) -> startsWith += tile
                name.contains(" $needle") -> wordStart += tile
            }
        }
        return exact + startsWith + wordStart
    }
}
