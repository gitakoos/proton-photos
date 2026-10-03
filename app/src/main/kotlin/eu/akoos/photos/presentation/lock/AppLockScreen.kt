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

import androidx.compose.ui.input.pointer.changedToUp
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.SecureScreenEffect
import eu.akoos.photos.presentation.util.findActivity
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * [unlockFocusRequester] is attached to the unlock button, so keyboard focus that the host keeps out of
 * the content behind the lock can be handed to it.
 */
@Composable
fun AppLockScreen(onUnlocked: () -> Unit, unlockFocusRequester: FocusRequester? = null) {
    val context = LocalContext.current

    LockSecureFlagEffect()

    LaunchedEffect(Unit) {
        showAppLockPrompt(context, onSuccess = onUnlocked)
    }
    // The system drops the prompt when the app goes to the background; closing it here as well resets
    // its state, so the Unlock button brings a fresh one up on return.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) cancelAppLockPrompt()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cancelAppLockPrompt()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0)
            // A press anywhere on the lock asks again, besides the button's own: the prompt must always
            // be one tap away once it has been dismissed, and nothing about the button's own handling
            // may stand in its way.
            .swallowPointerInput(
                onTap = { showAppLockPrompt(context, onSuccess = onUnlocked) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(PillBg, CircleShape)
                    .border(0.5.dp, PillBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.app_name),
                color = FgPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.app_lock_authenticate_prompt),
                color = FgDim,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(32.dp))
            Box(
                modifier = Modifier
                    .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                    .border(0.5.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                    .then(unlockFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .clickable { showAppLockPrompt(context, onSuccess = onUnlocked) }
                    .padding(horizontal = 32.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.hidden_photos_unlock), color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Opaque cover with no prompt, for while it is not yet known whether the lock is on. */
@Composable
fun AppLockCover() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0)
            .swallowPointerInput(),
    )
}

/**
 * Consumes every gesture so a cover drawn as an overlay over the nav graph cannot leak a tap or scroll
 * to the content behind it. A child's own clicks (the unlock button) still run first, so this only
 * swallows presses on the empty backdrop.
 */
private fun Modifier.swallowPointerInput(onTap: (() -> Unit)? = null): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            // A release nothing below has handled is a tap on the backdrop.
            if (onTap != null && event.changes.any { it.changedToUp() && !it.isConsumed }) onTap()
            event.changes.forEach { it.consume() }
        }
    }
}

/** FLAG_SECURE while the lock shows, put back on exit the way the lock found it ([SecureFlagHold]). */
@Composable
private fun LockSecureFlagEffect() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val hold = activity?.window?.let { SecureFlagHold.acquire(it) }
        onDispose { hold?.release() }
    }
}

/**
 * FLAG_SECURE held on a window and put back the way the hold found it: [release] clears the flag only when
 * the hold set it itself ([ownsSecureFlag]). The shared [SecureScreenEffect] clears the flag outright, which
 * would strip it from a vault screen still open under the lock.
 */
internal class SecureFlagHold private constructor(private val window: Window, private val owned: Boolean) {

    fun release() {
        if (owned) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    companion object {
        fun acquire(window: Window): SecureFlagHold {
            val hold = SecureFlagHold(window, owned = ownsSecureFlag(window.attributes.flags))
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            return hold
        }
    }
}

/** Whether a hold that finds a window's [windowFlags] sets FLAG_SECURE itself, and so clears it again. */
internal fun ownsSecureFlag(windowFlags: Int): Boolean =
    (windowFlags and WindowManager.LayoutParams.FLAG_SECURE) == 0
