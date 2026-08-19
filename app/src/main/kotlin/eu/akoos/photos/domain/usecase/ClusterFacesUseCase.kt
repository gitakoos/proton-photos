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
            manualNames.add(face.manualName?.takeIf { it.isNotBlank() && it !in blocked })
            samples.add(FaceSample(embedding, face.score, confident))
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
            nameForCluster.putAll(carryNamesToClusters(oldPersonIds, assignment, oldNameById))
            val seedVotes = HashMap<Int, HashMap<String, Int>>()
            for (i in assignment.indices) {
                val n = manualNames[i] ?: continue
                seedVotes.getOrPut(assignment[i]) { HashMap() }.merge(n, 1, Int::plus)
            }
            for ((cluster, votes) in seedVotes) {
                val name = votes.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .first().key
                nameForCluster.entries.filter { it.value == name && it.key != cluster }
                    .map { it.key }.forEach { nameForCluster.remove(it) }
                nameForCluster[cluster] = name
            }
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

            for ((cluster, members) in clusterIds) {
                val name = nameForCluster[cluster]
                val facePhotos = members.mapNotNullTo(HashSet()) { photoKeyById[it] }
                val manualPhotos = name?.let { manualByName[it] } ?: emptySet()
                // Honour a user's chosen cover when its photo is still one of the person's faces;
                // otherwise fall back to the clearest face (the score-descending first member).
                val chosenCover = name?.let { coverByName[it] }
                    ?.let { key -> members.firstOrNull { photoKeyById[it] == key } }
                val personId = personDao.insert(
                    PersonEntity(
                        userId = account,
                        displayName = name,
                        coverFaceId = chosenCover ?: members.first(),
                        faceCount = (facePhotos + manualPhotos).size,
                    ),
                )
                faceDao.assignPersonBatch(personId, members)
            }
        }
    }
}
