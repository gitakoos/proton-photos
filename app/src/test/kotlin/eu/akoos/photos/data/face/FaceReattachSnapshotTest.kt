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

import eu.akoos.photos.domain.usecase.ReattachLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Coverage for the reattach snapshot: the pure migration-step decision that keeps an interrupted wipe
 * from destroying a pending snapshot, and the file round-trip that carries the removed-face boxes across
 * the format bump. Plain JVM, only a scratch file for the round-trip.
 */
class FaceReattachSnapshotTest {

    @Test
    fun `faces present with no pending snapshot captures a fresh one`() {
        assertEquals(ReattachMigrationStep.Capture, reattachMigrationStep(faceTableEmpty = false, snapshotPresent = false))
    }

    @Test
    fun `a pending snapshot is resumed and never overwritten`() {
        // A snapshot on disk is un-consumed curation. Whether or not faces are present (a wipe cut short
        // before its marker, or a second model bump before the first reattach drained), capturing over it
        // would lose every name it holds.
        assertEquals(ReattachMigrationStep.Resume, reattachMigrationStep(faceTableEmpty = true, snapshotPresent = true))
        assertEquals(ReattachMigrationStep.Resume, reattachMigrationStep(faceTableEmpty = false, snapshotPresent = true))
    }

    @Test
    fun `faces gone and no snapshot has nothing to preserve`() {
        assertEquals(ReattachMigrationStep.Skip, reattachMigrationStep(faceTableEmpty = true, snapshotPresent = false))
    }

    @Test
    fun `the snapshot round-trips members manual nots and removed boxes`() {
        val file = File.createTempFile("reattach", ".bin").apply { deleteOnExit() }
        val data = FaceReattachData(
            members = listOf(ReattachLabel("p1#0", "p1", 0.10f, 0.10f, 0.30f, 0.30f, "Ann")),
            manual = listOf(ReattachManual("Ann", "p9")),
            nots = listOf(ReattachLabel("p2#1", "p2", 0.40f, 0.40f, 0.60f, 0.60f, "Bob")),
            rejected = listOf(ReattachBox("p3", 0.50f, 0.50f, 0.70f, 0.70f)),
        )
        FaceReattachSnapshot.write(file, data)
        assertEquals(data, FaceReattachSnapshot.read(file))
    }

    @Test
    fun `a missing file reads back as null`() {
        val file = File.createTempFile("reattach", ".bin").apply { delete() }
        assertNull(FaceReattachSnapshot.read(file))
    }
}
