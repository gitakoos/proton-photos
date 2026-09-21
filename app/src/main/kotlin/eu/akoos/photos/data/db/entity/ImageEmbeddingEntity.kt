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
import androidx.room.Index

/**
 * One CLIP image embedding per photo, the semantic search index. [photoKey] is the same identity the
 * gallery feed uses (a cloud linkId or a device content URI, the GalleryItem.stableId keyspace and
 * FaceEntity.photoKey), so a search hit joins straight back to the timeline. The row's presence doubles
 * as the "already embedded" marker, so a re-run skips a photo it has already indexed without a separate
 * scan table.
 *
 * [embedding] is the 512-d, L2-normalised image vector packed little-endian (the same blob format the
 * face rail uses), held as a plain blob in the app-private, backup-excluded database, never synced
 * anywhere, matching how a decrypted GPS fix already sits plain in `photo_location`. [modelVersion]
 * records which embedding model produced the vector, so a model bump drops the stale-model rows and
 * re-embeds. [indexedAt] is the wall-clock time the row was written.
 *
 * Not a data class: the generated equality would compare [embedding] by reference, so the members are
 * spelled out below with content equality over the blob.
 */
@Entity(
    tableName = "image_embedding",
    primaryKeys = ["userId", "photoKey"],
    indices = [Index("userId")],
)
class ImageEmbeddingEntity(
    val userId: String,
    val photoKey: String,
    val embedding: ByteArray,
    val modelVersion: Int,
    val indexedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageEmbeddingEntity) return false
        return userId == other.userId &&
            photoKey == other.photoKey &&
            embedding.contentEquals(other.embedding) &&
            modelVersion == other.modelVersion &&
            indexedAt == other.indexedAt
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + photoKey.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + modelVersion
        result = 31 * result + indexedAt.hashCode()
        return result
    }
}
