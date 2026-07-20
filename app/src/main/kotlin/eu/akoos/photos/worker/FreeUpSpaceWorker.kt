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

package eu.akoos.photos.worker

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import java.util.concurrent.TimeUnit

@HiltWorker
class FreeUpSpaceWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val freeUpSpace: FreeUpSpaceUseCase,
    private val accountManager: AccountManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val userId = accountManager.getPrimaryUserId().first() ?: return Result.failure()
        val intervalMs = inputData.getLong(KEY_INTERVAL_MS, NO_INTERVAL_MS)
        if (!isUsableInterval(intervalMs)) {
            Log.w(TAG, "Skipping sweep: request carries no usable interval ($intervalMs)")
            return Result.failure()
        }
        val nowMs = System.currentTimeMillis()
        val olderThanMs = nowMs - intervalMs
        // The sweep deletes a device copy purely on the strength of sync_state calling the photo
        // SYNCED, which is only worth acting on while reconcile has recently checked that against
        // the cloud. Backup being off cancels the background sync that drives reconcile, so an
        // unopened app can otherwise keep sweeping hourly against a frozen picture of Drive and
        // reclaim the device copy of a photo that was deleted from Drive weeks ago.
        val verifiedAtMs = context.settingsDataStore.data.first()[SettingsKeys.cloudVerifiedAtKey(userId.id)]
        if (!isCloudStateFreshEnough(verifiedAtMs, nowMs)) {
            Log.w(TAG, "Skipping sweep: cloud state last verified at $verifiedAtMs, now $nowMs")
            return Result.success()
        }
        return try {
            // The automatic sweep protects copies the user placed on the device (downloads, undone
            // deletes); only the manual button reclaims those.
            when (val result = freeUpSpace(userId, olderThanMs, protectDownloaded = true)) {
                is FreeUpSpaceUseCase.FreeUpResult.Done ->
                    Log.d(TAG, "Sweep reclaimed ${result.freed} photo(s)")
                is FreeUpSpaceUseCase.FreeUpResult.NeedsPermission -> {
                    // A worker has no Activity to drive the system delete dialog, so the URIs go on
                    // the batched consent queue that the foreground handler drains.
                    context.settingsDataStore.edit { p ->
                        val existing = p[SettingsKeys.PENDING_DELETE_URIS] ?: emptySet()
                        p[SettingsKeys.PENDING_DELETE_URIS] = existing + result.localUris
                    }
                    Log.d(TAG, "Sweep queued ${result.localUris.size} photo(s) for batched consent dialog")
                }
            }
            Result.success()
        } catch (e: Exception) {
            // Categorise failures so we only burn the retry budget on transient ones.
            // Permanent failures (file disappeared mid-sweep, MediaStore revoked write
            // access for a foreign-owned URI, the SAF tree we picked got abandoned by
            // the OS) won't fix themselves on the next attempt — three retries against
            // a permanent error just chews battery and timer slots for nothing.
            // Transient failures (IO error, database lock contention) get the existing
            // 3-attempt budget.
            val isPermanent = e is SecurityException ||
                e is IllegalStateException ||
                e is java.io.FileNotFoundException
            if (isPermanent || runAttemptCount >= 3) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val TAG = "free_up_worker"
        private const val NAME_ONESHOT = "free_up_worker_oneshot"
        const val KEY_INTERVAL_MS = "interval_ms"

        /** Read-back sentinel for input data that carries no [KEY_INTERVAL_MS] at all. */
        internal const val NO_INTERVAL_MS = -1L

        /**
         * Whether [intervalMs] is an age the sweep may enforce: true only for a strictly positive
         * value. Pure and side-effect-free so the interval gate can be pinned by a plain JVM test.
         *
         * An age of zero puts the cutoff at "now", which matches every backed-up photo, so a
         * regression here reclaims the device copy of a whole library in one sweep. A request
         * carrying no usable interval is refused rather than run against a guessed one: reclaiming
         * a device copy is irreversible, so a sweep nobody asked for is worse than no sweep.
         */
        fun isUsableInterval(intervalMs: Long): Boolean = intervalMs > 0L

        /**
         * How recently reconcile must have checked sync_state against a fully-listed cloud library
         * for the automatic sweep to delete anything on the strength of it.
         *
         * Seven days rather than something tighter because reconcile also runs on app launch, so
         * anyone who opens the app even weekly stays inside the window and the feature keeps
         * working. The case this shuts out is the app going untouched for far longer with backup
         * off, which is exactly when sync_state stops being refreshed at all.
         */
        internal const val CLOUD_FRESHNESS_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Whether [verifiedAtMs] is recent enough to sweep against. Pure and side-effect-free so
         * the freshness gate can be pinned by a plain JVM test.
         *
         * Every uncertain answer is false, because the cost is asymmetric: a blocked sweep only
         * means storage is not reclaimed automatically and the manual button still works, while an
         * unblocked one can delete a photo's last copy. So an absent timestamp (a fresh install, or
         * an install predating it) does NOT pass, and neither does one in the future, which is what
         * a clock moved backwards looks like and would otherwise read as infinitely fresh.
         */
        fun isCloudStateFreshEnough(verifiedAtMs: Long?, nowMs: Long): Boolean {
            if (verifiedAtMs == null || verifiedAtMs <= 0L) return false
            if (verifiedAtMs > nowMs) return false
            return nowMs - verifiedAtMs < CLOUD_FRESHNESS_WINDOW_MS
        }

        /**
         * The constraint set every scheduled sweep runs under. Pure and side-effect-free so it can
         * be pinned by a plain JVM test.
         *
         * The reclaim is a local MediaStore delete driven by already-persisted sync state, so a
         * network constraint would only stop the sweep running offline.
         */
        fun sweepConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        /**
         * [intervalMs] is how long a photo must have been backed up before its device copy may be
         * reclaimed, and has no default: every scheduled sweep states the age it enforces.
         */
        fun schedule(workManager: WorkManager, intervalMs: Long) {
            val request = PeriodicWorkRequestBuilder<FreeUpSpaceWorker>(1, TimeUnit.HOURS)
                .setConstraints(sweepConstraints())
                .setInputData(
                    androidx.work.Data.Builder()
                        .putLong(KEY_INTERVAL_MS, intervalMs)
                        .build()
                )
                .addTag(TAG)
                .build()
            // UPDATE (not KEEP) is essential when the user changes the interval: with KEEP,
            // WorkManager ignores the new input data because the unique-work entry already exists,
            // so every later sweep keeps reclaiming on the old age. UPDATE replaces it while
            // preserving the existing work ID and run history.
            workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * A one-shot sweep for app foreground. The periodic worker fires only hourly and an OEM doze
         * can delay it for hours, so a photo can sit past its age gate long after the app is opened.
         * Running the same sweep on launch reclaims eligible copies right then. Same [intervalMs] age
         * gate and [sweepConstraints]; its own unique name (so it never displaces the periodic entry)
         * with `APPEND_OR_REPLACE` so repeated launches coalesce instead of stacking.
         */
        fun runNow(workManager: WorkManager, intervalMs: Long) {
            val request = OneTimeWorkRequestBuilder<FreeUpSpaceWorker>()
                .setConstraints(sweepConstraints())
                .setInputData(
                    androidx.work.Data.Builder()
                        .putLong(KEY_INTERVAL_MS, intervalMs)
                        .build()
                )
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(NAME_ONESHOT, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(workManager: WorkManager) {
            workManager.cancelUniqueWork(TAG)
        }
    }
}
