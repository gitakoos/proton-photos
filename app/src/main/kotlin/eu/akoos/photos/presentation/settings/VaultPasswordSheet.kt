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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.akoos.photos.presentation.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.data.hidden.HiddenRescue
import eu.akoos.photos.presentation.common.DestructiveButton
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.common.SecondaryButton
import eu.akoos.photos.presentation.common.rememberSheetFieldsReady
import eu.akoos.photos.presentation.lock.showAppLockPrompt
import eu.akoos.photos.presentation.settings.components.ActionRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.SheetBg

/** The shortest vault password the sheet accepts. */
internal const val VAULT_PASSWORD_MIN_LENGTH = 8

/** Why a typed vault password cannot be saved yet, or null when it can. */
internal enum class VaultPasswordProblem { TooShort, Mismatch }

internal fun vaultPasswordProblem(password: String, repeat: String): VaultPasswordProblem? = when {
    password.length < VAULT_PASSWORD_MIN_LENGTH -> VaultPasswordProblem.TooShort
    password != repeat -> VaultPasswordProblem.Mismatch
    else -> null
}

/** Where the Settings row has the user in setting, changing or removing the vault password. */
internal enum class VaultPasswordStep { None, Set, Choose, Change, Remove }

/** One masked password field, focusable only once the sheet has settled (see [rememberFieldsReady]). */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    ready: Boolean,
    last: Boolean,
    isError: Boolean = false,
) {
    val colors = AppColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = colors.fgMute) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().focusProperties { canFocus = ready },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.line2,
            focusedTextColor = colors.fgPrimary,
            unfocusedTextColor = colors.fgPrimary,
            cursorColor = colors.accent,
        ),
    )
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, color = AppColors.current.fgPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

/** The body of a password sheet, laid out like the app's other confirm sheets. */
@Composable
private fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

/**
 * Asks for a new vault password, twice. With [changing] it first asks for the current password, which
 * [wrongCurrent] marks as not right. A lost password cannot be recovered, which the sheet says before
 * it asks for one.
 */
@Composable
internal fun VaultPasswordSheet(
    changing: Boolean,
    wrongCurrent: Boolean,
    onSave: (current: CharArray, new: CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val fieldsReady = rememberSheetFieldsReady()
    val problem = vaultPasswordProblem(password, repeat)
    // The hint shows once something is typed, so the empty sheet opens without an error.
    val shownProblem = problem.takeIf { password.isNotEmpty() }
    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        SheetColumn {
            SheetTitle(stringResource(R.string.vault_password_title))
            Text(stringResource(R.string.vault_password_warning), color = colors.fgDim, fontSize = 14.sp)
            if (changing) {
                PasswordField(
                    value = current,
                    onValueChange = { current = it },
                    label = stringResource(R.string.vault_password_current),
                    ready = fieldsReady,
                    last = false,
                    isError = wrongCurrent,
                )
                if (wrongCurrent) {
                    Text(stringResource(R.string.vault_password_wrong), color = colors.errorColor, fontSize = 12.5.sp)
                }
            }
            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(if (changing) R.string.vault_password_new else R.string.vault_password_field),
                ready = fieldsReady,
                last = false,
            )
            PasswordField(
                value = repeat,
                onValueChange = { repeat = it },
                label = stringResource(R.string.vault_password_repeat),
                ready = fieldsReady,
                last = true,
            )
            shownProblem?.let {
                Text(
                    stringResource(
                        if (it == VaultPasswordProblem.TooShort) R.string.vault_password_too_short
                        else R.string.vault_password_mismatch,
                        VAULT_PASSWORD_MIN_LENGTH,
                    ),
                    color = colors.errorColor,
                    fontSize = 12.5.sp,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.cancel), onDismiss, modifier = Modifier.weight(1f))
                PrimaryButton(
                    stringResource(R.string.vault_password_save),
                    { onSave(current.toCharArray(), password.toCharArray()) },
                    modifier = Modifier.weight(1f),
                    enabled = problem == null && (!changing || current.isNotEmpty()),
                )
            }
        }
    }
}

/** The first thing a tap on the row shows once a password is set: change it or remove it. */
@Composable
internal fun VaultPasswordChoiceSheet(
    onChange: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.vault_password_title),
                color = AppColors.current.fgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
            )
            RowDivider()
            ActionRow(
                label = stringResource(R.string.vault_password_change),
                icon = Icons.Default.Edit,
                onClick = onChange,
            )
            RowDivider()
            ActionRow(
                label = stringResource(R.string.vault_password_remove),
                icon = Icons.Default.Delete,
                onClick = onRemove,
                destructive = true,
            )
        }
    }
}

