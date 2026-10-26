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
/** The shortest gap between two saves of a machine-learning copy that only a scan changed. */
internal const val ML_SAVE_GAP_MS = 60L * 60_000L

/**
 * Whether a machine-learning copy is written: it changed ([changedAt]) since a round last began saving it ([savedAt]),
 * and it was never saved, the user changed it since ([userChangedAt]), or [ML_SAVE_GAP_MS] passed since that save. A
 * scan running for hours then sends its copy once an hour instead of every round, the whole index each time.
 */
internal fun mlSaveDue(changedAt: Long, userChangedAt: Long, savedAt: Long, nowMs: Long): Boolean =
    changedAt > savedAt && (savedAt == 0L || userChangedAt > savedAt || nowMs - savedAt >= ML_SAVE_GAP_MS)

class SharedBackupStore @Inject constructor(@ApplicationContext private val context: Context) {

    val status: Flow<SharedBackupStatus>
        get() = context.settingsDataStore.data.map { it.status() }

    suspend fun isEnabled(): Boolean = context.settingsDataStore.data.first()[SettingsKeys.SHARED_BACKUP_ENABLED] == true

    /**
     * Turns the shared backup on or off. Turning it on counts this device's machine-learning data as changed and not
     * yet saved, so the first round writes its copies.
     */
    suspend fun setEnabled(enabled: Boolean, nowMs: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.SHARED_BACKUP_ENABLED] = enabled
            if (enabled) {
                prefs[SettingsKeys.SHARED_BACKUP_FACES_CHANGED_AT] = nowMs
                prefs[SettingsKeys.SHARED_BACKUP_SEARCH_CHANGED_AT] = nowMs
                prefs.remove(SettingsKeys.SHARED_BACKUP_FACES_SAVED_AT)
                prefs.remove(SettingsKeys.SHARED_BACKUP_SEARCH_SAVED_AT)
                prefs[SettingsKeys.SHARED_BACKUP_LAST_FAILED] = false
            }
        }
    }

    /** Whether a round has taken this install's own copies in since a fresh install or a sign-out
     *  ([SettingsKeys.SHARED_BACKUP_OWN_COPY_TAKEN]). */
    suspend fun ownCopyTaken(): Boolean =
        context.settingsDataStore.data.first()[SettingsKeys.SHARED_BACKUP_OWN_COPY_TAKEN] == true

    suspend fun markOwnCopyTaken() {
        context.settingsDataStore.edit { it[SettingsKeys.SHARED_BACKUP_OWN_COPY_TAKEN] = true }
    }

    /** Reads this install's own copies on Drive back in at the next round, as after a fresh install. */
    suspend fun forgetOwnCopyTaken() {
        context.settingsDataStore.edit { it.remove(SettingsKeys.SHARED_BACKUP_OWN_COPY_TAKEN) }
    }

    /** When the account's face data was last deleted everywhere, as far as this device knows, 0 when never. */
    suspend fun facesWipedAt(): Long = context.settingsDataStore.data.first()[SettingsKeys.SHARED_BACKUP_FACES_WIPED_AT] ?: 0L

    /**
     * Notes a delete of the face data everywhere at [atMs]: made here when [madeHere], which also leaves the other
     * devices' face copies on Drive for a round to move to the trash, or read from another device's copy. An older
     * time than the one known changes nothing.
     */
    suspend fun recordFacesWiped(atMs: Long, madeHere: Boolean) {
        context.settingsDataStore.edit { prefs ->
            if (atMs <= (prefs[SettingsKeys.SHARED_BACKUP_FACES_WIPED_AT] ?: 0L)) return@edit
            prefs[SettingsKeys.SHARED_BACKUP_FACES_WIPED_AT] = atMs
            if (madeHere) prefs[SettingsKeys.SHARED_BACKUP_FACES_TRASH_PENDING] = true
        }
    }

    /** Whether the other devices' face copies still wait to go to the Drive trash after a delete made here. */
    suspend fun facesTrashPending(): Boolean =
        context.settingsDataStore.data.first()[SettingsKeys.SHARED_BACKUP_FACES_TRASH_PENDING] == true

    suspend fun facesTrashDone() {
        context.settingsDataStore.edit { it.remove(SettingsKeys.SHARED_BACKUP_FACES_TRASH_PENDING) }
    }

    /** The device names read from copies on Drive so far, by install id. */
    suspend fun deviceNames(): Map<String, String> =
        (context.settingsDataStore.data.first()[SettingsKeys.SHARED_BACKUP_DEVICE_NAMES] ?: emptySet())
            .mapNotNull { entry -> entry.indexOf('=').takeIf { it > 0 }?.let { entry.substring(0, it) to entry.substring(it + 1) } }
            .toMap()

    /** Notes the [names] of devices (install id to name) read from their copies. */
    suspend fun rememberDeviceNames(names: Map<String, String>) {
        if (names.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val merged = (prefs[SettingsKeys.SHARED_BACKUP_DEVICE_NAMES] ?: emptySet())
                .filterNot { it.substringBefore('=') in names }
                .toSet() + names.map { (id, name) -> "$id=$name" }
            prefs[SettingsKeys.SHARED_BACKUP_DEVICE_NAMES] = merged
        }
    }

    /** Whether the account [userId] had the automatic backup on, before a sign-out wiped its switch. */
    suspend fun rememberedOn(userId: String): Boolean =
        accountHash(userId) in (context.settingsDataStore.data.first()[SettingsKeys.SHARED_BACKUP_ACCOUNTS] ?: emptySet())

    /** Remembers whether the account [userId] has the automatic backup on, across a sign-out. */
    suspend fun remember(userId: String, on: Boolean) {
        val hash = accountHash(userId)
        context.settingsDataStore.edit { prefs ->
            val accounts = prefs[SettingsKeys.SHARED_BACKUP_ACCOUNTS] ?: emptySet()
            prefs[SettingsKeys.SHARED_BACKUP_ACCOUNTS] = if (on) accounts + hash else accounts - hash
        }
    }

    /** Notes a change of [kinds] here at [nowMs]; [byUser] when the user made it rather than a scan. */
    suspend fun markChanged(kinds: Collection<SharedBackupKind>, nowMs: Long, byUser: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.SHARED_BACKUP_CHANGED_AT] = nowMs
            if (SharedBackupKind.FACES in kinds) {
                prefs[SettingsKeys.SHARED_BACKUP_FACES_CHANGED_AT] = nowMs
                if (byUser) prefs[SettingsKeys.SHARED_BACKUP_FACES_USER_CHANGED_AT] = nowMs
            }
            if (SharedBackupKind.SEARCH in kinds) prefs[SettingsKeys.SHARED_BACKUP_SEARCH_CHANGED_AT] = nowMs
        }
    }

    /** Whether a round should write the machine-learning copy of [kind] at [nowMs] ([mlSaveDue]). */
    suspend fun mlDue(kind: MlDriveFileKind, nowMs: Long): Boolean {
        val prefs = context.settingsDataStore.data.first()
        val (changed, saved) = mlKeys(kind)
        val byUser = if (kind == MlDriveFileKind.FACES) prefs[SettingsKeys.SHARED_BACKUP_FACES_USER_CHANGED_AT] ?: 0L else 0L
        return mlSaveDue(prefs[changed] ?: 0L, byUser, prefs[saved] ?: 0L, nowMs)
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

    private fun accountHash(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private fun fingerprint(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
}
