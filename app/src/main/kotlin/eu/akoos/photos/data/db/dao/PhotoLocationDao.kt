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
import androidx.room.Query
import androidx.room.Upsert
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoLocationDao {

    /** Insert or replace a batch of GPS fixes in one transaction. */
    @Upsert
    suspend fun upsert(items: List<PhotoLocationEntity>)

    /** Live stream of every located photo for the account — the map view's source. A fix at exactly
     *  0, 0 is a photo with no GPS, left out here as well as dropped by [deleteNoFixRows], so one stored
     *  before that was recognised never shows while it waits for the next walk to clear it. */
    @Query("SELECT * FROM photo_location WHERE userId = :userId AND NOT (latitude = 0 AND longitude = 0)")
    fun observeForUser(userId: String): Flow<List<PhotoLocationEntity>>

    /** Ids already located, so a backfill can skip them without re-reading EXIF. */
    @Query("SELECT id FROM photo_location WHERE userId = :userId")
    suspend fun idsForUser(userId: String): List<String>

    /** The stored GPS fix for one photo (by its cloud linkId), or null if not located yet. Used by
     *  the details sheet to show a cloud-only photo's place without re-decrypting its XAttr. A 0, 0 fix
     *  reads as none, as in [observeForUser]. */
    @Query(
        "SELECT * FROM photo_location WHERE userId = :userId AND id = :id " +
            "AND NOT (latitude = 0 AND longitude = 0) LIMIT 1",
    )
    suspend fun getById(userId: String, id: String): PhotoLocationEntity?

    /**
     * Drops the stored fixes of [ids], the invalidation a place edit needs: the row is what
     * [observeForUser] plots and what [idsForUser] makes the backfill skip, so a photo whose file now
     * carries different coordinates has to lose its row before anything re-reads it. Scoped by user
     * exactly as the reads are, so one account's edit never clears another's row.
     *
     * Callers slice [ids] with [eu.akoos.photos.util.forEachSqlChunk]: one host variable per element,
     * against a per-statement cap a whole multi-select can exceed.
     */
    @Query("DELETE FROM photo_location WHERE userId = :userId AND id IN (:ids)")
    suspend fun deleteByIds(userId: String, ids: List<String>)

    /** Drops every stored fix at exactly 0, 0, the position written for a photo with no GPS, so no map,
     *  place or search shows one. Cheap and idempotent: the backfills run it before each walk. */
    @Query("DELETE FROM photo_location WHERE latitude = 0 AND longitude = 0")
    suspend fun deleteNoFixRows(): Int

    /**
     * Move a photo's location row onto a new [newId], keeping its coordinates and its owner. A revealed
     * photo comes back under a fresh MediaStore uri, and the row is keyed by uri, so without this it
     * would go on naming the uri the hide removed and the photo would lose its map pin. `id` is the
     * primary key and a uri is globally unique, so this touches at most one row whoever owns it.
     */
    @Query("UPDATE photo_location SET id = :newId WHERE id = :oldId")
    suspend fun rekey(oldId: String, newId: String)
}
