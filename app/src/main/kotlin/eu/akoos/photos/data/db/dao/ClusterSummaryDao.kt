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
import eu.akoos.photos.data.db.entity.ClusterSummaryEntity

@Dao
interface ClusterSummaryDao {

    /** Every cached centroid for the account, the incremental pass's set of people to match against. */
    @Query("SELECT * FROM cluster_summary WHERE userId = :userId")
    suspend fun getAllForUser(userId: String): List<ClusterSummaryEntity>

    /** Insert or replace a batch of centroids in one transaction, the incremental pass rewriting each
     *  touched person's summary at once. */
    @Upsert
    suspend fun upsertAll(rows: List<ClusterSummaryEntity>)

    /** Insert or replace one centroid, for a single person's summary refresh. */
    @Upsert
    suspend fun upsert(row: ClusterSummaryEntity)

    /** Removes every cached centroid for the account, for the sign-out wipe and a full recluster's
     *  clear-before-rebuild. */
    @Query("DELETE FROM cluster_summary WHERE userId = :userId")
    suspend fun clearForUser(userId: String)

    /** Drops one person's cached centroid, so a merged or deleted cluster leaves no stale summary. */
    @Query("DELETE FROM cluster_summary WHERE personId = :personId")
    suspend fun deleteByPerson(personId: Long)

    /** How many people the account has a cached centroid for, so a pass can tell an empty cache (needing
     *  a full build) from a populated one (an incremental update will do). */
    @Query("SELECT COUNT(*) FROM cluster_summary WHERE userId = :userId")
    suspend fun countForUser(userId: String): Int
}
