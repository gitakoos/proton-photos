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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import eu.akoos.photos.domain.ocr.RecognizedTextBlock
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.util.ImageFit
import kotlin.math.roundToInt

/**
 * One recognised run as the selection layer holds it: the [text] the reader gave up, the [style] it
 * is set in, where it goes, and how far it has to be squeezed along its baseline to cover the words.
 */
private data class ViewerTextRun(
    val text: String,
    val style: TextStyle,
    val placement: ViewerTextBlockPlacement,
    val horizontalScale: Float,
)

/**
 * A fully transparent, genuinely selectable copy of the text on the photo, laid over the words it
 * was read from.
 *
 * Nothing here draws a glyph the user can see. What it buys is that the selection is the platform's
 * own: a long press takes a word, the handles drag it out across as many runs as the user wants, the
 * magnifier follows the finger, and the floating toolbar is whatever the device puts there. Building
 * handles by hand would only ever be an imitation of that, and would still not be the gesture the
 * user already knows from every other page on the phone.
 *
 * Each run sits over its own quad: placed at the corner the words start at, given the quad's height
 * as its line height so the selection the platform paints covers exactly what the detector found,
 * turned to the quad's own angle, and scaled along its baseline so it ends where the words end. The
 * runs are composed in reading order, and the platform then orders a multi-run selection by where
 * the runs actually sit, so dragging down a receipt comes out as the receipt reads.
 *
 * The pinch is a layer over the whole set rather than a re-measure of every run: it is a uniform
 * scale about the page's centre, so it turns nothing and stretches everything by the same factor,
 * and a hundred runs re-laying out on every pointer event would not keep up with a finger.
 *
 * [selection] is filled in from here, because whether the user has anything picked out is a fact
 * only this layer is placed to observe.
 */
