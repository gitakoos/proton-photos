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

package eu.akoos.photos.presentation.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.onboarding.components.AllowButton
import eu.akoos.photos.presentation.onboarding.components.StepHeader

@Composable
internal fun AllFilesAccessStep(granted: Boolean, onAllow: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepHeader(
            icon = Icons.Default.FolderOpen,
            title = stringResource(R.string.onboarding_all_files_title),
            subtitle = stringResource(R.string.onboarding_all_files_subtitle),
        )
        Spacer(Modifier.height(20.dp))
        AllowButton(
            label = if (granted) stringResource(R.string.onboarding_permission_granted)
            else stringResource(R.string.onboarding_open_settings),
            granted = granted,
            onClick = onAllow,
        )
    }
}
