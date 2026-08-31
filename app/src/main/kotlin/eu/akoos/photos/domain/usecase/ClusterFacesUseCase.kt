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
import kotlinx.coroutines.withContext
import eu.akoos.photos.util.SyncDiagnostics
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonCoverDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.face.isConfidentFace
import eu.akoos.photos.data.face.sidewaysFromEncoded
import javax.inject.Inject

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
 * The merge needs every embedding at once, so the pass loads them together; memory stays proportional
 * to the library's face count, comfortably within the app's heap for a personal library.
 */
class ClusterFacesUseCase @Inject constructor(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
    private val personCoverDao: PersonCoverDao,
    private val appDatabase: AppDatabase,
) {
    suspend operator fun invoke(userId: UserId) = withContext(Dispatchers.Default) {
        val account = userId.id

        val faces = faceDao.allFacesByScoreDesc(account)
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
        // Manual photos by name (they survive a rebuild) plus a face-id to photo map, so each person's
        // count is the distinct photos across its faces AND any manually attached photos, not a raw
        // face tally.
        val photoKeyById = faces.associate { it.id to it.photoKey }
        val manualByName = personManualPhotoDao.allForUser(account)
            .groupBy({ it.personName }, { it.photoKey })
            .mapValues { it.value.toHashSet() }
        // The cover photo a user picked per name, resolved to a member face below, so a chosen cover
        // survives the rebuild as long as the person still has a face on that photo.
        val coverByName = personCoverDao.allForUser(account).associate { it.personName to it.photoKey }
        // Faces marked NOT a given person ("Is this X? No"): never confirmed for, nor pulled into, that
        // person, no matter how the clustering churns.
        val notPersonByFace = notPersonDao.allForUser(account)
            .groupBy({ it.faceId }, { it.personName }).mapValues { it.value.toHashSet() }
        val ids = ArrayList<String>(faces.size)
        val oldPersonIds = ArrayList<Long?>(faces.size)
        val manualNames = ArrayList<String?>(faces.size)
        val blockedNames = ArrayList<Set<String>>(faces.size)
        val samples = ArrayList<FaceSample>(faces.size)
        for (face in faces) {
            // A face the user removed from its person stays out of every future clustering pass.
            if (face.rejected) continue
            val embedding = unpackEmbedding(face.embedding)
            if (embedding.size != FACE_EMBEDDING_DIM) continue
            val confident = isConfidentFace(
                detectionScore = face.score,
                blur = face.blur?.toDouble(),
                sideways = sidewaysFromEncoded(face.landmarks),
            )
            ids.add(face.id)
            oldPersonIds.add(face.personId)
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
            samples.add(FaceSample(embedding, face.score, confident, confirmed = confirmedName != null))
        }

        // Cluster off the database (pure CPU), so the transaction below holds only the writes and
        // Room's invalidation fires once, at commit, rather than mid-rebuild.
        val assignment = if (samples.isEmpty()) null else clusterFaces(samples)

        // Names: carry the user's given names onto the matching new clusters, then let a cluster
        // holding CONFIRMED faces take that name (a confirmation outranks the carry), claiming the name
        // uniquely so two clusters never share one.
        val nameForCluster = HashMap<Int, String>()
        var finalAssignment: IntArray? = null
        if (assignment != null) {
            // The name follows the cluster holding the most of each person's faces, carry and confirmations
            // counted together, so a lone confirmed suggestion cannot strip the name off the person's bulk.
            nameForCluster.putAll(resolveClusterNames(oldPersonIds, manualNames, assignment, oldNameById))
            // Teach from the confirmations: confirmed faces anchor their person and pull matching split
            // faces in, off the database (pure CPU).
            finalAssignment = attractToNamedClusters(
                embeddings = samples.map { it.embedding },
                scores = FloatArray(samples.size) { samples[it].score },
                assignment = assignment,
                clusterName = nameForCluster,
                manualName = manualNames,
                threshold = FACE_CLUSTER_THRESHOLD,
                blockedNames = blockedNames,
            )
        }

        // Face-clustering diagnostics for Settings > Share diagnostics: capture each rebuild's shape
        // (cluster count, named vs unnamed, how many kept a stable id vs got a fresh one), so an odd
        // merge or split is visible after the fact. Filled inside the rewrite, logged after it commits.
        val createdClusters = ArrayList<Triple<Long, Int, String?>>()
        var keptIdCount = 0
        var otherBucketSize = 0

        // One transaction for the whole rewrite: the people Flow sees a single final list instead of
        // an empty-then-repopulate burst, so the People surfaces do not flicker.
        appDatabase.withTransaction {
            // Drop the old grouping first: unlink every face and remove the people, so the pass builds
            // a fresh set rather than growing onto stale clusters.
            faceDao.clearAssignments(account)
            personDao.clearForUser(account)
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
                createdClusters.add(Triple(personId, kept.size, name))
                if (reuseId != null) keptIdCount++
            }
        }

        val namedCount = createdClusters.count { it.third != null }
        SyncDiagnostics.log(
            "faces: rebuilt ${createdClusters.size} clusters from ${ids.size} faces " +
                "($namedCount named, ${createdClusters.size - namedCount} unnamed, " +
                "$keptIdCount kept id, ${createdClusters.size - keptIdCount} new)",
        )
        if (otherBucketSize > 0) SyncDiagnostics.log("  unsorted bucket: $otherBucketSize faces")
        createdClusters.sortedByDescending { it.second }.take(15).forEach { (id, size, name) ->
            SyncDiagnostics.log("  cluster $id: $size faces" + (name?.let { " = $it" } ?: ""))
        }
    }

    /**
     * The exact [FaceSample] list [invoke] clusters, so a debug threshold sweep can re-run
     * [clusterFaces] over the real stored embeddings without a re-embed. Mirrors [invoke]'s per-face
     * grading: rejected faces dropped, stale-width rows skipped, weak crops marked. Diagnostic only.
     */
    suspend fun samplesForSweep(userId: UserId): List<FaceSample> = withContext(Dispatchers.Default) {
        faceDao.allFacesByScoreDesc(userId.id).mapNotNull { face ->
            if (face.rejected) return@mapNotNull null
            val embedding = unpackEmbedding(face.embedding)
            if (embedding.size != FACE_EMBEDDING_DIM) return@mapNotNull null
            FaceSample(
                embedding = embedding,
                score = face.score,
                confident = isConfidentFace(
                    detectionScore = face.score,
                    blur = face.blur?.toDouble(),
                    sideways = sidewaysFromEncoded(face.landmarks),
                ),
            )
        }
    }
}
