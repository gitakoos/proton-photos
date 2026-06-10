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
 * Many-to-many edge table between cloud albums and the photos they reference. A photo
 * physically lives in the photos root folder on Drive; the album is just a list of
 * pointers, so the gallery's `photo_listing.parentLinkId` always points at the root —
 * NOT the album — and a join through this table is the only way to enumerate the
 * photos that belong to a given album.
 *
 * Without this, `loadAlbumPhotosCached(albumLinkId)` has no source of truth to walk
 * for offline reads: the photo rows are persisted by the gallery refresh path, but
 * their album membership disappears the moment the network call returns.
 */
@Entity(
    tableName = "album_photo_membership",
    primaryKeys = ["albumLinkId", "photoLinkId"],
    indices = [Index(value = ["photoLinkId"])],
)
data class AlbumPhotoMembershipEntity(
    val albumLinkId: String,
    val photoLinkId: String,
)
