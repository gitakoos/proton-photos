package me.proton.photos.data.preferences

import android.content.Context

/**
 * Synchronous mirror of `SettingsKeys.THEME_MODE` backed by SharedPreferences so
 * `App.onCreate` can call `AppCompatDelegate.setDefaultNightMode` before the first
 * Activity inflates without blocking on DataStore IO. Canonical store stays DataStore;
 * writes mirror here from [SettingsViewModel] and [App] refreshes lazily.
 *
 * Values: `"dark"` | `"light"` | `"system"` (null when never written).
 */
object ThemePrefsBoot {
    private const val PREFS_NAME = "theme_boot"
    private const val KEY_MODE = "theme_mode"

    /** Returns the cached mode key, or `"system"` if never written. Sync, fast. */
    fun read(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODE, null) ?: "system"
    }

    /**
     * Mirrors the canonical DataStore value into the boot cache. Safe to call on any
     * thread (SharedPreferences `apply()` schedules the disk write off the main thread).
     */
    fun write(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODE, mode).apply()
    }
}
