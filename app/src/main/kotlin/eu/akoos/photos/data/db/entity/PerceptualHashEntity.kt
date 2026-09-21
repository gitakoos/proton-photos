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

package eu.akoos.photos.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One persisted perceptual fingerprint per photo, the source the near-duplicate finder reads from.
 * Computing it means decoding a bitmap, so caching it here lets the finder open instantly instead of
 * re-decoding the whole library every time.
 *
 * The fingerprint is a 256-bit DCT hash held as four longs ([h0]..[h3]), plus a [quality] detail score
 * (a near-flat frame is dropped rather than matched against every other flat frame) and a coarse [color]
 * signature (so two shots that share a layout but not a palette are told apart). See
 * [eu.akoos.photos.util.PdqHash].
 *
 * A row is fresh only while its [freshness] key still matches the live item. For a device photo that key
 * is "<dateModified>_<size>", so a file replaced in place recomputes; for a cloud photo it is the
 * immutable linkId, so it never goes stale. [algoVersion] is the second freshness gate: bumping
 * [eu.akoos.photos.util.PdqHash.ALGO_VERSION] invalidates every stored fingerprint at once when the
 * algorithm changes. Rebuildable, so a dropped row is harmless.
 *
 * Not a data class: the generated equality would compare [color] by reference, so equality is spelled
 * out below with content comparison over the blob, matching how the other blob-carrying entities do it.
 */
@Entity(tableName = "perceptual_hash")
class PerceptualHashEntity(
    /** Stable id of the item: a cloud photo's linkId, or a local photo's content URI. */
    @PrimaryKey val key: String,
    /** The 256-bit DCT fingerprint, little-endian across the four longs. */
    val h0: Long,
    val h1: Long,
    val h2: Long,
    val h3: Long,
    /** Detail score 0..100; below [eu.akoos.photos.util.PdqHash.MIN_QUALITY] the frame is too flat to
     *  fingerprint reliably and the finder skips it. */
    val quality: Int,
    /** Coarse colour signature ([eu.akoos.photos.util.PdqHash.COLOR_BYTES] bytes, cell-major R,G,B). */
    val color: ByteArray,
    /** True for a cloud (linkId) key, false for a device (uri) key, so grouping stays homogeneous
     *  without re-parsing the source item. */
    val isCloud: Boolean,
    /** Freshness key: "<dateModified>_<size>" for device rows, the linkId for cloud rows. */
    val freshness: String,
    /** Fingerprint algorithm version this row was computed under; recompute when it moves. */
    val algoVersion: Int,
    /** Epoch-ms the fingerprint was computed, for diagnostics / future cache-age policy. */
    val computedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PerceptualHashEntity) return false
        return key == other.key &&
            h0 == other.h0 && h1 == other.h1 && h2 == other.h2 && h3 == other.h3 &&
            quality == other.quality &&
            color.contentEquals(other.color) &&
            isCloud == other.isCloud &&
            freshness == other.freshness &&
            algoVersion == other.algoVersion &&
            computedAt == other.computedAt
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + h0.hashCode()
        result = 31 * result + h1.hashCode()
        result = 31 * result + h2.hashCode()
        result = 31 * result + h3.hashCode()
        result = 31 * result + quality
        result = 31 * result + color.contentHashCode()
        result = 31 * result + isCloud.hashCode()
        result = 31 * result + freshness.hashCode()
        result = 31 * result + algoVersion
        result = 31 * result + computedAt.hashCode()
        return result
    }
}
