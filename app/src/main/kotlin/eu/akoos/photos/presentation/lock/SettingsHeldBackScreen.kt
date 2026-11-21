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

package eu.akoos.photos.presentation.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.data.localcrypto.SettingsHold
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Covers the app while its settings are held back ([SettingsHold]): an empty settings file would list the
 * photos hidden in the cloud again and show no hidden album as hidden. [onRetry] reads the file again and merges
 * back what its key opens now, on its own every few seconds as well. A copy set aside ([SettingsHold.SET_ASIDE])
 * can be left behind with [onContinue], after a confirmation; a file that cannot be read at all cannot.
 */
@Composable
fun SettingsHeldBackScreen(
    hold: SettingsHold,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    val colors = AppColors.current
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(hold) {
        while (true) {
            delay(AUTO_RETRY_MS)
            onRetry()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg0)
            .safeDrawingPadding()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(colors.accent.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = colors.accent, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.settings_held_back_title),
                color = colors.fgPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    if (hold == SettingsHold.UNREADABLE) R.string.settings_held_back_body_unreadable
                    else R.string.settings_held_back_body,
                ),
                color = colors.fgDim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            HeldBackButton(stringResource(R.string.settings_held_back_retry), onClick = onRetry)
            if (hold == SettingsHold.SET_ASIDE) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.settings_held_back_continue),
                    color = colors.fgDim,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { confirming = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.settings_held_back_confirm_title),
            message = stringResource(R.string.settings_held_back_confirm_body),
            confirmLabel = stringResource(R.string.settings_held_back_confirm_action),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                confirming = false
                onContinue()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun HeldBackButton(label: String, onClick: () -> Unit) {
    val accent = AppColors.current.accent
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .border(0.5.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

private const val AUTO_RETRY_MS = 5_000L
