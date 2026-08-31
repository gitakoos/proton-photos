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
import androidx.room.Query
import androidx.room.Upsert
import eu.akoos.photos.data.db.entity.PersonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {

    /** Inserts a new person and returns its generated id, so the faces it owns can point back to it
     *  right away. */
    @Insert
    suspend fun insert(person: PersonEntity): Long

    /** Insert or replace a person by id, for updating a cluster the app already tracks. */
    @Upsert
    suspend fun upsert(person: PersonEntity)

    /** Live stream of the account's people, most-photographed first, the people screen's source. */
    @Query("SELECT * FROM person WHERE userId = :userId ORDER BY faceCount DESC")
    fun observePeopleForUser(userId: String): Flow<List<PersonEntity>>

    /** One person by id, or null. */
    @Query("SELECT * FROM person WHERE id = :id LIMIT 1")
    suspend fun personById(id: Long): PersonEntity?

    /** Every person the user has named, read before a rebuild so those names can be carried onto the
     *  new clusters instead of being lost when the person table is rebuilt. */
    @Query("SELECT * FROM person WHERE userId = :userId AND displayName IS NOT NULL")
    suspend fun namedPeopleForUser(userId: String): List<PersonEntity>

    /** Every person (named and unnamed clusters), for one-shot passes like the suggestion engine. */
    @Query("SELECT * FROM person WHERE userId = :userId")
    suspend fun allForUser(userId: String): List<PersonEntity>

    /** The account's single "Unsorted" bucket id, read before a rebuild so the leftover faces keep the
     *  same person id across passes (an open screen keyed to it does not go stale). Null when none. */
    @Query("SELECT id FROM person WHERE userId = :userId AND isOther = 1 LIMIT 1")
    suspend fun otherPersonId(userId: String): Long?

    /** Renames a person, or clears the name back to null. */
    @Query("UPDATE person SET displayName = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String?)

    /** Refreshes a person's cover face and cached face count after a (re)cluster. */
    @Query("UPDATE person SET coverFaceId = :coverFaceId, faceCount = :faceCount WHERE id = :id")
    suspend fun updateCoverAndCount(id: Long, coverFaceId: String?, faceCount: Int)

    /** Sets only a person's cover face, for a user's explicit cover pick. */
    @Query("UPDATE person SET coverFaceId = :coverFaceId WHERE id = :id")
    suspend fun updateCover(id: Long, coverFaceId: String)

    /** Drops people that ended up with no faces, so an emptied cluster leaves no tile behind. */
    @Query("DELETE FROM person WHERE userId = :userId AND faceCount = 0")
    suspend fun deleteEmpty(userId: String)

    /** Removes every person for the account, for the sign-out wipe. */
    @Query("DELETE FROM person WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}
