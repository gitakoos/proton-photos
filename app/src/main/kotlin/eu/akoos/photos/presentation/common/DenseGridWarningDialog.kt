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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors

/**
 * One-time heads-up shown when the timeline is zoomed out to its densest grid. A "Don't show again"
 * checkbox folds the suppress choice into the single OK button, which reads cleaner than two
 * competing buttons. [onPersist] fires only when the box is ticked; otherwise [onDismiss] closes it
 * for the session and it can reappear later.
 */
@Composable
fun DenseGridWarningDialog(
    onDismiss: () -> Unit,
    onPersist: () -> Unit,
) {
    val colors = AppColors.current
    var dontShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor    = colors.cardBg,
        iconContentColor  = colors.accent,
        titleContentColor = colors.fgPrimary,
        textContentColor  = colors.fgDim,
        icon = { Icon(Icons.Default.Collections, contentDescription = null, modifier = Modifier.size(26.dp)) },
        title = { Text(stringResource(R.string.dense_grid_warning_title), fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(stringResource(R.string.dense_grid_warning_message), color = colors.fgDim, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { dontShowAgain = !dontShowAgain },
                ) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor   = colors.accent,
                            uncheckedColor = colors.fgDim,
                            checkmarkColor = colors.cardBg,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.dense_grid_warning_dismiss), color = colors.fgDim, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (dontShowAgain) onPersist() else onDismiss() }) {
                Text(
                    stringResource(R.string.err_dismiss_ok),
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}
