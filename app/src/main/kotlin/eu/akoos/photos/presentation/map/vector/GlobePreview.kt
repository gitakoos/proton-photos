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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import eu.akoos.photos.presentation.theme.AppColors
import kotlin.math.min

/**
 * A small, non-interactive rendering of the vector world globe, for a preview thumbnail. It loads the
 * same Natural Earth country data and orthographic projection the full [CustomMapScreen] draws with,
 * but at a fixed rotation and zoom and without pins, labels or gestures, so a card reads clearly as
 * the app's own globe. [centerLat] / [centerLng] turn the sphere so a region faces the viewer; the
 * sphere fills most of the shorter side so its curvature stays obvious.
 */
@Composable
internal fun GlobePreview(
    centerLat: Float,
    centerLng: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = AppColors.current
    // Draw from the cached base map synchronously when it is already parsed (the common case), so the
    // card shows the globe on its first frame instead of flashing it in after Search appears. Only a
    // genuine first parse loads asynchronously, and only that case fades in.
    val hadCache = remember { WorldMapData.cachedOrNull() != null }
    var world by remember { mutableStateOf(WorldMapData.cachedOrNull()) }
    LaunchedEffect(Unit) { if (world == null) world = WorldMapData.load(context) }
    val loadFade by animateFloatAsState(if (world != null) 1f else 0f, label = "globePreviewFade")
    val fade = if (hadCache) 1f else loadFade

    val ocean = if (colors.isLight) Color(0xFFE9EDF4) else Color(0xFF14161B)
    val land = if (colors.isLight) Color(0xFFD3D8E2) else Color(0xFF262932)
    val border = if (colors.isLight) Color(0xFFAEB4C2) else Color(0xFF3A3E48)

    Canvas(modifier = modifier) {
        val w = world ?: return@Canvas
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = (min(size.width, size.height) / 2f * 0.86f).toDouble()
        val cLat = centerLat.toDouble()
        val cLng = centerLng.toDouble()

        drawCircle(ocean, radius.toFloat(), Offset(cx, cy), alpha = fade)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = if (colors.isLight) 0.30f else 0.05f), Color.Transparent),
                center = Offset(cx - radius.toFloat() * 0.35f, cy - radius.toFloat() * 0.4f),
                radius = radius.toFloat() * 1.3f,
            ),
            radius = radius.toFloat(),
            center = Offset(cx, cy),
            alpha = fade,
        )

        val landPath = Path()
        val borderPath = Path()
        for (country in w.countries) {
            if (cosCentral(country.centroid.lat, country.centroid.lng, cLat, cLng) < FAR_CULL_COS) continue
            appendFill(landPath, country.rings, cx, cy, radius, cLat, cLng)
            appendBorder(borderPath, country.rings, cx, cy, radius, cLat, cLng)
        }
        drawPath(landPath, land, alpha = fade)
        drawPath(borderPath, border, alpha = fade, style = Stroke(width = 1.1f))
        drawCircle(border, radius.toFloat(), Offset(cx, cy), alpha = fade, style = Stroke(width = 2f))
    }
}
