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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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

// ── Filter building blocks ──────────────────────────────────────────────────────
// Chips, section headers and icons shared by the Albums filter sheet, the Photos search page and
// the settings pages.

private val filterChipShape = RoundedCornerShape(10.dp)

/** Sentence-case section title with a one-line explanation under it. */
@Composable
internal fun FilterSectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(text = subtitle, color = FgMute, fontSize = 12.sp)
    }
}

internal fun syncStatusIcon(status: SyncStatusFilter): (@Composable (Color) -> Unit)? {
    val vector = when (status) {
        SyncStatusFilter.LocalOnly -> Icons.Outlined.PhoneAndroid
        SyncStatusFilter.BackedUp  -> Icons.Outlined.CloudDone
        SyncStatusFilter.CloudOnly -> Icons.Outlined.Cloud
        SyncStatusFilter.AvailableOffline -> Icons.Outlined.OfflinePin
        SyncStatusFilter.All       -> return null
    }
    return { tint -> Icon(vector, null, tint = tint, modifier = Modifier.size(15.dp)) }
}

@Composable
internal fun FilterSectionLabel(text: String) {
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
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable (Color) -> Unit)? = null,
    accentWhenSelected: Boolean = false,
    trailingClear: Boolean = false,
) {
    val colors = AppColors.current
    val useAccent = accentWhenSelected && selected
    val contentColor = when {
        useAccent -> Accent
        selected -> FgPrimary
        else -> FgDim
    }
    Box(
        modifier = modifier
            .background(
                when {
                    useAccent -> Accent.copy(alpha = 0.15f)
                    selected -> colors.chipSelectedBg
                    else -> colors.chipUnselectedBg
                },
                filterChipShape,
            )
            .then(
                when {
                    useAccent -> Modifier.border(1.dp, Accent, filterChipShape)
                    !selected -> Modifier.border(0.5.dp, PillBorder, filterChipShape)
                    else -> Modifier
                }
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
            if (trailingClear && selected) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp),
                )
            }
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
        else -> null
    }
    when {
        drawableRes != null -> Icon(painterResource(drawableRes), null, tint = tint, modifier = Modifier.size(15.dp))
        vector != null      -> Icon(vector, null, tint = tint, modifier = Modifier.size(15.dp))
    }
}
