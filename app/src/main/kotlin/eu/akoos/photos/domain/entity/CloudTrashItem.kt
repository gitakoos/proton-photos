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
 * A photo that currently sits in the Proton Drive server-side trash.
 * It was deleted from the Photos stream (via the app or Drive web) but not yet
 * permanently removed — the server keeps it for 30 days before auto-purging.
 */
data class CloudTrashItem(
    val linkId: String,
    val captureTime: Long?,       // epoch seconds, null if unknown
    val thumbnailUrl: String?,    // pre-fetched CDN bare-URL, null if unavailable
    val thumbnailToken: String?,  // CDN token for the thumbnail URL
)
