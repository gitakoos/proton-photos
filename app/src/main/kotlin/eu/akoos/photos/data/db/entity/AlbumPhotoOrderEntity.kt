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
 * One photo's slot in a hand-made album order. [albumKey] is a cloud album's link id, or `folder:<name>` for a
 * device folder under the local user; [photoKey] is a cloud photo's link id, or a device file's uri in a folder.
 * One row per photo keeps a big album's order out of a single huge value.
 */
@Entity(
    tableName = "album_photo_order",
    primaryKeys = ["userId", "albumKey", "photoKey"],
    indices = [Index(value = ["userId", "albumKey", "position"])],
)
data class AlbumPhotoOrderEntity(
    val userId: String,
    val albumKey: String,
    val photoKey: String,
    val position: Int,
)
