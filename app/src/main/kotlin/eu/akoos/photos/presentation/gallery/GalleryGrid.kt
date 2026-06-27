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
    staggeredState: LazyStaggeredGridState,
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

    // Reversed views used only when the toggle is on. Reversing the flat list flips the within-group
    // item order AND, because groupBy preserves encounter order, the group order for the inline
    // None/Day/Year levels below. The Month groups arrive pre-bucketed from the ViewModel, so they
    // are reversed here too — group order and each group's items. When off, both pass straight
    // through, so behaviour is byte-identical to newest-first. The scrubber + scroll-date label
    // receive [orderedItems], so their position→date mapping needs no separate reversal flag.
    val orderedItems = remember(items, reverseOrder) {
        if (reverseOrder) items.asReversed() else items
    }
    val orderedMonthGroups = remember(monthGroups, reverseOrder) {
        if (reverseOrder) monthGroups.asReversed().map { (label, groupItems) -> label to groupItems.asReversed() }
        else monthGroups
    }

    // The staggered scroll state for the mosaic layout is hoisted to GalleryScreen and passed in, so
    // the screen-level scroll behaviours (re-tap scroll-to-top, overlay auto-hide, look-ahead
    // prefetch) follow it when mosaic is on. The fixed grid's [gridState] is untouched on the OFF path.

    // Both orders open at the grid's natural index-0 (the top of the page): newest in normal mode,
    // oldest when reversed. The re-tap-tab scroll-to-top also targets index 0, so the opening
    // position and the button agree in every mode.

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
    // a List.indexOf scan per click (an N-cost call repeated across every visible cell). Built over
    // [orderedItems] so the index matches the list the grid renders and hands to onPhotoClick.
    val indexByKey = remember(orderedItems) {
        HashMap<String, Int>(orderedItems.size).apply {
            orderedItems.forEachIndexed { idx, gi -> put(keyOf(gi), idx) }
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
        orderedMonthGroups
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
    )
    val staggeredDragSelectModifier = rememberStaggeredDragMultiSelectModifier(
        gridState = staggeredState,
        items = orderedItems,
        indexByKey = indexByKey,
        selected = selectedItems,
        onSelectionChange = onSelectionChange,
        tapGuard = tapGuard,
    )

    if (mosaicGrid) {
        MosaicPhotoGrid(
            staggeredState = staggeredState,
            grouped = grouped,
            orderedItems = orderedItems,
            onThisDayByYear = onThisDayByYear,
            showOnThisDay = showOnThisDay,
            showScrollDate = showScrollDate,
            columnCount = columnCount,
            effectiveGrouping = effectiveGrouping,
            topContentPadding = topContentPadding,
            permissionState = permissionState,
            onPermissionGrant = onPermissionGrant,
            onPhotoClick = onPhotoClick,
            selectedItems = selectedItems,
            isSelectionMode = isSelectionMode,
            onToggleSelect = onToggleSelect,
            onToggleGroup = onToggleGroup,
            hiddenCloudLinkIds = hiddenCloudLinkIds,
            downloadedCloudLinkIds = downloadedCloudLinkIds,
            favoriteIds = favoriteIds,
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
                        // Skip the release-tap that follows a long-press select; it would otherwise
                        // toggle the just-anchored cell back off.
                        if (tapGuard.value) {
                            tapGuard.value = false
                        } else if (isSelectionMode) onToggleSelect(item)
                        else onPhotoClick(orderedItems, indexByKey[keyOf(item)] ?: 0)
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

/** Clamp band for a mosaic tile's aspect ratio so a panorama or a sliver-thin source can't produce
 *  an absurdly short or tall cell that breaks the staggered flow. */
private const val MOSAIC_ASPECT_MIN = 0.5f
private const val MOSAIC_ASPECT_MAX = 2.0f

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
    orderedItems: List<GalleryItem>,
    onThisDayByYear: List<Pair<Int, List<GalleryItem>>>,
    showOnThisDay: Boolean,
    showScrollDate: Boolean,
    columnCount: Int,
    effectiveGrouping: TimelineGrouping,
    topContentPadding: Dp,
    permissionState: PermissionState,
    onPermissionGrant: () -> Unit,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    selectedItems: Set<GalleryItem>,
    isSelectionMode: Boolean,
    onToggleSelect: (GalleryItem) -> Unit,
    onToggleGroup: (List<GalleryItem>) -> Unit,
    hiddenCloudLinkIds: Set<String>,
    downloadedCloudLinkIds: Set<String>,
    favoriteIds: Set<String>,
    keyOf: (GalleryItem) -> String,
    indexByKey: Map<String, Int>,
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
                bottom = 120.dp,
                start = 20.dp,
                end = 20.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalItemSpacing = 6.dp,
            modifier = Modifier
                .fillMaxSize()
                .then(dragSelectModifier)
                .then(pinchModifier),
        ) {
            if (permissionState == PermissionState.Denied ||
                permissionState == PermissionState.PermanentlyDenied
            ) {
                item(span = StaggeredGridItemSpan.FullLine, contentType = "header") {
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

            if (showOnThisDay && onThisDayByYear.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "on_this_day", contentType = "header") {
                    OnThisDayCarousel(
                        yearGroups = onThisDayByYear,
                        onPhotoClick = onPhotoClick,
                    )
                }
            }

            for ((month, monthItems) in grouped) {
                val monthVideos = monthItems.count { item ->
                    val mt = when (item) {
                        is GalleryItem.LocalOnly -> item.local.mimeType
                        is GalleryItem.Synced    -> item.local.mimeType
                        is GalleryItem.CloudOnly -> item.cloud.mimeType
                    }
                    mt.startsWith("video/")
                }
                val monthPhotos = monthItems.size - monthVideos
                if (effectiveGrouping != TimelineGrouping.None) {
                    val selectedInGroup = monthItems.count { it in selectedItems }
                    item(span = StaggeredGridItemSpan.FullLine, contentType = "header") {
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
                    val cloudId = cloudLinkIdOfMosaic(item)
                    val inputs = remember(item, favoriteIds, downloadedCloudLinkIds) {
                        photoCellInputsFor(item, favoriteIds, downloadedCloudLinkIds)
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
                        aspectRatioOverride = storedAspect ?: thumbAspect,
                        onIntrinsicAspect = if (storedAspect == null) {
                            { aspect -> thumbAspect = aspect.coerceIn(MOSAIC_ASPECT_MIN, MOSAIC_ASPECT_MAX) }
                        } else null,
                        onClick           = {
                            // Skip the release-tap that follows a long-press select; it would otherwise
                            // toggle the just-anchored cell back off.
                            if (tapGuard.value) {
                                tapGuard.value = false
                            } else if (isSelectionMode) onToggleSelect(item)
                            else onPhotoClick(orderedItems, indexByKey[keyOf(item)] ?: 0)
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
            topPadding = topContentPadding + 8.dp,
            bottomPadding = 120.dp,
            onDraggingChange = { scrubberDragging = it },
        )

        if (showScrollDate) {
            ScrollDateLabelStaggered(
                gridState = staggeredState,
                items = orderedItems,
                grouping = effectiveGrouping,
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
    modifier: Modifier = Modifier,
) {
    val dateFormat = rememberTimelineDateFormat(grouping)

    var label by remember { mutableStateOf("") }
    var scrolling by remember { mutableStateOf(false) }

    LaunchedEffect(gridState, items, dateFormat) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstIndex ->
                label = timelineDateLabel(
                    firstIndex,
                    gridState.layoutInfo.totalItemsCount,
                    items,
                    dateFormat,
                )
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
private fun BoxScope.ScrollDateLabel(
    gridState: LazyGridState,
    items: List<GalleryItem>,
    grouping: TimelineGrouping,
    topPadding: Dp,
    suppressed: Boolean,
    modifier: Modifier = Modifier,
) {
    val dateFormat = rememberTimelineDateFormat(grouping)

    var label by remember { mutableStateOf("") }
    var scrolling by remember { mutableStateOf(false) }

    // Recompute the label off-composition whenever the first visible grid index changes, mapping that
    // grid index (headers + memories row included) to the photo's capture date via the shared helper.
    LaunchedEffect(gridState, items, dateFormat) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstIndex ->
                label = timelineDateLabel(
                    firstIndex,
                    gridState.layoutInfo.totalItemsCount,
                    items,
                    dateFormat,
                )
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
