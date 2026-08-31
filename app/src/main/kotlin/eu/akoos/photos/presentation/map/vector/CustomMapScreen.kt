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

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.PlaceCity
import eu.akoos.photos.presentation.map.ThumbnailPin
import eu.akoos.photos.presentation.places.PlaceSearchSheet
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.theme.AppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// A self-contained vector globe: it draws public-domain Natural Earth country outlines on a rotating
// orthographic sphere (see GlobeProjection + WorldMapData), then surfaces only WHERE THE ACCOUNT HAS
// BEEN — visited countries filled and outlined, a thumbnail pin plus label for each visited city. It
// renders everything itself, with no map tiles.

/**
 * A rotating vector globe that surfaces the account's own places. The whole world is drawn as a light
 * base map; the countries with photos are washed in the accent and given a thicker outline, and each
 * city with photos gets a rounded thumbnail of a photo taken there plus its name. Drag spins the globe
 * with a bit of glide, pinch zooms, a tap on a city thumbnail opens its photos and a tap on a highlighted
 * country opens its cities. Reached from the existing map and fully removable with it.
 */
@Composable
fun CustomMapScreen(
    onBack: () -> Unit,
    onCountryClick: (countryCode: String) -> Unit,
    onCityClick: (latitude: Double, longitude: Double) -> Unit,
    onSwitchStyle: () -> Unit = {},
    vm: CustomMapViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val countries by vm.countries.collectAsStateWithLifecycle()
    val cities by vm.cities.collectAsStateWithLifecycle()
    val placesLoaded by vm.loaded.collectAsStateWithLifecycle()

    val world by produceState<WorldMap?>(null) { value = WorldMapData.load(context) }
    val shapeByIso = remember(world) { world?.countries?.associateBy { it.iso2 }.orEmpty() }
    val highlightCodes = remember(countries) { countries.mapTo(HashSet()) { it.countryCode.uppercase() } }

    // Build each visited city's marker (the exact Google-Photos-style pin the OSM map uses) from its
    // cover, off the main thread and cached. Rendered large so it stays crisp as the globe zooms in.
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(cities) {
        for (city in cities) {
            val key = city.city + "|" + city.countryCode
            if (thumbs.containsKey(key)) continue
            val model = coverModel(city.cover) ?: continue
            val result = runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context).data(model).size(320).allowHardware(false).build(),
                )
            }.getOrNull()
            val cover = (result?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: continue
            val pin = runCatching { ThumbnailPin.build(cover, density * 2f) }.getOrNull() ?: continue
            thumbs[key] = pin.asImageBitmap()
        }
    }

    var centerLat by remember { mutableFloatStateOf(30f) }
    var centerLng by remember { mutableFloatStateOf(10f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var centered by remember { mutableStateOf(false) }
    val flingScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    // Staggered entrance: the globe zooms in first, then the header and bottom search follow.
    var chromeVisible by remember { mutableStateOf(false) }
    val fade by animateFloatAsState(if (world != null && centered) 1f else 0f, label = "mapFade")

    // Open already centred on the busiest city so the account's places face the viewer, rather than
    // starting on a default view and visibly rotating there. Gated on [placesLoaded] (not the city
    // list, which reads empty both while loading and when genuinely empty) so the globe stays hidden
    // until its centre is decided, then fades in in place.
    LaunchedEffect(placesLoaded) {
        if (!placesLoaded || centered) return@LaunchedEffect
        cities.firstOrNull()?.let { centerLat = it.latitude.toFloat(); centerLng = it.longitude.toFloat() }
        centered = true
    }

    // Once the globe is in, wait a beat, then bring the header and bottom search in, so the globe reads
    // first instead of the chrome landing at the same instant as the zoom.
    LaunchedEffect(centered) {
        if (centered) {
            delay(200)
            chromeVisible = true
        }
    }

    val ocean = if (colors.isLight) Color(0xFFE9EDF4) else Color(0xFF14161B)
    val land = if (colors.isLight) Color(0xFFD3D8E2) else Color(0xFF262932)
    val border = if (colors.isLight) Color(0xFFAEB4C2) else Color(0xFF3A3E48)
    val highlight = colors.accent
    val cityPaint = remember(colors) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = colors.fgPrimary.toArgb()
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val countryPaint = remember(colors) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = colors.fgDim.toArgb()
            textSize = 34f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.bg0)) {
        if (world == null || !centered) {
            CircularProgressIndicator(color = colors.accent, modifier = Modifier.align(Alignment.Center))
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(world, cities, highlightCodes) {
                    // One handler for rotate/pinch, a glide after a flick, AND tap: a gesture that barely
                    // moved is treated as a tap and hit-tested, so a separate tap detector can't miss it.
                    awaitEachGesture {
                        flingJob?.cancel()
                        val tracker = VelocityTracker()
                        val down = awaitFirstDown(requireUnconsumed = false)
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val downPos = down.position
                        var maxMove = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            if (zoomChange != 1f) zoom = (zoom * zoomChange).coerceIn(1f, 60f)
                            val pan = event.calculatePan()
                            if (pan != Offset.Zero) {
                                val k = 0.22f / zoom
                                centerLng -= pan.x * k
                                centerLat = (centerLat + pan.y * k).coerceIn(-85f, 85f)
                            }
                            event.changes.firstOrNull()?.let {
                                tracker.addPosition(it.uptimeMillis, it.position)
                                maxMove = max(maxMove, (it.position - downPos).getDistance())
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                        if (maxMove < 24f) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val radius = min(size.width, size.height) / 2f * 0.92f * zoom
                            val cLat = centerLat.toDouble()
                            val cLng = centerLng.toDouble()
                            val pinScale = (1f + (zoom - 1f) * 0.05f).coerceAtMost(3f)
                            val dstW = 46f * density * pinScale
                            val dstH = dstW * 1.15f
                            val city = cities.firstOrNull { c ->
                                val gp = GlobeProjection.project(c.latitude, c.longitude, cLat, cLng, radius.toDouble())
                                if (!gp.visible) return@firstOrNull false
                                val px = cx + gp.x
                                val py = cy + gp.y
                                downPos.x in (px - dstW / 2f)..(px + dstW / 2f) && downPos.y in (py - dstH)..(py + 8f)
                            }
                            if (city != null) {
                                onCityClick(city.latitude, city.longitude)
                            } else {
                                val w = world
                                val hit = if (w != null) countryAt(downPos.x, downPos.y, cx, cy, radius, cLat, cLng, w, highlightCodes) else null
                                if (hit != null) onCountryClick(hit)
                            }
                        } else {
                            val v = tracker.calculateVelocity()
                            flingJob = flingScope.launch {
                                var vx = v.x
                                var vy = v.y
                                while (abs(vx) > 40f || abs(vy) > 40f) {
                                    withFrameNanos { }
                                    val k = 0.22f / zoom
                                    centerLng -= (vx / 60f) * k
                                    centerLat = (centerLat + (vy / 60f) * k).coerceIn(-85f, 85f)
                                    vx *= 0.90f
                                    vy *= 0.90f
                                }
                            }
                        }
                    }
                },
        ) {
            val w = world ?: return@Canvas
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = (min(size.width, size.height) / 2f * 0.92f * zoom).toDouble()
            val cLat = centerLat.toDouble()
            val cLng = centerLng.toDouble()

            drawCircle(ocean, radius.toFloat(), Offset(cx, cy))
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = if (colors.isLight) 0.30f else 0.05f), Color.Transparent),
                    center = Offset(cx - radius.toFloat() * 0.35f, cy - radius.toFloat() * 0.4f),
                    radius = radius.toFloat() * 1.3f,
                ),
                radius = radius.toFloat(),
                center = Offset(cx, cy),
            )

            val landPath = Path()
            val highlightPath = Path()
            val borderPath = Path()
            val highlightBorderPath = Path()
            for (country in w.countries) {
                if (cosCentral(country.centroid.lat, country.centroid.lng, cLat, cLng) < FAR_CULL_COS) continue
                val hot = country.iso2 in highlightCodes
                appendFill(if (hot) highlightPath else landPath, country.rings, cx, cy, radius, cLat, cLng)
                appendBorder(if (hot) highlightBorderPath else borderPath, country.rings, cx, cy, radius, cLat, cLng)
            }
            drawPath(landPath, land)
            drawPath(highlightPath, highlight, alpha = 0.28f)
            drawPath(borderPath, border, style = Stroke(width = (1.1f + (zoom - 1f) * 0.05f).coerceAtMost(3f)))
            // Visited countries get a thicker accent outline: "you have been here".
            drawPath(highlightBorderPath, highlight, alpha = 0.9f, style = Stroke(width = (2.2f + (zoom - 1f) * 0.08f).coerceAtMost(5f)))
            drawCircle(border, radius.toFloat(), Offset(cx, cy), style = Stroke(width = 2f))

            // Country names, only for the visited ones, faint so they never crowd the map.
            for (country in countries) {
                val shape = shapeByIso[country.countryCode.uppercase()] ?: continue
                val gp = GlobeProjection.project(shape.centroid.lat, shape.centroid.lng, cLat, cLng, radius)
                if (!gp.visible) continue
                countryPaint.alpha = (150 * fade).toInt()
                drawIntoCanvas { it.nativeCanvas.drawText(country.countryName, cx + gp.x, cy + gp.y, countryPaint) }
            }

            // A thumbnail pin + name for each visited city, over everything else.
            for (city in cities) {
                val gp = GlobeProjection.project(city.latitude, city.longitude, cLat, cLng, radius)
                if (!gp.visible) continue
                val px = cx + gp.x
                val py = cy + gp.y
                if (px < -60f || px > size.width + 60f || py < -60f || py > size.height + 60f) continue
                val pinBmp = thumbs[city.city + "|" + city.countryCode]
                if (pinBmp != null) {
                    // The exact OSM marker bitmap, drawn with its bottom (the pointer tip) on the spot,
                    // matching the OSM map's anchor; grows a little as the globe is zoomed in.
                    val pinScale = (1f + (zoom - 1f) * 0.05f).coerceAtMost(3f)
                    val dstW = 46f * density * pinScale
                    val dstH = dstW * pinBmp.height / pinBmp.width
                    drawImage(
                        image = pinBmp,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(pinBmp.width, pinBmp.height),
                        dstOffset = IntOffset((px - dstW / 2f).toInt(), (py - dstH).toInt()),
                        dstSize = IntSize(dstW.toInt(), dstH.toInt()),
                        alpha = fade,
                    )
                } else {
                    drawCircle(Color.White, 7f, Offset(px, py), alpha = fade)
                    drawCircle(highlight, 5f, Offset(px, py), alpha = fade)
                }
                cityPaint.alpha = (210 * fade).toInt()
                drawIntoCanvas { it.nativeCanvas.drawText(city.city, px, py + 24f, cityPaint) }
            }
        }

        // An opaque search bar along the bottom; tapping it raises the same drawer the Places screen uses,
        // which slides up above the keyboard with its field visible. It hides while the drawer is open, so
        // the pressed bar reads as having risen into the drawer.
        AnimatedVisibility(
            visible = chromeVisible && cities.isNotEmpty() && !showSearch,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(240)) + slideInVertically(tween(280)) { it / 2 },
            exit = fadeOut(tween(160)),
        ) {
            val pill = RoundedCornerShape(14.dp)
            Row(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp, start = 20.dp, end = 20.dp)
                    .fillMaxWidth()
                    .clip(pill)
                    .background(colors.bg2, pill)
                    .border(0.5.dp, colors.line2, pill)
                    .clickable { showSearch = true }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = colors.fgDim, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.map_search_places), color = colors.fgMute, fontSize = 14.sp)
            }
        }
        if (showSearch) {
            PlaceSearchSheet(
                cities = cities,
                onPick = { showSearch = false; onCityClick(it.latitude, it.longitude) },
                onDismiss = { showSearch = false },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            enter = fadeIn(tween(240)) + slideInVertically(tween(280)) { -it / 2 },
            exit = fadeOut(tween(160)),
        ) {
            FloatingHeader(
                title = stringResource(R.string.map_vector_title),
                onBack = onBack,
                trailing = {
                    // Open the appearance setting that toggles the map style.
                    IconBubble(
                        icon = Icons.Filled.Layers,
                        contentDescription = stringResource(R.string.settings_map_style),
                        onClick = onSwitchStyle,
                        diameter = 40.dp,
                        iconSize = 18.dp,
                        background = colors.pillBg,
                        borderColor = colors.pillBorder,
                        tint = colors.fgPrimary,
                    )
                },
            )
        }
    }
}

