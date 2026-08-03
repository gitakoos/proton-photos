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

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import eu.akoos.photos.presentation.common.ActionSheetRow
import eu.akoos.photos.presentation.common.ActionSheetSectionHeading
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.EditFieldSheet
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.ScrollScrubber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.usecase.moveInArrangement
import eu.akoos.photos.presentation.folders.DeviceFolderActionsSheet
import eu.akoos.photos.presentation.folders.DeviceFolderOpenAction
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder

/** Height of the reorder-mode bar, also the grid's extra top inset while that mode is on. */
private val ReorderBarHeight = 52.dp

/** Grid index the cloud albums start at: the pinned Memories cell is the one item{} emitted ahead of them. */
private const val CloudSegmentStart = 1

/** The laid-out cell at [gridIndex], or null once it has scrolled out of the viewport. */
private fun LazyGridState.cellAt(gridIndex: Int): LazyGridItemInfo? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.index == gridIndex }

/**
 * Index into the cloud-album list of the cell holding [point], or null for anything that is not a
 * cloud card.
 *
 * One range check carries the whole rule. The Memories cell is laid out ahead of the segment and
 * the device folders behind it, so subtracting the segment's start puts both outside `0 until`
 * [cloudCount] — neither can be displaced, and neither can be dropped onto. A point in the gap
 * between two cards belongs to no cell at all and reads as "no target", which is what leaves an
 * arrangement untouched when a drag ends between slots.
 */
private fun LazyGridState.cloudIndexAt(point: Offset, cloudCount: Int): Int? {
    val cell = layoutInfo.visibleItemsInfo.firstOrNull {
        point.x >= it.offset.x && point.x < it.offset.x + it.size.width &&
            point.y >= it.offset.y && point.y < it.offset.y + it.size.height
    } ?: return null
    return (cell.index - CloudSegmentStart).takeIf { it in 0 until cloudCount }
}

/** How deep from each edge of the grid's content window a held card starts pulling the grid along,
 *  as a share of that window. A share rather than a fixed depth so the reach scales with the screen
 *  the grid was given. */
private const val EdgeScrollBandFraction = 0.15f

/** Speed of that pull at the very edge, per second. Only the edge itself runs this fast, so a card
 *  can still be parked on a row part-way into the band. */
private val EdgeScrollMaxSpeed = 500.dp

/** Longest frame the pull will bill for. A stalled frame would otherwise move the grid by however
 *  long the stall lasted, in one jump. */
private const val EdgeScrollMaxFrameSeconds = 1f / 30f

/** Peak tilt of the arrange-mode wobble, either side of upright. A hint that the card is loose, so
 *  it stays under the angle at which the eye starts reading it as an effect. */
private const val WobbleDegrees = 1.2f

/** One leg of the sway — a full there-and-back cycle is twice this. */
private const val WobbleLegMillis = 380

/** Upper bound of the per-card jitter added to that leg. */
private const val WobbleLegJitterMillis = 90

/**
 * Tilt for one card being arranged, or a flat 0 while [active] is false.
 *
 * Cards sharing a period and a starting point sway as one sheet rather than as loose items, so
 * [seed] spreads both. It is the album's id and not its grid index because a drag renumbers every
 * card it shifts, which would re-seed them and jump their tilt mid-swap.
 *
 * The animation lives in the active branch alone, so leaving the mode disposes it.
 */
