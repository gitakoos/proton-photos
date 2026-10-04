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

package eu.akoos.photos.util

import eu.akoos.photos.presentation.search.SearchFilter

/**
 * A comma-separated input split into clean labels. [labels] holds at most [LabelNormalizer.MAX_LABELS]
 * entries in input order; [overflow] holds the distinct labels past the cap, in input order, so a caller
 * can tell the user exactly which ones did not fit.
 */
data class ParsedLabels(val labels: List<String>, val overflow: List<String>) {
    val hitCap: Boolean get() = overflow.isNotEmpty()
}

/**
 * The result of adding labels to a photo that may already carry some. [labels] is the photo's full list
 * afterwards (existing first, then the new ones that fit), [added] the new ones that made it in, and
 * [rejected] the new, not-yet-present ones the cap turned away. A label the photo already had is neither
 * added nor rejected.
 */
data class LabelMerge(val labels: List<String>, val added: List<String>, val rejected: List<String>) {
    val hitCap: Boolean get() = rejected.isNotEmpty()
}

/** Why a single label can or cannot go onto a photo. */
enum class LabelAddCheck { OK, DUPLICATE, FULL, INVALID }

/**
 * The rules for a free-text photo label: at most [MAX_LABELS] per photo, each at most [MAX_LENGTH]
 * characters, spaces allowed, a comma always a separator and never part of a label. Two labels are the
 * same label when their [fold]s match (case- and accent-insensitive, the same folding search uses), and
 * the first spelling seen is the one kept for display.
 */
object LabelNormalizer {

    const val MAX_LABELS = 5
    const val MAX_LENGTH = 24
    const val SEPARATOR = ','

    /** Any run of whitespace, separator space or control character, collapsed to one space. */
    private val BLANKS = Regex("[\\s\\p{Z}\\p{Cc}]+")

    /**
     * One label made clean: blanks collapsed to single spaces, trimmed, cut to [MAX_LENGTH] code points
     * (never splitting a surrogate pair) and trimmed again. Null when nothing matchable is left or when
     * the text holds a [SEPARATOR], which a single label may not contain.
     */
    fun clean(raw: String): String? {
        if (SEPARATOR in raw) return null
        val collapsed = raw.replace(BLANKS, " ").trim()
        if (collapsed.isEmpty()) return null
        val cut = if (collapsed.codePointCount(0, collapsed.length) <= MAX_LENGTH) {
            collapsed
        } else {
            collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_LENGTH)).trimEnd()
        }
        // A label of nothing but combining marks folds to an empty key, which could never be matched.
        return cut.takeIf { it.isNotEmpty() && fold(it).isNotEmpty() }
    }

    /** The comparison key of [label]: blanks collapsed, trimmed, lower-cased and stripped of accents. */
    fun fold(label: String): String = SearchFilter.fold(label.replace(BLANKS, " ").trim())

    /** Splits [raw] on [SEPARATOR] into clean, distinct labels, capped at [MAX_LABELS]. */
    fun parse(raw: String): ParsedLabels = normalize(raw.split(SEPARATOR))

    /** [parse] for labels that arrive already split: each is cleaned, duplicates dropped, then capped. */
    fun normalize(labels: List<String>): ParsedLabels {
        val distinct = distinctClean(labels)
        return ParsedLabels(distinct.take(MAX_LABELS), distinct.drop(MAX_LABELS))
    }

    /** Adds [incoming] after [existing], skipping labels the photo already has, until [MAX_LABELS]. */
    fun merge(existing: List<String>, incoming: List<String>): LabelMerge {
        val kept = distinctClean(existing).take(MAX_LABELS)
        val seen = kept.mapTo(HashSet()) { fold(it) }
        val result = kept.toMutableList()
        val added = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        for (label in distinctClean(incoming)) {
            if (!seen.add(fold(label))) continue
            if (result.size < MAX_LABELS) {
                result += label
                added += label
            } else {
                rejected += label
            }
        }
        return LabelMerge(result, added, rejected)
    }

    /** Whether [label] could be added to a photo that carries [existing]. */
    fun canAdd(existing: List<String>, label: String): LabelAddCheck {
        val clean = clean(label) ?: return LabelAddCheck.INVALID
        val key = fold(clean)
        val current = distinctClean(existing)
        return when {
            current.any { fold(it) == key } -> LabelAddCheck.DUPLICATE
            current.size >= MAX_LABELS -> LabelAddCheck.FULL
            else -> LabelAddCheck.OK
        }
    }

    private fun distinctClean(labels: List<String>): List<String> {
        val seen = HashSet<String>()
        return labels.mapNotNull { raw -> clean(raw)?.takeIf { seen.add(fold(it)) } }
    }
}
