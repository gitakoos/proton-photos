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
import androidx.room.Query
import eu.akoos.photos.data.db.entity.ImportAlbumMemberEntity

@Dao
interface ImportAlbumMemberDao {

    /** Appends one membership edge for a run, one row per (uploaded photo, album) pair. */
    @Insert
    suspend fun insert(row: ImportAlbumMemberEntity)

    /** The export albums this run carried, so the album phase knows which albums to recreate. */
    @Query("SELECT DISTINCT albumName FROM import_album_member WHERE runId = :runId")
    suspend fun albumsForRun(runId: String): List<String>

    /** Each album the run filed photos into, with its photo count, for the finished-run summary, so the
     *  Done screen names the albums the run built rather than only totalling them. Ordered by name. */
    @Query(
        "SELECT albumName AS name, COUNT(DISTINCT linkId) AS count FROM import_album_member " +
            "WHERE runId = :runId GROUP BY albumName ORDER BY albumName COLLATE NOCASE",
    )
    suspend fun albumCountsForRun(runId: String): List<ImportAlbumCount>

    /** The Drive links that belong to one album of a run, the member set that album is rebuilt from. */
    @Query("SELECT DISTINCT linkId FROM import_album_member WHERE runId = :runId AND albumName = :albumName")
    suspend fun linkIdsForAlbum(runId: String, albumName: String): List<String>

    /** Drops every membership edge of one run, for a caller that finished or abandoned it. */
    @Query("DELETE FROM import_album_member WHERE runId = :runId")
    suspend fun clearForRun(runId: String)
}
