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

package eu.akoos.photos.presentation.map

import android.Manifest
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import eu.akoos.photos.presentation.map.vector.PinFootprint
import eu.akoos.photos.presentation.map.vector.keptPins
import eu.akoos.photos.R
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.lock.runWhenOpen
import eu.akoos.photos.presentation.places.PlaceSearchSheet
import eu.akoos.photos.presentation.theme.AppColors
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

// World-view fallback when the library has no located photos yet — a low zoom centred on the
// equator/prime-meridian shows the whole map rather than dropping the user on a blank ocean tile.
private const val WORLD_ZOOM = 5.0
private const val LOCATED_ZOOM = 5.0

// A map opened on one place starts at city level, well inside the 5 (country) to 15 (district) range the
// view allows, so the place reads with its surroundings.
private const val FOCUS_ZOOM = 13.0

// Coil decode target for the pin thumbnail — small, since the visible body is ~52dp. Cover-crop
// happens when the bitmap is composited into the rounded body.
internal const val PIN_THUMB_PX = 120

/**
 * Map page. Plots every geotagged photo for the account on an OpenStreetMap canvas. A plain
 * floating header sits over the map and a bottom bar opens the shared place search; the map itself
 * is an osmdroid [MapView] driven through an [AndroidView] with its lifecycle bridged via
 * [DisposableEffect].
 *
 * On entry the screen requests [Manifest.permission.ACCESS_MEDIA_LOCATION] (Android 10+) when
 * it isn't already held, and — once granted or already present — kicks the one-shot GPS
 * backfill so the location table fills in while the user looks at the map.
 *
 * Each city is plotted as one rounded-rectangle thumbnail pin (Google-Photos style). A placeholder
 * pin drops immediately; the thumbnail is loaded off-thread through Coil and swapped in once
 * decoded. Tapping a pin opens the full-screen place page ([onOpenPlace]) for the pin's
 * coordinates, the same located-photos view the world map and the Places browser open, so a place
 * opens one way everywhere. A pin that would overlap an earlier one shrinks to a small dot until a zoom gives it room.
 *
 * Opened with a [focus], the map starts centred on that place at city level with an accent marker on the
 * point, and the located photos plot around it without moving the camera.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onBack: () -> Unit,
    onOpenPlace: (latitude: Double, longitude: Double) -> Unit = { _, _ -> },
    onSwitchStyle: () -> Unit = {},
    focus: MapFocus? = null,
    vm: MapViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val isDark = !colors.isLight
    val context = LocalContext.current
    val pins by vm.pins.collectAsStateWithLifecycle()
    val placesLoaded by vm.placesLoaded.collectAsStateWithLifecycle()
    val density = LocalDensity.current.density

    // Bottom place search: a tap on the search bar raises the shared places drawer over the map.
    var showSearch by remember { mutableStateOf(false) }
    val cities by vm.cities.collectAsStateWithLifecycle()

    // Held false until the camera has been positioned on real data; the opaque cover over the map reads
    // from it, so the one-time re-centre happens behind the spinner instead of as a visible jump.
    var centered by remember { mutableStateOf(false) }

    // Set once the camera has been placed on the focus or on the located photos. A later pins emission (a
    // thumbnail decrypt, a backfill write) then re-plots the markers without pulling the camera back from
    // wherever the user has moved it. The world-view fallback leaves it unset, so fixes a backfill finds
    // afterwards still get framed once.
    var framed by remember { mutableStateOf(false) }

    // Staggered entrance, mirroring the globe: the map settles first, then a beat later the header and
    // bottom search fade and slide in, so the map reads before the chrome lands.
    var chromeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(200); chromeVisible = true }

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
        // Cloud photos carry their GPS in the encrypted XAttr — no permission needed, so start that
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

    // Build the MapView once; the lifecycle bridge below resumes/pauses/detaches it. Keeping the
    // instance in remember avoids re-inflating the heavy view on every recomposition.
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Hide the legacy +/- zoom buttons; pinch-zoom (multitouch) is the modern affordance.
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            // Zoom-out floor = country level (not the whole continent), zoom-in cap = district
            // level (not street) — keeps the view between "a country" and "a part of a city".
            setMinZoomLevel(5.0)
            setMaxZoomLevel(15.0)
            // One world only: stop the map duplicating, and clamp vertical panning so scrolling
            // up/down can't reveal grey space or a second copy past the poles.
            setVerticalMapRepetitionEnabled(false)
            setHorizontalMapRepetitionEnabled(false)
            val ts = MapView.getTileSystem()
            setScrollableAreaLimitLatitude(ts.maxLatitude, ts.minLatitude, 0)
        }
    }

    // osmdroid drives tile threads + the location engine off the view's lifecycle hooks. Bridge them
    // to the SCREEN's lifecycle (not just composition) so the tile threads pause when the app is
    // backgrounded while this screen is still on the back stack, and detach when the composable leaves.
    val mapLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(mapView, mapLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        mapLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            mapLifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Dark mode: invert the light MAPNIK tiles via the tiles-overlay colour filter so the map reads
    // as a dark surface; clear it (null) in light mode. Keyed on the boolean so it follows a live
    // theme flip while the page is open.
    LaunchedEffect(mapView, isDark) {
        mapView.overlayManager.tilesOverlay.setColorFilter(if (isDark) darkTileFilter() else null)
        mapView.invalidate()
    }

    // The place the map was opened on, in the accent. Rebuilt only when the place or the accent changes;
    // the plot below re-adds it after each clear, last so it draws over the photo pins.
    val focusColor = colors.accent.toArgb()
    val focusMarker = remember(mapView, focus, focusColor) {
        focus?.let { place ->
            Marker(mapView).apply {
                position = GeoPoint(place.latitude, place.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = BitmapDrawable(context.resources, focusMarkerBitmap(focusColor, density))
                // Not a tap target of its own: a tap falls through to the photo pin under it.
                setOnMarkerClickListener { _, _ -> false }
            }
        }
    }

    // Re-plot whenever the located set changes. Placeholder pins are added synchronously at the top
    // of this effect (before the first suspension point) so the map populates on the next frame;
    // the thumbnails then load off-thread and swap their icon in. Driving this from a LaunchedEffect
    // means a new `locations` emission cancels the previous run's in-flight loaders rather than
    // leaving them to repaint markers that were already cleared. The camera is placed once per visit:
    // on the focus when there is one, otherwise on the average of the points so the first frame frames
    // the user's photos, with a world view when nothing is located.
    LaunchedEffect(mapView, pins, placesLoaded, focusMarker) {
        mapView.overlays.clear()
        // A map opened on one place is framed on it from the start, so it reveals at once rather than
        // waiting on the located photos, which then plot around it.
        if (focus != null && !framed) {
            mapView.controller.setZoom(FOCUS_ZOOM)
            mapView.controller.setCenter(GeoPoint(focus.latitude, focus.longitude))
            framed = true
            centered = true
        }
        if (pins.isEmpty()) {
            focusMarker?.let { mapView.overlays.add(it) }
            // Nothing located yet and the query has not produced its first result: keep the cover's
            // spinner up and leave the camera alone, so the world view is never shown only to jump to
            // the fixes a moment later.
            if (!placesLoaded) {
                mapView.invalidate()
                return@LaunchedEffect
            }
            // The query has settled and the account genuinely has no located photos: rest on the world
            // view and reveal it as a world map, unless the camera is already placed.
            if (!centered) {
                mapView.controller.setZoom(WORLD_ZOOM)
                mapView.controller.setCenter(GeoPoint(0.0, 0.0))
            }
            mapView.invalidate()
            centered = true
            return@LaunchedEffect
        }

        // Sample down to the marker cap so a large located library stays cheap — evenly spaced so
        // the spread still reads, mirroring the Search map-preview card.
        val sampled = samplePins(pins)

        // One shared placeholder drawable across every pin until its thumbnail decodes.
        val placeholder = BitmapDrawable(context.resources, ThumbnailPin.placeholder(density))

        val markers = sampled.map { pin ->
            Marker(mapView).apply {
                position = GeoPoint(pin.latitude, pin.longitude)
                // Anchor at the pointer tip (centre-x, bottom) so the wedge points at the fix.
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = placeholder
                setOnMarkerClickListener { _, _ ->
                    onOpenPlace(pin.latitude, pin.longitude)
                    true
                }
            }.also { mapView.overlays.add(it) }
        }
        focusMarker?.let { mapView.overlays.add(it) }

        // Every pin keeps one size at every zoom. Where two would overlap, the busier city, earlier in the list, keeps
        // its pin and the other is left off the map until a zoom gives it room.
        val pinIcons = Array<android.graphics.drawable.Drawable>(markers.size) { placeholder }
        val shownAsPin = BooleanArray(markers.size) { true }
        fun showMarker(index: Int) {
            val marker = markers[index]
            marker.icon = pinIcons[index]
            marker.isEnabled = shownAsPin[index]
        }
        val screenPoint = android.graphics.Point()
        fun declutterPins() {
            if (mapView.width == 0 || mapView.height == 0) return
            val projection = mapView.projection
            val halfWidth = placeholder.intrinsicWidth / 2f
            val rise = placeholder.intrinsicHeight.toFloat()
            val footprints = markers.map { marker ->
                projection.toPixels(marker.position, screenPoint)
                PinFootprint(screenPoint.x.toFloat(), screenPoint.y.toFloat(), halfWidth, rise, 0f)
            }
            val kept = keptPins(footprints)
            for (index in markers.indices) {
                shownAsPin[index] = kept[index]
                showMarker(index)
            }
            mapView.invalidate()
        }
        val zoomListener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = false
            override fun onZoom(event: ZoomEvent?): Boolean {
                declutterPins()
                return false
            }
        }
        val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) declutterPins()
        }
        mapView.addMapListener(zoomListener)
        mapView.addOnLayoutChangeListener(layoutListener)
        try {
            if (!framed) {
                val avgLat = pins.sumOf { it.latitude } / pins.size
                val avgLon = pins.sumOf { it.longitude } / pins.size
                mapView.controller.setZoom(LOCATED_ZOOM)
                mapView.controller.setCenter(GeoPoint(avgLat, avgLon))
                framed = true
            }
            mapView.invalidate()
            declutterPins()
            // Camera now frames the located photos (or the focus); drop the cover so the map is revealed
            // already centred, ahead of the thumbnails that swap in below.
            centered = true

            // Now fill in thumbnails. Each pin's resolved library item supplies the same image source the
            // gallery cell uses — a local content uri or a cloud thumbnail, both decoded through Coil — so
            // cloud fixes load their photo too. An unresolved pin (no library item yet) keeps the placeholder.
            sampled.forEachIndexed { index, pin ->
                val imageData = pin.item?.let { photoCellInputsFor(it).imageData } ?: return@forEachIndexed
                val pinBitmap = runCatching {
                    val req = ImageRequest.Builder(context)
                        .data(imageData)
                        .size(PIN_THUMB_PX)
                        // Software bitmap so it can be composited onto our Canvas.
                        .allowHardware(false)
                        .build()
                    val result = context.imageLoader.execute(req)
                    // Don't recycle this: Coil may hand back a memory-cache-owned bitmap, and the
                    // composite reads from it. The pin is a fresh bitmap we own.
                    val source = (result as? SuccessResult)?.drawable?.toBitmap()
                        ?: return@runCatching null
                    ThumbnailPin.build(source, density)
                }.getOrNull() ?: return@forEachIndexed
                pinIcons[index] = BitmapDrawable(context.resources, pinBitmap)
                showMarker(index)
                mapView.invalidate()
            }
            // Keep the listeners for as long as these pins are on the map.
            awaitCancellation()
        } finally {
            mapView.removeMapListener(zoomListener)
            mapView.removeOnLayoutChangeListener(layoutListener)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg0),
    ) {
        // The map bleeds to the screen edges, full-bleed like the globe; the floating header and the
        // bottom search bar draw over it with their own insets.
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
            )

            // OpenStreetMap tile attribution — required by OSM's tile usage policy for the standard
            // MAPNIK tiles. A fixed dark scrim + light text keeps it legible over both the light
            // tiles and the dark-filtered tiles, regardless of theme.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.map_osm_attribution),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                )
            }

            // Opaque cover over the map, held until the camera is centred on real data so the one-time
            // re-centre lands off-screen; it fades out on [centered], revealing the already framed map
            // rather than a visible jump from the world view.
            AnimatedVisibility(
                visible = !centered,
                modifier = Modifier.fillMaxSize(),
                exit = fadeOut(tween(240)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.bg0),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
        }

        // An opaque search bar along the bottom raises the same drawer the Places screen uses; it hides
        // while the drawer is open, so the pressed bar reads as having risen into the drawer.
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
                onPick = { city ->
                    showSearch = false
                    onOpenPlace(city.latitude, city.longitude)
                },
                onDismiss = { showSearch = false },
            )
        }

        // Floating header, a later sibling drawn over the map, carrying its own status-bar padding. It
        // waits for the stagger, then fades and slides in over the map. No scrim: the header's dark
        // veil is invisible over the globe but strong over the bright OSM tiles.
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            enter = fadeIn(tween(240)) + slideInVertically(tween(280)) { -it / 2 },
            exit = fadeOut(tween(160)),
        ) {
            FloatingHeader(
                title = stringResource(R.string.map_title),
                onBack = onBack,
                showScrim = false,
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

/**
 * Colour filter that turns the light MAPNIK tiles into a dark basemap (dark land and water, readable
 * labels). The invert is followed by a half turn of hue, so water stays blue and vegetation green instead
 * of turning orange and purple, and saturation is muted afterwards so the colours don't read as neon.
 */
