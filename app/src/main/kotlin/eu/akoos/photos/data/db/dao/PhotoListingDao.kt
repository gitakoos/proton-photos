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
import eu.akoos.photos.data.db.entity.PhotoPickerRow
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
     * The user's own photo stream: every row except the ones that exist only inside an album.
     *
     * A photo contributed to a shared album sits in this same table under this same userId, and
     * `isChildOfAlbum` is the one fact that separates it from a backed-up photo. Reading that per-row
     * flag rather than testing the parent against the membership edges also means the answer holds
     * while an album's edges are absent, and keeps a correlated subquery out of the hottest query in
     * the app. A photo the user added to their own album stays parented to the photos root, so it is
     * not an album child and still belongs here.
     */
    @Query(
        "SELECT * FROM photo_listing WHERE userId = :userId AND isChildOfAlbum = 0 " +
            "ORDER BY captureTime DESC",
    )
    fun observeOwnStream(userId: String): Flow<List<PhotoListingEntity>>

    /**
     * Display-only projection of [observeOwnStream] for the timeline feed, on the same
     * `isChildOfAlbum = 0` rule, and selects just the columns
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
            "isChildOfAlbum = 0 ORDER BY captureTime DESC",
    )
    fun observeOwnStreamLite(userId: String): Flow<List<PhotoListingLite>>

    /**
     * The home-screen widget picker's grid: own-stream photos newest first, as the link id and the
     * already-decrypted thumbnail URL the cell binds.
     *
     * Same `isChildOfAlbum = 0` rule as [observeOwnStream], which is what keeps a photo from an album
     * someone shared with this user out of the pool a widget can cycle through. Its own named
     * projection rather than a widened [observeOwnStreamLite]: that one's exact column set is
     * load-bearing for the timeline feed's memory behaviour, and the picker needs a column it drops.
     * ORDER BY carries the newest-first order the picker shows, so a large library is never copied
     * into a second sorted list on every emission.
     */
    @Query(
        "SELECT linkId, thumbnailUrl FROM photo_listing WHERE userId = :userId AND " +
            "isChildOfAlbum = 0 ORDER BY captureTime DESC",
    )
    fun observeOwnStreamPickerRows(userId: String): Flow<List<PhotoPickerRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PhotoListingEntity>)

    /** Inserts listing stubs WITHOUT clobbering a row that already exists. The full-listing walk
     *  upserts a minimal stub (linkId + capture time + content hash + tags) per streamed link so a
     *  detail batch that later fails can't strand the row out of dedup; a prior fully-built row is
     *  left intact (IGNORE), and the per-batch detail pass replaces a stub with the complete row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStubsIgnore(rows: List<PhotoListingEntity>)

    /** Incomplete stub rows (a stub a failed detail batch never completed) as the light display
     *  projection, for the backfill pass that rebuilds each stub's wire fields. Keyed on an empty
     *  revisionId, which a fully-built row always resolves — so a RAW / odd-extension photo whose
     *  mimeType is legitimately empty is NOT mistaken for a stub and re-fetched forever. A light
     *  single-window result avoids the multi-window CursorWindow refill that a full-row read can
     *  fail on when it races a concurrent delete write (the trash-during-sync crash).
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

    /**
     * Scoped by user exactly as [deleteAll] is.
     *
     * linkId is this table's primary key, so a row belongs to whichever account wrote it last, and
     * an account switch leaves the previous account's rows in place until something clears them.
     * The scope is what makes a delete driven by one account's server response a no-op against a
     * row that currently belongs to another, rather than a silent eviction from that account's
     * cached library.
     *
     * Callers slice [linkIds] with [eu.akoos.photos.util.forEachSqlChunk]: one host variable per
     * element, against a per-statement cap this list can exceed on a large server-side deletion.
     */
    @Query("DELETE FROM photo_listing WHERE userId = :userId AND linkId IN (:linkIds)")
    suspend fun deleteByLinkIds(userId: String, linkIds: List<String>)

    @Query("SELECT * FROM photo_listing WHERE linkId = :linkId LIMIT 1")
    suspend fun getByLinkId(linkId: String): PhotoListingEntity?

    /** The server tag ids the library currently holds for one photo, as the stored CSV, or null when
     *  it holds no row for that link. Single-column projection, so asking one photo's tags never
     *  materialises the row's crypto blobs. Every tag write mirrors into this column, which makes it
     *  the local answer for a tag without a network round-trip. */
    @Query("SELECT tagsCsv FROM photo_listing WHERE linkId = :linkId LIMIT 1")
    suspend fun getTagsCsv(linkId: String): String?

    /** Returns all linkIds currently stored for a user. Used by the smart-merge refresh strategy. */
    @Query("SELECT linkId FROM photo_listing WHERE userId = :userId")
    suspend fun getAllLinkIds(userId: String): List<String>

    /**
     * The refresh sweep's candidate set: rows the volume's photo listing is entitled to speak for.
     *
     * Two constraints, one per way a row can be missing from that keep-set through no fault of its own.
     *
     * The volume constraint covers albums shared WITH this user. Such a photo is stored under this
     * user's own userId but keeps the OWNER's volumeId, and the stream walk that builds the keep-set
     * covers only this user's own volume, so a userId-only scope would offer up rows that have no way
     * to be kept and every clean full refresh would evict the shared album's offline cache.
     *
     * The album-child constraint covers the user's OWN volume. A photo someone contributed to an
     * album this user shared out is copied onto this volume parented to the album, and the volume
     * listing returns stream photos only, never album children. Left as a candidate it would be
     * swept on every full refresh, come back on the next album open, and vanish again.
     */
    @Query(
        "SELECT linkId FROM photo_listing WHERE userId = :userId AND volumeId = :volumeId " +
            "AND isChildOfAlbum = 0",
    )
    suspend fun getSweepCandidateLinkIds(userId: String, volumeId: String): List<String>

    /** Page of undecrypted-thumbnail rows with capture time before [beforeTime], newest first — fed to
     *  the scheduler's background warm-up so the most recently captured photos warm first and the cold
     *  tail decrypts on demand. Paged (not one big query) so a huge library's rows — each carrying
     *  crypto material — never all sit in memory at once. */
    @Query("SELECT * FROM photo_listing WHERE userId = :userId AND thumbnailUrl IS NULL AND captureTime < :beforeTime ORDER BY captureTime DESC LIMIT :limit")
    suspend fun getUndecryptedThumbnailsBefore(userId: String, beforeTime: Long, limit: Int): List<PhotoListingEntity>

    /** Returns entities for the given linkIds — used to preserve existing thumbnailUrls during refresh.
     *  Callers chunk [linkIds]: one host variable per element, against a per-statement cap. */
    @Query("SELECT * FROM photo_listing WHERE linkId IN (:linkIds)")
    suspend fun getByLinkIds(linkIds: List<String>): List<PhotoListingEntity>

    /**
     * Which of [contentHashes] this user's library already holds.
     *
     * Answers "do I already have this photo" for the save-a-shared-album path. A contentHash is
     * `HMAC-SHA256(rootNodeHashKey, content-sha)`, so it is scoped to the volume that produced it
     * and only matches across volumes because the copy paths carry the source's hash over instead
     * of recomputing one. That is precisely the case worth catching: a photo this user contributed
     * to a shared album keeps their own hash, so it is recognised when the album is saved back.
     *
     * Callers must chunk the input at 500 (SQLite's bind-variable limit is 999 on older Android).
     */
    @Query("SELECT contentHash FROM photo_listing WHERE userId = :userId AND contentHash IN (:contentHashes)")
    suspend fun findExistingContentHashes(userId: String, contentHashes: List<String>): List<String>

    /** The link of the one photo this user's library already holds for a content hash, or null when it
     *  holds none. The dedup path resolves the pre-existing link so a skipped import records it against
     *  the run, matching [findExistingContentHashes] on scope: same userId, same volume-scoped hash. */
    @Query("SELECT linkId FROM photo_listing WHERE userId = :userId AND contentHash = :contentHash LIMIT 1")
    suspend fun linkIdByContentHash(userId: String, contentHash: String): String?

    /** Writes [contentHash] onto a just-uploaded link when the row is present and carries none yet, so a
     *  later import recognises the same bytes and dedups them. The WHERE guard makes it a no-op once a
     *  hash is present, so it never overwrites the value a full library refresh resolves; a row not yet
     *  hydrated is left untouched for that refresh to fill. */
    @Query(
        "UPDATE photo_listing SET contentHash = :contentHash WHERE userId = :userId AND linkId = :linkId " +
            "AND (contentHash IS NULL OR contentHash = '')",
    )
    suspend fun seedContentHash(userId: String, linkId: String, contentHash: String)

    /** Live observation for a specific set of linkIds — used by album detail screen. Callers chunk
     *  [linkIds] and restate this ORDER BY when merging the slices back together. */
    @Query("SELECT * FROM photo_listing WHERE linkId IN (:linkIds) ORDER BY captureTime DESC")
    fun observeByLinkIds(linkIds: List<String>): Flow<List<PhotoListingEntity>>

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
     *  memory at once.
     *
     *  Volume-scoped for the same reason as [getSweepCandidateLinkIds]: a photo in an album another
     *  user shared is stored under this user's own userId but keeps the OWNER's volumeId, and its
     *  revision cannot be fetched from this user's volume. Such a row could never be marked as
     *  processed, so a userId-only scope would re-offer it on every pass for good. */
    @Query(
        "SELECT * FROM photo_listing WHERE userId = :userId AND volumeId = :volumeId AND gpsChecked = 0 " +
            "AND revisionId != '' ORDER BY captureTime DESC LIMIT :limit",
    )
    suspend fun getUngeocoded(userId: String, volumeId: String, limit: Int): List<PhotoListingEntity>

    /** Marks rows as GPS-processed so the backfill never revisits them, whether or not a fix was found. */
    @Query("UPDATE photo_listing SET gpsChecked = 1 WHERE linkId IN (:linkIds)")
    suspend fun markGpsChecked(linkIds: List<String>)

    /** Page of VIDEO rows whose duration hasn't been recovered yet, newest first, which bounds the
     *  duration backfill so it never re-walks a photo whose duration is already known. Matches only
     *  video mime types (an image never carries a Media.Duration worth reading). Paged so a large
     *  library's crypto-bearing rows never all sit in memory at once.
     *
     *  Volume-scoped exactly as [getUngeocoded] is: a video in an album another user shared keeps
     *  the OWNER's volumeId, so its revision never resolves here and its durationMs stays NULL,
     *  which is this query's own bound. Without the volume it would be re-offered on every pass. */
    @Query(
        "SELECT * FROM photo_listing WHERE userId = :userId AND volumeId = :volumeId AND durationMs IS NULL " +
            "AND revisionId != '' AND mimeType LIKE 'video/%' ORDER BY captureTime DESC LIMIT :limit",
    )
    suspend fun getVideosMissingDuration(userId: String, volumeId: String, limit: Int): List<PhotoListingEntity>

    /** Page of VIDEO rows inside ONE album whose duration hasn't been recovered yet, newest first, the
     *  per-album counterpart to [getVideosMissingDuration]. Matches only video mime types (an image
     *  never carries a Media.Duration worth reading). Paged so a large album's crypto-bearing rows never
     *  all sit in memory at once.
     *
     *  Selected by parent rather than by volume because these are the rows a volume can never name: a
     *  video in an album another user shared keeps the OWNER's volumeId, so no volume this user holds
     *  covers it. Such a row has its parentLinkId pinned to the album linkId when the album loads, which
     *  makes the parent the column that identifies one album's rows, and scoping to a single album is
     *  what keeps this walk separate from the volume-wide one. */
    @Query(
        "SELECT * FROM photo_listing WHERE userId = :userId AND parentLinkId = :albumLinkId " +
            "AND durationMs IS NULL AND revisionId != '' AND mimeType LIKE 'video/%' " +
            "ORDER BY captureTime DESC LIMIT :limit",
    )
    suspend fun getAlbumVideosMissingDuration(
        userId: String,
        albumLinkId: String,
        limit: Int,
    ): List<PhotoListingEntity>

    /** Writes JUST the recovered duration for one video, keyed by linkId, since a full-row upsert would
     *  race a concurrent metadata refresh and could clobber a freshly-decrypted field with a stale one. */
    @Query("UPDATE photo_listing SET durationMs = :durationMs WHERE linkId = :linkId")
    suspend fun updateDurationMs(linkId: String, durationMs: Long)
}
