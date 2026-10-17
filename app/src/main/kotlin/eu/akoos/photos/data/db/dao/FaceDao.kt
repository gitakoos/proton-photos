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
import eu.akoos.photos.util.flatMapSqlChunks
import eu.akoos.photos.util.forEachSqlChunk
import kotlinx.coroutines.flow.Flow

/**
 * One dismissed ("not a person") face reduced to the photo and box a crop needs. The embedding blob is
 * deliberately left out so listing the exclusions never loads the heavy recognition vectors.
 */
data class RejectedFace(
    val id: String,
    val photoKey: String,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
)

/** A face's stable id paired with the name the user confirmed it as, projected so a caller can spot an
 *  imported face that this device has already confirmed as a different person. Only confirmed faces are
 *  returned (a null [manualName] is filtered out by the query). */
data class FaceManualName(
    val id: String,
    val manualName: String,
)

/**
 * One not-yet-grouped face reduced to what the incremental clustering pass needs to place it: its id,
 * its recognition [embedding], and the three quality signals ([score], [blur], [landmarks]) the
 * confident-versus-weak grading reads. The heavy per-row columns the pass does not need (box, photoKey,
 * person link) are left out, so matching a fresh import loads only these few fields for the unclustered
 * remainder rather than the whole face table.
 */
data class UnclusteredFace(
    val id: String,
    val embedding: ByteArray,
    val score: Float,
    val blur: Float?,
    val landmarks: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnclusteredFace) return false
        return id == other.id &&
            embedding.contentEquals(other.embedding) &&
            score == other.score &&
            blur == other.blur &&
            landmarks == other.landmarks
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + score.hashCode()
        result = 31 * result + (blur?.hashCode() ?: 0)
        result = 31 * result + landmarks.hashCode()
        return result
    }
}

/** An [UnclusteredFace] with the row it is stored in ([rowId], in the order faces were stored), so a batched
 *  run can remember how far it has looked. */
data class UnclusteredBatchFace(
    val id: String,
    val embedding: ByteArray,
    val score: Float,
    val blur: Float?,
    val landmarks: String,
    val rowId: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnclusteredBatchFace) return false
        return id == other.id &&
            embedding.contentEquals(other.embedding) &&
            score == other.score &&
            blur == other.blur &&
            landmarks == other.landmarks &&
            rowId == other.rowId
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + score.hashCode()
        result = 31 * result + (blur?.hashCode() ?: 0)
        result = 31 * result + landmarks.hashCode()
        result = 31 * result + rowId.hashCode()
        return result
    }
}

/**
 * One face reduced to what a face snapshot export plans with: its photo, the person it is grouped under,
 * the name confirmed for it, whether it was removed, and the byte length of its embedding. The blob
 * itself is left unread, so planning an export over a whole library never loads a recognition vector.
 */
data class FaceHead(
    val id: String,
    val photoKey: String,
    val personId: Long?,
    val manualName: String?,
    val rejected: Boolean,
    val embeddingBytes: Int,
)

/**
 * One face reduced to its photo, its stored box, its detection [score] and the person it is grouped
 * under ([personId], null while it waits to be clustered). The embedding blob is left out, so listing
 * the faces across a selection of photos never loads the recognition vectors.
 */
data class FaceOnPhoto(
    val id: String,
    val photoKey: String,
    val personId: Long?,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    val score: Float,
)

/**
 * One face reduced to its stored box and its curation: the person it is grouped under ([personId]), the
 * name confirmed for it ([manualName]) and whether it was removed ([rejected]). The embedding blob is left
 * out, so pairing two face sets of one photo never loads a recognition vector.
 */
data class CuratedFace(
    val id: String,
    val personId: Long?,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    val rejected: Boolean,
    val manualName: String?,
)

/**
 * One kept face reduced to what a full regroup reads: its [embedding], the three quality signals the
 * confident-versus-weak grading reads ([score], [blur], [landmarks]), the person it is grouped under, the
 * name confirmed for it, and its photo (for each person's photo count and cover). The box and owner
 * columns are left out. Not a data class: nothing compares these, and the blob has no content equality.
 */
