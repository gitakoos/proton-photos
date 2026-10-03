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

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import eu.akoos.photos.R
import eu.akoos.photos.presentation.util.findFragmentActivity

/**
 * The authenticators the app lock prompt accepts on API level [sdkInt]: a biometric, or the device PIN,
 * pattern or password. On Android 11 (API 30) and later the biometric has to be Class 3 (strong).
 * androidx.biometric rejects that strong pair on API 28 and 29, in its availability check and in its prompt
 * builder alike, which leaves a secured device there with no prompt at all, so below API 30 any enrolled
 * biometric counts: the set the deprecated `setDeviceCredentialAllowed` stands for, which the hidden
 * album's prompt uses too.
 */
internal fun appLockAuthenticators(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.R) {
        BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    } else {
        BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }

/**
 * Whether changing the app lock from [current] to [next] weakens it, and so needs a successful
 * authentication first: turning the lock off, or a longer timeout while it stays on. Turning it on and a
 * shorter or equal timeout need none, and while the lock is off nothing is guarded, so no change does.
 */
internal fun appLockChangeNeedsAuth(current: AppLockSettings, next: AppLockSettings): Boolean =
    current.enabled && (!next.enabled || next.timeoutMs > current.timeoutMs)

/** The timeout the setup wizard offers while the lock is off: five minutes. */
internal const val WIZARD_LOCK_TIMEOUT_SECONDS = 300

/**
 * The setup wizard's app lock step starts from the stored [current] settings, so walking through the wizard
 * keeps the lock as it is. While the lock is off its timeout is not in effect, and the step offers its own.
 */
internal fun appLockWizardStart(current: AppLockSettings): AppLockSettings =
    if (current.enabled) current else current.copy(timeoutMs = WIZARD_LOCK_TIMEOUT_SECONDS * 1000L)

/** What the setup wizard does with its app lock choice; see [appLockWizardWrite]. */
internal enum class AppLockWizardWrite {
    /** The choice is the stored settings: nothing to write. */
    Keep,

    /** The choice keeps or strengthens the lock: written with the rest of the wizard. */
    Write,

    /** The choice weakens the lock: the wizard finishes only after a successful [showAppLockPrompt]. */
    WriteAfterAuth,
}

/**
 * The setup wizard's app lock choice [chosen] against the stored [current] settings, by the rule Settings
 * applies ([appLockChangeNeedsAuth]): weakening the lock needs a successful authentication first.
 */
internal fun appLockWizardWrite(current: AppLockSettings, chosen: AppLockSettings): AppLockWizardWrite = when {
    chosen == current -> AppLockWizardWrite.Keep
    appLockChangeNeedsAuth(current, chosen) -> AppLockWizardWrite.WriteAfterAuth
    else -> AppLockWizardWrite.Write
}

/**
 * Shows the app lock prompt and runs [onSuccess] once the user authenticates with a biometric or the
 * device credential ([appLockAuthenticators]). A cancelled prompt, an error, or a prompt that cannot show
 * runs nothing, so the caller stays as it is and can ask again. A biometric that does not match keeps the
 * prompt up for another try.
 *
 * A device with no secure lock at all runs [onSuccess] at once: there is nothing to authenticate against,
 * and refusing would lock the user out of the app. A secured device always gets the prompt, even when the
 * availability check fails, since its credential can still answer it.
 */
internal fun showAppLockPrompt(context: Context, onSuccess: () -> Unit) {
    // A prompt the system dropped while the app was away can leave the library believing one is still up,
    // which makes a new request do nothing; the old one is closed first so the Unlock button always works.
    cancelAppLockPrompt()
    // The composition's context is wrapped for the in-app language, so the activity is found by walking it.
    val activity = context.findFragmentActivity() ?: return
    val authenticators = appLockAuthenticators(Build.VERSION.SDK_INT)
    val available = runCatching { BiometricManager.from(activity).canAuthenticate(authenticators) }
        .getOrNull() == BiometricManager.BIOMETRIC_SUCCESS
    if (!available && activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
        onSuccess()
        return
    }
    // From Android 11 the system's own prompt is asked directly: every request is a fresh one, with no
    // wrapper state a dismissed prompt could leave behind, so the Unlock button brings it up each time.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        showSystemPrompt(activity, authenticators, onSuccess)
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                activePrompt = null
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                activePrompt = null
            }
        },
    )
    activePrompt = prompt
    // A prompt the platform refuses to build or start fails closed: onSuccess never runs.
    runCatching {
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.app_name))
                .setDescription(context.getString(R.string.app_lock_authenticate_prompt))
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }
}

/** The prompt on screen, if any, so a later request or the app going away can close it. */
private var activePrompt: BiometricPrompt? = null

/** The system prompt on screen, if any, so a later request or the app going away can close it. */
private var activeSignal: CancellationSignal? = null

/** Closes the app lock prompt if one is up. */
internal fun cancelAppLockPrompt() {
    activeSignal?.let { signal ->
        activeSignal = null
        runCatching { signal.cancel() }
    }
    val prompt = activePrompt ?: return
    activePrompt = null
    runCatching { prompt.cancelAuthentication() }
}

/** The platform's biometric prompt, asked for directly. Like the library path, a cancelled prompt or an
 *  error runs nothing, and one the platform refuses to build or start fails closed. */
@RequiresApi(Build.VERSION_CODES.R)
private fun showSystemPrompt(context: Context, authenticators: Int, onSuccess: () -> Unit) {
    val signal = CancellationSignal()
    activeSignal = signal
    runCatching {
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(context)
            .setTitle(context.getString(R.string.app_name))
            .setDescription(context.getString(R.string.app_lock_authenticate_prompt))
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(
            signal,
            ContextCompat.getMainExecutor(context),
            object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                    if (activeSignal === signal) activeSignal = null
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (activeSignal === signal) activeSignal = null
                }
            },
        )
    }.onFailure { if (activeSignal === signal) activeSignal = null }
}
