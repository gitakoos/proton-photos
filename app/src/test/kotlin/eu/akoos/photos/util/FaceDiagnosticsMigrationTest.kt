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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins how the copied diagnostics report a guest to account face migration, the one sign-in step whose
 * outcome is otherwise invisible: the recorded action and its people and face counts show up in the
 * snapshot, an idempotent second call that resolves to NONE does not clobber the real outcome, and a
 * sign-out clear resets it. [FaceDiagnostics] is a process singleton, so each test brackets itself with
 * a clear.
 */
class FaceDiagnosticsMigrationTest {

    @Before
    fun reset() = FaceDiagnostics.clear()

    @After
    fun tearDown() = FaceDiagnostics.clear()

    @Test
    fun a_fresh_session_reports_no_migration() {
        assertTrue(FaceDiagnostics.snapshot().contains("migration=none"))
    }

    @Test
    fun an_adopt_is_reported_with_its_people_and_face_counts() {
        FaceDiagnostics.recordMigration("ADOPT", people = 8, faces = 142)
        assertTrue(FaceDiagnostics.snapshot().contains("migration=ADOPT people=8 faces=142"))
    }

    @Test
    fun a_none_result_does_not_overwrite_a_real_migration() {
        // The walk and the on-demand path both run the idempotent migration; whichever runs second sees
        // no guest rows left and resolves to NONE. That NONE must not erase the first call's real result.
        FaceDiagnostics.recordMigration("ADOPT", people = 8, faces = 142)
        FaceDiagnostics.recordMigration("NONE", people = 0, faces = 0)
        val snapshot = FaceDiagnostics.snapshot()
        assertTrue(snapshot.contains("migration=ADOPT people=8 faces=142"))
        assertFalse(snapshot.contains("migration=none"))
    }

    @Test
    fun a_sign_out_clear_resets_the_migration() {
        FaceDiagnostics.recordMigration("DISCARD", people = 5, faces = 90)
        FaceDiagnostics.clear()
        assertTrue(FaceDiagnostics.snapshot().contains("migration=none"))
    }
}
