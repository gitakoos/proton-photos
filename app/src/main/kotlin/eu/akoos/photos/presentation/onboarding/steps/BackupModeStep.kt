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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.onboarding.BackupMode
import eu.akoos.photos.presentation.onboarding.components.ChoiceCard
import eu.akoos.photos.presentation.onboarding.components.StepHeader

@Composable
internal fun BackupModeStep(selected: BackupMode, onSelect: (BackupMode) -> Unit) {
    Column {
        StepHeader(
            icon = Icons.Default.CloudUpload,
            title = stringResource(R.string.onboarding_backup_title),
            subtitle = stringResource(R.string.onboarding_backup_subtitle),
        )
        Spacer(Modifier.height(20.dp))
        // "Nothing for now" first AND selected by default, so accidentally tapping through
        // onboarding never starts uploading the whole library unprompted.
        ChoiceCard(
            label = stringResource(R.string.onboarding_backup_nothing),
            description = stringResource(R.string.onboarding_backup_nothing_desc),
            selected = selected == BackupMode.NothingForNow,
            onClick = { onSelect(BackupMode.NothingForNow) },
        )
        Spacer(Modifier.height(10.dp))
        ChoiceCard(
            label = stringResource(R.string.onboarding_backup_folders),
            description = stringResource(R.string.onboarding_backup_folders_desc),
            selected = selected == BackupMode.ChooseLater,
            onClick = { onSelect(BackupMode.ChooseLater) },
        )
        Spacer(Modifier.height(10.dp))
        ChoiceCard(
            label = stringResource(R.string.onboarding_backup_everything),
            description = stringResource(R.string.onboarding_backup_everything_desc),
            selected = selected == BackupMode.Everything,
            onClick = { onSelect(BackupMode.Everything) },
        )
    }
}
