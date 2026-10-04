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
 * When one photo's label list last changed, kept beside [PhotoLabelEntity] so the Drive copy can merge
 * newest-wins per photo. A photo whose last label was removed keeps its row, which is what lets a removal
 * outrank an older list saved by another device. [photoKey] and [userId] mean what they do on
 * [PhotoLabelEntity].
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
