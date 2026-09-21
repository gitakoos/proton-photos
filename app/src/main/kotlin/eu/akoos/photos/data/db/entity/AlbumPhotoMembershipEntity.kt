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
 * Album ↔ photo edge table. An owned photo lives in the photos root, so its photo_listing row is
 * parented to the root rather than to the album; only a shared-with-me album's rows carry the
 * album as their parent. This join is therefore the one way to enumerate an album's photos for
 * offline reads whichever shape they have.
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
