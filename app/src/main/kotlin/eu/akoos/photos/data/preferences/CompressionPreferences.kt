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

package eu.akoos.photos.data.preferences

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.domain.entity.compression.VideoCodecChoice
import eu.akoos.photos.domain.entity.compression.VideoCompressionProfile

/**
 * Reads the video compression settings for the upload path and the settings screen alike.
 *
 * An install that compressed videos before the codec choice existed kept each clip's own codec, so
 * it stays on "Same as original" until the user picks a codec; everyone else starts on Auto. [migrate]
 * pins that once at startup, and until it has run the same rule applies on read.
 */
object CompressionPreferences {

    fun videoProfile(prefs: Preferences): VideoCompressionProfile = VideoCompressionProfile(
        codec = videoCodec(prefs),
        tier = UploadCompressionTier.fromOrdinalOrDefault(prefs[SettingsKeys.COMPRESS_UPLOAD_TIER_VIDEO] ?: -1),
    )

    fun videoCodec(prefs: Preferences): VideoCodecChoice =
        VideoCodecChoice.fromKey(prefs[SettingsKeys.COMPRESS_VIDEO_CODEC]) ?: defaultCodec(prefs)

    /** Pins the codec for an install that has none stored yet. Returns false once it has run. */
    fun migrate(prefs: MutablePreferences): Boolean {
        if (prefs[SettingsKeys.COMPRESS_CODEC_MIGRATED] == true) return false
        if (prefs[SettingsKeys.COMPRESS_VIDEO_CODEC] == null) {
            prefs[SettingsKeys.COMPRESS_VIDEO_CODEC] = defaultCodec(prefs).key
        }
        prefs[SettingsKeys.COMPRESS_CODEC_MIGRATED] = true
        return true
    }

    private fun defaultCodec(prefs: Preferences): VideoCodecChoice {
        val compressedBefore = prefs[SettingsKeys.COMPRESS_CODEC_MIGRATED] != true &&
            prefs[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] == true
        return if (compressedBefore) VideoCodecChoice.KEEP_SOURCE else VideoCodecChoice.AUTO
    }
}
