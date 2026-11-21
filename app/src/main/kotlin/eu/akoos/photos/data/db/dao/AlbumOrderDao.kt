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
import eu.akoos.photos.data.db.entity.AlbumOrderStateEntity
import eu.akoos.photos.data.db.entity.AlbumPhotoOrderEntity
import kotlinx.coroutines.flow.Flow

/** The newest photo time of one album, for the "Newest photo" album sort. */
data class AlbumNewestPhoto(val albumLinkId: String, val newest: Long)

/** Hand-made photo orders and each album's photo order choice. */
@Dao
interface AlbumOrderDao {

    @Query("SELECT * FROM album_order_state WHERE userId = :userId AND albumKey = :albumKey")
    fun observeState(userId: String, albumKey: String): Flow<AlbumOrderStateEntity?>

    @Query("SELECT * FROM album_order_state WHERE userId = :userId AND albumKey = :albumKey")
    suspend fun state(userId: String, albumKey: String): AlbumOrderStateEntity?

    @Query("SELECT * FROM album_order_state WHERE userId = :userId")
    suspend fun states(userId: String): List<AlbumOrderStateEntity>

    @Query("SELECT * FROM album_order_state WHERE userId = :userId AND mode IS NOT NULL")
    fun observeModes(userId: String): Flow<List<AlbumOrderStateEntity>>

    @Query("SELECT photoKey FROM album_photo_order WHERE userId = :userId AND albumKey = :albumKey ORDER BY position")
    fun observeArrangement(userId: String, albumKey: String): Flow<List<String>>

    @Query("SELECT photoKey FROM album_photo_order WHERE userId = :userId AND albumKey = :albumKey ORDER BY position")
    suspend fun arrangement(userId: String, albumKey: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(row: AlbumOrderStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(rows: List<AlbumPhotoOrderEntity>)

    @Query("DELETE FROM album_photo_order WHERE userId = :userId AND albumKey = :albumKey")
    suspend fun clearArrangement(userId: String, albumKey: String)

    @Query("UPDATE album_order_state SET mode = :mode, modeAt = :at WHERE userId = :userId AND albumKey = :albumKey")
    suspend fun updateMode(userId: String, albumKey: String, mode: String?, at: Long?): Int

    @Query("UPDATE album_order_state SET orderAt = :at WHERE userId = :userId AND albumKey = :albumKey")
    suspend fun updateOrderAt(userId: String, albumKey: String, at: Long?): Int

    /** Sets [albumKey]'s mode and its time, leaving the arrangement and its time as they are. */
    @Transaction
    suspend fun setMode(userId: String, albumKey: String, mode: String?, at: Long?) {
        if (updateMode(userId, albumKey, mode, at) == 0) insertState(AlbumOrderStateEntity(userId, albumKey, mode, at, null))
    }

    /** Replaces [albumKey]'s arrangement with [keys] and stamps it [at], leaving the mode as it is. */
    @Transaction
    suspend fun setArrangement(userId: String, albumKey: String, keys: List<String>, at: Long?) {
        clearArrangement(userId, albumKey)
        if (keys.isNotEmpty()) {
            insertOrder(keys.distinct().mapIndexed { index, key -> AlbumPhotoOrderEntity(userId, albumKey, key, index) })
        }
        if (updateOrderAt(userId, albumKey, at) == 0) insertState(AlbumOrderStateEntity(userId, albumKey, null, null, at))
    }

    /** A Done in arrange mode: the arrangement and the custom mode land together, both stamped [at]. */
    @Transaction
    suspend fun saveCustom(userId: String, albumKey: String, keys: List<String>, mode: String, at: Long) {
        setArrangement(userId, albumKey, keys, at)
        setMode(userId, albumKey, mode, at)
    }

    /** A mode carried over from before the choice was kept here, at time 0, only where the album has none yet. */
    @Query(
        "INSERT OR IGNORE INTO album_order_state (userId, albumKey, mode, modeAt, orderAt) " +
            "VALUES (:userId, :albumKey, :mode, 0, NULL)",
    )
    suspend fun seedMode(userId: String, albumKey: String, mode: String)

    @Query("DELETE FROM album_photo_order WHERE userId = :userId")
    suspend fun clearOrdersForUser(userId: String)

    @Query("DELETE FROM album_order_state WHERE userId = :userId")
    suspend fun clearStatesForUser(userId: String)

    @Transaction
    suspend fun deleteForUser(userId: String) {
        clearOrdersForUser(userId)
        clearStatesForUser(userId)
    }

    /** The newest Drive capture time among each album's cached photos; an album none of whose photos is cached is absent. */
    @Query(
        "SELECT m.albumLinkId AS albumLinkId, MAX(p.captureTime) AS newest FROM album_photo_membership m " +
            "JOIN photo_listing p ON p.linkId = m.photoLinkId WHERE p.userId = :userId GROUP BY m.albumLinkId",
    )
    fun observeNewestPhotoTimes(userId: String): Flow<List<AlbumNewestPhoto>>
}
