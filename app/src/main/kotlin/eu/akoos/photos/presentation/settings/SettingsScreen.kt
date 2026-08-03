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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.ErrorPopup
import eu.akoos.photos.presentation.common.FloatingHeaderScrim
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.common.ShimmerBox
import eu.akoos.photos.presentation.common.ShimmerTextLine
import eu.akoos.photos.util.sanitizeErrorMessage
import eu.akoos.photos.presentation.settings.components.AppLockTimeoutRow
import eu.akoos.photos.presentation.settings.components.CollapsibleSection
import eu.akoos.photos.presentation.settings.components.ExpandableHeaderRow
import eu.akoos.photos.presentation.settings.components.IndentedNavRow
import eu.akoos.photos.presentation.settings.components.InfoRow
import eu.akoos.photos.presentation.settings.components.NavRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.settings.components.SelectRow
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
 * Subtitle for the "Recently Deleted" entry, combines device-side count (known) with
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
    onActivityClick: () -> Unit = {},
    onStorageClick: () -> Unit = {},
    onPrivacySecurityClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onRecentlyDeletedClick: () -> Unit = {},
    onFindDuplicatesClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onLanguageClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    onFaqClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onCheckForUpdatesClick: () -> Unit = {},
    onWhatsNewClick: () -> Unit = {},
    onNewsClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val diagnosticsCopiedMsg = stringResource(R.string.settings_diagnostics_copied)
    val shareDiagnosticsChooserTitle = stringResource(R.string.settings_copy_diagnostics)
    // Diagnostics can leave the device two ways: a system share sheet (mail, chat) or the
    // clipboard. The row opens a small chooser offering both; the assembled text is identical
    // for either (privacy-safe: numbers, types and flags only, never photos or account data).
    var showDiagnosticsChooser by remember { mutableStateOf(false) }

    // Sync errors render in a copyable [ErrorPopup]: `state.syncError` is set from raw
    // exception messages whose payload can be a multi-line backend response, so a
    // 4-second auto-dismiss snackbar isn't enough time to read or act on it. The
    // snackbarHost stays mounted below for other transient confirmations.
    if (state.syncError != null) {
        ErrorPopup(
            title = stringResource(R.string.settings_sync_failed),
            message = sanitizeErrorMessage(state.syncError),
            onDismiss = viewModel::clearSyncError,
            onCopy = {},
        )
    }

    if (showDiagnosticsChooser) {
        // Assembles the privacy-safe bundle once for whichever exit the user picks. Header
        // (app + version + code, manufacturer/model, Android release + sdk), then the crash
        // records file, the sync log, and the perf snapshot + ring buffer. Numbers, counts,
        // types and flags only, wrapped in a markdown fence. Never photos or account data.
        val buildDiagnostics = {
            val header = buildString {
                append("Photos for Proton ")
                append(BuildConfig.VERSION_NAME)
                append(" (")
                append(BuildConfig.VERSION_CODE)
                append(")\n")
                append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("Android ").append(Build.VERSION.RELEASE)
                append(" (sdk ").append(Build.VERSION.SDK_INT).append(')')
            }
            // Crash records (privacy-safe: types + code frames only) live in a small file
            // so they survive the crash; fold them into the same bundle as the sync log.
            val crashLog = runCatching {
                java.io.File(java.io.File(context.filesDir, "diagnostics"), "last_crash.txt")
                    .takeIf { it.exists() }?.readText().orEmpty()
            }.getOrDefault("")
            val sync = if (eu.akoos.photos.util.SyncDiagnostics.isEmpty()) ""
                else eu.akoos.photos.util.SyncDiagnostics.dump()
            // Live heap / RAM / library-size snapshot plus the perf ring buffer. Numbers,
            // counts, and flags only (mirrors SyncDiagnostics) so nothing identifies the account.
            val perfSnapshot = eu.akoos.photos.util.PerfDiagnostics.snapshot(context)
            val perfBuffer = if (eu.akoos.photos.util.PerfDiagnostics.isEmpty()) ""
                else eu.akoos.photos.util.PerfDiagnostics.dump()
            // Whether the hidden vault's index, its files on disk and its pending hides agree, read
            // when this chooser opened. Counts and byte totals only, like every section beside it, so
            // it answers "is this vault consistent" without naming a single photo or folder.
            val vault = state.vaultDiagnostics
            val body = buildString {
                append("Performance:\n").append(perfSnapshot)
                if (perfBuffer.isNotBlank()) append('\n').append(perfBuffer)
                if (vault.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Vault:\n").append(vault)
                }
                if (sync.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Sync:\n").append(sync)
                }
                if (crashLog.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Crashes:\n").append(crashLog.trim())
                }
                if (isEmpty()) append("no log yet")
            }
            "```\n$header\n\n$body\n```"
        }
        // Styled to match [ConfirmDialog], but with two distinct actions (Share / Copy) instead
        // of a confirm+cancel pair, so a back press or scrim tap just closes without exporting.
        AlertDialog(
            onDismissRequest = { showDiagnosticsChooser = false },
            containerColor = colors.cardBg,
            titleContentColor = colors.fgPrimary,
            textContentColor = colors.fgDim,
            title = {
                Text(stringResource(R.string.settings_copy_diagnostics), fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text(
                    stringResource(R.string.settings_copy_diagnostics_desc),
                    color = colors.fgDim,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiagnosticsChooser = false
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, buildDiagnostics())
                    }
                    val chooser = Intent.createChooser(send, shareDiagnosticsChooserTitle)
                    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(chooser) }
                }) {
                    Text(
                        stringResource(R.string.share_action),
                        color = colors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiagnosticsChooser = false
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(buildDiagnostics()))
                    android.widget.Toast.makeText(
                        context, diagnosticsCopiedMsg, android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }) {
                    Text(stringResource(R.string.settings_diagnostics_copy_clipboard), color = colors.fgDim)
                }
            },
        )
    }

    // Enabling the screenshot quick-action bar needs the draw-over-other-apps grant. When it is
    // missing this launcher opens the system permission screen and, on return, only turns the
    // feature on once the grant actually landed.
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (android.provider.Settings.canDrawOverlays(context)) {
            viewModel.setScreenshotOverlayEnabled(true)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
        val contentTopPad = floatingHeaderContentTopPadding()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // Clear the floating header that draws over this scrolling content.
            Spacer(Modifier.height(contentTopPad))

            // ── Account ───────────────────────────────────────────────────────
            // The whole row is now a tap target, opens AccountScreen with avatar,
            // storage, web links, and sign out. Sign out moved out of the row to
            // avoid the cramped triple hit area (avatar / text / sign out) of the old
            // layout, and to keep the destructive action behind one more deliberate
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
                    if (state.accountLoading) {
                        ShimmerBox(modifier = Modifier.size(38.dp).clip(CircleShape), cornerRadius = 19.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            ShimmerTextLine(widthFraction = 0.55f, height = 14.dp)
                            Spacer(Modifier.height(6.dp))
                            ShimmerTextLine(widthFraction = 0.35f, height = 12.dp)
                        }
                    } else {
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
                        .clickable(onClick = onActivityClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(stringResource(R.string.sync_backed_up), color = colors.fgMute, fontSize = 11.sp)
                        if (state.countsLoading) {
                            Spacer(Modifier.height(4.dp))
                            ShimmerTextLine(widthFraction = 1f, height = 14.dp, modifier = Modifier.width(72.dp))
                        } else {
                            // Split photos vs videos so the user can spot at a glance that the
                            // backed-up total isn't pure-photos. Reuses the same selection_* strings
                            // as the gallery/album selection counter, already translated to 6 locales.
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
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.sync_pending), color = colors.fgMute, fontSize = 11.sp)
                        if (state.countsLoading) {
                            Spacer(Modifier.height(4.dp))
                            ShimmerTextLine(widthFraction = 1f, height = 14.dp, modifier = Modifier.width(56.dp))
                        } else {
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
                    }
                    if (state.isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = colors.fgMute, modifier = Modifier.size(13.dp))
                    }
                }
                // Deferral note, when the auto-sync drain is held back (waiting for Wi-Fi /
                // preparing the first backup) the pending count sits above zero with no active
                // upload. A one-line reason keeps "queued but idle" from reading as broken.
                state.uploadDeferReason?.let { reasonRes ->
                    Text(
                        stringResource(reasonRes),
                        color = colors.fgDim,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).padding(bottom = 8.dp),
                    )
                }
                // ── Progress bar + expandable per-file list (while syncing OR pending) ──
                // This renders *inside* the same Sync card (not as a separate card) so the
                // header row's tap-target stays the gateway to Sync Settings, only the panel
                // below stays interactive in its own right.
                //
                // Visibility = isSyncing OR pending > 0, the OR side means the panel is up
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
                    // Fall back to pending count when no live upload total exists yet so
                    // the user sees "0 / N" before the first per-file event arrives.
                    val displayTotal = if (state.uploadTotalCount > 0) state.uploadTotalCount else pending
                    SyncProgressPanel(
                        done = state.uploadDoneCount,
                        total = displayTotal,
                        events = state.uploadEvents,
                        bytesPerSecond = state.uploadBytesPerSecond,
                    )
                }
                // The whole status card opens the live Activity view; this row names that target so
                // the tap is obvious (transfers + photos still waiting to upload).
                RowDivider()
                NavRow(
                    label = stringResource(R.string.activity_title),
                    description = stringResource(R.string.activity_row_desc),
                    onClick = onActivityClick,
                )
                // Explicit entry to the backup settings, clearer than only the tappable status row.
                RowDivider()
                NavRow(
                    label = stringResource(R.string.sync_open_settings),
                    description = stringResource(R.string.sync_open_settings_desc),
                    onClick = onSyncSettingsClick,
                )
            }
            }

            Spacer(Modifier.height(20.dp))

            // ── Storage section ───────────────────────────────────────────────
            // Recently Deleted + Storage nav grouped together, both are about
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
                RowDivider()
                NavRow(
                    label = stringResource(R.string.settings_find_duplicates),
                    description = stringResource(R.string.settings_find_duplicates_desc),
                    onClick = onFindDuplicatesClick,
                )
            }
            }

            Spacer(Modifier.height(20.dp))

            // ── Settings nav rows ─────────────────────────────────────────────
            CollapsibleSection(label = stringResource(R.string.settings_section_settings)) {
            SettingsCard {
                NavRow(
                    label = stringResource(R.string.settings_privacy_security),
                    onClick = onPrivacySecurityClick,
                )
                RowDivider()
                NavRow(
                    label = stringResource(R.string.permissions_title),
                    description = stringResource(R.string.permissions_intro),
                    onClick = onPermissionsClick,
                )
                RowDivider()
                NavRow(
                    label = stringResource(R.string.notifications_title),
                    description = stringResource(R.string.notifications_nav_desc),
                    onClick = onNotificationsClick,
                )
                RowDivider()
                // Appearance + Language merged into one entry, the destination is the
                // unified appearance screen which now hosts theme + palette + language
                // in a single scroll. Cuts an entire row from the Settings list.
                NavRow(
                    label = stringResource(R.string.settings_appearance),
                    description = stringResource(R.string.settings_appearance_desc),
                    onClick = onAppearanceClick,
                )
                RowDivider()
                NavRow(
                    label = stringResource(R.string.settings_copy_diagnostics),
                    description = stringResource(R.string.settings_copy_diagnostics_desc),
                    onClick = {
                        // The vault snapshot is read on demand, so ask for it as the chooser opens
                        // rather than keeping a directory walk live behind every settings change.
                        viewModel.refreshVaultDiagnostics()
                        showDiagnosticsChooser = true
                    },
                )
            }
            }

            Spacer(Modifier.height(20.dp))

            // ── About ──────────────────────────────────────────────────────────
            // App identity, what's new, news, help, and the update check the version
            // answers for, gathered as their own section rather than trailing the settings.
            CollapsibleSection(label = stringResource(R.string.settings_section_about)) {
            SettingsCard {
                NavRow(
                    label = stringResource(R.string.whats_new_title),
                    description = stringResource(R.string.whats_new_history_desc),
                    onClick = onWhatsNewClick,
                )
                RowDivider()
                NavRow(
                    label = stringResource(R.string.news_title),
                    description = stringResource(R.string.news_settings_desc),
                    onClick = onNewsClick,
                )
                RowDivider()
                NavRow(
                    label = stringResource(R.string.faq_settings_entry),
                    description = stringResource(R.string.faq_title),
                    onClick = onFaqClick,
                )
                RowDivider()
                NavRow(
                    label = stringResource(R.string.about_title),
                    description = stringResource(R.string.settings_about_desc),
                    onClick = onAboutClick,
                )
                RowDivider()
                NavRow(
                    label = stringResource(R.string.update_check_settings_row),
                    description = stringResource(
                        R.string.update_check_settings_summary,
                        BuildConfig.VERSION_NAME,
                    ),
                    onClick = onCheckForUpdatesClick,
                )
            }
            }

            Spacer(Modifier.height(20.dp))

            // ── Extras ─────────────────────────────────────────────────────────
            // Home for optional utility features that sit outside the core backup
            // flow. The screenshot quick-action bar is the first entry here.
            CollapsibleSection(label = stringResource(R.string.settings_section_extras)) {
            SettingsCard {
                // Screenshot quick actions is a utility bar over a fresh screenshot; enabling it
                // needs the draw-over-other-apps grant, so the toggle routes through the launcher
                // and only flips on once that permission actually landed.
                ToggleRow(
                    label = stringResource(R.string.settings_screenshot_overlay),
                    description = stringResource(R.string.settings_screenshot_overlay_desc),
                    checked = state.screenshotOverlayEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            if (android.provider.Settings.canDrawOverlays(context)) {
                                viewModel.setScreenshotOverlayEnabled(true)
                            } else {
                                overlayPermissionLauncher.launch(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            }
                        } else {
                            viewModel.setScreenshotOverlayEnabled(false)
                        }
                    },
                )
            }
            }

            // Debug-only large-library simulator. Compiled out of release by the BuildConfig.DEBUG
            // guard, invisible and unreachable in production builds.
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(20.dp))
                LargeLibrarySimCard()
            }
        }

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())

        // Floating header, close button on the left (the same side every sub-page puts back),
        // title centered in a pill; the list scrolls under it.
        val debouncedClose = rememberDebouncedAction { onBack() }
        FloatingHeaderScrim()
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBubble(
                icon = Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
                onClick = debouncedClose,
                diameter = 40.dp,
                iconSize = 16.dp,
                background = colors.surfaceWeak,
                borderColor = colors.pillBorder,
                tint = colors.fgDim,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceWeak, RoundedCornerShape(20.dp))
                    .border(0.5.dp, colors.pillBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(stringResource(R.string.settings_title), color = colors.fgPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(40.dp))
        }
    }

}

