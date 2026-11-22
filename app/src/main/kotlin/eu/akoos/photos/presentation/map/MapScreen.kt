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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.presentation.common.LocalGlassBackdrop
import eu.akoos.photos.presentation.common.glassSource
import eu.akoos.photos.presentation.common.pillSurface
import eu.akoos.photos.presentation.common.rememberGlassBackdrop
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.lock.runWhenOpen
import eu.akoos.photos.presentation.places.PlaceSearchSheet
import eu.akoos.photos.presentation.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

// World-view fallback when the library has no located photos yet — a low zoom centred on the
// equator/prime-meridian shows the whole map rather than dropping the user on a blank ocean tile.
private const val WORLD_ZOOM = 5.0
private const val LOCATED_ZOOM = 5.0

// A map opened on one place starts at city level, well inside the 5 (country) to 15 (district) range the
// view allows, so the place reads with its surroundings.
private const val FOCUS_ZOOM = 13.0

// The deepest zoom, the tile source's own last level: street level, where the street a photo was taken in can be read.
private const val MAX_ZOOM = 19.0

// How far past the screen edges the pins are picked, as a scale of the view, so a pin is in place as it slides in.
private const val VIEW_MARGIN_SCALE = 1.4f

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
 * The located photos are grouped into pins by a grid over the map at the current zoom ([gridPins]), the way the
 * desktop map groups them: one rounded-rectangle thumbnail pin per cell, on the mean of its photos, wearing the
 * newest one and a count badge when it holds more than one. Zooming in splits a pin into the cells its photos
 * fall in. A placeholder pin drops immediately; the thumbnail is loaded off-thread through Coil and swapped in
 * once decoded. Tapping a pin opens exactly that pin's photos ([onOpenPin]); a place picked in the search opens
 * the place page ([onOpenPlace]).
 *
 * Opened with a [focus], the map starts centred on that place at city level with an accent marker on the
 * point, and the located photos plot around it without moving the camera.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onBack: () -> Unit,
    onOpenPlace: (latitude: Double, longitude: Double) -> Unit = { _, _ -> },
    onOpenPin: (MapPin) -> Unit = {},
    onSwitchStyle: () -> Unit = {},
    focus: MapFocus? = null,
    vm: MapViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val isDark = !colors.isLight
    val context = LocalContext.current
    val points by vm.points.collectAsStateWithLifecycle()
    val thumbnailUrls by vm.thumbnailUrls.collectAsStateWithLifecycle()
    val currentThumbnailUrls by rememberUpdatedState(thumbnailUrls)
    val currentOnOpenPin by rememberUpdatedState(onOpenPin)
    val density = LocalDensity.current.density
    val badgeTextPx = with(LocalDensity.current) { 12.sp.toPx() }

    // Bottom place search: a tap on the search bar raises the shared places drawer over the map.
    var showSearch by remember { mutableStateOf(false) }
    val cities by vm.cities.collectAsStateWithLifecycle()

    // Held false until the camera has been positioned on real data; the opaque cover over the map reads
    // from it, so the one-time re-centre happens behind the spinner instead of as a visible jump.
    var centered by remember { mutableStateOf(false) }

    // Set once the camera has been placed on the focus or on the located photos. A later points emission (a
    // backfill write) then re-plots the markers without pulling the camera back from
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

    // Glass for the floating header and search bar, over the plain page rather than the moving map.
    val glass = rememberGlassBackdrop(AppColors.current.bg0, heavy = true)
    // Build the MapView once; the lifecycle bridge below resumes/pauses/detaches it. Keeping the
    // instance in remember avoids re-inflating the heavy view on every recomposition.
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Hide the legacy +/- zoom buttons; pinch-zoom (multitouch) is the modern affordance.
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            // Zoom-out floor = country level (not the whole continent), zoom-in cap = street level, where
            // the street a photo was taken in can be read.
            setMinZoomLevel(5.0)
            setMaxZoomLevel(MAX_ZOOM)
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

    // The decoded pin thumbnails, by cover photo, kept across re-plots so a new emission never decodes them again.
    val pinThumbs = remember { pinThumbnailCache() }
    val askedCovers = remember { HashSet<String>() }
    // Moves, resizes and newly decrypted thumbnails ask for a re-plot; asks close together are answered once.
    val replot = remember { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(thumbnailUrls) { replot.trySend(Unit) }
    val accent = colors.accent.toArgb()

    // Re-plot whenever the located set changes. Placeholder pins are added at once and the thumbnails then load
    // off-thread and swap their icon in. Driving this from a LaunchedEffect means a new emission cancels the
    // previous run's in-flight loaders rather than leaving them to repaint markers that were already cleared.
    // The camera is placed once per visit: on the focus when there is one, otherwise on the average of the
    // points so the first frame frames the user's photos, with a world view when nothing is located.
    LaunchedEffect(mapView, points, focusMarker, accent, badgeTextPx) {
        mapView.overlays.clear()
        // A map opened on one place is framed on it from the start, so it reveals at once rather than
        // waiting on the located photos, which then plot around it.
        if (focus != null && !framed) {
            mapView.controller.setZoom(FOCUS_ZOOM)
            mapView.controller.setCenter(GeoPoint(focus.latitude, focus.longitude))
            framed = true
            centered = true
        }
        val located = points.orEmpty()
        if (located.isEmpty()) {
            focusMarker?.let { mapView.overlays.add(it) }
            // Nothing located yet and the query has not produced its first result: keep the cover's
            // spinner up and leave the camera alone, so the world view is never shown only to jump to
            // the fixes a moment later.
            if (points == null) {
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

        // Only the pins in view (plus a margin) get a marker and a decoded thumbnail, so thousands of photos stay cheap.
        val placeholderBitmap = ThumbnailPin.placeholder(density)
        val badge = PinCountBadge(density, badgeTextPx, accent)
        val cellPx = PIN_CELL_DP * density.toDouble()
        var grid: List<MapPin> = emptyList()
        var groupedZoom = Double.NaN
        // Keyed by cover and count, not by cell, so a group a zoom leaves whole keeps its marker and just moves.
        fun markerKey(pin: MapPin) = pin.cover.id + "|" + pin.count
        val markers = HashMap<String, Marker>()
        val shown = HashMap<String, MapPin>()
        fun iconFor(pin: MapPin) = CountedPinDrawable(pinThumbs[pin.cover.id] ?: placeholderBitmap, pin.count, badge)

        fun pinsNowInView(): List<MapPin> {
            if (mapView.width == 0 || mapView.height == 0) return grid
            // A margin around the screen, so a pin at the edge is already there as it slides in.
            val box = mapView.boundingBox.increaseByScale(VIEW_MARGIN_SCALE)
            return grid.filter { box.contains(it.latitude, it.longitude) }
        }

        var loader: kotlinx.coroutines.Job? = null
        suspend fun syncPins() {
            val zoom = mapView.zoomLevelDouble
            if (zoom != groupedZoom) {
                grid = withContext(Dispatchers.Default) {
                    gridPins(located, TileSystem.MapSize(zoom), cellPx)
                }
                groupedZoom = zoom
            }
            val next = pinsNowInView()
            val nextByKey = next.associateBy(::markerKey)
            val gone = markers.keys.filter { it !in nextByKey }
            for (key in gone) markers.remove(key)?.let { mapView.overlays.remove(it) }
            shown.clear()
            shown.putAll(nextByKey)
            focusMarker?.let { mapView.overlays.remove(it) }
            for ((key, pin) in nextByKey) {
                val have = markers[key]
                if (have != null) {
                    if (have.position.latitude != pin.latitude || have.position.longitude != pin.longitude) {
                        have.position = GeoPoint(pin.latitude, pin.longitude)
                    }
                    continue
                }
                markers[key] = Marker(mapView).apply {
                    position = GeoPoint(pin.latitude, pin.longitude)
                    // Anchor at the pointer tip (centre-x, bottom) so the wedge points at the fix.
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = iconFor(pin)
                    setOnMarkerClickListener { _, _ ->
                        shown[key]?.let { currentOnOpenPin(it) }
                        true
                    }
                }.also { mapView.overlays.add(it) }
            }
            // Last, so the place the map was opened on draws over the photo pins.
            focusMarker?.let { mapView.overlays.add(it) }
            mapView.invalidate()

            // Thumbnails for the pins that have none yet; one with no image source yet keeps the placeholder.
            val missing = next.filter { pinThumbs[it.cover.id] == null }
            loader?.cancel()
            if (missing.isEmpty()) return
            val urls = currentThumbnailUrls
            vm.requestCoverThumbnails(unrequestedCloudCovers(missing, urls, askedCovers))
            loader = launch {
                for (pin in missing) {
                    val pinBitmap = loadPinBitmap(context, pin.cover.item, urls, density) ?: continue
                    pinThumbs.put(pin.cover.id, pinBitmap)
                    markers[markerKey(pin)]?.icon = iconFor(pin)
                    mapView.invalidate()
                }
            }
        }

        val mapListener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                replot.trySend(Unit)
                return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                replot.trySend(Unit)
                return false
            }
        }
        val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) replot.trySend(Unit)
        }
        mapView.addMapListener(mapListener)
        mapView.addOnLayoutChangeListener(layoutListener)
        try {
            if (!framed) {
                val avgLat = located.sumOf { it.latitude } / located.size
                val avgLon = located.sumOf { it.longitude } / located.size
                mapView.controller.setZoom(LOCATED_ZOOM)
                mapView.controller.setCenter(GeoPoint(avgLat, avgLon))
                framed = true
            }
            syncPins()
            // Camera now frames the located photos (or the focus); drop the cover so the map is revealed
            // already centred, ahead of the thumbnails that swap in.
            centered = true
            // Regrouped once the map settles, not at each pinch step; between, the pins ride the map as they are.
            while (true) {
                replot.receive()
                delay(REGROUP_AFTER_MS)
                replot.tryReceive()
                syncPins()
            }
        } finally {
            mapView.removeMapListener(mapListener)
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
        Box(Modifier.fillMaxSize().glassSource(glass)) {
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

        }

        // An opaque search bar along the bottom raises the same drawer the Places screen uses; it hides
        // while the drawer is open, so the pressed bar reads as having risen into the drawer.
        CompositionLocalProvider(LocalGlassBackdrop provides glass) {
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
                    .pillSurface(colors.bg2, pill, border = colors.line2)
                    .clickable { showSearch = true }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = colors.fgDim, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.map_search_places), color = colors.fgMute, fontSize = 14.sp)
            }
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
                backdrop = glass,
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
