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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/** Cosine-similarity floor for a stored face to count as another sighting of a dismissed cluster. Held
 *  at the same-identity range so a different real person who merely sits near the junk is never swept in;
 *  the dismissed cluster's own faces are already rejected before the sweep runs, so only its look-alikes
 *  elsewhere in the library can match. */
internal const val HIDE_SIMILAR_THRESHOLD = 0.62f

/** Kept faces read per page during the sweep, so a large library never loads every embedding at once. */
private const val HIDE_SWEEP_PAGE = 2000

/**
 * When a cluster is dismissed as "not a person", also reject the account's other kept faces that are the
 * same thing by embedding, so a recurring object (a statue, a poster, a repeated false detection) does
 * not linger as several look-alike clusters the user has to dismiss one by one. The reference is the
 * dismissed cluster's own faces; their unit-normalised mean direction is compared against every kept
 * face, and matches at or above [threshold] are rejected. Returns how many EXTRA faces were hidden.
 *
 * The caller reads the reference embeddings and rejects the dismissed cluster BEFORE calling this, so the
 * sweep walks only the faces still kept and never re-touches the cluster it came from. Rejected faces stay
 * recoverable from the excluded-faces screen, exactly like a manual "not a person".
 */
class HideSimilarFacesUseCase @Inject constructor(
    private val faceDao: FaceDao,
) {
    suspend operator fun invoke(
        userId: String,
        referenceEmbeddings: List<FloatArray>,
        threshold: Float = HIDE_SIMILAR_THRESHOLD,
    ): Int = withContext(Dispatchers.IO) {
        val refs = referenceEmbeddings.filter { it.size == FACE_EMBEDDING_DIM }
        if (refs.isEmpty()) return@withContext 0
        val centroid = normalize(meanDirection(refs))
        if (centroid.all { it == 0f }) return@withContext 0

        val matches = ArrayList<String>()
        var offset = 0
        while (true) {
            val page = faceDao.keptFacesPaged(userId, HIDE_SWEEP_PAGE, offset)
            if (page.isEmpty()) break
            for (face in page) {
                coroutineContext.ensureActive()
                val embedding = unpackEmbedding(face.embedding)
                if (embedding.size != FACE_EMBEDDING_DIM) continue
                if (cosineSimilarity(centroid, embedding) >= threshold) matches += face.id
            }
            if (page.size < HIDE_SWEEP_PAGE) break
            offset += HIDE_SWEEP_PAGE
        }
        if (matches.isNotEmpty()) faceDao.rejectByIds(matches)
        matches.size
    }
}

/** Component-wise mean of unit embeddings, before the direction is renormalised by the caller. */
private fun meanDirection(vectors: List<FloatArray>): FloatArray {
    val dim = vectors.first().size
    val sum = FloatArray(dim)
    for (vector in vectors) for (i in 0 until dim) sum[i] += vector[i]
    val inv = 1f / vectors.size
    return FloatArray(dim) { sum[it] * inv }
}
