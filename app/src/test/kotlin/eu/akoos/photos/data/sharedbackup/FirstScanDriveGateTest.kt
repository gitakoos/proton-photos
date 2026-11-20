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

package eu.akoos.photos.data.sharedbackup

import eu.akoos.photos.data.mlsync.MlDriveFileKind
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * A first face or search scan with the Drive backup on takes in another device's copy of that kind first, so the
 * phone does not scan again what a computer scanned; whenever Drive cannot help, the scan starts as before.
 */
class FirstScanDriveGateTest {

    private class FakeDrive(
        val mayWait: Boolean = true,
        val pending: suspend () -> Set<MlDriveFileKind>? = { emptySet() },
        val roundStart: suspend () -> Unit = {},
        val roundEnd: suspend () -> Unit = {},
    ) : FirstScanDrive {
        var roundsAsked = 0

        override suspend fun mayWait(): Boolean = mayWait
        override suspend fun pendingKinds(): Set<MlDriveFileKind>? = pending()
        override suspend fun askRound(): Long {
            roundsAsked++
            return 1_000L
        }
        override suspend fun awaitRoundStart(askedAtMs: Long) = roundStart()
        override suspend fun awaitRoundEnd(askedAtMs: Long) = roundEnd()
    }

    @Test
    fun `a pending face copy is taken in by a round before the face scan`() = runTest {
        val drive = FakeDrive(pending = { setOf(MlDriveFileKind.FACES) })

        val outcome = waitForDriveCopies(MlDriveFileKind.FACES, drive)

        assertEquals(FirstScanWait.TOOK_IN, outcome)
        assertEquals(1, drive.roundsAsked)
    }

    @Test
    fun `a pending search copy holds the search scan`() = runTest {
        val drive = FakeDrive(pending = { setOf(MlDriveFileKind.SEARCH) })

        val outcome = waitForDriveCopies(MlDriveFileKind.SEARCH, drive)

        assertEquals(FirstScanWait.TOOK_IN, outcome)
        assertEquals(1, drive.roundsAsked)
    }

    @Test
    fun `a copy of the other kind only does not hold the scan`() = runTest {
        val drive = FakeDrive(pending = { setOf(MlDriveFileKind.SEARCH) })

        val outcome = waitForDriveCopies(MlDriveFileKind.FACES, drive)

        assertEquals(FirstScanWait.NOTHING_PENDING, outcome)
        assertEquals(0, drive.roundsAsked)
    }

    @Test
    fun `nothing new on Drive starts the scan without a round`() = runTest {
        val drive = FakeDrive(pending = { emptySet() })

        val outcome = waitForDriveCopies(MlDriveFileKind.FACES, drive)

        assertEquals(FirstScanWait.NOTHING_PENDING, outcome)
        assertEquals(0, drive.roundsAsked)
    }

    @Test
    fun `the backup off, a guest or no SDK lists nothing and asks no round`() = runTest {
        var listed = false
        val drive = FakeDrive(mayWait = false, pending = { listed = true; setOf(MlDriveFileKind.FACES) })

        val outcome = waitForDriveCopies(MlDriveFileKind.FACES, drive)

        assertEquals(FirstScanWait.NOT_APPLICABLE, outcome)
        assertEquals(false, listed)
        assertEquals(0, drive.roundsAsked)
    }

    @Test
    fun `Drive that cannot be listed lets the scan start`() = runTest {
        val drive = FakeDrive(pending = { null })

        val outcome = waitForDriveCopies(MlDriveFileKind.FACES, drive)

        assertEquals(FirstScanWait.UNREACHABLE, outcome)
        assertEquals(0, drive.roundsAsked)
    }

    @Test
    fun `a listing that throws lets the scan start`() = runTest {
        val drive = FakeDrive(pending = { throw IOException("offline") })

        val outcome = waitForDriveCopies(MlDriveFileKind.FACES, drive)

        assertEquals(FirstScanWait.UNREACHABLE, outcome)
    }

    @Test
    fun `a listing that hangs is given up after its bound`() = runTest {
        val drive = FakeDrive(pending = { awaitCancellation() })

        val outcome = waitForDriveCopies(MlDriveFileKind.FACES, drive, listTimeoutMs = 5_000L)

        assertEquals(FirstScanWait.UNREACHABLE, outcome)
        assertEquals(0, drive.roundsAsked)
    }

    @Test
    fun `a round that never starts lets the scan start after the start bound`() = runTest {
        val drive = FakeDrive(pending = { setOf(MlDriveFileKind.FACES) }, roundStart = { awaitCancellation() })

        val outcome = waitForDriveCopies(MlDriveFileKind.FACES, drive, startTimeoutMs = 120_000L)

        assertEquals(FirstScanWait.ROUND_NOT_STARTED, outcome)
        assertEquals(1, drive.roundsAsked)
    }

    @Test
    fun `a running round longer than the start bound is still waited for`() = runTest {
        val drive = FakeDrive(pending = { setOf(MlDriveFileKind.FACES) }, roundEnd = { delay(600_000L) })

        val outcome = waitForDriveCopies(
            MlDriveFileKind.FACES, drive, startTimeoutMs = 120_000L, runTimeoutMs = 1_800_000L,
        )

        assertEquals(FirstScanWait.TOOK_IN, outcome)
    }

    @Test
    fun `a round past its run bound lets the scan start`() = runTest {
        val drive = FakeDrive(pending = { setOf(MlDriveFileKind.FACES) }, roundEnd = { awaitCancellation() })

        val outcome = waitForDriveCopies(MlDriveFileKind.FACES, drive, runTimeoutMs = 1_800_000L)

        assertEquals(FirstScanWait.ROUND_TOO_LONG, outcome)
    }
}
