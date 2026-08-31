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
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.R
import eu.akoos.photos.data.face.FaceIndexingState
import eu.akoos.photos.data.face.FaceModelAssets
import eu.akoos.photos.data.ocr.OcrModelAssets
import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.presentation.gallery.PersonTile
import eu.akoos.photos.presentation.search.SearchFilter
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.common.ErrorPopup
import eu.akoos.photos.presentation.common.FloatingHeaderScrim
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.common.ShimmerBox
import eu.akoos.photos.presentation.common.ShimmerTextLine
import eu.akoos.photos.util.HealthBlockReason
import eu.akoos.photos.util.sanitizeErrorMessage
import eu.akoos.photos.presentation.settings.components.ActionRow
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
import eu.akoos.photos.presentation.theme.pillShape
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
    onAiClick: () -> Unit = {},
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
    onSignIn: () -> Unit = {},
    onCheckForUpdatesClick: () -> Unit = {},
    onWhatsNewClick: () -> Unit = {},
    onNewsClick: () -> Unit = {},
    /** Opens a settings-search result by its NavGraph route (top-level pages and the query-string
     *  destinations the index points at); the inline search resets when this fires. */
    onOpenRoute: (String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val newsUnread by viewModel.newsUnread.collectAsStateWithLifecycle()
    val colors = AppColors.current
    // Inline settings search, revealed by morphing the header search icon into a bar. Typing swaps the
    // six groups for matching results in place. Query saved across config change; reset whenever a
    // result opens so returning from a page shows the groups again.
    var query by rememberSaveable { mutableStateOf("") }
    // Hidden by default so opening Settings shows the groups and never raises the keyboard.
    var searchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Focus the field only as it opens, never on a plain Settings open.
    LaunchedEffect(searchActive) { if (searchActive) focusRequester.requestFocus() }
    // Hoisted so scrolling the groups can collapse an empty search back to the icon.
    val groupsScrollState = rememberScrollState()
    LaunchedEffect(groupsScrollState.isScrollInProgress) {
        if (groupsScrollState.isScrollInProgress && searchActive && query.isBlank()) {
            searchActive = false
            focusManager.clearFocus()
        }
    }
    val searchIndex = remember { settingsSearchIndex() }
    // Back closes the revealed field and clears the query before leaving Settings.
    BackHandler(enabled = searchActive) { searchActive = false; query = "" }
    val openSearchResult: (String) -> Unit = { route ->
        onOpenRoute(route)
        searchActive = false
        query = ""
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // The suspend Clipboard API is unnecessary for a synchronous copy in a click handler.
    @Suppress("DEPRECATION")
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
                run {
                    if (isNotEmpty()) append("\n\n")
                    append("Faces:\n").append(eu.akoos.photos.util.FaceDiagnostics.snapshot())
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
                    @Suppress("DEPRECATION")
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
                .padding(horizontal = 20.dp),
        ) {
            // Groups vs results swap. A blank query shows the six setting groups; a non-blank one
            // crossfades to the matching results. Both scrollers run full height so the content passes
            // UNDER the floating header; the top inset lives inside each scroller, not as a fixed gap
            // above the swap, so nothing shows a solid band under the pills.
            Crossfade(
                targetState = query.isBlank(),
                modifier = Modifier.weight(1f),
                label = "settingsContent",
            ) { showGroups ->
                if (showGroups) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(groupsScrollState)
                            .padding(bottom = 32.dp),
                    ) {
                    // Reserve the floating-header height inside the scroll, so the groups slide up
                    // behind the pills instead of leaving a fixed band above them.
                    Spacer(Modifier.height(contentTopPad))
                    Spacer(Modifier.height(12.dp))

            // ── Account ───────────────────────────────────────────────────────
            // The whole row is now a tap target, opens AccountScreen with avatar,
            // storage, web links, and sign out. Sign out moved out of the row to
            // avoid the cramped triple hit area (avatar / text / sign out) of the old
            // layout, and to keep the destructive action behind one more deliberate
            // step.
            CollapsibleSection(label = stringResource(R.string.settings_account_section)) {
            SettingsCard {
                if (state.isSignedIn) {
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
                } else {
                    // No account: a single sign-in row replaces the account card, same row style.
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(onClick = onSignIn)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_sign_in_to_proton),
                            color = colors.fgPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = colors.fgMute,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
            }

            // New-news banner: shown right under the account section while there is unread news (the
            // same signal as the settings-icon dot, off when news is switched off). Tapping opens the
            // News screen, which marks the feed read and clears both this banner and the dot.
            if (newsUnread) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.accent.copy(alpha = 0.12f))
                        .border(1.dp, colors.accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .clickable(onClick = onNewsClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.news_banner),
                        color = colors.fgPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Backup & Storage ──────────────────────────────────────────────
            // Backup status + activity and the storage controls share one group:
            // everything about where the library lives, on the device and on Drive.
            CollapsibleSection(label = stringResource(R.string.settings_backup_storage_section)) {
            SettingsCard {
                // Cloud-backed rows (backup status, activity, sync settings) need an account,
                // so the local-only session hides them and keeps the on-device controls below.
                if (state.isSignedIn) {
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
                RowDivider()
                }
                // Storage controls (device + Drive usage, recently deleted, duplicate
                // finder) stay available without an account, so the local-only session keeps them.
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

            // ── AI / Machine learning ─────────────────────────────────────────
            // Master opt-in for every on-device ML feature (Copy text, Hide faces,
            // and the People grouping to come). A primary section, not buried in
            // Extras, because it gates whether any model is ever fetched. The
            // sub-page hosts the toggle now and the indexing status a later piece adds.
            CollapsibleSection(label = stringResource(R.string.settings_ai_section)) {
            SettingsCard {
                NavRow(
                    label = stringResource(R.string.settings_ai_section),
                    description = stringResource(R.string.settings_ai_nav_desc),
                    onClick = onAiClick,
                )
            }
            }

            Spacer(Modifier.height(20.dp))

            // ── Privacy & Security ────────────────────────────────────────────
            CollapsibleSection(label = stringResource(R.string.settings_privacy_security)) {
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
            }
            }

            Spacer(Modifier.height(20.dp))

            // ── Appearance ────────────────────────────────────────────────────
            // Appearance + Language merged into one entry, the destination is the
            // unified appearance screen which now hosts theme + palette + language
            // in a single scroll.
            CollapsibleSection(label = stringResource(R.string.settings_appearance)) {
            SettingsCard {
                NavRow(
                    label = stringResource(R.string.settings_appearance),
                    description = stringResource(R.string.settings_appearance_desc),
                    onClick = onAppearanceClick,
                )
            }
            }

            Spacer(Modifier.height(20.dp))

            // ── Help & About ──────────────────────────────────────────────────
            // App identity, what's new, news, help, and the update check the version
            // answers for, plus the diagnostics export and the screenshot overlay.
            CollapsibleSection(label = stringResource(R.string.settings_help_about_section)) {
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
                RowDivider()
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
                Spacer(Modifier.height(20.dp))
                DebugDialogTestCard()
                Spacer(Modifier.height(20.dp))
                DeviceHealthDebugCard()
            }
                    }
                } else {
                    // Resolve each entry's localized title + breadcrumb (a composable read), then keep
                    // those whose folded title, breadcrumb or any keyword contains the folded query —
                    // the same accent-insensitive rule the photo search uses.
                    val folded = SearchFilter.fold(query.trim())
                    val matches = searchIndex
                        .map { entry ->
                            Triple(entry, stringResource(entry.titleRes), stringResource(entry.breadcrumbRes))
                        }
                        .filter { (entry, title, breadcrumb) ->
                            (state.isSignedIn || !entry.cloud) && (
                                SearchFilter.fold(title).contains(folded) ||
                                    SearchFilter.fold(breadcrumb).contains(folded) ||
                                    entry.keywords.any { SearchFilter.fold(it).contains(folded) }
                            )
                        }
                    if (matches.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.settings_search_no_results),
                                color = colors.fgMute,
                                fontSize = 14.sp,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = contentTopPad + 12.dp, bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(matches, key = { "${it.first.titleRes}_${it.first.route}" }) { (entry, title, breadcrumb) ->
                                SettingsCard {
                                    NavRow(
                                        label = title,
                                        description = breadcrumb,
                                        onClick = { openSearchResult(entry.route) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())

        // Floating header, close button on the left (the same side every sub-page puts back),
        // title centered in a pill; the list scrolls under it.
        val debouncedClose = rememberDebouncedAction { onBack() }
        FloatingHeaderScrim()
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        ) {
            // Tapping the search icon grows it sideways into a bar filling the row beside the close
            // button, while the centered title fades out, so the header morphs rather than opening a
            // field below it. Collapsed it is just the 40dp icon and the title is centered again.
            val expandedSearchWidth = maxWidth - 48.dp
            val searchWidth by animateDpAsState(
                targetValue = if (searchActive) expandedSearchWidth else 40.dp,
                animationSpec = tween(220),
                label = "headerSearchWidth",
            )
            val titleAlpha by animateFloatAsState(
                targetValue = if (searchActive) 0f else 1f,
                animationSpec = tween(220),
                label = "headerTitleAlpha",
            )
            val pillShape = RoundedCornerShape(20.dp)

            // Centered title, still measured while faded so nothing reflows; the search bar draws over it.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(titleAlpha)
                    .clip(pillShape)
                    .background(colors.pillBg, pillShape)
                    .border(0.5.dp, colors.pillBorder, pillShape)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(stringResource(R.string.settings_title), color = colors.fgPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            IconBubble(
                modifier = Modifier.align(Alignment.CenterStart),
                icon = Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
                onClick = debouncedClose,
                diameter = 40.dp,
                iconSize = 16.dp,
                background = colors.pillBg,
                borderColor = colors.pillBorder,
                tint = colors.fgPrimary,
            )

            // Morphing search element: a 40dp icon bubble collapsed, the input bar filling the row open.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(searchWidth)
                    .height(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.pillBg, RoundedCornerShape(14.dp))
                    .border(0.5.dp, if (query.isNotEmpty()) colors.accent else colors.pillBorder, RoundedCornerShape(14.dp))
                    .clickable(enabled = !searchActive) { searchActive = true },
            ) {
                if (searchActive) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = colors.fgDim, modifier = Modifier.size(18.dp))
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.settings_search_hint),
                                    color = colors.fgMute,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(color = colors.fgPrimary, fontSize = 14.sp),
                                cursorBrush = SolidColor(colors.accent),
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            )
                        }
                        // Clears a non-empty query, then closes the field on the next tap.
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { if (query.isNotEmpty()) query = "" else searchActive = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.cd_clear_search),
                                tint = colors.fgDim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.settings_search_hint),
                        tint = colors.fgDim,
                        modifier = Modifier.align(Alignment.Center).size(16.dp),
                    )
                }
            }
        }
    }

}

