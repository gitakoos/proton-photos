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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Proves the pure clustering rule the people grouping relies on: well-separated groups stay apart,
 * near-identical crops collapse, a moderately similar but distinct face is held out by the precision
 * floor, a weak crop needs a closer match to join, and a person split by arrival order is folded back
 * together by the merge pass. Vectors are built by hand and L2-normalised so a dot product is a cosine.
 */
class FaceClusteringTest {

    private fun unit(vararg values: Float): FloatArray {
        var sumSq = 0.0
        for (v in values) sumSq += v.toDouble() * v
        val norm = sqrt(sumSq).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }

    /** Wraps bare vectors as confident [FaceSample]s (equal score unless [scores] is given). */
    private fun cluster(
        vectors: List<FloatArray>,
        threshold: Float = FACE_CLUSTER_THRESHOLD,
        scores: List<Float>? = null,
    ): IntArray = clusterFaces(
        vectors.mapIndexed { i, v -> FaceSample(v, scores?.get(i) ?: 1f) },
        threshold,
    )

    private fun clusterCount(assignment: IntArray): Int = assignment.toSet().size

    @Test
    fun two_well_separated_groups_form_exactly_two_clusters() {
        val a1 = unit(1f, 0f, 0f)
        val a2 = unit(0.98f, 0.02f, 0f)
        val a3 = unit(0.97f, 0f, 0.03f)
        val b1 = unit(0f, 1f, 0f)
        val b2 = unit(0.02f, 0.98f, 0f)
        val b3 = unit(0f, 0.99f, 0.02f)

        val assignment = cluster(listOf(a1, b1, a2, b2, a3, b3))

        assertEquals("two identities give two clusters", 2, clusterCount(assignment))
        assertEquals("a2 joins a1", assignment[0], assignment[2])
        assertEquals("a3 joins a1", assignment[0], assignment[4])
        assertEquals("b2 joins b1", assignment[1], assignment[3])
        assertEquals("b3 joins b1", assignment[1], assignment[5])
        assertTrue("the two identities stay apart", assignment[0] != assignment[1])
    }

    @Test
    fun a_near_identical_vector_joins_the_existing_cluster() {
        val v = unit(0.30f, 0.60f, 0.75f)
        val nearlyV = unit(0.31f, 0.59f, 0.74f)

        val assignment = cluster(listOf(v, nearlyV))

        assertEquals("a near-duplicate does not open a second cluster", 1, clusterCount(assignment))
        assertEquals(assignment[0], assignment[1])
    }

    @Test
    fun two_orthogonal_vectors_form_two_clusters() {
        val assignment = cluster(listOf(unit(1f, 0f), unit(0f, 1f)))
        assertEquals("a zero dot is below the threshold", 2, clusterCount(assignment))
        assertTrue(assignment[0] != assignment[1])
    }

    @Test
    fun moderately_similar_faces_stay_apart_at_the_precision_threshold() {
        val v = unit(1f, 0f, 0f)
        val w = unit(0.40f, 0.917f, 0f) // cosine to v is about 0.40

        assertEquals("cosine 0.40 is below the precision floor", 2, clusterCount(cluster(listOf(v, w))))
        assertEquals(
            "the same pair collapses once the floor drops to 0.3",
            1,
            clusterCount(cluster(listOf(v, w), threshold = 0.3f)),
        )
    }

    @Test
    fun a_weak_face_needs_a_closer_match_to_join() {
        // Cosine 0.54 sits between the 0.5 confident floor and the 0.58 weak floor. The merge is
        // switched off (a high floor) so the join rule alone decides.
        val anchor = unit(1f, 0f, 0f)
        val candidate = unit(0.54f, 0.842f, 0f)

        assertEquals(
            "a confident face clears the 0.5 floor and joins",
            1,
            clusterCount(clusterFaces(listOf(FaceSample(anchor, 0.9f, true), FaceSample(candidate, 0.8f, true)), 0.5f, 0.99f)),
        )
        assertEquals(
            "a weak face must clear the stricter bar, so it stays apart",
            2,
            clusterCount(clusterFaces(listOf(FaceSample(anchor, 0.9f, true), FaceSample(candidate, 0.8f, false)), 0.5f, 0.99f)),
        )
    }

