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

package eu.akoos.photos.presentation.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.R
import eu.akoos.photos.data.autosave.AutoSaveCoordinator
import eu.akoos.photos.data.autosave.AutoSaveFrequency
import eu.akoos.photos.data.autosave.AutoSaveKind
import eu.akoos.photos.data.autosave.AutoSaveState
import eu.akoos.photos.data.autosave.AutoSaveStore
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.SecondaryButton
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.compression.ChoiceRow
import eu.akoos.photos.presentation.settings.compression.ChoiceSheet
import eu.akoos.photos.presentation.settings.compression.CurrentChoice
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.util.LocalIs24Hour
import eu.akoos.photos.presentation.util.appLocale
import eu.akoos.photos.presentation.util.formatDateTimeMinute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The automatic Drive saves' state and actions for the two settings sections that carry them. The work runs
 * on the app's own scope, so a switch tapped just before the screen closes still lands.
 */
@HiltViewModel
class AutoSaveViewModel @Inject constructor(
    private val coordinator: AutoSaveCoordinator,
    private val store: AutoSaveStore,
    @AppScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val states = HashMap<AutoSaveKind, StateFlow<AutoSaveState>>()

    fun state(kind: AutoSaveKind): StateFlow<AutoSaveState> = states.getOrPut(kind) {
        store.flow(kind).stateIn(appScope, SharingStarted.WhileSubscribed(5_000L), AutoSaveState())
    }

    fun setEnabled(kind: AutoSaveKind, enabled: Boolean) {
        appScope.launch { coordinator.setEnabled(kind, enabled) }
    }

    fun setFrequency(kind: AutoSaveKind, frequency: AutoSaveFrequency) {
        appScope.launch { coordinator.setFrequency(kind, frequency) }
    }

    /** What the debug buttons last reported, one line. */
    private val _debugMessage = MutableStateFlow("")
    val debugMessage: StateFlow<String> = _debugMessage

    /** Debug: acts as if the wait were over: saves when something changed, and says what it did. */
    fun debugCheckNow(kind: AutoSaveKind) {
        appScope.launch { _debugMessage.value = coordinator.trigger(kind, pretendDue = true) }
    }

    /** Debug: counts the data as changed now and reports where the schedule stands. */
    fun debugMarkChanged(kind: AutoSaveKind) {
        appScope.launch {
            coordinator.debugMarkChanged(kind)
            _debugMessage.value = coordinator.debugStatus(kind)
        }
    }

    /** Debug: reports where the schedule stands. */
    fun debugStatus(kind: AutoSaveKind) {
        appScope.launch { _debugMessage.value = coordinator.debugStatus(kind) }
    }
}

/**
 * The automatic-save rows of a Drive section, to sit inside its card under the Save and Restore rows: the
 * switch (asking first, as it saves to the account's Drive in the background), how often it runs, and when it
 * last finished. Debug builds add buttons that run the check as if the wait were over and that mark the data
 * as changed, so the schedule can be tried without waiting.
 */
