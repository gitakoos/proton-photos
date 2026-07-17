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
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.db.entity.PhotoListingLite
import eu.akoos.photos.data.db.entity.ThumbnailUrlSeed

@Dao
interface PhotoListingDao {

    /**
     * Read-back helper for DAO tests only. `SELECT *` pulls every row's crypto blob, which pins the
     * heap on a large library, so any production timeline read must use [observeOwnStreamLite] or
     * another projection that names only the columns it displays.
     */
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

    /**
     * Display-only projection of [observeOwnStream] for the timeline feed — selects just the columns
     * the grid binds, NOT the per-row crypto material. The full entity's encNodeKey / contentKeyPacket
     * / encNodePassphrase / encXAttr are armored PGP blocks (kilobytes each); selecting them for the
     * whole library on every Room re-emit, only to drop them in toDomain(), pins the heap at its
     * ceiling on a large library. The crypto stays in the table for the per-linkId decrypt lookup.
     *
     * thumbnailUrl is DELIBERATELY not selected. A decrypt-completion writes it via [updateThumbnailUrl];
     * Room invalidation is table-level, so that write re-runs this query either way. Dropping the column
     * makes each re-emission byte-identical, so the distinctUntilChanged in GetGalleryItemsUseCase drops
     * it before the merge/group rebuild; carrying it would make every thumbnail that lands during a
     * sustained scroll a distinct emission forcing a whole-library rebuild, the churn that tripped OOM on
     * a large account. The fresh URL reaches the cell through the in-memory [ThumbnailUrlStore] instead;
     * the column stays in the table for persistence and next-launch seeding.
     */
    @Query(
        "SELECT linkId, shareId, volumeId, captureTime, displayName, mimeType, sizeBytes, revisionId, " +
            "contentHash, tagsCsv, durationMs FROM photo_listing WHERE userId = :userId AND " +
            "(parentLinkId IS NULL OR parentLinkId NOT IN (SELECT DISTINCT albumLinkId FROM album_photo_membership)) " +
            "ORDER BY captureTime DESC",
    )
    fun observeOwnStreamLite(userId: String): Flow<List<PhotoListingLite>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PhotoListingEntity>)

    /** Inserts listing stubs WITHOUT clobbering a row that already exists. The full-listing walk
     *  upserts a minimal stub (linkId + capture time + content hash + tags) per streamed link so a
     *  detail batch that later fails can't strand the row out of dedup; a prior fully-built row is
     *  left intact (IGNORE), and the per-batch detail pass replaces a stub with the complete row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStubsIgnore(rows: List<PhotoListingEntity>)

    /** Stub rows that carry no detail blob yet (a stub a failed detail batch never completed). Keyed
     *  on an empty revisionId, which a fully-built row always resolves — so a RAW / odd-extension
     *  photo whose mimeType is legitimately empty is NOT mistaken for a stub and re-fetched forever.
     *  Used to backfill just the gap on a later pass instead of re-walking the whole library.
     *  linkId-only projection: both callers only need the ids, and a light single-window result
     *  avoids the multi-window CursorWindow refill that a full-row read can fail on when it races
     *  a concurrent delete write (the trash-during-sync crash). */
    @Query("SELECT linkId FROM photo_listing WHERE userId = :userId AND revisionId = ''")
    suspend fun getIncompleteRowLinkIds(userId: String): List<String>

    /** Incomplete stub rows as the light display projection (no crypto blobs), for the backfill pass
     *  that rebuilds each stub's wire fields. Same single-window safety as [getIncompleteRowLinkIds].
     *  thumbnailUrl is not selected, the backfill only reads the wire fields (capture time, content
     *  hash, tags) to rebuild the stub, and the projection dropped the column (see observeOwnStreamLite). */
    @Query(
        "SELECT linkId, shareId, volumeId, captureTime, displayName, mimeType, sizeBytes, revisionId, " +
            "contentHash, tagsCsv, durationMs FROM photo_listing WHERE userId = :userId AND revisionId = ''",
    )
    suspend fun getIncompleteRowsLite(userId: String): List<PhotoListingLite>

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

    /** linkId → already-decrypted thumbnailUrl for every own-stream row that carries one. Read once at
     *  startup to prime [ThumbnailUrlStore] so previously-decrypted cells paint immediately without a
     *  re-decrypt, now that the timeline projection no longer carries the column. Two-column light
     *  projection, so it never materialises the crypto blobs. */
    @Query("SELECT linkId, thumbnailUrl FROM photo_listing WHERE userId = :userId AND thumbnailUrl IS NOT NULL")
    suspend fun getThumbnailUrlSeed(userId: String): List<ThumbnailUrlSeed>

    /** Nulls every cached `file://` path after the on-disk thumbnails are deleted, re-enabling the
     *  lazy decrypt path (the scheduler skips rows whose thumbnailUrl is still non-null). */
    @Query("UPDATE photo_listing SET thumbnailUrl = NULL WHERE thumbnailUrl LIKE 'file://%'")
    suspend fun clearCachedThumbnailUrls()

    /** Per-link version of [clearCachedThumbnailUrls] for size-bounded cache eviction. Crypto
     *  material stays on the row, so a re-warm costs one decrypt, never a network round-trip. */
    @Query("UPDATE photo_listing SET thumbnailUrl = NULL WHERE linkId IN (:linkIds)")
    suspend fun clearThumbnailUrlsByLinkIds(linkIds: List<String>)

    /** Page of rows the GPS backfill has not yet processed, newest first — regardless of whether a
     *  cached encXAttr is present (rows synced before the encXAttr column carry none and have their
     *  XAttr fetched on demand). Paged so a large library's crypto-bearing rows never all sit in
     *  memory at once. */
    @Query("SELECT * FROM photo_listing WHERE userId = :userId AND gpsChecked = 0 AND revisionId != '' ORDER BY captureTime DESC LIMIT :limit")
    suspend fun getUngeocoded(userId: String, limit: Int): List<PhotoListingEntity>

    /** Marks rows as GPS-processed so the backfill never revisits them, whether or not a fix was found. */
    @Query("UPDATE photo_listing SET gpsChecked = 1 WHERE linkId IN (:linkIds)")
    suspend fun markGpsChecked(linkIds: List<String>)

    /** Page of VIDEO rows whose duration hasn't been recovered yet, newest first, which bounds the
     *  duration backfill so it never re-walks a photo whose duration is already known. Matches only
     *  video mime types (an image never carries a Media.Duration worth reading). Paged so a large
     *  library's crypto-bearing rows never all sit in memory at once. */
    @Query(
        "SELECT * FROM photo_listing WHERE userId = :userId AND durationMs IS NULL AND revisionId != '' " +
            "AND mimeType LIKE 'video/%' ORDER BY captureTime DESC LIMIT :limit",
    )
    suspend fun getVideosMissingDuration(userId: String, limit: Int): List<PhotoListingEntity>

    /** Writes JUST the recovered duration for one video, keyed by linkId, since a full-row upsert would
     *  race a concurrent metadata refresh and could clobber a freshly-decrypted field with a stale one. */
    @Query("UPDATE photo_listing SET durationMs = :durationMs WHERE linkId = :linkId")
    suspend fun updateDurationMs(linkId: String, durationMs: Long)
}
