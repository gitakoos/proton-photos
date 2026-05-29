package eu.akoos.photos

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.ThemePrefsBoot
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.worker.AlbumDownloadWorker
import eu.akoos.photos.worker.SyncWorker
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // Initialized in constructor so getWorkManagerConfiguration() never crashes even
    // when called by startup initializers before Hilt injects workerFactory.
    private val delegatingFactory = DelegatingWorkerFactory()

    /** App-scoped scope for fire-and-forget tasks (theme mirror sync, etc.). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(delegatingFactory)
            .build()

    override fun onCreate() {
        super.onCreate() // Hilt injects workerFactory here
        delegatingFactory.addFactory(workerFactory)
        // Apply the user's theme to AppCompatDelegate so externally-launched Activities
        // (ProtonCore login/payment screens with their own DayNight XML theme) honour it too.
        applyStoredThemeMode()
        // Register notification channels eagerly so the first foreground-promoted Worker
        // run doesn't race with channel creation (Android 8+). Idempotent — safe to call
        // every cold start.
        AlbumDownloadWorker.ensureChannel(this)
        registerUserPresentReceiver()
    }

    /**
     * Process-lifetime receiver for [Intent.ACTION_USER_PRESENT] — fires when the user
     * unlocks the device. Lock-screen photos (camera with quick-launch, screenshots taken
     * while locked) flow into MediaStore but the per-Activity ContentObserver in
     * LocalMediaRepositoryImpl is only registered while the gallery is open. Without this
     * receiver the user has to open the app to kick a sync after unlock — exactly the
     * "I unlocked but nothing uploaded until I opened the app" symptom users reported.
     * Static manifest receivers can't observe ACTION_USER_PRESENT on Android 8+, so this
     * has to be a runtime registration on the Application context.
     */
    private fun registerUserPresentReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_PRESENT) return
                appScope.launch {
                    val prefs = runCatching { settingsDataStore.data.first() }.getOrNull() ?: return@launch
                    val autoSync = prefs[SettingsKeys.AUTO_SYNC] != false
                    if (!autoSync) return@launch
                    val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false
                    Log.d("App", "ACTION_USER_PRESENT — kicking SyncWorker.runNow")
                    SyncWorker.runNow(this@App, wifiOnly)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        // ACTION_USER_PRESENT is sent by com.android.systemui (a different UID), so the
        // receiver MUST be exported. RECEIVER_NOT_EXPORTED silently drops the broadcast
        // with an "Exported Denial" in the broadcast log — the receiver looks armed via
        // dumpsys but never fires on unlock.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    /**
     * Reads the cached theme key from a tiny SharedPreferences mirror (microseconds, no IO
     * scheduler hop) and applies it to AppCompatDelegate. The canonical store is DataStore
     * (`SettingsKeys.THEME_MODE`); the SharedPreferences side is kept in sync on every
     * theme write in `SettingsViewModel` and refreshed in the background here in case the
     * canonical version drifted (e.g. fresh install before the first theme write).
     *
     * Trade-off rationale: the previous implementation `runBlocking { DataStore.data.first() }`
     * could block the main thread for tens of ms (cold-cache IO, DataStore migration), and on
     * worst-case devices for hundreds. SharedPreferences-backed reads complete in < 1 ms.
     */
    private fun applyStoredThemeMode() {
        val cached = ThemePrefsBoot.read(this)
        // The AppCompatDelegate night-mode setting governs ProtonCore's Compose-based login
        // screens (LoginTwoStepActivity etc.) because they read Configuration.uiMode via
        // `isSystemInDarkTheme()`. We force NIGHT_YES for every value except an explicit
        // "light" preference — the app's color palette is built for dark surfaces, and the
        // previous "system" default made the login flow appear light on light-system phones
        // (S22, default Samsung). The Compose-side ProtonPhotosTheme in MainActivity still
        // respects the user's full system/dark/light choice for the in-app surfaces, so a
        // user who explicitly picks "system" after first login still gets a system-following
        // main app — the login flow just doesn't bounce between modes.
        AppCompatDelegate.setDefaultNightMode(
            when (cached) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else    -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
        // Refresh the mirror from DataStore in the background — handles two cases:
        //  - First boot with no SharedPreferences entry yet (we just used "system"; DataStore
        //    may already hold the migrated DARK_MODE value).
        //  - DataStore was modified by a non-SettingsViewModel path (defensive sync).
        appScope.launch {
            val fromDataStore = runCatching {
                settingsDataStore.data.map { prefs ->
                    prefs[SettingsKeys.THEME_MODE] ?: when (prefs[SettingsKeys.DARK_MODE]) {
                        true  -> "dark"
                        false -> "light"
                        null  -> "system"
                    }
                }.first()
            }.getOrNull() ?: return@launch
            if (fromDataStore != cached) {
                ThemePrefsBoot.write(this@App, fromDataStore)
                // Note: don't re-apply on the fly — would cause activity recreate flash. The
                // current visible theme stays; the corrected value will apply on next cold start.
            }
        }
    }

    // Coil ImageLoader. VideoFrameDecoder for video posters. Memory cache capped at 12%
    // (largeHeap default of 25% balloons past 400 MB and made scrolling laggy).
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.12)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("coil_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()
}
