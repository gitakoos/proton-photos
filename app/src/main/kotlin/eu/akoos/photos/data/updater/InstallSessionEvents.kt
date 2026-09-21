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

package eu.akoos.photos.data.updater

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries the asynchronous result of a committed install session from [InstallStatusReceiver] to
 * whichever UI surface is hosting the updater dialog. A BroadcastReceiver is not injectable and
 * outlives no state of its own, so the singleton bus is the join point — the same shape the rest
 * of the app already uses for cross-component signals.
 *
 * Buffered rather than replayed: an install outcome is only actionable while the host is alive,
 * and a replayed one would re-launch a confirmation screen for a session that has since finished.
 */
@Singleton
class InstallSessionEvents @Inject constructor() {

    private val _outcomes = MutableSharedFlow<InstallOutcome>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val outcomes: SharedFlow<InstallOutcome> = _outcomes.asSharedFlow()

    /**
     * True when the outcome reached a live collector. False tells the caller nothing is listening,
     * so an outcome that needs acting on has to be handled where it was produced.
     */
    fun publish(outcome: InstallOutcome): Boolean =
        _outcomes.subscriptionCount.value > 0 && _outcomes.tryEmit(outcome)
}
