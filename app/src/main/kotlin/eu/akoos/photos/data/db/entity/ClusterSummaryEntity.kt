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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A per-person centroid cache, so an incremental clustering pass can place a newly indexed face against
 * the people already grouped without re-reading every stored embedding. [personId] mirrors the
 * `person.id` the summary describes, so one row summarises one cluster.
 *
 * [centroid] is the person's mean direction: the 128-d, L2-normalised recognition vector packed
 * little-endian, byte-for-byte the layout the indexer writes into `face.embedding` (packEmbedding /
 * unpackEmbedding), so a cosine between a face and a centroid is a plain dot product. [memberCount] is
 * how many faces the centroid was averaged over, so an incremental update can fold a new face in as a
 * running mean. [modelVersion] is the face-pipeline generation the summary belongs to, so a cache left
 * behind by an older detector or recognition model is ignored rather than trusted.
 *
 * Additive and rebuildable: the cache is derived entirely from the `face` and `person` tables, so an
 * empty table is always a correct state to arrive at and a full recluster refills it.
 *
 * Not a data class: the generated equality would compare [centroid] by reference, so the members are
 * spelled out below with content equality over the blob, matching FaceEntity.
 */
@Entity(
    tableName = "cluster_summary",
    indices = [Index("userId")],
)
class ClusterSummaryEntity(
    @PrimaryKey val personId: Long,
    val userId: String,
    val centroid: ByteArray,
    val memberCount: Int,
    val modelVersion: Int,
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClusterSummaryEntity) return false
        return personId == other.personId &&
            userId == other.userId &&
            centroid.contentEquals(other.centroid) &&
            memberCount == other.memberCount &&
            modelVersion == other.modelVersion &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = personId.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + centroid.contentHashCode()
        result = 31 * result + memberCount
        result = 31 * result + modelVersion
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