class RegroupFaceRow(
    val id: String,
    val photoKey: String,
    val personId: Long?,
    val manualName: String?,
    val score: Float,
    val blur: Float?,
    val landmarks: String,
    val embedding: ByteArray,
)

/** One face's id and recognition vector, the read a single person's centroid is summed from. */
class FaceVector(val id: String, val embedding: ByteArray)

/** How many kept faces one person holds. */
data class PersonFaceCount(val personId: Long, val faces: Int)

/** One kept face's id and the person it is grouped under, with no vector. */
data class FacePersonLink(val id: String, val personId: Long)

/** How many of a person's kept faces carry one confirmed [name]: a person's names are read this way so the
 *  name most of its faces carry can be put on the group. */
data class PersonConfirmedName(val personId: Long, val name: String, val faces: Int)

/** One face reduced to its photo and its stored box: no curation, no vector. */
data class FaceBoxRow(
    val id: String,
    val photoKey: String,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
)

/**
 * One face that may carry a name, reduced to its box, the person it is grouped under, the name confirmed
 * for it and its detection [score] (the order names are captured in). No vector. Not a data class:
 * nothing compares these rows.
 */
class FaceLabelRow(
    val id: String,
    val photoKey: String,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    val personId: Long?,
    val manualName: String?,
    val score: Float,
)

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

    /** Every kept face on the given photos, grouped or not (an ungrouped face has a null personId), so
     *  adding photos to a person can offer the faces clustering has not placed yet. Clearest first within
     *  each photo, without the embedding blob. Chunked for the SQL host-variable limit. */
    suspend fun facesOnPhotos(userId: String, photoKeys: Collection<String>): List<FaceOnPhoto> {
        val out = ArrayList<FaceOnPhoto>()
        photoKeys.toSet().forEachSqlChunk { out += facesOnPhotosChunk(userId, it) }
        return out
    }

    @Query(
        "SELECT id, photoKey, personId, `left` AS boxLeft, `top` AS boxTop, `right` AS boxRight, " +
            "`bottom` AS boxBottom, score FROM face WHERE photoKey IN (:photoKeys) AND +userId = :userId " +
            "AND rejected = 0 ORDER BY photoKey, score DESC",
    )
    suspend fun facesOnPhotosChunk(userId: String, photoKeys: List<String>): List<FaceOnPhoto>

    /** The account's faces among the given ids with their current person, so an assignment writes only
     *  this account's faces and knows which people it takes them from. Removed faces are included, since
     *  assigning one brings it back. Chunked for the SQL host-variable limit. The account term is written
     *  `+userId` here and in the other id and photo-key list reads, so the lookup runs on the listed keys'
     *  index: with no statistics SQLite would take the account index and read every face of the account. */
    suspend fun facesByIds(userId: String, faceIds: Collection<String>): List<FaceOnPhoto> {
        val out = ArrayList<FaceOnPhoto>()
        faceIds.toSet().forEachSqlChunk { out += facesByIdsChunk(userId, it) }
        return out
    }

    @Query(
        "SELECT id, photoKey, personId, `left` AS boxLeft, `top` AS boxTop, `right` AS boxRight, " +
            "`bottom` AS boxBottom, score FROM face WHERE id IN (:faceIds) AND +userId = :userId",
    )
    suspend fun facesByIdsChunk(userId: String, faceIds: List<String>): List<FaceOnPhoto>

    /** Every face assigned to one person (not removed), so a caller can build the person's mean face
     *  (centroid) to match candidates against in a "find more photos" sweep. */
    @Query("SELECT * FROM face WHERE userId = :userId AND personId = :personId AND rejected = 0")
    suspend fun facesForPerson(userId: String, personId: Long): List<FaceEntity>

    /** [facesForPerson] without the embedding blob, in the same row order, so a person's next live cover
     *  is picked without loading a recognition vector. The account term is written so it cannot pick the
     *  account index, which would read every face of the account for each person; the person index keeps
     *  the rows in the same order. */
    @Query(
        "SELECT id, photoKey, personId, `left` AS boxLeft, `top` AS boxTop, `right` AS boxRight, " +
            "`bottom` AS boxBottom, score FROM face WHERE +userId = :userId AND personId = :personId AND rejected = 0",
    )
    suspend fun facesForPersonLite(userId: String, personId: Long): List<FaceOnPhoto>

    /** A person's kept faces as their photo and box, followed live, so the person's page can centre each tile on
     *  the face rather than on the middle of the photo. */
    @Query(
        "SELECT id, photoKey, personId, `left` AS boxLeft, `top` AS boxTop, `right` AS boxRight, " +
            "`bottom` AS boxBottom, score FROM face WHERE +userId = :userId AND personId = :personId AND rejected = 0",
    )
    fun observeFaceBoxesForPerson(userId: String, personId: Long): Flow<List<FaceOnPhoto>>

    /** Which of [photoKeys] one person already appears in, read through the photo index so it costs the keys
     *  asked about, not every face the person has. */
    suspend fun photoKeysHeldAmong(userId: String, personId: Long, photoKeys: Collection<String>): Set<String> =
        photoKeys.toSet().flatMapSqlChunks { photoKeysHeldAmongChunk(userId, personId, it) }.toHashSet()

    @Query(
        "SELECT DISTINCT photoKey FROM face WHERE photoKey IN (:photoKeys) AND +userId = :userId " +
            "AND +personId = :personId AND rejected = 0",
    )
    suspend fun photoKeysHeldAmongChunk(userId: String, personId: Long, photoKeys: List<String>): List<String>

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

    @Query("UPDATE face SET rejected = 1, personId = NULL WHERE +userId = :userId AND personId = :personId AND photoKey IN (:photoKeys)")
    suspend fun rejectFacesForPersonChunk(userId: String, personId: Long, photoKeys: List<String>)

    /** Confirm the person the faces in the given photos belong to (and un-reject them, so a re-added
     *  photo the user once removed is welcomed back). A confirmed face anchors its person's cluster.
     *  Chunked for the host-variable limit. */
    suspend fun labelFacesForPhotos(userId: String, photoKeys: Collection<String>, name: String) =
        photoKeys.forEachSqlChunk { labelFacesForPhotosChunk(userId, it, name) }

    @Query("UPDATE face SET manualName = :name, rejected = 0 WHERE photoKey IN (:photoKeys) AND +userId = :userId")
    suspend fun labelFacesForPhotosChunk(userId: String, photoKeys: List<String>, name: String)

    /** Confirm a set of faces (by id) for a person, used when the user accepts a suggested cluster. */
    suspend fun labelFacesByIds(faceIds: Collection<String>, name: String) =
        faceIds.forEachSqlChunk { labelFacesByIdsChunk(it, name) }

    @Query("UPDATE face SET manualName = :name, rejected = 0 WHERE id IN (:faceIds)")
    suspend fun labelFacesByIdsChunk(faceIds: List<String>, name: String)

    /** Clear a set of faces' confirmed name (by id), used when a named person is un-named so the old
     *  name does not resurrect as a confirmation on the next rebuild. */
    suspend fun clearManualNameByIds(faceIds: Collection<String>) =
        faceIds.forEachSqlChunk { clearManualNameByIdsChunk(it) }

    @Query("UPDATE face SET manualName = NULL WHERE id IN (:faceIds)")
    suspend fun clearManualNameByIdsChunk(faceIds: List<String>)

    /** The confirmed names for the given face ids (only faces that carry one), so an import can keep a
     *  face this device already confirmed as a different person instead of relabelling it. Chunked for
     *  the SQL host-variable limit. */
    suspend fun manualNamesForIds(faceIds: Collection<String>): List<FaceManualName> {
        val out = ArrayList<FaceManualName>()
        faceIds.forEachSqlChunk { out += manualNamesForIdsChunk(it) }
        return out
    }

    @Query("SELECT id, manualName FROM face WHERE id IN (:faceIds) AND manualName IS NOT NULL")
    suspend fun manualNamesForIdsChunk(faceIds: List<String>): List<FaceManualName>

    /** Which of the given face ids this device currently holds as removed ("rejected"), so an import
     *  keeps a locally removed face removed instead of resurrecting it from an older or foreign index.
     *  Chunked for the SQL host-variable limit. */
    suspend fun rejectedIdsAmong(faceIds: Collection<String>): List<String> {
        val out = ArrayList<String>()
        faceIds.forEachSqlChunk { out += rejectedIdsAmongChunk(it) }
        return out
    }

    @Query("SELECT id FROM face WHERE id IN (:faceIds) AND rejected = 1")
    suspend fun rejectedIdsAmongChunk(faceIds: List<String>): List<String>

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

    @Query("SELECT id FROM face WHERE +userId = :userId AND personId = :personId AND photoKey IN (:photoKeys)")
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

    /** Every face the user dismissed as "not a person" (rejected), with the photo + box to render a
     *  crop, the excluded faces screen's source. Projected so the embedding blob is never loaded. */
    @Query(
        "SELECT id, photoKey, `left` AS boxLeft, `top` AS boxTop, `right` AS boxRight, " +
            "`bottom` AS boxBottom FROM face WHERE userId = :userId AND rejected = 1 ORDER BY photoKey",
    )
    suspend fun rejectedFacesForUser(userId: String): List<RejectedFace>

    /** Undo a "not a person" dismissal: clear the reject flag and the stale person link so the face
     *  reads as freshly detected and is regrouped on the next clustering pass. */
    @Query("UPDATE face SET rejected = 0, personId = NULL WHERE id = :faceId")
    suspend fun unrejectFace(faceId: String)

    /** Mark a set of faces (by id) as removed from People, dropping each from any person and from every
     *  future clustering pass. The model-swap reattach uses this to re-apply the exclusions it carried by
     *  geometry onto the freshly detected faces. Chunked for the SQL host-variable limit. */
    suspend fun rejectByIds(faceIds: Collection<String>) =
        faceIds.forEachSqlChunk { rejectByIdsChunk(it) }

    @Query("UPDATE face SET rejected = 1, personId = NULL WHERE id IN (:faceIds)")
    suspend fun rejectByIdsChunk(faceIds: List<String>)

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

    @Query("SELECT photoKey FROM face WHERE photoKey IN (:photoKeys) AND +userId = :userId")
    suspend fun facePhotoKeysInChunk(userId: String, photoKeys: List<String>): List<String>

    /** One page of the account's faces, clearest detection first, the rebuild pass reads them a page
     *  at a time so a whole library of embeddings never sits in memory at once. The id tie-break
     *  keeps the order stable across pages while personId is being rewritten underneath it. */
    @Query("SELECT * FROM face WHERE userId = :userId ORDER BY score DESC, id LIMIT :limit OFFSET :offset")
    suspend fun facesByScoreDesc(userId: String, limit: Int, offset: Int): List<FaceEntity>

    /** Every face for the account as whole rows, clearest detection first. Holds every blob at once, so
     *  the face passes read lean pages instead ([regroupFacesAfter], [keptPersonLinksByScoreDesc],
     *  [labelRowsAfter]), each reproducing this order where it matters. */
    @Query("SELECT * FROM face WHERE userId = :userId ORDER BY score DESC, id")
    suspend fun allFacesByScoreDesc(userId: String): List<FaceEntity>

    /**
     * The account's next [limit] kept faces after [afterId] in id order, reduced to [RegroupFaceRow], the
     * full regroup's paged read: each page resumes from the last id seen along the primary key, as
     * [facesAfter] does, so the whole library's blobs are never held at once. Removed faces are left out,
     * since no grouping places them. Start from an empty [afterId].
     */
    @Query(
        "SELECT id, photoKey, personId, manualName, score, blur, landmarks, embedding FROM face " +
            "WHERE +userId = :userId AND rejected = 0 AND id > :afterId ORDER BY id LIMIT :limit",
    )
    suspend fun regroupFacesAfter(userId: String, afterId: String, limit: Int): List<RegroupFaceRow>

    /** One person's next [limit] kept faces after [afterId] in id order, id and vector only, so a single
     *  person's centroid is summed a page at a time. Start from an empty [afterId]. */
    @Query(
        "SELECT id, embedding FROM face WHERE userId = :userId AND personId = :personId AND rejected = 0 " +
            "AND id > :afterId ORDER BY id LIMIT :limit",
    )
    suspend fun personVectorsAfter(userId: String, personId: Long, afterId: String, limit: Int): List<FaceVector>

    /** How many kept faces each of the account's people holds, one row per person and no vector read, so
     *  a cached centroid can be checked against the faces it should summarise. */
    @Query(
        "SELECT personId, COUNT(*) AS faces FROM face WHERE userId = :userId AND personId IS NOT NULL " +
            "AND rejected = 0 GROUP BY personId",
    )
    suspend fun keptFaceCountsByPerson(userId: String): List<PersonFaceCount>

    /** Moves every face of [from] onto [to]: the merge of two groups of one person, one statement however
     *  many faces they hold. */
    @Query("UPDATE face SET personId = :to WHERE userId = :userId AND personId = :from")
    suspend fun mergePersonInto(userId: String, from: Long, to: Long)

    /** Ungroups every face of the account whose person no longer exists, one statement with no id list, and
     *  returns how many it ungrouped. */
    @Query(
        "UPDATE face SET personId = NULL WHERE userId = :userId AND personId IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM person WHERE person.id = face.personId)",
    )
    suspend fun releaseOrphanedFaces(userId: String): Int

    /** Puts every kept face that no person holds yet onto [personId], the Unsorted bucket. */
    @Query("UPDATE face SET personId = :personId WHERE userId = :userId AND personId IS NULL AND rejected = 0")
    suspend fun assignUnclusteredTo(userId: String, personId: Long)

    /** The clearest kept face no person holds, for the cover of a new Unsorted bucket. Null when none. */
    @Query(
        "SELECT id FROM face WHERE userId = :userId AND personId IS NULL AND rejected = 0 " +
            "ORDER BY score DESC LIMIT 1",
    )
    suspend fun topUnclusteredFace(userId: String): String?

    /** For each grouped person, every confirmed name its kept faces carry and how many carry it: a small
     *  result (one row per person and name), read without any vector. */
    @Query(
        "SELECT personId, manualName AS name, COUNT(*) AS faces FROM face WHERE userId = :userId " +
            "AND personId IS NOT NULL AND rejected = 0 AND manualName IS NOT NULL AND TRIM(manualName) != '' " +
            "GROUP BY personId, manualName",
    )
    suspend fun confirmedNamesByPerson(userId: String): List<PersonConfirmedName>

    /** The people that have at least one kept face, which tells them apart from one a rescan or a model update
     *  left behind with its name and none of its faces. */
    @Query("SELECT DISTINCT personId FROM face WHERE userId = :userId AND personId IS NOT NULL AND rejected = 0")
    suspend fun personIdsWithKeptFaces(userId: String): List<Long>

    /** Every kept, grouped face of the account as its id and person, in [allFacesByScoreDesc]'s order
     *  (clearest detection first, then id), with no vector read: the vectors follow by id. */
    @Query(
        "SELECT id, personId FROM face WHERE userId = :userId AND rejected = 0 AND personId IS NOT NULL " +
            "ORDER BY score DESC, id",
    )
    suspend fun keptPersonLinksByScoreDesc(userId: String): List<FacePersonLink>

    /** The account's faces among [faceIds] as id and vector, in no set order. One slice: the caller keeps
     *  [faceIds] within the host-variable limit. */
    @Query("SELECT id, embedding FROM face WHERE id IN (:faceIds) AND +userId = :userId")
    suspend fun vectorsByIdsChunk(userId: String, faceIds: List<String>): List<FaceVector>

    /**
     * The account's next [limit] faces after [afterId] in id order that are grouped or carry a confirmed
     * name, removed ones included, reduced to [FaceLabelRow]: the name capture's paged read, a walk along
     * the primary key as [facesAfter] is. Start from an empty [afterId].
     */
    @Query(
        "SELECT id, photoKey, `left` AS boxLeft, `top` AS boxTop, `right` AS boxRight, `bottom` AS boxBottom, " +
            "personId, manualName, score FROM face WHERE +userId = :userId AND id > :afterId " +
            "AND (personId IS NOT NULL OR manualName IS NOT NULL) ORDER BY id LIMIT :limit",
    )
    suspend fun labelRowsAfter(userId: String, afterId: String, limit: Int): List<FaceLabelRow>

    /** Every face of the account on the given photos, removed ones included, as its box. One slice: the
     *  caller keeps [photoKeys] within the host-variable limit. */
    @Query(
        "SELECT id, photoKey, `left` AS boxLeft, `top` AS boxTop, `right` AS boxRight, `bottom` AS boxBottom " +
            "FROM face WHERE photoKey IN (:photoKeys) AND +userId = :userId",
    )
    suspend fun faceBoxesOnPhotosChunk(userId: String, photoKeys: List<String>): List<FaceBoxRow>

    /** The photo of each of the account's faces among [faceIds]. One slice, as [faceBoxesOnPhotosChunk]. */
    @Query("SELECT photoKey FROM face WHERE id IN (:faceIds) AND +userId = :userId")
    suspend fun photoKeysOfFacesChunk(userId: String, faceIds: List<String>): List<String>

    /**
     * The account's next [limit] faces after [afterId] in id order, whole rows, the face snapshot export's
     * read: an id (`photoKey#index`) keeps each photo's faces together, and a page resumes from the last
     * id seen, so every page is one walk along the primary key rather than a re-sort of the table the
     * way an OFFSET page is. The unary plus keeps the planner off the userId index, which would sort
     * every row of the account (blobs included) for each page. Start from an empty [afterId].
     */
    @Query("SELECT * FROM face WHERE +userId = :userId AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun facesAfter(userId: String, afterId: String, limit: Int): List<FaceEntity>

    /** [facesAfter] reduced to [FaceHead], the export's planning pass, with no embedding read. */
    @Query(
        "SELECT id, photoKey, personId, manualName, rejected, length(embedding) AS embeddingBytes " +
            "FROM face WHERE +userId = :userId AND id > :afterId ORDER BY id LIMIT :limit",
    )
    suspend fun faceHeadsAfter(userId: String, afterId: String, limit: Int): List<FaceHead>

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

    /** How many of the account's faces are grouped into a person (kept, not removed). The incremental
     *  pass compares this against the cached centroids' total member count: a mismatch means a curation
     *  edit drifted the assignments, so the cache is stale and a full rebuild reconciles it. */
    @Query("SELECT COUNT(*) FROM face WHERE userId = :userId AND personId IS NOT NULL AND rejected = 0")
    suspend fun assignedFaceCount(userId: String): Int

    /** Total detected faces for the account (kept, not removed), as a live count for the scan status.
     *  Emits again as the walk writes new faces, so the status figure climbs while a scan runs. */
    @Query("SELECT COUNT(*) FROM face WHERE userId = :userId AND rejected = 0")
    fun observeFaceCountForUser(userId: String): Flow<Int>

    /** The not-yet-grouped faces for the account, projected to the fields the incremental pass needs.
     *  Only the unclustered remainder is read (not the whole face table), so placing a fresh import
     *  against the cached centroids never loads every stored embedding at once. */
    @Query("SELECT id, embedding, score, blur, landmarks FROM face WHERE userId = :userId AND personId IS NULL AND rejected = 0")
    suspend fun unclusteredFaces(userId: String): List<UnclusteredFace>

    /** The next [limit] not-yet-grouped faces stored after row [afterRow], in the order they were stored, so a
     *  library too big for the whole-library rebuild is placed against the cached people a bounded batch at a
     *  time. The row cursor moves past faces that stay unplaced, so a batch of look-alike strangers never
     *  blocks the ones behind it. Start from 0 to read them all: rows are numbered in the order faces were
     *  stored, so a run that remembers the last row it looked at reads only the faces that arrived since, not
     *  the ones it left ungrouped on purpose. */
    @Query(
        "SELECT id, embedding, score, blur, landmarks, rowid AS rowId FROM face " +
            "WHERE +userId = :userId AND personId IS NULL AND rejected = 0 AND rowid > :afterRow " +
            "ORDER BY rowid LIMIT :limit",
    )
    suspend fun unclusteredFacesBatchSince(userId: String, afterRow: Long, limit: Int): List<UnclusteredBatchFace>

    /** How many not-yet-grouped faces were stored after row [minRow], the work a batched run from there has. */
    @Query("SELECT COUNT(*) FROM face WHERE +userId = :userId AND personId IS NULL AND rejected = 0 AND rowid > :minRow")
    suspend fun unclusteredCountSince(userId: String, minRow: Long): Int

    /** One page of the account's KEPT faces (not removed), id + embedding, for the hide-similar sweep so
     *  dismissing a junk cluster can also reject its look-alikes that landed in other clusters, without
     *  loading every stored embedding at once. Ordered by id so paging stays stable. */
    @Query(
        "SELECT id, embedding, score, blur, landmarks FROM face WHERE userId = :userId AND rejected = 0 " +
            "ORDER BY id LIMIT :limit OFFSET :offset",
    )
    suspend fun keptFacesPaged(userId: String, limit: Int, offset: Int): List<UnclusteredFace>

    /**
     * Drops every face belonging to [photoKeys], the invalidation a removed or re-indexed photo
     * needs. Chunked one host variable per key, against the per-statement cap a select-all can exceed.
     */
    suspend fun deleteByPhotoKeys(userId: String, photoKeys: Collection<String>) =
        photoKeys.forEachSqlChunk { deleteByPhotoKeysChunk(userId, it) }

    @Query("DELETE FROM face WHERE photoKey IN (:photoKeys) AND +userId = :userId")
    suspend fun deleteByPhotoKeysChunk(userId: String, photoKeys: List<String>)

    /** Removes every face for the account, for the sign-out wipe. */
    @Query("DELETE FROM face WHERE userId = :userId")
    suspend fun clearForUser(userId: String)

    /** Re-keys every face row from one owner to another, to adopt a guest's faces on sign-in. */
    @Query("UPDATE face SET userId = :to WHERE userId = :from")
    suspend fun updateUserId(from: String, to: String)

    /** How many face rows an owner holds, so a sign-in migration can report what it carried or dropped. */
    @Query("SELECT COUNT(*) FROM face WHERE userId = :userId")
    suspend fun countForUser(userId: String): Int

    /** Moves a photo's faces from one photo key to another when a device photo is backed up to Drive,
     *  so its faces (id is `photoKey#index`) reuse the cloud identity instead of a duplicate re-scan. */
    @Query("UPDATE face SET id = :newKey || substr(id, length(:oldKey) + 1), photoKey = :newKey WHERE userId = :userId AND photoKey = :oldKey")
    suspend fun rekeyPhoto(userId: String, oldKey: String, newKey: String)

    /** Every face on one photo, removed ones included, with its box and curation, so a backup re-key can
     *  pair the device copy's faces with the cloud copy's when both were scanned. */
    @Query(
        "SELECT id, personId, `left` AS boxLeft, `top` AS boxTop, `right` AS boxRight, `bottom` AS boxBottom, " +
            "rejected, manualName FROM face WHERE userId = :userId AND photoKey = :photoKey ORDER BY id",
    )
    suspend fun curatedFacesOnPhoto(userId: String, photoKey: String): List<CuratedFace>

    /** Moves one face to [newId] on [photoKey], its person, confirmation and removal kept, so a backup
     *  re-key can bring a device face the cloud copy lacks under the cloud key. */
    @Query("UPDATE face SET id = :newId, photoKey = :photoKey WHERE userId = :userId AND id = :oldId")
    suspend fun moveFace(userId: String, oldId: String, newId: String, photoKey: String)

    /** Removes every face for every account, the recognition-model re-index wipe: a model change
     *  invalidates all stored embeddings regardless of which account produced them. Returns the row
     *  count deleted. */
    @Query("DELETE FROM face")
    suspend fun clearAll(): Int
}
