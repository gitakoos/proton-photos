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

package eu.akoos.photos.hiddenunlock

import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.DebugPromptCompleter
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DebugPromptCompleterTest {

    private var successes = 0
    private var errors = 0

    private fun showPrompt(activity: FragmentActivity, title: String) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { successes++ }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { errors++ }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `no prompt waiting leaves nothing to complete`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        assertFalse(DebugPromptCompleter.complete(activity, "Hidden"))
    }

    @Test
    fun `a prompt with another title is left alone`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        showPrompt(activity, "App lock")
        assertFalse(DebugPromptCompleter.complete(activity, "Hidden"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, successes)
    }

    @Test
    fun `the waiting prompt reports one success and no error`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        showPrompt(activity, "Hidden")
        assertTrue(DebugPromptCompleter.complete(activity, "Hidden"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, successes)
        assertEquals(0, errors)
        assertFalse(DebugPromptCompleter.complete(activity, "Hidden"))
    }
}
