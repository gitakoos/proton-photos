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

package eu.akoos.photos.presentation.albums

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.gallery.GridZoom
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.TimelineScrubber
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns
import eu.akoos.photos.presentation.gallery.rememberSeamlessGrid
import eu.akoos.photos.presentation.gallery.rememberDragMultiSelectModifier
import eu.akoos.photos.presentation.gallery.rememberGridPinchZoomModifier
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow

/**
 * Multi-select photo picker for adding photos to an existing album from inside the album. The grid
 * is the user's whole library (the shared gallery feed); tapping a tile toggles selection — the
 * entire screen is always in selection mode. [excludeLinkIds] are the album's current cloud members,
 * pre-filtered out so the user can't re-add duplicates.
 */
@Composable
fun AlbumPhotoPickerScreen(
    albumLinkId: String = "",
    albumName: String = "",
    excludeLinkIds: Set<String> = emptySet(),
    // Item keys (the GalleryItem.stableId keyspace) already attached to the target, filtered out for
    // both cloud and on-device photos so the picker never offers a photo the target already holds.
    excludeKeys: Set<String> = emptySet(),
    onBack: () -> Unit,
    onAdded: () -> Unit = {},
    onPick: ((List<GalleryItem>) -> Unit)? = null,
    // Device-only mode for the logged-out "New folder" flow: the grid offers only photos with a device
    // original (LocalOnly + Synced), since a folder move relocates a device file, and the
    // All/Cloud/Device filter row is hidden because the source is locked to Device.
    deviceOnly: Boolean = false,
    // Return-mode filter to videos only (the video editor's "+" picker): drops non-video items so the
    // grid offers only addable clips. The All/Cloud/Device source filter still applies on top, and no
    // source is forced, so a guest with no account still sees device videos.
    videosOnly: Boolean = false,
    // Return-mode cap on how many items may be picked at once (the video editor's source cap). Once
    // reached, unselected tiles stop toggling on and a drag-sweep is clamped. Null = no cap.
    maxSelectable: Int? = null,
    // Return-mode source manager: keys to seed the selection with when the picker opens (the videos already
    // on the timeline), so the manager shows them pre-selected. Seeded once, since the VM is fresh per open.
    preselectedKeys: Set<String> = emptySet(),
    // Keys that cannot be deselected (the video editor's locked primary): tapping one does nothing, so the
    // video being edited always stays selected.
    lockedKeys: Set<String> = emptySet(),
    // Header title when nothing is picked yet. Defaults to the "Add to album" wording; a return-mode
    // caller (collage, person) passes its own so the picker reads for its context.
    titleRes: Int = R.string.album_picker_title,
    // A title built at the call site (the label picker names its label); wins over [titleRes] while nothing is picked.
    title: String? = null,
    // The unfiled-only toggle keeps photos no album holds, which only helps a caller adding to an album; one
    // that picks for something else (a label) leaves it out.
    showUnfiledFilter: Boolean = true,
    viewModel: AlbumPhotoPickerViewModel = hiltViewModel(),
) {
    val appColors = AppColors.current
    val content by viewModel.content.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Seed the source manager's pre-selection once. The picker VM is fresh per route open, so this runs a
    // single time to mark the videos already on the timeline as selected.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (preselectedKeys.isNotEmpty()) viewModel.setSelection(preselectedKeys)
    }
    val showScrollTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 4 } }
    var pickerFilter by remember { mutableStateOf(PickerFilter.All) }
    var unfiledOnly by remember { mutableStateOf(false) }
    // Status bar + the floating back-pill + the type-filter row, so the grid's first row and the
    // scrubber track start cleanly below both.
    val headerTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 100.dp
    // The "Add" bar floats above the navigation bar, so the grid's last row clears both.
    val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Filtering, date sections and key maps are prepared in the view model off the main thread, so a
    // large library never stalls the first frame. Null means still loading, distinct from an empty result.
    val photos = content?.photos ?: emptyList()

    // Pop back to the album once the add succeeds.
    androidx.compose.runtime.LaunchedEffect(addState) {
        val done = addState
        if (done is PickerAddState.Done) {
            // Local picks upload first and only then join the album, so closing the picker with
            // nothing yet visible would read as "nothing happened" — tell the user it is coming.
            if (done.queued > 0) {
                Toast.makeText(
                    context,
                    context.getString(R.string.gallery_add_to_album_queued),
                    Toast.LENGTH_LONG,
                ).show()
            }
            viewModel.resetAddState()
            onAdded()
        }
    }

    // Drive on-demand thumbnail decrypts for the visible cloud tiles, mirroring the gallery grid.
    val keyToIndex = content?.indexByKey ?: emptyMap()
    androidx.compose.runtime.LaunchedEffect(photos) {
        var requested = emptySet<String>()
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            .distinctUntilChanged()
            .collect { keys ->
                val firstIdx = keys.firstNotNullOfOrNull { keyToIndex[it] }
                val lastIdx = keys.asReversed().firstNotNullOfOrNull { keyToIndex[it] }
                val nowRequested = LinkedHashSet<String>()
                if (firstIdx != null && lastIdx != null) {
                    val to = (lastIdx + 1 + 12).coerceAtMost(photos.size)
                    for (i in firstIdx until to) {
                        val p = photos[i]
                        val cloudId = when (p) {
                            is GalleryItem.CloudOnly -> p.cloud.linkId
                            is GalleryItem.Synced    -> p.cloud.linkId
                            is GalleryItem.LocalOnly -> null
                        }
                        if (cloudId != null && nowRequested.add(cloudId)) viewModel.requestThumbnailDecrypt(cloudId)
                    }
                }
                requested.forEach { if (it !in nowRequested) viewModel.cancelThumbnailDecrypt(it) }
                requested = nowRequested
            }
    }

    Box(modifier = Modifier.fillMaxSize().background(appColors.bg0)) {
        // Pinch-zoom level: opening at the grid-layout default, then driven by the pinch gesture.
        // The level fixes both the column count and the date grouping for the headers below.
        val defaultCols = rememberDefaultGridColumns()
        val seamless = rememberSeamlessGrid()
        var levelIndex by rememberSaveable { mutableIntStateOf(GridZoom.levelForColumns(defaultCols)) }
        val (columnCount, grouping) = GridZoom.LEVELS[levelIndex.coerceIn(0, GridZoom.LEVELS.lastIndex)]

        val query = PickerQuery(
            excludeLinkIds = excludeLinkIds,
            excludeKeys = excludeKeys,
            deviceOnly = deviceOnly,
            videosOnly = videosOnly,
            filter = pickerFilter,
            unfiledOnly = unfiledOnly,
            grouping = grouping,
        )
        androidx.compose.runtime.LaunchedEffect(query) { viewModel.setQuery(query) }

        // Drag-sweep and decrypt both read the FLAT photos order; the sections below keep that order,
        // so the flat key->index map stays valid for hit-testing across group headers.
        val photoKeys = content?.photoKeys ?: emptyList()
        // Armed at the long-press anchor so the cell's release-tap skips toggling the just-selected
        // cell back off (otherwise a stationary long-press would select then immediately deselect).
        val tapGuard = remember { mutableStateOf(false) }
        val dragSelectModifier = rememberDragMultiSelectModifier(
            gridState = gridState,
            items = photoKeys,
            indexByKey = keyToIndex,
            selected = selected,
            onSelectionChange = { proposed ->
                // A locked key (the video editor's primary) stays selected even through a drag-sweep.
                val withLocked = if (lockedKeys.isEmpty()) proposed else proposed + lockedKeys
                viewModel.setSelection(
                    if (maxSelectable == null || withLocked.size <= maxSelectable) {
                        withLocked
                    } else {
                        // Over the cap: keep every locked key first, then fill the remaining budget
                        // from the swept keys in grid order. Taking the first N of the whole set
                        // instead could drop a locked key sitting past the first N swept tiles,
                        // briefly showing the primary unselected.
                        val remaining = (maxSelectable - lockedKeys.size).coerceAtLeast(0)
                        lockedKeys + photoKeys.filter { it in withLocked && it !in lockedKeys }.take(remaining)
                    },
                )
            },
            tapGuard = tapGuard,
        )
        val pinchModifier = rememberGridPinchZoomModifier(levelIndex, GridZoom.LEVELS.size) { levelIndex = it }

        val grouped = content?.sections ?: emptyList()

        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = gridState,
            // Match the timeline grid so tiles render at the same size/spacing. Top inset clears the
            // floating back-pill so the first row starts below it (content only slides under once scrolled).
            // Edge-to-edge drops the side inset and tightens the gap.
            contentPadding = PaddingValues(
                start = if (seamless) 0.dp else 20.dp,
                end = if (seamless) 0.dp else 20.dp,
                top = headerTopInset,
                bottom = 96.dp + navBottomInset,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
            // Drag-select first (claims the gesture only after a long-press time-out), then pinch
            // (two-finger). Plain single-finger drags fall through to the grid's own scroll.
            modifier = Modifier.fillMaxSize().then(dragSelectModifier).then(pinchModifier),
        ) {
            if (photos.isEmpty()) {
                val loading = content == null
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp).fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator(color = Accent)
                        else Text(stringResource(R.string.album_picker_empty), color = FgMute, fontSize = 14.sp)
                    }
                }
            } else {
                for ((label, groupItems) in grouped) {
                    // Full-span date header: no photo key, so drag hit-testing skips it. Keeps a
                    // side inset in seamless mode so the label never hits the screen edge.
                    item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        PickerSectionHeader(
                            label = label,
                            count = groupItems.size,
                            modifier = Modifier.padding(horizontal = if (seamless) 20.dp else 0.dp),
                        )
                    }
                    items(groupItems, key = { AlbumPhotoPickerViewModel.stableKeyOf(it) }, contentType = { "photo" }) { item ->
                        val key = AlbumPhotoPickerViewModel.stableKeyOf(item)
                        val inputs = remember(item) { photoCellInputsFor(item) }
                        PhotoCell(
                            imageData = inputs.imageData,
                            stableKey = inputs.stableKey,
                            isVideo = inputs.isVideo,
                            isLocalVideo = inputs.isLocalVideo,
                            durationMs = inputs.durationMs,
                            isPlaceholder = inputs.isPlaceholder,
                            selected = key in selected,
                            isSelectionMode = true,
                            showCloudBadge = inputs.showCloudBadge,
                            showSyncedBadge = inputs.showSyncedBadge,
                            isFavorite = inputs.isFavorite,
                            typeBadgeRes = inputs.typeBadgeRes,
                            typeBadgeCdRes = inputs.typeBadgeCdRes,
                            columns = columnCount,
                            cornerRadius = if (seamless) 0.dp else 10.dp,
                            onClick = {
                                // Skip the release-tap that follows a long-press select; it would
                                // otherwise toggle the just-anchored cell back off.
                                if (tapGuard.value) tapGuard.value = false
                                // A locked key (the video editor's primary) can never be deselected.
                                else if (key in lockedKeys) {}
                                else if (key in selected || maxSelectable == null || selected.size < maxSelectable) viewModel.toggle(key)
                                else android.widget.Toast.makeText(
                                    context, context.getString(R.string.picker_selection_limit, maxSelectable),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                }
            }
        }

        // Fast-scroll scrubber over the grid — the same handle as the timeline. The drag tooltip
        // follows the pinch-derived grouping (day / month / year). Keyed to match the grid cells.
        if (photos.isNotEmpty()) {
            TimelineScrubber(
                gridState = gridState,
                items = photos,
                grouping = grouping,
                topPadding = headerTopInset,
                bottomPadding = 96.dp,
                keyOf = { AlbumPhotoPickerViewModel.stableKeyOf(it) },
            )
        }

        // The shared floating page header: back at the start, the title in a pill (the picked count once
        // something is picked), and the album name in its own pill at the end when there is an album.
        FloatingHeader(
            title = if (selected.isEmpty()) title ?: stringResource(titleRes)
            else stringResource(R.string.album_picker_selected, selected.size),
            onBack = onBack,
            trailing = if (albumName.isBlank()) null else {
                {
                    Text(
                        albumName,
                        color = FgMute, fontSize = 13.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 160.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(PillBg, RoundedCornerShape(20.dp))
                            .border(0.5.dp, PillBorder, RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            },
        )

        // Type filter — a centered segmented control: All / Cloud / Device inside one pill, each
        // its own segment, so a big mixed library can be narrowed to one source. Sits just below the
        // back-pill row; the grid content inset clears both. Hidden in device-only mode, where the
        // source is locked to Device.
        if (!deviceOnly) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(top = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(PillBg, RoundedCornerShape(20.dp))
                        .border(0.5.dp, PillBorder, RoundedCornerShape(20.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PickerFilterSegment(stringResource(R.string.picker_filter_all), pickerFilter == PickerFilter.All) {
                        pickerFilter = PickerFilter.All
                    }
                    PickerFilterSegment(stringResource(R.string.picker_filter_cloud), pickerFilter == PickerFilter.Cloud) {
                        pickerFilter = PickerFilter.Cloud
                    }
                    PickerFilterSegment(stringResource(R.string.picker_filter_device), pickerFilter == PickerFilter.Device) {
                        pickerFilter = PickerFilter.Device
                    }
                }
                // Its own pill rather than a fourth segment: the control beside it picks a SOURCE, while
                // this picks a filed status. They are independent axes, so folding them into one
                // one-of-N control would make a combination like "cloud AND unfiled" unexpressible.
                if (showUnfiledFilter) PickerUnfiledToggle(unfiledOnly) { unfiledOnly = !unfiledOnly }
            }
        }

        // Confirm bar — "Add (N)". Disabled until at least one photo is picked.
        val isWorking = addState is PickerAddState.Working
        val canAdd = selected.isNotEmpty() && !isWorking
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (canAdd) Accent else PillBg, RoundedCornerShape(999.dp))
                .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
                .clickable(enabled = canAdd) {
                    if (onPick != null) {
                        onPick(viewModel.selectedItems())
                    } else {
                        viewModel.addSelectedToAlbum(albumLinkId)
                    }
                }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isWorking) {
                CircularProgressIndicator(color = appColors.fgPrimary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
            Text(
                stringResource(R.string.album_add_count, selected.size),
                color = if (canAdd) androidx.compose.ui.graphics.Color.White else FgMute,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
        }

        // Jump-to-top pill — appears once scrolled down. Centered and stacked just above the
        // "Add (N)" confirm pill (which sits at bottom = 24.dp with ~49.dp height), mirroring the
        // gallery's scroll-to-top affordance.
        AnimatedVisibility(
            visible = showScrollTop,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 84.dp),
        ) {
            IconBubble(
                icon = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.cd_scroll_to_top),
                onClick = { scope.launch { gridState.animateScrollToItem(0) } },
                diameter = 40.dp,
                iconSize = 24.dp,
                background = PillBg,
                borderColor = PillBorder,
                tint = appColors.fgPrimary,
            )
        }

        // Surface a failed add as a snackbar.
        val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
        val addFailedFmt = stringResource(R.string.gallery_add_to_album_failed)
        androidx.compose.runtime.LaunchedEffect(addState) {
            val st = addState
            if (st is PickerAddState.Failed) {
                snackbarHostState.showSnackbar(st.message.ifBlank { addFailedFmt })
                viewModel.resetAddState()
            }
        }
        eu.akoos.photos.presentation.common.ThemedSnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Full-span date section header for the picker grid. Mirrors the timeline header's type — the date
 * title plus a trailing item count — without the group-select circle (the picker has no group select).
 */
@Composable
private fun PickerSectionHeader(label: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = FgPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.44).sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = androidx.compose.ui.res.pluralStringResource(R.plurals.count_items_plural, count, count),
            color = FgMute,
            fontSize = 12.sp,
        )
    }
}

/** One All / Cloud / Device segment inside the album picker's type-filter pill. The outer pill draws
 *  the container; a selected segment fills with the accent, an unselected one stays transparent. */
@Composable
private fun PickerFilterSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    Text(
        label,
        color = if (selected) Color.White else colors.fgPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) Accent else Color.Transparent, RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

/** The unfiled-only toggle beside the type-filter pill: keeps only photos no album holds. Repeats
 *  the segmented control's container and accent fill so the two read as one row, and carries the
 *  toggle role so its on/off state is announced. */
@Composable
private fun PickerUnfiledToggle(selected: Boolean, onToggle: () -> Unit) {
    val colors = AppColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(PillBg, RoundedCornerShape(20.dp))
            .border(0.5.dp, PillBorder, RoundedCornerShape(20.dp))
            .toggleable(value = selected, role = Role.Switch, onValueChange = { onToggle() })
            .padding(3.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.LabelOff,
            contentDescription = stringResource(R.string.picker_filter_unfiled),
            tint = if (selected) Color.White else colors.fgPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(17.dp))
                .background(if (selected) Accent else Color.Transparent, RoundedCornerShape(17.dp))
                .padding(7.dp)
                .size(16.dp),
        )
    }
}
