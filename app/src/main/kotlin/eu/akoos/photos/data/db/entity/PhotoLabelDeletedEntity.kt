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
 * A label deleted on this device or taken in as deleted from another, with when, so an older copy from another
 * device does not bring it back. [labelKey] is the folded form and [label] the spelling, as on
 * [PhotoLabelEntity]. Putting the label back on a photo later clears the row.
 */
@Entity(
    tableName = "photo_label_deleted",
    primaryKeys = ["userId", "labelKey"],
)
data class PhotoLabelDeletedEntity(
    val userId: String,
    val labelKey: String,
    val label: String,
    val deletedAt: Long,
)
