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
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import eu.akoos.photos.data.db.entity.PhotoListingEntity

@Dao
interface PhotoListingDao {

    @Query("SELECT * FROM photo_listing WHERE userId = :userId ORDER BY captureTime DESC")
    fun observeAll(userId: String): Flow<List<PhotoListingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PhotoListingEntity>)

    @Upsert
    suspend fun upsertAll(entities: List<PhotoListingEntity>)

    @Query("DELETE FROM photo_listing WHERE userId = :userId")
    suspend fun deleteAll(userId: String)

    @Query("DELETE FROM photo_listing WHERE linkId IN (:linkIds)")
    suspend fun deleteByLinkIds(linkIds: List<String>)

    @Query("SELECT * FROM photo_listing WHERE linkId = :linkId LIMIT 1")
    suspend fun getByLinkId(linkId: String): PhotoListingEntity?

    /** Returns all linkIds currently stored for a user. Used by the smart-merge refresh strategy. */
    @Query("SELECT linkId FROM photo_listing WHERE userId = :userId")
    suspend fun getAllLinkIds(userId: String): List<String>

    /** Returns entities for the given linkIds — used to preserve existing thumbnailUrls during refresh. */
    @Query("SELECT * FROM photo_listing WHERE linkId IN (:linkIds)")
    suspend fun getByLinkIds(linkIds: List<String>): List<PhotoListingEntity>

    /** Live observation for a specific set of linkIds — used by album detail screen. */
    @Query("SELECT * FROM photo_listing WHERE linkId IN (:linkIds) ORDER BY captureTime DESC")
    fun observeByLinkIds(linkIds: List<String>): Flow<List<PhotoListingEntity>>

    /** Cached photos belonging to a specific album. Used by album detail to populate the
     *  grid INSTANTLY from the DB before the network refresh kicks in. Requires that the
     *  photo's `parentLinkId` field is populated — true for entities built by PhotoEntityBuilder
     *  after the v4→v5 migration, false for pre-migration legacy rows (those still need the
     *  full network refresh on first open). */
    @Query("SELECT * FROM photo_listing WHERE parentLinkId = :albumLinkId ORDER BY captureTime DESC")
    suspend fun getByParentLinkId(albumLinkId: String): List<PhotoListingEntity>

    /**
     * Lazy-thumbnail-decrypt: after the scheduler decrypts a single photo's thumbnail,
     * write JUST the URL field without touching anything else. Going through `upsertAll`
     * with a full row would race against concurrent metadata refreshes (which might
     * over-write a freshly-decrypted URL with stale null).
     */
    @Query("UPDATE photo_listing SET thumbnailUrl = :url WHERE linkId = :linkId")
    suspend fun updateThumbnailUrl(linkId: String, url: String)
}
