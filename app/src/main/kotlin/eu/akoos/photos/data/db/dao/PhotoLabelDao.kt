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
import eu.akoos.photos.data.db.entity.PhotoLabelDeletedEntity
import eu.akoos.photos.data.db.entity.PhotoLabelNameEntity
import eu.akoos.photos.data.db.entity.PhotoLabelStateEntity
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

    /** One slice of the change times [deleteForPhotoKeys] drops. */
    @Query("DELETE FROM photo_label_state WHERE photoKey IN (:photoKeys)")
    suspend fun deleteStatesForPhotoKeysChunk(photoKeys: List<String>): Int

    /**
     * Drops every label of the photos in [photoKeys] under every owner, guest included, so a photo
     * leaving view (hidden into the vault) keeps nothing searchable whichever session wrote it. Their
     * change times go too: the photo leaves the labels, which is no edit for another device to repeat.
     */
    suspend fun deleteForPhotoKeys(photoKeys: Collection<String>): Int {
        var deleted = 0
        photoKeys.distinct().forEachSqlChunk {
            deleted += deleteForPhotoKeysChunk(it)
            deleteStatesForPhotoKeysChunk(it)
        }
        return deleted
    }

    // ---- Change times and deletions, which the shared backup merges by ----

    @Upsert
    suspend fun upsertStates(rows: List<PhotoLabelStateEntity>)

    /** When each photo of the account last had its labels changed, a photo whose labels were all taken off included. */
    @Query("SELECT * FROM photo_label_state WHERE userId = :userId")
    suspend fun statesForUser(userId: String): List<PhotoLabelStateEntity>

    @Query("SELECT * FROM photo_label_state WHERE userId = :userId AND photoKey = :photoKey")
    suspend fun stateOf(userId: String, photoKey: String): PhotoLabelStateEntity?

    @Query("DELETE FROM photo_label_state WHERE userId = :userId AND photoKey = :photoKey")
    suspend fun deleteState(userId: String, photoKey: String): Int

    @Upsert
    suspend fun upsertDeleted(rows: List<PhotoLabelDeletedEntity>)

    /** The labels the account deleted, with when. */
    @Query("SELECT * FROM photo_label_deleted WHERE userId = :userId")
    suspend fun deletedForUser(userId: String): List<PhotoLabelDeletedEntity>

    @Query("DELETE FROM photo_label_deleted WHERE userId = :userId AND labelKey = :labelKey")
    suspend fun deleteDeleted(userId: String, labelKey: String): Int

    /** Clears the deletion of each of [labelKeys] made no later than [at]: the label was put back on purpose. */
    @Query("DELETE FROM photo_label_deleted WHERE userId = :userId AND labelKey IN (:labelKeys) AND deletedAt <= :at")
    suspend fun undelete(userId: String, labelKeys: List<String>, at: Long): Int

    /** Records that each photo of [photoKeys] had its labels changed at [at]. */
    suspend fun stamp(userId: String, photoKeys: Collection<String>, at: Long) {
        if (photoKeys.isEmpty()) return
        photoKeys.distinct().chunked(SQL_CHUNK_SIZE).forEach { chunk ->
            upsertStates(chunk.map { PhotoLabelStateEntity(userId, it, at) })
        }
    }

    /**
     * Sets one photo's labels to [rows] (already normalised, capped and spelled by the caller) as of [at]. A list
     * equal to the one the photo has, spellings included, changes nothing and records no time. Otherwise the
     * change time is recorded and the deletion of each label it now carries, if made before, is cleared. True
     * when the list changed.
     */
    @Transaction
    suspend fun setLabelsOfPhoto(userId: String, photoKey: String, rows: List<PhotoLabelEntity>, at: Long): Boolean {
        val before = labelsOfPhoto(userId, photoKey).map { it.label }
        if (before == rows.map { it.label }) return false
        replaceLabelsOfPhoto(userId, photoKey, rows)
        stamp(userId, listOf(photoKey), at)
        if (rows.isNotEmpty()) undelete(userId, rows.map { it.labelKey }, at)
        return true
    }

    /** Takes one label off one photo as of [at]. True when the photo carried it. */
    @Transaction
    suspend fun removeLabelOfPhoto(userId: String, photoKey: String, labelKey: String, at: Long): Boolean {
        if (deleteLabelOfPhoto(userId, photoKey, labelKey) == 0) return false
        stamp(userId, listOf(photoKey), at)
        return true
    }

    /**
     * Writes a merge's changes in one transaction: each photo of [photos] gets the given rows (in order) and change
     * time, the names of [nameKeysToDrop] go, [namesToAdd] join unless the account has them, and [deleted] is
     * recorded. A photo whose change time is no longer the one the merge read ([expectedChangedAt], null for none)
     * was edited meanwhile and keeps that edit.
     */
    @Transaction
    suspend fun writeMerged(
        userId: String,
        photos: Map<String, Pair<List<PhotoLabelEntity>, Long>>,
        expectedChangedAt: Map<String, Long?>,
        namesToAdd: List<PhotoLabelNameEntity>,
        nameKeysToDrop: List<String>,
        deleted: List<PhotoLabelDeletedEntity>,
    ) {
        for ((key, change) in photos) {
            if (stateOf(userId, key)?.changedAt != expectedChangedAt[key]) continue
            replaceLabelsOfPhoto(userId, key, change.first)
            upsertStates(listOf(PhotoLabelStateEntity(userId, key, change.second)))
        }
        for (key in nameKeysToDrop) deleteName(userId, key)
        for (name in namesToAdd) insertName(name)
        if (deleted.isNotEmpty()) upsertDeleted(deleted)
    }

    /** Creates the label with no photo as of [row]'s time, clearing an earlier deletion of it. */
    @Transaction
    suspend fun createName(row: PhotoLabelNameEntity): Long {
        undelete(row.userId, listOf(row.labelKey), row.createdAt)
        return insertName(row)
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
            val changedKeys = mutableListOf<String>()
            for (key in chunk) {
                val current = existing[key].orEmpty()
                val incoming = labels.map { PhotoLabelEntity(userId, key, it, LabelNormalizer.fold(it), now) }
                val fit = rowsThatFit(current, incoming)
                val currentKeys = current.mapTo(HashSet()) { it.labelKey }
                val wanted = incoming.count { it.labelKey !in currentKeys }
                if (fit.isNotEmpty()) {
                    changed++
                    changedKeys += key
                }
                if (fit.size < wanted) capped++
                rows += fit
            }
            if (rows.isNotEmpty()) insertIgnore(rows)
            stamp(userId, changedKeys, now)
        }
        if (changed > 0) undelete(userId, labels.map { LabelNormalizer.fold(it) }, now)
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
     * [removeLabelEverywhere] as of [at], spelled [label]: each photo that lost it records the change, and the
     * label is recorded as deleted when the account had it at all, so an older copy from another device does not
     * bring it back. Returns how many photos lost it.
     */
    @Transaction
    suspend fun deleteLabelAt(userId: String, labelKey: String, label: String, at: Long): Int {
        val keys = photoKeysOfLabel(userId, labelKey)
        val hadName = nameSpellingOf(userId, labelKey) != null
        val removed = removeLabelEverywhere(userId, labelKey)
        stamp(userId, keys, at)
        if (hadName || keys.isNotEmpty()) upsertDeleted(listOf(PhotoLabelDeletedEntity(userId, labelKey, label, at)))
        return removed
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

    @Query("SELECT photoKey FROM photo_label WHERE userId = :userId AND labelKey = :labelKey AND label != :label")
    suspend fun photoKeysSpelledOtherwise(userId: String, labelKey: String, label: String): List<String>

    /**
     * [renameLabel] as of [at]. Each photo whose list changed records it; a new spelling of the same label stamps
     * the photos that showed the old one. A rename to another label records the old one, spelled [fromLabel], as
     * deleted a millisecond before [at], so an older copy from another device does not bring it back while that
     * device still takes the renamed photos (see [eu.akoos.photos.data.label.LabelCopyMerge.renameLabel]), and
     * clears an earlier deletion of the new one.
     */
    @Transaction
    suspend fun renameLabelAt(
        userId: String,
        fromKey: String,
        fromLabel: String,
        toKey: String,
        toLabel: String,
        at: Long,
    ): Int {
        if (fromKey == toKey) {
            stamp(userId, photoKeysSpelledOtherwise(userId, toKey, toLabel), at)
            return renameLabel(userId, fromKey, toKey, toLabel)
        }
        val keys = photoKeysOfLabel(userId, fromKey)
        val moved = renameLabel(userId, fromKey, toKey, toLabel)
        stamp(userId, keys, at)
        upsertDeleted(listOf(PhotoLabelDeletedEntity(userId, fromKey, fromLabel, at - 1)))
        undelete(userId, listOf(toKey), at)
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
            moveState(userId, oldKey, newKey)
        }
        return moved
    }

    /** Moves the change time of [oldKey] onto [newKey], keeping the later of the two when both have one. */
    suspend fun moveState(userId: String, oldKey: String, newKey: String) {
        val old = stateOf(userId, oldKey) ?: return
        val new = stateOf(userId, newKey)
        upsertStates(listOf(PhotoLabelStateEntity(userId, newKey, maxOf(old.changedAt, new?.changedAt ?: 0L))))
        deleteState(userId, oldKey)
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
        copyStates(fromUser, toUser)
        copyDeleted(fromUser, toUser)
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

    /** Gives [toUser] the change time of every photo [fromUser] has one for and it has none. */
    @Query(
        "INSERT OR IGNORE INTO photo_label_state (userId, photoKey, changedAt) " +
            "SELECT :toUser, photoKey, changedAt FROM photo_label_state WHERE userId = :fromUser",
    )
    suspend fun copyStates(fromUser: String, toUser: String)

    /** Gives [toUser] every deletion [fromUser] recorded that it has not. */
    @Query(
        "INSERT OR IGNORE INTO photo_label_deleted (userId, labelKey, label, deletedAt) " +
            "SELECT :toUser, labelKey, label, deletedAt FROM photo_label_deleted WHERE userId = :fromUser",
    )
    suspend fun copyDeleted(fromUser: String, toUser: String)

    @Query("DELETE FROM photo_label_state WHERE userId = :userId")
    suspend fun deleteStatesForUser(userId: String): Int

    @Query("DELETE FROM photo_label_deleted WHERE userId = :userId")
    suspend fun deleteDeletedForUser(userId: String): Int

    /** Removes every label of the account, the ones without a photo included, for the sign-out wipe. */
    @Transaction
    suspend fun deleteAllForUser(userId: String): Int {
        deleteNamesForUser(userId)
        deleteStatesForUser(userId)
        deleteDeletedForUser(userId)
        return deleteLabelRowsForUser(userId)
    }
}
