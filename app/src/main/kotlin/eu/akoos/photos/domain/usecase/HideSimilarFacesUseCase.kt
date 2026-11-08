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
import eu.akoos.photos.data.db.dao.PersonDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/** Cosine-similarity floor for a stored face to count as another sighting of a dismissed cluster. Held
 *  at the same-identity range so a different real person who merely sits near the junk is never swept in;
 *  the dismissed cluster's own faces are left out of the sweep, so only its look-alikes elsewhere in the
 *  library can match. */
internal const val HIDE_SIMILAR_THRESHOLD = 0.62f

/** Kept faces read per page during the sweep, so a large library never loads every embedding at once. */
private const val HIDE_SWEEP_PAGE = 2000

/**
 * Whether the look-alike sweep may remove a kept face: never one the user confirmed a name for
 * ([manualName]), never one grouped under a named person ([namedIds]), and never one of the clusters being
 * dismissed ([dismissed]), which go as a whole. Pure so the rule is unit-tested without a database.
 */
internal fun sweepMayRemove(personId: Long?, manualName: String?, namedIds: Set<Long>, dismissed: Set<Long>): Boolean =
    manualName.isNullOrBlank() && (personId == null || (personId !in namedIds && personId !in dismissed))

/**
 * When a cluster is dismissed as "not a person", also find the account's other kept faces that are the same
 * thing by embedding, so a recurring object (a statue, a poster, a repeated false detection) does not linger
 * as several look-alike clusters the user has to dismiss one by one. The reference is the dismissed
 * cluster's own faces; their unit-normalised mean direction is compared against every kept face the sweep
 * may remove ([sweepMayRemove]), and matches at or above the threshold are the look-alikes.
 *
 * [findLookAlikes] only reads, so it runs before the caller's transaction; [removeLookAlikes] rejects them
 * inside it, asking the rule again of each face as it stands then. Rejected faces stay recoverable from the
 * excluded-faces screen, exactly like a manual "not a person".
 */
class HideSimilarFacesUseCase @Inject constructor(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
) {
    /** The ids of the look-alikes of [referenceEmbeddings] among [userId]'s kept faces, the clusters in
     *  [dismissed] left out. Reads a page of faces at a time along the primary key. */
    suspend fun findLookAlikes(
        userId: String,
        referenceEmbeddings: List<FloatArray>,
        dismissed: Set<Long>,
        threshold: Float = HIDE_SIMILAR_THRESHOLD,
    ): List<String> = withContext(Dispatchers.IO) {
        val refs = referenceEmbeddings.filter { it.size == FACE_EMBEDDING_DIM }
        if (refs.isEmpty()) return@withContext emptyList()
        val centroid = normalize(meanDirection(refs))
        if (centroid.all { it == 0f }) return@withContext emptyList()
        val namedIds = namedPersonIds(userId)

        val matches = ArrayList<String>()
        var after = ""
        while (true) {
            val page = faceDao.sweepFacesAfter(userId, after, HIDE_SWEEP_PAGE)
            for (face in page) {
                coroutineContext.ensureActive()
                if (!sweepMayRemove(face.personId, face.manualName, namedIds, dismissed)) continue
                val embedding = unpackEmbedding(face.embedding)
                if (embedding.size != FACE_EMBEDDING_DIM) continue
                if (cosineSimilarity(centroid, embedding) >= threshold) matches += face.id
            }
            if (page.size < HIDE_SWEEP_PAGE) break
            after = page.last().id
        }
        matches
    }

    /** Rejects those of [faceIds] the sweep may still remove, inside the caller's transaction, and returns how
     *  many it rejected and the people they were grouped under, for the caller to bring up to date. */
    suspend fun removeLookAlikes(userId: String, faceIds: Collection<String>, dismissed: Set<Long>): RemovedLookAlikes {
        if (faceIds.isEmpty()) return RemovedLookAlikes(0, emptySet())
        val namedIds = namedPersonIds(userId)
        val confirmed = faceDao.manualNamesForIds(faceIds).filter { it.manualName.isNotBlank() }.mapTo(HashSet()) { it.id }
        val faces = faceDao.facesByIds(userId, faceIds)
            .filter { it.id !in confirmed && sweepMayRemove(it.personId, null, namedIds, dismissed) }
        if (faces.isNotEmpty()) faceDao.rejectByIds(faces.map { it.id })
        return RemovedLookAlikes(faces.size, faces.mapNotNullTo(HashSet()) { it.personId })
    }

    private suspend fun namedPersonIds(userId: String): Set<Long> =
        personDao.namedPeopleForUser(userId).filter { !it.displayName.isNullOrBlank() }.mapTo(HashSet()) { it.id }
}

/** What [HideSimilarFacesUseCase.removeLookAlikes] rejected: how many [faces], and the [people] they were in. */
class RemovedLookAlikes(val faces: Int, val people: Set<Long>)

/** Component-wise mean of unit embeddings, before the direction is renormalised by the caller. */
private fun meanDirection(vectors: List<FloatArray>): FloatArray {
    val dim = vectors.first().size
    val sum = FloatArray(dim)
    for (vector in vectors) for (i in 0 until dim) sum[i] += vector[i]
    val inv = 1f / vectors.size
    return FloatArray(dim) { sum[it] * inv }
}
