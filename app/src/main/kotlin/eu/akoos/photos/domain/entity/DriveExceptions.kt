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

class StorageFullException(message: String = "Storage quota exceeded") : Exception(message)

class RateLimitedException(val retryAfterSeconds: Int) : Exception("Rate limited, retry after $retryAfterSeconds s")

class DriveNotFoundException(message: String = "Resource not found") : Exception(message)

/**
 * The server refused to delete an album because some of its photos exist nowhere else in the
 * owner's library, so removing the album would destroy them.
 *
 * Raised instead of a generic failure so the caller can offer the choice rather than report an
 * error: keep the album, save those photos into the library first, or delete them along with it.
 * The photos in question are typically contributions from someone the album was shared with, which
 * arrive as copies parented to the album and never enter the owner's own timeline.
 */
class AlbumDeleteWouldLosePhotos(val albumLinkId: String) :
    Exception("Deleting album $albumLinkId would delete photos held nowhere else")
