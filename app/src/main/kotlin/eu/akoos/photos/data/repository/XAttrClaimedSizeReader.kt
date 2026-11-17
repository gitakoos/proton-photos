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

import android.util.Log
import eu.akoos.photos.crypto.CryptoServiceClient
import eu.akoos.photos.crypto.DecryptPriority
import eu.akoos.photos.crypto.DecryptPriorityContext
import eu.akoos.photos.data.crypto.parseXAttrClaimedSize
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.repository.drive.AlbumCryptoChain
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.data.repository.drive.PhotosShareService
import eu.akoos.photos.domain.repository.DriveCopyClaimedSize
import eu.akoos.photos.domain.repository.DriveCopyXAttr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "XAttrClaimedSize"

/**
 * [DriveCopyClaimedSize] read from the photo's XAttr: the listing's cached blob, or, when asked, the one its
 * revision returns. The decrypt chain (parent key, node key, XAttr) is [PhotoLocationResolver]'s, run at
 * background priority so it yields to what the user is looking at.
 */
@Singleton
class XAttrClaimedSizeReader @Inject constructor(
    private val cryptoServiceClient: CryptoServiceClient,
    private val photoListingDao: PhotoListingDao,
    private val shareService: PhotosShareService,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val albumCryptoChain: AlbumCryptoChain,
) : DriveCopyClaimedSize {
    private val parentKeyCache = ConcurrentHashMap<String, ByteArray>()

    override suspend fun claimedSize(
        userId: UserId,
        linkId: String,
        fetchIfUncached: Boolean,
        fresh: DriveCopyXAttr?,
    ): Long? = try {
        withContext(DecryptPriorityContext(DecryptPriority.BACKGROUND)) {
            if (fresh != null) fromFresh(userId, fresh)?.let { return@withContext it }
            val row = photoListingDao.getByLinkId(linkId) ?: return@withContext null
            val fetched = if (row.encXAttr == null && fetchIfUncached) {
                linkDetailHelpers.fetchRevisionXAttrOrThrow(userId, row.volumeId, row.shareId, row.linkId, row.revisionId)
            } else {
                null
            }
            val armored = row.encXAttr ?: fetched ?: return@withContext null
            val encNodeKey = row.encNodeKey ?: return@withContext null
            val encNodePass = row.encNodePassphrase ?: return@withContext null
            val parentLinkId = row.parentLinkId ?: return@withContext null
            val parentKey = parentKeyBytes(userId, parentLinkId, row.volumeId) ?: return@withContext null
            val nodeKeyBytes = cryptoServiceClient.decryptNodeKey(encNodeKey, encNodePass, parentKey)
            cryptoServiceClient.decryptXAttr(armored, nodeKeyBytes)?.let(::parseXAttrClaimedSize)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "claimedSize: ${e::class.simpleName}")
        null
    }

    /** The declared size in [fresh]; null when it does not open, and the cached XAttr is tried instead. */
    private suspend fun fromFresh(userId: UserId, fresh: DriveCopyXAttr): Long? = try {
        val parentKey = parentKeyBytes(userId, fresh.parentLinkId, fresh.volumeId)
        if (parentKey == null) {
            null
        } else {
            val nodeKeyBytes = cryptoServiceClient.decryptNodeKey(fresh.nodeKey, fresh.nodePassphrase, parentKey)
            cryptoServiceClient.decryptXAttr(fresh.armoredXAttr, nodeKeyBytes)?.let(::parseXAttrClaimedSize)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "claimedSize fresh: ${e::class.simpleName}")
        null
    }

    /** The photos root's key, or an owner-side album's, memoised; null for anything else. */
    private suspend fun parentKeyBytes(userId: UserId, parentLinkId: String, volumeId: String): ByteArray? {
        if (parentLinkId == shareService.photosRootLinkId()) return shareService.getRootLinkKeyBytes(userId)
        parentKeyCache[parentLinkId]?.let { return it }
        val rootKey = shareService.getRootLinkKeyBytes(userId) ?: return null
        val album = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(parentLinkId))[parentLinkId]?.link
            ?: return null
        val bytes = albumCryptoChain.decryptAlbumKey(
            nodeKeyArmored = album.nodeKey ?: return null,
            nodePassphraseArmored = album.nodePassphrase ?: return null,
            parentKeyBytes = rootKey,
            contextHint = "claimed-size albumLinkId=$parentLinkId",
        ) ?: return null
        parentKeyCache[parentLinkId] = bytes
        return bytes
    }
}
