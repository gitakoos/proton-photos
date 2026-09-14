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

package eu.akoos.photos.data.semantic

import eu.akoos.photos.domain.usecase.SemanticHit
import eu.akoos.photos.domain.usecase.topKByCosine
import eu.akoos.photos.domain.usecase.unpackSemanticEmbedding
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the pure ranking rule semantic search relies on, with no model, database, or Android in play.
 * The query is the x-axis unit vector, so a row shaped (score, 0) has a cosine (dot product) against it
 * of exactly its first component; that lets every case pin an exact score and assert the exact result.
 */
class SemanticSearchRankingTest {

    private val query = floatArrayOf(1f, 0f)

    /** A row whose cosine against [query] is exactly [score]. */
    private fun row(key: String, score: Float): Pair<String, FloatArray> = key to floatArrayOf(score, 0f)

    private fun rank(
        rows: List<Pair<String, FloatArray>>,
        limit: Int,
        minScore: Float = 0.20f,
    ): List<SemanticHit> = topKByCosine(query, rows.asSequence(), limit, minScore)

    @Test
    fun `orders hits by descending score`() {
        val hits = rank(listOf(row("b", 0.5f), row("a", 0.9f), row("c", 0.7f)), limit = 10)
        assertEquals(listOf("a", "c", "b"), hits.map { it.photoKey })
        assertEquals(0.9f, hits.first().score, 1e-6f)
        assertEquals(0.5f, hits.last().score, 1e-6f)
    }

    @Test
    fun `keeps only the top hits up to the limit`() {
        val rows = listOf(
            row("p1", 0.9f), row("p2", 0.8f), row("p3", 0.7f), row("p4", 0.6f), row("p5", 0.5f),
        )
        val hits = rank(rows, limit = 3)
        assertEquals(3, hits.size)
        assertEquals(listOf("p1", "p2", "p3"), hits.map { it.photoKey })
    }

    @Test
    fun `drops hits below the score floor`() {
        val hits = rank(
            listOf(row("keep", 0.5f), row("edge", 0.25f), row("drop", 0.15f)),
            limit = 10,
            minScore = 0.20f,
        )
        assertEquals(listOf("keep", "edge"), hits.map { it.photoKey })
    }

    @Test
    fun `a zero vector tombstone never ranks`() {
        // The indexer writes an all-zero embedding for an undecodable photo; its cosine is 0, under any
        // positive floor, so it is never a match.
        val tombstone = "tomb" to floatArrayOf(0f, 0f)
        val hits = rank(listOf(row("real", 0.8f), tombstone), limit = 10, minScore = 0.20f)
        assertEquals(listOf("real"), hits.map { it.photoKey })
    }

    @Test
    fun `breaks score ties by photo key ascending`() {
        // Equal scores, offered out of key order: the result is deterministic all the same.
        val hits = rank(listOf(row("c", 0.5f), row("a", 0.5f), row("b", 0.5f)), limit = 10)
        assertEquals(listOf("a", "b", "c"), hits.map { it.photoKey })
    }

    @Test
    fun `resolves a tie at the limit boundary the same way whatever the input order`() {
        // One clear top hit, then three equal-score contenders for the single remaining slot. The kept
        // one is the smallest key, and it does not depend on the order the rows arrive in.
        val ascending = rank(listOf(row("top", 0.9f), row("b", 0.5f), row("c", 0.5f), row("d", 0.5f)), limit = 2)
        val descending = rank(listOf(row("top", 0.9f), row("d", 0.5f), row("c", 0.5f), row("b", 0.5f)), limit = 2)
        assertEquals(listOf("top", "b"), ascending.map { it.photoKey })
        assertEquals(listOf("top", "b"), descending.map { it.photoKey })
    }

    @Test
    fun `skips a row whose width does not match the query`() {
        // A stale-width blob unpacks to a differently sized array; it cannot be scored and is skipped
        // rather than crashing the ranking.
        val badWidth = "bad" to floatArrayOf(0.9f)
        val hits = rank(listOf(row("good", 0.6f), badWidth), limit = 10)
        assertEquals(listOf("good"), hits.map { it.photoKey })
    }

    @Test
    fun `unpacks a little-endian float32 blob back to its vector`() {
        val values = floatArrayOf(0.5f, -0.25f, 1f, 0f)
        val blob = ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putFloat(it) } }
            .array()
        val out = unpackSemanticEmbedding(blob)
        assertEquals(values.size, out.size)
        values.forEachIndexed { i, v -> assertEquals(v, out[i], 1e-7f) }
    }

    @Test
    fun `returns empty for a blob that is not a whole number of floats`() {
        // Independent of any other rail's embedding width: a corrupt or partial blob is unusable, not
        // silently mis-read, and the caller's width guard drops it.
        assertEquals(0, unpackSemanticEmbedding(ByteArray(6)).size)
        assertEquals(0, unpackSemanticEmbedding(ByteArray(0)).size)
    }
}
