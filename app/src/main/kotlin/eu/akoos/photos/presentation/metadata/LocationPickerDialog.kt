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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.akoos.photos.R
import eu.akoos.photos.presentation.map.darkTileFilter
import eu.akoos.photos.presentation.search.SearchFilter
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.util.appLocale
import eu.akoos.photos.util.OfflineGeocoder
import eu.akoos.photos.util.cityCountryLabel
import java.util.Locale
import org.osmdroid.events.MapEventsReceiver
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

private data class PickerResult(val label: String, val latitude: Double, val longitude: Double, val zoom: Double)

/**
 * Full-screen map for choosing where a photo was taken. The pin stays at the centre of the screen and
 * the map moves under it; a tap on the map, or a search result, brings that spot to the pin. The check
 * at the top right confirms the spot under the pin, the back arrow leaves without changing anything.
 * One search box covers countries and cities from the bundled place list, so it works offline.
 */
@Composable
internal fun LocationPickerDialog(
    initialLatitude: Double?,
    initialLongitude: Double?,
    onConfirm: (latitude: Double, longitude: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        LocationPickerContent(initialLatitude, initialLongitude, onConfirm, onDismiss)
    }
}

@Composable
private fun LocationPickerContent(
    initialLatitude: Double?,
    initialLongitude: Double?,
    onConfirm: (latitude: Double, longitude: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    val context = LocalContext.current
    val locale = appLocale()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PickerResult>>(emptyList()) }

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

    LaunchedEffect(query, locale) {
        val q = query.trim()
        results = if (q.length < 2) emptyList() else searchPlacesAndCountries(context, q, locale)
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.align(Alignment.Center).offset(y = (-20).dp).size(40.dp),
        )
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.pillBgOpaque, RoundedCornerShape(28.dp))
                    .border(0.5.dp, colors.pillBorder, RoundedCornerShape(28.dp))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = colors.fgPrimary)
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.metadata_editor_place_search),
                            color = colors.fgMute,
                            fontSize = 15.sp,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = colors.fgPrimary, fontSize = 15.sp),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(onClick = {
                    val center = mapView.mapCenter
                    onConfirm(center.latitude, center.longitude)
                }) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.metadata_editor_done),
                        tint = colors.accent,
                    )
                }
            }
            if (results.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(colors.pillBgOpaque, RoundedCornerShape(16.dp))
                        .border(0.5.dp, colors.pillBorder, RoundedCornerShape(16.dp)),
                ) {
                    results.forEach { result ->
                        Text(
                            text = result.label,
                            color = colors.fgPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    query = ""
                                    mapView.controller.setZoom(result.zoom)
                                    mapView.controller.setCenter(GeoPoint(result.latitude, result.longitude))
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
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
