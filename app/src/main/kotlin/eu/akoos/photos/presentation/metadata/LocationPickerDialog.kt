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
 */

package eu.akoos.photos.presentation.metadata

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.cardFill
import eu.akoos.photos.presentation.map.darkTileFilter
import eu.akoos.photos.presentation.search.SearchFilter
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.util.appLocale
import eu.akoos.photos.util.OfflineGeocoder
import eu.akoos.photos.util.cityCountryLabel
import java.util.Locale
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay

private const val WORLD_ZOOM = 3.0
private const val PLACE_ZOOM = 12.0
private const val CITY_ZOOM = 11.0
private const val COUNTRY_ZOOM = 5.0
private const val MAX_ZOOM = 17.0
private const val MAX_RESULTS = 8
private const val PLACE_LABEL_DELAY_MS = 250L
private val INLINE_MAP_HEIGHT = 300.dp

private data class PickerResult(val label: String, val latitude: Double, val longitude: Double, val zoom: Double)

/** The picker's map: centred on the photo's fix (or the world when there is none), the tap that brings a
 *  spot under the pin, the app's lifecycle and the dark tile tint. */
@Composable
private fun rememberPickerMap(initialLatitude: Double?, initialLongitude: Double?): MapView {
    val context = LocalContext.current
    val colors = AppColors.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            setMinZoomLevel(WORLD_ZOOM)
            setMaxZoomLevel(MAX_ZOOM)
            setVerticalMapRepetitionEnabled(false)
            setHorizontalMapRepetitionEnabled(false)
            val ts = MapView.getTileSystem()
            setScrollableAreaLimitLatitude(ts.maxLatitude, ts.minLatitude, 0)
            if (initialLatitude != null && initialLongitude != null) {
                controller.setZoom(PLACE_ZOOM)
                controller.setCenter(GeoPoint(initialLatitude, initialLongitude))
            } else {
                controller.setZoom(WORLD_ZOOM)
                controller.setCenter(GeoPoint(30.0, 10.0))
            }
            overlays.add(
                MapEventsOverlay(
                    object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            controller.animateTo(p)
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint): Boolean = false
                    },
                ),
            )
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(mapView, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    val isDark = !colors.isLight
    LaunchedEffect(mapView, isDark) {
        mapView.overlayManager.tilesOverlay.setColorFilter(if (isDark) darkTileFilter() else null)
        mapView.invalidate()
    }
    return mapView
}

/** Holds the map an [InlineLocationPicker] shows, so the page around it can read the spot under the pin. */
internal class PickerCenter {
    internal var mapView: MapView? = null

    /** The spot under the pin, or null before the map is laid out. */
    fun center(): Pair<Double, Double>? = mapView?.mapCenter?.let { it.latitude to it.longitude }
}

/**
 * The picker for a page that is not a whole screen (the details sheet): the same map with the pin fixed in
 * the middle and the same search, inside a rounded box, the place under the pin named along its bottom edge.
 */
@Composable
internal fun InlineLocationPicker(
    initialLatitude: Double?,
    initialLongitude: Double?,
    center: PickerCenter,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    val context = LocalContext.current
    val locale = appLocale()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PickerResult>>(emptyList()) }
    var placeLabel by remember { mutableStateOf<String?>(null) }
    var centerTick by remember { mutableStateOf(0) }
    val mapView = rememberPickerMap(initialLatitude, initialLongitude)
    DisposableEffect(mapView) {
        center.mapView = mapView
        // A touch that starts on the map claims the gesture for it, so the sheet around it never reads a pan
        // as a pull to close.
        mapView.setOnTouchListener { view, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        val listener = DelayedMapListener(
            object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    centerTick++
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    centerTick++
                    return false
                }
            },
            PLACE_LABEL_DELAY_MS,
        )
        mapView.addMapListener(listener)
        onDispose {
            mapView.removeMapListener(listener)
            mapView.setOnTouchListener(null)
            center.mapView = null
        }
    }
    LaunchedEffect(centerTick, locale) {
        val point = mapView.mapCenter
        val nearest = OfflineGeocoder.reverseGeocodeDetailed(context, point.latitude, point.longitude)
        placeLabel = nearest?.let { cityCountryLabel(it.city, it.countryCode, "", locale) }
    }
    LaunchedEffect(query, locale) {
        val q = query.trim()
        results = if (q.length < 2) emptyList() else searchPlacesAndCountries(context, q, locale)
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(cardFill(colors.cardBg), RoundedCornerShape(14.dp))
                .border(0.5.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = colors.fgMute, modifier = Modifier.size(18.dp))
            Box(modifier = Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.metadata_editor_place_search),
                        color = colors.fgMute, fontSize = 14.sp, maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.fgPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(INLINE_MAP_HEIGHT)
                .clip(RoundedCornerShape(14.dp)),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.align(Alignment.Center).offset(y = (-16).dp).size(32.dp),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    placeLabel.orEmpty(),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    hint,
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (results.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(8.dp)
                        .background(colors.pillBgOpaque, RoundedCornerShape(14.dp))
                        .border(0.5.dp, colors.pillBorder, RoundedCornerShape(14.dp)),
                ) {
                    results.forEach { result ->
                        Text(
                            text = result.label,
                            color = colors.fgPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    query = ""
                                    mapView.controller.setZoom(result.zoom)
                                    mapView.controller.setCenter(GeoPoint(result.latitude, result.longitude))
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Countries whose name contains [query] first, then cities, both from the bundled place list. */
private suspend fun searchPlacesAndCountries(
    context: android.content.Context,
    query: String,
    locale: Locale,
): List<PickerResult> {
    val folded = SearchFilter.fold(query)
    val countries = OfflineGeocoder.countries(context, locale)
        .filter { SearchFilter.fold(it.displayName).contains(folded) }
        .mapNotNull { country ->
            OfflineGeocoder.countryPoint(context, country.code)?.let { (lat, lon) ->
                PickerResult(country.displayName, lat, lon, COUNTRY_ZOOM)
            }
        }
    val cities = OfflineGeocoder.searchPlaces(context, query, null, limit = MAX_RESULTS)
        .map { place ->
            PickerResult(
                cityCountryLabel(place.name, place.countryCode, "", locale),
                place.latitude,
                place.longitude,
                CITY_ZOOM,
            )
        }
    return (countries + cities).take(MAX_RESULTS)
}
