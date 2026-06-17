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
import eu.akoos.photos.data.db.entity.LocalTagEntity

@Dao
interface LocalTagDao {

    /** Insert or replace one detection result. */
    @Upsert
    suspend fun upsert(entity: LocalTagEntity)

    /** Insert or replace a batch of detection results in one transaction. */
    @Upsert
    suspend fun upsert(entities: List<LocalTagEntity>)

    /** Every cached row. The repository turns this into a `uri → entity` map for an O(1)
     *  freshness lookup during a MediaStore scan. */
    @Query("SELECT * FROM local_tag")
    suspend fun getAll(): List<LocalTagEntity>

    /** Drop cache rows whose URIs no longer appear in MediaStore (file deleted). Keeps the
     *  table from growing without bound as the on-device library churns. */
    @Query("DELETE FROM local_tag WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    /** Drop the entire cache. Used when the detection-logic version changes so every file is
     *  re-detected with the new rules on the scan that follows. */
    @Query("DELETE FROM local_tag")
    suspend fun deleteAll()
}
