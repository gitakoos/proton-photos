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

package eu.akoos.photos.data.autosave

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.label.LabelBackupController
import eu.akoos.photos.data.label.LabelBackupStart
import eu.akoos.photos.data.mlsync.MlSyncController
import eu.akoos.photos.data.mlsync.MlSyncStart
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.worker.AutoSaveTriggerWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** How a finished save run counts for the automatic save. */
enum class AutoSaveRunResult { SAVED, FAILED, NEITHER }

/**
 * Keeps the Drive copies of the machine-learning data and of the labels up to date on their own, when the user
 * switched that on. It watches the tables behind each copy for changes ([start]), decides from the saved times
 * when the next save is due ([decideAutoSave]), hands a due save to the same background job a manual save
 * uses ([MlSyncController], [LabelBackupController]), and hears from that job when it ends
 * ([saveEnded]) to record the outcome and plan the next check. The next check is a WorkManager job that
 * survives the app being left or killed and a reboot; [start] plans it again at every launch, since a force
 * stop drops it.
 *
 * A save runs only while the account is signed in, on a connection (unmetered unless the user allowed mobile
 * data for backups) and with the battery not low. Two saves never run together: a busy answer only waits.
 */
@Singleton
class AutoSaveCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: AutoSaveStore,
    private val accountManager: AccountManager,
    private val mlSyncController: MlSyncController,
    private val labelBackupController: LabelBackupController,
    private val database: AppDatabase,
    @AppScope private val appScope: CoroutineScope,
) {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    private val zone: ZoneId
        get() = ZoneId.systemDefault()

    /** Watches the data behind both copies and plans the next check of each. Called once, from the main process. */
    fun start() {
        for (kind in AutoSaveKind.entries) {
            appScope.launch {
                try {
                    database.invalidationTracker.createFlow(*tablesOf(kind), emitInitialState = false)
                        .conflate()
                        .collect {
                            val wasClean = store.markChanged(kind, System.currentTimeMillis())
                            val state = store.read(kind)
                            if (state.enabled && (wasClean || state.frequency == AutoSaveFrequency.AFTER_CHANGES)) {
                                reschedule(kind)
                            }
                            // Changes arrive in bursts while a scan writes; noting one every half minute is enough.
                            delay(CHANGE_SAMPLE_MS)
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "change watch for $kind stopped: ${e.javaClass.simpleName}")
                }
            }
            appScope.launch { runCatching { reschedule(kind) } }
        }
    }

    /**
     * Switches the automatic save of [kind] on or off. Switching on saves once at once, so the copy starts
     * out current, and then follows the schedule; switching off drops the planned check.
     */
    suspend fun setEnabled(kind: AutoSaveKind, enabled: Boolean) {
        store.setEnabled(kind, enabled, System.currentTimeMillis())
        if (enabled) trigger(kind, pretendDue = true) else cancelWork(kind)
    }

    suspend fun setFrequency(kind: AutoSaveKind, frequency: AutoSaveFrequency) {
        store.setFrequency(kind, frequency)
        reschedule(kind)
    }

    /**
     * Plans the next check of [kind]: none when the save is off or nothing changed, otherwise a check after
     * the wait the rule gives.
     */
    suspend fun reschedule(kind: AutoSaveKind) {
        val state = store.read(kind)
        if (!state.enabled) {
            cancelWork(kind)
            return
        }
        when (val decision = decideAutoSave(state.frequency, state.clock, System.currentTimeMillis(), zone)) {
            AutoSaveDecision.Idle -> cancelWork(kind)
            AutoSaveDecision.Run -> enqueueCheck(kind, 0L)
            is AutoSaveDecision.Wait -> enqueueCheck(kind, decision.delayMs)
        }
    }

    /**
     * The planned check of [kind]: starts a save when one is due. With [pretendDue] the timing rule is skipped
     * and a save starts whenever something changed, which is what switching the save on and the debug button
     * use. Returns a one-line account of what it did, for the debug readout.
     */
    suspend fun trigger(kind: AutoSaveKind, pretendDue: Boolean = false): String {
        val state = store.read(kind)
        if (!state.enabled) {
            cancelWork(kind)
            return "off"
        }
        val userId = accountManager.getPrimaryUserId().first()
        if (userId == null) {
            cancelWork(kind)
            return "not signed in"
        }
        val now = System.currentTimeMillis()
        val decision = if (pretendDue) {
            if (state.dirtySinceMs == 0L) AutoSaveDecision.Idle else AutoSaveDecision.Run
        } else {
            decideAutoSave(state.frequency, state.clock, now, zone)
        }
        return when (decision) {
            AutoSaveDecision.Idle -> {
                cancelWork(kind)
                "nothing changed since the last save, skipped"
            }
            is AutoSaveDecision.Wait -> {
                enqueueCheck(kind, decision.delayMs)
                "not due yet, next check in ${decision.delayMs / 1000} s"
            }
            AutoSaveDecision.Run -> startSave(kind, userId.id)
        }
    }

    /**
     * A save job of [kind] ended, whatever started it; [started] is when it began and [result] how it went.
     * Records the outcome and plans the next check. Cancelled runs pass [AutoSaveRunResult.NEITHER].
     */
    suspend fun saveEnded(kind: AutoSaveKind, started: Long, result: AutoSaveRunResult) {
        val now = System.currentTimeMillis()
        when (result) {
            AutoSaveRunResult.SAVED -> store.recordSaved(kind, started, now)
            AutoSaveRunResult.FAILED -> store.recordFailed(kind, now)
            AutoSaveRunResult.NEITHER -> Unit
        }
        reschedule(kind)
    }

    /** Plans the next check of [kind] after the usual wait following a failure. */
    suspend fun retryLater(kind: AutoSaveKind) {
        if (store.read(kind).enabled) enqueueCheck(kind, AUTO_SAVE_FAILURE_RETRY_MS)
    }

    /** Debug aid: counts the data of [kind] as changed now. */
    suspend fun debugMarkChanged(kind: AutoSaveKind) {
        store.forceDirty(kind, System.currentTimeMillis())
        reschedule(kind)
    }

    /** Debug aid: what the timing rule says about [kind] right now, in one line. */
    suspend fun debugStatus(kind: AutoSaveKind): String {
        val state = store.read(kind)
        val now = System.currentTimeMillis()
        val decision = decideAutoSave(state.frequency, state.clock, now, zone)
        val verdict = when (decision) {
            AutoSaveDecision.Idle -> "idle (nothing changed)"
            AutoSaveDecision.Run -> "due now"
            is AutoSaveDecision.Wait -> "due in ${decision.delayMs / 60_000} min"
        }
        return "${if (state.enabled) "on" else "off"}, ${state.frequency.name.lowercase()}, $verdict, " +
            "changed ${ago(state.lastChangeMs, now)}, last save ${ago(state.lastSaveOkMs, now)}"
    }

    /** Drops both planned checks and forgets both saves' settings, for a sign-out. */
    suspend fun disableAll() {
        for (kind in AutoSaveKind.entries) cancelWork(kind)
        store.clearAll()
    }

    private suspend fun startSave(kind: AutoSaveKind, userId: String): String {
        val account = me.proton.core.domain.entity.UserId(userId)
        return when (kind) {
            AutoSaveKind.ML -> when (mlSyncController.save(account, createFolderConfirmed = true)) {
                MlSyncStart.STARTED -> "save started"
                MlSyncStart.ALREADY_RUNNING -> retryLater(kind, AUTO_SAVE_BUSY_RETRY_MS, "another save is running")
                MlSyncStart.NOT_AVAILABLE -> notAvailable(kind)
                MlSyncStart.NO_FILES, MlSyncStart.FOLDER_MISSING ->
                    retryLater(kind, AUTO_SAVE_FAILURE_RETRY_MS, "could not start")
            }
            AutoSaveKind.LABELS -> when (labelBackupController.save(account, createFolderConfirmed = true)) {
                LabelBackupStart.STARTED -> "save started"
                LabelBackupStart.ALREADY_RUNNING -> retryLater(kind, AUTO_SAVE_BUSY_RETRY_MS, "another save is running")
                LabelBackupStart.NOT_AVAILABLE -> notAvailable(kind)
                LabelBackupStart.FOLDER_MISSING -> retryLater(kind, AUTO_SAVE_FAILURE_RETRY_MS, "could not start")
            }
        }
    }

    private suspend fun retryLater(kind: AutoSaveKind, delayMs: Long, what: String): String {
        enqueueCheck(kind, delayMs)
        return "$what, next check in ${delayMs / 60_000} min"
    }

    private fun notAvailable(kind: AutoSaveKind): String {
        cancelWork(kind)
        return "not available on this device or account"
    }

    private suspend fun enqueueCheck(kind: AutoSaveKind, delayMs: Long) {
        val wifiOnly = context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] != false
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<AutoSaveTriggerWorker>()
            .setInitialDelay(maxOf(delayMs, 0L), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(workDataOf(AutoSaveTriggerWorker.KEY_KIND to kind.name))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueName(kind), ExistingWorkPolicy.REPLACE, request)
    }

    private fun cancelWork(kind: AutoSaveKind) {
        workManager.cancelUniqueWork(uniqueName(kind))
    }

    private fun ago(thenMs: Long, nowMs: Long): String =
        if (thenMs <= 0L) "never" else "${(nowMs - thenMs) / 60_000} min ago"

    companion object {
        private const val TAG = "AutoSave"
        private const val CHANGE_SAMPLE_MS = 30_000L

        fun uniqueName(kind: AutoSaveKind): String = "auto_save_${kind.name.lowercase()}"

        /** The tables whose rows make up a Drive copy; a write to any of them is a change to save. */
        internal fun tablesOf(kind: AutoSaveKind): Array<String> = when (kind) {
            AutoSaveKind.ML -> arrayOf(
                "face", "face_scan", "person", "not_person", "person_cover", "person_manual_photo", "image_embedding",
            )
            AutoSaveKind.LABELS -> arrayOf("photo_label", "photo_label_name")
        }
    }
}
