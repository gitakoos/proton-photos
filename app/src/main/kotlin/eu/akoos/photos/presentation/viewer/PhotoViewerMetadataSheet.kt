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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
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
import eu.akoos.photos.presentation.util.formatBytes
import eu.akoos.photos.util.ExifDateFormat
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.PhotoMetadata
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@Composable
internal fun PhotoMetadataSheet(
    item: GalleryItem?,
    exif: PhotoMetadata?,
    /** Geocoded place name for the Location row (device EXIF or a cloud photo's stored fix), or null
     *  while it resolves / when the photo has no GPS — the row then keeps its dash placeholder. */
    place: String? = null,
    /** The fix [place] names, resolved by the ViewModel across device EXIF and a cloud photo's stored
     *  location. Null while it resolves / when the photo has no GPS, and the coordinate rows then fall
     *  back to what the file's own EXIF carries. */
    resolvedGps: DetailsGps? = null,
    /** Device folder the photo lives in (LocalOnly / Synced). Null / blank for cloud-only items, which
     *  hides the Local folder row. */
    localFolder: String? = null,
    /** Names of every cloud album the photo belongs to, filled lazily. Empty hides the Cloud albums row. */
    cloudAlbums: List<String> = emptyList(),
    /** Resolution + length of a cloud-only video, read off its decrypted full-res (it has no EXIF or
     *  on-device media row). Null for other items / until it downloads. */
    cloudVideoMeta: CloudVideoMeta? = null,
    isStripping: Boolean,
    /** Resolved full-res byte count, used for the Size row when [CloudPhoto.sizeBytes] is 0 (the
     *  server batch API returns null size for some video uploads). Null until the download lands. */
    cloudSizeFallback: Long? = null,
    onStripFields: (MetadataStripConfig) -> Unit,
    onRenameClick: () -> Unit = {},
    /** Opens the date + place metadata editor from the Date and Place rows. Null collapses both
     *  pencils (a shared-with-me album guest), mirroring the rename pencil on the file row. */
    onEditMetadata: (() -> Unit)? = null,
    /** The photo's category PhotoTag ids + toggle callback. Every kind of photo carries categories;
     *  where a toggle is stored differs (Drive for a backed-up one, the device for a device-only
     *  one) and the caller owns that. */
    photoTags: Set<Int> = emptySet(),
    onToggleTag: (Int, Boolean) -> Unit = { _, _ -> },
    /** True for a vaulted photo the vault records a Drive copy for. Such a photo arrives here as a
     *  device-only item — vaulting removes the MediaStore row that paired it with its cloud half — so
     *  the Source row would otherwise say it is the only copy while the badge above says it is not. */
    hasCloudCopy: Boolean = false,
) {
    if (item == null) return
    val isLocal = item is GalleryItem.LocalOnly || item is GalleryItem.Synced
    // Per-section Strip only applies to device-only photos: Synced already went through the
    // "Strip on upload" toggle and CloudOnly has no on-device EXIF to touch.
    val isDeviceOnly = item is GalleryItem.LocalOnly
    var showStripConfirm by remember { mutableStateOf(false) }
    var pendingStripConfig by remember { mutableStateOf<MetadataStripConfig?>(null) }

    // Every group is on screen at once, so the sheet takes most of the height it can get; the
    // remaining gap keeps the panel clear of the status bar and the content still scrolls inside
    // that ceiling for a photo carrying the full EXIF set.
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    androidx.compose.foundation.rememberScrollState().let { scrollState ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(scrollState)
                .navigationBarsPadding()
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
            val rowSource = stringResource(R.string.viewer_meta_row_source)
            val rowResolution = stringResource(R.string.viewer_meta_row_resolution)
            val rowDuration = stringResource(R.string.viewer_meta_row_duration)
            val rowPlace = stringResource(R.string.viewer_meta_row_place)
            val stripLabel = stringResource(R.string.viewer_meta_strip)

            // One unified value set across device / synced / cloud and photo / video: each one is
            // sourced from whichever side carries it, so the groups below read the same for every
            // kind of item. Place and Resolution reserve their slot (like Size) and fill in once
            // resolved, rather than appearing only after they land.
            val localMedia = (item as? GalleryItem.LocalOnly)?.local ?: (item as? GalleryItem.Synced)?.local
            val cloud = (item as? GalleryItem.Synced)?.cloud ?: (item as? GalleryItem.CloudOnly)?.cloud
            val mimeType = localMedia?.mimeType ?: cloud?.mimeType ?: ""
            // Reads the sanitized capture time the timeline sorts and groups by, so the row shows the
            // same moment every list does for a photo.
            val dateMs = item.captureTimeMs
            val sizeBytes = when (item) {
                is GalleryItem.CloudOnly -> item.cloud.sizeBytes.takeIf { it > 0 } ?: cloudSizeFallback ?: 0L
                else -> localMedia?.sizeBytes ?: 0L
            }
            val resW = localMedia?.width?.takeIf { it > 0 } ?: exif?.width?.takeIf { it > 0 } ?: cloudVideoMeta?.width?.takeIf { it > 0 }
            val resH = localMedia?.height?.takeIf { it > 0 } ?: exif?.height?.takeIf { it > 0 } ?: cloudVideoMeta?.height?.takeIf { it > 0 }
            val source = when {
                item is GalleryItem.LocalOnly && !hasCloudCopy ->
                    stringResource(R.string.viewer_meta_source_device_only)
                item is GalleryItem.CloudOnly -> stringResource(R.string.viewer_meta_source_cloud)
                else -> stringResource(R.string.viewer_meta_source_backed_up)
            }

            MetadataSection(stringResource(R.string.viewer_meta_section_file_info)) {
                MetaRow(rowFile, localMedia?.displayName ?: cloud?.displayName ?: "", onEdit = onRenameClick)
                MetaRow(rowSize, if (sizeBytes > 0) formatBytes(sizeBytes) else "—")
                MetaRow(rowType, mimeType)
                (localFolder ?: localMedia?.bucketName)?.takeIf { it.isNotBlank() }?.let {
                    MetaRow(stringResource(R.string.viewer_meta_row_local_folder), it)
                }
                cloudAlbums.takeIf { it.isNotEmpty() }?.let {
                    MetaRow(stringResource(R.string.viewer_meta_row_cloud_albums), it.joinToString(", "))
                }
                MetaRow(rowSource, source)
            }

            // Resolution already merges the device, EXIF and cloud-video readings of the same width
            // and height, so it is the one geometry row and the raw EXIF dimensions stay out. A
            // video keeps the section on screen with dash placeholders while a cloud-only read
            // resolves, so its two rows fill in rather than pop in.
            val isVideo = mimeType.startsWith("video/")
            if (resW != null || isVideo || exif?.orientation != null) {
                MetadataSection(stringResource(R.string.viewer_meta_section_image)) {
                    MetaRow(rowResolution, if (resW != null && resH != null) "$resW × $resH" else "—")
                    if (isVideo) {
                        val durMs = videoDurationMs(localMedia?.duration, cloud?.durationMs, cloudVideoMeta?.durationMs)
                        MetaRow(rowDuration, if (durMs != null) eu.akoos.photos.presentation.util.formatVideoTime(durMs) else "—")
                    }
                    val orientNormal = stringResource(R.string.viewer_meta_orientation_normal)
                    val orient180 = stringResource(R.string.viewer_meta_orientation_180)
                    val orient90Cw = stringResource(R.string.viewer_meta_orientation_90_cw)
                    val orient90Ccw = stringResource(R.string.viewer_meta_orientation_90_ccw)
                    exif?.orientation?.let {
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

            CategoryEditor(tags = photoTags, onToggle = onToggleTag)

            // The Date row is the capture moment the app itself works with and the one the pencil
            // edits, so it leads. The raw EXIF pair earns a row only where it disagrees with that
            // moment, which keeps an untouched photo down to a single timestamp.
            val exifTaken = exif?.dateTimeOriginal?.takeIf { exifDiffersFrom(it, dateMs) }
            val exifModified = exif?.let { meta ->
                meta.dateTime?.takeIf { it != meta.dateTimeOriginal && exifDiffersFrom(it, dateMs) }
            }
            val hasExifDates = exif?.let { it.dateTime != null || it.dateTimeOriginal != null } == true
            MetadataSection(
                label = stringResource(R.string.viewer_meta_section_datetime),
                actionLabel = if (isDeviceOnly && hasExifDates) stripLabel else null,
                actionEnabled = !isStripping,
                onAction = {
                    pendingStripConfig = MetadataStripConfig(stripTimestamp = true)
                    showStripConfirm = true
                },
            ) {
                // Date and Place carry the editor pencil themselves: a wrong value is spotted on the
                // row that shows it, so the fix starts there rather than in a separate menu entry.
                MetaRow(rowDate, formatMsWithTime(dateMs), onEdit = onEditMetadata)
                exifTaken?.let { MetaRow(stringResource(R.string.viewer_meta_row_taken), formatExifDateTime(it)) }
                exifModified?.let { MetaRow(stringResource(R.string.viewer_meta_row_modified), formatExifDateTime(it)) }
            }

            // Keyed on the file's own EXIF, unlike the rows below: Strip removes what is written in
            // the file, and a fix held only in the cloud is not in there to take out.
            val hasGps = exif?.gpsLatitude != null || exif?.gpsLongitude != null
            MetadataSection(
                label = stringResource(R.string.viewer_meta_section_location),
                actionLabel = if (isDeviceOnly && hasGps) stripLabel else null,
                actionEnabled = !isStripping,
                onAction = {
                    pendingStripConfig = MetadataStripConfig(stripGps = true)
                    showStripConfirm = true
                },
            ) {
                // The place name is the coarse reading of the very fix the coordinates state
                // exactly, so both belong under one heading and take the same resolved fix.
                MetaRow(rowPlace, place ?: "—", onEdit = onEditMetadata)
                val shown = shownCoordinates(resolvedGps, exif)
                shown.latitude?.let {
                    MetaRow(stringResource(R.string.viewer_meta_row_latitude), "%.6f°".format(it))
                }
                shown.longitude?.let {
                    MetaRow(stringResource(R.string.viewer_meta_row_longitude), "%.6f°".format(it))
                }
                // Altitude has no cloud counterpart: the stored fix is a latitude and longitude pair,
                // so the file's own EXIF is the only place a height comes from.
                exif?.gpsAltitude?.let {
                    MetaRow(stringResource(R.string.viewer_meta_row_altitude), "%.1f m".format(it))
                }
            }

            if (exif != null && (
                    exif.make != null || exif.model != null || exif.lensModel != null ||
                        exif.focalLength != null || exif.aperture != null || exif.exposureTime != null ||
                        exif.isoSpeed != null || exif.flash != null || exif.whiteBalance != null
                    )
            ) {
                MetadataSection(
                    label = stringResource(R.string.viewer_meta_section_camera),
                    actionLabel = if (isDeviceOnly) stripLabel else null,
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

            if (exif != null &&
                (exif.description != null || exif.software != null || exif.artist != null || exif.copyright != null)
            ) {
                MetadataSection(
                    label = stringResource(R.string.viewer_meta_section_software),
                    actionLabel = if (isDeviceOnly) stripLabel else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        // The section lists the artist and copyright rows alongside the software
                        // one, so its Strip has to clear both groups or it leaves behind rows it
                        // just offered to remove.
                        pendingStripConfig = MetadataStripConfig(
                            stripSoftwareInfo = true,
                            stripAuthorship = true,
                        )
                        showStripConfirm = true
                    },
                ) {
                    // The caption leads the section: it describes the photo itself, while the rows
                    // under it record the tool and the rights holder.
                    exif.description?.let { MetaRow(stringResource(R.string.viewer_meta_row_description), it) }
                    exif.software?.let { MetaRow(stringResource(R.string.viewer_meta_row_software), it) }
                    exif.artist?.let { MetaRow(stringResource(R.string.viewer_meta_row_artist), it) }
                    exif.copyright?.let { MetaRow(stringResource(R.string.viewer_meta_row_copyright), it) }
                }
            }

            if (isLocal && exif != null) {
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
                                stripAuthorship = true,
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
        val whatAuthorship = stringResource(R.string.viewer_meta_strip_what_authorship)
        val what = buildList {
            if (config.stripGps) add(whatGps)
            if (config.stripCameraInfo) add(whatCamera)
            if (config.stripTimestamp) add(whatTimestamps)
            if (config.stripSoftwareInfo) add(whatSoftware)
            if (config.stripAuthorship) add(whatAuthorship)
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
 *  from mime type); labels reuse the gallery category-filter strings. One list for every photo,
 *  backed up or not, so a category offered on one is offered on all of them. */
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

/** Latitude and longitude the Location rows state, each null where nothing carries it. A null leaves
 *  that row out entirely rather than printing an empty degree value. */
internal data class ShownCoordinates(val latitude: Double?, val longitude: Double?)

/**
 * The coordinates to show under the place name.
 *
 * A [resolved] fix wins whole. It is the very fix the place row above is the coarse reading of, and for
 * a backed-up or cloud-only photo it is the only one there is: the file on Drive holds its location in
 * a revision the device never reads, so EXIF alone would name a place and no coordinates for it. With
 * no fix resolved the file's own [exif] pair stands, which covers a device photo whose read has landed
 * before the resolve has. A photo carrying neither shows no coordinate rows at all.
 */
internal fun shownCoordinates(resolved: DetailsGps?, exif: PhotoMetadata?): ShownCoordinates =
    if (resolved != null) ShownCoordinates(resolved.latitude, resolved.longitude)
    else ShownCoordinates(exif?.gpsLatitude, exif?.gpsLongitude)

/**
 * The length to show for a video, in milliseconds, or null to keep the row's dash.
 *
 * [local] is the on-device file's own reading and leads where there is one. [cloudStored] is the length
 * recorded on the cloud row, the same value the grid's duration pill reads, which is what a cloud-only
 * video has before any of its bytes are on the device. [fromBlob] is the reading taken off a decrypted
 * full-res, which only exists once that download lands. A non-positive value counts as no reading.
 */
internal fun videoDurationMs(local: Long?, cloudStored: Long?, fromBlob: Long?): Long? =
    local?.takeIf { it > 0 } ?: cloudStored?.takeIf { it > 0 } ?: fromBlob?.takeIf { it > 0 }

internal fun formatItemDate(item: GalleryItem): String = formatMs(item.captureTimeMs)

internal fun formatMs(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))

/** Capture moment down to the minute, in the locale's own order. The date + place editor renders its
 *  value the same way, so the row and the screen its pencil opens read alike. */
internal fun formatMsWithTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(ms))

/** EXIF stores timestamps as "yyyy:MM:dd HH:mm:ss" (the date uses colons too). Rendered like the
 *  capture row so the group reads as one; falls back to the raw value if it doesn't parse. */
internal fun formatExifDateTime(raw: String): String =
    parseExifDateTimeMs(raw)?.let { formatMsWithTime(it) } ?: raw

/** Milliseconds an EXIF timestamp lands on in the device zone, or null when it doesn't parse. */
internal fun parseExifDateTimeMs(raw: String): Long? = ExifDateFormat.fromExifLocal(raw, ZoneId.systemDefault())

/** True when a raw EXIF timestamp names a different minute than [ms], or is unreadable. A value that
 *  matches the capture moment already on screen would only repeat it, so its row stays out. */
private fun exifDiffersFrom(raw: String, ms: Long): Boolean {
    val parsed = parseExifDateTimeMs(raw) ?: return true
    return Math.floorDiv(parsed, 60_000L) != Math.floorDiv(ms, 60_000L)
}
