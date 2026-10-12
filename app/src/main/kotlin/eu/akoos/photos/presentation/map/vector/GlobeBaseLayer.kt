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

package eu.akoos.photos.presentation.map.vector

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Everything the base picture of the globe depends on besides where it is looking: the colours, and which countries
 * and provinces are tinted. A new one means the picture has to be drawn again.
 */
internal class BaseStyle(
    val ocean: Color,
    val land: Color,
    val border: Color,
    val highlight: Color,
    val isLight: Boolean,
    val density: Float,
    val detail: WorldDetail,
    val hotOwner: BooleanArray,
    val visitedProvinces: BooleanArray,
    val provinceIsos: Set<String>,
)

/** Where the base picture looks: the centre, the zoom, the screen size, and whether the globe is being moved. */
internal data class BaseView(
    val cLat: Double,
    val cLng: Double,
    val zoom: Float,
    val moving: Boolean,
    val width: Int,
    val height: Int,
    val pad: Int = 0,
) {
    /** The globe's radius for the screen [width] by [height]; the picture itself is [pad] larger on every side. */
    val radius: Double get() = globeRadius(width.toFloat(), height.toFloat(), zoom).toDouble()
}

/** A finished base picture and the view it was drawn for. */
internal class BaseFrame(val bitmap: ImageBitmap, val view: BaseView)

/**
 * Draws the base picture of the globe (sea, land, provinces, borders, the tint of where the account has been) into a
 * bitmap off the main thread, so that moving the globe never waits for it. The screen shows the latest finished
 * picture, shifted and scaled to the current view until the next one is ready. Three bitmaps are used in turn, so the
 * one being drawn into is never the one on screen or the one just replaced.
 */
internal class GlobeBaseRenderer {
    private val buffers = arrayOfNulls<ImageBitmap>(BUFFER_COUNT)
    private var next = 0

    /** The bitmap the screen is showing, set by the screen; a picture is never drawn into it. */
    @Volatile var onScreen: ImageBitmap? = null

    /**
     * Draws one picture. A drawing already under way cannot be stopped when the view changes again, so a new one waits
     * its turn instead of running beside it: the bitmaps and the drawing scope are used by one picture at a time.
     */
    @Synchronized
    fun render(style: BaseStyle, view: BaseView): BaseFrame {
        if (buffers[next] != null && buffers[next] === onScreen) next = (next + 1) % BUFFER_COUNT
        var bitmap = buffers[next]
        val bitmapWidth = view.width + 2 * view.pad
        val bitmapHeight = view.height + 2 * view.pad
        if (bitmap == null || bitmap.width != bitmapWidth || bitmap.height != bitmapHeight) {
            bitmap = ImageBitmap(bitmapWidth, bitmapHeight)
            buffers[next] = bitmap
        }
        next = (next + 1) % BUFFER_COUNT
        CanvasDrawScope().draw(
            Density(style.density),
            LayoutDirection.Ltr,
            Canvas(bitmap),
            Size(bitmapWidth.toFloat(), bitmapHeight.toFloat()),
        ) {
            drawRect(Color.Transparent, blendMode = BlendMode.Clear)
            drawGlobeBase(style, view)
        }
        return BaseFrame(bitmap, view)
    }

    private companion object {
        const val BUFFER_COUNT = 3
    }
}

/** Where the centre of [frame]'s picture now is on screen, and how much larger or smaller it should be drawn. */
internal class FramePlacement(val shiftX: Float, val shiftY: Float, val scale: Float)

internal fun placeFrame(frame: BaseFrame, cLat: Double, cLng: Double, radius: Double): FramePlacement {
    val point = GlobeProjection.project(frame.view.cLat, frame.view.cLng, cLat, cLng, radius)
    return FramePlacement(point.x, point.y, (radius / frame.view.radius).toFloat())
}

