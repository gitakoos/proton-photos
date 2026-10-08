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

package eu.akoos.photos.data.upload.compression

import eu.akoos.photos.data.upload.compression.TranscodeAcceptance.Verdict
import eu.akoos.photos.domain.entity.compression.CompressionSkipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscodeAcceptanceTest {

    @Test
    fun a_clearly_smaller_output_is_accepted() {
        assertEquals(Verdict.ACCEPT, TranscodeAcceptance.judge(outputBytes = 40, sourceBytes = 100))
    }

    @Test
    fun an_equal_or_larger_output_is_never_accepted() {
        assertEquals(Verdict.NOT_SMALLER, TranscodeAcceptance.judge(100, 100))
        assertEquals(Verdict.NOT_SMALLER, TranscodeAcceptance.judge(130, 100))
    }

    @Test
    fun a_saving_under_five_percent_is_discarded() {
        assertEquals(Verdict.SAVING_TOO_SMALL, TranscodeAcceptance.judge(97, 100))
        assertEquals(Verdict.ACCEPT, TranscodeAcceptance.judge(95, 100))
    }

    @Test
    fun verdicts_map_to_their_recorded_reasons() {
        assertNull(TranscodeAcceptance.reasonFor(Verdict.ACCEPT))
        assertEquals(CompressionSkipReason.NOT_SMALLER, TranscodeAcceptance.reasonFor(Verdict.NOT_SMALLER))
        assertEquals(CompressionSkipReason.SAVING_TOO_SMALL, TranscodeAcceptance.reasonFor(Verdict.SAVING_TOO_SMALL))
        assertEquals(CompressionSkipReason.ENCODER_ERROR, TranscodeAcceptance.reasonFor(TranscodeAcceptance.judge(0, 100)))
    }

    @Test
    fun only_an_output_that_did_not_shrink_enough_is_retried_in_cbr() {
        assertTrue(TranscodeAcceptance.shouldRetryWithCbr(Verdict.NOT_SMALLER))
        assertTrue(TranscodeAcceptance.shouldRetryWithCbr(Verdict.SAVING_TOO_SMALL))
        assertFalse(TranscodeAcceptance.shouldRetryWithCbr(Verdict.ACCEPT))
        assertFalse(TranscodeAcceptance.shouldRetryWithCbr(Verdict.EMPTY))
    }
}
