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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the phase markers of [PerfDiagnostics]: a start / end pair reports the item count and the heap
 * growth, the label keeps only the fixed-label alphabet (so a path, an email or a numeric id can never
 * reach a shared log), the marker ring and the peaks table stay bounded, and the dump / tail / clear
 * contract the Settings diagnostics and the crash record rely on.
 */
class PerfDiagnosticsPhaseTest {

    @Before
    fun setUp() = PerfDiagnostics.clear()

    @After
    fun tearDown() = PerfDiagnostics.clear()

    private fun phaseSection(): List<String> =
        PerfDiagnostics.dump().substringAfter("phases:\n").substringBefore("\nphase peaks:").lines()

    private fun peakSection(): List<String> =
        PerfDiagnostics.dump().substringAfter("phase peaks:\n").lines()

    @Test
    fun a_start_and_end_pair_reports_items_and_growth() {
        PerfDiagnostics.mark("device-scan:start", 0)
        PerfDiagnostics.mark("device-scan:end", 150_000)

        val phases = phaseSection()
        assertEquals(2, phases.size)
        assertTrue(phases[0].contains("device-scan:start items=0 heap="))
        assertFalse(phases[0].contains("grew="))
        assertTrue(phases[1].contains("device-scan:end items=150000 heap="))
        assertTrue(phases[1].contains(" grew="))
        assertTrue(peakSection().single().startsWith("device-scan marks=2 maxItems=150000 maxHeap="))
        assertTrue(peakSection().single().contains(" maxGrowth="))
    }

    @Test
    fun an_end_without_a_start_reports_no_growth() {
        PerfDiagnostics.mark("reconcile:end", 5)

        assertFalse(PerfDiagnostics.dump().contains("grew="))
        assertFalse(peakSection().single().contains("maxGrowth="))
    }

    @Test
    fun a_label_keeps_only_the_fixed_alphabet() {
        assertEquals("device-scan:start", PerfDiagnostics.phaseLabel("Device-Scan:START"))
        val leaked = PerfDiagnostics.phaseLabel("scan /storage/emulated/0/DCIM me@example.eu 1234")
        assertFalse(leaked.any { it == '/' || it == '@' || it == ' ' || it.isDigit() })
        assertEquals("phase", PerfDiagnostics.phaseLabel("1234 / @"))
        assertEquals(32, PerfDiagnostics.phaseLabel("a".repeat(100)).length)
    }

    @Test
    fun a_phase_line_formats_the_growth_with_its_sign() {
        assertEquals(
            "reconcile:end items=7 heap=300/512MB nativeMB=40 grew=-12MB in 1.5s",
            PerfDiagnostics.phaseLine("reconcile:end", 7, 300, 512, 40, -12, 1_500),
        )
        assertEquals(
            "upload-batch:start items=0 heap=80/512MB nativeMB=12",
            PerfDiagnostics.phaseLine("upload-batch:start", 0, 80, 512, 12, null, null),
        )
        assertEquals(
            "tag-scan marks=3 maxItems=9 maxHeap=200MB maxGrowth=+64MB",
            PerfDiagnostics.peakLine("tag-scan", 3, 9, 200, 64),
        )
    }

    @Test
    fun the_marker_ring_stays_bounded() {
        repeat(500) { PerfDiagnostics.mark("upload-batch:end", it) }

        val phases = phaseSection()
        assertEquals(64, phases.size)
        assertTrue(phases.last().contains("items=499"))
    }

    @Test
    fun the_peaks_table_stays_bounded() {
        repeat(100) { PerfDiagnostics.mark("phase-" + ('a' + it / 26) + ('a' + it % 26), it) }

        assertEquals(24, peakSection().size)
    }

    @Test
    fun the_tail_holds_only_the_newest_lines() {
        repeat(20) { PerfDiagnostics.mark("exif-walk:end", it) }

        val tail = PerfDiagnostics.phaseTail(3).lines()
        assertEquals(3, tail.size)
        assertTrue(tail.first().contains("items=17"))
        assertTrue(tail.last().contains("items=19"))
    }

    @Test
    fun clear_empties_the_markers_too() {
        PerfDiagnostics.mark("tag-scan:start", 3)
        assertFalse(PerfDiagnostics.isEmpty())

        PerfDiagnostics.clear()

        assertTrue(PerfDiagnostics.isEmpty())
        assertEquals("", PerfDiagnostics.dump())
        assertEquals("", PerfDiagnostics.phaseTail())
    }
}
