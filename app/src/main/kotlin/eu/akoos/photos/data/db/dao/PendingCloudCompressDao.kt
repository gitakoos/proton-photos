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
import eu.akoos.photos.data.db.entity.PendingCloudCompressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingCloudCompressDao {

    /** Queue rows; a photo already queued keeps its row, its place and any copy already uploaded. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<PendingCloudCompressEntity>)

    /** The next [limit] rows in queue order that the run [runId] has not tried yet. Read a slice at a time,
     *  so a queue of tens of thousands never sits in memory at once. */
    @Query(
        "SELECT * FROM pending_cloud_compress WHERE attemptRun IS NULL OR attemptRun != :runId " +
            "ORDER BY enqueuedAt ASC, linkId ASC LIMIT :limit"
    )
    suspend fun nextSlice(runId: String, limit: Int): List<PendingCloudCompressEntity>

    /** Marks a row kept for later, so the run [runId] does not serve it again; [attempts] is its failed
     *  tries so far. */
    @Query("UPDATE pending_cloud_compress SET attemptRun = :runId, attempts = :attempts WHERE linkId = :linkId")
    suspend fun markTried(linkId: String, runId: String, attempts: Int)

    /** Moves photos asked for again to the back of the queue as of [enqueuedAt], so a Cancel that came
     *  before this request no longer drops them. A row with a copy on Drive keeps its place. */
    @Query(
        "UPDATE pending_cloud_compress SET enqueuedAt = :enqueuedAt " +
            "WHERE newLinkId IS NULL AND enqueuedAt < :enqueuedAt AND linkId IN (:linkIds)"
    )
    suspend fun requeue(linkIds: List<String>, enqueuedAt: Long)

    /** Records that the original's labels and faces now sit on the copy. */
    @Query("UPDATE pending_cloud_compress SET carriedOver = 1 WHERE linkId = :linkId")
    suspend fun setCarriedOver(linkId: String)

    /** Records the compressed copy's link the moment its upload returns. */
    @Query("UPDATE pending_cloud_compress SET newLinkId = :newLinkId WHERE linkId = :linkId")
    suspend fun setNewLinkId(linkId: String, newLinkId: String)

    @Query("DELETE FROM pending_cloud_compress WHERE linkId = :linkId")
    suspend fun delete(linkId: String)

    /** The cancel: drops every row queued by [enqueuedUpTo] whose copy is not on Drive yet, apart from the
     *  photos being worked on right now ([running]). Those and a row with a copy stay, so the drain finishes
     *  them rather than leaving an original and its copy both on Drive. */
    @Query(
        "DELETE FROM pending_cloud_compress WHERE newLinkId IS NULL AND enqueuedAt <= :enqueuedUpTo " +
            "AND linkId NOT IN (:running)"
    )
    suspend fun clearNotStarted(enqueuedUpTo: Long, running: List<String>)

    @Query("SELECT COUNT(*) FROM pending_cloud_compress")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM pending_cloud_compress")
    fun observeCount(): Flow<Int>
}