private fun coverModel(item: GalleryItem?): Any? = when (item) {
    is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
    is GalleryItem.Synced -> Uri.parse(item.local.uri)
    is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl
    null -> null
}

/** Append one country's fill rings to [path], clamping far-side vertices to the limb so a country larger
 *  than a hemisphere still fills correctly. */
internal fun appendFill(
    path: Path,
    rings: List<List<GeoCoord>>,
    cx: Float, cy: Float, radius: Double, cLat: Double, cLng: Double,
) {
    for (ring in rings) {
        var started = false
        for (pt in ring) {
            val gp = GlobeProjection.project(pt.lat, pt.lng, cLat, cLng, radius)
            val x: Float
            val y: Float
            if (gp.visible) {
                x = cx + gp.x; y = cy + gp.y
            } else {
                val b = GlobeProjection.bearing(pt.lat, pt.lng, cLat, cLng)
                x = cx + (radius * sin(b)).toFloat(); y = cy - (radius * cos(b)).toFloat()
            }
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        if (started) path.close()
    }
}

/** Append several rings' borders to [path]: only runs of consecutive near-side vertices. */
internal fun appendBorder(
    path: Path,
    rings: List<List<GeoCoord>>,
    cx: Float, cy: Float, radius: Double, cLat: Double, cLng: Double,
) {
    for (ring in rings) {
        var open = false
        for (pt in ring) {
            val gp = GlobeProjection.project(pt.lat, pt.lng, cLat, cLng, radius)
            if (gp.visible) {
                val x = cx + gp.x; val y = cy + gp.y
                if (!open) { path.moveTo(x, y); open = true } else path.lineTo(x, y)
            } else {
                open = false
            }
        }
    }
}

/** Inverse-project a tap to a coordinate and return the ISO code of a highlighted country it lands in. */
private fun countryAt(
    tapX: Float, tapY: Float, cx: Float, cy: Float, radius: Float,
    cLat: Double, cLng: Double, world: WorldMap, highlightCodes: Set<String>,
): String? {
    val dx = (tapX - cx).toDouble()
    val dy = -(tapY - cy).toDouble()
    val rho = sqrt(dx * dx + dy * dy)
    if (rho > radius || rho == 0.0) return null
    val c = asin((rho / radius).coerceIn(-1.0, 1.0))
    val cLatR = Math.toRadians(cLat)
    val lat = Math.toDegrees(asin(cos(c) * sin(cLatR) + dy * sin(c) * cos(cLatR) / rho))
    val lng = cLng + Math.toDegrees(atan2(dx * sin(c), rho * cos(c) * cos(cLatR) - dy * sin(c) * sin(cLatR)))
    for (country in world.countries) {
        if (country.iso2 !in highlightCodes) continue
        if (country.rings.any { pointInRing(lat, lng, it) }) return country.iso2
    }
    return null
}

private fun pointInRing(lat: Double, lng: Double, ring: List<GeoCoord>): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val a = ring[i]; val b = ring[j]
        if ((a.lat > lat) != (b.lat > lat)) {
            val x = (b.lng - a.lng) * (lat - a.lat) / (b.lat - a.lat) + a.lng
            if (lng < x) inside = !inside
        }
        j = i
    }
    return inside
}

/** A country is skipped when its centre is more than about 99 degrees from the view centre, i.e. clearly
 *  round the back, so a back-side outline cannot bleed through onto the near disc. */
internal const val FAR_CULL_COS = -0.15

internal fun cosCentral(latDeg: Double, lngDeg: Double, cLatDeg: Double, cLngDeg: Double): Double {
    val lat = Math.toRadians(latDeg)
    val cLat = Math.toRadians(cLatDeg)
    val dLng = Math.toRadians(lngDeg) - Math.toRadians(cLngDeg)
    return sin(cLat) * sin(lat) + cos(cLat) * cos(lat) * cos(dLng)
}

