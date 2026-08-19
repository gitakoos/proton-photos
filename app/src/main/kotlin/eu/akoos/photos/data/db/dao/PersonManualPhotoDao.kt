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
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.util.forEachSqlChunk
import kotlinx.coroutines.flow.Flow

/**
 * The photos the user has manually attached to named people, keyed by name so a membership survives a
 * clustering rebuild (see [PersonManualPhotoEntity]). Reads back as a live [Flow] so a person's grid
 * updates the moment a photo is added or removed.
 */
@Dao
interface PersonManualPhotoDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(rows: List<PersonManualPhotoEntity>)

    /** The item keys manually attached to [personName], observed so a person's grid stays current. */
    @Query("SELECT photoKey FROM person_manual_photo WHERE userId = :userId AND personName = :personName")
    fun photoKeysForName(userId: String, personName: String): Flow<List<String>>

    /** One-shot keys for [personName], for recomputing a person's photo count off the write path. */
    @Query("SELECT photoKey FROM person_manual_photo WHERE userId = :userId AND personName = :personName")
    suspend fun photoKeysForNameList(userId: String, personName: String): List<String>

    /** Every manual membership for the account, so a rebuild can fold manual photos into each person's
     *  count in one read rather than a query per person. */
    @Query("SELECT * FROM person_manual_photo WHERE userId = :userId")
    suspend fun allForUser(userId: String): List<PersonManualPhotoEntity>

    /** Drop every manual attachment for a name, when that person is dismissed as "not a person". */
    @Query("DELETE FROM person_manual_photo WHERE userId = :userId AND personName = :personName")
    suspend fun clearForName(userId: String, personName: String)

    /** Detach the given photos from [personName]. Chunked so a large selection stays under the SQL
     *  host-variable limit. */
    suspend fun remove(userId: String, personName: String, photoKeys: Collection<String>) =
        photoKeys.forEachSqlChunk { removeChunk(userId, personName, it) }

    @Query("DELETE FROM person_manual_photo WHERE userId = :userId AND personName = :personName AND photoKey IN (:photoKeys)")
    suspend fun removeChunk(userId: String, personName: String, photoKeys: List<String>)

    /** Re-key every manual membership when a person is renamed, so the photos follow the new name. */
    @Query("UPDATE person_manual_photo SET personName = :newName WHERE userId = :userId AND personName = :oldName")
    suspend fun rename(userId: String, oldName: String, newName: String)

    @Query("DELETE FROM person_manual_photo WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}
