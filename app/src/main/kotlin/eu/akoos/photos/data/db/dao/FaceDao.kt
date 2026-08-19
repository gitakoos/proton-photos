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

package eu.akoos.photos.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.util.forEachSqlChunk
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceDao {

    /** Insert or replace one detected face. Idempotent on re-index, since [FaceEntity.id] is derived
     *  from the photo and the face's position within it. */
    @Upsert
    suspend fun upsert(face: FaceEntity)

    /** Insert or replace a batch of faces in one transaction. */
    @Upsert
    suspend fun upsert(faces: List<FaceEntity>)

    /** Live stream of every stored face for the account, the people grouping's source. */
    @Query("SELECT * FROM face WHERE userId = :userId")
    fun observeFacesForUser(userId: String): Flow<List<FaceEntity>>

    /** Distinct photo keys that already carry at least one face, so the indexer skips them without
     *  re-decoding the image. The detector's skip-set, mirroring PhotoLocationDao.idsForUser. */
    @Query("SELECT DISTINCT photoKey FROM face WHERE userId = :userId")
    suspend fun facePhotoKeysForUser(userId: String): List<String>

    /** One face by its stable id, or null. Resolves a person's cover face to its photo and box. */
    @Query("SELECT * FROM face WHERE id = :id LIMIT 1")
    suspend fun faceById(id: String): FaceEntity?

    /** The grouped faces on one photo (assigned to a person, not removed), clearest first, for the
     *  viewer's "people in this photo" bar. */
    @Query(
        "SELECT * FROM face WHERE userId = :userId AND photoKey = :photoKey " +
            "AND rejected = 0 AND personId IS NOT NULL ORDER BY score DESC",
    )
    suspend fun groupedFacesForPhoto(userId: String, photoKey: String): List<FaceEntity>

    /** Every face assigned to one person (not removed), so a caller can build the person's mean face
     *  (centroid) to match candidates against in a "find more photos" sweep. */
    @Query("SELECT * FROM face WHERE userId = :userId AND personId = :personId AND rejected = 0")
    suspend fun facesForPerson(userId: String, personId: Long): List<FaceEntity>

    /** Distinct photo keys a person appears in, the person filter's timeline source. */
    @Query("SELECT DISTINCT photoKey FROM face WHERE userId = :userId AND personId = :personId AND rejected = 0")
    fun photoKeysForPerson(userId: String, personId: Long): Flow<List<String>>

    /** Live count of a person's kept faces, so removing photos can refresh the cached person count. */
    @Query("SELECT COUNT(*) FROM face WHERE userId = :userId AND personId = :personId AND rejected = 0")
    suspend fun faceCountForPerson(userId: String, personId: Long): Int

    /** The distinct photos a person's kept faces appear in, so the displayed count can union these with
     *  the manually attached photos rather than counting faces (a photo can hold several faces). */
    @Query("SELECT DISTINCT photoKey FROM face WHERE userId = :userId AND personId = :personId AND rejected = 0")
    suspend fun distinctPhotoKeysForPerson(userId: String, personId: Long): List<String>

    /** The clearest remaining face of a person, so a removal that takes out the old cover can repoint
     *  it to a face that still belongs. Null once none remain. */
    @Query("SELECT id FROM face WHERE userId = :userId AND personId = :personId AND rejected = 0 ORDER BY score DESC LIMIT 1")
    suspend fun topFaceForPerson(userId: String, personId: Long): String?

    /** The person's clearest face on a specific photo, so the user can pick that photo as the cover. */
    @Query("SELECT id FROM face WHERE userId = :userId AND personId = :personId AND photoKey = :photoKey AND rejected = 0 ORDER BY score DESC LIMIT 1")
    suspend fun topFaceForPersonPhoto(userId: String, personId: Long, photoKey: String): String?

    /** Mark the person's faces in the given photos as removed by the user: they drop off the person now
     *  (personId cleared) and stay off every future clustering pass (rejected). Chunked for the SQL
     *  host-variable limit. */
    suspend fun rejectFacesForPersonInPhotos(userId: String, personId: Long, photoKeys: Collection<String>) =
        photoKeys.forEachSqlChunk { rejectFacesForPersonChunk(userId, personId, it) }

    @Query("UPDATE face SET rejected = 1, personId = NULL WHERE userId = :userId AND personId = :personId AND photoKey IN (:photoKeys)")
    suspend fun rejectFacesForPersonChunk(userId: String, personId: Long, photoKeys: List<String>)

    /** Confirm the person the faces in the given photos belong to (and un-reject them, so a re-added
     *  photo the user once removed is welcomed back). A confirmed face anchors its person's cluster.
     *  Chunked for the host-variable limit. */
    suspend fun labelFacesForPhotos(userId: String, photoKeys: Collection<String>, name: String) =
        photoKeys.forEachSqlChunk { labelFacesForPhotosChunk(userId, it, name) }

    @Query("UPDATE face SET manualName = :name, rejected = 0 WHERE userId = :userId AND photoKey IN (:photoKeys)")
    suspend fun labelFacesForPhotosChunk(userId: String, photoKeys: List<String>, name: String)

    /** Confirm a set of faces (by id) for a person, used when the user accepts a suggested cluster. */
    suspend fun labelFacesByIds(faceIds: Collection<String>, name: String) =
        faceIds.forEachSqlChunk { labelFacesByIdsChunk(it, name) }

    @Query("UPDATE face SET manualName = :name, rejected = 0 WHERE id IN (:faceIds)")
    suspend fun labelFacesByIdsChunk(faceIds: List<String>, name: String)

    /** Face ids assigned to a person (kept faces), so a suggestion can record its whole cluster. */
    @Query("SELECT id FROM face WHERE userId = :userId AND personId = :personId AND rejected = 0")
    suspend fun faceIdsForPerson(userId: String, personId: Long): List<String>

    /** Move a set of faces onto a person immediately, so accepting a suggested cluster merges it into
     *  the person without waiting for the next full rebuild. */
    suspend fun reassignFacesToPerson(faceIds: Collection<String>, personId: Long) =
        faceIds.forEachSqlChunk { reassignFacesToPersonChunk(it, personId) }

    @Query("UPDATE face SET personId = :personId, rejected = 0 WHERE id IN (:faceIds)")
    suspend fun reassignFacesToPersonChunk(faceIds: List<String>, personId: Long)

    /** The person's face ids that fall in the given photos, so a "not this person" removal can record
     *  those exact faces as rejected for the name and unassign them. */
    suspend fun faceIdsForPersonInPhotos(userId: String, personId: Long, photoKeys: Collection<String>): List<String> {
        val out = ArrayList<String>()
        photoKeys.forEachSqlChunk { out += faceIdsForPersonInPhotosChunk(userId, personId, it) }
        return out
    }

    @Query("SELECT id FROM face WHERE userId = :userId AND personId = :personId AND photoKey IN (:photoKeys)")
    suspend fun faceIdsForPersonInPhotosChunk(userId: String, personId: Long, photoKeys: List<String>): List<String>

    /** Detach faces from any person WITHOUT rejecting them, so a "not this person" removal lets them
     *  recluster (and be suggested for the right person) rather than vanishing from People for good. */
    suspend fun unassignFaces(faceIds: Collection<String>) =
        faceIds.forEachSqlChunk { unassignFacesChunk(it) }

    @Query("UPDATE face SET personId = NULL WHERE id IN (:faceIds)")
    suspend fun unassignFacesChunk(faceIds: List<String>)

    /** Reject every one of a person's faces, for "this is not a person": the cluster is dismissed and
     *  its faces never group again. */
    @Query("UPDATE face SET rejected = 1 WHERE userId = :userId AND personId = :personId")
    suspend fun rejectAllForPerson(userId: String, personId: Long)

    /** Re-key confirmed faces when a person is renamed, so a confirmed face follows the new name. */
    @Query("UPDATE face SET manualName = :newName WHERE userId = :userId AND manualName = :oldName")
    suspend fun renameManualName(userId: String, oldName: String, newName: String)

    /** The photoKey of each face in the given photos (one row per face), so the caller can pick out the
     *  photos holding exactly one face, the only ones safe to confirm without guessing which face is
     *  whom. */
    suspend fun facePhotoKeysIn(userId: String, photoKeys: Collection<String>): List<String> {
        val out = ArrayList<String>()
        photoKeys.forEachSqlChunk { out += facePhotoKeysInChunk(userId, it) }
        return out
    }

    @Query("SELECT photoKey FROM face WHERE userId = :userId AND photoKey IN (:photoKeys)")
    suspend fun facePhotoKeysInChunk(userId: String, photoKeys: List<String>): List<String>

    /** One page of the account's faces, clearest detection first, the rebuild pass reads them a page
     *  at a time so a whole library of embeddings never sits in memory at once. The id tie-break
     *  keeps the order stable across pages while personId is being rewritten underneath it. */
    @Query("SELECT * FROM face WHERE userId = :userId ORDER BY score DESC, id LIMIT :limit OFFSET :offset")
    suspend fun facesByScoreDesc(userId: String, limit: Int, offset: Int): List<FaceEntity>

    /** Every face for the account, clearest detection first, the batch clustering pass's input. The
     *  merge step needs all the embeddings at once, so the pass loads them together. */
    @Query("SELECT * FROM face WHERE userId = :userId ORDER BY score DESC, id")
    suspend fun allFacesByScoreDesc(userId: String): List<FaceEntity>

    /**
     * Places a whole cluster of faces into one person in as few statements as possible, the recluster
     * writing each person's members in a batch rather than a statement per face. Chunked one host
     * variable per id, against the per-statement cap a large person can exceed.
     */
    suspend fun assignPersonBatch(personId: Long, faceIds: Collection<String>) =
        faceIds.forEachSqlChunk { assignPersonChunk(personId, it) }

    @Query("UPDATE face SET personId = :personId WHERE id IN (:faceIds)")
    suspend fun assignPersonChunk(personId: Long, faceIds: List<String>)

    /** Clears every face's person link for the account, the first half of a from-scratch recluster
     *  that keeps the detected faces but drops their grouping. */
    @Query("UPDATE face SET personId = NULL WHERE userId = :userId")
    suspend fun clearAssignments(userId: String)

    /** How many of the account's faces are not yet grouped into a person. The drained walk clusters
     *  only when this is above zero, so a re-trigger with nothing new to group does no work. */
    // Rejected faces have a null personId but must not read as "waiting to be clustered", or the
    // scheduler would re-cluster forever chasing faces the user removed on purpose.
    @Query("SELECT COUNT(*) FROM face WHERE userId = :userId AND personId IS NULL AND rejected = 0")
    suspend fun unclusteredCount(userId: String): Int

    /**
     * Drops every face belonging to [photoKeys], the invalidation a removed or re-indexed photo
     * needs. Chunked one host variable per key, against the per-statement cap a select-all can exceed.
     */
    suspend fun deleteByPhotoKeys(userId: String, photoKeys: Collection<String>) =
        photoKeys.forEachSqlChunk { deleteByPhotoKeysChunk(userId, it) }

    @Query("DELETE FROM face WHERE userId = :userId AND photoKey IN (:photoKeys)")
    suspend fun deleteByPhotoKeysChunk(userId: String, photoKeys: List<String>)

    /** Removes every face for the account, for the sign-out wipe. */
    @Query("DELETE FROM face WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}
