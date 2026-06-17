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

package eu.akoos.photos.presentation.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.AppColors

/**
 * Opt-out switches for the three notification categories the app posts: the persistent
 * "Photo backup" foreground-service notification, album download progress, and the
 * delete-after-backup reminder. Every switch starts ON (the keys default to shown) so a
 * user only ever turns a notification off here, never has to turn one on to keep current
 * behaviour.
 */
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current

    SettingsSubPageScaffold(title = stringResource(R.string.notifications_title), onBack = onBack) {
        Text(
            stringResource(R.string.notifications_subtitle),
            color = colors.fgMute,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.notifications_backup_status),
                description = stringResource(R.string.notifications_backup_status_desc),
                checked = state.backupStatus,
                onCheckedChange = viewModel::setBackupStatus,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.notifications_album_download),
                description = stringResource(R.string.notifications_album_download_desc),
                checked = state.albumDownload,
                onCheckedChange = viewModel::setAlbumDownload,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.notifications_delete_reminder),
                description = stringResource(R.string.notifications_delete_reminder_desc),
                checked = state.deleteReminder,
                onCheckedChange = viewModel::setDeleteReminder,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}
