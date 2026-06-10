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

package eu.akoos.photos.presentation.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors

/**
 * In-app updater modal. Shows the lifecycle of an APK update flow as a single
 * dialog that swaps body + buttons by state:
 *
 *   - [UpdatePromptState.Available]    → "new version + size", Update/Later
 *   - [UpdatePromptState.Downloading]  → progress bar, no buttons, non-dismissible
 *   - [UpdatePromptState.InstallReady] → "tap to install", Update/Later
 *   - [UpdatePromptState.Error]        → friendly error, single dismiss button
 *
 * Styling mirrors [ConfirmDialog] (cardBg container, fgPrimary title, fgDim body,
 * accent confirm at SemiBold) so the updater feels native to the rest of the app.
 */
sealed class UpdatePromptState {
    data class Available(
        val versionName: String,
        val sizeMb: Int,
    ) : UpdatePromptState()

    data class Downloading(
        val versionName: String,
        val progressPercent: Int,
    ) : UpdatePromptState()

    data class InstallReady(
        val versionName: String,
    ) : UpdatePromptState()

    data class Error(
        val versionName: String?,
        val errorKind: ErrorKind,
    ) : UpdatePromptState()

    enum class ErrorKind {
        NETWORK,
        PERMISSION_DENIED,
    }
}

@Composable
fun UpdatePromptDialog(
    state: UpdatePromptState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    val title = stringResource(R.string.update_available_title)

    AlertDialog(
        onDismissRequest = if (state is UpdatePromptState.Downloading) {
            {}
        } else {
            onDismiss
        },
        containerColor    = colors.cardBg,
        titleContentColor = colors.fgPrimary,
        textContentColor  = colors.fgDim,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            when (state) {
                is UpdatePromptState.Available -> {
                    Text(
                        stringResource(
                            R.string.update_available_message,
                            state.versionName,
                            state.sizeMb,
                        ),
                        color = colors.fgDim,
                        fontSize = 13.sp,
                    )
                }
                is UpdatePromptState.Downloading -> {
                    Column {
                        Text(
                            stringResource(R.string.update_downloading, state.progressPercent),
                            color = colors.fgDim,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { state.progressPercent.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.accent,
                            trackColor = colors.trackBg,
                        )
                    }
                }
                is UpdatePromptState.InstallReady -> {
                    Text(
                        stringResource(R.string.update_install_prompt),
                        color = colors.fgDim,
                        fontSize = 13.sp,
                    )
                }
                is UpdatePromptState.Error -> {
                    val body = when (state.errorKind) {
                        UpdatePromptState.ErrorKind.NETWORK ->
                            stringResource(R.string.update_check_failed)
                        UpdatePromptState.ErrorKind.PERMISSION_DENIED ->
                            stringResource(R.string.update_permission_required)
                    }
                    Text(body, color = colors.fgDim, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdatePromptState.Available,
                is UpdatePromptState.InstallReady -> {
                    TextButton(onClick = onUpdate) {
                        Text(
                            stringResource(R.string.update_action_update),
                            color = colors.accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                is UpdatePromptState.Error -> {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.update_action_later),
                            color = colors.fgDim,
                        )
                    }
                }
                is UpdatePromptState.Downloading -> Unit
            }
        },
        dismissButton = when (state) {
            is UpdatePromptState.Available,
            is UpdatePromptState.InstallReady -> {
                {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.update_action_later),
                            color = colors.fgDim,
                        )
                    }
                }
            }
            else -> null
        },
    )
}
