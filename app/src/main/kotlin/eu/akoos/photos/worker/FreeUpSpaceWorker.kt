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

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException

/**
 * Automatic Free-up scheduling: age / AUTO_FREE_UP / thermal / battery / network gates, then the
 * shared [FreeUpPipelineWorker] for a single-pass verify+delete under distinct unique names
 * (no foreground service).
 *
 * Cancel targets only the automatic unique names and does not touch the manual pipeline.
 */
object FreeUpSpaceWorker {
    const val TAG = "free_up_worker"
    const val NAME_ONESHOT = "free_up_worker_oneshot"
    const val KEY_INTERVAL_MS = "interval_ms"

    /** Read-back sentinel for input data that carries no [KEY_INTERVAL_MS] at all. */
    const val NO_INTERVAL_MS = -1L

    /**
     * Whether the automatic pipeline should return success without sweeping when the AUTO_FREE_UP
     * setting is off. Pure so the setting gate can be pinned by a plain JVM test.
     */
    fun shouldSkipWhenAutoFreeUpDisabled(enabled: Boolean): Boolean = !enabled

    /**
     * Unique work names [cancel] must stop. Turning AUTO_FREE_UP off used to leave the launch
     * oneshot ([NAME_ONESHOT]) queued after only cancelling the periodic entry ([TAG]).
     */
    fun uniqueWorkNamesToCancel(): List<String> = listOf(TAG, NAME_ONESHOT)

    /**
     * Whether [error] is a permanent reclaim failure that should burn as failure rather than
     * retry. Pure so the catch classification can be pinned by a JVM test.
     *
     * [CancellationException] is an [IllegalStateException], but cooperative cancel must not
     * look permanent — callers rethrow it before consulting this classifier.
     */
    fun isPermanentSweepFailure(error: Throwable): Boolean {
        if (error is CancellationException) return false
        return error is SecurityException ||
            error is IllegalStateException ||
            error is java.io.FileNotFoundException
    }

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
    const val CLOUD_FRESHNESS_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

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
     * Verify talks to Drive, so the sweep needs [NetworkType.CONNECTED]. Battery-not-low stays
     * so hourly runs avoid low-power sessions.
     */
    fun sweepConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * [intervalMs] is how long a photo must have been backed up before its device copy may be
     * reclaimed, and has no default: every scheduled sweep states the age it enforces.
     */
    fun schedule(workManager: WorkManager, intervalMs: Long) {
        FreeUpPipelineWorker.scheduleAutomatic(workManager, intervalMs)
    }

    /**
     * A one-shot sweep for app foreground. The periodic worker fires only hourly and an OEM doze
     * can delay it for hours, so a photo can sit past its age gate long after the app is opened.
     * Running the same full pipeline on launch reclaims eligible copies. Same [intervalMs] age
     * gate and [sweepConstraints]; its own unique name (so it never displaces the periodic entry)
     * with `APPEND_OR_REPLACE` so repeated launches coalesce instead of stacking.
     */
    fun runNow(workManager: WorkManager, intervalMs: Long) {
        FreeUpPipelineWorker.runAutomaticNow(workManager, intervalMs)
    }

    fun cancel(workManager: WorkManager) {
        FreeUpPipelineWorker.cancelAutomatic(workManager)
    }
}
