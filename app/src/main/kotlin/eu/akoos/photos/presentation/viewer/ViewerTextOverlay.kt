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

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import eu.akoos.photos.R
import eu.akoos.photos.data.ocr.OnnxTextRecognizer
import eu.akoos.photos.domain.ocr.RecognizedTextBlock
import eu.akoos.photos.domain.ocr.TextReadStage
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.util.formatBytes
import eu.akoos.photos.util.ImageFit
import eu.akoos.photos.util.fitImageInBox

/**
 * What the read-the-text gesture is busy with. Four different waits, and they are told apart because
 * announcing a twenty megabyte download over a mobile link as "reading" looks like a read that hung,
 * and the two halves of the read itself are far enough apart in length to be worth naming.
 */
enum class ViewerTextStage {

    /** Checking what is already on the device, which is a hash over the models it finds. */
    PreparingModel,

    /** Models are actually coming over the wire, which happens once and is tens of megabytes. */
    DownloadingModel,

    /** Looking for where the words are. */
    Detecting,

    /** The words have been found and are being turned into characters. */
    Reading,
}

/** How the reader's own stages show up on the pill. */
fun TextReadStage.asViewerStage(): ViewerTextStage = when (this) {
    TextReadStage.Detecting -> ViewerTextStage.Detecting
    TextReadStage.Reading -> ViewerTextStage.Reading
}

/** Where the viewer's read-the-text gesture has got to on the photo currently on screen. */
sealed interface ViewerTextState {

    /** Nothing asked for, nothing drawn. */
    data object Idle : ViewerTextState

    /** The gesture is under way at [stage]; the progress pill is up and the photo is untouched. */
    data class Working(val stage: ViewerTextStage) : ViewerTextState

    /**
     * Text was found and is highlighted. [imageWidth] and [imageHeight] are the frame the blocks were
     * read from, which is what their coordinates are relative to; the bitmap itself is let go the
     * moment recognition returns, because a 4096px frame is tens of megabytes the viewer has no
     * further use for.
     */
    data class Showing(
        val blocks: List<RecognizedTextBlock>,
        val imageWidth: Int,
        val imageHeight: Int,
    ) : ViewerTextState
}

/** The blocks currently highlighted, empty in every other state. */
val ViewerTextState.blocks: List<RecognizedTextBlock>
    get() = (this as? ViewerTextState.Showing)?.blocks.orEmpty()

/**
 * Whether the user has any of the recognised text picked out right now.
 *
 * Kept as a plain holder rather than as compose state on purpose: nothing on screen changes with it,
 * and its one reader is the page's tap handler, which asks the instant a tap lands. As compose state
 * it would put the whole viewer through a recomposition every time a word is picked or dropped, for
 * an answer no composable ever draws.
 */
class ViewerTextSelection {
    var active: Boolean = false
}

/** The two things a tap on the photo can mean while recognised text is up. */
enum class ViewerTextTap {

    /** Something is picked out, so the tap drops it and the words stay up. */
    ClearSelection,

    /** Nothing is picked out, so the tap is the way out of text mode. */
    LeaveTextMode,
}

/**
 * Which of the two a tap is, given whether anything is picked out.
 *
 * Every other text on the phone behaves this way, and the two intents are far enough apart to be
 * worth splitting: a reader who taps beside the words they just picked is putting that pick down,
 * not asking for the photograph back. Deciding on what is actually selected rather than on how many
 * taps have landed is what keeps the first tap live on a photo the user has selected nothing in.
 */
fun viewerTextTap(selectionActive: Boolean): ViewerTextTap =
    if (selectionActive) ViewerTextTap.ClearSelection else ViewerTextTap.LeaveTextMode

/**
 * The engine the viewer reads text with, held for as long as the viewer is on screen so the
 * runtime session it opens is paid for once rather than per photo, and released the moment the
 * screen goes away.
 */
@Composable
fun rememberTextRecognizer(): OnnxTextRecognizer {
    val context = LocalContext.current.applicationContext
    val recognizer = remember(context) { OnnxTextRecognizer(context) }
    DisposableEffect(recognizer) { onDispose { recognizer.close() } }
    return recognizer
}

/**
 * Asks before tens of megabytes go over the wire. Raised by the read-the-text gesture itself, so it
 * is an answer to something the user just asked for rather than an interruption.
 */
