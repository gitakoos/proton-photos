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

package eu.akoos.photos.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the locale-independent byte-to-MB formatting the copied diagnostics ring uses, so the text stays
 * stable: rounding to one tenth of a MiB, the "?" sentinel for a missing size, and no overflow on a
 * gigabyte-scale total.
 */
class FaceDiagnosticsFormatTest {

    @Test
    fun a_missing_or_nonpositive_size_reads_as_a_question_mark() {
        assertEquals("?", FaceDiagnostics.formatMb(0L))
        assertEquals("?", FaceDiagnostics.formatMb(-1L))
    }

    @Test
    fun whole_and_half_mebibytes_format_to_one_decimal() {
        assertEquals("0.5MB", FaceDiagnostics.formatMb(512L * 1024L))
        assertEquals("1.0MB", FaceDiagnostics.formatMb(1024L * 1024L))
        assertEquals("1.5MB", FaceDiagnostics.formatMb(1024L * 1024L + 512L * 1024L))
        assertEquals("2.0MB", FaceDiagnostics.formatMb(2L * 1024L * 1024L))
    }

    @Test
    fun a_sub_tenth_size_rounds_to_zero_but_still_prints() {
        // ~0.01 MiB rounds to 0.0, so a tiny item still shows a size rather than dropping to "?".
        assertEquals("0.0MB", FaceDiagnostics.formatMb(10_000L))
    }

    @Test
    fun a_gigabyte_total_does_not_overflow() {
        // The session download total can reach the GB range; the *10 term must stay within Long.
        assertEquals("1024.0MB", FaceDiagnostics.formatMb(1024L * 1024L * 1024L))
    }
}
