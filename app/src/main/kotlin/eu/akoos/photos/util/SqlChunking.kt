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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Slice width for a collection headed into a Room `IN (:param)` clause.
 *
 * Room binds one SQLite host variable per element, and SQLite caps host variables per statement:
 * 999 below Android 12, 32766 from Android 12 on (where the bundled SQLite crossed 3.32). The floor
 * is what matters, since this app runs from Android 8. A user-sized list (a large album's whole
 * membership, a select-all) otherwise fails the entire statement with `too many SQL variables`, and
 * it fails per device rather than everywhere. 500 clears the 999 floor with room to spare for the
 * query's other bound parameters.
 */
internal const val SQL_CHUNK_SIZE = 500

/**
 * Runs [action] over [SQL_CHUNK_SIZE]-sized slices of the receiver, for statements whose result is
 * not needed (a delete, an update). A chunk that throws propagates, so a caller that must survive a
 * partial failure wraps the whole call rather than each slice.
 */
suspend fun <T> Collection<T>.forEachSqlChunk(action: suspend (List<T>) -> Unit) {
    if (isEmpty()) return
    for (chunk in chunked(SQL_CHUNK_SIZE)) action(chunk)
}

/**
 * Runs [query] over [SQL_CHUNK_SIZE]-sized slices and concatenates the rows. The result carries the
 * chunks in receiver order; a caller that needs the query's own ordering re-sorts, exactly as it
 * would when a single statement's rows come back needing a domain sort.
 */
suspend fun <T, R> Collection<T>.flatMapSqlChunks(query: suspend (List<T>) -> List<R>): List<R> {
    if (isEmpty()) return emptyList()
    val rows = ArrayList<R>(size)
    for (chunk in chunked(SQL_CHUNK_SIZE)) rows += query(chunk)
    return rows
}

/**
 * Observe-style counterpart: opens one [source] flow per slice and merges their latest emissions
 * into a single list.
 *
 * [comparator] must reproduce the chunked query's own `ORDER BY`. Merging per-slice results yields
 * chunk-major order, which the query never declared, so the collector would see rows in an order
 * that depends on how the input happened to be sliced.
 *
 * Two shapes are handled separately because `combine` cannot express them. Over an empty array of
 * flows it never emits at all, so an empty receiver is answered with one empty list instead of a
 * screen that waits forever. A single slice needs no merge, so its flow is returned untouched and
 * keeps the statement's exact ordering.
 */
fun <T, R> Collection<T>.combineSqlChunks(
    comparator: Comparator<in R>,
    source: (List<T>) -> Flow<List<R>>,
): Flow<List<R>> {
    val chunks = chunked(SQL_CHUNK_SIZE)
    return when (chunks.size) {
        0 -> flowOf(emptyList())
        1 -> source(chunks.first())
        else -> combine(chunks.map(source)) { parts -> parts.flatMap { it }.sortedWith(comparator) }
    }
}
