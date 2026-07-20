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

package eu.akoos.photos.data.repository.drive

import android.util.Log
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.dao.CloudAlbumDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SharedAlbumKeys"

/**
 * albumLinkId to the [AlbumCryptoChain.SharingContext] that unlocks an album another user shared
 * with this one. One place for the answer, so every reader of such an album (the photo grid, the
 * video-length pass, the full-resolution download) reaches the same share id and album key without
 * each running its own bootstrap.
 *
 * Two ways in. [put] seeds the bundle an album open already decrypted, and [contextFor] answers from
 * the cache or bootstraps the chain itself on a miss. The bootstrap matters because seeding happens
 * when the album is opened, while a process restart with cached rows can put one of its photos on
 * screen first, and a download that gave up at that point would look exactly like a photo whose
 * content cannot be read at all.
 *
 * In memory only. Share key bytes are never persisted, matching the note on
 * [ThumbnailDecryptScheduler]'s own map, and the next open of the album re-seeds this.
 *
 * [ThumbnailDecryptScheduler] deliberately keeps its own copy rather than reading this store: its map
 * does double duty with that class's `parentKeyCache`, and the thumbnail path is proven on device.
 * Leave it as it stands.
 */
@Singleton
class SharedAlbumKeyStore @Inject constructor(
    private val apiProvider: ApiProvider,
    private val cryptoHelper: DriveCryptoHelper,
    private val cloudAlbumDao: CloudAlbumDao,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val albumCryptoChain: AlbumCryptoChain,
    private val shareService: PhotosShareService,
) {
    private val contexts = ConcurrentHashMap<String, AlbumCryptoChain.SharingContext>()

    /** Serialises the bootstrap so a burst of photos from one album resolves the share once rather
     *  than once per photo. Held across the round trips on purpose: the cache re-check inside means
     *  every waiter past the first returns the first one's result. */
    private val bootstrapLock = Mutex()

    /** Register the bundle an album open decrypted. Pure memory, so a load path can call it inline. */
    fun put(ctx: AlbumCryptoChain.SharingContext) {
        contexts[ctx.albumLinkId] = ctx
    }

    /**
     * The crypto bundle for [albumLinkId], bootstrapping the share chain when nothing is cached yet.
     *
     * Null means "not an album shared with this user", which is the answer for every album the user
     * owns and for the photos root, and it is also what any failed step returns, so a caller can read
     * a null as "stay on the owner path" in both cases.
     */
    suspend fun contextFor(userId: UserId, albumLinkId: String): AlbumCryptoChain.SharingContext? {
        contexts[albumLinkId]?.let { return it }
        return bootstrapLock.withLock {
            contexts[albumLinkId] ?: bootstrap(userId, albumLinkId)?.also { contexts[albumLinkId] = it }
        }
    }

    /**
     * Walk the share chain for [albumLinkId] the way an album open does: the cached album row names
     * the sharing share, the bootstrap yields the share key, and the album link fetched THROUGH that
     * share carries the node key the album key unwraps from. The album lives on the owner's volume,
     * so the volume-scoped link endpoint cannot see it and only the share endpoint answers.
     *
     * `sharedByEmail` is the durable record of foreign ownership on a cached row, so an absent row or
     * an empty value ends the walk before any network work.
     */
    private suspend fun bootstrap(userId: UserId, albumLinkId: String): AlbumCryptoChain.SharingContext? {
        try {
            val album = cloudAlbumDao.getByLinkId(albumLinkId) ?: return null
            if (album.sharedByEmail == null) return null
            val sharingShareId = album.sharingShareId ?: return null

            val manager = apiProvider.get<DriveApiService>(userId)
            val shareBootstrap = shareService.networkSemaphore.withPermit {
                manager.invoke { getShareBootstrap(sharingShareId) }.valueOrThrow
            }
            val shareKeyBytes = cryptoHelper.decryptExternalShareKey(
                userId,
                shareBootstrap.key ?: return null,
                shareBootstrap.passphrase ?: return null,
            )

            val albumLink = linkDetailHelpers
                .batchFetchLinkDetailsViaShare(userId, sharingShareId, listOf(albumLinkId))[albumLinkId]
                ?.link ?: return null
            val albumKeyBytes = albumCryptoChain.decryptAlbumKey(
                nodeKeyArmored = albumLink.nodeKey ?: return null,
                nodePassphraseArmored = albumLink.nodePassphrase ?: return null,
                parentKeyBytes = shareKeyBytes,
                contextHint = "key store albumLinkId=$albumLinkId shareId=$sharingShareId",
            ) ?: return null

            Log.d(TAG, "bootstrapped shared album $albumLinkId via share $sharingShareId")
            return AlbumCryptoChain.SharingContext(
                albumLinkId = albumLinkId,
                sharingShareId = sharingShareId,
                sharedShareKeyBytes = shareKeyBytes,
                albumKeyBytes = albumKeyBytes,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "bootstrap of shared album $albumLinkId failed: ${e.message}")
            return null
        }
    }
}
