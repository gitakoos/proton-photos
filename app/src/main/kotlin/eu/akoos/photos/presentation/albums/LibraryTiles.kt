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

package eu.akoos.photos.presentation.albums

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.theme.AppColors
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The shortcut tiles at the top of the Albums tab, in their default order. */
enum class LibraryTile(@StringRes val label: Int) {
    Collection(R.string.memories_title),
    Hidden(R.string.gallery_filter_hidden),
    Shared(R.string.gallery_tab_shared),
    Map(R.string.map_title),
    Calendar(R.string.calendar_title),
    People(R.string.gallery_category_people),
    Offline(R.string.filter_offline),
}

internal val LibraryTile.icon: ImageVector
    get() = when (this) {
        LibraryTile.Collection -> Icons.Outlined.Collections
        LibraryTile.Hidden -> Icons.Outlined.Lock
        LibraryTile.Shared -> Icons.Outlined.Share
        LibraryTile.Map -> Icons.Outlined.Map
        LibraryTile.Calendar -> Icons.Outlined.CalendarMonth
        LibraryTile.People -> Icons.Outlined.Face
        LibraryTile.Offline -> Icons.Outlined.OfflinePin
    }

/** The tiles in the order saved in [savedCsv] (enum names), then any tile it does not name in its default place. */
internal fun resolveTileOrder(savedCsv: String?): List<LibraryTile> {
    val saved = savedCsv?.split(',')
        ?.mapNotNull { name -> LibraryTile.entries.firstOrNull { it.name == name } }
        ?.distinct()
        ?: emptyList()
    return saved + LibraryTile.entries.filterNot { it in saved }
}

/**
 * Show or hide and reorder the shortcut tiles, the way the timeline's category chips are set up:
 * each row has an eye and a drag handle, a hidden row dims but keeps its place. The last visible tile
 * cannot be hidden, so the row never ends up empty; the switch above it hides the whole row.
 */
@Composable
internal fun LibraryTileReorderList(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AppColors.current

    val savedCsv by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.ALBUMS_TILE_ORDER] }
    }.collectAsState(initial = null)
    val hidden by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.ALBUMS_TILE_HIDDEN] ?: emptySet() }
    }.collectAsState(initial = emptySet())
    var order by remember { mutableStateOf<List<LibraryTile>>(emptyList()) }
    LaunchedEffect(savedCsv) { order = resolveTileOrder(savedCsv) }

    fun persistOrder() {
        val snapshot = order
        scope.launch {
            runCatching {
                context.settingsDataStore.edit {
                    it[SettingsKeys.ALBUMS_TILE_ORDER] = snapshot.joinToString(",") { tile -> tile.name }
                }
            }
        }
    }

    fun toggleHidden(tile: LibraryTile) {
        scope.launch {
            runCatching {
                context.settingsDataStore.edit {
                    val current = it[SettingsKeys.ALBUMS_TILE_HIDDEN] ?: emptySet()
                    it[SettingsKeys.ALBUMS_TILE_HIDDEN] =
                        if (tile.name in current) current - tile.name else current + tile.name
                }
            }
        }
    }

    var draggedTile by remember { mutableStateOf<LibraryTile?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val visibleCount = LibraryTile.entries.count { it.name !in hidden }

    Column(modifier = modifier) {
        order.forEachIndexed { _, tile ->
            key(tile) {
                val isDragged = tile == draggedTile
                val isHidden = tile.name in hidden
                val label = stringResource(tile.label)
                Row(
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffsetY else 0f }
                        .fillMaxWidth()
                        .background(
                            if (isDragged) colors.bg2 else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        tile.icon,
                        contentDescription = null,
                        tint = if (isHidden) colors.fgDim.copy(alpha = 0.45f) else colors.fgDim,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        label,
                        color = if (isHidden) colors.fgPrimary.copy(alpha = 0.45f) else colors.fgPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    val canToggle = isHidden || visibleCount > 1
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(enabled = canToggle) { toggleHidden(tile) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (isHidden) R.string.gallery_category_show else R.string.gallery_category_hide,
                                label,
                            ),
                            tint = when {
                                isHidden -> colors.fgDim
                                canToggle -> colors.accent
                                else -> colors.accent.copy(alpha = 0.4f)
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .pointerInput(Unit) {
                                val rowH = 52.dp.toPx()
                                detectDragGestures(
                                    onDragStart = { draggedTile = tile; dragOffsetY = 0f },
                                    onDragEnd = { draggedTile = null; dragOffsetY = 0f; persistOrder() },
                                    onDragCancel = { draggedTile = null; dragOffsetY = 0f },
                                ) { change, drag ->
                                    change.consume()
                                    dragOffsetY += drag.y
                                    val cur = order.indexOf(tile)
                                    if (cur < 0) return@detectDragGestures
                                    if (dragOffsetY > rowH / 2 && cur < order.lastIndex) {
                                        order = order.toMutableList().apply { add(cur + 1, removeAt(cur)) }
                                        dragOffsetY -= rowH
                                    } else if (dragOffsetY < -rowH / 2 && cur > 0) {
                                        order = order.toMutableList().apply { add(cur - 1, removeAt(cur)) }
                                        dragOffsetY += rowH
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = null,
                            tint = colors.fgDim,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}
