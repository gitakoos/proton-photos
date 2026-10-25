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
import eu.akoos.photos.data.db.entity.PersonNameChangeEntity
import eu.akoos.photos.data.db.entity.PersonNameStateEntity

/** When each named person got their name, and the renames and merges made since, for the shared face copy. */
@Dao
interface PersonNameDao {

    @Query("SELECT * FROM person_name_state WHERE userId = :userId")
    suspend fun statesForUser(userId: String): List<PersonNameStateEntity>

    @Query("SELECT namedAt FROM person_name_state WHERE userId = :userId AND name = :name")
    suspend fun namedAt(userId: String, name: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setState(row: PersonNameStateEntity)

    @Query("DELETE FROM person_name_state WHERE userId = :userId AND name = :name")
    suspend fun deleteState(userId: String, name: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addChange(row: PersonNameChangeEntity)

    /** The newest [limit] renames and merges, oldest first, the order an importer applies them in. */
    @Query(
        "SELECT * FROM (SELECT * FROM person_name_change WHERE userId = :userId " +
            "ORDER BY changedAt DESC LIMIT :limit) ORDER BY changedAt ASC",
    )
    suspend fun recentChanges(userId: String, limit: Int): List<PersonNameChangeEntity>

    /** Drops all but the newest [keep] renames and merges. */
    @Query(
        "DELETE FROM person_name_change WHERE userId = :userId AND rowid NOT IN " +
            "(SELECT rowid FROM person_name_change WHERE userId = :userId ORDER BY changedAt DESC LIMIT :keep)",
    )
    suspend fun pruneChanges(userId: String, keep: Int)

    @Query("UPDATE OR REPLACE person_name_state SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun updateStateUserId(oldUserId: String, newUserId: String)

    @Query("UPDATE OR IGNORE person_name_change SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun updateChangeUserId(oldUserId: String, newUserId: String)

    @Query("DELETE FROM person_name_state WHERE userId = :userId")
    suspend fun clearStates(userId: String)

    @Query("DELETE FROM person_name_change WHERE userId = :userId")
    suspend fun clearChanges(userId: String)
}

/** Drops [userId]'s name times and changes, with the rest of its face data. */
suspend fun PersonNameDao.clearForUser(userId: String) {
    clearStates(userId)
    clearChanges(userId)
}
