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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PillBg
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
 *
 * [slot] marks one of the actions a selection reaches for most. A slotted action also gets a fixed
 * button under the selected photos, one tap away from the resting drawer, and leads the open list
 * in slot order.
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
    val slot: PrimarySlot? = null,
)

/**
 * The fixed buttons of a resting selection drawer, in the order they stand. [Organize] is whichever
 * action files the selection somewhere on that surface: Add to album, Move to folder without an
 * account, or Move to album inside an album.
 */
enum class PrimarySlot { Share, Organize, Delete }

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
    var selectAllCount: Int? = null
    var onSelectAll: (() -> Unit)? = null
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

/** The resting bar from the top: the handle band, the count row's round controls and the gap under
 *  them, the air above the strip, and the rule under the strip with its own gap, then the air above
 *  the bottom edge. The resting height is summed from these, so a surface can reserve it before the
 *  drawer lays out. */
private val HANDLE_PADDING = 10.dp
private val HANDLE_THICKNESS = 4.dp
private val HEADER_CONTROL_SIZE = 36.dp
private val HEADER_BOTTOM_GAP = 8.dp
private val HEADER_ITEM_GAP = 8.dp
private val STRIP_TOP_GAP = 10.dp
private val DIVIDER_TOP_GAP = 10.dp
private val DIVIDER_THICKNESS = 1.dp
private val DRAWER_BOTTOM_GAP = 12.dp

/** The count and the Select all label. Their line heights are pinned, so the count row's height is
 *  known before layout at any text size. */
private val COUNT_FONT_SIZE = 16.sp
private val COUNT_LINE_HEIGHT = 24.sp
private val SELECT_ALL_FONT_SIZE = 13.sp
private val SELECT_ALL_LINE_HEIGHT = 16.sp
private val SELECT_ALL_PADDING_H = 12.dp
private val SELECT_ALL_PADDING_V = 6.dp

/** One primary button: the action's glyph alone, named by its label for accessibility, in a pill of
 *  the action rows' own fill and border. The labelled rows are one pull away in the open list, so the
 *  resting bar stays low. The buttons of a row share one width, capped so a tablet keeps them
 *  compact. */
private val PRIMARY_ICON_SIZE = 22.dp
private val PRIMARY_BUTTON_PADDING_H = 3.dp
private val PRIMARY_BUTTON_PADDING_V = 9.dp
private val PRIMARY_BUTTON_GAP = 6.dp
private val PRIMARY_BUTTON_MAX_WIDTH = 120.dp
private val PRIMARY_BUTTON_CORNER = 12.dp
private val PRIMARY_GLYPH_SIZE = 18.dp

/** Air between the rule under the strip and the primary row. */
private val PRIMARY_ROW_TOP_GAP = 8.dp

/** The shortest travel the detent drag keeps, for an open list barely taller than the row it
 *  replaces. */
private val MIN_DETENT_TRAVEL = 24.dp

/** A window shorter than this, such as a phone on its side, takes the compact drawer: the primary
 *  actions sit in the count row as glyphs and the resting bar grows by nothing. */
private const val COMPACT_HEIGHT_DP = 480

/** Past this many photos, Select all asks before it selects. */
internal const val SELECT_ALL_CONFIRM_THRESHOLD = 500

/** The slotted actions in slot order. The sort is stable, so two actions sharing a slot keep the
 *  order the surface gave them. */
internal fun primaryActions(actions: List<SelectionAction>): List<SelectionAction> =
    actions.filter { it.slot != null }.sortedBy { it.slot?.ordinal }

/** The open list: the primary actions first, in the order the resting row shows them, then every
 *  other action in the order given. */
internal fun expandedActionOrder(actions: List<SelectionAction>): List<SelectionAction> =
    primaryActions(actions) + actions.filter { it.slot == null }

/** More ends the primary row only while the open list has something the row lacks. The compact
 *  drawer has none: its chevron sits right beside the glyphs and opens the same list. */
