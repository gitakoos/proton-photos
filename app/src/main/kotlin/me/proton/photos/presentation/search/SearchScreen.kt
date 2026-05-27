@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package me.proton.photos.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import me.proton.photos.presentation.gallery.PhotoCell
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.proton.photos.R
import me.proton.photos.domain.entity.GalleryItem
import me.proton.photos.presentation.gallery.ContentFilter
import me.proton.photos.presentation.gallery.ContentFilterSheet
import me.proton.photos.presentation.gallery.GalleryFilter
import me.proton.photos.presentation.gallery.MediaType
import me.proton.photos.presentation.gallery.SyncStatusFilter
import me.proton.photos.presentation.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onPhotoClick: (List<GalleryItem>, Int) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val filter by vm.contentFilter.collectAsStateWithLifecycle()

    var showFilterSheet by remember { mutableStateOf(false) }
    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg)
            .statusBarsPadding(),
    ) {
        // Top bar: back arrow + screen title.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                text = stringResource(R.string.search_title),
                color = colors.fgPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            placeholder = {
                Text(
                    text = stringResource(R.string.search_placeholder),
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
                            .clickable { vm.setQuery("") },
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
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )

        Spacer(modifier = Modifier.height(6.dp))

        FilterRailRow(
            colors = colors,
            filter = filter,
            onOpenSheet = { showFilterSheet = true },
            onClearMediaType = { vm.setContentFilter(filter.copy(mediaType = MediaType.All)) },
            onClearSyncStatus = { vm.setContentFilter(filter.copy(syncStatus = SyncStatusFilter.All)) },
            onClearYear = { vm.setContentFilter(filter.copy(year = null, month = null)) },
            onClearMonth = { vm.setContentFilter(filter.copy(month = null)) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (results.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (query.isBlank() && filter == ContentFilter())
                        stringResource(R.string.search_empty_idle)
                    else
                        stringResource(R.string.search_empty_no_results),
                    color = colors.fgMute,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    start = 6.dp, end = 6.dp, top = 4.dp, bottom = 12.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                items(results, key = { keyOf(it) }) { item ->
                    val idx = results.indexOf(item)
                    PhotoCell(
                        item = item,
                        onClick = { onPhotoClick(results, idx) },
                    )
                }
            }
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = filterSheetState,
            containerColor = colors.cardBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            ContentFilterSheet(
                currentFilter = filter,
                currentCategory = GalleryFilter.All,
                onApply = { vm.setContentFilter(it) },
                onCategorySelected = { /* not used on Search */ },
                onDismiss = { showFilterSheet = false },
                showCategorySection = false,
            )
        }
    }
}

@Composable
private fun FilterRailRow(
    colors: me.proton.photos.presentation.theme.AppColorsTokens,
    filter: ContentFilter,
    onOpenSheet: () -> Unit,
    onClearMediaType: () -> Unit,
    onClearSyncStatus: () -> Unit,
    onClearYear: () -> Unit,
    onClearMonth: () -> Unit,
) {
    LocalConfiguration.current // re-render on locale change
    val monthName = filter.month?.let { m ->
        SimpleDateFormat("MMM", Locale.getDefault())
            .format(Calendar.getInstance().apply { set(Calendar.MONTH, m - 1) }.time)
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "filter_btn") {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.chipUnselectedBg)
                    .border(0.5.dp, colors.pillBorder, RoundedCornerShape(50))
                    .clickable(onClick = onOpenSheet)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = null,
                    tint = colors.fgDim,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.filter_title),
                    color = colors.fgDim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (filter.mediaType != MediaType.All) {
            item(key = "chip_media") {
                ActiveFilterChip(
                    label = when (filter.mediaType) {
                        MediaType.PhotosOnly -> stringResource(R.string.gallery_tab_photos)
                        MediaType.VideosOnly -> stringResource(R.string.filter_type_videos)
                        else -> ""
                    },
                    colors = colors,
                    onClear = onClearMediaType,
                )
            }
        }
        if (filter.syncStatus != SyncStatusFilter.All) {
            item(key = "chip_sync") {
                ActiveFilterChip(
                    label = when (filter.syncStatus) {
                        SyncStatusFilter.LocalOnly -> stringResource(R.string.filter_sync_local)
                        SyncStatusFilter.BackedUp -> stringResource(R.string.filter_sync_backedup)
                        else -> ""
                    },
                    colors = colors,
                    onClear = onClearSyncStatus,
                )
            }
        }
        if (filter.year != null) {
            item(key = "chip_year") {
                ActiveFilterChip(
                    label = filter.year.toString(),
                    colors = colors,
                    onClear = onClearYear,
                )
            }
        }
        if (monthName != null) {
            item(key = "chip_month") {
                ActiveFilterChip(
                    label = monthName,
                    colors = colors,
                    onClear = onClearMonth,
                )
            }
        }
    }
}

@Composable
private fun ActiveFilterChip(
    label: String,
    colors: me.proton.photos.presentation.theme.AppColorsTokens,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.chipSelectedBg)
            .border(0.5.dp, colors.accent.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClick = onClear)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.fgPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            Icons.Filled.Close,
            contentDescription = null,
            tint = colors.fgDim,
            modifier = Modifier.size(14.dp),
        )
    }
}

private fun keyOf(item: GalleryItem): String = when (item) {
    is GalleryItem.LocalOnly -> "L:" + item.local.uri
    is GalleryItem.Synced    -> "S:" + item.local.uri
    is GalleryItem.CloudOnly -> "C:" + item.cloud.linkId
}
