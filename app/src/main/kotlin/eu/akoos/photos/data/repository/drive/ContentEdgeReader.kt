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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.crypto.CryptoServiceClient
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.api.dto.RevisionBlockDto
import eu.akoos.photos.domain.entity.CloudPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.crypto.common.pgp.SessionKey
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A photo's first content block and its last blocks run together, each null when not asked for or when the
 *  file has no blocks beyond the first, out of [blockCount]. */
class ContentEdges(val first: ByteArray?, val last: ByteArray?, val blockCount: Int)

/** Reads the first and last content blocks of a cloud photo into memory, for the map's file GPS fallback. */
@Singleton
class ContentEdgeReader @Inject constructor(
    private val apiProvider: ApiProvider,
    private val cryptoServiceClient: CryptoServiceClient,
    private val shareService: PhotosShareService,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val cdnBlockFetcher: CdnBlockFetcher,
    @ApplicationContext private val context: Context,
) {
    /**
     * The decrypted bytes of [photo]'s first content block (with [withFirst]) and of its last [tailBlocks]
     * blocks run together, never reaching back to the first, held in memory only. Fetched through the app's
     * own revision and block path, never the SDK stream, so nothing beyond those blocks is downloaded and
     * nothing lands in the resume cache. For a photo on the user's own volume only.
     */
    suspend fun readContentEdges(userId: UserId, photo: CloudPhoto, withFirst: Boolean, tailBlocks: Int): ContentEdges =
        withContext(Dispatchers.IO) {
            val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId) ?: error("Cannot decrypt root link key")
            val manager = apiProvider.get<DriveApiService>(userId)
            val linkDetail = linkDetailHelpers.batchFetchLinkDetails(userId, photo.volumeId, listOf(photo.linkId), strict = true)[photo.linkId]
                ?: throw PhotoUnavailableException("Link not found: ${photo.linkId}")
            val link = linkDetail.link
            val nodeKeyArmored = link.nodeKey ?: throw PhotoUnavailableException("No nodeKey for ${photo.linkId}")
            val nodePassphraseArmored = link.nodePassphrase
                ?: throw PhotoUnavailableException("No nodePassphrase for ${photo.linkId}")
            val parentLinkId = link.parentLinkId
            val parentKeyBytes = if (parentLinkId == null || parentLinkId == shareService.photosRootLinkId()) {
                rootLinkKeyBytes
            } else {
                val albumLink = linkDetailHelpers.batchFetchLinkDetails(userId, photo.volumeId, listOf(parentLinkId), strict = true)[parentLinkId]?.link
                    ?: throw PhotoUnavailableException("Parent album link not found for ${photo.linkId}: $parentLinkId")
                cryptoServiceClient.decryptNodeKey(
                    albumLink.nodeKey ?: throw PhotoUnavailableException("No nodeKey on parent album $parentLinkId"),
                    albumLink.nodePassphrase ?: throw PhotoUnavailableException("No nodePassphrase on parent album $parentLinkId"),
                    rootLinkKeyBytes,
                )
            }
            val nodeKeyBytes = cryptoServiceClient.decryptNodeKey(nodeKeyArmored, nodePassphraseArmored, parentKeyBytes)
            val revisionId = photo.revisionId.ifEmpty {
                link.fileProperties?.activeRevision?.id?.takeIf { it.isNotEmpty() }
                    ?: linkDetail.photo?.activeRevision?.revisionId?.takeIf { it.isNotEmpty() }
                    ?: link.activeRevision?.id?.takeIf { it.isNotEmpty() }
            } ?: throw PhotoUnavailableException("No revisionId for ${photo.linkId}")
            val revision = shareService.networkSemaphore.withPermit {
                manager.invoke { getRevisionByVolume(photo.volumeId, photo.linkId, revisionId) }.valueOrThrow
            }.revision
            val ckp = linkDetail.photo?.contentKeyPacket
                ?: link.fileProperties?.contentKeyPacket
                ?: revision.contentKeyPacket
                ?: revision.photo?.contentKeyPacket
                ?: link.activeRevision?.contentKeyPacket
            val sessionKey = ckp?.let { cryptoServiceClient.decryptSessionKey(it, nodeKeyBytes) }
            val blocks = revision.blocks.sortedBy { it.index }
            if (blocks.isEmpty()) throw PhotoUnavailableException("No blocks in revision for ${photo.linkId}")
            val first = if (withFirst) decryptBlockInMemory(blocks.first(), photo.linkId, sessionKey, nodeKeyBytes) else null
            val tail = blocks.drop(1).takeLast(tailBlocks.coerceAtLeast(0))
            val last = if (tail.isEmpty()) null else {
                tail.map { decryptBlockInMemory(it, photo.linkId, sessionKey, nodeKeyBytes) }
                    .fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            }
            ContentEdges(first, last, blocks.size)
        }

    /** One content block fetched and decrypted into memory; only its ciphertext touches disk, briefly. */
    private suspend fun decryptBlockInMemory(
        block: RevisionBlockDto,
        linkId: String,
        sessionKey: SessionKey?,
        nodeKeyBytes: ByteArray,
    ): ByteArray {
        val bareUrl = block.bareUrl ?: block.url ?: throw PhotoUnavailableException("No URL for block ${block.index} of $linkId")
        val encBytes = cdnBlockFetcher.fetchBlock(url = bareUrl, token = block.token, maxAttempts = 3, background = true)
        val encFile = File.createTempFile("edge_", ".bin", context.cacheDir)
        return try {
            encFile.writeBytes(encBytes)
            val viaSessionKey = sessionKey?.let { key ->
                try {
                    cryptoServiceClient.decryptWithSessionKey(key, encFile)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            viaSessionKey
                ?: cryptoServiceClient.decryptBinaryFile(encFile, nodeKeyBytes)
                ?: throw PhotoUnavailableException("Block ${block.index} of $linkId did not decrypt")
        } finally {
            encFile.delete()
        }
    }
}
