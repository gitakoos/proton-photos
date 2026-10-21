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

package eu.akoos.photos.presentation.gallery

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
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
import androidx.compose.runtime.MutableState
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
import eu.akoos.photos.presentation.common.ReturnToViewerPhoto
import eu.akoos.photos.presentation.lock.LocalAppLock
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
    dayGroups: List<Pair<String, List<GalleryItem>>>,
    onThisDayGroups: List<Pair<Int, List<GalleryItem>>>,
    gridState: LazyGridState,
    staggeredState: LazyStaggeredGridState,
    topContentPadding: Dp,
    permissionState: PermissionState,
    onPermissionGrant: () -> Unit,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    selectedItems: Set<GalleryItem> = emptySet(),
    isSelectionMode: Boolean = false,
    dragSelectEnabled: Boolean = true,
    onToggleSelect: (GalleryItem) -> Unit = {},
    onToggleGroup: (List<GalleryItem>) -> Unit = {},
    /** Whether a date header offers selecting its whole group; off where only one photo may be picked. */
    groupSelectable: Boolean = true,
    onSelectionChange: (Set<GalleryItem>) -> Unit = {},
    initialZoomLevel: Int = GridZoom.DEFAULT_LEVEL,
    gridRememberLast: Boolean = false,
    gridDefaultColumns: Int = GridZoom.DEFAULT_COLUMNS,
    onZoomLevelChanged: (Int) -> Unit = {},
    hiddenCloudLinkIds: Set<String> = emptySet(),
    downloadedCloudLinkIds: Set<String> = emptySet(),
    favoriteIds: Set<String> = emptySet(),
    offlinePinIds: Set<String> = emptySet(),
    // linkId → freshly-decrypted thumbnail URL from ThumbnailUrlStore. A cloud cell reads its own entry
    // so a decrypt landing mid-scroll repaints only that tile (the timeline model no longer re-emits).
    thumbnailUrls: Map<String, String> = emptyMap(),
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
    // Opt-in floating month/year label while scrolling; default off.
    val showScrollDate by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.SHOW_SCROLL_DATE] ?: false }
    }.collectAsState(initial = false)
    // Opt-in reversed chronology: oldest at the top, newest at the bottom; default off.
    val reverseOrder by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.REVERSE_TIMELINE_ORDER] ?: false }
    }.collectAsState(initial = false)
    // Opt-in staggered (masonry) layout; default off. When off, the fixed square grid below is
    // rendered exactly as before.
    val mosaicGrid by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.MOSAIC_GRID] ?: false }
    }.collectAsState(initial = false)
    // Opt-in edge-to-edge (seamless) layout; default off. When off, the padded, rounded tiles below
    // are rendered exactly as before. Drives the outer side padding, corner rounding, and gap on both
    // the fixed and mosaic grids. Read through the shared helper so the value is warm on first frame
    // (no padded-to-edge flash) and every grid across the app stays in sync.
    val seamless = rememberSeamlessGrid()

    // Reversed views used only when the toggle is on. Reversing the flat list flips the within-group
    // item order AND, because groupBy preserves encounter order, the group order for the inline
    // None/Year levels below. The Month and Day groups arrive pre-bucketed from the ViewModel, so
    // they are reversed here too — group order and each group's items. When off, all pass straight
    // through, so behaviour is byte-identical to newest-first. The scrubber + scroll-date label
    // receive [orderedItems], so their position→date mapping needs no separate reversal flag.
    val orderedItems = remember(items, reverseOrder) {
        if (reverseOrder) items.asReversed() else items
    }
    val orderedMonthGroups = remember(monthGroups, reverseOrder) {
        if (reverseOrder) monthGroups.asReversed().map { (label, groupItems) -> label to groupItems.asReversed() }
        else monthGroups
    }
    val orderedDayGroups = remember(dayGroups, reverseOrder) {
        if (reverseOrder) dayGroups.asReversed().map { (label, groupItems) -> label to groupItems.asReversed() }
        else dayGroups
    }

    // The staggered scroll state for the mosaic layout is hoisted to GalleryScreen and passed in, so
    // the screen-level scroll behaviours (re-tap scroll-to-top, overlay auto-hide, look-ahead
    // prefetch) follow it when mosaic is on. The fixed grid's [gridState] is untouched on the OFF path.

    // Both orders open at the grid's natural index-0 (the top of the page): newest in normal mode,
    // oldest when reversed. The re-tap-tab scroll-to-top also targets index 0, so the opening
    // position and the button agree in every mode.

    // Stable grid key for an item — must match the keys passed to the `items(...)` blocks below.
    // [GalleryKeyIndex] owns the scheme, so the index below parses exactly what the grid is keyed on.
    val keyOf: (GalleryItem) -> String = { item -> GalleryKeyIndex.keyOf(item) }
    val cloudLinkIdOf: (GalleryItem) -> String? = { item ->
        when (item) {
            is GalleryItem.CloudOnly -> item.cloud.linkId
            is GalleryItem.Synced    -> item.cloud.linkId
            is GalleryItem.LocalOnly -> null
        }
    }

    // key → index, so a cell's onClick resolves the tapped photo's position in O(1) instead of
    // a List.indexOf scan per click (an N-cost call repeated across every visible cell). Built over
    // [orderedItems] so the index matches the list the grid renders and hands to onPhotoClick. The
    // drag-select, the decrypt driver and the scroll-date label resolve keys through this same index,
    // which holds no key strings (see [GalleryKeyIndex]).
    val indexByKey = remember(orderedItems) { GalleryKeyIndex(orderedItems) }
    // Pending-decrypt linkId for a cloud row whose thumbnail isn't in the store yet. The timeline
    // projection no longer carries thumbnailUrl on the row, so "needs decrypt" is now "the store has no
    // URL for this linkId (and the item carries none)". Rows already in the store return null so the
    // driver below neither requests nor cancels them; the scheduler also short-circuits warm rows.
    val pendingCloudLinkIdOf: (GalleryItem) -> String? = { item ->
        when (item) {
            is GalleryItem.CloudOnly -> item.cloud.linkId
                .takeIf { thumbnailUrls[it] == null && item.cloud.thumbnailUrl == null }
            is GalleryItem.Synced    -> item.cloud.linkId
                .takeIf { thumbnailUrls[it] == null && item.cloud.thumbnailUrl == null }
            is GalleryItem.LocalOnly -> null
        }
    }

    // ── Visible-range thumbnail decrypt driver ────────────────────────────────
    // Requests decrypts for the cloud cells actually in the viewport, in viewport order, plus a
    // small leading margin, and cancels the cells that just scrolled out. Replaces the old
    // per-cell 120 ms-debounced LaunchedEffect, whose fire order followed compose/dispose timing
    // rather than scroll position, so thumbnails popped in out of order. Centralising both the
    // request and the cancel here also lets PhotoCell stay a pure (skippable) presentation cell.
    // The visible-key source is the only thing that differs between the fixed and staggered grids,
    // so it is supplied as a snapshot lambda and the range walk is shared (see [visibleRangeDecrypt]).
    if (mosaicGrid) {
        LaunchedEffect(staggeredState, orderedItems) {
            visibleRangeDecrypt(
                orderedItems = orderedItems,
                indexByKey = indexByKey,
                pendingCloudLinkIdOf = pendingCloudLinkIdOf,
                onRequestThumbnail = onRequestThumbnail,
                onCancelThumbnail = onCancelThumbnail,
                visibleKeys = { staggeredState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } },
            )
        }
    } else {
        LaunchedEffect(gridState, orderedItems) {
            visibleRangeDecrypt(
                orderedItems = orderedItems,
                indexByKey = indexByKey,
                pendingCloudLinkIdOf = pendingCloudLinkIdOf,
                onRequestThumbnail = onRequestThumbnail,
                onCancelThumbnail = onCancelThumbnail,
                visibleKeys = { gridState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } },
            )
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
    // A grid remembered at the densest level raises this on a cold start, under the app lock; its sheet
    // would open above the lock, so it waits for the unlock.
    val appLock = LocalAppLock.current
    if (showDenseWarning && !appLock.isLocked) {
        DenseGridWarningDialog(
            onDismiss = { showDenseWarning = false },
            onPersist = { showDenseWarning = false; onDismissDenseGridWarning() },
        )
    }

    // Day (the 3-column default) and Month (the 4-column level) are bucketed in the ViewModel off
    // the Main thread and arrive via [dayGroups] / [monthGroups] — at 8500+ photos, running
    // SimpleDateFormat per item inside composition on every thumbnail-decrypt re-emission was a
    // ~680 ms hitch. The remaining pinch levels (None flat / Year) are off-default and grouped
    // inline here as before; their label format and encounter order are unchanged.
    val grouped: List<Pair<String, List<GalleryItem>>> = when (effectiveGrouping) {
        TimelineGrouping.Month -> orderedMonthGroups
        TimelineGrouping.Day -> orderedDayGroups
        else -> {
            val dateFormat = remember(effectiveGrouping) {
                val pattern = when (effectiveGrouping) {
                    TimelineGrouping.None -> "yyyy"
                    TimelineGrouping.Day -> "d MMMM yyyy"
                    TimelineGrouping.Month -> "MMMM yyyy"
                    TimelineGrouping.Year -> "yyyy"
                }
                SimpleDateFormat(pattern, Locale.getDefault())
            }
            // [orderedItems] is already reversed when the toggle is on, so grouping it yields reversed
            // group order and reversed within-group items with no extra handling here.
            remember(orderedItems, effectiveGrouping) {
                if (effectiveGrouping == TimelineGrouping.None) {
                    // Single flat bucket — no header row is emitted for it (the header loop below
                    // skips MonthHeader entirely in None mode).
                    listOf("" to orderedItems)
                } else {
                    orderedItems.groupBy { item -> dateFormat.format(Date(item.captureTimeMs)) }
                        .entries.map { it.key to it.value }
                }
            }
        }
    }

    // Per-group photo/video split depends only on [grouped], so compute it once here instead of
    // re-running the O(all items) count in the grid builder on every thumbnail-decrypt recomposition.
    val monthCounts: List<Pair<Int, Int>> = remember(grouped) {
        grouped.map { (_, items) ->
            val videos = items.count { item ->
                val mt = when (item) {
                    is GalleryItem.LocalOnly -> item.local.mimeType
                    is GalleryItem.Synced    -> item.local.mimeType
                    is GalleryItem.CloudOnly -> item.cloud.mimeType
                }
                mt.startsWith("video/")
            }
            (items.size - videos) to videos   // (photos, videos)
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
                // the zoomLevels list (L0 is the densest, most columns; the last level is
                // 1 column, day-grouped), so pinch-out increments toward the last level.
                // After each snap refDist is reset so the same gesture can roll through
                // multiple levels.
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
    // [rememberDragMultiSelectModifier] (fixed grid) / [rememberStaggeredDragMultiSelectModifier].
    // Armed at the long-press anchor so the cell's release-tap (below) skips toggling the just-selected
    // cell back off; shared across both grid layouts since only one is mounted at a time.
    val tapGuard = remember { mutableStateOf(false) }
    val dragSelectModifier = rememberDragMultiSelectModifier(
        gridState = gridState,
        items = orderedItems,
        indexByKey = indexByKey,
        selected = selectedItems,
        onSelectionChange = onSelectionChange,
        tapGuard = tapGuard,
        enabled = dragSelectEnabled,
    )
    val staggeredDragSelectModifier = rememberStaggeredDragMultiSelectModifier(
        gridState = staggeredState,
        items = orderedItems,
        indexByKey = indexByKey,
        selected = selectedItems,
        onSelectionChange = onSelectionChange,
        tapGuard = tapGuard,
        enabled = dragSelectEnabled,
    )

    // Land back on the photo the viewer closed on. Both layouts emit the same shape around their
    // photos, the optional banner and On-this-day row first and then one header per group, so one
    // description of it serves both. In None grouping the single bucket gets no header, which is
    // exactly the flat case an empty group list means.
    val returnLeadingSlots =
        (if (permissionState == PermissionState.Denied ||
                permissionState == PermissionState.PermanentlyDenied) 1 else 0) +
        (if (showOnThisDay && onThisDayByYear.isNotEmpty()) 1 else 0)
    val returnGroups = remember(grouped) { grouped.map { (_, groupItems) -> groupItems } }
    val returnHasHeaders = effectiveGrouping != TimelineGrouping.None
    if (mosaicGrid) {
        ReturnToViewerPhoto(
            gridState = staggeredState,
            groups = returnGroups,
            headerPerGroup = returnHasHeaders,
            leadingSlots = returnLeadingSlots,
            keyOf = { it.stableId },
        )
    } else {
        ReturnToViewerPhoto(
            gridState = gridState,
            groups = returnGroups,
            headerPerGroup = returnHasHeaders,
            leadingSlots = returnLeadingSlots,
            keyOf = { it.stableId },
        )
    }

    if (mosaicGrid) {
        MosaicPhotoGrid(
            staggeredState = staggeredState,
            grouped = grouped,
            monthCounts = monthCounts,
            orderedItems = orderedItems,
            onThisDayByYear = onThisDayByYear,
            showOnThisDay = showOnThisDay,
            showScrollDate = showScrollDate,
            columnCount = columnCount,
            seamless = seamless,
            effectiveGrouping = effectiveGrouping,
            topContentPadding = topContentPadding,
            permissionState = permissionState,
            onPermissionGrant = onPermissionGrant,
            onPhotoClick = onPhotoClick,
            selectedItems = selectedItems,
            isSelectionMode = isSelectionMode,
            onToggleSelect = onToggleSelect,
            onToggleGroup = onToggleGroup,
            groupSelectable = groupSelectable,
            hiddenCloudLinkIds = hiddenCloudLinkIds,
            downloadedCloudLinkIds = downloadedCloudLinkIds,
            favoriteIds = favoriteIds,
            offlinePinIds = offlinePinIds,
            thumbnailUrls = thumbnailUrls,
            keyOf = keyOf,
            indexByKey = indexByKey,
            dragSelectModifier = staggeredDragSelectModifier,
            pinchModifier = pinchModifier,
            tapGuard = tapGuard,
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        state = gridState,
        contentPadding = PaddingValues(
            top = topContentPadding + 8.dp,
            bottom = selectionGridBottomPadding(120.dp, isSelectionMode),
            start = if (seamless) 0.dp else 20.dp,
            end = if (seamless) 0.dp else 20.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
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
                // Full-span rows keep the side inset even in seamless mode, where only the
                // photo tiles below bleed to the screen edge.
                Box(modifier = Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp)) {
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
        }

        // "On this day" memories carousel — full-width row above the photo grid. Hidden
        // entirely when there are no matching items, so first-time users / users with no
        // historical photos on today's date never see an empty placeholder.
        if (showOnThisDay && onThisDayByYear.isNotEmpty()) {
            item(span = { GridItemSpan(columnCount) }, key = "on_this_day", contentType = "header") {
                Box(modifier = Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp)) {
                    OnThisDayCarousel(
                        yearGroups = onThisDayByYear,
                        onPhotoClick = onPhotoClick,
                    )
                }
            }
        }

        for ((index, group) in grouped.withIndex()) {
            val (month, monthItems) = group
            val (monthPhotos, monthVideos) = monthCounts[index]
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
                        isSelectionMode = isSelectionMode && groupSelectable,
                        selectedInGroup = selectedInGroup,
                        groupSize = monthItems.size,
                        onToggleGroup = { onToggleGroup(monthItems) },
                        modifier = Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp),
                    )
                }
            }
            items(
                monthItems,
                key = { item -> keyOf(item) },
                contentType = { "photo" },
            ) { item ->
                val cloudId = cloudLinkIdOf(item)
                // The freshly-decrypted URL for this cell's cloud row, read from the store map. Reading
                // it per-cell (rather than passing the whole map to PhotoCell) keeps the cell skippable:
                // only cells whose own URL changed re-run this remember and rebind.
                val cellThumbUrl = cloudId?.let { thumbnailUrls[it] }
                // PhotoCell's inputs are resolved to primitives here in item scope so the cell stays
                // skippable: an unstable Set/GalleryItem param would force EVERY visible cell to
                // recompose on any selection toggle or thumbnail-decrypt re-emission.
                val inputs = remember(item, favoriteIds, downloadedCloudLinkIds, offlinePinIds, cellThumbUrl) {
                    photoCellInputsFor(item, favoriteIds, downloadedCloudLinkIds, offlinePinIds, cellThumbUrl)
                }
                PhotoCell(
                    imageData         = inputs.imageData,
                    stableKey         = inputs.stableKey,
                    isVideo           = inputs.isVideo,
                    isLocalVideo      = inputs.isLocalVideo,
                    durationMs        = inputs.durationMs,
                    isPlaceholder     = inputs.isPlaceholder,
                    selected          = item in selectedItems,
                    isSelectionMode   = isSelectionMode,
                    isHiddenOnDevice  = cloudId != null && cloudId in hiddenCloudLinkIds,
                    showCloudBadge    = inputs.showCloudBadge,
                    showSyncedBadge   = inputs.showSyncedBadge,
                    isFavorite        = inputs.isFavorite,
                    isOffline         = inputs.isOffline,
                    typeBadgeRes      = inputs.typeBadgeRes,
                    typeBadgeCdRes    = inputs.typeBadgeCdRes,
                    isLocalGif        = inputs.isLocalGif,
                    columns           = columnCount,
                    cornerRadius      = if (seamless) 0.dp else 10.dp,
                    onClick           = {
                        // Skip the release-tap that follows a long-press select; it would otherwise
                        // toggle the just-anchored cell back off.
                        if (tapGuard.value) {
                            tapGuard.value = false
                        } else if (isSelectionMode) onToggleSelect(item)
                        else onPhotoClick(orderedItems, indexByKey.positionOf(item).coerceAtLeast(0))
                    },
                )
            }
        }
    }

        // Tracks the scrubber's drag so the floating scroll-date label can yield while the user is
        // scrubbing — both sit near the top and must never show at once.
        var scrubberDragging by remember { mutableStateOf(false) }

        // Timeline scrubber sidebar — fades in while scrolling, draggable to seek.
        // Reads the effective (pinch-controlled) grouping so its tooltip format matches
        // the headers the user is currently seeing.
        // [orderedItems] matches the grid's render order, so the scrubber maps grid position → date
        // correctly under the reversed order without a separate flag (top = oldest when reversed).
        TimelineScrubber(
            gridState = gridState,
            items = orderedItems,
            grouping = effectiveGrouping,
            columns = columnCount,
            keyOf = keyOf,
            topPadding = topContentPadding + 8.dp,
            bottomPadding = 120.dp,
            onDraggingChange = { scrubberDragging = it },
        )

        // Opt-in floating month/year label — top-centre, visible only while the grid is actively
        // scrolling and the scrubber isn't being dragged. Shares the scrubber's date mapping so the
        // two surfaces always agree.
        if (showScrollDate) {
            ScrollDateLabel(
                gridState = gridState,
                items = orderedItems,
                grouping = effectiveGrouping,
                columns = columnCount,
                keyIndex = indexByKey,
                topPadding = topContentPadding + 12.dp,
                suppressed = scrubberDragging,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * Shared visible-range decrypt loop for both grid layouts. The fixed and staggered grids expose
 * the visible cell keys through different layout-info types, so the caller passes a snapshot
 * [visibleKeys] lambda; everything downstream (the leading-margin range walk + cancel of cells that
 * left the window) is identical. Suspends for the lifetime of the owning LaunchedEffect.
 */
private suspend fun visibleRangeDecrypt(
    orderedItems: List<GalleryItem>,
    indexByKey: Map<String, Int>,
    pendingCloudLinkIdOf: (GalleryItem) -> String?,
    onRequestThumbnail: (String) -> Unit,
    onCancelThumbnail: (String) -> Unit,
    visibleKeys: () -> List<String>,
) {
    var requested = emptySet<String>()
    snapshotFlow { visibleKeys() }.distinctUntilChanged().collect { keys ->
        val firstIdx = keys.firstNotNullOfOrNull { indexByKey[it] }
        val lastIdx = keys.asReversed().firstNotNullOfOrNull { indexByKey[it] }
        val nowRequested = LinkedHashSet<String>()
        if (firstIdx != null && lastIdx != null) {
            val to = (lastIdx + 1 + 12).coerceAtMost(orderedItems.size)
            for (i in firstIdx until to) pendingCloudLinkIdOf(orderedItems[i])?.let {
                if (nowRequested.add(it)) onRequestThumbnail(it)
            }
        }
        requested.forEach { if (it !in nowRequested) onCancelThumbnail(it) }
        requested = nowRequested
    }
}

/** Clamp band for a mosaic tile's aspect ratio, kept to a moderate portrait-to-landscape range (2:3 to
 *  3:2). The staggered grid packs into whichever lane is shortest and a date header re-levels the lanes,
 *  so an extreme panorama or sliver tile leaves the neighbouring lanes far behind and opens a large gap
 *  above the next header; holding the band near-square keeps the masonry look while cutting that ragged
 *  whitespace. */
private const val MOSAIC_ASPECT_MIN = 0.6667f
private const val MOSAIC_ASPECT_MAX = 1.5f

/**
 * Width / height aspect ratio for a mosaic tile from STORED dimensions, or null when none are
 * known. Local-backed items carry MediaStore dimensions; a cloud-only item has none, so it returns
 * null and the cell sizes itself from the decoded thumbnail instead (see the mosaic items block).
 * The ratio is clamped to a sane band.
 */
private fun storedMosaicAspect(item: GalleryItem): Float? {
    val (w, h) = when (item) {
        is GalleryItem.LocalOnly -> item.local.width to item.local.height
        is GalleryItem.Synced    -> item.local.width to item.local.height
        is GalleryItem.CloudOnly -> 0 to 0
    }
    if (w <= 0 || h <= 0) return null
    return (w.toFloat() / h.toFloat()).coerceIn(MOSAIC_ASPECT_MIN, MOSAIC_ASPECT_MAX)
}

/**
 * Opt-in staggered (masonry) timeline. A separate composable so the fixed-grid path in [PhotoGrid]
 * stays exactly as it was — this is only reached when the Mosaic toggle is on. Reads the SAME
 * ordered + grouped views the fixed grid does, so reversed-order and pinch-grouping behave
 * identically; only the layout (variable tile heights via per-item aspect ratio) differs. Date
 * headers and the On-This-Day row span the full row via [StaggeredGridItemSpan.FullLine].
 */
@Composable
private fun MosaicPhotoGrid(
    staggeredState: LazyStaggeredGridState,
    grouped: List<Pair<String, List<GalleryItem>>>,
    monthCounts: List<Pair<Int, Int>>,
    orderedItems: List<GalleryItem>,
    onThisDayByYear: List<Pair<Int, List<GalleryItem>>>,
    showOnThisDay: Boolean,
    showScrollDate: Boolean,
    columnCount: Int,
    seamless: Boolean,
    effectiveGrouping: TimelineGrouping,
    topContentPadding: Dp,
    permissionState: PermissionState,
    onPermissionGrant: () -> Unit,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    selectedItems: Set<GalleryItem>,
    isSelectionMode: Boolean,
    onToggleSelect: (GalleryItem) -> Unit,
    onToggleGroup: (List<GalleryItem>) -> Unit,
    groupSelectable: Boolean,
    hiddenCloudLinkIds: Set<String>,
    downloadedCloudLinkIds: Set<String>,
    favoriteIds: Set<String>,
    offlinePinIds: Set<String>,
    thumbnailUrls: Map<String, String>,
    keyOf: (GalleryItem) -> String,
    indexByKey: GalleryKeyIndex,
    dragSelectModifier: Modifier,
    pinchModifier: Modifier,
    tapGuard: MutableState<Boolean>,
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columnCount),
            state = staggeredState,
            contentPadding = PaddingValues(
                top = topContentPadding + 8.dp,
                bottom = selectionGridBottomPadding(120.dp, isSelectionMode),
                start = if (seamless) 0.dp else 20.dp,
                end = if (seamless) 0.dp else 20.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
            verticalItemSpacing = if (seamless) 2.dp else 6.dp,
            modifier = Modifier
                .fillMaxSize()
                .then(dragSelectModifier)
                .then(pinchModifier),
        ) {
            if (permissionState == PermissionState.Denied ||
                permissionState == PermissionState.PermanentlyDenied
            ) {
                item(span = StaggeredGridItemSpan.FullLine, contentType = "header") {
                    // Full-span rows keep the side inset even in seamless mode, where only the
                    // photo tiles below bleed to the screen edge.
                    Box(modifier = Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp)) {
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
            }

            if (showOnThisDay && onThisDayByYear.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "on_this_day", contentType = "header") {
                    Box(modifier = Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp)) {
                        OnThisDayCarousel(
                            yearGroups = onThisDayByYear,
                            onPhotoClick = onPhotoClick,
                        )
                    }
                }
            }

            for ((index, group) in grouped.withIndex()) {
                val (month, monthItems) = group
                val (monthPhotos, monthVideos) = monthCounts[index]
                // A section with fewer photos than a full row cannot fill the lanes, so a masonry mix of
                // tall and short tiles there just leaves a ragged gap under the empty lanes before the next
                // header. Lay such a sparse section out as uniform squares so it reads as a tidy short
                // block rather than whitespace.
                val sparseSection = monthItems.size < columnCount
                if (effectiveGrouping != TimelineGrouping.None) {
                    val selectedInGroup = monthItems.count { it in selectedItems }
                    item(span = StaggeredGridItemSpan.FullLine, contentType = "header") {
                        MonthHeader(
                            month = month,
                            photoCount = monthPhotos,
                            videoCount = monthVideos,
                            isSelectionMode = isSelectionMode && groupSelectable,
                            selectedInGroup = selectedInGroup,
                            groupSize = monthItems.size,
                            onToggleGroup = { onToggleGroup(monthItems) },
                            modifier = Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp),
                        )
                    }
                }
                items(
                    monthItems,
                    key = { item -> keyOf(item) },
                    contentType = { "photo" },
                ) { item ->
                    val cloudId = cloudLinkIdOfMosaic(item)
                    val cellThumbUrl = cloudId?.let { thumbnailUrls[it] }
                    val inputs = remember(item, favoriteIds, downloadedCloudLinkIds, offlinePinIds, cellThumbUrl) {
                        photoCellInputsFor(item, favoriteIds, downloadedCloudLinkIds, offlinePinIds, cellThumbUrl)
                    }
                    // Prefer the stored MediaStore aspect (local-backed items) so they lay out with
                    // no relayout. A cloud-only cell has none, so it starts square and adopts the
                    // decoded thumbnail's aspect once it loads — one relayout per such cell.
                    val storedAspect = remember(item) { storedMosaicAspect(item) }
                    var thumbAspect by remember(inputs.stableKey) { mutableStateOf(1f) }
                    PhotoCell(
                        imageData         = inputs.imageData,
                        stableKey         = inputs.stableKey,
                        isVideo           = inputs.isVideo,
                        isLocalVideo      = inputs.isLocalVideo,
                        durationMs        = inputs.durationMs,
                        isPlaceholder     = inputs.isPlaceholder,
                        selected          = item in selectedItems,
                        isSelectionMode   = isSelectionMode,
                        isHiddenOnDevice  = cloudId != null && cloudId in hiddenCloudLinkIds,
                        showCloudBadge    = inputs.showCloudBadge,
                        showSyncedBadge   = inputs.showSyncedBadge,
                        isFavorite        = inputs.isFavorite,
                        isOffline         = inputs.isOffline,
                        typeBadgeRes      = inputs.typeBadgeRes,
                        typeBadgeCdRes    = inputs.typeBadgeCdRes,
                        isLocalGif        = inputs.isLocalGif,
                        columns           = columnCount,
                        cornerRadius      = if (seamless) 0.dp else 10.dp,
                        aspectRatioOverride = if (sparseSection) 1f else (storedAspect ?: thumbAspect),
                        onIntrinsicAspect = if (!sparseSection && storedAspect == null) {
                            { aspect -> thumbAspect = aspect.coerceIn(MOSAIC_ASPECT_MIN, MOSAIC_ASPECT_MAX) }
                        } else null,
                        onClick           = {
                            // Skip the release-tap that follows a long-press select; it would otherwise
                            // toggle the just-anchored cell back off.
                            if (tapGuard.value) {
                                tapGuard.value = false
                            } else if (isSelectionMode) onToggleSelect(item)
                            else onPhotoClick(orderedItems, indexByKey.positionOf(item).coerceAtLeast(0))
                        },
                    )
                }
            }
        }

        var scrubberDragging by remember { mutableStateOf(false) }

        TimelineScrubberStaggered(
            gridState = staggeredState,
            items = orderedItems,
            grouping = effectiveGrouping,
            columns = columnCount,
            keyOf = keyOf,
            topPadding = topContentPadding + 8.dp,
            bottomPadding = 120.dp,
            onDraggingChange = { scrubberDragging = it },
        )

        if (showScrollDate) {
            ScrollDateLabelStaggered(
                gridState = staggeredState,
                items = orderedItems,
                grouping = effectiveGrouping,
                columns = columnCount,
                keyIndex = indexByKey,
                topPadding = topContentPadding + 12.dp,
                suppressed = scrubberDragging,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/** Cloud link id for the mosaic cell's hidden-overlay gate; mirrors [PhotoGrid]'s cloudLinkIdOf. */
private fun cloudLinkIdOfMosaic(item: GalleryItem): String? = when (item) {
    is GalleryItem.CloudOnly -> item.cloud.linkId
    is GalleryItem.Synced    -> item.cloud.linkId
    is GalleryItem.LocalOnly -> null
}

/**
 * Visible cell key → photo for the scroll-date labels. The timeline hands in its [keyIndex], so no
 * second whole-library map is built; a caller with only [keyOf] gets a key map built once per list.
 * Null when neither is given, which keeps the label on the scroll-fraction estimate.
 */
@Composable
private fun rememberItemForKey(
    items: List<GalleryItem>,
    keyOf: ((GalleryItem) -> String)?,
    keyIndex: GalleryKeyIndex?,
): ((String) -> GalleryItem?)? = remember(items, keyOf, keyIndex) {
    when {
        keyIndex != null -> keyIndex::itemFor
        keyOf != null -> {
            val byKey = items.associateBy(keyOf)
            val resolve: (String) -> GalleryItem? = { key -> byKey[key] }
            resolve
        }
        else -> null
    }
}

/**
 * Staggered-grid twin of [ScrollDateLabel] for the mosaic timeline. Same off-composition snapshot
 * driving and fade behaviour; only the scroll-state type differs.
 */
@Composable
private fun BoxScope.ScrollDateLabelStaggered(
    gridState: LazyStaggeredGridState,
    items: List<GalleryItem>,
    grouping: TimelineGrouping,
    topPadding: Dp,
    suppressed: Boolean,
    columns: Int? = null,
    keyOf: ((GalleryItem) -> String)? = null,
    keyIndex: GalleryKeyIndex? = null,
    modifier: Modifier = Modifier,
) {
    val dateFormat = rememberTimelineDateFormat(grouping, columns)
    // Timeline callers pass keyIndex (or keyOf) so the label reads the EXACT first visible photo (no
    // drift); with neither the label keeps the lightweight scroll-fraction estimate.
    val itemForKey = rememberItemForKey(items, keyOf, keyIndex)

    var label by remember { mutableStateOf("") }
    var scrolling by remember { mutableStateOf(false) }

    LaunchedEffect(gridState, items, dateFormat, itemForKey) {
        val resolve = itemForKey
        if (resolve == null) {
            // Albums / folders: the lightweight scroll-fraction estimate, on first-visible-index change.
            snapshotFlow { gridState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { firstIndex ->
                    label = timelineDateLabel(
                        firstIndex, gridState.layoutInfo.totalItemsCount, items, dateFormat,
                    )
                }
        } else {
            // Timeline: snapshot the capture time of the photo that FILLS the top of the screen, not the
            // one leaving it. Resolving inside snapshotFlow keeps the label in step with the frame on
            // screen (no index-vs-layout skew), and requiring the cell to be at least half in view from
            // the top makes it track the section now dominating rather than lagging a section behind on
            // the sliver still exiting up top. Header / memories-row cells carry non-photo keys, skipped.
            snapshotFlow {
                val layout = gridState.layoutInfo
                val viewportTop = layout.viewportStartOffset
                layout.visibleItemsInfo.firstNotNullOfOrNull { info ->
                    val key = info.key as? String
                    if (key != null && info.offset.y + info.size.height / 2 >= viewportTop) {
                        resolve(key)?.captureTimeMs
                    } else {
                        null
                    }
                }
            }
                .distinctUntilChanged()
                .collect { ts -> if (ts != null) label = dateFormat.format(Date(ts)) }
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { inProgress ->
                if (inProgress) {
                    scrolling = true
                } else {
                    delay(900)
                    scrolling = false
                }
            }
    }

    AnimatedVisibility(
        visible = scrolling && !suppressed && label.isNotEmpty() && items.isNotEmpty(),
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(220)),
        modifier = modifier
            .padding(top = topPadding)
            .wrapContentSize(),
    ) {
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Floating month/year pill shown over the timeline while it scrolls, giving a time reference for the
 * topmost visible photo. The label + visibility are driven entirely off the composition via
 * [snapshotFlow] — `firstVisibleItemIndex` is never read in the composable body (that read was the
 * dominant scroll-jank source), only inside the flow. Fades out a beat after scrolling stops.
 */
@Composable
internal fun BoxScope.ScrollDateLabel(
    gridState: LazyGridState,
    items: List<GalleryItem>,
    grouping: TimelineGrouping,
    topPadding: Dp,
    suppressed: Boolean,
    columns: Int? = null,
    keyOf: ((GalleryItem) -> String)? = null,
    // The timeline's own key index, which resolves a visible cell to its photo with no extra map.
    keyIndex: GalleryKeyIndex? = null,
    modifier: Modifier = Modifier,
) {
    val dateFormat = rememberTimelineDateFormat(grouping, columns)
    // Timeline callers pass keyIndex (or keyOf) so the label reads the EXACT first visible photo (no
    // drift); with neither the label keeps the lightweight scroll-fraction estimate.
    val itemForKey = rememberItemForKey(items, keyOf, keyIndex)

    var label by remember { mutableStateOf("") }
    var scrolling by remember { mutableStateOf(false) }

    // Recompute the label off-composition whenever the first visible grid index changes, mapping that
    // grid index (headers + memories row included) to the photo's capture date via the shared helper.
    LaunchedEffect(gridState, items, dateFormat, itemForKey) {
        val resolve = itemForKey
        if (resolve == null) {
            // Albums / folders: the lightweight scroll-fraction estimate, on first-visible-index change.
            snapshotFlow { gridState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { firstIndex ->
                    label = timelineDateLabel(
                        firstIndex, gridState.layoutInfo.totalItemsCount, items, dateFormat,
                    )
                }
        } else {
            // Timeline: snapshot the capture time of the photo that FILLS the top of the screen, not the
            // one leaving it. Resolving inside snapshotFlow keeps the label in step with the frame on
            // screen (no index-vs-layout skew), and requiring the cell to be at least half in view from
            // the top makes it track the section now dominating rather than lagging a section behind on
            // the sliver still exiting up top. Header / memories-row cells carry non-photo keys, skipped.
            snapshotFlow {
                val layout = gridState.layoutInfo
                val viewportTop = layout.viewportStartOffset
                layout.visibleItemsInfo.firstNotNullOfOrNull { info ->
                    val key = info.key as? String
                    if (key != null && info.offset.y + info.size.height / 2 >= viewportTop) {
                        resolve(key)?.captureTimeMs
                    } else {
                        null
                    }
                }
            }
                .distinctUntilChanged()
                .collect { ts -> if (ts != null) label = dateFormat.format(Date(ts)) }
        }
    }
    // Visibility tracks the grid's scroll activity, with a short tail so the label lingers briefly
    // after a fling settles instead of blinking out the instant motion stops.
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { inProgress ->
                if (inProgress) {
                    scrolling = true
                } else {
                    delay(900)
                    scrolling = false
                }
            }
    }

    AnimatedVisibility(
        visible = scrolling && !suppressed && label.isNotEmpty() && items.isNotEmpty(),
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(220)),
        modifier = modifier
            .padding(top = topPadding)
            .wrapContentSize(),
    ) {
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
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

/**
 * Timeline grid cell key → position in [items]: the lookup behind tap-to-open, drag-select, the
 * visible-range decrypt driver and the scroll-date label. Keys follow [keyOf], a `local_`, `synced_`
 * or `cloud_` prefix on the uri or linkId the item already carries.
 *
 * Holds no key strings. Positions sit in an open-addressed [IntArray] hashed on that uri or linkId
 * (whose hash the String caches after the first build), and a lookup reads the key's prefix and
 * compares the rest of the key in place. A rebuild per list emission therefore allocates one array
 * rather than a key String, a map node and a boxed Int per photo.
 *
 * A read-only [Map] so it drops into the lookups that take one. A repeated key keeps its last
 * position, as a HashMap filled in list order would. Equality is identity: the index is rebuilt per
 * list, and an entry-wise compare would build every key string the index exists to avoid.
 */
internal class GalleryKeyIndex(private val items: List<GalleryItem>) : AbstractMap<String, Int>() {

    // Slot → item position + 1, with 0 marking an empty slot. Sized for a load factor of at most 2/3.
    private val table: IntArray
    private val mask: Int
    private val shift: Int
    private val distinct: Int

    init {
        val needed = items.size + items.size / 2 + 1
        var capacity = 2
        while (capacity < needed) capacity = capacity shl 1
        table = IntArray(capacity)
        mask = capacity - 1
        shift = capacity.countLeadingZeroBits() + 1
        var count = 0
        for (position in items.indices) {
            val item = items[position]
            val kind = kindOf(item)
            val id = rawIdOf(item)
            val hash = id.hashCode()
            var slot = slotFor(hash)
            while (true) {
                val occupant = table[slot]
                if (occupant == 0) {
                    table[slot] = position + 1
                    count++
                    break
                }
                if (sameKey(items[occupant - 1], kind, id, hash)) {
                    table[slot] = position + 1
                    break
                }
                slot = (slot + 1) and mask
            }
        }
        distinct = count
    }

    /** Position of the photo behind grid cell [key], or -1 for a header, the memories row or a key
     *  this list does not hold. */
    fun positionOfKey(key: String): Int {
        val kind = when {
            key.startsWith(CLOUD_PREFIX) -> CLOUD
            key.startsWith(SYNCED_PREFIX) -> SYNCED
            key.startsWith(LOCAL_PREFIX) -> LOCAL
            else -> return -1
        }
        val start = prefixOf(kind).length
        val idLength = key.length - start
        // String.hashCode of the key's id part, computed in place rather than on a substring.
        var hash = 0
        for (i in start until key.length) hash = 31 * hash + key[i].code
        var slot = slotFor(hash)
        while (true) {
            val occupant = table[slot]
            if (occupant == 0) return -1
            val item = items[occupant - 1]
            if (kindOf(item) == kind) {
                val id = rawIdOf(item)
                if (id.length == idLength && id.hashCode() == hash && key.regionMatches(start, id, 0, idLength)) {
                    return occupant - 1
                }
            }
            slot = (slot + 1) and mask
        }
    }

    /** Position of [item] itself, found without building its key string; -1 when it is not indexed. */
    fun positionOf(item: GalleryItem): Int {
        val kind = kindOf(item)
        val id = rawIdOf(item)
        val hash = id.hashCode()
        var slot = slotFor(hash)
        while (true) {
            val occupant = table[slot]
            if (occupant == 0) return -1
            if (sameKey(items[occupant - 1], kind, id, hash)) return occupant - 1
            slot = (slot + 1) and mask
        }
    }

    /** The photo behind grid cell [key], or null when the key is not one of this list's photos. */
    fun itemFor(key: String): GalleryItem? {
        val position = positionOfKey(key)
        return if (position < 0) null else items[position]
    }

    override val size: Int get() = distinct

    override fun isEmpty(): Boolean = distinct == 0

    override operator fun get(key: String): Int? {
        val position = positionOfKey(key)
        return if (position < 0) null else position
    }

    override fun containsKey(key: String): Boolean = positionOfKey(key) >= 0

    override fun containsValue(value: Int): Boolean =
        value >= 0 && value < items.size && positionOf(items[value]) == value

    override val entries: Set<Map.Entry<String, Int>>
        get() = EntryView()

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "GalleryKeyIndex(size=$distinct)"

    private fun slotFor(hash: Int): Int = (hash * FIBONACCI_MULTIPLIER) ushr shift

    private fun nextOccupied(from: Int): Int {
        var slot = from
        while (slot < table.size && table[slot] == 0) slot++
        return slot
    }

    /** Entries built on demand while iterating; none of the grid's lookups walk them. */
    private inner class EntryView : AbstractSet<Map.Entry<String, Int>>() {
        override val size: Int get() = distinct

        override fun iterator(): Iterator<Map.Entry<String, Int>> = object : Iterator<Map.Entry<String, Int>> {
            private var slot = nextOccupied(0)

            override fun hasNext(): Boolean = slot < table.size

            override fun next(): Map.Entry<String, Int> {
                if (slot >= table.size) throw NoSuchElementException()
                val position = table[slot] - 1
                slot = nextOccupied(slot + 1)
                return java.util.AbstractMap.SimpleImmutableEntry(keyOf(items[position]), position)
            }
        }
    }

    companion object {
        private const val LOCAL_PREFIX = "local_"
        private const val SYNCED_PREFIX = "synced_"
        private const val CLOUD_PREFIX = "cloud_"

        private const val LOCAL = 0
        private const val SYNCED = 1
        private const val CLOUD = 2

        // 2^32 divided by the golden ratio: spreads the String hashes over the table's high bits.
        private const val FIBONACCI_MULTIPLIER = -0x61c88647

        /** The timeline grid's cell key for [item]. */
        fun keyOf(item: GalleryItem): String = when (item) {
            is GalleryItem.LocalOnly -> LOCAL_PREFIX + item.local.uri
            is GalleryItem.Synced    -> SYNCED_PREFIX + item.local.uri
            is GalleryItem.CloudOnly -> CLOUD_PREFIX + item.cloud.linkId
        }

        private fun kindOf(item: GalleryItem): Int = when (item) {
            is GalleryItem.LocalOnly -> LOCAL
            is GalleryItem.Synced    -> SYNCED
            is GalleryItem.CloudOnly -> CLOUD
        }

        private fun rawIdOf(item: GalleryItem): String = when (item) {
            is GalleryItem.LocalOnly -> item.local.uri
            is GalleryItem.Synced    -> item.local.uri
            is GalleryItem.CloudOnly -> item.cloud.linkId
        }

        private fun prefixOf(kind: Int): String = when (kind) {
            LOCAL -> LOCAL_PREFIX
            SYNCED -> SYNCED_PREFIX
            else -> CLOUD_PREFIX
        }

        private fun sameKey(item: GalleryItem, kind: Int, id: String, hash: Int): Boolean {
            if (kindOf(item) != kind) return false
            val other = rawIdOf(item)
            return other === id || (other.hashCode() == hash && other == id)
        }
    }
}
