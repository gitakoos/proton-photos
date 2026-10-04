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
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import eu.akoos.photos.data.db.entity.PhotoLabelEntity
import eu.akoos.photos.data.db.entity.PhotoLabelNameEntity
import eu.akoos.photos.util.LabelNormalizer
import eu.akoos.photos.util.SQL_CHUNK_SIZE
import eu.akoos.photos.util.flatMapSqlChunks
import eu.akoos.photos.util.forEachSqlChunk
import kotlinx.coroutines.flow.Flow

/** Pairs [PhotoLabelDao.rekeyPhotos] moves per transaction, so a long list is applied in short writes. */
private const val REKEY_BATCH_SIZE = 500

/** One photo's key and one of its labels, the two columns a lookup needs. */
data class PhotoLabelRow(val photoKey: String, val label: String)

/** One distinct label of an account: its folded key, its display spelling and how many photos carry it. */
data class LabelUsage(val labelKey: String, val label: String, val count: Int)

/**
 * The outcome of adding labels to many photos at once. [changedPhotos] gained at least one label;
 * [cappedPhotos] had at least one requested label turned away because it already carried
 * [LabelNormalizer.MAX_LABELS]. A photo can be counted in both.
 */
data class LabelBulkResult(val changedPhotos: Int, val cappedPhotos: Int)

/**
 * The rows of [incoming] that may join a photo already holding [target]: those whose label the photo
 * does not have yet, in order, until the photo holds [LabelNormalizer.MAX_LABELS]. The photo's existing
 * labels always win over the incoming ones.
 */
internal fun rowsThatFit(target: List<PhotoLabelEntity>, incoming: List<PhotoLabelEntity>): List<PhotoLabelEntity> {
    val keys = target.mapTo(HashSet()) { it.labelKey }
    val fit = mutableListOf<PhotoLabelEntity>()
    for (row in incoming) {
        if (keys.size >= LabelNormalizer.MAX_LABELS) break
        if (keys.add(row.labelKey)) fit += row
    }
    return fit
}

@Dao
interface PhotoLabelDao {

    /** Every label of the account, key + spelling only, each photo's labels in display order. */
    @Query("SELECT photoKey, label FROM photo_label WHERE userId = :userId ORDER BY photoKey, updatedAt, rowid")
    fun observeAllForUser(userId: String): Flow<List<PhotoLabelRow>>

    /** One photo's labels in display order. */
    @Query("SELECT * FROM photo_label WHERE userId = :userId AND photoKey = :photoKey ORDER BY updatedAt, rowid")
    suspend fun labelsOfPhoto(userId: String, photoKey: String): List<PhotoLabelEntity>

    /** [labelsOfPhoto], live. */
    @Query("SELECT * FROM photo_label WHERE userId = :userId AND photoKey = :photoKey ORDER BY updatedAt, rowid")
    fun observeLabelsOfPhoto(userId: String, photoKey: String): Flow<List<PhotoLabelEntity>>

    /** One slice of [labelsForKeys]; callers go through that, which bounds the list. */
    @Query(
        "SELECT * FROM photo_label WHERE userId = :userId AND photoKey IN (:photoKeys) " +
            "ORDER BY photoKey, updatedAt, rowid",
    )
    suspend fun labelsForKeysChunk(userId: String, photoKeys: List<String>): List<PhotoLabelEntity>

    /** The labels of every photo in [photoKeys], queried in slices under the host-variable limit. */
    suspend fun labelsForKeys(userId: String, photoKeys: Collection<String>): List<PhotoLabelEntity> =
        photoKeys.distinct().flatMapSqlChunks { labelsForKeysChunk(userId, it) }

    /** Every label row of the account, each photo's labels in display order. */
    @Query("SELECT * FROM photo_label WHERE userId = :userId ORDER BY photoKey, updatedAt, rowid")
    suspend fun allLabelsForUser(userId: String): List<PhotoLabelEntity>

    /** Keeps [row] as a label with no photo yet, unless the account already has that label. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertName(row: PhotoLabelNameEntity): Long

    /** The labels the account created without a photo, in the order they were made. */
    @Query("SELECT * FROM photo_label_name WHERE userId = :userId ORDER BY createdAt, rowid")
    suspend fun namesForUser(userId: String): List<PhotoLabelNameEntity>

