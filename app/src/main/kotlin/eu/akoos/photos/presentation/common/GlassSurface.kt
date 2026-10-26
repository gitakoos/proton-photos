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

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import kotlin.math.min
import kotlin.math.roundToInt

/** Whether the user turned the frosted pills on (Appearance), provided once at the root. */
val LocalLiquidGlass = staticCompositionLocalOf { false }

/** The backdrop the pills above a screen's content frost, or null where they draw their plain fill. */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/**
 * A screen's content as the pills floating over it see it: [glassSource] records the content into [layer] each
 * time it draws, and every [pillSurface] above draws that recording through its glass. [base] is the colour behind
 * the content, painted first, as the content leaves gaps. A [frozen] backdrop holds one picture of the content
 * instead ([freeze]), for a menu that opens in a window of its own over content that stands still.
 */
class GlassBackdrop internal constructor(
    internal val layer: GraphicsLayer,
    internal val base: Color,
    internal val frozen: ImageBitmap? = null,
    originOnScreen: Offset = Offset.Zero,
) {
    internal var originOnScreen by mutableStateOf(originOnScreen)

    /** A still picture of the content as it is now, or null when it cannot be taken. */
    suspend fun freeze(): GlassBackdrop? =
        runCatching { GlassBackdrop(layer, base, layer.toImageBitmap(), originOnScreen) }.getOrNull()
}

/**
 * The backdrop for a screen, or null when the frosted pills are off or the phone cannot blur a layer (Android
 * 11 and older), so the pills keep their plain fill there.
 */
@Composable
fun rememberGlassBackdrop(base: Color): GlassBackdrop? {
    if (!LocalLiquidGlass.current || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val layer = rememberGraphicsLayer()
    return remember(layer, base) { GlassBackdrop(layer, base) }
}

/**
 * Records this element's drawing as [backdrop] for the pills above it. The pills must sit outside this
 * element: one inside would draw the recording into itself.
 */
fun Modifier.glassSource(backdrop: GlassBackdrop?): Modifier =
    if (backdrop == null) this else this then GlassSourceElement(backdrop)

/**
 * A floating pill's surface: [color] in [shape] with a [border] hairline, or, with a [LocalGlassBackdrop]
 * provided, glass: the content behind it seen through a lens that bends it at the rim, softened, its tone
 * pulled toward what keeps the pill's labels legible over it, tinted by [color] and lit along the top edge.
 */
fun Modifier.pillSurface(color: Color, shape: Shape, border: Color? = null): Modifier =
    this then PillSurfaceElement(shape, border, useLocal = true, explicit = null, panel = false) { color }

/** [pillSurface] with a fill read as the pill draws, for a tint that animates without recomposing. */
fun Modifier.pillSurface(shape: Shape, border: Color? = null, color: () -> Color): Modifier =
    this then PillSurfaceElement(shape, border, useLocal = true, explicit = null, panel = false, color = color)

/**
 * [pillSurface] for a larger panel over [backdrop] (a [GlassBackdrop.freeze]d one, for a menu's own window), frosted
 * harder so rows of text stay readable over a busy picture; plain with none.
 */
fun Modifier.glassPanel(backdrop: GlassBackdrop?, color: Color, shape: Shape): Modifier =
    this then PillSurfaceElement(shape, null, useLocal = false, explicit = backdrop, panel = true) { color }

private data class GlassSourceElement(val backdrop: GlassBackdrop) : ModifierNodeElement<GlassSourceNode>() {
    override fun create() = GlassSourceNode(backdrop)
    override fun update(node: GlassSourceNode) {
        node.backdrop = backdrop
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "glassSource"
    }
}

private class GlassSourceNode(var backdrop: GlassBackdrop) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        backdrop.originOnScreen = coordinates.positionOnScreen()
    }

    override fun ContentDrawScope.draw() {
        val layer = backdrop.layer
        layer.record(size.toIntSize()) { this@draw.drawContent() }
        drawLayer(layer)
    }
}

private data class PillSurfaceElement(
    val shape: Shape,
    val border: Color?,
    val useLocal: Boolean,
    val explicit: GlassBackdrop?,
    val panel: Boolean,
    val color: () -> Color,
) : ModifierNodeElement<PillSurfaceNode>() {
    override fun create() = PillSurfaceNode(shape, border, useLocal, explicit, panel, color)
    override fun update(node: PillSurfaceNode) {
        node.shape = shape
        node.border = border
        node.useLocal = useLocal
        node.explicit = explicit
        node.panel = panel
        node.color = color
        node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "pillSurface"
    }
}

