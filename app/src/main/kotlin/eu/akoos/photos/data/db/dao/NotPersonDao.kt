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
import eu.akoos.photos.data.db.entity.NotPersonEntity

/**
 * The "not this person" feedback the suggestion review records, keyed by person name so it survives a
 * clustering rebuild. Read at suggestion time (to never re-offer a rejected match) and at cluster time
 * (so the attract pass never pulls a rejected face into that person).
 */
@Dao
interface NotPersonDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(rows: List<NotPersonEntity>)

    /** Every not-person mark for the account, so a rebuild and the suggestion pass read it in one go. */
    @Query("SELECT * FROM not_person WHERE userId = :userId")
    suspend fun allForUser(userId: String): List<NotPersonEntity>

    /** Re-key the marks when a person is renamed, so the rejections follow the new name. */
    @Query("UPDATE not_person SET personName = :newName WHERE userId = :userId AND personName = :oldName")
    suspend fun rename(userId: String, oldName: String, newName: String)

    /** Drop the "not this person" marks for a name, when that person is dismissed as "not a person". */
    @Query("DELETE FROM not_person WHERE userId = :userId AND personName = :personName")
    suspend fun clearForName(userId: String, personName: String)

    @Query("DELETE FROM not_person WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}
