@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package eu.akoos.photos.presentation.calendar.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.calendar.DayBucket
import eu.akoos.photos.presentation.calendar.MonthBucket
import eu.akoos.photos.presentation.theme.AppColors

/**
 * Single-month-per-page calendar layout — used when the user toggles View B in the top
 * bar. Each page is one [MonthBucket] rendered full-width with the same [MonthGrid]
 * that the stacked layout uses, so the day-cell visuals stay consistent across modes.
 *
 * Why a [HorizontalPager] instead of a [LazyRow]: pager gives us page snapping for free
 * (the user always lands cleanly on one month), plus the page-change callback we use to
 * jump to a specific month from the year/month picker. The initial page is held in a
 * [rememberSaveable] so a rotation doesn't reset the user back to "current month".
 *
 * [targetPage] lets the parent imperatively jump (e.g. from a year/month picker). We
 * react to its changes via [LaunchedEffect] and run an animated scroll on the pager.
 */
@Composable
fun MonthPager(
    months: List<MonthBucket>,
    targetPage: Int?,
    onTargetConsumed: () -> Unit,
    onDayClick: (DayBucket) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    if (months.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.calendar_no_photos),
                color = colors.fgMute,
                fontSize = 13.sp,
            )
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { months.size },
    )

    // Honour parent-driven jumps. The parent flips `targetPage` to a non-null index, we
    // animate-scroll once, then notify so the parent can null it back out.
    LaunchedEffect(targetPage) {
        val target = targetPage ?: return@LaunchedEffect
        if (target in months.indices && target != pagerState.currentPage) {
            pagerState.animateScrollToPage(target)
        }
        onTargetConsumed()
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        // Tight side padding so the user can preview the edge of the next month, hinting
        // at the swipe affordance without giving away too much horizontal real estate.
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) { page ->
        val bucket = months[page]
        // Each page fills the pager exactly — we pass `expanded = true` so the grid's
        // six week rows divide the remaining vertical space (after the month title /
        // weekday strip) equally. Bottom navigation-bars padding keeps the last row off
        // the system gesture area, and a small bottom inset gives the grid room to breathe
        // matching the 16dp brief.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            if (bucket.days.isEmpty()) {
                // Still render the grid (so the user sees the month/weekday header and the
                // empty-day cells) but stack a small explanatory line beneath it. We weight
                // the grid so the explanatory line keeps its natural height at the bottom.
                Box(modifier = Modifier.weight(1f)) {
                    MonthGrid(
                        bucket = bucket,
                        onDayClick = onDayClick,
                        expanded = true,
                    )
                }
                Text(
                    text = stringResource(R.string.calendar_no_photos),
                    color = colors.fgMute,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            } else {
                MonthGrid(
                    bucket = bucket,
                    onDayClick = onDayClick,
                    expanded = true,
                )
            }
        }
    }
}
