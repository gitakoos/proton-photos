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

package eu.akoos.photos.presentation.settings

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.ErrorPopup
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.util.sanitizeErrorMessage
import eu.akoos.photos.presentation.settings.components.AppLockTimeoutRow
import eu.akoos.photos.presentation.settings.components.CollapsibleSection
import eu.akoos.photos.presentation.settings.components.ExpandableHeaderRow
import eu.akoos.photos.presentation.settings.components.IndentedNavRow
import eu.akoos.photos.presentation.settings.components.NavRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.settings.components.rememberDebouncedAction
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.StatusError
import eu.akoos.photos.presentation.theme.StatusPending
import eu.akoos.photos.presentation.theme.StatusSynced
import eu.akoos.photos.presentation.util.formatBytes

private val cardShape = RoundedCornerShape(12.dp)

private fun storageColor(fraction: Float): Color = when {
    fraction < 0.70f -> StatusSynced
    fraction < 0.90f -> StatusPending
    else             -> StatusError
}

/**
 * Subtitle for the "Recently Deleted" entry — combines device-side count (known) with
 * the Drive-side count (nullable). When cloud is known we surface both, otherwise we
 * fall back to the device-only pluralised text.
 */
@Composable
private fun recentlyDeletedSubtitle(deviceCount: Int, cloudCount: Int?): String = when {
    cloudCount != null -> stringResource(
        R.string.settings_recently_deleted_subtitle_device_cloud,
        deviceCount,
        cloudCount,
    )
    deviceCount == 0 -> stringResource(R.string.settings_recently_deleted_empty)
    deviceCount == 1 -> stringResource(R.string.settings_recently_deleted_subtitle_singular)
    else -> stringResource(R.string.settings_recently_deleted_subtitle, deviceCount)
}

