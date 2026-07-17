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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-value coverage for the upload-compression tiers: the ordinal round-trip that maps the
 * persisted [SettingsKeys.COMPRESS_UPLOAD_TIER] int back to a tier, and the per-tier quality and
 * downscale parameters the recompressor reads. No Android, no Robolectric.
 */
class UploadCompressionTierTest {

    @Test
    fun fromOrdinal_maps_each_valid_ordinal_back_to_its_tier() {
        assertEquals(UploadCompressionTier.LIGHT, UploadCompressionTier.fromOrdinalOrDefault(0))
        assertEquals(UploadCompressionTier.BALANCED, UploadCompressionTier.fromOrdinalOrDefault(1))
        assertEquals(UploadCompressionTier.SPACE_SAVER, UploadCompressionTier.fromOrdinalOrDefault(2))
    }

    @Test
    fun fromOrdinal_falls_back_to_balanced_when_absent_or_out_of_range() {
        // -1 is the "key absent" sentinel the upload path and the ViewModel pass.
        assertEquals(UploadCompressionTier.BALANCED, UploadCompressionTier.fromOrdinalOrDefault(-1))
        assertEquals(UploadCompressionTier.BALANCED, UploadCompressionTier.fromOrdinalOrDefault(99))
    }

    @Test
    fun tiers_get_progressively_lighter() {
        // Quality drops as the tier gets heavier; only LIGHT keeps full resolution (cap 0 = no
        // downscale) while the others cap the longest edge.
        assertEquals(90, UploadCompressionTier.LIGHT.quality)
        assertEquals(80, UploadCompressionTier.BALANCED.quality)
        assertEquals(70, UploadCompressionTier.SPACE_SAVER.quality)
        assertEquals(0, UploadCompressionTier.LIGHT.maxLongEdgePx)
        assertEquals(4096, UploadCompressionTier.BALANCED.maxLongEdgePx)
        assertEquals(2560, UploadCompressionTier.SPACE_SAVER.maxLongEdgePx)
    }
}
