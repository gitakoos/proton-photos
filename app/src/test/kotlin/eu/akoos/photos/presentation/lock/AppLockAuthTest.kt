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

package eu.akoos.photos.presentation.lock

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app lock prompt's authenticators per API level ([appLockAuthenticators]), which settings changes
 * need an authentication first ([appLockChangeNeedsAuth]), and the setup wizard's lock step
 * ([appLockWizardStart], [appLockWizardWrite]).
 */
class AppLockAuthTest {

    private val fiveMinutes = 5 * 60_000L
    private val on = AppLockSettings(enabled = true, timeoutMs = fiveMinutes)
    private val off = AppLockSettings(enabled = false, timeoutMs = fiveMinutes)

    // appLockAuthenticators

    @Test
    fun `Android 8 takes any biometric or the device credential`() {
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, appLockAuthenticators(26))
    }

    @Test
    fun `Android 9 and 10 take any biometric or the device credential instead of the strong pair`() {
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, appLockAuthenticators(28))
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, appLockAuthenticators(29))
    }

    @Test
    fun `Android 11 and later take a strong biometric or the device credential`() {
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, appLockAuthenticators(30))
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, appLockAuthenticators(34))
    }

    @Test
    fun `every API level from the minimum SDK accepts the device credential`() {
        for (sdk in 26..36) {
            assertEquals("API $sdk", DEVICE_CREDENTIAL, appLockAuthenticators(sdk) and DEVICE_CREDENTIAL)
        }
    }

    // appLockChangeNeedsAuth

    @Test
    fun `turning the lock off needs authentication`() {
        assertTrue(appLockChangeNeedsAuth(on, off))
        assertTrue(appLockChangeNeedsAuth(on, off.copy(timeoutMs = 0L)))
    }

    @Test
    fun `turning the lock on needs none`() {
        assertFalse(appLockChangeNeedsAuth(off, on))
        assertFalse(appLockChangeNeedsAuth(off, on.copy(timeoutMs = 60 * 60_000L)))
    }

    @Test
    fun `a longer timeout needs authentication`() {
        assertTrue(appLockChangeNeedsAuth(on, on.copy(timeoutMs = fiveMinutes + 1)))
        assertTrue(appLockChangeNeedsAuth(on.copy(timeoutMs = 0L), on))
    }

    @Test
    fun `a shorter or equal timeout needs none`() {
        assertFalse(appLockChangeNeedsAuth(on, on.copy(timeoutMs = fiveMinutes - 1)))
        assertFalse(appLockChangeNeedsAuth(on, on.copy(timeoutMs = 0L)))
        assertFalse(appLockChangeNeedsAuth(on, on))
    }

    @Test
    fun `no change needs authentication while the lock is off`() {
        assertFalse(appLockChangeNeedsAuth(off, off.copy(timeoutMs = 60 * 60_000L)))
        assertFalse(appLockChangeNeedsAuth(off, off))
    }

    // appLockWizardStart

    private val wizardDefault = WIZARD_LOCK_TIMEOUT_SECONDS * 1000L

    @Test
    fun `the wizard starts from an enabled lock as it is`() {
        assertEquals(on, appLockWizardStart(on))
        assertEquals(on.copy(timeoutMs = 0L), appLockWizardStart(on.copy(timeoutMs = 0L)))
        assertEquals(on.copy(timeoutMs = 1_800_000L), appLockWizardStart(on.copy(timeoutMs = 1_800_000L)))
    }

    @Test
    fun `the wizard starts a lock that is off with its own timeout`() {
        assertEquals(AppLockSettings(enabled = false, timeoutMs = wizardDefault), appLockWizardStart(off))
        assertEquals(
            AppLockSettings(enabled = false, timeoutMs = wizardDefault),
            appLockWizardStart(off.copy(timeoutMs = 0L)),
        )
    }

    // appLockWizardWrite

    @Test
    fun `walking through the wizard without a change writes nothing`() {
        assertEquals(AppLockWizardWrite.Keep, appLockWizardWrite(on, appLockWizardStart(on)))
        val immediately = on.copy(timeoutMs = 0L)
        assertEquals(AppLockWizardWrite.Keep, appLockWizardWrite(immediately, appLockWizardStart(immediately)))
    }

    @Test
    fun `switching an enabled lock off in the wizard needs authentication`() {
        assertEquals(AppLockWizardWrite.WriteAfterAuth, appLockWizardWrite(on, off))
        // The step's old fixed start, off with five minutes, over a lock set to lock immediately.
        val oldStart = AppLockSettings(enabled = false, timeoutMs = wizardDefault)
        assertEquals(AppLockWizardWrite.WriteAfterAuth, appLockWizardWrite(on.copy(timeoutMs = 0L), oldStart))
    }

    @Test
    fun `a longer timeout in the wizard needs authentication`() {
        val immediately = on.copy(timeoutMs = 0L)
        assertEquals(AppLockWizardWrite.WriteAfterAuth, appLockWizardWrite(immediately, on))
        assertEquals(AppLockWizardWrite.WriteAfterAuth, appLockWizardWrite(on, on.copy(timeoutMs = 60 * 60_000L)))
    }

    @Test
    fun `turning the lock on or shortening it in the wizard writes without a prompt`() {
        assertEquals(AppLockWizardWrite.Write, appLockWizardWrite(off, on))
        assertEquals(AppLockWizardWrite.Write, appLockWizardWrite(appLockWizardStart(off), on))
        assertEquals(AppLockWizardWrite.Write, appLockWizardWrite(on, on.copy(timeoutMs = 0L)))
    }

    @Test
    fun `a timeout change while the lock stays off writes without a prompt`() {
        assertEquals(AppLockWizardWrite.Write, appLockWizardWrite(off.copy(timeoutMs = 0L), appLockWizardStart(off)))
        assertEquals(AppLockWizardWrite.Keep, appLockWizardWrite(appLockWizardStart(off), appLockWizardStart(off)))
    }
}
