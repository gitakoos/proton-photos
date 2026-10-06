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

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import eu.akoos.photos.util.SyncDiagnostics
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.ClusterSummaryDao
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonCoverDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.db.entity.ClusterSummaryEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.face.isConfidentFace
import eu.akoos.photos.data.face.sidewaysFromEncoded
import javax.inject.Inject

/**
 * The diagnostics line for one rebuilt cluster. It takes whether the cluster is named and never the
 * name: a tester pastes the copied log into a public issue, and a person's name must not be in it.
 */
internal fun clusterLogLine(size: Int, named: Boolean): String =
    "  cluster: $size faces, " + if (named) "named" else "unnamed"

/**
 * What one full rebuild of the people did. [committed] is false when its write check refused the rewrite,
 * so nothing was written. Otherwise [leftUngrouped] is how many of the faces it read (removed ones aside)
 * it left without a person: those kept out of a named person they were marked as not being, and those
 * whose stored vector could not be read.
 */
data class FaceRegroupOutcome(val committed: Boolean, val leftUngrouped: Int)

/**
 * Rebuilds the account's people from scratch out of every stored face embedding, one quality-aware
 * pass with a merge step.
 *
 * Every face is graded on its detection score, sharpness and turn, so a weak crop clusters under a
 * stricter bar and cannot bridge two people. Faces group by [clusterFaces]'s exemplar rule, and a
 * final merge folds together clusters that are the same person split in two, which is what removes
 * the "one face shown as several people" duplication. The clearest face of each person becomes its
 * cover.
 *
 * A name the user has given a person is carried onto whichever new cluster inherits most of that
 * person's faces, so a rename survives the rebuild a newly indexed photo triggers rather than
 * reverting to an unnamed cluster.
 *
 * The merge needs every embedding at once, so the pass holds them together, each unpacked to its float
 * vector as its page of rows is read ([loadRegroupInput]); the rows themselves are never all held, so the
 * peak is the vectors plus one page.
 *
 * Both writing passes take a `writeAllowed` check, asked inside their transaction before anything is
 * written: the caller's fence against an import (or a sign-out) that began after the pass read the faces,
 * so a grouping of the faces as they were never lands over what came after. A refused pass writes nothing.
 */
