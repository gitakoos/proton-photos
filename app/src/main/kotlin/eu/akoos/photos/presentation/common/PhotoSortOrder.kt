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

package eu.akoos.photos.presentation.common

import eu.akoos.photos.domain.entity.GalleryItem

/**
 * The one direction rule every photo list is arranged by: effective capture time, newest or oldest
 * first, with an ascending stable-id tie-breaker.
 *
 * The tie-breaker keeps the order total, so a burst of identical timestamps holds its arrangement
 * between a chunked emission and the final paint instead of reshuffling. It runs the same way in
 * both directions on purpose: turning it around as well would rearrange such a burst on a direction
 * change for no reason the user could read.
 */
object PhotoSortOrder {

    /**
     * [items] by [timeOf], newest or oldest first per [newestFirst], tie-broken on [idOf] ascending.
     *
     * Each key is read once per item rather than once per comparison: on the lists this backs both
     * are computed getters over a library-sized collection, and a comparator that calls them
     * re-derives every key O(n log n) times.
     */
    fun <T> ordered(
        items: List<T>,
        newestFirst: Boolean,
        timeOf: (T) -> Long,
        idOf: (T) -> String,
    ): List<T> {
        val n = items.size
        if (n < 2) return items
        val times = LongArray(n)
        val ids = arrayOfNulls<String>(n)
        for (i in 0 until n) {
            times[i] = timeOf(items[i])
            ids[i] = idOf(items[i])
        }
        val order = (0 until n).sortedWith(
            Comparator { a, b ->
                val byTime =
                    if (newestFirst) times[b].compareTo(times[a]) else times[a].compareTo(times[b])
                if (byTime != 0) byTime else ids[a]!!.compareTo(ids[b]!!)
            },
        )
        return order.map { items[it] }
    }

    /**
     * [items] in that order on the keys a [GalleryItem] already carries: the sanity-floored capture
     * time every month header, scrubber and scroll pill reads, and the stable id the grids key their
     * cells by.
     *
     * Going through `captureTimeMs` rather than a raw per-state timestamp is what keeps a list and
     * its month headings agreeing: a synced photo whose device DATE_TAKEN differs from its Drive
     * capture time resolves to one value here and in the header it lands under.
     */
    fun ordered(items: List<GalleryItem>, newestFirst: Boolean): List<GalleryItem> =
        ordered(items, newestFirst, { it.captureTimeMs }, { it.stableId })
}
