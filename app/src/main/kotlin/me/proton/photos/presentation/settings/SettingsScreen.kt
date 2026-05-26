package me.proton.photos.presentation.settings

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.proton.photos.R
import me.proton.photos.presentation.theme.AppColors
import me.proton.photos.presentation.theme.StatusError
import me.proton.photos.presentation.theme.StatusPending
import me.proton.photos.presentation.theme.StatusSynced
import me.proton.photos.presentation.util.formatBytes

private val cardShape = RoundedCornerShape(12.dp)

private fun storageColor(fraction: Float): Color = when {
    fraction < 0.70f -> StatusSynced
    fraction < 0.90f -> StatusPending
    else             -> StatusError
}

// ── Main Settings screen ──────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSyncSettingsClick: () -> Unit = {},
    onPrivacySettingsClick: () -> Unit = {},
    onSecuritySettingsClick: () -> Unit = {},
    onRecentlyDeletedClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onLanguageClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showSignOutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.syncError) {
        state.syncError?.let { snackbarHostState.showSnackbar(it); viewModel.clearSyncError() }
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
                Box(
                    modifier = Modifier.size(36.dp).background(colors.surfaceWeak, CircleShape)
                        .border(0.5.dp, colors.pillBorder, CircleShape).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, null, tint = colors.fgDim, modifier = Modifier.size(16.dp))
                }
            }

            // ── Account ───────────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_account_section))
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
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
                    Text(
                        stringResource(R.string.settings_sign_out), color = colors.errorColor, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { showSignOutDialog = true },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Sync (backup status, realtime) ────────────────────────────────
            SectionLabel(stringResource(R.string.sync_section))
            Spacer(Modifier.height(8.dp))
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
                val showPanel = state.isSyncing || (pending > 0 && state.uploadTotalCount > 0)
                if (showPanel) {
                    // Fall back to pending count if we don't have a live upload total yet so
                    // the user sees "0 / N" before the first per-file event arrives.
                    val displayTotal = if (state.uploadTotalCount > 0) state.uploadTotalCount else pending
                    SyncProgressPanel(
                        done = state.uploadDoneCount,
                        total = displayTotal,
                        events = state.uploadEvents,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Storage (realtime) ────────────────────────────────────────────
            // Refresh-on-entry so the device-storage and app-cache numbers don't lag behind
            // an external file delete that happened while we were elsewhere in the app.
            LaunchedEffect(Unit) { viewModel.refreshLocalStorage() }
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

            // ── Recently Deleted ──────────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_recently_deleted))
            Spacer(Modifier.height(8.dp))
            RecentlyDeletedCard(
                count = state.trashedCount,
                onClick = onRecentlyDeletedClick,
            )

            Spacer(Modifier.height(24.dp))

            // ── Settings nav rows ─────────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_settings))
            Spacer(Modifier.height(8.dp))
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
                NavRow(
                    label = stringResource(R.string.settings_appearance),
                    description = stringResource(R.string.settings_appearance_desc),
                    onClick = onAppearanceClick,
                )
                RowDivider()
                NavRow(
                    label = stringResource(R.string.settings_language),
                    description = stringResource(R.string.language_section_desc),
                    onClick = onLanguageClick,
                )
                RowDivider()
                NavRow(
                    label = stringResource(R.string.about_title),
                    description = stringResource(R.string.settings_about_desc),
                    onClick = onAboutClick,
                )
            }
        }

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            containerColor = colors.bg2, titleContentColor = colors.fgPrimary, textContentColor = colors.fgDim,
            title = { Text(stringResource(R.string.sign_out_dialog_title)) },
            text  = { Text(stringResource(R.string.sign_out_dialog_message)) },
            confirmButton = {
                TextButton(onClick = { showSignOutDialog = false; viewModel.signOut() }) {
                    Text(stringResource(R.string.settings_sign_out), color = colors.errorColor, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(R.string.cancel), color = colors.fgDim)
                }
            },
        )
    }
}

// ── Sync Settings sub-page ────────────────────────────────────────────────────

@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    onBackupFoldersClick: () -> Unit = {},
    onRecentlyDeletedClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    SubPageScaffold(title = stringResource(R.string.sync_section), onBack = onBack) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_auto_sync),
                description = stringResource(R.string.settings_auto_sync_desc),
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
            SyncIntervalRow(
                label = stringResource(R.string.settings_sync_interval),
                description = stringResource(R.string.settings_sync_interval_desc),
                selectedMinutes = state.syncIntervalMinutes,
                onSelected = viewModel::setSyncIntervalMinutes,
                indented = true,
                enabled = state.autoSync,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_background_sync),
                description = stringResource(R.string.settings_background_sync_desc),
                checked = state.autoFreeUp,
                onCheckedChange = viewModel::setAutoFreeUp,
                indented = true,
                enabled = state.autoSync,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.settings_backup_folders),
                description = stringResource(R.string.settings_backup_folders_desc),
                onClick = onBackupFoldersClick,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_auto_backup_new_folders),
                description = stringResource(R.string.settings_auto_backup_new_folders_desc),
                checked = state.autoBackupNewFolders,
                onCheckedChange = viewModel::setAutoBackupNewFolders,
                indented = true,
                enabled = state.autoSync,
            )
            RowDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_sync_now), color = colors.fgPrimary, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                if (state.isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                } else {
                    Text(
                        stringResource(R.string.settings_sync_now_action), color = colors.accent, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable(enabled = !state.isSyncing) { viewModel.syncNow() },
                    )
                }
            }
        }
    }
}

