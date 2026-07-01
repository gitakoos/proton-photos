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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.gallery.GridZoom
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.TimelineGrouping
import eu.akoos.photos.presentation.gallery.TimelineScrubber
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns
import eu.akoos.photos.presentation.gallery.rememberDragMultiSelectModifier
import eu.akoos.photos.presentation.gallery.rememberGridPinchZoomModifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
/** Type filter for the album photo picker: everything, cloud-backed only, or on-device only. */
private enum class PickerFilter { All, Cloud, Device }

@Composable
fun AlbumPhotoPickerScreen(
    albumLinkId: String,
    albumName: String,
    excludeLinkIds: Set<String>,
    onBack: () -> Unit,
    onAdded: () -> Unit,
    viewModel: AlbumPhotoPickerViewModel = hiltViewModel(),
) {
    val appColors = AppColors.current
    val allItems by viewModel.items.collectAsStateWithLifecycle()
    val hiddenCloudLinkIds by viewModel.hiddenCloudLinkIds.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val showScrollTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 4 } }
    var pickerFilter by remember { mutableStateOf(PickerFilter.All) }
    // Status bar + the floating back-pill + the type-filter row, so the grid's first row and the
    // scrubber track start cleanly below both.
    val headerTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 100.dp

    // Drop photos already in this album, and photos hidden on this device, so the picker only
    // offers addable new ones. A hidden photo re-added to an album would un-hide it.
    val basePhotos = remember(allItems, excludeLinkIds, hiddenCloudLinkIds) {
        if (excludeLinkIds.isEmpty() && hiddenCloudLinkIds.isEmpty()) allItems
        else allItems.filter { item ->
            val cloudId = when (item) {
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.Synced    -> item.cloud.linkId
                is GalleryItem.LocalOnly -> null
            }
            cloudId == null || (cloudId !in excludeLinkIds && cloudId !in hiddenCloudLinkIds)
        }
    }
    // Type filter (#40): narrow the mixed library to cloud-backed photos (CloudOnly + Synced) or
    // on-device-only photos (LocalOnly), so it is clear which source a photo comes from.
    val photos = remember(basePhotos, pickerFilter) {
        when (pickerFilter) {
            PickerFilter.All -> basePhotos
            PickerFilter.Cloud -> basePhotos.filter { it is GalleryItem.CloudOnly || it is GalleryItem.Synced }
            PickerFilter.Device -> basePhotos.filter { it is GalleryItem.LocalOnly }
        }
    }

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
    val keyToIndex = remember(photos) {
        photos.mapIndexed { i, p -> AlbumPhotoPickerViewModel.stableKeyOf(p) to i }.toMap()
    }
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
        var levelIndex by rememberSaveable { mutableIntStateOf(GridZoom.levelForColumns(defaultCols)) }
        val (columnCount, grouping) = GridZoom.LEVELS[levelIndex.coerceIn(0, GridZoom.LEVELS.lastIndex)]

        // Drag-sweep and decrypt both read the FLAT photos order; grouping below is presentational and
        // preserves order, so the flat key->index map stays valid for hit-testing across group headers.
        val photoKeys = remember(photos) { photos.map { AlbumPhotoPickerViewModel.stableKeyOf(it) } }
        val indexByKey = remember(photoKeys) { photoKeys.withIndex().associate { (i, k) -> k to i } }
        // Armed at the long-press anchor so the cell's release-tap skips toggling the just-selected
        // cell back off (otherwise a stationary long-press would select then immediately deselect).
        val tapGuard = remember { mutableStateOf(false) }
        val dragSelectModifier = rememberDragMultiSelectModifier(
            gridState = gridState,
            items = photoKeys,
            indexByKey = indexByKey,
            selected = selected,
            onSelectionChange = viewModel::setSelection,
            tapGuard = tapGuard,
        )
        val pinchModifier = rememberGridPinchZoomModifier(levelIndex, GridZoom.LEVELS.size) { levelIndex = it }

        // Group the flat list by the level's grouping for full-span section headers. Order is preserved,
        // so the rendering order matches the flat `photos` order the drag indices/scrubber rely on.
        val grouped: List<Pair<String, List<GalleryItem>>> = remember(photos, grouping) {
            val pattern = when (grouping) {
                TimelineGrouping.None -> "yyyy"
                TimelineGrouping.Day -> "d MMMM yyyy"
                TimelineGrouping.Month -> "MMMM yyyy"
                TimelineGrouping.Year -> "yyyy"
            }
            val fmt = SimpleDateFormat(pattern, Locale.getDefault())
            photos.groupBy { fmt.format(Date(it.captureTimeMs)) }.entries.map { it.key to it.value }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = gridState,
            // Match the timeline grid so tiles render at the same size/spacing. Top inset clears the
            // floating back-pill so the first row starts below it (content only slides under once scrolled).
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = headerTopInset, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            // Drag-select first (claims the gesture only after a long-press time-out), then pinch
            // (two-finger). Plain single-finger drags fall through to the grid's own scroll.
            modifier = Modifier.fillMaxSize().then(dragSelectModifier).then(pinchModifier),
        ) {
            if (photos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.album_picker_empty), color = FgMute, fontSize = 14.sp)
                    }
                }
            } else {
                for ((label, groupItems) in grouped) {
                    // Full-span date header — no photo key, so drag hit-testing skips it.
                    item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        PickerSectionHeader(label = label, count = groupItems.size)
                    }
                    items(groupItems, key = { AlbumPhotoPickerViewModel.stableKeyOf(it) }, contentType = { "photo" }) { item ->
                        val key = AlbumPhotoPickerViewModel.stableKeyOf(item)
                        val inputs = remember(item) { photoCellInputsFor(item) }
                        PhotoCell(
                            imageData = inputs.imageData,
                            stableKey = inputs.stableKey,
                            isVideo = inputs.isVideo,
                            isPlaceholder = inputs.isPlaceholder,
                            selected = key in selected,
                            isSelectionMode = true,
                            showCloudBadge = inputs.showCloudBadge,
                            showSyncedBadge = inputs.showSyncedBadge,
                            isFavorite = inputs.isFavorite,
                            typeBadgeRes = inputs.typeBadgeRes,
                            typeBadgeCdRes = inputs.typeBadgeCdRes,
                            onClick = {
                                // Skip the release-tap that follows a long-press select; it would
                                // otherwise toggle the just-anchored cell back off.
                                if (tapGuard.value) tapGuard.value = false
                                else viewModel.toggle(key)
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

        // Top bar — the compact floating back-pill recipe shared by Map / Calendar / Search: a
        // small circular back button at top-start with its own title pill beside it, instead of a
        // full-width filled bar. The title pill is single-line (title + folded-in selected count);
        // the album name rides in its own pill pushed to the right end of the row.
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconBubble(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.close),
                onClick = onBack,
                diameter = 40.dp,
                iconSize = 18.dp,
                background = PillBg,
                borderColor = PillBorder,
                tint = appColors.fgPrimary,
            )
            Text(
                if (selected.isEmpty()) stringResource(R.string.album_picker_title)
                else stringResource(R.string.album_picker_selected, selected.size),
                color = FgPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(PillBg, RoundedCornerShape(20.dp))
                    .border(0.5.dp, PillBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Spacer(Modifier.weight(1f))
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

        // Type filter — a centered segmented control: All / Cloud / Device inside one pill, each
        // its own segment, so a big mixed library can be narrowed to one source. Sits just below the
        // back-pill row; the grid content inset clears both.
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(top = 56.dp),
            horizontalArrangement = Arrangement.Center,
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
                .clickable(enabled = canAdd) { viewModel.addSelectedToAlbum(albumLinkId) }
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
                background = PillBgOpaque,
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
private fun PickerSectionHeader(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp),
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