@Composable
internal fun AutoSaveRows(
    kind: AutoSaveKind,
    confirmMessageRes: Int,
    viewModel: AutoSaveViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val state by remember(kind) { viewModel.state(kind) }.collectAsStateWithLifecycle()
    var confirm by rememberSaveable(kind.name) { mutableStateOf(false) }
    var choosing by rememberSaveable(kind.name + "_choose") { mutableStateOf(false) }
    val locale = appLocale()

    RowDivider()
    ToggleRow(
        label = stringResource(R.string.settings_autosave_title),
        checked = state.enabled,
        onCheckedChange = { on -> if (on) confirm = true else viewModel.setEnabled(kind, false) },
    )

    // The choice is one short card that opens a drawer, as the video codec is, so the page's other cards keep their room.
    if (state.enabled) {
        val failed = state.lastFailureMs > state.lastSaveStartMs
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            CurrentChoice(
                title = stringResource(frequencyLabel(state.frequency)),
                description = if (state.lastSaveOkMs > 0L) {
                    stringResource(R.string.settings_autosave_last_saved, formatSavedTime(state.lastSaveOkMs, locale, LocalIs24Hour.current))
                } else {
                    stringResource(frequencyDescription(state.frequency))
                },
                detail = if (failed) {
                    { Text(stringResource(R.string.settings_autosave_failed), color = colors.fgDim, fontSize = 12.sp) }
                } else {
                    null
                },
                onClick = { choosing = true },
            )
        }
    }

    if (choosing && state.enabled && !LocalAppLock.current.isLocked) {
        ChoiceSheet(title = stringResource(R.string.settings_autosave_title), onDismiss = { choosing = false }) {
            Text(stringResource(R.string.settings_autosave_desc), color = colors.fgDim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            AutoSaveFrequency.entries.forEach { frequency ->
                ChoiceRow(
                    selected = state.frequency == frequency,
                    enabled = true,
                    title = stringResource(frequencyLabel(frequency)),
                    description = stringResource(frequencyDescription(frequency)),
                    detail = null,
                    onClick = {
                        viewModel.setFrequency(kind, frequency)
                        choosing = false
                    },
                )
            }
            if (BuildConfig.DEBUG) AutoSaveDebugControls(kind, viewModel)
        }
    }

    // A sheet raised under the app lock would be a window of its own above it, so none is composed then.
    if (confirm && !LocalAppLock.current.isLocked) {
        ConfirmSheet(
            title = stringResource(R.string.settings_autosave_confirm_title),
            message = stringResource(confirmMessageRes),
            confirmLabel = stringResource(R.string.settings_autosave_confirm_on),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                confirm = false
                viewModel.setEnabled(kind, true)
            },
            onDismiss = { confirm = false },
        )
    }
}

/** Debug builds only: runs the automatic save's check as if the wait were over, marks the data changed, and
 *  reads back where the schedule stands. Not translated, as no release build shows it. */
@Composable
private fun AutoSaveDebugControls(kind: AutoSaveKind, viewModel: AutoSaveViewModel) {
    val colors = AppColors.current
    val message by viewModel.debugMessage.collectAsState()
    Spacer(Modifier.height(12.dp))
    Text("Debug: automatic save", color = colors.fgMute, fontSize = 11.sp)
    Spacer(Modifier.height(6.dp))
    SecondaryButton(
        label = "Run the check now (as if the time passed)",
        onClick = { viewModel.debugCheckNow(kind) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    SecondaryButton(
        label = "Mark the data as changed",
        onClick = { viewModel.debugMarkChanged(kind) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    SecondaryButton(
        label = "Show the schedule",
        onClick = { viewModel.debugStatus(kind) },
        modifier = Modifier.fillMaxWidth(),
    )
    if (message.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(message, color = colors.fgMute, fontSize = 11.5.sp)
    }
}

private fun frequencyLabel(frequency: AutoSaveFrequency): Int = when (frequency) {
    AutoSaveFrequency.AFTER_CHANGES -> R.string.settings_autosave_after_changes
    AutoSaveFrequency.DAILY -> R.string.settings_autosave_daily
    AutoSaveFrequency.WEEKLY -> R.string.settings_autosave_weekly
}

private fun frequencyDescription(frequency: AutoSaveFrequency): Int = when (frequency) {
    AutoSaveFrequency.AFTER_CHANGES -> R.string.settings_autosave_after_changes_desc
    AutoSaveFrequency.DAILY -> R.string.settings_autosave_daily_desc
    AutoSaveFrequency.WEEKLY -> R.string.settings_autosave_weekly_desc
}

private fun formatSavedTime(epochMs: Long, locale: java.util.Locale, is24Hour: Boolean): String =
    formatDateTimeMinute(epochMs, locale, is24Hour)
