/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoLocationDao {

    /** Insert or replace a batch of GPS fixes in one transaction. */
    @Upsert
    suspend fun upsert(items: List<PhotoLocationEntity>)

    /** Live stream of every located photo for the account — the map view's source. */
    @Query("SELECT * FROM photo_location WHERE userId = :userId")
    fun observeForUser(userId: String): Flow<List<PhotoLocationEntity>>

    /** Ids already located, so a backfill can skip them without re-reading EXIF. */
    @Query("SELECT id FROM photo_location WHERE userId = :userId")
    suspend fun idsForUser(userId: String): List<String>

    /** The stored GPS fix for one photo (by its cloud linkId), or null if not located yet. Used by
     *  the details sheet to show a cloud-only photo's place without re-decrypting its XAttr. */
    @Query("SELECT * FROM photo_location WHERE userId = :userId AND id = :id LIMIT 1")
    suspend fun getById(userId: String, id: String): PhotoLocationEntity?
}