/** The base picture itself: the same drawing whether it goes straight to the screen or into a bitmap. */
internal fun DrawScope.drawGlobeBase(style: BaseStyle, baseView: BaseView) {
    val detail = style.detail
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = baseView.radius
    val density = style.density

    val hugeGlobe = radius > HUGE_GLOBE_RADIUS
    if (hugeGlobe) {
        drawRect(style.ocean)
    } else {
        drawCircle(style.ocean, radius.toFloat(), Offset(cx, cy))
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = if (style.isLight) 0.30f else 0.05f), Color.Transparent),
                center = Offset(cx - radius.toFloat() * 0.35f, cy - radius.toFloat() * 0.4f),
                radius = radius.toFloat() * 1.3f,
            ),
            radius = radius.toFloat(),
            center = Offset(cx, cy),
        )
    }

    val landPath = Path()
    val tintPath = Path().apply { fillType = PathFillType.EvenOdd }
    val tintBorderPath = Path()
    val borderPath = Path()
    val highlightBorderPath = Path()
    // The detail follows the zoom: a coarse outline from afar, every kept point close up, and the provinces'
    // borders only once a country is big enough on screen for them to read.
    val detailLevel = detailLevelFor(radius, if (baseView.moving) MOVING_TOLERANCE_PX else SETTLED_TOLERANCE_PX)
    val view = GlobeView(cx, cy, radius, baseView.cLat, baseView.cLng, size.width, size.height)
    for (ring in detail.countryRings) {
        if (!ringMayShow(ring, view)) continue
        val hot = style.hotOwner[ring.owner]
        appendDetailFill(landPath, ring, detailLevel, view)
        if (hot && detail.countries[ring.owner].iso2 !in style.provinceIsos) {
            appendDetailFill(tintPath, ring, detailLevel, view)
        }
        appendDetailBorder(if (hot) highlightBorderPath else borderPath, ring, detailLevel, view)
    }
    val visited = style.visitedProvinces
    for (ring in detail.provinceRings) {
        if (ring.owner >= visited.size || !visited[ring.owner]) continue
        if (!ringMayShow(ring, view)) continue
        appendDetailFill(tintPath, ring, detailLevel, view)
        appendDetailBorder(tintBorderPath, ring, detailLevel, view)
    }
    drawPath(landPath, style.land)
    drawPath(tintPath, style.highlight, alpha = 0.28f)
    if (radius >= PROVINCE_MIN_RADIUS) {
        val provincePath = Path()
        for (ring in detail.provinceRings) {
            if (!ringMayShow(ring, view, PROVINCE_MIN_PX)) continue
            appendDetailBorder(provincePath, ring, detailLevel, view)
        }
        drawPath(provincePath, style.border, alpha = 0.9f, style = Stroke(width = 1.2f * density))
    }
    drawPath(tintBorderPath, style.highlight, alpha = 0.85f, style = Stroke(width = 1.4f * density))
    drawPath(borderPath, style.border, style = Stroke(width = (1.1f + (baseView.zoom - 1f) * 0.05f).coerceAtMost(3f)))
    // Visited countries get a thicker accent outline: "you have been here".
    drawPath(
        highlightBorderPath, style.highlight, alpha = 0.9f,
        style = Stroke(width = (2.2f + (baseView.zoom - 1f) * 0.08f).coerceAtMost(5f)),
    )
    if (!hugeGlobe) drawCircle(style.border, radius.toFloat(), Offset(cx, cy), style = Stroke(width = 2f))
}

// Past this disc radius (in pixels) the edge of the globe is far off screen, so the ocean is a plain fill and the
// shading and the rim are skipped, which a circle that large would draw slowly and unevenly.
internal const val HUGE_GLOBE_RADIUS = 40_000.0

// The province borders show once the globe is this big (in pixels of radius).
internal const val PROVINCE_MIN_RADIUS = 3000.0

// A province smaller than this on screen is left out of the borders: it would only be a speck.
internal const val PROVINCE_MIN_PX = 8.0
