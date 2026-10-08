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

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.domain.entity.compression.VideoCodecChoice
import eu.akoos.photos.domain.entity.compression.VideoCompressionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionPreferencesTest {

    @Test
    fun a_fresh_install_reads_the_defaults() {
        assertEquals(VideoCompressionProfile(), CompressionPreferences.videoProfile(preferencesOf()))
    }

    @Test
    fun the_tier_is_the_existing_video_tier() {
        val prefs = preferencesOf(SettingsKeys.COMPRESS_UPLOAD_TIER_VIDEO to UploadCompressionTier.SPACE_SAVER.ordinal)
        assertEquals(UploadCompressionTier.SPACE_SAVER, CompressionPreferences.videoProfile(prefs).tier)
    }

    @Test
    fun an_install_already_compressing_videos_keeps_the_source_codec_until_it_chooses() {
        assertEquals(
            VideoCodecChoice.KEEP_SOURCE,
            CompressionPreferences.videoCodec(preferencesOf(SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD to true)),
        )
        assertEquals(VideoCodecChoice.AUTO, CompressionPreferences.videoCodec(preferencesOf()))
    }

    @Test
    fun after_the_migration_turning_videos_on_starts_on_auto() {
        val prefs = preferencesOf(
            SettingsKeys.COMPRESS_CODEC_MIGRATED to true,
            SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD to true,
        )
        assertEquals(VideoCodecChoice.AUTO, CompressionPreferences.videoCodec(prefs))
    }

    @Test
    fun a_stored_codec_wins() {
        val prefs = preferencesOf(SettingsKeys.COMPRESS_VIDEO_CODEC to "av1", SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD to true)
        assertEquals(VideoCodecChoice.AV1, CompressionPreferences.videoCodec(prefs))
    }

    @Test
    fun the_migration_pins_the_effective_codec_once_and_never_overwrites_a_choice() {
        val upgrading = mutablePreferencesOf(SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD to true)
        assertTrue(CompressionPreferences.migrate(upgrading))
        assertEquals("keep", upgrading[SettingsKeys.COMPRESS_VIDEO_CODEC])
        assertEquals(VideoCodecChoice.KEEP_SOURCE, CompressionPreferences.videoCodec(upgrading))

        val fresh = mutablePreferencesOf()
        assertTrue(CompressionPreferences.migrate(fresh))
        assertEquals("auto", fresh[SettingsKeys.COMPRESS_VIDEO_CODEC])

        val chosen = mutablePreferencesOf(SettingsKeys.COMPRESS_VIDEO_CODEC to "hevc")
        assertTrue(CompressionPreferences.migrate(chosen))
        assertEquals("hevc", chosen[SettingsKeys.COMPRESS_VIDEO_CODEC])
        chosen[SettingsKeys.COMPRESS_VIDEO_CODEC] = "av1"
        assertFalse(CompressionPreferences.migrate(chosen))
        assertEquals("av1", chosen[SettingsKeys.COMPRESS_VIDEO_CODEC])
    }

    @Test
    fun the_migration_never_touches_the_toggles() {
        val prefs = mutablePreferencesOf(SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD to false)
        CompressionPreferences.migrate(prefs)
        assertEquals(false, prefs[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD])
        assertNull(prefs[SettingsKeys.COMPRESS_ON_UPLOAD])
    }
}
