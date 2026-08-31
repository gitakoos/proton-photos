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

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Accent2
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.ArcTrack
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.theme.pillShape
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Face
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.transform.Transformation

/**
 * Data and callbacks the People chip and face bar on the category rail read, handed down by
 * [eu.akoos.photos.presentation.gallery.GalleryScreen] through a CompositionLocal so [CategoryRail]
 * can render them without new parameters threaded through the header. Empty by default, so a rail
 * composed with the AI features off, no indexed people, or outside the provider (the search screen)
 * shows no People chip and no bar.
 */
internal data class PeopleRailData(
    val people: List<PersonUi> = emptyList(),
    val selectedPersonId: Long? = null,
    /** True while the People bar is revealed (the chip is toggled on or a person is selected). */
    val active: Boolean = false,
    val onToggle: () -> Unit = {},
    val onPersonSelected: (Long) -> Unit = {},
)

internal val LocalPeopleRail = compositionLocalOf { PeopleRailData() }

@Composable
internal fun AlbumsFilterRail(
    onHiddenAlbumClick: () -> Unit = {},
    onNewAlbumClick: () -> Unit = {},
    /** Logged-out only: create a real device folder from picked local photos (Android 10+). */
    onNewLocalFolder: () -> Unit = {},
    selectedFilter: AlbumDisplayFilter = AlbumDisplayFilter.All,
    onFilterSelected: (AlbumDisplayFilter) -> Unit = {},
    onOpenSheet: () -> Unit = {},
    isSignedIn: Boolean = true,
    /** Albums-tab inline search, hoisted in the gallery and threaded through the header. */
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    searchActive: Boolean = false,
    onSearchActiveChange: (Boolean) -> Unit = {},
    /** True while the page arranges albums; hides the search entry so the two never share the rail. */
    reorderActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Focus the field only as the bar opens, never on a plain tab visit.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(searchActive) { if (searchActive) focusRequester.requestFocus() }
    // Back closes the bar and clears the query before it leaves the tab.
    BackHandler(enabled = searchActive) { onSearchActiveChange(false); onSearchQueryChange("") }

    BoxWithConstraints(modifier = modifier) {
        // The rail is the weighted half of the header row, so it already stops before the avatar: the
        // open bar fills the whole rail width, collapsed it is one 38dp icon pinned at the end.
        val searchWidth by animateDpAsState(
            targetValue = if (searchActive) maxWidth else 38.dp,
            animationSpec = tween(220),
            label = "albumsSearchWidth",
        )
        // Filter, Hidden and New pills, taken off the rail while the bar is open so none is pressed by
        // accident. The avatar beside the rail stays in place throughout.
        if (!searchActive) {
            AlbumsRailPills(
                onHiddenAlbumClick = onHiddenAlbumClick,
                onNewAlbumClick = onNewAlbumClick,
                onNewLocalFolder = onNewLocalFolder,
                selectedFilter = selectedFilter,
                onFilterSelected = onFilterSelected,
                onOpenSheet = onOpenSheet,
                isSignedIn = isSignedIn,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Morphing search element: a 38dp icon collapsed, the input bar filling the rail open. Gone
        // while arranging, which takes the whole rail for its own bar.
        if (!reorderActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(searchWidth)
                    .height(38.dp)
                    .clip(pillShape)
                    .background(PillBg, pillShape)
                    .border(0.5.dp, if (searchQuery.isNotEmpty()) Accent else PillBorder, pillShape)
                    .clickable(enabled = !searchActive) { onSearchActiveChange(true) },
            ) {
                if (searchActive) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = FgDim, modifier = Modifier.size(18.dp))
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    stringResource(R.string.albums_search_hint),
                                    color = FgMute,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                singleLine = true,
                                textStyle = TextStyle(color = FgPrimary, fontSize = 14.sp),
                                cursorBrush = SolidColor(Accent),
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            )
                        }
                        // Clears a non-empty query, then closes the bar on the next tap.
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { if (searchQuery.isNotEmpty()) onSearchQueryChange("") else onSearchActiveChange(false) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.cd_clear_search),
                                tint = FgDim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.search_title),
                        tint = FgDim,
                        modifier = Modifier.align(Alignment.Center).size(18.dp),
                    )
                }
            }
        }
    }
}

/** The Albums rail's fixed pills (view filter, Hidden, New), lifted out so the search bar can take
 *  their place while it is open without re-indenting or duplicating them. */
