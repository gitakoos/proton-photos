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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.map.vector.FAR_CULL_COS
import eu.akoos.photos.presentation.map.vector.WorldMapData
import eu.akoos.photos.presentation.map.vector.appendBorder
import eu.akoos.photos.presentation.map.vector.appendFill
import eu.akoos.photos.presentation.map.vector.cosCentral
import eu.akoos.photos.presentation.map.darkTileFilter
import eu.akoos.photos.presentation.theme.AppColors
import kotlinx.coroutines.flow.map
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import kotlin.math.sin

private val DETAILS_MAP_HEIGHT = 150.dp

// A town and its surroundings on the classic tiles, inside the full map's own zoom range.
private const val DETAILS_TILE_ZOOM = 12.0

/** Degrees of the globe the World Map card spans across its height: a country and its neighbours. */
internal const val DETAILS_GLOBE_SPAN_DEG = 7.0

// OpenStreetMap's land colour. A tile that has not loaded (offline, or still on its way) paints flat in
// it instead of osmdroid's grey grid, and the dark tile filter recolours that placeholder exactly as it
// recolours a real tile, so a gap reads as plain land in either theme.
private const val OSM_LAND_COLOR = 0xFFF2EFE9.toInt()

private val PIN_SIZE = 28.dp

// The Place glyph's tip sits 10 of its 24 units below the icon's centre; lifting the icon by that much
// puts the tip on the card's centre, which is the photo's position in both map styles.
private const val PIN_TIP_LIFT = 10f / 24f

/**
 * The Map box of the photo details panel: a small, display-only map centred on where the photo was
 * taken, an accent pin on the spot, and [placeLabel] ("City, Country") as a caption once it is known.
 *
 * It follows the map-style setting the way the Search map card does. The World Map style, which is also
 * what an unchosen style means, draws the offline vector globe zoomed to the photo's country and its
 * neighbours, with no network. The Classic style shows the OpenStreetMap tiles around the spot. A tap
 * anywhere calls [onOpen]; a null [onOpen] (trash, the vault) leaves the box inert. A fix that
 * [mappablePoint] rejects renders nothing.
 */
@Composable
internal fun DetailsMapBox(
    latitude: Double,
    longitude: Double,
    placeLabel: String?,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (mappablePoint(latitude, longitude) == null) return
    val context = LocalContext.current
    // Null until the setting is read, so a Classic user never sees the globe flash in first.
    val mapStyleOsm by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.MAP_STYLE_OSM] ?: false }
    }.collectAsStateWithLifecycle<Boolean?>(initialValue = null)
    val shape = RoundedCornerShape(12.dp)

    Box(modifier = modifier) {
        MetadataSection(label = stringResource(R.string.map_title)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DETAILS_MAP_HEIGHT)
                    .clip(shape)
                    .then(
                        if (onOpen != null) {
                            Modifier.clickable(
                                onClickLabel = stringResource(R.string.cd_viewer_open_map),
                                role = Role.Button,
                                onClick = onOpen,
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                val classic = mapStyleOsm
                if (classic != null) {
                    if (classic) {
                        DetailsTileMap(latitude, longitude, modifier = Modifier.fillMaxSize())
                    } else {
                        DetailsGlobe(latitude, longitude, modifier = Modifier.fillMaxSize())
                    }
                    if (placeLabel != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.45f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.85f),
                                    ),
                                ),
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.Place,
                        contentDescription = null,
                        tint = AppColors.current.accent,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = -(PIN_SIZE * PIN_TIP_LIFT))
                            .size(PIN_SIZE),
                    )
                    if (placeLabel != null || classic) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            if (placeLabel != null) {
                                Text(
                                    text = placeLabel,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp, end = 8.dp, bottom = 10.dp),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            if (classic) OsmAttribution()
                        }
                    }
                }
                // Topmost and never consuming: the tile view underneath is not hit, so it cannot pan,
                // while a tap still reaches the box's click and a drag still scrolls the sheet.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                )
            }
        }
    }
}

/**
 * The World Map style: the app's offline vector globe with the photo's position facing the viewer,
 * zoomed to [DETAILS_GLOBE_SPAN_DEG]. The outlines are projected once per size and position, so a frame
 * only replays the cached paths.
 */
