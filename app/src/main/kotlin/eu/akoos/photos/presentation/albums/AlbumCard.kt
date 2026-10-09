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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ShimmerBox
import eu.akoos.photos.presentation.common.ShimmerTextLine
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.LocalGifAutoplayCovers
import eu.akoos.photos.presentation.theme.LocalStaticImageLoader

/** The optional sharing state shown via the top-start pill. */
enum class AlbumShareBadge {
    None,
    SharedByMe,    // Shared by this account → violet pill
    SharedWithMe,  // someone shared it with me → blue pill
}

/** The optional cloud/local indicator in the bottom-end corner. */
enum class AlbumCloudBadge {
    None,
    Cloud,                 // Drive-only album (no local copy)
    LocallyBackedUpFull,   // every photo backed up to Drive
    LocallyBackedUpPart,   // some photos backed up
}

/** Single album card used across the Albums, Shared-by-me and Shared-with-me surfaces. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UnifiedAlbumCard(
    coverModel: Any?,
    /** True when [coverModel] is a local GIF whose uri hides its extension (a MediaStore content://
     *  id). Lets the card pick the still-frame loader when cover autoplay is off. A cloud GIF cover
     *  needs no flag: it arrives resolved as a file path ending in .gif. */
    coverIsGif: Boolean = false,
    title: String,
    metaText: String,
    shareBadge: AlbumShareBadge = AlbumShareBadge.None,
    cloudBadge: AlbumCloudBadge = AlbumCloudBadge.None,
    /** Shows a folder glyph in the corner badge for bucket-derived local albums (rename/delete
     *  refused for these). */
    isDeviceFolder: Boolean = false,
    /** Drawn on an empty cover in place of an image, for a card that opens a screen or starts an album
     *  rather than showing one, in [glyphTint]. */
    glyph: ImageVector? = null,
    glyphTint: Color = FgDim,
    /** False while a grid-wide mode owns the card's gestures. The clickable is dropped outright
     *  rather than pointed at no-op lambdas, so the card cannot ripple at a tap it will ignore. */
    interactionsEnabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (interactionsEnabled) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier
                }
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(AlbumCoverShape)
                .background(Bg2),
        ) {
            if (glyph != null) {
                Icon(glyph, contentDescription = null, tint = glyphTint, modifier = Modifier.align(Alignment.Center).size(40.dp))
            } else if (coverModel != null) {
                AlbumCoverImage(coverModel, coverIsGif)
            }

            CloudCornerBadge(
                cloud = cloudBadge,
                share = shareBadge,
                folder = isDeviceFolder,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            color = FgPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = metaText,
            color = FgMute,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Corner of every album cover, on a card and on a list row alike. */
private val AlbumCoverCorner = 14.dp
private val AlbumCoverShape = RoundedCornerShape(AlbumCoverCorner)

/** Edge of the square cover on a list row. */
private val AlbumRowCoverSize = 56.dp

/** A list row's insets: the Albums tab's 20dp margin ahead of the cover, and less behind, where the
 *  options button's own touch padding makes up the rest. */
private val AlbumRowPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)

/** Gap between a list row's cover and its text. */
private val AlbumRowGap = 14.dp

/**
 * A cover image filling its box. A GIF cover animates only when the user opted into cover autoplay;
 * otherwise the decoder-free loader renders its still first frame. A local cover's content:// uri
 * hides the extension, so [coverIsGif] carries the answer there; a resolved cloud GIF arrives as a
 * file path ending in .gif. Every other cover keeps the default loader and is a still image regardless.
 */
@Composable
private fun AlbumCoverImage(coverModel: Any, coverIsGif: Boolean) {
    val autoplay = LocalGifAutoplayCovers.current
    val staticLoader = LocalStaticImageLoader.current
    val context = LocalContext.current
    val modelIsGif = coverIsGif || coverModel.toString().endsWith(".gif", ignoreCase = true)
    val coverLoader =
        if (modelIsGif && !autoplay) staticLoader ?: context.imageLoader
        else context.imageLoader
    AsyncImage(
        model              = coverModel,
        imageLoader        = coverLoader,
        contentDescription = null,
        contentScale       = ContentScale.Crop,
        modifier           = Modifier.fillMaxSize(),
    )
}

/**
 * The list-view counterpart of [UnifiedAlbumCard]: the same cover, corner and badge at thumbnail
 * size, the title and meta line beside it, and a trailing options button. The row spans the full
 * width so a press lights all of it, and pads its own content.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UnifiedAlbumRow(
    coverModel: Any?,
    /** Same as [UnifiedAlbumCard]'s: a local GIF cover whose uri hides its extension. */
    coverIsGif: Boolean = false,
    title: String,
    /** The line under the title. Null leaves the title alone, centred on the cover. */
    metaText: String? = null,
    shareBadge: AlbumShareBadge = AlbumShareBadge.None,
    cloudBadge: AlbumCloudBadge = AlbumCloudBadge.None,
    isDeviceFolder: Boolean = false,
    /** Drawn on an empty cover in place of an image, for a row that opens a screen rather than an
     *  album. */
    glyph: ImageVector? = null,
    glyphTint: Color = FgDim,
    /** True while the albums are being arranged. The row's gestures belong to the arrangement then,
     *  so its clicks are dropped, as a card's are, and a drag handle stands where the options button
     *  was. */
    arranging: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    /** Opens the row's action sheet. Null leaves the row without the button. */
    onOptionsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (arranging) {
                    Modifier
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                }
            )
            .padding(AlbumRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AlbumRowGap),
    ) {
        Box(
            modifier = Modifier
                .size(AlbumRowCoverSize)
                .clip(AlbumCoverShape)
                .background(Bg2),
            contentAlignment = Alignment.Center,
        ) {
            when {
                glyph != null ->
                    Icon(glyph, contentDescription = null, tint = glyphTint, modifier = Modifier.size(24.dp))
                coverModel != null -> AlbumCoverImage(coverModel, coverIsGif)
            }
            CloudCornerBadge(
                cloud = cloudBadge,
                share = shareBadge,
                folder = isDeviceFolder,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = FgPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (metaText != null) {
                Text(
                    text = metaText,
                    color = FgMute,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (arranging) {
            // The same 48dp slot the button takes, so the text does not reflow as the mode opens.
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DragHandle, contentDescription = null, tint = FgDim)
            }
        } else if (onOptionsClick != null) {
            IconButton(onClick = onOptionsClick) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.album_options),
                    tint = FgDim,
                )
            }
        }
    }
}

