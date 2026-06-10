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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

// ── Metadata sheet ─────────────────────────────────────────────────────────────

@Composable
internal fun PhotoMetadataSheet(
    item: GalleryItem?,
    exif: PhotoMetadata?,
    isStripping: Boolean,
    /** On-disk size of the resolved full-res blob. The server batch API sometimes returns
     *  `link.size = null` for video uploads, leaving [CloudPhoto.sizeBytes] at 0 — this
     *  fallback fills the Size row with the real byte count once the viewer's background
     *  download finishes. Null when the item is local-only or the download hasn't landed. */
    cloudSizeFallback: Long? = null,
    onStripFields: (MetadataStripConfig) -> Unit,
    onRenameClick: () -> Unit = {},
) {
    if (item == null) return
    val isLocal = item is GalleryItem.LocalOnly || item is GalleryItem.Synced
    // Per-section Strip actions only apply to device-only photos. Synced items already
    // went through Settings → Privacy & Metadata's "Strip on upload" toggle, so the
    // local file's EXIF is the right copy and we don't want to teach users that a
    // post-upload strip changes anything visible on Drive (it doesn't — Drive holds
    // the already-stripped version). CloudOnly has no on-device EXIF to touch at all.
    val isDeviceOnly = item is GalleryItem.LocalOnly
    // Hidden-vault photos live under file:// in app-private storage and aren't safe to
    // rename via the normal MediaStore path. The dedicated rename function does work
    // but the underlying file's `__<captureMs>.ext` suffix bleeds back into the visible
    // display-name (the file's name IS the display-name for hidden items) and produces
    // confusing artifacts on re-rename ("file with that name already exists" hits when
    // the user un-knowingly tries to rename to the same stem + suffix combination).
    // Cleanest UX: hide the rename affordance entirely for hidden items.
    val itemUri = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced -> item.local.uri
        is GalleryItem.CloudOnly -> null
    }
    val isHiddenItem = itemUri?.startsWith("file://") == true
    val effectiveRenameClick: (() -> Unit)? = if (isHiddenItem) null else onRenameClick
    var showStripConfirm by remember { mutableStateOf(false) }
    var pendingStripConfig by remember { mutableStateOf<MetadataStripConfig?>(null) }

    androidx.compose.foundation.rememberScrollState().let { scrollState ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Details",
                color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )

            // ── File info ──────────────────────────────────────────────────
            MetadataSection("File Info") {
                when (item) {
                    is GalleryItem.LocalOnly -> {
                        MetaRow("File", item.local.displayName, onEdit = effectiveRenameClick)
                        MetaRow("Date", formatMs(item.local.dateTaken))
                        MetaRow("Size", formatBytes(item.local.sizeBytes))
                        MetaRow("Type", item.local.mimeType)
                        item.local.bucketName?.let { MetaRow("Album", it) }
                        MetaRow("Source", "On device only")
                    }
                    is GalleryItem.Synced -> {
                        MetaRow("File", item.local.displayName, onEdit = effectiveRenameClick)
                        // Date sourced from the CLOUD captureTime — Drive stores the original
                        // upload-time consistently while MediaStore's DATE_TAKEN can drift to
                        // the download moment on devices where the column gets silently reset
                        // (Samsung One UI is the worst offender). Falls back to the local
                        // dateTaken only when the cloud side has no captureTime (rare).
                        val displayDateMs = if (item.cloud.captureTime > 0L)
                            item.cloud.captureTime * 1000L
                        else item.local.dateTaken
                        MetaRow("Date", formatMs(displayDateMs))
                        MetaRow("Size", formatBytes(item.local.sizeBytes))
                        MetaRow("Type", item.local.mimeType)
                        item.local.bucketName?.let { MetaRow("Album", it) }
                        MetaRow("Source", "Backed up to Proton Drive")
                    }
                    is GalleryItem.CloudOnly -> {
                        MetaRow("File", item.cloud.displayName, onEdit = onRenameClick)
                        MetaRow("Date", formatMs(item.cloud.captureTime * 1000L))
                        val displaySize = item.cloud.sizeBytes.takeIf { it > 0 } ?: cloudSizeFallback ?: 0L
                        MetaRow("Size", if (displaySize > 0) formatBytes(displaySize) else "—")
                        MetaRow("Type", item.cloud.mimeType)
                        MetaRow("Source", "Proton Drive")
                    }
                }
            }

            // ── EXIF — Camera ──────────────────────────────────────────────
            if (exif != null && (exif.make != null || exif.model != null || exif.focalLength != null)) {
                MetadataSection(
                    label = "Camera",
                    actionLabel = if (isDeviceOnly) "Strip" else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        pendingStripConfig = MetadataStripConfig(stripCameraInfo = true)
                        showStripConfirm = true
                    },
                ) {
                    exif.make?.let { MetaRow("Make", it) }
                    exif.model?.let { MetaRow("Model", it) }
                    exif.lensModel?.let { MetaRow("Lens", it) }
                    exif.focalLength?.let { MetaRow("Focal length", "${it}mm") }
                    exif.aperture?.let { MetaRow("Aperture", "f/$it") }
                    exif.exposureTime?.let { MetaRow("Exposure", it) }
                    exif.isoSpeed?.let { MetaRow("ISO", it) }
                    exif.flash?.let { MetaRow("Flash", if (it and 0x01 != 0) "Fired" else "No flash") }
                    exif.whiteBalance?.let { MetaRow("White balance", if (it == 0) "Auto" else "Manual") }
                }
            }

            // ── EXIF — Location ────────────────────────────────────────────
            if (exif != null && (exif.gpsLatitude != null || exif.gpsLongitude != null)) {
                MetadataSection(
                    label = "Location",
                    actionLabel = if (isDeviceOnly) "Strip" else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        pendingStripConfig = MetadataStripConfig(stripGps = true)
                        showStripConfirm = true
                    },
                ) {
                    exif.gpsLatitude?.let {
                        MetaRow("Latitude", "%.6f°".format(it))
                    }
                    exif.gpsLongitude?.let {
                        MetaRow("Longitude", "%.6f°".format(it))
                    }
                    exif.gpsAltitude?.let {
                        MetaRow("Altitude", "%.1f m".format(it))
                    }
                }
            }

            // ── EXIF — Date & Time ─────────────────────────────────────────
            if (exif != null && (exif.dateTime != null || exif.dateTimeOriginal != null)) {
                MetadataSection(
                    label = "Date & Time",
                    actionLabel = if (isDeviceOnly) "Strip" else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        pendingStripConfig = MetadataStripConfig(stripTimestamp = true)
                        showStripConfirm = true
                    },
                ) {
                    exif.dateTimeOriginal?.let { MetaRow("Taken", it) }
                    exif.dateTime?.let { MetaRow("Modified", it) }
                }
            }

            // ── EXIF — Software ────────────────────────────────────────────
            if (exif != null && (exif.software != null || exif.artist != null || exif.copyright != null)) {
                MetadataSection(
                    label = "Software & Author",
                    actionLabel = if (isDeviceOnly) "Strip" else null,
                    actionEnabled = !isStripping,
                    onAction = {
                        pendingStripConfig = MetadataStripConfig(stripSoftwareInfo = true)
                        showStripConfirm = true
                    },
                ) {
                    exif.software?.let { MetaRow("Software", it) }
                    exif.artist?.let { MetaRow("Artist", it) }
                    exif.copyright?.let { MetaRow("Copyright", it) }
                }
            }

            // ── Image dimensions ───────────────────────────────────────────
            if (exif != null && exif.width != null && exif.height != null) {
                MetadataSection("Image") {
                    MetaRow("Dimensions", "${exif.width} × ${exif.height}")
                    exif.orientation?.let {
                        val orientLabel = when (it) {
                            1 -> "Normal"
                            3 -> "Rotated 180°"
                            6 -> "Rotated 90° CW"
                            8 -> "Rotated 90° CCW"
                            else -> "$it"
                        }
                        MetaRow("Orientation", orientLabel)
                    }
                }
            }

            // ── Strip all — quick action for local items ───────────────────
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
                            )
                            showStripConfirm = true
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Strip all private metadata",
                            color = ErrorColor, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Removes GPS, camera info and software fields from this file",
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
        val what = buildList {
            if (config.stripGps) add("GPS location")
            if (config.stripCameraInfo) add("camera info")
            if (config.stripTimestamp) add("timestamps")
            if (config.stripSoftwareInfo) add("software info")
        }.joinToString(", ")
        ConfirmDialog(
            title = "Strip metadata?",
            message = "This will permanently remove $what from the file on your device. This cannot be undone.",
            confirmLabel = "Strip",
            dismissLabel = "Cancel",
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

// ── Helpers ────────────────────────────────────────────────────────────────────

internal fun formatItemDate(item: GalleryItem): String = formatMs(item.captureTimeMs)

internal fun formatMs(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000     -> "%.0f KB".format(bytes / 1_000.0)
    else               -> "$bytes B"
}
