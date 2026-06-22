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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.onboarding.components.AllowButton
import eu.akoos.photos.presentation.onboarding.components.StepHeader
import eu.akoos.photos.presentation.onboarding.components.ToggleCard

/**
 * Opt-in for mirroring upload changes (metadata removal + rename) onto the on-device originals so the
 * backed-up and local copies stay identical and pair by content hash. It modifies the user's files,
 * so it's off by default; on devices that refuse a silent MediaStore write even with MANAGE_MEDIA it
 * needs all-files access, surfaced here as a one-tap grant once the toggle is on.
 */
@Composable
internal fun MirrorOptInStep(
    enabled: Boolean,
    granted: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onGrant: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepHeader(
            icon = Icons.Default.Sync,
            title = stringResource(R.string.onboarding_mirror_title),
            subtitle = stringResource(R.string.onboarding_mirror_subtitle),
        )
        Spacer(Modifier.height(20.dp))
        ToggleCard(
            label = stringResource(R.string.onboarding_mirror_toggle),
            description = stringResource(R.string.onboarding_mirror_toggle_desc),
            checked = enabled,
            onChange = onEnabledChange,
        )
        if (enabled && !granted) {
            Spacer(Modifier.height(16.dp))
            AllowButton(
                label = stringResource(R.string.onboarding_mirror_grant),
                granted = false,
                onClick = onGrant,
            )
        }
    }
}