// ── Sync Settings sub-page ────────────────────────────────────────────────────

@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    onBackupContentClick: () -> Unit = {},
    onBackupBehaviorClick: () -> Unit = {},
    onNetworkClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    SettingsSubPageScaffold(title = stringResource(R.string.sync_section), onBack = onBack) {
        // Hub: each backup concern opens its own focused sub-page instead of one
        // long mixed scroll (what gets backed up / how it runs / network usage).
        SettingsCard {
            NavRow(
                label = stringResource(R.string.settings_what_backed_up_section),
                onClick = onBackupContentClick,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.settings_backup_how_section),
                onClick = onBackupBehaviorClick,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.settings_network_section),
                onClick = onNetworkClick,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Sync now action row ──────────────────────────────────────────────
        // Imperative one-tap action (not a persistent setting), so it sits on its
        // own card with an accent label that reads as a button.
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
}

// ── Backup content sub-page (what gets backed up) ────────────────────────────

@Composable
fun BackupContentSettingsScreen(
    onBack: () -> Unit,
    onBackupFoldersClick: () -> Unit = {},
    onExcludedFoldersClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsSubPageScaffold(title = stringResource(R.string.settings_what_backed_up_section), onBack = onBack) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_backup_everything),
                description = stringResource(R.string.settings_backup_everything_desc),
                checked = state.backupEverything,
                onCheckedChange = viewModel::setBackupEverything,
                enabled = state.autoSync,
            )
            // Include/exclude drilldown is mutually exclusive on the everything-toggle.
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
        }
    }
}

