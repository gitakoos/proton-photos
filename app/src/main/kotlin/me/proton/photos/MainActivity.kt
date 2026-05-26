package me.proton.photos

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.auth.presentation.AuthOrchestrator
import me.proton.photos.data.api.FORCE_UPDATE_REQUIRED
import me.proton.photos.data.preferences.SettingsKeys
import me.proton.photos.data.preferences.settingsDataStore
import me.proton.photos.domain.repository.DrivePhotoRepository
import me.proton.photos.domain.usecase.ReconcileSyncStateUseCase
import me.proton.photos.navigation.NavGraph
import me.proton.photos.presentation.lock.AppLockManager
import me.proton.photos.presentation.lock.AppLockScreen
import me.proton.photos.presentation.settings.ThemeMode
import me.proton.photos.presentation.theme.ProtonPhotosTheme
import me.proton.photos.worker.SyncWorker
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var authOrchestrator: AuthOrchestrator
    @Inject lateinit var workManager: WorkManager
    @Inject lateinit var appLockManager: AppLockManager
    @Inject lateinit var accountManager: AccountManager
    @Inject lateinit var driveRepo: DrivePhotoRepository
    @Inject lateinit var reconcile: ReconcileSyncStateUseCase

    private var isLocked by mutableStateOf(false)
    private var lockEnabled = false
    private var wentToBackground = false

    /** Foreground-resume guard: silent refresh fires only when this much time has passed since
     *  the last successful sync. Mirrors the threshold the user sees as "fresh enough". */
    private val resumeRefreshThresholdMs = 5L * 60L * 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authOrchestrator.register(this)
        enableEdgeToEdge()

        lifecycleScope.launch {
            val prefs = settingsDataStore.data.first()
            val autoSync = prefs[SettingsKeys.AUTO_SYNC] != false
            val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false
            val intervalMinutes = prefs[SettingsKeys.SYNC_INTERVAL_MINUTES] ?: 15L
            if (autoSync) {
                SyncWorker.schedule(workManager, wifiOnly, intervalMinutes)
                // Don't wait 15 minutes for the first periodic fire — kick off a OneTime run
                // immediately so any pending uploads (including newly-imported photos) start
                // backing up the moment the app launches. KEEP-policy enqueue inside
                // SyncWorker.runNow coalesces with the periodic run if it's already active.
                SyncWorker.runNow(this@MainActivity, wifiOnly)
            }
        }

        // Observe lock setting changes
        lifecycleScope.launch {
            appLockManager.isLockEnabled.collect { enabled ->
                lockEnabled = enabled
                if (enabled && savedInstanceState == null) isLocked = true
            }
        }

        // Lock the app when it comes back from background
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (wentToBackground && lockEnabled) {
                    isLocked = true
                }
                wentToBackground = false
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
                            null  -> "system"
                        }
                }
            }
            val themeKey by themeKeyFlow.collectAsState(initial = "system")
            val themeMode = ThemeMode.fromKey(themeKey)
            val systemDark = isSystemInDarkTheme()
            val useDark = when (themeMode) {
                ThemeMode.System -> systemDark
                ThemeMode.Light  -> false
                ThemeMode.Dark   -> true
            }

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

            ProtonPhotosTheme(darkTheme = useDark) {
                val forceUpdateFlow = remember {
                    settingsDataStore.data.map { it[FORCE_UPDATE_REQUIRED] == true }
                }
                val forceUpdate by forceUpdateFlow.collectAsState(initial = false)

                when {
                    forceUpdate -> ForceUpdateDialog()
                    isLocked -> AppLockScreen(onUnlocked = { isLocked = false })
                    else -> NavGraph(
                        onStartLogin = { authOrchestrator.startLoginWorkflow(null) },
                    )
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ForceUpdateDialog() {
        Box(Modifier.fillMaxSize()) {
            AlertDialog(
                onDismissRequest = { /* non-dismissible */ },
                title = { Text("Update required") },
                text = { Text("This version is no longer supported. Please update to continue.") },
                confirmButton = {
                    TextButton(onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            "market://details?id=$packageName".toUri(),
                        )
                        runCatching { startActivity(intent) }
                        finish()
                    }) { Text("Update") }
                },
            )
        }
    }

    override fun onStop() {
        super.onStop()
        wentToBackground = true
    }

    override fun onResume() {
        super.onResume()
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
}
