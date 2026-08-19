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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.entity.FaceEntity
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * One "Is this <name>?" match: an unnamed cluster ([candidateId]) offered to a named person, ranked by
 * [similarity]. The review screen resolves both ids to face tiles for the Yes/No prompt.
 */
data class PersonSuggestion(
    val personId: Long,
    val personName: String,
    val candidateId: Long,
    val candidateSize: Int,
    val similarity: Float,
)

/**
 * Builds the review queue: each unnamed cluster is offered to the named person its mean face direction
 * is closest to (above [FACE_SUGGEST_THRESHOLD]), skipping any the user has already said "not this
 * person" to. A one-shot CPU pass over the stored embeddings, so it runs when the review screen opens
 * rather than continuously.
 */
class SuggestPersonMatchesUseCase @Inject constructor(
    private val accountManager: AccountManager,
    private val personDao: PersonDao,
    private val faceDao: FaceDao,
    private val notPersonDao: NotPersonDao,
) {
    suspend operator fun invoke(): List<PersonSuggestion> = withContext(Dispatchers.Default) {
        val userId = accountManager.getPrimaryUserId().first() ?: return@withContext emptyList()
        val account = userId.id
        val persons = personDao.allForUser(account)
        if (persons.none { !it.displayName.isNullOrBlank() }) return@withContext emptyList()

        val faces = faceDao.allFacesByScoreDesc(account)
        val byCluster = HashMap<Long, MutableList<FaceEntity>>()
        for (f in faces) {
            if (f.rejected) continue
            val p = f.personId ?: continue
            byCluster.getOrPut(p) { ArrayList() }.add(f)
        }
        val centroid = HashMap<Long, FloatArray>()
        for ((pid, fs) in byCluster) normalizedMeanOf(fs)?.let { centroid[pid] = it }

        val named = persons.filter { !it.displayName.isNullOrBlank() && centroid[it.id] != null }
            .map { it.id to centroid.getValue(it.id) }
        val unnamed = persons.filter {
            it.displayName.isNullOrBlank() && it.faceCount >= MIN_FACES_TO_SHOW_PERSON && centroid[it.id] != null
        }.map { it.id to centroid.getValue(it.id) }

        val matches = suggestPersonMatches(named, unnamed, FACE_SUGGEST_THRESHOLD)
        val nameById = persons.associate { it.id to it.displayName }
        val notByName = notPersonDao.allForUser(account)
            .groupBy({ it.personName }, { it.faceId }).mapValues { it.value.toHashSet() }
        val faceIdsByCluster = byCluster.mapValues { (_, fs) -> fs.mapTo(HashSet()) { it.id } }

        matches.mapNotNull { (namedId, candidateId, sim) ->
            val name = nameById[namedId]?.takeIf { !it.isNullOrBlank() } ?: return@mapNotNull null
            val rejected = notByName[name]
            val candidateFaces = faceIdsByCluster[candidateId] ?: emptySet()
            // Already answered "not this person" for a face in this cluster: never offer it again.
            if (rejected != null && candidateFaces.any { it in rejected }) return@mapNotNull null
            PersonSuggestion(namedId, name, candidateId, candidateFaces.size, sim)
        }
    }

    /** L2-normalised mean of a cluster's face embeddings, its centroid, or null if none are valid. */
    private fun normalizedMeanOf(faces: List<FaceEntity>): FloatArray? {
        var sum: DoubleArray? = null
        var used = 0
        for (f in faces) {
            val e = unpackEmbedding(f.embedding)
            if (e.size != FACE_EMBEDDING_DIM) continue
            val s = sum ?: DoubleArray(e.size).also { sum = it }
            for (i in e.indices) s[i] += e[i]
            used++
        }
        val acc = sum ?: return null
        if (used == 0) return null
        var norm = 0.0
        for (v in acc) norm += v * v
        val inv = if (norm > 0.0) 1.0 / sqrt(norm) else 0.0
        return FloatArray(acc.size) { (acc[it] * inv).toFloat() }
    }
}
