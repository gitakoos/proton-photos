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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * The cursor of a batched incremental placement: each [ClusterFacesUseCase.assignNewFaces] call reads at
 * most [limit] ungrouped faces after [afterId], then advances [afterId] past them and reports in [scanned]
 * how many it read, so the caller loops until a call reads none.
 */
class IncrementalBatch(val limit: Int) {
    var afterId: String = ""
    var scanned: Int = 0

    /** Faces this run has placed so far (onto an existing person or into a new one) and the people it has
     *  opened, summed over its calls for the one summary line the caller logs. */
    var placed: Int = 0
    var created: Int = 0
}

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
        batch: IncrementalBatch? = null,
        writeAllowed: () -> Boolean = { true },
    ): Boolean = withContext(Dispatchers.Default) {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER

        var summaries = clusterSummaryDao.getAllForUser(account)
        if (batch == null) {
            // (1) No cache yet: nothing to match against, so a full build must seed it first.
            if (summaries.isEmpty()) return@withContext false
            // (2) A cached centroid from an older pipeline: a full rebuild re-embeds and refills.
            if (summaries.any { it.modelVersion != FACE_MODEL_VERSION }) return@withContext false
            // (3) The cache no longer sums to the grouped faces, so a curation edit drifted the assignments
            //     and the summaries are stale. One self-validating check standing in for scattered invalidation.
            if (summaries.sumOf { it.memberCount } != faceDao.assignedFaceCount(account)) return@withContext false
        } else if (!cacheDescribesGroups(summaries, faceDao.assignedFaceCount(account))) {
            // A [batch] run is how a library too big for the whole rebuild gets its people, so a cache that
            // is missing, from an older pipeline or off the grouped faces is rebuilt from the assignments
            // (one person's faces at a time) rather than refused. No grouped faces and no cache is a valid
            // start: the first batch then opens the first people itself.
            if (!rebuildSummaries(account, writeAllowed)) return@withContext false
            summaries = clusterSummaryDao.getAllForUser(account)
            if (!cacheDescribesGroups(summaries, faceDao.assignedFaceCount(account))) return@withContext false
        }
        // (4) A large unclustered backlog: a first scan or a big import groups better in one global pass.
        // A [batch] run is the answer to that backlog (the whole-library pass did not fit the heap), so it
        // skips this bail-out and reads the backlog a bounded slice at a time instead.
        val backlog = faceDao.unclusteredCount(account)
        if (batch == null && backlog > INCREMENTAL_MAX_BACKLOG) return@withContext false
        // Nothing waiting: handled, no rebuild needed.
        if (backlog == 0) return@withContext true

        // The people to match against, minus the Unsorted bucket: its centroid is a grab-bag mean, and
        // pulling a fresh face into Unsorted would rob the next rebuild of the chance to form a real
        // person from it. A batched run with nobody to match against carries on: every confident face of
        // the batch then groups among its own, which is how the first people of a big library appear.
        val otherId = personDao.otherPersonId(account)
        val matchable = summaries.filter { it.personId != otherId }
        if (matchable.isEmpty() && batch == null) return@withContext true
        val centroids = ArrayList<FloatArray>(matchable.size)
        for (row in matchable) {
            val c = unpackEmbedding(row.centroid)
            // A stale-width cached centroid cannot be matched against safely, so defer to a full rebuild.
            if (c.size != FACE_EMBEDDING_DIM) return@withContext false
            centroids.add(c)
        }

        // Only the unclustered remainder is read, graded exactly as the full pass grades a face so a weak
        // crop clears a stricter bar (see samplesForSweep).
        val rows = if (batch == null) {
            faceDao.unclusteredFaces(account)
        } else {
            faceDao.unclusteredFacesBatch(account, batch.afterId, batch.limit).also {
                batch.scanned = it.size
                if (it.isNotEmpty()) batch.afterId = it.last().id
            }
        }
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
        val assignment = assignIncrementalParallel(samples, centroids)

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
            // The person is inserted already carrying its photo count and its clearest face as the cover,
            // the way a full rebuild writes one: the people screens read the cached count (a group needs
            // two photos to show) and an empty-person sweep removes any person left at zero.
            val cover = idBySample[newFaceLocalIdx[members.maxByOrNull { newFaceSamples[it].score }!!]]
            NewCluster(faceIds, normalize(centroid), cover, faceIds.mapTo(HashSet()) { photoKeyOfFaceId(it) }.size)
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
            for (cluster in newClusters) {
                val newPersonId = personDao.insert(
                    PersonEntity(
                        userId = account,
                        displayName = null,
                        coverFaceId = cluster.coverFaceId,
                        faceCount = cluster.photos,
                    ),
                )
                faceDao.assignPersonBatch(newPersonId, cluster.faceIds)
                clusterSummaryDao.upsertAll(
                    listOf(
                        ClusterSummaryEntity(
                            personId = newPersonId,
                            userId = account,
                            centroid = packEmbedding(cluster.centroid),
                            memberCount = cluster.faceIds.size,
                            modelVersion = FACE_MODEL_VERSION,
                            updatedAt = now,
                        ),
                    ),
                )
            }
            // The people that gained faces get their cached photo count back in step: one statement for
            // the unnamed ones, the manual-photo union for a named person (its attached photos count too).
            val gained = faceIdsByPerson.map { it.first }
            personDao.recountPhotos(gained.filter { nameByPersonId[it] == null })
            for (personId in gained) {
                val name = nameByPersonId[personId] ?: continue
                val person = personDao.personById(personId) ?: continue
                val manual = personManualPhotoDao.photoKeysForNameList(account, name)
                val photos = (faceDao.distinctPhotoKeysForPerson(account, personId) + manual).toHashSet().size
                personDao.updateCoverAndCount(personId, person.coverFaceId, photos)
            }
        }
        if (!placed) return@withContext false
        val placedFaces = faceIdsByPerson.sumOf { it.second.size } + newClusters.sumOf { it.faceIds.size }
        if (batch != null) {
            batch.placed += placedFaces
            batch.created += newClusters.size
        } else {
            SyncDiagnostics.log(
                "faces: incremental placed ${faceIdsByPerson.sumOf { it.second.size }} onto " +
                    "${faceIdsByPerson.size} people and formed ${newClusters.size} new clusters " +
                    "(${matchable.size} candidates)",
            )
        }
        true
    }

    /**
     * Puts a name on each unnamed group whose faces carry one: the name most of its faces were confirmed
     * under (by the user, or put back on the faces by a restore or import). The whole-library rebuild carries
     * these onto its clusters itself; the batched placement forms groups without that step, so a batched run
     * ends here. The groups holding the most named faces claim a name first, and a name a person already
     * holds is never put on a second one. Written in one transaction behind [writeAllowed]; returns how
     * many groups it named.
     */
    suspend fun nameGroupsFromConfirmedFaces(
        userId: UserId?,
        writeAllowed: () -> Boolean = { true },
    ): Int = withContext(Dispatchers.Default) {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        val rows = faceDao.confirmedNamesByPerson(account)
        if (rows.isEmpty()) return@withContext 0
        val people = personDao.allForUser(account).associateBy { it.id }
        val claimed = HashSet<String>()
        for (person in people.values) person.displayName?.takeIf { it.isNotBlank() }?.let { claimed.add(it) }
        val strongestPerPerson = rows.groupBy { it.personId }.values
            .map { names -> names.maxByOrNull { it.faces }!! }
            .sortedByDescending { it.faces }
        var named = 0
        appDatabase.withTransaction {
            if (!writeAllowed()) return@withTransaction
            for (row in strongestPerPerson) {
                val person = people[row.personId] ?: continue
                if (person.isOther || !person.displayName.isNullOrBlank() || row.name in claimed) continue
                val manual = personManualPhotoDao.photoKeysForNameList(account, row.name)
                val photos = (faceDao.distinctPhotoKeysForPerson(account, person.id) + manual).toHashSet().size
                personDao.updateName(person.id, row.name)
                personDao.updateCoverAndCount(person.id, person.coverFaceId, photos)
                claimed.add(row.name)
                named++
            }
        }
        named
    }

    /**
     * Folds together the groups that are one person split in two, the step the whole-library rebuild ends
     * with, done on the cached centroids so it compares people (thousands) rather than faces (hundreds of
     * thousands). Greedy by closeness: the closest pair at or over [FACE_CLUSTER_MERGE_THRESHOLD] merges, the
     * merged centroid (the faces' weighted mean) is compared with every other group again, and so on, so a
     * chain of near matches cannot fuse people who are far apart. Two groups carrying different names never
     * merge, a named group outlives an unnamed one (else the bigger one stays), and the Unsorted bucket takes
     * no part. A cache that does not describe the grouped faces is left alone: merging on it would be wrong.
     * Written in chunks, each one transaction behind [writeAllowed] that moves the faces, drops the merged
     * people and their centroids and refreshes the survivors' centroid and photo count together; [onTick] is
     * called now and then so a host watching for progress sees the work move. Returns how many groups it
     * folded into another.
     */
    suspend fun mergeSimilarGroups(
        userId: UserId?,
        writeAllowed: () -> Boolean = { true },
        onTick: () -> Unit = {},
    ): Int = withContext(Dispatchers.Default) {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        val summaries = clusterSummaryDao.getAllForUser(account)
        if (!cacheDescribesGroups(summaries, faceDao.assignedFaceCount(account))) return@withContext 0
        val otherId = personDao.otherPersonId(account)
        val rows = summaries.filter { it.personId != otherId }
        val n = rows.size
        if (n < 2) return@withContext 0
        val people = personDao.allForUser(account).associateBy { it.id }
        val centroids = ArrayList<FloatArray>(n)
        for (row in rows) {
            val centroid = unpackEmbedding(row.centroid)
            if (centroid.size != FACE_EMBEDDING_DIM) return@withContext 0
            centroids.add(centroid)
        }
        val counts = IntArray(n) { rows[it].memberCount }
        val names = Array(n) { people[rows[it].personId]?.displayName?.takeIf { name -> name.isNotBlank() } }
        val alive = BooleanArray(n) { true }
        val version = IntArray(n)
        val root = IntArray(n) { it }

        val queue = java.util.PriorityQueue<MergePair>(1024, compareByDescending { it.similarity })
        queue.addAll(similarPairs(centroids))
        var folded = 0
        while (true) {
            val top = queue.poll() ?: break
            if (folded and 0x3F == 0) {
                coroutineContext.ensureActive()
                onTick()
            }
            val a = top.a
            val b = top.b
            if (!alive[a] || !alive[b] || version[a] != top.versionA || version[b] != top.versionB) continue
            val nameA = names[a]
            val nameB = names[b]
            if (nameA != null && nameB != null && nameA != nameB) continue
            val survivor = when {
                nameA != null && nameB == null -> a
                nameB != null && nameA == null -> b
                counts[a] >= counts[b] -> a
                else -> b
            }
            val dropped = if (survivor == a) b else a
            centroids[survivor] = weightedMean(centroids[survivor], counts[survivor], centroids[dropped], counts[dropped])
            counts[survivor] += counts[dropped]
            alive[dropped] = false
            root[dropped] = survivor
            version[survivor]++
            folded++
            for (k in 0 until n) {
                if (!alive[k] || k == survivor) continue
                val similarity = cosineSimilarity(centroids[survivor], centroids[k])
                if (similarity >= FACE_CLUSTER_MERGE_THRESHOLD) {
                    queue.add(MergePair(survivor, k, similarity, version[survivor], version[k]))
                }
            }
        }
        if (folded == 0) return@withContext 0

        val groups = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            if (alive[i]) continue
            var survivor = root[i]
            while (!alive[survivor]) survivor = root[survivor]
            groups.getOrPut(survivor) { ArrayList() }.add(i)
        }
        val now = System.currentTimeMillis()
        var applied = 0
        for (chunk in groups.keys.chunked(MERGE_CHUNK)) {
            var wrote = false
            appDatabase.withTransaction {
                if (!writeAllowed()) return@withTransaction
                wrote = true
                val droppedIds = ArrayList<Long>()
                val unnamed = ArrayList<Long>()
                val summariesOut = ArrayList<ClusterSummaryEntity>(chunk.size)
                for (s in chunk) {
                    val survivorId = rows[s].personId
                    val absorbed = groups.getValue(s)
                    for (d in absorbed) {
                        faceDao.mergePersonInto(account, rows[d].personId, survivorId)
                        droppedIds.add(rows[d].personId)
                    }
                    summariesOut.add(
                        ClusterSummaryEntity(survivorId, account, packEmbedding(centroids[s]), counts[s], FACE_MODEL_VERSION, now),
                    )
                    val name = names[s]
                    if (name == null) {
                        unnamed.add(survivorId)
                    } else {
                        val manual = personManualPhotoDao.photoKeysForNameList(account, name)
                        val photos = (faceDao.distinctPhotoKeysForPerson(account, survivorId) + manual).toHashSet().size
                        personDao.updateCoverAndCount(survivorId, people[survivorId]?.coverFaceId, photos)
                    }
                    applied += absorbed.size
                }
                clusterSummaryDao.deleteByPersons(droppedIds)
                personDao.deleteByIds(droppedIds)
                clusterSummaryDao.upsertAll(summariesOut)
                personDao.recountPhotos(unnamed)
            }
            if (!wrote) break
            coroutineContext.ensureActive()
            onTick()
        }
        applied
    }

    /**
     * Puts every face still without a person into the account's single Unsorted bucket, the way the
     * whole-library rebuild routes the faces it could not place, so they can be looked through instead of
     * sitting outside every screen. The bucket is reused when one exists, its cached count and photo count are
     * kept in step, and its centroid is left empty: it is a grab-bag, which the matching never reads. Called
     * once a batched run is over, since a face put here is no longer offered to the people a later run opens.
     * Written in one transaction behind [writeAllowed]; returns how many faces it moved.
     */
    suspend fun moveLeftoverToUnsorted(userId: UserId?, writeAllowed: () -> Boolean = { true }): Int =
        withContext(Dispatchers.Default) {
            val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
            val leftover = faceDao.unclusteredCount(account)
            if (leftover == 0) return@withContext 0
            val cover = faceDao.topUnclusteredFace(account)
            var moved = 0
            appDatabase.withTransaction {
                if (!writeAllowed()) return@withTransaction
                val existing = personDao.otherPersonId(account)
                val otherId = existing ?: personDao.insert(
                    PersonEntity(userId = account, coverFaceId = cover, isOther = true),
                )
                faceDao.assignUnclusteredTo(account, otherId)
                personDao.recountPhotos(listOf(otherId))
                val storedCover = personDao.personById(otherId)?.coverFaceId
                if (storedCover == null && cover != null) personDao.updateCover(otherId, cover)
                clusterSummaryDao.upsert(
                    ClusterSummaryEntity(
                        personId = otherId,
                        userId = account,
                        centroid = packEmbedding(FloatArray(FACE_EMBEDDING_DIM)),
                        memberCount = faceDao.faceCountForPerson(account, otherId),
                        modelVersion = FACE_MODEL_VERSION,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                moved = leftover
            }
            moved
        }

    /** Pairs of centroids at or over the merge floor, found on [ASSIGN_WORKERS] workers: each takes every
     *  n-th row against all later ones, so the triangle of comparisons is shared out evenly. */
    private suspend fun similarPairs(centroids: List<FloatArray>): List<MergePair> {
        val n = centroids.size
        return coroutineScope {
            (0 until ASSIGN_WORKERS).map { worker ->
                async(Dispatchers.Default) {
                    val found = ArrayList<MergePair>()
                    var i = worker
                    var rowsDone = 0
                    while (i < n) {
                        for (j in i + 1 until n) {
                            val similarity = cosineSimilarity(centroids[i], centroids[j])
                            if (similarity >= FACE_CLUSTER_MERGE_THRESHOLD) found.add(MergePair(i, j, similarity, 0, 0))
                        }
                        i += ASSIGN_WORKERS
                        if (++rowsDone and 0x3F == 0) ensureActive()
                    }
                    found
                }
            }.awaitAll().flatten()
        }
    }

    /** The L2-normalised mean of two centroids weighted by how many faces each was averaged over. */
    private fun weightedMean(first: FloatArray, firstCount: Int, second: FloatArray, secondCount: Int): FloatArray =
        normalize(FloatArray(first.size) { first[it] * firstCount + second[it] * secondCount })

    /**
     * Whether the centroid cache [summaries] describes the grouped faces as they stand: every row from the
     * current face pipeline and the cached member counts summing to the [assigned] faces. An empty cache over
     * no grouped faces counts, since there is nothing yet for it to describe.
     */
    private fun cacheDescribesGroups(summaries: List<ClusterSummaryEntity>, assigned: Int): Boolean =
        summaries.none { it.modelVersion != FACE_MODEL_VERSION } && summaries.sumOf { it.memberCount } == assigned

    /**
     * Rebuilds the centroid cache from the existing assignments: each person's centroid summed from its own
     * kept faces a page at a time ([PersonCentroids] sums the same way), so the vectors are never held
     * together. Written in one transaction behind [writeAllowed]; returns false when the check refused it.
     * A person none of whose vectors can be read is left out, so the caller checks the totals once more.
     */
    private suspend fun rebuildSummaries(account: String, writeAllowed: () -> Boolean): Boolean {
        val kept = faceDao.keptFaceCountsByPerson(account)
        val now = System.currentTimeMillis()
        val rows = ArrayList<ClusterSummaryEntity>(kept.size)
        for (person in kept) {
            coroutineContext.ensureActive()
            val sum = CentroidSum()
            var afterId = ""
            while (true) {
                val page = faceDao.personVectorsAfter(account, person.personId, afterId, SUMMARY_PAGE_SIZE)
                for (face in page) sum.add(face.embedding)
                if (page.size < SUMMARY_PAGE_SIZE) break
                afterId = page.last().id
            }
            val centroid = sum.centroid() ?: continue
            rows.add(ClusterSummaryEntity(person.personId, account, packEmbedding(centroid), person.faces, FACE_MODEL_VERSION, now))
        }
        var written = false
        appDatabase.withTransaction {
            if (!writeAllowed()) return@withTransaction
            written = true
            clusterSummaryDao.clearForUser(account)
            if (rows.isNotEmpty()) clusterSummaryDao.upsertAll(rows)
        }
        return written
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

        /** Vectors per page when one person's centroid is summed for the cache rebuild. */
        const val SUMMARY_PAGE_SIZE = 500
    }
}

/** Faces and people below which matching them in one piece is cheaper than splitting it across workers. */
private const val PARALLEL_MIN_FACES = 200
private const val PARALLEL_MIN_CENTROIDS = 200

/** Workers a batch's matching is spread over: enough to cut the wait several times over, few enough to leave
 *  cores for the rest of the app while a long grouping runs behind it. */
private const val ASSIGN_WORKERS = 3

/**
 * [assignIncremental] over [samples], split into slices matched concurrently: each face's best centroid is
 * independent of every other face's, so the slices give exactly the assignment one call would, in the same
 * order. A batch against thousands of people is the cost of a big library's grouping (every face is compared
 * with every centroid), so this is where the run time is cut.
 */
internal suspend fun assignIncrementalParallel(samples: List<FaceSample>, centroids: List<FloatArray>): IntArray {
    if (samples.size < PARALLEL_MIN_FACES || centroids.size < PARALLEL_MIN_CENTROIDS) {
        return assignIncremental(samples, centroids)
    }
    val slices = samples.chunked((samples.size + ASSIGN_WORKERS - 1) / ASSIGN_WORKERS)
    val parts = coroutineScope {
        slices.map { slice -> async(Dispatchers.Default) { assignIncremental(slice, centroids) } }.awaitAll()
    }
    val out = IntArray(samples.size)
    var at = 0
    for (part in parts) {
        part.copyInto(out, at)
        at += part.size
    }
    return out
}

/** A candidate merge of two groups, valid only while neither has changed since it was found (its versions). */
private class MergePair(val a: Int, val b: Int, val similarity: Float, val versionA: Int, val versionB: Int)

/** Merges applied per transaction, so a long merge commits as it goes and a stop loses little. */
private const val MERGE_CHUNK = 100

/** A group of faces the batched placement opens as a new person: its member ids, unit-mean centroid, the
 *  clearest member as the cover and its distinct photo count. */
private class NewCluster(
    val faceIds: List<String>,
    val centroid: FloatArray,
    val coverFaceId: String,
    val photos: Int,
)

/** The photo a face id belongs to: ids are the photo key, a `#`, and the face's index in that photo. */
internal fun photoKeyOfFaceId(faceId: String): String = faceId.substringBeforeLast('#')

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
