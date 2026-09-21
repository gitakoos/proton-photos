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
 * One media entry staged from a picked import archive, awaiting the user's review before anything is
 * uploaded. The staging pass writes one row per entry with the metadata it resolved (title, capture
 * time, place, description) plus a cached thumbnail so the review list can render without re-reading
 * the archive. The row lives until the run's staged set is cleared, so a review survives a process kill
 * and resumes where it was left.
 *
 * Keyed on ([zipId], [entryName]): [zipId] is the picked archive's stable identity across a kill,
 * [entryName] is the entry's path within it, so re-staging the same archive dedupes per entry while a
 * different archive keeps its own set. The composite primary key already indexes [zipId] as its
 * leftmost column; the explicit index on [zipId] mirrors the per-zip observe and count queries.
 *
 * [excluded] is the user's "skip this one" choice and [uploaded] is set once the entry has been sent,
 * so the upload pass takes only the rows that are neither. [sizeBytes] is the entry's byte length for a
 * running total on the review screen, [thumbPath] is the on-disk cached preview (deleted when the
 * staged set is cleared), and [stagedAt] is the epoch-ms the row was written.
 *
 * [albumName] is the export album folder this entry belongs to, or null for a timeline entry no album
 * claims, so the album phase can regroup the run's photos into their source albums after upload.
 * [alreadyInDrive] is set at stage time when the entry's content already lives in Drive, so the review
 * can badge it as a skip before anything uploads.
 */
@Entity(
    tableName = "import_staged",
    primaryKeys = ["zipId", "entryName"],
    indices = [Index("zipId")],
)
data class ImportStagedEntity(
    val zipId: String,
    val entryName: String,
    val title: String? = null,
    val dateMs: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val description: String? = null,
    val sizeBytes: Long = 0,
    val thumbPath: String? = null,
    val excluded: Boolean = false,
    val uploaded: Boolean = false,
    val stagedAt: Long,
    val albumName: String? = null,
    val alreadyInDrive: Boolean = false,
)