    /** [namesForUser], live. */
    @Query("SELECT * FROM photo_label_name WHERE userId = :userId ORDER BY createdAt, rowid")
    fun observeNames(userId: String): Flow<List<PhotoLabelNameEntity>>

    /** The spelling the account gave [labelKey] as a label with no photo, or null. */
    @Query("SELECT label FROM photo_label_name WHERE userId = :userId AND labelKey = :labelKey")
    suspend fun nameSpellingOf(userId: String, labelKey: String): String?

    @Query("DELETE FROM photo_label_name WHERE userId = :userId AND labelKey = :labelKey")
    suspend fun deleteName(userId: String, labelKey: String): Int

    @Query("DELETE FROM photo_label_name WHERE userId = :userId")
    suspend fun deleteNamesForUser(userId: String): Int

    /** Moves the name [fromKey] to [toKey] unless the account already has [toKey]. */
    @Query(
        "UPDATE OR IGNORE photo_label_name SET labelKey = :toKey, label = :toLabel " +
            "WHERE userId = :userId AND labelKey = :fromKey",
    )
    suspend fun moveName(userId: String, fromKey: String, toKey: String, toLabel: String): Int

    @Query("UPDATE photo_label_name SET label = :label WHERE userId = :userId AND labelKey = :labelKey")
    suspend fun setNameSpelling(userId: String, labelKey: String, label: String): Int

    /** Gives [toUser] every name [fromUser] has that it does not have yet. */
    @Query(
        "INSERT OR IGNORE INTO photo_label_name (userId, labelKey, label, createdAt) " +
            "SELECT :toUser, labelKey, label, createdAt FROM photo_label_name WHERE userId = :fromUser",
    )
    suspend fun copyNames(fromUser: String, toUser: String)

    @Upsert
    suspend fun upsert(row: PhotoLabelEntity)

    @Upsert
    suspend fun upsert(rows: List<PhotoLabelEntity>)

    /** Inserts the rows whose (userId, photoKey, labelKey) is still free and skips the rest. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<PhotoLabelEntity>): List<Long>

    /** The spelling the account first gave [labelKey], or null when no photo carries it. */
    @Query("SELECT label FROM photo_label WHERE userId = :userId AND labelKey = :labelKey ORDER BY updatedAt, rowid LIMIT 1")
    suspend fun spellingOf(userId: String, labelKey: String): String?

    @Query("DELETE FROM photo_label WHERE userId = :userId AND photoKey = :photoKey AND labelKey = :labelKey")
    suspend fun deleteLabelOfPhoto(userId: String, photoKey: String, labelKey: String): Int

    @Query("DELETE FROM photo_label WHERE userId = :userId AND photoKey = :photoKey")
    suspend fun deleteAllOfPhoto(userId: String, photoKey: String): Int

    /** One slice of [deleteForPhotoKeys]. */
    @Query("DELETE FROM photo_label WHERE photoKey IN (:photoKeys)")
    suspend fun deleteForPhotoKeysChunk(photoKeys: List<String>): Int

    /**
     * Drops every label of the photos in [photoKeys] under every owner, guest included, so a photo
     * leaving view (hidden into the vault) keeps nothing searchable whichever session wrote it.
     */
    suspend fun deleteForPhotoKeys(photoKeys: Collection<String>): Int {
        var deleted = 0
        photoKeys.distinct().forEachSqlChunk { deleted += deleteForPhotoKeysChunk(it) }
        return deleted
    }

    /** Replaces one photo's labels with [rows] (already normalised and capped by the caller). */
    @Transaction
    suspend fun replaceLabelsOfPhoto(userId: String, photoKey: String, rows: List<PhotoLabelEntity>) {
        deleteAllOfPhoto(userId, photoKey)
        if (rows.isNotEmpty()) insertIgnore(rows)
    }

