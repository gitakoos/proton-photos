package me.proton.photos

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.proton.photos.data.preferences.SettingsKeys
import me.proton.photos.data.preferences.ThemePrefsBoot
import me.proton.photos.data.preferences.settingsDataStore
import me.proton.photos.worker.AlbumDownloadWorker
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
        AppCompatDelegate.setDefaultNightMode(
            when (cached) {
                "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
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

    /**
     * Custom Coil ImageLoader registered through [ImageLoaderFactory] (Coil 2 discovers it via
     * the Application). Adds [VideoFrameDecoder] so video URIs render a poster frame in the
     * gallery grid and filmstrip just like image URIs do.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