// ── Backup behaviour sub-page (how backup runs) ───────────────────────────────

@Composable
fun BackupBehaviorSettingsScreen(
    onBack: () -> Unit,
    onUploadProcessingClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsSubPageScaffold(title = stringResource(R.string.settings_backup_how_section), onBack = onBack) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_continuous_backup),
                description = stringResource(R.string.settings_continuous_backup_desc),
                checked = state.autoSync,
                onCheckedChange = viewModel::setAutoSync,
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

        Spacer(Modifier.height(20.dp))

        // Rename/strip/compress all happen as a photo is backed up, so the processing hub
        // sits with the other backup-behaviour controls.
        SettingsCard {
            NavRow(
                label = stringResource(R.string.settings_metadata),
                description = stringResource(R.string.settings_metadata_desc),
                onClick = onUploadProcessingClick,
            )
        }
    }
}

// ── Backup network sub-page (network usage) ───────────────────────────────────

@Composable
fun BackupNetworkSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsSubPageScaffold(title = stringResource(R.string.settings_network_section), onBack = onBack) {
        SettingsCard {
            // Sync Wi-Fi only gates backing UP to Drive (meaningful only while continuous
            // backup is on); full-res Wi-Fi only gates downloading full-resolution photos
            // for the viewer. Both are network-consumption choices, grouped here.
            ToggleRow(
                label = stringResource(R.string.settings_sync_wifi_only),
                description = stringResource(R.string.settings_sync_wifi_desc),
                checked = state.syncWifiOnly,
                onCheckedChange = viewModel::setSyncWifiOnly,
                enabled = state.autoSync,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_fullres_wifi_only),
                description = stringResource(R.string.settings_fullres_wifi_only_desc),
                checked = state.fullresWifiOnly,
                onCheckedChange = viewModel::setFullresWifiOnly,
            )
        }
    }
}