    /**
     * Adds [labels] (clean, distinct, display spellings) to every photo in [photoKeys], skipping a
     * label a photo already has and stopping at [LabelNormalizer.MAX_LABELS] per photo. One
     * transaction, read in slices under the host-variable limit.
     */
    @Transaction
    suspend fun addLabelsToPhotos(
        userId: String,
        photoKeys: Collection<String>,
        labels: List<String>,
        now: Long,
    ): LabelBulkResult {
        if (labels.isEmpty()) return LabelBulkResult(0, 0)
        var changed = 0
        var capped = 0
        for (chunk in photoKeys.distinct().chunked(SQL_CHUNK_SIZE)) {
            val existing = labelsForKeysChunk(userId, chunk).groupBy { it.photoKey }
            val rows = mutableListOf<PhotoLabelEntity>()
            for (key in chunk) {
                val current = existing[key].orEmpty()
                val incoming = labels.map { PhotoLabelEntity(userId, key, it, LabelNormalizer.fold(it), now) }
                val fit = rowsThatFit(current, incoming)
                val currentKeys = current.mapTo(HashSet()) { it.labelKey }
                val wanted = incoming.count { it.labelKey !in currentKeys }
                if (fit.isNotEmpty()) changed++
                if (fit.size < wanted) capped++
                rows += fit
            }
            if (rows.isNotEmpty()) insertIgnore(rows)
        }
        return LabelBulkResult(changed, capped)
    }

    /** Moves [fromKey] rows to [toKey] where the photo does not carry [toKey] yet; the rest stay put. */
    @Query(
        "UPDATE OR IGNORE photo_label SET labelKey = :toKey, label = :toLabel " +
            "WHERE userId = :userId AND labelKey = :fromKey",
    )
    suspend fun moveLabelIfFree(userId: String, fromKey: String, toKey: String, toLabel: String): Int

    @Query("UPDATE photo_label SET label = :label WHERE userId = :userId AND labelKey = :labelKey")
    suspend fun setSpelling(userId: String, labelKey: String, label: String): Int

    /** Removes one label from every photo of the account. */
    @Query("DELETE FROM photo_label WHERE userId = :userId AND labelKey = :labelKey")
    suspend fun deleteLabelEverywhere(userId: String, labelKey: String): Int

    @Query("SELECT photoKey FROM photo_label WHERE userId = :userId AND labelKey = :labelKey")
    suspend fun photoKeysOfLabel(userId: String, labelKey: String): List<String>

    /** [deleteLabelEverywhere] that also forgets the label's name. Returns how many photos lost it. */
    @Transaction
    suspend fun removeLabelEverywhere(userId: String, labelKey: String): Int {
        deleteName(userId, labelKey)
        return deleteLabelEverywhere(userId, labelKey)
    }

    /**
     * Renames the label [fromKey] to [toLabel] on every photo of the account, merging it into
     * [toKey] when that label already exists. A photo carrying both keeps its [toKey] row and loses
     * the [fromKey] one, so a merge never raises a photo's count. Every [toKey] row takes the
     * [toLabel] spelling. Returns how many rows changed key.
     */
    @Transaction
    suspend fun renameLabel(userId: String, fromKey: String, toKey: String, toLabel: String): Int {
        if (fromKey == toKey) {
            setSpelling(userId, toKey, toLabel)
            setNameSpelling(userId, toKey, toLabel)
            return 0
        }
        val moved = moveLabelIfFree(userId, fromKey, toKey, toLabel)
        deleteLabelEverywhere(userId, fromKey)
        setSpelling(userId, toKey, toLabel)
        moveName(userId, fromKey, toKey, toLabel)
        deleteName(userId, fromKey)
        setNameSpelling(userId, toKey, toLabel)
        return moved
    }

    /** Every distinct label of the account with its photo count, most used first. */
    @Query(
        "SELECT p.labelKey AS labelKey, " +
            "(SELECT q.label FROM photo_label q WHERE q.userId = :userId AND q.labelKey = p.labelKey " +
            "ORDER BY q.updatedAt, q.rowid LIMIT 1) AS label, COUNT(*) AS count " +
            "FROM photo_label p WHERE p.userId = :userId GROUP BY p.labelKey ORDER BY count DESC, p.labelKey",
    )
    fun observeUsage(userId: String): Flow<List<LabelUsage>>

