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
import kotlinx.coroutines.flow.Flow
import eu.akoos.photos.data.db.entity.CloudAlbumEntity

@Dao
interface CloudAlbumDao {

    /**
     * The user's own cached albums, newest first; NULL lastActivityTimeMs sorts to the end.
     *
     * Ownership-scoped on purpose. The table also holds shared-with-me albums so the add-to-album
     * picker can offer them, but the album grid and the widget picker are about the user's own
     * albums, and a shared one appearing there would be a surprise in the first case and
     * unresolvable in the second.
     */
    @Query("SELECT * FROM cloud_albums WHERE sharedByEmail IS NULL ORDER BY lastActivityTimeMs DESC")
    suspend fun getOwned(): List<CloudAlbumEntity>

    /** Live stream of the user's own cached albums, driving the AlbumsScreen grid. */
    @Query("SELECT * FROM cloud_albums WHERE sharedByEmail IS NULL ORDER BY lastActivityTimeMs DESC")
    fun observeOwned(): Flow<List<CloudAlbumEntity>>

    /**
     * Every cached album's linkId, owned and shared-with-me alike.
     *
     * Read once per sync pass to answer "is this photo's parent an album", which is what decides
     * [eu.akoos.photos.data.db.entity.PhotoListingEntity.isChildOfAlbum]. Both kinds belong: a photo
     * parented to an album is outside the user's own stream whoever owns the album.
     */
    @Query("SELECT linkId FROM cloud_albums")
    suspend fun getAllLinkIds(): List<String>

    /** Cached albums someone else shared with this user, for the add-to-album picker. */
    @Query("SELECT * FROM cloud_albums WHERE sharedByEmail IS NOT NULL ORDER BY lastActivityTimeMs DESC")
    suspend fun getSharedWithMe(): List<CloudAlbumEntity>

    /** Upsert the network refresh result. REPLACE keeps `lastFetchedMs` honest on every write. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CloudAlbumEntity>)

    /**
     * Drop cached rows whose linkId is not in [keepLinkIds] — used after a successful
     * full refresh so locally-deleted-on-server albums disappear from the grid.
     *
     * Scoped to owned albums. The refresh that calls this lists only the user's own volume, so a
     * shared-with-me row is never in [keepLinkIds] and an unscoped delete would evict every one of
     * them on each refresh. They are pruned by their own refresh, and immediately by
     * [deleteByLinkId] when the user leaves one.
     */
    @Query("DELETE FROM cloud_albums WHERE linkId NOT IN (:keepLinkIds) AND sharedByEmail IS NULL")
    suspend fun deleteWhereNotIn(keepLinkIds: List<String>)

    /**
     * The mirror of [deleteWhereNotIn] for the shared-with-me refresh: drops shared rows the
     * sharer has since revoked or deleted, and never touches an owned album.
     */
    @Query("DELETE FROM cloud_albums WHERE linkId NOT IN (:keepLinkIds) AND sharedByEmail IS NOT NULL")
    suspend fun deleteSharedWhereNotIn(keepLinkIds: List<String>)

    /** Targeted removal so a left shared-with-me album disappears from the grid immediately,
     *  without waiting for the full refresh that would also drop it. */
    @Query("DELETE FROM cloud_albums WHERE linkId = :linkId")
    suspend fun deleteByLinkId(linkId: String)

    /**
     * Whether the cached row for [linkId] is an album someone else shared with this user.
     *
     * `sharedByEmail` (the sharer's address) is written only by the shared-with-me listing, so it
     * is the one durable record of who owns an album for a caller holding nothing but a linkId.
     * An absent row answers false, which is the safe default for the delete paths that ask: they
     * use the answer to decide whether an album's photo rows may go with it, and a guest's
     * leftover row merely clutters, while an owned album's photo row is the user's own photo.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM cloud_albums WHERE linkId = :linkId AND sharedByEmail IS NOT NULL)")
    suspend fun isSharedWithMe(linkId: String): Boolean

    /**
     * The cached row for [linkId], owned or shared-with-me alike, or null when the album was never
     * listed on this device. Lets a caller holding nothing but an album linkId recover the sharing
     * share id and the sharer's address, which is what decides whether that album's photos are read
     * through a share or through the user's own volume.
     */
    @Query("SELECT * FROM cloud_albums WHERE linkId = :linkId")
    suspend fun getByLinkId(linkId: String): CloudAlbumEntity?

    /** Wipe everything — called on sign-out. */
    @Query("DELETE FROM cloud_albums")
    suspend fun clearAll()
}