internal fun showsMoreButton(actions: List<SelectionAction>, compact: Boolean): Boolean =
    !compact && actions.any { it.slot != null } && actions.any { it.slot == null }

/** Whether Select all asks first: only when it would select more than [SELECT_ALL_CONFIRM_THRESHOLD]
 *  photos. An unknown count selects at once. */
internal fun selectAllNeedsConfirm(selectAllCount: Int?): Boolean =
    selectAllCount != null && selectAllCount > SELECT_ALL_CONFIRM_THRESHOLD

/** Whether a window [screenHeightDp] tall takes the compact drawer. */
internal fun isCompactDrawer(screenHeightDp: Int): Boolean = screenHeightDp < COMPACT_HEIGHT_DP

/**
 * Whether a drawer control acts on a tap. A drawer on its way out ([opened] false) still draws the
 * selection that has just ended, and a tap there would act on nothing or leave a sheet raised for
 * it, so none of its controls act. The fixed buttons also stop once they have faded away under the
 * open list ([shownAlpha] 0).
 */
internal fun drawerTakesTaps(opened: Boolean, shownAlpha: Float = 1f): Boolean =
    opened && shownAlpha > 0f

/** Whether the Select all label fits beside the count at full length, all in pixels. When it does
 *  not, the control shows a glyph instead, so the count is never cut short for it. */
internal fun selectAllFitsAsText(available: Int, countWidth: Int, labelWidth: Int, gap: Int): Boolean =
    countWidth + gap + labelWidth <= available

/** One primary button: its padding around its glyph. It carries no text, so it keeps this height at
 *  any text size. */
internal fun primaryButtonHeight(): Dp = PRIMARY_BUTTON_PADDING_V * 2 + PRIMARY_ICON_SIZE

/** The band the primary row adds to the resting bar: the air under the rule and one button. */
internal fun primaryRowHeight(): Dp = PRIMARY_ROW_TOP_GAP + primaryButtonHeight()

/** The count row: the tallest of its round controls, its count and its Select all label, and the gap
 *  under it. */
internal fun headerRowHeight(countLine: Dp, selectAllLine: Dp): Dp =
    maxOf(HEADER_CONTROL_SIZE, countLine, selectAllLine + SELECT_ALL_PADDING_V * 2) + HEADER_BOTTOM_GAP

/**
 * The resting drawer above the navigation-bar inset, from the text line heights as the current text
 * size resolves them. [primaryRow] adds the button band; the [compact] drawer carries its buttons in
 * the count row and adds nothing.
 */
internal fun restingDrawerHeight(
    countLine: Dp,
    selectAllLine: Dp,
    primaryRow: Boolean,
    compact: Boolean,
): Dp {
    val primaryBand = if (primaryRow && !compact) primaryRowHeight() else 0.dp
    return HANDLE_PADDING * 2 + HANDLE_THICKNESS + headerRowHeight(countLine, selectAllLine) +
        STRIP_TOP_GAP + THUMB_SIZE + DIVIDER_TOP_GAP + DIVIDER_THICKNESS + primaryBand +
        DRAWER_BOTTOM_GAP
}

/** How far the drawer's top edge travels between its detents: the open list's height less the
 *  primary row that folds away on the way, never below [minTravel]. */
internal fun detentTravel(listHeight: Float, primaryRowHeight: Float, minTravel: Float): Float =
    (listHeight - primaryRowHeight).coerceAtLeast(minTravel)

/** How open the drawer is, 0 at rest and 1 fully open, for [reveal] of [travel]. */
internal fun openFraction(reveal: Float, travel: Float): Float =
    if (travel <= 0f) 0f else (reveal / travel).coerceIn(0f, 1f)