// ── Machine learning sub-page (hub) ───────────────────────────────────────────
// Master opt-in for the on-device ML features. When off, nothing downloads and the per-feature
// controls stay hidden. With it on, two sub-toggles pick which features run: Copy text and Face
// recognition. Face recognition stays disabled until its model is available, and adds a management
// row whose subtitle mirrors the live scan state so progress shows without drilling in.

@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    onFaceRecognitionClick: () -> Unit,
    onExcludedFacesClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    SettingsSubPageScaffold(title = stringResource(R.string.settings_ai_section), onBack = onBack) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_ai_enable),
                description = stringResource(R.string.settings_ai_enable_desc),
                checked = state.aiFeaturesEnabled,
                onCheckedChange = viewModel::setAiFeaturesEnabled,
            )
        }

        // Dark when ML is off: the per-feature toggles stay hidden until the master toggle is on.
        if (state.aiFeaturesEnabled) {
            Spacer(Modifier.height(20.dp))
            SettingsCard {
                ToggleRow(
                    label = stringResource(R.string.settings_ai_ocr),
                    // While the model is fetching the row reads as busy, and a failed fetch is stated in
                    // place so the switch staying off is explained rather than looking stuck.
                    description = when {
                        state.ocrModelDownloading -> stringResource(R.string.settings_ai_ocr_downloading)
                        state.ocrModelDownloadFailed -> stringResource(R.string.viewer_text_model_failed)
                        else -> stringResource(R.string.settings_ai_ocr_desc)
                    },
                    checked = state.ocrEnabled,
                    onCheckedChange = viewModel::setOcrEnabled,
                    enabled = !state.ocrModelDownloading,
                )
                RowDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_ai_face_toggle),
                    // While the model fetches the row reads as busy, a failed fetch is stated in place so
                    // the switch staying off is explained, and a switched-on feature whose model is no
                    // longer on the device says so rather than looking as if it were still working.
                    description = when {
                        state.faceModelDownloading ->
                            stringResource(R.string.settings_ai_face_downloading)
                        state.faceModelDownloadFailed ->
                            stringResource(R.string.settings_ai_face_download_failed)
                        state.faceEnabled && !state.faceRecognitionAvailable ->
                            stringResource(R.string.settings_ai_face_model_missing)
                        else -> stringResource(R.string.settings_ai_face_desc)
                    },
                    checked = state.faceEnabled,
                    onCheckedChange = viewModel::setFaceEnabled,
                    enabled = !state.faceModelDownloading,
                )
                // A spinner under the toggle makes the model fetch visibly in progress; a failed fetch
                // adds a line saying the switch itself is the retry, so a stuck-looking row is explained.
                if (state.faceModelDownloading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.accent,
                        )
                    }
                } else if (state.faceModelDownloadFailed) {
                    Text(
                        text = stringResource(R.string.settings_ai_face_download_retry),
                        color = colors.fgMute,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    )
                }
            }

            // The face management entry only makes sense once the scan is switched on, so its live
            // subtitle reflects real progress; it stays hidden while face recognition is off.
            if (state.faceEnabled) {
                Spacer(Modifier.height(20.dp))
                val progress by viewModel.faceIndexingProgress.collectAsStateWithLifecycle()
                val peopleCount by viewModel.peopleCount.collectAsStateWithLifecycle()
                SettingsCard {
                    NavRow(
                        label = stringResource(R.string.settings_face_section),
                        description = faceRowSubtitle(progress.state, peopleCount),
                        onClick = onFaceRecognitionClick,
                    )
                    RowDivider()
                    NavRow(
                        label = stringResource(R.string.settings_face_excluded),
                        description = stringResource(R.string.settings_face_excluded_desc),
                        onClick = onExcludedFacesClick,
                    )
                }
            }

            // Both Copy text model drawers, matching the app's other confirm sheets: the download
            // consent raised when the feature is switched on without the model, and the destructive
            // removal raised when it is switched off. The size quoted is the figure that goes over the
            // wire.
            when (state.ocrModelPrompt) {
                OcrModelPrompt.Download -> ConfirmSheet(
                    title = stringResource(R.string.settings_ai_ocr_download_title),
                    message = stringResource(
                        R.string.settings_ai_ocr_download_message,
                        formatBytes(OcrModelAssets.TOTAL_DOWNLOAD_BYTES),
                    ),
                    confirmLabel = stringResource(R.string.settings_ai_ocr_download_confirm),
                    dismissLabel = stringResource(R.string.cancel),
                    onConfirm = viewModel::confirmOcrModelDownload,
                    onDismiss = viewModel::dismissOcrModelPrompt,
                )
                OcrModelPrompt.Remove -> ConfirmSheet(
                    title = stringResource(R.string.settings_ai_ocr_remove_title),
                    message = stringResource(
                        R.string.settings_ai_ocr_remove_message,
                        formatBytes(OcrModelAssets.TOTAL_DOWNLOAD_BYTES),
                    ),
                    confirmLabel = stringResource(R.string.settings_ai_ocr_remove_confirm),
                    dismissLabel = stringResource(R.string.settings_ai_ocr_remove_keep),
                    onConfirm = viewModel::confirmOcrModelRemoval,
                    onDismiss = viewModel::disableOcrKeepingModel,
                    onOutsideDismiss = viewModel::dismissOcrModelPrompt,
                    destructive = true,
                )
                OcrModelPrompt.None -> Unit
            }

            // The face model drawers. Download is the consent raised when the feature is switched on with
            // no model on disk; the size quoted is the figure that goes over the wire. Remove and
            // ConfirmRemove form the two-stage disable, so an accidental tap cannot delete: Remove asks
            // for a final confirmation, ConfirmRemove then wipes the model files and every detected face
            // and name. Keep, Cancel, or a swipe leaves everything in place.
            when (state.faceModelPrompt) {
                FaceModelPrompt.Download -> ConfirmSheet(
                    title = stringResource(R.string.settings_ai_face_download_title),
                    message = stringResource(
                        R.string.settings_ai_face_download_message,
                        formatBytes(FaceModelAssets.TOTAL_DOWNLOAD_BYTES),
                    ),
                    confirmLabel = stringResource(R.string.settings_ai_face_download_confirm),
                    dismissLabel = stringResource(R.string.cancel),
                    onConfirm = viewModel::confirmFaceModelDownload,
                    onDismiss = viewModel::dismissFaceModelPrompt,
                )
                FaceModelPrompt.Remove -> ConfirmSheet(
                    title = stringResource(R.string.settings_ai_face_remove_title),
                    message = stringResource(R.string.settings_ai_face_remove_message),
                    confirmLabel = stringResource(R.string.settings_ai_face_remove_confirm),
                    dismissLabel = stringResource(R.string.settings_ai_face_remove_keep),
                    onConfirm = viewModel::requestFaceModelRemoval,
                    onDismiss = viewModel::disableFaceKeepingData,
                    onOutsideDismiss = viewModel::dismissFaceModelPrompt,
                    destructive = true,
                )
                FaceModelPrompt.ConfirmRemove -> ConfirmSheet(
                    title = stringResource(R.string.settings_ai_face_remove_confirm_title),
                    message = stringResource(R.string.settings_ai_face_remove_confirm_message),
                    confirmLabel = stringResource(R.string.settings_ai_face_remove_confirm),
                    dismissLabel = stringResource(R.string.cancel),
                    onConfirm = viewModel::confirmFaceModelRemoval,
                    onDismiss = viewModel::backToFaceRemovePrompt,
                    onOutsideDismiss = viewModel::dismissFaceModelPrompt,
                    destructive = true,
                )
                FaceModelPrompt.None -> Unit
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Subtitle for the Face recognition row: the live scan state while a walk runs or is paused,
 *  otherwise the count of people found, so the row reads as a result when idle. */
@Composable
private fun faceRowSubtitle(state: FaceIndexingState, peopleCount: Int): String = when (state) {
    FaceIndexingState.Running, FaceIndexingState.WaitingModel ->
        stringResource(R.string.settings_ai_indexing_running)
    FaceIndexingState.Paused -> stringResource(R.string.settings_ai_indexing_paused)
    else -> pluralStringResource(R.plurals.settings_ai_indexing_people, peopleCount, peopleCount)
}

// ── Face recognition sub-page ─────────────────────────────────────────────────
// The on-device face scan and the controls to manage it: a status card (people found, scan state,
// progress, a preview of the people, and Start / Pause / Resume), a Maintenance card (rescan / clear),
// and a Transfer card (export / import the portable index).

@Composable
fun FaceRecognitionScreen(
    onBack: () -> Unit,
    onSeeAllPeople: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val transferMsg by viewModel.faceTransferMsg.collectAsStateWithLifecycle()
    val transferInProgress by viewModel.faceTransferInProgress.collectAsStateWithLifecycle()
    val transferCtx = LocalContext.current
    LaunchedEffect(transferMsg) {
        transferMsg?.let {
            Toast.makeText(transferCtx, it, Toast.LENGTH_LONG).show()
            viewModel.clearFaceTransferMsg()
        }
    }
    val faceUi by viewModel.faceIndexingUi.collectAsStateWithLifecycle()
    val peopleCount by viewModel.peopleCount.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()

    SettingsSubPageScaffold(title = stringResource(R.string.settings_face_section), onBack = onBack) {
        FaceStatusCard(
            ui = faceUi,
            peopleCount = peopleCount,
            people = people,
            onSetPaused = viewModel::setFaceIndexingPaused,
            onSeeAllPeople = onSeeAllPeople,
        )
        Spacer(Modifier.height(20.dp))
        FaceMaintenanceCard(
            onRescan = viewModel::rescanFaces,
            onClear = viewModel::clearFaceIndex,
        )
        Spacer(Modifier.height(20.dp))
        FaceTransferCard(
            inProgress = transferInProgress,
            onExport = viewModel::exportFaceIndex,
            onImport = viewModel::importFaceIndex,
        )
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * Status of the face scan: the people found, a one-line state label, a progress bar only while a walk
 * is running or paused, a preview row of the people, and one primary control that pauses a live walk
 * and starts a scan otherwise, so indexing can be kicked off from Idle or right after a launch.
 */
@Composable
private fun FaceStatusCard(
    ui: FaceIndexingUi,
    peopleCount: Int,
    people: List<eu.akoos.photos.presentation.gallery.PersonUi>,
    onSetPaused: (Boolean) -> Unit,
    onSeeAllPeople: () -> Unit,
) {
    val colors = AppColors.current
    val state = ui.state
    // While a walk is active or pending, a persistent device-health block is what has actually parked
    // it: the scheduler keeps reporting Running when it stands down for a warm phone, a low battery or
    // the power saver, so name that reason in place of the plain running / paused label.
    val healthLabel = if (state == FaceIndexingState.Running || state == FaceIndexingState.Paused) {
        when (ui.blockReason) {
            HealthBlockReason.LOW_BATTERY -> stringResource(R.string.settings_ai_indexing_paused_battery)
            HealthBlockReason.WARM -> stringResource(R.string.settings_ai_indexing_paused_warm)
            HealthBlockReason.POWER_SAVE -> stringResource(R.string.settings_ai_indexing_paused_power_save)
            else -> null
        }
    } else {
        null
    }
    // Indexing is automatic, so a status line only appears while something is actually happening; an
    // idle or finished scan shows just the people summary, with no "not started" wording.
    val stateLabel = healthLabel ?: when (state) {
        FaceIndexingState.Running -> stringResource(R.string.settings_ai_indexing_running)
        FaceIndexingState.WaitingModel -> stringResource(R.string.settings_ai_indexing_waiting)
        FaceIndexingState.Paused -> stringResource(R.string.settings_ai_indexing_paused)
        else -> null
    }
    val showBar = (state == FaceIndexingState.Running || state == FaceIndexingState.Paused) &&
        ui.total > 0

    SettingsCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                pluralStringResource(R.plurals.settings_ai_indexing_people, peopleCount, peopleCount),
                color = colors.fgPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (stateLabel != null) {
                Spacer(Modifier.height(4.dp))
                Text(stateLabel, color = colors.fgMute, fontSize = 12.5.sp)
            }

            if (showBar) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { ui.indexed.toFloat() / ui.total },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.settings_ai_indexing_progress, ui.indexed, ui.total),
                    color = colors.fgMute,
                    fontSize = 12.sp,
                )
            }

            // The found people as round face tiles; tapping any opens the full People page to manage them.
            if (people.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(people, key = { it.personId }) { person ->
                        PersonTile(person = person, selected = false, onClick = onSeeAllPeople)
                    }
                }
            }

            // No manual start: indexing runs automatically and new photos are picked up on their own,
            // so the only control is to pause a live scan or resume a paused one. Idle, finished and
            // model-waiting states show no button at all.
            when (state) {
                FaceIndexingState.Running -> {
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        label = stringResource(R.string.settings_ai_pause),
                        icon = Icons.Default.Pause,
                        onClick = { onSetPaused(true) },
                        enabled = ui.actionEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FaceIndexingState.Paused -> {
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        label = stringResource(R.string.settings_ai_resume),
                        icon = Icons.Default.PlayArrow,
                        onClick = { onSetPaused(false) },
                        enabled = ui.actionEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {}
            }
        }
    }
}

