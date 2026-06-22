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
import kotlinx.coroutines.flow.Flow
import eu.akoos.photos.data.db.entity.DayMetaEntity

@Dao
interface DayMetaDao {

    @Query("SELECT * FROM day_meta WHERE userId = :userId")
    fun observeAll(userId: String): Flow<List<DayMetaEntity>>

    /** Live row for a specific day — backs the Day Detail screen's editable fields. */
    @Query("SELECT * FROM day_meta WHERE userId = :userId AND date = :date LIMIT 1")
    fun observeByDate(userId: String, date: String): Flow<DayMetaEntity?>

    @Query("SELECT * FROM day_meta WHERE userId = :userId AND date = :date LIMIT 1")
    suspend fun getByDate(userId: String, date: String): DayMetaEntity?

    /** Days whose description text matches [needle] (caller supplies the `%` wildcards). */
    @Query(
        """
        SELECT * FROM day_meta
        WHERE userId = :userId
          AND LOWER(IFNULL(description, '')) LIKE :needle
        """
    )
    suspend fun searchByText(userId: String, needle: String): List<DayMetaEntity>

    @Upsert
    suspend fun upsert(entity: DayMetaEntity)

    @Query("DELETE FROM day_meta WHERE userId = :userId AND date = :date")
    suspend fun delete(userId: String, date: String)

    /** Wipe a user's day-meta on sign-out so typed location/description text doesn't linger. */
    @Query("DELETE FROM day_meta WHERE userId = :userId")
    suspend fun deleteAll(userId: String)
}
