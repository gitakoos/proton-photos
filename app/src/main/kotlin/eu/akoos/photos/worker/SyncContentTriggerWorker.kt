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
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.akoos.photos.data.preferences.syncEffectivelyEnabled

/**
 * The MediaStore content trigger of the backup ([SyncWorker.scheduleContentObserver]). It runs when a photo
 * or video changes on the device, queues a backup run behind any run already going ([SyncWorker.queueTriggeredRun]) and
 * arms the next trigger. It never uploads itself, so arming the trigger again can only ever replace this
 * quick request, never a backup partway through a file.
 */
@HiltWorker
class SyncContentTriggerWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        // Backup effectively off (auto-sync on, no folder selected): nothing to queue, and the trigger is not
        // armed again, so an idle fire stops here.
        if (!syncEffectivelyEnabled(context)) return Result.success()
        // No battery condition, and never left waiting behind a run that is itself only waiting.
        SyncWorker.queueTriggeredRun(context)
        // Last: arming the successor replaces this very request, which stops it.
        SyncWorker.armContentTrigger(context, selfRearm = true)
        return Result.success()
    }
}
