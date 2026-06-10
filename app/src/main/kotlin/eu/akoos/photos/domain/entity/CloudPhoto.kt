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

package eu.akoos.photos.domain.entity

data class CloudPhoto(
    val linkId: String,
    val shareId: String,
    val volumeId: String,
    val captureTime: Long,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val thumbnailUrl: String?,
    val revisionId: String,
    /** SHA-256 of the plaintext content (hex) — used for hash-based local↔cloud matching. */
    val contentHash: String? = null,
    /** PhotoTag ids assigned to this photo by Drive (0 = Favorite). */
    val tags: Set<Int> = emptySet(),
) {
    val isFavoriteOnCloud: Boolean get() = 0 in tags

    /**
     * captureTime (epoch SECONDS) promoted to ms, treated as valid only when it clears
     * [TimestampSanity.FLOOR_MS]. Drive returns 0 for photos uploaded with no capture
     * timestamp, which would otherwise pin them to the epoch at the list tail. Callers
     * with a local twin (see [GalleryItem.Synced]) substitute the local DATE_TAKEN here.
     */
    val captureTimeMs: Long get() = captureTime * 1000L
}
