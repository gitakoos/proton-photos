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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.akoos.photos.domain.usecase.UploadPendingUseCase

/**
 * Backing receiver for the backup notification's "Stop" action.
 *
 * Requests a COOPERATIVE stop of the current upload batch (see
 * [UploadPendingUseCase.requestStop]) instead of WorkManager-cancelling the running
 * [SyncWorker]. Cancelling the worker would cancel the upload coroutine mid-encrypt, which
 * tears down an in-flight native PGP call (libgojni) and crashes the process with a native
 * SIGSEGV. The cooperative stop lets the item currently in transit finish and back up, while
 * preventing any not-yet-started queued item from beginning.
 *
 * Reaches the @Singleton use case via a Hilt EntryPoint since a BroadcastReceiver is not itself
 * injected. The worker's own foreground notification is dismissed when the batch drains and
 * doWork() returns normally.
 */
class StopUploadReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StopUploadEntryPoint {
        fun uploadPendingUseCase(): UploadPendingUseCase
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_UPLOAD) return
        runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                StopUploadEntryPoint::class.java,
            )
            entryPoint.uploadPendingUseCase().requestStop()
            Log.d(TAG, "Cooperative upload stop requested from notification")
        }.onFailure { Log.w(TAG, "Stop-upload request failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "stop_upload_receiver"
        const val ACTION_STOP_UPLOAD = "eu.akoos.photos.action.STOP_UPLOAD"
    }
}
