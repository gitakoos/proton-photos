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

package eu.akoos.photos.data.repository.drive

import eu.akoos.photos.data.db.dao.LinkTags

/** The PhotoTag id Drive keeps a favourite under. */
internal const val FAVORITE_TAG = 0

/**
 * The tag CSV each stored row needs so the favourites match [favoriteLinkIds], the volume's favourite-filtered
 * listing: a listed row ([listedRows], the stored rows of those links) gains the tag, and a row holding it
 * that the listing no longer names ([rowsWithTag], only rows the listing speaks for) loses it. Rows already
 * right are left out, so an unchanged library writes nothing.
 *
 * Needed because Drive's change feed carries no event when another device favourites or unfavourites an
 * existing photo, so without it such a change waited for the next full listing.
 */
internal fun favoriteTagWrites(
    favoriteLinkIds: Set<String>,
    listedRows: List<LinkTags>,
    rowsWithTag: List<LinkTags>,
): Map<String, String> {
    val writes = LinkedHashMap<String, String>()
    for (row in listedRows) {
        val tags = parseTags(row.tagsCsv)
        if (FAVORITE_TAG !in tags) writes[row.linkId] = (tags + FAVORITE_TAG).sorted().joinToString(",")
    }
    for (row in rowsWithTag) {
        if (row.linkId in favoriteLinkIds) continue
        writes[row.linkId] = (parseTags(row.tagsCsv) - FAVORITE_TAG).sorted().joinToString(",")
    }
    return writes
}

private fun parseTags(csv: String): Set<Int> =
    if (csv.isEmpty()) emptySet() else csv.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
