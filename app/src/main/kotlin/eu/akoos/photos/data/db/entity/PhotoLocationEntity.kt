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
 * One GPS fix per photo, the source the map view reads from. [id] is the photo's stable key:
 * the local content URI for an on-device photo (and the cloud linkId for a synced photo, which
 * reuses this same table). Rows exist only for photos that actually carry coordinates, so the
 * map can query by [userId] and plot every row without a presence check.
 */
@Entity(tableName = "photo_location")
data class PhotoLocationEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val latitude: Double,
    val longitude: Double,
)