/** Loading placeholder for [UnifiedAlbumRow], on the same insets so the rows land where it stood. */
@Composable
fun ShimmerAlbumRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AlbumRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AlbumRowGap),
    ) {
        ShimmerBox(modifier = Modifier.size(AlbumRowCoverSize), cornerRadius = AlbumCoverCorner)
        Column(modifier = Modifier.weight(1f)) {
            ShimmerTextLine(widthFraction = 0.6f, height = 14.dp)
            Spacer(Modifier.height(4.dp))
            ShimmerTextLine(widthFraction = 0.3f, height = 12.dp)
        }
    }
}

/** Bottom-end badge combining a sharing icon, a device-folder marker, and the cloud/backup indicator
 *  in one pill. A device folder shows the folder glyph here, in the same corner cloud albums use, so
 *  the two read the same way instead of a text pill in the opposite corner. */
@Composable
private fun CloudCornerBadge(
    cloud: AlbumCloudBadge,
    share: AlbumShareBadge = AlbumShareBadge.None,
    folder: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (cloud == AlbumCloudBadge.None && share == AlbumShareBadge.None && !folder) return
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (share != AlbumShareBadge.None) {
            Icon(Icons.Default.People, null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        if (folder) {
            Icon(
                Icons.Default.Folder,
                stringResource(R.string.album_card_folder_badge),
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
        when (cloud) {
            AlbumCloudBadge.Cloud ->
                Icon(Icons.Default.Cloud, null, tint = Color.White, modifier = Modifier.size(13.dp))
            AlbumCloudBadge.LocallyBackedUpFull ->
                Icon(Icons.Default.CheckCircle, stringResource(R.string.cd_album_fully_backed_up), tint = Color(0xFF4CAF50), modifier = Modifier.size(13.dp))
            AlbumCloudBadge.LocallyBackedUpPart ->
                Icon(Icons.Default.CheckCircle, stringResource(R.string.cd_album_partially_backed_up), tint = Color(0xFFFF9800), modifier = Modifier.size(13.dp))
            AlbumCloudBadge.None -> Unit
        }
    }
}
