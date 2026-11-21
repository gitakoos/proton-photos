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

package eu.akoos.photos.presentation.common

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Space kept between a raised snackbar and the top of the bar it clears. */
private val SnackbarBarGap = 8.dp

/**
 * The tappable bars along the bottom of the window that a snackbar must not cover: the viewer's
 * controls, the dock, the selection drawer, a jump-to-top pill. Each one reports how far its top
 * edge sits above the window's bottom edge, and a snackbar placed with [snackbarAboveBars] keeps
 * clear of the tallest. A bar that leaves the screen takes its report with it.
 */
@Stable
class SnackbarClearance {
    private val bars = mutableStateMapOf<Any, Int>()

    /** The highest bar's top, in pixels above the window's bottom edge; 0 with no bar on screen. */
    val obstaclePx: Int get() = bars.values.maxOrNull() ?: 0

    internal fun report(key: Any, px: Int) {
        if (bars[key] != px) bars[key] = px
    }

    internal fun clear(key: Any) {
        bars.remove(key)
    }
}

/** Provided at the navigation root, so every screen's bars and every snackbar share one [SnackbarClearance]. */
val LocalSnackbarClearance = staticCompositionLocalOf<SnackbarClearance?> { null }

/**
 * How far a bar whose top edge sits at [barTopPx] reaches up from the bottom of a window
 * [rootHeightPx] tall. A bar laid out entirely below the window reaches nothing.
 */
internal fun barHeightFromBottom(rootHeightPx: Int, barTopPx: Float): Int =
    (rootHeightPx - barTopPx).toInt().coerceIn(0, rootHeightPx.coerceAtLeast(0))

/**
 * The snackbar's bottom padding: its usual [restingPx] spot, raised to [gapPx] above the highest
 * tappable bar ([obstaclePx], 0 when none is shown) whenever the usual spot would put it on that bar.
 */
internal fun snackbarBottomPaddingPx(restingPx: Int, obstaclePx: Int, gapPx: Int): Int =
    if (obstaclePx <= 0) restingPx else maxOf(restingPx, obstaclePx + gapPx)

/**
 * Marks a tappable bar along the bottom of the screen, so a snackbar placed with [snackbarAboveBars]
 * sits above it rather than over it. Goes on the bar's own content (inside any AnimatedVisibility),
 * so a hidden bar stops counting the moment it leaves the composition.
 */
fun Modifier.snackbarObstacle(): Modifier = composed {
    val clearance = LocalSnackbarClearance.current
    if (clearance == null) {
        Modifier
    } else {
        val key = remember { Any() }
        DisposableEffect(clearance, key) { onDispose { clearance.clear(key) } }
        Modifier.onGloballyPositioned { coordinates ->
            val rootHeight = coordinates.findRootCoordinates().size.height
            clearance.report(key, barHeightFromBottom(rootHeight, coordinates.boundsInRoot().top))
        }
    }
}

/**
 * Bottom placement for a snackbar host that fills to the window's bottom edge: [resting] above the
 * navigation bar, raised over any bar marked with [snackbarObstacle] that is on screen, and eased
 * back down when that bar hides. Replaces `navigationBarsPadding().padding(bottom = resting)`.
 */
fun Modifier.snackbarAboveBars(resting: Dp = 24.dp): Modifier = composed {
    val density = LocalDensity.current
    val clearance = LocalSnackbarClearance.current
    val restingPx = WindowInsets.navigationBars.getBottom(density) + with(density) { resting.roundToPx() }
    val target = snackbarBottomPaddingPx(
        restingPx = restingPx,
        obstaclePx = clearance?.obstaclePx ?: 0,
        gapPx = with(density) { SnackbarBarGap.roundToPx() },
    )
    val bottomPx by animateIntAsState(target, tween(200), label = "snackbarAboveBars")
    Modifier.padding(bottom = with(density) { bottomPx.toDp() })
}