/**
 * The height the drawer covers at rest, the navigation-bar inset included, so the grid behind it can
 * pad its end by as much and still scroll its last row clear. It follows the text size and the
 * compact variant the way the drawer does. [withPrimaryRow] is false for a surface that slots no
 * action and so has no button row.
 */
@Composable
fun selectionDrawerRestingHeight(withPrimaryRow: Boolean = true): Dp {
    val compact = isCompactDrawer(LocalConfiguration.current.screenHeightDp)
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val resting = with(LocalDensity.current) {
        restingDrawerHeight(
            countLine = COUNT_LINE_HEIGHT.toDp(),
            selectAllLine = SELECT_ALL_LINE_HEIGHT.toDp(),
            primaryRow = withPrimaryRow,
            compact = compact,
        )
    }
    return resting + navInset
}

/**
 * The bottom drawer every multi-select surface shares, with two detents the user drags between.
 *
 * Rest is the state it opens in: a low bar carrying the selected photos as a horizontally
 * scrollable strip of thumbnails, the media-typed count, a chevron and a drag handle, and under the
 * strip a fixed row of buttons for the actions a surface slots through [SelectionAction.slot],
 * ended by More while other actions remain. Nothing behind it is covered by a scrim and no action
 * list is thrown up the screen, so selecting carries on underneath. Dragging the grab area up,
 * tapping the handle, the chevron or More reveals every action as a vertical list of labelled rows,
 * the slotted ones first, while the button row folds away; dragging down returns to rest and stops
 * there. Leaving selection mode is the close bubble's job alone. A short window carries the slotted
 * actions as glyphs in the count row instead, so the resting bar does not grow there.
 *
 * Tapping a thumbnail blows it up in place for a closer look and puts the rows below it further
 * down; scrolling the strip or touching an action settles it back. The preview never changes
 * the selection.
 *
 * [visible] drives the slide in and out. [items] is the live selection, [actions] the action set
 * built for it, and [onDismiss] leaves selection mode from the drawer's own close control. With
 * [onSelectAll] set, the count row offers Select all, which asks first when [selectAllCount], the
 * number it would select, passes [SELECT_ALL_CONFIRM_THRESHOLD]. The caller positions the drawer
 * through [modifier], normally bottom-aligned inside a Box, and can pad what scrolls behind it by
 * [selectionDrawerRestingHeight].
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
    selectAllCount: Int? = null,
    onSelectAll: (() -> Unit)? = null,
) {
    // Selection mode is derived from the selection itself, so clearing it empties [items] and
    // [actions] while the drawer is still animating out. The drawer renders this snapshot whenever
    // the incoming selection is empty, which keeps its thumbnails, its buttons, its Select all and
    // its action list exactly as they were for the whole exit.
    val frozen = remember { FrozenSelection() }
    val live = items.isNotEmpty()
    if (live) {
        frozen.items = items
        frozen.actions = actions
        frozen.selectAllCount = selectAllCount
        frozen.onSelectAll = onSelectAll
    }
    val shownItems = if (live) items else frozen.items
    val shownActions = if (live) actions else frozen.actions

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
            selectAllCount = if (live) selectAllCount else frozen.selectAllCount,
            onSelectAll = if (live) onSelectAll else frozen.onSelectAll,
        )
    }
}

/**
 * The drawer surface itself, always rendered from an already-resolved (and possibly frozen)
 * selection. [opened] re-seats the drawer at rest each time it comes back and keeps a closing
 * drawer from acting: its Select all, buttons and rows take no tap ([drawerTakesTaps]). It changes
 * nothing that is drawn, so a closing drawer holds its geometry as well as its content.
 */
