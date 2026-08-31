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

package eu.akoos.photos.presentation.duplicates

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.FindDuplicatesUseCase.DuplicateGroup
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.duplicates.DuplicateFinderViewModel.CloudFullRes
import eu.akoos.photos.presentation.gallery.LocalThumbnailUrls
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.util.formatBytes
import eu.akoos.photos.presentation.util.formatVideoTime
import eu.akoos.photos.presentation.viewer.MetaRow
import eu.akoos.photos.presentation.viewer.MetadataSection
import eu.akoos.photos.presentation.viewer.formatExifDateTime
import eu.akoos.photos.presentation.viewer.formatMsWithTime
import eu.akoos.photos.presentation.viewer.shownCoordinates
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.PhotoMetadata
import java.io.File

private val SHEET_CORNER = 28.dp
private val HANDLE_WIDTH = 32.dp
private val HANDLE_HEIGHT = 4.dp
private const val SETTLE_VELOCITY = 400f

/**
 * Full-screen review of one duplicate group. A pager swipes the copies at full resolution above a
 * draggable drawer that carries their details; pulling the drawer up shrinks the photo and reveals
 * more, pulling it down grows the photo back. A copy is marked for removal with the same top-left
 * circle the gallery uses, so the review reads like the rest of the app.
 *
 * Every copy's details are fetched as the group opens ([onRequestFullRes] over the cloud copies; device
 * copies already carry theirs), so the panel is populated rather than spinning. The removal selection
 * is the SAME hoisted set the card edits ([selected] / [onSelectionChange]), and delete goes through the
 * one keep-set contract ([keepIdsForRemoval] over [toggleDuplicateRemoval]), which can never tick a
 * group's last copy. Delete closes the review, since the pruned group would leave a stale copy here.
 */