// ── Main Settings screen ──────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSyncSettingsClick: () -> Unit = {},
    onStorageClick: () -> Unit = {},
    onPrivacySettingsClick: () -> Unit = {},
    onSecuritySettingsClick: () -> Unit = {},
    onRecentlyDeletedClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onLanguageClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onCheckForUpdatesClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Sync errors render in a copyable [ErrorPopup] now instead of a transient
    // snackbar — `state.syncError` is set from raw exception messages whose payload
    // can be a multi-line backend response (and previously included the user's
    // filename / linkId before sanitisation), so a 4-second auto-dismiss snackbar
    // wasn't enough time to read or act on it. We keep the snackbarHost mounted
    // below for other transient confirmations.
    if (state.syncError != null) {
        ErrorPopup(
            title = "Sync failed",
            message = sanitizeErrorMessage(state.syncError),
            onDismiss = viewModel::clearSyncError,
            onCopy = {},
        )
    }

    // System delete dialog for "Free up space now" on Android 11+
    val freeUpPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onFreeUpPermissionGranted()
        else viewModel.clearFreeUpIntent()
    }
    LaunchedEffect(state.freeUpPendingIntent) {
        state.freeUpPendingIntent?.let { pi ->
            freeUpPermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_title), color = colors.fgPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                val debouncedBack = rememberDebouncedAction { onBack() }
                IconBubble(
                    icon = Icons.Default.Close,
                    contentDescription = null,
                    onClick = debouncedBack,
                    diameter = 36.dp,
                    iconSize = 16.dp,
                    background = colors.surfaceWeak,
                    borderColor = colors.pillBorder,
                    tint = colors.fgDim,
                )
            }

            // ── Account ───────────────────────────────────────────────────────
            // The whole row is now a tap target — opens AccountScreen with avatar,
            // storage, web links, and sign out. Sign out moved out of the row to
            // avoid the cramped triple hit area (avatar / text / sign out) we had
            // before, and to keep the destructive action behind one more deliberate
            // step.
            CollapsibleSection(label = stringResource(R.string.settings_account_section)) {
            SettingsCard {
                val debouncedAccountClick = rememberDebouncedAction { onAccountClick() }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(onClick = debouncedAccountClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(38.dp).background(
                            Brush.linearGradient(listOf(colors.accent, colors.accent2), Offset.Zero, Offset(80f, 80f)),
                            CircleShape,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.userDisplayName.firstOrNull()?.uppercaseChar()?.toString()
                                ?: state.userEmail.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(state.userDisplayName.ifEmpty { state.userEmail }, color = colors.fgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (state.userDisplayName.isNotEmpty()) {
                            Text(state.userEmail, color = colors.fgMute, fontSize = 12.sp)
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = colors.fgMute,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            }

            Spacer(Modifier.height(20.dp))

            // ── Sync (backup status, realtime) ────────────────────────────────
            CollapsibleSection(label = stringResource(R.string.sync_section)) {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSyncSettingsClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(stringResource(R.string.sync_backed_up), color = colors.fgMute, fontSize = 11.sp)
                        // Split photos vs videos so the user can spot at a glance that the
                        // backed-up total isn't pure-photos. Reuses the same selection_* strings
                        // as the gallery/album selection counter — already translated to 6 locales.
                        val backedUpLabel = when {
                            state.syncedPhotoCount > 0 && state.syncedVideoCount > 0 ->
                                stringResource(R.string.selection_mixed, state.syncedPhotoCount, state.syncedVideoCount)
                            state.syncedVideoCount > 0 ->
                                stringResource(R.string.selection_videos_only, state.syncedVideoCount)
                            state.syncedPhotoCount > 0 ->
                                stringResource(R.string.selection_photos_only, state.syncedPhotoCount)
                            // A running sync with zero rows yet is the fresh-login first-sync
                            // window: the DB-backed count is genuinely 0 because the listing is
                            // still being page-fetched. Show progress copy instead of a bald
                            // "None" so the user doesn't read it as "nothing is backed up".
                            state.isSyncing ->
                                stringResource(R.string.sync_first_run_in_progress)
                            else ->
                                stringResource(R.string.sync_none)
                        }
                        Text(
                            backedUpLabel,
                            color = StatusSynced, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.sync_pending), color = colors.fgMute, fontSize = 11.sp)
                        Text(
                            when {
                                state.notSyncedCount <= 0 -> stringResource(R.string.sync_none)
                                state.notSyncedCount == 1 -> stringResource(R.string.sync_photo_count_singular)
                                else -> stringResource(R.string.sync_photo_count, state.notSyncedCount)
                            },
                            color = if (state.notSyncedCount > 0) StatusPending else colors.fgMute,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (state.isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = colors.fgMute, modifier = Modifier.size(13.dp))
                    }
                }
                // ── Progress bar + expandable per-file list (while syncing OR pending) ──
                // We render this *inside* the same Sync card (not as a separate card) so the
                // header row's tap-target stays the gateway to Sync Settings — only the panel
                // below stays interactive in its own right.
                //
                // Visibility = isSyncing OR pending > 0 — the OR side means the panel is up
                // already when the OneTime SyncWorker is enqueued but not yet running (the
                // ViewModel's isSyncing flag tracks only the in-process upload). Without this
                // the user would see "Pending: 5" with no progress bar for the first second or
                // two until the worker spins up.
                val pending = state.notSyncedCount
                // Show the panel while a batch is live OR while the last batch's events
                // are still meaningful to the user (sticky log of recent activity).
                val showPanel = state.isSyncing ||
                    (pending > 0 && state.uploadTotalCount > 0) ||
                    state.uploadEvents.isNotEmpty()
                if (showPanel) {
                    // Fall back to pending count if we don't have a live upload total yet so
                    // the user sees "0 / N" before the first per-file event arrives.
                    val displayTotal = if (state.uploadTotalCount > 0) state.uploadTotalCount else pending
                    SyncProgressPanel(
                        done = state.uploadDoneCount,
                        total = displayTotal,
                        events = state.uploadEvents,
                        bytesPerSecond = state.uploadBytesPerSecond,
                    )
                }
            }
            }

            Spacer(Modifier.height(20.dp))

            // ── Storage section ───────────────────────────────────────────────
            // Recently Deleted + Storage nav grouped together — both are about
            // "where my data lives on device + Drive". Kept above the device-config
            // section so the user finds disk-space related controls without scrolling.
            CollapsibleSection(label = stringResource(R.string.settings_storage_section)) {
            SettingsCard {
                NavRow(
                    label = stringResource(R.string.settings_storage_section),
                    description = stringResource(R.string.settings_storage_nav_desc),
                    onClick = onStorageClick,
                )
                RowDivider()
                NavRow(
                    label = stringResource(R.string.settings_recently_deleted),
                    description = recentlyDeletedSubtitle(state.trashedCount, state.cloudTrashCount),
                    onClick = onRecentlyDeletedClick,
                )
            }
            }

            Spacer(Modifier.height(20.dp))

            // ── Settings nav rows ─────────────────────────────────────────────
            CollapsibleSection(label = stringResource(R.string.settings_section_settings)) {
            SettingsCard {
                NavRow(
                    label = stringResource(R.string.settings_privacy_metadata),
                    description = stringResource(R.string.settings_privacy_metadata_desc),
                    onClick = onPrivacySettingsClick,
                )
                RowDivider()
                NavRow(
                    label = stringResource(R.string.settings_security),
                    description = stringResource(R.string.settings_security_desc),
                    onClick = onSecuritySettingsClick,
                )
                RowDivider()
                // Appearance + Language merged into one entry — the destination is the
                // unified appearance screen which now hosts theme + palette + language
                // in a single scroll. Cuts an entire row from the Settings list.
                NavRow(
                    label = stringResource(R.string.settings_appearance),
                    description = stringResource(R.string.settings_appearance_desc),
                    onClick = onAppearanceClick,
                )
                RowDivider()
                // Manual update check — taps fire a forced (cache-bypassing) GitHub
                // Releases query. The orchestrator surfaces the result either through
                // the UpdatePromptDialog (new version) or a Toast (no update / network
                // flake). The current versionName lives in the row description so the
                // user can sanity-check what they're on without opening About.
                NavRow(
                    label = stringResource(R.string.update_check_settings_row),
                    description = stringResource(
                        R.string.update_check_settings_summary,
                        BuildConfig.VERSION_NAME,
                    ),
                    onClick = onCheckForUpdatesClick,
                )
                RowDivider()
                NavRow(
                    label = stringResource(R.string.about_title),
                    description = stringResource(R.string.settings_about_desc),
                    onClick = onAboutClick,
                )
            }
            }
        }

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

}

