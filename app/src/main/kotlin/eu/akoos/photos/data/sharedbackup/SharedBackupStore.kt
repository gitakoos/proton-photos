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

package eu.akoos.photos.data.sharedbackup

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.mlsync.MlDriveFileKind
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** The kinds of data the shared backup keeps a copy of, with the name each is recorded under. */
enum class SharedBackupKind(val key: String) { FACES("faces"), SEARCH("search"), LABELS("labels"), HIDDEN("hidden") }

/** How the shared backup stands, for the settings row. */
data class SharedBackupStatus(val enabled: Boolean, val lastAtMs: Long, val lastFailed: Boolean)

/**
 * The jobs that use the Drive SDK, the machine-learning and label saves and restores and the shared backup's round,
 * run one at a time: the SDK's crypto runs in this process, outside the crypto isolation. Each worker holds this
 * lock for its whole run.
 */
object DriveJobLock {
    val mutex = Mutex()
}

/**
 * The shared backup's own record in the settings store: whether it is on, how the last round went, what each kind's
 * copy on Drive held when this device last wrote it, and when the machine-learning data last changed here.
 */
@Singleton
class SharedBackupStore @Inject constructor(@ApplicationContext private val context: Context) {

    val status: Flow<SharedBackupStatus>
        get() = context.settingsDataStore.data.map { it.status() }

    suspend fun isEnabled(): Boolean = context.settingsDataStore.data.first()[SettingsKeys.SHARED_BACKUP_ENABLED] == true

    /**
     * Turns the shared backup on or off. Turning it on counts this device's machine-learning data as changed, so the
     * first round writes its copies.
     */
    suspend fun setEnabled(enabled: Boolean, nowMs: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.SHARED_BACKUP_ENABLED] = enabled
            if (enabled) {
                prefs[SettingsKeys.SHARED_BACKUP_FACES_CHANGED_AT] = nowMs
                prefs[SettingsKeys.SHARED_BACKUP_SEARCH_CHANGED_AT] = nowMs
                prefs[SettingsKeys.SHARED_BACKUP_LAST_FAILED] = false
            }
        }
    }

    /** Notes a change of [kinds] here at [nowMs]. */
    suspend fun markChanged(kinds: Collection<SharedBackupKind>, nowMs: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.SHARED_BACKUP_CHANGED_AT] = nowMs
            if (SharedBackupKind.FACES in kinds) prefs[SettingsKeys.SHARED_BACKUP_FACES_CHANGED_AT] = nowMs
            if (SharedBackupKind.SEARCH in kinds) prefs[SettingsKeys.SHARED_BACKUP_SEARCH_CHANGED_AT] = nowMs
        }
    }

    /** Whether the machine-learning data of [kind] changed since a round last began saving it. */
    suspend fun mlChanged(kind: MlDriveFileKind): Boolean {
        val prefs = context.settingsDataStore.data.first()
        val (changed, saved) = mlKeys(kind)
        return (prefs[changed] ?: 0L) > (prefs[saved] ?: 0L)
    }

    /** Records that a round that began at [startedMs] wrote the copy of [kind], or found nothing to write. */
    suspend fun mlSaved(kind: MlDriveFileKind, startedMs: Long) {
        context.settingsDataStore.edit { it[mlKeys(kind).second] = startedMs }
    }

    /** Whether [content], a copy with its time left out, is what [kind]'s copy on Drive already holds. */
    suspend fun unchanged(kind: SharedBackupKind, content: ByteArray): Boolean =
        savedFingerprints(context.settingsDataStore.data.first())[kind.key] == fingerprint(content)

    /** Remembers [content] as what [kind]'s copy on Drive holds now. */
    suspend fun recordSaved(kind: SharedBackupKind, content: ByteArray) {
        context.settingsDataStore.edit { prefs ->
            val saved = savedFingerprints(prefs).toMutableMap()
            saved[kind.key] = fingerprint(content)
            prefs[SettingsKeys.SHARED_BACKUP_SAVED] = saved.mapTo(HashSet()) { (k, v) -> "$k|$v" }
        }
    }

    /** Records how the round that just ended went. */
    suspend fun recordRound(endedMs: Long, failed: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.SHARED_BACKUP_LAST_AT] = endedMs
            prefs[SettingsKeys.SHARED_BACKUP_LAST_FAILED] = failed
        }
    }

    private fun mlKeys(kind: MlDriveFileKind): Pair<Preferences.Key<Long>, Preferences.Key<Long>> = when (kind) {
        MlDriveFileKind.FACES -> SettingsKeys.SHARED_BACKUP_FACES_CHANGED_AT to SettingsKeys.SHARED_BACKUP_FACES_SAVED_AT
        MlDriveFileKind.SEARCH -> SettingsKeys.SHARED_BACKUP_SEARCH_CHANGED_AT to SettingsKeys.SHARED_BACKUP_SEARCH_SAVED_AT
    }

    private fun Preferences.status() = SharedBackupStatus(
        enabled = this[SettingsKeys.SHARED_BACKUP_ENABLED] == true,
        lastAtMs = this[SettingsKeys.SHARED_BACKUP_LAST_AT] ?: 0L,
        lastFailed = this[SettingsKeys.SHARED_BACKUP_LAST_FAILED] == true,
    )

    private fun savedFingerprints(prefs: Preferences): Map<String, String> =
        (prefs[SettingsKeys.SHARED_BACKUP_SAVED] ?: emptySet()).associate {
            it.substringBefore('|') to it.substringAfter('|', "")
        }

    private fun fingerprint(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
}
