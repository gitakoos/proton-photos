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
import eu.akoos.photos.data.db.entity.PersonHiddenEntity

/** Which people are hidden, by name; the people lists read the same rows through [PersonDao]. */
@Dao
interface PersonHiddenDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(row: PersonHiddenEntity)

    @Query("SELECT * FROM person_hidden WHERE userId = :userId")
    suspend fun rowsForUser(userId: String): List<PersonHiddenEntity>

    /** The names currently hidden, a hidden=false row not counting. */
    @Query("SELECT name FROM person_hidden WHERE userId = :userId AND hidden = 1")
    suspend fun hiddenNames(userId: String): List<String>

    @Query("SELECT hidden FROM person_hidden WHERE userId = :userId AND name = :name")
    suspend fun isHidden(userId: String, name: String): Boolean?

    @Query("DELETE FROM person_hidden WHERE userId = :userId AND name = :name")
    suspend fun delete(userId: String, name: String)

    /** Moves a hidden choice from the name [from] to [to], keeping its time, when a hidden person is renamed. */
    @Query("UPDATE OR REPLACE person_hidden SET name = :to WHERE userId = :userId AND name = :from")
    suspend fun rename(userId: String, from: String, to: String)

    @Query("UPDATE OR REPLACE person_hidden SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun updateUserId(oldUserId: String, newUserId: String)

    @Query("DELETE FROM person_hidden WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}