private class PillSurfaceNode(
    var shape: Shape,
    var border: Color?,
    var useLocal: Boolean,
    var explicit: GlassBackdrop?,
    var panel: Boolean,
    var color: () -> Color,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {

    private var positionOnScreen = Offset.Zero

    /** The content around the pill, a margin wider on every side, so the blur and the lens draw from what lies
     *  just past the rim too and nothing pops in at the edge as the content scrolls. */
    private var glassLayer: GraphicsLayer? = null

    /** The pill's own outline, which the renderer clips smoothly, unlike a clip path on the canvas. */
    private var clipLayer: GraphicsLayer? = null
    private var releaseLayer: ((GraphicsLayer) -> Unit)? = null
    private var lens: GlassLens? = null
    private var effectKey: Any? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val position = coordinates.positionOnScreen()
        if (position != positionOnScreen) {
            positionOnScreen = position
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val outline = shape.createOutline(size, layoutDirection, this)
        val color = color()
        val backdrop = if (useLocal) currentValueOf(LocalGlassBackdrop) else explicit
        val hairline = 0.5.dp.toPx()
        if (backdrop == null || size.minDimension <= 0f) {
            drawOutline(outline, color)
            border?.let { drawOutline(outline, it, style = Stroke(hairline)) }
            drawContent()
            return
        }
        val context = currentValueOf(LocalGraphicsContext)
        releaseLayer = context::releaseGraphicsLayer
        val glass = glassLayer ?: context.createGraphicsLayer().also { glassLayer = it }
        val clip = clipLayer ?: context.createGraphicsLayer().also { clipLayer = it }

        val margin = MARGIN.toPx().roundToInt()
        val pill = size.toIntSize()
        val padded = IntSize(pill.width + 2 * margin, pill.height + 2 * margin)
        val lightLabels = backdrop.base.luminance() < 0.5f
        val shaded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val radius = cornerRadius(outline, pill)
        val key = listOf(padded, pill, radius, color.toArgb(), lightLabels, shaded, panel)
        if (key != effectKey) {
            effectKey = key
            glass.renderEffect = if (shaded) {
                lensEffect(margin.toFloat(), pill, radius, color, lightLabels)
            } else {
                val blur = (if (panel) BLUR_PANEL else BLUR_PLAIN).toPx()
                BlurEffect(blur, blur, TileMode.Clamp)
            }
        }
        // Reading the backdrop's origin here redraws the pill when the content moves under it; a live recording is
        // drawn by reference, so a scroll of the content needs no new recording here.
        val shift = backdrop.originOnScreen - positionOnScreen
        glass.record(padded) {
            drawRect(backdrop.base)
            translate(shift.x + margin, shift.y + margin) {
                val still = backdrop.frozen
                if (still != null) drawImage(still) else drawLayer(backdrop.layer)
            }
        }
        when (outline) {
            is Outline.Rectangle -> clip.setRectOutline()
            is Outline.Rounded -> clip.setRoundRectOutline(cornerRadius = radius)
            is Outline.Generic -> clip.setPathOutline(outline.path)
        }
        clip.clip = true
        clip.record(pill) {
            translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(glass) }
        }
        drawLayer(clip)
        if (!shaded) {
            // Without the lens, a plain tint keeps the labels legible and a faint line marks the edge.
            drawOutline(outline, color.copy(alpha = min(color.alpha, PLAIN_TINT)))
            if (border != null) {
                val edge = if (lightLabels) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
                drawOutline(outline, edge, style = Stroke(hairline))
            }
        }
        drawContent()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun ContentDrawScope.lensEffect(
        margin: Float,
        pill: IntSize,
        radius: Float,
        tint: Color,
        lightLabels: Boolean,
    ): RenderEffect {
        val shader = (lens ?: GlassLens().also { lens = it }).shader
        shader.setFloatUniform("origin", margin, margin)
        shader.setFloatUniform("pillSize", pill.width.toFloat(), pill.height.toFloat())
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("bevel", min(BEVEL.toPx(), min(pill.width, pill.height) / 2f))
        shader.setFloatUniform("refraction", REFRACTION.toPx())
        shader.setFloatUniform("tint", tint.red, tint.green, tint.blue, min(tint.alpha, if (panel) PANEL_TINT else LENS_TINT))
        shader.setFloatUniform("lightLabels", if (lightLabels) 1f else 0f)
        val blur = (if (panel) BLUR_PANEL else BLUR_LENS).toPx()
        return android.graphics.RenderEffect.createChainEffect(
            android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"),
            android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP),
        ).asComposeRenderEffect()
    }

    override fun onDetach() {
        listOfNotNull(glassLayer, clipLayer).forEach { layer -> releaseLayer?.invoke(layer) }
        glassLayer = null
        clipLayer = null
        effectKey = null
    }
}

