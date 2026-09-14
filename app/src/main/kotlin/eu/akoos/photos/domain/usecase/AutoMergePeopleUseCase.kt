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

import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.entity.FaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/** One auto-merge fold: the unnamed cluster [fromClusterId] is folded into the named person [toPersonId]. */
internal data class AutoMergePair(val fromClusterId: Long, val toPersonId: Long)

/**
 * Chooses the auto-merge folds for one pass. For every NAMED person it takes its single closest UNNAMED
 * candidate cluster whose centroid cosine clears [threshold] (the same gate the manual "this may be the
 * same person" card uses), skipping the Unsorted bucket [otherId] and any (named, candidate) pair the user
 * already rejected as "not this person". A candidate that is closest to two named people is given to the
 * nearer one, and only unnamed candidates fold, so two names the user kept apart are never silently
 * combined and no fold chains within a pass. Pure: no database, no Android, so the selection is unit-tested.
 */
internal fun pickAutoMergePairs(
    centroids: Map<Long, FloatArray>,
    namedIds: Set<Long>,
    otherId: Long,
    rejectedPairs: Set<Pair<Long, Long>>,
    threshold: Float,
): List<AutoMergePair> {
    val candidateIds = centroids.keys.filter { it != otherId && it !in namedIds }
    if (candidateIds.isEmpty() || namedIds.isEmpty()) return emptyList()
    // candidateId -> (nearest named person so far, its similarity), so a candidate folds into one person only.
    val claim = HashMap<Long, Pair<Long, Float>>()
    for (namedId in namedIds) {
        val nc = centroids[namedId] ?: continue
        for (cid in candidateIds) {
            if ((namedId to cid) in rejectedPairs) continue
            val c = centroids[cid] ?: continue
            val s = cosineSimilarity(nc, c)
            if (s <= threshold) continue
            val prior = claim[cid]
            if (prior == null || s > prior.second) claim[cid] = namedId to s
        }
    }
    return claim.map { (cid, ns) -> AutoMergePair(cid, ns.first) }
}

/**
 * Applies, automatically, the same merges the manual "this may be the same person" card would offer: each
 * named person absorbs its closest look-alike unnamed cluster. Opt-in (the scheduler only calls this when
 * the user turned auto-merge on), and it reuses the ordinary [AssignPersonNameUseCase] fold, so an
 * auto-merge is durable and identical to a manual one. Runs one pass (closest single candidate per person,
 * no chaining); a later indexing pass catches any further matches. Guest-safe: [account] is the user id or
 * the local partition, exactly as clustering keys it.
 */
@Singleton
class AutoMergePeopleUseCase @Inject constructor(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val notPersonDao: NotPersonDao,
    private val assignPersonName: AssignPersonNameUseCase,
) {
    /** Folds the chosen candidates and returns how many clusters were merged (0 when nothing qualifies). */
    suspend operator fun invoke(account: String): Int = withContext(Dispatchers.Default) {
        val faces = faceDao.allFacesByScoreDesc(account)
        val centroids = centroidsByPerson(faces)
        if (centroids.isEmpty()) return@withContext 0
        val named = personDao.namedPeopleForUser(account).filter { !it.displayName.isNullOrBlank() }
        if (named.isEmpty()) return@withContext 0
        val namedIds = named.mapTo(HashSet()) { it.id }
        val nameById = named.associate { it.id to it.displayName!!.trim() }
        val otherId = personDao.otherPersonId(account) ?: -1L

        // (named person, candidate cluster) pairs the user rejected: a candidate holding a face marked
        // "not this person" under that person's name is never re-offered, so it is not auto-merged either.
        val faceIdsByPerson = HashMap<Long, MutableSet<String>>()
        for (f in faces) {
            if (f.rejected) continue
            val pid = f.personId ?: continue
            faceIdsByPerson.getOrPut(pid) { HashSet() }.add(f.id)
        }
        val rejectedByName = notPersonDao.allForUser(account).groupBy({ it.personName }, { it.faceId })
        val rejectedPairs = HashSet<Pair<Long, Long>>()
        for ((namedId, name) in nameById) {
            val rejected = rejectedByName[name]?.toHashSet() ?: continue
            for (cid in centroids.keys) {
                if (cid == namedId) continue
                if (faceIdsByPerson[cid]?.any { it in rejected } == true) rejectedPairs.add(namedId to cid)
            }
        }

        val pairs = pickAutoMergePairs(centroids, namedIds, otherId, rejectedPairs, FACE_SUGGEST_THRESHOLD)
        for (pair in pairs) {
            val name = nameById[pair.toPersonId] ?: continue
            // Fold the candidate into the named person (by name); the surviving side is the named person.
            assignPersonName(account, pair.fromClusterId, name)
        }
        pairs.size
    }

    /** L2-normalised mean embedding per person, over their kept faces, mirroring the manual suggestion path. */
    private fun centroidsByPerson(faces: List<FaceEntity>): Map<Long, FloatArray> {
        val sums = HashMap<Long, DoubleArray>()
        for (f in faces) {
            if (f.rejected) continue
            val pid = f.personId ?: continue
            val e = unpackEmbedding(f.embedding)
            if (e.size != FACE_EMBEDDING_DIM) continue
            val acc = sums.getOrPut(pid) { DoubleArray(e.size) }
            for (i in e.indices) acc[i] += e[i]
        }
        val out = HashMap<Long, FloatArray>()
        for ((pid, acc) in sums) {
            var norm = 0.0
            for (v in acc) norm += v * v
            val inv = if (norm > 0.0) 1.0 / sqrt(norm) else 0.0
            out[pid] = FloatArray(acc.size) { (acc[it] * inv).toFloat() }
        }
        return out
    }
}
