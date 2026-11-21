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

package eu.akoos.photos.data.upload

import android.util.Log
import eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.ShortCloudCopyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShortCopyReplacer"

/**
 * Finishes the backup of a device file whose Drive copy was short, once its whole copy is up: the whole copy
 * joins the albums the short one was in, then the short one goes to Drive's trash (never deleted outright),
 * and a delete the pending delete queue held back for it is handed to the upload.
 */
@Singleton
class ShortCopyReplacer @Inject constructor(
    private val cloudRepo: DrivePhotoRepository,
    private val membershipDao: AlbumPhotoMembershipDao,
    private val shortCopies: ShortCloudCopyStore,
) {
    /** Whether [deviceUri] left the pending delete queue for a short Drive copy, so its whole backup deletes it. */
    suspend fun deleteAfterWholeBackup(deviceUri: String): Boolean = quietly(false) {
        shortCopies.deleteAfterWholeBackup(deviceUri)
    }

    /**
     * Whether [linkId], the link an upload of [deviceUri] returned, is a Drive copy recorded as short: the one
     * this upload replaces, or any other. Such a link is never taken for the file's whole copy.
     */
    suspend fun isShortCopy(deviceUri: String, linkId: String): Boolean = quietly(true) {
        linkId == shortCopies.replacedBy(deviceUri) || linkId in shortCopies.all()
    }

    /** The delete held back for [deviceUri] was handed on. */
    suspend fun deleteHandedOn(deviceUri: String) {
        quietly(Unit) { shortCopies.removeDeleteAfterWholeBackup(deviceUri) }
    }

    /**
     * [deviceUri] is backed up whole as [wholeLinkId], an upload that read and hashed every byte of the file.
     * The short copy it replaces, if any, goes to Drive's trash once [wholeLinkId] is in each of its albums;
     * an album that does not take it keeps the short copy where it is, for [retryPending] to try again.
     */
    suspend fun replaced(userId: UserId, deviceUri: String, wholeLinkId: String) {
        quietly(Unit) {
            val shortLinkId = shortCopies.replacedBy(deviceUri) ?: return@quietly
            if (shortLinkId == wholeLinkId) {
                shortCopies.removeReplacement(deviceUri)
                return@quietly
            }
            val albums = membershipDao.observeAlbumIdsForPhotos(setOf(shortLinkId)).first()
                .map { it.albumLinkId }.distinct()
            for (album in albums) {
                val added = runCatching { cloudRepo.addPhotosToAlbum(userId, album, listOf(wholeLinkId)) }
                    .getOrElse { e -> if (e is CancellationException) throw e else null }
                if (added == null || wholeLinkId !in added.succeededLinkIds) {
                    Log.w(TAG, "replaced: an album did not take the whole copy; the short copy stays")
                    return@quietly
                }
            }
            val outcome = cloudRepo.deleteFiles(userId, listOf(shortLinkId))
            if (shortLinkId in outcome.trashedLinkIds) {
                shortCopies.removeReplacement(deviceUri)
                Log.d(TAG, "replaced: short copy moved to Drive's trash")
            }
        }
    }

    /** [replaced] again for each recorded replacement whose device file is backed up whole ([rowOf]). */
    suspend fun retryPending(userId: UserId, rowOf: suspend (String) -> SyncState?) {
        for ((uri, shortLinkId) in quietly(emptyMap()) { shortCopies.replacements() }) {
            val row = quietly(null) { rowOf(uri) } ?: continue
            val whole = row.cloudFileId ?: continue
            if (row.status != SyncStatus.SYNCED || row.backedUpAtMs == null || whole == shortLinkId) continue
            replaced(userId, uri, whole)
        }
    }

    private suspend fun <T> quietly(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "${e::class.simpleName}")
        fallback
    }
}