// ── Sync Settings sub-page ────────────────────────────────────────────────────

@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    onBackupFoldersClick: () -> Unit = {},
    onExcludedFoldersClick: () -> Unit = {},
    onAlbumMirrorFoldersClick: () -> Unit = {},
    onRecentlyDeletedClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    SettingsSubPageScaffold(title = stringResource(R.string.sync_section), onBack = onBack) {
        // ── WHAT GETS BACKED UP ───────────────────────────────────────────────
        // Backup-scope decisions live here: the everything-toggle and either the
        // include drilldown (off) or the exclude drilldown (on). Sync cadence /
        // network gating live in the WHEN section below. Sections open by default;
        // user can collapse via the animated chevron.
        CollapsibleSection(label = stringResource(R.string.settings_what_backed_up_section)) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_backup_everything),
                description = stringResource(R.string.settings_backup_everything_desc),
                checked = state.backupEverything,
                onCheckedChange = viewModel::setBackupEverything,
                enabled = state.autoSync,
            )
            // The two drilldown rows are mutually exclusive — only one is visible at
            // a time depending on the everything-toggle. Indented (28.dp) so the eye
            // groups them as children of the parent toggle.
            if (state.backupEverything) {
                RowDivider()
                val excludedCount = state.excludedFolderNames.size
                val excludedDesc = when (excludedCount) {
                    0 -> stringResource(R.string.settings_excluded_folders_desc_none)
                    1 -> stringResource(R.string.settings_excluded_folders_desc_singular)
                    else -> stringResource(R.string.settings_excluded_folders_desc_count, excludedCount)
                }
                IndentedNavRow(
                    label = stringResource(R.string.settings_excluded_folders),
                    description = excludedDesc,
                    onClick = onExcludedFoldersClick,
                    enabled = state.autoSync,
                )
            } else {
                RowDivider()
                IndentedNavRow(
                    label = stringResource(R.string.settings_backup_folders),
                    description = stringResource(R.string.settings_backup_folders_desc),
                    onClick = onBackupFoldersClick,
                    enabled = state.autoSync,
                )
            }
            // Album-mirror drilldown sits alongside the include/exclude rows because
            // it answers the related question "which device folders also surface as
            // Drive albums when their photos upload?" — orthogonal to the everything
            // toggle, so it stays visible in both branches.
            RowDivider()
            IndentedNavRow(
                label = stringResource(R.string.settings_album_mirror_folders),
                description = stringResource(R.string.settings_album_mirror_folders_desc),
                onClick = onAlbumMirrorFoldersClick,
                enabled = state.autoSync,
            )
        }
        }

        Spacer(Modifier.height(20.dp))

        // ── WHEN IT HAPPENS ───────────────────────────────────────────────────
        // Continuous backup is the only top-level cadence knob now: the periodic
        // interval picker is gone because the OS content-URI trigger plus
        // BackgroundSyncService observer kick the upload pipeline within seconds of
        // a new photo, so the interval was misleading. Sync Wi-Fi only and Delete
        // after backup live as children here because they're meaningless when
        // continuous backup is off.
        CollapsibleSection(label = stringResource(R.string.settings_when_section)) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_continuous_backup),
                description = stringResource(R.string.settings_continuous_backup_desc),
                checked = state.autoSync,
                onCheckedChange = viewModel::setAutoSync,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_sync_wifi_only),
                description = stringResource(R.string.settings_sync_wifi_desc),
                checked = state.syncWifiOnly,
                onCheckedChange = viewModel::setSyncWifiOnly,
                indented = true,
                enabled = state.autoSync,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_delete_after_backup),
                description = stringResource(R.string.settings_delete_after_backup_desc),
                checked = state.deleteLocalAfterBackup,
                onCheckedChange = viewModel::setDeleteLocalAfterBackup,
                indented = true,
                enabled = state.autoSync,
            )
        }
        }

        Spacer(Modifier.height(20.dp))

        // ── Sync now action row ──────────────────────────────────────────────
        // Separated from the When card because it's an imperative one-tap action
        // rather than a setting: putting it alongside persistent toggles confused
        // users into thinking the button was a "saved" state. Standalone card +
        // accent label reads as a button now.
        CollapsibleSection(label = stringResource(R.string.settings_sync_now)) {
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.isSyncing) { viewModel.syncNow() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_sync_now),
                    color = colors.fgPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (state.isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                } else {
                    Text(
                        stringResource(R.string.settings_sync_now_action),
                        color = colors.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        }

        Spacer(Modifier.height(20.dp))

        // ── NETWORK USAGE ────────────────────────────────────────────────────
        // Full-res Wi-Fi only is about CONSUMING Drive (downloading full-resolution
        // photos for the viewer) rather than backing up to it, so it doesn't belong
        // under "When backup happens". Promoted to its own section so users don't
        // assume disabling continuous backup also stops full-res viewer downloads.
        CollapsibleSection(label = stringResource(R.string.settings_network_section)) {
            SettingsCard {
                ToggleRow(
                    label = stringResource(R.string.settings_fullres_wifi_only),
                    description = stringResource(R.string.settings_fullres_wifi_only_desc),
                    checked = state.fullresWifiOnly,
                    onCheckedChange = viewModel::setFullresWifiOnly,
                )
            }
        }
    }
}

