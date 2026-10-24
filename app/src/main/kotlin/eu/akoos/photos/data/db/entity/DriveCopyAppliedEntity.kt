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

/**
 * Another install's Drive copy of one [kind] of data the shared backup has taken in, by that install's
 * [deviceId], at the time the copy was made ([createdAt]), so each copy is read and put together with this
 * device's data once.
 */
@Entity(
    tableName = "drive_copy_applied",
    primaryKeys = ["userId", "kind", "deviceId"],
)
data class DriveCopyAppliedEntity(
    val userId: String,
    val kind: String,
    val deviceId: String,
    val createdAt: Long,
)