// ── Privacy Settings sub-page ─────────────────────────────────────────────────

@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SubPageScaffold(title = stringResource(R.string.settings_privacy_metadata), onBack = onBack) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_strip_metadata_upload),
                description = stringResource(R.string.settings_strip_metadata_upload_desc),
                checked = state.stripOnUpload,
                onCheckedChange = viewModel::setStripOnUpload,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_strip_gps),
                description = stringResource(R.string.settings_strip_gps_desc),
                checked = state.stripGps,
                onCheckedChange = viewModel::setStripGps,
                indented = true,
                enabled = state.stripOnUpload,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_strip_camera),
                description = stringResource(R.string.settings_strip_camera_desc),
                checked = state.stripCameraInfo,
                onCheckedChange = viewModel::setStripCameraInfo,
                indented = true,
                enabled = state.stripOnUpload,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_strip_timestamp),
                description = stringResource(R.string.settings_strip_timestamp_desc),
                checked = state.stripTimestamp,
                onCheckedChange = viewModel::setStripTimestamp,
                indented = true,
                enabled = state.stripOnUpload,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_strip_software),
                description = stringResource(R.string.settings_strip_software_desc),
                checked = state.stripSoftwareInfo,
                onCheckedChange = viewModel::setStripSoftwareInfo,
                indented = true,
                enabled = state.stripOnUpload,
            )
        }
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
    val colors = AppColors.current
    SubPageScaffold(title = stringResource(R.string.settings_security), onBack = onBack) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_app_lock),
                description = stringResource(R.string.settings_app_lock_desc),
                checked = state.appLockEnabled,
                onCheckedChange = { viewModel.setAppLockEnabled(it) },
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.settings_hidden_photos),
                description = stringResource(R.string.settings_hidden_photos_desc),
                onClick = onHiddenAlbumClick,
            )
        }

        Spacer(Modifier.height(20.dp))

        // OS-level media management permission (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            val canManage = remember { MediaStore.canManageMedia(context) }
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

// ── Shared scaffold ───────────────────────────────────────────────────────────

@Composable
private fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = AppColors.current
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(36.dp).background(colors.surfaceWeak, CircleShape)
                        .border(0.5.dp, colors.pillBorder, CircleShape).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colors.fgDim, modifier = Modifier.size(16.dp))
                }
                Text(title, color = colors.fgPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

// ── Sync progress panel (inside Sync card while syncing) ─────────────────────

@Composable
private fun SyncProgressPanel(
    done: Int,
    total: Int,
    events: List<UploadEvent>,
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
            Spacer(Modifier.height(8.dp))
            // Show the most recent activity at the top (mirrors a download manager).
            val ordered = remember(events) { events.asReversed() }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 200.dp)
                    .background(colors.surfaceWeak, RoundedCornerShape(8.dp))
                    .padding(vertical = 4.dp),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status glyph: spinner / check / X. Internal "Queued" label kept English-only per task
        // scope — the actual upload code path doesn't emit it today, so users rarely see it.
        when (evt.status) {
            UploadEventStatus.Uploading -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = colors.accent,
            )
            UploadEventStatus.Done -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = StatusSynced,
                modifier = Modifier.size(14.dp),
            )
            UploadEventStatus.Failed -> Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = StatusError,
                modifier = Modifier.size(14.dp),
            )
            UploadEventStatus.Queued -> Box(
                modifier = Modifier.size(8.dp).background(colors.fgMute, CircleShape),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = evt.displayName,
            color = colors.fgPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // Inline status label — internal v1 strings, kept English (caught in localization pass).
        val (label, color) = when (evt.status) {
            UploadEventStatus.Uploading -> "Uploading" to colors.accent
            UploadEventStatus.Done -> "Done" to StatusSynced
            UploadEventStatus.Failed -> "Failed" to StatusError
            UploadEventStatus.Queued -> "Queued" to colors.fgMute
        }
        Text(label, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
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
            // Pill-style destructive button — same red-tint + thin border as the Trash screen's
            // "Delete" action. Drops the legacy "Delete →" arrow which the Material guidelines
            // discourage for destructive actions.
            Box(
                modifier = Modifier
                    .background(colors.deleteTint, RoundedCornerShape(10.dp))
                    .border(0.5.dp, colors.errorColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .clickable(enabled = !isFreeingUp, onClick = onFreeUp)
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
}

@Composable
private fun ProtonStorageRow(state: SettingsUiState) {
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
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = colors.bg2, titleContentColor = colors.fgPrimary, textContentColor = colors.fgDim,
            title = { Text(stringResource(R.string.settings_storage_clear_cache_dialog_title)) },
            text  = {
                Text(stringResource(
                    R.string.settings_storage_clear_cache_dialog_message,
                    formatBytes(state.appCacheBytes),
                ))
            },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onClearCache() }) {
                    Text(stringResource(R.string.settings_storage_clear_cache), color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = colors.fgDim)
                }
            },
        )
    }
}

