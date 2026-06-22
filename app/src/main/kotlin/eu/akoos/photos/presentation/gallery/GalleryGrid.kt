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

package eu.akoos.photos.presentation.gallery

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.DenseGridWarningDialog
import eu.akoos.photos.presentation.memories.OnThisDayCarousel
import eu.akoos.photos.presentation.memories.OnThisDayCard
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorChipBg
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ── Photo grid ────────────────────────────────────────────────────────────────

@Composable
internal fun PhotoGrid(
    items: List<GalleryItem>,
    allItems: List<GalleryItem>,
    monthGroups: List<Pair<String, List<GalleryItem>>>,
    onThisDayGroups: List<Pair<Int, List<GalleryItem>>>,
    gridState: LazyGridState,
    topContentPadding: Dp,
    permissionState: PermissionState,
    onPermissionGrant: () -> Unit,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    selectedItems: Set<GalleryItem> = emptySet(),
    isSelectionMode: Boolean = false,
    onToggleSelect: (GalleryItem) -> Unit = {},
    onToggleGroup: (List<GalleryItem>) -> Unit = {},
    onSelectionChange: (Set<GalleryItem>) -> Unit = {},
    initialZoomLevel: Int = GridZoom.DEFAULT_LEVEL,
    gridRememberLast: Boolean = false,
    gridDefaultColumns: Int = GridZoom.DEFAULT_COLUMNS,
    onZoomLevelChanged: (Int) -> Unit = {},
    hiddenCloudLinkIds: Set<String> = emptySet(),
    downloadedCloudLinkIds: Set<String> = emptySet(),
    favoriteIds: Set<String> = emptySet(),
    onRequestThumbnail: (linkId: String) -> Unit = {},
    onCancelThumbnail: (linkId: String) -> Unit = {},
    denseGridWarningDismissed: Boolean = false,
    onDismissDenseGridWarning: () -> Unit = {},
) {
    // "On this day" memories — items from previous years that fall on today's calendar date.
    // Independent of the active content filter so memories still surface when the user has
    // applied e.g. a year/month filter that would otherwise hide them. Grouped by year,
    // most-recent-first; empty when there are none (carousel is hidden entirely). Computed in
    // the ViewModel off the Main thread from the unfiltered list (state.items == allItems here).
    val onThisDayByYear: List<Pair<Int, List<GalleryItem>>> = onThisDayGroups
    val context = LocalContext.current
    // User can hide the memories carousel from the timeline filter; default on.
    val showOnThisDay by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.SHOW_ON_THIS_DAY] ?: true }
    }.collectAsState(initial = true)

    // Stable grid key for an item — must match the keys passed to the `items(...)` blocks below.
    val keyOf: (GalleryItem) -> String = { item ->
        when (item) {
            is GalleryItem.LocalOnly -> "local_${item.local.uri}"
            is GalleryItem.Synced    -> "synced_${item.local.uri}"
            is GalleryItem.CloudOnly -> "cloud_${item.cloud.linkId}"
        }
    }
    val cloudLinkIdOf: (GalleryItem) -> String? = { item ->
        when (item) {
            is GalleryItem.CloudOnly -> item.cloud.linkId
            is GalleryItem.Synced    -> item.cloud.linkId
            is GalleryItem.LocalOnly -> null
        }
    }

    // key → index, so a cell's onClick resolves the tapped photo's position in O(1) instead of
    // a List.indexOf scan per click (an N-cost call repeated across every visible cell).
    val indexByKey = remember(items) {
        HashMap<String, Int>(items.size).apply {
            items.forEachIndexed { idx, gi -> put(keyOf(gi), idx) }
        }
    }
    // Pending-decrypt linkId for a cloud row whose thumbnail isn't cached yet (matches the old
    // per-cell gate: a Synced/CloudOnly row with thumbnailUrl == null). Cached rows return null
    // so the driver below neither requests nor cancels them.
    val pendingCloudLinkIdOf: (GalleryItem) -> String? = { item ->
        when (item) {
            is GalleryItem.CloudOnly -> item.cloud.linkId.takeIf { item.cloud.thumbnailUrl == null }
            is GalleryItem.Synced    -> item.cloud.linkId.takeIf { item.cloud.thumbnailUrl == null }
            is GalleryItem.LocalOnly -> null
        }
    }

    // ── Visible-range thumbnail decrypt driver ────────────────────────────────
    // Requests decrypts for the cloud cells actually in the viewport, in viewport order, plus a
    // small leading margin, and cancels the cells that just scrolled out. Replaces the old
    // per-cell 120 ms-debounced LaunchedEffect, whose fire order followed compose/dispose timing
    // rather than scroll position, so thumbnails popped in out of order. Centralising both the
    // request and the cancel here also lets PhotoCell stay a pure (skippable) presentation cell.
    LaunchedEffect(gridState, items) {
        var requested = emptySet<String>()
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
        }.distinctUntilChanged().collect { visibleKeys ->
            val firstIdx = visibleKeys.firstNotNullOfOrNull { indexByKey[it] }
            val lastIdx = visibleKeys.asReversed().firstNotNullOfOrNull { indexByKey[it] }
            val nowRequested = LinkedHashSet<String>()
            if (firstIdx != null && lastIdx != null) {
                val to = (lastIdx + 1 + 12).coerceAtMost(items.size)
                for (i in firstIdx until to) pendingCloudLinkIdOf(items[i])?.let {
                    if (nowRequested.add(it)) onRequestThumbnail(it)
                }
            }
            // Cancel rows that left the request window so the scheduler stops decrypting flung-past
            // cells — the same back-pressure the old per-cell onDispose provided.
            requested.forEach { if (it !in nowRequested) onCancelThumbnail(it) }
            requested = nowRequested
        }
    }

    // ── Pinch-to-zoom levels ──────────────────────────────────────────────────
    // The ladder (columns + grouping per level) lives in GridZoom.LEVELS so the grid, the
    // ViewModel and the grid-layout settings all agree on it. Pinch cycles through the levels; the
    // cross-over from year to month/day grouping is where tiles get big enough that a date header
    // above them stops feeling like clutter.
    val zoomLevels = GridZoom.LEVELS
    // Opening level comes from the grid-layout preference (resolved in the ViewModel): the last
    // pinched level when "remember last used" is on, else the level for the default columns.
    var levelIndex by rememberSaveable { mutableIntStateOf(initialZoomLevel.coerceIn(0, zoomLevels.lastIndex)) }
    // Follow a settings change without a restart: re-derive the default level when the default
    // columns change or "remember last used" is switched off. A pinch never touches these inputs,
    // so it isn't overridden mid-session; while remembering, the level is left where the user left it.
    LaunchedEffect(gridRememberLast, gridDefaultColumns) {
        if (!gridRememberLast) levelIndex = GridZoom.levelForColumns(gridDefaultColumns)
    }
    val (columnCount, effectiveGrouping) = zoomLevels[levelIndex.coerceIn(0, zoomLevels.lastIndex)]

    // One-time heads-up the first time the timeline is zoomed out to the densest grid in a session
    // — more tiles per row is heavier to scroll on very large libraries. "Don't show again" persists;
    // OK (or tapping outside) just closes and it can reappear next session.
    var showDenseWarning by remember { mutableStateOf(false) }
    var denseWarningShownThisSession by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(levelIndex, denseGridWarningDismissed) {
        if (levelIndex == 0 && !denseGridWarningDismissed && !denseWarningShownThisSession) {
            showDenseWarning = true
            denseWarningShownThisSession = true
        }
    }
    if (showDenseWarning) {
        DenseGridWarningDialog(
            onDismiss = { showDenseWarning = false },
            onPersist = { showDenseWarning = false; onDismissDenseGridWarning() },
        )
    }

    // Month (the default browsing level) is bucketed in the ViewModel off the Main thread and
    // arrives via [monthGroups] — at 8500+ photos, running SimpleDateFormat per item inside
    // composition on every thumbnail-decrypt re-emission was a ~680 ms hitch. The other pinch
    // levels (None flat / Day / Year) are off-default and grouped inline here as before; their
    // label format and encounter order are unchanged.
    val grouped: List<Pair<String, List<GalleryItem>>> = if (effectiveGrouping == TimelineGrouping.Month) {
        monthGroups
    } else {
        val dateFormat = remember(effectiveGrouping) {
            val pattern = when (effectiveGrouping) {
                TimelineGrouping.None -> "yyyy"
                TimelineGrouping.Day -> "d MMMM yyyy"
                TimelineGrouping.Month -> "MMMM yyyy"
                TimelineGrouping.Year -> "yyyy"
            }
            SimpleDateFormat(pattern, Locale.getDefault())
        }
        remember(items, effectiveGrouping) {
            if (effectiveGrouping == TimelineGrouping.None) {
                // Single flat bucket — no header row is emitted for it (the header loop below
                // skips MonthHeader entirely in None mode).
                listOf("" to items)
            } else {
                items.groupBy { item -> dateFormat.format(Date(item.captureTimeMs)) }
                    .entries.map { it.key to it.value }
            }
        }
    }

    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Two-finger pinch detector that does NOT eat single-finger drags. It only activates
    // once the second finger goes down, which leaves the grid's own LazyVerticalGrid
    // scroll free to handle vertical drags without competing for the gesture. Pinch is
    // isolated to a deliberate two-finger interaction.
    //
    // Each gesture commits exactly one level step in either direction once the distance
    // ratio crosses ±30%. Cycling through every level needs multiple gestures, which
    // matches users' expectation that one pinch = one zoom step and keeps the snap from
    // feeling jittery while the fingers are still moving.
    val pinchModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            var second: androidx.compose.ui.input.pointer.PointerInputChange? = null
            // Wait for either: (a) a second finger to enter → pinch mode, or
            //                  (b) the first finger to lift → bail out so this gesture
            //                      stays a regular drag for the grid.
            while (second == null) {
                val event = awaitPointerEvent()
                if (event.changes.none { p -> p.pressed }) return@awaitEachGesture
                second = event.changes.firstOrNull { p -> p.id != firstDown.id && p.pressed }
            }
            // Reference distance is reset to the current distance AFTER each snap, so the
            // user can keep zooming in / out continuously during one gesture (every +/-30%
            // since the last snap fires a new step) without lifting and re-pinching.
            var refDist = kotlin.math.max(
                1f,
                kotlin.math.hypot(
                    (firstDown.position.x - second.position.x).toDouble(),
                    (firstDown.position.y - second.position.y).toDouble(),
                ).toFloat(),
            )
            while (true) {
                val event = awaitPointerEvent()
                val p1 = event.changes.firstOrNull { p -> p.id == firstDown.id }
                val p2 = event.changes.firstOrNull { p -> p.id == second.id }
                if (p1 == null || p2 == null || !p1.pressed || !p2.pressed) break
                val curDist = kotlin.math.max(
                    1f,
                    kotlin.math.hypot(
                        (p1.position.x - p2.position.x).toDouble(),
                        (p1.position.y - p2.position.y).toDouble(),
                    ).toFloat(),
                )
                val ratio = curDist / refDist
                // Pinch-OUT (fingers spread, ratio > 1) zooms IN — bigger tiles, finer
                // day-level navigation. Pinch-IN (ratio < 1) zooms OUT — smaller tiles,
                // broader year-level overview. levelIndex grows as columns SHRINK in
                // the zoomLevels list (L0=6 cols flat, L5=1 col day-grouped), so
                // pinch-out increments toward L5. After each snap refDist is reset so
                // the same gesture can roll through multiple levels.
                when {
                    ratio >= 1.30f && levelIndex < zoomLevels.lastIndex -> {
                        levelIndex += 1
                        onZoomLevelChanged(levelIndex)
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                        refDist = curDist
                    }
                    ratio <= 1f / 1.30f && levelIndex > 0 -> {
                        levelIndex -= 1
                        onZoomLevelChanged(levelIndex)
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                        refDist = curDist
                    }
                }
                // Consume so the grid doesn't try to scroll while both fingers are down.
                p1.consume(); p2.consume()
            }
        }
    }

    // ── Drag-to-select ────────────────────────────────────────────────────────
    // Long-press a photo then drag to select/deselect a contiguous range; a plain single-finger drag
    // still scrolls and the two-finger pinch above is untouched. The gesture lives in the shared
    // [rememberDragMultiSelectModifier].
    val dragSelectModifier = rememberDragMultiSelectModifier(
        gridState = gridState,
        items = items,
        indexByKey = indexByKey,
        selected = selectedItems,
        onSelectionChange = onSelectionChange,
    )

    Box(modifier = Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        state = gridState,
        contentPadding = PaddingValues(
            top = topContentPadding + 8.dp,
            bottom = 120.dp,
            start = 20.dp,
            end = 20.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxSize()
            // Drag-select first (it only claims the gesture after a long-press time-out), then pinch
            // (two-finger). Plain single-finger drags fall through to the grid's own scroll.
            .then(dragSelectModifier)
            .then(pinchModifier),
    ) {
        if (permissionState == PermissionState.Denied ||
            permissionState == PermissionState.PermanentlyDenied
        ) {
            item(span = { GridItemSpan(columnCount) }, contentType = "header") {
                PermissionBanner(
                    permanent = permissionState == PermissionState.PermanentlyDenied,
                    onAction = {
                        if (permissionState == PermissionState.PermanentlyDenied) {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            ).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } else {
                            onPermissionGrant()
                        }
                    },
                )
            }
        }

        // "On this day" memories carousel — full-width row above the photo grid. Hidden
        // entirely when there are no matching items, so first-time users / users with no
        // historical photos on today's date never see an empty placeholder.
        if (showOnThisDay && onThisDayByYear.isNotEmpty()) {
            item(span = { GridItemSpan(columnCount) }, key = "on_this_day", contentType = "header") {
                OnThisDayCarousel(
                    yearGroups = onThisDayByYear,
                    onPhotoClick = onPhotoClick,
                )
            }
        }

        for ((month, monthItems) in grouped) {
            // Split month-group count by media type so the header reads
            // "11 photos, 1 video" instead of an undifferentiated "12 photos".
            val monthVideos = monthItems.count { item ->
                val mt = when (item) {
                    is GalleryItem.LocalOnly -> item.local.mimeType
                    is GalleryItem.Synced    -> item.local.mimeType
                    is GalleryItem.CloudOnly -> item.cloud.mimeType
                }
                mt.startsWith("video/")
            }
            val monthPhotos = monthItems.size - monthVideos
            // None-grouping levels (L0..L2) skip the header entirely so the user gets a
            // truly flat thumbnail wall. The single placeholder "bucket" produced above
            // is still iterated to render its items.
            if (effectiveGrouping != TimelineGrouping.None) {
                val selectedInGroup = monthItems.count { it in selectedItems }
                item(span = { GridItemSpan(columnCount) }, contentType = "header") {
                    MonthHeader(
                        month = month,
                        photoCount = monthPhotos,
                        videoCount = monthVideos,
                        isSelectionMode = isSelectionMode,
                        selectedInGroup = selectedInGroup,
                        groupSize = monthItems.size,
                        onToggleGroup = { onToggleGroup(monthItems) },
                    )
                }
            }
            items(
                monthItems,
                key = { item -> keyOf(item) },
                contentType = { "photo" },
            ) { item ->
                val cloudId = cloudLinkIdOf(item)
                // PhotoCell's inputs are resolved to primitives here in item scope so the cell stays
                // skippable: an unstable Set/GalleryItem param would force EVERY visible cell to
                // recompose on any selection toggle or thumbnail-decrypt re-emission.
                val inputs = remember(item, favoriteIds, downloadedCloudLinkIds) {
                    photoCellInputsFor(item, favoriteIds, downloadedCloudLinkIds)
                }
                PhotoCell(
                    imageData         = inputs.imageData,
                    stableKey         = inputs.stableKey,
                    isVideo           = inputs.isVideo,
                    isPlaceholder     = inputs.isPlaceholder,
                    selected          = item in selectedItems,
                    isSelectionMode   = isSelectionMode,
                    isHiddenOnDevice  = cloudId != null && cloudId in hiddenCloudLinkIds,
                    showCloudBadge    = inputs.showCloudBadge,
                    showSyncedBadge   = inputs.showSyncedBadge,
                    isFavorite        = inputs.isFavorite,
                    typeBadgeRes      = inputs.typeBadgeRes,
                    typeBadgeCdRes    = inputs.typeBadgeCdRes,
                    showTypeBadges    = columnCount < 5,
                    onClick           = {
                        if (isSelectionMode) onToggleSelect(item)
                        else onPhotoClick(items, indexByKey[keyOf(item)] ?: 0)
                    },
                )
            }
        }
    }

        // Timeline scrubber sidebar — fades in while scrolling, draggable to seek.
        // Reads the effective (pinch-controlled) grouping so its tooltip format matches
        // the headers the user is currently seeing.
        TimelineScrubber(
            gridState = gridState,
            items = items,
            grouping = effectiveGrouping,
            topPadding = topContentPadding + 8.dp,
            bottomPadding = 120.dp,
        )
    }
}

