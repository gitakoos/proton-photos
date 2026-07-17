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
import kotlinx.coroutines.CancellationException
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.crypto.CryptoServiceClient
import eu.akoos.photos.data.crypto.parsePhotoLocation
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.repository.drive.AlbumCryptoChain
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.data.repository.drive.PhotosShareService
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhotoLocationResolver"

/**
 * Recovers a cloud photo's GPS fix from its encrypted XAttr and persists it to [PhotoLocationEntity].
 * The decrypt chain (parent key → node key → XAttr → Location) is shared by the map's bounded
 * [CloudGpsBackfillScheduler] walk and by the download path, which needs a single photo's coordinates
 * on demand so an exported file keeps its location.
 *
 * The ONLY location source is the cloud XAttr Location block. A photo uploaded with GPS stripped has
 * no Location in its XAttr, so [locate] and [resolve] yield null for it and the caller writes nothing;
 * coordinates are never invented from any other source.
 *
 * Resolving the parent key mirrors [eu.akoos.photos.data.repository.drive.ThumbnailDecryptScheduler]:
 * the photo's nodeKey was encrypted to its parent link's key (the photos root, or an album). That
 * scheduler's `getParentKeyBytes` is private and seeded with a shared-album context the thumbnail path
 * owns, so rather than refactor a libgojni-sensitive file this replicates the two owner-side branches
 * against the same injected services, with a parent-key cache so successive photos in one parent skip
 * the second-tier decrypt. A photo whose parent key can't be resolved (e.g. a shared-with-me album)
 * yields null, like any other per-row failure.
 */
@Singleton
class PhotoLocationResolver @Inject constructor(
    private val cryptoServiceClient: CryptoServiceClient,
    private val photoListingDao: PhotoListingDao,
    private val photoLocationDao: PhotoLocationDao,
    private val shareService: PhotosShareService,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val albumCryptoChain: AlbumCryptoChain,
) {
    /** parentLinkId → decrypted parent key bytes, so photos sharing a parent skip the re-decrypt. */
    private val parentKeyCache = ConcurrentHashMap<String, ByteArray>()

    /**
     * Decrypt one [row]'s XAttr and return its location, or null when it carries no GPS / can't be
     * resolved. [armoredXAttr] is the row's cached XAttr or a value the caller fetched from the
     * revision endpoint. The parent-key resolution mirrors the thumbnail scheduler's owner-side branches.
     */
    suspend fun locate(userId: UserId, row: PhotoListingEntity, armoredXAttr: String?): PhotoLocationEntity? {
        val encXAttr = armoredXAttr ?: return null
        val encNodeKey = row.encNodeKey ?: return null
        val encNodePass = row.encNodePassphrase ?: return null
        val parentLinkId = row.parentLinkId ?: return null
        val parentKey = getParentKeyBytes(userId, parentLinkId, row.volumeId) ?: return null
        val nodeKeyBytes = cryptoServiceClient.decryptNodeKey(encNodeKey, encNodePass, parentKey)
        val json = cryptoServiceClient.decryptXAttr(encXAttr, nodeKeyBytes) ?: return null
        val (lat, lon) = parsePhotoLocation(json) ?: return null
        return PhotoLocationEntity(id = row.linkId, userId = userId.id, latitude = lat, longitude = lon)
    }

    /**
     * The persisted GPS fix for [linkId], resolved cache-first. A hit in `photo_location` returns with
     * no crypto work. A miss decrypts THAT ONE photo's XAttr (the row's cached blob, or one fetched
     * from its revision), persists the recovered fix so the next read is free, and returns it. Null when
     * the photo carries no GPS or its material can't be resolved, including a photo whose Location was
     * gated off at upload: it has no XAttr Location, so nothing is persisted and nothing is invented.
     */
    suspend fun resolve(userId: UserId, linkId: String): PhotoLocationEntity? {
        photoLocationDao.getById(userId.id, linkId)?.let { return it }
        val row = photoListingDao.getByLinkId(linkId) ?: return null
        val armoredXAttr = row.encXAttr ?: runCatching {
            linkDetailHelpers.fetchRevisionXAttrOrThrow(
                userId, row.volumeId, row.shareId, row.linkId, row.revisionId,
            )
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "resolve: XAttr fetch for $linkId failed: ${e.message}")
            null
        }
        val located = locate(userId, row, armoredXAttr) ?: return null
        runCatching { photoLocationDao.upsert(listOf(located)) }
            .onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "resolve: persist location for $linkId failed: ${e.message}")
            }
        return located
    }

    /**
     * Decrypted node-key bytes for [parentLinkId]:
     *   • Photos root link → [PhotosShareService.getRootLinkKeyBytes] (itself cached).
     *   • Owner-side album → fetch the album's BatchLinkDto, decrypt its nodeKey with the root key,
     *     and memoise in [parentKeyCache].
     * Returns null when the album link can't be fetched / decrypted (e.g. a shared-with-me album,
     * which the thumbnail path resolves through a context map this resolver is not seeded with).
     */
    private suspend fun getParentKeyBytes(userId: UserId, parentLinkId: String, volumeId: String): ByteArray? {
        if (parentLinkId == shareService.photosRootLinkId()) {
            return shareService.getRootLinkKeyBytes(userId)
        }
        parentKeyCache[parentLinkId]?.let { return it }

        val rootKey = shareService.getRootLinkKeyBytes(userId) ?: return null
        val albumDetail = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(parentLinkId))[parentLinkId]
            ?: run {
                Log.w(TAG, "getParentKeyBytes: album link $parentLinkId not in batch response")
                return null
            }
        val albumLink = albumDetail.link
        val albumNodeKey = albumLink.nodeKey ?: return null
        val albumNodePass = albumLink.nodePassphrase ?: return null
        val bytes = albumCryptoChain.decryptAlbumKey(
            nodeKeyArmored = albumNodeKey,
            nodePassphraseArmored = albumNodePass,
            parentKeyBytes = rootKey,
            contextHint = "photo-location-resolver albumLinkId=$parentLinkId",
        ) ?: return null
        parentKeyCache[parentLinkId] = bytes
        return bytes
    }
}
