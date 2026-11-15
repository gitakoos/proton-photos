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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.domain.usecase.PhotoArrangement
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** An arrange session: the order on screen, the photos selected in it, and whether anything moved since it began. */
data class ArrangeSession(
    val order: List<String>,
    val selected: Set<String> = emptySet(),
    val moved: Boolean = false,
) {
    fun toggled(key: String): ArrangeSession =
        copy(selected = if (key in selected) selected - key else selected + key)

    fun reordered(next: List<String>): ArrangeSession =
        if (next == order) this else copy(order = next, moved = true)

    fun movedToStart(): ArrangeSession = reordered(PhotoArrangement.moveToStart(order, selected)).copy(selected = emptySet())

    fun movedToEnd(): ArrangeSession = reordered(PhotoArrangement.moveToEnd(order, selected)).copy(selected = emptySet())

    fun movedNextTo(target: String, after: Boolean): ArrangeSession {
        val next = if (after) PhotoArrangement.moveAfter(order, selected, target) else PhotoArrangement.moveBefore(order, selected, target)
        return reordered(next).copy(selected = emptySet())
    }
}

/**
 * The arrange mode of an album or a device folder: the photos in one run with no date headers, a held photo (or the
 * selected block, when the held photo is one of them) follows the finger and the others make room, the grid scrolls
 * by itself near the top and bottom edge, a tap selects, and the bar at the bottom moves the selection to the start,
 * to the end, or next to a photo picked with Move here. Done keeps the order and Cancel (or Back) throws it away,
 * asking first once something moved.
 *
 * [cell] draws one photo by its key, square, and calls its tap back; the photos riding on the finger stay in place
 * dimmed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoArrangeScreen(
    session: ArrangeSession,
    columns: Int,
    seamless: Boolean,
    cell: @Composable (key: String, selected: Boolean, onTap: () -> Unit) -> Unit,
    onToggle: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onMoveToStart: () -> Unit,
    onMoveToEnd: () -> Unit,
    onMoveNextTo: (target: String, after: Boolean) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = AppColors.current
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edgePx = remember(density) { with(density) { 96.dp.toPx() } }
    val latestSession by rememberUpdatedState(session)

    // The order while a drag runs, handed back once the finger lifts, so a long drag is one step for the session.
    var working by remember { mutableStateOf<List<String>?>(null) }
    val shown = working ?: session.order
    var moving by remember { mutableStateOf<Set<String>>(emptySet()) }
    var anchor by remember { mutableStateOf<String?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    var liftSize by remember { mutableStateOf(IntSize.Zero) }
    var lastTarget by remember { mutableStateOf<String?>(null) }
    var edgeVelocity by remember { mutableFloatStateOf(0f) }
    val tapGuard = remember { mutableStateOf(false) }

    // Move here: the first tap after it picks the photo the selection goes next to.
    var pickingTarget by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // A move or a deselect that empties the selection ends a Move here in progress.
    LaunchedEffect(session.selected.isEmpty()) {
        if (session.selected.isEmpty()) {
            pickingTarget = false
            target = null
        }
    }

    fun requestCancel() {
        if (session.moved) confirmDiscard = true else onCancel()
    }
    BackHandler {
        when {
            pickingTarget || target != null -> {
                pickingTarget = false
                target = null
            }
            else -> requestCancel()
        }
    }

    fun keyAt(position: Offset): String? {
        val start = gridState.layoutInfo.viewportStartOffset
        return gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val top = item.offset.y - start
            position.y >= top && position.y < top + item.size.height &&
                position.x >= item.offset.x && position.x < item.offset.x + item.size.width
        }?.key as? String
    }

    fun retarget() {
        val order = working ?: return
        val over = keyAt(finger) ?: return
        if (over == lastTarget || over in moving) return
        lastTarget = over
        val next = PhotoArrangement.dragTo(order, moving, over)
        if (next != order) {
            working = next
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun endDrag() {
        val done = working
        working = null
        moving = emptySet()
        anchor = null
        lastTarget = null
        edgeVelocity = 0f
        tapGuard.value = false
        if (done != null && done != latestSession.order) onReorder(done)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { edgeVelocity }.collect { velocity ->
            if (velocity == 0f) return@collect
            while (isActive && edgeVelocity != 0f) {
                gridState.scrollBy(edgeVelocity)
                retarget()
                withFrameNanos {}
            }
        }
    }

    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { position ->
                val key = keyAt(position) ?: return@detectDragGesturesAfterLongPress
                val current = latestSession
                tapGuard.value = true
                pickingTarget = false
                target = null
                val block = if (key in current.selected) current.selected else setOf(key)
                moving = block
                anchor = key
                working = PhotoArrangement.gather(current.order, block, key)
                val start = gridState.layoutInfo.viewportStartOffset
                gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { item ->
                    liftSize = item.size
                    grabOffset = Offset(position.x - item.offset.x, position.y - (item.offset.y - start))
                }
                finger = position
                lastTarget = key
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDrag = { change, delta ->
                if (anchor == null) return@detectDragGesturesAfterLongPress
                change.consume()
                finger += delta
                retarget()
                val height = gridState.layoutInfo.viewportSize.height.toFloat()
                edgeVelocity = when {
                    finger.y < edgePx -> -((edgePx - finger.y) / edgePx).coerceAtMost(1f) * 28f
                    finger.y > height - edgePx -> ((finger.y - (height - edgePx)) / edgePx).coerceAtMost(1f) * 28f
                    else -> 0f
                }
            },
            onDragEnd = { endDrag() },
            onDragCancel = { endDrag() },
        )
    }

    Box(Modifier.fillMaxSize().background(colors.bg0)) {
        val topBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
        Box(Modifier.fillMaxSize().padding(top = topBar)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(
                    start = if (seamless) 0.dp else 20.dp,
                    end = if (seamless) 0.dp else 20.dp,
                    top = 8.dp,
                    bottom = 140.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
                verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
                modifier = Modifier.fillMaxSize().then(dragModifier),
                // The grid's own scroll would take a vertical drag and cancel the move; the edge scroll drives it instead.
                userScrollEnabled = anchor == null,
            ) {
                items(shown, key = { it }) { key ->
                    val isTarget = key == target
                    Box(
                        Modifier
                            .animateItem()
                            .alpha(if (key in moving) 0.35f else 1f)
                            .then(
                                if (isTarget) Modifier.border(2.dp, Accent, RoundedCornerShape(if (seamless) 0.dp else 6.dp))
                                else Modifier,
                            ),
                    ) {
                        cell(key, key in session.selected) {
                            when {
                                tapGuard.value -> tapGuard.value = false
                                pickingTarget || target != null -> {
                                    if (key !in session.selected) {
                                        target = key
                                        pickingTarget = false
                                    }
                                }
                                else -> onToggle(key)
                            }
                        }
                    }
                }
            }
            ScrollScrubber(gridState = gridState, topPadding = 12.dp, bottomPadding = 140.dp, minItemsToShow = 60)

            // The photo, or the block, riding on the finger.
            val lifted = anchor
            if (lifted != null && liftSize != IntSize.Zero) {
                val widthDp = with(density) { liftSize.width.toDp() }
                Box(
                    Modifier
                        .offset { IntOffset((finger.x - grabOffset.x).roundToInt(), (finger.y - grabOffset.y).roundToInt()) }
                        .size(widthDp * 1.08f)
                        .shadow(12.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    cell(lifted, false) {}
                    if (moving.size > 1) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .clip(CircleShape)
                                .background(Accent)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(moving.size.toString(), color = colors.bg0, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        ArrangeTopBar(
            selectedCount = session.selected.size,
            onCancel = ::requestCancel,
            onDone = onDone,
            modifier = Modifier.align(Alignment.TopStart),
        )

        ArrangeBottomBar(
            hasSelection = session.selected.isNotEmpty(),
            pickingTarget = pickingTarget,
            target = target,
            onMoveToStart = {
                onMoveToStart()
                scope.launch { gridState.scrollToItem(0) }
            },
            onMoveToEnd = {
                onMoveToEnd()
                scope.launch { gridState.scrollToItem((session.order.size - 1).coerceAtLeast(0)) }
            },
            onMoveHere = { pickingTarget = true },
            onPlace = { after ->
                target?.let { onMoveNextTo(it, after) }
                target = null
                pickingTarget = false
            },
            onCancelPick = {
                target = null
                pickingTarget = false
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.arrange_discard_title),
            message = stringResource(R.string.arrange_discard_message),
            confirmLabel = stringResource(R.string.arrange_discard_confirm),
            dismissLabel = stringResource(R.string.arrange_discard_keep),
            onConfirm = {
                confirmDiscard = false
                onCancel()
            },
            onDismiss = { confirmDiscard = false },
            destructive = true,
        )
    }
}

/** Close bubble (Cancel), the mode pill with the selection count, and the accent check bubble (Done). */
@Composable
private fun ArrangeTopBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBubble(
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.cancel),
            onClick = onCancel,
            iconSize = 20.dp,
            background = PillBg,
            borderColor = PillBorder,
            tint = FgPrimary,
        )
        Box(Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(PillBgOpaque)
                    .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.DragHandle, null, tint = Accent, modifier = Modifier.size(18.dp))
                Text(
                    if (selectedCount > 0) stringResource(R.string.arrange_selected_count, selectedCount)
                    else stringResource(R.string.album_arrange_photos),
                    color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconBubble(
            icon = Icons.Default.Check,
            contentDescription = stringResource(R.string.arrange_done),
            onClick = onDone,
            iconSize = 20.dp,
            background = Accent,
            borderColor = null,
            tint = AppColors.current.bg0,
        )
    }
}

