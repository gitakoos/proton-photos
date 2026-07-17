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
import eu.akoos.photos.data.db.entity.UploadAlbumTargetEntity

@Dao
interface UploadAlbumTargetDao {

    /** Queue a photo to join an album on upload. IGNORE on conflict so re-adding the same photo to
     *  the same album is a harmless no-op (the composite PK already covers the pair). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: UploadAlbumTargetEntity)

    /** Convenience overload matching the (localUri, albumLinkId) call sites. */
    suspend fun insertIgnore(localUri: String, albumLinkId: String) =
        insertIgnore(UploadAlbumTargetEntity(localUri = localUri, albumLinkId = albumLinkId))

    /** The album linkIds a given local photo is queued to join. */
    @Query("SELECT albumLinkId FROM upload_album_target WHERE localUri = :localUri")
    suspend fun getTargetsFor(localUri: String): List<String>

    /** Drop every queued album target for a local photo once it has joined them (or is abandoned). */
    @Query("DELETE FROM upload_album_target WHERE localUri = :localUri")
    suspend fun deleteFor(localUri: String)

    /** Drop one queued (photo, album) pair after that single album-add lands. A photo can target
     *  several albums, so a partial success must remove only the pair that succeeded and leave the
     *  rest for the next drain, never wipe the whole photo with [deleteFor]. */
    @Query("DELETE FROM upload_album_target WHERE localUri = :localUri AND albumLinkId = :albumLinkId")
    suspend fun deleteTarget(localUri: String, albumLinkId: String)

    /** Every queued (photo, album) pair — the drain's source of truth. */
    @Query("SELECT * FROM upload_album_target")
    suspend fun getAll(): List<UploadAlbumTargetEntity>
}
