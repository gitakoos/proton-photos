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

package eu.akoos.photos.presentation.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.freeup.cancelFreeUpActionRequiredNotification
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.FreeUpPendingDeleteQueue
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.repository.isFreeUpPendingOfferStale
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * Permanent-delete consent for free-up reclaim via [FreeUpPendingDeleteQueue]. Hard-separated from
 * trash / [eu.akoos.photos.data.preferences.SettingsKeys.PENDING_DELETE_URIS].
 *
 * Mounted only on the Free up route so [android.provider.MediaStore.createDeleteRequest] never
 * appears over the editor, viewer, or other screens. On entry, offers older than
 * [eu.akoos.photos.domain.repository.FREE_UP_PENDING_OFFER_STALE_MS] are cleared so Continue can
 * re-verify Drive before another delete dialog.
 *
 * Grant settles the consented chunk even when the status update fails (files are already gone).
 * Deny settles the entire pending queue so remaining chunks are not re-offered.
 * Technical launch failure clears the offer (not as user denial) so Continue can re-verify.
 */
@HiltViewModel
class FreeUpPermanentDeleteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: FreeUpPendingDeleteQueue,
    private val syncStateRepo: SyncStateRepository,
    private val freeUpSpace: FreeUpSpaceUseCase,
    private val accountManager: AccountManager,
) : ViewModel() {

    val pendingUris: StateFlow<Set<String>> = bridge.uris

    /** Clears a stale in-memory offer so Free up can Continue with a fresh pipeline run. */
    fun clearStaleOfferIfNeeded(nowMs: Long = System.currentTimeMillis()) {
        if (!isFreeUpPendingOfferStale(bridge.offeredAtMs, nowMs)) return
        bridge.settleAllPending(granted = false)
        cancelFreeUpActionRequiredNotification(context)
    }

    /**
     * Strict just-in-time Drive verification before [android.provider.MediaStore.createDeleteRequest].
     * Rejected URIs leave the free-up queue via [FreeUpPendingDeleteQueue.remove] (no settlement).
     */
    suspend fun prepareBatch(batch: Set<String>): Set<String> {
        if (batch.isEmpty()) return emptySet()
        val userId = accountManager.getPrimaryUserId().first()
        if (userId == null) {
            bridge.remove(batch)
            if (bridge.uris.value.isEmpty()) {
                cancelFreeUpActionRequiredNotification(context)
            }
            return emptySet()
        }
        val verified = freeUpSpace.verifyPendingConsentBatch(userId, batch)
        val rejected = batch - verified
        if (rejected.isNotEmpty()) {
            bridge.remove(rejected)
            if (bridge.uris.value.isEmpty()) {
                cancelFreeUpActionRequiredNotification(context)
            }
        }
        return verified
    }

    fun onConsentGranted(uris: Set<String>) {
        viewModelScope.launch {
            runCatching {
                syncStateRepo.updateStatusAndDeleteLocal(uris.toList(), SyncStatus.CLOUD_ONLY)
            }
            // Always settle: RESULT_OK means MediaStore already deleted; a failed status write
            // must not leave the queue blocking Free up.
            bridge.settle(uris, granted = true)
            if (bridge.uris.value.isEmpty()) {
                cancelFreeUpActionRequiredNotification(context)
            }
        }
    }

    fun onConsentDenied(@Suppress("UNUSED_PARAMETER") uris: Set<String>) {
        viewModelScope.launch {
            bridge.settleAllPending(granted = false)
            cancelFreeUpActionRequiredNotification(context)
        }
    }

    /**
     * Exhausted createDeleteRequest / launcher failure. Clears the permanent offer so Continue can
     * re-run verification. Does **not** route through [onConsentDenied] / user-deny messaging.
     */
    fun onLaunchFailed(@Suppress("UNUSED_PARAMETER") uris: Set<String>) {
        viewModelScope.launch {
            bridge.settleAllPending(granted = false)
            cancelFreeUpActionRequiredNotification(context)
        }
    }
}

@Composable
fun FreeUpPermanentDeleteHandler(
    viewModel: FreeUpPermanentDeleteViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) {
        viewModel.clearStaleOfferIfNeeded()
    }
    MediaStoreConsentHandler(
        pendingUris = viewModel.pendingUris,
        mode = MediaStoreConsentMode.PermanentDelete,
        settleDelayMs = 0L,
        prepareBatch = viewModel::prepareBatch,
        onGranted = viewModel::onConsentGranted,
        onDenied = viewModel::onConsentDenied,
        onLaunchFailed = viewModel::onLaunchFailed,
    )
}