@Composable
internal fun DuplicateGroupReview(
    group: DuplicateGroup,
    selected: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    isDeleting: Boolean,
    onDeleteExtras: (DuplicateGroup, Set<String>) -> Unit,
    onClose: () -> Unit,
    requestDecrypt: (String) -> Unit,
    cancelDecrypt: (String) -> Unit,
    cloudFullRes: Map<String, CloudFullRes>,
    onRequestFullRes: (GalleryItem) -> Unit,
    initialIndex: Int = 0,
    albumNames: Map<String, String> = emptyMap(),
) {
    BackHandler { onClose() }

    val allIds = remember(group) { group.items.map { it.stableId }.toSet() }
    // Open on the copy the user tapped in the group (falls back to the first) so tapping a tile lands
    // straight on it instead of always starting at the front.
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (group.items.size - 1).coerceAtLeast(0)),
        pageCount = { group.items.size },
    )
    var showConfirm by remember(group) { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val exifCache = remember { mutableStateMapOf<String, PhotoMetadata>() }

    val currentItem = group.items[pagerState.currentPage]
    val canDelete = !isDeleting && selected.isNotEmpty()

    // Fetch every copy's full-res (and thereby its true size and dimensions) as the group opens, so a
    // swipe lands on already-resolved details instead of a spinner. Device copies need no fetch.
    LaunchedEffect(group) {
        group.items.forEach { if (it is GalleryItem.CloudOnly) onRequestFullRes(it) }
    }

    // Read the visible copy's full EXIF once off the main thread and cache it: a device copy from its
    // own file, a cloud copy from the decrypted full-res once that download lands.
    val exifSourceUri: String? = currentItem.reviewExifUri(cloudFullRes)
    LaunchedEffect(currentItem.stableId, exifSourceUri) {
        val id = currentItem.stableId
        if (exifSourceUri != null && id !in exifCache) {
            val meta = withContext(Dispatchers.IO) {
                runCatching { ExifHelper.readMetadata(context, exifSourceUri) }.getOrNull()
            }
            if (meta != null) exifCache[id] = meta
        }
    }

    // Drawer travel: a peek that shows the handle and the first details, expanding to the rest. The
    // photo above takes the remaining height, so growing the drawer shrinks the photo and vice versa.
    val peekPx = with(density) { 190.dp.toPx() }
    val expandedPx = with(density) {
        minOf(430.dp, (LocalConfiguration.current.screenHeightDp * 0.60f).dp).toPx()
    }
    val travelPx = (expandedPx - peekPx).coerceAtLeast(0f)
    val reveal = remember { Animatable(0f) }
    val expanded = reveal.value > travelPx / 2f
    val dragState = rememberDraggableState { delta ->
        scope.launch { reveal.snapTo((reveal.value - delta).coerceIn(0f, travelPx)) }
    }
    val handleDrag = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = dragState,
        onDragStopped = { velocity ->
            val target = when {
                velocity < -SETTLE_VELOCITY -> travelPx
                velocity > SETTLE_VELOCITY -> 0f
                reveal.value > travelPx / 2f -> travelPx
                else -> 0f
            }
            // Settle on the stable scope rather than the drag gesture's, which no longer outlives the
            // release, so the panel snaps to a detent instead of sticking where the finger lifted.
            scope.launch { reveal.animateTo(target, tween(240)) }
        },
    )
    val toggleDetent: () -> Unit = {
        scope.launch { reveal.animateTo(if (expanded) 0f else travelPx, tween(240)) }
    }
    // Scrolling the details first grows the drawer (and shrinks the photo); only once it is fully open
    // does the content itself scroll, and a pull back down at the top collapses the drawer again.
    val sheetNested = remember(travelPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy < 0f && reveal.value < travelPx) {
                    val used = (-dy).coerceAtMost(travelPx - reveal.value)
                    scope.launch { reveal.snapTo((reveal.value + used).coerceIn(0f, travelPx)) }
                    return Offset(0f, -used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy > 0f && reveal.value > 0f) {
                    val used = dy.coerceAtMost(reveal.value)
                    scope.launch { reveal.snapTo((reveal.value - used).coerceIn(0f, travelPx)) }
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (reveal.value <= 0f || reveal.value >= travelPx) return Velocity.Zero
                val target = when {
                    available.y < -SETTLE_VELOCITY -> travelPx
                    available.y > SETTLE_VELOCITY -> 0f
                    reveal.value > travelPx / 2f -> travelPx
                    else -> 0f
                }
                scope.launch { reveal.animateTo(target, tween(240)) }
                return available
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg0)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar, held clear of the status bar: close on the left, page position centered, one
            // delete on the right, live only while a copy is ticked and tinted destructive.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(Icons.Default.Close, stringResource(R.string.close), onClick = onClose)
                Spacer(Modifier.weight(1f))
                Text(
                    "${pagerState.currentPage + 1} / ${group.items.size}",
                    color = FgMute, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                IconBubble(
                    icon = Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.duplicates_confirm_delete),
                    onClick = { showConfirm = true },
                    enabled = canDelete,
                    tint = if (canDelete) ErrorColor else FgMute,
                )
            }

            // The photo, sized to whatever height the drawer leaves. A cloud page shows its decrypted
            // thumbnail until the full-res download lands; the removal circle sits top-left on the photo.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                val item = group.items[page]
                val pendingLinkId =
                    (item as? GalleryItem.CloudOnly)?.cloud?.takeIf { it.thumbnailUrl == null }?.linkId
                if (pendingLinkId != null) {
                    LaunchedEffect(pendingLinkId) {
                        delay(120)
                        requestDecrypt(pendingLinkId)
                    }
                    DisposableEffect(pendingLinkId) {
                        onDispose { cancelDecrypt(pendingLinkId) }
                    }
                }
                val inputs = photoCellInputsFor(item)
                // Only use the full-res once its temp file is really there; a cache-pruned uri would
                // load to black, so fall back to the thumbnail (the view model re-fetches it meanwhile).
                val fullResUri = (item as? GalleryItem.CloudOnly)?.let { cloudFullRes[it.cloud.linkId]?.uri }
                    ?.takeIf { fileUriExists(it) }
                val model = fullResUri ?: inputs.imageData ?: LocalThumbnailUrls.current.value[inputs.stableKey]
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (model != null) {
                        AsyncImage(
                            model = model,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    } else {
                        Icon(
                            Icons.Default.Photo,
                            contentDescription = null,
                            tint = FgDim.copy(alpha = 0.45f),
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    SelectionCircle(
                        selected = item.stableId in selected,
                        onClick = { onSelectionChange(toggleDuplicateRemoval(selected, item.stableId, allIds)) },
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    )
                }
            }

            // Draggable details drawer: a rounded top with a handle, its height driven by [reveal] so a
            // drag or a tap on the handle grows it (and shrinks the photo) between the peek and expanded.
            val shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { (peekPx + reveal.value).toDp() })
                    .clip(shape)
                    .background(Bg2)
                    .border(0.5.dp, PillBorder, shape)
                    .clipToBounds(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(handleDrag)
                        .clickable(onClick = toggleDetent)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(HANDLE_WIDTH, HANDLE_HEIGHT)
                            .background(FgMute.copy(alpha = 0.5f), CircleShape),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .nestedScroll(sheetNested)
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val exif = exifCache[currentItem.stableId]

                    // Row order mirrors the photo viewer's own File-info section exactly (File, Size,
                    // Type, Local folder, Cloud albums, Source) so the two details sheets read the same.
                    MetadataSection(label = stringResource(R.string.viewer_meta_section_file_info)) {
                        MetaRow(stringResource(R.string.viewer_meta_row_file), currentItem.reviewName())
                        MetaRow(stringResource(R.string.viewer_meta_row_size), currentItem.reviewSize(cloudFullRes))
                        MetaRow(stringResource(R.string.viewer_meta_row_type), currentItem.reviewType())
                        // Where the copy lives, matching the photo viewer's own details rows so the
                        // review shows the same album/folder cue used to pick which copy to remove.
                        val reviewFolder = when (val ci = currentItem) {
                            is GalleryItem.LocalOnly -> ci.local.bucketName
                            is GalleryItem.Synced -> ci.local.bucketName
                            is GalleryItem.CloudOnly -> null
                        }?.takeIf { it.isNotBlank() }
                        reviewFolder?.let {
                            MetaRow(stringResource(R.string.viewer_meta_row_local_folder), it)
                        }
                        val reviewAlbum = when (val ci = currentItem) {
                            is GalleryItem.CloudOnly -> albumNames[ci.cloud.linkId]
                            is GalleryItem.Synced -> albumNames[ci.cloud.linkId]
                            is GalleryItem.LocalOnly -> null
                        }?.takeIf { it.isNotBlank() }
                        reviewAlbum?.let {
                            MetaRow(stringResource(R.string.viewer_meta_row_cloud_albums), it)
                        }
                        MetaRow(stringResource(R.string.viewer_meta_row_source), stringResource(currentItem.reviewSourceRes()))
                    }

                    MetadataSection(label = stringResource(R.string.viewer_meta_section_image)) {
                        MetaRow(stringResource(R.string.viewer_meta_row_resolution), currentItem.reviewResolution(cloudFullRes))
                        currentItem.reviewDurationMs()?.let {
                            MetaRow(stringResource(R.string.viewer_meta_row_duration), formatVideoTime(it))
                        }
                    }

                    MetadataSection(label = stringResource(R.string.viewer_meta_section_datetime)) {
                        MetaRow(stringResource(R.string.viewer_meta_row_date), formatMsWithTime(currentItem.captureTimeMs))
                        exif?.dateTimeOriginal?.let {
                            MetaRow(stringResource(R.string.viewer_meta_row_taken), formatExifDateTime(it))
                        }
                        exif?.dateTime?.takeIf { it != exif.dateTimeOriginal }?.let {
                            MetaRow(stringResource(R.string.viewer_meta_row_modified), formatExifDateTime(it))
                        }
                    }

                    val coords = shownCoordinates(null, exif)
                    if (coords.latitude != null || coords.longitude != null || exif?.gpsAltitude != null) {
                        MetadataSection(label = stringResource(R.string.viewer_meta_section_location)) {
                            coords.latitude?.let {
                                MetaRow(stringResource(R.string.viewer_meta_row_latitude), "%.6f°".format(it))
                            }
                            coords.longitude?.let {
                                MetaRow(stringResource(R.string.viewer_meta_row_longitude), "%.6f°".format(it))
                            }
                            exif?.gpsAltitude?.let {
                                MetaRow(stringResource(R.string.viewer_meta_row_altitude), "%.1f m".format(it))
                            }
                        }
                    }

                    if (exif != null && (
                            exif.make != null || exif.model != null || exif.lensModel != null ||
                                exif.focalLength != null || exif.aperture != null || exif.exposureTime != null ||
                                exif.isoSpeed != null || exif.flash != null || exif.whiteBalance != null
                            )
                    ) {
                        MetadataSection(label = stringResource(R.string.viewer_meta_section_camera)) {
                            exif.make?.let { MetaRow(stringResource(R.string.viewer_meta_row_make), it) }
                            exif.model?.let { MetaRow(stringResource(R.string.viewer_meta_row_model), it) }
                            exif.lensModel?.let { MetaRow(stringResource(R.string.viewer_meta_row_lens), it) }
                            exif.focalLength?.let { MetaRow(stringResource(R.string.viewer_meta_row_focal_length), "${it}mm") }
                            exif.aperture?.let { MetaRow(stringResource(R.string.viewer_meta_row_aperture), "f/$it") }
                            exif.exposureTime?.let { MetaRow(stringResource(R.string.viewer_meta_row_exposure), it) }
                            exif.isoSpeed?.let { MetaRow(stringResource(R.string.viewer_meta_row_iso), it) }
                            exif.flash?.let {
                                MetaRow(
                                    stringResource(R.string.viewer_meta_row_flash),
                                    if (it and 0x01 != 0) stringResource(R.string.viewer_meta_flash_fired)
                                    else stringResource(R.string.viewer_meta_flash_none),
                                )
                            }
                            exif.whiteBalance?.let {
                                MetaRow(
                                    stringResource(R.string.viewer_meta_row_white_balance),
                                    if (it == 0) stringResource(R.string.viewer_meta_wb_auto)
                                    else stringResource(R.string.viewer_meta_wb_manual),
                                )
                            }
                        }
                    }

                    if (exif != null && (
                            exif.description != null || exif.software != null ||
                                exif.artist != null || exif.copyright != null
                            )
                    ) {
                        MetadataSection(label = stringResource(R.string.viewer_meta_section_software)) {
                            exif.description?.let { MetaRow(stringResource(R.string.viewer_meta_row_description), it) }
                            exif.software?.let { MetaRow(stringResource(R.string.viewer_meta_row_software), it) }
                            exif.artist?.let { MetaRow(stringResource(R.string.viewer_meta_row_artist), it) }
                            exif.copyright?.let { MetaRow(stringResource(R.string.viewer_meta_row_copyright), it) }
                        }
                    }
                }
            }
        }

        if (showConfirm) {
            // A backed-up copy carries its device file with it: leaving that file behind would only have
            // the next backup re-upload it and put the duplicate straight back.
            val takesDeviceCopyToo = group.items.any { it.stableId in selected && it is GalleryItem.Synced }
            ConfirmDialog(
                title = stringResource(R.string.duplicates_confirm_title),
                message = stringResource(
                    if (takesDeviceCopyToo) R.string.duplicates_confirm_message_synced
                    else R.string.duplicates_confirm_message
                ),
                confirmLabel = stringResource(R.string.duplicates_confirm_delete),
                dismissLabel = stringResource(R.string.cancel),
                destructive = true,
                onConfirm = {
                    showConfirm = false
                    onDeleteExtras(group, keepIdsForRemoval(allIds, selected))
                    onClose()
                },
                onDismiss = { showConfirm = false },
            )
        }
    }
}