@Composable
private fun DetailsGlobe(latitude: Double, longitude: Double, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = AppColors.current
    var world by remember { mutableStateOf(WorldMapData.cachedOrNull()) }
    LaunchedEffect(Unit) { if (world == null) world = WorldMapData.load(context) }
    // An already parsed base map starts fully drawn; only a first parse fades in.
    val fade = animateFloatAsState(if (world != null) 1f else 0f, label = "detailsGlobeFade")

    val ocean = if (colors.isLight) Color(0xFFE9EDF4) else Color(0xFF14161B)
    val land = if (colors.isLight) Color(0xFFD3D8E2) else Color(0xFF262932)
    val border = if (colors.isLight) Color(0xFFAEB4C2) else Color(0xFF3A3E48)
    val shine = Color.White.copy(alpha = if (colors.isLight) 0.30f else 0.05f)

    Spacer(
        modifier = modifier.drawWithCache {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = globeRadiusForSpan(size.minDimension, DETAILS_GLOBE_SPAN_DEG)
            val landPath = Path()
            val borderPath = Path()
            world?.countries?.forEach { country ->
                if (cosCentral(country.centroid.lat, country.centroid.lng, latitude, longitude) < FAR_CULL_COS) {
                    return@forEach
                }
                appendFill(landPath, country.rings, cx, cy, radius, latitude, longitude)
                appendBorder(borderPath, country.rings, cx, cy, radius, latitude, longitude)
            }
            val r = radius.toFloat()
            val centre = Offset(cx, cy)
            val shineBrush = Brush.radialGradient(
                listOf(shine, Color.Transparent),
                center = Offset(cx - r * 0.35f, cy - r * 0.4f),
                radius = r * 1.3f,
            )
            val borderStroke = Stroke(width = 1.8f)
            onDrawBehind {
                val opacity = fade.value
                drawCircle(ocean, r, centre, alpha = opacity)
                drawCircle(shineBrush, r, centre, alpha = opacity)
                drawPath(landPath, land, alpha = opacity)
                drawPath(borderPath, border, alpha = opacity, style = borderStroke)
            }
        },
    )
}

/**
 * The Classic style: a display-only osmdroid view over the MAPNIK tiles, built like the Search map card.
 * Gestures are off (the box's touch shield keeps every touch from it), dark mode recolours the tiles,
 * and the view is paused, resumed and detached with the screen so a closed sheet does not leak it.
 */
@Composable
private fun DetailsTileMap(latitude: Double, longitude: Double, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isDark = !AppColors.current.isLight
    // Framed and filtered as it is built, so the first frame already shows the right place in the right
    // theme; the effects below follow any later change.
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setVerticalMapRepetitionEnabled(false)
            overlayManager.tilesOverlay.apply {
                setLoadingBackgroundColor(OSM_LAND_COLOR)
                setLoadingLineColor(OSM_LAND_COLOR)
                setColorFilter(if (isDark) darkTileFilter() else null)
            }
            controller.setZoom(DETAILS_TILE_ZOOM)
            controller.setCenter(GeoPoint(latitude, longitude))
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(mapView, isDark) {
        mapView.overlayManager.tilesOverlay.setColorFilter(if (isDark) darkTileFilter() else null)
        mapView.invalidate()
    }

    LaunchedEffect(mapView, latitude, longitude) {
        mapView.controller.setCenter(GeoPoint(latitude, longitude))
        mapView.invalidate()
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** OpenStreetMap's attribution, which its tile usage policy requires wherever the MAPNIK tiles show. */
@Composable
private fun OsmAttribution() {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.map_osm_attribution),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 9.sp,
        )
    }
}

/**
 * The fix the Map box can place, or null when either value is missing, is not a finite number, or lies
 * outside the WGS84 latitude / longitude ranges. A fix at 0,0 is placed like any other, the same as the
 * Location rows and the map read it.
 */
internal fun mappablePoint(latitude: Double?, longitude: Double?): DetailsGps? {
    if (latitude == null || longitude == null) return null
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return DetailsGps(latitude, longitude)
}

/**
 * The globe radius, in the units of [shortSide], at which [spanDeg] degrees of the sphere's surface reach
 * across the card's shorter side: a point half the span from the centre projects onto that side's edge.
 */
internal fun globeRadiusForSpan(shortSide: Float, spanDeg: Double): Double =
    (shortSide / 2.0) / sin(Math.toRadians(spanDeg / 2.0))
