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
import eu.akoos.photos.presentation.onboarding.components.StepHeader
import eu.akoos.photos.presentation.settings.components.ActionRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.ToggleRow

@Composable
internal fun MirrorOptInStep(
    enabled: Boolean,
    granted: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onGrant: () -> Unit,
) {
    Column {
        StepHeader(
            title = stringResource(R.string.onboarding_mirror_title),
            subtitle = stringResource(R.string.onboarding_mirror_subtitle),
        )
        Spacer(Modifier.height(24.dp))
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.onboarding_mirror_toggle),
                description = stringResource(R.string.onboarding_mirror_toggle_desc),
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
            if (enabled && !granted) {
                RowDivider()
                ActionRow(
                    label = stringResource(R.string.onboarding_mirror_grant),
                    icon = Icons.Default.FolderOpen,
                    onClick = onGrant,
                )
            }
        }
    }
}
