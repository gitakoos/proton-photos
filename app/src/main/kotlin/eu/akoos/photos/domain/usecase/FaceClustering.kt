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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Exemplar-based face clustering with a quality-aware join and a merge pass, kept free of Android and
 * database types so the whole grouping rule runs in a plain JVM test. The embeddings are the 128-d,
 * L2-normalised recognition vectors the indexer stores, so a cosine similarity between two of them is
 * a plain dot product.
 *
 * A cluster is summarised by up to [FACE_CLUSTER_EXEMPLARS] anchor faces, the highest detection score
 * members, and a candidate joins by its single best match to any anchor. Each anchor stays a real
 * face, so a large cluster's representative never blurs into a mean-of-many direction that sits close
 * to everyone. A weak face (low score, blurred, turned) must clear a stricter bar to join, so a poor
 * crop can still find an obvious match but never bridges two people. Finally a merge pass folds
 * together clusters whose mean directions are the same person split in two, which is what removes the
 * "one face shown as several people" duplication.
 */

/** Cosine-similarity floor for two confident faces to be treated as the same person. Tuned against
 *  full-resolution embeddings, where different people sit far below this and the same person across
 *  poses reaches it. SFace's same-identity cosine is compressed relative to ArcFace, so this floor sits
 *  in a low absolute range; within it the value leans toward precision, so a stray non-face embedding
 *  that lands near a real identity does not join it, at the cost of a hard same-identity crop
 *  occasionally splitting off (recovered by naming or an explicit merge). */
internal const val FACE_CLUSTER_THRESHOLD = 0.53f

/** Extra cosine a weak face (low score, blurred, or turned) must clear to join, so a poor crop can
 *  still find an obvious match but cannot bridge two different people. Mirrors Ente's stricter
 *  distance for low-quality faces. */
internal const val FACE_CLUSTER_STRICT_DELTA = 0.08f

/** Two clusters whose mean directions are at least this close are treated as the same person split in
 *  two, and are merged. Kept at the join floor: a merge more lenient than the join would fuse people
 *  the join deliberately kept apart. Duplicate clusters of one person are consolidated by naming one
 *  (the unnamed twins then stay hidden) or by an explicit merge, not by loosening this. */
internal const val FACE_CLUSTER_MERGE_THRESHOLD = 0.53f

/** How close an unnamed cluster must sit to a named person to be OFFERED as "Is this <name>?". Set
 *  below the merge floor so plausible-but-not-certain matches surface for the user to confirm or
 *  reject, rather than being auto-merged or hidden. */
internal const val FACE_SUGGEST_THRESHOLD = 0.40f

/** Component count of a stored face embedding. */
internal const val FACE_EMBEDDING_DIM = 128

/** Face-pipeline generation the stored faces belong to. A bump means the stored rows were produced by
 *  an older detector, recognition model, or quality gate, so the indexer clears the face rows once and
 *  re-detects and re-embeds them with the current pipeline before any clustering reads them. */
internal const val FACE_MODEL_VERSION = 4

/** Highest detection score faces kept as a cluster's comparison anchors. */
internal const val FACE_CLUSTER_EXEMPLARS = 5

/**
 * Photos a person must appear in before showing as a browsable person. A one-off false detection, an
 * object that vaguely read as a face, lands in a cluster of one and never clears this floor, so it
 * stays out of the People surfaces while a genuinely recurring face shows through.
 */
const val MIN_FACES_TO_SHOW_PERSON = 2

/**
 * The little-endian floats [bytes] packs, unpacked in the exact layout the indexer wrote them: a
 * [ByteBuffer] in little-endian order, one float per component. A blob whose length is not exactly
 * [FACE_EMBEDDING_DIM] floats (a truncated write, or a row an earlier model produced at a different
 * width) yields an empty array, which every caller's dimension guard skips, so a stale-width row can
 * neither crash nor poison clustering.
 */
internal fun unpackEmbedding(bytes: ByteArray): FloatArray {
    if (bytes.size != FACE_EMBEDDING_DIM * Float.SIZE_BYTES) return FloatArray(0)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(FACE_EMBEDDING_DIM) { buffer.float }
}

/** Cosine similarity of two unit-length embeddings, i.e. their dot product. */
internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0.0
    for (i in a.indices) dot += a[i].toDouble() * b[i]
    return dot.toFloat()
}

/**
 * A growing cluster holding up to [FACE_CLUSTER_EXEMPLARS] anchor embeddings and their detection
 * scores. Similarity to a candidate is the best match against any anchor. When full, a stronger
 * candidate replaces the weakest anchor so the anchors track the clearest faces of the identity.
 */