// ── Storage Settings sub-page ─────────────────────────────────────────────────

@Composable
fun StorageSettingsScreen(
    onBack: () -> Unit,
    onRecentlyDeletedClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    val freeUpPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onFreeUpPermissionGranted()
        else viewModel.clearFreeUpIntent()
    }
    LaunchedEffect(state.freeUpPendingIntent) {
        state.freeUpPendingIntent?.let { pi ->
            freeUpPermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }
    LaunchedEffect(Unit) { viewModel.refreshLocalStorage() }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_storage_section), onBack = onBack) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(stringResource(R.string.settings_storage_section))
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable { viewModel.refresh() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.settings_storage_refresh),
                    tint = colors.fgMute,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            StorageContent(
                state = state,
                isFreeingUp = state.isFreeingUp,
                onFreeUp = { viewModel.freeUpNow() },
                onClearCache = { viewModel.clearAppCache() },
            )
        }
        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.settings_recently_deleted))
        Spacer(Modifier.height(8.dp))
        RecentlyDeletedCard(
            count = state.trashedCount,
            cloudCount = state.cloudTrashCount,
            onClick = onRecentlyDeletedClick,
        )
    }
}

// ── Privacy Settings sub-page ─────────────────────────────────────────────────

@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    onHiddenAlbumClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsSubPageScaffold(title = stringResource(R.string.settings_privacy_metadata), onBack = onBack) {
        // ── Metadata card ───────────────────────────────────────────────────
        // Strip sub-toggles collapse under the main strip-on-upload switch — they
        // only exist to attenuate WHICH metadata categories get stripped, so showing
        // them while strip is off was pure noise. Rename stays at the top because
        // it modifies displayName (a separate decision from EXIF stripping).
        CollapsibleSection(label = stringResource(R.string.settings_privacy_section_metadata)) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_rename_on_upload),
                description = stringResource(R.string.settings_rename_on_upload_desc),
                checked = state.renameToCaptureDate,
                onCheckedChange = viewModel::setRenameToCaptureDate,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_strip_metadata_upload),
                description = stringResource(R.string.settings_strip_metadata_upload_desc),
                checked = state.stripOnUpload,
                onCheckedChange = viewModel::setStripOnUpload,
            )
            // Sub-toggles live behind a collapsed "Customize" row by default — even
            // with strip enabled they're rarely tweaked, so they were eating screen
            // real estate every time the user opened Privacy & Metadata. Pressing the
            // chevron row expands the four GPS / Camera / Timestamp / Software
            // overrides; defaults match what the upload pipeline actually does when
            // none of them are explicitly set.
            if (state.stripOnUpload) {
                RowDivider()
                var showStripDetails by remember { mutableStateOf(false) }
                ExpandableHeaderRow(
                    label = stringResource(R.string.settings_strip_customize),
                    expanded = showStripDetails,
                    onClick = { showStripDetails = !showStripDetails },
                )
                if (showStripDetails) {
                    RowDivider()
                    ToggleRow(
                        label = stringResource(R.string.settings_strip_gps),
                        description = stringResource(R.string.settings_strip_gps_desc),
                        checked = state.stripGps,
                        onCheckedChange = viewModel::setStripGps,
                        indented = true,
                    )
                    RowDivider()
                    ToggleRow(
                        label = stringResource(R.string.settings_strip_camera),
                        description = stringResource(R.string.settings_strip_camera_desc),
                        checked = state.stripCameraInfo,
                        onCheckedChange = viewModel::setStripCameraInfo,
                        indented = true,
                    )
                    RowDivider()
                    ToggleRow(
                        label = stringResource(R.string.settings_strip_timestamp),
                        description = stringResource(R.string.settings_strip_timestamp_desc),
                        checked = state.stripTimestamp,
                        onCheckedChange = viewModel::setStripTimestamp,
                        indented = true,
                    )
                    RowDivider()
                    ToggleRow(
                        label = stringResource(R.string.settings_strip_software),
                        description = stringResource(R.string.settings_strip_software_desc),
                        checked = state.stripSoftwareInfo,
                        onCheckedChange = viewModel::setStripSoftwareInfo,
                        indented = true,
                    )
                }
            }
        }
        }

        Spacer(Modifier.height(20.dp))

        // ── Device-side privacy card ─────────────────────────────────────────
        // Clear-cache-on-close + Hidden vault — what stays on this device after
        // close.
        CollapsibleSection(label = stringResource(R.string.settings_privacy_section_device)) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_clear_cache_on_close),
                description = stringResource(R.string.settings_clear_cache_on_close_desc),
                checked = state.clearCacheOnAppClose,
                onCheckedChange = viewModel::setClearCacheOnAppClose,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.settings_hidden_photos),
                description = stringResource(R.string.settings_hidden_photos_desc),
                onClick = onHiddenAlbumClick,
            )
            RowDivider()
            // Telemetry visibility row. Telemetry events fired by the embedded
            // ProtonCore stack are gated by IsTelemetryEnabledImpl, which reads
            // the user's server side `Telemetry` preference. We surface this here
            // as a read only row so the user knows the control exists and where
            // it lives — overriding the binding locally would conflict with
            // ProtonCore's own @Binds and ripple into the auth flow. A future
            // iteration can move this to a dedicated Diagnostics section once
            // we add more such "follow your Proton account" rows.
            ToggleRow(
                label = stringResource(R.string.settings_telemetry),
                description = stringResource(R.string.settings_telemetry_desc),
                checked = false,
                onCheckedChange = {},
                enabled = false,
            )
        }
        }
    }
}

