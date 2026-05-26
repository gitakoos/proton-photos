package me.proton.photos.data.repository.drive

import android.util.Log
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.domain.entity.UserId
import me.proton.photos.data.api.dto.BatchLinkDto
import me.proton.photos.data.api.dto.PhotoLinkDto
import me.proton.photos.data.crypto.DriveCryptoHelper
import me.proton.photos.data.db.entity.PhotoListingEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhotoEntityBuilder"

/**
 * Builds [PhotoListingEntity] rows from a Photos-stream stub + per-link detail blob,
 * decrypting node keys / names / thumbnails along the way. Shared by [PhotoStreamService]
 * and `AlbumService`.
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
                    val nodeKeyBytes = cryptoHelper.decryptNodeKey(nodeKeyArmored, nodePassphraseArmored, parentKeyBytes)

                    // Verify NodePassphraseSignature (detached) against own keys. The signer is
                    // link.signatureEmail; we only fast-verify when that's our primary address.
                    val nodePassSig = link.nodePassphraseSignature
                    if (nodePassSig != null && ownPublicKeys.isNotEmpty()) {
                        val passphraseBytes = runCatching {
                            cryptoContext.pgpCrypto.decryptData(nodePassphraseArmored, parentKeyBytes)
                        }.getOrNull()
                        if (passphraseBytes != null) {
                            val ok = cryptoHelper.verifyDetachedSignature(passphraseBytes, nodePassSig, ownPublicKeys)
                            if (!ok) Log.w(TAG, "VERIFY_FAIL nodePassphrase linkId=${stub.linkId} signer=${link.signatureEmail}")
                        }
                    }

                    // Decrypt + verify Name (encrypted+signed PGP message). Best-effort verify.
                    link.name?.let { encName ->
                        val verified = cryptoHelper.decryptAndVerifyData(encName, parentKeyBytes, ownPublicKeys)
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

                    // Thumbnail: SEIPD-only format (same as content blocks), decrypted with
                    // the session key from ContentKeyPacket. Download URL pre-fetched in bulk.
                    if (thumbnailInfo != null) {
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
