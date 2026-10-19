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

package eu.akoos.photos.domain.entity.compression

import eu.akoos.photos.domain.entity.UploadCompressionTier

/**
 * The upload compression options as one value: [photos] and [videos] switch each kind on, [hdr] lets an
 * Ultra HDR still be compressed (flattening its HDR), [tier] is the photo tier and [videoProfile] the
 * video codec and tier.
 */
data class CompressionSettings(
    val photos: Boolean = false,
    val hdr: Boolean = false,
    val tier: UploadCompressionTier = UploadCompressionTier.BALANCED,
    val videos: Boolean = false,
    val videoProfile: VideoCompressionProfile = VideoCompressionProfile(),
)
