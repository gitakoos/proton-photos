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

/**
 * Pure helpers for the categories a user picks for a device photo, persisted as a comma-separated
 * list of Drive PhotoTag ids in `local_tag.userTagsCsv`.
 *
 * That column is separate from the scanner's `tagsCsv` because the two answer different questions.
 * The scanner's column is a cache it may rebuild whenever it likes: a file whose size or modified
 * time drifts is re-detected, and a [PhotoTagDetector.DETECTOR_VERSION] bump discards every
 * detection at once. This column is the only record of an answer a person gave, and nothing can
 * re-derive it, so it must survive both.
 *
 * Valid ids are the Drive PhotoTag enum, 0 to 9; the server rejects anything outside that range.
 * An out-of-range id is therefore dropped rather than stored or surfaced, on both the encode and
 * the decode side: it can only reach here from a damaged row, and dropping the one id keeps the
 * rest of the file's choice usable, where refusing the whole value would lose those too.
 *
 * Centralised so the write side and the read side share one encoding and can be verified without a
 * device.
 */
object UserPhotoTags {

    /** The Drive PhotoTag id range. Nothing outside it is storable. */
    val VALID_IDS: IntRange = 0..9

    /** Flatten the ids a user chose into the persisted CSV: in-range ids only, deduplicated, and in
     *  ascending order so the same choice always encodes to the same string. */
    fun encode(ids: Collection<Int>): String =
        ids.filter { it in VALID_IDS }.distinct().sorted().joinToString(",")

    /** Parse the persisted CSV back into the chosen id set, dropping blank, non-numeric and
     *  out-of-range entries. Never throws: a damaged value degrades to the ids it can still
     *  account for rather than costing the user the whole row. */
    fun decode(csv: String?): Set<Int> {
        if (csv.isNullOrEmpty()) return emptySet()
        val out = LinkedHashSet<Int>()
        for (part in csv.split(',')) {
            val id = part.trim().toIntOrNull() ?: continue
            if (id in VALID_IDS) out += id
        }
        return out
    }
}
