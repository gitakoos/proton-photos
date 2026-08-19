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
import eu.akoos.photos.data.db.entity.PersonCoverEntity

/**
 * The cover photo a user has chosen for each named person, keyed by name so the choice survives a
 * clustering rebuild (see [PersonCoverEntity]). One row per person: setting a new cover replaces the
 * old one.
 */
@Dao
interface PersonCoverDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(row: PersonCoverEntity)

    /** The chosen cover photo key for [personName], or null when the automatic cover should be used. */
    @Query("SELECT photoKey FROM person_cover WHERE userId = :userId AND personName = :personName LIMIT 1")
    suspend fun photoKeyForName(userId: String, personName: String): String?

    /** Every chosen cover for the account, so a rebuild can apply them in one read. */
    @Query("SELECT * FROM person_cover WHERE userId = :userId")
    suspend fun allForUser(userId: String): List<PersonCoverEntity>

    /** Follow a rename, so the chosen cover stays with the person under its new name. OR REPLACE
     *  because the target name may already hold a cover (e.g. when a merge re-keys onto it): the moved
     *  cover replaces it rather than colliding on the (userId, personName) key. */
    @Query("UPDATE OR REPLACE person_cover SET personName = :newName WHERE userId = :userId AND personName = :oldName")
    suspend fun rename(userId: String, oldName: String, newName: String)

    /** Drop the chosen cover for a name, when that person is dismissed as "not a person". */
    @Query("DELETE FROM person_cover WHERE userId = :userId AND personName = :personName")
    suspend fun clearForName(userId: String, personName: String)

    @Query("DELETE FROM person_cover WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}
