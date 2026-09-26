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

import eu.akoos.photos.data.db.dao.ClusterSummaryDao
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.entity.ClusterSummaryEntity
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * People's mean faces for comparing one person against the others, read without loading the whole face
 * table. A person's centroid comes from the per-person cache the grouping keeps ([usableCachedCentroid])
 * when that still describes the person, and otherwise from that person's own faces alone, read a page at
 * a time ([exactCentroid]); the one person the caller compares against is always summed from its own
 * faces. Guest-safe: [account] is the user id or the local partition, as the face rows are keyed.
 */
class PersonCentroids @Inject constructor(
    private val faceDao: FaceDao,
    private val clusterSummaryDao: ClusterSummaryDao,
) {
    /**
     * The centroid of every person of [account] holding a kept face that [wanted] accepts, keyed by person
     * id; a person none of whose faces has a readable vector is left out. [exactFor] is summed from its own
     * faces even when a cached centroid would do.
     */
    suspend fun load(account: String, exactFor: Long?, wanted: (Long) -> Boolean): Map<Long, FloatArray> {
        val keptByPerson = faceDao.keptFaceCountsByPerson(account)
        if (keptByPerson.isEmpty()) return emptyMap()
        val cached = clusterSummaryDao.getAllForUser(account).associateBy { it.personId }
        val out = HashMap<Long, FloatArray>()
        for (row in keptByPerson) {
            val personId = row.personId
            if (!wanted(personId)) continue
            val fromCache = if (personId == exactFor) null else usableCachedCentroid(cached[personId], row.faces)
            val centroid = fromCache ?: exactCentroid(account, personId) ?: continue
            out[personId] = centroid
        }
        return out
    }

    /** [personId]'s mean face summed from its own kept faces, a page at a time, or null when none of them
     *  has a readable vector. */
    suspend fun exactCentroid(account: String, personId: Long): FloatArray? {
        val sum = CentroidSum()
        var afterId = ""
        while (true) {
            val page = faceDao.personVectorsAfter(account, personId, afterId, PAGE_SIZE)
            for (face in page) sum.add(face.embedding)
            if (page.size < PAGE_SIZE) break
            afterId = page.last().id
        }
        return sum.centroid()
    }

    private companion object {
        /** Faces per page of one person's read: about a megabyte of vectors. */
        const val PAGE_SIZE = 500
    }
}

/**
 * A cached centroid [summary] as a person's mean face, or null when it cannot stand in for one: nothing is
 * cached, it came from an older face pipeline, its vector is the wrong width, or it no longer counts the
 * person's [keptFaces] (a curation edit moved faces since it was written). Pure so it is unit-tested.
 */
internal fun usableCachedCentroid(summary: ClusterSummaryEntity?, keptFaces: Int): FloatArray? {
    if (summary == null) return null
    if (summary.modelVersion != FACE_MODEL_VERSION) return null
    if (summary.memberCount != keptFaces) return null
    return unpackEmbedding(summary.centroid).takeIf { it.size == FACE_EMBEDDING_DIM }
}

/**
 * Sums face vectors into their mean direction: each readable vector (a stale-width one is skipped) is
 * added in double precision and the total L2-normalised once at the end, so a person's centroid comes out
 * the same whether its faces arrive in one list or a page at a time.
 */
internal class CentroidSum {
    private var sum: DoubleArray? = null

    fun add(embedding: ByteArray) {
        val e = unpackEmbedding(embedding)
        if (e.size != FACE_EMBEDDING_DIM) return
        val acc = sum ?: DoubleArray(e.size).also { sum = it }
        for (i in e.indices) acc[i] += e[i]
    }

    /** The normalised mean, or null when nothing readable was added. */
    fun centroid(): FloatArray? {
        val acc = sum ?: return null
        var norm = 0.0
        for (v in acc) norm += v * v
        val inv = if (norm > 0.0) 1.0 / sqrt(norm) else 0.0
        return FloatArray(acc.size) { (acc[it] * inv).toFloat() }
    }
}

/**
 * The people a named person's merge suggestion may offer, most alike first: every person in [centroids]
 * other than those in [skip] whose centroid's cosine to [target] is above [threshold]. The caller takes
 * the first that the user has not already turned down. Pure so it is unit-tested.
 */
internal fun rankSuggestionCandidates(
    target: FloatArray,
    centroids: Map<Long, FloatArray>,
    skip: Set<Long>,
    threshold: Float,
): List<Long> =
    centroids.entries
        .filter { it.key !in skip }
        .map { it.key to cosineSimilarity(target, it.value) }
        .filter { it.second > threshold }
        .sortedByDescending { it.second }
        .map { it.first }
