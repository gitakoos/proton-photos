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
import androidx.room.Query
import androidx.room.Upsert
import eu.akoos.photos.data.db.entity.DriveCopyAppliedEntity

/** Which copy of each other install's data the shared backup has taken in, per account and kind. */
@Dao
interface DriveCopyAppliedDao {

    /** Every other install's copy of [kind] taken in for [userId], with the time each copy was made. */
    @Query("SELECT * FROM drive_copy_applied WHERE userId = :userId AND kind = :kind")
    suspend fun forKind(userId: String, kind: String): List<DriveCopyAppliedEntity>

    @Upsert
    suspend fun upsert(rows: List<DriveCopyAppliedEntity>)

    /** Forgets the copies of [kind] taken in for [userId], so the next round takes them in again. */
    @Query("DELETE FROM drive_copy_applied WHERE userId = :userId AND kind = :kind")
    suspend fun deleteKind(userId: String, kind: String): Int

    /** Forgets every copy taken in for [userId], for the sign-out wipe. */
    @Query("DELETE FROM drive_copy_applied WHERE userId = :userId")
    suspend fun deleteForUser(userId: String): Int
}
