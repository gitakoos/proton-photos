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

import eu.akoos.photos.data.db.entity.ClusterSummaryEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pins the person page's centroid source: when a cached centroid may stand in for a person's mean face,
 * that a person's own faces sum to exactly the centroid the page computed from the whole face table, and
 * how the merge suggestion's candidates are ranked and cut at the suggestion bar.
 */
class PersonCentroidsTest {

    private fun pack(v: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buffer.putFloat(x)
        return buffer.array()
    }

    private fun unit(random: Random) = normalize(FloatArray(FACE_EMBEDDING_DIM) { random.nextFloat() - 0.5f })

    private fun summary(
        centroid: ByteArray = pack(unit(Random(1))),
        memberCount: Int = 12,
        modelVersion: Int = FACE_MODEL_VERSION,
    ) = ClusterSummaryEntity(personId = 3L, userId = "acct", centroid = centroid, memberCount = memberCount, modelVersion = modelVersion)

    @Test
    fun `a current summary that still counts the person's faces stands in for the mean face`() {
        val c = unit(Random(1))
        assertArrayEquals(c, usableCachedCentroid(summary(centroid = pack(c)), keptFaces = 12), 0f)
    }

    @Test
    fun `no summary, an older pipeline's, a stale width or a drifted count is not used`() {
        assertNull(usableCachedCentroid(null, keptFaces = 12))
        assertNull(usableCachedCentroid(summary(modelVersion = FACE_MODEL_VERSION - 1), keptFaces = 12))
        assertNull(usableCachedCentroid(summary(centroid = ByteArray(128 * 4)), keptFaces = 12))
        assertNull(usableCachedCentroid(summary(memberCount = 12), keptFaces = 11))
    }

    /** The person page's whole-table centroid for one person's faces, as it was computed before. */
    private fun wholeTableCentroid(embeddings: List<ByteArray>): FloatArray? {
        var acc: DoubleArray? = null
        for (bytes in embeddings) {
            val e = unpackEmbedding(bytes)
            if (e.size != FACE_EMBEDDING_DIM) continue
            val sum = acc ?: DoubleArray(e.size).also { acc = it }
            for (i in e.indices) sum[i] += e[i]
        }
        val sum = acc ?: return null
        var norm = 0.0
        for (v in sum) norm += v * v
        val inv = if (norm > 0.0) 1.0 / sqrt(norm) else 0.0
        return FloatArray(sum.size) { (sum[it] * inv).toFloat() }
    }

    @Test
    fun `a person's faces sum to the whole-table centroid bit for bit`() {
        val random = Random(9)
        val faces = List(300) { if (it % 37 == 0) ByteArray(12) else pack(unit(random)) }
        val sum = CentroidSum()
        faces.forEach { sum.add(it) }
        val expected = wholeTableCentroid(faces)!!
        val actual = sum.centroid()!!
        for (i in expected.indices) assertEquals(expected[i].toRawBits(), actual[i].toRawBits())
    }

    @Test
    fun `a person with no readable vector has no centroid`() {
        val sum = CentroidSum()
        sum.add(ByteArray(0))
        sum.add(ByteArray(8))
        assertNull(sum.centroid())
    }

    @Test
    fun `candidates rank most alike first, above the bar only, skipping the person and Unsorted`() {
        val target = FloatArray(FACE_EMBEDDING_DIM).also { it[0] = 1f }
        fun at(cos: Float) = FloatArray(FACE_EMBEDDING_DIM).also {
            it[0] = cos
            it[1] = sqrt(1f - cos * cos)
        }
        val centroids = mapOf(
            1L to target,
            2L to at(0.50f),
            3L to at(0.90f),
            4L to at(0.70f),
            5L to at(0.95f),
            6L to at(0.30f),
        )
        val ranked = rankSuggestionCandidates(target, centroids, skip = setOf(1L, 5L), threshold = 0.45f)
        assertEquals(listOf(3L, 4L, 2L), ranked)
    }

    @Test
    fun `a candidate exactly at the bar is not offered`() {
        val target = FloatArray(FACE_EMBEDDING_DIM).also { it[0] = 1f }
        val at = FloatArray(FACE_EMBEDDING_DIM).also { it[0] = 0.5f }
        assertEquals(emptyList<Long>(), rankSuggestionCandidates(target, mapOf(2L to at), emptySet(), threshold = 0.5f))
    }
}
