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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.cardFill
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.CardBg
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.util.formatBytes
import eu.akoos.photos.presentation.util.formatDateTimeMinute
import eu.akoos.photos.util.ExifDateFormat
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.PhotoMetadata
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * The details sheet. One sheet with pages: [pages] is the stack the caller holds (so the sheet's back
 * gesture can step back a page before it closes), its last entry the page on screen. The main page sums
 * the photo up; each edit opens as a page of its own in the same sheet, never as a second sheet on top.
 */
@Composable
internal fun PhotoMetadataSheet(
    item: GalleryItem?,
    exif: PhotoMetadata?,
    /** Geocoded place name for the Location row (device EXIF or a cloud photo's stored fix), or null
     *  while it resolves / when the photo has no GPS. */
    place: String? = null,
    /** The fix [place] names, resolved by the ViewModel across device EXIF and a cloud photo's stored
     *  location. Null while it resolves / when the photo has no GPS, and the coordinate rows then fall
     *  back to what the file's own EXIF carries. */
    resolvedGps: DetailsGps? = null,
    /** Device folder the photo lives in (LocalOnly / Synced). Null / blank for cloud-only items. */
    localFolder: String? = null,
    /** Names of every cloud album the photo belongs to, filled lazily. */
    cloudAlbums: List<String> = emptyList(),
    /** Resolution + length of a cloud-only video, read off its decrypted full-res (it has no EXIF or
     *  on-device media row). Null for other items / until it downloads. */
    cloudVideoMeta: CloudVideoMeta? = null,
    isStripping: Boolean,
    /** Resolved full-res byte count, used for the size when [CloudPhoto.sizeBytes] is 0 (the server
     *  batch API returns null size for some video uploads). Null until the download lands. */
    cloudSizeFallback: Long? = null,
    onStripFields: (MetadataStripConfig) -> Unit,
    pages: List<DetailsPage>,
    onPagesChange: (List<DetailsPage>) -> Unit,
    /** True when the date and the place of this photo can be changed here; false hides both Adjust
     *  buttons (a shared-with-me album guest, a photo no editor can write). */
    metadataEditable: Boolean = false,
    isReadOnlyAlbum: Boolean = false,
    /** Called once a date or place edit has been written, so the caller reads the photo again. */
    onMetadataSaved: () -> Unit = {},
    /** The photo's category PhotoTag ids + toggle callback. Where a toggle is stored differs (Drive for
     *  a backed-up photo, the device for a device-only one) and the caller owns that. */
    photoTags: Set<Int> = emptySet(),
    onToggleTag: (Int, Boolean) -> Unit = { _, _ -> },
    /** True for a vaulted photo the vault records a Drive copy for. Such a photo arrives here as a
     *  device-only item, so the storage line would otherwise say it is the only copy. */
    hasCloudCopy: Boolean = false,
    /** The People box. Emitting nothing leaves it out, gap included. */
    peopleBox: @Composable () -> Unit = {},
    /** The Labels section of the main page, handed the way to the Labels page. Null leaves labels out
     *  (a vaulted or hidden photo, someone else's album). */
    labelsSection: (@Composable (onEdit: () -> Unit) -> Unit)? = null,
    /** The Labels page, drawn whole by the caller and handed the way back. */
    labelsPage: (@Composable (onClose: () -> Unit) -> Unit)? = null,
    /** The File name page's form under its title, handed the way back. Null leaves renaming out. */
    renamePage: (@Composable (onClose: () -> Unit) -> Unit)? = null,
    /** Whether the map may show. The caller turns it off for a photo whose place stays private, since
     *  the classic style fetches map tiles around the spot. */
    showMapBox: Boolean = false,
    /** Opens the full map centred on the photo's fix from the map. Null leaves the map inert. */
    onOpenMap: ((latitude: Double, longitude: Double) -> Unit)? = null,
) {
    if (item == null) return
    val page = pages.lastOrNull() ?: DetailsPage.Main
    val push = { next: DetailsPage -> onPagesChange(pages + next) }
    val back = { onPagesChange(pages.dropLast(1).ifEmpty { listOf(DetailsPage.Main) }) }
    val home = { onPagesChange(listOf(DetailsPage.Main)) }
    // Another photo starts on the main page; the identity is the file, so the item a write refreshes
    // keeps the page it is on.
    LaunchedEffect(detailsKey(item)) { if (pages != listOf(DetailsPage.Main)) home() }

    val isLocal = item is GalleryItem.LocalOnly || item is GalleryItem.Synced
    val localMedia = (item as? GalleryItem.LocalOnly)?.local ?: (item as? GalleryItem.Synced)?.local
    val cloud = (item as? GalleryItem.Synced)?.cloud ?: (item as? GalleryItem.CloudOnly)?.cloud
    val mimeType = localMedia?.mimeType ?: cloud?.mimeType ?: ""
    val isVideo = mimeType.startsWith("video/")
    val fileName = localMedia?.displayName ?: cloud?.displayName ?: ""
    // The sanitized capture time the timeline sorts and groups by, so the sheet shows the same moment
    // every list does for a photo.
    val dateMs = item.captureTimeMs
    val sizeBytes = when (item) {
        is GalleryItem.CloudOnly -> item.cloud.sizeBytes.takeIf { it > 0 } ?: cloudSizeFallback ?: 0L
        else -> localMedia?.sizeBytes ?: 0L
    }
    val resW = localMedia?.width?.takeIf { it > 0 } ?: exif?.width?.takeIf { it > 0 } ?: cloudVideoMeta?.width?.takeIf { it > 0 }
    val resH = localMedia?.height?.takeIf { it > 0 } ?: exif?.height?.takeIf { it > 0 } ?: cloudVideoMeta?.height?.takeIf { it > 0 }
    val source = when {
        item is GalleryItem.LocalOnly && !hasCloudCopy -> stringResource(R.string.viewer_meta_source_device_only)
        item is GalleryItem.CloudOnly -> stringResource(R.string.viewer_meta_source_cloud)
        else -> stringResource(R.string.viewer_meta_source_backed_up)
    }
    val folder = (localFolder ?: localMedia?.bucketName)?.takeIf { it.isNotBlank() }
    val where = folder ?: cloudAlbums.takeIf { it.isNotEmpty() }?.joinToString(", ")
    val shown = shownCoordinates(resolvedGps, exif)
    val privateItems = if (isLocal && exif != null) privateInfoItems(exif) else emptyList()

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState.depth > initialState.depth
            val spec = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            (slideInHorizontally(spec) { if (forward) it else -it / 3 } + fadeIn())
                .togetherWith(slideOutHorizontally(spec) { if (forward) -it / 3 else it } + fadeOut())
                .using(SizeTransform(clip = false) { _, _ -> spring(stiffness = Spring.StiffnessMediumLow) })
        },
        label = "detailsPage",
    ) { shownPage ->
        when (shownPage) {
            DetailsPage.Main -> DetailsPageFrame {
                DetailsPageHeader(stringResource(R.string.viewer_menu_details))
                val durationMs = if (isVideo) videoDurationMs(localMedia?.duration, cloud?.durationMs, cloudVideoMeta?.durationMs) else null
                DetailsSummaryCard(
                    dateMs = dateMs,
                    fileName = fileName,
                    facts = listOfNotNull(
                        formatOf(mimeType).takeIf { it.isNotBlank() },
                        if (resW != null && resH != null) megapixelsText(resW, resH) else null,
                        if (resW != null && resH != null) "$resW × $resH" else null,
                        durationMs?.let { eu.akoos.photos.presentation.util.formatVideoTime(it) },
                        sizeBytes.takeIf { it > 0 }?.let { formatBytes(it) },
                    ),
                    source = source,
                    where = where,
                    onAdjust = if (metadataEditable) { { push(DetailsPage.Date) } } else null,
                    onRename = if (renamePage != null) { { push(DetailsPage.Rename) } } else null,
                    onAllDetails = { push(DetailsPage.All) },
                )
                peopleBox()
                val mapPoint = if (showMapBox) mappablePoint(shown.latitude, shown.longitude) else null
                if (mapPoint != null) {
                    DetailsMapBox(
                        latitude = mapPoint.latitude,
                        longitude = mapPoint.longitude,
                        placeLabel = place,
                        onOpen = onOpenMap?.let { open -> { open(mapPoint.latitude, mapPoint.longitude) } },
                        onAdjust = if (metadataEditable) { { push(DetailsPage.Place) } } else null,
                    )
                } else if (showMapBox && metadataEditable && mimeType.startsWith("image/")) {
                    DetailsAddLocation(onClick = { push(DetailsPage.Place) })
                }
                labelsSection?.invoke { push(DetailsPage.Labels) }
                val categoryNames = EDITABLE_CATEGORY_TAGS.filter { it.first in photoTags }.map { stringResource(it.second) }
                DetailsChipSection(
                    title = stringResource(R.string.gallery_filter_categories),
                    chips = categoryNames,
                    onEdit = { push(DetailsPage.Categories) },
                )
            }

            DetailsPage.Date -> DetailsDatePage(
                item = item,
                isReadOnlyAlbum = isReadOnlyAlbum,
                onBack = back,
                onSaved = { onMetadataSaved(); back() },
            )

            DetailsPage.Place -> DetailsPlacePage(
                item = item,
                isReadOnlyAlbum = isReadOnlyAlbum,
                fallbackLatitude = shown.latitude,
                fallbackLongitude = shown.longitude,
                onBack = back,
                onSaved = { onMetadataSaved(); back() },
            )

            DetailsPage.Labels -> DetailsPageFrame { labelsPage?.invoke(back) }

            DetailsPage.Categories -> DetailsPageFrame {
                DetailsPageHeader(stringResource(R.string.gallery_filter_categories), onBack = back)
                DetailsCard {
                    EDITABLE_CATEGORY_TAGS.forEachIndexed { index, (tagId, labelRes) ->
                        val selected = tagId in photoTags
                        if (index > 0) DetailsDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clickable { onToggleTag(tagId, !selected) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(labelRes), color = FgPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (selected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            DetailsPage.Rename -> DetailsPageFrame {
                DetailsPageHeader(stringResource(R.string.rename_sheet_title), onBack = back)
                renamePage?.invoke(back)
            }

            DetailsPage.All -> DetailsPageFrame {
                DetailsPageHeader(stringResource(R.string.viewer_meta_all_details), onBack = back)
                DetailsAllSections(
                    exif = exif,
                    dateMs = dateMs,
                    place = place,
                    shown = shown,
                    mimeType = mimeType,
                    isVideo = isVideo,
                    resW = resW,
                    resH = resH,
                    videoDurationMs = if (isVideo) videoDurationMs(localMedia?.duration, cloud?.durationMs, cloudVideoMeta?.durationMs) else null,
                    fileName = fileName,
                    sizeBytes = sizeBytes,
                    driveSizeBytes = (item as? GalleryItem.Synced)?.cloud?.sizeBytes ?: 0L,
                    folder = folder,
                    cloudAlbums = cloudAlbums,
                    source = source,
                )
                if (privateItems.isNotEmpty()) {
                    DetailsDangerButton(
                        label = stringResource(R.string.details_remove_private),
                        enabled = !isStripping,
                        onClick = { push(DetailsPage.Private) },
                    )
                }
            }

            DetailsPage.Private -> DetailsPageFrame {
                DetailsPageHeader(stringResource(R.string.details_remove_private), onBack = back)
                DetailsPrivatePage(
                    items = privateItems,
                    isStripping = isStripping,
                    onRemove = { config ->
                        onStripFields(config)
                        home()
                    },
                )
            }
        }
    }
}

/** The pages of the details sheet. [depth] orders them for the slide: a deeper page enters from the end. */
internal enum class DetailsPage(val depth: Int) {
    Main(0), Date(1), Place(1), Labels(1), Categories(1), Rename(1), All(1), Private(2),
}

/** The file a details page belongs to, stable across the item a write refreshes. */
private fun detailsKey(item: GalleryItem): String = when (item) {
    is GalleryItem.LocalOnly -> item.local.uri
    is GalleryItem.Synced -> item.local.uri
    is GalleryItem.CloudOnly -> item.cloud.linkId
}

/** Every field the file carries, read only, grouped; empty groups are left out. */
@Composable
private fun DetailsAllSections(
    exif: PhotoMetadata?,
    dateMs: Long,
    place: String?,
    shown: ShownCoordinates,
    mimeType: String,
    isVideo: Boolean,
    resW: Int?,
    resH: Int?,
    videoDurationMs: Long?,
    fileName: String,
    sizeBytes: Long,
    driveSizeBytes: Long,
    folder: String?,
    cloudAlbums: List<String>,
    source: String,
) {
    // The raw EXIF pair earns a row only where it disagrees with the capture moment the app works with,
    // which keeps an untouched photo down to a single timestamp.
    val exifTaken = exif?.dateTimeOriginal?.takeIf { exifDiffersFrom(it, dateMs) }
    val exifModified = exif?.let { meta ->
        meta.dateTime?.takeIf { it != meta.dateTimeOriginal && exifDiffersFrom(it, dateMs) }
    }
    MetadataSection(stringResource(R.string.viewer_meta_section_datetime)) {
        MetaRow(stringResource(R.string.viewer_meta_row_date), formatMsWithTime(dateMs))
        exifTaken?.let { MetaRow(stringResource(R.string.viewer_meta_row_taken), formatExifDateTime(it)) }
        exifModified?.let { MetaRow(stringResource(R.string.viewer_meta_row_modified), formatExifDateTime(it)) }
    }
    if (place != null || shown.latitude != null || exif?.gpsAltitude != null) {
        MetadataSection(stringResource(R.string.viewer_meta_section_location)) {
            place?.let { MetaRow(stringResource(R.string.viewer_meta_row_place), it) }
            shown.latitude?.let { MetaRow(stringResource(R.string.viewer_meta_row_latitude), "%.6f°".format(it)) }
            shown.longitude?.let { MetaRow(stringResource(R.string.viewer_meta_row_longitude), "%.6f°".format(it)) }
            // Altitude has no cloud counterpart: the stored fix is a latitude and longitude pair.
            exif?.gpsAltitude?.let { MetaRow(stringResource(R.string.viewer_meta_row_altitude), "%.1f m".format(it)) }
        }
    }
    if (exif != null && (
            exif.make != null || exif.model != null || exif.lensModel != null ||
                exif.focalLength != null || exif.aperture != null || exif.exposureTime != null ||
                exif.isoSpeed != null || exif.flash != null || exif.whiteBalance != null
            )
    ) {
        MetadataSection(stringResource(R.string.viewer_meta_section_camera)) {
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
    // Resolution merges the device, EXIF and cloud-video readings of the same width and height, so it is
    // the one geometry row and the raw EXIF dimensions stay out.
    if (resW != null || isVideo || exif?.orientation in TURNED_ORIENTATIONS) {
        MetadataSection(stringResource(R.string.viewer_meta_section_image)) {
            MetaRow(stringResource(R.string.viewer_meta_row_resolution), if (resW != null && resH != null) resolutionText(resW, resH) else "—")
            if (isVideo) {
                MetaRow(
                    stringResource(R.string.viewer_meta_row_duration),
                    videoDurationMs?.let { eu.akoos.photos.presentation.util.formatVideoTime(it) } ?: "—",
                )
            }
            // Only a turned photo says so: upright is every photo's default.
            val orientRes = when (exif?.orientation) {
                3 -> R.string.viewer_meta_orientation_180
                6 -> R.string.viewer_meta_orientation_90_cw
                8 -> R.string.viewer_meta_orientation_90_ccw
                else -> null
            }
            orientRes?.let { MetaRow(stringResource(R.string.viewer_meta_row_orientation), stringResource(it)) }
        }
    }
    MetadataSection(stringResource(R.string.viewer_meta_section_file_info)) {
        MetaRow(stringResource(R.string.viewer_meta_row_file), fileName)
        // A compressed or stripped upload differs from the phone's copy, so name both sizes.
        if (sizeBytes > 0 && driveSizeBytes > 0 && formatBytes(driveSizeBytes) != formatBytes(sizeBytes)) {
            MetaRow(stringResource(R.string.viewer_meta_row_size_device), formatBytes(sizeBytes))
            MetaRow(stringResource(R.string.viewer_meta_row_size_drive), formatBytes(driveSizeBytes))
        } else {
            MetaRow(stringResource(R.string.viewer_meta_row_size), if (sizeBytes > 0) formatBytes(sizeBytes) else "-")
        }
        MetaRow(stringResource(R.string.viewer_meta_row_type), formatOf(mimeType))
        folder?.let { MetaRow(stringResource(R.string.viewer_meta_row_local_folder), it) }
        cloudAlbums.takeIf { it.isNotEmpty() }?.let {
            MetaRow(stringResource(R.string.viewer_meta_row_cloud_albums), it.joinToString(", "))
        }
        MetaRow(stringResource(R.string.viewer_meta_row_source), source)
    }
    if (exif != null &&
        (exif.description != null || exif.software != null || exif.artist != null || exif.copyright != null)
    ) {
        MetadataSection(stringResource(R.string.viewer_meta_section_software)) {
            exif.description?.let { MetaRow(stringResource(R.string.viewer_meta_row_description), it) }
            exif.software?.let { MetaRow(stringResource(R.string.viewer_meta_row_software), it) }
            exif.artist?.let { MetaRow(stringResource(R.string.viewer_meta_row_artist), it) }
            exif.copyright?.let { MetaRow(stringResource(R.string.viewer_meta_row_copyright), it) }
        }
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
                .background(cardFill(CardBg), RoundedCornerShape(12.dp))
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
                maxLines = 1, overflow = TextOverflow.Ellipsis,
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

/** The categories a photo can be put in by hand. Favorites (its own heart) and Videos (read from the file) are
 *  not among them; the names are the gallery's category names. */
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

/** One page of the details sheet: the sheet's side margins, its height ceiling and, unless [scroll] is off
 *  (a page holding a map, which needs its drags), a scroll of its own, so every page opens at its top. */
@Composable
internal fun DetailsPageFrame(scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** A page's title row: the way back on a page under the main one, the title, and the page's own action
 *  (Done) on the right, a spinner in its place while it saves. */
@Composable
internal fun DetailsPageHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    action: String? = null,
    actionEnabled: Boolean = true,
    busy: Boolean = false,
    onAction: () -> Unit = {},
) {
    Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.onboarding_back),
                tint = FgPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(6.dp)
                    .size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title,
            color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp).size(18.dp))
        } else if (action != null) {
            Text(
                action,
                color = if (actionEnabled) Accent else FgMute,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = actionEnabled, onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** The card every group on the details pages sits in. */
@Composable
internal fun DetailsCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardFill(CardBg))
            .border(0.5.dp, Line2, shape),
        content = content,
    )
}

/** The hairline between the rows of a [DetailsCard]. */
@Composable
internal fun DetailsDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Line2))
}

/** The Adjust button on the thing it changes: the date on the summary card, the map. */
@Composable
internal fun AdjustPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.details_adjust),
        color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SheetBg.copy(alpha = 0.55f))
            .background(Accent.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * The photo at a glance. Every line keeps to one line and the card to one height, whatever the names: the
 * day (with Adjust beside it), the weekday and time, the file name cut in the middle so its end shows, the
 * format tags in one sliding row, where the photo is kept, and the way to every other field.
 */
@Composable
private fun DetailsSummaryCard(
    dateMs: Long,
    fileName: String,
    facts: List<String>,
    source: String,
    where: String?,
    onAdjust: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onAllDetails: () -> Unit,
) {
    val locale = Locale.getDefault()
    val is24Hour = eu.akoos.photos.presentation.util.LocalIs24Hour.current
    val day = remember(dateMs, locale) {
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMMy"), locale).format(Date(dateMs))
    }
    val weekdayTime = remember(dateMs, locale, is24Hour) {
        val weekday = SimpleDateFormat("EEEE", locale).format(Date(dateMs)).replaceFirstChar { it.titlecase(locale) }
        "$weekday · " + eu.akoos.photos.presentation.util.formatTimeOfDay(dateMs, locale, is24Hour)
    }
    DetailsCard {
        Column(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Row(modifier = Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day,
                    color = FgPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onAdjust != null) {
                    Spacer(Modifier.width(10.dp))
                    AdjustPill(onAdjust)
                }
            }
            Text(
                weekdayTime,
                color = FgDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .height(22.dp)
                    .then(if (onRename != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onRename) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    fileName,
                    color = FgPrimary, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (onRename != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.rename_sheet_title), tint = Accent, modifier = Modifier.size(14.dp))
                }
            }
            if (facts.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    facts.forEach { fact ->
                        Text(
                            fact,
                            color = FgPrimary.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier
                                .background(FgPrimary.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
        DetailsDivider()
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildAnnotatedString {
                    append(source)
                    if (where != null) {
                        append(" · ")
                        withStyle(SpanStyle(color = FgMute)) { append(where) }
                    }
                },
                color = FgPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        DetailsDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clickable(onClick = onAllDetails)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.viewer_meta_all_details),
                color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = FgMute, modifier = Modifier.size(20.dp))
        }
    }
}

/** In the map's place for a photo without a location that can take one. */
@Composable
private fun DetailsAddLocation(onClick: () -> Unit) {
    DetailsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Place, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.details_add_location), color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** A full-width red action on a details page: remove the private info, remove the location. */
@Composable
internal fun DetailsDangerButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(ErrorColor.copy(alpha = 0.12f))
            .border(0.5.dp, ErrorColor.copy(alpha = 0.45f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ErrorColor, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The groups of private metadata a file can carry, each removable on its own. */
internal enum class PrivateInfoKind { LOCATION, CAMERA, SOFTWARE, AUTHOR, DATES }

/** One group the file carries now, with the value it holds, so the page shows what removing it takes. */
internal data class PrivateInfo(val kind: PrivateInfoKind, val value: String)

/** The private groups [exif] actually carries, in the order the page lists them. */
internal fun privateInfoItems(exif: PhotoMetadata, locale: Locale = Locale.getDefault()): List<PrivateInfo> = buildList {
    val lat = exif.gpsLatitude
    val lon = exif.gpsLongitude
    if (lat != null && lon != null) add(PrivateInfo(PrivateInfoKind.LOCATION, String.format(locale, "%.4f, %.4f", lat, lon)))
    val cameraName = listOfNotNull(exif.make, exif.model).joinToString(" ").takeIf { it.isNotBlank() }
    val shot = listOfNotNull(exif.aperture?.let { "f/$it" }, exif.exposureTime, exif.isoSpeed?.let { "ISO $it" })
        .joinToString(" · ").takeIf { it.isNotBlank() }
    val camera = listOfNotNull(cameraName, exif.lensModel).joinToString(", ").takeIf { it.isNotBlank() } ?: shot
    if (camera != null || exif.focalLength != null || exif.flash != null || exif.whiteBalance != null) {
        add(PrivateInfo(PrivateInfoKind.CAMERA, camera ?: exif.focalLength?.let { "${it}mm" }.orEmpty()))
    }
    exif.software?.let { add(PrivateInfo(PrivateInfoKind.SOFTWARE, it)) }
    listOfNotNull(exif.artist, exif.copyright).joinToString(", ").takeIf { it.isNotBlank() }
        ?.let { add(PrivateInfo(PrivateInfoKind.AUTHOR, it)) }
    (exif.dateTimeOriginal ?: exif.dateTime)?.let { add(PrivateInfo(PrivateInfoKind.DATES, formatExifDateTime(it))) }
}

/** The strip that removes exactly the [kinds] picked. */
internal fun stripConfigFor(kinds: Set<PrivateInfoKind>): MetadataStripConfig = MetadataStripConfig(
    stripGps = PrivateInfoKind.LOCATION in kinds,
    stripCameraInfo = PrivateInfoKind.CAMERA in kinds,
    stripTimestamp = PrivateInfoKind.DATES in kinds,
    stripSoftwareInfo = PrivateInfoKind.SOFTWARE in kinds,
    stripAuthorship = PrivateInfoKind.AUTHOR in kinds,
)

/** What the file holds now, each group with its value and a check; everything but the dates starts picked,
 *  since a photo's date is what the app sorts it by. The button names the count it removes. */
@Composable
private fun DetailsPrivatePage(
    items: List<PrivateInfo>,
    isStripping: Boolean,
    onRemove: (MetadataStripConfig) -> Unit,
) {
    var picked by remember(items) {
        mutableStateOf(items.map { it.kind }.filter { it != PrivateInfoKind.DATES }.toSet())
    }
    Text(stringResource(R.string.details_private_intro), color = FgDim, fontSize = 13.sp)
    DetailsCard {
        items.forEachIndexed { index, info ->
            if (index > 0) DetailsDivider()
            val selected = info.kind in picked
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clickable { picked = if (selected) picked - info.kind else picked + info.kind }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            when (info.kind) {
                                PrivateInfoKind.LOCATION -> R.string.viewer_meta_section_location
                                PrivateInfoKind.CAMERA -> R.string.viewer_meta_section_camera
                                PrivateInfoKind.SOFTWARE -> R.string.viewer_meta_row_software
                                PrivateInfoKind.AUTHOR -> R.string.details_private_author
                                PrivateInfoKind.DATES -> R.string.details_private_dates
                            },
                        ),
                        color = FgPrimary, fontSize = 14.sp,
                    )
                    Text(info.value, color = FgMute, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (selected) Accent else FgMute,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
    Text(stringResource(R.string.details_private_undo), color = FgMute, fontSize = 12.sp)
    DetailsDangerButton(
        label = pluralStringResource(R.plurals.details_private_remove, picked.size, picked.size),
        enabled = picked.isNotEmpty() && !isStripping,
        onClick = { onRemove(stripConfigFor(picked)) },
    )
}

/** The EXIF orientations that turn a photo, the only ones the details name. */
private val TURNED_ORIENTATIONS = setOf(3, 6, 8)

/** The format a person knows a file by, from its media type: JPEG for image/jpeg, MOV for video/quicktime. */
internal fun formatOf(mimeType: String): String {
    val sub = mimeType.substringAfter('/', "").lowercase()
    return when {
        sub.isEmpty() -> mimeType
        sub == "quicktime" -> "MOV"
        sub == "x-matroska" -> "MKV"
        sub == "x-adobe-dng" || sub == "dng" -> "DNG"
        sub.startsWith("x-") -> sub.removePrefix("x-").uppercase()
        else -> sub.substringBefore('+').uppercase()
    }
}

/** The megapixels alone, "12 MP", the decimal written the [locale]'s way below ten. */
internal fun megapixelsText(width: Int, height: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String =
    resolutionText(width, height, locale).substringAfter(" · ")

/** Width by height, with the megapixels a camera is known by: "4000 × 3000 · 12 MP", the decimal written the
 *  [locale]'s way. */
internal fun resolutionText(width: Int, height: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    val mp = width.toLong() * height / 1_000_000.0
    val mpText = if (mp >= 10) String.format(locale, "%.0f", mp) else String.format(locale, "%.1f", mp)
    return "$width × $height · $mpText MP"
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
internal fun formatMsWithTime(ms: Long): String = formatDateTimeMinute(ms)

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
