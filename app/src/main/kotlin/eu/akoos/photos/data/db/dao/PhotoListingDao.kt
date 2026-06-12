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
     * The user's own photo stream. Photos loaded from a shared-with-me album live in the same
     * table but carry the album linkId as their parentLinkId (the recipient-side pin written by
     * the album loader); the user's own photos are parented to their photos root, never to an
     * album. Excluding rows whose parent is a known album keeps someone else's shared photos
     * off the timeline, the search index and the widget picker. parentLinkId IS NULL rows are
     * legacy own photos from before the parent column existed — they stay visible.
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

    /** Rows whose thumbnail hasn't been decrypted yet, newest photo first — fed to the
     *  scheduler's background warm-up. The scheduler walks this list until the on-disk cache
     *  reaches its size budget, so the most recently captured photos (the ones a user is most
     *  likely to browse) get warmed first and the cold tail decrypts on demand. */
    @Query("SELECT * FROM photo_listing WHERE userId = :userId AND thumbnailUrl IS NULL ORDER BY captureTime DESC")
    suspend fun getUndecryptedThumbnails(userId: String): List<PhotoListingEntity>

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

    /**
     * Clears every cached `file://` thumbnail path so the rows fall back to the lazy
     * decrypt path. Called after the decrypted-thumbnail files are deleted from disk:
     * the stored paths now point at missing files, and the scheduler skips re-decrypt
     * while `thumbnailUrl` is non-null. Nulling the column lets each visible cell
     * re-request its thumbnail immediately off the persisted crypto material, with no
     * per-photo network round-trip and without waiting for a full library refresh.
     */
    @Query("UPDATE photo_listing SET thumbnailUrl = NULL WHERE thumbnailUrl LIKE 'file://%'")
    suspend fun clearCachedThumbnailUrls()

    /**
     * Nulls the cached `file://` thumbnail path for a specific set of links. Used by the
     * thumbnail cache's size-bounded eviction: once the oldest `thumb_<linkId>.jpg` files are
     * deleted to stay under the cache cap, their rows must drop the now-dangling path so the
     * scheduler re-decrypts them the next time the cell scrolls into view (the enqueue path
     * skips any row whose thumbnailUrl is still non-null). The crypto material stays on the
     * row, so a re-warm costs one decrypt, never a network round-trip.
     */
    @Query("UPDATE photo_listing SET thumbnailUrl = NULL WHERE linkId IN (:linkIds)")
    suspend fun clearThumbnailUrlsByLinkIds(linkIds: List<String>)
}
