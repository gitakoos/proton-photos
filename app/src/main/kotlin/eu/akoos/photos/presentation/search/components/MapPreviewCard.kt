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

package eu.akoos.photos.presentation.search.components

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import eu.akoos.photos.R
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.map.MapPin
import eu.akoos.photos.presentation.map.ThumbnailPin
import eu.akoos.photos.presentation.theme.AppColors
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

// World-view fallback when nothing is located yet — a low zoom on the equator/prime-meridian
// shows the whole map instead of a blank ocean tile.
private const val PREVIEW_WORLD_ZOOM = 3.0
private const val PREVIEW_LOCATED_ZOOM = 4.0

// The card is a small 160dp preview; thumbnail pins are decoded + composited bitmaps, so cap the
// preview well below the full map page (which shows far more) and sample evenly so the spread reads.
private const val PREVIEW_MARKER_CAP = 40

// Coil decode target for the pin thumbnail — small, since the visible pin body is tiny in the card.
private const val PIN_THUMB_PX = 120

/**
 * "Map" entry card on the Search idle page. A wide, full-width card whose background is a live,
 * display-only osmdroid [MapView] centred on the average of the account's geotagged photos, with a
 * thumbnail pin dropped per location (capped + evenly sampled, mirroring the full map page). A
 * placeholder pin drops immediately and each thumbnail loads off-thread through Coil and swaps in.
 * A transparent overlay swallows touches so the preview never pans — a tap opens the real map page
 * via [onOpenMap].
 *
 * [cityCount] drives the subtitle ("N cities") — the number of distinct places the located photos
 * were taken in. In dark mode the light MAPNIK tiles are inverted via a tiles-overlay colour filter
 * so the backdrop matches the rest of the app.
 *
 * The caller is the first item of the Search idle list, so this sits above "Recent".
 */
@Composable
fun MapPreviewCard(
    pins: List<MapPin>,
    cityCount: Int,
    onOpenMap: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val isDark = !AppColors.current.isLight
    val shape = RoundedCornerShape(14.dp)

    // Build the preview MapView once; the lifecycle bridge below resumes/pauses/detaches it.
    // Multitouch is off and an overlay swallows taps, so this map is strictly a still backdrop.
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMinZoomLevel(PREVIEW_WORLD_ZOOM)
            setMaxZoomLevel(19.0)
            setVerticalMapRepetitionEnabled(false)
            setHorizontalMapRepetitionEnabled(false)
            val ts = MapView.getTileSystem()
            setScrollableAreaLimitLatitude(ts.maxLatitude, ts.minLatitude, 0)
        }
    }

    // osmdroid drives its tile threads off the view lifecycle. Bridge them to the SCREEN's lifecycle
    // (not just composition) so the tiles pause when the app is backgrounded while the Search screen
    // is still on the back stack, and detach (releasing tile handles) when the card leaves.
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
    // theme flip while the card is on screen.
    LaunchedEffect(mapView, isDark) {
        mapView.overlayManager.tilesOverlay.setColorFilter(if (isDark) darkTileFilter() else null)
        mapView.invalidate()
    }

    // Re-centre + re-plot whenever the located set changes. Placeholder pins drop synchronously
    // before the first suspension point so the map populates on the next frame; thumbnails then load
    // off-thread and swap their icon in. A LaunchedEffect (not DisposableEffect) lets a new emission
    // cancel the previous run's in-flight loaders rather than repaint markers that were cleared.
    LaunchedEffect(mapView, pins) {
        mapView.overlays.clear()
        if (pins.isEmpty()) {
            mapView.controller.setZoom(PREVIEW_WORLD_ZOOM)
            mapView.controller.setCenter(GeoPoint(0.0, 0.0))
            mapView.invalidate()
            return@LaunchedEffect
        }

        val sampled = if (pins.size <= PREVIEW_MARKER_CAP) {
            pins
        } else {
            val step = pins.size.toDouble() / PREVIEW_MARKER_CAP
            (0 until PREVIEW_MARKER_CAP).map { pins[(it * step).toInt()] }
        }

        // One shared placeholder drawable across every pin until its thumbnail decodes.
        val placeholder = BitmapDrawable(context.resources, ThumbnailPin.placeholder(density))

        val markers = sampled.map { pin ->
            Marker(mapView).apply {
                position = GeoPoint(pin.latitude, pin.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = placeholder
            }.also { mapView.overlays.add(it) }
        }

        val avgLat = pins.sumOf { it.latitude } / pins.size
        val avgLon = pins.sumOf { it.longitude } / pins.size
        mapView.controller.setZoom(PREVIEW_LOCATED_ZOOM)
        mapView.controller.setCenter(GeoPoint(avgLat, avgLon))
        mapView.invalidate()

        // Fill in thumbnails. Each pin's resolved library item supplies the same image source the
        // gallery cell uses — a local content uri or a cloud thumbnail, both decoded through Coil — so
        // cloud fixes load their photo too. An unresolved pin keeps the placeholder.
        sampled.forEachIndexed { index, pin ->
            val imageData = pin.item?.let { photoCellInputsFor(it).imageData } ?: return@forEachIndexed
            val pinBitmap = runCatching {
                val req = ImageRequest.Builder(context)
                    .data(imageData)
                    .size(PIN_THUMB_PX)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(req)
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
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 4.dp)
            .height(160.dp)
            .clip(shape)
            .border(1.dp, Color.Black.copy(alpha = 0.08f), shape),
    ) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )

        // Transparent tap target over the map: osmdroid would otherwise consume the touch and pan
        // the preview. Routing the tap here opens the full map and leaves the backdrop static.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onOpenMap),
        )

        // Bottom fade — starts around the vertical middle and ramps to a strong near-black at the
        // very bottom so the white title/subtitle clearly pop over light tiles.
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

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.padding(start = 6.dp)) {
                Text(
                    text = stringResource(R.string.map_card_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.4).sp,
                )
                Text(
                    text = if (pins.isEmpty()) {
                        stringResource(R.string.map_card_subtitle_empty)
                    } else {
                        pluralStringResource(
                            R.plurals.map_card_cities_count,
                            cityCount,
                            cityCount,
                        )
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // OpenStreetMap tile attribution — required by OSM's tile usage policy for the standard
        // MAPNIK tiles. A fixed dark scrim + light text keeps it legible over both the light tiles
        // and the dark-filtered tiles.
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
                fontSize = 9.sp,
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
