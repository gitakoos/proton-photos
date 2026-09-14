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
import eu.akoos.photos.data.db.entity.ImageEmbeddingEntity

/**
 * One photo's key paired with its packed embedding blob, the two columns the search ranks. Projected so
 * scoring never loads the model-version or timestamp columns it does not need.
 */
data class ImageEmbeddingRow(
    val photoKey: String,
    val embedding: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageEmbeddingRow) return false
        return photoKey == other.photoKey && embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = photoKey.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

@Dao
interface ImageEmbeddingDao {

    /** Insert or replace one photo's embedding. Idempotent on re-index, since the primary key is
     *  (userId, photoKey). */
    @Upsert
    suspend fun upsert(row: ImageEmbeddingEntity)

    /** Insert or replace a batch of embeddings in one transaction. */
    @Upsert
    suspend fun upsert(rows: List<ImageEmbeddingEntity>)

    /** Every photo key already embedded for the account, the walk's skip set: a re-run derives its
     *  pending set from these so it does not re-decode a photo it has already indexed. Mirrors
     *  FaceScanDao.scannedKeysForUser. */
    @Query("SELECT photoKey FROM image_embedding WHERE userId = :userId")
    suspend fun scannedKeysForUser(userId: String): List<String>

    /** How many photos are embedded for the account, so the indexer can report progress. */
    @Query("SELECT COUNT(*) FROM image_embedding WHERE userId = :userId")
    suspend fun countForUser(userId: String): Int

    /** Every embedding for the account, key + blob, the search's input: it ranks these in memory by
     *  cosine similarity and keeps the top matches. */
    @Query("SELECT photoKey, embedding FROM image_embedding WHERE userId = :userId")
    suspend fun allEmbeddingsForUser(userId: String): List<ImageEmbeddingRow>

    /** One page of the account's embeddings, key + blob, ordered by photoKey so paging stays stable, so
     *  a large library can be scored a page at a time rather than loading every embedding at once. */
    @Query(
        "SELECT photoKey, embedding FROM image_embedding WHERE userId = :userId " +
            "ORDER BY photoKey LIMIT :limit OFFSET :offset",
    )
    suspend fun embeddingsPaged(userId: String, limit: Int, offset: Int): List<ImageEmbeddingRow>

    /** Drops one photo's embedding, the invalidation a removed or re-indexed photo needs. */
    @Query("DELETE FROM image_embedding WHERE userId = :userId AND photoKey = :photoKey")
    suspend fun deleteByPhotoKey(userId: String, photoKey: String)

    /** Removes every embedding for the account, for the sign-out wipe or a full rescan. */
    @Query("DELETE FROM image_embedding WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    /** Drops every embedding for the account not produced by the current model, so a model-version bump
     *  re-embeds only the stale-model rows and keeps the ones already at the current model. */
    @Query("DELETE FROM image_embedding WHERE userId = :userId AND modelVersion != :modelVersion")
    suspend fun deleteByModelVersionNot(userId: String, modelVersion: Int)
}
