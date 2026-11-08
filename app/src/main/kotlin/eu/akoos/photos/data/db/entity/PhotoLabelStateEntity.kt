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
 * When one photo's labels last changed on this device, so the shared backup can tell a later edit from an
 * earlier one when it puts this device's labels together with another's. A row whose photo has no
 * [PhotoLabelEntity] left is a photo whose labels were all taken off, kept so that removal reaches the other
 * devices too. [userId] and [photoKey] are as on [PhotoLabelEntity].
 */
@Entity(
    tableName = "photo_label_state",
    primaryKeys = ["userId", "photoKey"],
)
data class PhotoLabelStateEntity(
    val userId: String,
    val photoKey: String,
    val changedAt: Long,
)
