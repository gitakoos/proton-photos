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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions the background release check turns on, extracted as pure functions so they can
 * be checked without WorkManager, DataStore or a device.
 *
 * [shouldNotifyForUpdate] is what separates a helpful notice from a recurring one: the check runs
 * on a fixed cadence and finds the same release every single time, so without the persisted marker
 * the user is told about one version over and over for as long as they put off installing it.
 *
 * [stagedUpdateMatches] guards the pre-downloaded archive. It is written by one process and read by
 * another, potentially days later, so the version it was recorded against is the only thing proving
 * the bytes still belong to the update on offer.
 */
class UpdateCheckDecisionsTest {

    @Test
    fun `a newly found version notifies`() {
        assertTrue(shouldNotifyForUpdate(availableVersion = "2.4.1", lastNotifiedVersion = null))
    }

    @Test
    fun `the same version does not notify twice`() {
        assertFalse(shouldNotifyForUpdate(availableVersion = "2.4.1", lastNotifiedVersion = "2.4.1"))
    }

    @Test
    fun `a different version notifies again`() {
        // Installing is what normally ends the reminder, but a release published on top of one the
        // user ignored has to break the silence rather than inherit it.
        assertTrue(shouldNotifyForUpdate(availableVersion = "2.4.2", lastNotifiedVersion = "2.4.1"))
    }

    @Test
    fun `nothing available never notifies`() {
        assertFalse(shouldNotifyForUpdate(availableVersion = null, lastNotifiedVersion = null))
        assertFalse(shouldNotifyForUpdate(availableVersion = null, lastNotifiedVersion = "2.4.1"))
        assertFalse(shouldNotifyForUpdate(availableVersion = "", lastNotifiedVersion = null))
        assertFalse(shouldNotifyForUpdate(availableVersion = "   ", lastNotifiedVersion = null))
    }

    @Test
    fun `a staged archive for the offered version is usable`() {
        assertTrue(stagedUpdateMatches(stagedVersion = "2.4.1", availableVersion = "2.4.1"))
    }

    @Test
    fun `a staged archive for a superseded version is not usable`() {
        assertFalse(stagedUpdateMatches(stagedVersion = "2.4.0", availableVersion = "2.4.1"))
    }

    @Test
    fun `nothing staged is not usable`() {
        assertFalse(stagedUpdateMatches(stagedVersion = null, availableVersion = "2.4.1"))
        assertFalse(stagedUpdateMatches(stagedVersion = "", availableVersion = "2.4.1"))
    }

    @Test
    fun `a staged archive with nothing on offer is not usable`() {
        // The app is up to date, so whatever sits in the cache is a leftover to delete.
        assertFalse(stagedUpdateMatches(stagedVersion = "2.4.1", availableVersion = null))
        assertFalse(stagedUpdateMatches(stagedVersion = null, availableVersion = null))
    }
}