@Composable
private fun AlbumsRailPills(
    onHiddenAlbumClick: () -> Unit,
    onNewAlbumClick: () -> Unit,
    onNewLocalFolder: () -> Unit,
    selectedFilter: AlbumDisplayFilter,
    onFilterSelected: (AlbumDisplayFilter) -> Unit,
    onOpenSheet: () -> Unit,
    isSignedIn: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The configured default narrowing. The pill highlights only when the current filter differs
    // from this default, so leaving the filter at its default reads as "unfiltered".
    val defaultFilterOrdinal by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.ALBUMS_DEFAULT_FILTER] ?: 0 }
    }.collectAsState(initial = 0)
    val defaultFilter = AlbumDisplayFilter.entries[
        defaultFilterOrdinal.coerceIn(0, AlbumDisplayFilter.entries.lastIndex)
    ]
    val hiddenLabel = stringResource(R.string.gallery_filter_hidden)
    val newAlbumLabel = stringResource(R.string.albums_new_album)
    val newFolderLabel = stringResource(R.string.new_folder)
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // Room at the end so a pill never slides under the collapsed search icon.
        contentPadding = PaddingValues(end = 46.dp),
    ) {
        // All / Cloud / Local view filter. Tapping the label cycles All to Cloud to Local; the
        // filter icon after the separator opens the sheet (default + remember-last). Highlighted
        // when off the configured default, so a filter sitting at its default reads as unfiltered.
        item(key = "album_filter") {
            val active = selectedFilter != defaultFilter
            val label = when (selectedFilter) {
                AlbumDisplayFilter.All -> stringResource(R.string.albums_filter_all)
                AlbumDisplayFilter.Cloud -> stringResource(R.string.albums_filter_cloud)
                AlbumDisplayFilter.Local -> stringResource(R.string.albums_filter_local)
            }
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .background(if (active) AppColors.current.chipSelectedBg else PillBg, pillShape)
                    .border(0.5.dp, if (active) Accent else PillBorder, pillShape)
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Label part cycles to the next narrowing on tap.
                Row(
                    modifier = Modifier
                        .clickable {
                            val next = AlbumDisplayFilter.entries[
                                (selectedFilter.ordinal + 1) % AlbumDisplayFilter.entries.size
                            ]
                            onFilterSelected(next)
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(label, color = if (active) FgPrimary else FgDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                // Hairline separator + filter button, mirroring the timeline All pill. Opens the
                // sheet where the default filter and the remember-last toggle are set.
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .width(0.5.dp)
                        .background(if (active) Accent.copy(alpha = 0.4f) else PillBorder),
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onOpenSheet() }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.FilterList,
                        stringResource(R.string.albums_filter_sheet_title),
                        tint = if (active) Accent else FgDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        // Hidden albums: compact icon button, matching the other icon pills.
        item(key = "hidden") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(PillBg, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .clickable { onHiddenAlbumClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Lock, hiddenLabel, tint = FgDim, modifier = Modifier.size(15.dp))
            }
        }
        // New album: compact icon button that opens the create-album dialog. A local-only session
        // has no cloud to create an album in, so the pill is present only when signed in.
        if (isSignedIn) {
            item(key = "new_album") {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(PillBg, pillShape)
                        .border(0.5.dp, PillBorder, pillShape)
                        .clickable { onNewAlbumClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.CreateNewFolder, newAlbumLabel, tint = FgDim, modifier = Modifier.size(17.dp))
                }
            }
        }
        // New folder: the logged-out counterpart in the same spot. A local-only session has no cloud
        // album to create, but it can make a real device folder from picked photos; the in-place move
        // that fills it is a scoped-storage write, so the pill needs Android 10+.
        if (!isSignedIn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            item(key = "new_local_folder") {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(PillBg, pillShape)
                        .border(0.5.dp, PillBorder, pillShape)
                        .clickable { onNewLocalFolder() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.CreateNewFolder, newFolderLabel, tint = FgDim, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

// ── Storage color helper ──────────────────────────────────────────────────────

/** Returns green / amber / red based on storage fill ratio. */
private fun storageArcColor(fraction: Float): Color = when {
    fraction < 0.70f -> Color(0xFF30D158)   // green
    fraction < 0.90f -> Color(0xFFFF9F0A)   // amber
    else             -> Color(0xFFFF453A)   // red
}

// ── Avatar button ─────────────────────────────────────────────────────────────

@Composable
internal fun AvatarButton(
    initial: String,
    storageFraction: Float,
    isSyncing: Boolean,
    hasActiveUpload: Boolean = false,
    hasActiveDownload: Boolean = false,
    isOffline: Boolean = false,
    updateAvailable: Boolean = false,
    newsUnread: Boolean = false,
    onClick: () -> Unit,
    onUpdateClick: () -> Unit = onClick,
    onUploadClick: () -> Unit = onClick,
    onDownloadClick: () -> Unit = onClick,
) {
    // Animate the arc smoothly when storage data first loads
    val animatedFraction by animateFloatAsState(
        targetValue = storageFraction,
        animationSpec = tween(durationMillis = 800),
        label = "storage_arc",
    )
    val arcColor   = storageArcColor(storageFraction)
    val trackColor = ArcTrack
    val pillBgColor = PillBg

    val transferActive = hasActiveUpload || hasActiveDownload
    val ringActive = isSyncing || transferActive
    // Status glyphs (update / offline) surface only when no upload/download/sync runs: an active
    // process always wins the pill, matching the requested precedence.
    val showStatus = !transferActive && !isSyncing && (updateAvailable || isOffline)
    // Green while photos actually move (issue #57); a plain sync keeps the existing blue.
    val ringColor = if (transferActive) Color(0xFF34D399) else Color(0xFF60AFFF)
    // The pill grows left to fit however many glyphs sit beside the avatar.
    val leftGlyphs = when {
        transferActive -> (if (hasActiveUpload) 1 else 0) + (if (hasActiveDownload) 1 else 0)
        showStatus -> (if (updateAvailable) 1 else 0) + (if (isOffline) 1 else 0)
        else -> 0
    }
    val pillWidth by animateDpAsState(
        targetValue = 46.dp + (leftGlyphs * 28).dp,
        animationSpec = tween(durationMillis = 320),
        label = "avatar_pill_width",
    )

    // Spinning ring: rotates continuously while syncing or transferring
    val infiniteTransition = rememberInfiniteTransition(label = "sync_spin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
        ),
        label = "spin_angle",
    )

    Box(
        modifier = Modifier
            .height(46.dp)
            .width(pillWidth)
            .drawBehind {
                val strokePx = 2.6.dp.toPx()
                val h = size.height
                val radius = h / 2f
                // Pill fill + border track around the whole rounded rectangle.
                drawRoundRect(color = pillBgColor, cornerRadius = CornerRadius(radius, radius))
                drawRoundRect(
                    color = trackColor,
                    topLeft = androidx.compose.ui.geometry.Offset(strokePx / 2f, strokePx / 2f),
                    size = Size(size.width - strokePx, h - strokePx),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = strokePx),
                )
                // Idle storage arc lives on the avatar-end circle; the active ring is a comet that
                // travels the whole pill outline, so the spin follows the widened shape.
                val arcRect = Size(h - strokePx, h - strokePx)
                val arcTopLeft = androidx.compose.ui.geometry.Offset(size.width - h + strokePx / 2f, strokePx / 2f)
                if (ringActive) {
                    val inset = strokePx / 2f
                    val outline = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                left = inset, top = inset,
                                right = size.width - inset, bottom = h - inset,
                                radiusX = radius, radiusY = radius,
                            ),
                        )
                    }
                    val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(outline, true) }
                    val len = measure.length
                    val cometLen = len * 0.32f
                    val startD = (spinAngle / 360f) * len
                    val comet = androidx.compose.ui.graphics.Path()
                    measure.getSegment(startD, minOf(startD + cometLen, len), comet, true)
                    if (startD + cometLen > len) {
                        measure.getSegment(0f, startD + cometLen - len, comet, true)
                    }
                    drawPath(comet, color = ringColor, style = Stroke(width = strokePx, cap = StrokeCap.Round))
                } else if (animatedFraction > 0f) {
                    drawArc(
                        color = arcColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedFraction,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcRect,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
            }
            // Tapping the avatar itself always opens Settings; the status glyphs below carry their
            // own smaller tap targets (update / uploads / downloads) that consume the tap first.
            .clickable(onClick = onClick),
    ) {
        // Left area: transfer arrows always win; otherwise the status glyphs (update / offline).
        if (transferActive) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 15.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasActiveUpload) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = stringResource(R.string.activity_tab_uploads),
                        tint = ringColor,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onUploadClick)
                            .size(13.dp),
                    )
                }
                if (hasActiveDownload) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = stringResource(R.string.activity_tab_downloads),
                        tint = ringColor,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onDownloadClick)
                            .size(13.dp),
                    )
                }
            }
        } else if (showStatus) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 15.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (updateAvailable) {
                    Icon(
                        Icons.Default.NewReleases,
                        contentDescription = null,
                        tint = Accent2,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onUpdateClick)
                            .size(15.dp),
                    )
                }
                if (isOffline) {
                    // Soft red so "no connection" reads at a glance without shouting.
                    Icon(Icons.Default.WifiOff, contentDescription = null, tint = Color(0xFFE57373), modifier = Modifier.size(14.dp))
                }
            }
        }
        // Avatar cluster at the right end: a 46 dp circle region, same layout as before.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(46.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Avatar gradient circle
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(Accent, Accent2),
                            start = Offset(0f, 0f),
                            end   = Offset(80f, 80f),
                        ),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = initial.ifEmpty { "?" },
                    color      = Color.White,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // Gear badge
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomEnd)
                    .background(Bg2, CircleShape)
                    .border(1.dp, PillBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = FgDim,
                    modifier = Modifier.size(8.dp),
                )
            }
            // Unread-news dot. A quiet mark at the opposite corner from the gear, drawn on its own so
            // it never touches the pill's sync/transfer/update precedence beside the avatar.
            if (newsUnread) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.TopEnd)
                        .background(Accent2, CircleShape)
                        .border(1.5.dp, Bg2, CircleShape),
                )
            }
        }
    }
}

