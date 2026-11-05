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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.di.AppScope
import eu.akoos.photos.domain.repository.LocalMediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Called wherever a folder's album mirror is turned on: the photos that folder already backed up join
 * its Drive album too, so nobody has to add them by hand. Runs on the app scope, because the switch's
 * screen can close long before a big folder is done; a guest has no Drive and nothing to join.
 */
@Singleton
class FolderAlbumBackfill @Inject constructor(
    private val accountManager: AccountManager,
    private val upload: UploadPendingUseCase,
    private val localRepo: LocalMediaRepository,
    private val drivePairs: DrivePairLookup,
    @AppScope private val appScope: CoroutineScope,
) {
    fun onAlbumTurnedOn(bucketName: String) {
        if (bucketName.isEmpty()) return
        appScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching {
                val items = localRepo.queryByBucket(bucketName)
                val onDrive = drivePairs.pairs(
                    userId,
                    items.associate { it.uri to it.displayName },
                    items.associate { it.uri to it.dateTaken },
                )
                upload.backfillFolderAlbum(userId, bucketName, onDrive)
            }
                .onSuccess { eu.akoos.photos.util.SyncDiagnostics.log("folder album backfill: $it photo(s) queued") }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    eu.akoos.photos.util.SyncDiagnostics.log(
                        "folder album backfill FAILED: " + eu.akoos.photos.util.describeUploadFailure(e),
                    )
                }
        }
    }
}
