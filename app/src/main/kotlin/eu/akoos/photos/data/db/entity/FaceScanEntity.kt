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

/**
 * One marker per photo the face indexer has fully scanned, whether or not it held a face. A re-run
 * derives its pending set from these markers, so a faceless photo is not re-decoded (and, for a cloud
 * photo, not re-downloaded) on every later pass, which is what turns the walk idempotent across
 * gallery re-entries rather than only within one process.
 *
 * [photoKey] is the same identity the gallery feed uses (a cloud linkId or a device content URI, the
 * GalleryItem.stableId keyspace and FaceEntity.photoKey), so the marker lines up with the timeline the
 * walk enumerates.
 *
 * [hiResScanned] records that the sensitive "find more photos" sweep has already re-checked this
 * faceless photo at the high-resolution detector setting. The background walk leaves it false; the
 * sweep sets it once it has looked, so each faceless photo is hi-res swept at most once ever and a
 * repeat "find more" run does not re-scan the whole faceless set from zero.
 */
@Entity(
    tableName = "face_scan",
    primaryKeys = ["userId", "photoKey"],
    indices = [Index("userId")],
)
data class FaceScanEntity(
    val userId: String,
    val photoKey: String,
    @ColumnInfo(defaultValue = "0")
    val hiResScanned: Boolean = false,
)
