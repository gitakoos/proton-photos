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
 * A face the user has said is NOT a given person, recorded when they answer "No" to a suggested match
 * ("Is this <name>?"). Keyed by the person's NAME (stable across a clustering rebuild) and the face id,
 * so the rejected face is never suggested for, nor pulled into, that person again, no matter how the
 * clustering churns underneath. Distinct from [FaceEntity.rejected], which drops a face from People
 * entirely; a not-person face may still belong to a different person.
 */
@Entity(
    tableName = "not_person",
    primaryKeys = ["userId", "personName", "faceId"],
    indices = [Index("userId", "personName")],
)
data class NotPersonEntity(
    val userId: String,
    val personName: String,
    val faceId: String,
)
