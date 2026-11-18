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

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.data.localcrypto.LocalCrypto
import eu.akoos.photos.data.localcrypto.LocalEncryptionCoordinator
import eu.akoos.photos.data.localcrypto.LocalEncryptionState
import eu.akoos.photos.data.localcrypto.RecoveryOutcome
import eu.akoos.photos.presentation.common.AppModalSheet
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.common.SecondaryButton
import eu.akoos.photos.presentation.common.rememberSheetFieldsReady
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.SheetBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LocalEncryptionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: LocalEncryptionCoordinator,
) : ViewModel() {
    val state: StateFlow<LocalEncryptionState> = coordinator.state

    private val _noticeDue = MutableStateFlow(false)
    /** The one-time notice after the first start that found every vault file encrypted. */
    val noticeDue: StateFlow<Boolean> = _noticeDue.asStateFlow()

    init {
        viewModelScope.launch {
            coordinator.refresh()
            _noticeDue.value = withContext(Dispatchers.IO) { LocalCrypto.isNoticeDue(context) }
        }
    }

    fun dismissNotice() {
        _noticeDue.value = false
        viewModelScope.launch(Dispatchers.IO) { LocalCrypto.clearNotice(context) }
    }

    fun dismissGuestHideWarning() = coordinator.dismissGuestHideWarning()

    fun unlock(password: CharArray, onResult: (RecoveryOutcome) -> Unit) {
        viewModelScope.launch {
            val outcome = try {
                coordinator.unlockWithPassword(password)
            } finally {
                password.fill('\u0000')
            }
            onResult(outcome)
        }
    }
}

/** The value the row shows: on, still encrypting, or locked. */
@Composable
private fun stateLabel(state: LocalEncryptionState): String = when {
    state.locked -> stringResource(R.string.local_encryption_state_locked)
    state.pendingFiles > 0 -> stringResource(R.string.local_encryption_state_encrypting)
    else -> stringResource(R.string.local_encryption_state_on)
}

