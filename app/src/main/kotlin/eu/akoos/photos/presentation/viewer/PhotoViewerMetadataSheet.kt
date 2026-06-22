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

package eu.akoos.photos.presentation.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.CardBg
import eu.akoos.photos.presentation.theme.DeleteTint
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.PhotoMetadata
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun PhotoMetadataSheet(
    item: GalleryItem?,
    exif: PhotoMetadata?,
    /** Geocoded place name for the Location row (device EXIF or a cloud photo's stored fix), or null
     *  while it resolves / when the photo has no GPS — the row then keeps its dash placeholder. */
    place: String? = null,
    /** Resolution + length of a cloud-only video, read off its decrypted full-res (it has no EXIF or
     *  on-device media row). Null for other items / until it downloads. */
    cloudVideoMeta: CloudVideoMeta? = null,
    isStripping: Boolean,
    /** Resolved full-res byte count, used for the Size row when [CloudPhoto.sizeBytes] is 0 (the
     *  server batch API returns null size for some video uploads). Null until the download lands. */
    cloudSizeFallback: Long? = null,
    onStripFields: (MetadataStripConfig) -> Unit,
    onRenameClick: () -> Unit = {},
    /** Category PhotoTag ids on the photo + toggle callback. Cloud-backed photos only. */
    photoTags: Set<Int> = emptySet(),
    onToggleTag: (Int, Boolean) -> Unit = { _, _ -> },
) {
    if (item == null) return
    val isLocal = item is GalleryItem.LocalOnly || item is GalleryItem.Synced
    // Per-section Strip only applies to device-only photos: Synced already went through the
    // "Strip on upload" toggle and CloudOnly has no on-device EXIF to touch.
    val isDeviceOnly = item is GalleryItem.LocalOnly
    // Hide rename for hidden-vault items: the file's `__<captureMs>.ext` suffix (the file name IS
    // the display-name there) bleeds into the field and breaks re-rename.
    val itemUri = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced -> item.local.uri
        is GalleryItem.CloudOnly -> null
    }
    val isHiddenItem = itemUri?.startsWith("file://") == true
    val effectiveRenameClick: (() -> Unit)? = if (isHiddenItem) null else onRenameClick
    var showStripConfirm by remember { mutableStateOf(false) }
    var pendingStripConfig by remember { mutableStateOf<MetadataStripConfig?>(null) }
    // The EXIF detail is hidden behind a tap so the sheet's main view stays short; tapping the
    // "Full metadata" row reveals it, tapping again collapses back.
    var showFullMetadata by remember { mutableStateOf(false) }
    val hasExif = exif != null && (
        exif.make != null || exif.model != null || exif.focalLength != null ||
        exif.gpsLatitude != null || exif.gpsLongitude != null ||
        exif.dateTime != null || exif.dateTimeOriginal != null ||
        exif.software != null || exif.artist != null || exif.copyright != null ||
        (exif.width != null && exif.height != null)
    )

    // Cap the sheet at roughly half the screen so a photo with lots of EXIF doesn't shove the
    // panel up under the status bar; the content scrolls within that ceiling.
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    androidx.compose.foundation.rememberScrollState().let { scrollState ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.viewer_menu_details),
                color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )

            val rowFile = stringResource(R.string.viewer_meta_row_file)
            val rowDate = stringResource(R.string.viewer_meta_row_date)
            val rowSize = stringResource(R.string.viewer_meta_row_size)
            val rowType = stringResource(R.string.viewer_meta_row_type)
            val rowAlbum = stringResource(R.string.viewer_meta_row_album)
            val rowSource = stringResource(R.string.viewer_meta_row_source)
            val rowResolution = stringResource(R.string.viewer_meta_row_resolution)
            val rowDuration = stringResource(R.string.viewer_meta_row_duration)
            val rowPlace = stringResource(R.string.viewer_meta_row_place)
            MetadataSection(stringResource(R.string.viewer_meta_section_file_info)) {
                // One unified row set across device / synced / cloud and photo / video — each value is
                // sourced from whichever side carries it, in the same order every time. Location and
                // Resolution reserve their slot (like Size) and fill in once resolved, rather than
                // appearing only after they land.
                val localMedia = (item as? GalleryItem.LocalOnly)?.local ?: (item as? GalleryItem.Synced)?.local
                val cloud = (item as? GalleryItem.Synced)?.cloud ?: (item as? GalleryItem.CloudOnly)?.cloud
                val mimeType = localMedia?.mimeType ?: cloud?.mimeType ?: ""
                val dateMs = when (item) {
                    is GalleryItem.LocalOnly -> item.local.dateTaken
                    // Prefer the cloud captureTime: MediaStore's DATE_TAKEN can drift to the download
                    // moment on some devices. Falls back to the local timestamp.
                    is GalleryItem.Synced -> if (item.cloud.captureTime > 0L) item.cloud.captureTime * 1000L else item.local.dateTaken
                    is GalleryItem.CloudOnly -> item.cloud.captureTime * 1000L
                }
                val sizeBytes = when (item) {
                    is GalleryItem.CloudOnly -> item.cloud.sizeBytes.takeIf { it > 0 } ?: cloudSizeFallback ?: 0L
                    else -> localMedia?.sizeBytes ?: 0L
                }
                val resW = localMedia?.width?.takeIf { it > 0 } ?: exif?.width?.takeIf { it > 0 } ?: cloudVideoMeta?.width?.takeIf { it > 0 }
                val resH = localMedia?.height?.takeIf { it > 0 } ?: exif?.height?.takeIf { it > 0 } ?: cloudVideoMeta?.height?.takeIf { it > 0 }
                val source = when (item) {
                    is GalleryItem.LocalOnly -> stringResource(R.string.viewer_meta_source_device_only)
                    is GalleryItem.Synced -> stringResource(R.string.viewer_meta_source_backed_up)
                    is GalleryItem.CloudOnly -> stringResource(R.string.viewer_meta_source_cloud)
                }

                MetaRow(rowFile, localMedia?.displayName ?: cloud?.displayName ?: "", onEdit = effectiveRenameClick)
                MetaRow(rowDate, formatMs(dateMs))
                MetaRow(rowPlace, place ?: "—")
                MetaRow(rowResolution, if (resW != null && resH != null) "$resW × $resH" else "—")
                if (mimeType.startsWith("video/")) {
                    val durMs = localMedia?.duration?.takeIf { it > 0 } ?: cloudVideoMeta?.durationMs?.takeIf { it > 0 }
                    MetaRow(rowDuration, if (durMs != null) eu.akoos.photos.presentation.util.formatVideoTime(durMs) else "—")
                }
                MetaRow(rowSize, if (sizeBytes > 0) formatBytes(sizeBytes) else "—")
                MetaRow(rowType, mimeType)
                localMedia?.bucketName?.let { MetaRow(rowAlbum, it) }
                MetaRow(rowSource, source)
            }

            if (item is GalleryItem.Synced || item is GalleryItem.CloudOnly) {
                CategoryEditor(tags = photoTags, onToggle = onToggleTag)
            }

            if (hasExif) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBg, RoundedCornerShape(12.dp))
                        .border(0.5.dp, Line2, RoundedCornerShape(12.dp))
                        .clickable { showFullMetadata = !showFullMetadata }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.viewer_meta_full),
                        color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                    Icon(
                        if (showFullMetadata) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = FgMute,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (showFullMetadata && exif != null && (exif.make != null || exif.model != null || exif.focalLength != null)) {
                MetadataSection(
                    label = stringResource(R.string.viewer_meta_section_camera),
                    actionLabel = if (isDeviceOnly) stringResource(R.string.viewer_meta_strip) else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        pendingStripConfig = MetadataStripConfig(stripCameraInfo = true)
                        showStripConfirm = true
                    },
                ) {
                    exif.make?.let { MetaRow(stringResource(R.string.viewer_meta_row_make), it) }
                    exif.model?.let { MetaRow(stringResource(R.string.viewer_meta_row_model), it) }
                    exif.lensModel?.let { MetaRow(stringResource(R.string.viewer_meta_row_lens), it) }
                    exif.focalLength?.let { MetaRow(stringResource(R.string.viewer_meta_row_focal_length), "${it}mm") }
                    exif.aperture?.let { MetaRow(stringResource(R.string.viewer_meta_row_aperture), "f/$it") }
                    exif.exposureTime?.let { MetaRow(stringResource(R.string.viewer_meta_row_exposure), it) }
                    exif.isoSpeed?.let { MetaRow(stringResource(R.string.viewer_meta_row_iso), it) }
                    exif.flash?.let {
                        MetaRow(
                            stringResource(R.string.viewer_meta_row_flash),
                            if (it and 0x01 != 0) stringResource(R.string.viewer_meta_flash_fired)
                            else stringResource(R.string.viewer_meta_flash_none),
                        )
                    }
                    exif.whiteBalance?.let {
                        MetaRow(
                            stringResource(R.string.viewer_meta_row_white_balance),
                            if (it == 0) stringResource(R.string.viewer_meta_wb_auto)
                            else stringResource(R.string.viewer_meta_wb_manual),
                        )
                    }
                }
            }

            if (showFullMetadata && exif != null && (exif.gpsLatitude != null || exif.gpsLongitude != null)) {
                MetadataSection(
                    label = stringResource(R.string.viewer_meta_section_location),
                    actionLabel = if (isDeviceOnly) stringResource(R.string.viewer_meta_strip) else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        pendingStripConfig = MetadataStripConfig(stripGps = true)
                        showStripConfirm = true
                    },
                ) {
                    // The coarse place name now sits in the overview above; this section keeps the
                    // raw coordinates behind Full metadata.
                    exif.gpsLatitude?.let {
                        MetaRow(stringResource(R.string.viewer_meta_row_latitude), "%.6f°".format(it))
                    }
                    exif.gpsLongitude?.let {
                        MetaRow(stringResource(R.string.viewer_meta_row_longitude), "%.6f°".format(it))
                    }
                    exif.gpsAltitude?.let {
                        MetaRow(stringResource(R.string.viewer_meta_row_altitude), "%.1f m".format(it))
                    }
                }
            }

            if (showFullMetadata && exif != null && (exif.dateTime != null || exif.dateTimeOriginal != null)) {
                MetadataSection(
                    label = stringResource(R.string.viewer_meta_section_datetime),
                    actionLabel = if (isDeviceOnly) stringResource(R.string.viewer_meta_strip) else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        pendingStripConfig = MetadataStripConfig(stripTimestamp = true)
                        showStripConfirm = true
                    },
                ) {
                    exif.dateTimeOriginal?.let { MetaRow(stringResource(R.string.viewer_meta_row_taken), formatExifDateTime(it)) }
                    exif.dateTime?.let { MetaRow(stringResource(R.string.viewer_meta_row_modified), formatExifDateTime(it)) }
                }
            }

            if (showFullMetadata && exif != null && (exif.software != null || exif.artist != null || exif.copyright != null)) {
                MetadataSection(
                    label = stringResource(R.string.viewer_meta_section_software),
                    actionLabel = if (isDeviceOnly) stringResource(R.string.viewer_meta_strip) else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        pendingStripConfig = MetadataStripConfig(stripSoftwareInfo = true)
                        showStripConfirm = true
                    },
                ) {
                    exif.software?.let { MetaRow(stringResource(R.string.viewer_meta_row_software), it) }
                    exif.artist?.let { MetaRow(stringResource(R.string.viewer_meta_row_artist), it) }
                    exif.copyright?.let { MetaRow(stringResource(R.string.viewer_meta_row_copyright), it) }
                }
            }

            if (showFullMetadata && exif != null && exif.width != null && exif.height != null) {
                MetadataSection(stringResource(R.string.viewer_meta_section_image)) {
                    val orientNormal = stringResource(R.string.viewer_meta_orientation_normal)
                    val orient180 = stringResource(R.string.viewer_meta_orientation_180)
                    val orient90Cw = stringResource(R.string.viewer_meta_orientation_90_cw)
                    val orient90Ccw = stringResource(R.string.viewer_meta_orientation_90_ccw)
                    MetaRow(stringResource(R.string.viewer_meta_row_dimensions), "${exif.width} × ${exif.height}")
                    exif.orientation?.let {
                        val orientLabel = when (it) {
                            1 -> orientNormal
                            3 -> orient180
                            6 -> orient90Cw
                            8 -> orient90Ccw
                            else -> "$it"
                        }
                        MetaRow(stringResource(R.string.viewer_meta_row_orientation), orientLabel)
                    }
                }
            }

            if (showFullMetadata && isLocal && exif != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DeleteTint, RoundedCornerShape(12.dp))
                        .border(0.5.dp, ErrorColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .clickable(enabled = !isStripping) {
                            pendingStripConfig = MetadataStripConfig(
                                stripGps = true,
                                stripCameraInfo = true,
                                stripTimestamp = false,
                                stripSoftwareInfo = true,
                            )
                            showStripConfirm = true
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            stringResource(R.string.viewer_meta_strip_all),
                            color = ErrorColor, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.viewer_meta_strip_all_desc),
                            color = FgMute, fontSize = 11.5.sp,
                        )
                    }
                    if (isStripping) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ErrorColor,
                        )
                    }
                }
            }
        }
    }

    if (showStripConfirm && pendingStripConfig != null) {
        val config = pendingStripConfig!!
        val whatGps = stringResource(R.string.viewer_meta_strip_what_gps)
        val whatCamera = stringResource(R.string.viewer_meta_strip_what_camera)
        val whatTimestamps = stringResource(R.string.viewer_meta_strip_what_timestamps)
        val whatSoftware = stringResource(R.string.viewer_meta_strip_what_software)
        val what = buildList {
            if (config.stripGps) add(whatGps)
            if (config.stripCameraInfo) add(whatCamera)
            if (config.stripTimestamp) add(whatTimestamps)
            if (config.stripSoftwareInfo) add(whatSoftware)
        }.joinToString(", ")
        ConfirmDialog(
            title = stringResource(R.string.viewer_meta_strip_confirm_title),
            message = stringResource(R.string.viewer_meta_strip_confirm_message, what),
            confirmLabel = stringResource(R.string.viewer_meta_strip),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showStripConfirm = false
                onStripFields(config)
                pendingStripConfig = null
            },
            onDismiss = { showStripConfirm = false },
            destructive = true,
        )
    }
}

