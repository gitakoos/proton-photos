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

import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.util.LabelNormalizer

/**
 * The label keyword at the end of a typed query: [rest] is the text before it, and [name] what follows
 * `keyword:`, null when the keyword stands alone.
 */
internal data class LabelTyping(val rest: String, val name: String?)

/**
 * Finds the label keyword ("labels", "label", or the app language's word) at the end of a search query,
 * alone or as `labels:<name>`. A label name may hold spaces and colons but never a comma, so the keyword
 * is looked for after the last comma, at a word start, and everything after its colon is the name. It is
 * only looked for in the text a pill may read ([SearchQuery.pillText]), so a quoted "labels:x" stays exact text.
 */
internal object LabelQuery {

    private val SPACES = Regex("\\s+")

    /** The keyword variants of each bar-separated [phrases] string, folded, longest first. */
    fun keywords(phrases: Collection<String>): List<String> =
        phrases.flatMap { it.split('|') }.map(::key).filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

    /** The keyword [q] ends with, or null when it holds none. */
    fun parse(q: String, keywords: List<String>): LabelTyping? {
        if (keywords.isEmpty() || q.isBlank()) return null
        val readable = SearchQuery.pillText(q)
        if (readable.isBlank()) return null
        val start = maxOf(q.lastIndexOf(',') + 1, q.length - readable.length)
        for (i in start until q.length) {
            if (q[i].isWhitespace() || (i > 0 && !q[i - 1].isWhitespace() && q[i - 1] != ',')) continue
            val tail = q.substring(i)
            val colon = tail.indexOf(':')
            val head = if (colon >= 0) tail.substring(0, colon) else tail
            if (key(head) !in keywords) continue
            val rest = q.substring(0, i).trimEnd().trimEnd(',').trimEnd()
            return LabelTyping(rest, if (colon >= 0) tail.substring(colon + 1).trim() else null)
        }
        return null
    }

    /** Whether the search is a label search: a label is picked in [filter], or [q] ends with the keyword. Content
     *  search and the name rows stay out of one, so its results are exactly the labelled photos. */
    fun isLabelSearch(q: String, filter: ContentFilter, keywords: List<String>): Boolean =
        filter.labelKeys.isNotEmpty() || parse(q, keywords) != null

    /** [q] without the label keyword it ends with, the text the rest of the search works on. */
    fun textOf(q: String, keywords: List<String>): String = parse(q, keywords)?.rest ?: q

    /** The labels the chip row offers while [typing] is under way: those whose name holds what follows the
     *  colon, the ones already [picked] left out, most used first as [usage] comes. */
    fun offered(typing: LabelTyping, usage: List<LabelUsage>, picked: Set<String>): List<LabelUsage> {
        val needle = typing.name?.let { LabelNormalizer.fold(it) }.orEmpty()
        return usage.filter { it.labelKey !in picked && (needle.isEmpty() || needle in it.labelKey) }
    }

    /** The label [typing] names exactly, folded, among [usage]; null for none or a bare keyword. */
    fun named(typing: LabelTyping, usage: List<LabelUsage>): LabelUsage? {
        val name = typing.name ?: return null
        val wanted = LabelNormalizer.fold(name)
        if (wanted.isEmpty()) return null
        return usage.firstOrNull { it.labelKey == wanted }
    }

    private fun key(text: String): String = SearchFilter.fold(text).replace(SPACES, " ").trim()
}
