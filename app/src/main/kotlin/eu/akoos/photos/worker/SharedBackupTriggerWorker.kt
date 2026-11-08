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
import eu.akoos.photos.data.sharedbackup.SharedBackupScheduler
import kotlinx.coroutines.CancellationException

/**
 * A planned check of the shared backup ([SharedBackupScheduler]): once its wait is over and a connection is there, it
 * starts the round, which is the ML sync job. It only hands over, so it is quick.
 */
@HiltWorker
class SharedBackupTriggerWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: SharedBackupScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        scheduler.syncNow()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "shared backup check failed: ${e.javaClass.simpleName}")
        Result.success()
    }

    private companion object {
        const val TAG = "SharedBackupWorker"
    }
}
