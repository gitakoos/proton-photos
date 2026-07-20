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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the slicing that keeps a user-sized list out of a single Room `IN (:param)` statement. The
 * failure it guards against is `too many SQL variables`, which only fires on devices whose bundled
 * SQLite still caps host variables at 999 and only once a library or a selection is big enough, so
 * the boundaries are verified here rather than waiting for the device that happens to hit them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SqlChunkingTest {

    @Test
    fun the_chunk_size_clears_the_lowest_host_variable_cap() {
        assertTrue(
            "a chunk plus a query's other bound parameters must stay under SQLite's 999 floor",
            SQL_CHUNK_SIZE in 1 until 999,
        )
    }

    @Test
    fun chunk_counts_are_exact_at_the_boundary() = runTest {
        val expected = mapOf(0 to 0, 1 to 1, 499 to 1, 500 to 1, 501 to 2, 1001 to 3)
        for ((inputSize, chunkCount) in expected) {
            val sizes = mutableListOf<Int>()
            List(inputSize) { it }.forEachSqlChunk { sizes += it.size }
            assertEquals("input of $inputSize", chunkCount, sizes.size)
            assertEquals("input of $inputSize must be fully covered", inputSize, sizes.sum())
            assertTrue("input of $inputSize must not exceed the cap", sizes.all { it <= SQL_CHUNK_SIZE })
        }
    }

    @Test
    fun the_aggregating_helper_concatenates_in_input_order() = runTest {
        val input = List(1001) { "link-$it" }
        val chunkSizes = mutableListOf<Int>()

        val rows = input.flatMapSqlChunks { chunk ->
            chunkSizes += chunk.size
            chunk.map { it.uppercase() }
        }

        assertEquals(listOf(500, 500, 1), chunkSizes)
        assertEquals("every row survives, in the order it was asked for", input.map { it.uppercase() }, rows)
    }

    @Test
    fun the_aggregating_helper_never_queries_for_an_empty_input() = runTest {
        var queries = 0
        val rows = emptyList<String>().flatMapSqlChunks { queries++; listOf("unexpected") }
        assertEquals(emptyList<String>(), rows)
        assertEquals("an empty input has nothing to look up", 0, queries)
    }

    @Test
    fun the_flow_helper_emits_one_empty_list_for_an_empty_input() = runTest {
        // combine over no flows never emits, which would leave the screen on its skeleton forever.
        val emissions = emptyList<String>()
            .combineSqlChunks<String, Int>(naturalOrder()) { error("must not be queried") }
            .toList()

        assertEquals("exactly one emission, and it is empty", listOf(emptyList<Int>()), emissions)
    }

    @Test
    fun a_single_chunk_is_passed_through_without_a_merge_wrapper() = runTest {
        // The one statement already applied its own ORDER BY, so the comparator must not touch it.
        val rows = listOf(3, 1, 2)
        var opened = 0

        val emitted = rows.combineSqlChunks(naturalOrder<Int>()) { chunk ->
            opened++
            flowOf(chunk)
        }.toList()

        assertEquals("one chunk means one underlying flow", 1, opened)
        assertEquals("the query's own ordering is left alone", listOf(listOf(3, 1, 2)), emitted)
    }

    @Test
    fun a_merged_flow_honours_the_comparator_rather_than_chunk_order() = runTest {
        // Ascending input, so plain concatenation would come back ascending. A descending comparator
        // therefore only holds if the merge really re-sorts instead of stitching the slices together.
        val ids = (1..1001).toList()
        val source: (List<Int>) -> Flow<List<Int>> = { chunk -> flowOf(chunk) }

        val merged = ids.combineSqlChunks(compareByDescending<Int> { it }, source).toList().single()

        assertEquals("no row is lost or duplicated across the slices", ids.size, merged.size)
        assertEquals(ids.reversed(), merged)
    }
}
