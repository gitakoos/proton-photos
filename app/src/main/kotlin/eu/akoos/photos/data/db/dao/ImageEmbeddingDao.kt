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
import androidx.room.Transaction
import androidx.room.Upsert
import eu.akoos.photos.data.db.entity.ImageEmbeddingEntity
import kotlinx.coroutines.flow.Flow

/** Pairs [ImageEmbeddingDao.rekeyPhotos] moves per transaction, so a large first backup is applied in
 *  short bounded writes rather than one long transaction that holds the database's single writer
 *  against every other write in the app. */
private const val REKEY_BATCH_SIZE = 500

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

    /** A live count of the account's embeddings, so a settings card can tell an empty index (nothing
     *  searchable yet) from one that already has embedded photos, even right after launch before a walk
     *  reports progress. */
    @Query("SELECT COUNT(*) FROM image_embedding WHERE userId = :userId")
    fun observeCountForUser(userId: String): Flow<Int>

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

    /**
     * One page of the account's photo keys whose row holds a [modelVersion] embedding of exactly
     * [embeddingBytes] bytes, in photoKey order and strictly after [afterKey] ("" for the first page). A
     * caller walks the whole index by passing the last key of each page back in. Seeking past a key
     * rather than skipping an offset keeps the walk exact while the indexer writes: no row is read twice,
     * and no row that stays in the table is skipped.
     */
    @Query(
        "SELECT photoKey FROM image_embedding WHERE userId = :userId AND modelVersion = :modelVersion " +
            "AND length(embedding) = :embeddingBytes AND photoKey > :afterKey ORDER BY photoKey LIMIT :limit",
    )
    suspend fun keysAfter(
        userId: String,
        modelVersion: Int,
        embeddingBytes: Int,
        afterKey: String,
        limit: Int,
    ): List<String>

    /** The rows behind [keysAfter], key + blob, under the same filter, order and paging. */
    @Query(
        "SELECT photoKey, embedding FROM image_embedding WHERE userId = :userId AND modelVersion = :modelVersion " +
            "AND length(embedding) = :embeddingBytes AND photoKey > :afterKey ORDER BY photoKey LIMIT :limit",
    )
    suspend fun embeddingsAfter(
        userId: String,
        modelVersion: Int,
        embeddingBytes: Int,
        afterKey: String,
        limit: Int,
    ): List<ImageEmbeddingRow>

    /** Drops one photo's embedding, the invalidation a removed or re-indexed photo needs. */
    @Query("DELETE FROM image_embedding WHERE userId = :userId AND photoKey = :photoKey")
    suspend fun deleteByPhotoKey(userId: String, photoKey: String)

    /**
     * Moves one photo's row from [oldKey] to [newKey] when [newKey] holds no row for the account yet.
     * When it does, OR IGNORE skips the primary-key clash and leaves the [oldKey] row where it was.
     * Returns how many rows moved (0 or 1). The building block of [rekeyPhotoBatch], which also drops
     * a row this leaves behind; on its own it can leave both keys holding a row.
     */
    @Query("UPDATE OR IGNORE image_embedding SET photoKey = :newKey WHERE userId = :userId AND photoKey = :oldKey")
    suspend fun moveRowIfKeyFree(userId: String, oldKey: String, newKey: String): Int

    /**
     * Re-keys one photo's embedding once the device photo is backed up: the row keyed by its device
     * content URI ([oldKey]) moves to its Drive linkId ([newKey]), the key its Synced gallery item
     * carries, so the photo is not embedded a second time under its cloud identity and no
     * device-keyed row is left behind. When [newKey] already holds a row, that row is kept and the
     * [oldKey] row is dropped, so exactly one row for the photo remains. Atomic. A self pair, or an
     * [oldKey] with no row, changes nothing. Returns true only when the row moved.
     */
    suspend fun rekeyPhoto(userId: String, oldKey: String, newKey: String): Boolean =
        rekeyPhotoBatch(userId, listOf(oldKey to newKey)) > 0

    /**
     * [rekeyPhoto] over a list of (oldKey, newKey) pairs, [REKEY_BATCH_SIZE] pairs per transaction.
     * Each batch lands whole or not at all; a failing batch throws, leaving the batches before it
     * applied and the ones after it untouched. Returns how many rows moved (a device-keyed row dropped
     * in favour of an existing cloud-keyed one is not counted).
     */
    suspend fun rekeyPhotos(userId: String, pairs: List<Pair<String, String>>): Int {
        var moved = 0
        for (batch in pairs.chunked(REKEY_BATCH_SIZE)) moved += rekeyPhotoBatch(userId, batch)
        return moved
    }

    /**
     * One transaction of [rekeyPhotos]: for each pair in order, move the [Pair.first] row to the
     * [Pair.second] key when that key is free, then drop whatever still sits under [Pair.first], which
     * is only ever the row that lost to an existing one. A self pair is skipped, since its drop would
     * delete the very row it names. Callers go through [rekeyPhotos] or [rekeyPhoto], which bound the
     * list; returns how many rows moved.
     */
    @Transaction
    suspend fun rekeyPhotoBatch(userId: String, pairs: List<Pair<String, String>>): Int {
        var moved = 0
        for ((oldKey, newKey) in pairs) {
            if (oldKey == newKey) continue
            moved += moveRowIfKeyFree(userId, oldKey, newKey)
            deleteByPhotoKey(userId, oldKey)
        }
        return moved
    }

    /** Removes every embedding for the account, for the sign-out wipe or a full rescan. */
    @Query("DELETE FROM image_embedding WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    /** Drops every embedding for the account not produced by the current model, so a model-version bump
     *  re-embeds only the stale-model rows and keeps the ones already at the current model. */
    @Query("DELETE FROM image_embedding WHERE userId = :userId AND modelVersion != :modelVersion")
    suspend fun deleteByModelVersionNot(userId: String, modelVersion: Int)
}
