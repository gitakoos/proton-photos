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

package eu.akoos.photos.presentation.viewer

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.delay

/** How long the chrome stays up over a playing video before it steps aside on its own. */
internal const val VIDEO_CHROME_AUTO_HIDE_MS = 2_500L

/**
 * How long the viewer's chrome waits over a playing video before it hides, or null to leave it up.
 *
 * Only a playing video under visible chrome hides it. The slideshow keeps its own hide, anything holding
 * the chrome open (a finger on the screen, a menu, a sheet or a dialog) keeps it where it is, and so does
 * a screen reader's explore by touch, whose user finds the controls by feel.
 */
internal fun videoChromeAutoHideDelayMs(
    chromeVisible: Boolean,
    videoPlaying: Boolean,
    slideshowRunning: Boolean,
    heldOpen: Boolean,
    touchExploration: Boolean,
): Long? =
    if (chromeVisible && videoPlaying && !slideshowRunning && !heldOpen && !touchExploration) {
        VIDEO_CHROME_AUTO_HIDE_MS
    } else {
        null
    }

/**
 * The presses on the viewer, as far as the auto-hide cares: whether a finger is down, and a count that
 * moves on every press and every lift so each one starts the wait over. Moves in between write nothing.
 */
internal class ViewerTouches {
    var fingerDown by mutableStateOf(false)
        private set
    var touchCount by mutableIntStateOf(0)
        private set

    fun onPointers(anyPressed: Boolean) {
        if (anyPressed == fingerDown) return
        fingerDown = anyPressed
        touchCount++
    }
}

@Composable
internal fun rememberViewerTouches(): ViewerTouches = remember { ViewerTouches() }

/**
 * Reports every press and lift under this node to [touches]. Read on the Initial pass, ahead of every
 * control below, and nothing is consumed, so the taps, drags and pinches underneath, the seek bar and the
 * reel included, carry on exactly as before.
 */
internal fun Modifier.observeTouches(touches: ViewerTouches): Modifier =
    pointerInput(touches) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                touches.onPointers(event.changes.any { it.pressed })
            }
        }
    }

/**
 * Hides the chrome over a playing video once it has stood for [VIDEO_CHROME_AUTO_HIDE_MS], through [onHide],
 * which sets the same flag a tap on the photo flips.
 *
 * The wait starts over on every press and lift, on a new [settledPage], and whenever playback or the chrome
 * comes or goes. It holds while a finger is down, so a slow scrub never loses its seek bar, and while the
 * viewer's window is out of focus: the overflow menu and every sheet and dialog is a window of its own that
 * takes the focus while it is up. The wait stretches to the system's accessibility timeout when one is set.
 *
 * Its own composable so a touch recomposes only this, never the whole viewer.
 */
@Composable
internal fun VideoChromeAutoHide(
    chromeVisible: Boolean,
    videoPlaying: Boolean,
    slideshowRunning: Boolean,
    settledPage: Int,
    touches: ViewerTouches,
    onHide: () -> Unit,
) {
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val touchExploration = rememberTouchExplorationEnabled()
    val accessibility = LocalAccessibilityManager.current
    val delayMs = videoChromeAutoHideDelayMs(
        chromeVisible = chromeVisible,
        videoPlaying = videoPlaying,
        slideshowRunning = slideshowRunning,
        heldOpen = touches.fingerDown || !windowFocused,
        touchExploration = touchExploration,
    )?.let { base ->
        accessibility?.calculateRecommendedTimeoutMillis(base, containsIcons = true, containsControls = true)
            ?: base
    }
    val currentOnHide by rememberUpdatedState(onHide)
    LaunchedEffect(delayMs, settledPage, touches.touchCount) {
        if (delayMs == null) return@LaunchedEffect
        delay(delayMs)
        // A press that lands ahead of the next recomposition still holds the chrome.
        if (!touches.fingerDown) currentOnHide()
    }
}

/** Whether a screen reader's explore by touch is on, kept current while the viewer is up. */
@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        enabled = manager?.isTouchExplorationEnabled == true
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
