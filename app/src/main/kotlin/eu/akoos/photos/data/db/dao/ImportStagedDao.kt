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
import androidx.room.Transaction
import eu.akoos.photos.data.db.entity.ImportStagedEntity
import eu.akoos.photos.util.forEachSqlChunk
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

    /** Live review list for one run, newest capture first with the entry name as a stable tiebreak. A Live
     *  Photo shows as its still; a video whose still row is gone shows on its own. */
    @Query(
        "SELECT * FROM import_staged r WHERE r.zipId = :zipId AND (r.liveOf IS NULL OR NOT EXISTS " +
            "(SELECT 1 FROM import_staged s WHERE s.zipId = r.zipId AND s.entryName = r.liveOf)) " +
            "ORDER BY r.dateMs DESC, r.entryName ASC"
    )
    fun observeForZip(zipId: String): Flow<List<ImportStagedEntity>>

    /** The entries the upload pass should send: kept by the user and not yet uploaded. */
    @Query(
        "SELECT * FROM import_staged WHERE zipId = :zipId AND excluded = 0 AND uploaded = 0 " +
            "ORDER BY entryName ASC"
    )
    suspend fun pendingUpload(zipId: String): List<ImportStagedEntity>

    /** Flips the excluded flag for a selection of entries in one run, a Live Photo's video with its still.
     *  Chunked for the SQL host-variable limit. */
    @Transaction
    suspend fun setExcluded(zipId: String, entryNames: List<String>, excluded: Boolean) {
        entryNames.forEachSqlChunk {
            setExcludedByName(zipId, it, excluded)
            setExcludedByStill(zipId, it, excluded)
        }
    }

    @Query("UPDATE import_staged SET excluded = :excluded WHERE zipId = :zipId AND entryName IN (:entryNames)")
    suspend fun setExcludedByName(zipId: String, entryNames: List<String>, excluded: Boolean)

    @Query("UPDATE import_staged SET excluded = :excluded WHERE zipId = :zipId AND liveOf IN (:stillNames)")
    suspend fun setExcludedByStill(zipId: String, stillNames: List<String>, excluded: Boolean)

    /** Marks one entry uploaded once it has been sent, so a resumed upload skips it. */
    @Query("UPDATE import_staged SET uploaded = 1 WHERE zipId = :zipId AND entryName = :entryName")
    suspend fun markUploaded(zipId: String, entryName: String)

    /** Marks one entry uploaded and keeps the Drive link it landed under, so a video of a Live Photo can
     *  be attached to it even after the run was killed and resumed. */
    @Query("UPDATE import_staged SET uploaded = 1, linkId = :linkId WHERE zipId = :zipId AND entryName = :entryName")
    suspend fun markUploadedWithLink(zipId: String, entryName: String, linkId: String?)

    /** Every row of one run, the Live Photo videos included, in no particular order. */
    @Query("SELECT * FROM import_staged WHERE zipId = :zipId")
    suspend fun allForZip(zipId: String): List<ImportStagedEntity>

    /** Marks one entry as the video of the Live Photo whose still is [stillName]. */
    @Query("UPDATE import_staged SET liveOf = :stillName WHERE zipId = :zipId AND entryName = :videoName")
    suspend fun setLiveOf(zipId: String, videoName: String, stillName: String)

    /** Stores the capture date one entry is uploaded with. */
    @Query("UPDATE import_staged SET dateMs = :dateMs WHERE zipId = :zipId AND entryName = :entryName")
    suspend fun setDateMs(zipId: String, entryName: String, dateMs: Long)

    /** Records whether one entry's content already lives in Drive, so the review can badge it a skip. */
    @Query("UPDATE import_staged SET alreadyInDrive = :flag WHERE zipId = :zipId AND entryName = :entryName")
    suspend fun setAlreadyInDrive(zipId: String, entryName: String, flag: Boolean)

    /** Live count of entries still queued to upload for one run, for the confirm button's total. A Live
     *  Photo counts once: its video only once the still is no longer pending. */
    @Query(
        "SELECT COUNT(*) FROM import_staged r WHERE r.zipId = :zipId AND r.excluded = 0 AND r.uploaded = 0 " +
            "AND (r.liveOf IS NULL OR NOT EXISTS (SELECT 1 FROM import_staged s WHERE s.zipId = r.zipId " +
            "AND s.entryName = r.liveOf AND s.uploaded = 0))"
    )
    fun observeIncludedCount(zipId: String): Flow<Int>

    /** Live count of the genuinely new entries a confirm would upload: kept and not already in Drive.
     *  The review shows this as its headline count so a run whose archive overlaps Drive reports how many
     *  photos are actually new rather than the whole kept set. */
    @Query("SELECT COUNT(*) FROM import_staged WHERE zipId = :zipId AND excluded = 0 AND alreadyInDrive = 0 AND liveOf IS NULL")
    fun observeNewCount(zipId: String): Flow<Int>

    /** Live count of the kept entries Drive already holds, so the review can report how many the run will
     *  skip as duplicates before it uploads anything. */
    @Query("SELECT COUNT(*) FROM import_staged WHERE zipId = :zipId AND excluded = 0 AND alreadyInDrive = 1 AND liveOf IS NULL")
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
            "WHERE zipId = :zipId AND albumName IS NOT NULL AND excluded = 0 AND liveOf IS NULL " +
            "GROUP BY albumName ORDER BY albumName COLLATE NOCASE",
    )
    fun observeAlbumSummary(zipId: String): Flow<List<ImportAlbumCount>>

    /** The entry names this run has staged so far, so a resumed staging pass steps over them. */
    @Query("SELECT entryName FROM import_staged WHERE zipId = :zipId")
    suspend fun entryNames(zipId: String): List<String>

    /** How many entries this run staged in all, review choices included. */
    @Query("SELECT COUNT(*) FROM import_staged WHERE zipId = :zipId")
    suspend fun countForZip(zipId: String): Int

    /** The archives that still have an entry left to upload, so a pending review can be resumed. An
     *  excluded row is not pending: it never uploads, so an excluded-only leftover from a finished run
     *  must not re-adopt the archive on the next open. */
    @Query("SELECT DISTINCT zipId FROM import_staged WHERE uploaded = 0 AND excluded = 0")
    suspend fun zipsWithPending(): List<String>

    /** Frees a finished run's settled rows: everything uploaded or excluded. A still-failed row keeps
     *  uploaded = 0 and excluded = 0, so it survives here and stays available for a resume, and so does the
     *  uploaded still of a Live Photo whose video is still pending, whose link the video joins. Each entry's
     *  cached thumbnail is deleted as it finishes, so only the rows are dropped here. */
    @Query(
        "DELETE FROM import_staged WHERE zipId = :zipId AND (excluded = 1 OR (uploaded = 1 AND entryName NOT IN " +
            "(SELECT liveOf FROM import_staged WHERE zipId = :zipId AND liveOf IS NOT NULL AND uploaded = 0 " +
            "AND excluded = 0)))"
    )
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

    /** Remove every staged entry, for the sign-out that ends the account they were staged for. */
    @Query("DELETE FROM import_staged")
    suspend fun deleteAll()
}
