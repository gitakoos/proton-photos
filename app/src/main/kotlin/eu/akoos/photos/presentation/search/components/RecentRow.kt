package eu.akoos.photos.presentation.search.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.AppColors

/**
 * "Recent" section of the Search empty state.
 *
 * Renders the first 6 of [items] as a non-scrollable 3-column × 2-row grid. No
 * horizontal scrolling — only the freshest 6 photos surface, so the user gets a
 * quick at-a-glance shortcut to the newest captures without flicking through a
 * carousel.
 *
 * Caller is responsible for hiding this composable when [items] is empty.
 */
@Composable
fun RecentRow(
    items: List<GalleryItem>,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
) {
    val colors = AppColors.current
    val six = items.take(6)
    val gap = 4.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Match the side padding used by the JumpToMonth grid so the Recent
            // strip doesn't run edge-to-edge while the section below it is inset.
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.search_section_recent),
            color = colors.fgPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.44).sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        // Split the (up to) 6 items into two rows of three. Each row uses
        // weight(1f) per cell so the tiles scale evenly with the screen width
        // and don't overflow on small devices.
        val rows = six.chunked(3)
        for ((rowIdx, row) in rows.withIndex()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                for (i in 0 until 3) {
                    val item = row.getOrNull(i)
                    if (item != null) {
                        RecentTile(
                            item = item,
                            placeholderBg = colors.cardBg,
                            onClick = { onPhotoClick(six, six.indexOf(item)) },
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                        )
                    } else {
                        // Pad the trailing row when items.size is not a multiple
                        // of 3 so the remaining tiles don't stretch sideways.
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
            if (rowIdx < rows.lastIndex) Spacer(modifier = Modifier.padding(top = gap / 2))
        }
    }
}

@Composable
private fun RecentTile(
    item: GalleryItem,
    placeholderBg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageModel: Any? = when (item) {
        is GalleryItem.LocalOnly -> android.net.Uri.parse(item.local.uri)
        is GalleryItem.Synced    -> android.net.Uri.parse(item.local.uri)
        is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl
    }
    val mimeType = when (item) {
        is GalleryItem.LocalOnly -> item.local.mimeType
        is GalleryItem.Synced    -> item.local.mimeType
        is GalleryItem.CloudOnly -> item.cloud.mimeType
    }
    val isVideo = mimeType.startsWith("video/")
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(placeholderBg)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = imageModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        if (isVideo) {
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(0.dp).clip(CircleShape),
                )
            }
        }
    }
}
