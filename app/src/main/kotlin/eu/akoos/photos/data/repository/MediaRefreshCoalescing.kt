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

package eu.akoos.photos.data.repository

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Why a refresh of the local MediaStore snapshot was requested.
 *
 * [Immediate] must not wait: the first gallery load and [LocalMediaRepository.notifyMediaChanged]
 * (permission grant, side-store updates) need a query right away. [Observer] is the noisy
 * MediaStore [android.database.ContentObserver] path — camera bursts and bulk deletes fire many
 * times in a short window, and each full scan is expensive.
 */
internal enum class MediaRefreshTrigger {
    Immediate,
    Observer,
}

/**
 * Quiet window used before a MediaStore-observer burst triggers [queryAllMedia]. Long enough to
 * fold a camera burst / multi-delete into one scan; short enough that the gallery still feels
 * current once the burst settles. Distinct from the SyncWorker settle timer (2s), which only
 * kicks uploads.
 */
internal const val MEDIA_REFRESH_SETTLE_MS: Long = 300L

/**
 * Coalesces rapid [MediaRefreshTrigger.Observer] signals into one trailing emit after [settleMs]
 * of quiet, so the collector runs an expensive MediaStore scan once per burst. Guarantees the
 * final post-burst signal is delivered: [channelFlow] keeps the pending settle child alive until
 * it fires even after upstream completes.
 *
 * [MediaRefreshTrigger.Immediate] cancels any pending settle and emits without waiting, so the
 * initial load and permission / manual refreshes stay snappy.
 */
internal fun Flow<MediaRefreshTrigger>.coalesceMediaRefresh(
    settleMs: Long = MEDIA_REFRESH_SETTLE_MS,
): Flow<Unit> = channelFlow {
    var settleJob: Job? = null
    collect { trigger ->
        when (trigger) {
            MediaRefreshTrigger.Immediate -> {
                settleJob?.cancel()
                settleJob = null
                send(Unit)
            }
            MediaRefreshTrigger.Observer -> {
                settleJob?.cancel()
                settleJob = launch {
                    delay(settleMs)
                    send(Unit)
                }
            }
        }
    }
}