@Composable
private fun PermissionBanner(permanent: Boolean, onAction: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(ErrorChipBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                text = if (permanent)
                    stringResource(R.string.permission_permanently_denied_banner)
                else
                    stringResource(R.string.permission_denied_banner),
                color = ErrorColor,
                fontSize = 13.sp,
            )
            TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                Text(
                    text = if (permanent)
                        stringResource(R.string.permission_open_settings)
                    else
                        stringResource(R.string.permission_grant_button),
                    color = Accent,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(
    month: String,
    photoCount: Int,
    videoCount: Int,
    isSelectionMode: Boolean = false,
    selectedInGroup: Int = 0,
    groupSize: Int = 0,
    onToggleGroup: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading tri-state circle — only while selecting. Tapping it selects/deselects the
        // whole date group. Mirrors the per-cell indicator styling so the two read as one
        // selection language: filled accent + check when every item is in, accent with a
        // hollow centre when partial, dim bordered ring when none.
        if (isSelectionMode) {
            val allSelected = groupSize > 0 && selectedInGroup == groupSize
            val partiallySelected = selectedInGroup > 0 && !allSelected
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleGroup)
                    .then(
                        if (allSelected || partiallySelected)
                            Modifier.background(Accent, CircleShape)
                        else Modifier
                            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                            .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    allSelected -> Icon(
                        Icons.Default.Check, stringResource(R.string.gallery_deselect_all),
                        tint = Color.White, modifier = Modifier.size(14.dp),
                    )
                    partiallySelected -> Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color.White, CircleShape),
                    )
                }
            }
        }
        Text(
            text = month,
            color = FgPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.44).sp,
            modifier = Modifier.weight(1f),
        )
        // Use the plurals-aware count strings so "1 photo" / "1 video" render correctly
        // instead of "1 photos" / "1 videos". Mixed case is a simple localised join of the two
        // plural results — most romance + germanic languages accept comma+space, and the
        // comma is invariant.
        val photosText = androidx.compose.ui.res.pluralStringResource(
            R.plurals.count_photos_plural, photoCount, photoCount,
        )
        val videosText = androidx.compose.ui.res.pluralStringResource(
            R.plurals.count_videos_plural, videoCount, videoCount,
        )
        val countLabel = when {
            photoCount > 0 && videoCount > 0 -> "$photosText, $videosText"
            videoCount > 0 -> videosText
            else -> photosText
        }
        Text(
            text = countLabel,
            color = FgMute,
            fontSize = 12.sp,
        )
    }
}
