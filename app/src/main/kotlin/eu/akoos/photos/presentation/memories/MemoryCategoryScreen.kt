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

package eu.akoos.photos.presentation.memories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * "See all" sub-page for a single [MemoryCategory]. Same floating back-bar scaffold and theming as
 * [MemoriesScreen], titled with the category name, with every card of that category laid out as a
 * two-column album-style grid. Reuses the shared [MemoriesViewModel] — its data is cached, so
 * re-deriving here is free. Each card routes its photos into the shared viewer via [onPhotoClick].
 * The Seasons page adds a newest/oldest-first sort toggle.
 */
@Composable
fun MemoryCategoryScreen(
    category: MemoryCategory,
    onBack: () -> Unit,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    onSwitchCategory: (MemoryCategory) -> Unit = {},
    viewModel: MemoriesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    val title = stringResource(
        when (category) {
            MemoryCategory.ON_THIS_DAY -> R.string.gallery_on_this_day
            MemoryCategory.SEASONS -> R.string.memories_seasons
        },
    )
    // The other categories, surfaced as a dropdown on the title pill for quick switching.
    val otdLabel = stringResource(R.string.gallery_on_this_day)
    val seasonsLabel = stringResource(R.string.memories_seasons)
    val switchMenu = MemoryCategory.values().filter { it != category }.map { other ->
        val label = when (other) {
            MemoryCategory.ON_THIS_DAY -> otdLabel
            MemoryCategory.SEASONS -> seasonsLabel
        }
        label to { onSwitchCategory(other) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg0),
    ) {
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // Extra gap below the floating pills so the first row isn't crowded against them.
        val contentTopPad = statusBarTop + 72.dp

        val now = remember { Calendar.getInstance().get(Calendar.YEAR) }
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        val showScrollTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 6 } }

        // Seasons can be flipped oldest-first; the VM hands them over newest-first.
        var sortNewestFirst by remember { mutableStateOf(true) }
        val seasons = if (sortNewestFirst) state.seasons else state.seasons.asReversed()
        // Reversing keeps the same scroll index but the item under it changes, which reads as the
        // view "jumping" — snap back to the top whenever the sort flips so it stays put.
        LaunchedEffect(sortNewestFirst) { gridState.scrollToItem(0) }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 14.dp, end = 14.dp, top = contentTopPad, bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (category) {
                MemoryCategory.ON_THIS_DAY -> {
                    items(state.onThisDay, key = { "otd_${it.first}" }) { (year, items) ->
                        OnThisDayCard(
                            coverItem = items.first(),
                            yearsAgo = (now - year).coerceAtLeast(1),
                            count = items.size,
                            onClick = { onPhotoClick(items, 0) },
                        )
                    }
                }
                MemoryCategory.SEASONS -> {
                    // No item key: reversing the SAME items with keys makes the grid chase the
                    // moved first-visible item (it lands at the far end), which the scroll-to-top
                    // then yanks back — two jumps that read as a flicker. Positional items just
                    // re-lay-out in place and the scroll-to-top below keeps it clean.
                    items(seasons) { bucket ->
                        SeasonCard(
                            coverItem = bucket.cover,
                            title = bucket.title,
                            count = bucket.items.size,
                            onClick = { onPhotoClick(bucket.items, 0) },
                        )
                    }
                }
            }
        }

        // Floating pills — back, title, and (Seasons only) the sort toggle, all in the same top row.
        FloatingMemoriesHeader(
            title = title,
            onBack = onBack,
            menuItems = switchMenu,
            trailing = if (category == MemoryCategory.SEASONS) {
                {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(PillBg, RoundedCornerShape(50))
                            .border(0.5.dp, PillBorder, RoundedCornerShape(50))
                            .clickable { sortNewestFirst = !sortNewestFirst }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.SwapVert,
                            contentDescription = null,
                            tint = colors.fgPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            stringResource(
                                if (sortNewestFirst) R.string.sort_newest_first
                                else R.string.sort_oldest_first,
                            ),
                            color = colors.fgPrimary,
                            fontSize = 12.sp,
                        )
                    }
                }
            } else null,
        )

        // Scroll-to-top, once the grid has moved down a bit — mirrors the album screens.
        if (showScrollTop) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(PillBg, CircleShape)
                    .border(0.5.dp, PillBorder, CircleShape)
                    .clickable { scope.launch { gridState.animateScrollToItem(0) } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = colors.fgPrimary,
                )
            }
        }
    }
}
