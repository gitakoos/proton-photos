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

/**
 * A single library photo the current user has shared via a public link, surfaced in the
 * "Shared by me" tab next to shared albums. Lightweight by design: it only carries what the
 * grid cell needs plus the linkId so the manage-link sheet can act on it. The public URL
 * itself is resolved lazily when the user opens the manage sheet — listing every link's URL
 * up front would cost one network round-trip per shared photo.
 */
data class SharedPhoto(
    val linkId: String,
    val displayName: String,
    val isVideo: Boolean,
    /** CDN/file thumbnail URL once decrypted; null until the lazy thumbnail decrypt lands. */
    val thumbnailUrl: String? = null,
)