/** The gallery's own top-left selection circle: a filled accent tick when picked, an empty ringed
 *  scrim otherwise, so marking a copy for removal here reads exactly like selecting one in the grid. */
@Composable
private fun SelectionCircle(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(
                onClickLabel = stringResource(if (selected) R.string.duplicates_keep else R.string.duplicates_remove),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier.size(22.dp).background(Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        } else {
            Box(
                Modifier
                    .size(22.dp)
                    .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape),
            )
        }
    }
}

/** The uri to read EXIF from: a device copy's own file, or a cloud copy's decrypted full-res once it
 *  has downloaded (null until then, so the read waits for it). */
private fun GalleryItem.reviewExifUri(fullRes: Map<String, CloudFullRes>): String? = when (this) {
    is GalleryItem.LocalOnly -> local.uri
    is GalleryItem.Synced    -> local.uri
    is GalleryItem.CloudOnly -> fullRes[cloud.linkId]?.uri?.takeIf { fileUriExists(it) }
}

/** Whether a file uri still resolves to a real file (a cloud full-res temp can be cache-pruned). */
private fun fileUriExists(uri: String): Boolean =
    runCatching { Uri.parse(uri).path?.let { File(it).exists() } == true }.getOrDefault(false)

/** The Source row string: device-only, cloud-only, or backed up on both sides. */
private fun GalleryItem.reviewSourceRes(): Int = when (this) {
    is GalleryItem.LocalOnly -> R.string.viewer_meta_source_device_only
    is GalleryItem.CloudOnly -> R.string.viewer_meta_source_cloud
    is GalleryItem.Synced    -> R.string.viewer_meta_source_backed_up
}