@Composable
private fun SelectionDrawerBody(
    items: List<GalleryItem>,
    actions: List<SelectionAction>,
    opened: Boolean,
    onDismiss: () -> Unit,
    contentScrolling: Boolean,
    cacheThumbnails: Boolean,
    selectAllCount: Int?,
    onSelectAll: (() -> Unit)?,
) {
    val colors = AppColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER)
    val screenHeightDp = LocalConfiguration.current.screenHeightDp

    // The slotted actions lead twice, in the same order: as the fixed buttons under the strip at
    // rest and as the first rows of the open list. A short window carries them as glyphs in the
    // count row instead.
    val compact = isCompactDrawer(screenHeightDp)
    val primary = primaryActions(actions)
    val listed = expandedActionOrder(actions)
    val showMore = showsMoreButton(actions, compact)
    val buttonHeight = primaryButtonHeight()
    val rowBand = if (primary.isNotEmpty() && !compact) primaryRowHeight() else 0.dp

    // Full open list: every action pill, the gaps between them and the padding around the list.
    // Capped so the drawer never owns most of the screen.
    val naturalExpanded = ACTION_LIST_INSET * 2 + STRIP_TO_ACTIONS_GAP +
        ACTION_ROW_HEIGHT * listed.size +
        ACTION_ROW_GAP * (listed.size - 1).coerceAtLeast(0)
    val maxExpanded = (screenHeightDp * MAX_EXPANDED_FRACTION).dp
    // The action list scrolls only when it genuinely overflows. While it fits, a vertical drag over
    // the rows belongs to the drawer: a scroll that is always on consumes that drag first, which
    // leaves the handle as the only way to pull the drawer shut.
    val listOverflows = naturalExpanded > maxExpanded
    val listScroll = rememberScrollState()
    val listHeightPx = with(density) { minOf(naturalExpanded, maxExpanded).toPx() }
    // The top edge travels the list's height less the button row folding away under it, so the
    // drawer tracks the finger one to one and the open detent still lands on the end of the list.
    val travelPx = detentTravel(
        listHeight = listHeightPx,
        primaryRowHeight = with(density) { rowBand.toPx() },
        minTravel = with(density) { MIN_DETENT_TRAVEL.toPx() },
    )

    // Rest is zero travel: the action list measures nothing there, so the resting bar is the
    // handle, the count row, the thumbnail strip and the button row.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(opened) { if (opened) reveal.snapTo(0f) }
    LaunchedEffect(travelPx) {
        if (reveal.value > travelPx) reveal.snapTo(travelPx)
    }
    val fraction = openFraction(reveal.value, travelPx)
    val expanded = reveal.value > travelPx / 2f
    // The buttons fade over the first half of the opening, as the list's rows for the same actions
    // arrive, and take no tap once they are gone, nor while the drawer is closing.
    val primaryAlpha = (1f - fraction * 2f).coerceIn(0f, 1f)
    val primaryLive = drawerTakesTaps(opened, primaryAlpha)
    val rowsLive = drawerTakesTaps(opened)
    val dragState = rememberDraggableState { delta ->
        scope.launch { reveal.snapTo((reveal.value - delta).coerceIn(0f, travelPx)) }
    }
    // A scrolling list wins a vertical drag before the drawer sees it, so a list long enough to
    // scroll would leave the handle as the only way to pull the drawer shut. This hands back what
    // the list could not use: at its top there is nothing above to reach, so a further pull down
    // belongs to the drawer.
    val listHandoff = remember(travelPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0f || reveal.value <= 0f) return Offset.Zero
                val settled = (reveal.value - available.y).coerceIn(0f, travelPx)
                val used = reveal.value - settled
                scope.launch { reveal.snapTo(settled) }
                return Offset(0f, used)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (reveal.value <= 0f || reveal.value >= travelPx) return Velocity.Zero
                // Same reason as the drag settle: run it on the drawer's own scope so the fling
                // handoff finishes settling even after the scroll gesture that raised it has ended.
                scope.launch {
                    reveal.animateTo(
                        if (reveal.value > travelPx / 2f) travelPx else 0f,
                        tween(240),
                    )
                }
                return Velocity.Zero
            }
        }
    }
    // Both detents are travel positions, so no drag, fling, chevron or More tap can end the
    // selection. The close bubble is the one control that dismisses.
    val toggleDetent: () -> Unit = {
        scope.launch { reveal.animateTo(if (expanded) 0f else travelPx, tween(240)) }
    }
    val openFully: () -> Unit = {
        scope.launch { reveal.animateTo(travelPx, tween(240)) }
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
    val previewOpen = previewKey != null
    val settlePreview: () -> Unit = { previewKey = null }

    // Select all acts at once up to the threshold and asks first past it. A drawer on its way out
    // takes no new selection.
    var confirmSelectAllCount by remember { mutableStateOf<Int?>(null) }
    val requestSelectAll: (() -> Unit)? = onSelectAll?.let { selectAll ->
        {
            if (opened) {
                if (selectAllNeedsConfirm(selectAllCount)) {
                    confirmSelectAllCount = selectAllCount
                } else {
                    selectAll()
                }
            }
        }
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
                velocity < -SETTLE_VELOCITY -> travelPx
                velocity > SETTLE_VELOCITY -> 0f
                reveal.value > travelPx / 2f -> travelPx
                else -> 0f
            }
            // Settle on the drawer's own scope rather than the drag gesture's: the gesture scope does
            // not outlive the release, so an animateTo left in it is cancelled mid-settle and the
            // drawer stops wherever the finger let go instead of snapping to a detent.
            scope.launch { reveal.animateTo(target, tween(240)) }
        },
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A shadow lifts the sheet off the grid instead of a hairline border, matching the borderless
            // elevated sheets the rest of the app uses (the modal bottom sheets and the import bars).
            .shadow(6.dp, shape)
            .clip(shape)
            .background(SheetBg)
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
                    .padding(vertical = HANDLE_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(HANDLE_THICKNESS)
                        .background(colors.fgMute.copy(alpha = 0.5f), CircleShape),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = SHEET_SIDE_PADDING, end = 12.dp, bottom = HEADER_BOTTOM_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CountWithSelectAll(
                    countLabel = selectionCountLabel(items),
                    onSelectAll = requestSelectAll,
                    modifier = Modifier.weight(1f),
                )
                if (compact && primary.isNotEmpty()) {
                    Spacer(Modifier.width(HEADER_ITEM_GAP))
                    Row(
                        modifier = Modifier
                            .alpha(primaryAlpha)
                            .settlesPreviewFirst(previewOpen, settlePreview),
                        horizontalArrangement = Arrangement.spacedBy(HEADER_ITEM_GAP),
                    ) {
                        primary.forEach { action ->
                            PrimaryGlyphButton(action = action, live = primaryLive)
                        }
                    }
                }
                Spacer(Modifier.width(HEADER_ITEM_GAP))
                // Chevron toggle: the same two detents the drag settles on, reachable without a
                // drag. It points the way the drawer will move.
                IconBubble(
                    icon = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(
                        if (expanded) R.string.sel_hide_actions else R.string.sel_show_actions,
                    ),
                    onClick = toggleDetent,
                    diameter = HEADER_CONTROL_SIZE,
                    iconSize = 20.dp,
                )
                Spacer(Modifier.width(HEADER_ITEM_GAP))
                IconBubble(
                    icon = Icons.Default.Close,
                    contentDescription = stringResource(R.string.gallery_cancel_selection),
                    onClick = onDismiss,
                    diameter = HEADER_CONTROL_SIZE,
                    iconSize = 16.dp,
                )
            }
        }

        // Clear air under the count row, so the photos read as their own band instead of crowding
        // the header above them.
        Spacer(Modifier.height(STRIP_TOP_GAP))
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
        // A rule under the selected photos, drawn the way every other sheet draws one, so the
        // actions below read as their own band rather than as something hanging off the last
        // thumbnail.
        HorizontalDivider(
            color = Line2,
            thickness = DIVIDER_THICKNESS,
            modifier = Modifier.padding(
                top = DIVIDER_TOP_GAP, start = SHEET_SIDE_PADDING, end = SHEET_SIDE_PADDING,
            ),
        )

        // The fixed buttons. They fold away as the drawer opens, measured at full height and
        // clipped from below, so the open list's own rows for the same actions take their place
        // and no action shows twice. While a thumbnail is enlarged the first touch here only
        // settles that thumbnail back.
        if (rowBand > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowBand * (1f - fraction))
                    .then(if (fraction > 0f) Modifier.clipToBounds() else Modifier)
                    .alpha(primaryAlpha)
                    .settlesPreviewFirst(previewOpen, settlePreview),
            ) {
                PrimaryActionRow(
                    actions = primary,
                    onMore = if (showMore) openFully else null,
                    buttonHeight = buttonHeight,
                    live = primaryLive,
                    modifier = Modifier
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .padding(
                            top = PRIMARY_ROW_TOP_GAP, start = SHEET_SIDE_PADDING, end = SHEET_SIDE_PADDING,
                        ),
                )
            }
        }

        // The expanded half. Its height follows the drag, so at rest it measures zero and the
        // rows are clipped away entirely rather than pushed up the screen. While a thumbnail is
        // enlarged the first touch here only settles that thumbnail back, so a tap meant to put
        // the preview away cannot fire a row instead.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { (listHeightPx * fraction).toDp() })
                .clipToBounds()
                .nestedScroll(listHandoff)
                .then(detentDrag)
                .settlesPreviewFirst(previewOpen, settlePreview),
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
                listed.forEach { action ->
                    // Pinned to the row height the expanded travel was computed from, so the open
                    // detent still lands on the end of the list. A closing drawer's rows keep their
                    // look but not their action.
                    ActionSheetRow(
                        icon = action.icon,
                        title = action.shownLabel(),
                        onClick = if (rowsLive) action.onClick else ({}),
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
        Spacer(Modifier.height(DRAWER_BOTTOM_GAP))
    }

    confirmSelectAllCount?.let { count ->
        ConfirmDialog(
            title = pluralStringResource(R.plurals.select_all_confirm_title, count, count),
            message = stringResource(R.string.select_all_confirm_body),
            confirmLabel = stringResource(R.string.select_all),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                confirmSelectAllCount = null
                onSelectAll?.invoke()
            },
            onDismiss = { confirmSelectAllCount = null },
        )
    }
}

