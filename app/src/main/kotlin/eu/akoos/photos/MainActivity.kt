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

package eu.akoos.photos

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
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
import eu.akoos.photos.data.updater.InstallOutcome
import eu.akoos.photos.data.updater.InstallSessionEvents
import eu.akoos.photos.data.updater.UpdateInstaller
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.ThemePrefsBoot
import eu.akoos.photos.data.preferences.continueWithoutAccount
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.preferences.syncEffectivelyEnabled
import eu.akoos.photos.data.hidden.HiddenVaultRestorer
import eu.akoos.photos.data.hidden.VaultRescueNotifier
import eu.akoos.photos.data.hidden.VaultSignOutPolicy
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.NewsRepository
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.usecase.PendingDeleteNotificationUseCase
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.settings.FreeUpInterval
import eu.akoos.photos.presentation.common.UpdatePromptDialog
import eu.akoos.photos.presentation.common.UpdatePromptState
import eu.akoos.photos.presentation.updater.UpdateOrchestrator
import eu.akoos.photos.domain.usecase.ReconcileSyncStateUseCase
import eu.akoos.photos.navigation.ExternalEditRequest
import eu.akoos.photos.navigation.NavGraph
import eu.akoos.photos.presentation.lock.AppLockGate
import eu.akoos.photos.presentation.lock.AppLockGateState
import eu.akoos.photos.presentation.lock.AppLockSession
import eu.akoos.photos.presentation.lock.runWhenOpen
import eu.akoos.photos.presentation.settings.ThemeMode
import eu.akoos.photos.presentation.settings.ThemePalette
import eu.akoos.photos.presentation.theme.LocalStaticImageLoader
import eu.akoos.photos.presentation.theme.ProtonPhotosTheme
import eu.akoos.photos.presentation.util.ClockFormat
import eu.akoos.photos.presentation.util.ClockFormatState
import eu.akoos.photos.presentation.util.LocalIs24Hour
import eu.akoos.photos.presentation.util.LocaleOverride
import eu.akoos.photos.presentation.util.phoneUses24Hour
import eu.akoos.photos.data.repository.drive.PhotoStreamService
import eu.akoos.photos.util.DeviceHealthPolicy
import eu.akoos.photos.util.NetworkObserver
import eu.akoos.photos.util.isBatteryLow
import eu.akoos.photos.worker.FreeUpSpaceWorker
import eu.akoos.photos.worker.SyncWorker
import javax.inject.Inject

/**
 * A cross-app edit or view intent is honoured only for a content:// URI. A file:// URI could aim
 * the app at its own private storage, so every scheme other than content is refused. Kept as a pure
 * predicate so it is verifiable in a plain JVM unit test.
 */
internal fun isAcceptableExternalEditScheme(scheme: String?): Boolean = scheme == "content"

/**
 * True for an authority this app's own providers answer to: the package name itself or anything under
 * it. A user prefix ("0@authority") is stripped the way the content resolver strips it, and a port is
 * ignored, so neither form slips past.
 */
internal fun isOwnProviderAuthority(authority: String?, ownPackage: String): Boolean {
    if (authority == null) return false
    val host = authority.substringAfterLast('@').substringBefore(':').trim().lowercase(Locale.ROOT)
    val pkg = ownPackage.lowercase(Locale.ROOT)
    return host == pkg || host.startsWith("$pkg.")
}

/**
 * The "Open with" / "Edit with" gate: a content:// URI served by another app. A URI of one of this
 * app's own FileProviders is refused, since the app can read those itself, and a caller could use that
 * to have it open a vault photo or the decrypted cache.
 */