// ── Filter rail ───────────────────────────────────────────────────────────────

@Composable
internal fun FilterRail(
    totalCount: Int,
    contentFilter: ContentFilter,
    onSearchClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onClearContentFilter: () -> Unit,
    onOpenTimelineFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isContentFilterActive = contentFilter != ContentFilter()
    val photosLabel = stringResource(R.string.gallery_filter_photos)
    val videosLabel = stringResource(R.string.filter_type_videos)
    val localLabel = stringResource(R.string.filter_sync_local)
    val backedUpLabel = stringResource(R.string.filter_sync_backedup)
    val cloudLabel = stringResource(R.string.filter_sync_cloud)
    val allLabel = stringResource(R.string.gallery_filter_all)
    val filterSummary = remember(contentFilter, photosLabel, videosLabel, localLabel, backedUpLabel, cloudLabel) {
        buildContentFilterSummary(contentFilter, photosLabel, videosLabel, localLabel, backedUpLabel, cloudLabel)
    }

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        // ── All pill — shows content filter summary when active ───────────────
        item(key = "all") {
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .background(
                        if (isContentFilterActive) Accent.copy(alpha = 0.18f) else AppColors.current.filterPillBg,
                        pillShape
                    )
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Summary + count — tap to clear the active content filter (unchanged behaviour).
                Row(
                    modifier = Modifier
                        .clickable(enabled = isContentFilterActive) { onClearContentFilter() }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!isContentFilterActive) {
                        Icon(Icons.Default.Check, null, tint = FgPrimary, modifier = Modifier.size(12.dp))
                    }
                    Text(
                        filterSummary ?: allLabel,
                        color = if (isContentFilterActive) Accent else FgPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (totalCount > 0) {
                        Text(
                            formatCount(totalCount),
                            color = if (isContentFilterActive) Accent.copy(alpha = 0.7f) else FgDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                // Hairline separator + filter button. Opens the content-filter drawer (sync status
                // + date) right here, so it is discoverable from the timeline instead of only from
                // Settings.
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .width(0.5.dp)
                        .background(if (isContentFilterActive) Accent.copy(alpha = 0.4f) else PillBorder),
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onOpenTimelineFilter() }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.FilterList,
                        stringResource(R.string.settings_timeline_filter),
                        tint = if (isContentFilterActive) Accent else FgDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // ── Search button ─────────────────────────────────────────────────────
        item(key = "search_button") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(PillBg, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .clickable { onSearchClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Search, stringResource(R.string.search_title),
                    tint = FgDim, modifier = Modifier.size(18.dp))
            }
        }

    }
}

// ── Category rail ─────────────────────────────────────────────────────────────

/** The canonical Drive-web order, also the fallback when no custom order is saved. */
private val DEFAULT_CATEGORY_ORDER = listOf(
    GalleryFilter.Favorites, GalleryFilter.Screenshots, GalleryFilter.Videos,
    GalleryFilter.LivePhotos, GalleryFilter.Selfies, GalleryFilter.Portraits,
    GalleryFilter.Bursts, GalleryFilter.Panoramas, GalleryFilter.Raw,
    GalleryFilter.Offline,
)

/** Resolve the saved CSV into a category list: saved entries first (in their stored order), then
 *  any default category not yet saved (so a newly added category still appears). Unknown / dropped
 *  names are ignored. */
private fun resolveCategoryOrder(savedCsv: String?): List<GalleryFilter> {
    val saved = savedCsv?.split(',')
        ?.mapNotNull { name -> DEFAULT_CATEGORY_ORDER.firstOrNull { it.name == name } }
        ?: emptyList()
    return saved + DEFAULT_CATEGORY_ORDER.filterNot { it in saved }
}

@Composable
private fun categoryLabel(filter: GalleryFilter): String = when (filter) {
    GalleryFilter.Favorites   -> stringResource(R.string.gallery_filter_favorites)
    GalleryFilter.Screenshots -> stringResource(R.string.gallery_filter_screenshots)
    GalleryFilter.Videos      -> stringResource(R.string.filter_type_videos)
    GalleryFilter.LivePhotos  -> stringResource(R.string.gallery_filter_live_photos)
    GalleryFilter.Selfies     -> stringResource(R.string.gallery_filter_selfies)
    GalleryFilter.Portraits   -> stringResource(R.string.gallery_filter_portraits)
    GalleryFilter.Bursts      -> stringResource(R.string.gallery_filter_bursts)
    GalleryFilter.Panoramas   -> stringResource(R.string.gallery_filter_panoramas)
    GalleryFilter.Raw         -> stringResource(R.string.gallery_filter_raw)
    GalleryFilter.Offline     -> stringResource(R.string.filter_offline)
    else -> filter.name
}

/**
 * Horizontally scrollable row of category chips under the timeline's top rail (Favorites,
 * Screenshots, Videos, Live / Motion, Selfies, Portraits, Bursts, Panoramas, RAW), each with its
 * Drive-web glyph and label. Tapping one filters the timeline to that category; tapping the active
 * one clears back to All. The order is arranged on the Timeline filter settings screen
 * ([CategoryReorderList]) and read here.
 */
@Composable
internal fun CategoryRail(
    selectedFilter: GalleryFilter,
    onFilterSelected: (GalleryFilter) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = pillShape,
) {
    val context = LocalContext.current
    val peopleRail = LocalPeopleRail.current

    val savedCsv by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.CATEGORY_RAIL_ORDER] }
    }.collectAsState(initial = null)
    val order = remember(savedCsv) { resolveCategoryOrder(savedCsv) }

    Column(modifier = modifier) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            itemsIndexed(order, key = { _, f -> f.name }) { _, cat ->
                val selected = selectedFilter == cat
                val chipBg by animateColorAsState(
                    if (selected) Accent.copy(alpha = 0.18f) else PillBg, label = "catChipBg")
                val chipFg by animateColorAsState(if (selected) Accent else FgDim, label = "catChipFg")
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(shape)
                        .background(chipBg, shape)
                        .then(if (!selected) Modifier.border(0.5.dp, PillBorder, shape) else Modifier)
                        .clickable { onFilterSelected(if (selected) GalleryFilter.All else cat) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GalleryCategoryIcon(cat, tint = chipFg)
                    Text(
                        categoryLabel(cat),
                        color = chipFg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            // People chip: present only once at least one person is indexed (which already implies
            // the AI features are on). Toggles the face bar below; highlighted while it is open.
            if (peopleRail.people.isNotEmpty()) {
                item(key = "people") {
                    val selected = peopleRail.active
                    val chipBg by animateColorAsState(
                        if (selected) Accent.copy(alpha = 0.18f) else PillBg, label = "peopleChipBg")
                    val chipFg by animateColorAsState(if (selected) Accent else FgDim, label = "peopleChipFg")
                    Row(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(shape)
                            .background(chipBg, shape)
                            .then(if (!selected) Modifier.border(0.5.dp, PillBorder, shape) else Modifier)
                            .clickable { peopleRail.onToggle() }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.Face,
                            contentDescription = null,
                            tint = chipFg,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            stringResource(R.string.gallery_category_people),
                            color = chipFg,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
        if (peopleRail.active && peopleRail.people.isNotEmpty()) {
            PeopleBar(
                people = peopleRail.people,
                selectedPersonId = peopleRail.selectedPersonId,
                onPersonSelected = peopleRail.onPersonSelected,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}

/**
 * Horizontal row of round face thumbnails, one per indexed [PersonUi], shown under the category rail
 * when the People chip is open. Tapping a face filters the timeline to that person; the selected
 * face carries an accent ring. The list is small (cover references only) and the tiles decode at a
 * low target size, so this stays memory-light on the timeline screen.
 */
@Composable
private fun PeopleBar(
    people: List<PersonUi>,
    selectedPersonId: Long?,
    onPersonSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        items(people, key = { it.personId }) { person ->
            PersonTile(
                person = person,
                selected = person.personId == selectedPersonId,
                onClick = { onPersonSelected(person.personId) },
            )
        }
    }
}

/** One round face tile: the cover photo's thumbnail cropped to the face box inside a circle, with the
 *  person's name below when one is set. Reuses the timeline's linkId → decrypted-thumbnail resolution
 *  ([LocalThumbnailUrls]); a local cover falls back to its own content uri. */
@Composable
internal fun PersonTile(
    person: PersonUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val model = LocalThumbnailUrls.current.value[person.coverPhotoKey] ?: person.coverPhotoKey
    val request = remember(model, person.faceBox) {
        ImageRequest.Builder(context)
            .data(model)
            .size(FACE_TILE_PX)
            .crossfade(false)
            .apply { person.faceBox?.let { transformations(FaceCropTransformation(it)) } }
            .build()
    }
    Column(
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Bg2)
                .then(if (selected) Modifier.border(2.dp, Accent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = request,
                contentDescription = person.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
        val name = person.displayName?.takeIf { it.isNotBlank() }
        if (name != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                name,
                color = if (selected) Accent else FgDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Album-style People card: the person's face-cropped cover filling a rounded rectangle, captioned with
 * the name and a photo count, mirroring the memories [SeasonCard] (same aspect, corners, background and
 * caption treatment) but for a person. Fills its grid cell width. Reuses [PersonTile]'s cover resolution
 * ([LocalThumbnailUrls] keyed by coverPhotoKey) and the same [FaceCropTransformation], clipped to the
 * card corners instead of a circle; an unresolved cover falls back to the card's [Bg2] background.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PersonCard(
    person: PersonUi,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    val context = LocalContext.current
    val model = LocalThumbnailUrls.current.value[person.coverPhotoKey] ?: person.coverPhotoKey
    val request = remember(model, person.faceBox) {
        ImageRequest.Builder(context)
            .data(model)
            .size(FACE_CARD_PX)
            .crossfade(false)
            .apply { person.faceBox?.let { transformations(FaceCropTransformation(it)) } }
            .build()
    }
    val name = when {
        person.isOther -> stringResource(R.string.person_unsorted)
        else -> person.displayName ?: stringResource(R.string.person_detail_unnamed)
    }
    val countLabel = pluralStringResource(
        R.plurals.count_photos_plural, person.faceCount, person.faceCount,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(132f / 168f)
            .clip(RoundedCornerShape(16.dp))
            .background(Bg2)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = request,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Bottom gradient so the caption stays legible over bright covers.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.66f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = countLabel,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (selected) {
            Box(modifier = Modifier.matchParentSize().background(Accent.copy(alpha = 0.30f)))
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** Decode width for the small circular face tile (56dp). The crop keeps only the face region, so the
 *  source is decoded well above the tile's pixel size to leave the cropped face sharp. */
private const val FACE_TILE_PX = 320

/** Decode width for the larger album-style [PersonCard]. The face box is a fraction of the frame, so
 *  cropping it out of a small decode would upscale a tiny region; a generous source keeps the card as
 *  crisp as the uncropped Season card (a cloud cover is still capped by its thumbnail's own size). */
private const val FACE_CARD_PX = 1024

/**
 * Crops a Coil-decoded cover thumbnail to a square around the stored face box (given as fractions of
 * the image, so it is correct at any decode resolution), with a little padding, so a tile reads as a
 * face portrait rather than the whole photo. The output square is clipped to a circle by the tile.
 */
internal class FaceCropTransformation(private val box: FaceBox) : Transformation {
    override val cacheKey: String = "face:${box.left},${box.top},${box.right},${box.bottom}"

    override suspend fun transform(input: Bitmap, size: coil.size.Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0) return input
        val faceW = (box.right - box.left) * w
        val faceH = (box.bottom - box.top) * h
        val cx = ((box.left + box.right) / 2f) * w
        val cy = ((box.top + box.bottom) / 2f) * h
        val side = (maxOf(faceW, faceH) * 1.4f).coerceIn(1f, minOf(w, h).toFloat())
        val half = side / 2f
        val left = (cx - half).roundToInt().coerceIn(0, w - 1)
        val top = (cy - half).roundToInt().coerceIn(0, h - 1)
        val s = side.roundToInt().coerceIn(1, minOf(w - left, h - top))
        return Bitmap.createBitmap(input, left, top, s, s)
    }

    override fun equals(other: Any?): Boolean = other is FaceCropTransformation && other.box == box
    override fun hashCode(): Int = box.hashCode()
}

/**
 * Arrow-driven reorder list for the timeline category bar, shown on the Timeline filter settings
 * screen. Each row carries the category's glyph + label with a drag handle; dragging a row lifts
 * it to follow the finger and reorders the list live, then rewrites the persisted
 * [SettingsKeys.CATEGORY_RAIL_ORDER] so [CategoryRail] reflects the new order.
 */
@Composable
internal fun CategoryReorderList(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AppColors.current

    val savedCsv by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.CATEGORY_RAIL_ORDER] }
    }.collectAsState(initial = null)
    var order by remember { mutableStateOf<List<GalleryFilter>>(emptyList()) }
    LaunchedEffect(savedCsv) { order = resolveCategoryOrder(savedCsv) }

    fun persistOrder() {
        val snapshot = order
        scope.launch {
            runCatching {
                context.settingsDataStore.edit {
                    it[SettingsKeys.CATEGORY_RAIL_ORDER] = snapshot.joinToString(",") { f -> f.name }
                }
            }
        }
    }

    var draggedCat by remember { mutableStateOf<GalleryFilter?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    Column(modifier = modifier) {
        order.forEachIndexed { index, cat ->
            key(cat) {
                val isDragged = cat == draggedCat
                Row(
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffsetY else 0f }
                        .fillMaxWidth()
                        .background(
                            if (isDragged) colors.bg2 else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GalleryCategoryIcon(cat, tint = FgDim)
                    Text(
                        categoryLabel(cat),
                        color = FgPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    // Drag handle — grab and drag; the row lifts and follows the finger while the
                    // list reorders live as it passes neighbours. Keyed on the item so the gesture
                    // survives the reorder recompositions mid-drag.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .pointerInput(Unit) {
                                val rowH = 52.dp.toPx()
                                detectDragGestures(
                                    onDragStart = { draggedCat = cat; dragOffsetY = 0f },
                                    onDragEnd = { draggedCat = null; dragOffsetY = 0f; persistOrder() },
                                    onDragCancel = { draggedCat = null; dragOffsetY = 0f },
                                ) { change, drag ->
                                    change.consume()
                                    dragOffsetY += drag.y
                                    val cur = order.indexOf(cat)
                                    if (cur < 0) return@detectDragGestures
                                    if (dragOffsetY > rowH / 2 && cur < order.lastIndex) {
                                        order = order.toMutableList().apply { add(cur + 1, removeAt(cur)) }
                                        dragOffsetY -= rowH
                                    } else if (dragOffsetY < -rowH / 2 && cur > 0) {
                                        order = order.toMutableList().apply { add(cur - 1, removeAt(cur)) }
                                        dragOffsetY += rowH
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = null,
                            tint = FgDim,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            if (index < order.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 44.dp),
                    thickness = 0.5.dp,
                    color = colors.cardBorder,
                )
            }
        }
    }
}

// ── Bottom dock ───────────────────────────────────────────────────────────────

@Composable
internal fun BottomDock(position: Float, onTabSelected: (Int) -> Unit, showShared: Boolean = true) {
    val density = LocalDensity.current
    // Each tab's measured left offset + size (labels differ in width), so the single highlight can slide
    // to the selected one instead of the fill just snapping between tabs.
    val tabX = remember { mutableStateListOf(0f, 0f, 0f) }
    val tabW = remember { mutableStateListOf(0f, 0f, 0f) }
    var tabH by remember { mutableFloatStateOf(0f) }
    val selectedTab = position.roundToInt().coerceIn(0, 2)
    // Drive the highlight off the pager's LIVE fractional position, interpolating between the two tabs it
    // sits over, so it tracks a swiping finger the whole way (and a tap, which the pager animates) rather
    // than only sliding once the page has settled.
    val lower = position.toInt().coerceIn(0, 2)
    val upper = (lower + 1).coerceAtMost(2)
    val frac = (position - lower).coerceIn(0f, 1f)
    val hlX = lerp(tabX[lower], tabX[upper], frac)
    val hlW = lerp(tabW[lower], tabW[upper], frac)
    Box(
        modifier = Modifier
            .background(PillBgOpaque, pillShape)
            .border(0.5.dp, PillBorder, pillShape)
            .padding(4.dp),
    ) {
        // The sliding fill, drawn behind the tab row and animated to the active tab's bounds.
        if (hlW > 0f && tabH > 0f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(hlX.roundToInt(), 0) }
                    .size(width = with(density) { hlW.toDp() }, height = with(density) { tabH.toDp() })
                    .background(Accent.copy(alpha = 0.18f), RoundedCornerShape(999.dp)),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DockTab(Icons.Default.Photo, stringResource(R.string.gallery_tab_photos), selectedTab == 0, { onTabSelected(0) }) { x, w, h -> tabX[0] = x; tabW[0] = w; tabH = h }
            DockTab(Icons.Default.Collections, stringResource(R.string.gallery_tab_albums), selectedTab == 1, { onTabSelected(1) }) { x, w, h -> tabX[1] = x; tabW[1] = w; tabH = h }
            // Cloud-only: a local-only session omits the Shared tab, leaving a two-tab control.
            if (showShared) {
                DockTab(Icons.Default.Share, stringResource(R.string.gallery_tab_shared), selectedTab == 2, { onTabSelected(2) }) { x, w, h -> tabX[2] = x; tabW[2] = w; tabH = h }
            }
        }
    }
}

@Composable
private fun DockTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onBounds: (x: Float, width: Float, height: Float) -> Unit,
) {
    // Compact padding + maxLines/softWrap=false so the labels never wrap onto two lines
    // on narrow screens (6.1"-class and smaller screens). The text shrinks to
    // ellipsis if a localised label is unusually long instead of breaking the pill.
    // No own background: the shared sliding highlight in BottomDock fills the active tab. Just report
    // this tab's position and size so the highlight can animate to it, and animate the icon/label colour.
    val tabFg by animateColorAsState(if (selected) Accent else FgDim, label = "dockTabFg")
    Row(
        modifier = Modifier
            .onGloballyPositioned { c ->
                onBounds(c.positionInParent().x, c.size.width.toFloat(), c.size.height.toFloat())
            }
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tabFg,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = tabFg,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Shared filter rail ────────────────────────────────────────────────────────

enum class SharedFilter { SharedWithMe, SharedByMe }

/** Albums-tab view filter. All shows cloud albums + device folders in one common stream; Cloud
 *  and Local narrow to one kind. Picked from the filter sheet opened by [AlbumsFilterRail]. */
enum class AlbumDisplayFilter { All, Cloud, Local }

@Composable
internal fun SharedFilterRail(
    selectedFilter: SharedFilter,
    onFilterSelected: (SharedFilter) -> Unit,
    activeEmailFilter: String? = null,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        // Single toggle pill — tap flips between "with me" and "by me". Direction icon
        // makes the active scope obvious (incoming arrow for with-me, outgoing for by-me).
        item(key = "shared_toggle") {
            val isWithMe = selectedFilter == SharedFilter.SharedWithMe
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .background(AppColors.current.chipSelectedBg, pillShape)
                    .clickable {
                        onFilterSelected(
                            if (isWithMe) SharedFilter.SharedByMe else SharedFilter.SharedWithMe,
                        )
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (isWithMe) Icons.AutoMirrored.Filled.CallReceived
                    else Icons.AutoMirrored.Filled.CallMade,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    stringResource(
                        if (isWithMe) R.string.share_shared_with_me
                        else R.string.share_shared_by_me,
                    ),
                    color = FgPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = FgDim,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        // Filter button — highlighted when an email filter is active
        item(key = "filter_button") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(if (activeEmailFilter != null) Accent.copy(alpha = 0.15f) else PillBg, pillShape)
                    .border(0.5.dp, if (activeEmailFilter != null) Accent else PillBorder, pillShape)
                    .clickable { onFilterClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.FilterList, stringResource(R.string.share_filter_account),
                    tint = if (activeEmailFilter != null) Accent else FgDim,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}
