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
import androidx.room.Index

/**
 * Album ↔ photo edge table. A photo physically lives in the photos root (photo_listing.parentLinkId
 * points at the root, NOT the album), so this join is the only way to enumerate an album's photos
 * for offline reads.
 */
@Entity(
    tableName = "album_photo_membership",
    primaryKeys = ["albumLinkId", "photoLinkId"],
    // photoLinkId = reverse lookup (a photo's albums); albumLinkId = forward lookup (an album's photos).
    indices = [Index(value = ["photoLinkId"]), Index(value = ["albumLinkId"])],
)
data class AlbumPhotoMembershipEntity(
    val albumLinkId: String,
    val photoLinkId: String,
)
