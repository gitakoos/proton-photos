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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the copied diagnostics lines that show the ML memory guards at work: the full regroups skipped for
 * want of heap with the latest need and free heap, and the model sessions abandoned to the watchdog with
 * whether they halted the background scan, for faces and for search. Both objects are process singletons,
 * so each test brackets itself with a clear.
 */
class MlMemoryDiagnosticsTest {

    private val mib = 1024L * 1024L

    @Before
    fun reset() {
        FaceDiagnostics.clear()
        SemanticDiagnostics.clear()
    }

    @After
    fun tearDown() = reset()

    @Test
    fun `nothing skipped or abandoned reads as zero and running`() {
        val faces = FaceDiagnostics.snapshot(now = 0L)
        assertTrue(faces, faces.contains("lastRegroupCompleted=never\nregroupHeapSkips=0\ninFlight="))
        assertTrue(faces, faces.contains("\nabandonedSessions=0 scanHalted=no\n"))
        val search = SemanticDiagnostics.snapshot()
        assertTrue(search, search.contains("\nabandonedSessions=0 indexHalted=no\n"))
    }

    @Test
    fun `heap skips count up and show the latest need beside the free heap`() {
        FaceDiagnostics.recordRegroupHeapSkip(needBytes = 200L * mib, freeBytes = 90L * mib)
        FaceDiagnostics.recordRegroupHeapSkip(needBytes = 138L * mib, freeBytes = 96L * mib)
        val snapshot = FaceDiagnostics.snapshot(now = 0L)
        assertTrue(snapshot, snapshot.contains("regroupHeapSkips=2 (need=138.0MB free=96.0MB)\n"))
    }

    @Test
    fun `abandoned sessions show the process total and the halt`() {
        FaceDiagnostics.recordAbandonedSessions(1, halted = false)
        assertTrue(FaceDiagnostics.snapshot(now = 0L).contains("abandonedSessions=1 scanHalted=no\n"))
        FaceDiagnostics.recordAbandonedSessions(2, halted = true)
        assertTrue(FaceDiagnostics.snapshot(now = 0L).contains("abandonedSessions=2 scanHalted=yes\n"))
        SemanticDiagnostics.recordAbandonedSessions(2, halted = true)
        assertTrue(SemanticDiagnostics.snapshot().contains("abandonedSessions=2 indexHalted=yes\n"))
    }

    @Test
    fun `clear resets the skips and the abandoned sessions`() {
        FaceDiagnostics.recordRegroupHeapSkip(needBytes = 200L * mib, freeBytes = 90L * mib)
        FaceDiagnostics.recordAbandonedSessions(2, halted = true)
        SemanticDiagnostics.recordAbandonedSessions(2, halted = true)
        FaceDiagnostics.clear()
        SemanticDiagnostics.clear()
        val faces = FaceDiagnostics.snapshot(now = 0L)
        assertTrue(faces, faces.contains("regroupHeapSkips=0\n"))
        assertTrue(faces, faces.contains("abandonedSessions=0 scanHalted=no\n"))
        assertTrue(SemanticDiagnostics.snapshot().contains("abandonedSessions=0 indexHalted=no\n"))
    }
}
