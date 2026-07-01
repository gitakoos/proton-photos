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

package eu.akoos.photos.presentation.albums.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.fullBleedHorizontal
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * Hero header for [AlbumDetailScreen]: 4:3 cover, a title row (name + optional rename pencil), and a
 * meta row with [metaLeading] (share avatars) + count on the left and [titleActions] at the far right.
 * Actions live on the meta row so a long album name can't shove them around. [coverModel] is anything Coil loads.
 */
@Composable
internal fun AlbumHeroHeader(
    coverModel: Any?,
    title: String,
    photoCountText: String,
    /** Live scroll offset (px) of the header, read inside graphicsLayer so the cover can parallax
     *  behind the scrolling content without recomposing. */
    coverParallax: () -> Float = { 0f },
    canRename: Boolean = true,
    onRenameClick: () -> Unit = {},
    titleActions: @Composable (RowScope.() -> Unit)? = null,
    metaLeading: @Composable (RowScope.() -> Unit)? = null,
) {
    // Full-bleed: cancel the parent grid's 20.dp side contentPadding so the cover banner and the
    // title block span edge-to-edge, while the photo cells below stay inset by that padding.
    Column(modifier = Modifier.fullBleedHorizontal(20.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clipToBounds()
                .background(Bg2),
        ) {
            if (coverModel != null) {
                // Crossfade to the new cover when it changes. The request is keyed on the model so
                // it isn't rebuilt on every recomposition (which would reload and flicker).
                val context = LocalContext.current
                val coverRequest = remember(coverModel) {
                    ImageRequest.Builder(context).data(coverModel).crossfade(400).build()
                }
                AsyncImage(
                    model = coverRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // Parallax: the cover drifts at a fraction of the scroll speed and is slightly
                    // overscanned (scale > 1) so the slower drift never opens a gap at the top edge.
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = coverParallax() * 0.4f
                            scaleX = 1.1f
                            scaleY = 1.1f
                        },
                )
            }

            // A rounded "sheet" lip so the content below meets the cover with rounded top corners,
            // like the top of a bottom sheet (without the handle).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(AppColors.current.bg0),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = AppColors.current.fgPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (canRename) {
                    Spacer(Modifier.size(8.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onRenameClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.album_rename),
                            tint = AppColors.current.fgDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Fixed height fits the 40.dp action buttons so a late member fetch doesn't shift the layout.
            if (metaLeading != null || photoCountText.isNotEmpty() || titleActions != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    if (metaLeading != null) {
                        metaLeading()
                        if (photoCountText.isNotEmpty()) {
                            Text("·", color = AppColors.current.fgMute, fontSize = 14.sp)
                        }
                    }
                    if (photoCountText.isNotEmpty()) {
                        Text(photoCountText, color = AppColors.current.fgMute, fontSize = 14.sp)
                    }
                    if (titleActions != null) {
                        Spacer(Modifier.weight(1f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            content = titleActions,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = PillBorder, thickness = 0.5.dp)
    }
}
