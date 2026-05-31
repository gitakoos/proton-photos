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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.onboarding.components.StepHeader
import eu.akoos.photos.presentation.onboarding.components.SubToggle
import eu.akoos.photos.presentation.onboarding.components.ToggleCard
import eu.akoos.photos.presentation.onboarding.components.onboardingSwitchColors
import eu.akoos.photos.presentation.theme.AppColors

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
    val colors = AppColors.current
    var stripExpanded by rememberSaveable { mutableStateOf(false) }
    Column {
        StepHeader(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.onboarding_privacy_title),
            subtitle = stringResource(R.string.onboarding_privacy_subtitle),
        )
        Spacer(Modifier.height(20.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.cardBg, RoundedCornerShape(14.dp))
                .border(0.5.dp, colors.cardBorder, RoundedCornerShape(14.dp)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStripChange(!stripMetadata) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.onboarding_privacy_strip), color = colors.fgPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(R.string.onboarding_privacy_strip_desc), color = colors.fgMute, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = stripMetadata, onCheckedChange = onStripChange, colors = onboardingSwitchColors())
            }
            if (stripMetadata) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.5.dp).background(colors.cardBorder))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { stripExpanded = !stripExpanded }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_strip_customize), color = colors.fgDim, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ExpandMore, null, tint = colors.fgMute, modifier = Modifier.size(18.dp).graphicsLayer(rotationZ = if (stripExpanded) 180f else 0f))
                }
                if (stripExpanded) {
                    SubToggle(label = stringResource(R.string.settings_strip_gps), description = stringResource(R.string.settings_strip_gps_desc), checked = stripGps, onChange = onStripGps)
                    SubToggle(label = stringResource(R.string.settings_strip_camera), description = stringResource(R.string.settings_strip_camera_desc), checked = stripCamera, onChange = onStripCamera)
                    SubToggle(label = stringResource(R.string.settings_strip_timestamp), description = stringResource(R.string.settings_strip_timestamp_desc), checked = stripTimestamp, onChange = onStripTimestamp)
                    SubToggle(label = stringResource(R.string.settings_strip_software), description = stringResource(R.string.settings_strip_software_desc), checked = stripSoftware, onChange = onStripSoftware)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        ToggleCard(label = stringResource(R.string.onboarding_privacy_rename), description = stringResource(R.string.onboarding_privacy_rename_desc), checked = renameOnUpload, onChange = onRenameChange)
        Spacer(Modifier.height(10.dp))
        ToggleCard(label = stringResource(R.string.onboarding_privacy_delete), description = stringResource(R.string.onboarding_privacy_delete_desc), checked = deleteAfterBackup, onChange = onDeleteChange)
    }
}
