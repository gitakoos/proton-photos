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

package eu.akoos.photos.presentation.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.gallery.LocalThumbnailUrls
import eu.akoos.photos.presentation.gallery.PhotoCellInputs
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PillBorder
import kotlinx.coroutines.launch

/**
 * One action offered over a multi-select selection: an icon, an already-resolved label and its
 * click. A normal action leaves [tint] unspecified, so the shared action row paints it in the app's
 * accent; a destructive one such as delete or remove passes ErrorColor. While [working] the icon
 * becomes a spinner, determinate when [progress] is set, overlaid with [workingIcon], and the label
 * switches to [workingLabel].
 *
 * A working row is inert, so a second tap cannot land on work already under way. An action whose
 * tap is what stops that work sets [clickableWhileWorking] and stays live throughout: the album
 * download is one, where the running row is also the batch's cancel control.
 */
data class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val tint: Color = Color.Unspecified,
    val enabled: Boolean = true,
    val working: Boolean = false,
    val workingIcon: ImageVector? = null,
    val workingLabel: String? = null,
    val progress: Float? = null,
    val clickableWhileWorking: Boolean = false,
)

/**
 * The favourite action every selection drawer lists, so none of the surfaces decides the wording or
 * the glyph for itself. [turnsOn] is [favoriteTurnsOn]'s answer for the selection the drawer belongs
 * to.
 *
 * The icon states the press, not the selection: a filled heart to put these into Favourites, an
 * empty one to take them back out, matching every other row whose glyph is its action. The label
 * names the same press in words, in the wording the viewer's own heart carries.
 */
@Composable
fun favoriteSelectionAction(
    turnsOn: Boolean,
    state: FavoriteActionState,
    onClick: () -> Unit,
): SelectionAction {
    val working = state as? FavoriteActionState.Working
    return SelectionAction(
        icon = if (turnsOn) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
        label = stringResource(
            if (turnsOn) R.string.cd_favorite_add else R.string.cd_favorite_remove,
        ),
        working = working != null,
        progress = working?.let { if (it.total > 0) it.done.toFloat() / it.total else 0f },
        enabled = working == null,
        onClick = onClick,
    )
}

/** Holder for the last non-empty selection the drawer was handed. Deliberately a plain object
 *  rather than snapshot state: it is only ever read once the live selection has already emptied,
 *  so writing it must not schedule a recomposition of its own. */
private class FrozenSelection {
    var items: List<GalleryItem> = emptyList()
    var actions: List<SelectionAction> = emptyList()
}

/** Height of one action pill and the gap between two of them, matching the app's other action
 *  sheets. The full expanded travel is derived from these rather than measured, so the drawer knows
 *  both detents before it lays out. */
private val ACTION_ROW_HEIGHT = 50.dp
private val ACTION_ROW_GAP = 8.dp

/** Padding above and below the action list, counted into the expanded travel. */
private val ACTION_LIST_INSET = 8.dp

/** Top corners and body inset of a bottom sheet in this app. The drawer is not a ModalBottomSheet,
 *  so it carries the numbers the material sheets around it are drawn with. */
private val SHEET_CORNER = 28.dp
private val SHEET_SIDE_PADDING = 20.dp

/** Clear space between the selected photos and the first action row, so the strip reads as its own
 *  band rather than as the top of the list. */
private val STRIP_TO_ACTIONS_GAP = 14.dp

/** Edge of one thumbnail in the resting strip, and the edge it grows to while it is previewed. */
private val THUMB_SIZE = 56.dp
private val THUMB_PREVIEW_SIZE = 168.dp

/** How long a thumbnail takes to grow into its preview and to settle back. */
private const val THUMB_PREVIEW_MS = 220

/** Decode budget for a strip thumbnail. Matches the grid's own budget so a selected photo whose
 *  tile is already warm in Coil's memory cache is an O(1) hit here instead of a second decode. */
private const val SELECTION_THUMB_PX = 320

/** Past this drag speed the release settles the drawer in the direction of the fling rather than
 *  by how far it travelled. Pixels per second. */
private const val SETTLE_VELOCITY = 400f

/** Share of the screen the expanded action list may take. The drawer is an overlay over a live
 *  grid, so it keeps most of the photos on screen even fully open. */
private const val MAX_EXPANDED_FRACTION = 0.55f

