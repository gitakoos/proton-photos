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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.model.FaceBoxNorm
import eu.akoos.photos.domain.model.PersonSummary
import javax.inject.Inject

/** Mirrors the face indexer's power-of-two downsample so a stored box maps back to fractions. MUST
 *  equal `FaceIndexingScheduler.SOURCE_MAX_EDGE`: the detector runs on the bitmap bounded to that edge
 *  and stores the box in its pixel space, so reconstructing the bounded size with a different bound
 *  scales the box fractions wrong (up to 2x for a range of photo sizes) and misplaces the face crop. */
private const val FACE_SOURCE_MAX_EDGE = 1600

/**
 * Observes the account's clustered people and resolves each to a [PersonSummary]: cover face, display
 * name, face count and a normalised cover-face box. People arrive most-photographed first from
 * [PersonDao]; a person whose cover face no longer resolves, or whose cover photo has left the
 * library, is dropped, so the list matches the People rail the gallery has always shown. Reusable by
 * every people surface (the rail, an album picker, a person-detail screen) so the resolution and
 * ordering live in one place.
 *
 * The cover-face box is normalised against the cover photo's own dimensions, which the face and person
 * tables do not carry. The caller passes its already-loaded feed as [items] so the lookup reads
 * dimensions it already holds rather than re-decoding an image; a cover photo absent from that feed (a
 * cloud-only photo off the device) keeps a null box and its tile shows the whole cover. Recombined
 * with the feed, so a box appears once its cover photo loads and does not rebuild on a thumbnail
 * decrypt, since the feed changes only when photos are added or removed.
 */
class ObservePeopleUseCase @Inject constructor(
    private val personDao: PersonDao,
    private val faceDao: FaceDao,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        userId: UserId,
        items: Flow<List<GalleryItem>>,
    ): Flow<List<PersonSummary>> =
        combine(personDao.observePeopleForUser(userId.id), items) { people, feed -> people to feed }
            .mapLatest { (people, feed) -> buildSummaries(people, feed) }

    /** Resolve each person's cover face to a [PersonSummary], normalising the cover face box against
     *  the cover photo's dimensions when they are known (a device or backed-up-on-device photo); a
     *  cloud-only cover with no local dimensions keeps a null box and its tile shows the whole cover. */
    private suspend fun buildSummaries(
        people: List<PersonEntity>,
        items: List<GalleryItem>,
    ): List<PersonSummary> {
        val covers = people.mapNotNull { person ->
            val coverFaceId = person.coverFaceId ?: return@mapNotNull null
            val face = runCatching { faceDao.faceById(coverFaceId) }.getOrNull() ?: return@mapNotNull null
            person to face
        }
        if (covers.isEmpty()) return emptyList()
        // One pass over the feed, collecting dimensions for the handful of cover photos and noting
        // which of those photos the library still holds, so the lookup stays memory-light on a large
        // library (no full stableId to dimensions map). Off the main thread since the feed can hold
        // tens of thousands of items.
        val neededKeys = covers.mapTo(HashSet()) { it.second.photoKey }
        val (coverDims, present) = withContext(Dispatchers.Default) {
            val dims = HashMap<String, Pair<Int, Int>>()
            val seen = HashSet<String>()
            for (item in items) {
                val key = item.stableId
                if (key !in neededKeys) continue
                seen.add(key)
                if (key in dims) continue
                val d = when (item) {
                    is GalleryItem.LocalOnly -> item.local.width to item.local.height
                    is GalleryItem.Synced -> item.local.width to item.local.height
                    is GalleryItem.CloudOnly -> null
                }
                if (d != null && d.first > 0 && d.second > 0) dims[key] = d
            }
            dims to seen
        }
        // Drop a person whose cover photo has left the library (its source photo was deleted), so a
        // stale face embedding cannot surface a ghost person with an unloadable cover. Skipped while
        // the feed is still empty, so people are not hidden mid-load.
        val live = if (items.isEmpty()) covers else covers.filter { it.second.photoKey in present }
        return live.map { (person, face) ->
            PersonSummary(
                personId = person.id,
                displayName = person.displayName,
                coverPhotoKey = face.photoKey,
                faceBox = normalizedFaceBox(face, coverDims[face.photoKey]),
                faceCount = person.faceCount,
            )
        }
    }

    /** The stored face box as a 0..1 fraction of the cover image. The current indexer already stores
     *  the box as a fraction, usable directly and even for a cloud-only cover whose pixel size is
     *  unknown (which is why a cloud cover used to show the whole group photo). An older face stored
     *  detector pixels of the bounded decode (a coordinate exceeds 1); those are reconstructed from the
     *  cover photo's dimensions, when the feed knows them, by mirroring the indexer's downsample. */
    private fun normalizedFaceBox(face: FaceEntity, dims: Pair<Int, Int>?): FaceBoxNorm? {
        val isFraction = face.left <= 1f && face.top <= 1f && face.right <= 1f && face.bottom <= 1f
        if (isFraction) {
            if (face.right <= face.left || face.bottom <= face.top) return null
            return FaceBoxNorm(
                face.left.coerceIn(0f, 1f), face.top.coerceIn(0f, 1f),
                face.right.coerceIn(0f, 1f), face.bottom.coerceIn(0f, 1f),
            )
        }
        val (originalWidth, originalHeight) = dims ?: return null
        val sample = faceSampleSize(originalWidth, originalHeight)
        val boundedW = (originalWidth / sample).toFloat()
        val boundedH = (originalHeight / sample).toFloat()
        if (boundedW <= 0f || boundedH <= 0f) return null
        val left = (face.left / boundedW).coerceIn(0f, 1f)
        val top = (face.top / boundedH).coerceIn(0f, 1f)
        val right = (face.right / boundedW).coerceIn(0f, 1f)
        val bottom = (face.bottom / boundedH).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        return FaceBoxNorm(left, top, right, bottom)
    }

    /** Mirrors the indexer's power-of-two downsample so a stored box maps back to fractions. */
    private fun faceSampleSize(width: Int, height: Int): Int {
        val longEdge = maxOf(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= FACE_SOURCE_MAX_EDGE) sample *= 2
        return sample
    }
}