/**
 * Maintenance actions, one destructive: rescan re-detects every photo (keeping the user's names and
 * corrections), clear wipes all faces and people. Both confirm first.
 */
@Composable
private fun FaceMaintenanceCard(
    onRescan: () -> Unit,
    onClear: () -> Unit,
) {
    var showRescanConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    SectionLabel(stringResource(R.string.settings_face_maintenance))
    Spacer(Modifier.height(8.dp))
    SettingsCard {
        ActionRow(
            label = stringResource(R.string.settings_ai_rescan),
            description = stringResource(R.string.settings_face_rescan_desc),
            icon = Icons.Default.Refresh,
            onClick = { showRescanConfirm = true },
        )
        RowDivider()
        ActionRow(
            label = stringResource(R.string.settings_ai_clear),
            description = stringResource(R.string.settings_face_clear_desc),
            icon = Icons.Default.DeleteOutline,
            destructive = true,
            onClick = { showClearConfirm = true },
        )
    }

    if (showRescanConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_ai_rescan_confirm_title),
            message = stringResource(R.string.settings_ai_rescan_confirm_msg),
            confirmLabel = stringResource(R.string.settings_ai_rescan),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showRescanConfirm = false; onRescan() },
            onDismiss = { showRescanConfirm = false },
        )
    }
    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_ai_clear_confirm_title),
            message = stringResource(R.string.settings_ai_clear_confirm_msg),
            confirmLabel = stringResource(R.string.settings_ai_clear),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showClearConfirm = false; onClear() },
            onDismiss = { showClearConfirm = false },
            destructive = true,
        )
    }
}