internal fun darkTileFilter(): android.graphics.ColorMatrixColorFilter =
    android.graphics.ColorMatrixColorFilter(darkTileMatrix())

internal fun darkTileMatrix(): android.graphics.ColorMatrix {
    val m = android.graphics.ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    m.postConcat(
        android.graphics.ColorMatrix(
            floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    m.postConcat(android.graphics.ColorMatrix().apply { setSaturation(0.55f) })
    return m
}

/**
 * The focus marker: an [accent] dot in the white frame and soft drop shadow the photo pins wear, inside a
 * faint accent halo, so the place the map was opened on stands apart from the pins around it. Sized off
 * the screen [density]; the caller anchors it at its centre, on the point itself.
 */
private fun focusMarkerBitmap(accent: Int, density: Float): android.graphics.Bitmap {
    val haloRadius = 20f * density
    val frameRadius = 10.5f * density
    val dotRadius = 8f * density
    val size = (haloRadius * 2).toInt()
    val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val c = size / 2f
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = accent
    paint.alpha = 60
    canvas.drawCircle(c, c, haloRadius - 1f, paint)
    // The shadow margin (blur plus drop) stays inside the halo, so it is never clipped at the edge.
    paint.color = android.graphics.Color.WHITE
    paint.setShadowLayer(5f * density, 0f, 2f * density, android.graphics.Color.argb(70, 0, 0, 0))
    canvas.drawCircle(c, c, frameRadius, paint)
    paint.clearShadowLayer()
    paint.color = accent
    canvas.drawCircle(c, c, dotRadius, paint)
    return out
}