internal fun isAcceptableExternalEditUri(scheme: String?, authority: String?, ownPackage: String): Boolean =
    isAcceptableExternalEditScheme(scheme) &&
        !authority.isNullOrBlank() &&
        !isOwnProviderAuthority(authority, ownPackage)

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var authOrchestrator: AuthOrchestrator
    @Inject lateinit var workManager: WorkManager
    @Inject lateinit var lockSession: AppLockSession
    @Inject lateinit var accountManager: AccountManager
    @Inject lateinit var driveRepo: DrivePhotoRepository
    @Inject lateinit var photoStreamService: PhotoStreamService
    @Inject lateinit var networkObserver: NetworkObserver
    @Inject lateinit var deviceHealth: DeviceHealthPolicy
    @Inject lateinit var reconcile: ReconcileSyncStateUseCase
    @Inject lateinit var pendingDeleteNotif: PendingDeleteNotificationUseCase
    @Inject lateinit var updateOrchestrator: UpdateOrchestrator
    @Inject lateinit var updateInstaller: UpdateInstaller
    @Inject lateinit var installSessionEvents: InstallSessionEvents
    @Inject lateinit var newsRepository: NewsRepository
    @Inject lateinit var hiddenVaultRestorer: HiddenVaultRestorer
    @Inject lateinit var vaultRescueNotifier: VaultRescueNotifier
    @Inject lateinit var externalPickHandover: eu.akoos.photos.presentation.picker.ExternalPickHandover
    @Inject @eu.akoos.photos.di.AppScope lateinit var appScope: kotlinx.coroutines.CoroutineScope

    private val vaultSignOut by lazy {
        val restorer = hiddenVaultRestorer
        val notifier = vaultRescueNotifier
        VaultSignOutPolicy(
            rescue = { restorer.rescueDeviceOnly() },
            notifyMoved = { zipName -> notifier.notifyMoved(zipName) },
        )
    }

    /** Disables an account whose sign-in gave up before it made a session; the vault stays. */
    private suspend fun disableUnfinishedSignIn(userId: UserId) {
        vaultSignOut.markUnfinishedSignIn(userId.id)
        eu.akoos.photos.util.SignOutCleanup.noteUnfinishedSignIn(userId.id)
        accountManager.disableAccount(userId)
    }

    /**
     * Everything a disabled account leaves behind, in order: the work scheduled against it stops, the
     * vault's device-only photos are rescued, then its rows, keys, preferences and decrypted caches are
     * wiped. It runs on the app scope, so a recreated or finished Activity does not cut it short, and
     * [persist] keeps a marker until the end, so a process killed part way finishes it on the next
     * launch (see [replayUnfinishedSignOutCleanups]).
     */
    private suspend fun cleanUpAfterSignOut(
        app: android.content.Context,
        policy: VaultSignOutPolicy,
        userId: UserId,
        persist: Boolean,
    ) {
        val cleanup = eu.akoos.photos.util.SignOutCleanup
        if (!cleanup.claim(userId.id)) return
        try {
            if (persist) signOutStep { cleanup.markPending(app, userId.id) }
            cleanup.stopBackgroundWork(app, workManager)
            val vault = try {
                policy.afterSignOut(userId.id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                eu.akoos.photos.domain.repository.VaultWipe.None
            }
            signOutStep { driveRepo.clearCacheForSignOut(userId, vault) }
            signOutStep { eu.akoos.photos.data.preferences.AccountScopedPreferences.clear(app, userId) }
            signOutStep {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cleanup.clearDecryptedCaches(app)
                }
            }
            signOutStep { cleanup.clearPending(app, userId.id) }
        } finally {
            cleanup.release(userId.id)
        }
    }

    private suspend inline fun signOutStep(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("MainActivity", "sign-out cleanup step failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Finishes a sign-out cleanup an earlier process started and did not end, but only while nobody is
     * signed in and no guest session is in use: anyone present now, the same account again, another one
     * or a guest, only loses the marker, since most of the cleanup is not scoped to the account that left.
     */
    private suspend fun replayUnfinishedSignOutCleanups(app: android.content.Context, policy: VaultSignOutPolicy) {
        val cleanup = eu.akoos.photos.util.SignOutCleanup
        val pending = cleanup.pending(app)
        if (pending.isEmpty()) return
        val present = accountManager.getPrimaryUserId().first()?.id
            ?: accountManager.getAccounts().first().firstOrNull {
                it.state != me.proton.core.account.domain.entity.AccountState.Disabled &&
                    it.state != me.proton.core.account.domain.entity.AccountState.Removed
            }?.userId?.id
            ?: eu.akoos.photos.data.db.entity.PhotoLocationEntity.LOCAL_USER
                .takeIf { app.continueWithoutAccount.first() }
        for ((userId, step) in cleanup.replayPlan(pending, present, cleanup.runningNow())) {
            when (step) {
                eu.akoos.photos.util.SignOutReplay.RUN -> cleanUpAfterSignOut(app, policy, UserId(userId), persist = true)
                eu.akoos.photos.util.SignOutReplay.DROP -> signOutStep { cleanup.clearPending(app, userId) }
                eu.akoos.photos.util.SignOutReplay.SKIP -> Unit
            }
        }
    }

    /** The app lock over the whole screen; see [AppLockGate]. */
    private val lockGate by lazy { AppLockGateState(lockSession) }
    /** Photo URI from a home-screen widget tap; NavGraph reads + clears it to route to the viewer. */
    private var widgetPhotoUri by mutableStateOf<String?>(null)
    /** Request from a system "Open with" / "Edit with" intent; NavGraph routes to the editor. */
    private var externalEditRequest by mutableStateOf<ExternalEditRequest?>(null)
    /** Another app asking for a photo; NavGraph opens the album picker and hands the picks back here. */
    private var externalPickRequest by mutableStateOf<eu.akoos.photos.presentation.picker.PickRequest?>(null)
    private var externalPickWorking by mutableStateOf(false)

    /** Foreground-resume silent refresh fires only after this long since the last successful sync. */
    // Short floor (not minutes) because the on-resume refresh is now a cheap newest-page poll for
    // accounts without an event anchor, so it can run on almost every return without a battery cost.
    private val resumeRefreshThresholdMs = 60L * 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        // Follow the real OS light/dark setting for THIS activity, so Compose's isSystemInDarkTheme()
        // reports it truthfully under the "System" theme. The app-wide default stays NIGHT_YES
        // (App.applyStoredThemeMode) to keep the XML ProtonCore login screens dark; this local
        // override only affects MainActivity, whose every surface is themed by Compose.
        delegate.setLocalNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)
        authOrchestrator.register(this)
        enableEdgeToEdge()

        // Widget-tap URI from the launching intent (mirrored in onNewIntent for a foregrounded re-tap).
        widgetPhotoUri = intent?.getStringExtra(EXTRA_WIDGET_PHOTO_URI)
        externalEditRequest = parseExternalEditRequest(intent)
        externalPickRequest = parseExternalPickRequest(intent)

        // ProtonCore account-state handler. Without it, 2FA / two-pass accounts stall after the
        // password: LoginActivity closes on first-factor success but nothing observes the resulting
        // SecondFactorNeeded / TwoPassModeNeeded state. These observers open the right workflow.
        val app = applicationContext
        val policy = vaultSignOut
        accountManager.observe(this.lifecycle, Lifecycle.State.CREATED)
            .onAccountTwoPassModeNeeded { authOrchestrator.startTwoPassModeWorkflow(it) }
            .onAccountTwoPassModeFailed {
                lifecycleScope.launch { disableUnfinishedSignIn(it.userId) }
            }
            .onAccountCreateAddressNeeded { authOrchestrator.startChooseAddressWorkflow(it) }
            .onAccountCreateAddressFailed {
                lifecycleScope.launch { disableUnfinishedSignIn(it.userId) }
            }
            .onSessionSecondFactorNeeded { authOrchestrator.startSecondFactorWorkflow(it) }
            .onSessionSecondFactorFailed {
                lifecycleScope.launch { disableUnfinishedSignIn(it.userId) }
            }
            .onSessionForceLogout {
                lifecycleScope.launch { accountManager.disableAccount(it.userId) }
            }
            // initialState = false is load-bearing. The default replays the state on every
            // subscription, and the app only ever disables an account rather than removing it, so the
            // disabled row lives on and this block ran again on each Activity creation: a cold start,
            // a process death, or the system flipping to dark mode. Almost nothing below is scoped to
            // the account named here, so those repeats landed on whoever was signed in by then, down
            // to emptying the hidden area, whose copy is the only one a device-only photo has.
            .onAccountDisabled(initialState = false) {
                // Every sign-out path converges here (explicit sign-out, force-logout, 2FA /
                // key-check failures). Wipe this account's cached rows, plaintext key material and
                // decrypted thumbnails so a revoked or re-authed session leaves nothing resident.
                // NavGraph already routes to login once isLoggedIn = false.
                // The stored state goes with them. The comment above is right that every path
                // converges here, but only the cached rows and key material were being cleared: the
                // folder selection, album mapping, hidden folder names and queued Drive cleanups are
                // preferences, and they stayed for whoever signed in next.
                val persist = !eu.akoos.photos.util.SignOutCleanup.consumeUnfinishedSignIn(it.userId.id)
                appScope.launch { cleanUpAfterSignOut(app, policy, it.userId, persist) }
            }
            .onUserKeyCheckFailed { /* corrupt user key — best to just disable and re-login */ }
            .onUserAddressKeyCheckFailed { /* same */ }

        appScope.launch { replayUnfinishedSignOutCleanups(app, policy) }

        lifecycleScope.launch {
            // Login gate: don't arm the BG "Watching for new photos" notification before sign-in,
            // or a never-completed install shows a permanent notif for an app doing no work.
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) return@launch

            val prefs = settingsDataStore.data.first()
            val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false
            // Arm (or, if sync is effectively off, tear down) the three triggers in one place:
            // content-URI (sub-second on stock Android), periodic 15-min (OEM-throttle safety net),
            // and the opt-in BG service (keeps the observer alive on Samsung One UI). Gated so
            // auto-sync ON with no folder selected arms nothing.
            SyncWorker.reconcileBackgroundWork(this@MainActivity)
            // Opt-in screenshot quick-action overlay: a specialUse foreground service cannot be
            // launched from boot on Android 14+, so re-arm it here on launch when it is enabled and
            // the draw-over-other-apps grant is still in place. start() also self-guards the grant.
            if (prefs[SettingsKeys.SCREENSHOT_OVERLAY_ENABLED] == true &&
                android.provider.Settings.canDrawOverlays(this@MainActivity)
            ) {
                eu.akoos.photos.service.ScreenshotOverlayService.start(this@MainActivity)
            }
            // Kick a OneTime run now so pending uploads start at launch; APPEND_OR_REPLACE coalesces
            // with the periodic run. Only when backup will actually upload.
            if (syncEffectivelyEnabled(this@MainActivity)) {
                SyncWorker.runNow(this@MainActivity, wifiOnly)
            }
            // Reclaim eligible device copies on launch too, not just on the hourly worker an OEM
            // doze can delay for hours. Same age gate; a sweep with no eligible photo is cheap.
            if (prefs[SettingsKeys.AUTO_FREE_UP] == true) {
                val freeUpInterval = FreeUpInterval.fromKey(prefs[SettingsKeys.FREE_UP_INTERVAL])
                FreeUpSpaceWorker.runNow(WorkManager.getInstance(this@MainActivity), freeUpInterval.ms)
            }
        }

        // Early cloud-photo refresh once a primary user resolves, so thumbnails are landing by the
        // time the gallery appears. setGentleSync(true) paces the decrypt burst slowly under the
        // heavy first screen; GalleryViewModel flips it off when visible. The walk is single-
        // flighted and runs on the app scope, so the later syncOnLaunch refresh just awaits this
        // one, and backgrounding the Activity no longer abandons it midway. Gated on the same
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

        // The lock settings settle a cold start here, and every later change; returns from the background
        // are decided in onStart.
        lockGate.followSettings(this)

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
            val themeKey by themeKeyFlow.collectAsState(initial = ThemePrefsBoot.read(this@MainActivity))
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

            // AMOLED pure-black switch — DataStore-backed, re-collected so it applies live.
            val amoledFlow = remember {
                settingsDataStore.data.map { it[SettingsKeys.AMOLED_BLACK] == true }
            }
            val amoledBlack by amoledFlow.collectAsState(initial = false)

            // Tint the green "backed up" cloud badges with the palette accent — DataStore-backed, live.
            val tintCloudFlow = remember {
                settingsDataStore.data.map { it[SettingsKeys.TINT_CLOUD_WITH_ACCENT] == true }
            }
            val tintCloudWithAccent by tintCloudFlow.collectAsState(initial = false)

            // GIF autoplay in grids / on album covers, DataStore-backed, re-collected so they apply live.
            val gifAutoplayGridFlow = remember {
                settingsDataStore.data.map { it[SettingsKeys.GIF_AUTOPLAY_GRID] == true }
            }
            val gifAutoplayGrid by gifAutoplayGridFlow.collectAsState(initial = false)
            val gifAutoplayCoversFlow = remember {
                settingsDataStore.data.map { it[SettingsKeys.GIF_AUTOPLAY_COVERS] == true }
            }
            val gifAutoplayCovers by gifAutoplayCoversFlow.collectAsState(initial = false)

            // The animated-decoder-free Coil loader, exposed to grids/covers that render a GIF as a still.
            val staticImageLoader = remember { (application as App).staticImageLoader }

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

            // The clock choice is independent of the language; the formatters outside a composition read it
            // from ClockFormatState, so it is refreshed here as it changes.
            val clockFlow = remember {
                settingsDataStore.data.map { prefs -> ClockFormat.fromKey(prefs[SettingsKeys.CLOCK_FORMAT]) }
            }
            val clockFormat by clockFlow.collectAsState(initial = ClockFormat.AUTO)
            val phoneIs24Hour = phoneUses24Hour(this@MainActivity)
            val is24Hour = clockFormat.is24Hour(phoneIs24Hour)
            ClockFormatState.update(clockFormat, phoneIs24Hour)

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
                ProtonPhotosTheme(
                    darkTheme = useDark,
                    palette = palette,
                    amoledBlack = amoledBlack,
                    tintCloudWithAccent = tintCloudWithAccent,
                    gifAutoplayGrid = gifAutoplayGrid,
                    gifAutoplayCovers = gifAutoplayCovers,
                ) {
                  CompositionLocalProvider(
                      LocalStaticImageLoader provides staticImageLoader,
                      LocalIs24Hour provides is24Hour,
                  ) {
                    val forceUpdateFlow = remember {
                        settingsDataStore.data.map { it[FORCE_UPDATE_REQUIRED] == true }
                    }
                    val forceUpdate by forceUpdateFlow.collectAsState(initial = false)

                    when {
                        forceUpdate -> ForceUpdateDialog()
                        else -> {
                            // The lock gate keeps the nav graph composed under the lock rather than
                            // swapping it out: out of composition it would lose its NavController and
                            // back stack, and unlocking would land on the start destination instead of
                            // where the user was. The lock shows in a window of its own above every
                            // dialog and sheet, the content under it stays covered and inert, and back
                            // leaves the app instead of reaching it.
                            AppLockGate(state = lockGate, onLeave = { leaveWhileLocked() }) {
                                NavGraph(
                                    onStartLogin = { authOrchestrator.startLoginWorkflow(null) },
                                    onCheckForUpdates = { runManualUpdateCheck() },
                                    widgetPhotoUri = widgetPhotoUri,
                                    onWidgetPhotoConsumed = { widgetPhotoUri = null },
                                    externalEditRequest = externalEditRequest,
                                    onExternalEditConsumed = { externalEditRequest = null },
                                    externalPickRequest = externalPickRequest,
                                    externalPickWorking = externalPickWorking,
                                    onExternalPickDone = ::answerExternalPick,
                                    onExternalPickCancel = { finish() },
                                )
                            }
                            // An update prompt would open inside the app that asked for a photo.
                            UpdaterHost(promptAllowed = !lockGate.isInert && externalPickRequest == null)
                            if (lockGate.isInert) LockBackInterceptor()
                        }
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
     * The prompt is a window of its own and would open above the app lock, so it waits for the
     * unlock ([promptAllowed]); the update itself carries on meanwhile.
     */
    @androidx.compose.runtime.Composable
    private fun UpdaterHost(promptAllowed: Boolean) {
        val current by updateOrchestrator.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        // Back from the "Install unknown apps" permission screen — fire the install immediately if
        // the OS now allows it, so the user needn't tap Update again.
        val installLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (updateOrchestrator.pendingInstallFile() != null && updateInstaller.canInstall()) {
                startPendingInstall()
            }
        }
        // Terminal result of a committed install session. A confirmation screen is launched from
        // here (an Activity already has a task); a failed session drops to the legacy intent. Either
        // screen would open above the app lock, so it waits for the unlock.
        LaunchedEffect(Unit) {
            installSessionEvents.outcomes.collect { outcome ->
                when (outcome) {
                    is InstallOutcome.PendingUserAction ->
                        lockGate.runWhenOpen { runCatching { startActivity(outcome.intent) } }
                    is InstallOutcome.Failed -> lockGate.runWhenOpen { launchLegacyInstall() }
                    else -> Unit
                }
            }
        }
        current?.takeIf { promptAllowed }?.let { state ->
            UpdatePromptDialog(
                state = state,
                onUpdate = {
                    when (state) {
                        is UpdatePromptState.Available -> updateOrchestrator.confirmUpdate(scope)
                        is UpdatePromptState.InstallReady -> {
                            if (updateOrchestrator.pendingInstallFile() != null) {
                                if (!updateInstaller.canInstall()) {
                                    runCatching {
                                        installLauncher.launch(updateInstaller.buildPermissionRequestIntent())
                                    }
                                } else {
                                    startPendingInstall()
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
     * While the lock or its pending cover shows, back leaves the app instead of reaching a screen behind
     * it. From API 33 an overlay-priority callback outranks every callback the screens register, before
     * or after it; below that, [onBackPressed] sees the back key first.
     */
    @androidx.compose.runtime.Composable
    private fun LockBackInterceptor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            DisposableEffect(Unit) {
                val callback = OnBackInvokedCallback { leaveWhileLocked() }
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    callback,
                )
                onDispose { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
            }
        }
    }

    /** Sends the app to the background, or closes an instance another app opened in its own task. */
    private fun leaveWhileLocked() {
        if (isTaskRoot) moveTaskToBack(true) else finish()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (lockGate.isInert) leaveWhileLocked() else super.onBackPressed()
    }

    /**
     * Runs the staged update through the silent install session. A refused install (wrong signer,
     * not newer) already shows its own dialog error, so only a plumbing failure falls back.
     */
    private fun startPendingInstall() {
        when (val outcome = updateOrchestrator.installPending()) {
            is InstallOutcome.Failed -> launchLegacyInstall()
            is InstallOutcome.PendingUserAction -> runCatching { startActivity(outcome.intent) }
            else -> Unit
        }
    }

    /** Last resort: hand the APK to the system installer through the FileProvider intent. */
    private fun launchLegacyInstall() {
        val file = updateOrchestrator.pendingInstallFile() ?: return
        runCatching { startActivity(updateInstaller.buildInstallIntent(file)) }
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
     * The photo request another app makes with GET_CONTENT or PICK, or null for any other intent. This
     * screen is exported for the launcher, so a pick sent to it directly is answered only while the
     * user has the picker switched on, the same as one that came through its alias.
     */
    private fun parseExternalPickRequest(intent: Intent?): eu.akoos.photos.presentation.picker.PickRequest? {
        intent ?: return null
        if (!eu.akoos.photos.presentation.picker.isPickAction(intent.action)) return null
        if (!eu.akoos.photos.presentation.picker.ExternalPicker.isEnabled(this)) return null
        return eu.akoos.photos.presentation.picker.pickRequestOf(intent)
    }

    /** Hands [picks] back to the asking app and closes, or says why none of them can go and stays open. */
    private fun answerExternalPick(picks: List<eu.akoos.photos.domain.entity.GalleryItem>) {
        val request = externalPickRequest ?: return
        if (picks.isEmpty() || externalPickWorking) return
        externalPickWorking = true
        lifecycleScope.launch {
            val answer = externalPickHandover.answer(request, picks)
            externalPickWorking = false
            val why = when (answer) {
                is eu.akoos.photos.presentation.picker.PickAnswer.Ready -> {
                    setResult(RESULT_OK, answer.intent)
                    finish()
                    return@launch
                }
                is eu.akoos.photos.presentation.picker.PickAnswer.WrongKind ->
                    if (answer.videosOnly) R.string.picker_only_videos else R.string.picker_only_photos
                eu.akoos.photos.presentation.picker.PickAnswer.OnlyHidden -> R.string.picker_hidden_not_handed
                eu.akoos.photos.presentation.picker.PickAnswer.Failed -> R.string.picker_prepare_failed
            }
            Toast.makeText(this@MainActivity, getString(why), Toast.LENGTH_SHORT).show()
        }
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
        if (!isAcceptableExternalEditUri(uri.scheme, uri.authority, packageName)) return null
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

    // Feed the device-health policy every touch so heavy on-device work stands aside while the user
    // is actively using the app, then resumes shortly after they stop.
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        deviceHealth.markInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        // Synchronous, so the first frame after a return already carries the lock.
        lockGate.onStart()
    }

    override fun onStop() {
        super.onStop()
        // A configuration change (dark mode, font scale, a split-screen resize) recreates this screen in the
        // foreground, which is not leaving the app, so "Lock immediately" must not count it as one.
        if (!isChangingConfigurations) lockGate.onStop()
    }

    override fun onPause() {
        super.onPause()
        lockGate.onPause(this)
    }

    override fun onResume() {
        super.onResume()
        lockGate.onResume()
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
                val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false
                // Lightweight pair: events-based Drive delta, then one SyncState pass.
                driveRepo.refreshCloudPhotosIncremental(userId)
                reconcile(userId).collect {}
                // OneTime sync if backup is effectively on — picks up LOCAL_ONLY entries reconcile
                // just flagged. Skipped when no folder is selected (nothing would upload).
                if (syncEffectivelyEnabled(this@MainActivity)) SyncWorker.runNow(this@MainActivity, wifiOnly)
            } catch (_: Exception) {
                // Silent — onResume must never crash; the next refresh or SyncWorker tick retries.
            }
        }
        // Silent in-app update check. The repository caches the result for 4h, so this is a no-op
        // on most resumes; when a newer GitHub release exists it surfaces the update dialog (and,
        // after "Not now", the dismissable gallery banner) through UpdateOrchestrator. This is the
        // foreground half only: UpdateCheckWorker runs the same check on its own schedule so a
        // release still gets noticed while the app is closed.
        lifecycleScope.launch {
            runCatching { updateOrchestrator.runSilentCheck() }
        }
        // Refresh the news feed on the same resume, so the unread dot is current the moment the user
        // is looking. A no-op when news is switched off, and a dropped network keeps the last feed.
        lifecycleScope.launch {
            runCatching { newsRepository.refresh() }
        }
    }

    companion object {
        /** Intent extra: MediaStore URI string from a home-screen widget tap. */
        const val EXTRA_WIDGET_PHOTO_URI = "widget_photo_uri"
    }
}
