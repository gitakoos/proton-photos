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
import eu.akoos.photos.data.db.entity.FaceScanEntity
import eu.akoos.photos.util.forEachSqlChunk

@Dao
interface FaceScanDao {

    /** Insert or replace a batch of scan markers in one transaction. Idempotent on re-scan, since the
     *  primary key is (userId, photoKey). */
    @Upsert
    suspend fun upsert(scans: List<FaceScanEntity>)

    /** Every photo key the indexer has already scanned for the account, the walk's skip set: it covers
     *  both photos that produced a face and faceless ones, so a re-run never re-decodes a photo it has
     *  already looked at. */
    @Query("SELECT photoKey FROM face_scan WHERE userId = :userId")
    suspend fun scannedKeysForUser(userId: String): List<String>

    /** Whether one photo has already been scanned, so an on-demand pass can skip a photo the walk (or an
     *  earlier on-demand pass) already looked at. */
    @Query("SELECT EXISTS(SELECT 1 FROM face_scan WHERE userId = :userId AND photoKey = :photoKey)")
    suspend fun isScanned(userId: String, photoKey: String): Boolean

    /** Photos the walk scanned but found no face on, the set a sensitive "find more" sweep re-checks:
     *  these are exactly the shots the precision-first walk (1024) left without a face. */
    @Query(
        "SELECT photoKey FROM face_scan WHERE userId = :userId " +
            "AND photoKey NOT IN (SELECT photoKey FROM face WHERE userId = :userId)",
    )
    suspend fun facelessPhotoKeys(userId: String): List<String>

    /** The faceless photos a "find more" sweep has not yet re-checked at the high-resolution setting:
     *  [facelessPhotoKeys] narrowed to markers still flagged not hi-res swept. This is what makes a
     *  repeat sweep progressively cheaper, since a landscape looked at once is never re-scanned. */
    @Query(
        "SELECT photoKey FROM face_scan WHERE userId = :userId AND hiResScanned = 0 " +
            "AND photoKey NOT IN (SELECT photoKey FROM face WHERE userId = :userId)",
    )
    suspend fun hiResPendingKeys(userId: String): List<String>

    /**
     * Flags [photoKeys] as hi-res swept, so a later "find more" sweep skips a photo an earlier sweep
     * already looked at whether or not it turned up a face. Chunked one host variable per key, against
     * the per-statement cap a select-all can exceed.
     */
    suspend fun markHiResScanned(userId: String, photoKeys: Collection<String>) =
        photoKeys.forEachSqlChunk { markHiResScannedChunk(userId, it) }

    @Query("UPDATE face_scan SET hiResScanned = 1 WHERE userId = :userId AND photoKey IN (:photoKeys)")
    suspend fun markHiResScannedChunk(userId: String, photoKeys: List<String>)

    /**
     * Drops the scan markers for [photoKeys], the invalidation a removed or re-indexed photo needs so
     * it is scanned afresh. Chunked one host variable per key, against the per-statement cap a
     * select-all can exceed.
     */
    suspend fun deleteByPhotoKeys(userId: String, photoKeys: Collection<String>) =
        photoKeys.forEachSqlChunk { deleteByPhotoKeysChunk(userId, it) }

    @Query("DELETE FROM face_scan WHERE userId = :userId AND photoKey IN (:photoKeys)")
    suspend fun deleteByPhotoKeysChunk(userId: String, photoKeys: List<String>)

    /** Removes every scan marker for the account, for the sign-out wipe. */
    @Query("DELETE FROM face_scan WHERE userId = :userId")
    suspend fun clearForUser(userId: String)

    /** Removes every scan marker for every account, so a recognition-model re-index wipe forces the
     *  whole library to be re-scanned and re-embedded with the current model. Returns the row count
     *  deleted. */
    @Query("DELETE FROM face_scan")
    suspend fun clearAll(): Int
}
