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
 * Per-URI category-tag cache so a re-scan only re-detects changed files (tag detection reads XMP,
 * too costly per file per scan). A row is fresh only while both [dateModified] and [sizeBytes]
 * still match MediaStore — otherwise the file was replaced and tags recompute. Rebuildable.
 */
@Entity(tableName = "local_tag")
data class LocalTagEntity(
    /** MediaStore content URI of the item, e.g. content://media/external/images/media/12345. */
    @PrimaryKey val uri: String,
    /** MediaStore DATE_MODIFIED at scan time — half of the freshness key. */
    val dateModified: Long,
    /** MediaStore SIZE at scan time — the other half of the freshness key. */
    val sizeBytes: Long,
    /** Comma-separated PhotoTag ids (Drive enum: 1=Screenshot, 2=Video, 4=MotionPhoto, …). */
    val tagsCsv: String,
    /** Epoch-ms the detection ran, for diagnostics / future cache-age policy. */
    val scannedAt: Long,
) {
    /** Decode [tagsCsv] into the tag-id set the gallery and search consume. */
    fun tags(): Set<Int> =
        if (tagsCsv.isEmpty()) emptySet()
        else tagsCsv.split(',').mapNotNull { it.toIntOrNull() }.toSet()
}
