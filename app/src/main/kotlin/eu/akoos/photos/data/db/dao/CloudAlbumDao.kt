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
import kotlinx.coroutines.flow.Flow
import eu.akoos.photos.data.db.entity.CloudAlbumEntity

@Dao
interface CloudAlbumDao {

    /**
     * One-shot read of every cached album, newest first by last-activity time.
     * NULL `lastActivityTimeMs` rows sort to the end (SQLite default with DESC).
     */
    @Query("SELECT * FROM cloud_albums ORDER BY lastActivityTimeMs DESC")
    suspend fun getAll(): List<CloudAlbumEntity>

    /** Live stream of the cached album list — drives the AlbumsScreen grid. */
    @Query("SELECT * FROM cloud_albums ORDER BY lastActivityTimeMs DESC")
    fun observeAll(): Flow<List<CloudAlbumEntity>>

    /** Upsert the network refresh result. REPLACE keeps `lastFetchedMs` honest on every write. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CloudAlbumEntity>)

    /**
     * Drop cached rows whose linkId is not in [keepLinkIds] — used after a successful
     * full refresh so locally-deleted-on-server albums disappear from the grid.
     */
    @Query("DELETE FROM cloud_albums WHERE linkId NOT IN (:keepLinkIds)")
    suspend fun deleteWhereNotIn(keepLinkIds: List<String>)

    /** Targeted single-album removal — used when the user leaves a shared-with-me
     *  album, so the row disappears from the grid without waiting for a full
     *  network refresh. The shared listing endpoint won't return the album after
     *  the leave call, so `deleteWhereNotIn` would also clear it, but explicit
     *  removal gives the UI an immediate confirmation. */
    @Query("DELETE FROM cloud_albums WHERE linkId = :linkId")
    suspend fun deleteByLinkId(linkId: String)

    /** Wipe everything — called on sign-out. */
    @Query("DELETE FROM cloud_albums")
    suspend fun clearAll()
}
