@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.akoos.photos.presentation.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.calendar.components.MonthGrid
import eu.akoos.photos.presentation.calendar.components.MonthPager
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.PillBorder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Top-level Calendar view. Three layered surfaces:
 *  - [SearchResultsGrid] when a search query is active,
 *  - [MonthPager] when the user picks the "per-month swipe" view,
 *  - [LazyColumn] of [MonthGrid]s otherwise (the default stacked layout).
 *
 * The floating header reuses the same back-pill recipe as the rest of the app and
 * adds three trailing affordances (jump-to-month, search, view-toggle) styled to
 * match. Tapping a populated day routes to the Day Detail screen via [onDayClick].
 */
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    onDayClick: (date: String) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    // Hold the "jump to this page" target locally and feed it into the pager. The VM
    // only knows about month identity (year/month); the pager wants a flat index.
    var pagerTargetPage by remember { mutableStateOf<Int?>(null) }
    // Also remember a target month for the STACKED layout so the LazyColumn can scroll
    // to it. Cleared after the scroll is performed.
    var stackedTargetIndex by remember { mutableStateOf<Int?>(null) }
    val stackedListState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(stackedTargetIndex, state.viewMode) {
        val idx = stackedTargetIndex ?: return@LaunchedEffect
        if (state.viewMode == CalendarViewMode.Stacked && idx in state.months.indices) {
            stackedListState.animateScrollToItem(idx)
        }
        stackedTargetIndex = null
    }

    // System back unwinds the in-bar surfaces in order before letting the nav pop.
    BackHandler(enabled = state.isJumpPickerVisible || state.isSearchActive) {
        when {
            state.isJumpPickerVisible -> viewModel.setJumpPickerVisible(false)
            state.isSearchActive -> viewModel.setSearchActive(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg0),
    ) {
        // ───────────── Main content area ─────────────
        // We always reserve enough space at the top for the floating header — that's
        // why the content's first padding line includes the search bar height when the
        // bar is open.
        val contentTopPad = when {
            // 56 (status approx) + 56 (header) + 64 (search field) + 32 (16dp breathing
            // room above & below the field per the polish pass) + 8 (slack) when search
            // is open; otherwise just the header zone.
            state.isSearchActive -> 156.dp
            else -> 76.dp
        }
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
            state.isSearchActive && state.searchQuery.isNotBlank() -> {
                SearchResultsArea(
                    contentTopPad = contentTopPad,
                    state = state,
                    onDayClick = { day -> onDayClick(day.date) },
                )
            }
            state.viewMode == CalendarViewMode.PerMonth -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = contentTopPad),
                ) {
                    MonthPager(
                        months = state.months,
                        targetPage = pagerTargetPage,
                        onTargetConsumed = { pagerTargetPage = null },
                        onDayClick = { day -> onDayClick(day.date) },
                    )
                }
            }
            else -> {
                // Stacked vertical scroll — the legacy default. `contentTopPad` ensures
                // the first month header doesn't slip under the floating bar.
                LazyColumn(
                    state = stackedListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = contentTopPad, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(
                        items = state.months,
                        key = { bucket -> "${bucket.year}-${bucket.month}" },
                    ) { bucket ->
                        // Each month tile takes a full screen so the day cells in stacked
                        // mode have the same generous sizing as PerMonth — the user wanted
                        // the wider-day layout applied to both modes. fillParentMaxHeight
                        // gives the inner expanded grid a concrete height to weight against.
                        // No "no photos this month" caption — empty grids speak for
                        // themselves and the extra line of text broke the per-month
                        // height rhythm when scrolling.
                        Box(modifier = Modifier.fillParentMaxHeight()) {
                            MonthGrid(
                                bucket = bucket,
                                onDayClick = { day -> onDayClick(day.date) },
                                expanded = true,
                            )
                        }
                    }
                }
            }
        }

        // ───────────── Floating header column ─────────────
        // The top bar + (optional) search field + (optional) jump-picker dropdown all
        // live in this column, layered above the scroll area. They share a column so
        // the content padding above stays a stable height regardless of which surface
        // is open.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(colors.bg0),
        ) {
            CalendarTopBar(
                onBack = onBack,
                isSearchActive = state.isSearchActive,
                viewMode = state.viewMode,
                onSearchToggle = { viewModel.setSearchActive(!state.isSearchActive) },
                onViewModeToggle = { viewModel.toggleViewMode() },
                onJumpToggle = { viewModel.setJumpPickerVisible(!state.isJumpPickerVisible) },
                isJumpVisible = state.isJumpPickerVisible,
            )

            // Inline search input — slides in below the title bar when the search icon
            // is tapped. Hidden again on close (which also resets the query).
            AnimatedVisibility(
                visible = state.isSearchActive,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                CalendarSearchField(
                    query = state.searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    onClear = { viewModel.setSearchQuery("") },
                )
            }

            // Jump-to-month picker overlay — a compact year + month picker that lives
            // directly below the header. Tapping a month dispatches to whichever view
            // mode is current.
            AnimatedVisibility(
                visible = state.isJumpPickerVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                JumpToMonthPicker(
                    months = state.months,
                    onMonthPicked = { yearMonth ->
                        val idx = state.months.indexOfFirst {
                            it.year == yearMonth.year && it.month == yearMonth.month
                        }
                        if (idx >= 0) {
                            if (state.viewMode == CalendarViewMode.PerMonth) {
                                pagerTargetPage = idx
                            } else {
                                stackedTargetIndex = idx
                            }
                        }
                        viewModel.setJumpPickerVisible(false)
                    },
                    onDismiss = { viewModel.setJumpPickerVisible(false) },
                )
            }
        }
    }
}

