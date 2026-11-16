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

package androidx.biometric

import android.annotation.SuppressLint
import androidx.fragment.app.FragmentActivity

/**
 * Debug build only. Finishes the BiometricPrompt waiting on [activity] as a successful device-credential
 * authentication, so the prompt's own success callback runs, then closes the system dialog. Only a prompt
 * titled [title] is touched. Returns false when no such prompt is waiting for a result.
 * Reaches the library's own prompt state on purpose, hence the RestrictedApi suppression.
 */
@SuppressLint("RestrictedApi")
object DebugPromptCompleter {
    fun complete(activity: FragmentActivity, title: String): Boolean {
        val fragment = activity.supportFragmentManager
            .findFragmentByTag(BiometricPrompt.BIOMETRIC_FRAGMENT_TAG) as? BiometricFragment ?: return false
        val model = fragment.viewModel ?: return false
        if (!model.isAwaitingResult || model.title?.toString() != title) return false
        fragment.onAuthenticationSucceeded(
            BiometricPrompt.AuthenticationResult(null, BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL),
        )
        // The result is already delivered, so the cancel error that follows is not sent to the client.
        fragment.cancelAuthentication(BiometricFragment.CANCELED_FROM_CLIENT)
        return true
    }
}
