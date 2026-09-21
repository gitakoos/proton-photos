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
import eu.akoos.photos.data.db.entity.PendingMetadataEditEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingMetadataEditDao {

    /** Persist pending edits, REPLACE on conflict so re-enqueuing the same photo overwrites its older
     *  pending edit (the linkId primary key already covers the pair). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PendingMetadataEditEntity>)

    /** Every pending edit in enqueue order, the drain's FIFO source of truth. */
    @Query("SELECT * FROM pending_metadata_edit ORDER BY enqueuedAt ASC")
    suspend fun getAll(): List<PendingMetadataEditEntity>

    /** Record the corrected copy's linkId onto a queued row the moment its upload returns, so a
     *  process kill before the album re-add and trash resumes from this link rather than re-uploading. */
    @Query("UPDATE pending_metadata_edit SET newLinkId = :newLinkId WHERE linkId = :linkId")
    suspend fun setNewLinkId(linkId: String, newLinkId: String)

    /** Drop one photo's pending edit once the worker has applied (or abandoned) it. */
    @Query("DELETE FROM pending_metadata_edit WHERE linkId = :linkId")
    suspend fun deleteByLinkId(linkId: String)

    /** Empty the whole queue, for the cancel path: a user cancel abandons every pending edit so a later
     *  enqueue never resumes them. */
    @Query("DELETE FROM pending_metadata_edit")
    suspend fun clear()

    /** Live count of pending edits, so a progress surface can show the outstanding backlog. */
    @Query("SELECT COUNT(*) FROM pending_metadata_edit")
    fun observeCount(): Flow<Int>

    /** The device files still referenced by a pending synced edit, so the backup upload selector can
     *  avoid racing the worker on the same device file. */
    @Query("SELECT deviceUri FROM pending_metadata_edit WHERE deviceUri IS NOT NULL")
    suspend fun allPendingDeviceUris(): List<String>
}