/**
 * Top bar — back chevron on the LEFT, title next to it, then three trailing actions
 * (jump-to-month, search, view-mode toggle). Padding mirrors [SearchScreen]'s top bar
 * so all the back-nav screens feel like cousins.
 */
@Composable
private fun CalendarTopBar(
    onBack: () -> Unit,
    isSearchActive: Boolean,
    viewMode: CalendarViewMode,
    onSearchToggle: () -> Unit,
    onViewModeToggle: () -> Unit,
    onJumpToggle: () -> Unit,
    isJumpVisible: Boolean,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back chevron — left-aligned, 40dp tap target, same as SearchScreen.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = colors.fgPrimary,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.calendar_title),
            color = colors.fgPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Jump-to-month action — same pill style as the back chevron, no fill.
        IconBtn(
            icon = if (isJumpVisible) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(R.string.calendar_jump_cd),
            onClick = onJumpToggle,
            tint = colors.fgPrimary,
        )
        Spacer(modifier = Modifier.width(4.dp))
        // Search action — flips to Close when the search bar is open so the user has a
        // way out without using system back.
        IconBtn(
            icon = if (isSearchActive) Icons.Filled.Close else Icons.Filled.Search,
            contentDescription = stringResource(R.string.calendar_search_cd),
            onClick = onSearchToggle,
            tint = colors.fgPrimary,
        )
        Spacer(modifier = Modifier.width(4.dp))
        // View mode toggle — calendar icon means "per month pager", grid means "stacked
        // view". The icon represents the OTHER mode the user could switch to.
        IconBtn(
            icon = if (viewMode == CalendarViewMode.Stacked) {
                Icons.Filled.CalendarMonth
            } else {
                Icons.Filled.ViewModule
            },
            contentDescription = stringResource(R.string.calendar_view_toggle_cd),
            onClick = onViewModeToggle,
            tint = colors.fgPrimary,
        )
    }
}

/**
 * Inline search text field. Mirrors the [SearchScreen]'s OutlinedTextField recipe so
 * the two surfaces feel consistent — same shape, border, accent cursor, leading magnifier.
 */
@Composable
private fun CalendarSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = AppColors.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                text = stringResource(R.string.calendar_search_placeholder),
                color = colors.fgMute,
            )
        },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = colors.fgDim,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = colors.fgDim,
                    )
                }
            }
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.line,
            focusedTextColor = colors.fgPrimary,
            unfocusedTextColor = colors.fgPrimary,
            cursorColor = colors.accent,
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            // 16dp of vertical breathing room above + below the field so it doesn't sit
            // flush against the top bar or the photo grid underneath.
            .padding(horizontal = 14.dp, vertical = 16.dp),
    )
}

/**
 * Tiny circular icon button reused across the top bar — matches the back-pill size + tap
 * target so the row feels evenly weighted.
 */
@Composable
private fun IconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}

/**
 * Year/month picker shown below the top bar when the jump action is tapped. Renders
 * each (year, month) bucket as a small pill — the active months in the user's library
 * — and dispatches a picked month to the parent for scrolling.
 */
