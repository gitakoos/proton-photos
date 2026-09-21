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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.TrackBg
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

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
    // Guard the divisor: a container can report 0 duration until the player resolves the video size, and
    // ms / 0 * w yields NaN, which would poison hit-testing and the mask/handle drawing.
    @Suppress("NAME_SHADOWING")
    val durationMs = durationMs.coerceAtLeast(1L)

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
                            Grabbed.Volume, null -> Unit
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

private enum class Grabbed { Start, End, Playhead, Window, Volume }

// ─── Clip timeline (video track) ─────────────────────────────────────────────

/**
 * One laid-out clip on the timeline. The block occupies a FIXED SLOT [xL]..[xR] — the territory the clip
 * can be trimmed across in its source — and the kept sub-range [srcStart]..[srcEnd] maps to [activeXL]..
 * [activeXR] inside it, with the trimmed head/tail shown as dimmed slot space. That is what lets a start
 * trim move the LEFT (active) edge under the finger while the slot's right edge stays put, like the single
 * clip trimmer, instead of the whole block shrinking from the right. [editedStart] is the played-time
 * offset (kept clips only). Removed clips still take a slot (greyed).
 */
private class ClipBlk(
    val index: Int,
    val id: Long,
    val sourceId: Int,
    val removed: Boolean,
    val srcStart: Long,
    val srcEnd: Long,
    val slotStart: Long,
    val slotEnd: Long,
    val xL: Float,
    val xR: Float,
    val editedStart: Long,
) {
    val srcDur: Long get() = (srcEnd - srcStart).coerceAtLeast(1L)
    val slotDur: Long get() = (slotEnd - slotStart).coerceAtLeast(1L)
    val activeXL: Float get() = xL + ((srcStart.coerceIn(slotStart, slotEnd) - slotStart).toFloat() / slotDur) * (xR - xL)
    val activeXR: Float get() = xL + ((srcEnd.coerceIn(slotStart, slotEnd) - slotStart).toFloat() / slotDur) * (xR - xL)
}

/**
 * Lay the clips left-to-right in PLAY order, each occupying its OWN FIXED SLOT ([VideoClip.slotStartMs]..
 * [VideoClip.slotEndMs], the trim territory a split assigned it), at a FIXED [pxPerMs] scale. Trimming a
 * clip moves its active edge inside its slot; the slot is read from the clip itself, NOT derived from
 * sibling positions, so trimming one split half never grows another half's slot or reflows the track.
 * [sourceDurationOf] only clamps a stale/unset slot edge to the real source length. Pure geometry.
 */
private fun clipBlocks(
    clips: List<eu.akoos.photos.presentation.editor.VideoClip>,
    pxPerMs: Float,
    sourceDurationOf: (Int) -> Long,
): List<ClipBlk> {
    var accSlotMs = 0L
    var editedAcc = 0L
    return clips.mapIndexed { i, c ->
        val srcDur = sourceDurationOf(c.sourceId).coerceAtLeast(1L)
        val slotStart = c.slotStartMs.coerceIn(0L, (srcDur - 1L).coerceAtLeast(0L))
        // The slot's far edge is the clip's own stored slotEndMs (an unset default of MAX clamps to the
        // source length). maxOf keeps the range non-empty against a transient stale/short srcDur so
        // coerceIn never throws "empty range".
        val slotEnd = c.slotEndMs.coerceIn(slotStart + 1L, maxOf(srcDur, slotStart + 1L))
        val slotW = slotEnd - slotStart
        val xL = accSlotMs.toFloat() * pxPerMs
        val xR = (accSlotMs + slotW).toFloat() * pxPerMs
        val blk = ClipBlk(i, c.id, c.sourceId, c.removed, c.startMs, c.endMs, slotStart, slotEnd, xL, xR, editedAcc)
        accSlotMs += slotW
        if (!c.removed) editedAcc += c.durationMs
        blk
    }
}

/**
 * The video-editor track, CapCut-style, over an ORDERED clip list. Clips lay out left to right in play
 * order, each block its own mini trimmer (heavy accent handle bars + rails) showing its own frames
 * sliced from the shared filmstrip; a removed clip is greyed, hatched and struck (kept visible) and
 * skipped. Tap selects a clip; dragging a handle trims that clip; dragging the body scrubs (edited
 * time); a long-press then drag picks a clip up and drops it in a new slot (reorder). The preview plays
 * the clips back to back in this order, so what shows is what exports.
 */