@Composable
fun ViewerTextSelectionLayer(
    showing: ViewerTextState.Showing,
    transform: ViewerTransform,
    selection: ViewerTextSelection,
    modifier: Modifier = Modifier,
) {
    if (showing.blocks.isEmpty()) return
    if (transform.containerW <= 0f || transform.containerH <= 0f) return
    if (showing.imageWidth <= 0 || showing.imageHeight <= 0) return

    val fit = remember(showing, transform.containerW, transform.containerH) {
        viewerTextFit(showing, transform.containerW, transform.containerH)
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val runs = remember(showing.blocks, fit, density, measurer) {
        // Placed at fit-to-screen and zoomed by the layer below, so the arithmetic here survives a
        // pinch untouched.
        val flat = ViewerTransform.untransformed(transform.containerW, transform.containerH)
        showing.blocks.mapNotNull { block -> textRun(block, fit, flat, density, measurer) }
    }
    if (runs.isEmpty()) return

    val accent = Accent
    val selectionColors = remember(accent) {
        TextSelectionColors(
            handleColor = accent,
            backgroundColor = accent.copy(alpha = SELECTION_ALPHA),
        )
    }
    val platformToolbar = LocalTextToolbar.current
    val toolbar = remember(platformToolbar, selection) {
        ViewerSelectionToolbar(platformToolbar) { shown -> selection.active = shown }
    }
    CompositionLocalProvider(
        LocalTextSelectionColors provides selectionColors,
        LocalTextToolbar provides toolbar,
    ) {
        SelectionContainer(modifier = modifier.fillMaxSize()) {
            Layout(
                content = {
                    runs.forEach { run ->
                        // The trailing break is what a selection dragged across several runs joins
                        // them with: the platform concatenates the runs it crossed with nothing in
                        // between, and a shop name run straight into its street is one unusable
                        // string. A run the drag ends inside contributes only as far as the finger
                        // got, so the break never lands at the end of what the user picked. Held off
                        // the single laid-out line so it costs the node no height.
                        BasicText(
                            text = run.text + "\n",
                            style = run.style,
                            softWrap = false,
                            maxLines = 1,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = transform.scale,
                        scaleY = transform.scale,
                        translationX = transform.offsetX,
                        translationY = transform.offsetY,
                    ),
            ) { measurables, constraints ->
                // Measured unbounded: a run wider than the page is a photo the user has zoomed
                // into, and clipping it to the page would cut characters out of the selection.
                val placeables = measurables.map { it.measure(Constraints()) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeables.forEachIndexed { slot, placeable ->
                        val run = runs.getOrNull(slot) ?: return@forEachIndexed
                        placeable.placeWithLayer(
                            x = run.placement.originX.roundToInt(),
                            y = run.placement.originY.roundToInt(),
                        ) {
                            // About the corner the words start at, so the turn and the squeeze both
                            // pivot on the placement rather than dragging the run off it.
                            transformOrigin = TransformOrigin(0f, 0f)
                            rotationZ = run.placement.angleDegrees
                            scaleX = run.horizontalScale
                        }
                    }
                }
            }
        }
    }
}

/**
 * The platform's selection toolbar, with a note taken of whether it is up.
 *
 * Nothing public says, from outside the container, whether the user has anything picked out: the
 * selection is held by machinery this file does not own, and the form of `SelectionContainer` that
 * would hand it over is internal to the library. What the container does do is ask a toolbar to
 * appear the moment a selection settles, and to go away the moment one is dropped, whichever way it
 * is dropped. Sitting on that traffic answers the same question from the side the library does
 * expose, and it answers it for this container alone, because the toolbar is provided no wider than
 * the layer.
 *
 * [status] stays the real toolbar's. The container reads it to decide whether a hide is needed at
 * all, and a second opinion here would be this file answering a question the platform owns.
 */
private class ViewerSelectionToolbar(
    private val delegate: TextToolbar,
    private val onShownChange: (Boolean) -> Unit,
) : TextToolbar {

    override val status: TextToolbarStatus get() = delegate.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        delegate.showMenu(
            rect,
            onCopyRequested,
            onPasteRequested,
            onCutRequested,
            onSelectAllRequested,
        )
        onShownChange(true)
    }

    override fun hide() {
        delegate.hide()
        onShownChange(false)
    }
}

/**
 * [block] as a run ready to place, or null when there is nothing worth putting a node over: an empty
 * read, or a quad too small to hold a character the user could aim a handle at.
 */
private fun textRun(
    block: RecognizedTextBlock,
    fit: ImageFit,
    transform: ViewerTransform,
    density: Density,
    measurer: TextMeasurer,
): ViewerTextRun? {
    val text = block.text.trim()
    if (text.isEmpty()) return null
    val placement = blockPlacement(block, fit, transform)
    if (placement.heightPx < MIN_RUN_PX || placement.widthPx < MIN_RUN_PX) return null
    val style = runStyle(placement, density)
    val natural = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
    return ViewerTextRun(
        text = text,
        style = style,
        placement = placement,
        horizontalScale = blockHorizontalScale(placement.widthPx, natural.toFloat()),
    )
}

/**
 * How a run is set: transparent, at the quad's own line height, with the glyphs centred in it.
 *
 * The line height is the load-bearing part. The platform paints its selection over the line box, not
 * over the ink, so a line box the height of the quad is a highlight the height of the words, and the
 * node's own bounds are then the area a drag has to enter to reach that run.
 */
private fun runStyle(placement: ViewerTextBlockPlacement, density: Density): TextStyle = TextStyle(
    color = Color.Transparent,
    fontSize = with(density) { blockFontSizePx(placement.heightPx).toSp() },
    lineHeight = with(density) { placement.heightPx.toSp() },
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
    // Without this the platform adds its own padding above and below, and the line box stops being
    // the quad.
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** The smallest quad worth a node: below this a run is noise no handle could be dropped into. */
private const val MIN_RUN_PX = 4f

/** How far the accent is taken down for the selection wash, so the words under it stay readable. */
private const val SELECTION_ALPHA = 0.4f
