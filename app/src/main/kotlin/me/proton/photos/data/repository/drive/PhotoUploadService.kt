package me.proton.photos.data.repository.drive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.photos.data.api.DriveApiService
import me.proton.photos.data.api.dto.BlockUploadInfoDto
import me.proton.photos.data.api.dto.CommitBlockDto
import me.proton.photos.data.api.dto.CommitRevisionRequest
import me.proton.photos.data.api.dto.CommitRevisionV2Request
import me.proton.photos.data.api.dto.CommitThumbnailDto
import me.proton.photos.data.api.dto.CreateFileRequest
import me.proton.photos.data.api.dto.CreatePhotoLinkData
import me.proton.photos.data.api.dto.CreatePhotoMetadata
import me.proton.photos.data.api.dto.CreatePhotoRequest
import me.proton.photos.data.api.dto.PhotoMetaDto
import me.proton.photos.data.api.dto.ThumbnailUploadInfoDto
import me.proton.photos.data.api.dto.UploadBlockRequest
import me.proton.photos.data.api.dto.VerifierDto
import me.proton.photos.data.crypto.DriveCryptoHelper
import me.proton.photos.data.db.dao.PhotoListingDao
import me.proton.photos.data.db.entity.PhotoListingEntity
import me.proton.photos.domain.entity.LocalMediaItem
import me.proton.photos.util.retryWithBackoff
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhotoUploadSvc"

/**
 * Per-file streaming upload pipeline.
 *
 * Memory profile: each 4 MB block is encrypted, signed, hashed, verifier-token-computed,
 * then spilled to `cacheDir/upload_$fileId/block_$idx.enc`. Per-block metadata
 * (file ref, signature, hash, verifier, encrypted size) lives in heap as O(N × ~256 B).
 * The CDN upload loop reads each block off disk just before the PUT and deletes it
 * after success. try/finally wipes tempDir on every exit path.
 */
