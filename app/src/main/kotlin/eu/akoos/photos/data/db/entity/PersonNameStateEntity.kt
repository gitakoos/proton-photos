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
 * When a named person got the name they hold now, keyed by the NAME like the rest of a person's curation, since a
 * clustering rebuild mints fresh person ids while the name survives. The shared face copy carries it, so an
 * install can tell a rename made elsewhere after this name from one made before it. A name with no row was
 * given before the time was kept and counts as 0.
 */
@Entity(
    tableName = "person_name_state",
    primaryKeys = ["userId", "name"],
)
data class PersonNameStateEntity(
    val userId: String,
    val name: String,
    val namedAt: Long,
)