// ── Storage Settings sub-page ─────────────────────────────────────────────────

@Composable
fun StorageSettingsScreen(
    onBack: () -> Unit,
    onOpenTrash: (cloud: Boolean) -> Unit = {},
    onFreeUpSpace: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    LaunchedEffect(Unit) { viewModel.refreshLocalStorage() }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_storage_section), onBack = onBack) {
        // Manual refresh sits top-right; the two storage groups label themselves below.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
        StorageContent(
            state = state,
            onFreeUp = onFreeUpSpace,
            onClearCache = { viewModel.clearAppCache() },
            onClearOffline = { viewModel.clearOfflineStorage() },
            onOpenTrash = onOpenTrash,
        )

        Spacer(Modifier.height(20.dp))

        // The recurring counterpart to the manual "Free up" action on the device gauge above,
        // so it sits with it rather than under backup behaviour.
        SectionLabel(stringResource(R.string.settings_free_up_auto_section))
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_free_up_auto),
                description = stringResource(R.string.settings_free_up_auto_desc),
                checked = state.autoFreeUp,
                onCheckedChange = viewModel::setAutoFreeUp,
            )
            RowDivider()
            SelectRow(
                label = stringResource(R.string.settings_free_up_interval),
                description = stringResource(R.string.settings_free_up_interval_desc),
                selected = state.freeUpInterval,
                onSelected = viewModel::setFreeUpInterval,
                indented = true,
                enabled = state.autoFreeUp,
            )
        }
    }
}

// ── Privacy & Security hub ────────────────────────────────────────────────────

