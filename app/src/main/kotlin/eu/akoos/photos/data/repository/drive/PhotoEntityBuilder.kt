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

package eu.akoos.photos.data.repository.drive

import android.util.Log
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.api.dto.PhotoLinkDto
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhotoEntityBuilder"

/**
 * Builds [PhotoListingEntity] rows from a Photos-stream stub + per-link detail blob,
 * decrypting node keys / names / thumbnails along the way. Shared by [PhotoStreamService]
 * and `AlbumService`.
 *
 * Two callable modes:
 *   • `decryptThumbnail = true` (default, legacy behaviour): downloads + decrypts the
 *     encrypted thumbnail blob during the sync pass, writing its on-disk URL into
 *     [PhotoListingEntity.thumbnailUrl]. Used by paths that still want eager thumbnails
 *     (e.g. album cover prefetch).
 *   • `decryptThumbnail = false` (lazy path): decrypts cheap metadata only
 *     (displayName, revision id) and persists the ENCRYPTED material needed to do the
 *     thumbnail later (`serverThumbnailUrl`, `contentKeyPacket`, `encNodeKey`,
 *     `encNodePassphrase`, `parentLinkId`). [PhotoListingEntity.thumbnailUrl] stays
 *     null; the grid cell's on-screen visibility triggers [ThumbnailDecryptScheduler]
 *     to fill it in and observers re-emit the updated row.
 */