/** The corner radius of [outline] in pixels, the half of the shorter side for a shape that is not a rounded box. */
private fun cornerRadius(outline: Outline, pill: IntSize): Float = when (outline) {
    is Outline.Rectangle -> 0f
    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
    is Outline.Generic -> min(pill.width, pill.height) / 2f
}

/** The lens shader, one per pill, as its uniforms are the pill's own. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class GlassLens {
    val shader = RuntimeShader(LENS_SHADER)
}

private val MARGIN = 28.dp
private val BEVEL = 16.dp
private val REFRACTION = 9.dp
private val BLUR_LENS = 7.dp
private val BLUR_PLAIN = 20.dp
private val BLUR_PANEL = 18.dp

/** The most of a pill's own fill laid over the lens; the lens already keeps the labels legible. */
private const val LENS_TINT = 0.28f

/** The most of a panel's own fill laid over the lens. */
private const val PANEL_TINT = 0.45f

/** The most of a pill's own fill laid over the plain blur. */
private const val PLAIN_TINT = 0.55f

/**
 * The glass, run on the softened content around the pill. Inside a band along the rim the content is sampled
 * from further in along the rim's normal, the more the closer to the edge, as a curved glass edge bends what is
 * behind it, with the three colour channels bent a little apart. The colour is then made a touch more vivid and
 * its brightness bent through a soft knee toward what keeps the pill's labels readable: with light labels (a dark
 * theme) a dark backdrop passes as it is and everything brighter is held down, more the brighter it is; with dark
 * labels a light backdrop passes and a darker one is lifted. So the glass follows how light or dark the content
 * under it is, and the labels keep their contrast over white and black alike. The
 * pill's own tint goes over that, and a thin light rim, strongest along the top, gives the edge its depth.
 */
private const val LENS_SHADER = """
uniform shader content;
uniform float2 origin;
uniform float2 pillSize;
uniform float radius;
uniform float bevel;
uniform float refraction;
uniform float4 tint;
uniform float lightLabels;

float roundBox(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

half4 main(float2 coord) {
    float2 halfSize = pillSize * 0.5;
    float2 p = coord - (origin + halfSize);
    float r = min(radius, min(halfSize.x, halfSize.y));
    float d = roundBox(p, halfSize, r);
    float2 grad = float2(
        roundBox(p + float2(1.0, 0.0), halfSize, r) - roundBox(p - float2(1.0, 0.0), halfSize, r),
        roundBox(p + float2(0.0, 1.0), halfSize, r) - roundBox(p - float2(0.0, 1.0), halfSize, r));
    float2 n = length(grad) > 0.0001 ? normalize(grad) : float2(0.0);
    float t = clamp(1.0 + d / max(bevel, 1.0), 0.0, 1.0);
    float2 bend = -n * (t * t * t) * refraction;

    float3 col = float3(
        float(content.eval(coord + bend).r),
        float(content.eval(coord + bend * 1.12).g),
        float(content.eval(coord + bend * 1.24).b));

    float l = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = clamp(mix(float3(l), col, 1.18), 0.0, 1.0);
    l = dot(col, float3(0.2126, 0.7152, 0.0722));
    if (lightLabels > 0.5) {
        float held = l <= 0.15 ? l : 0.15 + (l - 0.15) * 0.2;
        col *= l > 0.0001 ? held / l : 1.0;
    } else {
        float lifted = l >= 0.78 ? l : 0.78 - (0.78 - l) * 0.25;
        col = min(col + float3(lifted - l), float3(1.0));
    }
    col = mix(col, tint.rgb, tint.a);

    float rim = smoothstep(2.2, 0.0, abs(d));
    float lit = 0.5 + 0.5 * dot(n, normalize(float2(-0.35, -1.0)));
    float rimStrength = lightLabels > 0.5 ? (0.06 + 0.26 * lit) : (0.04 + 0.16 * lit);
    col += float3(rim * rimStrength);
    col += float3(t * t * (lightLabels > 0.5 ? 0.045 : 0.03));
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""
