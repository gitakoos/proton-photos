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

/**
 * Manual Free-up entry points: enqueue/cancel the shared [FreeUpPipelineWorker] under a distinct
 * unique name (expedited FGS, network-only — no AUTO_FREE_UP / thermal gates).
 *
 * Stop cancels [UNIQUE_NAME] only and does not cancel automatic unique work.
 */
object ManualFreeUpWorker {
    const val TAG = "manual_free_up_worker"
    const val UNIQUE_NAME = "manual_free_up"

    fun enqueue(context: Context) {
        FreeUpPipelineWorker.enqueueManual(context)
    }

    fun cancel(context: Context) {
        FreeUpPipelineWorker.cancelManual(context)
    }
}
