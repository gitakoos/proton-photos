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
import eu.akoos.photos.data.db.entity.ListingSweepSnapshotEntity

/**
 * The refresh sweep's self-consuming candidate set. See [ListingSweepSnapshotEntity] for why the
 * safety comes from the moment [replaceGeneration] runs rather than from any test applied later.
 */
@Dao
interface ListingSweepSnapshotDao {

    /** IGNORE on conflict: a generation names each candidate once, so a repeated id is not news. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<ListingSweepSnapshotEntity>)

    /** What this generation still has not accounted for — the delete set once pagination ends. */
    @Query("SELECT linkId FROM listing_sweep_snapshot WHERE userId = :userId AND volumeId = :volumeId")
    suspend fun getGeneration(userId: String, volumeId: String): List<String>

    /** Ends a generation, either because its answer has been read or because a new pass supersedes it. */
    @Query("DELETE FROM listing_sweep_snapshot WHERE userId = :userId AND volumeId = :volumeId")
    suspend fun clearGeneration(userId: String, volumeId: String)

    /** Every generation this user holds, across volumes, for the sign-out wipe. A leftover generation
     *  is harmless to a later walk (a fresh pass replaces it before reading it), but it names one
     *  account's photos and the sign-out wipe leaves none of those behind. */
    @Query("DELETE FROM listing_sweep_snapshot WHERE userId = :userId")
    suspend fun clearForUser(userId: String)

    /**
     * Accounts for the links one listing page returned. Called per page from the walk itself, so the
     * set shrinks by what the SERVER said it still holds and never by what per-row processing later
     * managed to make of it.
     *
     * Callers chunk [linkIds]: one host variable per element, against a per-statement cap a page can
     * exceed.
     */
    @Query(
        "DELETE FROM listing_sweep_snapshot WHERE userId = :userId AND volumeId = :volumeId " +
            "AND linkId IN (:linkIds)",
    )
    suspend fun deleteListed(userId: String, volumeId: String, linkIds: List<String>)

    /**
     * Starts a generation from the candidates [linkIds] names, replacing whatever a previous pass
     * left behind. Only a pass that starts fresh may call this: a resumed pass would re-offer the
     * rows earlier pages of the same walk already accounted for, and those pages are not coming back.
     *
     * Atomic, so a failure part-way leaves the previous generation rather than half of two. The
     * inserts are chunked so a hundred-thousand-row library never holds every entity at once.
     */
    @Transaction
    suspend fun replaceGeneration(userId: String, volumeId: String, linkIds: List<String>) {
        clearGeneration(userId, volumeId)
        for (chunk in linkIds.chunked(500)) {
            insertAll(chunk.map { ListingSweepSnapshotEntity(userId, volumeId, it) })
        }
    }
}