internal class ExemplarCluster(first: FloatArray, firstScore: Float) {
    val anchors = ArrayList<FloatArray>(FACE_CLUSTER_EXEMPLARS)
    private val scores = ArrayList<Float>(FACE_CLUSTER_EXEMPLARS)

    init {
        anchors.add(first)
        scores.add(firstScore)
    }

    fun similarityTo(embedding: FloatArray): Float {
        var best = Float.NEGATIVE_INFINITY
        for (anchor in anchors) {
            val s = cosineSimilarity(embedding, anchor)
            if (s > best) best = s
        }
        return best
    }

    fun add(embedding: FloatArray, score: Float) {
        if (anchors.size < FACE_CLUSTER_EXEMPLARS) {
            anchors.add(embedding)
            scores.add(score)
            return
        }
        var weakest = 0
        for (i in 1 until scores.size) if (scores[i] < scores[weakest]) weakest = i
        if (score > scores[weakest]) {
            anchors[weakest] = embedding
            scores[weakest] = score
        }
    }
}

/**
 * One face handed to the clusterer: its embedding, the detector score that rates its clarity, whether
 * it is a confident (clear, frontal, sharp) crop, and whether the user has confirmed it as a named
 * person. A weak face joins under a stricter bar. Only a confident or a confirmed face may open a new
 * cluster: an unconfirmed weak crop that matches nothing is left unassigned rather than seeding one, so
 * false-positive detections (landscapes, backs of heads, non-faces) cannot pile into a junk cluster.
 */
class FaceSample(
    val embedding: FloatArray,
    val score: Float,
    val confident: Boolean = true,
    val confirmed: Boolean = false,
)

/**
 * Groups [samples] into identities. Faces are visited from the highest detection score down so the
 * clearest crop anchors each cluster; a confident face joins the cluster whose best anchor match
 * clears [threshold], a weak one must clear [threshold] plus [FACE_CLUSTER_STRICT_DELTA]. A face that
 * matches nothing opens a new cluster only when it is confident or confirmed; an unconfirmed weak face
 * that matches nothing is left unassigned (-1), so junk detections do not seed a cluster. A final merge
 * pass folds clusters whose mean directions clear [mergeThreshold] into one, leaving the -1 group
 * untouched. Returns the cluster index per input (or -1), in the ORIGINAL input order. Deterministic.
 */
fun clusterFaces(
    samples: List<FaceSample>,
    threshold: Float = FACE_CLUSTER_THRESHOLD,
    mergeThreshold: Float = FACE_CLUSTER_MERGE_THRESHOLD,
): IntArray {
    val order = samples.indices.sortedByDescending { samples[it].score }
    val clusters = ArrayList<ExemplarCluster>()
    val assignment = IntArray(samples.size) { -1 }
    for (index in order) {
        val sample = samples[index]
        val need = if (sample.confident) threshold else threshold + FACE_CLUSTER_STRICT_DELTA
        var bestCluster = -1
        var bestSimilarity = Float.NEGATIVE_INFINITY
        for (c in clusters.indices) {
            val similarity = clusters[c].similarityTo(sample.embedding)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestCluster = c
            }
        }
        if (bestCluster >= 0 && bestSimilarity >= need) {
            clusters[bestCluster].add(sample.embedding, sample.score)
            assignment[index] = bestCluster
        } else if (sample.confident || sample.confirmed) {
            assignment[index] = clusters.size
            clusters.add(ExemplarCluster(sample.embedding, sample.score))
        }
        // else: an unconfirmed weak face that matched no cluster stays unassigned (-1). It is not allowed
        // to seed a cluster, so a run of low-quality false detections cannot coalesce into a junk cluster.
        // The caller routes the leftover -1 faces into the "Unsorted" bucket.
    }
    return mergeSimilarClusters(assignment, samples, mergeThreshold)
}

/**
 * Assigns each given name to the cluster holding the most of that person's faces on a rebuild, counting
 * BOTH the old grouping a face carried (its previous person) AND the faces the user confirmed. Counting
 * the carry is what stops a single confirmed odd-angle face the base pass split into its own cluster from
 * stripping the name off the cluster that holds the person's bulk: a lone confirmation cannot outvote the
 * dozens of carried faces. Confirmations break ties, so a person deliberately re-anchored by many
 * confirmations still follows them. [oldPersonIdPerFace], [manualNamePerFace] and [clusterPerFace] are
 * parallel (one entry per clustered face): the face's previous person id (or null), the name the user
 * confirmed it as (or null), and its new cluster index. [names] maps a previous person id to its
 * non-blank display name. The largest person is placed first and each cluster is claimed at most once,
 * so two people merged into one cluster do not both claim it; ties break by most confirmations then
 * smallest cluster index, so the result is deterministic. Returns new-cluster-index to the name it gets.
 */
