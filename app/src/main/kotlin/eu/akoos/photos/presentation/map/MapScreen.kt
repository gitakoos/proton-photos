/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.location.LocationDetailSheet
import eu.akoos.photos.presentation.memories.FloatingMemoriesHeader
import eu.akoos.photos.presentation.theme.AppColors
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

// World-view fallback when the library has no located photos yet — a low zoom centred on the
// equator/prime-meridian shows the whole map rather than dropping the user on a blank ocean tile.
private const val WORLD_ZOOM = 5.0
private const val LOCATED_ZOOM = 5.0

// Plotting every fix gets expensive on a large located library (each pin is a decoded thumbnail +
// a composited bitmap). Cap to an evenly-sampled subset; clustering arrives in a later piece.
private const val MARKER_CAP = 150

// Coil decode target for the pin thumbnail — small, since the visible body is ~52dp. Cover-crop
// happens when the bitmap is composited into the rounded body.
private const val PIN_THUMB_PX = 120

/**
 * Map page. Plots every geotagged photo for the account on an OpenStreetMap canvas. The
 * top bar mirrors the Calendar / Search back-pill recipe; the map itself is an osmdroid
 * [MapView] driven through an [AndroidView] with its lifecycle bridged via [DisposableEffect].
 *
 * On entry the screen requests [Manifest.permission.ACCESS_MEDIA_LOCATION] (Android 10+) when
 * it isn't already held, and — once granted or already present — kicks the one-shot GPS
 * backfill so the location table fills in while the user looks at the map.
 *
 * Each fix is plotted as a rounded-rectangle thumbnail pin (Google-Photos style). A placeholder
 * pin drops immediately; the thumbnail is loaded off-thread through Coil and swapped in once
 * decoded. Tapping a pin raises a [LocationDetailSheet] as a [ModalBottomSheet] OVER the map (the
 * map stays behind), which resolves the pin's coordinates to a place and lists every photo taken
 * there. A photo tap inside the drawer closes it and opens the viewer via [onPhotoClick].
 * Clustering arrives in a later piece.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onBack: () -> Unit,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit = { _, _ -> },
    onOpenCalendar: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    vm: MapViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val isDark = !colors.isLight
    val context = LocalContext.current
    val pins by vm.pins.collectAsStateWithLifecycle()
    val density = LocalDensity.current.density

    // Coordinates of the tapped pin. Non-null raises the location-detail drawer over the map;
    // dismiss (drag-down / scrim tap / a photo tap) clears it back to the map.
    var sheetCoords by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // City-search overlay: a tap on the search pill opens it; it lists the geocoded city set.
    var showSearch by remember { mutableStateOf(false) }
    val cities by vm.cities.collectAsStateWithLifecycle()

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
            permissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
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

    // Re-plot whenever the located set changes. Placeholder pins are added synchronously at the top
    // of this effect (before the first suspension point) so the map populates on the next frame;
    // the thumbnails then load off-thread and swap their icon in. Driving this from a LaunchedEffect
    // means a new `locations` emission cancels the previous run's in-flight loaders rather than
    // leaving them to repaint markers that were already cleared. Center on the average of the points
    // so the first frame frames the user's photos; fall back to a world view when nothing is located.
    LaunchedEffect(mapView, pins) {
        mapView.overlays.clear()
        if (pins.isEmpty()) {
            mapView.controller.setZoom(WORLD_ZOOM)
            mapView.controller.setCenter(GeoPoint(0.0, 0.0))
            mapView.invalidate()
            return@LaunchedEffect
        }

        // Sample down to the marker cap so a large located library stays cheap — evenly spaced so
        // the spread still reads, mirroring the Search map-preview card.
        val sampled = if (pins.size <= MARKER_CAP) {
            pins
        } else {
            val step = pins.size.toDouble() / MARKER_CAP
            (0 until MARKER_CAP).map { pins[(it * step).toInt()] }
        }

        // One shared placeholder drawable across every pin until its thumbnail decodes.
        val placeholder = BitmapDrawable(context.resources, ThumbnailPin.placeholder(density))

        val markers = sampled.map { pin ->
            Marker(mapView).apply {
                position = GeoPoint(pin.latitude, pin.longitude)
                // Anchor at the pointer tip (centre-x, bottom) so the wedge points at the fix.
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = placeholder
                setOnMarkerClickListener { _, _ ->
                    sheetCoords = pin.latitude to pin.longitude
                    true
                }
            }.also { mapView.overlays.add(it) }
        }

        val avgLat = pins.sumOf { it.latitude } / pins.size
        val avgLon = pins.sumOf { it.longitude } / pins.size
        mapView.controller.setZoom(LOCATED_ZOOM)
        mapView.controller.setCenter(GeoPoint(avgLat, avgLon))
        mapView.invalidate()

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
            markers[index].icon = BitmapDrawable(context.resources, pinBitmap)
            mapView.invalidate()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg0),
    ) {
        // The map lives in its own rounded, bordered container (inset from the edges) rather than
        // bleeding to the screen edges — this keeps a clear gap below the top bar so the map's own
        // touch handling can't fight the back button, and reads as a contained surface.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 56.dp, start = 12.dp, end = 12.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(0.5.dp, colors.pillBorder, RoundedCornerShape(16.dp)),
        ) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (showSearch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur(16.dp)
                        } else {
                            Modifier
                        },
                    ),
            )
            // City-search overlay — dims (and blurs where supported) the map and lists the places.
            if (showSearch) {
                MapCitySearchOverlay(
                    cities = cities,
                    onPick = { city ->
                        mapView.controller.animateTo(GeoPoint(city.latitude, city.longitude), 9.0, 900L)
                        showSearch = false
                        sheetCoords = city.latitude to city.longitude
                    },
                    onClose = { showSearch = false },
                )
            }

            // OpenStreetMap tile attribution — required by OSM's tile usage policy for the standard
            // MAPNIK tiles. A fixed dark scrim + light text keeps it legible over both the light
            // tiles and the dark-filtered tiles, regardless of theme.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
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
        }

        // Floating header — drawn as a later sibling over the map. The title pill grows downward
        // into its view-switch menu over the map (back button + search pill stay pinned at the top)
        // instead of pushing the row down. It carries its own statusBarsPadding.
        FloatingMemoriesHeader(
            title = stringResource(R.string.map_title),
            onBack = onBack,
            menuItems = listOf(stringResource(R.string.calendar_title) to onOpenCalendar),
            trailing = {
                // Search pill — opens (and re-taps to close) the city-search overlay.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.pillBg, CircleShape)
                        .border(0.5.dp, colors.pillBorder, CircleShape)
                        .clickable { showSearch = !showSearch },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (showSearch) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = stringResource(R.string.map_search_places),
                        tint = colors.fgPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
    }

    // Location detail drawer — rises over the map when a pin is tapped. Opens tall-but-not-full
    // (skipPartiallyExpanded); the cover runs to the top edge with a slim handle floated over it
    // inside the sheet, so there's no separate surface band. A photo tap closes it, then the viewer.
    sheetCoords?.let { (lat, lon) ->
        ModalBottomSheet(
            onDismissRequest = { sheetCoords = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppColors.current.bg0,
            dragHandle = null,
        ) {
            LocationDetailSheet(
                latitude = lat,
                longitude = lon,
                onPhotoClick = { items, index ->
                    sheetCoords = null
                    onPhotoClick(items, index)
                },
            )
        }
    }
}

/**
 * Luminance-invert colour filter that turns the light MAPNIK tiles into a dark basemap (dark land
 * and water, readable labels). Saturation is muted afterwards so the inverted hues don't read as
 * garish neon.
 */
private fun darkTileFilter(): android.graphics.ColorMatrixColorFilter {
    val m = android.graphics.ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    m.postConcat(android.graphics.ColorMatrix().apply { setSaturation(0.55f) })
    return android.graphics.ColorMatrixColorFilter(m)
}