/**
 * Asks for the current password to remove it, with a way out for a forgotten one: "Forgot password?"
 * confirms the user with the device lock instead. Nothing is deleted either way, which the sheet says.
 */
@Composable
internal fun VaultPasswordRemoveSheet(
    wrongCurrent: Boolean,
    onRemove: (current: CharArray) -> Unit,
    onForgot: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    var current by remember { mutableStateOf("") }
    val fieldsReady = rememberSheetFieldsReady()
    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        SheetColumn {
            SheetTitle(stringResource(R.string.vault_password_remove_title))
            Text(
                stringResource(R.string.vault_password_remove_body, HiddenRescue.FOLDER),
                color = colors.fgDim,
                fontSize = 14.sp,
            )
            PasswordField(
                value = current,
                onValueChange = { current = it },
                label = stringResource(R.string.vault_password_current),
                ready = fieldsReady,
                last = true,
                isError = wrongCurrent,
            )
            if (wrongCurrent) {
                Text(stringResource(R.string.vault_password_wrong), color = colors.errorColor, fontSize = 12.5.sp)
            }
            TextButton(onClick = onForgot, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vault_password_forgot), color = colors.fgDim)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.cancel), onDismiss, modifier = Modifier.weight(1f))
                DestructiveButton(
                    stringResource(R.string.action_remove),
                    { onRemove(current.toCharArray()) },
                    modifier = Modifier.weight(1f),
                    enabled = current.isNotEmpty(),
                )
            }
        }
    }
}

/** The sheets of one pass through setting, changing or removing the vault password, shown for [step]. */
@Composable
internal fun VaultPasswordFlow(
    step: VaultPasswordStep,
    onStep: (VaultPasswordStep) -> Unit,
    viewModel: VaultPasswordViewModel,
) {
    val context = LocalContext.current
    var wrongCurrent by remember(step) { mutableStateOf(false) }
    fun done(message: Int) {
        Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
        onStep(VaultPasswordStep.None)
    }
    when (step) {
        VaultPasswordStep.None -> Unit
        VaultPasswordStep.Set, VaultPasswordStep.Change -> VaultPasswordSheet(
            changing = step == VaultPasswordStep.Change,
            wrongCurrent = wrongCurrent,
            onSave = { current, new ->
                if (step == VaultPasswordStep.Set) {
                    viewModel.set(new) { done(R.string.vault_password_toast_set) }
                } else {
                    viewModel.change(current, new) { right ->
                        wrongCurrent = !right
                        if (right) done(R.string.vault_password_toast_changed)
                    }
                }
            },
            onDismiss = { onStep(VaultPasswordStep.None) },
        )
        VaultPasswordStep.Choose -> VaultPasswordChoiceSheet(
            onChange = { onStep(VaultPasswordStep.Change) },
            onRemove = { onStep(VaultPasswordStep.Remove) },
            onDismiss = { onStep(VaultPasswordStep.None) },
        )
        VaultPasswordStep.Remove -> VaultPasswordRemoveSheet(
            wrongCurrent = wrongCurrent,
            onRemove = { current ->
                viewModel.remove(current) { right ->
                    wrongCurrent = !right
                    if (right) done(R.string.vault_password_toast_removed)
                }
            },
            onForgot = {
                showAppLockPrompt(context) {
                    viewModel.removeAfterDeviceAuth { done(R.string.vault_password_toast_removed) }
                }
            },
            onDismiss = { onStep(VaultPasswordStep.None) },
        )
    }
}

/** The one row that opens the vault password flow, with what a forced sign-out does in the current state. */
@Composable
internal fun VaultPasswordSettingRow(isSet: Boolean, onClick: () -> Unit) {
    ActionRow(
        label = stringResource(R.string.settings_vault_password),
        icon = Icons.Default.Lock,
        description = if (isSet) {
            stringResource(R.string.settings_vault_password_on_desc)
        } else {
            stringResource(R.string.settings_vault_password_desc, HiddenRescue.FOLDER)
        },
        onClick = onClick,
    )
}

/** The settings of the Hidden photos screen, reached from its header: today the sign-out password. */
@Composable
internal fun HiddenSettingsSheet(
    passwordSet: Boolean,
    onOpenPassword: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.settings_title),
                color = AppColors.current.fgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
            )
            RowDivider()
            VaultPasswordSettingRow(isSet = passwordSet, onClick = onOpenPassword)
        }
    }
}
