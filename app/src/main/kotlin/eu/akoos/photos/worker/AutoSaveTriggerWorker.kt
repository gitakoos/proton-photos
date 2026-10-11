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
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.akoos.photos.data.autosave.AutoSaveCoordinator
import eu.akoos.photos.data.autosave.AutoSaveKind
import kotlinx.coroutines.CancellationException

/**
 * The planned check of an automatic save ([AutoSaveCoordinator]): runs once its wait is over, on a connection
 * and with the battery not low, and starts the Drive save when one is due. It only decides and hands over, so
 * it is quick; the save itself is the usual background job, whose end plans the next check.
 */
@HiltWorker
class AutoSaveTriggerWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: AutoSaveCoordinator,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND)?.let { name -> AutoSaveKind.entries.firstOrNull { it.name == name } }
            ?: return Result.failure()
        return try {
            Log.i(TAG, "$kind: ${coordinator.trigger(kind)}")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$kind check failed: ${e.javaClass.simpleName}")
            // The next check is planned again from here, a while later, so a failure that repeats never loops.
            runCatching { coordinator.retryLater(kind) }
            Result.success()
        }
    }

    companion object {
        const val KEY_KIND = "kind"
        private const val TAG = "AutoSaveWorker"
    }
}
