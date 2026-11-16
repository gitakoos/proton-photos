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

package eu.akoos.photos.data.hidden

import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.data.repository.drive.PhotosShareService
import eu.akoos.photos.util.SyncDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks Drive, at sign-out, which of the hidden photos' Drive copies it still holds.
 *
 * The vault records a photo's Drive copy when the photo is hidden, and a sign-out destroys the vault
 * file of a photo Drive holds. The copy may have been trashed or deleted since, so the record alone is
 * not enough to destroy the only device copy over: only Drive's own answer, given now, is.
 */
@Singleton
class HiddenDriveCopyCheck @Inject constructor(
    private val accountManager: AccountManager,
    private val shareService: PhotosShareService,
    private val linkDetails: LinkDetailHelpers,
) {

    /**
     * The link ids among [linkIds] that Drive confirms it holds outside its trash, asked as [userId].
     *
     * Empty whenever Drive cannot answer: no account, a session already ended, no network, or no answer
     * within [TIMEOUT_MS]. The caller then treats every one of those photos as having no Drive copy and
     * takes it back to the device, which can only cost a duplicate.
     */
    suspend fun liveOf(userId: UserId?, linkIds: Collection<String>): Set<String> {
        if (userId == null || linkIds.isEmpty()) return emptySet()
        val live = try {
            // A session the server ended cannot answer, and asking would only wait for the refusal.
            if (accountManager.getAccount(userId).first()?.sessionId == null) {
                emptySet()
            } else {
                withTimeoutOrNull(TIMEOUT_MS) {
                    val volumeId = shareService.getVolumeId(userId)
                    val details = linkDetails.batchFetchLinkDetails(userId, volumeId, linkIds.distinct(), strict = true)
                    HiddenVaultDecisions.liveCloudIds(details.mapValues { it.value.link.state })
                } ?: emptySet()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptySet()
        }
        SyncDiagnostics.log("sign-out: Drive confirmed ${live.size} of ${linkIds.size} hidden photo copies")
        return live
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
