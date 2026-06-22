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

package eu.akoos.photos.presentation.calendar

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.fullBleedHorizontal
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Day Detail screen — hero photo at the top, editable description below, then a grid of
 * every photo + video captured on that calendar date.
 *
 * The hero image comes from the user-picked cover when set, otherwise the first photo
 * of the day. Long-pressing any thumbnail in the grid promotes it to the new cover.
 */
@Composable
fun DayDetailScreen(
    date: String,
    onBack: () -> Unit,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    viewModel: DayDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(date) { viewModel.setDate(date) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    // Local input state that mirrors meta but lets the user type without each keystroke
    // hitting Room. The mirror resets when a new meta row comes in (e.g. on first load
    // or when a different date is opened).
    var descriptionInput by remember(state.meta?.date) { mutableStateOf(state.meta?.description ?: "") }

    // Whether the description editor sheet is open.
    var editingDescription by remember { mutableStateOf(false) }

    val heroItem: GalleryItem? = remember(state.items, state.meta?.coverPhotoUri) {
        val cover = state.meta?.coverPhotoUri
        if (cover.isNullOrBlank()) state.items.firstOrNull()
        else state.items.firstOrNull { item ->
            when (item) {
                is GalleryItem.LocalOnly -> item.local.uri == cover
                is GalleryItem.Synced    -> item.local.uri == cover || item.cloud.linkId == cover
                is GalleryItem.CloudOnly -> item.cloud.linkId == cover
            }
        } ?: state.items.firstOrNull()
    }

    val dateLabel = remember(date) { formatDateLabel(date) }
    val totalPhotos = state.items.size

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg0),
    ) {
        if (state.isLoading && state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
        } else {
            val cols = rememberDefaultGridColumns()
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                modifier = Modifier.fillMaxSize(),
                // Match the main timeline grid (GalleryGrid): same default columns, 20.dp side inset
                // and 6.dp gap, so the day's photos render at the same size as the Photos page.
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Hero + metadata header span the full row.
                item(span = { GridItemSpan(maxLineSpan) }, key = "header_hero") {
                    HeroHeader(
                        heroItem = heroItem,
                        dateLabel = dateLabel,
                        totalPhotos = totalPhotos,
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "header_inputs") {
                    Column(modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        // Lightweight row pattern: icon + current value (or hint) on a single
                        // row. Tapping anywhere on the row opens an EditFieldSheet — a heavier
                        // inline OutlinedTextField would dominate the page and make the photos
                        // below feel secondary.
                        EditableMetaRow(
                            icon = Icons.AutoMirrored.Filled.Notes,
                            hint = stringResource(R.string.day_detail_description_hint),
                            value = descriptionInput,
                            onClick = { editingDescription = true },
                            multiline = true,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }

                itemsIndexed(
                    items = state.items,
                    key = { _, item -> itemKey(item) },
                ) { index, item ->
                    val inputs = remember(item) { photoCellInputsFor(item) }
                    PhotoCell(
                        imageData = inputs.imageData,
                        stableKey = inputs.stableKey,
                        isVideo = inputs.isVideo,
                        isPlaceholder = inputs.isPlaceholder,
                        showCloudBadge = inputs.showCloudBadge,
                        showSyncedBadge = inputs.showSyncedBadge,
                        isFavorite = inputs.isFavorite,
                        typeBadgeRes = inputs.typeBadgeRes,
                        typeBadgeCdRes = inputs.typeBadgeCdRes,
                        onClick = { onPhotoClick(state.items, index) },
                        // Long-press promotes this thumbnail to the day's cover.
                        onLongClick = { viewModel.setCover(item) },
                    )
                }
            }
        }

        // Floating back button + top scrim.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBubble(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.onboarding_back),
                onClick = onBack,
                diameter = 40.dp,
                iconSize = 20.dp,
                background = PillBg,
                borderColor = PillBorder,
                tint = colors.fgPrimary,
            )
        }
    }

    // Description edit sheet. Save commits to Room via the existing VM setter; cancel just
    // dismisses without persisting.
    if (editingDescription) {
        eu.akoos.photos.presentation.calendar.components.EditFieldSheet(
            title = stringResource(R.string.day_detail_edit_description_title),
            initialValue = descriptionInput,
            hint = stringResource(R.string.day_detail_description_hint),
            singleLine = false,
            onDismiss = { editingDescription = false },
            onSave = { v ->
                descriptionInput = v
                viewModel.updateDescription(v)
                editingDescription = false
            },
        )
    }
}

/**
 * Single-row meta display: icon on the left, current value (or hint when empty) flowing
 * to the right. Whole row is clickable — caller swaps the value out via a bottom sheet.
 * Designed to read as a quiet info line, not a form field; keeps the photo grid below
 * as the page's visual centre.
 */
@Composable
private fun EditableMetaRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    hint: String,
    value: String,
    onClick: () -> Unit,
    multiline: Boolean = false,
) {
    val colors = AppColors.current
    val isEmpty = value.isBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isEmpty) colors.fgMute else colors.fgDim,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (isEmpty) hint else value,
            color = if (isEmpty) colors.fgMute else colors.fgPrimary,
            fontSize = 14.sp,
            fontWeight = if (isEmpty) FontWeight.Normal else FontWeight.Medium,
            maxLines = if (multiline) 3 else 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HeroHeader(
    heroItem: GalleryItem?,
    dateLabel: String,
    totalPhotos: Int,
) {
    val colors = AppColors.current
    Box(
        modifier = Modifier
            // Cancel the parent grid's 20.dp side contentPadding so the cover spans edge-to-edge,
            // exactly like the album detail hero, while the photo cells below stay inset.
            .fullBleedHorizontal(20.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .background(Bg2),
    ) {
        if (heroItem != null) {
            val model: Any? = when (heroItem) {
                is GalleryItem.LocalOnly -> android.net.Uri.parse(heroItem.local.uri)
                is GalleryItem.Synced    -> android.net.Uri.parse(heroItem.local.uri)
                is GalleryItem.CloudOnly -> heroItem.cloud.thumbnailUrl
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Bottom gradient so the date label stays readable on any cover.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.35f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.65f),
                            ),
                        ),
                    ),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(
                text = dateLabel,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.44).sp,
            )
            if (totalPhotos > 0) {
                Text(
                    text = stringResource(R.string.day_detail_photos_count, totalPhotos),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun itemKey(item: GalleryItem): String = when (item) {
    is GalleryItem.LocalOnly -> "local_${item.local.uri}"
    is GalleryItem.Synced    -> "synced_${item.cloud.linkId}"
    is GalleryItem.CloudOnly -> "cloud_${item.cloud.linkId}"
}

private fun formatDateLabel(date: String): String {
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    val parsed = runCatching { parser.parse(date) }.getOrNull() ?: return date
    val display = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
    return display.format(parsed)
}
