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

    /**
     * The user's own photo stream. Shared-with-me photos sit in the same table parented to an
     * album linkId, so excluding rows whose parent is a known album keeps them off the timeline;
     * own photos are parented to the photos root. NULL parentLinkId = legacy own photos, kept visible.
     */
    @Query(
        "SELECT * FROM photo_listing WHERE userId = :userId AND (parentLinkId IS NULL OR " +
            "parentLinkId NOT IN (SELECT DISTINCT albumLinkId FROM album_photo_membership)) " +
            "ORDER BY captureTime DESC",
    )
    fun observeOwnStream(userId: String): Flow<List<PhotoListingEntity>>

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

    /** Page of undecrypted-thumbnail rows with capture time before [beforeTime], newest first — fed to
     *  the scheduler's background warm-up so the most recently captured photos warm first and the cold
     *  tail decrypts on demand. Paged (not one big query) so a huge library's rows — each carrying
     *  crypto material — never all sit in memory at once. */
    @Query("SELECT * FROM photo_listing WHERE userId = :userId AND thumbnailUrl IS NULL AND captureTime < :beforeTime ORDER BY captureTime DESC LIMIT :limit")
    suspend fun getUndecryptedThumbnailsBefore(userId: String, beforeTime: Long, limit: Int): List<PhotoListingEntity>

    /** Returns entities for the given linkIds — used to preserve existing thumbnailUrls during refresh. */
    @Query("SELECT * FROM photo_listing WHERE linkId IN (:linkIds)")
    suspend fun getByLinkIds(linkIds: List<String>): List<PhotoListingEntity>

    /** Live observation for a specific set of linkIds — used by album detail screen. */
    @Query("SELECT * FROM photo_listing WHERE linkId IN (:linkIds) ORDER BY captureTime DESC")
    fun observeByLinkIds(linkIds: List<String>): Flow<List<PhotoListingEntity>>

    /** Cached photos for an album, for instant album-detail paint. Needs a populated parentLinkId —
     *  pre-v4→v5 legacy rows lack it and still need a network refresh on first open. */
    @Query("SELECT * FROM photo_listing WHERE parentLinkId = :albumLinkId ORDER BY captureTime DESC")
    suspend fun getByParentLinkId(albumLinkId: String): List<PhotoListingEntity>

    /** Writes JUST the thumbnailUrl — a full-row upsert would race a concurrent metadata refresh
     *  and could overwrite the freshly-decrypted URL with a stale null. */
    @Query("UPDATE photo_listing SET thumbnailUrl = :url WHERE linkId = :linkId")
    suspend fun updateThumbnailUrl(linkId: String, url: String)

    /** Nulls every cached `file://` path after the on-disk thumbnails are deleted, re-enabling the
     *  lazy decrypt path (the scheduler skips rows whose thumbnailUrl is still non-null). */
    @Query("UPDATE photo_listing SET thumbnailUrl = NULL WHERE thumbnailUrl LIKE 'file://%'")
    suspend fun clearCachedThumbnailUrls()

    /** Per-link version of [clearCachedThumbnailUrls] for size-bounded cache eviction. Crypto
     *  material stays on the row, so a re-warm costs one decrypt, never a network round-trip. */
    @Query("UPDATE photo_listing SET thumbnailUrl = NULL WHERE linkId IN (:linkIds)")
    suspend fun clearThumbnailUrlsByLinkIds(linkIds: List<String>)
}
