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

package eu.akoos.photos.domain.usecase

/**
 * A hand-made photo order inside an album or a device folder: the arrangement is a list of photo keys in display
 * order, and every function here returns a new list, so an arrange session can keep the order it started from for
 * Cancel. Pure, so it is unit-tested directly; the desktop client draws the same order from the same rule.
 */
object PhotoArrangement {

    /**
     * [items] in the order [arrangement] gives: first every item the arrangement places, in its order, then the
     * items it does not know (added since the last save, or saved on a device that never saw them) by [timeOf]
     * ascending, ties on [idOf]. A key of the arrangement no item has (a photo removed, hidden or not listed yet) is
     * skipped and keeps its slot for the day it comes back.
     */
    fun <T> ordered(items: List<T>, arrangement: List<String>, idOf: (T) -> String, timeOf: (T) -> Long): List<T> {
        if (items.isEmpty()) return items
        val rank = rankOf(arrangement)
        val placed = ArrayList<Pair<Int, T>>()
        val fresh = ArrayList<T>()
        for (item in items) {
            val at = rank[idOf(item)]
            if (at != null) placed += at to item else fresh += item
        }
        placed.sortBy { it.first }
        val out = ArrayList<T>(items.size)
        placed.mapTo(out) { it.second }
        if (fresh.isNotEmpty()) {
            out += PhotoSortOrderRule.ascending(fresh, timeOf, idOf)
        }
        return out
    }

    /** [order] with the [moving] keys taken out and put first, keeping their order among themselves. */
    fun moveToStart(order: List<String>, moving: Set<String>): List<String> {
        if (moving.isEmpty()) return order
        val (block, rest) = order.partition { it in moving }
        return block + rest
    }

    /** [order] with the [moving] keys taken out and put last, keeping their order among themselves. */
    fun moveToEnd(order: List<String>, moving: Set<String>): List<String> {
        if (moving.isEmpty()) return order
        val (block, rest) = order.partition { it in moving }
        return rest + block
    }

    /** [order] with the [moving] keys put right before [target]; a target that moves itself changes nothing. */
    fun moveBefore(order: List<String>, moving: Set<String>, target: String): List<String> =
        insertAround(order, moving, target, after = false)

    /** [order] with the [moving] keys put right after [target]; a target that moves itself changes nothing. */
    fun moveAfter(order: List<String>, moving: Set<String>, target: String): List<String> =
        insertAround(order, moving, target, after = true)

    /**
     * The [moving] keys gathered into one block where [anchor], the photo the finger holds, stands, so a drag
     * carries a scattered selection as one run. The block keeps the keys' order among themselves.
     */
    fun gather(order: List<String>, moving: Set<String>, anchor: String): List<String> {
        if (moving.size < 2 || anchor !in moving) return order
        val block = order.filter { it in moving }
        val out = ArrayList<String>(order.size)
        for (key in order) {
            if (key == anchor) out += block else if (key !in moving) out += key
        }
        return out
    }

    /**
     * Where a dragged block lands when the finger is over [target]: before it when the target stands before the
     * block, after it otherwise, the way a list makes room as the finger passes each cell. Over the block itself, or
     * over a key the order does not hold, nothing changes.
     */
    fun dragTo(order: List<String>, moving: Set<String>, target: String): List<String> {
        if (target in moving || moving.isEmpty()) return order
        val targetAt = order.indexOf(target)
        val blockAt = order.indexOfFirst { it in moving }
        if (targetAt < 0 || blockAt < 0) return order
        return insertAround(order, moving, target, after = targetAt > blockAt)
    }

    /**
     * The whole arrangement to store once a session ends with Done. [stored] is the arrangement held before and
     * [arranged] the photos the session showed, in the order the user left them. A stored key the session did not
     * show (a hidden photo, or one this device has not listed) keeps its slot, the shown photos take the slots the
     * shown ones held in their new order, and a shown photo the stored list never had joins in its place. With
     * [members] given, a key that is neither a member nor shown is dropped: the photo left the album.
     */
    fun forSave(stored: List<String>, arranged: List<String>, members: Set<String>? = null): List<String> {
        val shown = arranged.distinct()
        val shownSet = shown.toHashSet()
        val kept = stored.distinct().filter { members == null || it in members || it in shownSet }
        val keptSet = kept.toHashSet()
        return mergeArrangement(kept + shown.filterNot { it in keptSet }, shown)
    }

    private fun insertAround(order: List<String>, moving: Set<String>, target: String, after: Boolean): List<String> {
        if (moving.isEmpty() || target in moving || target !in order) return order
        val block = order.filter { it in moving }
        if (block.isEmpty()) return order
        val out = ArrayList<String>(order.size)
        for (key in order) {
            if (key in moving) continue
            if (key == target) {
                if (after) {
                    out += key
                    out += block
                } else {
                    out += block
                    out += key
                }
            } else {
                out += key
            }
        }
        return out
    }

    private fun rankOf(arrangement: List<String>): HashMap<String, Int> {
        val rank = HashMap<String, Int>(arrangement.size * 2)
        arrangement.forEachIndexed { index, key -> rank.putIfAbsent(key, index) }
        return rank
    }
}

/** Oldest first with an ascending key tie-break, each key read once per item. */
internal object PhotoSortOrderRule {
    fun <T> ascending(items: List<T>, timeOf: (T) -> Long, idOf: (T) -> String): List<T> {
        if (items.size < 2) return items
        val times = LongArray(items.size) { timeOf(items[it]) }
        val ids = Array(items.size) { idOf(items[it]) }
        return (items.indices).sortedWith { a, b ->
            val byTime = times[a].compareTo(times[b])
            if (byTime != 0) byTime else ids[a].compareTo(ids[b])
        }.map { items[it] }
    }
}
