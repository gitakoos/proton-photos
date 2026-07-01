/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One persisted perceptual fingerprint (dHash) per photo, the source the near-duplicate finder
 * reads from. Computing the hash means decoding a bitmap, so caching it here lets the finder open
 * instantly instead of re-decoding the whole library every time.
 *
 * A row is fresh only while its [freshness] key still matches the live item. For a device photo
 * that key is "<dateModified>_<size>", so a file replaced in place recomputes; for a cloud photo
 * it is the immutable linkId, so it never goes stale. [algoVersion] is the second freshness gate:
 * bumping [eu.akoos.photos.util.PerceptualHash.DHASH_ALGO_VERSION] invalidates every stored hash
 * at once when the algorithm changes. Rebuildable, so a dropped row is harmless.
 */
@Entity(tableName = "perceptual_hash")
data class PerceptualHashEntity(
    /** Stable id of the item: a cloud photo's linkId, or a local photo's content URI. */
    @PrimaryKey val key: String,
    /** The 64-bit dHash signature. */
    val hash: Long,
    /** True for a cloud (linkId) key, false for a device (uri) key, so grouping stays homogeneous
     *  without re-parsing the source item. */
    val isCloud: Boolean,
    /** Freshness key — "<dateModified>_<size>" for device rows, the linkId for cloud rows. */
    val freshness: String,
    /** dHash algorithm version this row was computed under; recompute when it moves. */
    val algoVersion: Int,
    /** Epoch-ms the hash was computed, for diagnostics / future cache-age policy. */
    val computedAt: Long,
)