// ── Security Settings sub-page ────────────────────────────────────────────────

@Composable
fun SecuritySettingsScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onHiddenAlbumClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    SettingsSubPageScaffold(title = stringResource(R.string.settings_security), onBack = onBack) {
        CollapsibleSection(label = stringResource(R.string.settings_security_section_lock)) {
            SettingsCard {
                ToggleRow(
                    label = stringResource(R.string.settings_app_lock),
                    description = stringResource(R.string.settings_app_lock_desc),
                    checked = state.appLockEnabled,
                    onCheckedChange = { viewModel.setAppLockEnabled(it) },
                )
                if (state.appLockEnabled) {
                    RowDivider()
                    AppLockTimeoutRow(
                        label = stringResource(R.string.settings_app_lock_timeout),
                        description = stringResource(R.string.settings_app_lock_timeout_desc),
                        selectedMinutes = state.appLockTimeoutMinutes,
                        onSelected = viewModel::setAppLockTimeoutMinutes,
                    )
                }
            }
        }

        // OS-level media management permission (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Spacer(Modifier.height(20.dp))
            val context = LocalContext.current
            val canManage = remember { MediaStore.canManageMedia(context) }
            CollapsibleSection(label = stringResource(R.string.settings_security_section_media)) {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_media_management), color = colors.fgPrimary, fontSize = 13.5.sp)
                            Text(
                                if (canManage) stringResource(R.string.settings_media_management_granted)
                                else stringResource(R.string.settings_media_management_grant_desc),
                                color = if (canManage) StatusSynced else colors.fgMute, fontSize = 11.5.sp,
                            )
                        }
                        if (!canManage) {
                            Text(
                                stringResource(R.string.settings_media_management_grant_action), color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable {
                                    context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Shared scaffold ───────────────────────────────────────────────────────────


// ── Sync progress panel (inside Sync card while syncing) ─────────────────────

@Composable
private fun SyncProgressPanel(
    done: Int,
    total: Int,
    events: List<UploadEvent>,
    bytesPerSecond: Long?,
) {
    val colors = AppColors.current
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val pct = (fraction * 100).toInt()
    val animFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(450),
        label = "sync_progress_bar",
    )
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp)) {
        // Linear progress bar — Material 3 default; we override only the track/indicator
        // colors so it matches the app accent. We render our own rounded-rect background to
        // sidestep the M3 1.3 stop-indicator (which adds a small dot at the end and looked
        // wrong on a chip-sized 4dp bar).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.line2),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animFraction)
                    .height(4.dp)
                    .background(colors.accent, RoundedCornerShape(2.dp)),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.sync_progress_completed, pct),
                color = colors.fgDim, fontSize = 11.5.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.sync_progress_x_of_n, done, total),
                color = colors.fgMute, fontSize = 11.5.sp,
            )
            if (bytesPerSecond != null && bytesPerSecond > 0L) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${formatBytes(bytesPerSecond)}/s",
                    color = colors.fgMute, fontSize = 11.5.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.sync_progress_hide_files else R.string.sync_progress_show_files
                ),
                tint = colors.fgMute,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded && events.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            // Show the most recent activity at the top (mirrors a download manager).
            // Pills are self-contained capsules now (PillBg + PillBorder + 999.dp radius),
            // so the panel doesn't need its own container background — only an 8.dp gap
            // between rows. We still cap the scroll height so the list can't push the
            // rest of Settings off-screen during a 30-file burst.
            val ordered = remember(events) { events.asReversed() }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp, max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = ordered, key = { it.uri.ifEmpty { it.displayName + it.status } }) { evt ->
                    UploadEventRow(evt)
                }
            }
        }
    }
}

