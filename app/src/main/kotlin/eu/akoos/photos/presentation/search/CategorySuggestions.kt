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

package eu.akoos.photos.presentation.search

import eu.akoos.photos.presentation.gallery.GalleryFilter

/**
 * A category chip the typed query names. [consumedTokens] is how many trailing words of the query
 * spell it, so selecting it can take them out of the text.
 */
data class CategorySuggestion(val filter: GalleryFilter, val consumedTokens: Int)

/** Reads a category name off the end of a typed query: the last word, or the last two for a two-word
 *  name, must spell the whole name, singular or plural ("screenshot" and "screenshots"). A partial
 *  word ("scre", "vid") or only part of a two-word name ("live") names nothing. */
internal object CategorySuggestions {
    private val SPACES = Regex("\\s+")
    private val NAME_SEPARATORS = Regex("[\\s-]+")

    /** [foldedNames] are the rail's chips in its order, each name folded like the query; [selected] is
     *  the chip already picked, which is never returned. A two-word match wins over a one-word one. */
    fun match(
        query: String,
        foldedNames: Map<GalleryFilter, String>,
        selected: GalleryFilter,
    ): CategorySuggestion? {
        val tokens = SearchFilter.fold(query.trim()).split(SPACES).filter { it.isNotEmpty() }
        val last = tokens.lastOrNull() ?: return null
        val lastTwo = tokens.getOrNull(tokens.size - 2)?.let { "$it $last" }
        val candidates = foldedNames.entries
            .filter { it.key != selected && it.key != GalleryFilter.All }
            .map { (filter, name) -> filter to name.split(NAME_SEPARATORS).filter { it.isNotEmpty() } }
        candidates.firstOrNull { (_, words) ->
            words.size == 2 && lastTwo != null && spells(lastTwo, words.joinToString(" "))
        }?.let { return CategorySuggestion(it.first, 2) }
        return candidates.firstOrNull { (_, words) -> words.size == 1 && spells(last, words.single()) }
            ?.let { CategorySuggestion(it.first, 1) }
    }

    /** Whether [typed] is [name] itself or its singular or plural, one trailing 's' apart. */
    private fun spells(typed: String, name: String): Boolean =
        typed == name ||
            (typed.length == name.length + 1 && typed.startsWith(name) && typed.endsWith('s')) ||
            (name.length == typed.length + 1 && name.startsWith(typed) && name.endsWith('s'))
}
