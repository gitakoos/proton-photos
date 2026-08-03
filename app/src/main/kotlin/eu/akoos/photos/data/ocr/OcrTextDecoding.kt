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

/*
 * The alphabet layout and the CTC collapse below are derived from mobile_ocr, which is MIT licensed:
 *
 *   Copyright (c) 2025 Laurens Priem
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, subject to the conditions of the MIT License.
 */

package eu.akoos.photos.data.ocr

/**
 * What the reader made of one crop. [confidence] is the mean of the per-character probabilities and
 * is a different quantity from the detector's own score, which says how sure it was that there was
 * text there at all.
 */
data class DecodedText(val text: String, val confidence: Float)

/**
 * The index the network emits for "nothing here". CTC needs a symbol that separates two identical
 * characters in a row, and index zero is it; it never reaches the output.
 */
const val CTC_BLANK_INDEX = 0

/**
 * The alphabet the reader's output indices point into, built from the shipped dictionary's [lines].
 *
 * Two entries surround the file's own contents and both are load-bearing. Index zero is the CTC
 * blank, which shifts every real character up by one, and a space is appended at the end because the
 * dictionary does not carry one and a line of text without it reads as a single run-on word. Getting
 * either wrong does not fail loudly: every photo simply comes back as the wrong characters.
 */
fun buildCharacterDictionary(lines: List<String>): List<String> = buildList(lines.size + 2) {
    add("")
    addAll(lines)
    add(" ")
}

/**
 * Collapses one crop's per-column argmax into text.
 *
 * The network answers per column of the squeezed crop, so a single wide letter lights up several
 * columns in a row and a gap lights up the blank. CTC's rule is therefore: take each run of one
 * repeated index as one character, and drop the blank entirely. That is what lets a genuine double
 * letter survive, because the network puts a blank column between the two halves and the run breaks
 * there.
 *
 * [indices] and [probabilities] are read up to the shorter of the two. An index past the end of
 * [dictionary] is a character this build has no name for; the run is consumed but nothing is
 * emitted, rather than reading on as if the column belonged to the next character.
 */
fun ctcDecode(indices: IntArray, probabilities: FloatArray, dictionary: List<String>): DecodedText {
    val columns = minOf(indices.size, probabilities.size)
    if (columns == 0) return DecodedText("", 0f)

    val text = StringBuilder()
    var probabilitySum = 0.0
    var characters = 0

    var column = 0
    while (column < columns) {
        val index = indices[column]
        if (index == CTC_BLANK_INDEX) {
            column++
            continue
        }

        var end = column
        var runSum = 0.0
        while (end < columns && indices[end] == index) {
            runSum += probabilities[end]
            end++
        }

        if (index in dictionary.indices) {
            text.append(dictionary[index])
            probabilitySum += runSum / (end - column)
            characters++
        }
        column = end
    }

    val confidence = if (characters > 0) (probabilitySum / characters).toFloat() else 0f
    return DecodedText(text.toString(), confidence)
}
