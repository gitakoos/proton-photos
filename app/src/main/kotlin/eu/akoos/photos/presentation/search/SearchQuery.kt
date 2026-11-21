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

/**
 * How a typed query reads quote marks. A quoted span is one exact term: its spaces stay and a comma
 * inside it splits nothing. Any of the marks opens or closes a span, whatever the keyboard paired it
 * with, and a span left open runs to the end of the query.
 */
internal object SearchQuery {

    private const val MARKS = "\"“”„«»"

    fun isMark(c: Char): Boolean = c in MARKS

    fun hasQuote(q: String): Boolean = q.any(::isMark)

    /** [q] with the quote marks removed. */
    fun stripped(q: String): String = q.filterNot(::isMark)

    /**
     * The query's alternatives, each the folded terms that must all match: commas outside quotes
     * separate alternatives, spaces outside quotes separate words, and a quoted span is one term.
     */
    fun alternatives(q: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var terms = ArrayList<String>()
        val buf = StringBuilder()
        var quoted = false
        fun flush() {
            val term = if (quoted) buf.trim().toString() else buf.toString()
            if (term.isNotBlank()) terms += SearchFilter.fold(term)
            buf.setLength(0)
        }
        for (c in q.trim()) {
            when {
                isMark(c) -> { flush(); quoted = !quoted }
                quoted -> buf.append(c)
                c == ',' -> { flush(); if (terms.isNotEmpty()) out += terms; terms = ArrayList() }
                c == ' ' -> flush()
                else -> buf.append(c)
            }
        }
        flush()
        if (terms.isNotEmpty()) out += terms
        return out
    }

    /**
     * The part of [q] a date or category pill may read: all of it without quotes, the words after the
     * last closing mark (less a word glued to that mark), and nothing while a quote is still open.
     */
    fun pillText(q: String): String {
        val last = q.indexOfLast(::isMark)
        if (last < 0) return q
        if (q.count(::isMark) % 2 != 0) return ""
        val tail = q.substring(last + 1)
        return if (tail.isEmpty() || tail[0].isWhitespace()) tail else tail.dropWhile { !it.isWhitespace() }
    }

    /** [q] without its last [count] words, read from the end so a quoted span before them stays exactly
     *  as typed. */
    fun dropTrailingWords(q: String, count: Int): String {
        var end = q.trimEnd().length
        repeat(count) {
            while (end > 0 && !q[end - 1].isWhitespace()) end--
            while (end > 0 && q[end - 1].isWhitespace()) end--
        }
        return q.substring(0, end)
    }
}
