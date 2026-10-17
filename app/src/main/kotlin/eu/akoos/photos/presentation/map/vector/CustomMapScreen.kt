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

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.conflate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.core.graphics.drawable.toBitmap
import eu.akoos.photos.R
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.domain.usecase.PlaceCity
import eu.akoos.photos.presentation.map.MapFocus
import eu.akoos.photos.presentation.map.MapViewModel
import eu.akoos.photos.presentation.map.ThumbnailPin
import eu.akoos.photos.presentation.map.samplePins
import eu.akoos.photos.presentation.map.MapPin
import eu.akoos.photos.presentation.map.PIN_THUMB_PX
import eu.akoos.photos.presentation.places.PlaceSearchSheet
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.util.appLocale
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.lock.runWhenOpen
import eu.akoos.photos.util.countryName
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A self-contained vector globe: it draws public-domain Natural Earth country outlines on a rotating
// orthographic sphere (see GlobeProjection + WorldMapData), then surfaces only WHERE THE ACCOUNT HAS
// BEEN — visited countries filled and outlined, a thumbnail pin plus label for each visited city. It
// renders everything itself, with no map tiles.

// The zoom a globe opened on one place starts at: the place's region, roughly a thousand kilometres across
// the screen's shorter side.
private const val FOCUS_ZOOM = 12f

// How far a pinch can zoom the globe in. Deep enough that cities a few kilometres apart each get room.
private const val MAX_ZOOM = 1500f
// The view the globe opens on: over Europe.
private const val START_LAT = 47f
private const val START_LNG = 15f

// The province borders show once the globe is this big (in pixels of radius), and the names of places up to this
// many at a time.
private const val MAX_PLACE_LABELS = 90

// The picture is drawn this much larger than the screen on every side (a fraction of the shorter side), so moving the
// globe shows picture, not blank, at the edges until the next one is ready.
private const val OVERSCAN_FRACTION = 0.12f

// A globe up to this many half-diagonals across is drawn straight to the screen; a larger one uses the finished picture.
private const val DIRECT_DRAW_FACTOR = 1.4

// A spot farther from the middle than this share of the radius is not kept under the finger (it is near the edge).
private const val ANCHOR_REACH = 0.8

// How far from a city's dot a tap still counts as that city.
private const val DOT_HIT_DP = 16f

/**
 * A rotating vector globe that surfaces the account's own places. The whole world is drawn as a light
 * base map; the countries with photos are washed in the accent and given a thicker outline, and each
 * city with photos gets a rounded thumbnail of a photo taken there plus its name. Drag spins the globe
 * with a bit of glide, pinch zooms, a tap on a city thumbnail opens its photos and a tap on a highlighted
 * country opens its cities. Reached from the existing map and fully removable with it. Opened with a
 * [focus], the globe starts turned to that place and zoomed in to its region, with an accent dot on it.
 */
