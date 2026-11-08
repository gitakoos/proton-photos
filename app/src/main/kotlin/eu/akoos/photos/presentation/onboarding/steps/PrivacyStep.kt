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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.onboarding.components.StepHeader
import eu.akoos.photos.presentation.settings.components.ExpandableHeaderRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.ToggleRow

@Composable
internal fun PrivacyStep(
    stripMetadata: Boolean,
    stripGps: Boolean,
    stripCamera: Boolean,
    stripTimestamp: Boolean,
    stripSoftware: Boolean,
    renameOnUpload: Boolean,
    deleteAfterBackup: Boolean,
    onStripChange: (Boolean) -> Unit,
    onStripGps: (Boolean) -> Unit,
    onStripCamera: (Boolean) -> Unit,
    onStripTimestamp: (Boolean) -> Unit,
    onStripSoftware: (Boolean) -> Unit,
    onRenameChange: (Boolean) -> Unit,
    onDeleteChange: (Boolean) -> Unit,
) {
    var stripExpanded by rememberSaveable { mutableStateOf(false) }
    Column {
        StepHeader(
            title = stringResource(R.string.onboarding_privacy_title),
            subtitle = stringResource(R.string.onboarding_privacy_subtitle),
        )
        Spacer(Modifier.height(24.dp))
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.onboarding_privacy_strip),
                description = stringResource(R.string.onboarding_privacy_strip_desc),
                checked = stripMetadata,
                onCheckedChange = onStripChange,
            )
            if (stripMetadata) {
                RowDivider()
                ExpandableHeaderRow(
                    label = stringResource(R.string.settings_strip_customize),
                    expanded = stripExpanded,
                    onClick = { stripExpanded = !stripExpanded },
                )
                if (stripExpanded) {
                    ToggleRow(stringResource(R.string.settings_strip_gps), stringResource(R.string.settings_strip_gps_desc), stripGps, onStripGps, indented = true)
                    ToggleRow(stringResource(R.string.settings_strip_camera), stringResource(R.string.settings_strip_camera_desc), stripCamera, onStripCamera, indented = true)
                    ToggleRow(stringResource(R.string.settings_strip_timestamp), stringResource(R.string.settings_strip_timestamp_desc), stripTimestamp, onStripTimestamp, indented = true)
                    ToggleRow(stringResource(R.string.settings_strip_software), stringResource(R.string.settings_strip_software_desc), stripSoftware, onStripSoftware, indented = true)
                }
            }
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.onboarding_privacy_rename),
                description = stringResource(R.string.onboarding_privacy_rename_desc),
                checked = renameOnUpload,
                onCheckedChange = onRenameChange,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.onboarding_privacy_delete),
                description = stringResource(R.string.onboarding_privacy_delete_desc),
                checked = deleteAfterBackup,
                onCheckedChange = onDeleteChange,
            )
        }
    }
}
