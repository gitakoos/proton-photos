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

/**
 * Best-effort projection of a trashed cloud item into the [CloudPhoto] the full-screen viewer
 * consumes. Full-res + crypto are re-resolved by the viewer from shareId/linkId/revisionId, so a
 * legacy row missing one of those degrades to its thumbnail rather than failing to open; passing
 * [thumbnailFileUrl] (the already-decrypted trash thumbnail) lets the viewer paint immediately.
 */
fun CloudTrashItem.toCloudPhoto(thumbnailFileUrl: String? = null): CloudPhoto = CloudPhoto(
    linkId = linkId,
    shareId = shareId ?: "",
    volumeId = volumeId ?: "",
    captureTime = captureTime ?: 0L,
    displayName = name ?: "",
    mimeType = mimeType ?: "application/octet-stream",
    sizeBytes = sizeBytes ?: 0L,
    thumbnailUrl = thumbnailFileUrl,
    revisionId = revisionId ?: "",
)