    @Test
    fun a_merge_pass_folds_one_person_split_by_arrival_order_back_together() {
        // One identity whose crops arrive out of order: a1 seeds, a3 opens a second cluster, a2 joins
        // a1. The two clusters' mean directions are close, so the merge folds them into one person.
        val a1 = unit(1f, 0f, 0f)
        val a2 = unit(0.9f, 0.44f, 0f)
        val a3 = unit(0.44f, 0.9f, 0f)
        val vecs = listOf(a1, a2, a3)
        val scores = listOf(0.9f, 0.7f, 0.8f)
        fun run(mergeThreshold: Float) = clusterFaces(
            vecs.mapIndexed { i, v -> FaceSample(v, scores[i]) }, 0.5f, mergeThreshold,
        )

        assertEquals("arrival order alone splits the person in two", 2, clusterCount(run(0.99f)))
        assertEquals("the merge folds them back into one", 1, clusterCount(run(FACE_CLUSTER_MERGE_THRESHOLD)))
    }

    @Test
    fun a_named_person_keeps_its_name_when_its_faces_stay_in_one_cluster() {
        val result = carryNamesToClusters(
            oldPersonIdPerFace = listOf(5L, 5L, 5L),
            clusterPerFace = intArrayOf(2, 2, 2),
            names = mapOf(5L to "Ákos"),
        )
        assertEquals(mapOf(2 to "Ákos"), result)
    }

    @Test
    fun a_split_person_keeps_its_name_on_the_larger_fragment() {
        // Person 5's four faces land three in cluster 0 and one in cluster 1; the name follows the
        // majority so it does not chase the stray face into cluster 1.
        val result = carryNamesToClusters(
            oldPersonIdPerFace = listOf(5L, 5L, 5L, 5L),
            clusterPerFace = intArrayOf(0, 0, 0, 1),
            names = mapOf(5L to "Ákos"),
        )
        assertEquals(mapOf(0 to "Ákos"), result)
    }

    @Test
    fun two_people_merged_into_one_cluster_do_not_both_claim_it() {
        // Persons 5 (three faces) and 6 (one face) both land in cluster 0. The larger claims the name;
        // the smaller has nowhere else to go, so its name drops rather than mislabel the cluster.
        val result = carryNamesToClusters(
            oldPersonIdPerFace = listOf(5L, 5L, 5L, 6L),
            clusterPerFace = intArrayOf(0, 0, 0, 0),
            names = mapOf(5L to "Ákos", 6L to "Béla"),
        )
        assertEquals(mapOf(0 to "Ákos"), result)
    }

    @Test
    fun two_named_people_in_separate_clusters_each_keep_their_name() {
        val result = carryNamesToClusters(
            oldPersonIdPerFace = listOf(5L, 6L),
            clusterPerFace = intArrayOf(0, 1),
            names = mapOf(5L to "Ákos", 6L to "Béla"),
        )
        assertEquals(mapOf(0 to "Ákos", 1 to "Béla"), result)
    }

    @Test
    fun an_unnamed_or_unknown_face_produces_no_name() {
        val result = carryNamesToClusters(
            oldPersonIdPerFace = listOf(null, 9L),
            clusterPerFace = intArrayOf(0, 1),
            names = mapOf(5L to "Ákos"),
        )
        assertTrue("no named person's faces are present", result.isEmpty())
    }

    @Test
    fun a_confirmed_face_moves_into_the_cluster_carrying_its_name() {
        val v = unit(1f, 0f, 0f)
        val result = attractToNamedClusters(
            embeddings = listOf(v, v, v),
            scores = floatArrayOf(1f, 1f, 1f),
            assignment = intArrayOf(0, 2, 2),
            clusterName = mapOf(0 to null, 2 to "Ákos"),
            manualName = listOf("Ákos", null, null),
            threshold = FACE_CLUSTER_THRESHOLD,
        )
        assertEquals("the confirmed face joins the cluster carrying its name", 2, result[0])
    }

