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
import eu.akoos.photos.data.db.entity.ImportHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportHistoryDao {

    /** Records one completed import run. The id auto-generates, so each finished run is its own row. */
    @Insert
    suspend fun insert(row: ImportHistoryEntity)

    /** Live history list, newest run first. */
    @Query("SELECT * FROM import_history ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<ImportHistoryEntity>>

    /** The most recent runs, newest first, for a caller that only wants a short tail. */
    @Query("SELECT * FROM import_history ORDER BY importedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ImportHistoryEntity>

    /** The summary for a given run id, used to resolve an undo back to the run it belongs to. */
    @Query("SELECT * FROM import_history WHERE runId = :runId LIMIT 1")
    suspend fun byRun(runId: String): ImportHistoryEntity?

    /** Overwrites an existing run's counts, so a run first recorded as a partial (on a cancel) can be
     *  updated to its final tally when a resume completes, without leaving two rows for one archive. */
    @Query(
        "UPDATE import_history SET total = :total, uploaded = :uploaded, skipped = :skipped, " +
            "failed = :failed, importedAt = :importedAt WHERE runId = :runId",
    )
    suspend fun updateRun(runId: String, total: Int, uploaded: Int, skipped: Int, failed: Int, importedAt: Long)

    /** Forget one history row by its stable id, so a row that predates the run-id column can be removed
     *  too. The caller drops the run's ledger rows alongside this; nothing on Drive is touched, so this
     *  only removes the local record, never a photo. */
    @Query("DELETE FROM import_history WHERE id = :id")
    suspend fun deleteById(id: Long)
}
