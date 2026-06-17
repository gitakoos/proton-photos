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

package eu.akoos.photos.presentation.viewer

import eu.akoos.photos.presentation.common.DestructiveButton
import eu.akoos.photos.presentation.common.PrimaryButton

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * Manage a photo's public link, opened from the share drawer's "Public link" row. Driven by
 * [PublicLinkState]: None (explainer + Create), Loading, Active (URL + Copy,
 * password switch, Remove), Error (message + retry).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManagePublicLinkSheet(
    sheetState: SheetState,
    publicLinkState: PublicLinkState,
    onDismiss: () -> Unit,
    onCreateLink: () -> Unit,
    onCopyLink: () -> Unit,
    onRemoveLink: () -> Unit,
    /** null/blank clears any custom password (back to anyone-with-the-link); non-blank sets it. */
    onSetPassword: (String?) -> Unit,
    /** True for a not-yet-backed-up local photo: the None state offers "Upload & create link"
     *  (uploads first, then mints the link) instead of a plain "Create link". */
    needsUpload: Boolean = false,
    onUploadAndCreate: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Bg2,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.share_manage_link_title),
                color = FgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            when (val s = publicLinkState) {
                is PublicLinkState.None -> {
                    Text(
                        stringResource(
                            if (needsUpload) R.string.share_link_local_only_note
                            else R.string.share_manage_link_explainer,
                        ),
                        color = FgMute, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    PrimaryButton(
                        label = stringResource(
                            if (needsUpload) R.string.share_upload_create_link
                            else R.string.share_create_link,
                        ),
                        onClick = if (needsUpload) onUploadAndCreate else onCreateLink,
                    )
                }

                is PublicLinkState.Loading ->
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            color = Accent, strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            // Neutral label: Loading is shared by create / remove / password update.
                            stringResource(R.string.share_link_busy),
                            color = FgDim, fontSize = 13.sp,
                        )
                    }

                is PublicLinkState.Active ->
                    ActiveLinkBody(
                        url = s.url,
                        hasPassword = s.hasPassword,
                        onCopyLink = onCopyLink,
                        onRemoveLink = onRemoveLink,
                        onSetPassword = onSetPassword,
                    )

                is PublicLinkState.Error -> {
                    Text(
                        s.message,
                        color = ErrorColor, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    PrimaryButton(
                        label = stringResource(R.string.share_retry),
                        icon = Icons.Default.Refresh,
                        onClick = onCreateLink,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ActiveLinkBody(
    url: String,
    hasPassword: Boolean,
    onCopyLink: () -> Unit,
    onRemoveLink: () -> Unit,
    onSetPassword: (String?) -> Unit,
) {
    // Selectable (long-press to copy by hand) and single-line elided so it can't blow out the sheet.
    SelectionContainer {
        Text(
            url,
            color = FgPrimary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(PillBg, RoundedCornerShape(12.dp))
                .border(0.5.dp, PillBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    PrimaryButton(
        label = stringResource(R.string.share_copy_link),
        icon = Icons.Default.ContentCopy,
        onClick = onCopyLink,
        modifier = Modifier.fillMaxWidth(),
    )

    // Require-a-password switch: OFF immediately clears the custom password; ON reveals the field.
    Spacer(Modifier.height(20.dp))
    var passwordEnabled by remember(hasPassword) { mutableStateOf(hasPassword) }
    var password by remember(hasPassword) { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.share_require_password),
                color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.share_require_password_desc),
                color = FgMute, fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = passwordEnabled,
            onCheckedChange = { checked ->
                passwordEnabled = checked
                if (!checked) {
                    // Clearing the password rebuilds the link as anyone-with-the-link.
                    password = ""
                    if (hasPassword) onSetPassword(null)
                }
            },
            colors = SwitchDefaults.colors(
                checkedTrackColor   = Accent,
                checkedThumbColor   = Color.White,
                uncheckedTrackColor = Line2,
                uncheckedThumbColor = FgMute,
            ),
        )
    }

    if (passwordEnabled) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            placeholder = {
                Text(stringResource(R.string.share_password_hint), color = FgMute)
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { if (password.isNotBlank()) onSetPassword(password) },
            ),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Accent,
                unfocusedBorderColor = Line2,
                focusedTextColor     = FgPrimary,
                unfocusedTextColor   = FgPrimary,
                cursorColor          = Accent,
            ),
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            // "Update" once a custom password is already set, "Set password" otherwise.
            label = if (hasPassword) {
                stringResource(R.string.share_update_password)
            } else {
                stringResource(R.string.share_set_password)
            },
            onClick = { if (password.isNotBlank()) onSetPassword(password) },
            enabled = password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(20.dp))
    DestructiveButton(
        label = stringResource(R.string.share_remove_link),
        onClick = onRemoveLink,
        modifier = Modifier.fillMaxWidth(),
    )
}

// Create / Copy / Set password / Retry and the Remove-link action use the shared
// PrimaryButton / DestructiveButton from presentation/common.