@Composable
private fun UploadEventRow(evt: UploadEvent) {
    val colors = AppColors.current
    // Each row is a standalone pill: PillBg + 0.5dp PillBorder + 999.dp corner radius,
    // matching the gallery filter pills and the editor adjustment pills. Read-only —
    // no clickable modifier — so the row only communicates status, never invites taps.
    val pillShape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.pillBg, pillShape)
            .border(0.5.dp, colors.pillBorder, pillShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status glyph at 18dp. Uploading uses a circular progress so users see live
        // activity; the rest are static Material icons tinted from the theme. "Queued"
        // falls back to a clock — the upload pipeline rarely emits it, but matching the
        // spec keeps the design consistent if it ever does.
        when (evt.status) {
            UploadEventStatus.Uploading -> Box(
                modifier = Modifier.size(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.8.dp,
                    color = colors.accent,
                )
            }
            UploadEventStatus.Encrypting -> Box(
                // Same spinner shape as Uploading but rendered in the dimmer fgDim tint —
                // signals "pre-network work in progress" without competing visually with the
                // active CDN-PUT spinner. Keeps the row height stable across phase swaps.
                modifier = Modifier.size(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.8.dp,
                    color = colors.fgDim,
                )
            }
            UploadEventStatus.Queued -> Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = colors.fgMute,
                modifier = Modifier.size(18.dp),
            )
            UploadEventStatus.Done -> Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = StatusSynced,
                modifier = Modifier.size(18.dp),
            )
            UploadEventStatus.Failed -> Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = colors.errorColor,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = evt.displayName,
                color = colors.fgPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (evt.sizeBytes > 0L) {
                Text(
                    text = formatBytes(evt.sizeBytes),
                    color = colors.fgMute,
                    fontSize = 10.5.sp,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        // Trailing status label — kept English: status copy not yet localized. The label
        // colour mirrors the icon so the eye reads icon + label as a single status token.
        val (label, labelColor) = when (evt.status) {
            UploadEventStatus.Uploading -> "Uploading…" to colors.accent
            UploadEventStatus.Encrypting -> "Encrypting…" to colors.fgDim
            UploadEventStatus.Queued -> "Queued" to colors.fgMute
            UploadEventStatus.Done -> "Done" to StatusSynced
            UploadEventStatus.Failed -> "Failed" to colors.errorColor
        }
        Text(
            text = label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── Storage content ───────────────────────────────────────────────────────────

/**
 * Storage card: three stacked sections inside one [SettingsCard], divided by row separators.
 *
 *  1. Proton Drive — cloud quota (existing X/Y GB rendering, just relabelled).
 *  2. On this device — /data partition free/total via StatFs.
 *  3. App cache — context.cacheDir size + "Clear cache" pill (confirm dialog).
 *
 * Followed by the existing "Free up space now" row which still belongs to the Proton scope
 * (it deletes already-backed-up local files, not cache files).
 */
@Composable
private fun StorageContent(
    state: SettingsUiState,
    isFreeingUp: Boolean = false,
    onFreeUp: () -> Unit = {},
    onClearCache: () -> Unit = {},
) {
    // ── 1. Proton Drive ─────────────────────────────────────────────────────────
    ProtonStorageRow(state = state)
    RowDivider()

    // ── 2. On this device ───────────────────────────────────────────────────────
    DeviceStorageRow(state = state)
    RowDivider()

    // ── 3. App cache ────────────────────────────────────────────────────────────
    AppCacheRow(state = state, onClearCache = onClearCache)

    // ── 4. Free up space now (existing) ─────────────────────────────────────────
    RowDivider()
    var showFreeUpConfirm by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colors = AppColors.current
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_free_up_now), color = colors.fgPrimary, fontSize = 13.5.sp)
            Text(stringResource(R.string.settings_free_up_desc), color = colors.fgMute, fontSize = 11.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        if (isFreeingUp) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
        } else {
            // Pill-style destructive button — confirm dialog gates the actual delete so
            // an accidental tap doesn't immediately wipe device copies of every
            // backed-up file. The dialog text spells out the irreversible scope.
            Box(
                modifier = Modifier
                    .background(colors.deleteTint, RoundedCornerShape(10.dp))
                    .border(0.5.dp, colors.errorColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .clickable(enabled = !isFreeingUp) { showFreeUpConfirm = true }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = colors.errorColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(R.string.settings_free_up_action),
                        color = colors.errorColor, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
    if (showFreeUpConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_free_up_confirm_title),
            message = stringResource(R.string.settings_free_up_confirm_message),
            confirmLabel = stringResource(R.string.settings_free_up_action),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showFreeUpConfirm = false; onFreeUp() },
            onDismiss = { showFreeUpConfirm = false },
            destructive = true,
        )
    }
}

@Composable
internal fun ProtonStorageRow(state: SettingsUiState) {
    val colors = AppColors.current
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        val hasCloud = state.cloudMaxBytes > 0L
        val fraction = if (hasCloud) (state.cloudUsedBytes.toFloat() / state.cloudMaxBytes).coerceIn(0f, 1f) else 0f
        val usedPct  = (fraction * 100).toInt()
        val barColor = storageColor(fraction)
        val animFraction by animateFloatAsState(targetValue = fraction, animationSpec = tween(800), label = "storage_bar_proton")

        Text(
            stringResource(R.string.settings_storage_proton),
            color = colors.fgMute, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatBytes(state.cloudUsedBytes), color = if (hasCloud) barColor else colors.fgPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (hasCloud) {
                Spacer(Modifier.width(6.dp))
                Text("/ ${formatBytes(state.cloudMaxBytes)}", color = colors.fgDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                Spacer(Modifier.weight(1f))
                Text("$usedPct%", color = barColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(colors.line2, RoundedCornerShape(3.dp))) {
            if (hasCloud && animFraction > 0f) {
                Box(modifier = Modifier.fillMaxWidth(animFraction).height(6.dp).background(barColor, RoundedCornerShape(3.dp)))
            }
        }
        if (state.backedUpBytes > 0L) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_backup_size, formatBytes(state.backedUpBytes)), color = colors.fgMute, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun DeviceStorageRow(state: SettingsUiState) {
    val colors = AppColors.current
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        val hasData = state.deviceTotalBytes > 0L
        val used = (state.deviceTotalBytes - state.deviceFreeBytes).coerceAtLeast(0L)
        val fraction = if (hasData) (used.toFloat() / state.deviceTotalBytes).coerceIn(0f, 1f) else 0f
        val usedPct  = (fraction * 100).toInt()
        val barColor = storageColor(fraction)
        val animFraction by animateFloatAsState(targetValue = fraction, animationSpec = tween(800), label = "storage_bar_device")

        Text(
            stringResource(R.string.settings_storage_device),
            color = colors.fgMute, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatBytes(state.deviceFreeBytes),
                color = if (hasData) barColor else colors.fgPrimary,
                fontSize = 22.sp, fontWeight = FontWeight.Bold,
            )
            if (hasData) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "/ ${formatBytes(state.deviceTotalBytes)}",
                    color = colors.fgDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp),
                )
                Spacer(Modifier.weight(1f))
                Text("$usedPct%", color = barColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(colors.line2, RoundedCornerShape(3.dp))) {
            if (hasData && animFraction > 0f) {
                Box(modifier = Modifier.fillMaxWidth(animFraction).height(6.dp).background(barColor, RoundedCornerShape(3.dp)))
            }
        }
        if (hasData) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.settings_storage_device_free,
                    formatBytes(state.deviceFreeBytes),
                    formatBytes(state.deviceTotalBytes),
                ),
                color = colors.fgMute, fontSize = 11.5.sp,
            )
        }
    }
}