/**
 * The bottom drawer every multi-select surface shares, with two detents the user drags between.
 *
 * Rest is the state it opens in: a low bar carrying the selected photos as a horizontally
 * scrollable strip of thumbnails, the media-typed count, a chevron and a drag handle. No action row
 * shows through, nothing behind it is covered by a scrim and no action list is thrown up the
 * screen, so selecting carries on underneath. Dragging the grab area up, tapping the handle or
 * tapping the chevron reveals the actions as a vertical list of labelled rows; dragging down
 * returns to rest and stops there. Leaving selection mode is the close bubble's job alone.
 *
 * Tapping a thumbnail blows it up in place for a closer look and puts the rows below it further
 * down; scrolling the strip or touching an action row settles it back. The preview never changes
 * the selection.
 *
 * [visible] drives the slide in and out. [items] is the live selection, [actions] the action set
 * built for it, and [onDismiss] leaves selection mode from the drawer's own close control. The
 * caller positions the drawer through [modifier], normally bottom-aligned inside a Box.
 */
@Composable
fun SelectionDrawer(
    visible: Boolean,
    items: List<GalleryItem>,
    actions: List<SelectionAction>,
    onDismiss: () -> Unit,
    contentScrolling: Boolean = false,
    cacheThumbnails: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Selection mode is derived from the selection itself, so clearing it empties [items] and
    // [actions] while the drawer is still animating out. The drawer renders this snapshot whenever
    // the incoming selection is empty, which keeps its thumbnails and its action list exactly as
    // they were for the whole exit.
    val frozen = remember { FrozenSelection() }
    if (items.isNotEmpty()) {
        frozen.items = items
        frozen.actions = actions
    }
    val shownItems = if (items.isNotEmpty()) items else frozen.items
    val shownActions = if (items.isNotEmpty()) actions else frozen.actions

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
    ) {
        if (shownItems.isEmpty() && shownActions.isEmpty()) return@AnimatedVisibility
        SelectionDrawerBody(
            items = shownItems,
            actions = shownActions,
            opened = visible,
            onDismiss = onDismiss,
            contentScrolling = contentScrolling,
            cacheThumbnails = cacheThumbnails,
        )
    }
}

/**
 * The drawer surface itself, always rendered from an already-resolved (and possibly frozen)
 * selection. [opened] re-seats the drawer at rest each time it comes back, and is deliberately not
 * consulted for anything else so a closing drawer holds its geometry as well as its content.
 */