    /** [observeUsage], once. */
    @Query(
        "SELECT p.labelKey AS labelKey, " +
            "(SELECT q.label FROM photo_label q WHERE q.userId = :userId AND q.labelKey = p.labelKey " +
            "ORDER BY q.updatedAt, q.rowid LIMIT 1) AS label, COUNT(*) AS count " +
            "FROM photo_label p WHERE p.userId = :userId GROUP BY p.labelKey ORDER BY count DESC, p.labelKey",
    )
    suspend fun usage(userId: String): List<LabelUsage>

    /**
     * Moves each photo's labels from its old key to its new one, the device URI to the Drive linkId
     * once the photo is backed up, [REKEY_BATCH_SIZE] pairs per transaction. Returns how many rows
     * landed under a new key. See [rekeyPhotoBatch].
     */
    suspend fun rekeyPhotos(userId: String, pairs: List<Pair<String, String>>): Int {
        var moved = 0
        for (batch in pairs.chunked(REKEY_BATCH_SIZE)) moved += rekeyPhotoBatch(userId, batch)
        return moved
    }

    /**
     * One transaction of [rekeyPhotos]: for each (oldKey, newKey) the old rows join the new key where
     * the label is not there yet and the photo is under [LabelNormalizer.MAX_LABELS], keeping their
     * spelling and order; then every row left under the old key is dropped. A self pair is skipped.
     */
    @Transaction
    suspend fun rekeyPhotoBatch(userId: String, pairs: List<Pair<String, String>>): Int {
        var moved = 0
        for ((oldKey, newKey) in pairs) {
            if (oldKey == newKey) continue
            val moving = labelsOfPhoto(userId, oldKey)
            if (moving.isEmpty()) continue
            val fit = rowsThatFit(labelsOfPhoto(userId, newKey), moving.map { it.copy(photoKey = newKey) })
            if (fit.isNotEmpty()) insertIgnore(fit)
            deleteAllOfPhoto(userId, oldKey)
            moved += fit.size
        }
        return moved
    }

    @Query("SELECT DISTINCT photoKey FROM photo_label WHERE userId = :userId")
    suspend fun photoKeysForUser(userId: String): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM photo_label WHERE userId = :userId)")
    suspend fun hasAnyForUser(userId: String): Boolean

    /** Hands every row of [fromUser] to [toUser] for the photos [toUser] has no label on yet. */
    @Query(
        "UPDATE photo_label SET userId = :toUser WHERE userId = :fromUser " +
            "AND photoKey NOT IN (SELECT photoKey FROM photo_label WHERE userId = :toUser)",
    )
    suspend fun moveRowsWherePhotoFree(fromUser: String, toUser: String): Int

    /**
     * Adopts the labels of [fromUser] (the guest) into [toUser] (the account signing in). A photo the
     * account has not labelled moves over whole; on one both have labelled, the account's labels win
     * and the guest's join behind them up to [LabelNormalizer.MAX_LABELS]. The guest's labels without a
     * photo come along. Nothing is left under [fromUser]. Returns how many rows the account gained.
     */
    @Transaction
    suspend fun adoptUserRows(fromUser: String, toUser: String): Int {
        if (fromUser == toUser) return 0
        copyNames(fromUser, toUser)
        var adopted = moveRowsWherePhotoFree(fromUser, toUser)
        for (key in photoKeysForUser(fromUser)) {
            val incoming = labelsOfPhoto(fromUser, key).map { it.copy(userId = toUser) }
            val fit = rowsThatFit(labelsOfPhoto(toUser, key), incoming)
            if (fit.isNotEmpty()) insertIgnore(fit)
            adopted += fit.size
        }
        deleteAllForUser(fromUser)
        return adopted
    }

    @Query("DELETE FROM photo_label WHERE userId = :userId")
    suspend fun deleteLabelRowsForUser(userId: String): Int

    /** Removes every label of the account, the ones without a photo included, for the sign-out wipe. */
    @Transaction
    suspend fun deleteAllForUser(userId: String): Int {
        deleteNamesForUser(userId)
        return deleteLabelRowsForUser(userId)
    }
}