@Composable
internal fun MetadataSection(
    label: String,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label.uppercase(),
                color = FgMute, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            if (actionLabel != null) {
                Text(
                    actionLabel,
                    color = if (actionEnabled) ErrorColor else FgMute,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(enabled = actionEnabled, onClick = onAction),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBg, RoundedCornerShape(12.dp))
                .border(0.5.dp, Line2, RoundedCornerShape(12.dp)),
        ) {
            Column { content() }
        }
    }
}

@Composable
internal fun MetaRow(label: String, value: String, onEdit: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onEdit != null) it.clickable(onClick = onEdit) else it }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = FgMute, fontSize = 13.sp, modifier = Modifier.weight(0.4f))
        Row(
            modifier = Modifier.weight(0.6f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onEdit != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.cd_viewer_metadata_edit),
                    tint = Accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Toggleable category chips. Excludes tag 0 Favorites (own heart button) and 2 Videos (derived
 *  from mime type); labels reuse the gallery category-filter strings. */
private val EDITABLE_CATEGORY_TAGS = listOf(
    1 to R.string.gallery_filter_screenshots,
    3 to R.string.gallery_filter_live_photos,
    4 to R.string.gallery_filter_motion_photos,
    5 to R.string.gallery_filter_selfies,
    6 to R.string.gallery_filter_portraits,
    7 to R.string.gallery_filter_bursts,
    8 to R.string.gallery_filter_panoramas,
    9 to R.string.gallery_filter_raw,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditor(tags: Set<Int>, onToggle: (Int, Boolean) -> Unit) {
    Column {
        Text(
            stringResource(R.string.gallery_filter_categories).uppercase(),
            color = FgMute, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EDITABLE_CATEGORY_TAGS.forEach { (tagId, labelRes) ->
                val selected = tagId in tags
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) Accent.copy(alpha = 0.18f) else CardBg)
                        .border(0.5.dp, if (selected) Accent else Line2, RoundedCornerShape(20.dp))
                        .clickable { onToggle(tagId, !selected) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (selected) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = null,
                        tint = if (selected) Accent else FgMute,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(labelRes),
                        color = if (selected) FgPrimary else FgMute,
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

internal fun formatItemDate(item: GalleryItem): String = formatMs(item.captureTimeMs)

internal fun formatMs(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))

/** EXIF stores timestamps as "yyyy:MM:dd HH:mm:ss" (the date uses colons too). Show the
 *  conventional "yyyy-MM-dd HH:mm" instead; fall back to the raw value if it doesn't parse. */
internal fun formatExifDateTime(raw: String): String = try {
    val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw.trim())
    if (parsed != null) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(parsed) else raw
} catch (_: Exception) { raw }

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000     -> "%.0f KB".format(bytes / 1_000.0)
    else               -> "$bytes B"
}
