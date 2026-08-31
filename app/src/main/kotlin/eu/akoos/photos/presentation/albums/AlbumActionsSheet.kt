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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ActionSheetRow
import eu.akoos.photos.presentation.common.ActionSheetSectionHeading
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import kotlinx.coroutines.launch

/**
 * Every album action as a drawer, so the hero header carries one control beside the count instead of
 * a row of pills. Follows the app's other action sheets (see PhotoShareSheet): a pill per row with a
 * leading glyph, a label, and a trailing tick on the active sort.
 *
 * Sections follow the app's action-sheet taxonomy: what runs on the album's photos, then the album's
 * settings, then the sort, then what cannot be undone below a rule.
 *
 * Which rows appear follows who is looking: [canAddPhotos] covers an owner and an editor on a shared
 * album, [isSharedWithMe] swaps download for save-to-library, drops rename and adds leave. Both hides
 * are on every album, because each writes a client-side id set that touches neither Drive nor the
 * owner, so the settings group is never empty. They are separate sets and separate choices: keeping
 * the photos out of the main feed leaves the album's card on the grid and leaves the photos in
 * search, on the map, in the calendar and in every picker, which is why it carries a tick and
 * reverses on a second tap, while hiding the album is the stronger, one-way action below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumActionsSheet(
    sheetState: SheetState,
    isSharedWithMe: Boolean,
    /** True once this album has sharing set up. Ticks the share row with the same mark the active
     *  sort carries, so a share already in place is visible without opening it. */
    isShared: Boolean,
    canAddPhotos: Boolean,
    hasPhotos: Boolean,
    /** True while the download / save-to-library worker runs: that row wears the ring and stops taking taps. */
    copyBusy: Boolean,
    /** Determinate share of that work, or null before it can report a count. */
    copyFraction: Float?,
    sortMode: AlbumPhotoSortMode,
    /** True while this album's photos are kept out of the main feed. About the photos, which [onHide]
     *  is not: that one takes the album itself out of every list. */
    isHiddenFromTimeline: Boolean,
    onDismiss: () -> Unit,
    onAddPhotos: () -> Unit,
    onShareOrInfo: () -> Unit,
    onCopy: () -> Unit,
    onEditMetadata: () -> Unit,
    onRename: () -> Unit,
    onToggleHiddenFromTimeline: () -> Unit,
    onHide: () -> Unit,
    onSlideshow: () -> Unit,
    onSortSelected: (AlbumPhotoSortMode) -> Unit,
    onLeaveAlbum: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Slide the drawer away before the action lands, so a confirmation or picker never opens behind
    // a sheet that is still on screen.
    fun close(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBg,
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
                stringResource(R.string.albums_more_actions),
                color = FgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // Share and the download / save row are on every album, so this group is never empty.
            ActionSheetSectionHeading(
                stringResource(R.string.action_sheet_actions_heading),
                first = true,
            )
            if (canAddPhotos) {
                ActionSheetRow(
                    icon = Icons.Default.Add,
                    title = stringResource(R.string.album_add_photos),
                    onClick = { close(onAddPhotos) },
                )
                Spacer(Modifier.height(8.dp))
            }

            // A guest's row is an info row about the share they were given, so the tick is the
            // owner's alone: it says this album is one they have already shared.
            ActionSheetRow(
                icon = if (isSharedWithMe) Icons.Default.Info else Icons.Default.Share,
                title = stringResource(
                    if (isSharedWithMe) R.string.share_shared_with else R.string.albums_share_button,
                ),
                onClick = { close(onShareOrInfo) },
                showCheck = isShared && !isSharedWithMe,
            )
            Spacer(Modifier.height(8.dp))

            // The owner downloads the album; a guest copies it into their own library. MoveToInbox,
            // not LibraryAdd: the latter draws a plus on a stack, which reads as "add photos here"
            // and is exactly what an editor reaches for, while the plus belongs to Add.
            ActionSheetRow(
                icon = if (isSharedWithMe) Icons.Default.MoveToInbox else Icons.Default.FileDownload,
                title = stringResource(
                    if (isSharedWithMe) R.string.shared_save_to_library else R.string.albums_download_all,
                ),
                onClick = { close(onCopy) },
                enabled = !copyBusy,
                busy = copyBusy,
                busyFraction = copyFraction,
            )

            // Edit the date, place and text tags across the whole album, the same entry the album
            // grid's long-press sheet carries. An owner action: on a shared-with-me album those fields
            // belong to the owner.
            if (!isSharedWithMe && hasPhotos) {
                Spacer(Modifier.height(8.dp))
                ActionSheetRow(
                    icon = Icons.Default.EditNote,
                    title = stringResource(R.string.metadata_editor_edit_metadata),
                    onClick = { close(onEditMetadata) },
                )
            }

            if (hasPhotos) {
                Spacer(Modifier.height(8.dp))
                ActionSheetRow(
                    icon = Icons.Default.PlayArrow,
                    title = stringResource(R.string.viewer_play_slideshow),
                    onClick = { close(onSlideshow) },
                )
            }

            // Both hides are on every album, so this group is never empty; rename is the owner's alone.
            ActionSheetSectionHeading(stringResource(R.string.action_sheet_settings_heading))
            if (!isSharedWithMe) {
                ActionSheetRow(
                    icon = Icons.Default.Edit,
                    title = stringResource(R.string.album_rename),
                    onClick = { close(onRename) },
                )
                Spacer(Modifier.height(8.dp))
            }
            // The weaker of the two hides, and the one that carries a tick: it writes the same
            // per-album key the Albums grid's drawer and the Settings picker do, so the state is the
            // current one wherever it was set. The row below is the stronger action and stays a
            // one-way choice.
            ActionSheetRow(
                icon = Icons.Default.HideImage,
                title = stringResource(R.string.device_folder_hide_from_timeline),
                onClick = { close(onToggleHiddenFromTimeline) },
                showCheck = isHiddenFromTimeline,
            )
            Spacer(Modifier.height(8.dp))
            ActionSheetRow(
                icon = Icons.Default.VisibilityOff,
                title = stringResource(R.string.albums_hide_album),
                onClick = { close(onHide) },
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

            if (isSharedWithMe) {
                // Leaving drops the album out of the library, so it keeps the rule above it and the
                // red treatment it carries everywhere else.
                HorizontalDivider(color = Line2, modifier = Modifier.padding(vertical = 16.dp))
                ActionSheetRow(
                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                    title = stringResource(R.string.leave_album),
                    onClick = { close(onLeaveAlbum) },
                    destructive = true,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