/** The hint, the moves for the selection, or the Before / After choice once Move here picked a photo. */
@Composable
private fun ArrangeBottomBar(
    hasSelection: Boolean,
    pickingTarget: Boolean,
    target: String?,
    onMoveToStart: () -> Unit,
    onMoveToEnd: () -> Unit,
    onMoveHere: () -> Unit,
    onPlace: (after: Boolean) -> Unit,
    onCancelPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(PillBgOpaque)
            .border(0.5.dp, PillBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            target != null -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ArrangeAction(Icons.Default.FirstPage, stringResource(R.string.arrange_before), Modifier.weight(1f)) { onPlace(false) }
                ArrangeAction(Icons.Default.LastPage, stringResource(R.string.arrange_after), Modifier.weight(1f)) { onPlace(true) }
                IconBubble(
                    icon = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                    onClick = onCancelPick,
                    diameter = 36.dp,
                    background = PillBg,
                    borderColor = PillBorder,
                    tint = FgPrimary,
                )
            }
            pickingTarget -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.arrange_pick_target),
                    color = FgPrimary, fontSize = 14.sp,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                IconBubble(
                    icon = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                    onClick = onCancelPick,
                    diameter = 36.dp,
                    background = PillBg,
                    borderColor = PillBorder,
                    tint = FgPrimary,
                )
            }
            hasSelection -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ArrangeAction(Icons.Default.FirstPage, stringResource(R.string.arrange_move_to_start), Modifier.weight(1f), onMoveToStart)
                ArrangeAction(Icons.Default.PinDrop, stringResource(R.string.arrange_move_here), Modifier.weight(1f), onMoveHere)
                ArrangeAction(Icons.Default.LastPage, stringResource(R.string.arrange_move_to_end), Modifier.weight(1f), onMoveToEnd)
            }
            else -> Text(
                stringResource(R.string.arrange_hint),
                color = FgMute, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ArrangeAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(22.dp))
        Text(
            label,
            color = FgPrimary, fontSize = 12.sp, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
