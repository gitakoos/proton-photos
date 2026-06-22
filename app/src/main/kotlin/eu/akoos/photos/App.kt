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

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import androidx.work.WorkManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import eu.akoos.photos.data.preferences.LanguagePrefsBoot
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.ThemePrefsBoot
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.worker.AlbumDownloadWorker
import eu.akoos.photos.worker.CachePruneWorker
import eu.akoos.photos.worker.SyncWorker
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var networkObserver: eu.akoos.photos.util.NetworkObserver

    @Inject
    lateinit var photoListingDao: eu.akoos.photos.data.db.dao.PhotoListingDao

    // Set up in constructor so getWorkManagerConfiguration() works before Hilt injects workerFactory.
    private val delegatingFactory = DelegatingWorkerFactory()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(delegatingFactory)
            .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Disable Go's SIGURG goroutine preemption before libgojni loads: its signals collide
        // with the platform's userfaultfd GC and SIGABRT the process when two JNI threads enter
        // the Go runtime at once. Must be an OS env var (Go reads getenv at init, not JVM props)
        // set in the earliest app hook before any crypto class loads; guarded as setenv can throw.
        runCatching { android.system.Os.setenv("GODEBUG", "asyncpreemptoff=1", true) }
    }

    override fun onCreate() {
        super.onCreate() // Hilt injects workerFactory here
        // The :crypto process shares this Application but must skip the main-process init below
        // (WorkManager schedules, lifecycle observer, receivers, Coil) to avoid duplicate workers.
        if (!isMainProcess()) return
        delegatingFactory.addFactory(workerFactory)
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectCleartextNetwork()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .penaltyLog()
                    .build()
            )
        }
        // osmdroid requires a unique user-agent for the OSM tile-server fair-use policy; the
        // default "osmdroid" string is rate-limited. Set once here before any MapView mounts.
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = BuildConfig.APPLICATION_ID
            // Cap the on-disk tile cache (default ~600MB) and keep cached tiles valid for 30 days so
            // pre-cached photo regions stay usable offline rather than expiring after the default day.
            tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 180L * 1024 * 1024
            expirationOverrideDuration = 1000L * 60 * 60 * 24 * 30
        }
        // Apply theme/locale to AppCompatDelegate so externally-launched ProtonCore login/payment
        // Activities (XML-based, outside our Compose tree) honour them too.
        applyStoredThemeMode()
        applyStoredLanguage()
        // Register channels eagerly so the first foreground-promoted Worker doesn't race channel
        // creation (Android 8+). Idempotent.
        AlbumDownloadWorker.ensureChannel(this)
        registerUserPresentReceiver()
        scheduleFullResCachePrune()
        // Periodic sweeper for the "process killed for days, cache still on disk" gap the cold-start
        // prune above can't reach.
        CachePruneWorker.schedule(WorkManager.getInstance(this))
        seedAlbumOptInFromBucketMap()
        registerCacheCleanupOnBackground()
    }

    /** True only in the main app process (the :crypto process runs as "$packageName:crypto"). */
    private fun isMainProcess(): Boolean {
        val procName = if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager)
                .runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }
                ?.processName
        }
        return procName == packageName
    }

    /**
     * One-shot seed for [SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES]: copies existing
     * [SettingsKeys.ALBUM_BUCKET_MAP] bucket names into the opt-in set so installs already
     * mirroring folders keep doing so once the toggle becomes user-visible. Gated on
     * [SettingsKeys.ALBUM_OPT_IN_MIGRATED].
     */
    private fun seedAlbumOptInFromBucketMap() {
        appScope.launch {
            runCatching {
                val prefs = settingsDataStore.data.first()
                if (prefs[SettingsKeys.ALBUM_OPT_IN_MIGRATED] == true) return@runCatching
                val mapEntries = prefs[SettingsKeys.ALBUM_BUCKET_MAP].orEmpty()
                val existingBucketNames = mapEntries
                    .mapNotNull { it.substringBefore('=', missingDelimiterValue = "").takeIf { name -> name.isNotEmpty() } }
                    .toSet()
                settingsDataStore.edit { p ->
                    p[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] = existingBucketNames
                    p[SettingsKeys.ALBUM_OPT_IN_MIGRATED] = true
                }
                Log.d("AlbumOptInMigration", "Seeded album opt-in list with ${existingBucketNames.size} folders from ALBUM_BUCKET_MAP")
            }
        }
    }

    /**
     * One-shot TTL prune for the full-res cache, deferred ~5 s so cold-start IO goes to the
     * gallery first. No-op when offline so cached photos stay viewable until the network returns.
     */
    private fun scheduleFullResCachePrune() {
        appScope.launch {
            delay(5_000L)
            runCatching {
                eu.akoos.photos.data.repository.drive.PhotoDownloadService.pruneStaleFullResCache(
                    context = this@App,
                    networkAvailable = networkObserver.isOnline.value,
                )
            }
        }
    }

    /**
     * Process-lifetime [Intent.ACTION_USER_PRESENT] receiver — kicks a sync on unlock so
     * lock-screen captures (which the gallery's per-Activity observer misses while closed) back
     * up without reopening the app. Must be a runtime registration: manifest receivers can't
     * observe ACTION_USER_PRESENT on Android 8+.
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
        // Must be exported: ACTION_USER_PRESENT comes from systemui (a different UID), and
        // RECEIVER_NOT_EXPORTED silently drops it so the receiver looks armed but never fires.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    /**
     * Reads the cached theme key from a SharedPreferences mirror (< 1 ms, no IO hop) and applies it
     * to AppCompatDelegate, avoiding a main-thread `runBlocking { DataStore.data.first() }` that
     * could stall for tens to hundreds of ms on cold cache. DataStore (`SettingsKeys.THEME_MODE`)
     * is canonical; the mirror is written on every theme write and refreshed below for drift.
     */
    private fun applyStoredThemeMode() {
        val cached = ThemePrefsBoot.read(this)
        // AppCompatDelegate night mode governs ProtonCore's login screens (they read uiMode via
        // isSystemInDarkTheme). Force NIGHT_YES except an explicit "light" pick — the palette is
        // dark-built and a "system" default made login appear light on light-system OEM phones.
        // The in-app Compose ProtonPhotosTheme still honours the full system/dark/light choice.
        AppCompatDelegate.setDefaultNightMode(
            when (cached) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else    -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
        // Background drift-sync of the mirror from DataStore (e.g. first boot before any theme write).
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
                // Don't re-apply live (would flash an activity recreate); corrected value lands next cold start.
            }
        }
    }

    /**
     * Applies the cached language tag (SharedPreferences mirror of DataStore `SettingsKeys.LANGUAGE`)
     * to AppCompatDelegate once at startup. One-shot because `setApplicationLocales` after startup
     * forces an Activity recreate that loses nav state; runtime switches go through `LocaleOverride`
     * in Compose, so this only covers the XML-based ProtonCore login Activities.
     */
    private fun applyStoredLanguage() {
        val cached = LanguagePrefsBoot.read(this)
        val desired = if (cached == "system" || cached.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(cached)
        }
        // Skip the call if the framework already has the right locale — setApplicationLocales
        // triggers an Activity recreate and persists across process death, so this is the steady state.
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.toLanguageTags() != desired.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
        // Background drift-sync of the mirror from DataStore (same as applyStoredThemeMode).
        appScope.launch {
            val fromDataStore = runCatching {
                settingsDataStore.data.map { prefs ->
                    prefs[SettingsKeys.LANGUAGE] ?: "system"
                }.first()
            }.getOrNull() ?: return@launch
            if (fromDataStore != cached) {
                LanguagePrefsBoot.write(this@App, fromDataStore)
                // Don't re-apply live — would force the Activity recreate this avoids; lands next cold start.
            }
        }
    }

    /**
     * Privacy opt-in ([SettingsKeys.CLEAR_CACHE_ON_APP_CLOSE]): wipe disk caches when the whole
     * process backgrounds. Process-level ON_STOP is the right hook — it fires only when all
     * Activities leave the started state, not on rotation / picker round-trips. Wipes fullres,
     * thumbnails, and coil_cache; deliberately leaves in-flight upload block dirs and DataStore alone.
     */
    private fun registerCacheCleanupOnBackground() {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    appScope.launch {
                        val enabled = runCatching {
                            settingsDataStore.data.first()[SettingsKeys.CLEAR_CACHE_ON_APP_CLOSE] == true
                        }.getOrDefault(false)
                        if (!enabled) return@launch
                        runCatching {
                            listOf(
                                "fullres", "thumbnails", "coil_cache",
                            ).forEach { sub ->
                                java.io.File(cacheDir, sub).deleteRecursively()
                            }
                            // Null the DB thumbnail paths too, else the scheduler skips the now-missing
                            // files as "done" instead of re-requesting a decrypt next launch.
                            photoListingDao.clearCachedThumbnailUrls()
                        }
                    }
                }
            }
        )
    }

    // Coil ImageLoader. VideoFrameDecoder for video posters; the animated decoder plays GIFs and
    // widens HEIF/AVIF coverage (ImageDecoderDecoder on API 28+, GifDecoder below). Memory cache is
    // capped well under the largeHeap 25% default, which balloons past 400 MB and made scrolling laggy.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(VideoFrameDecoder.Factory())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(coil.decode.ImageDecoderDecoder.Factory())
            } else {
                add(coil.decode.GifDecoder.Factory())
            }
        }
        .crossfade(true)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.18)
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
