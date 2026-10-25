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
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.autosave.AutoSaveCoordinator
import eu.akoos.photos.data.autosave.AutoSaveKind
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.mlsync.MlSyncOperation
import eu.akoos.photos.data.mlsync.MlSyncWorkData
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.worker.MlSyncWorker
import eu.akoos.photos.worker.SharedBackupTriggerWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the shared backup's round runs: when the switch is turned on and at every app start, [SAVE_AFTER_MS] after the
 * last change the user made here (each such change moves the wait, up to [SAVE_AT_MOST_AFTER_MS] after the first one
 * still waiting), every [ROUND_EVERY_MS] for what the other devices saved and what a scan changed, and from the Sync
 * now row. The round itself is the ML sync job, one at a time; the automatic ones keep to an unmetered connection
 * when backups do, the Sync now row and the switch take any connection. The planned checks are WorkManager jobs, so
 * they survive the app being left or killed.
 */
/** How long a round after a change the user made waits: [SharedBackupScheduler.SAVE_AFTER_MS], or less once the
 *  first change still waiting came [SharedBackupScheduler.SAVE_AT_MOST_AFTER_MS] ago. */
internal fun soonRoundDelay(nowMs: Long, firstWaitingMs: Long): Long =
    minOf(SharedBackupScheduler.SAVE_AFTER_MS, maxOf(0L, firstWaitingMs + SharedBackupScheduler.SAVE_AT_MOST_AFTER_MS - nowMs))

/** Tables whose writes are a change of [kinds], made by the user ([byUser]) or by a scan. */
private class WatchedTables(val kinds: List<SharedBackupKind>, val byUser: Boolean, vararg val tables: String)

