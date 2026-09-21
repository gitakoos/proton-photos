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
 * Move the album at [from] to [to], sliding the albums between them one slot the other way.
 *
 * [to] is the index the moved album ends up at in the returned list, in both directions: moving 0
 * to 2 in `[a, b, c, d]` gives `[b, c, a, d]`, and moving 3 to 1 gives `[a, d, b, c]`. Removing
 * before inserting is what makes that hold going down the list, where taking the album out has
 * already shifted every later index back by one.
 *
 * An out-of-range index or a move onto the album's own slot returns [order] untouched, so a drag
 * that ends over the Memories cell, a device folder or the gap between two cards leaves the
 * arrangement exactly as it was rather than dropping or duplicating an entry.
 */
fun moveInArrangement(order: List<String>, from: Int, to: Int): List<String> {
    if (from == to || from !in order.indices || to !in order.indices) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}
