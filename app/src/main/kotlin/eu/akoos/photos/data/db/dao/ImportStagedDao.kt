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
import eu.akoos.photos.data.db.entity.ImportStagedEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportStagedDao {

    /** Writes the staged entries for one run. REPLACE on conflict so re-staging the same entry
     *  overwrites its earlier row rather than failing on the composite primary key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<ImportStagedEntity>)

    /** Writes a single staged entry, same REPLACE-on-conflict rule as the batch overload. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ImportStagedEntity)

    /** Live review list for one run, newest capture first with the entry name as a stable tiebreak. */
    @Query("SELECT * FROM import_staged WHERE zipId = :zipId ORDER BY dateMs DESC, entryName ASC")
    fun observeForZip(zipId: String): Flow<List<ImportStagedEntity>>

    /** The entries the upload pass should send: kept by the user and not yet uploaded. */
    @Query(
        "SELECT * FROM import_staged WHERE zipId = :zipId AND excluded = 0 AND uploaded = 0 " +
            "ORDER BY entryName ASC"
    )
    suspend fun pendingUpload(zipId: String): List<ImportStagedEntity>

    /** Flips the excluded flag for a selection of entries in one run. */
    @Query(
        "UPDATE import_staged SET excluded = :excluded WHERE zipId = :zipId AND entryName IN (:entryNames)"
    )
    suspend fun setExcluded(zipId: String, entryNames: List<String>, excluded: Boolean)

    /** Marks one entry uploaded once it has been sent, so a resumed upload skips it. */
    @Query("UPDATE import_staged SET uploaded = 1 WHERE zipId = :zipId AND entryName = :entryName")
    suspend fun markUploaded(zipId: String, entryName: String)

    /** Records whether one entry's content already lives in Drive, so the review can badge it a skip. */
    @Query("UPDATE import_staged SET alreadyInDrive = :flag WHERE zipId = :zipId AND entryName = :entryName")
    suspend fun setAlreadyInDrive(zipId: String, entryName: String, flag: Boolean)

    /** Live count of entries still queued to upload for one run, for the confirm button's total. */
    @Query("SELECT COUNT(*) FROM import_staged WHERE zipId = :zipId AND excluded = 0 AND uploaded = 0")
    fun observeIncludedCount(zipId: String): Flow<Int>

    /** Live count of the genuinely new entries a confirm would upload: kept and not already in Drive.
     *  The review shows this as its headline count so a run whose archive overlaps Drive reports how many
     *  photos are actually new rather than the whole kept set. */
    @Query("SELECT COUNT(*) FROM import_staged WHERE zipId = :zipId AND excluded = 0 AND alreadyInDrive = 0")
    fun observeNewCount(zipId: String): Flow<Int>

    /** Live count of the kept entries Drive already holds, so the review can report how many the run will
     *  skip as duplicates before it uploads anything. */
    @Query("SELECT COUNT(*) FROM import_staged WHERE zipId = :zipId AND excluded = 0 AND alreadyInDrive = 1")
    fun observeAlreadyCount(zipId: String): Flow<Int>

    /** Whether this run's archive carried any album folder, so the review offers the album-mode toggle
     *  only when there is an album to reconstruct. */
    @Query("SELECT EXISTS(SELECT 1 FROM import_staged WHERE zipId = :zipId AND albumName IS NOT NULL)")
    fun observeHasAlbums(zipId: String): Flow<Boolean>

    /** The export's albums for the review preview: each album folder the kept rows carry, with how many
     *  kept photos fall in it, so the review can name the albums a confirm would build rather than only
     *  count them. Excluded rows drop out so the preview tracks the review's current selection. */
    @Query(
        "SELECT albumName AS name, COUNT(*) AS count FROM import_staged " +
            "WHERE zipId = :zipId AND albumName IS NOT NULL AND excluded = 0 " +
            "GROUP BY albumName ORDER BY albumName COLLATE NOCASE",
    )
    fun observeAlbumSummary(zipId: String): Flow<List<ImportAlbumCount>>

    /** How many entries this run staged in all, review choices included. */
    @Query("SELECT COUNT(*) FROM import_staged WHERE zipId = :zipId")
    suspend fun countForZip(zipId: String): Int

    /** How many entries this run kept, review exclusions removed, uploaded or not. The denominator a
     *  finished run's summary reports against, so a run killed and resumed still sizes its whole self
     *  rather than only the slice left pending when it resumed. */
    @Query("SELECT COUNT(*) FROM import_staged WHERE zipId = :zipId AND excluded = 0")
    suspend fun includedTotal(zipId: String): Int

    /** The archives that still have an entry left to upload, so a pending review can be resumed. An
     *  excluded row is not pending: it never uploads, so an excluded-only leftover from a finished run
     *  must not re-adopt the archive on the next open. */
    @Query("SELECT DISTINCT zipId FROM import_staged WHERE uploaded = 0 AND excluded = 0")
    suspend fun zipsWithPending(): List<String>

    /** Frees a finished run's settled rows: everything uploaded or excluded. A still-failed row keeps
     *  uploaded = 0 and excluded = 0, so it survives here and stays available for a resume. Each entry's
     *  cached thumbnail is deleted as it finishes, so only the rows are dropped here. */
    @Query("DELETE FROM import_staged WHERE zipId = :zipId AND (uploaded = 1 OR excluded = 1)")
    suspend fun clearResolved(zipId: String)

    /** Drops every staged row for one run, for a caller that finished or abandoned it. */
    @Query("DELETE FROM import_staged WHERE zipId = :zipId")
    suspend fun clearForZip(zipId: String)

    /** The cached thumbnail paths for one run, so a caller can delete the files before clearing. */
    @Query("SELECT thumbPath FROM import_staged WHERE zipId = :zipId")
    suspend fun thumbPaths(zipId: String): List<String?>

    /** The cached thumbnail paths of rows staged before a cutoff, so a caller deletes the files before
     *  pruning the abandoned rows they belong to. */
    @Query("SELECT thumbPath FROM import_staged WHERE stagedAt < :cutoffMs")
    suspend fun thumbPathsOlderThan(cutoffMs: Long): List<String?>

    /** Drops staged rows left behind before a cutoff, for a run a user picked but never finished. */
    @Query("DELETE FROM import_staged WHERE stagedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
