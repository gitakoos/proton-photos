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

package eu.akoos.photos.presentation.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBorder
import java.util.Calendar

// ── Content filter bottom sheet ───────────────────────────────────────────────

private val filterChipShape = RoundedCornerShape(10.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentFilterSheet(
    currentFilter: ContentFilter,
    currentCategory: GalleryFilter,
    onApply: (ContentFilter) -> Unit,
    onCategorySelected: (GalleryFilter) -> Unit,
    onDismiss: () -> Unit,
    showCategorySection: Boolean = true,
    showMediaTypeSection: Boolean = true,
) {
    var mediaType by remember { mutableStateOf(currentFilter.mediaType) }
    var syncStatus by remember { mutableStateOf(currentFilter.syncStatus) }
    var year by remember { mutableStateOf(currentFilter.year) }
    var month by remember { mutableStateOf(currentFilter.month) }
    var day by remember { mutableStateOf(currentFilter.day) }
    var category by remember { mutableStateOf(currentCategory) }

    fun applyNow(
        mt: MediaType = mediaType,
        ss: SyncStatusFilter = syncStatus,
        y: Int? = year,
        m: Int? = month,
        d: Int? = day,
    ) = onApply(ContentFilter(mediaType = mt, syncStatus = ss, year = y, month = m, day = d))

    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val years = remember { (currentYear downTo currentYear - 10).toList() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Title row — the reset action sits on the right here, shown only when a filter is active,
        // instead of a separate button at the bottom of the sheet.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.filter_title),
                color = FgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            val hasActiveFilter = mediaType != MediaType.All || syncStatus != SyncStatusFilter.All ||
                year != null || category != GalleryFilter.All
            if (hasActiveFilter) {
                Text(
                    text = "✕  ${stringResource(R.string.filter_reset)}",
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        mediaType = MediaType.All; syncStatus = SyncStatusFilter.All
                        year = null; month = null; day = null
                        category = GalleryFilter.All
                        onCategorySelected(GalleryFilter.All)
                        applyNow(mt = MediaType.All, ss = SyncStatusFilter.All, y = null, m = null, d = null)
                    },
                )
            }
        }

        // ── Categories (server-assigned PhotoTag) ────────────────────────────
        // Matches the Drive web Photos page categories: Favorites, Screenshots, Videos,
        // Live Photos, Motion Photos, Selfies, Portraits, Bursts, Panoramas, RAW.
        // These are tag-id 0..9 — server populates them automatically; local-only items
        // (not yet backed up) cannot be filtered by category.
        if (showCategorySection) {
            FilterSectionLabel(stringResource(R.string.gallery_filter_categories))
            val categories = listOf(
                GalleryFilter.All          to stringResource(R.string.gallery_filter_all),
                GalleryFilter.Favorites    to stringResource(R.string.gallery_filter_favorites),
                GalleryFilter.Screenshots  to stringResource(R.string.gallery_filter_screenshots),
                GalleryFilter.Videos       to stringResource(R.string.filter_type_videos),
                GalleryFilter.LivePhotos   to stringResource(R.string.gallery_filter_live_photos),
                GalleryFilter.Selfies      to stringResource(R.string.gallery_filter_selfies),
                GalleryFilter.Portraits    to stringResource(R.string.gallery_filter_portraits),
                GalleryFilter.Bursts       to stringResource(R.string.gallery_filter_bursts),
                GalleryFilter.Panoramas    to stringResource(R.string.gallery_filter_panoramas),
                GalleryFilter.Raw          to stringResource(R.string.gallery_filter_raw),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { (cat, label) ->
                    item(key = "cat_${cat.name}") {
                        FilterChip(
                            label = label,
                            selected = category == cat,
                            onClick = {
                                category = cat
                                onCategorySelected(cat)
                            },
                            leadingIcon = if (cat == GalleryFilter.All) null
                                else { tint -> GalleryCategoryIcon(cat, tint) },
                        )
                    }
                }
            }
        }

        // ── Media type ───────────────────────────────────────────────────────
        if (showMediaTypeSection) {
            FilterSectionLabel(stringResource(R.string.filter_type_label))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    MediaType.All to stringResource(R.string.gallery_filter_all),
                    MediaType.PhotosOnly to stringResource(R.string.gallery_tab_photos),
                    MediaType.VideosOnly to stringResource(R.string.filter_type_videos),
                ).forEach { (type, label) ->
                    FilterChip(
                        label = label,
                        selected = mediaType == type,
                        onClick = { mediaType = type; applyNow(mt = type) },
                    )
                }
            }
        }

        // ── Sync status ──────────────────────────────────────────────────────
        FilterSectionLabel(stringResource(R.string.filter_sync_label))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                SyncStatusFilter.All to stringResource(R.string.gallery_filter_all),
                SyncStatusFilter.LocalOnly to stringResource(R.string.filter_sync_local),
                SyncStatusFilter.BackedUp to stringResource(R.string.filter_sync_backedup),
            ).forEach { (status, label) ->
                FilterChip(
                    label = label,
                    selected = syncStatus == status,
                    onClick = { syncStatus = status; applyNow(ss = status) },
                )
            }
        }

        // ── Date (calendar — year, optional month, optional day) ───────────────
        DateFilterCalendar(
            year = year,
            month = month,
            day = day,
            years = years,
            onPick = { y, m, d ->
                year = y; month = m; day = d
                applyNow(y = y, m = m, d = d)
            },
        )
    }
}

@Composable
private fun FilterSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = FgMute,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    )
}

@Composable
internal fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: (@Composable (Color) -> Unit)? = null,
) {
    val colors = AppColors.current
    val contentColor = if (selected) FgPrimary else FgDim
    Box(
        modifier = Modifier
            .background(
                if (selected) colors.chipSelectedBg else colors.chipUnselectedBg,
                filterChipShape,
            )
            .then(
                if (!selected) Modifier.border(0.5.dp, PillBorder, filterChipShape) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leadingIcon?.invoke(contentColor)
            Text(
                text = label,
                color = contentColor,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

/** Leading icon for a category filter chip, mirroring the Drive web category row: Proton's
 *  own category glyphs for screenshots / videos / live / bursts / panoramas / raw, with the
 *  closest Material icons standing in for favourites / selfies / portraits. */
@Composable
internal fun GalleryCategoryIcon(filter: GalleryFilter, tint: Color) {
    val drawableRes: Int? = when (filter) {
        GalleryFilter.Screenshots  -> R.drawable.ic_screenshot
        GalleryFilter.Videos       -> R.drawable.ic_video_camera
        GalleryFilter.LivePhotos,
        GalleryFilter.MotionPhotos -> R.drawable.ic_live
        GalleryFilter.Bursts       -> R.drawable.ic_image_stacked
        GalleryFilter.Panoramas    -> R.drawable.ic_panorama
        GalleryFilter.Raw          -> R.drawable.ic_raw
        else -> null
    }
    val vector = when (filter) {
        GalleryFilter.Favorites -> Icons.Default.FavoriteBorder
        GalleryFilter.Selfies   -> Icons.Default.Person
        GalleryFilter.Portraits -> Icons.Default.AccountCircle
        GalleryFilter.Offline   -> Icons.Default.OfflinePin
        else -> null
    }
    when {
        drawableRes != null -> Icon(painterResource(drawableRes), null, tint = tint, modifier = Modifier.size(15.dp))
        vector != null      -> Icon(vector, null, tint = tint, modifier = Modifier.size(15.dp))
    }
}