/** Hub that groups the privacy/metadata and security sub-pages under one Settings entry. */
@Composable
fun PrivacySecuritySettingsScreen(
    onBack: () -> Unit,
    onPrivacyClick: () -> Unit,
    onSecurityClick: () -> Unit,
) {
    SettingsSubPageScaffold(title = stringResource(R.string.settings_privacy_security), onBack = onBack) {
        SettingsCard {
            NavRow(
                label = stringResource(R.string.settings_privacy),
                description = stringResource(R.string.settings_privacy_desc),
                onClick = onPrivacyClick,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.settings_security),
                description = stringResource(R.string.settings_security_desc),
                onClick = onSecurityClick,
            )
        }
    }
}

// ── Metadata Settings sub-page (under Backup) ─────────────────────────────────
// Rename + EXIF stripping happen when a photo is backed up, so these controls live under
// Sync/Backup alongside the other backup options.

@Composable
fun MetadataSettingsScreen(
    onBack: () -> Unit,
    onOpenFileName: () -> Unit = {},
    onOpenMetadata: () -> Unit = {},
    onOpenQuality: () -> Unit = {},
) {
    SettingsSubPageScaffold(title = stringResource(R.string.settings_metadata), onBack = onBack) {
        // Hub: each processing concern opens its own focused sub-page (file name / metadata /
        // quality and size) instead of one long mixed scroll.
        SettingsCard {
            NavRow(
                label = stringResource(R.string.settings_section_file_name),
                onClick = onOpenFileName,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.settings_privacy_section_metadata),
                onClick = onOpenMetadata,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.settings_section_quality_size),
                onClick = onOpenQuality,
            )
        }
    }
}

// ── Upload processing: File name sub-page ─────────────────────────────────────
// How the uploaded copy is named. The on-device file keeps its own name.

@Composable
fun UploadFileNameSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsSubPageScaffold(title = stringResource(R.string.settings_section_file_name), onBack = onBack) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_rename_on_upload),
                description = stringResource(R.string.settings_rename_on_upload_desc),
                checked = state.renameToCaptureDate,
                onCheckedChange = viewModel::setRenameToCaptureDate,
            )
        }
    }
}

// ── Upload processing: Metadata sub-page ──────────────────────────────────────
// EXIF stripping on the uploaded copy, with an optional mirror to the original.

@Composable
fun UploadMetadataSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = AppColors.current
    SettingsSubPageScaffold(title = stringResource(R.string.settings_privacy_section_metadata), onBack = onBack) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_strip_metadata_upload),
                description = stringResource(R.string.settings_strip_metadata_upload_desc),
                checked = state.stripOnUpload,
                onCheckedChange = viewModel::setStripOnUpload,
            )
            if (state.stripOnUpload) {
                RowDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_mirror_strip_local),
                    description = stringResource(R.string.settings_mirror_strip_local_desc),
                    checked = state.mirrorStripToLocal,
                    onCheckedChange = { enabled ->
                        viewModel.setMirrorStripToLocal(enabled)
                        // Writing the on-device original in place needs all-files access on devices
                        // that refuse a silent MediaStore write even with MANAGE_MEDIA. Send the user
                        // to that grant screen when they opt in without it; until it's granted the
                        // mirror falls back to a temp-copy strip and the original stays untouched.
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            !android.os.Environment.isExternalStorageManager()
                        ) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            }.onFailure {
                                runCatching {
                                    context.startActivity(
                                        Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    )
                                }
                            }
                        }
                    },
                )
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
        if (state.stripOnUpload) {
            // Footnote: HEIC/HEIF/AVIF cannot store metadata edits, so the uploaded copy is
            // transcoded to JPEG; the on-device file is left untouched.
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.settings_strip_format_note),
                color = colors.fgMute,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

// ── Upload processing: Quality and size sub-page ──────────────────────────────
// Re-encode the uploaded copy smaller, with an optional mirror to the original.

@Composable
fun UploadQualitySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsSubPageScaffold(title = stringResource(R.string.settings_section_quality_size), onBack = onBack) {
        // The on-device mirror is a standalone toggle in its own card at the top: shrinking the copy
        // kept on this device applies to whichever of photos/videos is being compressed, and unlike
        // the type toggles below it reveals no tier list. The gap to the next card is the separator.
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_mirror_compress_local),
                description = stringResource(R.string.settings_mirror_compress_local_desc),
                checked = state.mirrorCompressToLocal,
                onCheckedChange = { enabled ->
                    viewModel.setMirrorCompressToLocal(enabled)
                    // Writing the on-device original in place needs all-files access on devices
                    // that refuse a silent MediaStore write even with MANAGE_MEDIA. Send the user
                    // to that grant screen when they opt in without it; until it's granted the
                    // mirror falls back to a temp-copy strip and the original stays untouched.
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        !android.os.Environment.isExternalStorageManager()
                    ) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                )
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                )
                            }
                        }
                    }
                },
            )
        }
        // The type toggles and the shared quality tier: each toggle reveals the tier list below when
        // either photos or videos are set to compress.
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_compress_photos),
                description = stringResource(R.string.settings_compress_upload_desc),
                checked = state.compressOnUpload,
                onCheckedChange = viewModel::setCompressOnUpload,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_compress_videos),
                description = stringResource(R.string.settings_compress_videos_desc),
                checked = state.compressVideosOnUpload,
                onCheckedChange = viewModel::setCompressVideosOnUpload,
            )
            // The quality tier governs both paths, so it shows whenever either toggle is on.
            if (state.compressOnUpload || state.compressVideosOnUpload) {
                UploadCompressionTier.entries.forEach { tier ->
                    RowDivider()
                    CompressTierRow(
                        label = stringResource(tier.labelRes),
                        description = stringResource(tier.descRes),
                        selected = state.compressTier == tier,
                        onClick = { viewModel.setCompressTier(tier) },
                    )
                }
            }
        }
    }
}

