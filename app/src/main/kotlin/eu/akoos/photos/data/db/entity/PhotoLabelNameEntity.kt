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
 * A label the user created without putting it on a photo yet, so it is listed in Settings and offered
 * when labelling. Once a photo carries the label it shows up through [PhotoLabelEntity] as well, and this
 * row only keeps the spelling. [labelKey] is the folded form and [label] the spelling, as on
 * [PhotoLabelEntity]; [userId] is the account or the guest owner.
 */
@Entity(
    tableName = "photo_label_name",
    primaryKeys = ["userId", "labelKey"],
)
data class PhotoLabelNameEntity(
    val userId: String,
    val labelKey: String,
    val label: String,
    val createdAt: Long,
)
