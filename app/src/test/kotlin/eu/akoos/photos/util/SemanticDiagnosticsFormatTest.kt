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
 * Pins the source-deferral line of the copied semantic-search diagnostics: the photos tombstoned this
 * session because their source can never load, and the last pass's backlog of photos waiting out a retry
 * backoff or left over the download budget. [SemanticDiagnostics] is a process singleton, so each test
 * brackets itself with a clear.
 */
class SemanticDiagnosticsFormatTest {

    @Before
    fun reset() = SemanticDiagnostics.clear()

    @After
    fun tearDown() = SemanticDiagnostics.clear()

    @Test
    fun source_deferrals_read_zero_before_anything_is_recorded() {
        val snapshot = SemanticDiagnostics.snapshot()
        assertTrue(
            snapshot,
            snapshot.contains("embedFailures=0\npermanentSkipped=0 transientWaiting=0 overBudget=0\nlastPark="),
        )
    }

    @Test
    fun source_deferrals_print_the_session_skips_and_the_last_pass_backlog() {
        SemanticDiagnostics.recordPermanentSkip()
        SemanticDiagnostics.recordSourceBacklog(transientWaiting = 3, overBudget = 400)
        SemanticDiagnostics.recordSourceBacklog(transientWaiting = 4, overBudget = 336)
        val snapshot = SemanticDiagnostics.snapshot()
        assertTrue(snapshot, snapshot.contains("permanentSkipped=1 transientWaiting=4 overBudget=336\n"))
    }

    @Test
    fun clear_resets_the_source_deferrals() {
        SemanticDiagnostics.recordPermanentSkip()
        SemanticDiagnostics.recordSourceBacklog(transientWaiting = 3, overBudget = 400)
        SemanticDiagnostics.clear()
        val snapshot = SemanticDiagnostics.snapshot()
        assertTrue(snapshot, snapshot.contains("permanentSkipped=0 transientWaiting=0 overBudget=0\n"))
    }
}