/**
 * Transfer the portable face index. Export warns that the file holds biometric fingerprints; import
 * explains it folds a saved file into this device and regroups, keeping the current faces.
 */
@Composable
private fun FaceTransferCard(
    inProgress: Boolean,
    onExport: (android.net.Uri) -> Unit,
    onImport: (android.net.Uri) -> Unit,
) {
    var showExportWarn by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(onExport) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }

    SectionLabel(stringResource(R.string.settings_face_transfer))
    Spacer(Modifier.height(8.dp))
    SettingsCard {
        ActionRow(
            label = stringResource(R.string.settings_ai_export),
            description = if (inProgress) stringResource(R.string.settings_ai_transfer_working)
                else stringResource(R.string.settings_face_export_desc),
            icon = Icons.Default.Upload,
            onClick = { showExportWarn = true },
            enabled = !inProgress,
        )
        RowDivider()
        ActionRow(
            label = stringResource(R.string.settings_ai_import),
            description = if (inProgress) stringResource(R.string.settings_ai_transfer_working)
                else stringResource(R.string.settings_face_import_desc),
            icon = Icons.Default.Download,
            onClick = { showImportConfirm = true },
            enabled = !inProgress,
        )
    }

    if (showExportWarn) {
        ConfirmDialog(
            title = stringResource(R.string.settings_ai_export_warn_title),
            message = stringResource(R.string.settings_ai_export_warn_msg),
            confirmLabel = stringResource(R.string.settings_ai_export),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showExportWarn = false; exportLauncher.launch("photosforproton-faces.ppfi") },
            onDismiss = { showExportWarn = false },
        )
    }
    if (showImportConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_ai_import_confirm_title),
            message = stringResource(R.string.settings_ai_import_confirm_msg),
            confirmLabel = stringResource(R.string.settings_ai_import),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showImportConfirm = false; importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            onDismiss = { showImportConfirm = false },
        )
    }
}

