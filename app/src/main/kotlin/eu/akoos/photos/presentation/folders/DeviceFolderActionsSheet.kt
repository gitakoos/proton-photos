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

package eu.akoos.photos.presentation.folders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.albums.AlbumPhotoSortMode
import eu.akoos.photos.presentation.common.ActionSheetRow
import eu.akoos.photos.presentation.common.ActionSheetSectionHeading
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgPrimary
import kotlinx.coroutines.launch

/**
 * Every device-folder action as a drawer, so the hero header carries one control beside the count.
 * Sibling of the album's [eu.akoos.photos.presentation.albums.AlbumActionsSheet] and built to the
 * same recipe, but with its own parameter list: a folder has no sharing, no guest and no leave.
 *
 * Sections follow the app's action-sheet taxonomy: what runs on the folder's photos, then the
 * per-folder settings, then the sort. A folder has nothing irreversible to offer, so the fourth
 * group is absent here.
 *
 * The first group holds the folder's two back-up outcomes, a one-time upload to the stream or an
 * upload that also mirrors the folder as a Drive album, and the slideshow. The settings group
 * carries the per-folder preferences, each showing its current state as a trailing tick: the three
 * Settings otherwise owns alone, plus hiding the folder, which takes its card off the Albums grid and
 * moves its photos into the vault. Slideshow and the sort direction are the album actions a folder
 * can answer for itself, and they read the same rows and labels here as they do there.
 *
 * The rows that run on the folder's photos hang off their callback being non-null, so a folder with
 * nothing in it drops the whole first group and still offers every preference and the sort rather
 * than no drawer at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceFolderActionsSheet(
    sheetState: SheetState,
    isMirroredAsAlbum: Boolean,
    isExcludedFromBackup: Boolean,
    isHiddenFromTimeline: Boolean,
    /** True where the photos this surface stands for are the vault's rather than the device's, which
     *  is also when the hide row reveals them instead. A grid card and a folder screen opened from
     *  the grid both stand for what is on the device, so both leave this false even for a folder the
     *  vault holds other photos from. The weaker [isHiddenFromTimeline] only keeps a folder's photos
     *  out of the main feed. */
    isHiddenCard: Boolean,
    sortMode: AlbumPhotoSortMode,
    onDismiss: () -> Unit,
    onToggleMirrorAsAlbum: () -> Unit,
    onToggleExcludedFromBackup: () -> Unit,
    onToggleHiddenFromTimeline: () -> Unit,
    onToggleHiddenCard: () -> Unit,
    onSortSelected: (AlbumPhotoSortMode) -> Unit,
    /** Heads the drawer with the folder's name where several are in reach; the folder's own screen
     *  already says which folder this is, so it leaves this null and keeps the generic heading. */
    folderName: String? = null,
    /** True while a folder back-up runs: the back-up rows wear the ring and stop taking taps. */
    backupBusy: Boolean = false,
    /** Determinate share of that back-up, or null before it can report a count. */
    backupFraction: Float? = null,
    /** Back up every photo here; `asMirror` also opts the folder in to its Drive album. Null where
     *  the folder holds no photos, which drops the back-up group. */
    onBackUp: ((asMirror: Boolean) -> Unit)? = null,
    /** Null where the folder holds no photos, which drops the slideshow row. */
    onSlideshow: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // Slide the drawer away before the action lands, so a snackbar or system dialog never opens
    // behind a sheet that is still on screen.
    fun close(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Bg2,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        // Height-capped + scroll so a short device never clips the last row.
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                folderName?.let { "\"$it\"" } ?: stringResource(R.string.albums_more_actions),
                color = FgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            val hasPhotoActions = onBackUp != null || onSlideshow != null
            if (hasPhotoActions) {
                ActionSheetSectionHeading(
                    stringResource(R.string.action_sheet_actions_heading),
                    first = true,
                )
                if (onBackUp != null) {
                    // The two modes differ in what happens after this upload, which their titles
                    // alone cannot say, so both carry a second line.
                    ActionSheetRow(
                        icon = Icons.Default.CloudUpload,
                        title = stringResource(R.string.device_folder_backup_timeline),
                        subtitle = stringResource(R.string.device_folder_backup_timeline_sub),
                        onClick = { close { onBackUp(false) } },
                        enabled = !backupBusy,
                        busy = backupBusy,
                        busyFraction = backupFraction,
                    )
                    Spacer(Modifier.height(8.dp))
                    ActionSheetRow(
                        icon = Icons.Default.PhotoAlbum,
                        title = stringResource(R.string.device_folder_backup_mirror),
                        subtitle = stringResource(R.string.device_folder_backup_mirror_sub),
                        onClick = { close { onBackUp(true) } },
                        enabled = !backupBusy,
                        busy = backupBusy,
                        busyFraction = backupFraction,
                    )
                }
                if (onSlideshow != null) {
                    if (onBackUp != null) Spacer(Modifier.height(8.dp))
                    ActionSheetRow(
                        icon = Icons.Default.PlayArrow,
                        title = stringResource(R.string.viewer_play_slideshow),
                        onClick = { close(onSlideshow) },
                    )
                }
            }

            ActionSheetSectionHeading(
                stringResource(R.string.action_sheet_settings_heading),
                first = !hasPhotoActions,
            )
            // Each of the three writes the same preference set its Settings picker does, so a change
            // here and a change there are the same change. The tick is the current state.
            ActionSheetRow(
                icon = Icons.Default.Collections,
                title = stringResource(R.string.device_folder_mirror_as_album),
                onClick = { close(onToggleMirrorAsAlbum) },
                showCheck = isMirroredAsAlbum,
            )
            Spacer(Modifier.height(8.dp))
            ActionSheetRow(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.device_folder_exclude_from_backup),
                onClick = { close(onToggleExcludedFromBackup) },
                showCheck = isExcludedFromBackup,
            )
            Spacer(Modifier.height(8.dp))
            ActionSheetRow(
                icon = Icons.Default.VisibilityOff,
                title = stringResource(R.string.device_folder_hide_from_timeline),
                onClick = { close(onToggleHiddenFromTimeline) },
                showCheck = isHiddenFromTimeline,
            )
            Spacer(Modifier.height(8.dp))
            // Sits next to the row above and reaches much further, so it carries the one subtitle in
            // this group: which of the two the user is about to change is otherwise a guess from a
            // label alone.
            ActionSheetRow(
                icon = Icons.Default.FolderOff,
                title = stringResource(R.string.device_folder_hide_card),
                subtitle = stringResource(R.string.device_folder_hide_card_sub),
                onClick = { close(onToggleHiddenCard) },
                showCheck = isHiddenCard,
            )

            ActionSheetSectionHeading(stringResource(R.string.albums_sort_heading))
            AlbumPhotoSortMode.entries.forEachIndexed { index, mode ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                ActionSheetRow(
                    icon = when (mode) {
                        AlbumPhotoSortMode.NewestFirst -> Icons.Default.ArrowDownward
                        AlbumPhotoSortMode.OldestFirst -> Icons.Default.ArrowUpward
                    },
                    title = stringResource(
                        when (mode) {
                            AlbumPhotoSortMode.NewestFirst -> R.string.sort_newest_first
                            AlbumPhotoSortMode.OldestFirst -> R.string.sort_oldest_first
                        },
                    ),
                    onClick = { close { onSortSelected(mode) } },
                    showCheck = mode == sortMode,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