    @Test
    fun an_unnamed_face_matching_a_confirmed_person_is_pulled_in() {
        val v = unit(1f, 0f, 0f)
        val near = unit(0.9f, 0.436f, 0f) // cosine to v about 0.9, above the floor
        val result = attractToNamedClusters(
            embeddings = listOf(v, near),
            scores = floatArrayOf(1f, 1f),
            assignment = intArrayOf(0, 1),
            clusterName = mapOf(0 to "Ákos", 1 to null),
            manualName = listOf("Ákos", null),
            threshold = FACE_CLUSTER_THRESHOLD,
        )
        assertEquals("a close unnamed face is pulled onto the confirmed person", 0, result[1])
    }

    @Test
    fun a_distant_face_is_not_pulled_onto_a_confirmed_person() {
        val v = unit(1f, 0f, 0f)
        val far = unit(0.2f, 0.98f, 0f) // cosine to v about 0.2, below the floor
        val result = attractToNamedClusters(
            embeddings = listOf(v, far),
            scores = floatArrayOf(1f, 1f),
            assignment = intArrayOf(0, 1),
            clusterName = mapOf(0 to "Ákos", 1 to null),
            manualName = listOf("Ákos", null),
            threshold = FACE_CLUSTER_THRESHOLD,
        )
        assertEquals("a distant face stays where the base pass put it", 1, result[1])
    }

    @Test
    fun suggests_each_unnamed_cluster_to_its_closest_named_person_above_the_floor() {
        val a = unit(1f, 0f, 0f)
        val b = unit(0f, 1f, 0f)
        val named = listOf(10L to a, 20L to b)
        val unnamed = listOf(
            30L to unit(0.95f, 0.31f, 0f), // close to A
            40L to unit(0.10f, 0.99f, 0f), // close to B
            50L to unit(0.30f, 0.30f, 0.90f), // far from both, below the floor
        )
        val res = suggestPersonMatches(named, unnamed, FACE_SUGGEST_THRESHOLD)
        val to = res.associate { it.second to it.first }
        assertEquals("only the two close clusters are offered", 2, res.size)
        assertEquals("cluster 30 is offered as person A", 10L, to[30L])
        assertEquals("cluster 40 is offered as person B", 20L, to[40L])
        assertTrue("the far cluster is not offered", 50L !in to)
    }

    @Test
    fun with_no_confirmed_people_the_assignment_is_unchanged() {
        val a = intArrayOf(0, 1, 0, 2)
        val result = attractToNamedClusters(
            embeddings = listOf(unit(1f, 0f), unit(0f, 1f), unit(1f, 0f), unit(1f, 1f)),
            scores = floatArrayOf(1f, 1f, 1f, 1f),
            assignment = a,
            clusterName = mapOf(0 to null, 1 to null, 2 to null),
            manualName = listOf(null, null, null, null),
            threshold = FACE_CLUSTER_THRESHOLD,
        )
        assertArrayEquals("no named clusters means nothing moves", a, result)
    }

    @Test
    fun the_same_input_always_yields_the_same_assignment() {
        val input = listOf(
            unit(1f, 0f, 0f),
            unit(0f, 1f, 0f),
            unit(0.95f, 0.05f, 0f),
            unit(0f, 0f, 1f),
            unit(0.02f, 0.98f, 0f),
        )
        assertArrayEquals(cluster(input), cluster(input))
    }

    @Test
    fun unpack_reads_the_little_endian_floats_the_indexer_packed() {
        val vector = floatArrayOf(0.5f, -0.25f, 1.5f, 0f)
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        assertArrayEquals(vector, unpackEmbedding(buffer.array()), 0f)
    }
}
