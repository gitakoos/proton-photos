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
 * How one album lists its photos and when that changed: [mode] is `newest`, `oldest` or `custom` (null follows
 * the default) as of [modeAt], and [orderAt] is when the hand-made order in [AlbumPhotoOrderEntity] was last
 * saved (null when there is none). The two times are separate, so picking Newest on one device never wipes a
 * newer arrangement made on another.
 */
@Entity(
    tableName = "album_order_state",
    primaryKeys = ["userId", "albumKey"],
)
data class AlbumOrderStateEntity(
    val userId: String,
    val albumKey: String,
    val mode: String?,
    val modeAt: Long?,
    val orderAt: Long?,
)
