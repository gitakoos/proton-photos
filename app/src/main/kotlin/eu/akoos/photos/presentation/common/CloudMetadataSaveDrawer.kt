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

package eu.akoos.photos.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.domain.usecase.CloudMetadataSaveController
import eu.akoos.photos.domain.usecase.CloudSavePhase
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg2

/**
 * The live status of the app-scoped cloud metadata save batch, as a bottom drawer. The editor hands
 * its staged edits to the controller and closes; this shows the batch's per-step progress and its
 * result over whatever screen the editor closed back to. "Continue in background" hides it while the
 * upload keeps running, still tracked by the Activity transfer list.
 *
 * Mounted on every screen a metadata edit can be started from and returned to (the timeline and an
 * album), so the drawer follows the batch rather than the one screen it happened to start on. [ui]
 * null means nothing to show; the worker's [WorkInfo] drives it through [CloudMetadataSaveController.ui].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudMetadataSaveDrawer(
    ui: CloudMetadataSaveController.SaveUi?,
    onDismiss: () -> Unit,
) {
    if (ui == null) return
    val appColors = AppColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bg2,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (ui) {
                is CloudMetadataSaveController.SaveUi.Running -> {
                    Text(
                        stringResource(R.string.metadata_editor_cloud_saving_title),
                        color = appColors.fgPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Accent,
                        )
                        Text(
                            stringResource(
                                when (ui.phase) {
                                    CloudSavePhase.PREPARING -> R.string.metadata_editor_cloud_phase_preparing
                                    CloudSavePhase.UPLOADING -> R.string.metadata_editor_cloud_phase_uploading
                                    CloudSavePhase.FINISHING -> R.string.metadata_editor_cloud_phase_finishing
                                },
                            ),
                            color = appColors.fgPrimary, fontSize = 15.sp,
                        )
                    }
                    Text(
                        stringResource(R.string.metadata_editor_cloud_progress, ui.done, ui.total),
                        color = appColors.fgDim, fontSize = 14.sp,
                    )
                    SecondaryButton(
                        stringResource(R.string.metadata_editor_cloud_background),
                        onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is CloudMetadataSaveController.SaveUi.Done -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            stringResource(R.string.metadata_editor_cloud_done_title),
                            color = appColors.fgPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        if (ui.failed > 0)
                            pluralStringResource(
                                R.plurals.metadata_editor_cloud_result_partial,
                                ui.updated, ui.updated, ui.failed,
                            )
                        else
                            pluralStringResource(
                                R.plurals.metadata_editor_cloud_result_updated,
                                ui.updated, ui.updated,
                            ),
                        color = appColors.fgDim, fontSize = 14.sp,
                    )
                    PrimaryButton(
                        stringResource(R.string.metadata_editor_done),
                        onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
