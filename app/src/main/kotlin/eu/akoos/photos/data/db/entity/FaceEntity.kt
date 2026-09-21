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
 * One detected face. [id] is a stable key the indexer derives from the photo it came from
 * ([photoKey] + "#" + the face's index within that photo), so re-indexing a photo overwrites its
 * own rows instead of piling up duplicates. [photoKey] is the same identity the gallery feed uses
 * (a cloud linkId or a device content URI, the GalleryItem.stableId keyspace), so a person filter
 * joins straight back to the timeline.
 *
 * [embedding] is the 128-d recognition vector packed little-endian, held as a plain blob in the
 * app-private, backup-excluded database, never synced anywhere, matching how a decrypted GPS fix
 * already sits plain in `photo_location`. It may arrive empty and be filled in once embedded.
 * [personId] is null until the face is clustered into a person.
 *
 * Not a data class: the generated equality would compare [embedding] by reference, so the members
 * are spelled out below with content equality over the blob.
 */
@Entity(
    tableName = "face",
    indices = [Index("photoKey"), Index("personId"), Index("userId")],
)
class FaceEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val photoKey: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val landmarks: String,
    val embedding: ByteArray,
    val personId: Long?,
    val score: Float,
    /** Laplacian-variance sharpness of the aligned crop; null for a row indexed before the metric
     *  existed. A blurred face is held to a stricter cluster distance rather than dropped. */
    val blur: Float? = null,
    /** True once the user removes this face's photo from its person: it is dropped from that person
     *  and skipped by every future clustering pass, so a manual removal is not undone by a rescan. */
    @ColumnInfo(defaultValue = "0")
    val rejected: Boolean = false,
    /** The person name the user confirmed this face belongs to (by adding its photo to that person).
     *  A confirmed face anchors its person's cluster and pulls matching faces in, so a manual add
     *  teaches future clustering. Null for a face the user has not confirmed. */
    val manualName: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEntity) return false
        return id == other.id &&
            userId == other.userId &&
            photoKey == other.photoKey &&
            left == other.left &&
            top == other.top &&
            right == other.right &&
            bottom == other.bottom &&
            landmarks == other.landmarks &&
            embedding.contentEquals(other.embedding) &&
            personId == other.personId &&
            score == other.score &&
            blur == other.blur &&
            rejected == other.rejected &&
            manualName == other.manualName
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + photoKey.hashCode()
        result = 31 * result + left.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + right.hashCode()
        result = 31 * result + bottom.hashCode()
        result = 31 * result + landmarks.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + (personId?.hashCode() ?: 0)
        result = 31 * result + score.hashCode()
        result = 31 * result + (blur?.hashCode() ?: 0)
        result = 31 * result + rejected.hashCode()
        result = 31 * result + (manualName?.hashCode() ?: 0)
        return result
    }
}
