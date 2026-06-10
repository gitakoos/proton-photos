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
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import eu.akoos.photos.data.db.entity.AlbumPhotoMembershipEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumPhotoMembershipDao {

    @Query("SELECT photoLinkId FROM album_photo_membership WHERE albumLinkId = :albumLinkId")
    suspend fun getPhotoLinkIds(albumLinkId: String): List<String>

    /** Cloud linkIds that appear in ANY album — used by the "hide photos already in
     *  albums" Photos-tab filter. Emits a fresh snapshot whenever the membership
     *  table changes (album opened, photo added/removed, etc.). */
    @Query("SELECT DISTINCT photoLinkId FROM album_photo_membership")
    fun observeAllAssociatedPhotoLinkIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertAll(entries: List<AlbumPhotoMembershipEntity>)

    @Query("DELETE FROM album_photo_membership WHERE albumLinkId = :albumLinkId")
    suspend fun deleteAllForAlbum(albumLinkId: String)

    /** Targeted delete used by remove-from-album so the gallery filter re-fires
     *  instantly without dropping every other photo's membership row. */
    @Query("DELETE FROM album_photo_membership WHERE albumLinkId = :albumLinkId AND photoLinkId IN (:photoLinkIds)")
    suspend fun deleteForAlbumPhotos(albumLinkId: String, photoLinkIds: List<String>)

    /**
     * Atomically replaces the membership rows for an album so the offline list matches
     * the network truth without leaving deleted-on-Drive references behind.
     */
    @Transaction
    suspend fun replaceAllForAlbum(albumLinkId: String, photoLinkIds: List<String>) {
        deleteAllForAlbum(albumLinkId)
        if (photoLinkIds.isNotEmpty()) {
            upsertAll(photoLinkIds.map { AlbumPhotoMembershipEntity(albumLinkId, it) })
        }
    }

    @Query("DELETE FROM album_photo_membership")
    suspend fun clearAll()
}