@Singleton
class PhotoEntityBuilder @Inject constructor(
    private val cryptoHelper: DriveCryptoHelper,
    private val cryptoContext: CryptoContext,
    private val thumbnailHelpers: ThumbnailHelpers,
) {
    suspend fun build(
        stub: PhotoLinkDto,
        detail: BatchLinkDto?,
        userId: UserId,
        shareId: String,
        volumeId: String,
        parentKeyBytes: ByteArray?,
        thumbnailCacheDir: File,
        thumbnailInfo: ThumbnailUrlInfo? = null,
        contentKeyPacket: String? = null,
        ownPublicKeys: List<String> = emptyList(),
        decryptThumbnail: Boolean = true,
        /** Extra decryption-key candidate offered for the photo Name decrypt path.
         *  Shared-with-me albums substitute the photo Name's PKESK to the share's
         *  encryption subkey, so the album-level parent key alone doesn't open it.
         *  Passing the share private key bytes here lets gopenpgp try both and pick
         *  whichever PKESK matches the photo's substituted target. Owner-side album
         *  opens leave this null and behave exactly as before. */
        fallbackParentKeyBytes: ByteArray? = null,
    ): PhotoListingEntity {
        val link = detail?.link
        var displayName = ""
        var revisionId = ""
        var thumbnailUrl: String? = null

        if (link != null && parentKeyBytes != null) {
            val nodeKeyArmored = link.nodeKey
            val nodePassphraseArmored = link.nodePassphrase
            if (nodeKeyArmored != null && nodePassphraseArmored != null) {
                try {
                    // The lazy path can SKIP unlocking the photo's node key — the encrypted
                    // material we persist is enough for the scheduler to redo this work later.
                    // Lifting the eager unlock saves the single most expensive crypto call per
                    // photo (libgojni's unlock + decryptData over the passphrase), which is
                    // exactly the JNI pressure we're trying to drop off the sync hot path.
                    val nodeKeyBytes: ByteArray? = if (decryptThumbnail || ownPublicKeys.isNotEmpty()) {
                        runCatching { cryptoHelper.decryptNodeKey(nodeKeyArmored, nodePassphraseArmored, parentKeyBytes) }
                            .getOrNull()
                    } else null

                    // Verify NodePassphraseSignature (detached) against own keys. Only attempted
                    // when we already decrypted the nodeKey (eager path); the lazy path defers
                    // verification to thumbnail-decrypt time too — it's a UI hint, not a hard
                    // requirement, and skipping it here keeps the metadata pass JNI-cheap.
                    if (nodeKeyBytes != null) {
                        val nodePassSig = link.nodePassphraseSignature
                        if (nodePassSig != null && ownPublicKeys.isNotEmpty()) {
                            val passphraseBytes = runCatching {
                                cryptoHelper.withCryptoLock {
                                    cryptoContext.pgpCrypto.decryptData(nodePassphraseArmored, parentKeyBytes)
                                }
                            }.getOrNull()
                            if (passphraseBytes != null) {
                                val ok = cryptoHelper.verifyDetachedSignature(passphraseBytes, nodePassSig, ownPublicKeys)
                                if (!ok) Log.w(TAG, "VERIFY_FAIL nodePassphrase linkId=${stub.linkId} signer=${link.signatureEmail}")
                            }
                        }
                    }

                    // Decrypt + verify Name (encrypted+signed PGP message). Best-effort verify.
                    // We ALWAYS decrypt the name even on the lazy path — the grid header / search /
                    // viewer caption read it synchronously while the cell is on-screen, so deferring
                    // it would visibly blank the metadata UI until the on-demand crypto completes.
                    // Decryption uses the PARENT key directly (Drive encrypts Link.Name to the
                    // parent, not the node key) which is already in-hand from the share cache.
                    link.name?.let { encName ->
                        val candidates = buildList {
                            add(parentKeyBytes)
                            fallbackParentKeyBytes?.let { add(it) }
                        }
                        val verified = cryptoHelper.decryptAndVerifyData(encName, candidates, ownPublicKeys)
                        if (verified != null) {
                            displayName = String(verified.data, Charsets.UTF_8)
                            if (!verified.verified && ownPublicKeys.isNotEmpty()) {
                                Log.w(TAG, "VERIFY_FAIL name linkId=${stub.linkId} signer=${link.signatureEmail}")
                            }
                        }
                    }

                    // Resolve revisionId.
                    // Priority (per official SDK):
                    //   1. Link.FileProperties.ActiveRevision.ID  ← canonical for file links
                    //   2. Photo.ActiveRevision.RevisionID        ← Photos-API field
                    //   3. Link.ActiveRevision.ID                 ← legacy / folder fallback
                    val filePropsRevision = link.fileProperties?.activeRevision
                    val photoRevision = detail?.photo?.activeRevision
                    val linkRevision = link.activeRevision
                    val resolvedRevId = filePropsRevision?.id?.takeIf { it.isNotEmpty() }
                        ?: photoRevision?.revisionId?.takeIf { it.isNotEmpty() }
                        ?: linkRevision?.id?.takeIf { it.isNotEmpty() }
                    if (resolvedRevId != null) {
                        revisionId = resolvedRevId
                    } else {
                        Log.w(TAG, "build: no revisionId for ${stub.linkId}")
                    }

                    // Thumbnail: only do the expensive download+decrypt on the eager path.
                    // The lazy path leaves thumbnailUrl null and persists the cipher inputs
                    // below — [ThumbnailDecryptScheduler] consumes them when the cell becomes
                    // visible.
                    if (decryptThumbnail && nodeKeyBytes != null && thumbnailInfo != null) {
                        val ckp = contentKeyPacket
                            ?: detail?.photo?.contentKeyPacket
                            ?: detail?.link?.fileProperties?.contentKeyPacket
                        val thumbSessionKey = ckp?.let {
                            runCatching { cryptoHelper.decryptSessionKey(it, nodeKeyBytes) }
                                .getOrElse { e ->
                                    Log.w(TAG, "thumbnail CKP decrypt failed for ${stub.linkId}: ${e.message}")
                                    null
                                }
                        }
                        thumbnailUrl = thumbnailHelpers.downloadAndDecryptBinary(
                            thumbnailInfo, nodeKeyBytes, thumbSessionKey, stub.linkId, thumbnailCacheDir,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "crypto failed for link=${stub.linkId}: ${e.message}")
                }
            }
        }

        val rawMimeType = link?.mimeType ?: ""
        val mimeType = rawMimeType.ifEmpty { guessMimeType(displayName) }

        // ContentHash priority: photo metadata (set at upload), then the stub's own hash field.
        val contentHash = detail?.photo?.contentHash ?: stub.contentHash

        // Tags: server-side PhotoTag ids (0 = Favorite). Read from the photo-stream stub primarily;
        // the batch-link response sometimes echoes the same list back via detail.photo.tags.
        val tags = (stub.tags + (detail?.photo?.tags ?: emptyList())).toSet()
        val tagsCsv = tags.joinToString(",")

        // Lazy-path metadata: persist whatever the scheduler will need later.
        //
        // Skipping this entirely on the eager path would force a follow-up sync to rewrite every
        // row. Cheap to keep populated regardless of decrypt mode — these are all already-in-hand
        // strings from `detail`, no extra crypto required.
        val resolvedCkp = contentKeyPacket
            ?: detail?.photo?.contentKeyPacket
            ?: detail?.link?.fileProperties?.contentKeyPacket

        // Encrypted XAttr for the active revision — canonical for file links is FileProperties.ActiveRevision,
        // with the legacy Link.ActiveRevision as fallback. Stored verbatim; decrypted off the read path.
        val resolvedEncXAttr = link?.fileProperties?.activeRevision?.xAttr
            ?: link?.activeRevision?.xAttr

        return PhotoListingEntity(
            linkId = stub.linkId,
            shareId = shareId,
            volumeId = volumeId,
            userId = userId.id,
            captureTime = stub.captureTime,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = link?.size ?: 0L,
            revisionId = revisionId,
            thumbnailUrl = thumbnailUrl,
            contentHash = contentHash,
            tagsCsv = tagsCsv,
            serverThumbnailUrl = thumbnailInfo?.bareUrl,
            serverThumbnailToken = thumbnailInfo?.token,
            contentKeyPacket = resolvedCkp,
            encNodeKey = link?.nodeKey,
            encNodePassphrase = link?.nodePassphrase,
            parentLinkId = link?.parentLinkId,
            encXAttr = resolvedEncXAttr,
        )
    }
}

internal fun guessMimeType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "mp4", "m4v", "3gp", "ts" -> "video/mp4"
    "mov"                      -> "video/quicktime"
    "avi"                      -> "video/x-msvideo"
    "mkv"                      -> "video/x-matroska"
    "webm"                     -> "video/webm"
    "jpg", "jpeg"              -> "image/jpeg"
    "png"                      -> "image/png"
    "gif"                      -> "image/gif"
    "webp"                     -> "image/webp"
    "heic", "heif"             -> "image/heic"
    else                       -> ""
}