/** Single-choice row for the upload-compression tier picker. Mirrors the landing-tab radio row
 *  style (a filled check on the selected entry) but carries a one-line tradeoff description under
 *  the label, and sits indented under the "Compress uploads" toggle. */
@Composable
private fun CompressTierRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 32.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(description, color = colors.fgMute, fontSize = 12.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        if (selected) {
            Box(
                modifier = Modifier.size(20.dp).background(colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Check,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ── Privacy Settings sub-page ─────────────────────────────────────────────────

@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    onOfflinePhotosClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsSubPageScaffold(title = stringResource(R.string.settings_privacy), onBack = onBack) {
        // ── Device-side privacy card ─────────────────────────────────────────
        // What this device keeps locally after close: the cache, the offline copies,
        // and the read-only telemetry mirror.
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
                label = stringResource(R.string.settings_offline_photos),
                description = stringResource(R.string.offline_screen_empty),
                onClick = onOfflinePhotosClick,
            )
            RowDivider()
            // Telemetry events fired by the embedded ProtonCore stack are gated by
            // IsTelemetryEnabledImpl, which reads the server side `Telemetry`
            // preference. This is surfaced as a read-only mirror so the control's
            // location is discoverable; the actual switch lives in the Proton
            // account settings.
            InfoRow(
                label = stringResource(R.string.settings_telemetry),
                description = stringResource(R.string.settings_telemetry_desc),
                value = when (state.telemetryEnabled) {
                    true -> stringResource(R.string.settings_telemetry_on)
                    false -> stringResource(R.string.settings_telemetry_off)
                    // Unresolved stays neutral: the gate defaults to enabled when the
                    // account setting is unreadable, so "Off" would be a false assurance.
                    null -> stringResource(R.string.settings_telemetry_checking)
                },
            )
        }
        }
        // Footnote: the map draws its background from a public tile endpoint, which is the
        // one place the app reaches a server outside Proton.
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.settings_privacy_map_tiles_note),
            color = AppColors.current.fgMute,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

// ── Security Settings sub-page ────────────────────────────────────────────────

@Composable
fun SecuritySettingsScreen(
    onBack: () -> Unit,
    onHiddenAlbumClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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

        Spacer(Modifier.height(20.dp))
        // Hidden vault, a lock/biometric-gated photo collection, so it belongs with the app lock.
        SettingsCard {
            NavRow(
                label = stringResource(R.string.settings_hidden_photos),
                description = stringResource(R.string.settings_hidden_photos_desc),
                onClick = onHiddenAlbumClick,
            )
        }
    }
}

// ── Shared scaffold ───────────────────────────────────────────────────────────


// ── Sync progress panel (inside Sync card while syncing; reused by the Activity screen) ──

