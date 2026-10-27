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

package eu.akoos.photos.data.freeup

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.domain.repository.FreeUpConsentSettlement
import eu.akoos.photos.domain.repository.FreeUpPendingDeleteQueue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cancels the free-up action-required / awaiting-consent shade entry.
 * Call whenever the permanent-delete queue drains, is denied, goes stale, or fails technically.
 */
fun cancelFreeUpActionRequiredNotification(context: Context) {
    runCatching {
        NotificationManagerCompat.from(context).cancel(NotificationIds.FREE_UP_ACTION_REQUIRED)
    }
}

/**
 * Data-layer [FreeUpPendingDeleteQueue] for free-up permanent-delete consent.
 * Hard-separated from [eu.akoos.photos.data.preferences.SettingsKeys.PENDING_DELETE_URIS].
 */
@Singleton
class FreeUpPendingDeleteBridge @Inject constructor() : FreeUpPendingDeleteQueue {
    private val _uris = MutableStateFlow<Set<String>>(emptySet())
    override val uris: StateFlow<Set<String>> = _uris.asStateFlow()

    private val _settled = MutableSharedFlow<FreeUpConsentSettlement>(
        replay = 0,
        // Multi-chunk grants settle sequentially; keep a small buffer so a slow collector does not
        // drop an intermediate chunk settlement.
        extraBufferCapacity = 8,
    )
    override val settled: SharedFlow<FreeUpConsentSettlement> = _settled.asSharedFlow()

    @Volatile
    override var offeredAtMs: Long? = null
        private set

    override fun offer(uris: List<String>) {
        if (uris.isEmpty()) return
        _uris.update { current ->
            if (current.isEmpty()) {
                offeredAtMs = System.currentTimeMillis()
            }
            current + uris
        }
    }

    override fun settle(uris: Set<String>, granted: Boolean) {
        if (uris.isEmpty()) return
        _uris.update { current ->
            val next = current - uris
            if (next.isEmpty()) offeredAtMs = null
            next
        }
        _settled.tryEmit(FreeUpConsentSettlement(uris = uris, granted = granted))
    }

    override fun settleAllPending(granted: Boolean) {
        val snapshot = _uris.value
        if (snapshot.isEmpty()) return
        settle(snapshot, granted)
    }

    /**
     * Silently drops [uris] without emitting a [FreeUpConsentSettlement].
     * Used by the just-in-time verifier to discard Drive-rejected URIs before consent launches.
     */
    override fun remove(uris: Set<String>) {
        if (uris.isEmpty()) return
        _uris.update { current ->
            val next = current - uris
            if (next.isEmpty()) offeredAtMs = null
            next
        }
    }
}