@Composable
private fun rememberReorderWobble(active: Boolean, seed: Int): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0f) }
    val hash = seed and Int.MAX_VALUE
    val leg = WobbleLegMillis + (hash shr 8) % WobbleLegJitterMillis
    val transition = rememberInfiniteTransition(label = "albumWobble")
    return transition.animateFloat(
        initialValue = -WobbleDegrees,
        targetValue = WobbleDegrees,
        animationSpec = infiniteRepeatable(
            animation = tween(leg, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
            // Enters the sway part-way through it, spread over the whole there-and-back cycle.
            initialStartOffset = StartOffset(hash % (leg * 2)),
        ),
        label = "albumWobbleTilt",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    topPadding: Dp = 0.dp,
    gridState: LazyGridState = rememberLazyGridState(),
    onAlbumClick: (Album) -> Unit = {},
    /** Opens an album the user owns with its share drawer already up, from the long-press sheet.
     *  Sharing is bound to the album screen's own state, so the quick action routes there rather
     *  than mounting a second copy of that flow on the grid. */
    onAlbumShareClick: (Album) -> Unit = {},
    /** Opens an album to carry out one action on arrival, from the long-press sheet. The three rows
     *  that need the album's members route there rather than resolving a whole album's worth of
     *  links on this grid, and the album screen already carries the confirmation, the progress and
     *  the cancel each of them reports through. */
    onAlbumActionClick: (Album, AlbumOpenAction) -> Unit = { _, _ -> },
    onDeviceFolderClick: (bucketName: String) -> Unit = {},
    /** Opens a device folder to carry out one action on arrival, from the long-press sheet. The two
     *  actions that need the folder's photos route there rather than pulling a whole library's worth
     *  of items into this grid. */
    onDeviceFolderActionClick: (bucketName: String, action: DeviceFolderOpenAction) -> Unit = { _, _ -> },
    /** Takes a device folder's card off this grid and moves its photos into the vault, where the user
     *  pressed. The host runs it and shows its progress, so hiding a folder goes nowhere. */
    onHideDeviceFolder: (bucketName: String) -> Unit = {},
    onMemoriesClick: () -> Unit = {},
    /** Increments each time the Albums-tab header "New album" pill is tapped; opens the create
     *  dialog. The in-grid New album row opens it directly, so 0 (no external trigger) is fine. */
    createRequestSignal: Int = 0,
    displayFilter: eu.akoos.photos.presentation.gallery.AlbumDisplayFilter =
        eu.akoos.photos.presentation.gallery.AlbumDisplayFilter.All,
    viewModel: AlbumsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val pullRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    // Open the create dialog only when the header pill's signal actually advances past the last one
    // handled. rememberSaveable persists that watermark through the pager disposing this page, so
    // returning to the Albums tab (which re-runs this effect with an unchanged signal) no longer
    // re-opens the dialog.
    var lastHandledCreateSignal by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(createRequestSignal) {
        if (createRequestSignal > lastHandledCreateSignal) {
            lastHandledCreateSignal = createRequestSignal
            showCreateDialog = true
        }
    }
    // Reorder mode is entered deliberately from the long-press sheet rather than armed by the
    // press itself: the cards are combinedClickable and the grid's right edge carries the
    // scrubber, so a gesture-armed drag would be fighting both. Plain remember, not
    // rememberSaveable — the mode is a stance the user is holding right now, so it belongs with
    // the screen and never reaches the settings store.
    var reorderMode by remember { mutableStateOf(false) }
    BackHandler(enabled = reorderMode) { reorderMode = false }
    // Cloud-album ids in the order the cards are laid out while arranging. Seeded when the mode
    // opens and read only while it is open, so a finished arrangement cannot shadow a sort the user
    // picks afterwards.
    var arrangedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    // The card under the finger: which album, where its top-left has been dragged to in the grid's
    // own coordinates, and the lift that carries it there from whichever slot it currently holds.
    var draggedAlbumId by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // Where inside the card the finger came down. The card's position and the finger move by the
    // same deltas from there on, so this stays the distance between the two and is what gives the
    // auto-scroll the finger's own place in the grid rather than the card's corner.
    var dragGrip by remember { mutableStateOf(Offset.Zero) }
    // Switching the All / Cloud / Device filter changes the list under the same scroll index, which
    // reads as the grid jumping — snap back to the top whenever the filter changes. The filter also
    // decides whether cloud albums are on screen at all, so an arrangement in progress ends with it.
    LaunchedEffect(displayFilter) {
        reorderMode = false
        gridState.scrollToItem(0)
    }
    var albumToDelete by remember { mutableStateOf<Album?>(null) }

    // Cloud-album long-press surfaces a Rename + Delete bottom sheet. Holding the in-flight
    // Album object directly so we can read the current name + linkId without a second lookup.
    var cloudAlbumSheetFor by remember { mutableStateOf<Album?>(null) }
    val cloudAlbumSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var cloudAlbumRenameFor by remember { mutableStateOf<Album?>(null) }

    // A device-folder card answers the same long press, opening the drawer the folder's own screen
    // carries so its preferences and its order are reachable from the grid too.
    var deviceFolderSheetFor by remember { mutableStateOf<DeviceFolder?>(null) }
    val deviceFolderSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    /** Collect an [AlbumActionResult] flow once and snackbar the outcome. */
    suspend fun handleAlbumActionFlow(
        actionFlow: Flow<AlbumActionResult>,
        doneMessage: String,
    ) {
        when (val outcome = actionFlow.first()) {
            is AlbumActionResult.Done -> snackbarHostState.showSnackbar(doneMessage)
            is AlbumActionResult.Failed -> snackbarHostState.showSnackbar(outcome.message)
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(state.createAlbumError) {
        state.createAlbumError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearCreateAlbumError()
        }
    }

    // The ViewModel's list leads, except while an arrangement is open: then the local order holds
    // the cards in the slots the finger has put them in, so the reflow is visible under the finger
    // instead of a store round-trip later. An album that arrives mid-arrangement is unknown to that
    // order and trails, staying put until the mode is re-entered.
    val albums = if (reorderMode && arrangedIds.isNotEmpty()) {
        state.visibleCloudAlbums.sortedBy { album ->
            arrangedIds.indexOf(album.linkId).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
    } else {
        state.visibleCloudAlbums
    }
    // Folders whose card the user hid are off the grid, exactly as a hidden cloud album is. The
    // folder itself is untouched and its photos stay in the timeline; only the card goes.
    val deviceFolders = state.visibleDeviceFolders
    LaunchedEffect(reorderMode) {
        arrangedIds = if (reorderMode) albums.map { it.linkId } else emptyList()
    }

    /**
     * Store the arrangement as the grid is showing it. Cloud albums the grid cannot see — the ones
     * hidden client-side — keep a slot at the end, because the stored order replaces the previous
     * one outright and a save that only knew the visible cards would drop theirs every time.
     */
    fun saveArrangement() {
        val shown = arrangedIds
        if (shown.isEmpty()) return
        viewModel.saveAlbumArrangement(shown + state.albums.map { it.linkId }.filterNot { it in shown })
    }

    /**
     * Put the held card where [dragPosition] now points: take the slot under its centre when that
     * is a different cloud card, then measure the lift from whichever slot it ends up owning.
     *
     * Both the pointer handler and the auto-scroll loop run this. Slots move under a finger that is
     * holding still exactly as much as under one that is moving, and the lift is measured against a
     * slot, so re-running it on every scrolled frame is what keeps the card on the finger instead of
     * letting it ride away with its old slot.
     */
    fun settleDrag(albumId: String) {
        val from = arrangedIds.indexOf(albumId)
        val cell = (if (from >= 0) gridState.cellAt(CloudSegmentStart + from) else null) ?: return
        // The card's own centre picks the target, so how far it has to travel to take a slot does
        // not depend on where inside it the finger landed.
        val centre = dragPosition + Offset(cell.size.width / 2f, cell.size.height / 2f)
        val to = gridState.cloudIndexAt(centre, arrangedIds.size)
        // A move lands the card on the slot its target holds right now, so the lift is measured from
        // that slot rather than the one being left.
        var slot = cell.offset
        if (to != null && to != from) {
            gridState.cellAt(CloudSegmentStart + to)?.let { slot = it.offset }
            arrangedIds = moveInArrangement(arrangedIds, from, to)
        }
        dragOffset = dragPosition - Offset(slot.x.toFloat(), slot.y.toFloat())
    }

    val density = LocalDensity.current
    // Pull the grid along while a held card sits near an edge, so an arrangement is not confined to
    // the screenful the card was picked up in. One loop covers the whole drag and idles outside the
    // bands: the other cards are wobbling, so frames are being produced regardless and an idle turn
    // buys nothing back by unsubscribing from them.
    LaunchedEffect(draggedAlbumId) {
        val albumId = draggedAlbumId ?: return@LaunchedEffect
        val maxSpeed = with(density) { EdgeScrollMaxSpeed.toPx() }
        var previousFrame = 0L
        while (true) {
            val frame = withFrameNanos { it }
            // Billed per elapsed second rather than per frame, so the pull covers the same ground on
            // a 120Hz panel as on a 60Hz one.
            val elapsed = if (previousFrame == 0L) 0f else (frame - previousFrame) / 1_000_000_000f
            previousFrame = frame
            val info = gridState.layoutInfo
            // Measured off the content window: the strips the grid pads for the floating header and
            // for the bottom bar hold no cards, so treating them as inside the window would put the
            // whole top band behind the reorder bar.
            val top = (info.viewportStartOffset + info.beforeContentPadding).toFloat()
            val bottom = (info.viewportEndOffset - info.afterContentPadding).toFloat()
            val velocity = edgeScrollVelocity(
                pointerY = dragPosition.y + dragGrip.y,
                contentTop = top,
                contentBottom = bottom,
                band = (bottom - top) * EdgeScrollBandFraction,
                maxVelocity = maxSpeed,
            )
            if (velocity == 0f || elapsed <= 0f) continue
            // The pull only ever serves this card's move, so it stops at the ends of the
            // arrangement. Past them there is no slot left to take and the grid would scroll the
            // card's own slot out from under it, taking the card off screen with it.
            val at = arrangedIds.indexOf(albumId)
            if (at < 0 || (velocity > 0f && at == arrangedIds.lastIndex) || (velocity < 0f && at == 0)) continue
            gridState.scrollBy(velocity * elapsed.coerceAtMost(EdgeScrollMaxFrameSeconds))
            settleDrag(albumId)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.refresh() },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {},
        ) {
            when {
                state.isLoading && albums.isEmpty() ->
                    // Skeleton placeholder grid — must use the SAME paddings, spacings, and
                    // header structure as the real grid below, otherwise the transition into
                    // real content reflows visibly (placeholders shift to new positions when
                    // the cards arrive).
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(
                            top = topPadding + 12.dp,
                            start = 20.dp,
                            end = 20.dp,
                            bottom = 120.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(6) {
                            eu.akoos.photos.presentation.common.ShimmerAlbumCard()
                        }
                    }

                // Nothing matches the active filter → centred empty state.
                !(displayFilter != eu.akoos.photos.presentation.gallery.AlbumDisplayFilter.Local && albums.isNotEmpty()) &&
                    !(displayFilter != eu.akoos.photos.presentation.gallery.AlbumDisplayFilter.Cloud && deviceFolders.isNotEmpty()) ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Device-only filter has no Drive albums, so the cloud-worded copy would be
                        // wrong — show a device-folder line instead.
                        val localOnly = displayFilter == eu.akoos.photos.presentation.gallery.AlbumDisplayFilter.Local
                        Text(
                            stringResource(if (localOnly) R.string.albums_empty_local else R.string.albums_empty_title),
                            color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        )
                        if (!localOnly) {
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.albums_empty_subtitle), color = FgDim, fontSize = 14.sp)
                        }
                    }

                // Common stream: cloud albums + device folders in one flat grid, narrowed by the
                // Albums-tab toggle pill (All / Cloud / Local). No section headers — the pill is
                // the only grouping control now.
                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        contentPadding = PaddingValues(
                            // The reorder bar floats over the grid, so the first row is pushed
                            // clear of it instead of starting underneath it.
                            top = topPadding + 12.dp + if (reorderMode) ReorderBarHeight else 0.dp,
                            start = 20.dp,
                            end = 20.dp,
                            bottom = 120.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item {
                            MemoriesPinnedCard(onClick = onMemoriesClick)
                        }
                        if (displayFilter != eu.akoos.photos.presentation.gallery.AlbumDisplayFilter.Local) {
                            items(
                                albums,
                                key = { "cloud_${it.linkId}" },
                            ) { album ->
                                val dragged = album.linkId == draggedAlbumId
                                // The lifted card is already following the finger and needs no
                                // invitation to be picked up.
                                val wobble = rememberReorderWobble(
                                    active = reorderMode && !dragged,
                                    seed = album.linkId.hashCode(),
                                )
                                // Only the cloud cards go inert while arranging: opening an album
                                // or reopening the sheet mid-arrangement would both be a surprise,
                                // and the mode leaves their gestures free for the arrangement
                                // itself. The Memories card and the device folders below are not
                                // part of the arrangement, so they keep their own taps.
                                CloudAlbumCard(
                                    album       = album,
                                    interactionsEnabled = !reorderMode,
                                    onClick     = { onAlbumClick(album) },
                                    onLongClick = { cloudAlbumSheetFor = album },
                                    modifier = Modifier
                                        .animateItem(
                                            // The dragged card is placed by the finger, so letting
                                            // the grid animate it into its new slot as well would
                                            // pull it out from under the finger after every swap.
                                            placementSpec = if (dragged) null else spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                visibilityThreshold = IntOffset.VisibilityThreshold,
                                            ),
                                        )
                                        .zIndex(if (dragged) 1f else 0f)
                                        // Read inside the layer block, so a frame of sway costs a
                                        // redraw of this card rather than a recomposition.
                                        .graphicsLayer {
                                            translationX = if (dragged) dragOffset.x else 0f
                                            translationY = if (dragged) dragOffset.y else 0f
                                            rotationZ = wobble.value
                                        }
                                        .then(
                                            // Press and hold to pick a card up, so a plain swipe is
                                            // left to the grid: a gesture that claims every
                                            // touch-and-move leaves a library taller than the screen
                                            // with no way to scroll while arranging, stranding every
                                            // album below the fold. Nothing else is waiting on the
                                            // hold — the mode has already taken combinedClickable off
                                            // these cards — and the wait consumes nothing, so the
                                            // grid still takes over the moment the finger passes
                                            // touch slop.
                                            //
                                            // These handlers outlive the composition that built them,
                                            // so they read arrangedIds rather than albums or the
                                            // item's index: only state stays current in here.
                                            if (!reorderMode) Modifier else Modifier.pointerInput(album.linkId) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { grip ->
                                                        val from = arrangedIds.indexOf(album.linkId)
                                                        val cell = if (from >= 0) gridState.cellAt(CloudSegmentStart + from) else null
                                                        if (cell != null) {
                                                            draggedAlbumId = album.linkId
                                                            dragPosition = Offset(cell.offset.x.toFloat(), cell.offset.y.toFloat())
                                                            dragGrip = grip
                                                            dragOffset = Offset.Zero
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        if (draggedAlbumId == album.linkId) {
                                                            draggedAlbumId = null
                                                            dragOffset = Offset.Zero
                                                            saveArrangement()
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        // The reorder lands as the pointer moves, so a
                                                        // cancelled gesture has already changed what the
                                                        // user sees. Saving matches the screen; skipping
                                                        // it left the card in its new slot until the next
                                                        // repaint quietly put it back.
                                                        //
                                                        // Guarded like onDragEnd: this also fires when the
                                                        // card simply leaves the grid, and scrolling while
                                                        // arranging is expected. Unguarded it saved for a
                                                        // card nobody dragged, which switches the album
                                                        // sort to Custom behind the user's back.
                                                        if (draggedAlbumId == album.linkId) {
                                                            draggedAlbumId = null
                                                            dragOffset = Offset.Zero
                                                            saveArrangement()
                                                        }
                                                    },
                                                ) { change, drag ->
                                                    change.consume()
                                                    if (draggedAlbumId != album.linkId) return@detectDragGesturesAfterLongPress
                                                    dragPosition += drag
                                                    settleDrag(album.linkId)
                                                }
                                            }
                                        ),
                                )
                            }
                        }
                        if (displayFilter != eu.akoos.photos.presentation.gallery.AlbumDisplayFilter.Cloud) {
                            items(
                                deviceFolders,
                                key = { "devfolder_${it.name}" },
                            ) { folder ->
                                UnifiedAlbumCard(
                                    coverModel = folder.coverUri?.let(Uri::parse),
                                    title = folder.name,
                                    metaText = pluralStringResource(
                                        R.plurals.count_photos_plural, folder.itemCount, folder.itemCount,
                                    ),
                                    isDeviceFolder = true,
                                    onClick = { onDeviceFolderClick(folder.name) },
                                    onLongClick = { deviceFolderSheetFor = folder },
                                )
                            }
                        }
                    }
            }
        }

        // Reorder-mode bar: says the mode is on and offers the single way out. Mirrors the
        // selection-mode header used on the photo grids (close bubble + state pill), sitting under
        // the host's floating header because this screen is a pager page and does not own the top
        // of the window. Opaque fill so it stays readable with album covers scrolling beneath it.
        if (reorderMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = topPadding, start = 20.dp, end = 20.dp)
                    .height(ReorderBarHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconBubble(
                    icon = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    onClick = { reorderMode = false },
                    diameter = 40.dp,
                    iconSize = 20.dp,
                    background = PillBgOpaque,
                    borderColor = PillBorder,
                    tint = FgPrimary,
                )
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
                        stringResource(R.string.albums_reorder),
                        color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Fast-scroll handle for long album / device-folder grids. Two-column grid, so it only
        // earns its keep past several rows — gate on a screenful's worth of cells. No date axis
        // here, so it's a plain scroll-position thumb (no label).
        ScrollScrubber(
            gridState = gridState,
            topPadding = topPadding + 12.dp,
            bottomPadding = 24.dp,
            minItemsToShow = 12,
        )

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    // ── Delete Album confirmation dialog ──────────────────────────────────────
    // AlbumService.deleteAlbum passes deleteAlbumPhotos=0, so only the album container
    // is removed — the photos themselves stay in Proton Drive. Uses a dedicated string
    // (not the in-album multi-delete confirmation, which IS destructive) so the copy
    // accurately describes the container-only delete instead of promising to
    // "permanently delete the original".
    albumToDelete?.let { album ->
        ConfirmDialog(
            title = "\"${album.name}\"",
            message = stringResource(R.string.delete_album_container_warning),
            confirmLabel = stringResource(R.string.delete_button_permanently),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { viewModel.deleteAlbum(album.linkId); albumToDelete = null },
            onDismiss = { albumToDelete = null },
            destructive = true,
        )
    }

    // ── Second confirmation, raised by the server, not by us ──────────────────
    // The delete above asks for no photo deletion, and Drive refuses it outright when the album
    // holds the only copy of some photos. That happens once someone the album was shared with has
    // contributed: their photo arrives as a copy parented to the album and never enters this
    // library, so removing the album really would destroy it. Nothing has been touched at this
    // point, the album is still there, and the user gets to decide with the fact in front of them.
    state.deleteWouldLosePhotosFor?.let { albumLinkId ->
        ConfirmDialog(
            title = stringResource(R.string.albums_delete_would_lose_title),
            message = stringResource(R.string.albums_delete_would_lose_body),
            confirmLabel = stringResource(R.string.albums_delete_would_lose_action),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { viewModel.deleteAlbum(albumLinkId, deletePhotosToo = true) },
            onDismiss = { viewModel.dismissDeleteWouldLosePhotos() },
            destructive = true,
        )
    }

    // ── Create Album dialog ────────────────────────────────────────────────────
    if (showCreateDialog) {
        var albumName by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { showCreateDialog = false; albumName = "" },
            containerColor = Bg2,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.albums_new_album), color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = albumName,
                    onValueChange = { albumName = it },
                    placeholder = { Text(stringResource(R.string.albums_create_album_hint), color = FgMute) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Accent,
                        unfocusedBorderColor = Line2,
                        focusedTextColor     = FgPrimary,
                        unfocusedTextColor   = FgPrimary,
                        cursorColor          = Accent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (albumName.trim().isNotEmpty()) {
                            viewModel.createAlbum(albumName)
                            showCreateDialog = false
                            albumName = ""
                        }
                    }),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showCreateDialog = false; albumName = "" }) {
                        Text(stringResource(R.string.cancel), color = FgDim)
                    }
                    TextButton(
                        onClick = {
                            viewModel.createAlbum(albumName)
                            showCreateDialog = false
                            albumName = ""
                        },
                        enabled = albumName.trim().isNotEmpty(),
                    ) { Text(stringResource(R.string.albums_create_album), color = Accent, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    // ── Cloud-album long-press action sheet ──────────────────────────────────
    // Share hands the album to the host, which opens it with its share drawer up. Delete maps to the
    // existing albumToDelete confirm path, and rename opens the sheet below which calls
    // renameCloudAlbum via the action-flow helper. The three rows that run on the album's members
    // hand it over with an intent instead, for the album screen to carry out on arrival.
    cloudAlbumSheetFor?.let { album ->
        CloudAlbumActionSheet(
            album = album,
            sheetState = cloudAlbumSheetState,
            sortMode = state.albumPhotoSortMode,
            isHiddenFromTimeline = AlbumTimelineHide.isExcluded(state.timelineExcludedAlbumIds, album.linkId),
            onDismiss = { cloudAlbumSheetFor = null },
            onShare = { onAlbumShareClick(album) },
            onRename = { cloudAlbumRenameFor = album },
            onToggleHiddenFromTimeline = { viewModel.toggleAlbumHiddenFromTimeline(album.linkId) },
            onHide = { viewModel.hideAlbum(album.linkId) },
            onReorder = { reorderMode = true },
            onDelete = { albumToDelete = album },
            onSortSelected = viewModel::setAlbumPhotoSortMode,
            onOpenAction = { action -> onAlbumActionClick(album, action) },
        )
    }

    // ── Device-folder long-press action sheet ────────────────────────────────
    // The folder screen's own drawer, offering the same rows. The grid holds a cover and a count per
    // bucket, not a photo list, so the rows that need the whole folder in hand — the back-up and the
    // slideshow, which want the player too — hand the folder to its own screen with the action to
    // carry out on arrival. The hide stays here: it resolves the one bucket it acts on and reports
    // through the host's progress pill, so taking a card off this grid never leaves it. The rest are
    // the per-folder preferences and the sort direction, each writing the same key the folder screen
    // and Settings write.
    deviceFolderSheetFor?.let { folder ->
        DeviceFolderActionsSheet(
            sheetState = deviceFolderSheetState,
            folderName = folder.name,
            isMirroredAsAlbum = folder.name in state.folderPrefs.mirroredAsAlbum,
            isExcludedFromBackup = folder.name in state.folderPrefs.excludedFromBackup,
            isHiddenFromTimeline = folder.name in state.folderPrefs.hiddenFromTimeline,
            // A card on this grid stands for photos that are on the device, so its hide row always
            // puts something away and is never already done — even where the vault holds more of the
            // same folder from an earlier hide.
            isHiddenCard = false,
            sortMode = state.folderPrefs.sortMode,
            onDismiss = { deviceFolderSheetFor = null },
            onBackUp = { asMirror ->
                onDeviceFolderActionClick(
                    folder.name,
                    if (asMirror) DeviceFolderOpenAction.BackUpAndMirror
                    else DeviceFolderOpenAction.BackUpToTimeline,
                )
            },
            onSlideshow = { onDeviceFolderActionClick(folder.name, DeviceFolderOpenAction.Slideshow) },
            onToggleMirrorAsAlbum = { viewModel.toggleFolderMirrorAsAlbum(folder.name) },
            onToggleExcludedFromBackup = { viewModel.toggleFolderExcludedFromBackup(folder.name) },
            onToggleHiddenFromTimeline = { viewModel.toggleFolderHiddenFromTimeline(folder.name) },
            onToggleHiddenCard = { onHideDeviceFolder(folder.name) },
            onSortSelected = viewModel::setDeviceFolderSortMode,
        )
    }

    // ── Cloud-album rename sheet ─────────────────────────────────────────────
    cloudAlbumRenameFor?.let { album ->
        // Resolved here (composable scope) so the scope.launch lambda below — which runs off
        // the composition — can format it without calling stringResource in a non-composable.
        val renamedToTemplate = stringResource(R.string.albums_renamed_to)
        EditFieldSheet(
            title = stringResource(R.string.album_rename),
            hint = stringResource(R.string.albums_create_album_hint),
            initialValue = album.name,
            singleLine = true,
            confirmLabel = stringResource(R.string.album_rename_confirm),
            canConfirm = { AlbumRenameInput.isAcceptable(it, album.name) },
            onDismiss = { cloudAlbumRenameFor = null },
            onSave = { entered ->
                val target = entered.trim()
                scope.launch {
                    handleAlbumActionFlow(
                        actionFlow = viewModel.renameCloudAlbum(album.linkId, album.name, target),
                        doneMessage = renamedToTemplate.format(target),
                    )
                }
            },
        )
    }

}

/**
 * Bottom sheet that opens on long-press of a cloud album card. It offers what the album's own drawer
 * offers, so the shortest route to an album's actions is not the poorer one: add photos, share,
 * download all, slideshow, rename, hide, reorder, sort, delete.
 *
 * Sections follow the app's action-sheet taxonomy: what runs on the album's photos, then the album's
 * settings, then the sort, then what cannot be undone below a rule. The sort direction is one global
 * choice every album shares, so [sortMode] is the direction in force rather than this album's own.
 *
 * Who is looking decides several of them: share, download, rename and delete belong to the owner,
 * matching what the album's own drawer offers a guest, so a shared album shows what is still the
 * guest's to make. Hide is one of those: it writes a client-side id set, so a guest hiding a shared
 * album takes the card out of their own grid and touches neither Drive nor the owner. Keeping the
 * album's photos out of the main feed is the weaker choice beside it, on its own id set: the card
 * stays on the grid and the photos stay in search, on the map, in the calendar and in every picker,
 * so it carries a tick and reverses on a second tap. Adding follows
 * the album's write grant rather than ownership, which is what lets an editor contribute. Reorder is
 * the odd one out: it acts on the grid rather than on this album, and is here because the press that
 * opens this sheet is the same press a rearrangement starts from.
 *
 * The three [AlbumOpenAction] rows leave through [onOpenAction] instead of acting here: each of them
 * runs on the album's members, which this grid never holds, and each reports through a confirmation,
 * a progress pill or a picker that belongs to the album screen. An empty album drops the two that
 * would have nothing to work on and keeps the one that fills it.
 *
 * Cloud rename is wired through `AlbumsViewModel.renameCloudAlbum` which round-trips through
 * `DrivePhotoRepository.renameAlbum`. Hide is client-side only via `AlbumsViewModel.hideAlbum`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudAlbumActionSheet(
    album: Album,
    sheetState: SheetState,
    sortMode: AlbumPhotoSortMode,
    /** True while this album's photos are kept out of the main feed. About the photos, which
     *  [onHide] is not: that one takes the album itself out of every list. */
    isHiddenFromTimeline: Boolean,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onToggleHiddenFromTimeline: () -> Unit,
    onHide: () -> Unit,
    onReorder: () -> Unit,
    onDelete: () -> Unit,
    onSortSelected: (AlbumPhotoSortMode) -> Unit,
    onOpenAction: (AlbumOpenAction) -> Unit,
) {
    val colors = AppColors.current
    val scope = rememberCoroutineScope()
    // Slide the drawer away before the action lands, so the rename sheet or the delete confirmation
    // never opens behind a sheet that is still on screen.
    fun close(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bg2,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        // Height-capped + scroll so a short device never clips the last row.
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            // Gaps are the explicit spacers alone, the same 8dp between rows and 20dp above a
            // heading its two sibling drawers keep.
            Text(
                "\"${album.name}\"",
                color = colors.fgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            // A guest's drawer on an album they can neither add to nor play holds nothing that runs
            // on the photos, and drops this group rather than heading an empty one.
            val hasPhotoActions =
                album.canAddPhotos || !album.isSharedWithMe || album.photoCount > 0
            if (hasPhotoActions) {
                ActionSheetSectionHeading(
                    stringResource(R.string.action_sheet_actions_heading),
                    first = true,
                )
                if (album.canAddPhotos) {
                    ActionSheetRow(
                        icon = Icons.Default.Add,
                        title = stringResource(R.string.album_add_photos),
                        onClick = { close { onOpenAction(AlbumOpenAction.AddPhotos) } },
                    )
                }
                if (!album.isSharedWithMe) {
                    if (album.canAddPhotos) Spacer(Modifier.height(8.dp))
                    // Ticked once the album is shared, the same mark the album's own drawer carries.
                    ActionSheetRow(
                        icon = Icons.Default.Share,
                        title = stringResource(R.string.albums_share_button),
                        onClick = { close(onShare) },
                        showCheck = album.isShared,
                    )
                    // A guest's copy of an album is saved from the Shared tab, which owns that flow
                    // and its wording, so this stays the owner's plain download.
                    if (album.photoCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        ActionSheetRow(
                            icon = Icons.Default.FileDownload,
                            title = stringResource(R.string.albums_download_all),
                            onClick = { close { onOpenAction(AlbumOpenAction.DownloadAll) } },
                        )
                    }
                }
                if (album.photoCount > 0) {
                    if (album.canAddPhotos || !album.isSharedWithMe) Spacer(Modifier.height(8.dp))
                    ActionSheetRow(
                        icon = Icons.Default.PlayArrow,
                        title = stringResource(R.string.viewer_play_slideshow),
                        onClick = { close { onOpenAction(AlbumOpenAction.Slideshow) } },
                    )
                }
            }

            // Hide and reorder are on every card, so this group is never empty.
            ActionSheetSectionHeading(
                stringResource(R.string.action_sheet_settings_heading),
                first = !hasPhotoActions,
            )
            if (!album.isSharedWithMe) {
                ActionSheetRow(
                    icon = Icons.Default.Edit,
                    title = stringResource(R.string.album_rename),
                    onClick = { close(onRename) },
                )
                Spacer(Modifier.height(8.dp))
            }
            // The weaker of the two hides, and the one that carries a tick: it writes the same
            // per-album key the Settings picker does, so the state is the current one wherever it
            // was set. The row below is the stronger action and stays a one-way choice.
            ActionSheetRow(
                icon = Icons.Default.HideImage,
                title = stringResource(R.string.device_folder_hide_from_timeline),
                onClick = { close(onToggleHiddenFromTimeline) },
                showCheck = isHiddenFromTimeline,
            )
            Spacer(Modifier.height(8.dp))
            ActionSheetRow(
                icon = Icons.Default.VisibilityOff,
                title = stringResource(R.string.albums_hide_album),
                onClick = { close(onHide) },
            )
            Spacer(Modifier.height(8.dp))
            ActionSheetRow(
                icon = Icons.Default.DragHandle,
                title = stringResource(R.string.albums_reorder),
                onClick = { close(onReorder) },
            )

            ActionSheetSectionHeading(stringResource(R.string.albums_sort_heading))
            AlbumPhotoSortMode.entries.forEachIndexed { index, mode ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                ActionSheetRow(
                    icon = when (mode) {
                        AlbumPhotoSortMode.NewestFirst -> Icons.Default.ArrowDownward
                        AlbumPhotoSortMode.OldestFirst -> Icons.Default.ArrowUpward
                    },
                    title = stringResource(
                        when (mode) {
                            AlbumPhotoSortMode.NewestFirst -> R.string.sort_newest_first
                            AlbumPhotoSortMode.OldestFirst -> R.string.sort_oldest_first
                        },
                    ),
                    onClick = { close { onSortSelected(mode) } },
                    showCheck = mode == sortMode,
                )
            }

            if (!album.isSharedWithMe) {
                // Deleting takes the album off Drive, so it keeps the rule above it and the red
                // treatment it carries everywhere else.
                HorizontalDivider(color = colors.line2, modifier = Modifier.padding(vertical = 16.dp))
                ActionSheetRow(
                    icon = Icons.Default.DeleteOutline,
                    title = stringResource(R.string.delete_button_permanently),
                    onClick = { close(onDelete) },
                    destructive = true,
                )
            }
        }
    }
}

