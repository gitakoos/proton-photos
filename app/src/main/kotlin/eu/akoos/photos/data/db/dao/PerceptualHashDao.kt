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
import eu.akoos.photos.data.db.entity.PerceptualHashEntity
import kotlinx.coroutines.flow.Flow

/** Lean projection of a fingerprint row: only the columns the duplicate finder reads (key, hash,
 *  freshness). Skipping the unused `isCloud` / `computedAt` / `algoVersion` columns keeps the row
 *  small so a whole-library stream fits far more rows per cursor window. Mirrors the PhotoListingLite
 *  pattern the timeline feed uses. */
data class PerceptualHashLite(
    val key: String,
    val hash: Long,
    val freshness: String,
)

@Dao
interface PerceptualHashDao {

    /** Insert or replace a batch of computed fingerprints in one transaction. */
    @Upsert
    suspend fun upsertAll(rows: List<PerceptualHashEntity>)

    /** Live stream of every stored fingerprint — the near-duplicate finder's source; it re-groups
     *  as the background scheduler fills in missing rows. */
    @Query("SELECT * FROM perceptual_hash")
    fun observeAll(): Flow<List<PerceptualHashEntity>>

    /** Lean, algorithm-filtered stream for the duplicate finder: only the current-algorithm rows,
     *  projected to the three columns it uses. Filtering `algoVersion` in SQL (instead of loading every
     *  row and dropping stale ones in Kotlin) plus the narrow projection is what keeps a 50k-library
     *  fingerprint stream from pinning the heap. */
    @Query("SELECT `key`, hash, freshness FROM perceptual_hash WHERE algoVersion = :algo")
    fun observeLite(algo: Int): Flow<List<PerceptualHashLite>>

    /** One-shot read of every stored fingerprint, for the scheduler's missing/stale check. */
    @Query("SELECT * FROM perceptual_hash")
    suspend fun getAll(): List<PerceptualHashEntity>

    /** Drop every fingerprint. Called on sign-out so one account's prints don't linger. */
    @Query("DELETE FROM perceptual_hash")
    suspend fun clearAll()

    /** Drop specific fingerprints by key — used to evict rows for items that left the library. */
    @Query("DELETE FROM perceptual_hash WHERE `key` IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)
}