@Composable
internal fun SyncProgressPanel(
    done: Int,
    total: Int,
    events: List<UploadEvent>,
    bytesPerSecond: Long?,
    initiallyExpanded: Boolean = false,
) {
    val colors = AppColors.current
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val pct = (fraction * 100).toInt()
    val animFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(450),
        label = "sync_progress_bar",
    )
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp)) {
        // Linear progress bar, Material 3 default, with only the track/indicator colors
        // overridden so it matches the app accent. A hand-drawn rounded-rect background
        // sidesteps the M3 1.3 stop-indicator (which adds a small dot at the end and looked
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
            // so the panel doesn't need its own container background, only an 8.dp gap
            // between rows. The scroll height is still capped so the list can't push the
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
    // matching the gallery filter pills and the editor adjustment pills. Read-only -
    // no clickable modifier, so the row only communicates status, never invites taps.
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
        // falls back to a clock, the upload pipeline rarely emits it, but matching the
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
                // Same spinner shape as Uploading but rendered in the dimmer fgDim tint -
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
            // Live per-file progress bar while this photo is encrypting or uploading.
            if ((evt.status == UploadEventStatus.Uploading || evt.status == UploadEventStatus.Encrypting) &&
                evt.sizeBytes > 0L
            ) {
                val frac = (evt.doneBytes.toFloat() / evt.sizeBytes).coerceIn(0f, 1f)
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.line2),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(frac)
                            .height(3.dp)
                            .background(colors.accent, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        // Trailing status label, the label colour mirrors the icon so the eye reads
        // icon + label as a single status token.
        val (label, labelColor) = when (evt.status) {
            UploadEventStatus.Uploading -> stringResource(R.string.upload_status_uploading) to colors.accent
            UploadEventStatus.Encrypting -> stringResource(R.string.upload_status_encrypting) to colors.fgDim
            UploadEventStatus.Queued -> stringResource(R.string.upload_status_queued) to colors.fgMute
            UploadEventStatus.Done -> stringResource(R.string.upload_status_done) to StatusSynced
            UploadEventStatus.Failed -> stringResource(R.string.upload_status_failed) to colors.errorColor
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

/** Every storage card is locked to this height so a carousel never resizes as you swipe. */
private val storageCardHeight = 134.dp

/**
 * Storage overview as two swipeable carousels: a Proton Drive group (cloud quota + cloud trash) and
 * an on-device group (app cache, offline copies, device storage, device trash). Each card is a
 * full-width rectangle that fills with its usage fraction; one card shows at a time and the dots
 * track the position.
 */
@Composable
private fun StorageContent(
    state: SettingsUiState,
    onFreeUp: () -> Unit = {},
    onClearCache: () -> Unit = {},
    onClearOffline: () -> Unit = {},
    onOpenTrash: (cloud: Boolean) -> Unit = {},
) {
    val deviceTotal = state.deviceTotalBytes
    val deviceUsed = (deviceTotal - state.deviceFreeBytes).coerceAtLeast(0L)

    SectionLabel(stringResource(R.string.settings_storage_proton))
    Spacer(Modifier.height(8.dp))
    StorageCarousel(pageCount = 2) { page ->
        if (page == 0) {
            StorageGaugeCard(
                icon = Icons.Default.Cloud,
                label = stringResource(R.string.settings_storage_proton),
                value = formatBytes(state.cloudUsedBytes),
                detail = if (state.cloudMaxBytes > 0L)
                    stringResource(R.string.settings_storage_used_of, formatBytes(state.cloudUsedBytes), formatBytes(state.cloudMaxBytes))
                else formatBytes(state.cloudUsedBytes),
            )
        } else {
            StorageTrashCard(
                label = stringResource(R.string.settings_recently_deleted),
                count = state.cloudTrashCount ?: 0,
                onOpen = { onOpenTrash(true) },
            )
        }
    }

    Spacer(Modifier.height(20.dp))

    SectionLabel(stringResource(R.string.settings_storage_device))
    Spacer(Modifier.height(8.dp))
    StorageCarousel(pageCount = 4) { page ->
        when (page) {
            0 -> StorageGaugeCard(
                icon = Icons.Default.Storage,
                label = stringResource(R.string.settings_storage_app_cache),
                value = formatBytes(state.appCacheBytes),
                detail = stringResource(R.string.settings_storage_app_cache_desc),
                action = {
                    StorageClearAction(
                        enabled = state.appCacheBytes > 0L,
                        dialogTitle = stringResource(R.string.settings_storage_clear_cache_dialog_title),
                        dialogMessage = stringResource(R.string.settings_storage_clear_cache_dialog_message, formatBytes(state.appCacheBytes)),
                        onConfirm = onClearCache,
                    )
                },
            )
            1 -> StorageGaugeCard(
                icon = Icons.Default.OfflinePin,
                label = stringResource(R.string.settings_offline_storage_title),
                value = formatBytes(state.offlineBytes),
                detail = stringResource(R.string.settings_offline_storage_subtitle),
                action = {
                    StorageClearAction(
                        enabled = state.offlineBytes > 0L,
                        dialogTitle = stringResource(R.string.settings_offline_storage_clear_dialog_title),
                        dialogMessage = stringResource(R.string.settings_offline_storage_clear_dialog_message, formatBytes(state.offlineBytes)),
                        onConfirm = onClearOffline,
                    )
                },
            )
            2 -> StorageGaugeCard(
                icon = Icons.Default.PhoneAndroid,
                label = stringResource(R.string.settings_storage_device),
                value = formatBytes(state.deviceFreeBytes),
                detail = stringResource(R.string.settings_storage_device_free, formatBytes(state.deviceFreeBytes), formatBytes(deviceTotal)),
                action = { StorageFreeUpAction(onFreeUp = onFreeUp) },
            )
            else -> StorageTrashCard(
                label = stringResource(R.string.settings_recently_deleted),
                count = state.trashedCount,
                onOpen = { onOpenTrash(false) },
            )
        }
    }
}

/** One-card-at-a-time carousel with page dots underneath. */
@Composable
private fun StorageCarousel(pageCount: Int, content: @Composable (Int) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pageCount })
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxWidth().height(storageCardHeight),
        ) { page ->
            content(page)
        }
        Spacer(Modifier.height(12.dp))
        StoragePagerDots(current = pagerState.currentPage, count = pageCount)
    }
}

/** A storage type as a full-width card: an icon + label, the size as a big value, and a detail line,
 *  with an optional action square separated at the right. */