// ── Album cards ────────────────────────────────────────────────────────────────

private fun shareBadgeOf(album: Album): AlbumShareBadge = when {
    album.isSharedWithMe -> AlbumShareBadge.SharedWithMe
    album.isShared       -> AlbumShareBadge.SharedByMe
    else                 -> AlbumShareBadge.None
}

@Composable
private fun CloudAlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    interactionsEnabled: Boolean = true,
) {
    // Cloud Album entity has no per-mime-type breakdown, so we only have a total to show.
    // Using the media-neutral count_items_plural keeps "1 item" / "N items" pluralisation
    // correct without promising a photos-vs-videos split we can't compute without per-album
    // child fetches.
    UnifiedAlbumCard(
        coverModel  = album.coverThumbnailUrl,
        title       = album.name,
        metaText    = androidx.compose.ui.res.pluralStringResource(
            R.plurals.count_items_plural, album.photoCount, album.photoCount,
        ),
        shareBadge  = shareBadgeOf(album),
        cloudBadge  = AlbumCloudBadge.Cloud,
        interactionsEnabled = interactionsEnabled,
        onClick     = onClick,
        onLongClick = onLongClick,
        modifier    = modifier,
    )
}

/**
 * First cell in the album grid. Mirrors [UnifiedAlbumCard]'s shape, corner radius and title
 * typography, but with a neutral cover holding a single centred Collections glyph — a normal
 * album card labelled "Memories" that opens the Memories screen.
 */
@Composable
private fun MemoriesPinnedCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Bg2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Collections,
                contentDescription = null,
                tint = FgDim,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.memories_title),
            color = FgPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
