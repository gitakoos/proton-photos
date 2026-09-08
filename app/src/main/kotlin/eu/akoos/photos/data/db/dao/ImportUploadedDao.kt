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
import eu.akoos.photos.data.db.entity.ImportUploadedEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportUploadedDao {

    /** Appends a batch of uploaded-photo rows for a run, one row per photo the run sent to Drive. */
    @Insert
    suspend fun insert(rows: List<ImportUploadedEntity>)

    /** Appends a single uploaded-photo row, for the streaming upload path that records each success. */
    @Insert
    suspend fun insert(row: ImportUploadedEntity)

    /** Live ledger for one run, oldest first, so a detail screen can show what the run brought in. */
    @Query("SELECT * FROM import_uploaded WHERE runId = :runId ORDER BY id ASC")
    fun observeByRun(runId: String): Flow<List<ImportUploadedEntity>>

    /** The run's rows not yet undone, the candidate set an undo verifies against Drive before trashing. */
    @Query("SELECT * FROM import_uploaded WHERE runId = :runId AND undone = 0")
    suspend fun pendingByRun(runId: String): List<ImportUploadedEntity>

    /** The run's rows not yet undone that the run actually uploaded, excluding deduped rows that only
     *  record a pre-existing Drive link. An undo trashes only real uploads, so a photo already in Drive
     *  before the run is never moved to the trash by undoing that run. */
    @Query("SELECT * FROM import_uploaded WHERE runId = :runId AND undone = 0 AND alreadyInDrive = 0")
    suspend fun pendingRealUploadsByRun(runId: String): List<ImportUploadedEntity>

    /** How many of the run's ledger rows are real uploads, not a recorded pre-existing match. The run's
     *  imported tally read straight from durable state, so a resumed completion counts every upload the
     *  run ever sent rather than only the ones this session handled. */
    @Query("SELECT COUNT(*) FROM import_uploaded WHERE runId = :runId AND alreadyInDrive = 0")
    suspend fun realUploadCount(runId: String): Int

    /** How many of the run's ledger rows record a photo Drive already held, its already-in-Drive tally,
     *  read back for the same resumed-completion accounting as [realUploadCount]. */
    @Query("SELECT COUNT(*) FROM import_uploaded WHERE runId = :runId AND alreadyInDrive = 1")
    suspend fun alreadyInDriveCount(runId: String): Int

    /** Every Drive link the run uploaded, for a caller that only needs the identifiers. */
    @Query("SELECT linkId FROM import_uploaded WHERE runId = :runId")
    suspend fun linkIdsByRun(runId: String): List<String>

    /**
     * Marks the given links of a run undone once their trash move succeeds, so a repeated undo skips them.
     * A caller may chunk [linkIds] at the host-variable limit for a very large selection; a normal run
     * stays well under it.
     */
    @Query("UPDATE import_uploaded SET undone = 1 WHERE runId = :runId AND linkId IN (:linkIds)")
    suspend fun markUndone(runId: String, linkIds: List<String>)

    /** How many of the run's uploads are still on Drive, so the UI can offer or hide the undo action. */
    @Query("SELECT COUNT(*) FROM import_uploaded WHERE runId = :runId AND undone = 0")
    suspend fun pendingCount(runId: String): Int

    /** Drop the run's whole upload ledger when its history entry is deleted. Local only: it never trashes
     *  or touches the photos on Drive, it just forgets that this run recorded them. */
    @Query("DELETE FROM import_uploaded WHERE runId = :runId")
    suspend fun deleteRun(runId: String)
}