fun resolveClusterNames(
    oldPersonIdPerFace: List<Long?>,
    manualNamePerFace: List<String?>,
    clusterPerFace: IntArray,
    names: Map<Long, String>,
): Map<Int, String> {
    class Evidence { var total = 0; var confirmed = 0 }
    val evidenceByName = HashMap<String, HashMap<Int, Evidence>>()
    for (i in clusterPerFace.indices) {
        // The unassigned (-1) leftover is never a named cluster, so it can never become a name target
        // that would pull a person's confirmed faces into the Unsorted bucket.
        if (clusterPerFace[i] < 0) continue
        val confirmed = manualNamePerFace[i]
        val name = confirmed ?: oldPersonIdPerFace[i]?.let { names[it] } ?: continue
        val ev = evidenceByName.getOrPut(name) { HashMap() }.getOrPut(clusterPerFace[i]) { Evidence() }
        ev.total++
        if (confirmed != null) ev.confirmed++
    }
    val nameForCluster = HashMap<Int, String>()
    val claimed = HashSet<Int>()
    val orderedNames = evidenceByName.entries.sortedWith(
        compareByDescending<Map.Entry<String, HashMap<Int, Evidence>>> { e -> e.value.values.sumOf { it.total } }
            .thenBy { it.key },
    )
    for ((name, byCluster) in orderedNames) {
        val best = byCluster.entries
            .filter { it.key !in claimed }
            .sortedWith(
                compareByDescending<Map.Entry<Int, Evidence>> { it.value.total }
                    .thenByDescending { it.value.confirmed }
                    .thenBy { it.key },
            ).firstOrNull()?.key ?: continue
        nameForCluster[best] = name
        claimed.add(best)
    }
    return nameForCluster
}

/**
 * Teaches from the user's confirmations without disturbing the base clustering: every face the user
 * confirmed for a person is moved into that person's cluster, and then any face still sitting in an
 * unnamed cluster is pulled into the named cluster whose confirmed faces it best matches, when that
 * match clears [threshold]. So a manual add both keeps its own photo on the right person and drags in
 * that person's other faces the base pass split off, while a face too far from every confirmed person
 * (a genuinely distant or low-quality crop) is left where it is. Faces already in a named cluster are
 * never pulled elsewhere. Deterministic: named clusters are considered in id order. Returns the
 * updated per-face assignment.
 *
 * [clusterName] maps a base-cluster id to the person name it carries (or null); [manualName] is the
 * confirmed name per face (or null). [scores] rank faces so the clearest confirmed crops anchor.
 */
fun attractToNamedClusters(
    embeddings: List<FloatArray>,
    scores: FloatArray,
    assignment: IntArray,
    clusterName: Map<Int, String?>,
    manualName: List<String?>,
    threshold: Float,
    blockedNames: List<Set<String>> = emptyList(),
): IntArray {
    val nameToCluster = HashMap<String, Int>()
    for ((cid, name) in clusterName) if (name != null) nameToCluster[name] = cid
    val result = assignment.copyOf()
    if (nameToCluster.isEmpty()) return result

    // 1. A confirmed face belongs to its named cluster, so two clusters holding the same person's
    //    confirmed faces consolidate onto the one that carries the name.
    for (i in result.indices) {
        val target = manualName[i]?.let { nameToCluster[it] } ?: continue
        result[i] = target
    }

    // 2. Highest-score members of each named cluster become its anchors.
    val exemplars = HashMap<Int, List<FloatArray>>()
    for (cid in nameToCluster.values) {
        val members = result.indices.filter { result[it] == cid }
        exemplars[cid] = members.sortedByDescending { scores[it] }.take(FACE_CLUSTER_EXEMPLARS)
            .map { embeddings[it] }
    }

    // 3. Pull an unnamed face into the best-matching named cluster once it clears the floor.
    val orderedCids = exemplars.keys.sorted()
    for (i in result.indices) {
        if (manualName[i] != null) continue
        if (clusterName[result[i]] != null) continue
        val blocked = blockedNames.getOrNull(i) ?: emptySet()
        var bestCid = -1
        var bestSim = Float.NEGATIVE_INFINITY
        for (cid in orderedCids) {
            // Never pull a face into a person it was explicitly said not to belong to.
            if (clusterName[cid] in blocked) continue
            val sim = exemplars.getValue(cid).maxOfOrNull { cosineSimilarity(embeddings[i], it) } ?: continue
            if (sim > bestSim) {
                bestSim = sim
                bestCid = cid
            }
        }
        if (bestCid >= 0 && bestSim >= threshold) result[i] = bestCid
    }
    return result
}

