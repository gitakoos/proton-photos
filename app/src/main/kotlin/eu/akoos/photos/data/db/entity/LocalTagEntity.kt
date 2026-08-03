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
import androidx.room.PrimaryKey
import eu.akoos.photos.util.UserPhotoTags

/**
 * Per-URI category-tag cache so a re-scan only re-detects changed files (tag detection reads XMP,
 * too costly per file per scan). A row is fresh only while both [dateModified] and [sizeBytes]
 * still match MediaStore — otherwise the file was replaced and tags recompute. Rebuildable.
 *
 * [userTagsCsv] is the one part that is NOT rebuildable, so the scanner never writes it: see the
 * column's own note below.
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
    /**
     * Comma-separated PhotoTag ids the USER chose for this file, kept apart from [tagsCsv] because
     * the two have opposite lifetimes. Everything above is a detection the scanner may discard and
     * recompute at will; this is an answer only a person can give, so the scanner writes it never
     * and re-detection leaves it standing (see LocalTagDao.upsertDetection).
     */
    val userTagsCsv: String = "",
) {
    /** Decode [tagsCsv] into the tag-id set the gallery and search consume. */
    fun tags(): Set<Int> =
        if (tagsCsv.isEmpty()) emptySet()
        else tagsCsv.split(',').mapNotNull { it.toIntOrNull() }.toSet()

    /** Decode [userTagsCsv] into the tag-id set the user picked for this file. Out-of-range ids are
     *  dropped, since the Drive PhotoTag enum only runs 0 to 9. */
    fun userTags(): Set<Int> = UserPhotoTags.decode(userTagsCsv)
}