class ClusterFacesUseCase @Inject constructor(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
    private val personCoverDao: PersonCoverDao,
    private val clusterSummaryDao: ClusterSummaryDao,
    private val appDatabase: AppDatabase,
) {
    suspend operator fun invoke(
        userId: UserId?,
        writeAllowed: () -> Boolean = { true },
    ): FaceRegroupOutcome = withContext(Dispatchers.Default) {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER

        // Faces marked NOT a given person ("Is this X? No"): never confirmed for, nor pulled into, that
        // person, no matter how the clustering churns.
        val notPersonByFace = notPersonDao.allForUser(account)
            .groupBy({ it.faceId }, { it.personName }).mapValues { it.value.toHashSet() }
        val input = loadRegroupInput(account, notPersonByFace)
        // Every face a grouping can place (the removed ones never can), so the faces this rebuild leaves
        // without a person can be told apart from the ones indexed after it read the table.
        val placeableFaces = input.placeableFaces
        val ids = input.ids
        val oldPersonIds = input.oldPersonIds
        val manualNames = input.manualNames
        val blockedNames = input.blockedNames
        val samples = input.samples
        val photoKeyById = input.photoKeyById
        // The names the user has given, read before the rebuild clears the person table, so a rename
        // survives the recluster a newly indexed photo triggers.
        val oldNameById = personDao.namedPeopleForUser(account)
            .filter { !it.displayName.isNullOrBlank() }
            .associate { it.id to it.displayName!! }
        // Reverse map, so the cluster that inherits a name REUSES that person's existing id instead of
        // minting a fresh one. Without it every rebuild deletes the row an open person page (or the
        // People rail) is keyed to, so the person looks emptied out until its faces are merged back onto
        // the new id. Names are unique per person, so one id per name.
        val oldIdByName = oldNameById.entries.associate { (id, name) -> name to id }
        // The current "Unsorted" bucket id, read before the rebuild clears the table, so the leftover
        // faces keep the same person id across passes (a review screen open on it does not go stale).
        val oldOtherId = personDao.otherPersonId(account)
        // Manual photos by name (they survive a rebuild), so each person's count is the distinct photos
        // across its faces AND any manually attached photos, not a raw face tally.
        val manualByName = personManualPhotoDao.allForUser(account)
            .groupBy({ it.personName }, { it.photoKey })
            .mapValues { it.value.toHashSet() }
        // The cover photo a user picked per name, resolved to a member face below, so a chosen cover
        // survives the rebuild as long as the person still has a face on that photo.
        val coverByName = personCoverDao.allForUser(account).associate { it.personName to it.photoKey }

        // Cluster off the database (pure CPU), so the transaction below holds only the writes and
        // Room's invalidation fires once, at commit, rather than mid-rebuild. The clusterer polls
        // coroutineContext.ensureActive() so a stop / pause / timeout aborts the CPU-heavy pass.
        val ctx = coroutineContext
        val assignment = if (samples.isEmpty()) null
            else clusterFaces(samples, checkActive = { ctx.ensureActive() })

        // Names: carry the user's given names onto the matching new clusters, then let a cluster
        // holding CONFIRMED faces take that name (a confirmation outranks the carry), claiming the name
        // uniquely so two clusters never share one.
        val nameForCluster = HashMap<Int, String>()
        var finalAssignment: IntArray? = null
        if (assignment != null) {
            // The name follows the cluster holding the most of each person's faces, carry and confirmations
            // counted together, so a lone confirmed suggestion cannot strip the name off the person's bulk.
            val votedNames = resolveClusterNames(oldPersonIds, manualNames, assignment, oldNameById)
            // A confirmed name that won no cluster (its faces still group with a bigger named person) gets
            // a cluster of its own, so a face moved to a new person keeps that name across the rebuild.
            val (namedAssignment, clusterNames) = splitOutUnclaimedNames(manualNames, assignment, votedNames)
            nameForCluster.putAll(clusterNames)
            // Teach from the confirmations: confirmed faces anchor their person and pull matching split
            // faces in, off the database (pure CPU).
            finalAssignment = attractToNamedClusters(
                embeddings = samples.map { it.embedding },
                scores = FloatArray(samples.size) { samples[it].score },
                assignment = namedAssignment,
                clusterName = nameForCluster,
                manualName = manualNames,
                threshold = FACE_CLUSTER_THRESHOLD,
                blockedNames = blockedNames,
            )
        }

        // Face-clustering diagnostics for Settings > Share diagnostics: capture each rebuild's shape
        // (cluster count, named vs unnamed, how many kept a stable id vs got a fresh one), so an odd
        // merge or split is visible after the fact. Filled inside the rewrite, logged after it commits.
        // Each cluster is kept as its size and whether it is named, never its name or id: the copied
        // diagnostics are often posted publicly.
        val createdClusters = ArrayList<Pair<Int, Boolean>>()
        var keptIdCount = 0
        var otherBucketSize = 0
        var placedFaces = 0
        var committed = false

        // A face id to its embedding, so each final person's centroid summary can be built from its
        // members without a re-read. The values are the same arrays already held in samples, so this
        // adds no embedding copies, only the index.
        val embeddingById = HashMap<String, FloatArray>(ids.size)
        for (i in ids.indices) embeddingById[ids[i]] = samples[i].embedding

        // The per-person centroid cache the incremental pass matches new faces against, rebuilt from the
        // final grouping below so the full rebuild is the single source of truth for the summaries.
        val summaryRows = ArrayList<ClusterSummaryEntity>()
        val summaryNow = System.currentTimeMillis()

        // One transaction for the whole rewrite: the people Flow sees a single final list instead of
        // an empty-then-repopulate burst, so the People surfaces do not flicker.
        appDatabase.withTransaction {
            // Asked here, inside the transaction, so nothing the caller fences against can commit between
            // the check and the rewrite.
            if (!writeAllowed()) return@withTransaction
            committed = true
            // Drop the old grouping first: unlink every face and remove the people, so the pass builds
            // a fresh set rather than growing onto stale clusters. The centroid cache is cleared with
            // them, so it never outlives the grouping it summarises (an empty cache is a valid state:
            // the next pass falls back to a full build).
            faceDao.clearAssignments(account)
            personDao.clearForUser(account)
            clusterSummaryDao.clearForUser(account)
            val assign = finalAssignment ?: return@withTransaction

            // Group each cluster's face ids, keeping the clearest face (the score-descending input
            // order) first so it becomes the person's cover.
            val clusterIds = LinkedHashMap<Int, MutableList<String>>()
            for (i in ids.indices) clusterIds.getOrPut(assign[i]) { ArrayList() }.add(ids[i])

            // Keep an UNNAMED cluster's id stable across rebuilds, the way a named person's id is kept via
            // oldIdByName: a new unnamed cluster reuses the id of the old unnamed cluster most of its faces
            // came from. Without this every rebuild mints a fresh id, so any screen or rail keyed to an
            // unnamed cluster goes stale (its grid empties, and an edit targets a now-dead id). Reuse needs
            // a strict majority and never an id already claimed this pass or belonging to a named person.
            val idToOldPerson = HashMap<String, Long>(ids.size)
            for (i in ids.indices) oldPersonIds[i]?.let { idToOldPerson[ids[i]] = it }
            val namedOldIds = oldNameById.keys
            val usedPersonIds = HashSet<Long>()
            // Reserve the old Unsorted-bucket id up front so no ordinary cluster's majority vote can
            // claim it: only the leftover (-1) group below reuses it.
            oldOtherId?.let { usedPersonIds.add(it) }

            for ((cluster, members) in clusterIds) {
                // The unassigned leftover (-1): faces the clusterer could not confidently place. They go
                // into the single stable "Unsorted" bucket, reusing its id across rebuilds, never a named
                // person and (via isOther) excluded from suggestions and index export.
                if (cluster < 0) {
                    val photos = members.mapNotNullTo(HashSet()) { photoKeyById[it] }.size
                    val otherId = personDao.insert(
                        PersonEntity(
                            id = oldOtherId ?: 0L,
                            userId = account,
                            displayName = null,
                            coverFaceId = members.first(),
                            faceCount = photos,
                            isOther = true,
                        ),
                    )
                    faceDao.assignPersonBatch(otherId, members)
                    otherBucketSize = members.size
                    placedFaces += members.size
                    // The Unsorted bucket is summarised too, so the cached member total equals the count
                    // of grouped faces (the incremental pass's self-check). Its centroid is a grab-bag
                    // mean, so the incremental pass excludes this row from the people it matches against.
                    summaryRows.add(summaryFor(otherId, account, members, embeddingById, summaryNow))
                    continue
                }
                val name = nameForCluster[cluster]
                // A face marked as NOT this person never rejoins it, even when the base pass groups
                // it back into the person's cluster: drop those members so a "not this person" removal
                // stays durable instead of the face silently reappearing on the next rebuild.
                val kept = if (name == null) members
                    else members.filter { name !in (notPersonByFace[it] ?: emptySet()) }
                if (kept.isEmpty()) continue
                val facePhotos = kept.mapNotNullTo(HashSet()) { photoKeyById[it] }
                val manualPhotos = name?.let { manualByName[it] } ?: emptySet()
                // Honour a user's chosen cover when its photo is still one of the person's faces;
                // otherwise fall back to the clearest face (the score-descending first member).
                val chosenCover = name?.let { coverByName[it] }
                    ?.let { key -> kept.firstOrNull { photoKeyById[it] == key } }
                val reuseId = if (name != null) {
                    oldIdByName[name]
                } else {
                    val votes = HashMap<Long, Int>()
                    for (fid in kept) {
                        val oid = idToOldPerson[fid] ?: continue
                        if (oid in namedOldIds) continue
                        votes.merge(oid, 1, Int::plus)
                    }
                    votes.entries
                        .filter { it.key !in usedPersonIds && it.value * 2 > kept.size }
                        .maxByOrNull { it.value }?.key
                }
                if (reuseId != null) usedPersonIds.add(reuseId)
                val personId = personDao.insert(
                    PersonEntity(
                        // Keep a person's id stable across the rebuild (0 = a fresh cluster gets a new id).
                        // AUTOINCREMENT means re-inserting an old id never collides with a new one.
                        id = reuseId ?: 0L,
                        userId = account,
                        displayName = name,
                        coverFaceId = chosenCover ?: kept.first(),
                        faceCount = (facePhotos + manualPhotos).size,
                    ),
                )
                faceDao.assignPersonBatch(personId, kept)
                placedFaces += kept.size
                summaryRows.add(summaryFor(personId, account, kept, embeddingById, summaryNow))
                createdClusters.add(kept.size to (name != null))
                if (reuseId != null) keptIdCount++
            }
            // Refill the centroid cache from the final people, so an incremental pass can place a newly
            // indexed face against them without re-reading every embedding.
            clusterSummaryDao.upsertAll(summaryRows)
        }
        if (!committed) return@withContext FaceRegroupOutcome(committed = false, leftUngrouped = 0)

        // The faces this rebuild read but left without a person: those kept out of a named person they
        // were marked as not being, and those whose vector could not be read. They stay ungrouped by
        // design, so the caller measures new faces against this count rather than against zero.
        val leftUngrouped = (placeableFaces - placedFaces).coerceAtLeast(0)
        val namedCount = createdClusters.count { it.second }
        SyncDiagnostics.log(
            "faces: rebuilt ${createdClusters.size} clusters from ${ids.size} faces " +
                "($namedCount named, ${createdClusters.size - namedCount} unnamed, " +
                "$keptIdCount kept id, ${createdClusters.size - keptIdCount} new, $leftUngrouped left ungrouped)",
        )
        if (otherBucketSize > 0) SyncDiagnostics.log("  unsorted bucket: $otherBucketSize faces")
        createdClusters.sortedByDescending { it.first }.take(15).forEach { (size, named) ->
            SyncDiagnostics.log(clusterLogLine(size, named))
        }
        FaceRegroupOutcome(committed = true, leftUngrouped = leftUngrouped)
    }

    /**
     * Places the account's not-yet-grouped faces against the people already held in the centroid cache,
     * the fast path that skips a whole-library rebuild when only a few faces are new. Returns true when
     * it handled the pass incrementally (a "nothing to place" pass counts as handled); returns false when
     * the caller must run a full rebuild instead, on any of four cheap, count-only conditions:
     *
     *   1. the cache is empty (first run, nothing to match against),
     *   2. a cached centroid was produced by an older face pipeline ([FACE_MODEL_VERSION] moved),
     *   3. the cached member total no longer equals the count of grouped faces, so a curation edit
     *      (name / merge / not-a-person / remove) drifted the assignments and left the cache stale, or
     *   4. the unclustered backlog is large (a first scan or a big import), which a global pass groups
     *      better than a one-by-one placement.
     *
     * On the fast path it loads ONLY the cached centroids and ONLY the unclustered faces, never the whole
     * face table, so a huge library's embeddings never sit in memory at once. Each new face is assigned to
     * its single best-matching person over the join floor via [assignIncremental] and folded into that
     * person's running centroid; a face that matches no existing person is left unclustered for the next
     * full rebuild to form a new person from. So this never opens a cluster, bridges two people, or
     * disturbs an existing assignment. The Unsorted bucket is excluded from the people matched against, so
     * a fresh face is never absorbed into the grab-bag. Guest-safe via the [PhotoLocationEntity.LOCAL_USER]
     * account sentinel. A placement its [writeAllowed] check refuses writes nothing and returns false, so
     * the caller leaves the faces to a rebuild of the data as it now stands.
     */
    suspend fun assignNewFaces(
        userId: UserId?,
        writeAllowed: () -> Boolean = { true },
    ): Boolean = withContext(Dispatchers.Default) {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER

        // (1) No cache yet: nothing to match against, so a full build must seed it first.
        if (clusterSummaryDao.countForUser(account) == 0) return@withContext false
        val summaries = clusterSummaryDao.getAllForUser(account)
        // (2) A cached centroid from an older pipeline: a full rebuild re-embeds and refills.
        if (summaries.any { it.modelVersion != FACE_MODEL_VERSION }) return@withContext false
        // (3) The cache no longer sums to the grouped faces, so a curation edit drifted the assignments
        //     and the summaries are stale. One self-validating check standing in for scattered invalidation.
        if (summaries.sumOf { it.memberCount } != faceDao.assignedFaceCount(account)) return@withContext false
        // (4) A large unclustered backlog: a first scan or a big import groups better in one global pass.
        val backlog = faceDao.unclusteredCount(account)
        if (backlog > INCREMENTAL_MAX_BACKLOG) return@withContext false
        // Nothing waiting: handled, no rebuild needed.
        if (backlog == 0) return@withContext true

        // The people to match against, minus the Unsorted bucket: its centroid is a grab-bag mean, and
        // pulling a fresh face into Unsorted would rob the next rebuild of the chance to form a real
        // person from it.
        val otherId = personDao.otherPersonId(account)
        val matchable = summaries.filter { it.personId != otherId }
        if (matchable.isEmpty()) return@withContext true
        val centroids = ArrayList<FloatArray>(matchable.size)
        for (row in matchable) {
            val c = unpackEmbedding(row.centroid)
            // A stale-width cached centroid cannot be matched against safely, so defer to a full rebuild.
            if (c.size != FACE_EMBEDDING_DIM) return@withContext false
            centroids.add(c)
        }

        // Only the unclustered remainder is read, graded exactly as the full pass grades a face so a weak
        // crop clears a stricter bar (see samplesForSweep).
        val rows = faceDao.unclusteredFaces(account)
        val idBySample = ArrayList<String>(rows.size)
        val samples = ArrayList<FaceSample>(rows.size)
        for (row in rows) {
            val embedding = unpackEmbedding(row.embedding)
            if (embedding.size != FACE_EMBEDDING_DIM) continue
            idBySample.add(row.id)
            samples.add(
                FaceSample(
                    embedding = embedding,
                    score = row.score,
                    confident = isConfidentFace(
                        detectionScore = row.score,
                        blur = row.blur?.toDouble(),
                        sideways = sidewaysFromEncoded(row.landmarks),
                    ),
                ),
            )
        }
        if (samples.isEmpty()) return@withContext true

        // The curation the match must honour: a face the user marked "not this person" is never
        // auto-rejoined to that person. The block is keyed by name, so this vetoes only a match to a named
        // person the face is blocked from, mirroring the filter the full rebuild applies to a named
        // cluster's members. A blocked face then stays unclustered for the full rebuild (and the suggestion
        // flow) to place.
        val blockedNamesByFace = notPersonDao.allForUser(account)
            .groupBy({ it.faceId }, { it.personName }).mapValues { it.value.toHashSet() }
        val nameByPersonId = personDao.namedPeopleForUser(account)
            .filter { !it.displayName.isNullOrBlank() }
            .associate { it.id to it.displayName!! }

        coroutineContext.ensureActive()
        val assignment = assignIncremental(samples, centroids)

        // Group the matched faces by the centroid they joined, so each touched person's summary is folded
        // and rewritten once. A no-match (-1) face, or one vetoed by a "not this person" block, is left
        // unclustered.
        val matchedByCentroid = LinkedHashMap<Int, MutableList<Int>>()
        for (s in assignment.indices) {
            val c = assignment[s]
            if (c < 0) continue
            val personName = nameByPersonId[matchable[c].personId]
            if (personName != null && personName in (blockedNamesByFace[idBySample[s]] ?: emptySet())) continue
            matchedByCentroid.getOrPut(c) { ArrayList() }.add(s)
        }

        // Faces that matched no existing person and are clear enough to anchor a cluster are grouped among
        // THEMSELVES into new people here, so a fresh face (say ten new photos of someone not seen before)
        // forms its own nameable cluster on the spot, without a whole-library regroup. A weak no-match is
        // left unplaced for the full pass to route to Unsorted, and a face vetoed by a "not this person"
        // block kept a matched (>=0) assignment so it is not swept in here, exactly as the full pass leaves
        // it. New clusters are provisional: the next full regroup reconciles them globally.
        val newFaceLocalIdx = samples.indices.filter { assignment[it] == -1 && samples[it].confident }
        val newFaceSamples = newFaceLocalIdx.map { samples[it] }
        val newGrouping = if (newFaceSamples.isEmpty()) IntArray(0) else clusterFaces(newFaceSamples)
        val newGroups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in newGrouping.indices) {
            val g = newGrouping[i]
            if (g < 0) continue
            newGroups.getOrPut(g) { ArrayList() }.add(i)
        }

        if (matchedByCentroid.isEmpty() && newGroups.isEmpty()) return@withContext true

        val faceIdsByPerson = ArrayList<Pair<Long, List<String>>>(matchedByCentroid.size)
        val updatedSummaries = ArrayList<ClusterSummaryEntity>(matchedByCentroid.size)
        val now = System.currentTimeMillis()
        for ((c, memberSamples) in matchedByCentroid) {
            val row = matchable[c]
            var centroid = centroids[c]
            var count = row.memberCount
            for (s in memberSamples) {
                centroid = incrementalCentroid(centroid, count, samples[s].embedding)
                count++
            }
            faceIdsByPerson.add(row.personId to memberSamples.map { idBySample[it] })
            updatedSummaries.add(
                ClusterSummaryEntity(
                    personId = row.personId,
                    userId = account,
                    centroid = packEmbedding(centroid),
                    memberCount = count,
                    modelVersion = FACE_MODEL_VERSION,
                    updatedAt = now,
                ),
            )
        }

        // Each new group's face ids and its unit-mean centroid, ready to insert as a fresh unnamed person
        // in the transaction (the new personId is minted there, so its faces and its summary land together).
        val newClusters = newGroups.values.map { members ->
            val faceIds = members.map { idBySample[newFaceLocalIdx[it]] }
            var centroid = FloatArray(FACE_EMBEDDING_DIM)
            var count = 0
            for (m in members) {
                centroid = incrementalCentroid(centroid, count, newFaceSamples[m].embedding)
                count++
            }
            faceIds to normalize(centroid)
        }

        // One transaction: the faces gain their person link (existing or newly minted) and every touched or
        // created summary is written together, so the cache stays in step with the assignments (its member
        // total keeps matching [assignedFaceCount], the incremental pass's self-check).
        var placed = false
        appDatabase.withTransaction {
            // Asked inside the transaction, as the full rebuild does, so the faces and people read above
            // cannot be replaced between the check and the write.
            if (!writeAllowed()) return@withTransaction
            placed = true
            for ((personId, faceIds) in faceIdsByPerson) faceDao.assignPersonBatch(personId, faceIds)
            if (updatedSummaries.isNotEmpty()) clusterSummaryDao.upsertAll(updatedSummaries)
            for ((faceIds, centroid) in newClusters) {
                val newPersonId = personDao.insert(PersonEntity(userId = account, displayName = null))
                faceDao.assignPersonBatch(newPersonId, faceIds)
                clusterSummaryDao.upsertAll(
                    listOf(
                        ClusterSummaryEntity(
                            personId = newPersonId,
                            userId = account,
                            centroid = packEmbedding(centroid),
                            memberCount = faceIds.size,
                            modelVersion = FACE_MODEL_VERSION,
                            updatedAt = now,
                        ),
                    ),
                )
            }
        }
        if (!placed) return@withContext false
        SyncDiagnostics.log(
            "faces: incremental placed ${faceIdsByPerson.sumOf { it.second.size }} onto " +
                "${faceIdsByPerson.size} people and formed ${newClusters.size} new clusters " +
                "(${matchable.size} candidates)",
        )
        true
    }

    /** One person's centroid summary: the L2-normalised mean of its member faces' embeddings, packed
     *  little-endian to match the face embedding layout, with the member face count. */
    private fun summaryFor(
        personId: Long,
        account: String,
        faceIds: List<String>,
        embeddingById: Map<String, FloatArray>,
        now: Long,
    ): ClusterSummaryEntity = ClusterSummaryEntity(
        personId = personId,
        userId = account,
        centroid = packEmbedding(normalizedMeanOf(faceIds.mapNotNull { embeddingById[it] })),
        memberCount = faceIds.size,
        modelVersion = FACE_MODEL_VERSION,
        updatedAt = now,
    )

    /** The L2-normalised mean direction of the given embeddings, a cluster's centroid. An empty or
     *  all-zero input yields a zero vector (no direction), which [normalize] maps safely. */
    private fun normalizedMeanOf(embeddings: List<FloatArray>): FloatArray {
        val sum = FloatArray(FACE_EMBEDDING_DIM)
        for (e in embeddings) {
            if (e.size != FACE_EMBEDDING_DIM) continue
            for (i in 0 until FACE_EMBEDDING_DIM) sum[i] += e[i]
        }
        return normalize(sum)
    }

    /** Packs an embedding little-endian, byte-for-byte the layout [unpackEmbedding] reads and the indexer
     *  writes into a face row, so a stored centroid round-trips through the same codec as a face. */
    private fun packEmbedding(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        return buffer.array()
    }

    /**
     * The exact [FaceSample] list [invoke] clusters, so a debug threshold sweep can re-run
     * [clusterFaces] over the real stored embeddings without a re-embed. Mirrors [invoke]'s per-face
     * grading: rejected faces dropped, stale-width rows skipped, weak crops marked. Diagnostic only.
     */
    suspend fun samplesForSweep(userId: UserId): List<FaceSample> = withContext(Dispatchers.Default) {
        loadRegroupInput(userId.id, emptyMap()).samples.map { FaceSample(it.embedding, it.score, it.confident) }
    }

    /**
     * The faces a full regroup clusters, read a page of [REGROUP_PAGE_SIZE] rows at a time. Each row's
     * embedding is unpacked and its quality graded as its page arrives, then the page is let go, so the
     * rows and the unpacked vectors are never all held together. The faces are then put in the order the
     * clusterer reads them, clearest detection first ([REGROUP_ORDER]), and reduced to the parallel lists
     * [invoke] works from: removed faces never appear, and a face whose vector cannot be read counts
     * toward [RegroupInput.placeableFaces] but is not clustered. [notPersonByFace] holds the names each
     * face was marked as not being.
     */
    internal suspend fun loadRegroupInput(
        account: String,
        notPersonByFace: Map<String, Set<String>>,
    ): RegroupInput {
        val faces = ArrayList<RegroupFace>()
        var placeable = 0
        var afterId = ""
        while (true) {
            val page = faceDao.regroupFacesAfter(account, afterId, REGROUP_PAGE_SIZE)
            for (row in page) {
                placeable++
                val embedding = unpackEmbedding(row.embedding)
                if (embedding.size != FACE_EMBEDDING_DIM) continue
                faces.add(
                    RegroupFace(
                        id = row.id,
                        photoKey = row.photoKey,
                        personId = row.personId,
                        manualName = row.manualName,
                        score = row.score,
                        confident = isConfidentFace(
                            detectionScore = row.score,
                            blur = row.blur?.toDouble(),
                            sideways = sidewaysFromEncoded(row.landmarks),
                        ),
                        embedding = embedding,
                    ),
                )
            }
            if (page.size < REGROUP_PAGE_SIZE) break
            afterId = page.last().id
            coroutineContext.ensureActive()
        }
        faces.sortWith(REGROUP_ORDER)

        val ids = ArrayList<String>(faces.size)
        val oldPersonIds = ArrayList<Long?>(faces.size)
        val manualNames = ArrayList<String?>(faces.size)
        val blockedNames = ArrayList<Set<String>>(faces.size)
        val samples = ArrayList<FaceSample>(faces.size)
        val photoKeyById = HashMap<String, String>(faces.size)
        for (face in faces) {
            ids.add(face.id)
            oldPersonIds.add(face.personId)
            photoKeyById[face.id] = face.photoKey
            val blocked = notPersonByFace[face.id] ?: emptySet()
            blockedNames.add(blocked)
            // Confirmed name: ONLY the explicit label the user set by confirming a face they actually
            // saw (the suggestion review), and never a person this face was told it is NOT. A single
            // detected face on a manually attached photo is deliberately NOT treated as a confirmation:
            // when the person's own face is too distant to detect, that lone face is usually a bystander,
            // and trusting it poisons the person's centroid. Manual attachments stay display-only.
            val confirmedName = face.manualName?.takeIf { it.isNotBlank() && it !in blocked }
            manualNames.add(confirmedName)
            // A confirmed face may seed a cluster even when its crop is weak, so a person the user has
            // named is never left to fall into the Unsorted bucket for want of a confident crop.
            samples.add(FaceSample(face.embedding, face.score, face.confident, confirmed = confirmedName != null))
        }
        return RegroupInput(ids, oldPersonIds, manualNames, blockedNames, samples, photoKeyById, placeable)
    }

    private companion object {
        /** Above this many not-yet-grouped faces, the incremental pass defers to a full rebuild: a first
         *  scan or a big import forms cleaner people in one global pass than by placing faces one at a
         *  time against the existing centroids. A handful of newly indexed faces stays on the fast path. */
        const val INCREMENTAL_MAX_BACKLOG = 500

        /** Rows per page of the full regroup's read: about a megabyte of blobs, inside one cursor window. */
        const val REGROUP_PAGE_SIZE = 400
    }
}