/**
 * Suggests "Is this <named person>?" matches: each unnamed cluster is offered to the single named
 * person its mean direction is closest to, when that cosine clears [threshold]. Best matches first.
 * The caller resolves the ids to faces to show and filters out pairs the user already rejected. Pure
 * and deterministic (named people are compared in id order; ties break by candidate id).
 *
 * [named] and [unnamed] are (personId, L2-normalised centroid) pairs.
 */
fun suggestPersonMatches(
    named: List<Pair<Long, FloatArray>>,
    unnamed: List<Pair<Long, FloatArray>>,
    threshold: Float,
): List<Triple<Long, Long, Float>> {
    if (named.isEmpty() || unnamed.isEmpty()) return emptyList()
    val namedById = named.sortedBy { it.first }
    val out = ArrayList<Triple<Long, Long, Float>>()
    for ((unnamedId, uc) in unnamed) {
        var bestId = -1L
        var bestSim = Float.NEGATIVE_INFINITY
        for ((namedId, nc) in namedById) {
            val s = cosineSimilarity(uc, nc)
            if (s > bestSim) {
                bestSim = s
                bestId = namedId
            }
        }
        if (bestId >= 0 && bestSim >= threshold) out.add(Triple(bestId, unnamedId, bestSim))
    }
    return out.sortedWith(compareByDescending<Triple<Long, Long, Float>> { it.third }.thenBy { it.second })
}

/**
 * Consolidates clusters that are the same person split in two: repeatedly merges the closest pair of
 * cluster mean directions while they are at least [mergeThreshold] apart in cosine, recomputing after
 * each merge so a thin chain of near-matches cannot fuse unlike people. Returns a compacted
 * assignment.
 */
private fun mergeSimilarClusters(
    assignment: IntArray,
    samples: List<FaceSample>,
    mergeThreshold: Float,
): IntArray {
    val result = assignment.copyOf()
    while (true) {
        val members = HashMap<Int, MutableList<Int>>()
        // The unassigned (-1) leftover is not a cluster: never give it a centroid and never merge it,
        // so junk faces cannot pull a real cluster into themselves.
        for (i in result.indices) if (result[i] >= 0) members.getOrPut(result[i]) { ArrayList() }.add(i)
        val ids = members.keys.toList()
        if (ids.size < 2) break
        val centroids = HashMap<Int, FloatArray>()
        for (id in ids) centroids[id] = normalizedMean(members.getValue(id), samples)

        var mergeInto = -1
        var mergeFrom = -1
        var bestSimilarity = mergeThreshold
        for (a in ids.indices) {
            for (b in a + 1 until ids.size) {
                val similarity = cosineSimilarity(centroids.getValue(ids[a]), centroids.getValue(ids[b]))
                if (similarity >= bestSimilarity) {
                    bestSimilarity = similarity
                    mergeInto = ids[a]
                    mergeFrom = ids[b]
                }
            }
        }
        if (mergeFrom < 0) break
        for (i in result.indices) if (result[i] == mergeFrom) result[i] = mergeInto
    }
    val remap = HashMap<Int, Int>()
    for (v in result) if (v >= 0) remap.getOrPut(v) { remap.size }
    return IntArray(result.size) { val v = result[it]; if (v < 0) -1 else remap.getValue(v) }
}

/** L2-normalised mean of the members' embeddings, a cluster's mean direction. */
private fun normalizedMean(members: List<Int>, samples: List<FaceSample>): FloatArray {
    val dim = samples[members[0]].embedding.size
    val sum = FloatArray(dim)
    for (m in members) {
        val e = samples[m].embedding
        for (i in 0 until dim) sum[i] += e[i]
    }
    var normSq = 0.0
    for (v in sum) normSq += v.toDouble() * v
    val inv = if (normSq > 0.0) (1.0 / sqrt(normSq)).toFloat() else 0f
    for (i in sum.indices) sum[i] *= inv
    return sum
}
