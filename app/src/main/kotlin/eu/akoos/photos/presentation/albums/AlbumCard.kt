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

package eu.akoos.photos.presentation.albums

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary

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

/**
 * Single album card used everywhere — Albums tab, Shared by me, Shared with me. Keeps the
 * shared-badge / cloud-badge / title / meta arrangement consistent so the same album never
 * looks different across surfaces.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UnifiedAlbumCard(
    coverModel: Any?,
    title: String,
    metaText: String,
    shareBadge: AlbumShareBadge = AlbumShareBadge.None,
    cloudBadge: AlbumCloudBadge = AlbumCloudBadge.None,
    /** When true, surfaces a "Folder" pill on the top-start of the cover. Used for
     *  bucket-derived local albums so the user can distinguish them from user-created
     *  virtual albums at a glance — rename/delete is refused for these. */
    isDeviceFolder: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Bg2),
        ) {
            if (coverModel != null) {
                AsyncImage(
                    model              = coverModel,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            }

            if (isDeviceFolder) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(Icons.Default.Folder, null, tint = Color.White, modifier = Modifier.size(11.dp))
                    Text("Folder", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }

            // Bottom-end: combined badge (people-icon + cloud / backup indicator inside one pill).
            CloudCornerBadge(
                cloud = cloudBadge,
                share = shareBadge,
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

/**
 * Bottom-end corner badge: combines a sharing icon (when applicable) and the cloud/backup
 * indicator inside ONE dark pill. Matches the original design where shared albums have a
 * people-icon next to the cloud icon (no big top-left chip).
 */
@Composable
private fun CloudCornerBadge(
    cloud: AlbumCloudBadge,
    share: AlbumShareBadge = AlbumShareBadge.None,
    modifier: Modifier = Modifier,
) {
    if (cloud == AlbumCloudBadge.None && share == AlbumShareBadge.None) return
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
        when (cloud) {
            AlbumCloudBadge.Cloud ->
                Icon(Icons.Default.Cloud, null, tint = Color.White, modifier = Modifier.size(13.dp))
            AlbumCloudBadge.LocallyBackedUpFull ->
                Icon(Icons.Default.CheckCircle, "Fully backed up", tint = Color(0xFF4CAF50), modifier = Modifier.size(13.dp))
            AlbumCloudBadge.LocallyBackedUpPart ->
                Icon(Icons.Default.CheckCircle, "Partially backed up", tint = Color(0xFFFF9800), modifier = Modifier.size(13.dp))
            AlbumCloudBadge.None -> Unit
        }
    }
}