/**
 * The media-typed count and, when [onSelectAll] is set, a Select all control right after it. The
 * control keeps its label while both fit on the line and shows a glyph when they do not, so the
 * count is never cut short for it.
 */
@Composable
private fun CountWithSelectAll(
    countLabel: String,
    onSelectAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val selectAllLabel = stringResource(R.string.select_all)
    val baseStyle = LocalTextStyle.current
    val countStyle = baseStyle.merge(
        TextStyle(fontSize = COUNT_FONT_SIZE, lineHeight = COUNT_LINE_HEIGHT, fontWeight = FontWeight.SemiBold),
    )
    val selectAllStyle = baseStyle.merge(
        TextStyle(fontSize = SELECT_ALL_FONT_SIZE, lineHeight = SELECT_ALL_LINE_HEIGHT, fontWeight = FontWeight.Medium),
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val available = constraints.maxWidth
        val labelFits = if (onSelectAll == null) {
            false
        } else {
            remember(countLabel, selectAllLabel, countStyle, selectAllStyle, available, density) {
                selectAllFitsAsText(
                    available = available,
                    countWidth = measurer.measure(countLabel, countStyle, maxLines = 1).size.width,
                    labelWidth = measurer.measure(selectAllLabel, selectAllStyle, maxLines = 1).size.width +
                        with(density) { (SELECT_ALL_PADDING_H * 2).roundToPx() },
                    gap = with(density) { HEADER_ITEM_GAP.roundToPx() },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                countLabel,
                color = FgPrimary,
                style = countStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onSelectAll != null) {
                Spacer(Modifier.width(HEADER_ITEM_GAP))
                if (labelFits) {
                    SelectAllPill(label = selectAllLabel, style = selectAllStyle, onClick = onSelectAll)
                } else {
                    IconBubble(
                        icon = Icons.Default.SelectAll,
                        contentDescription = selectAllLabel,
                        onClick = onSelectAll,
                        diameter = HEADER_CONTROL_SIZE,
                        iconSize = 18.dp,
                    )
                }
            }
        }
    }
}

/** Select all as a labelled pill, as tall as the round controls beside it. */
@Composable
private fun SelectAllPill(label: String, style: TextStyle, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = HEADER_CONTROL_SIZE)
            .background(PillBg, CircleShape)
            .border(0.5.dp, PillBorder, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = SELECT_ALL_PADDING_H, vertical = SELECT_ALL_PADDING_V),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = FgPrimary, style = style, maxLines = 1)
    }
}

