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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.FaceOnPhoto
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.model.FaceBoxNorm
import eu.akoos.photos.domain.model.PersonSummary
import eu.akoos.photos.util.FaceDiagnostics
import eu.akoos.photos.util.retryOnDbTear
import java.util.concurrent.atomic.AtomicReference
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
 *
 * Each list is built to the end before the next input is taken ([mapCompleting]): while a sync keeps the
 * feed and the person table changing faster than one build takes, a build cancelled by every new input
 * would never deliver a list at all. After each build it rests in proportion to what the build cost, so a
 * library that keeps changing (a sync, a grouping under way) never has the list rebuilt back to back.
 */
class ObservePeopleUseCase @Inject constructor(
    private val personDao: PersonDao,
    private val faceDao: FaceDao,
) {

    /**
     * The account's people as summaries; [namedOnly] reads only the people carrying a name
     * ([PersonDao.observeNamedPeopleForUser]), for a surface that keeps no other, with the same people in the
     * same order once the caller has dropped the blank names. The copied diagnostics time the full builds only.
     */
    operator fun invoke(
        userId: UserId?,
        items: Flow<List<GalleryItem>>,
        namedOnly: Boolean = false,
    ): Flow<List<PersonSummary>> {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        val keysMemo = AtomicReference<Pair<List<GalleryItem>, Set<String>>?>(null)
        val rows = if (namedOnly) personDao.observeNamedPeopleForUser(account) else personDao.observePeopleForUser(account)
        return combine(rows.retryOnDbTear(TEAR_TAG, onRetry = { FaceDiagnostics.recordDbTear(TEAR_TAG, it) }), items) { people, feed ->
            people to feed
        }
            .mapCompleting { (people, feed) ->
                if (!namedOnly) FaceDiagnostics.recordPeopleBuildStarted()
                val startNs = System.nanoTime()
                val build = buildSummaries(account, people, feed, keysMemo)
                if (!namedOnly) {
                    FaceDiagnostics.recordPeopleBuildCompleted(
                        durationMs = (System.nanoTime() - startNs) / NANOS_PER_MS,
                        people = build.summaries.size,
                        fallbacks = build.fallbacks,
                        storedReadMs = build.storedReadNs / NANOS_PER_MS,
                        fallbackMs = build.fallbackNs / NANOS_PER_MS,
                    )
                }
                build.summaries
            }
            .distinctUntilChanged()
    }

    /** Resolve each person's cover face to a [PersonSummary], normalising the cover face box against
     *  the cover photo's dimensions when they are known (a device or backed-up-on-device photo); a
     *  cloud-only cover with no local dimensions keeps a null box and its tile shows the whole cover. */
    private suspend fun buildSummaries(
        account: String,
        people: List<PersonEntity>,
        items: List<GalleryItem>,
        keysMemo: AtomicReference<Pair<List<GalleryItem>, Set<String>>?>,
    ): PeopleBuild {
        if (people.isEmpty()) return PeopleBuild(emptyList())
        val feedEmpty = items.isEmpty()
        // A set of the feed's own stableId strings (references, not copies) for liveness checks, kept while
        // the same feed list comes round again (a person-table write alone). Off the main thread since the
        // feed can hold tens of thousands of items. A backed-up photo's device uri counts too: faces scanned
        // under the uri keep it once the photo pairs with its cloud copy.
        val feedKeys: Set<String> = if (feedEmpty) emptySet()
        else keysMemo.get()?.takeIf { it.first === items }?.second
            ?: withContext(Dispatchers.Default) { liveKeys(items) }.also { keysMemo.set(items to it) }
        // Resolve each person to a cover face whose photo is still in the library: the stored cover when
        // it is live, else the person's next available face. Losing the cover photo (hidden, vaulted,
        // deleted, reaped) then reshuffles the cover instead of hiding the whole person; only a person
        // with no live face left is dropped. While the feed is still loading, keep the stored cover and
        // drop nobody. The stored covers are read together in a few bounded statements, with no vectors;
        // the per-person fallback runs only where the stored cover is missing or off the feed.
        var storedReadNs = 0L
        var fallbackNs = 0L
        var fallbacks = 0
        val covers = withContext(Dispatchers.Default) {
            val readStart = System.nanoTime()
            val storedById = runCatching { faceDao.facesByIds(account, people.mapNotNull { it.coverFaceId }) }
                .getOrNull()?.associateBy { it.id } ?: emptyMap()
            storedReadNs = System.nanoTime() - readStart
            people.mapNotNull { person ->
                val stored = person.coverFaceId?.let { storedById[it] }
                val fallback: suspend () -> FaceOnPhoto? = {
                    fallbacks++
                    val fallbackStart = System.nanoTime()
                    runCatching { faceDao.facesForPersonLite(account, person.id) }.getOrNull()
                        ?.firstOrNull { it.photoKey in feedKeys }
                        .also { fallbackNs += System.nanoTime() - fallbackStart }
                }
                val cover = chooseLiveCoverFace(stored, feedKeys, feedEmpty, { it.photoKey }, fallback)
                    ?: return@mapNotNull null
                person to cover
            }
        }
        if (covers.isEmpty()) return PeopleBuild(emptyList(), fallbacks, storedReadNs, fallbackNs)
        // One light pass for the chosen covers' dimensions (early-continue on every other item), only when a
        // cover holds an older pixel box: a fraction box needs none. A backed-up photo answers to its uri too.
        val neededKeys = covers.filter { !isFractionBox(it.second) }.mapTo(HashSet()) { it.second.photoKey }
        val coverDims = if (neededKeys.isEmpty()) emptyMap() else withContext(Dispatchers.Default) {
            val dims = HashMap<String, Pair<Int, Int>>()
            for (item in items) {
                val d = when (item) {
                    is GalleryItem.LocalOnly -> item.local.width to item.local.height
                    is GalleryItem.Synced -> item.local.width to item.local.height
                    is GalleryItem.CloudOnly -> null
                }
                if (d == null || d.first <= 0 || d.second <= 0) continue
                val key = item.stableId
                if (key in neededKeys && key !in dims) dims[key] = d
                if (item is GalleryItem.Synced) {
                    val uri = item.local.uri
                    if (uri in neededKeys && uri !in dims) dims[uri] = d
                }
            }
            dims
        }
        val summaries = covers.map { (person, face) ->
            PersonSummary(
                personId = person.id,
                displayName = person.displayName,
                coverPhotoKey = face.photoKey,
                faceBox = normalizedFaceBox(face.boxLeft, face.boxTop, face.boxRight, face.boxBottom, coverDims[face.photoKey]),
                faceCount = person.faceCount,
                isOther = person.isOther,
            )
        }
        return PeopleBuild(summaries, fallbacks, storedReadNs, fallbackNs)
    }

    /** The keys a cover photo is live under: every item's stableId, and a backed-up photo's device uri. */
    private fun liveKeys(items: List<GalleryItem>): Set<String> {
        val keys = HashSet<String>(items.size + items.size / 4)
        for (item in items) {
            keys.add(item.stableId)
            if (item is GalleryItem.Synced) keys.add(item.local.uri)
        }
        return keys
    }

    private fun isFractionBox(face: FaceOnPhoto): Boolean =
        face.boxLeft <= 1f && face.boxTop <= 1f && face.boxRight <= 1f && face.boxBottom <= 1f

    /** The stored face box as a 0..1 fraction of the cover image. The current indexer already stores
     *  the box as a fraction, usable directly and even for a cloud-only cover whose pixel size is
     *  unknown (which is why a cloud cover used to show the whole group photo). An older face stored
     *  detector pixels of the bounded decode (a coordinate exceeds 1); those are reconstructed from the
     *  cover photo's dimensions, when the feed knows them, by mirroring the indexer's downsample. */
    private fun normalizedFaceBox(
        faceLeft: Float,
        faceTop: Float,
        faceRight: Float,
        faceBottom: Float,
        dims: Pair<Int, Int>?,
    ): FaceBoxNorm? {
        val isFraction = faceLeft <= 1f && faceTop <= 1f && faceRight <= 1f && faceBottom <= 1f
        if (isFraction) {
            if (faceRight <= faceLeft || faceBottom <= faceTop) return null
            return FaceBoxNorm(
                faceLeft.coerceIn(0f, 1f), faceTop.coerceIn(0f, 1f),
                faceRight.coerceIn(0f, 1f), faceBottom.coerceIn(0f, 1f),
            )
        }
        val (originalWidth, originalHeight) = dims ?: return null
        val sample = faceSampleSize(originalWidth, originalHeight)
        val boundedW = (originalWidth / sample).toFloat()
        val boundedH = (originalHeight / sample).toFloat()
        if (boundedW <= 0f || boundedH <= 0f) return null
        val left = (faceLeft / boundedW).coerceIn(0f, 1f)
        val top = (faceTop / boundedH).coerceIn(0f, 1f)
        val right = (faceRight / boundedW).coerceIn(0f, 1f)
        val bottom = (faceBottom / boundedH).coerceIn(0f, 1f)
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

/** One build of the people list and what it cost: how many people needed the per-person fallback, and the
 *  nanoseconds spent reading the stored covers and in the fallbacks. */
private class PeopleBuild(
    val summaries: List<PersonSummary>,
    val fallbacks: Int = 0,
    val storedReadNs: Long = 0L,
    val fallbackNs: Long = 0L,
)

private const val NANOS_PER_MS = 1_000_000L

/** The people stream's label in the logs and the copied diagnostics. */
private const val TEAR_TAG = "ObservePeople"

/** The least and the most a people-list build rests after it, and how many times its own run time it rests
 *  in between. The least also caps a list that keeps changing at four builds a second. */
internal const val PEOPLE_BUILD_REST_MIN_MS = 250L
internal const val PEOPLE_BUILD_REST_MAX_MS = 10_000L
internal const val PEOPLE_BUILD_REST_FACTOR = 3L

/** How long a people-list build that ran [elapsedMs] rests before the next one: [PEOPLE_BUILD_REST_FACTOR]
 *  times its run time, within [PEOPLE_BUILD_REST_MIN_MS] and [PEOPLE_BUILD_REST_MAX_MS], so the builds take
 *  at most about a quarter of the time however often the library changes. */
internal fun peopleBuildRestMs(elapsedMs: Long): Long =
    (PEOPLE_BUILD_REST_FACTOR * elapsedMs).coerceIn(PEOPLE_BUILD_REST_MIN_MS, PEOPLE_BUILD_REST_MAX_MS)

/**
 * [build] over each value, each run to its end before the next value is taken, the values that arrived
 * meanwhile collapsed to the latest. Unlike `mapLatest`, a source that changes faster than [build] takes
 * still delivers: a run is never cancelled by the value after it. Each result is emitted first, and then
 * the next run waits [restAfter] the run's own time, read off [nowMs] (a monotonic clock), so a source that
 * never settles cannot keep [build] running back to back; the newest value is taken once the rest is over.
 */
internal fun <A, B> Flow<A>.mapCompleting(
    nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    restAfter: (elapsedMs: Long) -> Long = ::peopleBuildRestMs,
    build: suspend (A) -> B,
): Flow<B> = conflate().transform { value ->
    val startMs = nowMs()
    val result = build(value)
    val elapsedMs = nowMs() - startMs
    emit(result)
    delay(restAfter(elapsedMs))
}

/**
 * The cover face to show for a person: the stored cover when its photo is still in the library, else
 * the [fallback] (the person's next live face), else null so the caller drops a person with no live
 * face. While the feed is still loading ([feedEmpty]) the stored cover is kept as-is so people are not
 * reshuffled or hidden mid-load. [photoKeyOf] reads a face's photo.
 */
internal suspend fun <F : Any> chooseLiveCoverFace(
    stored: F?,
    feedKeys: Set<String>,
    feedEmpty: Boolean,
    photoKeyOf: (F) -> String,
    fallback: suspend () -> F?,
): F? {
    if (feedEmpty) return stored
    if (stored != null && photoKeyOf(stored) in feedKeys) return stored
    return fallback()
}

/** [chooseLiveCoverFace] over stored face rows. */
internal suspend fun chooseLiveCoverFace(
    stored: FaceEntity?,
    feedKeys: Set<String>,
    feedEmpty: Boolean,
    fallback: suspend () -> FaceEntity?,
): FaceEntity? = chooseLiveCoverFace(stored, feedKeys, feedEmpty, { it.photoKey }, fallback)
