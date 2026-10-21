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
) {
    companion object {
        /**
         * The [userId] partition used when there is no signed-in account, so on-device GPS fixes are
         * stored and read without one. A real Proton user id never equals this, so the two never
         * collide; because [id] is the sole primary key, a photo's row is owned by whichever partition
         * last backfilled it, and a guest/account switch simply re-reads it once.
         */
        const val LOCAL_USER = "local"
    }
}

/**
 * Whether this fix sits at exactly 0 degrees latitude and longitude. A photo with no real GPS is often stored
 * there, in the sea off West Africa, so the maps and the places grouping leave such a fix out.
 */
fun PhotoLocationEntity.isNullIsland(): Boolean = latitude == 0.0 && longitude == 0.0

/**
 * Whether [latitude] and [longitude] are a real position: inside the WGS84 ranges and not exactly 0, 0,
 * which a camera or an app writes when it has no fix. Every read of a photo's GPS goes through this, so a
 * photo without a real position shows no place instead of the sea off West Africa.
 */
fun isUsableGpsFix(latitude: Double, longitude: Double): Boolean =
    latitude in -90.0..90.0 && longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0)
