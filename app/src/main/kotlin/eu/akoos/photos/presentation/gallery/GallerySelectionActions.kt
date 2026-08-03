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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import eu.akoos.photos.presentation.common.FavoriteActionState
import eu.akoos.photos.presentation.common.SelectionAction
import eu.akoos.photos.presentation.common.allLocalOnly
import eu.akoos.photos.presentation.common.anyCloudOnly
import eu.akoos.photos.presentation.common.anyLocalOnly
import eu.akoos.photos.presentation.common.favoriteSelectionAction
import eu.akoos.photos.presentation.common.favoriteTurnsOn
import eu.akoos.photos.presentation.common.hasDownloadable
import eu.akoos.photos.presentation.common.offlinePinnableLinkIds
import eu.akoos.photos.presentation.common.offlineTurnsOn
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.util.MetadataStripConfig

/**
 * Every bulk action the timeline offers over a multi-select, in the order the shared selection
 * drawer lists them: select-all first, then the everyday ones, then the destructive pair last.
 *
 * Each entry carries its own gate, so an action that cannot apply to the current selection is
 * absent rather than dead: Back up needs a photo that is still device-only, Download and the
 * offline row a cloud-only one, Edit metadata a device-only one, and Strip metadata a wholly
 * device-only selection (a synced photo keeps its Drive copy's EXIF, so stripping only the local
 * file would be a half-strip).
 *
 * Every label names what the tap does, and the two rows that toggle say which way this press goes.
 *
 * The metadata-strip picker is owned here so Strip metadata opens it directly, and is rendered
 * inline.
 */
@Composable
fun rememberGallerySelectionActions(
    selectedItems: Set<GalleryItem>,
    favoriteIds: Set<String>,
    offlinePinIds: Set<String>,
    favoriteState: FavoriteActionState,
    multiShareState: MultiShareState,
    multiDeleteState: MultiDeleteState,
    multiDownloadState: MultiDownloadState,
    multiStripState: MultiStripState,
    addToAlbumState: AddToAlbumState,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onHide: () -> Unit,
    onRequestDelete: () -> Unit,
    onDownload: () -> Unit,
    onMakeAvailableOffline: () -> Unit,
    onRequestAddToAlbum: () -> Unit,
    onToggleFavorite: () -> Unit,
    onBackUp: () -> Unit,
    onStripMetadata: (MetadataStripConfig) -> Unit,
    onEditMetadata: () -> Unit,
): List<SelectionAction> {
    val sharing = multiShareState as? MultiShareState.Working
    val isAddingToAlbum = addToAlbumState is AddToAlbumState.Working
    val isDownloading = multiDownloadState is MultiDownloadState.Working
    val isStripping = multiStripState is MultiStripState.Working
    val isDeleting = multiDeleteState is MultiDeleteState.Working

    var showStripPicker by remember { mutableStateOf(false) }

    // Which way the offline row goes, so it names the press rather than the state: a pin while
    // anything pinnable in the selection is still un-pinned, a removal once none is.
    val offlinePinsSelection = remember(selectedItems, offlinePinIds) {
        offlineTurnsOn(offlinePinnableLinkIds(selectedItems), offlinePinIds)
    }

    val actions = buildList {
        add(
            SelectionAction(
                icon = Icons.Default.SelectAll,
                label = stringResource(
                    if (allSelected) R.string.gallery_deselect_all else R.string.select_all,
                ),
                onClick = onSelectAll,
            )
        )
        add(
            SelectionAction(
                icon = Icons.Default.Share,
                label = stringResource(R.string.sel_label_share),
                working = sharing != null,
                progress = sharing?.let { if (it.total > 0) it.done.toFloat() / it.total else 0f },
                enabled = sharing == null,
                onClick = onShare,
            )
        )
        add(
            SelectionAction(
                icon = Icons.Default.PhotoAlbum,
                label = stringResource(R.string.gallery_add_to_album),
                working = isAddingToAlbum,
                enabled = !isAddingToAlbum,
                onClick = onRequestAddToAlbum,
            )
        )
        add(
            favoriteSelectionAction(
                turnsOn = remember(selectedItems, favoriteIds) {
                    favoriteTurnsOn(selectedItems, favoriteIds)
                },
                state = favoriteState,
                onClick = onToggleFavorite,
            )
        )
        // Back up the not-yet-uploaded (LocalOnly) photos in the selection.
        if (anyLocalOnly(selectedItems)) {
            add(
                SelectionAction(
                    icon = Icons.Default.CloudUpload,
                    label = stringResource(R.string.sel_label_back_up),
                    onClick = onBackUp,
                )
            )
        }
        // Download / offline apply to cloud-only photos (no local file yet): the offline pin only
        // fetches bytes that aren't already on the device.
        if (hasDownloadable(selectedItems)) {
            add(
                SelectionAction(
                    icon = Icons.Default.FileDownload,
                    label = stringResource(R.string.sel_label_download),
                    working = isDownloading,
                    enabled = !isDownloading,
                    onClick = onDownload,
                )
            )
        }
        if (anyCloudOnly(selectedItems)) {
            add(
                SelectionAction(
                    icon = Icons.Default.OfflinePin,
                    label = stringResource(
                        if (offlinePinsSelection) R.string.offline_make_available
                        else R.string.offline_remove,
                    ),
                    onClick = onMakeAvailableOffline,
                )
            )
        }
        // The editor writes exactly the device-only photos and names the count it lands on, so a
        // mixed selection keeps the entry instead of losing it without a word.
        if (anyLocalOnly(selectedItems)) {
            add(
                SelectionAction(
                    icon = Icons.Default.EditNote,
                    label = stringResource(R.string.metadata_editor_edit_metadata),
                    onClick = onEditMetadata,
                )
            )
        }
        // The strip stays behind an all-device-only selection: a Synced photo keeps its Drive
        // copy's EXIF, so stripping just the local file is a misleading half-strip.
        if (allLocalOnly(selectedItems)) {
            add(
                SelectionAction(
                    icon = Icons.Default.PrivacyTip,
                    label = stringResource(R.string.gallery_strip_metadata),
                    working = isStripping,
                    enabled = !isStripping,
                    onClick = { showStripPicker = true },
                )
            )
        }
        // Offered for any non-empty selection: a photo that lives only on this device moves into the
        // vault, one with a cloud copy is filtered by linkId.
        if (selectedItems.isNotEmpty()) {
            add(
                SelectionAction(
                    icon = Icons.Default.VisibilityOff,
                    label = stringResource(R.string.sel_label_hide),
                    enabled = !isDeleting,
                    onClick = onHide,
                )
            )
        }
        add(
            SelectionAction(
                icon = Icons.Default.DeleteOutline,
                label = stringResource(R.string.sel_label_delete),
                tint = ErrorColor,
                working = isDeleting,
                enabled = !isDeleting,
                onClick = onRequestDelete,
            )
        )
    }

    if (showStripPicker) {
        MetadataStripPickerDialog(
            onConfirm = {
                showStripPicker = false
                onStripMetadata(it)
            },
            onDismiss = { showStripPicker = false },
        )
    }

    return actions
}

