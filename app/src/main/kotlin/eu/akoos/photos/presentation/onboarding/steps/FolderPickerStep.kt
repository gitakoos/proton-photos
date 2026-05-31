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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.presentation.onboarding.BackupMode
import eu.akoos.photos.presentation.onboarding.OnboardingViewModel
import eu.akoos.photos.presentation.onboarding.components.StepHeader
import eu.akoos.photos.presentation.theme.AppColors

@Composable
internal fun FolderPickerStep(
    viewModel: OnboardingViewModel,
    mode: BackupMode,
    mediaGranted: Boolean,
    selectedFolders: Set<String>,
    excludedFolders: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    onExcludedChange: (Set<String>) -> Unit,
) {
    val colors = AppColors.current
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val isExclude = mode == BackupMode.Everything
    val title = if (isExclude)
        stringResource(R.string.onboarding_folders_exclude_title)
    else
        stringResource(R.string.onboarding_folders_include_title)
    val subtitle = if (isExclude)
        stringResource(R.string.onboarding_folders_exclude_subtitle)
    else
        stringResource(R.string.onboarding_folders_include_subtitle)
    Column {
        StepHeader(
            icon = Icons.Default.Folder,
            title = title,
            subtitle = subtitle,
        )
        Spacer(Modifier.height(16.dp))
        if (!mediaGranted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 16.dp),
            ) {
                Text(
                    stringResource(R.string.onboarding_folders_needs_perm),
                    color = colors.fgDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (folders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 16.dp),
            ) {
                Text(
                    stringResource(R.string.onboarding_folders_empty),
                    color = colors.fgDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp)),
            ) {
                folders.forEachIndexed { idx, folder ->
                    val checked = if (isExclude) folder.name in excludedFolders
                    else folder.name in selectedFolders
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isExclude) {
                                    val next = if (checked) excludedFolders - folder.name
                                    else excludedFolders + folder.name
                                    onExcludedChange(next)
                                } else {
                                    val next = if (checked) selectedFolders - folder.name
                                    else selectedFolders + folder.name
                                    onSelectedChange(next)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(folder.name, color = colors.fgPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "${folder.itemCount}",
                                color = colors.fgMute,
                                fontSize = 11.sp,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(
                                    color = if (checked) colors.accent else Color.Transparent,
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .border(
                                    width = if (checked) 0.dp else 1.5.dp,
                                    color = colors.cardBorder,
                                    shape = RoundedCornerShape(6.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (checked) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                    if (idx < folders.lastIndex) {
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(0.5.dp).background(colors.cardBorder))
                    }
                }
            }
        }
    }
}
