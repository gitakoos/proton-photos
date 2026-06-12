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

    // Initialized in constructor so getWorkManagerConfiguration() never crashes even
    // when called by startup initializers before Hilt injects workerFactory.
    private val delegatingFactory = DelegatingWorkerFactory()

    /** App-scoped scope for fire-and-forget tasks (theme mirror sync, etc.). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(delegatingFactory)
            .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Disable Go's signal-based goroutine preemption (SIGURG) in the native crypto
        // runtime before its shared library loads. The Go GC's preemption signals can
        // collide with the platform's userfaultfd-based GC, aborting the process with a
        // SIGABRT when two JNI threads call into the Go runtime concurrently. This env var
        // is read by the Go runtime via libc getenv at init, so it must be set in the
        // process environment here, the earliest app hook, before any crypto class loads.
        // It only affects preemptibility of tight call-free loops, which the crypto code
        // does not have, so throughput is unaffected. A JVM system property would not work
        // (the runtime reads the OS environment, not JVM properties). setenv can throw
        // ErrnoException, so guard it — failing to set it must never crash startup.
        runCatching { android.system.Os.setenv("GODEBUG", "asyncpreemptoff=1", true) }
    }

    override fun onCreate() {
        super.onCreate() // Hilt injects workerFactory here
        delegatingFactory.addFactory(workerFactory)
        // StrictMode in debug only. detectCleartextNetwork() trips a violation the
        // moment any plain HTTP socket opens - belt and suspenders to the manifest
        // network security config which rejects cleartext at the TLS layer. Other
        // VM detectors catch resource leaks during development. Release builds skip
        // this entirely to avoid the perf hit.
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
        // Apply the user's theme to AppCompatDelegate so externally-launched Activities
        // (ProtonCore login/payment screens with their own DayNight XML theme) honour it too.
        applyStoredThemeMode()
        // Apply the user's saved locale to AppCompatDelegate ONCE at process startup so
        // ProtonCore login Activities (XML-based, outside our Compose tree) render in
        // the chosen language. Runtime switches inside the app use LocaleOverride in
        // the Compose tree and intentionally skip this call — calling it after process
        // startup forces an Activity recreate which loses navigation state.
        applyStoredLanguage()
        // Register notification channels eagerly so the first foreground-promoted Worker
        // run doesn't race with channel creation (Android 8+). Idempotent — safe to call
        // every cold start.
        AlbumDownloadWorker.ensureChannel(this)
        registerUserPresentReceiver()
        scheduleFullResCachePrune()
        // Background sweeper covering the "process killed for days, cache still on disk"
        // gap that the one-shot prune in scheduleFullResCachePrune() (only runs on cold
        // start) cannot reach. Idempotent — ExistingPeriodicWorkPolicy.UPDATE means safe
        // to call on every launch.
        CachePruneWorker.schedule(WorkManager.getInstance(this))
        seedAlbumOptInFromBucketMap()
        registerCacheCleanupOnBackground()
    }

    /**
     * One-shot seed for [SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES]. The first launch after
     * this code ships, copies the bucket names already present in [SettingsKeys.ALBUM_BUCKET_MAP]
     * into the opt-in set, so installs that were silently mirroring folders as Drive albums
     * keep mirroring exactly those folders after the toggle becomes user-visible. Gated on
     * [SettingsKeys.ALBUM_OPT_IN_MIGRATED] so subsequent launches skip the work entirely.
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
     * One-shot TTL prune for the full-res cache, deferred ~5 s so cold-start IO budget
     * goes to the gallery first. The sweeper is a no-op when offline so cached photos
     * stay viewable until the network returns.
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
     * Process-lifetime receiver for [Intent.ACTION_USER_PRESENT] — fires when the user
     * unlocks the device. Lock-screen photos (camera with quick-launch, screenshots taken
     * while locked) flow into MediaStore but the per-Activity ContentObserver in
     * LocalMediaRepositoryImpl is only registered while the gallery is open. Without this
     * receiver the user has to open the app to kick a sync after unlock — the
     * observable symptom is that captures taken on the lock screen sit on the device
     * until the next manual app launch.
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
        // (default theme on some OEM builds). The Compose-side ProtonPhotosTheme in MainActivity still
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

    /**
     * Reads the cached language tag from a tiny SharedPreferences mirror and applies it
     * to AppCompatDelegate exactly once at process startup. The canonical store is
     * DataStore (`SettingsKeys.LANGUAGE`); the SharedPreferences side is kept in sync on
     * every language write in `SettingsViewModel` / `OnboardingViewModel` and refreshed
     * in the background here in case the canonical version drifted.
     *
     * Why a one-shot at startup: `setApplicationLocales` triggers an Activity recreate
     * when called after startup, which loses navigation state and plays a reload
     * animation. Runtime locale switches inside the app go through `LocaleOverride` in
     * the Compose tree — this call only covers the externally-launched ProtonCore login
     * Activities (XML-based, outside the Compose tree).
     */
    private fun applyStoredLanguage() {
        val cached = LanguagePrefsBoot.read(this)
        val desired = if (cached == "system" || cached.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(cached)
        }
        // Dedup: setApplicationLocales triggers an Activity recreate (visible as a
        // scale-from-center animation). If the framework already has the right locale
        // from a previous session, skip the call. AppCompatDelegate persists
        // setApplicationLocales across process death, so the no-op branch is the steady
        // state once the user has picked a language.
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.toLanguageTags() != desired.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
        // Defensive: same drift-sync as applyStoredThemeMode(). Handles a first boot
        // where DataStore already holds the migrated tag but the SharedPreferences
        // mirror has nothing.
        appScope.launch {
            val fromDataStore = runCatching {
                settingsDataStore.data.map { prefs ->
                    prefs[SettingsKeys.LANGUAGE] ?: "system"
                }.first()
            }.getOrNull() ?: return@launch
            if (fromDataStore != cached) {
                LanguagePrefsBoot.write(this@App, fromDataStore)
                // Don't re-apply on the fly — would force the Activity recreate this
                // whole mechanism is built to avoid. The corrected value lands on the
                // next cold start.
            }
        }
    }

    /**
     * Privacy opt-in — when [SettingsKeys.CLEAR_CACHE_ON_APP_CLOSE] is on, wipe every
     * disk cache the app maintains as soon as the whole process backgrounds. Users
     * who flip this on are explicitly choosing zero on-disk traces over fast-restart
     * UX; the cost is a fresh thumbnail + full-res pull on the next open. Lifecycle
     * .Event.ON_STOP on the process-level lifecycle fires after the last Activity
     * moves out of the started state (all activities backgrounded or destroyed), so
     * this is the correct hook — single-Activity transitions (rotation, photo picker
     * round-trips) do not trigger it.
     *
     * Wipe targets:
     *  - `fullres/`, `fullres-session/` — full-res photo + video blob caches
     *  - `thumbnails/` — decrypted photo thumbnails written by the gallery
     *  - `coil_cache/` — Coil's bounded LRU disk cache (still leaks otherwise)
     *
     * Off-limits (would corrupt in-flight work or break next launch):
     *  - `upload_<id>/` — in-flight encrypted upload block dirs
     *  - `code_cache/` and other OS-managed subdirs
     *  - the DataStore preferences themselves
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
                                "fullres", "fullres-session", "thumbnails", "coil_cache",
                            ).forEach { sub ->
                                java.io.File(cacheDir, sub).deleteRecursively()
                            }
                            // The decrypted thumbnail files are gone; null their DB paths so
                            // the next launch re-requests a decrypt instead of pointing cells
                            // at missing files (which the scheduler would skip as "done").
                            photoListingDao.clearCachedThumbnailUrls()
                        }
                    }
                }
            }
        )
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
