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
import eu.akoos.photos.data.db.entity.LocalTagEntity

@Dao
interface LocalTagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: LocalTagEntity): Long

    @Query(
        """
        UPDATE local_tag SET
            dateModified = :dateModified,
            sizeBytes = :sizeBytes,
            tagsCsv = :tagsCsv,
            scannedAt = :scannedAt
        WHERE uri = :uri
        """
    )
    suspend fun updateDetectionColumns(
        uri: String,
        dateModified: Long,
        sizeBytes: Long,
        tagsCsv: String,
        scannedAt: Long,
    )

    /**
     * Store one detection result, touching no column the scanner does not own. In particular
     * [LocalTagEntity.userTagsCsv] on an existing row is left exactly as it is: a file is re-detected
     * whenever its size or modified time drifts, so a whole-row write would silently drop the
     * categories a user picked for it, and nothing else holds them.
     *
     * Insert-then-update rather than an ON CONFLICT ... DO UPDATE upsert on purpose: the two steps
     * run inside one [Transaction] (so they are atomic), and the plain INSERT/UPDATE statements work
     * on every SQLite version the app and its tests run on. The IGNORE insert no-ops when the row
     * exists; the detection UPDATE no-ops for the just-inserted new row and only bites on the
     * existing one, whose user column it does not name.
     */
    @Transaction
    suspend fun upsertDetection(entity: LocalTagEntity) {
        val inserted = insertIgnore(entity)
        if (inserted == -1L) {
            updateDetectionColumns(
                uri = entity.uri,
                dateModified = entity.dateModified,
                sizeBytes = entity.sizeBytes,
                tagsCsv = entity.tagsCsv,
                scannedAt = entity.scannedAt,
            )
        }
    }

    /** The raw CSV of categories the user picked for one file, or null when the table has no row
     *  for that uri at all. */
    @Query("SELECT userTagsCsv FROM local_tag WHERE uri = :uri")
    suspend fun getUserTagsCsv(uri: String): String?

    @Query("UPDATE local_tag SET userTagsCsv = :userTagsCsv WHERE uri = :uri")
    suspend fun updateUserTagsColumn(uri: String, userTagsCsv: String)

    /**
     * Record the user's category choice for one file, touching no column the scanner owns.
     *
     * The insert covers a file the scanner has not reached yet: it lands a row carrying the uri and
     * the choice, with the detection columns at their unscanned zeros. Those zeros match no live
     * MediaStore row, so the scanner still sees the file as needing detection and fills them in on
     * its next pass. When a row already exists the insert is ignored and the UPDATE writes the one
     * column, so a detection sitting in the same row survives untouched. Atomic, like its detection
     * counterpart, and for the same reason.
     */
    @Transaction
    suspend fun setUserTagsCsv(uri: String, userTagsCsv: String) {
        val inserted = insertIgnore(
            LocalTagEntity(
                uri = uri,
                dateModified = 0L,
                sizeBytes = 0L,
                tagsCsv = "",
                scannedAt = 0L,
                userTagsCsv = userTagsCsv,
            )
        )
        if (inserted == -1L) updateUserTagsColumn(uri, userTagsCsv)
    }

    /** Every cached row. The repository turns this into a `uri → entity` map for an O(1)
     *  freshness lookup during a MediaStore scan. */
    @Query("SELECT * FROM local_tag")
    suspend fun getAll(): List<LocalTagEntity>

    /** Drop cache rows whose URIs no longer appear in MediaStore (file deleted). Keeps the
     *  table from growing without bound as the on-device library churns. */
    @Query("DELETE FROM local_tag WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("DELETE FROM local_tag WHERE userTagsCsv = ''")
    suspend fun deleteRowsWithoutUserTags()

    @Query("UPDATE local_tag SET dateModified = 0, sizeBytes = 0, tagsCsv = '', scannedAt = 0")
    suspend fun resetDetectionColumns()

    /**
     * Discard every detection, so the scan that follows re-detects each file. Used when the
     * detection-logic version changes and each cached verdict is therefore suspect.
     *
     * A row holding nothing but a detection goes entirely. A row that also carries a user choice
     * keeps the choice and has its freshness key zeroed instead, which is exactly what makes the
     * scanner treat the file as unscanned: deleting it would take a choice with it that no re-scan
     * could ever put back. The delete runs first, so the reset only ever meets the rows that stay.
     */
    @Transaction
    suspend fun clearDetections() {
        deleteRowsWithoutUserTags()
        resetDetectionColumns()
    }
}
