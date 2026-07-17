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

package eu.akoos.photos.domain.entity

data class LocalMediaItem(
    val uri: String,
    val dateTaken: Long,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bucketName: String?,
    val width: Int = 0,
    val height: Int = 0,
    val duration: Long = 0,
    /** MediaStore DATE_MODIFIED (epoch seconds). Half of the local-tag cache freshness key —
     *  with [sizeBytes] it detects a file that was replaced in place. 0 when unavailable. */
    val dateModified: Long = 0,
    /** Category-tag ids (Drive PhotoTag enum) from the persisted local-tag cache. Empty when no
     *  fresh cache entry exists yet; categorization then falls back to the cheap heuristics. */
    val tags: Set<Int> = emptySet(),
    /** True when [dateTaken] came from a real MediaStore DATE_TAKEN. False when it fell back to
     *  DATE_ADDED (import time), which for a file received from another app can be far from the
     *  actual capture date the file's own EXIF still carries. Default true so existing constructions
     *  keep their meaning. */
    val dateTakenIsExplicit: Boolean = true,
)
