package me.proton.photos.presentation.lock

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.proton.photos.data.preferences.SettingsKeys
import me.proton.photos.data.preferences.settingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val isLockEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[SettingsKeys.APP_LOCK_ENABLED] ?: false }

    /**
     * Timeout in minutes between backgrounding the app and re-locking it on resume.
     * 0 = lock immediately (legacy v1.0.0-beta behavior). Larger values let the user briefly
     * switch to other apps without re-authenticating on every return.
     */
    val lockTimeoutMinutes: Flow<Int> = context.settingsDataStore.data
        .map { it[SettingsKeys.APP_LOCK_TIMEOUT_MINUTES] ?: 0 }

    suspend fun setLockEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setLockTimeoutMinutes(minutes: Int) {
        val clamped = minutes.coerceAtLeast(0)
        context.settingsDataStore.edit { it[SettingsKeys.APP_LOCK_TIMEOUT_MINUTES] = clamped }
    }
}
