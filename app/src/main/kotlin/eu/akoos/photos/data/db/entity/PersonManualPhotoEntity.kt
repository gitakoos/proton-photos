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
 * A photo the user has manually attached to a named person, over and above what face clustering
 * found. Keyed by the person's NAME rather than its row id, because a clustering rebuild mints fresh
 * person ids while a given name survives (it is carried onto the matching new cluster), so a manual
 * membership stays attached to the same person across rescans. [photoKey] is the item's stableId, the
 * same key the face index and the merged library use, so a manual add resolves through the same path.
 */
@Entity(
    tableName = "person_manual_photo",
    primaryKeys = ["userId", "personName", "photoKey"],
    indices = [Index("userId", "personName")],
)
data class PersonManualPhotoEntity(
    val userId: String,
    val personName: String,
    val photoKey: String,
)