@Composable
private fun AppCacheRow(state: SettingsUiState, onClearCache: () -> Unit) {
    val colors = AppColors.current
    var showConfirm by remember { mutableStateOf(false) }
    // Color hint: amber over ~1 GB to nudge users without screaming-red.
    // Cache is benign — losing it just costs re-download bandwidth.
    val warn = state.appCacheBytes > 1L * 1024 * 1024 * 1024
    val valueColor = if (warn) StatusPending else colors.fgPrimary

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_storage_app_cache),
                color = colors.fgMute, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatBytes(state.appCacheBytes),
                color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_storage_app_cache_desc),
                color = colors.fgMute, fontSize = 11.5.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .background(colors.surfaceWeak, RoundedCornerShape(10.dp))
                .border(0.5.dp, colors.pillBorder, RoundedCornerShape(10.dp))
                .clickable(enabled = state.appCacheBytes > 0L) { showConfirm = true }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.settings_storage_clear_cache),
                color = if (state.appCacheBytes > 0L) colors.accent else colors.fgMute,
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
    if (showConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_storage_clear_cache_dialog_title),
            message = stringResource(
                R.string.settings_storage_clear_cache_dialog_message,
                formatBytes(state.appCacheBytes),
            ),
            confirmLabel = stringResource(R.string.settings_storage_clear_cache),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showConfirm = false; onClearCache() },
            onDismiss = { showConfirm = false },
        )
    }
}

// ── Shared composables ────────────────────────────────────────────────────────

@Composable
private fun RecentlyDeletedCard(
    count: Int,
    cloudCount: Int?,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardBg, cardShape)
            .border(0.5.dp, colors.cardBorder, cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Trash icon badge
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(StatusError.copy(alpha = 0.13f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = colors.errorColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_recently_deleted),
                    color = colors.fgPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    recentlyDeletedSubtitle(count, cloudCount),
                    color = if (count > 0 || (cloudCount ?: 0) > 0) {
                        colors.errorColor.copy(alpha = 0.75f)
                    } else colors.fgMute,
                    fontSize = 11.5.sp,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = colors.fgMute,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}


