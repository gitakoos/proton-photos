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
import androidx.room.PrimaryKey

/**
 * A cluster of faces the app believes belong to one person. [id] auto-generates so a fresh cluster
 * needs no id chosen up front, and each face points back to it through its own personId.
 * [displayName] is null until someone names the person, [coverFaceId] is the `face.id` shown on the
 * person's tile, and [faceCount] caches how many faces are assigned so the people list need not
 * aggregate on every read. [updatedAt] is an epoch-ms stamp a recluster can bump.
 */
@Entity(
    tableName = "person",
    indices = [Index("userId")],
)
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val displayName: String? = null,
    val coverFaceId: String? = null,
    val faceCount: Int = 0,
    val updatedAt: Long = 0,
)