/**
 * Field picker for the manual multi-select metadata strip, shared by the timeline, Search and the
 * device-folder view. It stands on its own, decoupled from the upload-strip settings: the user ticks
 * exactly which categories to remove from the selected device photos and [onConfirm] fires with that
 * config. Timestamps default off so capture dates are kept. Confirm is disabled until a box is ticked,
 * so an empty config never leaves this dialog.
 */
@Composable
fun MetadataStripPickerDialog(
    onConfirm: (MetadataStripConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    var stripGps by remember { mutableStateOf(true) }
    var stripCamera by remember { mutableStateOf(true) }
    var stripTimestamp by remember { mutableStateOf(false) }
    var stripSoftware by remember { mutableStateOf(true) }
    // Ticked by default so a strip clears the credit fields; unticking it is how a user keeps the
    // author name and rights on their own photos.
    var stripAuthorship by remember { mutableStateOf(true) }
    val anyChecked = stripGps || stripCamera || stripTimestamp || stripSoftware || stripAuthorship
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor    = colors.cardBg,
        titleContentColor = colors.fgPrimary,
        textContentColor  = colors.fgDim,
        title = {
            Text(stringResource(R.string.viewer_meta_strip_confirm_title), fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                StripPickerRow(stringResource(R.string.settings_strip_gps), stripGps) { stripGps = it }
                StripPickerRow(stringResource(R.string.settings_strip_camera), stripCamera) { stripCamera = it }
                StripPickerRow(stringResource(R.string.settings_strip_timestamp), stripTimestamp) { stripTimestamp = it }
                StripPickerRow(stringResource(R.string.settings_strip_software), stripSoftware) { stripSoftware = it }
                StripPickerRow(stringResource(R.string.settings_strip_authorship), stripAuthorship) { stripAuthorship = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        MetadataStripConfig(
                            stripGps          = stripGps,
                            stripCameraInfo   = stripCamera,
                            stripTimestamp    = stripTimestamp,
                            stripSoftwareInfo = stripSoftware,
                            stripAuthorship   = stripAuthorship,
                        )
                    )
                },
                enabled = anyChecked,
            ) {
                Text(
                    stringResource(R.string.viewer_meta_strip),
                    color = if (anyChecked) ErrorColor else colors.fgDim,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = colors.fgDim)
            }
        },
    )
}

@Composable
private fun StripPickerRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = colors.accent),
        )
        Spacer(Modifier.size(8.dp))
        Text(label, color = colors.fgPrimary, fontSize = 14.sp)
    }
}