/** The row in the Security card. Not shown where the platform offers no Keystore (nothing is encrypted there). */
@Composable
internal fun LocalEncryptionRow(state: LocalEncryptionState, onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.local_encryption_title), color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.local_encryption_desc), color = colors.fgMute, fontSize = 12.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            stateLabel(state),
            color = if (state.locked) colors.errorColor else colors.accent,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(colors.line2, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * What the row (and the one-time notice) opens: what is encrypted and how the key comes back, a Hidden password
 * nudge for a guest without one, or the unlock for data whose key this phone lost.
 */
@Composable
internal fun LocalEncryptionSheet(
    state: LocalEncryptionState,
    onSetPassword: () -> Unit,
    onUnlock: (CharArray, (RecoveryOutcome) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    val context = LocalContext.current
    AppModalSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.locked) {
                LockedBody(state, onUnlock = onUnlock, onDone = { outcome ->
                    val message = if (outcome.databases > 0) R.string.local_encryption_unlocked_restart
                    else R.string.local_encryption_unlocked
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    onDismiss()
                }, onDismiss = onDismiss)
                return@Column
            }
            Text(stringResource(R.string.local_encryption_sheet_title), color = colors.fgPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.local_encryption_sheet_lead), color = colors.fgDim, fontSize = 14.sp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (item in listOf(
                    R.string.local_encryption_item_library,
                    R.string.local_encryption_item_hidden,
                    R.string.local_encryption_item_settings,
                    R.string.local_encryption_item_cache,
                )) {
                    Text("•  " + stringResource(item), color = colors.fgPrimary, fontSize = 14.sp)
                }
            }
            Text(stringResource(R.string.local_encryption_sheet_note), color = colors.fgDim, fontSize = 14.sp)
            when {
                state.signedIn -> {
                    Text(stringResource(R.string.local_encryption_account_note), color = colors.fgDim, fontSize = 14.sp)
                    PrimaryButton(stringResource(R.string.local_encryption_ok), onDismiss, modifier = Modifier.fillMaxWidth())
                }
                state.passwordSet -> {
                    Text(stringResource(R.string.local_encryption_password_note), color = colors.fgDim, fontSize = 14.sp)
                    PrimaryButton(stringResource(R.string.local_encryption_ok), onDismiss, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    Text(stringResource(R.string.local_encryption_guest_title), color = colors.fgPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.local_encryption_guest_note), color = colors.fgDim, fontSize = 14.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton(stringResource(R.string.local_encryption_not_now), onDismiss, modifier = Modifier.weight(1f))
                        PrimaryButton(stringResource(R.string.local_encryption_set_password), onSetPassword, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun LockedBody(
    state: LocalEncryptionState,
    onUnlock: (CharArray, (RecoveryOutcome) -> Unit) -> Unit,
    onDone: (RecoveryOutcome) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val fieldsReady = rememberSheetFieldsReady()
    Text(stringResource(R.string.local_encryption_locked_title), color = colors.fgPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    if (!state.passwordCanUnlock) {
        Text(stringResource(R.string.local_encryption_locked_account), color = colors.fgDim, fontSize = 14.sp)
        PrimaryButton(stringResource(R.string.local_encryption_ok), onDismiss, modifier = Modifier.fillMaxWidth())
        return
    }
    Text(stringResource(R.string.local_encryption_locked_password), color = colors.fgDim, fontSize = 14.sp)
    OutlinedTextField(
        value = password,
        onValueChange = { password = it; wrong = false },
        label = { Text(stringResource(R.string.local_encryption_password_field), color = colors.fgMute) },
        singleLine = true,
        isError = wrong,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth().focusProperties { canFocus = fieldsReady },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.line2,
            focusedTextColor = colors.fgPrimary,
            unfocusedTextColor = colors.fgPrimary,
            cursorColor = colors.accent,
        ),
    )
    if (wrong) Text(stringResource(R.string.local_encryption_wrong_password), color = colors.errorColor, fontSize = 12.5.sp)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton(stringResource(R.string.local_encryption_not_now), onDismiss, modifier = Modifier.weight(1f))
        PrimaryButton(
            stringResource(R.string.local_encryption_unlock),
            {
                busy = true
                onUnlock(password.toCharArray()) { outcome ->
                    busy = false
                    if (outcome.any) onDone(outcome) else wrong = true
                }
            },
            modifier = Modifier.weight(1f),
            enabled = password.isNotEmpty() && !busy,
        )
    }
}

/**
 * The one-time notice after the first start that encrypted everything, shown once over the app's main screen.
 * A guest without a Hidden password can set one from it.
 */
@Composable
fun LocalEncryptionNotice(
    viewModel: LocalEncryptionViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    passwordViewModel: VaultPasswordViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val due by viewModel.noticeDue.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var passwordStep by remember { mutableStateOf(VaultPasswordStep.None) }
    if (due && state.available) {
        LocalEncryptionSheet(
            state = state,
            onSetPassword = {
                viewModel.dismissNotice()
                passwordStep = VaultPasswordStep.Set
            },
            onUnlock = viewModel::unlock,
            onDismiss = viewModel::dismissNotice,
        )
    }
    VaultPasswordFlow(step = passwordStep, onStep = { passwordStep = it }, viewModel = passwordViewModel)
}

/**
 * Shown once over any screen, after a guest without a Hidden password hides a photo: those photos can be opened
 * only with this phone's key unless a password is set. Hiding has already gone through; this only informs.
 */
@Composable
fun GuestHideWarning(
    viewModel: LocalEncryptionViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    passwordViewModel: VaultPasswordViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var passwordStep by remember { mutableStateOf(VaultPasswordStep.None) }
    if (state.guestHideWarning && !state.signedIn && !state.passwordSet) {
        val colors = AppColors.current
        AppModalSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismissRequest = viewModel::dismissGuestHideWarning,
            containerColor = SheetBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(stringResource(R.string.local_encryption_guest_hide_title), color = colors.fgPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.local_encryption_guest_note), color = colors.fgDim, fontSize = 14.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(stringResource(R.string.local_encryption_not_now), viewModel::dismissGuestHideWarning, modifier = Modifier.weight(1f))
                    PrimaryButton(
                        stringResource(R.string.local_encryption_set_password),
                        {
                            viewModel.dismissGuestHideWarning()
                            passwordStep = VaultPasswordStep.Set
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    VaultPasswordFlow(step = passwordStep, onStep = { passwordStep = it }, viewModel = passwordViewModel)
}
