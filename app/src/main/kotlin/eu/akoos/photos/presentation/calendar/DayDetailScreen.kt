package eu.akoos.photos.presentation.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Day Detail screen — hero photo at the top, editable location/description below, then
 * a grid of every photo + video captured on that calendar date.
 *
 * The hero image comes from the user-picked cover when set, otherwise the first photo
 * of the day. Long-pressing any thumbnail in the grid promotes it to the new cover.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    var locationInput by remember(state.meta?.date) { mutableStateOf(state.meta?.locationText ?: "") }
    var descriptionInput by remember(state.meta?.date) { mutableStateOf(state.meta?.description ?: "") }

    // Which of the two metadata fields (if any) the user is currently editing. The
    // active one drives an EditFieldSheet modal that's mounted below.
    var editingLocation by remember { mutableStateOf(false) }
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Hero + metadata header span all three columns.
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
                        // row. Tapping anywhere on the row opens an EditFieldSheet for that
                        // field — heavier inline OutlinedTextFields would dominate the page
                        // and make the photos below feel secondary.
                        EditableMetaRow(
                            icon = Icons.Default.LocationOn,
                            hint = stringResource(R.string.day_detail_location_hint),
                            value = locationInput,
                            onClick = { editingLocation = true },
                        )
                        Spacer(Modifier.height(6.dp))
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
                    DayThumbnail(
                        item = item,
                        onClick = { onPhotoClick(state.items, index) },
                        onLongPress = { viewModel.setCover(item) },
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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(PillBg, CircleShape)
                    .border(0.5.dp, PillBorder, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = colors.fgPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    // Edit-field bottom sheets — at most one is open at a time. Save commits to Room
    // via the existing VM setters (same path the inline OutlinedTextField used to take);
    // cancel just dismisses without persisting.
    if (editingLocation) {
        eu.akoos.photos.presentation.calendar.components.EditFieldSheet(
            title = stringResource(R.string.day_detail_edit_location_title),
            initialValue = locationInput,
            hint = stringResource(R.string.day_detail_location_hint),
            singleLine = true,
            onDismiss = { editingLocation = false },
            onSave = { v ->
                locationInput = v
                viewModel.updateLocation(v)
                editingLocation = false
            },
        )
    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayThumbnail(
    item: GalleryItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = AppColors.current
    val model: Any? = when (item) {
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
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Bg2)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
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