// ── Sync Settings sub-page ────────────────────────────────────────────────────

@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    onBackupFoldersClick: () -> Unit = {},
    onExcludedFoldersClick: () -> Unit = {},
    onProcessingClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    SettingsSubPageScaffold(title = stringResource(R.string.sync_section), onBack = onBack) {
        // ── What gets backed up ──────────────────────────────────────────────
        SectionLabel(stringResource(R.string.settings_what_backed_up_section))
        Spacer(Modifier.height(8.dp))
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

        Spacer(Modifier.height(20.dp))

        // ── How backup runs ──────────────────────────────────────────────────
        SectionLabel(stringResource(R.string.settings_backup_how_section))
        Spacer(Modifier.height(8.dp))
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

        // ── Upload processing ────────────────────────────────────────────────
        // File name, metadata and quality controls live one level down, so the
        // common backup switches aren't buried under them.
        SettingsCard {
            NavRow(
                label = stringResource(R.string.settings_metadata),
                description = stringResource(R.string.settings_metadata_desc),
                onClick = onProcessingClick,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Network usage ────────────────────────────────────────────────────
        SectionLabel(stringResource(R.string.settings_network_section))
        Spacer(Modifier.height(8.dp))
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

// ── Backup processing sub-page ────────────────────────────────────────────────

/**
 * The advanced upload-processing controls, one level under the backup page: how the uploaded copy is
 * named, what metadata it keeps, and how far it is re-encoded. The common backup switches stay on the
 * parent page; these three sections moved here so they no longer bury them.
 */
@Composable
fun BackupProcessingScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = AppColors.current
    SettingsSubPageScaffold(title = stringResource(R.string.settings_metadata), onBack = onBack) {
        // ── File name ────────────────────────────────────────────────────────
        // How the uploaded copy is named. The on-device file keeps its own name.
        SectionLabel(stringResource(R.string.settings_section_file_name))
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_rename_on_upload),
                description = stringResource(R.string.settings_rename_on_upload_desc),
                checked = state.renameToCaptureDate,
                onCheckedChange = viewModel::setRenameToCaptureDate,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Metadata ─────────────────────────────────────────────────────────
        // EXIF stripping on the uploaded copy, with an optional mirror to the original.
        SectionLabel(stringResource(R.string.settings_privacy_section_metadata))
        Spacer(Modifier.height(8.dp))
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

        Spacer(Modifier.height(20.dp))

        // ── Quality and size ─────────────────────────────────────────────────
        // Re-encode the uploaded copy smaller, with an optional mirror to the original.
        SectionLabel(stringResource(R.string.settings_section_quality_size))
        Spacer(Modifier.height(8.dp))
        // The on-device mirror is a standalone toggle in its own card: shrinking the copy kept on this
        // device applies to whichever of photos/videos is being compressed, and unlike the type toggles
        // below it reveals no tier list. The gap to the next card is the separator.
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

        // The recurring counterpart to the manual "Free up" action on the device gauge above acts
        // on backed-up copies, so it belongs to a signed-in session.
        if (state.isSignedIn) {
            Spacer(Modifier.height(20.dp))

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
}

// ── Privacy & Security (single scrolling page) ────────────────────────────────

/** Privacy and security on one page: a Privacy section (device-side data plus the
 *  offline-photos drilldown) followed by a Security section (app lock plus the
 *  hidden-vault drilldown). */
@Composable
fun PrivacySecuritySettingsScreen(
    onBack: () -> Unit,
    onOfflinePhotosClick: () -> Unit = {},
    onHiddenAlbumClick: () -> Unit = {},
    onShareMetadataClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsSubPageScaffold(title = stringResource(R.string.settings_privacy_security), onBack = onBack) {
        // ── Privacy ──────────────────────────────────────────────────────────
        // Device-side privacy: what this device keeps locally after close (the cache
        // and the offline copies) plus the read-only telemetry mirror.
        SectionLabel(stringResource(R.string.settings_privacy))
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_clear_cache_on_close),
                description = stringResource(R.string.settings_clear_cache_on_close_desc),
                checked = state.clearCacheOnAppClose,
                onCheckedChange = viewModel::setClearCacheOnAppClose,
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.settings_strip_share),
                onClick = onShareMetadataClick,
            )
            RowDivider()
            if (state.isSignedIn) {
                NavRow(
                    label = stringResource(R.string.settings_offline_photos),
                    description = stringResource(R.string.offline_screen_empty),
                    onClick = onOfflinePhotosClick,
                )
                RowDivider()
            }
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
        // Footnote: the map draws its background from a public tile endpoint, which is the
        // one place the app reaches a server outside Proton.
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.settings_privacy_map_tiles_note),
            color = AppColors.current.fgMute,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(20.dp))

        // ── Security ─────────────────────────────────────────────────────────
        SectionLabel(stringResource(R.string.settings_security))
        Spacer(Modifier.height(8.dp))
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