@Composable
fun CustomMapScreen(
    onBack: () -> Unit,
    onCountryClick: (countryCode: String) -> Unit,
    onCityClick: (latitude: Double, longitude: Double) -> Unit,
    onSwitchStyle: () -> Unit = {},
    focus: MapFocus? = null,
    vm: MapViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val mapPlaces by vm.mapPlaces.collectAsStateWithLifecycle()
    val countries = mapPlaces.countries
    val cities = mapPlaces.cities
    val placesLoaded = mapPlaces.loaded
    val pins by vm.pins.collectAsStateWithLifecycle()

    // Permission gate: ACCESS_MEDIA_LOCATION only exists on Android 10+. On older OS versions
    // EXIF GPS is readable without it, so we treat the grant as implicitly present and go
    // straight to the backfill.
    val needsMediaLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // The on-device EXIF half needs the grant; a denial just leaves it un-run (the cloud half
        // already started below). A grant fills the table from local GPS tags.
        if (granted) vm.startLocalBackfill()
    }

    // A map restored under the app lock (a cold start after the process was reclaimed) asks here too, and
    // the system dialog would open above the lock, so the request waits for the unlock.
    val appLock = LocalAppLock.current
    LaunchedEffect(Unit) {
        // Cloud photos carry their GPS in the encrypted XAttr, so no permission is needed; start that
        // half immediately and unconditionally. The on-device EXIF half is gated on the grant below.
        vm.startCloudBackfill()
        val alreadyGranted = !needsMediaLocation || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_MEDIA_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            vm.startLocalBackfill()
        } else {
            appLock.runWhenOpen { permissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
        }
    }

    val world by produceState<WorldDetail?>(null) { value = WorldDetailData.load(context) }
    val shapeByIso = remember(world) { world?.countries?.associateBy { it.iso2 }.orEmpty() }
    val highlightCodes = remember(countries) { countries.mapTo(HashSet()) { it.countryCode.uppercase() } }
    val hotOwner = remember(world, highlightCodes) {
        world?.countries?.let { all -> BooleanArray(all.size) { all[it].iso2 in highlightCodes } } ?: BooleanArray(0)
    }
    // The provinces the account has photos in: only these are tinted, not the whole country. A country the data
    // has no provinces for is tinted as a whole.
    val provinceLocator = remember(world) { world?.let { LazyProvinceLocator(it) } }
    var visitedProvinces by remember { mutableStateOf(BooleanArray(0)) }
    LaunchedEffect(world, pins) {
        visitedProvinces = withContext(Dispatchers.Default) {
            val locator = provinceLocator ?: return@withContext BooleanArray(0)
            val seen = HashMap<Long, Pair<Float, Float>>()
            for (pin in pins) {
                val key = Math.round((pin.latitude + 100.0) * 50.0) * 100_000L + Math.round((pin.longitude + 200.0) * 50.0)
                if (key !in seen) seen[key] = pin.latitude.toFloat() to pin.longitude.toFloat()
            }
            locator.locator.visited(seen.values)
        }
    }
    val provinceIsos = remember(world) { world?.provinces?.mapTo(HashSet()) { it.iso2 }.orEmpty() }
    // Populated places, biggest first, so the larger a place the earlier it claims room for its name.
    val placesByPopulation = remember(world) { world?.places?.sortedByDescending { it.population }.orEmpty() }
    // The visited countries' names in the app's language, display only: fills and taps key on the code.
    val locale = appLocale()
    val countryLabels = remember(countries, locale) {
        countries.associate { it.countryCode to countryName(it.countryCode, locale, it.countryName) }
    }

    // The globe plots the same pins as the OSM map: one per city, the same cap, the same pin and thumbnail. A pin
    // shows the neutral tile until its thumbnail decodes.
    val shownPins = remember(pins) { samplePins(pins) }
    val pinThumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    val placeholderPin = remember(density) { ThumbnailPin.placeholder(density).asImageBitmap() }
    LaunchedEffect(shownPins) {
        for (pin in shownPins) {
            if (pinThumbs.containsKey(pin.id)) continue
            val imageData = pin.item?.let { photoCellInputsFor(it).imageData } ?: continue
            val source = runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context).data(imageData).size(PIN_THUMB_PX).allowHardware(false).build(),
                )
            }.getOrNull()?.let { (it as? SuccessResult)?.drawable?.toBitmap() } ?: continue
            pinThumbs[pin.id] = ThumbnailPin.build(source, density).asImageBitmap()
        }
    }

    // A focus decides the view up front: the globe opens turned to the place and zoomed in to its region,
    // within the latitude band a drag allows.
    // The view is saved with the screen, so coming back from a place opens the globe where it was left.
    var centerLat by rememberSaveable { mutableStateOf(focus?.latitude?.toFloat()?.coerceIn(-85f, 85f) ?: START_LAT) }
    var centerLng by rememberSaveable { mutableStateOf(focus?.longitude?.toFloat() ?: START_LNG) }
    var zoom by rememberSaveable { mutableStateOf(if (focus != null) FOCUS_ZOOM else 1f) }
    var centered by rememberSaveable { mutableStateOf(false) }
    val flingScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    // True while a finger is on the globe or it is gliding: the outlines are drawn coarser then and sharpen at rest.
    var moving by remember { mutableStateOf(false) }
    // Staggered entrance: the globe zooms in first, then the header and bottom search follow.
    var chromeVisible by remember { mutableStateOf(false) }
    // The latest finished base picture of the globe (see the renderer below).
    var baseFrame by remember { mutableStateOf<BaseFrame?>(null) }
    val fade by animateFloatAsState(if (world != null && centered && baseFrame != null) 1f else 0f, label = "mapFade")

    // The globe opens over Europe and fades in once the places have their first result. A focus has already
    // placed the view, so it keeps it and only waits for the places to fade in with them.
    LaunchedEffect(placesLoaded) {
        if (placesLoaded) centered = true
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
    val placePaint = remember(colors, density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = colors.fgPrimary.toArgb()
            textSize = 11f * density
            typeface = android.graphics.Typeface.DEFAULT
        }
    }
    val placeHaloPaint = remember(colors, density, ocean) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = land.toArgb()
            textSize = 11f * density
            typeface = android.graphics.Typeface.DEFAULT
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeJoin = android.graphics.Paint.Join.ROUND
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

    // The base picture is drawn off the main thread into a bitmap and the screen shows the latest one, shifted and
    // scaled to the current view until the next is ready, so moving the globe never waits for the outlines.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val baseRenderer = remember { GlobeBaseRenderer() }
    val baseStyle = remember(world, ocean, land, border, highlight, colors.isLight, density, hotOwner, visitedProvinces, provinceIsos) {
        world?.let { BaseStyle(ocean, land, border, highlight, colors.isLight, density, it, hotOwner, visitedProvinces, provinceIsos) }
    }
    LaunchedEffect(baseStyle, canvasSize) {
        val style = baseStyle ?: return@LaunchedEffect
        if (canvasSize.width == 0 || canvasSize.height == 0) return@LaunchedEffect
        snapshotFlow {
            BaseView(
                centerLat.toDouble(), centerLng.toDouble(), zoom, moving, canvasSize.width, canvasSize.height,
                pad = (min(canvasSize.width, canvasSize.height) * OVERSCAN_FRACTION).toInt(),
            )
        }.conflate().collect { requested ->
            val drawn = withContext(Dispatchers.Default) { baseRenderer.render(style, requested) }
            baseRenderer.onScreen = drawn.bitmap
            baseFrame = drawn
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.bg0)) {
        if (world == null || !centered || baseFrame == null) {
            CircularProgressIndicator(color = colors.accent, modifier = Modifier.align(Alignment.Center))
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(world, shownPins, highlightCodes) {
                    // One handler for rotate/pinch, a glide after a flick, AND tap: a gesture that barely
                    // moved is treated as a tap and hit-tested, so a separate tap detector can't miss it.
                    awaitEachGesture {
                        flingJob?.cancel()
                        val tracker = VelocityTracker()
                        val down = awaitFirstDown(requireUnconsumed = false)
                        moving = true
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val downPos = down.position
                        var maxMove = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val before = event.calculateCentroid(useCurrent = false)
                            val now = event.calculateCentroid(useCurrent = true)
                            if (before.isSpecified && now.isSpecified && (zoomChange != 1f || before != now)) {
                                val gx = size.width / 2f
                                val gy = size.height / 2f
                                val oldRadius = globeRadius(size.width.toFloat(), size.height.toFloat(), zoom).toDouble()
                                val anchor = geoAt(before.x, before.y, gx, gy, oldRadius, centerLat.toDouble(), centerLng.toDouble())
                                if (zoomChange != 1f) zoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                                val newRadius = globeRadius(size.width.toFloat(), size.height.toFloat(), zoom).toDouble()
                                // The spot between the fingers stays between them, whatever the zoom does. Near the
                                // edge of the globe that cannot be kept, so there the globe turns by the finger's travel.
                                val nearCentre = hypot((before.x - gx).toDouble(), (before.y - gy).toDouble()) < oldRadius * ANCHOR_REACH
                                val kept = if (anchor != null && nearCentre) {
                                    centerKeepingPoint(
                                        anchor.first, anchor.second, now.x, now.y, gx, gy, newRadius,
                                        centerLat.toDouble(), centerLng.toDouble(),
                                    )
                                } else {
                                    null
                                }
                                if (kept != null) {
                                    centerLat = kept.first.toFloat()
                                    centerLng = kept.second.toFloat()
                                } else {
                                    val perPixel = degreesPerPixel(newRadius).toFloat()
                                    val acrossScale = 1f / max(0.1f, cos(Math.toRadians(centerLat.toDouble())).toFloat())
                                    centerLng -= (now.x - before.x) * perPixel * acrossScale
                                    centerLat = (centerLat + (now.y - before.y) * perPixel).coerceIn(-MAX_CENTER_LAT.toFloat(), MAX_CENTER_LAT.toFloat())
                                }
                            }
                            event.changes.firstOrNull()?.let {
                                tracker.addPosition(it.uptimeMillis, it.position)
                                maxMove = max(maxMove, (it.position - downPos).getDistance())
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                        if (maxMove < 24f) {
                            moving = false
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val radius = min(size.width, size.height) / 2f * 0.92f * zoom
                            val cLat = centerLat.toDouble()
                            val cLng = centerLng.toDouble()
                            val tapped = placePins(
                                shownPins, size.width.toFloat(), size.height.toFloat(), radius.toDouble(), cLat, cLng,
                                placeholderPin.width, placeholderPin.height,
                            ).sortedByDescending { it.asPin }
                                .firstOrNull { it.hit(downPos.x, downPos.y, density, placeholderPin.width, placeholderPin.height) }
                            if (tapped != null) {
                                onCityClick(tapped.pin.latitude, tapped.pin.longitude)
                            } else {
                                val w = world
                                val hit = if (w != null) countryAtDetail(downPos.x, downPos.y, cx, cy, radius, cLat, cLng, w, hotOwner) else null
                                if (hit != null) onCountryClick(hit)
                            }
                        } else {
                            val v = tracker.calculateVelocity()
                            flingJob = flingScope.launch {
                                var vx = v.x
                                var vy = v.y
                                while (abs(vx) > 40f || abs(vy) > 40f) {
                                    withFrameNanos { }
                                    // One pixel of the glide turns the globe exactly as far as one pixel of a drag.
                                    val perPixel = degreesPerPixel(
                                        globeRadius(size.width.toFloat(), size.height.toFloat(), zoom).toDouble(),
                                    ).toFloat()
                                    val acrossScale = 1f / max(0.1f, cos(Math.toRadians(centerLat.toDouble())).toFloat())
                                    centerLng -= (vx / 60f) * perPixel * acrossScale
                                    centerLat = (centerLat + (vy / 60f) * perPixel).coerceIn(-MAX_CENTER_LAT.toFloat(), MAX_CENTER_LAT.toFloat())
                                    vx *= 0.90f
                                    vy *= 0.90f
                                }
                                moving = false
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

            val view = GlobeView(cx, cy, radius, cLat, cLng, size.width, size.height)
            val style = baseStyle
            if (style != null && radius <= view.halfDiagonal * DIRECT_DRAW_FACTOR) {
                // A globe that still shows its edge turns inside it, which a shifted picture cannot show, and it is
                // cheap enough to draw in place.
                val current = BaseView(cLat, cLng, zoom, moving, size.width.toInt(), size.height.toInt())
                if (fade < 0.999f) {
                    drawIntoCanvas { it.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = fade }) }
                    drawGlobeBase(style, current)
                    drawIntoCanvas { it.restore() }
                } else {
                    drawGlobeBase(style, current)
                }
            } else {
                baseFrame?.let { frame ->
                    // Until the next picture is ready the latest one is shifted and scaled to the current view.
                    val placement = placeFrame(frame, cLat, cLng, radius)
                    withTransform({
                        translate(placement.shiftX, placement.shiftY)
                        scale(placement.scale, placement.scale, Offset(cx, cy))
                    }) {
                        drawImage(frame.bitmap, Offset(-frame.view.pad.toFloat(), -frame.view.pad.toFloat()), alpha = fade)
                    }
                }
            }

            // Country names, only for the visited ones, faint so they never crowd the map.
            for (country in countries) {
                val shape = shapeByIso[country.countryCode.uppercase()] ?: continue
                val gp = GlobeProjection.project(shape.centreLat.toDouble(), shape.centreLng.toDouble(), cLat, cLng, radius)
                if (!gp.visible) continue
                countryPaint.alpha = (150 * fade).toInt()
                val label = countryLabels[country.countryCode] ?: country.countryName
                drawIntoCanvas { it.nativeCanvas.drawText(label, cx + gp.x, cy + gp.y, countryPaint) }
            }

            // The same pins as the OSM map: a pin where it has room, none until a zoom gives it space. The
            // pins earlier in the list claim their room first.
            val placements = placePins(shownPins, size.width, size.height, radius, cLat, cLng, placeholderPin.width, placeholderPin.height)

            // Names of populated places, biggest first, each only where it fits clear of the names and pins
            // already placed. More of them appear as the globe is zoomed in.
            val labelZoom = labelZoomFor(radius, density)
            val footprints = ArrayList<PinFootprint>()
            for (place in placements) {
                if (place.asPin) {
                    footprints += PinFootprint(place.x, place.y, placeholderPin.width / 2f, placeholderPin.height.toFloat(), 0f)
                }
            }
            val pinFootprintCount = footprints.size
            val shown = ArrayList<PlaceLabelSpot>()
            val dotRadius = 2.6f * density
            val gap = 4f * density
            // The list is biggest first, so the walk ends at the first place too small to show even in the middle.
            val centreThreshold = populationForLabel(labelZoom)
            for (place in placesByPopulation) {
                if (shown.size >= MAX_PLACE_LABELS) break
                if (place.population < centreThreshold) break
                val gp = GlobeProjection.project(place.lat.toDouble(), place.lng.toDouble(), cLat, cLng, radius)
                if (!gp.visible) continue
                val x = cx + gp.x
                val y = cy + gp.y
                if (x < 0f || x > size.width || y < 0f || y > size.height) continue
                val fromCentre = hypot((x - cx).toDouble(), (y - cy).toDouble()) / view.halfDiagonal
                if (place.population < populationForLabelAt(labelZoom, fromCentre)) continue
                val textWidth = placePaint.measureText(place.name)
                footprints += PinFootprint(
                    x = x + dotRadius + gap + textWidth / 2f,
                    y = y,
                    halfWidth = textWidth / 2f + dotRadius + gap,
                    above = placePaint.textSize * 0.6f,
                    below = placePaint.textSize * 0.6f,
                )
                shown += PlaceLabelSpot(place.name, x, y)
            }
            val labelKept = keptPins(footprints, gap = 2f * density)
            placePaint.alpha = (230 * fade).toInt()
            placeHaloPaint.alpha = (200 * fade).toInt()
            for (i in shown.indices) {
                if (!labelKept[pinFootprintCount + i]) continue
                val spot = shown[i]
                drawCircle(Color.White, dotRadius + 1f * density, Offset(spot.x, spot.y), alpha = fade)
                drawCircle(land, dotRadius, Offset(spot.x, spot.y), alpha = fade)
                drawIntoCanvas {
                    val textX = spot.x + dotRadius + gap
                    val textY = spot.y + placePaint.textSize * 0.35f
                    it.nativeCanvas.drawText(spot.name, textX, textY, placeHaloPaint)
                    it.nativeCanvas.drawText(spot.name, textX, textY, placePaint)
                }
            }

            for (place in placements) {
                if (!place.asPin) continue
                val image = pinThumbs[place.pin.id] ?: placeholderPin
                // The bottom of the pin (its pointer tip) sits on the spot, as on the OSM map.
                drawImage(image, Offset(place.x - image.width / 2f, place.y - image.height), alpha = fade)
            }

            // The place the globe was opened on: an accent dot in a white ring inside a faint accent halo,
            // drawn last so it sits over the city pins, and kept compact so the name under a pin's tip
            // stays mostly readable.
            if (focus != null) {
                val gp = GlobeProjection.project(focus.latitude, focus.longitude, cLat, cLng, radius)
                if (gp.visible) {
                    val at = Offset(cx + gp.x, cy + gp.y)
                    drawCircle(highlight, 16f * density, at, alpha = 0.28f * fade)
                    drawCircle(Color.White, 7.5f * density, at, alpha = fade)
                    drawCircle(highlight, 5.5f * density, at, alpha = fade)
                }
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

/** A pin placed on screen: [asPin] is true where it is drawn in full, false where it waits for room. */
private class PinPlacement(val pin: MapPin, val x: Float, val y: Float, val asPin: Boolean) {
    /** Whether a tap at ([tapX], [tapY]) lands on this pin, or on its dot. */
    fun hit(tapX: Float, tapY: Float, density: Float, pinWidth: Int, pinHeight: Int): Boolean {
        if (!asPin) return false
        return tapX in (x - pinWidth / 2f)..(x + pinWidth / 2f) && tapY in (y - pinHeight)..(y + 8f)
    }
}

/**
 * The pins on the visible face of the globe that fall on screen, each as a full pin where the ones ahead of it
 * leave room and not at all where they do not, by the same rule the OSM map uses. Both the drawing and the tap use
 * this, so a tap always meets what is shown.
 */
private fun placePins(
    pins: List<MapPin>,
    width: Float,
    height: Float,
    radius: Double,
    cLat: Double,
    cLng: Double,
    pinWidth: Int,
    pinHeight: Int,
): List<PinPlacement> {
    val cx = width / 2f
    val cy = height / 2f
    val onScreen = ArrayList<MapPin>()
    val spots = ArrayList<Offset>()
    val footprints = ArrayList<PinFootprint>()
    for (pin in pins) {
        val gp = GlobeProjection.project(pin.latitude, pin.longitude, cLat, cLng, radius)
        if (!gp.visible) continue
        val x = cx + gp.x
        val y = cy + gp.y
        if (x < -60f || x > width + 60f || y < -60f || y > height + 60f) continue
        onScreen += pin
        spots += Offset(x, y)
        footprints += PinFootprint(x = x, y = y, halfWidth = pinWidth / 2f, above = pinHeight.toFloat(), below = 0f)
    }
    val kept = keptPins(footprints)
    return onScreen.indices.map { i -> PinPlacement(onScreen[i], spots[i].x, spots[i].y, kept[i]) }
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

/** A country is skipped when its centre is more than about 99 degrees from the view centre, i.e. clearly
 *  round the back, so a back-side outline cannot bleed through onto the near disc. */
internal const val FAR_CULL_COS = -0.15

internal fun cosCentral(latDeg: Double, lngDeg: Double, cLatDeg: Double, cLngDeg: Double): Double {
    val lat = Math.toRadians(latDeg)
    val cLat = Math.toRadians(cLatDeg)
    val dLng = Math.toRadians(lngDeg) - Math.toRadians(cLngDeg)
    return sin(cLat) * sin(lat) + cos(cLat) * cos(lat) * cos(dLng)
}

/** A place name ready to draw: where its dot goes on screen. */
private class PlaceLabelSpot(val name: String, val x: Float, val y: Float)

/** Builds the province locator the first time it is asked for, off the main thread's drawing path. */
private class LazyProvinceLocator(private val detail: WorldDetail) {
    val locator: ProvinceLocator by lazy { ProvinceLocator(detail) }
}
