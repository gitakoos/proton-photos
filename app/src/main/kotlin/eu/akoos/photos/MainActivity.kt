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
import android.net.Uri
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
import eu.akoos.photos.worker.SyncWorker
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var authOrchestrator: AuthOrchestrator
    @Inject lateinit var workManager: WorkManager
    @Inject lateinit var appLockManager: AppLockManager
    @Inject lateinit var accountManager: AccountManager
    @Inject lateinit var driveRepo: DrivePhotoRepository
    @Inject lateinit var reconcile: ReconcileSyncStateUseCase
    @Inject lateinit var pendingDeleteNotif: PendingDeleteNotificationUseCase
    @Inject lateinit var updateOrchestrator: UpdateOrchestrator
    @Inject lateinit var updateInstaller: UpdateInstaller

    private var isLocked by mutableStateOf(false)
    private var lockEnabled = false
    /** Set when the user taps the home-screen photo widget — carries the URI of the photo
     *  the widget was showing at tap time. The NavGraph reads + clears this so the gallery
     *  can route straight to the viewer for that photo. `null` for regular cold starts. */
    private var widgetPhotoUri by mutableStateOf<String?>(null)
    /** Set when the user opens an external image/video via the system "Open with" / "Edit with"
     *  chooser. The NavGraph reads this and routes straight to the editor for that URI. `null`
     *  for cold starts without an EDIT/VIEW intent. */
    private var externalEditRequest by mutableStateOf<ExternalEditRequest?>(null)
    /** Wall-clock timestamp of the last ON_STOP (real backgrounding). Compared against the
     *  user-configured timeout (read from DataStore inside the lifecycle check) to decide
     *  whether enough time has elapsed to re-lock the app. */
    private var lastBackgroundMs = 0L
    /** Timestamp of the last successful unlock. Used to gate the re-lock check below: the
     *  Android BiometricPrompt internally cycles the host activity through ON_STOP / ON_RESTART
     *  on success, which would re-fire the lock guard and lock the app right after unlock
     *  without this grace window. A short grace window after [onUnlocked] suppresses the
     *  re-entry. */
    private var lastUnlockMs = 0L
    private val unlockGraceMs = 2000L

    /** Foreground-resume guard: silent refresh fires only when this much time has passed since
     *  the last successful sync. Mirrors the threshold the user sees as "fresh enough". */
    private val resumeRefreshThresholdMs = 5L * 60L * 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authOrchestrator.register(this)
        enableEdgeToEdge()

        // Pull the widget-tap photo URI out of the launching intent (if any). The same
        // path is mirrored in onNewIntent so a re-tap while the app is foregrounded also
        // routes correctly.
        widgetPhotoUri = intent?.getStringExtra(EXTRA_WIDGET_PHOTO_URI)
        externalEditRequest = parseExternalEditRequest(intent)

        // ProtonCore account-state handler. Without this, accounts with 2FA or two-pass mode
        // enabled get stuck after entering the password: the LoginActivity closes (because
        // first-factor auth succeeded), the AccountManager transitions to SessionSecondFactorNeeded
        // / AccountTwoPassModeNeeded, but nothing observes those states and the user is left
        // staring at the sign-in screen with no way forward. Wiring the observers here triggers
        // the corresponding workflows (which open the right ProtonCore activity).
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
            .onAccountDisabled { /* NavGraph already routes to login when isLoggedIn = false */ }
            .onUserKeyCheckFailed { /* corrupt user key — best to just disable and re-login */ }
            .onUserAddressKeyCheckFailed { /* same */ }

        lifecycleScope.launch {
            // Login gate: don't surface the BG "Watching for new photos" notification
            // until the user has actually signed in. A pre-login start meant a fresh
            // install that never completed login would show a permanent BG notif
            // pointing at an app that does no work — confusing and battery-curious.
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) return@launch

            val prefs = settingsDataStore.data.first()
            val autoSync = prefs[SettingsKeys.AUTO_SYNC] != false
            val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false
            // Periodic 15-min run + OS content URI trigger + persistent BG service. The
            // content trigger gives sub-second reaction on stock Android; the periodic
            // is the OEM-throttle safety net; the BG service keeps the in-process
            // MediaStore observer alive on Samsung One UI where the content trigger
            // doesn't survive Recents-swipe.
            if (autoSync) {
                SyncWorker.schedule(workManager, wifiOnly, SyncWorker.MIN_INTERVAL_MINUTES)
                // Don't wait for the first periodic fire — kick off a OneTime run immediately
                // so any pending uploads (including newly-imported photos) start backing up
                // the moment the app launches. APPEND_OR_REPLACE inside SyncWorker.runNow
                // coalesces with the periodic run if it's already active.
                SyncWorker.runNow(this@MainActivity, wifiOnly)
                SyncWorker.scheduleContentObserver(this@MainActivity, wifiOnly)
                eu.akoos.photos.service.BackgroundSyncService.start(this@MainActivity)
            }
        }

        // Observe lock setting changes. The DataStore-backed flow re-emits on every preference
        // write (e.g. LAST_SYNC_MS bumps every sync), so without distinctUntilChanged this
        // collector would re-assert isLocked=true on every sync tick — manifesting as the lock
        // screen reappearing repeatedly after the user already unlocked.
        //
        // Track previous value so a user-driven OFF→ON toggle in Settings re-locks immediately,
        // but a same-value emission (the spurious one we're guarding against) does nothing.
        var sawFirstEmission = false
        var previousEnabled = false
        lifecycleScope.launch {
            appLockManager.isLockEnabled.distinctUntilChanged().collect { enabled ->
                lockEnabled = enabled
                if (!sawFirstEmission) {
                    // Initial state on cold start: lock if enabled and there's no saved state.
                    if (enabled && savedInstanceState == null) isLocked = true
                } else if (enabled && !previousEnabled) {
                    // User just turned the lock ON from Settings — apply immediately.
                    isLocked = true
                }
                previousEnabled = enabled
                sawFirstEmission = true
            }
        }

        // Re-lock on foreground when the user has been backgrounded longer than the configured
        // timeout. The lifecycle re-enters STARTED on every resume; the check itself is cheap
        // (a couple of Long comparisons + one DataStore read) so it's fine to run on every
        // STARTED entry.
        //
        // We suspend-read the timeout INSIDE the block instead of caching it in a field. The
        // cached approach raced against the DataStore-backed flow: if the STARTED block fired
        // before the flow's initial emission, lockTimeoutMs was still 0 (the field default),
        // and a "Lock after 5 minutes" preference would behave as "Lock immediately". Reading
        // the current value at check time eliminates the race.
        // Sign-out drops the timestamps belonging to the previous user's session
        // so a re-login as a different account can't inherit a sinceBackground window
        // that fires the re-lock guard before the new account's preferences settle.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appLockManager.resetLockTimestamps.collect {
                    lastBackgroundMs = 0L
                    lastUnlockMs = 0L
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val timeoutMs = appLockManager.lockTimeoutMinutes.first().toLong() * 60_000L
                val now = System.currentTimeMillis()
                val sinceUnlock = now - lastUnlockMs
                val sinceBackground = if (lastBackgroundMs == 0L) 0L else now - lastBackgroundMs
                // Conditions to re-lock:
                //   - lock feature on at all
                //   - the activity actually was backgrounded since the last unlock (lastBackgroundMs != 0)
                //   - background duration >= the user-configured timeout
                //   - not inside the post-unlock biometric-prompt grace window
                if (lockEnabled
                    && lastBackgroundMs != 0L
                    && sinceBackground >= timeoutMs
                    && sinceUnlock > unlockGraceMs
                ) {
                    isLocked = true
                }
                // Always clear the background marker on STARTED entry — fresh resume cycle.
                lastBackgroundMs = 0L
            }
        }

        setContent {
            // Theme mode lives in DataStore — re-collect when the user changes it so the UI
            // re-themes immediately without an app restart.
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

            // Accent-color palette — DataStore-backed, re-collected so changes apply live
            // without an app restart. Independent of light/dark.
            val paletteFlow = remember {
                settingsDataStore.data.map { it[SettingsKeys.THEME_PALETTE] }
            }
            val paletteKey by paletteFlow.collectAsState(initial = null)
            val palette = ThemePalette.fromKey(paletteKey)

            // Active locale — driven by DataStore so a user change reflows the in-app
            // string-resource resolution without an Activity recreate. Initial value
            // comes from the boot-mirror so the first composition uses the right locale
            // without a Flow round-trip flash.
            val languageFlow = remember {
                settingsDataStore.data.map { prefs ->
                    prefs[SettingsKeys.LANGUAGE] ?: "system"
                }
            }
            val language by languageFlow.collectAsState(
                initial = LanguagePrefsBoot.read(this@MainActivity),
            )

            // Flip status/navigation-bar icon contrast to match the active theme so they stay
            // legible on light backgrounds.
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
     * Compose surface for the in-app updater dialog. Subscribes to the singleton
     * [UpdateOrchestrator]'s state and renders [UpdatePromptDialog] whenever a phase
     * (Available / Downloading / InstallReady / Error) is active. Lives inside the host
     * Activity (not inside a screen) because the prompt is global — the user might be on
     * the gallery, in the editor, or browsing an album when the silent check completes,
     * and the prompt should layer on top of whichever route is rendered.
     */
    @androidx.compose.runtime.Composable
    private fun UpdaterHost() {
        val current by updateOrchestrator.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        // Returns from the "Install unknown apps" permission screen. We re-check whether
        // the OS now allows installs; if so, fire the install intent immediately so the
        // user doesn't have to tap Update a second time.
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
     * Fired from the Settings "Check for updates" row. Runs a forced (cache-bypassing)
     * check; success-with-no-update surfaces a brief Toast, a network flake surfaces a
     * different one. New-version-available implicitly transitions through
     * [UpdatePromptDialog] because the orchestrator updates its state flow.
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
        // Widget re-tap while the app is foregrounded → grab the new photo URI so the
        // NavGraph LaunchedEffect picks it up and forwards to the viewer.
        intent.getStringExtra(EXTRA_WIDGET_PHOTO_URI)?.let { widgetPhotoUri = it }
        parseExternalEditRequest(intent)?.let { externalEditRequest = it }
    }

    /**
     * Inspect the intent for an ACTION_EDIT or ACTION_VIEW carrying an image/video URI.
     * Returns null if the intent doesn't match (regular launches, widget taps, etc.).
     * Display name is resolved via OpenableColumns; falls back to "image" / "video" plus
     * a timestamp when the content provider doesn't expose one (some providers don't).
     * The two actions land on different downstream routes: VIEW → photo viewer, EDIT →
     * editor — the flag is captured here so the NavGraph branch is unambiguous.
     */
    private fun parseExternalEditRequest(intent: Intent?): ExternalEditRequest? {
        intent ?: return null
        val action = intent.action ?: return null
        if (action != Intent.ACTION_EDIT && action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        // Prefer the type the launching app explicitly set on the intent; fall back to
        // asking the ContentResolver if it's missing (some apps omit the type).
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
        // Fall back to last path segment (often a sane filename for file:// URIs and many
        // FileProvider URIs). If that fails too, synthesise a name from the timestamp.
        val segment = uri.lastPathSegment
        if (!segment.isNullOrBlank() && segment.contains('.')) return segment
        val ts = System.currentTimeMillis()
        return if (isVideo) "video_$ts.mp4" else "image_$ts.jpg"
    }

    override fun onStop() {
        super.onStop()
        // Record the wall-clock time the activity stopped being visible — the re-lock check
        // in repeatOnLifecycle compares this against the configured timeout. We deliberately
        // use System.currentTimeMillis (not elapsedRealtime) so a clock change while the app
        // is in the background still does the sensible thing — clock-forward locks the app,
        // clock-backward leaves it unlocked (the right safe default).
        lastBackgroundMs = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        // Reconcile the delete-after-backup pending queue against MediaStore the
        // moment the user opens the app. Covers the offline scenario where the
        // user deleted a queued file via the file manager: the worker's content
        // URI trigger needs network to fire, but a foreground resume always runs.
        // The prune drops stale URIs from the queue, collapses their SyncState to
        // CLOUD_ONLY, and refreshes or cancels the consent notification — so the
        // user never sees a stale "X photos ready to remove" banner or trips the
        // createDeleteRequest stale URI bug.
        lifecycleScope.launch {
            runCatching { pendingDeleteNotif() }
        }
        // Auto-refresh on foreground resume when the last sync is stale. Silent (no spinner,
        // no error toasts) — purely background work that the gallery's DB observer picks up.
        //
        // Why this exists: WorkManager is throttled on aggressive OEMs; without an in-process
        // poke, a user who left the app for an hour can come back to a stale gallery showing
        // photos that were deleted on Drive web in the meantime. The pull-to-refresh gesture
        // covers the explicit case; this covers the implicit "just opened the app" case.
        lifecycleScope.launch {
            try {
                val prefs = settingsDataStore.data.first()
                val lastSync = prefs[SettingsKeys.LAST_SYNC_MS] ?: 0L
                if (System.currentTimeMillis() - lastSync < resumeRefreshThresholdMs) return@launch
                val userId = accountManager.getPrimaryUserId().first() ?: return@launch
                val autoSync = prefs[SettingsKeys.AUTO_SYNC] != false
                val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false
                // Incremental + reconcile is the lightweight pair: events-based delta from
                // Drive, then a single SyncState pass.
                driveRepo.refreshCloudPhotosIncremental(userId)
                reconcile(userId).collect {}
                // Kick off a OneTime sync if backup is enabled — picks up any LOCAL_ONLY
                // entries that reconcile just flagged. Coalesces with anything already
                // running via the unique-work KEEP policy.
                if (autoSync) SyncWorker.runNow(this@MainActivity, wifiOnly)
            } catch (_: Exception) {
                // Silent — onResume must never crash the activity. The next user-driven
                // refresh (or the next SyncWorker tick) will retry.
            }
        }
    }

    companion object {
        /** Intent extra key — carries a MediaStore URI string when the user taps the
         *  home-screen photo widget. NavGraph + GalleryScreen route to the viewer. */
        const val EXTRA_WIDGET_PHOTO_URI = "widget_photo_uri"
    }
}
