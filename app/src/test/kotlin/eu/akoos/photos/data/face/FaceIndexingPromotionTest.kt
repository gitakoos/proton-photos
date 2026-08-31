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

package eu.akoos.photos.data.face

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure promotion rule: only a substantial initial backlog runs under the foreground service,
 * and only while the background switch is on, so a few incremental photos never posts a notification.
 */
class FaceIndexingPromotionTest {

    @Test
    fun a_backlog_below_the_threshold_does_not_promote() {
        assertFalse(shouldRunForegroundIndex(FACE_INDEX_FOREGROUND_THRESHOLD - 1, backgroundEnabled = true))
    }

    @Test
    fun a_backlog_at_the_threshold_promotes() {
        assertTrue(shouldRunForegroundIndex(FACE_INDEX_FOREGROUND_THRESHOLD, backgroundEnabled = true))
    }

    @Test
    fun a_backlog_above_the_threshold_promotes() {
        assertTrue(shouldRunForegroundIndex(FACE_INDEX_FOREGROUND_THRESHOLD + 1, backgroundEnabled = true))
    }

    @Test
    fun the_background_switch_off_never_promotes() {
        assertFalse(shouldRunForegroundIndex(FACE_INDEX_FOREGROUND_THRESHOLD, backgroundEnabled = false))
        assertFalse(shouldRunForegroundIndex(FACE_INDEX_FOREGROUND_THRESHOLD * 10, backgroundEnabled = false))
    }

    @Test
    fun an_empty_backlog_does_not_promote() {
        assertFalse(shouldRunForegroundIndex(0, backgroundEnabled = true))
    }
}
