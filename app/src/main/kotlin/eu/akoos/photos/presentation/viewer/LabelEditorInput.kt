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

package eu.akoos.photos.presentation.viewer

import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.util.LabelNormalizer

/** What a change to the label field leaves: [commit] is the text before its last comma, ready to add
 *  (null when there is none), and [remainder] the text after it, which stays in the field. */
internal data class LabelFieldEdit(val commit: String?, val remainder: String)

/** The label field's text handling, kept free of Compose so it can be tested on its own. */
internal object LabelEditorInput {

    const val SUGGESTION_LIMIT = 8

    /**
     * Splits the field's new [text] at its last comma: what comes before is committed, what follows
     * stays, so typing "beach," adds "beach" and pasting "a, b, c" adds "a" and "b" and keeps "c". The
     * kept text is cut to [LabelNormalizer.MAX_LENGTH] characters, the longest a label can be.
     */
    fun onEdit(text: String): LabelFieldEdit {
        val cut = text.lastIndexOf(LabelNormalizer.SEPARATOR)
        if (cut < 0) return LabelFieldEdit(null, clip(text.trimStart()))
        val commit = text.substring(0, cut).takeIf { it.isNotBlank() }
        return LabelFieldEdit(commit, clip(text.substring(cut + 1).trimStart()))
    }

    /**
     * The used labels to offer under the field: those not in [existing] yet, narrowed to the ones that
     * contain what is [typed] (case and accents ignored), those starting with it first. [usage] arrives
     * most used first and each group keeps that order. At most [limit].
     */
    fun suggestions(
        usage: List<LabelUsage>,
        existing: List<String>,
        typed: String,
        limit: Int = SUGGESTION_LIMIT,
    ): List<String> {
        val have = existing.mapTo(HashSet()) { LabelNormalizer.fold(it) }
        val open = usage.filter { it.labelKey !in have }
        val needle = LabelNormalizer.fold(typed)
        if (needle.isEmpty()) return open.take(limit).map { it.label }
        val (starting, rest) = open.filter { needle in it.labelKey }.partition { it.labelKey.startsWith(needle) }
        return (starting + rest).take(limit).map { it.label }
    }

    private fun clip(text: String): String =
        if (text.codePointCount(0, text.length) <= LabelNormalizer.MAX_LENGTH) text
        else text.substring(0, text.offsetByCodePoints(0, LabelNormalizer.MAX_LENGTH))
}