@Composable
internal fun VideoClipTimeline(
    /** Scale reference: the primary video's full duration. The scale is viewport / this, so the original
     *  video fills the screen once, and a duplicate or an added video keeps that same per-ms width and
     *  EXTENDS the track (scrollable) instead of being crammed in with the original. The content width
     *  itself is the sum of the clips' slot widths. */
    referenceDurationMs: Long,
    clips: List<eu.akoos.photos.presentation.editor.VideoClip>,
    playheadEditedMs: Long,
    selectedId: Long?,
    /** Per-source filmstrip: each clip slices ITS source's frames, so a second video shows its own
     *  footage. The count is per source (the primary densifies on zoom), so the draw reads each list's
     *  actual size. Returns an empty list for a source still decoding (drawn as track-coloured placeholders). */
    thumbnailsOf: (sourceId: Int) -> List<android.graphics.Bitmap?>,
    /** Per-source full duration, for mapping a clip's source range onto that source's frames. */
    sourceDurationOf: (sourceId: Int) -> Long,
    /** Fired while a clip's own start/end handle is dragged: the clip's play-order index, which edge,
     *  and the new SOURCE ms. */
    onEdgeDrag: (index: Int, isStart: Boolean, sourceMs: Long) -> Unit,
    /** Fired alongside [onEdgeDrag] with the dragged clip's play-order index, which edge, and the new
     *  SOURCE ms, so the host can seek the preview to that frame on that clip's source during the trim. */
    onEdgeSeek: (index: Int, isStart: Boolean, sourceMs: Long) -> Unit = { _, _, _ -> },
    onScrubEditedMs: (Long) -> Unit,
    onScrubStart: () -> Unit = {},
    onScrubEnd: () -> Unit = {},
    onSelect: (id: Long) -> Unit = {},
    onReorder: (from: Int, to: Int) -> Unit = { _, _ -> },
    /** While a clip is dragged for reorder: its 0..1 x fraction and play-order index, or (null, -1) when
     *  released. The host uses it to float a lifted copy above the track. */
    onReorderFraction: (fraction: Float?, index: Int) -> Unit = { _, _ -> },
    /** The added music's display name, or null when no overlay is loaded. Null renders no lane and leaves
     *  the timeline exactly as it is without music. */
    audioName: String? = null,
    /** The music block on the edited timeline: its left edge sits at [audioOffsetMs]; the played slice is
     *  [audioTrimStartMs]..[audioTrimEndMs] of the audio file, so the block's length is their difference. */
    audioOffsetMs: Long = 0L,
    audioTrimStartMs: Long = 0L,
    audioTrimEndMs: Long = 0L,
    /** Dragging the block body: the block's new LEFT edge in edited ms (its right edge and the audio under
     *  it shift with it). */
    onAudioMove: (newLeftMs: Long) -> Unit = {},
    /** Dragging the LEFT handle: the new left edge in edited ms (trims the in-point, right edge stays put). */
    onAudioTrimLeft: (newLeftMs: Long) -> Unit = {},
    /** Dragging the RIGHT handle: the new right edge in edited ms (trims the out-point). */
    onAudioTrimRight: (newRightMs: Long) -> Unit = {},
    /** Fired on music-drag start / end so the host can bracket the whole drag as one undo step. */
    onAudioEditStart: () -> Unit = {},
    onAudioEditEnd: () -> Unit = {},
    /** Reports the live pinch-zoom multiplier whenever it changes, so the host can adapt how many
     *  filmstrip frames it extracts (denser when zoomed in). The zoom itself stays internal here. */
    onZoomChange: (Float) -> Unit = {},
) {
    val density = LocalDensity.current
    val barWPx = with(density) { 12.dp.toPx() }
    val handleSlopPx = with(density) { 24.dp.toPx() }
    val gapPx = with(density) { 6.dp.toPx() }
    // A little taller than the standalone trimmer so the clips + handles are comfortable to grab.
    val stripHeight = 54.dp
    val accentColor = Accent
    val cutColor = Bg0
    val trackBgColor = TrackBg

    val viewportWidthPx = remember { mutableFloatStateOf(1f) }
    // Horizontal scroll offset in content px (0 = start). The track scrolls when the content is wider than
    // the viewport (after a duplicate / added video, or a pinch-zoom in). We own the scroll ourselves (an
    // offset + a drag) rather than Modifier.horizontalScroll, so the trim / scrub / tap gestures never
    // fight it in the gesture arena.
    val scrollOffset = remember { mutableFloatStateOf(0f) }
    // True while the user is dragging the track to scroll it: the playhead auto-scroll must not fight a
    // manual scroll (during playback the moving playhead would otherwise keep snapping the view back).
    var isUserScrolling by remember { mutableStateOf(false) }
    // Pinch-zoom multiplier on the base scale: 1 = the base fit, > 1 = zoomed in (clips stretch, scroll),
    // < 1 = zoomed out (compress to see more at once).
    val zoomFactor = remember { mutableFloatStateOf(1f) }
    // Grab zone around the thin playhead line so it can be caught and dragged to seek.
    val playheadSlopPx = with(density) { 18.dp.toPx() }
    // Live references so the Unit-keyed gesture / effect closures always see the current clips + scale +
    // source durations. The source-duration lambda especially MUST be live: a stale one (captured before
    // load, with empty sources) reports ~0 and made clipBlocks build a degenerate slot that crashed the
    // split (coerceIn on an empty range).
    val latestClips by androidx.compose.runtime.rememberUpdatedState(clips)
    val latestRef by androidx.compose.runtime.rememberUpdatedState(referenceDurationMs)
    val latestSourceDurationOf by androidx.compose.runtime.rememberUpdatedState(sourceDurationOf)
    // The drag gestures live in pointerInput(Unit), which never restarts, so it would otherwise capture the
    // first composition's onEdgeSeek — and that reads fresh clips/sources (source swap + playhead pin), so a
    // stale one breaks after any edit. Keep it fresh.
    val latestOnEdgeSeek by androidx.compose.runtime.rememberUpdatedState(onEdgeSeek)
    // onScrubEditedMs closes over the host's seekEdited + the freshly-resolved clips; captured directly in
    // pointerInput(Unit) it would seek via the PRE-EDIT clip layout (wrong frame / wrong source) after any
    // remove / reorder / trim / added source. Keep it fresh, like latestOnEdgeSeek.
    val latestOnScrub by androidx.compose.runtime.rememberUpdatedState(onScrubEditedMs)
    // playheadEditedMs is a plain Long parameter, so a snapshotFlow reading it directly emits once and
    // never again (no snapshot state read to invalidate on). Mirror it into snapshot state so the
    // auto-scroll effect actually re-fires as playback advances the playhead.
    val livePlayhead by androidx.compose.runtime.rememberUpdatedState(playheadEditedMs)
    // The music lane's gesture also lives in a pointerInput(Unit), so it reads the offset + trim and its
    // callbacks through these proxies instead of the once-captured params (same stale-capture reason).
    val latestAudioOffsetMs by androidx.compose.runtime.rememberUpdatedState(audioOffsetMs)
    val latestAudioTrimStartMs by androidx.compose.runtime.rememberUpdatedState(audioTrimStartMs)
    val latestAudioTrimEndMs by androidx.compose.runtime.rememberUpdatedState(audioTrimEndMs)
    val latestOnAudioMove by androidx.compose.runtime.rememberUpdatedState(onAudioMove)
    val latestOnAudioTrimLeft by androidx.compose.runtime.rememberUpdatedState(onAudioTrimLeft)
    val latestOnAudioTrimRight by androidx.compose.runtime.rememberUpdatedState(onAudioTrimRight)
    val latestOnAudioEditStart by androidx.compose.runtime.rememberUpdatedState(onAudioEditStart)
    val latestOnAudioEditEnd by androidx.compose.runtime.rememberUpdatedState(onAudioEditEnd)
    // Reported up on every pinch so the host can adapt the filmstrip density; read through a proxy because
    // the pinch gesture lives in a pointerInput(Unit) that closes over its callbacks once.
    val latestOnZoomChange by androidx.compose.runtime.rememberUpdatedState(onZoomChange)
    // Auto-follow: the view tracks the playhead as playback advances. A manual scroll turns it OFF so a
    // look-ahead is NOT yanked back to the playing position; it turns back ON only once playback advances
    // the playhead into the visible window again. Paused, the playhead does not move, so nothing scrolls.
    var autoFollow by remember { mutableStateOf(true) }
    var reorderGrab by remember { mutableStateOf(-1) }
    var reorderX by remember { mutableFloatStateOf(0f) }

    // Base scale: the primary video's full duration maps to the viewport once (a single video fills the
    // screen). The pinch [zoomFactor] scales it: a duplicate / added video or a zoom-in makes the content
    // wider than the screen so the track SCROLLS; a zoom-out compresses it to show more.
    fun pxPerMs(): Float =
        (viewportWidthPx.floatValue / latestRef.coerceAtLeast(1L).toFloat() * zoomFactor.floatValue)
            .coerceAtLeast(0.0001f)
    // Total width in ms is the SUM OF SLOT widths (each clip's trim territory), not the played duration, so
    // trimmed heads/tails keep their dimmed space and the content only grows as clips are added.
    fun totalSlotMs(): Long =
        clipBlocks(latestClips, 1f, latestSourceDurationOf).sumOf { it.slotDur }.coerceAtLeast(1L)
    fun contentWidthPx(): Float = totalSlotMs().toFloat() * pxPerMs()
    fun maxScrollPx(): Float = (contentWidthPx() - viewportWidthPx.floatValue).coerceAtLeast(0f)

    fun blockAt(x: Float): ClipBlk? {
        val blks = clipBlocks(latestClips, pxPerMs(), latestSourceDurationOf)
        if (blks.isEmpty()) return null
        // Clamp to the ends: a scrub dragged left of the first block (x < 0, e.g. off the left edge toward
        // the "+") must land on the FIRST clip, not fall through to lastOrNull() and jump to the last clip.
        return blks.firstOrNull { x >= it.xL && x < it.xR }
            ?: if (x < blks.first().xL) blks.first() else blks.last()
    }
    // Edited-ms under a content-x: only the ACTIVE sub-range advances edited time; the dimmed head / tail
    // snap to the clip's edited start / end.
    fun editedAt(x: Float): Long {
        val blk = blockAt(x) ?: return 0L
        if (blk.removed) return blk.editedStart
        val axl = blk.activeXL
        val axr = blk.activeXR
        if (axr <= axl) return blk.editedStart
        val frac = ((x - axl) / (axr - axl)).coerceIn(0f, 1f)
        return blk.editedStart + (frac * blk.srcDur).toLong()
    }
    // The playhead's x in CONTENT coordinates, along the ACTIVE ranges (for auto-scroll). Reads
    // livePlayhead, NOT the playheadEditedMs param: this is called from the Unit-keyed auto-scroll effect
    // and drag closures, which captured the FIRST composition's stale param — so using it froze the
    // computed x at ~0, and the auto-follow never advanced (it just kept pulling the view to the start).
    fun playheadContentX(): Float {
        val ph = livePlayhead
        val blks = clipBlocks(latestClips, pxPerMs(), latestSourceDurationOf)
        var px = 0f
        for (blk in blks) {
            if (blk.removed) continue
            if (ph <= blk.editedStart + blk.srcDur) {
                val frac = ((ph - blk.editedStart).toFloat() / blk.srcDur).coerceIn(0f, 1f)
                return blk.activeXL + frac * (blk.activeXR - blk.activeXL)
            }
            px = blk.activeXR
        }
        return px
    }

    // Whenever the content shrinks (zoom out, a removed / undone clip) pull the scroll offset back inside
    // range, so it never stays stuck past the (new, smaller) end and the start becomes reachable again.
    LaunchedEffect(Unit) {
        snapshotFlow { maxScrollPx() }.collect { maxS ->
            if (scrollOffset.floatValue > maxS) scrollOffset.floatValue = maxS
        }
    }

    // Auto-follow the playhead so an overflowing track keeps it visible during playback. Only fires when
    // the playhead actually MOVES (the poll advances it only while playing, so paused = no emission = the
    // user's scroll sticks). A manual scroll set autoFollow=false; it resumes only when playback carries
    // the playhead back into the visible window — so looking ahead is never yanked back to the start, yet
    // normal playback (and a loop back to 0) still follows.
    LaunchedEffect(Unit) {
        snapshotFlow { livePlayhead }.collect {
            val maxS = maxScrollPx()
            if (maxS <= 0f) return@collect
            val px = playheadContentX()
            val cur = scrollOffset.floatValue
            val vpW = viewportWidthPx.floatValue
            if (!autoFollow && px >= cur && px <= cur + vpW) autoFollow = true
            if (autoFollow && !isUserScrolling && (px < cur + vpW * 0.10f || px > cur + vpW * 0.90f)) {
                scrollOffset.floatValue = (px - vpW / 2f).coerceIn(0f, maxS)
            }
        }
    }

    // A music lane rides directly under the clip strip when an overlay is loaded, so the whole widget is a
    // Column: the clip strip (its own 54dp Box + gesture arena) on top, the music lane below. With no music
    // the Column holds only the clip strip and the timeline is unchanged.
    Column(modifier = Modifier.fillMaxWidth()) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(stripHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(TrackBg)
            .systemGestureExclusion()
            .onSizeChanged { viewportWidthPx.floatValue = it.width.toFloat().coerceAtLeast(1f) }
            // Pinch to zoom the scale: stretch the clips for a finer trim, or compress to see more at once.
            // We only consume once a SECOND pointer is down, so the one-finger trim / scrub / scroll / tap
            // gestures below are never disturbed.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val zc = event.calculateZoom()
                            if (zc != 1f && zc.isFinite()) {
                                val minZoom = (latestRef.toFloat() / totalSlotMs().toFloat()).coerceIn(0.25f, 1f)
                                val oldZoom = zoomFactor.floatValue
                                val newZoom = (oldZoom * zc).coerceIn(minZoom, 8f)
                                val applied = if (oldZoom > 0f) newZoom / oldZoom else 1f
                                zoomFactor.floatValue = newZoom
                                latestOnZoomChange(newZoom)
                                // Zoom around the pinch centroid: keep the content point under the fingers
                                // fixed, so it expands both ways instead of only growing rightward.
                                val cxScreen = event.calculateCentroid(useCurrent = true).x
                                if (cxScreen.isFinite()) {
                                    val contentX = cxScreen + scrollOffset.floatValue
                                    scrollOffset.floatValue = (contentX * applied - cxScreen).coerceIn(0f, maxScrollPx())
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // Tap: select the clip AND move the playhead to the tapped frame, so Split cuts where you tap.
            // The host pauses on select, so this positions the cut without the mid-playback seek stutter.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    val cx = offset.x + scrollOffset.floatValue
                    blockAt(cx)?.let { onSelect(it.id) }
                    latestOnScrub(editedAt(cx))
                })
            }
            // Long-press then DRAG picks a clip up and drops it in a new slot (reorder); a long-press
            // without moving just selects it (the delete lives in the pill above the track, so there is no
            // long-press menu). Fraction is reported viewport-relative so the host's lifted chip follows.
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val cx = offset.x + scrollOffset.floatValue
                        reorderGrab = blockAt(cx)?.index ?: -1
                        reorderX = cx
                        if (reorderGrab in latestClips.indices) {
                            onSelect(latestClips[reorderGrab].id)
                            onReorderFraction((offset.x / viewportWidthPx.floatValue).coerceIn(0f, 1f), reorderGrab)
                        }
                    },
                    onDrag = { change, _ ->
                        val cx = change.position.x + scrollOffset.floatValue
                        reorderX = cx
                        if (reorderGrab >= 0) onReorderFraction((change.position.x / viewportWidthPx.floatValue).coerceIn(0f, 1f), reorderGrab)
                        change.consume()
                    },
                    onDragEnd = {
                        val from = reorderGrab
                        val to = blockAt(reorderX)?.index ?: from
                        reorderGrab = -1
                        onReorderFraction(null, -1)
                        if (from >= 0 && to >= 0 && from != to) onReorder(from, to)
                    },
                    onDragCancel = { reorderGrab = -1; onReorderFraction(null, -1) },
                )
            }
            // Drag: trim a clip's handle (mode 1), scroll the overflowing track (mode 3), or — on a track
            // that fits — scrub the playhead (mode 2). We map pointer x to content x by adding the scroll
            // offset, and own the scroll here (offsetting the content below) rather than a nested scroller.
            .pointerInput(Unit) {
                var mode = 0
                var grabIndex = -1
                var grabIsStart = false
                var grabSrcStart = 0L
                var grabSrcEnd = 0L
                var grabX = 0f
                var lastX = 0f
                detectDragGestures(
                    onDragStart = { offset ->
                        val cx = offset.x + scrollOffset.floatValue
                        val blks = clipBlocks(latestClips, pxPerMs(), latestSourceDurationOf)
                        var pick = -1
                        var pickStart = false
                        var best = handleSlopPx
                        blks.forEach { blk ->
                            if (blk.removed) return@forEach
                            val barW = barWPx.coerceAtMost((blk.activeXR - blk.activeXL) / 2f)
                            val ds = kotlin.math.abs(cx - (blk.activeXL + barW / 2f))
                            val de = kotlin.math.abs(cx - (blk.activeXR - barW / 2f))
                            if (ds < best) { best = ds; pick = blk.index; pickStart = true }
                            if (de < best) { best = de; pick = blk.index; pickStart = false }
                        }
                        val phX = playheadContentX()
                        when {
                            pick >= 0 -> {
                                mode = 1; grabIndex = pick; grabIsStart = pickStart; grabX = cx
                                blks.getOrNull(pick)?.let { grabSrcStart = it.srcStart; grabSrcEnd = it.srcEnd }
                                onScrubStart()
                            }
                            // Grabbing the playhead line (within its slop) scrubs — even on a scrollable
                            // track, so the thin line is a real drag-to-seek handle.
                            kotlin.math.abs(cx - phX) < playheadSlopPx -> {
                                mode = 2; onScrubStart(); latestOnScrub(editedAt(cx))
                            }
                            maxScrollPx() > 1f -> { mode = 3; lastX = offset.x; isUserScrolling = true; autoFollow = false }
                            else -> { mode = 2; onScrubStart(); latestOnScrub(editedAt(cx)) }
                        }
                    },
                    onDrag = { change, _ ->
                        when (mode) {
                            1 -> {
                                val cx = change.position.x + scrollOffset.floatValue
                                val deltaMs = ((cx - grabX) / pxPerMs().coerceAtLeast(1e-6f)).toLong()
                                val newMs = if (grabIsStart) grabSrcStart + deltaMs else grabSrcEnd + deltaMs
                                onEdgeDrag(grabIndex, grabIsStart, newMs)
                                latestOnEdgeSeek(grabIndex, grabIsStart, newMs)
                            }
                            2 -> latestOnScrub(editedAt(change.position.x + scrollOffset.floatValue))
                            3 -> {
                                val dx = change.position.x - lastX
                                lastX = change.position.x
                                scrollOffset.floatValue = (scrollOffset.floatValue - dx).coerceIn(0f, maxScrollPx())
                                autoFollow = false
                            }
                        }
                        change.consume()
                    },
                    onDragEnd = { if (mode == 1 || mode == 2) onScrubEnd(); mode = 0; grabIndex = -1; isUserScrolling = false },
                    onDragCancel = { if (mode == 1 || mode == 2) onScrubEnd(); mode = 0; grabIndex = -1; isUserScrolling = false },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .width(with(density) { contentWidthPx().toDp() })
                .fillMaxHeight()
                .offset { IntOffset(-scrollOffset.floatValue.coerceIn(0f, maxScrollPx()).roundToInt(), 0) },
        ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // The track Box is clipped to this radius, so the selection outline rounds to match it.
            val cornerPx = 10.dp.toPx()
            val blks = clipBlocks(clips, pxPerMs(), latestSourceDurationOf)
            // Per-clip frames across the whole SLOT (its source territory); the trimmed head / tail are
            // dimmed just below, so the active kept range stands out while the slot edges stay fixed.
            blks.forEach { blk ->
                if (blk.xR - blk.xL < 1f) return@forEach
                val blkFrames = thumbnailsOf(blk.sourceId)
                val blkSrcDur = latestSourceDurationOf(blk.sourceId).coerceAtLeast(1L)
                // Frame count is whatever this source currently holds: the primary densifies as the user
                // pinch-zooms in (extra "+" sources keep their base count), so the timestamp spacing here
                // stays t = (i + 0.5)/n * srcDur, matching the extractor's spacing for THIS source's n. A
                // source still decoding reports an empty list; fall back to one centre slot so it still tiles.
                val n = blkFrames.size
                val inRange = if (n <= 0) emptyList() else (0 until n).filter { i ->
                    val t = ((i + 0.5f) / n * blkSrcDur).toLong()
                    t in blk.slotStart..blk.slotEnd
                }
                val idxs = inRange.ifEmpty {
                    if (n <= 0) listOf(0)
                    else listOf(((blk.slotStart + blk.slotEnd) / 2f / blkSrcDur * n).toInt().coerceIn(0, n - 1))
                }
                clipRect(left = blk.xL, top = 0f, right = blk.xR, bottom = h) {
                    val tw = (blk.xR - blk.xL) / idxs.size
                    idxs.forEachIndexed { k, i ->
                        val bmp = blkFrames.getOrNull(i)
                        val dx = blk.xL + k * tw
                        if (bmp != null) {
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = androidx.compose.ui.unit.IntOffset(dx.toInt(), 0),
                                dstSize = androidx.compose.ui.unit.IntSize(
                                    (tw + 1f).toInt().coerceAtLeast(1),
                                    h.toInt().coerceAtLeast(1),
                                ),
                            )
                        } else {
                            drawRect(trackBgColor, topLeft = Offset(dx, 0f), size = GSize(tw + 1f, h))
                        }
                    }
                }
                // Dim the trimmed head + tail (the slot space outside the active kept range).
                val axl = blk.activeXL
                val axr = blk.activeXR
                if (axl > blk.xL + 0.5f) {
                    drawRect(Color.Black.copy(alpha = 0.5f), topLeft = Offset(blk.xL, 0f), size = GSize(axl - blk.xL, h))
                }
                if (axr < blk.xR - 0.5f) {
                    drawRect(Color.Black.copy(alpha = 0.5f), topLeft = Offset(axr, 0f), size = GSize(blk.xR - axr, h))
                }
            }

            // Gaps between blocks so they read as separate clips.
            blks.forEach { blk ->
                if (blk.index == 0) return@forEach
                drawRect(cutColor, topLeft = Offset(blk.xL - gapPx / 2f, 0f), size = GSize(gapPx, h))
            }

            // Removed clips: scrim + hatch + strike (kept visible, skipped on playback).
            blks.forEach { blk ->
                if (!blk.removed) return@forEach
                val a = blk.xL
                val b = blk.xR
                if (b <= a) return@forEach
                drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(a, 0f), size = GSize(b - a, h))
                clipRect(left = a, top = 0f, right = b, bottom = h) {
                    var x = a - h
                    while (x < b) {
                        drawLine(Color.White.copy(alpha = 0.14f), Offset(x, h), Offset(x + h, 0f), strokeWidth = 1.5f)
                        x += 14f
                    }
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(a, h / 2f), Offset(b, h / 2f), strokeWidth = 2f)
                }
            }

            // Kept clips: rails + start/end handle bars + grip notches AT THE ACTIVE (kept) edges, so a
            // start-trim moves the left handle under the finger while the slot's edges stay put. The
            // selected clip is brighter, and a clip being dragged for reorder gets a translucent lift.
            blks.forEach { blk ->
                if (blk.removed) return@forEach
                val a = blk.activeXL
                val b = blk.activeXR.coerceAtLeast(a + 4f)
                val isSel = blk.id == selectedId
                val col = if (isSel) accentColor else accentColor.copy(alpha = 0.9f)
                val barW = barWPx.coerceAtMost((b - a) / 2f)
                if (blk.index == reorderGrab) {
                    drawRect(accentColor.copy(alpha = 0.28f), topLeft = Offset(a, 0f), size = GSize(b - a, h))
                }
                drawRect(col, topLeft = Offset(a, 0f), size = GSize(b - a, 3f))
                drawRect(col, topLeft = Offset(a, h - 3f), size = GSize(b - a, 3f))
                drawRect(col, topLeft = Offset(a, 0f), size = GSize(barW, h))
                drawRect(col, topLeft = Offset(b - barW, 0f), size = GSize(barW, h))
                val notch = Color.White.copy(alpha = if (isSel) 0.95f else 0.8f)
                val nH = h * (if (isSel) 0.5f else 0.4f)
                val nY = (h - nH) / 2f
                drawRect(notch, topLeft = Offset(a + barW / 2f - 0.75f, nY), size = GSize(1.5f, nH))
                drawRect(notch, topLeft = Offset(b - barW / 2f - 0.75f, nY), size = GSize(1.5f, nH))
                // A bright white frame marks the actively selected clip. A corner is ROUNDED only where the
                // active edge sits at the track's physical rounded end (the first clip's un-trimmed left, the
                // last clip's un-trimmed right); every inner / trimmed edge is a SQUARE 90 deg corner, so the
                // frame follows the grip: it hugs the arc at the track end and squares off once dragged in.
                if (isSel) {
                    val fw = 6f
                    val r = (cornerPx - fw / 2f).coerceAtLeast(0f)
                    val roundL = blk.index == 0 && blk.srcStart <= blk.slotStart
                    val roundR = blk.index == blks.lastIndex && blk.srcEnd >= blk.slotEnd
                    val rl = if (roundL) CornerRadius(r, r) else CornerRadius.Zero
                    val rr = if (roundR) CornerRadius(r, r) else CornerRadius.Zero
                    val frame = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = a + fw / 2f,
                                top = fw / 2f,
                                right = b - fw / 2f,
                                bottom = h - fw / 2f,
                                topLeftCornerRadius = rl,
                                topRightCornerRadius = rr,
                                bottomRightCornerRadius = rr,
                                bottomLeftCornerRadius = rl,
                            ),
                        )
                    }
                    drawPath(frame, Color.White, style = Stroke(width = fw))
                }
            }

            // Playhead in edited time: find the kept block holding it, map to x along its ACTIVE range.
            var px = 0f
            run {
                for (blk in blks) {
                    if (blk.removed) continue
                    if (playheadEditedMs <= blk.editedStart + blk.srcDur) {
                        val frac = ((playheadEditedMs - blk.editedStart).toFloat() / blk.srcDur).coerceIn(0f, 1f)
                        px = blk.activeXL + frac * (blk.activeXR - blk.activeXL)
                        return@run
                    }
                    px = blk.activeXR
                }
            }
            // White core line for contrast over any footage, capped with an accent knob at the top so the
            // playhead reads as the position marker rather than another clip handle.
            drawRect(Color.White, topLeft = Offset(px - 1.5f, 0f), size = GSize(3f, h))
            drawRect(accentColor, topLeft = Offset(px - 5f, 0f), size = GSize(10f, 6f))

            // Reorder feedback: while a clip is held (long-press), mark where it will drop and float a
            // lifted copy under the finger, so the move is visible before releasing.
            if (reorderGrab in blks.indices) {
                val target = blks.firstOrNull { reorderX >= it.xL && reorderX < it.xR } ?: blks.lastOrNull()
                if (target != null) {
                    val insX = if (target.index <= reorderGrab) target.xL else target.xR
                    drawRect(Color.White, topLeft = Offset(insX - 1.5f, 0f), size = GSize(3f, h))
                }
                val g = blks[reorderGrab]
                val gw = (g.xR - g.xL).coerceAtLeast(12f)
                val lx = (reorderX - gw / 2f).coerceIn(0f, (w - gw).coerceAtLeast(0f))
                // Shadow, then an opaque accent body with a white border, so the held clip clearly lifts
                // off the track and rides under the finger.
                drawRect(Color.Black.copy(alpha = 0.35f), topLeft = Offset(lx + 3f, 5f), size = GSize(gw, h - 8f))
                drawRect(accentColor.copy(alpha = 0.9f), topLeft = Offset(lx, 2f), size = GSize(gw, h - 8f))
                val bw = 2.5f
                drawRect(Color.White, topLeft = Offset(lx, 2f), size = GSize(gw, bw))
                drawRect(Color.White, topLeft = Offset(lx, h - 6f - bw), size = GSize(gw, bw))
                drawRect(Color.White, topLeft = Offset(lx, 2f), size = GSize(bw, h - 8f))
                drawRect(Color.White, topLeft = Offset(lx + gw - bw, 2f), size = GSize(bw, h - 8f))
            }
        }
        }
    }

        // Music lane: shown only when an overlay is loaded, as a compact row directly under the clip strip.
        // It shares the clips' pxPerMs() scale and scrollOffset, so the music block stays glued to the video
        // time axis and scrolls / zooms with the clips; screen-x = content-x - scrollOffset, exactly as the
        // clip strip maps its blocks. The block reads as a sibling of the clip blocks: same accent tokens,
        // same 10dp corner, edge grab handles with grip notches.
        if (audioName != null) {
            Spacer(modifier = Modifier.height(6.dp))
            val laneHeight = 30.dp
            val handleBarDp = 12.dp
            val handleBarPx = with(density) { handleBarDp.toPx() }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(laneHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TrackBg)
                    // The lane owns its own gesture arena, entirely below the clip strip's y-region, so it
                    // never touches the clip tap / scrub / reorder / zoom gestures above. pointerInput(Unit)
                    // captures once, so the offset + trim come through the latest* proxies.
                    .pointerInput(Unit) {
                        // 0 = none, 1 = move the block, 2 = drag the left edge, 3 = drag the right edge.
                        var zone = 0
                        var grabWithinBlockPx = 0f
                        detectDragGestures(
                            onDragStart = { down ->
                                val pxpm = pxPerMs()
                                val slice = (latestAudioTrimEndMs - latestAudioTrimStartMs).coerceAtLeast(1L)
                                val leftScreen = latestAudioOffsetMs.toFloat() * pxpm - scrollOffset.floatValue
                                val rightScreen = (latestAudioOffsetMs + slice).toFloat() * pxpm - scrollOffset.floatValue
                                zone = when {
                                    kotlin.math.abs(down.x - leftScreen) <= handleSlopPx -> 2
                                    kotlin.math.abs(down.x - rightScreen) <= handleSlopPx -> 3
                                    down.x >= leftScreen && down.x <= rightScreen -> 1
                                    else -> 0
                                }
                                if (zone != 0) {
                                    latestOnAudioEditStart()
                                    // Track the finger's offset within the block so a move never jumps it.
                                    if (zone == 1) grabWithinBlockPx = down.x - leftScreen
                                }
                            },
                            onDrag = { change, _ ->
                                if (zone != 0) {
                                    val pxpm = pxPerMs().coerceAtLeast(1e-6f)
                                    val screenX = change.position.x
                                    when (zone) {
                                        1 -> {
                                            val newLeftMs = (((screenX - grabWithinBlockPx) + scrollOffset.floatValue) / pxpm)
                                                .toLong().coerceAtLeast(0L)
                                            latestOnAudioMove(newLeftMs)
                                        }
                                        2 -> {
                                            val newLeftMs = ((screenX + scrollOffset.floatValue) / pxpm).toLong().coerceAtLeast(0L)
                                            latestOnAudioTrimLeft(newLeftMs)
                                        }
                                        3 -> {
                                            val newRightMs = ((screenX + scrollOffset.floatValue) / pxpm).toLong().coerceAtLeast(0L)
                                            latestOnAudioTrimRight(newRightMs)
                                        }
                                    }
                                    change.consume()
                                }
                            },
                            onDragEnd = { if (zone != 0) latestOnAudioEditEnd(); zone = 0 },
                            onDragCancel = { if (zone != 0) latestOnAudioEditEnd(); zone = 0 },
                        )
                    },
            ) {
                // The block, positioned by offset at screen-x = content-x - scrollOffset and clipped to the
                // lane (viewport) width by the parent. Its width is the played slice length at the shared scale.
                val sliceMs = (audioTrimEndMs - audioTrimStartMs).coerceAtLeast(1L)
                Row(
                    modifier = Modifier
                        .offset {
                            IntOffset((audioOffsetMs.toFloat() * pxPerMs() - scrollOffset.floatValue).roundToInt(), 0)
                        }
                        .width(with(density) { (sliceMs.toFloat() * pxPerMs()).coerceAtLeast(handleBarPx * 2f).toDp() })
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.28f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AudioLaneHandle(widthDp = handleBarDp, accent = accentColor)
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = FgPrimary,
                        modifier = Modifier.padding(start = 4.dp).size(13.dp),
                    )
                    Text(
                        text = audioName,
                        color = FgPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                    AudioLaneHandle(widthDp = handleBarDp, accent = accentColor)
                }
            }
        }
    }
}

/**
 * One edge grab handle on the music block: a solid accent bar the width of the clip handles, with a thin
 * white grip notch, so the music block's edges read and grab like a clip's start / end handles.
 */
@Composable
private fun AudioLaneHandle(widthDp: Dp, accent: Color) {
    Box(
        modifier = Modifier
            .width(widthDp)
            .fillMaxHeight()
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight(0.45f)
                .clip(RoundedCornerShape(1.dp))
                .background(Color.White.copy(alpha = 0.9f)),
        )
    }
}