@Singleton
class SharedBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SharedBackupStore,
    private val accountManager: AccountManager,
    private val database: AppDatabase,
    private val autoSave: AutoSaveCoordinator,
    @AppScope private val appScope: CoroutineScope,
) {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    /** When the first change the user made that no round has picked up yet came, or 0. */
    @Volatile
    private var firstWaitingChangeMs = 0L

    /** Watches the data the shared backup keeps and runs a round now when it is on. Called once, from the main process. */
    fun start() {
        for (watch in WATCHED_TABLES) {
            appScope.launch {
                try {
                    database.invalidationTracker.createFlow(*watch.tables, emitInitialState = false)
                        .conflate()
                        .collect {
                            changed(watch.kinds, byUser = watch.byUser)
                            // A scan writes in bursts; noting a change every half minute is enough.
                            delay(CHANGE_SAMPLE_MS)
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "change watch stopped: ${e.javaClass.simpleName}")
                }
            }
        }
        appScope.launch {
            try {
                context.settingsDataStore.data
                    .map { prefs -> HIDDEN_KEYS.map { prefs[it] } }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { changed(listOf(SharedBackupKind.HIDDEN), byUser = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "hidden watch stopped: ${e.javaClass.simpleName}")
            }
        }
        appScope.launch { runCatching { syncNow() } }
    }

    /**
     * Turns the shared backup on, with a round at once, or off, dropping the planned checks and handing the Drive
     * copies back to the automatic saves the user had switched on.
     */
    suspend fun setEnabled(enabled: Boolean) {
        store.setEnabled(enabled, System.currentTimeMillis())
        if (enabled) {
            syncNow(userAsked = true)
        } else {
            cancel()
            for (kind in AutoSaveKind.entries) runCatching { autoSave.reschedule(kind) }
        }
    }

    /**
     * Runs a round as soon as a connection allows, while the shared backup is on and an account is signed in. One the
     * user asked for ([userAsked]) takes any connection and the place of an automatic round still waiting for an
     * unmetered one; an automatic round keeps to an unmetered connection when backups do.
     */
    suspend fun syncNow(userAsked: Boolean = false) {
        if (!store.isEnabled()) return
        val userId = accountManager.getPrimaryUserId().first() ?: return
        firstWaitingChangeMs = 0L
        val anyNetwork = userAsked || context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] == false
        val replaceWaiting = userAsked && withContext(Dispatchers.IO) { automaticRoundWaiting() }
        MlSyncWorker.enqueueRound(context, userId, anyNetwork, replaceWaiting)
    }

    private fun automaticRoundWaiting(): Boolean = runCatching {
        workManager.getWorkInfosForUniqueWork(MlSyncWorker.UNIQUE_WORK_NAME).get().any {
            it.state == WorkInfo.State.ENQUEUED && MlSyncWorkData.operationTag(MlSyncOperation.ROUND) in it.tags
        }
    }.getOrDefault(false)

    /** Plans the next round [ROUND_EVERY_MS] from now. The round worker calls this as it ends. */
    suspend fun scheduleNext() {
        if (!store.isEnabled()) return
        enqueueCheck(NEXT_WORK, ROUND_EVERY_MS)
    }

    /** Drops every planned check, for a switch turned off or a sign-out. */
    fun cancel() {
        workManager.cancelUniqueWork(SOON_WORK)
        workManager.cancelUniqueWork(NEXT_WORK)
    }

    /**
     * Notes a change of [kinds]. One the user made plans a round [SAVE_AFTER_MS] later; a scan's waits for the
     * half-hourly round, so a scan writing for hours neither keeps moving the user's change nor runs a round of its own.
     */
    private suspend fun changed(kinds: Collection<SharedBackupKind>, byUser: Boolean) {
        val now = System.currentTimeMillis()
        store.markChanged(kinds, now, byUser)
        if (!byUser || !store.isEnabled()) return
        if (firstWaitingChangeMs == 0L) firstWaitingChangeMs = now
        enqueueCheck(SOON_WORK, soonRoundDelay(now, firstWaitingChangeMs))
    }

    private fun enqueueCheck(name: String, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<SharedBackupTriggerWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        private const val TAG = "SharedBackup"

        /** A change waits this long, so a run of edits goes out in one round. */
        const val SAVE_AFTER_MS = 3L * 60_000L

        /** A round runs this often anyway, for what the other devices saved. */
        const val ROUND_EVERY_MS = 30L * 60_000L

        private const val CHANGE_SAMPLE_MS = 30_000L
        /** The planned checks' unique work names: the one after a change, and the next half-hourly one. */
        const val SOON_WORK = "shared_backup_soon"
        const val NEXT_WORK = "shared_backup_next"

        /** A change waits at most this long after the first one no round has picked up, however many follow it. */
        const val SAVE_AT_MOST_AFTER_MS = 10L * 60_000L

        /** The tables behind each kind's copy; a write to one is a change of that kind, made by a scan or the user. */
        private val WATCHED_TABLES: List<WatchedTables> = listOf(
            WatchedTables(listOf(SharedBackupKind.FACES), byUser = false, "face", "face_scan"),
            WatchedTables(
                listOf(SharedBackupKind.FACES), byUser = true,
                "person", "not_person", "person_cover", "person_manual_photo",
            ),
            WatchedTables(listOf(SharedBackupKind.SEARCH), byUser = false, "image_embedding"),
            WatchedTables(
                listOf(SharedBackupKind.LABELS), byUser = true,
                "photo_label", "photo_label_name", "photo_label_state", "photo_label_deleted",
            ),
        )

        /** The settings behind the hidden list. */
        private val HIDDEN_KEYS = listOf(
            SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS,
            SettingsKeys.HIDDEN_ALBUM_IDS,
            SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP,
            SettingsKeys.HIDDEN_PHOTO_HIDE_TIMES,
            SettingsKeys.HIDDEN_PHOTO_SHOW_TIMES,
            SettingsKeys.HIDDEN_ALBUM_HIDE_TIMES,
            SettingsKeys.HIDDEN_ALBUM_SHOW_TIMES,
        )
    }
}
