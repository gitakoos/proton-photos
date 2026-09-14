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

package eu.akoos.photos.presentation.editor.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.TrackBg

// ─── Video filmstrip + playhead trim slider ──────────────────────────────────

/**
 * Trim widget that looks and feels like a tiny video editor: a strip of evenly-spaced
 * frame thumbnails as the track, two heavy bar-shaped handles at the edges of the
 * selected range, a darker mask over the trimmed-off ends, and a thin white playhead
 * line that follows the live ExoPlayer position. Dragging the playhead seeks the
 * preview; dragging an edge handle moves the trim in/out point.
 *
 * Shared by the video editor and the GIF maker. With [allowWindowDrag] on, a drag that
 * begins inside the selection body slides the whole window at a fixed span instead of
 * scrubbing the playhead, and only a narrow zone at each edge still grabs a handle, so a
 * short selection stays draggable; with it off (the editor default) that same inside-drag
 * scrubs the playhead exactly as before.
 *
 * The [thumbnails] are extracted by the shared filmstrip hook at the screen scope and
 * passed in; the strip deliberately does not reshuffle as the trim range moves, since that
 * would re-key the frames and tank the UX.
 */
@Composable
internal fun VideoFilmstripTrimmer(
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    playheadMs: Long,
    /** Hoisted at the screen scope; this composable just renders. Hoisting prevents the
     *  strip from resetting on a tab-switch round trip since the extraction state no longer
     *  dies with this composable. */
    thumbnails: List<android.graphics.Bitmap?>,
    onTrimChange: (start: Long, end: Long) -> Unit,
    onScrubMs: (Long) -> Unit,
    /** Fired when a PLAYHEAD scrub begins / ends (not the trim handles), so the host can turn the
     *  player's scrubbing mode on for the drag and off on release. */
    onScrubStart: () -> Unit = {},
    onScrubEnd: () -> Unit = {},
    /** When true, a drag starting inside the selection body shifts the whole window at a
     *  fixed span (only a narrow zone at each edge grabs a handle, so a short selection is
     *  still draggable); when false the same drag scrubs the playhead. The video editor keeps
     *  the default (false); the GIF maker turns it on. */
    allowWindowDrag: Boolean = false,
) {
    val density = LocalDensity.current
    val handleWidthPx = with(density) { 14.dp.toPx() }
    // Generous hit-slop around each trim bar: 48dp is the platform minimum touch target
    // and it gives the user a comfortable margin to grab the start/end edges. The
    // playhead is only picked up when the touch is well inside the trimmed window so
    // the bars always win when the gesture starts near an edge.
    val touchSlopPx = with(density) { 48.dp.toPx() }
    // Window-drag mode only: a NARROW edge zone, much tighter than the 48dp handle slop
    // above, so the selection BODY (not just a sliver at its centre) grabs the whole
    // window. This is what lets a short selection be slid along the bar: with the wide
    // slop, a short window sits entirely within one edge's slop and a window grab never
    // wins. The handle zones also reach this far OUTSIDE each edge so a narrow window's
    // handles stay reachable from just past its edges.
    val edgeGrabPx = with(density) { 20.dp.toPx() }
    val stripHeight = 64.dp

    // Thumbnails are passed in from the screen scope so they survive activeTool tab
    // swaps; the shared hook caches the finished strip, so a swap back reads a warm strip
    // instead of re-running the extraction.

    var grabbed by remember { mutableStateOf<Grabbed?>(null) }
    var canvasWidthPx by remember { mutableFloatStateOf(1f) }
    // Window-drag anchors, captured at grab time so a whole-window shift is derived from the
    // total pointer displacement rather than per-event deltas (several drag events can land
    // within one recomposition, which would drop accumulated deltas and make the window lag).
    var windowAnchorX by remember { mutableFloatStateOf(0f) }
    var windowStartAtGrab by remember { mutableStateOf(0L) }
    var windowEndAtGrab by remember { mutableStateOf(0L) }
    // Capture composable colors out of the Canvas draw scope (Canvas's body is *not*
    // composable, so we can't read Accent there).
    val accentColor = Accent

    // pointerInput's lambda captures its closure ONCE per key change: recompositions
    // don't refresh the captured props. Without these State proxies the gesture handlers
    // see stale `trimStartMs`/`trimEndMs` after the first drag: the user trims start to
    // 5 s, releases, then taps the bar to drag it back, but the picker still thinks
    // start is at x=0 (stale) and routes the touch to Playhead instead of Start. Using
    // a State<Long> reference whose `value` is always the latest snapshot fixes that
    // without re-keying the gesture loop (which would restart drags mid-motion).
    val latestTrimStart by androidx.compose.runtime.rememberUpdatedState(trimStartMs)
    val latestTrimEnd by androidx.compose.runtime.rememberUpdatedState(trimEndMs)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(stripHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(TrackBg)
            // Same reason as the range slider: the two edge bars reach the display's gesture
            // strips, and a 64dp strip is cheap to exclude whole.
            .systemGestureExclusion()
            .onSizeChanged { canvasWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            // Key on `durationMs` only: including the trim values here would restart
            // the gesture pipeline on every drag step (the user types a tiny drag ->
            // onTrimChange fires -> trim* updates -> pointerInput resets -> user has to
            // release-and-regrab to keep dragging). The handlers read latestTrimStart /
            // latestTrimEnd via rememberUpdatedState so they always see fresh values
            // without restarting the gesture loop.
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val startX = latestTrimStart.toFloat() / durationMs * w
                        val endX = latestTrimEnd.toFloat() / durationMs * w
                        val dStart = kotlin.math.abs(offset.x - startX)
                        val dEnd = kotlin.math.abs(offset.x - endX)
                        grabbed = if (allowWindowDrag) {
                            // Window-drag mode (the GIF maker). The selection BODY grabs the
                            // whole window so even a short selection can be slid along the bar
                            // as one; only the narrow edgeGrabPx zone at each bar grabs a handle.
                            // Those handle zones also reach edgeGrabPx OUTSIDE each edge, so a
                            // window narrower than two edge zones still exposes its handles by
                            // pressing just past its edges.
                            val center = (startX + endX) / 2f
                            val insideWindow = offset.x > startX && offset.x < endX
                            when {
                                // Just outside the start / end edge, within the narrow zone.
                                offset.x <= startX && offset.x >= startX - edgeGrabPx -> Grabbed.Start
                                offset.x >= endX && offset.x <= endX + edgeGrabPx -> Grabbed.End
                                // Inside the window, biased to Window near the centre so a very
                                // short window (whose two edge zones overlap) is always movable.
                                insideWindow && kotlin.math.abs(offset.x - center) <= edgeGrabPx -> Grabbed.Window
                                // Inside but hugging an edge (only reached on a wider window).
                                insideWindow && offset.x - startX <= edgeGrabPx -> Grabbed.Start
                                insideWindow && endX - offset.x <= edgeGrabPx -> Grabbed.End
                                // Anywhere else in the body: slide the whole window.
                                insideWindow -> Grabbed.Window
                                // Outside the selection entirely: no-op, as the editor does.
                                else -> null
                            }
                        } else {
                            // Editor default (unchanged): edge bars win inside their hit-slop even
                            // from within the active range, whichever bar is closer; a touch well
                            // clear of either bar scrubs the playhead.
                            when {
                                dStart < touchSlopPx && dStart <= dEnd -> Grabbed.Start
                                dEnd < touchSlopPx && dEnd < dStart -> Grabbed.End
                                offset.x > startX + touchSlopPx && offset.x < endX - touchSlopPx -> Grabbed.Playhead
                                else -> null
                            }
                        }
                        if (grabbed != null) onScrubStart()
                        if (grabbed == Grabbed.Playhead) {
                            val pct = (offset.x / w).coerceIn(0f, 1f)
                            onScrubMs((pct * durationMs).toLong().coerceIn(latestTrimStart, latestTrimEnd))
                        }
                        if (grabbed == Grabbed.Window) {
                            // Anchor the shift to the grab point and the window at grab time.
                            windowAnchorX = offset.x
                            windowStartAtGrab = latestTrimStart
                            windowEndAtGrab = latestTrimEnd
                        }
                    },
                    onDrag = { change, _ ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val pct = (change.position.x / w).coerceIn(0f, 1f)
                        val ms = (pct * durationMs).toLong()
                        when (grabbed) {
                            // Seek the preview to the edge being dragged so the boundary frame is
                            // visible as the handle moves.
                            Grabbed.Start -> { onTrimChange(ms, latestTrimEnd); onScrubMs(ms) }
                            Grabbed.End -> { onTrimChange(latestTrimStart, ms); onScrubMs(ms) }
                            Grabbed.Playhead -> onScrubMs(ms.coerceIn(latestTrimStart, latestTrimEnd))
                            // Shift both edges by the same delta, preserving the span, and clamp
                            // the whole window inside [0, durationMs].
                            Grabbed.Window -> {
                                val deltaMs = ((change.position.x - windowAnchorX) / w * durationMs).toLong()
                                val span = windowEndAtGrab - windowStartAtGrab
                                val newStart = (windowStartAtGrab + deltaMs).coerceIn(0L, durationMs - span)
                                onTrimChange(newStart, newStart + span)
                            }
                            null -> Unit
                        }
                        change.consume()
                    },
                    onDragEnd = { grabbed = null; onScrubEnd() },
                    onDragCancel = { grabbed = null; onScrubEnd() },
                )
            },
    ) {
        // Filmstrip: always render all 12 slots; arriving thumbnails fill their slot, the
        // rest stay as track-coloured placeholders. A fixed 12-slot row keeps the layout
        // from jumping (laying out only as frames arrive would resize slots from full-width
        // to halves to thirds as each lands); only the bitmap inside each slot pops in.
        val slotCount = 12
        Row(modifier = Modifier.fillMaxSize()) {
            for (i in 0 until slotCount) {
                val bmp = thumbnails.getOrNull(i)
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(modifier = Modifier.weight(1f).fillMaxSize().background(TrackBg))
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val startX = (trimStartMs.toFloat() / durationMs * w).coerceIn(0f, w)
            val endX = (trimEndMs.toFloat() / durationMs * w).coerceIn(0f, w)
            // Dim the trimmed-off portions so the active range pops visually.
            val mask = Color.Black.copy(alpha = 0.55f)
            drawRect(mask, topLeft = Offset(0f, 0f), size = GSize(startX, h))
            drawRect(mask, topLeft = Offset(endX, 0f), size = GSize(w - endX, h))
            // Accent-coloured outline around the selected range: top/bottom bars and
            // the two edge handles. The bar handles are wider than the slider thumbs so
            // a fingertip naturally lands on them without precision dragging.
            val barW = handleWidthPx
            drawRect(accentColor, topLeft = Offset(startX, 0f), size = GSize(barW, h))
            drawRect(accentColor, topLeft = Offset(endX - barW, 0f), size = GSize(barW, h))
            drawRect(accentColor, topLeft = Offset(startX, 0f), size = GSize(endX - startX, 3f))
            drawRect(accentColor, topLeft = Offset(startX, h - 3f), size = GSize(endX - startX, 3f))
            // Faint twin-stroke grip at the window centre so the selection reads as draggable
            // when window-drag is on. Short and semi-transparent so it never reads as the playhead.
            if (allowWindowDrag) {
                val gripX = (startX + endX) / 2f
                val gripH = h * 0.34f
                val gripY = (h - gripH) / 2f
                val gripColor = Color.White.copy(alpha = 0.45f)
                drawRect(gripColor, topLeft = Offset(gripX - 3f, gripY), size = GSize(1.5f, gripH))
                drawRect(gripColor, topLeft = Offset(gripX + 1.5f, gripY), size = GSize(1.5f, gripH))
            }
            // Playhead line: thin, bright, with a small triangle on top so it's spotted
            // immediately when the strip is busy.
            val playX = (playheadMs.toFloat() / durationMs * w).coerceIn(0f, w)
            drawRect(
                color = Color.White,
                topLeft = Offset(playX - 1.5f, 0f),
                size = GSize(3f, h),
            )
        }
    }
}

private enum class Grabbed { Start, End, Playhead, Window }
