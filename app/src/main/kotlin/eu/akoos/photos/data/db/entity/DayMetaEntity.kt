/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import androidx.room.PrimaryKey

/**
 * Per-day user-authored metadata for the Calendar view, keyed on an ISO-8601 date string.
 * Keyed on date alone (Room @PrimaryKey is single-column), so [DayMetaDao] queries filter by
 * [userId] to keep a second account from reading or overwriting the previous account's notes.
 */
@Entity(tableName = "day_meta")
data class DayMetaEntity(
    @PrimaryKey val date: String,
    val userId: String,
    /**
     * URI (local content://...) or cloud linkId of the user-picked cover photo. Null
     * means "auto-pick the first photo by captureTime for this day".
     */
    val coverPhotoUri: String? = null,
    /** Free-text location label. */
    val locationText: String? = null,
    /** Longer-form note about this day. */
    val description: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
