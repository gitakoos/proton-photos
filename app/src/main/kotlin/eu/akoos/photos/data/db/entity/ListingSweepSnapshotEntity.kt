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
 * One refresh pass's sweep candidates, materialised before its listing walk asks for a page and
 * consumed as the walk accounts for them.
 *
 * The table exists so that what the sweep may delete is decided by WHEN the candidate set was read,
 * not by a predicate about how well the walk went. Rows are written only by a pass that starts
 * fresh; every later step removes rows and none adds any, so whatever survives to the end of
 * pagination is by construction a subset of what the library already held before the walk began. A
 * photo another client uploads mid-walk was never a candidate and so can never be deleted, however
 * many passes the walk takes or how often one of them is interrupted.
 *
 * Scoped by volume as well as by user, because the sweep is: the stream walk speaks only for the
 * user's own volume, and a photo from an album another user shared keeps the OWNER's volumeId. The
 * composite primary key indexes (userId, volumeId) as its own prefix, so the per-generation reads
 * and deletes need no second index.
 */
@Entity(tableName = "listing_sweep_snapshot", primaryKeys = ["userId", "volumeId", "linkId"])
data class ListingSweepSnapshotEntity(
    val userId: String,
    val volumeId: String,
    val linkId: String,
)