// ── Shared composables ────────────────────────────────────────────────────────

@Composable
private fun RecentlyDeletedCard(
    count: Int,
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
                    when {
                        count == 0 -> stringResource(R.string.settings_recently_deleted_empty)
                        count == 1 -> stringResource(R.string.settings_recently_deleted_subtitle_singular)
                        else       -> stringResource(R.string.settings_recently_deleted_subtitle, count)
                    },
                    color = if (count > 0) colors.errorColor.copy(alpha = 0.75f) else colors.fgMute,
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

@Composable
internal fun SectionLabel(text: String) {
    val colors = AppColors.current
    Text(text.uppercase(), color = colors.fgMute, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    val colors = AppColors.current
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.cardBg, cardShape).border(0.5.dp, colors.cardBorder, cardShape),
    ) { content() }
}

@Composable
internal fun NavRow(label: String, description: String? = null, onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            if (description != null) Text(description, color = colors.fgMute, fontSize = 11.5.sp)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = colors.fgMute, modifier = Modifier.size(13.dp))
    }
}

@Composable
internal fun RowDivider() {
    val colors = AppColors.current
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = colors.cardBorder)
}

@Composable
internal fun ToggleRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    indented: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = AppColors.current
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = if (indented) 28.dp else 16.dp,
            end = 16.dp, top = 11.dp, bottom = 11.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (indented) {
            Box(modifier = Modifier.width(6.dp).height(1.dp).background(colors.line2).padding(end = 8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary.copy(alpha = alpha), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            if (description != null) Text(description, color = colors.fgMute.copy(alpha = alpha), fontSize = 11.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor   = colors.accent,
                checkedThumbColor   = Color.White,
                uncheckedTrackColor = colors.line2,
                uncheckedThumbColor = colors.fgMute,
                disabledCheckedTrackColor   = colors.accent.copy(alpha = 0.5f),
                disabledUncheckedTrackColor = colors.line2.copy(alpha = 0.5f),
            ),
        )
    }
}

@Composable
internal fun SelectRow(
    label: String,
    description: String? = null,
    selected: FreeUpInterval,
    onSelected: (FreeUpInterval) -> Unit,
    indented: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = AppColors.current
    var expanded by remember { mutableStateOf(false) }
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = if (indented) 28.dp else 16.dp,
            end = 16.dp, top = 11.dp, bottom = 11.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary.copy(alpha = alpha), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            if (description != null) Text(description, color = colors.fgMute.copy(alpha = alpha), fontSize = 11.5.sp)
        }
        Box {
            Text(
                stringResource(selected.labelRes), color = if (enabled) colors.fgDim else colors.fgMute, fontSize = 12.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceWeak)
                    .border(0.5.dp, colors.line2, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                FreeUpInterval.entries.forEach { interval ->
                    DropdownMenuItem(
                        text = { Text(stringResource(interval.labelRes), color = colors.fgPrimary) },
                        onClick = { onSelected(interval); expanded = false },
                    )
                }
            }
        }
    }
}

/**
 * Periodic-sync interval picker. Mirrors [SelectRow]'s look but for the Long-minute values
 * actually consumed by WorkManager. WorkManager's hard floor is 15 minutes — the first
 * option matches that. Longer cadences trade fresher backup for less battery/data.
 */
@Composable
internal fun SyncIntervalRow(
    label: String,
    description: String? = null,
    selectedMinutes: Long,
    onSelected: (Long) -> Unit,
    indented: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = AppColors.current
    var expanded by remember { mutableStateOf(false) }
    val alpha = if (enabled) 1f else 0.4f
    val options: List<Pair<Long, Int>> = listOf(
        15L to R.string.settings_sync_interval_15min,
        30L to R.string.settings_sync_interval_30min,
        60L to R.string.settings_sync_interval_1h,
        180L to R.string.settings_sync_interval_3h,
        360L to R.string.settings_sync_interval_6h,
        720L to R.string.settings_sync_interval_12h,
        1440L to R.string.settings_sync_interval_24h,
    )
    val selectedLabel = options.firstOrNull { it.first == selectedMinutes }?.second
        ?: R.string.settings_sync_interval_15min
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = if (indented) 28.dp else 16.dp,
            end = 16.dp, top = 11.dp, bottom = 11.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.fgPrimary.copy(alpha = alpha), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            if (description != null) Text(description, color = colors.fgMute.copy(alpha = alpha), fontSize = 11.5.sp)
        }
        Box {
            Text(
                stringResource(selectedLabel), color = if (enabled) colors.fgDim else colors.fgMute, fontSize = 12.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceWeak)
                    .border(0.5.dp, colors.line2, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (minutes, labelRes) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes), color = colors.fgPrimary) },
                        onClick = { onSelected(minutes); expanded = false },
                    )
                }
            }
        }
    }
}

