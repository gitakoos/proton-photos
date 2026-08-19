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
 * The photo a user has picked as a named person's cover, overriding the automatic clearest-face pick.
 * Keyed by the person's NAME (one cover per name) rather than its row id, because a clustering rebuild
 * mints fresh person ids while a name survives, so the choice follows the person across a rescan. The
 * cover is resolved back to that person's face on [photoKey] at display time, so it stays a face crop;
 * if the person no longer has a face on that photo the choice simply falls back to the automatic cover.
 */
@Entity(
    tableName = "person_cover",
    primaryKeys = ["userId", "personName"],
)
data class PersonCoverEntity(
    val userId: String,
    val personName: String,
    val photoKey: String,
)