private fun GalleryItem.reviewName(): String = when (this) {
    is GalleryItem.LocalOnly -> local.displayName
    is GalleryItem.Synced    -> local.displayName
    is GalleryItem.CloudOnly -> cloud.displayName
}.ifBlank { "—" }

private fun GalleryItem.reviewType(): String = when (this) {
    is GalleryItem.LocalOnly -> local.mimeType
    is GalleryItem.Synced    -> local.mimeType
    is GalleryItem.CloudOnly -> cloud.mimeType
}.ifBlank { "—" }

/** Video length in ms, or null for a still (so the Duration row only appears on videos). */
private fun GalleryItem.reviewDurationMs(): Long? = when (this) {
    is GalleryItem.LocalOnly -> local.duration.takeIf { it > 0 }
    is GalleryItem.Synced    -> local.duration.takeIf { it > 0 }
    is GalleryItem.CloudOnly -> cloud.durationMs?.takeIf { it > 0 }
}

/** Byte size shown for the copy: the device file's for a local or synced copy; for a cloud-only copy
 *  the resolved full-res size, or an ellipsis while its download is still in flight. */
private fun GalleryItem.reviewSize(fullRes: Map<String, CloudFullRes>): String = when (this) {
    is GalleryItem.LocalOnly -> if (local.sizeBytes > 0) formatBytes(local.sizeBytes) else "—"
    is GalleryItem.Synced    -> if (local.sizeBytes > 0) formatBytes(local.sizeBytes) else "—"
    is GalleryItem.CloudOnly -> fullRes[cloud.linkId]?.let { formatBytes(it.sizeBytes) } ?: "…"
}

/** "w × h" from the device file's stored dimensions, or the resolved full-res dimensions for a
 *  cloud-only copy (an ellipsis while its download is still in flight). */
private fun GalleryItem.reviewResolution(fullRes: Map<String, CloudFullRes>): String {
    val dims: Pair<Int, Int>? = when (this) {
        is GalleryItem.LocalOnly -> local.width to local.height
        is GalleryItem.Synced    -> local.width to local.height
        is GalleryItem.CloudOnly -> fullRes[cloud.linkId]?.let { it.width to it.height } ?: return "…"
    }
    val (w, h) = dims ?: return "—"
    return if (w > 0 && h > 0) "$w × $h" else "—"
}