@Composable
private fun JumpToMonthPicker(
    months: List<MonthBucket>,
    onMonthPicked: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    if (months.isEmpty()) return
    val years = remember(months) {
        months.map { it.year }.distinct().sortedDescending()
    }
    // Default to the newest year (which is also the first month bucket).
    var selectedYear by remember(years) {
        mutableIntStateOf(years.firstOrNull() ?: Calendar.getInstance().get(Calendar.YEAR))
    }
    val monthsForYear = remember(selectedYear, months) {
        months.filter { it.year == selectedYear }
            .sortedByDescending { it.month }
    }
    val monthFormat = remember { SimpleDateFormat("MMM", Locale.getDefault()) }
    val monthFmtCal = remember { Calendar.getInstance() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.calendar_jump_title),
                color = colors.fgPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = colors.fgDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Year row — horizontal pills, tap to filter the month strip below.
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(years) { year ->
                val selected = year == selectedYear
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) colors.chipSelectedBg else colors.chipUnselectedBg)
                        .border(0.5.dp, if (selected) colors.accent.copy(alpha = 0.6f) else colors.pillBorder, RoundedCornerShape(50))
                        .clickable { selectedYear = year }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = year.toString(),
                        color = if (selected) colors.fgPrimary else colors.fgDim,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Month row — only renders the months in the selected year that have photos,
        // so the user can't pick an empty bucket.
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(monthsForYear) { bucket ->
                val label = remember(bucket.year, bucket.month) {
                    monthFmtCal.set(bucket.year, bucket.month - 1, 1)
                    monthFormat.format(monthFmtCal.time)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.chipUnselectedBg)
                        .border(0.5.dp, colors.pillBorder, RoundedCornerShape(50))
                        .clickable {
                            onMonthPicked(YearMonth(bucket.year, bucket.month))
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = label,
                        color = colors.fgPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

private data class YearMonth(val year: Int, val month: Int)

/**
 * Search results area — a 3-column grid of matching DAY tiles. Each tile is a
 * miniature day-cell (cover photo + day number + optional location pin) sized to the
 * grid columns. Tapping a tile opens the corresponding Day Detail screen.
 */
@Composable
private fun SearchResultsArea(
    contentTopPad: androidx.compose.ui.unit.Dp,
    state: CalendarUiState,
    onDayClick: (DayBucket) -> Unit,
) {
    val colors = AppColors.current
    val results = state.searchResults
    val isLoading = state.isSearchLoading
    when {
        isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = contentTopPad),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = colors.accent)
            }
        }
        results.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = contentTopPad),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.calendar_search_no_results),
                    color = colors.fgMute,
                    fontSize = 14.sp,
                )
            }
        }
        else -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    start = 6.dp, end = 6.dp, top = contentTopPad, bottom = 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                items(results, key = { "search-${it.date}" }) { day ->
                    SearchDayTile(day = day, onClick = { onDayClick(day) })
                }
            }
        }
    }
}

/**
 * Standalone day tile used in the search results grid. Inlines the same visual recipe
 * as [components.MonthGrid]'s DayCell — cover photo with bottom gradient + day number
 * + (optional) location pin — but adds the full ISO date underneath for context, so
 * the user can disambiguate "May 3 2024" from "May 3 2023" in the results list.
 */
@Composable
private fun SearchDayTile(
    day: DayBucket,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    val shape = RoundedCornerShape(10.dp)
    val cal = remember(day.date) {
        Calendar.getInstance().apply {
            val parts = day.date.split('-')
            if (parts.size == 3) {
                set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
            }
        }
    }
    val dayNumber = cal.get(Calendar.DAY_OF_MONTH)
    val dateLabel = remember(day.date) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(cal.time)
    }
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(Color.Black)
                .border(0.5.dp, PillBorder, shape)
                .clickable(onClick = onClick),
        ) {
            val coverModel: Any? = when (val item = day.coverItem) {
                is GalleryItem.LocalOnly -> android.net.Uri.parse(item.local.uri)
                is GalleryItem.Synced    -> android.net.Uri.parse(item.local.uri)
                is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl
            }
            AsyncImage(
                model = coverModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.35f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.55f),
                            ),
                        ),
                    ),
            )
            Text(
                text = dayNumber.toString(),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 4.dp),
            )
            if (!day.locationText.isNullOrBlank()) {
                Icon(
                    Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(14.dp),
                )
            }
        }
        Text(
            text = dateLabel,
            color = colors.fgDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 2.dp),
        )
    }
}
