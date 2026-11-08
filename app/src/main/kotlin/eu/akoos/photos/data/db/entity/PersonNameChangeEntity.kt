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
 * A rename of a named person, or a merge of one into another, at [changedAt]: the person named [fromName] is
 * called [toName] from then on, or folded into the person already called that. The shared face copy carries
 * these, so a later rename or merge made on one install reaches the others.
 */
@Entity(
    tableName = "person_name_change",
    primaryKeys = ["userId", "fromName", "toName", "changedAt"],
)
data class PersonNameChangeEntity(
    val userId: String,
    val fromName: String,
    val toName: String,
    val changedAt: Long,
)
