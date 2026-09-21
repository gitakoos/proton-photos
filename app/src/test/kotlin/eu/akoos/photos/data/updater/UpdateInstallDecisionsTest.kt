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

package eu.akoos.photos.data.updater

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions the silent self-update turns on, extracted as pure functions so they can be
 * checked without a PackageManager, a session or a device.
 *
 * [isCandidateNewer] is the guard that stops a downgrade reaching the installer, where Android
 * would reject it with an opaque error instead of a message the app can explain. Preview builds
 * burn version codes, so a tester can genuinely end up with a downloaded APK that ranks at or
 * below what is running.
 *
 * [verdictForStatus] maps a committed session's status int. Only the two documented good values
 * are recognised; everything else has to read as a failure so an unrecognised status from a
 * future Android release can never be mistaken for a completed install.
 */
class UpdateInstallDecisionsTest {

    @Test
    fun `a higher version code installs`() {
        assertTrue(isCandidateNewer(installedVersionCode = 257L, candidateVersionCode = 258L))
    }

    @Test
    fun `an equal version code is refused`() {
        assertFalse(isCandidateNewer(installedVersionCode = 257L, candidateVersionCode = 257L))
    }

    @Test
    fun `a lower version code is refused`() {
        assertFalse(isCandidateNewer(installedVersionCode = 257L, candidateVersionCode = 242L))
    }

    @Test
    fun `version codes compare across the int boundary`() {
        // getLongVersionCode folds versionCodeMajor into the high word, so the comparison has to
        // stay 64-bit rather than silently wrapping.
        assertTrue(
            isCandidateNewer(
                installedVersionCode = Int.MAX_VALUE.toLong(),
                candidateVersionCode = Int.MAX_VALUE.toLong() + 1L,
            ),
        )
        assertFalse(
            isCandidateNewer(
                installedVersionCode = Int.MAX_VALUE.toLong() + 1L,
                candidateVersionCode = Int.MAX_VALUE.toLong(),
            ),
        )
    }

    @Test
    fun `a pending user action is recognised`() {
        assertEquals(
            InstallStatusVerdict.PENDING_USER_ACTION,
            verdictForStatus(PackageInstaller.STATUS_PENDING_USER_ACTION),
        )
    }

    @Test
    fun `success is recognised`() {
        assertEquals(
            InstallStatusVerdict.SUCCESS,
            verdictForStatus(PackageInstaller.STATUS_SUCCESS),
        )
    }

    @Test
    fun `a blocked install is a failure`() {
        assertEquals(
            InstallStatusVerdict.FAILURE,
            verdictForStatus(PackageInstaller.STATUS_FAILURE_BLOCKED),
        )
    }

    @Test
    fun `a conflicting install is a failure`() {
        assertEquals(
            InstallStatusVerdict.FAILURE,
            verdictForStatus(PackageInstaller.STATUS_FAILURE_CONFLICT),
        )
    }

    @Test
    fun `an aborted install is a failure`() {
        assertEquals(
            InstallStatusVerdict.FAILURE,
            verdictForStatus(PackageInstaller.STATUS_FAILURE_ABORTED),
        )
    }

    @Test
    fun `an unrecognised status is a failure, never a success`() {
        assertEquals(InstallStatusVerdict.FAILURE, verdictForStatus(Int.MIN_VALUE))
        assertEquals(InstallStatusVerdict.FAILURE, verdictForStatus(Int.MAX_VALUE))
    }
}
