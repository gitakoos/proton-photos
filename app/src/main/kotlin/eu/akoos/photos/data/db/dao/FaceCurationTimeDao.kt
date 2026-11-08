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
import eu.akoos.photos.data.db.entity.FaceCurationTimeEntity

/** When each piece of a person's curation was last changed by hand, removals included, for the shared face copy. */
@Dao
interface FaceCurationTimeDao {

    @Query("SELECT * FROM face_curation_time WHERE userId = :userId")
    suspend fun allForUser(userId: String): List<FaceCurationTimeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(rows: List<FaceCurationTimeEntity>)

    /** The account's times about one photo: those of its manual adds ([ref] the photo key) and of the marks on
     *  its faces ([ref] a face id, `photoKey#index`), so a backup re-key can move them with the photo. */
    @Query(
        "SELECT * FROM face_curation_time WHERE userId = :userId AND (ref = :photoKey OR " +
            "(substr(ref, 1, length(:photoKey)) = :photoKey AND substr(ref, length(:photoKey) + 1, 1) = '#'))",
    )
    suspend fun rowsOnPhoto(userId: String, photoKey: String): List<FaceCurationTimeEntity>

    /** Drops the account's times about one photo, once a backup re-key has written them under the new key. */
    @Query(
        "DELETE FROM face_curation_time WHERE userId = :userId AND (ref = :photoKey OR " +
            "(substr(ref, 1, length(:photoKey)) = :photoKey AND substr(ref, length(:photoKey) + 1, 1) = '#'))",
    )
    suspend fun deleteOnPhoto(userId: String, photoKey: String)

    /** Follows a rename, so the times stay with the person under the new name. */
    @Query("UPDATE OR REPLACE face_curation_time SET personName = :newName WHERE userId = :userId AND personName = :oldName")
    suspend fun rename(userId: String, oldName: String, newName: String)

    /** Drops all but the newest [keep] removals; what is still there needs no removal to stay. */
    @Query(
        "DELETE FROM face_curation_time WHERE userId = :userId AND removed = 1 AND rowid NOT IN " +
            "(SELECT rowid FROM face_curation_time WHERE userId = :userId AND removed = 1 ORDER BY changedAt DESC LIMIT :keep)",
    )
    suspend fun pruneRemovals(userId: String, keep: Int)

    @Query("UPDATE OR REPLACE face_curation_time SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun updateUserId(oldUserId: String, newUserId: String)

    @Query("DELETE FROM face_curation_time WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}
