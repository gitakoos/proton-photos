/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.domain.usecase

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import javax.inject.Inject

class FreeUpSpaceUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateRepo: SyncStateRepository,
) {
    sealed class FreeUpResult {
        data class Done(val freed: Int) : FreeUpResult()
        /** Android 11+: system delete dialog must be shown for these URIs. */
        data class NeedsPermission(
            val pendingIntent: PendingIntent,
            val localUris: List<String>,
        ) : FreeUpResult()
    }

    suspend operator fun invoke(userId: UserId, olderThanMs: Long): FreeUpResult {
        val candidates = syncStateRepo.getSyncedBefore(olderThanMs)
            .filter { it.status == SyncStatus.SYNCED }

        var freed = 0
        val needsDialog = mutableListOf<Pair<String, Uri>>()  // localUri → contentUri

        for (state in candidates) {
            val contentUri = Uri.parse(state.localUri)
            try {
                val deleted = context.contentResolver.delete(contentUri, null, null)
                if (deleted > 0) {
                    syncStateRepo.updateStatusAndDeleteLocal(state.localUri, SyncStatus.CLOUD_ONLY)
                    freed++
                } else {
                    // delete() returned 0 — likely needs permission on API 30+
                    needsDialog += state.localUri to contentUri
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // SecurityException / RecoverableSecurityException on Android 11+
                needsDialog += state.localUri to contentUri
            }
        }

        if (needsDialog.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pi = MediaStore.createDeleteRequest(
                context.contentResolver,
                needsDialog.map { it.second },
            )
            return FreeUpResult.NeedsPermission(pi, needsDialog.map { it.first })
        }

        return FreeUpResult.Done(freed)
    }
}
