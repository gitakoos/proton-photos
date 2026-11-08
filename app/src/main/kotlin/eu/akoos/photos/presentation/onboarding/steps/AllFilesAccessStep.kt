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

package eu.akoos.photos.presentation.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.onboarding.components.PermissionRow
import eu.akoos.photos.presentation.onboarding.components.StepHeader
import eu.akoos.photos.presentation.settings.components.SettingsCard

@Composable
internal fun AllFilesAccessStep(granted: Boolean) {
    Column {
        StepHeader(
            title = stringResource(R.string.onboarding_all_files_title),
            subtitle = stringResource(R.string.onboarding_all_files_subtitle),
        )
        Spacer(Modifier.height(24.dp))
        SettingsCard {
            PermissionRow(Icons.Default.FolderOpen, stringResource(R.string.permissions_allfiles_title), granted)
        }
    }
}
