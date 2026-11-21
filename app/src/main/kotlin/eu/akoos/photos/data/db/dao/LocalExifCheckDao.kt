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
import eu.akoos.photos.data.db.entity.LocalExifCheckEntity

@Dao
interface LocalExifCheckDao {

    /** Every recorded check; the walk reads them once and classifies the whole library against them. */
    @Query("SELECT * FROM local_exif_check")
    suspend fun all(): List<LocalExifCheckEntity>

    @Upsert
    suspend fun upsert(items: List<LocalExifCheckEntity>)

    /**
     * Drops the checks of [uris], so the next walk reads those files again. Callers slice [uris] with
     * [eu.akoos.photos.util.forEachSqlChunk]: one host variable per element.
     */
    @Query("DELETE FROM local_exif_check WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)
}