@Composable
private fun SelectionDrawerBody(
    items: List<GalleryItem>,
    actions: List<SelectionAction>,
    opened: Boolean,
    onDismiss: () -> Unit,
    contentScrolling: Boolean,
    cacheThumbnails: Boolean,
) {
    val colors = AppColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER)

    // Full expanded travel: every action pill, the gaps between them and the padding around the
    // list. Capped so the drawer never owns most of the screen.
    val naturalExpanded = ACTION_LIST_INSET * 2 + STRIP_TO_ACTIONS_GAP +
        ACTION_ROW_HEIGHT * actions.size +
        ACTION_ROW_GAP * (actions.size - 1).coerceAtLeast(0)
    val maxExpanded = (LocalConfiguration.current.screenHeightDp * MAX_EXPANDED_FRACTION).dp
    // The action list scrolls only when it genuinely overflows. While it fits, a vertical drag over
    // the rows belongs to the drawer: a scroll that is always on consumes that drag first, which
    // leaves the handle as the only way to pull the drawer shut.
    val listOverflows = naturalExpanded > maxExpanded
    val listScroll = rememberScrollState()
    val expandedTravelPx = with(density) { minOf(naturalExpanded, maxExpanded).toPx() }

    // Rest is zero travel: the action list measures nothing there, so the resting bar is the
    // handle, the count, the chevron, the close bubble and the thumbnail strip.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(opened) { if (opened) reveal.snapTo(0f) }
    LaunchedEffect(expandedTravelPx) {
        if (reveal.value > expandedTravelPx) reveal.snapTo(expandedTravelPx)
    }
    val expanded = reveal.value > expandedTravelPx / 2f
    val dragState = rememberDraggableState { delta ->
        scope.launch { reveal.snapTo((reveal.value - delta).coerceIn(0f, expandedTravelPx)) }
    }
    // A scrolling list wins a vertical drag before the drawer sees it, so a list long enough to
    // scroll would leave the handle as the only way to pull the drawer shut. This hands back what
    // the list could not use: at its top there is nothing above to reach, so a further pull down
    // belongs to the drawer.
    val listHandoff = remember(expandedTravelPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0f || reveal.value <= 0f) return Offset.Zero
                val settled = (reveal.value - available.y).coerceIn(0f, expandedTravelPx)
                val used = reveal.value - settled
                scope.launch { reveal.snapTo(settled) }
                return Offset(0f, used)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (reveal.value <= 0f || reveal.value >= expandedTravelPx) return Velocity.Zero
                reveal.animateTo(
                    if (reveal.value > expandedTravelPx / 2f) expandedTravelPx else 0f,
                    tween(240),
                )
                return Velocity.Zero
            }
        }
    }
    // Both detents are travel positions, so no drag, fling or chevron tap can end the selection.
    // The close bubble is the one control that dismisses.
    val toggleDetent: () -> Unit = {
        scope.launch { reveal.animateTo(if (expanded) 0f else expandedTravelPx, tween(240)) }
    }

    // Scrolling what sits behind the drawer collapses it, so reaching past it to carry on through
    // the grid needs no deliberate pull or tap first. The selection is untouched: this is the list
    // getting out of the way, not the selection ending.
    LaunchedEffect(contentScrolling) {
        if (contentScrolling && reveal.value > 0f) reveal.animateTo(0f, tween(240))
    }

    // The one thumbnail blown up for a closer look, held by its stable key so only ever one is.
    // A preview is a look, not a pick: it leaves the selection untouched. A change to the selection
    // retires it, since the strip is rebuilt and that photo may have left it.
    var previewKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(items.size) { previewKey = null }
    val stripState = rememberLazyListState()
    LaunchedEffect(stripState.isScrollInProgress) {
        if (stripState.isScrollInProgress) previewKey = null
    }

    // The detent drag, shared by the grab area and the action list below it, so the drawer can
    // be pulled shut from anywhere it covers rather than from its handle alone. The thumbnail
    // strip stays out of it: a horizontal flick through the selection must not read as a drag,
    // and a vertical one there would fight the strip's own scrolling.
    val detentDrag = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = dragState,
        onDragStopped = { velocity ->
            val target = when {
                velocity < -SETTLE_VELOCITY -> expandedTravelPx
                velocity > SETTLE_VELOCITY -> 0f
                reveal.value > expandedTravelPx / 2f -> expandedTravelPx
                else -> 0f
            }
            reveal.animateTo(target, tween(240))
        },
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Bg2)
            .border(0.5.dp, PillBorder, shape)
            .navigationBarsPadding()
            .then(detentDrag),
    ) {

        // Grab area: handle plus the count row.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(detentDrag),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = toggleDetent)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(colors.fgMute.copy(alpha = 0.5f), CircleShape),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = SHEET_SIDE_PADDING, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectionCountLabel(items),
                    color = colors.fgPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Chevron toggle: the same two detents the drag settles on, reachable without a
                // drag. It points the way the drawer will move.
                IconBubble(
                    icon = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(
                        if (expanded) R.string.sel_hide_actions else R.string.sel_show_actions,
                    ),
                    onClick = toggleDetent,
                    diameter = 36.dp,
                    iconSize = 20.dp,
                )
                Spacer(Modifier.width(8.dp))
                IconBubble(
                    icon = Icons.Default.Close,
                    contentDescription = stringResource(R.string.gallery_cancel_selection),
                    onClick = onDismiss,
                    diameter = 36.dp,
                    iconSize = 16.dp,
                )
            }
        }

        // Clear air under the count row, so the photos read as their own band instead of crowding
        // the header above them.
        Spacer(Modifier.height(10.dp))
        LazyRow(
            state = stripState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = SHEET_SIDE_PADDING),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(count = items.size) { index ->
                val inputs = remember(items[index]) { photoCellInputsFor(items[index]) }
                SelectionThumb(
                    cacheThumbnails = cacheThumbnails,
                    inputs = inputs,
                    enlarged = previewKey == inputs.stableKey,
                    onClick = {
                        previewKey = if (previewKey == inputs.stableKey) null else inputs.stableKey
                    },
                )
            }
        }
        // A rule under the selected photos, drawn the way every other sheet draws one, so the first
        // action reads as the start of its own list rather than as something hanging off the last
        // thumbnail.
        HorizontalDivider(
            color = Line2,
            modifier = Modifier.padding(top = 10.dp, start = SHEET_SIDE_PADDING, end = SHEET_SIDE_PADDING),
        )

        // The expanded half. Its height is the drag position, so at rest it measures zero and the
        // rows are clipped away entirely rather than pushed up the screen. While a thumbnail is
        // enlarged the first touch here only settles that thumbnail back, so the row the preview
        // pushed down cannot fire by accident.
        val previewOpen = previewKey != null
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { reveal.value.toDp() })
                .clipToBounds()
                .nestedScroll(listHandoff)
                .then(detentDrag)
                .pointerInput(previewOpen) {
                    if (!previewOpen) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                event.changes.forEach { it.consume() }
                                previewKey = null
                            }
                        }
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(listScroll, enabled = listOverflows)
                    .padding(
                        start = SHEET_SIDE_PADDING, end = SHEET_SIDE_PADDING,
                        top = ACTION_LIST_INSET + STRIP_TO_ACTIONS_GAP, bottom = ACTION_LIST_INSET,
                    ),
                // Pills separated by clear space, exactly as the app's other action sheets space
                // them. No rule between two of them.
                verticalArrangement = Arrangement.spacedBy(ACTION_ROW_GAP),
            ) {
                actions.forEach { action ->
                    // Pinned to the row height the expanded travel was computed from, so the open
                    // detent still lands on the end of the list.
                    ActionSheetRow(
                        icon = action.icon,
                        title = if (action.working && action.workingLabel != null) {
                            action.workingLabel
                        } else {
                            action.label
                        },
                        onClick = action.onClick,
                        modifier = Modifier.height(ACTION_ROW_HEIGHT),
                        enabled = action.enabled,
                        tint = action.tint,
                        busy = action.working,
                        busyFraction = action.progress,
                        busyIcon = action.workingIcon,
                        clickableWhileBusy = action.clickableWhileWorking,
                        titleMaxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** The media-typed count the resting bar carries: "5 photos, 2 videos" rather than an
 *  undifferentiated total, since the storage and network cost of a video differs a lot. */
@Composable
private fun selectionCountLabel(items: List<GalleryItem>): String {
    val counts = selectionMimeCounts(items)
    val photosText = pluralStringResource(R.plurals.count_photos_plural, counts.photos, counts.photos)
    val videosText = pluralStringResource(R.plurals.count_videos_plural, counts.videos, counts.videos)
    return when {
        counts.photos > 0 && counts.videos > 0 -> "$photosText, $videosText"
        counts.videos > 0 -> videosText
        else -> photosText
    }
}

/**
 * One thumbnail in the strip. Resolves its image the way a grid cell does (the item's own data
 * first, then the shared decrypted-thumbnail store for a cloud row) and binds under the same
 * memory-cache key, so a photo already drawn in the grid costs nothing to draw again here.
 *
 * [enlarged] animates the tile between its resting edge and the preview edge. The tile is the
 * tallest thing in the strip while it grows, so the strip owns the extra height and everything
 * below it moves down. The tap only reports itself through [onClick] and never selects.
 */
@Composable
private fun SelectionThumb(
    inputs: PhotoCellInputs,
    enlarged: Boolean,
    cacheThumbnails: Boolean,
    onClick: () -> Unit,
) {
    val imageData = inputs.imageData ?: LocalThumbnailUrls.current.value[inputs.stableKey]
    val edge by animateDpAsState(
        targetValue = if (enlarged) THUMB_PREVIEW_SIZE else THUMB_SIZE,
        animationSpec = tween(THUMB_PREVIEW_MS),
        label = "selection_thumb_edge",
    )
    Box(
        modifier = Modifier
            .size(edge)
            .clip(RoundedCornerShape(8.dp))
            .background(Bg2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (imageData == null) {
            Icon(
                Icons.Default.Photo,
                contentDescription = null,
                tint = FgDim.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp),
            )
        } else {
            val context = LocalContext.current
            val request = remember(imageData, inputs.stableKey) {
                ImageRequest.Builder(context)
                    .data(imageData)
                    .size(SELECTION_THUMB_PX)
                    .crossfade(false)
                    .apply {
                        // A vault photo is decoded fresh every time and kept out of both caches, so
                        // no other surface and no cache dump can rebuild it from its uri. The strip
                        // shows the same photos, so it has to hold the same line.
                        if (cacheThumbnails) {
                            memoryCacheKey(inputs.stableKey)
                        } else {
                            memoryCachePolicy(CachePolicy.DISABLED)
                            diskCachePolicy(CachePolicy.DISABLED)
                        }
                    }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (inputs.isVideo) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}
