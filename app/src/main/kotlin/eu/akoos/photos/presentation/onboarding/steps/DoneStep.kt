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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.onboarding.BackupMode
import eu.akoos.photos.presentation.onboarding.components.StepHeader
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.theme.AppColors

/** The last step: what the wizard is about to apply, as a settings card, so Finish holds no surprise. */
@Composable
internal fun DoneStep(
    backupMode: BackupMode,
    folderCount: Int,
    mlFace: Boolean,
    mlSemantic: Boolean,
    mlOcr: Boolean,
    appLockEnabled: Boolean,
) {
    val colors = AppColors.current
    val backupLine = when {
        backupMode == BackupMode.Everything -> stringResource(R.string.onboarding_done_backup_everything)
        backupMode == BackupMode.ChooseLater && folderCount > 0 ->
            pluralStringResource(R.plurals.onboarding_done_backup_folders, folderCount, folderCount)
        else -> stringResource(R.string.onboarding_done_backup_nothing)
    }
    val mlNames = buildList {
        if (mlFace) add(stringResource(R.string.settings_ai_face_toggle))
        if (mlSemantic) add(stringResource(R.string.settings_ai_semantic))
        if (mlOcr) add(stringResource(R.string.settings_ai_ocr))
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        StepHeader(
            title = stringResource(R.string.onboarding_done_title),
            subtitle = stringResource(R.string.onboarding_done_subtitle),
        )
        Spacer(Modifier.height(24.dp))
        SettingsCard {
            SummaryRow(Icons.Default.CloudUpload, backupLine)
            if (mlNames.isNotEmpty()) {
                RowDivider()
                SummaryRow(Icons.Default.Download, stringResource(R.string.onboarding_done_ml, mlNames.joinToString(", ")))
            }
            if (appLockEnabled) {
                RowDivider()
                SummaryRow(Icons.Default.Lock, stringResource(R.string.onboarding_done_lock))
            }
        }
    }
}

@Composable
private fun SummaryRow(icon: ImageVector, text: String) {
    val colors = AppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.fgDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