/**
 * What a full regroup clusters, in the clusterer's input order: per face its id, the person it was
 * grouped under, its confirmed name, the names it was marked as not being and its clustering sample,
 * plus each face's photo. [placeableFaces] counts every kept face, including those whose vector could
 * not be read.
 */
internal class RegroupInput(
    val ids: ArrayList<String>,
    val oldPersonIds: ArrayList<Long?>,
    val manualNames: ArrayList<String?>,
    val blockedNames: ArrayList<Set<String>>,
    val samples: ArrayList<FaceSample>,
    val photoKeyById: HashMap<String, String>,
    val placeableFaces: Int,
)

/** One kept face as the regroup holds it once its row is let go: the graded sample's parts and the
 *  bookkeeping the rewrite needs, with no box, owner or landmark text. */
internal class RegroupFace(
    val id: String,
    val photoKey: String,
    val personId: Long?,
    val manualName: String?,
    val score: Float,
    val confident: Boolean,
    val embedding: FloatArray,
)

/**
 * The clusterer's input order, the same total order SQLite's `ORDER BY score DESC, id` gives: the higher
 * score first, compared as numbers (so 0.0 and -0.0 tie), then the ids in code-point order, which is the
 * byte order of their UTF-8 text that SQLite's default collation compares. Ids are unique, so no two
 * faces tie.
 */
internal val REGROUP_ORDER: Comparator<RegroupFace> = Comparator { a, b ->
    when {
        a.score > b.score -> -1
        a.score < b.score -> 1
        else -> compareCodePoints(a.id, b.id)
    }
}

/** [a] against [b] by Unicode code point, the order UTF-8 bytes sort in; it differs from [String.compareTo]
 *  only where a surrogate pair meets a character above it in the basic plane. */
internal fun compareCodePoints(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a.codePointAt(i)
        val cb = b.codePointAt(j)
        if (ca != cb) return ca.compareTo(cb)
        i += Character.charCount(ca)
        j += Character.charCount(cb)
    }
    return (a.length - i).compareTo(b.length - j)
}