@Singleton
class PhotoUploadService @Inject constructor(
    private val apiProvider: ApiProvider,
    private val cryptoHelper: DriveCryptoHelper,
    private val cryptoContext: CryptoContext,
    private val photoListingDao: PhotoListingDao,
    private val shareService: PhotosShareService,
    private val recentUploadsTracker: RecentUploadsTracker,
    @ApplicationContext private val context: Context,
) {
    private val semaphore get() = shareService.networkSemaphore

    /**
     * Bounded pool for raw block + thumbnail CDN PUTs. Sized to 2: enough to saturate
     * most consumer uplinks without overwhelming the CDN. Kept SEPARATE from
     * [PhotosShareService.networkSemaphore] so a multi-block upload's CDN traffic doesn't
     * starve regular Proton API calls (metadata refresh, heartbeat, etc.).
     */
    private val cdnUploadSemaphore = Semaphore(2)

    suspend fun uploadFile(userId: UserId, item: LocalMediaItem, hash: String, uploadUri: String): String =
        withContext(Dispatchers.IO) {
            val volumeId = shareService.getVolumeId(userId)
            val shareId = shareService.getShareId(userId, volumeId)
            val rootLinkId = shareService.photosRootLinkId() ?: error("Root link ID not loaded")

            val signingKey = cryptoHelper.getAddressSigningKey(userId)

            // Generate per-file node key
            // NodePassphrase must be encrypted to the PARENT link's public key.
            // The parent is the root link (photos root), so we use the root link's armored key.
            val nodeKey = cryptoHelper.generateNodeKey()
            val rootLinkArmoredKey = shareService.rootLinkArmoredKey()
                ?: run { shareService.getRootLinkKeyBytes(userId); shareService.rootLinkArmoredKey() }
                ?: error("Root link armored key not available")
            val rootLinkPublicKey = cryptoContext.pgpCrypto.getPublicKey(rootLinkArmoredKey)
            val nodePassphraseEncrypted = cryptoHelper.encryptDataToPgpMessage(nodeKey.passphraseBytes, rootLinkPublicKey)
            // Detached PGP signature — the Drive API requires plain signData here (same as web client).
            val nodePassphraseSignature = cryptoHelper.signData(nodeKey.passphraseBytes, signingKey.unlockedKeyBytes)

            // Name must be encrypted to the PARENT's public key (root link), not the file's own node key.
            // Must also be signed so the web client can verify nameAuthor.
            val encryptedName = cryptoHelper.encryptName(item.displayName, rootLinkPublicKey, signingKey.unlockedKeyBytes)

            // The Hash field is HMAC-SHA256(plaintextName, parentNodeHashKey) — used by the server
            // for name-collision detection.  Fall back to file content hash if the root NodeHashKey
            // isn't available (server may reject this but we avoid crashing).
            val nameHashForCreate = shareService.rootNodeHashKeyBytes()?.let {
                cryptoHelper.computeNameHash(item.displayName, it)
            } ?: hash.also { Log.w(TAG, "uploadFile: rootNodeHashKey not cached — using content hash as fallback") }

            // Generate session key for content
            val sessionKey = cryptoHelper.generateSessionKey()
            val contentKeyPacketBytes = cryptoHelper.encryptSessionKeyToNode(sessionKey, nodeKey.publicKeyArmored)
            val contentKeyPacketBase64 = cryptoHelper.base64Encode(contentKeyPacketBytes)
            // ContentKeyPacketSignature must be a detached signature of the RAW SESSION KEY BYTES
            // (sessionKey.key — the plaintext symmetric key, e.g. 32 bytes for AES-256), NOT the
            // encrypted key packet.  The Drive web client decrypts the ContentKeyPacket first to
            // recover the session key bytes, then verifies the signature against those bytes.
            // Signing the PKESK bytes causes "Signed digest did not match" because the verifier
            // presents the decrypted session key while we signed the encrypted packet.
            // Reference: android-drive GenerateContentKey.kt — signData(nodeKey, sessionKey.key)
            val contentKeyPacketSignature = cryptoHelper.signData(sessionKey.key, signingKey.unlockedKeyBytes)

            val manager = apiProvider.get<DriveApiService>(userId)

            // Create photo file record.
            // Try the Photos-stream endpoint first; fall back to the volume-based v2 path.
            val fileRequest = CreateFileRequest(
                name = encryptedName,
                hash = nameHashForCreate,
                parentLinkId = rootLinkId,
                mimeType = item.mimeType,
                nodeKey = nodeKey.armoredPrivateKey,
                nodePassphrase = nodePassphraseEncrypted,
                nodePassphraseSignature = nodePassphraseSignature,
                signatureAddress = signingKey.email,
                contentKeyPacket = contentKeyPacketBase64,
                contentKeyPacketSignature = contentKeyPacketSignature,
            )
            val photoRequest = CreatePhotoRequest(
                photo = CreatePhotoMetadata(
                    captureTime = item.dateTaken / 1000L,
                    // ContentKeyPacket belongs in the Photo field — the server stores it at
                    // Photo.ContentKeyPacket which is where the web client reads it from.
                    contentKeyPacket = contentKeyPacketBase64,
                    contentKeyPacketSignature = contentKeyPacketSignature,
                ),
                link = CreatePhotoLinkData(
                    name = encryptedName,
                    hash = nameHashForCreate,
                    parentLinkId = rootLinkId,
                    mimeType = item.mimeType,
                    nodeKey = nodeKey.armoredPrivateKey,
                    nodePassphrase = nodePassphraseEncrypted,
                    nodePassphraseSignature = nodePassphraseSignature,
                    signatureEmail = signingKey.email,
                    // Also send CKP at the link level so the server populates
                    // FileProperties.ContentKeyPacket (read by the Proton web client).
                    contentKeyPacket = contentKeyPacketBase64,
                    contentKeyPacketSignature = contentKeyPacketSignature,
                ),
            )

            // Try Photos stream endpoint first, fall back to v2/volumes path.
            // useVolumeEndpoints = true means we must also use volume-based upload/commit paths.
            var useVolumeEndpoints = false
            val streamResult = runCatching {
                val resp = semaphore.withPermit {
                    manager.invoke { createPhoto(volumeId, photoRequest) }.valueOrThrow
                }
                val linkId = resp.photo.link.linkId
                val revId = resp.photo.link.revisionId
                    ?: semaphore.withPermit {
                        manager.invoke { createRevision(shareId, linkId) }.valueOrThrow
                    }.revision.id
                Log.d(TAG, "uploadFile: created via Photos stream endpoint fileId=$linkId")
                linkId to revId
            }
            val (fileId, revisionId) = if (streamResult.isSuccess) {
                streamResult.getOrThrow()
            } else {
                Log.w(TAG, "uploadFile: stream endpoint failed (${streamResult.exceptionOrNull()?.message}), trying v2/volumes fallback")
                val resp = semaphore.withPermit {
                    manager.invoke { createFileByVolume(volumeId, fileRequest) }.valueOrThrow
                }
                useVolumeEndpoints = true
                Log.d(TAG, "uploadFile: created via v2/volumes fileId=${resp.file.id}")
                resp.file.id to resp.file.revisionId
            }

            // Stream file into 4 MB blocks; spill each encrypted block to disk so the live
            // heap stays ~4 MB regardless of source-file size. Per-block metadata (file ref,
            // signature, hash, verifier token, encrypted size) lives in heap as O(N × ~256 B).
            // tempDir is wiped in the finally at the end of uploadFile().
            val blockSize = 4 * 1024 * 1024
            val tempDir = File(context.cacheDir, "upload_$fileId").also { it.mkdirs() }
            try {

            // Fetch the server-issued verification code BEFORE the stream loop so we can compute
            // per-block verifier tokens inline (no second pass over spilled blocks).
            // Per-block verifier token = base64( verificationCodeBytes XOR encBlock[0..N] )
            // The CDN verifies server-side without decrypting since it knows the session key.
            val verificationCodeBase64 = semaphore.withPermit {
                manager.invoke {
                    getVerificationData(shareId, fileId, revisionId)
                }.valueOrThrow
            }.verificationCode
            val verificationCodeBytes = Base64.decode(verificationCodeBase64, Base64.DEFAULT)

            data class BlockSpill(
                val file: File,
                val encSignature: String,
                val hashBytes: ByteArray,
                val verifierTokenBase64: String,
                val encSize: Long,
            )
            val blockInfos = mutableListOf<BlockSpill>()
            val inputStream = context.contentResolver.openInputStream(Uri.parse(uploadUri))
                ?: error("Cannot read file: $uploadUri")
            inputStream.use { stream ->
                val buf = ByteArray(blockSize)
                var idx = 0
                while (true) {
                    val read = stream.read(buf)
                    if (read == -1) break
                    val chunk = if (read == blockSize) buf else buf.copyOf(read)
                    val encBlock = cryptoHelper.encryptBlock(chunk, sessionKey)
                    val encSig = cryptoHelper.signBlockEncrypted(chunk, signingKey.unlockedKeyBytes, nodeKey.publicKeyArmored)
                    val hashBytes = cryptoHelper.sha256(encBlock)
                    val xorLen = minOf(verificationCodeBytes.size, encBlock.size)
                    val tokenBytes = ByteArray(verificationCodeBytes.size) { i ->
                        if (i < xorLen) (verificationCodeBytes[i].toInt() xor encBlock[i].toInt()).toByte()
                        else verificationCodeBytes[i]
                    }
                    val tokenBase64 = Base64.encodeToString(tokenBytes, Base64.NO_WRAP)
                    val blockFile = File(tempDir, "block_$idx.enc")
                    blockFile.writeBytes(encBlock)
                    blockInfos += BlockSpill(
                        file = blockFile,
                        encSignature = encSig,
                        hashBytes = hashBytes,
                        verifierTokenBase64 = tokenBase64,
                        encSize = encBlock.size.toLong(),
                    )
                    idx++
                    // chunk + encBlock + tokenBytes now orphaned; only `buf` is reused.
                }
            }
            Log.d(TAG, "uploadFile: spilled ${blockInfos.size} block(s) to ${tempDir.absolutePath}")

            // Generate and encrypt a thumbnail for supported image formats.
            // Only JPEG/PNG/HEIC/WEBP are accepted by Proton Photos; other formats get no thumbnail.
            // The thumbnail is JPEG-compressed to ≤512 px and encrypted with the same session key.
            val supportedThumbnailMime = item.mimeType in setOf(
                "image/jpeg", "image/jpg", "image/png", "image/heic", "image/heif", "image/webp",
            ) || item.mimeType.startsWith("video/")
            val thumbnailBytes: ByteArray? = if (supportedThumbnailMime)
                runCatching { generateThumbnailBytes(uploadUri, item.mimeType) }.getOrNull()?.also {
                    Log.d(TAG, "uploadFile: thumbnail generated ${it.size}B")
                }
            else null
            var encThumbnail: ByteArray? = thumbnailBytes?.let {
                runCatching { cryptoHelper.encryptBlock(it, sessionKey) }.getOrElse { e ->
                    Log.w(TAG, "uploadFile: thumbnail encryption failed: ${e.message}"); null
                }
            }
            var encThumbnailHash: ByteArray? = encThumbnail?.let {
                runCatching { cryptoHelper.sha256(it) }.getOrElse { null }
            }
            Log.d(TAG, "uploadFile: thumbnail=${if (encThumbnail != null) "${encThumbnail!!.size}B" else "none"}")

            // POST drive/blocks — all context in the body; AddressID is the user's address ID.
            // Web client uses VolumeID (not ShareID) here, matching the v2 commit endpoint.
            val blockUploadRequest = UploadBlockRequest(
                blockList = blockInfos.mapIndexed { idx, info ->
                    BlockUploadInfoDto(
                        index = idx + 1,
                        hash = cryptoHelper.base64Encode(info.hashBytes),
                        encSignature = info.encSignature,
                        size = info.encSize,
                        verifier = VerifierDto(info.verifierTokenBase64),
                    )
                },
                addressId = signingKey.addressId,
                shareId = shareId,
                linkId = fileId,
                revisionId = revisionId,
                // Upload the thumbnail on BOTH paths. The official Drive Android client treats
                // the thumbnail as a regular block with a special negative index (-5 = DEFAULT,
                // -4 = PHOTO); the manifest signature below includes its hash sorted by index,
                // so thumbnail-hash comes FIRST. The Photos batch endpoint uses the DEFAULT kind
                // for the type=1 thumbnail (small grid preview).
                thumbnailList = if (encThumbnail != null) listOf(ThumbnailUploadInfoDto(type = 1)) else emptyList(),
            )
            // If the server rejects the upload request (e.g. unsupported format for thumbnails),
            // fall back to a thumbnail-free request so the content upload always succeeds.
            val uploadLinksResp = try {
                semaphore.withPermit {
                    manager.invoke { requestUploadLinks(blockUploadRequest) }.valueOrThrow
                }
            } catch (e: Exception) {
                if (encThumbnail != null) {
                    Log.w(TAG, "uploadFile: requestUploadLinks with thumbnail failed (${e.message}), retrying without thumbnail")
                    encThumbnail = null
                    encThumbnailHash = null
                    semaphore.withPermit {
                        manager.invoke {
                            requestUploadLinks(blockUploadRequest.copy(thumbnailList = emptyList()))
                        }.valueOrThrow
                    }
                } else throw e
            }

            // Upload each block to its CDN bare URL via multipart POST through the Proton API layer.
            // Routing via ApiProvider ensures Authorization + x-pm-appversion headers are added,
            // which the Proton CDN requires for block acceptance.
            //
            // sortedUploadLinks is ordered by block index so idx aligns with blockInfos.
            //
            // Block uploads run in parallel bounded by [cdnUploadSemaphore] (2 in flight).
            // Each PUT carries an explicit block index so server-side ordering doesn't depend
            // on arrival order. Structured concurrency: any block failure cancels the others
            // and rethrows from coroutineScope — matches the previous "first failure aborts the
            // whole upload" semantics of the serial for-loop.
            val sortedUploadLinks = uploadLinksResp.uploadLinks.sortedBy { it.index }
            coroutineScope {
                sortedUploadLinks.mapIndexed { idx, uploadLink ->
                    async {
                        val info = blockInfos[idx]
                        if (!info.file.exists()) error("upload temp block evicted: ${info.file.absolutePath}")
                        retryWithBackoff(maxAttempts = 4) { _ ->
                            // Build a fresh MultipartBody.Part on every attempt — OkHttp re-opens
                            // the file via FileRequestBody.writeTo() so retries get a fresh stream.
                            //
                            // CRITICAL: stream from disk instead of File.readBytes(). Each block is
                            // 4 MB; with concurrent uploads (cdnUploadSemaphore) and large videos
                            // (many blocks), readBytes()-allocated ByteArrays accumulate on the
                            // heap fast enough to OOM at ~200 MB (the app's tight release-build
                            // heap). asRequestBody() streams the file directly via OkHttp without
                            // ever materialising the bytes in heap.
                            val blockPart = MultipartBody.Part.createFormData(
                                "Block", "blob",
                                info.file.asRequestBody("application/octet-stream".toMediaType()),
                            )
                            cdnUploadSemaphore.withPermit {
                                manager.invoke {
                                    uploadBlockCdn(
                                        url = uploadLink.bareUrl,
                                        block = blockPart,
                                        storageToken = uploadLink.token,
                                    )
                                }.valueOrThrow
                            }
                        }
                        info.file.delete()  // free disk eagerly; the data is now in the CDN
                    }
                }.awaitAll()
            }

            // Upload thumbnail if one was generated and the server returned a thumbnail CDN link.
            // ThumbnailLinks entries use ThumbnailType (not Index) to identify type 1 = DEFAULT.
            // Uploaded on BOTH commit paths — the v2 path verifies its hash in the manifest below.
            val thumbUploadLink = uploadLinksResp.thumbnailLinks.firstOrNull { it.thumbnailType == 1 }
                ?: uploadLinksResp.thumbnailLinks.firstOrNull()
            var thumbToken: String? = null
            if (encThumbnail != null && thumbUploadLink != null) {
                try {
                    retryWithBackoff(maxAttempts = 3) { _ ->
                        val thumbPart = MultipartBody.Part.createFormData(
                            "Block", "blob",
                            encThumbnail!!.toRequestBody("application/octet-stream".toMediaType()),
                        )
                        cdnUploadSemaphore.withPermit {
                            manager.invoke {
                                uploadBlockCdn(
                                    url = thumbUploadLink.bareUrl,
                                    block = thumbPart,
                                    storageToken = thumbUploadLink.token,
                                )
                            }.valueOrThrow
                        }
                    }
                    thumbToken = thumbUploadLink.token
                    Log.d(TAG, "uploadFile: thumbnail CDN upload OK (${encThumbnail!!.size}B type=1)")
                } catch (e: Exception) {
                    Log.w(TAG, "uploadFile: thumbnail CDN upload failed: ${e.message}")
                }
            } else if (encThumbnail != null) {
                Log.w(TAG, "uploadFile: server returned no ThumbnailLinks — thumbnail will be omitted from commit")
            }

            // Manifest = concat(sha256(encryptedBlock)) over ALL committed blocks (content + thumbnail),
            // sorted by block index ASCENDING. Per the official Drive Android Block.kt:
            //   THUMBNAIL_DEFAULT = -5, THUMBNAIL_PHOTO = -4, content blocks = 1, 2, 3, ...
            // So the thumbnail hash (-5) comes FIRST, then content block hashes in upload order.
            val manifestHashes = buildList {
                if (thumbToken != null && encThumbnailHash != null) add(encThumbnailHash!!)
                addAll(blockInfos.map { it.hashBytes })
            }
            val manifestSignature = cryptoHelper.signManifest(manifestHashes, signingKey.unlockedKeyBytes)
            val modTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(item.dateTaken))
            val encBlockSizes = blockInfos.map { it.encSize }
            val xAttr = cryptoHelper.encryptXAttr(
                modificationTimeIso  = modTime,
                sizeBytes            = item.sizeBytes,
                blockSizes           = encBlockSizes,
                width                = item.width,
                height               = item.height,
                duration             = item.duration,
                nodePublicKeyArmored = nodeKey.publicKeyArmored,
                signerKeyBytes       = signingKey.unlockedKeyBytes,
            )

            // Use the correct commit endpoint based on how the file was created:
            //   • createFileByVolume (useVolumeEndpoints=true) → commitRevisionByVolume (v2)
            //     The v2 endpoint implicitly commits all uploaded blocks/thumbnails via CDN.
            //     No BlockList or ThumbnailList needed in the request body.
            //   • createPhoto / stream path (useVolumeEndpoints=false) → legacy share-based commit
            //     Requires explicit BlockList, ThumbnailList, ContentKeyPacket in the request.
            if (useVolumeEndpoints) {
                val v2Request = CommitRevisionV2Request(
                    manifestSignature = manifestSignature,
                    signatureAddress  = signingKey.email,
                    xAttr             = xAttr,
                    photo             = PhotoMetaDto(captureTime = item.dateTaken / 1000L, contentHash = hash),
                )
                semaphore.withPermit {
                    manager.invoke {
                        commitRevisionByVolume(volumeId, fileId, revisionId, v2Request)
                    }.valueOrThrow
                }
            } else {
                val commitRequest = CommitRevisionRequest(
                    blockList = blockInfos.mapIndexed { idx, info ->
                        val uploadToken = sortedUploadLinks.getOrNull(idx)?.token
                        CommitBlockDto(index = idx + 1, hash = cryptoHelper.base64Encode(info.hashBytes), encSignature = info.encSignature, token = uploadToken)
                    },
                    manifestSignature = manifestSignature,
                    signatureAddress  = signingKey.email,
                    xAttr             = xAttr,
                    photo             = PhotoMetaDto(captureTime = item.dateTaken / 1000L, contentHash = hash),
                    contentKeyPacket  = contentKeyPacketBase64,
                    contentKeyPacketSignature = contentKeyPacketSignature,
                    thumbnailList     = if (encThumbnail != null && encThumbnailHash != null && thumbToken != null)
                        listOf(CommitThumbnailDto(
                            index = 1,
                            hash  = cryptoHelper.base64Encode(encThumbnailHash!!),
                            token = thumbToken!!,
                            type  = 1,
                        ))
                    else emptyList(),
                )
                semaphore.withPermit {
                    manager.invoke {
                        commitRevision(shareId, fileId, revisionId, commitRequest)
                    }.valueOrThrow
                }
            }
            Log.d(TAG, "uploadFile: committed fileId=$fileId v2=$useVolumeEndpoints (thumbnail=${if (thumbToken != null) "yes" else "no"})")

            // Track this upload so refreshCloudPhotos doesn't delete it if the photo
            // stream is temporarily unavailable. Persisted so a process restart between this
            // upload and the next refresh can't drop it either.
            recentUploadsTracker.record(fileId)

            // Cache thumbnail locally so the gallery shows it immediately after upload —
            // without waiting for the next full cloud refresh to decrypt it from the server.
            val localThumbnailUrl: String? = if (thumbnailBytes != null) {
                runCatching {
                    val thumbCacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }
                    val thumbFile = File(thumbCacheDir, "thumb_$fileId.jpg")
                    thumbFile.writeBytes(thumbnailBytes)
                    "file://${thumbFile.absolutePath}"
                }.getOrElse { e ->
                    Log.w(TAG, "uploadFile: failed to cache thumbnail: ${e.message}")
                    null
                }
            } else null

            // Persist the newly uploaded photo to the local DB immediately so the
            // gallery (which observes the DB via Room Flow) shows it right away —
            // without waiting for the next full cloud refresh.
            photoListingDao.upsertAll(listOf(
                PhotoListingEntity(
                    linkId       = fileId,
                    shareId      = shareId,
                    volumeId     = volumeId,
                    userId       = userId.id,
                    captureTime  = item.dateTaken / 1000L,
                    displayName  = item.displayName,
                    mimeType     = item.mimeType,
                    sizeBytes    = item.sizeBytes,
                    revisionId   = revisionId,
                    thumbnailUrl = localThumbnailUrl,
                )
            ))
            Log.d(TAG, "uploadFile: completed fileId=$fileId, persisted to DB")
            fileId
            } finally {
                runCatching { tempDir.deleteRecursively() }
                    .onFailure { Log.w(TAG, "uploadFile: cleanup of ${tempDir.absolutePath} failed: ${it.message}") }
            }
        }

    /**
     * Generates a JPEG thumbnail from the given media URI. Downscales the image so the
     * longest edge is at most [maxPx] pixels (default 512), then compresses to JPEG at 80 %.
     * Returns null for non-image/video MIME types, when the content resolver cannot open
     * the stream, or when decoding fails.
     */
    private fun generateThumbnailBytes(uri: String, mimeType: String, maxPx: Int = 512): ByteArray? {
        return when {
            mimeType.startsWith("video/") -> generateVideoThumbnailBytes(uri, maxPx)
            mimeType.startsWith("image/") -> generateImageThumbnailBytes(uri, maxPx)
            else -> null
        }
    }

    private fun generateVideoThumbnailBytes(uri: String, maxPx: Int): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            val androidUri = Uri.parse(uri)
            if (androidUri.scheme == "file") {
                retriever.setDataSource(androidUri.path ?: return@runCatching null)
            } else {
                retriever.setDataSource(context, androidUri)
            }
            // Grab the first decodable frame near t=0. Some devices return null at exactly 0.
            val frame = retriever.getFrameAtTime(1_000_000 /* 1s */, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0)
                ?: return@runCatching null
            val scaled = if (frame.width > maxPx || frame.height > maxPx) {
                val scale = maxPx.toFloat() / maxOf(frame.width, frame.height)
                val sw = (frame.width * scale).toInt().coerceAtLeast(1)
                val sh = (frame.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(frame, sw, sh, true).also {
                    if (it !== frame) frame.recycle()
                }
            } else frame
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                scaled.recycle()
                out.toByteArray()
            }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrElse { e ->
        Log.w(TAG, "generateVideoThumbnailBytes failed for $uri: ${e.message}")
        null
    }

    private fun generateImageThumbnailBytes(uri: String, maxPx: Int): ByteArray? {
        return runCatching {
            val androidUri = Uri.parse(uri)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(androidUri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val w = opts.outWidth.coerceAtLeast(1)
            val h = opts.outHeight.coerceAtLeast(1)
            var sample = 1
            var tw = w; var th = h
            while (tw / 2 >= maxPx || th / 2 >= maxPx) { sample *= 2; tw = w / sample; th = h / sample }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val sampled = context.contentResolver.openInputStream(androidUri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val bitmap = if (sampled.width > maxPx || sampled.height > maxPx) {
                val scale = maxPx.toFloat() / maxOf(sampled.width, sampled.height)
                val scaled = Bitmap.createScaledBitmap(
                    sampled,
                    (sampled.width * scale).toInt().coerceAtLeast(1),
                    (sampled.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== sampled) sampled.recycle()
                scaled
            } else sampled

            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }.getOrElse { e ->
            Log.w(TAG, "generateThumbnailBytes: failed for $uri — ${e.message}")
            null
        }
    }
}
