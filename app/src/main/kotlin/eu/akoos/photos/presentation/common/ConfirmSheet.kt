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

package eu.akoos.photos.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.data.hidden.HiddenFolderRecords
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.SheetBg

/**
 * Bottom-sheet ("drawer") equivalent of [ConfirmDialog], matching the app's other sliding sheets:
 * a [Bg2] container with the default drag handle, the title and message above, and the cancel /
 * confirm actions as the shared [SecondaryButton] / [PrimaryButton]. Use for a confirmation that
 * should read as a drawer rather than a centred dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String,
    message: String?,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** False while the sheet is still waiting on something it needs before the action can be
     *  agreed to, which keeps a confirmation from being given against a message that is not there
     *  yet. Cancel stays live throughout. */
    confirmEnabled: Boolean = true,
    /** A destructive confirm (a delete, or a hide that permanently removes the device originals) shows
     *  the red action instead of the accent one, so it reads like every other destructive confirm. */
    destructive: Boolean = false,
    /** Called when the sheet is closed by tapping the scrim, swiping down or the back gesture, as
     *  opposed to the [dismissLabel] button ([onDismiss]). Defaults to [onDismiss]; override when an
     *  outside dismissal should differ from the labelled cancel, e.g. to leave a pending toggle in its
     *  prior state rather than commit the cancel. */
    onOutsideDismiss: () -> Unit = onDismiss,
) {
    val colors = AppColors.current
    ModalBottomSheet(
        onDismissRequest = onOutsideDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, color = colors.fgPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (message != null) {
                Text(message, color = colors.fgDim, fontSize = 14.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryButton(dismissLabel, onDismiss, modifier = Modifier.weight(1f))
                if (destructive) {
                    DestructiveButton(
                        confirmLabel, onConfirm,
                        modifier = Modifier.weight(1f),
                        enabled = confirmEnabled,
                    )
                } else {
                    PrimaryButton(
                        confirmLabel, onConfirm,
                        modifier = Modifier.weight(1f),
                        enabled = confirmEnabled,
                    )
                }
            }
        }
    }
}

/**
 * The one confirmation every hide goes through, on whichever surface asked.
 *
 * A hide ends in a permanent removal of the device originals it vaults, so it is confirmed exactly
 * as the delete beside it on the same bar is. What makes it worth reading is [split]: the message is
 * built from the halves the hide will really act on, so a selection hears about the phone's gallery
 * only when something of it leaves it, about Proton Drive copies only when it holds one, and about
 * cloud-only photos only when one is there.
 *
 * A null [split] is a hide whose photos are still being counted, which a whole folder's is: the
 * sheet then says so and holds the confirm button, since agreeing to a count that is not on screen
 * yet is agreeing to nothing. Cancel stays live, so the way out never waits.
 */
@Composable
fun HideConfirmSheet(
    split: HiddenFolderRecords.HideSplit?,
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val wording = split?.let { hideConfirmWording(it) }
    val vaultClause = if (wording != null && wording.vaultCount > 0) {
        pluralStringResource(R.plurals.hide_confirm_vault, wording.vaultCount, wording.vaultCount)
    } else null
    val cloudCopyClause = if (wording != null && wording.cloudCopyCount > 0) {
        pluralStringResource(
            R.plurals.hide_confirm_cloud_copy, wording.cloudCopyCount, wording.cloudCopyCount,
        )
    } else null
    val cloudOnlyClause = if (wording != null && wording.cloudOnlyCount > 0) {
        pluralStringResource(
            R.plurals.hide_confirm_cloud_only, wording.cloudOnlyCount, wording.cloudOnlyCount,
        )
    } else null
    val message = if (wording == null) {
        stringResource(R.string.hide_confirm_counting)
    } else {
        listOfNotNull(vaultClause, cloudCopyClause, cloudOnlyClause)
            .joinToString("\n\n").ifBlank { null }
    }
    ConfirmSheet(
        title = title,
        message = message,
        confirmLabel = stringResource(R.string.hide_confirm_action),
        dismissLabel = stringResource(R.string.cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmEnabled = wording != null,
        destructive = true,
    )
}