@Composable
fun ViewerTextModelDialog(downloadBytes: Long, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.viewer_text_model_title),
        message = stringResource(R.string.viewer_text_model_body, formatBytes(downloadBytes)),
        confirmLabel = stringResource(R.string.sel_label_download),
        dismissLabel = stringResource(R.string.cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * How the frame [showing] was read from sits inside a [containerW] by [containerH] page. Shared by
 * the drawing and the hit-test so the outline the user sees is the one they tap.
 */
fun viewerTextFit(showing: ViewerTextState.Showing, containerW: Float, containerH: Float): ImageFit =
    fitImageInBox(
        showing.imageWidth.toFloat(), showing.imageHeight.toFloat(),
        containerW.coerceAtLeast(1f), containerH.coerceAtLeast(1f),
    )

/** The page's live pinch-zoom, in the form the overlay and the hit-test both take. */
fun viewerTransform(containerSize: IntSize, scale: Float, offset: Offset): ViewerTransform =
    ViewerTransform(
        containerW = containerSize.width.toFloat(),
        containerH = containerSize.height.toFloat(),
        scale = scale,
        offsetX = offset.x,
        offsetY = offset.y,
    )

/**
 * Highlights recognised text over the photo. Sits beside the image rather than inside its
 * `graphicsLayer`, and applies [transform] itself, so the quads stay glued to the words through a
 * pinch and a pan while the outlines keep their on-screen weight.
 *
 * The photo goes dark and the words that were read stay lit: the scrim is laid over the drawn photo
 * and then lifted off each run, so the eye lands on what can be read instead of hunting for thin
 * outlines on a fully lit picture. Nothing is painted outside the photo's own rectangle, so the
 * letterbox bars stay clean whatever the frame's shape.
 *
 * What the user has actually picked out of those runs is not drawn here: the selection is the
 * platform's own, painted by the selectable layer over this one.
 *
 * Draws only; it takes no pointer input, so the page's tap, double-tap and drag detectors keep
 * seeing everything they saw before. It carries no reading of its own either, because the selectable
 * layer over it puts every run in front of a screen reader as real text.
 */
@Composable
fun ViewerTextOverlay(
    showing: ViewerTextState.Showing,
    transform: ViewerTransform,
    modifier: Modifier = Modifier,
) {
    if (showing.blocks.isEmpty()) return
    if (transform.containerW <= 0f || transform.containerH <= 0f) return
    if (showing.imageWidth <= 0 || showing.imageHeight <= 0) return

    // Keyed on the read alone: a fresh page of runs sweeps in, and nothing the user does to the
    // words afterwards is allowed to replay the sweep under their finger.
    var revealed by remember(showing) { mutableStateOf(false) }
    LaunchedEffect(showing) { revealed = true }
    val progress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "viewer_text_reveal",
    )

    val fit = remember(showing, transform.containerW, transform.containerH) {
        viewerTextFit(showing, transform.containerW, transform.containerH)
    }
    val accent = Accent
    val density = LocalDensity.current
    val strokePx = with(density) { OUTLINE_WIDTH.toPx() }
    val padPx = with(density) { ViewerTextHighlightPadding.toPx() }
    // Reused across frames: a pan redraws this on every pointer event and a fresh Path per block per
    // frame is pure churn.
    val paths = remember(showing.blocks) { List(showing.blocks.size) { Path() } }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // The scrim is lifted off the words rather than drawn around them, and a blend that
            // subtracts needs a layer of its own: without one it would take the photo underneath
            // with it instead of only the scrim above it.
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        val photo = photoRectOnScreen(showing.imageWidth, showing.imageHeight, fit, transform)
        clipRect(left = photo.left, top = photo.top, right = photo.right, bottom = photo.bottom) {
            // Black rather than a theme colour: this dims a photograph, not the app's own surfaces,
            // and a tinted photo would read as a filter applied to it.
            drawRect(color = Color.Black.copy(alpha = SCRIM_ALPHA * progress))
            showing.blocks.forEachIndexed { slot, block ->
                val reveal = blockReveal(progress, slot, showing.blocks.size)
                if (reveal <= 0f) return@forEachIndexed
                buildHighlight(paths[slot], block, fit, transform, padPx, reveal)
                // Fading the hole in rather than switching it on is what makes the dim look like it
                // is being lifted off the words.
                drawPath(
                    paths[slot],
                    color = Color.Black.copy(alpha = reveal),
                    blendMode = BlendMode.DstOut,
                )
            }
            // Every outline after every hole: a hole punched later would erase what was drawn
            // before it, and runs reveal in turn.
            showing.blocks.indices.forEach { slot ->
                val reveal = blockReveal(progress, slot, showing.blocks.size)
                if (reveal <= 0f) return@forEach
                drawPath(
                    paths[slot],
                    color = accent.copy(alpha = OUTLINE_ALPHA * reveal),
                    style = Stroke(width = strokePx, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/**
 * Lays [block]'s outline into [path], at [reveal] of the way through its own reveal: a short grow
 * out of the word's own centre, so each highlight settles onto the text instead of appearing on top
 * of it.
 */
private fun buildHighlight(
    path: Path,
    block: RecognizedTextBlock,
    fit: ImageFit,
    transform: ViewerTransform,
    padPx: Float,
    reveal: Float,
) {
    val corners = blockCornersOnScreen(block, fit, transform, padPx)
    var sumX = 0f
    var sumY = 0f
    corners.forEach { sumX += it.x; sumY += it.y }
    val centreX = sumX / corners.size
    val centreY = sumY / corners.size
    val grow = 1f - REVEAL_GROW * (1f - reveal)
    path.reset()
    corners.forEachIndexed { corner, point ->
        val x = centreX + (point.x - centreX) * grow
        val y = centreY + (point.y - centreY) * grow
        if (corner == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/** The pill shown while the gesture is working, matching the viewer's other status pills. */
@Composable
fun ViewerTextProgress(stage: ViewerTextStage, modifier: Modifier = Modifier) {
    val label = when (stage) {
        ViewerTextStage.PreparingModel -> stringResource(R.string.viewer_text_preparing)
        ViewerTextStage.DownloadingModel -> stringResource(R.string.viewer_text_downloading)
        ViewerTextStage.Detecting -> stringResource(R.string.viewer_text_detecting)
        ViewerTextStage.Reading -> stringResource(R.string.viewer_text_working)
    }
    Row(
        modifier = modifier
            .background(PillBg, RoundedCornerShape(999.dp))
            .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            color = FgPrimary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(14.dp),
        )
        Text(label, color = FgPrimary, fontSize = 12.sp)
    }
}

/**
 * Says the photo is in text mode, and is the way back out of it.
 *
 * The viewer's own bars step aside while the words are up, so without this nothing on screen names
 * the mode. It sits low and quiet: a status line the eye can skip, with one real control on it,
 * rather than a bar of actions competing with the toolbar the platform throws up beside a selection.
 *
 * Taps that land on it stop there. A tap on the photograph leaves text mode, and a tap aimed at the
 * pill is not one.
 */
@Composable
fun ViewerTextModePill(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Row(
        modifier = modifier
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .clip(RoundedCornerShape(20.dp))
            .background(colors.bg0.copy(alpha = 0.95f))
            .border(0.5.dp, colors.pillBorder, RoundedCornerShape(20.dp))
            // The dismiss carries a 48.dp touch target and sets the height on its own.
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.TextFields,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            stringResource(R.string.viewer_text_selectable),
            color = colors.fgPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            modifier = Modifier
                .size(ViewerTextPillControl)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
                tint = colors.fgMute,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Moves a viewer page from [from] to [to], handing each step to [onFrame].
 *
 * Text mode squeezes the photo inside the system bars on the way in and gives the user their own
 * zoom back on the way out. Both are animated, because a picture that changes size the instant the
 * highlights appear reads as a different picture having been loaded.
 */
suspend fun animateViewerZoom(from: ViewerZoom, to: ViewerZoom, onFrame: (ViewerZoom) -> Unit) {
    animate(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
    ) { fraction, _ ->
        onFrame(
            ViewerZoom(
                scale = lerp(from.scale, to.scale, fraction),
                offsetX = lerp(from.offsetX, to.offsetX, fraction),
                offsetY = lerp(from.offsetY, to.offsetY, fraction),
            ),
        )
    }
}

/** The air kept around each highlight, both where it is drawn and where a tap is measured. */
val ViewerTextHighlightPadding = 3.dp

/** The dismiss control's touch target, which is also what sets the pill's height. */
private val ViewerTextPillControl = 48.dp

/** The air above and below the pill where it is mounted. */
val ViewerTextPillPadding = 12.dp

/**
 * The band the mode pill takes at the foot of the page, on top of whatever the navigation bar
 * already claims.
 *
 * The squeeze that fits a photo inside the system bars has to give this up as well, or the pill
 * covers the last line of text on the very screenshots the squeeze exists to make readable. Derived
 * from the pill's own measurements rather than repeated as a number, so moving the pill cannot leave
 * the photo squeezed by the wrong amount.
 */
val ViewerTextPillReserve = ViewerTextPillControl + ViewerTextPillPadding * 2

/**
 * How far the photo is taken down outside the words. Deep enough that the lit runs are the only
 * thing the eye settles on, and short of blacking the picture out, because what the words sit on is
 * often what tells the user which photo they are reading.
 */
private const val SCRIM_ALPHA = 0.66f

/**
 * The edge of a run, kept to the faintest line that still reads as one. The dim on either side of it
 * is what marks the words out; the outline only has to say where a run stops, and anything heavier
 * turns a page of text into a grid of boxes the eye reads before the words. Set to blend into the
 * darkening rather than sit on top of it.
 */
private val OUTLINE_WIDTH = 0.5.dp
private const val OUTLINE_ALPHA = 0.16f

private const val REVEAL_GROW = 0.06f