/**
 * The fixed buttons under the selected photos: [actions] in slot order, then More when [onMore] is
 * set. The buttons share the row's width equally up to [PRIMARY_BUTTON_MAX_WIDTH] each and stay
 * centred beyond it. [live] turns their taps off once the row has faded out and while the drawer
 * closes.
 */
@Composable
private fun PrimaryActionRow(
    actions: List<SelectionAction>,
    onMore: (() -> Unit)?,
    buttonHeight: Dp,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val more = onMore?.let {
        SelectionAction(
            icon = Icons.Default.MoreHoriz,
            label = stringResource(R.string.viewer_menu_more),
            onClick = it,
        )
    }
    val buttons = if (more != null) actions + more else actions
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Row(
            modifier = Modifier
                .widthIn(max = PRIMARY_BUTTON_MAX_WIDTH * buttons.size + PRIMARY_BUTTON_GAP * (buttons.size - 1))
                .fillMaxWidth()
                .height(buttonHeight),
            horizontalArrangement = Arrangement.spacedBy(PRIMARY_BUTTON_GAP),
        ) {
            buttons.forEach { action ->
                PrimaryActionButton(
                    action = action,
                    live = live,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/** One primary button: the action's glyph, or its spinner while it works, named by its label for
 *  accessibility. The open list shows the same action with its label. */
@Composable
private fun PrimaryActionButton(
    action: SelectionAction,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(PRIMARY_BUTTON_CORNER)
    val label = action.shownLabel()
    Box(
        modifier = modifier
            .background(PillBg, shape)
            .border(0.5.dp, PillBorder, shape)
            .clickable(enabled = live && action.takesTaps(), onClick = action.onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = PRIMARY_BUTTON_PADDING_H, vertical = PRIMARY_BUTTON_PADDING_V),
        contentAlignment = Alignment.Center,
    ) {
        ActionGlyph(action = action, size = PRIMARY_ICON_SIZE, alpha = if (action.enabled) 1f else 0.5f)
    }
}

/** A primary action in the compact count row: its glyph alone in a round button, named by its label
 *  for accessibility. */
@Composable
private fun PrimaryGlyphButton(action: SelectionAction, live: Boolean) {
    val label = action.shownLabel()
    Box(
        modifier = Modifier
            .size(HEADER_CONTROL_SIZE)
            .background(PillBg, CircleShape)
            .border(0.5.dp, PillBorder, CircleShape)
            .clickable(enabled = live && action.takesTaps(), onClick = action.onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ActionGlyph(action = action, size = PRIMARY_GLYPH_SIZE, alpha = if (action.enabled) 1f else 0.5f)
    }
}

/** An action's glyph in its tint, or while it works a spinner in the same tint, determinate once it
 *  reports progress and carrying its working mark. */
@Composable
private fun ActionGlyph(action: SelectionAction, size: Dp, alpha: Float) {
    val tint = action.tint.takeOrElse { Accent }
    val progress = action.progress
    val workingIcon = action.workingIcon
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        when {
            action.working && progress != null -> CircularProgressIndicator(
                progress = { progress },
                color = tint,
                strokeWidth = 2.dp,
                modifier = Modifier.size(size),
            )
            action.working -> CircularProgressIndicator(
                color = tint,
                strokeWidth = 2.dp,
                modifier = Modifier.size(size),
            )
            else -> Icon(
                action.icon,
                contentDescription = null,
                tint = tint.copy(alpha = alpha),
                modifier = Modifier.size(size),
            )
        }
        if (action.working && workingIcon != null) {
            Icon(
                workingIcon,
                contentDescription = null,
                tint = ErrorColor,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

/** The label an action shows: its working label while it works, when it has one. */
private fun SelectionAction.shownLabel(): String =
    if (working && workingLabel != null) workingLabel else label

/** Whether a tap reaches the action: it is enabled, and not already at work unless its tap is what
 *  stops that work. */
private fun SelectionAction.takesTaps(): Boolean = enabled && (!working || clickableWhileWorking)

/**
 * While a thumbnail is enlarged, the first press here only settles it back, so a tap meant to put
 * the preview away cannot fire an action instead. [previewOpen] keys the guard, which stands down
 * once the preview is gone.
 */
private fun Modifier.settlesPreviewFirst(previewOpen: Boolean, onSettle: () -> Unit): Modifier =
    pointerInput(previewOpen) {
        if (!previewOpen) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) {
                    event.changes.forEach { it.consume() }
                    onSettle()
                }
            }
        }
    }

/**
 * The picked photos as a system gallery's picker shows them, for a screen that is only picking: a strip of
 * their thumbnails, or, while nothing is picked, a line saying so in the same space, so the page below does
 * not move when the first photo is picked, over a rule with a chevron in it. [expanded] folds the strip away
 * and leaves the rule, which [onToggle] opens and closes again. Each thumbnail takes [onItemClick] (the picker
 * puts the photo back), and the strip follows the newest pick.
 */
@Composable
fun SelectedPhotosStrip(
    items: List<GalleryItem>,
    emptyLabel: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onItemClick: (GalleryItem) -> Unit,
    modifier: Modifier = Modifier,
    cacheThumbnails: Boolean = true,
) {
    val colors = AppColors.current
    val stripState = rememberLazyListState()
    var lastCount by remember { mutableIntStateOf(items.size) }
    LaunchedEffect(items.size) {
        if (items.size > lastCount) stripState.animateScrollToItem(items.lastIndex)
        lastCount = items.size
    }
    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
        ) {
            Crossfade(
                targetState = items.isEmpty(),
                animationSpec = tween(160),
                label = "selectedPhotosStrip",
                modifier = Modifier.fillMaxWidth().height(PICKED_STRIP_HEIGHT),
            ) { empty ->
                if (empty) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(emptyLabel, color = colors.fgMute, fontSize = 14.sp)
                    }
                } else {
                    LazyRow(
                        state = stripState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(items, key = { it.stableId }) { item ->
                            val inputs = remember(item) { photoCellInputsFor(item) }
                            SelectionThumb(
                                cacheThumbnails = cacheThumbnails,
                                inputs = inputs,
                                enlarged = false,
                                onClick = { onItemClick(item) },
                            )
                        }
                    }
                }
            }
        }
        // The rule, with the chevron that opens and closes the strip breaking it.
        Row(
            modifier = Modifier.fillMaxWidth().height(24.dp).clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = Line2, thickness = DIVIDER_THICKNESS)
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.fgMute,
                modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = Line2, thickness = DIVIDER_THICKNESS)
        }
    }
}

/** Height of the picked-photos strip: the thumbnail and the air around it. */
private val PICKED_STRIP_HEIGHT = 72.dp

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