// ── Remove metadata when sharing (sub-page) ───────────────────────────────────

/** Per-field control over what a shared copy carries. A master toggle plus, once it is on, the five
 *  field choices. The labels are reused from the upload stripper, but the config written here is a
 *  separate one that only the share paths consult; the on-device original is never modified. */
@Composable
fun ShareMetadataScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsSubPageScaffold(title = stringResource(R.string.settings_strip_share_title), onBack = onBack) {
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.settings_strip_share),
                description = stringResource(R.string.settings_strip_share_desc),
                checked = state.stripOnShare,
                onCheckedChange = viewModel::setStripOnShare,
            )
            if (state.stripOnShare) {
                RowDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_strip_gps),
                    description = stringResource(R.string.settings_strip_gps_desc),
                    checked = state.stripShareGps,
                    onCheckedChange = viewModel::setStripShareGps,
                    indented = true,
                )
                RowDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_strip_camera),
                    description = stringResource(R.string.settings_strip_camera_desc),
                    checked = state.stripShareCameraInfo,
                    onCheckedChange = viewModel::setStripShareCameraInfo,
                    indented = true,
                )
                RowDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_strip_timestamp),
                    description = stringResource(R.string.settings_strip_timestamp_desc),
                    checked = state.stripShareTimestamp,
                    onCheckedChange = viewModel::setStripShareTimestamp,
                    indented = true,
                )
                RowDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_strip_software),
                    description = stringResource(R.string.settings_strip_software_desc),
                    checked = state.stripShareSoftwareInfo,
                    onCheckedChange = viewModel::setStripShareSoftwareInfo,
                    indented = true,
                )
                RowDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_strip_authorship),
                    checked = state.stripShareAuthorship,
                    onCheckedChange = viewModel::setStripShareAuthorship,
                    indented = true,
                )
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

    // Cloud storage group is hidden for a local-only (signed-out) session.
    if (state.isSignedIn) {
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
    }

    SectionLabel(stringResource(R.string.settings_storage_device))
    Spacer(Modifier.height(8.dp))
    // Offline copies and the device free-up act on synced copies, so both stay signed-in only;
    // app cache, device storage and device trash remain for every session. The page list is built
    // conditionally so its indices never point at a dropped page.
    val devicePages = buildList<@Composable () -> Unit> {
        add {
            StorageGaugeCard(
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
        }
        if (state.isSignedIn) {
            add {
                StorageGaugeCard(
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
            }
        }
        add {
            StorageGaugeCard(
                icon = Icons.Default.PhoneAndroid,
                label = stringResource(R.string.settings_storage_device),
                value = formatBytes(state.deviceFreeBytes),
                detail = stringResource(R.string.settings_storage_device_free, formatBytes(state.deviceFreeBytes), formatBytes(deviceTotal)),
                action = if (state.isSignedIn) {
                    { StorageFreeUpAction(onFreeUp = onFreeUp) }
                } else null,
            )
        }
        add {
            StorageTrashCard(
                label = stringResource(R.string.settings_recently_deleted),
                count = state.trashedCount,
                onOpen = { onOpenTrash(false) },
            )
        }
    }
    StorageCarousel(pageCount = devicePages.size) { page ->
        devicePages[page]()
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

// ── Debug-only preview drawers + large-library simulator ──────────────────────

/** Debug-only preview buttons for the app's warning / confirm / error drawers, so each can be seen
 *  and tuned without reproducing the real condition. Compiled out of release by the BuildConfig.DEBUG
 *  guard at the only call site. English-only labels: a developer tool, never user-facing. */
@Composable
private fun DebugDialogTestCard() {
    var showErrShort by remember { mutableStateOf(false) }
    var showErrLong by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var showDestructive by remember { mutableStateOf(false) }
    var showDenseGrid by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SettingsCard {
        NavRow("Test: error popup (short)", "Preview the error drawer") { showErrShort = true }
        RowDivider()
        NavRow("Test: error popup (long)", "Long message, Show more, Copy") { showErrLong = true }
        RowDivider()
        NavRow("Test: confirm dialog", "Neutral confirm drawer") { showConfirm = true }
        RowDivider()
        NavRow("Test: confirm dialog (destructive)", "Red confirm drawer") { showDestructive = true }
        RowDivider()
        NavRow("Test: dense grid warning", "Warning drawer with checkbox") { showDenseGrid = true }
        RowDivider()
        NavRow("Test: update prompt", "Update-available drawer") { showUpdate = true }
        RowDivider()
        NavRow("Test: toast", "Fire a sample toast") {
            android.widget.Toast.makeText(context, "Test toast", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    if (showErrShort) {
        eu.akoos.photos.presentation.common.ErrorPopup(
            title = "Test error",
            message = "Something went wrong while doing the thing.",
            onDismiss = { showErrShort = false },
            onCopy = {},
        )
    }
    if (showErrLong) {
        eu.akoos.photos.presentation.common.ErrorPopup(
            title = "Test error",
            message = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(12),
            onDismiss = { showErrLong = false },
            onCopy = {},
        )
    }
    if (showConfirm) {
        eu.akoos.photos.presentation.common.ConfirmDialog(
            title = "Confirm test",
            message = "Proceed with the test action?",
            confirmLabel = "Confirm",
            dismissLabel = "Cancel",
            onConfirm = { showConfirm = false },
            onDismiss = { showConfirm = false },
        )
    }
    if (showDestructive) {
        eu.akoos.photos.presentation.common.ConfirmDialog(
            title = "Delete test",
            message = "This cannot be undone.",
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            onConfirm = { showDestructive = false },
            onDismiss = { showDestructive = false },
            destructive = true,
        )
    }
    if (showDenseGrid) {
        eu.akoos.photos.presentation.common.DenseGridWarningDialog(
            onDismiss = { showDenseGrid = false },
            onPersist = { showDenseGrid = false },
        )
    }
    if (showUpdate) {
        eu.akoos.photos.presentation.common.UpdatePromptDialog(
            state = eu.akoos.photos.presentation.common.UpdatePromptState.Available(BuildConfig.VERSION_NAME, 42),
            onUpdate = { showUpdate = false },
            onDismiss = { showUpdate = false },
        )
    }
}

/**
 * DEBUG-only readout of the live device-health signals and the resulting verdict, so the gate can be
 * watched while the device state is simulated over adb (battery / temperature / thermal / power
 * saver). Only ever rendered behind a BuildConfig.DEBUG guard; inline English, never localized.
 */
@Composable
private fun DeviceHealthDebugCard(
    viewModel: DeviceHealthDebugViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val s by viewModel.snapshot.collectAsStateWithLifecycle()
    val v = eu.akoos.photos.util.evaluateDeviceHealth(s)
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            Text("Device health (live)", color = colors.fgPrimary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "battery ${s.batteryPercent}%  charging=${s.charging}  temp=${if (s.batteryTempCelsius.isNaN()) "n/a" else "${s.batteryTempCelsius}C"}",
                color = colors.fgMute, fontSize = 12.sp,
            )
            Text(
                "thermal=${s.thermal}  powerSaver=${s.powerSaveOn}  interacting=${s.interacting}  online=${s.online}",
                color = colors.fgMute, fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "heavyMl=${v.heavyMlAllowed}  background=${v.backgroundWorkAllowed}",
                color = colors.fgPrimary, fontSize = 12.sp,
            )
            Text("reason: ${v.reason}", color = colors.fgMute, fontSize = 12.sp)
        }
    }
}

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



