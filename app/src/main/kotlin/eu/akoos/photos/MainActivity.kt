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

package eu.akoos.photos

import android.content.ContentResolver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.accountmanager.presentation.observe
import me.proton.core.accountmanager.presentation.onAccountCreateAddressFailed
import me.proton.core.accountmanager.presentation.onAccountCreateAddressNeeded
import me.proton.core.accountmanager.presentation.onAccountDisabled
import me.proton.core.accountmanager.presentation.onAccountTwoPassModeFailed
import me.proton.core.accountmanager.presentation.onAccountTwoPassModeNeeded
import me.proton.core.accountmanager.presentation.onSessionForceLogout
import me.proton.core.accountmanager.presentation.onSessionSecondFactorFailed
import me.proton.core.accountmanager.presentation.onSessionSecondFactorNeeded
import me.proton.core.accountmanager.presentation.onUserAddressKeyCheckFailed
import me.proton.core.accountmanager.presentation.onUserKeyCheckFailed
import me.proton.core.auth.presentation.AuthOrchestrator
import eu.akoos.photos.data.api.FORCE_UPDATE_REQUIRED
import eu.akoos.photos.data.preferences.LanguagePrefsBoot
import eu.akoos.photos.data.updater.UpdateInstaller
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.PendingDeleteNotificationUseCase
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.UpdatePromptDialog
import eu.akoos.photos.presentation.common.UpdatePromptState
import eu.akoos.photos.presentation.updater.UpdateOrchestrator
import eu.akoos.photos.domain.usecase.ReconcileSyncStateUseCase
import eu.akoos.photos.navigation.ExternalEditRequest
import eu.akoos.photos.navigation.NavGraph
import eu.akoos.photos.presentation.lock.AppLockManager
import eu.akoos.photos.presentation.lock.AppLockScreen
import eu.akoos.photos.presentation.settings.ThemeMode
import eu.akoos.photos.presentation.settings.ThemePalette
import eu.akoos.photos.presentation.theme.ProtonPhotosTheme
import eu.akoos.photos.presentation.util.LocaleOverride
import eu.akoos.photos.data.repository.drive.PhotoStreamService
import eu.akoos.photos.util.NetworkObserver
import eu.akoos.photos.worker.SyncWorker
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var authOrchestrator: AuthOrchestrator
    @Inject lateinit var workManager: WorkManager
    @Inject lateinit var appLockManager: AppLockManager
    @Inject lateinit var accountManager: AccountManager
    @Inject lateinit var driveRepo: DrivePhotoRepository
    @Inject lateinit var photoStreamService: PhotoStreamService
    @Inject lateinit var networkObserver: NetworkObserver
    @Inject lateinit var reconcile: ReconcileSyncStateUseCase
    @Inject lateinit var pendingDeleteNotif: PendingDeleteNotificationUseCase
    @Inject lateinit var updateOrchestrator: UpdateOrchestrator
    @Inject lateinit var updateInstaller: UpdateInstaller

    private var isLocked by mutableStateOf(false)
    private var lockEnabled = false
    /** Photo URI from a home-screen widget tap; NavGraph reads + clears it to route to the viewer. */
    private var widgetPhotoUri by mutableStateOf<String?>(null)
    /** Request from a system "Open with" / "Edit with" intent; NavGraph routes to the editor. */
    private var externalEditRequest by mutableStateOf<ExternalEditRequest?>(null)
    /** Wall-clock of the last ON_STOP, compared against the configured timeout to decide re-lock. */
    private var lastBackgroundMs = 0L
    /** Last successful unlock. BiometricPrompt cycles the host through ON_STOP/ON_RESTART on
     *  success, which would re-fire the lock guard; [unlockGraceMs] after this suppresses that. */
    private var lastUnlockMs = 0L
    private val unlockGraceMs = 2000L

    /** Foreground-resume silent refresh fires only after this long since the last successful sync. */
    // Short floor (not minutes) because the on-resume refresh is now a cheap newest-page poll for
    // accounts without an event anchor, so it can run on almost every return without a battery cost.
    private val resumeRefreshThresholdMs = 60L * 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authOrchestrator.register(this)
        enableEdgeToEdge()

        // Widget-tap URI from the launching intent (mirrored in onNewIntent for a foregrounded re-tap).
        widgetPhotoUri = intent?.getStringExtra(EXTRA_WIDGET_PHOTO_URI)
        externalEditRequest = parseExternalEditRequest(intent)

        // ProtonCore account-state handler. Without it, 2FA / two-pass accounts stall after the
        // password: LoginActivity closes on first-factor success but nothing observes the resulting
        // SecondFactorNeeded / TwoPassModeNeeded state. These observers open the right workflow.
        accountManager.observe(this.lifecycle, Lifecycle.State.CREATED)
            .onAccountTwoPassModeNeeded { authOrchestrator.startTwoPassModeWorkflow(it) }
            .onAccountTwoPassModeFailed {
                lifecycleScope.launch { accountManager.disableAccount(it.userId) }
            }
            .onAccountCreateAddressNeeded { authOrchestrator.startChooseAddressWorkflow(it) }
            .onAccountCreateAddressFailed {
                lifecycleScope.launch { accountManager.disableAccount(it.userId) }
            }
            .onSessionSecondFactorNeeded { authOrchestrator.startSecondFactorWorkflow(it) }
            .onSessionSecondFactorFailed {
                lifecycleScope.launch { accountManager.disableAccount(it.userId) }
            }
            .onSessionForceLogout {
                lifecycleScope.launch { accountManager.disableAccount(it.userId) }
            }
            .onAccountDisabled {
                // Every sign-out path converges here (explicit sign-out, force-logout, 2FA /
                // key-check failures). Wipe this account's cached rows, plaintext key material and
                // decrypted thumbnails so a revoked or re-authed session leaves nothing resident.
                // NavGraph already routes to login once isLoggedIn = false.
                lifecycleScope.launch { runCatching { driveRepo.clearCacheForSignOut(it.userId) } }
            }
            .onUserKeyCheckFailed { /* corrupt user key — best to just disable and re-login */ }
            .onUserAddressKeyCheckFailed { /* same */ }

        lifecycleScope.launch {
            // Login gate: don't arm the BG "Watching for new photos" notification before sign-in,
            // or a never-completed install shows a permanent notif for an app doing no work.
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) return@launch

            val prefs = settingsDataStore.data.first()
            val autoSync = prefs[SettingsKeys.AUTO_SYNC] != false
            val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false
            // Three triggers: content-URI (sub-second on stock Android), periodic 15-min (OEM-throttle
            // safety net), and the BG service (keeps the observer alive on Samsung One UI where the
            // content trigger doesn't survive a Recents-swipe).
            if (autoSync) {
                SyncWorker.schedule(workManager, wifiOnly, SyncWorker.MIN_INTERVAL_MINUTES)
                // Kick a OneTime run now so pending uploads start at launch; APPEND_OR_REPLACE coalesces
                // with the periodic run.
                SyncWorker.runNow(this@MainActivity, wifiOnly)
                SyncWorker.scheduleContentObserver(this@MainActivity, wifiOnly)
                // The persistent keep-alive foreground service is opt-in and OFF by default — the
                // content trigger + periodic worker keep backups flowing without it (and without a
                // standing notification). Only start it when the user has explicitly opted in.
                val backupNotif = prefs[SettingsKeys.NOTIFY_BACKUP_STATUS] == true
                if (backupNotif) eu.akoos.photos.service.BackgroundSyncService.start(this@MainActivity)
            }
        }

        // Early cloud-photo refresh once a primary user resolves, so thumbnails are landing by the
        // time the gallery appears. setGentleSync(true) paces the decrypt burst slowly under the
        // heavy first screen; GalleryViewModel flips it off when visible. refreshFullMutex single-
        // flights it, so the later syncOnLaunch refresh just awaits this one. Gated on the same
        // constraints as the sync workers: auto-sync on, not low battery, not metered when Wi-Fi-only.
        lifecycleScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val prefs = settingsDataStore.data.first()
            val autoSync = prefs[SettingsKeys.AUTO_SYNC] != false
            if (!autoSync) return@launch
            if (isBatteryLow()) return@launch
            val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false
            if (wifiOnly && !networkObserver.isUnmetered.value) return@launch
            photoStreamService.setGentleSync(true)
            runCatching { driveRepo.refreshCloudPhotos(userId) }
        }

        // Observe lock setting changes. distinctUntilChanged is essential: the DataStore flow
        // re-emits on every preference write (e.g. LAST_SYNC_MS each sync), which would otherwise
        // re-assert isLocked=true on every tick and pop the lock screen after the user unlocked.
        // Tracking previousEnabled lets a real OFF→ON toggle re-lock while same-value emits don't.
        var sawFirstEmission = false
        var previousEnabled = false
        lifecycleScope.launch {
            appLockManager.isLockEnabled.distinctUntilChanged().collect { enabled ->
                lockEnabled = enabled
                if (!sawFirstEmission) {
                    // Lock on the first emission of every fresh Activity create when the lock is on,
                    // including an OS process-death restore (savedInstanceState != null). Rotation is
                    // handled via configChanges (no recreate), so a recreate only happens on a real
                    // cold / death start, where re-locking is exactly right. Guarding on
                    // savedInstanceState == null instead would let a process-death restore reopen unlocked.
                    if (enabled) isLocked = true
                } else if (enabled && !previousEnabled) {
                    isLocked = true
                }
                previousEnabled = enabled
                sawFirstEmission = true
            }
        }

        // Sign-out drops the previous session's timestamps so a re-login as another account can't
        // inherit a sinceBackground window that fires the re-lock guard before its prefs settle.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appLockManager.resetLockTimestamps.collect {
                    lastBackgroundMs = 0L
                    lastUnlockMs = 0L
                }
            }
        }
        // Re-lock on foreground when backgrounded longer than the configured timeout. The timeout is
        // suspend-read INSIDE the block, not cached in a field: a cached default of 0 raced the
        // DataStore flow's first emission and turned "Lock after 5 min" into "Lock immediately".
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val timeoutMs = appLockManager.lockTimeoutMinutes.first().toLong() * 60_000L
                val now = System.currentTimeMillis()
                val sinceUnlock = now - lastUnlockMs
                val sinceBackground = if (lastBackgroundMs == 0L) 0L else now - lastBackgroundMs
                // Re-lock only if: lock on, actually backgrounded since last unlock, past the timeout,
                // and outside the post-unlock biometric grace window.
                if (lockEnabled
                    && lastBackgroundMs != 0L
                    && sinceBackground >= timeoutMs
                    && sinceUnlock > unlockGraceMs
                ) {
                    isLocked = true
                }
                lastBackgroundMs = 0L
            }
        }

        setContent {
            // Theme mode lives in DataStore — re-collect so a change re-themes without a restart.
            val themeKeyFlow = remember {
                settingsDataStore.data.map { prefs ->
                    prefs[SettingsKeys.THEME_MODE]
                        ?: when (prefs[SettingsKeys.DARK_MODE]) {
                            true  -> "dark"
                            false -> "light"
                            null  -> "dark"  // first-launch default — matches ThemePrefsBoot
                        }
                }
            }
            val themeKey by themeKeyFlow.collectAsState(initial = "dark")
            val themeMode = ThemeMode.fromKey(themeKey)
            val systemDark = isSystemInDarkTheme()
            val useDark = when (themeMode) {
                ThemeMode.System -> systemDark
                ThemeMode.Light  -> false
                ThemeMode.Dark   -> true
            }

            // Accent-color palette — DataStore-backed, re-collected so changes apply live.
            val paletteFlow = remember {
                settingsDataStore.data.map { it[SettingsKeys.THEME_PALETTE] }
            }
            val paletteKey by paletteFlow.collectAsState(initial = null)
            val palette = ThemePalette.fromKey(paletteKey)

            // Active locale — DataStore-driven so a change reflows string resolution without an
            // Activity recreate. Initial value from the boot-mirror to avoid a first-composition flash.
            val languageFlow = remember {
                settingsDataStore.data.map { prefs ->
                    prefs[SettingsKeys.LANGUAGE] ?: "system"
                }
            }
            val language by languageFlow.collectAsState(
                initial = LanguagePrefsBoot.read(this@MainActivity),
            )

            // Match status/navigation-bar icon contrast to the active theme.
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = this@MainActivity.window
                    val insets = WindowCompat.getInsetsController(window, view)
                    insets.isAppearanceLightStatusBars     = !useDark
                    insets.isAppearanceLightNavigationBars = !useDark
                }
            }

            LocaleOverride(language) {
                ProtonPhotosTheme(darkTheme = useDark, palette = palette) {
                    val forceUpdateFlow = remember {
                        settingsDataStore.data.map { it[FORCE_UPDATE_REQUIRED] == true }
                    }
                    val forceUpdate by forceUpdateFlow.collectAsState(initial = false)

                    when {
                        forceUpdate -> ForceUpdateDialog()
                        isLocked -> AppLockScreen(onUnlocked = {
                            lastUnlockMs = System.currentTimeMillis()
                            isLocked = false
                        })
                        else -> {
                            NavGraph(
                                onStartLogin = { authOrchestrator.startLoginWorkflow(null) },
                                onCheckForUpdates = { runManualUpdateCheck() },
                                widgetPhotoUri = widgetPhotoUri,
                                onWidgetPhotoConsumed = { widgetPhotoUri = null },
                                externalEditRequest = externalEditRequest,
                                onExternalEditConsumed = { externalEditRequest = null },
                            )
                            UpdaterHost()
                        }
                    }
                }
            }
        }
    }

    /**
     * Compose surface for the in-app updater dialog. Renders [UpdatePromptDialog] for whatever
     * [UpdateOrchestrator] phase is active. Lives in the host Activity, not a screen, so the
     * global prompt layers over whichever route is rendered when the silent check completes.
     */
    @androidx.compose.runtime.Composable
    private fun UpdaterHost() {
        val current by updateOrchestrator.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        // Back from the "Install unknown apps" permission screen — fire the install immediately if
        // the OS now allows it, so the user needn't tap Update again.
        val installLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            val file = updateOrchestrator.pendingInstallFile()
            if (file != null && updateInstaller.canInstall()) {
                runCatching { startActivity(updateInstaller.buildInstallIntent(file)) }
            }
        }
        current?.let { state ->
            UpdatePromptDialog(
                state = state,
                onUpdate = {
                    when (state) {
                        is UpdatePromptState.Available -> updateOrchestrator.confirmUpdate(scope)
                        is UpdatePromptState.InstallReady -> {
                            val file = updateOrchestrator.pendingInstallFile()
                            if (file != null) {
                                if (!updateInstaller.canInstall()) {
                                    runCatching {
                                        installLauncher.launch(updateInstaller.buildPermissionRequestIntent())
                                    }
                                } else {
                                    runCatching { startActivity(updateInstaller.buildInstallIntent(file)) }
                                }
                            }
                        }
                        else -> Unit
                    }
                },
                onDismiss = { updateOrchestrator.dismiss(scope) },
            )
        }
    }

    /**
     * Settings "Check for updates" — runs a forced check; up-to-date and network-flake each show a
     * Toast. A new version surfaces via [UpdatePromptDialog] through the orchestrator's state flow.
     */
    private fun runManualUpdateCheck() {
        lifecycleScope.launch {
            when (updateOrchestrator.runManualCheck()) {
                UpdateOrchestrator.ManualCheckOutcome.UpToDate -> {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_no_update),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                is UpdateOrchestrator.ManualCheckOutcome.Failed -> {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_check_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                UpdateOrchestrator.ManualCheckOutcome.NewVersionShown,
                UpdateOrchestrator.ManualCheckOutcome.InProgress -> Unit
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ForceUpdateDialog() {
        Box(Modifier.fillMaxSize()) {
            ConfirmDialog(
                title = androidx.compose.ui.res.stringResource(eu.akoos.photos.R.string.force_update_title),
                message = androidx.compose.ui.res.stringResource(eu.akoos.photos.R.string.force_update_body),
                confirmLabel = androidx.compose.ui.res.stringResource(eu.akoos.photos.R.string.force_update_action),
                dismissLabel = null,
                onConfirm = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        "market://details?id=$packageName".toUri(),
                    )
                    runCatching { startActivity(intent) }
                    finish()
                },
                onDismiss = { /* non-dismissible */ },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Foregrounded widget re-tap → grab the new URI for NavGraph's LaunchedEffect.
        intent.getStringExtra(EXTRA_WIDGET_PHOTO_URI)?.let { widgetPhotoUri = it }
        parseExternalEditRequest(intent)?.let { externalEditRequest = it }
    }

    /**
     * Parses an ACTION_EDIT / ACTION_VIEW intent carrying an image/video URI into an
     * [ExternalEditRequest], or null for non-matching intents. The isViewOnly flag splits the
     * downstream route (VIEW → viewer, EDIT → editor).
     */
    private fun parseExternalEditRequest(intent: Intent?): ExternalEditRequest? {
        intent ?: return null
        val action = intent.action ?: return null
        if (action != Intent.ACTION_EDIT && action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        // Prefer the intent's type; fall back to the ContentResolver (some apps omit it).
        val mimeType = intent.type
            ?: runCatching { contentResolver.getType(uri) }.getOrNull()
            ?: return null
        val isImage = mimeType.startsWith("image/")
        val isVideo = mimeType.startsWith("video/")
        if (!isImage && !isVideo) return null
        val displayName = queryDisplayName(uri, isVideo)
        return ExternalEditRequest(
            uri = uri.toString(),
            displayName = displayName,
            mimeType = mimeType,
            isVideo = isVideo,
            isViewOnly = action == Intent.ACTION_VIEW,
        )
    }

    private fun queryDisplayName(uri: Uri, isVideo: Boolean): String {
        val fromResolver = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull()
        if (!fromResolver.isNullOrBlank()) return fromResolver
        // Fall back to the last path segment, then to a timestamp-synthesised name.
        val segment = uri.lastPathSegment
        if (!segment.isNullOrBlank() && segment.contains('.')) return segment
        val ts = System.currentTimeMillis()
        return if (isVideo) "video_$ts.mp4" else "image_$ts.jpg"
    }

    /**
     * Runtime analogue of the workers' `setRequiresBatteryNotLow(true)`: reads the sticky
     * ACTION_BATTERY_CHANGED broadcast and compares to the OS's 15% "battery low" floor. Returns
     * false (don't block) when the level can't be read, so a missing broadcast never suppresses refresh.
     */
    private fun isBatteryLow(): Boolean {
        val status = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return false
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return false
        return level.toFloat() / scale.toFloat() <= 0.15f
    }

    override fun onStop() {
        super.onStop()
        // Wall-clock (not elapsedRealtime) so a background clock change stays safe: forward locks,
        // backward leaves unlocked. The re-lock check compares this against the configured timeout.
        lastBackgroundMs = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        // Reconcile the delete-after-backup queue against MediaStore on open. Covers the offline
        // case where a queued file was deleted via the file manager (the worker's content trigger
        // needs network, but resume always runs): stale URIs are dropped to CLOUD_ONLY and the
        // consent notification refreshed, avoiding a stale banner and the createDeleteRequest bug.
        lifecycleScope.launch {
            runCatching { pendingDeleteNotif() }
        }
        // Silent auto-refresh on resume when the last sync is stale. WorkManager is throttled on
        // aggressive OEMs, so without this poke a gallery left for an hour can show photos already
        // deleted on Drive web. Pull-to-refresh covers the explicit case; this covers "just opened".
        lifecycleScope.launch {
            try {
                val prefs = settingsDataStore.data.first()
                val lastSync = prefs[SettingsKeys.LAST_SYNC_MS] ?: 0L
                if (System.currentTimeMillis() - lastSync < resumeRefreshThresholdMs) return@launch
                val userId = accountManager.getPrimaryUserId().first() ?: return@launch
                val autoSync = prefs[SettingsKeys.AUTO_SYNC] != false
                val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false
                // Lightweight pair: events-based Drive delta, then one SyncState pass.
                driveRepo.refreshCloudPhotosIncremental(userId)
                reconcile(userId).collect {}
                // OneTime sync if backup is on — picks up LOCAL_ONLY entries reconcile just flagged.
                if (autoSync) SyncWorker.runNow(this@MainActivity, wifiOnly)
            } catch (_: Exception) {
                // Silent — onResume must never crash; the next refresh or SyncWorker tick retries.
            }
        }
        // Silent in-app update check. The repository caches the result for 24h, so this is a no-op
        // on most resumes; when a newer GitHub release exists it surfaces the update dialog (and,
        // after "Not now", the dismissable gallery banner) through UpdateOrchestrator.
        lifecycleScope.launch {
            runCatching { updateOrchestrator.runSilentCheck() }
        }
    }

    companion object {
        /** Intent extra: MediaStore URI string from a home-screen widget tap. */
        const val EXTRA_WIDGET_PHOTO_URI = "widget_photo_uri"
    }
}
