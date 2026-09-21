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

import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A large-library stress benchmark for face clustering, run on the JVM with synthetic embeddings (the
 * clustering is pure float math, no detector, no ONNX, no device). It reproduces the "scan stalls near
 * 40k photos and never moves" report: the previous clusterer was O(faces x clusters) with an O(clusters^3)
 * merge and no bound, so a heavily-fragmented library (thousands of one-off/junk faces) turned it into an
 * effectively infinite hang. The current clusterer caps the cluster count and skips the runaway merge, so
 * it finishes in bounded time.
 *
 * Manual: it deliberately runs to tens of seconds, so it is @Ignore'd out of the normal suite and run on
 * demand with `--tests eu.akoos.photos.domain.usecase.FaceClusteringScaleBenchmark`.
 */
@Ignore("Manual scale benchmark, run explicitly with --tests")
class FaceClusteringScaleBenchmark {

    private val dim = FACE_EMBEDDING_DIM

    /** L2-normalise in place and return. */
    private fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x.toDouble() * x
        val inv = if (n > 0) (1.0 / sqrt(n)).toFloat() else 0f
        for (i in v.indices) v[i] *= inv
        return v
    }

    private fun randomUnit(rnd: Random): FloatArray = normalize(FloatArray(dim) { rnd.nextGaussian(0.0, 1.0).toFloat() })

    private fun Random.nextGaussian(mean: Double, sd: Double): Double {
        // Box-Muller; kotlin.random has no Gaussian.
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return mean + sd * sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    /**
     * A realistic-shape stress set: [people] real identities (a random centre plus [perPerson] noisy
     * faces that stay well within the join floor, so they form real clusters), plus [junk] one-off
     * distinct faces (random unit vectors that match nothing, each its own cluster). The junk is what
     * blows the old cluster count up into the thousands. Real faces get the higher score so they seed
     * first. Deterministic.
     */
    private fun buildDataset(people: Int, perPerson: Int, junk: Int, seed: Long): List<FaceSample> {
        val rnd = Random(seed)
        val out = ArrayList<FaceSample>(people * perPerson + junk)
        repeat(people) {
            val centre = randomUnit(rnd)
            repeat(perPerson) {
                val v = FloatArray(dim) { i -> centre[i] + rnd.nextGaussian(0.0, 0.05).toFloat() }
                out.add(FaceSample(normalize(v), score = 0.9f, confident = true))
            }
        }
        repeat(junk) { out.add(FaceSample(randomUnit(rnd), score = 0.65f, confident = true)) }
        out.shuffle(rnd)
        return out
    }

    // ---- The PREVIOUS (pre-fix) clusterer, inlined so both can run on identical data ----
    // Unbounded: no MAX_CLUSTERS cap, no cancellation check, and the merge recomputes every centroid and
    // every pair after each merge (O(clusters^3)). This is the code that hung.

    private class OldCluster(first: FloatArray, firstScore: Float) {
        val anchors = ArrayList<FloatArray>(FACE_CLUSTER_EXEMPLARS)
        private val scores = ArrayList<Float>(FACE_CLUSTER_EXEMPLARS)
        init { anchors.add(first); scores.add(firstScore) }
        fun similarityTo(e: FloatArray): Float {
            var best = Float.NEGATIVE_INFINITY
            for (a in anchors) { val s = cosineSimilarity(e, a); if (s > best) best = s }
            return best
        }
        fun add(e: FloatArray, score: Float) {
            if (anchors.size < FACE_CLUSTER_EXEMPLARS) { anchors.add(e); scores.add(score); return }
            var w = 0; for (i in 1 until scores.size) if (scores[i] < scores[w]) w = i
            if (score > scores[w]) { anchors[w] = e; scores[w] = score }
        }
    }

    private fun oldNormalizedMean(members: List<Int>, samples: List<FaceSample>): FloatArray {
        val sum = FloatArray(dim)
        for (m in members) { val e = samples[m].embedding; for (i in 0 until dim) sum[i] += e[i] }
        return normalize(sum)
    }

    private fun oldMerge(assignment: IntArray, samples: List<FaceSample>, mergeThreshold: Float): IntArray {
        val result = assignment.copyOf()
        while (true) {
            val members = HashMap<Int, MutableList<Int>>()
            for (i in result.indices) if (result[i] >= 0) members.getOrPut(result[i]) { ArrayList() }.add(i)
            val ids = members.keys.toList()
            if (ids.size < 2) break
            val centroids = HashMap<Int, FloatArray>()
            for (id in ids) centroids[id] = oldNormalizedMean(members.getValue(id), samples)
            var into = -1; var from = -1; var best = mergeThreshold
            for (a in ids.indices) for (b in a + 1 until ids.size) {
                val s = cosineSimilarity(centroids.getValue(ids[a]), centroids.getValue(ids[b]))
                if (s >= best) { best = s; into = ids[a]; from = ids[b] }
            }
            if (from < 0) break
            for (i in result.indices) if (result[i] == from) result[i] = into
        }
        return result
    }

    private fun oldClusterFaces(samples: List<FaceSample>): IntArray {
        val order = samples.indices.sortedByDescending { samples[it].score }
        val clusters = ArrayList<OldCluster>()
        val assignment = IntArray(samples.size) { -1 }
        for (index in order) {
            val sample = samples[index]
            val need = if (sample.confident) FACE_CLUSTER_THRESHOLD else FACE_CLUSTER_THRESHOLD + FACE_CLUSTER_STRICT_DELTA
            var bestCluster = -1; var bestSim = Float.NEGATIVE_INFINITY
            for (c in clusters.indices) { val s = clusters[c].similarityTo(sample.embedding); if (s > bestSim) { bestSim = s; bestCluster = c } }
            if (bestCluster >= 0 && bestSim >= need) { clusters[bestCluster].add(sample.embedding, sample.score); assignment[index] = bestCluster }
            else if (sample.confident || sample.confirmed) { assignment[index] = clusters.size; clusters.add(OldCluster(sample.embedding, sample.score)) }
        }
        return oldMerge(assignment, samples, FACE_CLUSTER_MERGE_THRESHOLD)
    }

    private fun clusterCount(a: IntArray): Int = a.asSequence().filter { it >= 0 }.toHashSet().size
    private fun groupsOfAtLeast(a: IntArray, min: Int): Int =
        a.asSequence().filter { it >= 0 }.groupingBy { it }.eachCount().count { it.value >= min }

    @Test
    fun old_stalls_new_finishes_on_a_fragmented_library() {
        val people = 300
        val perPerson = 30
        // Two sizes so the divergence shows: the old clusterer is O(faces x clusters) + O(clusters^3),
        // the new one is bounded by the cluster cap, so the old grows quadratically while the new grows
        // linearly. This CPU is far faster than a phone, so a hang here that is "only" tens of seconds is
        // minutes-to-hours on a mid-range device with 40k+ photos.
        for (junk in intArrayOf(21_000, 50_000)) {
            val samples = buildDataset(people, perPerson, junk, seed = 42)
            println("BENCH ---- ${samples.size} faces = $people people x $perPerson + $junk one-off faces ----")

            // NEW (current source, bounded + merge-capped).
            val newStart = System.currentTimeMillis()
            val newAssign = clusterFaces(samples)
            val newMs = System.currentTimeMillis() - newStart
            println(
                "BENCH NEW  : ${newMs}ms, clusters=${clusterCount(newAssign)}, " +
                    "real people found=${groupsOfAtLeast(newAssign, perPerson / 2)}/$people",
            )

            // OLD (inlined pre-fix, unbounded) under a hard wall-clock cap on a daemon thread. It needs
            // minutes on the larger input, so a timeout here IS the reproduction of the stall.
            val oldCapMs = 45_000L
            var oldMs = -1L
            var oldClusters = -1
            val t = Thread {
                val s = System.currentTimeMillis()
                val a = oldClusterFaces(samples)
                oldMs = System.currentTimeMillis() - s
                oldClusters = clusterCount(a)
            }.apply { isDaemon = true; start() }
            t.join(oldCapMs)
            if (t.isAlive) {
                println("BENCH OLD  : STALLED, did not finish within ${oldCapMs}ms (the reported hang)")
            } else {
                println("BENCH OLD  : ${oldMs}ms, clusters=$oldClusters")
            }

            // The point of the fix: the current clusterer completes fast and still recovers the real
            // people, on a library the old one cannot. Asserted generously so a slow box does not flake.
            assertTrue("NEW clustering should finish well under the OLD cap", newMs < oldCapMs)
            assertTrue("NEW should still recover most real people", groupsOfAtLeast(newAssign, perPerson / 2) >= people * 8 / 10)
        }
    }

    /**
     * The incremental fast path is O(new faces), not O(library): once the people are grouped and their
     * centroids cached, placing a handful of freshly indexed faces against those centroids is a few
     * hundred dot products, independent of how large the library already is. This times that placement
     * against the cost of the whole-library re-cluster it replaces, on the same 59k-face library, and
     * checks it puts the new faces on the right people.
     */
    @Test
    fun incremental_is_O_new() {
        val people = 300
        val perPerson = 30
        val junk = 50_000
        val rnd = Random(7)
        // Real identities with KNOWN centres and per-face labels, so a fresh face can be generated near a
        // chosen person and its incremental placement judged against that person. Parallel arrays, left
        // unshuffled so trueLabel stays aligned with samples.
        val centres = ArrayList<FloatArray>(people)
        val samples = ArrayList<FaceSample>(people * perPerson + junk)
        val trueLabel = ArrayList<Int>(people * perPerson + junk)
        repeat(people) { p ->
            val centre = randomUnit(rnd)
            centres.add(centre)
            repeat(perPerson) {
                val v = FloatArray(dim) { i -> centre[i] + rnd.nextGaussian(0.0, 0.05).toFloat() }
                samples.add(FaceSample(normalize(v), score = 0.9f, confident = true))
                trueLabel.add(p)
            }
        }
        repeat(junk) {
            samples.add(FaceSample(randomUnit(rnd), score = 0.65f, confident = true))
            trueLabel.add(-1)
        }
        println("BENCH ---- ${samples.size} faces = $people people x $perPerson + $junk one-off faces ----")

        // The full pass once, to seed the centroid cache the incremental path matches against (the same
        // per-person centroids ClusterFacesUseCase writes into cluster_summary after a rebuild).
        val seedAssign = clusterFaces(samples)
        val members = HashMap<Int, MutableList<Int>>()
        for (i in seedAssign.indices) if (seedAssign[i] >= 0) members.getOrPut(seedAssign[i]) { ArrayList() }.add(i)
        val clusterIds = members.keys.sorted()
        val centroids = clusterIds.map { oldNormalizedMean(members.getValue(it), samples) }
        // The dominant true person per cached centroid, so a placement is judged "right person" even if the
        // base pass split a person across fragments.
        val dominantPerson = IntArray(centroids.size) { ci ->
            val votes = HashMap<Int, Int>()
            for (m in members.getValue(clusterIds[ci])) if (trueLabel[m] >= 0) votes.merge(trueLabel[m], 1, Int::plus)
            votes.maxByOrNull { it.value }?.key ?: -1
        }
        val peopleWithCluster = dominantPerson.filter { it >= 0 }.toHashSet()
        println("BENCH SEED : ${clusterCount(seedAssign)} clusters, ${peopleWithCluster.size}/$people real people cached")

        // 100 fresh faces of existing people (another noisy sample around each chosen centre).
        val newCount = 100
        val newFaces = ArrayList<FaceSample>(newCount)
        val expectedPerson = ArrayList<Int>(newCount)
        var pk = 0
        while (newFaces.size < newCount && pk < people * 8) {
            val p = (pk++) % people
            if (p !in peopleWithCluster) continue
            val centre = centres[p]
            val v = FloatArray(dim) { i -> centre[i] + rnd.nextGaussian(0.0, 0.05).toFloat() }
            newFaces.add(FaceSample(normalize(v), score = 0.9f, confident = true))
            expectedPerson.add(p)
        }

        // NEW: place the 100 against the cached centroids. O(new x people), independent of library size.
        val incStart = System.currentTimeMillis()
        val incAssign = assignIncremental(newFaces, centroids)
        val incMs = System.currentTimeMillis() - incStart

        // OLD: what the app did before the fast path, re-cluster the WHOLE library plus the 100 new faces.
        val fullStart = System.currentTimeMillis()
        clusterFaces(samples + newFaces)
        val fullMs = System.currentTimeMillis() - fullStart

        var correct = 0
        for (i in newFaces.indices) {
            val c = incAssign[i]
            if (c >= 0 && dominantPerson[c] == expectedPerson[i]) correct++
        }

        println("BENCH OLD  : full re-cluster of ${samples.size + newCount} faces = ${fullMs}ms")
        println("BENCH NEW  : incremental +$newCount vs ${centroids.size} centroids = ${incMs}ms, recovered $correct/$newCount to the right person")

        assertTrue("incremental placement should be dramatically faster than a full re-cluster", incMs < fullMs / 10)
        assertTrue("incremental should recover almost all of the new faces to the right person", correct >= newCount * 9 / 10)
    }
}
