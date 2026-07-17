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
 * Quality tier for opt-in upload compression, shared by the photo and the video toggles. Each value
 * carries the JPEG quality plus longest-edge cap the photo path re-encodes at ([maxLongEdgePx] of 0
 * means keep the original dimensions), and the short-edge cap plus target bitrate the video path
 * transcodes at ([videoMaxShortEdgePx] of 0 means keep the source resolution). The ordinal is what
 * gets persisted in [eu.akoos.photos.data.preferences.SettingsKeys.COMPRESS_UPLOAD_TIER], so keep
 * the declaration order stable: reordering silently repoints every saved setting at a different
 * tier. Picker labels and descriptions map to string resources in the settings UI layer.
 */
enum class UploadCompressionTier(
    val quality: Int,
    val maxLongEdgePx: Int,
    val videoMaxShortEdgePx: Int,
    val videoBitrateBps: Int,
) {
    LIGHT(90, 0, 0, 12_000_000),
    BALANCED(80, 4096, 1080, 8_000_000),
    SPACE_SAVER(70, 2560, 720, 4_000_000);

    companion object {
        fun fromOrdinalOrDefault(ordinal: Int): UploadCompressionTier =
            entries.firstOrNull { it.ordinal == ordinal } ?: BALANCED
    }
}