@Composable
private fun StorageGaugeCard(
    icon: ImageVector,
    label: String,
    value: String,
    detail: String,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.cardBg)
            .border(0.5.dp, colors.cardBorder, RoundedCornerShape(18.dp)),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = colors.fgMute, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Text(value, color = colors.fgPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    detail, color = colors.fgMute, fontSize = 12.sp,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        if (action != null) {
            Box(modifier = Modifier.width(0.5.dp).fillMaxHeight().background(colors.cardBorder))
            Box(
                modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                action()
            }
        }
    }
}

/** Recently-deleted as a card: the item count fills the rectangle, with a centered action that
 *  opens the trash. */
@Composable
private fun StorageTrashCard(label: String, count: Int, onOpen: () -> Unit) {
    val colors = AppColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.cardBg)
            .border(0.5.dp, colors.cardBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, color = colors.fgMute, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = colors.fgMute, modifier = Modifier.size(13.dp))
            }
            // The count sits centred on its own soft accent panel so the card reads as a real tile,
            // not a bare number.
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(colors.accent.copy(alpha = 0.20f), colors.accent2.copy(alpha = 0.10f)),
                            ),
                        )
                        .border(0.5.dp, colors.accent.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 34.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(count.toString(), color = colors.fgPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Carousel page-indicator dots; the active one stretches into a pill. */
@Composable
private fun StoragePagerDots(current: Int, count: Int) {
    val colors = AppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = if (active) 18.dp else 6.dp, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (active) colors.accent else colors.line2),
            )
        }
    }
}

/** A separated square action at the card's right edge: a delete icon over a short label, dimmed when
 *  disabled. */
@Composable
private fun StorageActionSquare(label: String, destructive: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    val tint = when {
        !enabled -> colors.fgMute
        destructive -> colors.errorColor
        else -> colors.accent
    }
    val bg = if (destructive && enabled) colors.deleteTint else colors.surfaceWeak
    val borderColor = if (destructive && enabled) colors.errorColor.copy(alpha = 0.3f) else colors.pillBorder
    Column(
        modifier = Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(0.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Delete, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Square button that confirms clearing a reclaimable store (cache / offline copies). */
@Composable
private fun StorageClearAction(
    enabled: Boolean,
    dialogTitle: String,
    dialogMessage: String,
    onConfirm: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    StorageActionSquare(
        label = stringResource(R.string.settings_storage_clear),
        destructive = false,
        enabled = enabled,
        onClick = { showConfirm = true },
    )
    if (showConfirm) {
        ConfirmDialog(
            title = dialogTitle,
            message = dialogMessage,
            confirmLabel = stringResource(R.string.settings_storage_clear),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showConfirm = false; onConfirm() },
            onDismiss = { showConfirm = false },
        )
    }
}

/** Opens the Free up space screen. The reclaim itself no longer starts from here: it permanently
 *  deletes the device copy of potentially thousands of photos, and a confirm sheet asking about a
 *  number is a weaker thing to agree to than the list of photos that screen shows first. The whole
 *  control is centered under the device gauge. */
@Composable
private fun StorageFreeUpAction(onFreeUp: () -> Unit) {
    StorageActionSquare(
        label = stringResource(R.string.settings_storage_free_up),
        destructive = true,
        enabled = true,
        onClick = onFreeUp,
    )
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


// ── Shared composables ────────────────────────────────────────────────────────

// ── Debug-only large-library simulator card ───────────────────────────────────

/**
 * DEBUG-only card to drive the [eu.akoos.photos.data.repository.drive.LargeLibrarySimulator].
 * Set N, Populate to generate N synthetic photos that exercise the real decrypt + cache treadmill
 * with no CDN traffic, or Clear to remove them. Only ever rendered behind a BuildConfig.DEBUG guard.
 * Strings are inline English, this surface never ships, so it isn't localized.
 */
@Composable
private fun LargeLibrarySimCard(
    viewModel: LargeLibrarySimViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by remember(state.count) { mutableStateOf(if (state.count > 0) state.count.toString() else "") }

    SectionLabel("Developer, large library simulator")
    Spacer(Modifier.height(8.dp))
    SettingsCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Generate synthetic photos that run the real decrypt + cache pipeline with no Proton traffic.",
                color = colors.fgMute, fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(7)
                    text = digits
                    viewModel.setCount(digits.toIntOrNull() ?: 0)
                },
                label = { Text("Photo count (e.g. 21000)") },
                singleLine = true,
                enabled = !state.running,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Button(
                    onClick = { viewModel.populate() },
                    enabled = !state.running && state.count > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("Populate") }
                Spacer(Modifier.width(12.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = { viewModel.clear() },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) { Text("Clear simulation") }
            }
            if (state.running) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                    Spacer(Modifier.width(10.dp))
                    Text("Working…", color = colors.fgMute, fontSize = 12.sp)
                }
            }
            state.message?.let { msg ->
                Spacer(Modifier.height(10.dp))
                Text(msg, color = colors.fgDim, fontSize = 12.sp)
            }
        }
    }
}



