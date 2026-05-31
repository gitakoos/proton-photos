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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.api.dto.BlockUploadInfoDto
import eu.akoos.photos.data.api.dto.CommitBlockDto
import eu.akoos.photos.data.api.dto.CommitRevisionRequest
import eu.akoos.photos.data.api.dto.CommitRevisionV2Request
import eu.akoos.photos.data.api.dto.CommitThumbnailDto
import eu.akoos.photos.data.api.dto.CreateFileRequest
import eu.akoos.photos.data.api.dto.CreatePhotoLinkData
import eu.akoos.photos.data.api.dto.CreatePhotoMetadata
import eu.akoos.photos.data.api.dto.CreatePhotoRequest
import eu.akoos.photos.data.api.dto.PhotoMetaDto
import eu.akoos.photos.data.api.dto.ThumbnailUploadInfoDto
import eu.akoos.photos.data.api.dto.UploadBlockRequest
import eu.akoos.photos.data.api.dto.VerifierDto
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.util.retryWithBackoff
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhotoUploadSvc"

/**
 * Source-bytes-consumed phase reported via `uploadFile`'s `onProgress` callback. UI maps
 * `Encrypting` to a dimmer spinner so the user sees the pre-network stage distinctly from
 * the CDN PUT stage. Each phase emits its own progress curve from 0 → totalBytes; a final
 * 100 % tick is guaranteed at every phase transition so the bar always settles cleanly.
 */
enum class UploadPhase { Encrypting, Uploading }

/**
 * Concurrent block encrypt+sign+spill slots. Three is the sweet spot:
 * - On a 6-core phone, three CPU cores stay busy on PGP/AES while one handles UI and one
 *   handles the parallel CDN PUTs (cdnUploadSemaphore=2). Above 3 the cores spend more
 *   time on context switches than on the cipher.
 * - Each in-flight block keeps a 4 MB plaintext buffer + the encrypted output in RAM until
 *   it spills, so 3 × ~9 MB ≈ 27 MB of heap headroom. Stays well under the 256 MB largeHeap
 *   ceiling even on a 2 GB Pixel 6a.
 *
 * Increased above 1 reduces the wall-clock between "Save" tap and the first CDN PUT for
 * multi-block uploads — the dominant cost on long videos.
 */
private const val ENCRYPT_PARALLELISM = 3

/** Throttle window for [PhotoUploadService.uploadFile]'s onProgress callback (per phase). */
private const val PROGRESS_THROTTLE_MS = 250L

/**
 * Per-call retry tuned for upload-side metadata endpoints. The default
 * 3 attempts × 500 ms gave the network ~3 seconds to recover — observed in the field
 * as: first "Save to Drive" tap returns "the DNS name" failure, second tap (a few
 * seconds later) succeeds. 5 attempts × 1500 ms base / 15 s cap covers the typical
 * 5–10 second DNS / mobile-radio reattach window.
 *
 * `shouldRetry` also matches by message substring so an exception wrapped by the
 * ProtonCore network stack (which sometimes hides the IOException inside an
 * ApiException) still retries instead of bubbling straight to the user's "Save failed"
 * toast. Looking for "host", "dns", "timeout", "connection" covers UnknownHostException,
 * SocketTimeoutException, and ConnectException across vendor JVMs.
 */
private suspend fun <T> retryUploadCall(block: suspend (attempt: Int) -> T): T =
    eu.akoos.photos.util.retryWithBackoff(
        maxAttempts = 5,
        baseMs = 1500,
        maxBackoffMs = 15_000,
        shouldRetry = { e ->
            // Walk the cause chain — ProtonCore wraps IOExceptions inside ApiException
            // wrappers, so message-only matching on `e.message` misses the real reason.
            // Concatenating up to 4 levels covers the common Drive backend exceptions
            // (UnknownHostException, SSLException, SSL_PROTOCOL_ERROR, read errors
            // mid-handshake, mobile-radio reattach timeouts).
            val msgs = generateSequence<Throwable>(e) { it.cause }
                .take(4)
                .mapNotNull { it.message }
                .joinToString(" ")
                .lowercase()
            msgs.contains("unable to resolve") ||
                msgs.contains("host") ||
                msgs.contains("dns") ||
                msgs.contains("timeout") ||
                msgs.contains("connection") ||
                msgs.contains("network") ||
                msgs.contains("ssl") ||
                msgs.contains("tls") ||
                msgs.contains("read error") ||
                msgs.contains("broken pipe") ||
                msgs.contains("reset by peer") ||
                msgs.contains("socket closed")
        },
        block = block,
    )

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

    // NOTE: Per-file Semaphores are allocated INSIDE `uploadFile()` below — see
    // the `cdnUploadSlots` and `encryptSlots` locals. This is a deliberate
    // departure from the previous design where both lived as instance fields on
    // the Singleton service. Instance-level pools meant a single in-flight 150
    // MB video could hold all of the CDN slots and starve sibling photo uploads
    // running in parallel through `UploadPendingUseCase.UPLOAD_PARALLELISM=3`.
    // Photos waiting on a video's encrypt+upload cycle is what the user saw as
    // "sync stuck after one photo" with backup-everything turned on. With
    // per-file allocation each `uploadFile` invocation gets its own fresh pool
    // and big videos no longer block the photo pipeline.
    //
    // The global `cryptoLock` in DriveCryptoHelper still serialises PGP work
    // (necessary for libgojni single-thread invariant), but that's a CPU lock —
    // it doesn't tie up CDN or filesystem resources.

    suspend fun uploadFile(
        userId: UserId,
        item: LocalMediaItem,
        hash: String,
        uploadUri: String,
        onProgress: ((phase: UploadPhase, doneBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): String =
        withContext(Dispatchers.IO) {
            // Per-file slot pools — each upload owns its own. With instance-level
            // pools a single 150 MB video would hold all 2 CDN slots for several
            // minutes, starving sibling photo uploads scheduled in parallel by
            // UploadPendingUseCase. Per-file pools let every concurrent file
            // make progress on its own CDN / encrypt budget.
            val cdnUploadSlots = Semaphore(2)
            val encryptSlots = Semaphore(ENCRYPT_PARALLELISM)

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
            val rootLinkPublicKey = cryptoHelper.withCryptoLock {
                cryptoContext.pgpCrypto.getPublicKey(rootLinkArmoredKey)
            }
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
            // Both create paths run through retryWithBackoff — without retry on the metadata
            // endpoints, a single DNS hiccup at the start of upload surfaces as a hard failure
            // and requires a manual retry of the whole edit-and-save.
            var useVolumeEndpoints = false
            val streamResult = runCatching {
                val resp = retryUploadCall { _ ->
                    semaphore.withPermit {
                        manager.invoke { createPhoto(volumeId, photoRequest) }.valueOrThrow
                    }
                }
                val linkId = resp.photo.link.linkId
                val revId = resp.photo.link.revisionId
                    ?: retryUploadCall { _ ->
                        semaphore.withPermit {
                            manager.invoke { createRevision(shareId, linkId) }.valueOrThrow
                        }
                    }.revision.id
                Log.d(TAG, "uploadFile: created via Photos stream endpoint fileId=$linkId")
                linkId to revId
            }
            val (fileId, revisionId) = if (streamResult.isSuccess) {
                streamResult.getOrThrow()
            } else {
                Log.w(TAG, "uploadFile: stream endpoint failed (${streamResult.exceptionOrNull()?.message}), trying v2/volumes fallback")
                val resp = retryUploadCall { _ ->
                    semaphore.withPermit {
                        manager.invoke { createFileByVolume(volumeId, fileRequest) }.valueOrThrow
                    }
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
            val verificationCodeBase64 = retryUploadCall { _ ->
                semaphore.withPermit {
                    manager.invoke {
                        getVerificationData(shareId, fileId, revisionId)
                    }.valueOrThrow
                }
            }.verificationCode
            val verificationCodeBytes = Base64.decode(verificationCodeBase64, Base64.DEFAULT)

            data class BlockSpill(
                val file: File,
                val encSignature: String,
                val hashBytes: ByteArray,
                val verifierTokenBase64: String,
                val encSize: Long,
                /**
                 * Plaintext byte count of the block BEFORE PGP encryption. Goes into the
                 * xAttr's BlockSizes array — Drive Web uses these to split the downloaded
                 * ciphertext into its constituent blocks (each block is independently PGP-
                 * decrypted). If we stored encSize here instead, the encrypted overhead
                 * (~200B PKESK + SEIPD packet headers) would push every boundary off by a
                 * few hundred bytes, and the Web decoder would misalign block boundaries —
                 * the last block's SEIPD packet would span the boundary, partial ciphertext
                 * left orphaned, decryption fails → the video downloads to Web but won't
                 * play (observed by the user as "encoding bad on Drive but fine in our app").
                 */
                val plaintextSize: Long,
            )
            // Parallel encrypt pipeline. Source is opened once via openFileDescriptor —
            // FileChannel.read(buffer, position) is documented thread-safe for the position
            // argument and doesn't disturb the channel's own position state, so multiple
            // coroutines can pread independently into their own buffers.
            //
            // Each in-flight permit:
            //   1. pread(offset, BLOCK_SIZE) from the shared FileChannel
            //   2. PGP encrypt + detached sign (serialized through DriveCryptoHelper.withCryptoLock
            //      to match the libgojni single-thread invariant — concurrent JNI entries into
            //      the Go runtime race the GC's signal handlers and SIGABRT under sustained load;
            //      see DriveCryptoHelper.cryptoLock doc)
            //   3. SHA-256 + verifier-token XOR (pure JVM, no JNI)
            //   4. Spill to tempDir/block_$idx.enc
            //   5. Emit phase progress through the throttled callback
            //
            // BlockSpill list MUST stay in index order — manifest signature is computed over
            // hash concatenation in that order. Pre-allocate an array and fill by idx so we
            // can convert to list before passing on.
            val totalBytes = item.sizeBytes
            val numBlocks = if (totalBytes <= 0L) 1
                else ((totalBytes + blockSize - 1L) / blockSize).toInt().coerceAtLeast(1)
            val blockArr = arrayOfNulls<BlockSpill>(numBlocks)

            val doneEncryptedBytes = AtomicLong(0L)
            val lastEncryptEmitMs = AtomicLong(0L)
            // Open the source PFD once; share the channel across the parallel encrypt tasks.
            val sourcePfd: ParcelFileDescriptor = context.contentResolver
                .openFileDescriptor(Uri.parse(uploadUri), "r")
                ?: error("Cannot openFileDescriptor: $uploadUri")
            try {
                FileInputStream(sourcePfd.fileDescriptor).use { fis ->
                    val sourceChannel: FileChannel = fis.channel
                    coroutineScope {
                        (0 until numBlocks).map { idx ->
                            async(Dispatchers.IO) {
                                encryptSlots.withPermit {
                                    val offset = idx.toLong() * blockSize
                                    // pread into a fresh ByteBuffer. We size the buffer to the
                                    // EXPECTED remaining bytes for this block — the final block
                                    // may be shorter than blockSize. If the source under-reports
                                    // sizeBytes (rare with MediaStore deletes mid-upload), the
                                    // read returns -1 and we treat the block as empty (skipped).
                                    // When totalBytes is known we cap the request to the exact
                                    // remaining bytes so the final block isn't over-allocated;
                                    // when sizeBytes is missing (totalBytes <= 0) we ask for a
                                    // full block and let the channel return whatever's actually
                                    // there. This safety branch covers the rare case where the
                                    // OS hasn't published MediaStore SIZE yet for a freshly-saved
                                    // photo — we'd rather read more than zero than truncate.
                                    val want = if (totalBytes > 0L)
                                        minOf(blockSize.toLong(), totalBytes - offset).toInt()
                                            .coerceAtLeast(0)
                                    else blockSize
                                    if (want == 0) {
                                        // Zero-length tail block (last-block offset == file size).
                                        // Skip; blockInfos.filterNotNull() drops this slot.
                                        return@withPermit
                                    }
                                    val buf = ByteBuffer.allocate(want)
                                    var totalRead = 0
                                    while (totalRead < want) {
                                        val n = sourceChannel.read(buf, offset + totalRead)
                                        if (n <= 0) break
                                        totalRead += n
                                    }
                                    if (totalRead == 0) return@withPermit
                                    val chunk = if (totalRead == buf.array().size) buf.array()
                                        else buf.array().copyOf(totalRead)

                                    // PGP encrypt + detached sign must serialize through the
                                    // single global cryptoLock — see DriveCryptoHelper. Pre/post
                                    // work (pread, sha256, verifier XOR, spill) stays parallel.
                                    val (encBlock, encSig) = cryptoHelper.withCryptoLock {
                                        val eb = cryptoHelper.encryptBlock(chunk, sessionKey)
                                        val es = cryptoHelper.signBlockEncrypted(
                                            chunk, signingKey.unlockedKeyBytes, nodeKey.publicKeyArmored,
                                        )
                                        eb to es
                                    }
                                    val hashBytes = cryptoHelper.sha256(encBlock)
                                    val xorLen = minOf(verificationCodeBytes.size, encBlock.size)
                                    val tokenBytes = ByteArray(verificationCodeBytes.size) { i ->
                                        if (i < xorLen) (verificationCodeBytes[i].toInt() xor encBlock[i].toInt()).toByte()
                                        else verificationCodeBytes[i]
                                    }
                                    val tokenBase64 = Base64.encodeToString(tokenBytes, Base64.NO_WRAP)
                                    val blockFile = File(tempDir, "block_$idx.enc")
                                    blockFile.writeBytes(encBlock)
                                    blockArr[idx] = BlockSpill(
                                        file = blockFile,
                                        encSignature = encSig,
                                        hashBytes = hashBytes,
                                        verifierTokenBase64 = tokenBase64,
                                        encSize = encBlock.size.toLong(),
                                        plaintextSize = chunk.size.toLong(),
                                    )
                                    if (onProgress != null) {
                                        val done = doneEncryptedBytes.addAndGet(chunk.size.toLong())
                                        val denom = if (totalBytes > 0L) totalBytes else done
                                        val nowMs = System.currentTimeMillis()
                                        val prev = lastEncryptEmitMs.get()
                                        if (nowMs - prev >= PROGRESS_THROTTLE_MS &&
                                            lastEncryptEmitMs.compareAndSet(prev, nowMs)
                                        ) {
                                            onProgress(UploadPhase.Encrypting, done, denom)
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } finally {
                runCatching { sourcePfd.close() }
            }
            // Guarantee a final 100 % Encrypting tick so the UI lands cleanly at the phase
            // boundary, regardless of where the last throttled tick fell.
            if (onProgress != null) {
                val finalDone = doneEncryptedBytes.get()
                val denom = if (totalBytes > 0L) totalBytes else finalDone
                onProgress(UploadPhase.Encrypting, denom, denom)
            }
            val blockInfos: List<BlockSpill> = blockArr.filterNotNull()
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
            // Snapshot the var into a local val so the size log doesn't need `!!` — the
            // var is reassigned by the requestUploadLinks fallback below, and using !!
            // on a var after a separate null check is the kind of smart-cast hole that
            // future linters (and humans) flag. Pattern repeats wherever we read the
            // encrypted thumbnail bytes from a var.
            encThumbnail.let { snap ->
                Log.d(TAG, "uploadFile: thumbnail=${if (snap != null) "${snap.size}B" else "none"}")
            }

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
            // Per-block upload-phase progress counter. Reused atomic pattern from the
            // encrypt phase so the same throttle math drives both meters.
            val doneUploadedBytes = AtomicLong(0L)
            val lastUploadEmitMs = AtomicLong(0L)
            coroutineScope {
                sortedUploadLinks.mapIndexed { idx, uploadLink ->
                    async {
                        val info = blockInfos[idx]
                        if (!info.file.exists()) error("upload temp block evicted: ${info.file.absolutePath}")
                        // Block CDN PUTs go through the same beefed-up retry envelope as the
                        // metadata calls — SSL handshake hiccups mid-block ("read error:
                        // ssl=0x...") and DNS reattach during mobile-radio handoff both fall
                        // through the default IOException check unless the cause chain is
                        // string-matched. Same retryUploadCall() helper, so a single tuning
                        // point covers every upload-side flake.
                        retryUploadCall { _ ->
                            val blockPart = MultipartBody.Part.createFormData(
                                "Block", "blob",
                                info.file.asRequestBody("application/octet-stream".toMediaType()),
                            )
                            cdnUploadSlots.withPermit {
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
                        // Per-block upload progress emission. Counted in plaintext bytes so the
                        // bar's denominator matches the Encrypting phase's denominator (totalBytes
                        // = item.sizeBytes). Encrypted overhead would skew the percentage by a few
                        // hundred bytes per block — invisible at typical block counts but tidier.
                        if (onProgress != null) {
                            val done = doneUploadedBytes.addAndGet(info.plaintextSize)
                            val denom = if (totalBytes > 0L) totalBytes else done
                            val nowMs = System.currentTimeMillis()
                            val prev = lastUploadEmitMs.get()
                            if (nowMs - prev >= PROGRESS_THROTTLE_MS &&
                                lastUploadEmitMs.compareAndSet(prev, nowMs)
                            ) {
                                onProgress(UploadPhase.Uploading, done, denom)
                            }
                        }
                    }
                }.awaitAll()
            }
            // Guarantee a final 100 % Uploading tick.
            if (onProgress != null) {
                val finalDone = doneUploadedBytes.get()
                val denom = if (totalBytes > 0L) totalBytes else finalDone
                onProgress(UploadPhase.Uploading, denom, denom)
            }

            // Upload thumbnail if one was generated and the server returned a thumbnail CDN link.
            // ThumbnailLinks entries use ThumbnailType (not Index) to identify type 1 = DEFAULT.
            // Uploaded on BOTH commit paths — the v2 path verifies its hash in the manifest below.
            val thumbUploadLink = uploadLinksResp.thumbnailLinks.firstOrNull { it.thumbnailType == 1 }
                ?: uploadLinksResp.thumbnailLinks.firstOrNull()
            var thumbToken: String? = null
            // Capture the var into a local val before the upload block so the !! checks
            // below collapse into plain val reads. encThumbnail can be nulled by the
            // requestUploadLinks fallback above; once we get here we've decided what
            // bytes to send and shouldn't re-read the mutable field.
            val capturedEncThumbnail = encThumbnail
            if (capturedEncThumbnail != null && thumbUploadLink != null) {
                try {
                    retryUploadCall { _ ->
                        val thumbPart = MultipartBody.Part.createFormData(
                            "Block", "blob",
                            capturedEncThumbnail.toRequestBody("application/octet-stream".toMediaType()),
                        )
                        cdnUploadSlots.withPermit {
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
                    Log.d(TAG, "uploadFile: thumbnail CDN upload OK (${capturedEncThumbnail.size}B type=1)")
                } catch (e: Exception) {
                    Log.w(TAG, "uploadFile: thumbnail CDN upload failed: ${e.message}")
                }
            } else if (capturedEncThumbnail != null) {
                Log.w(TAG, "uploadFile: server returned no ThumbnailLinks — thumbnail will be omitted from commit")
            }

            // Manifest = concat(sha256(encryptedBlock)) over ALL committed blocks (content + thumbnail),
            // sorted by block index ASCENDING. Per the official Drive Android Block.kt:
            //   THUMBNAIL_DEFAULT = -5, THUMBNAIL_PHOTO = -4, content blocks = 1, 2, 3, ...
            // So the thumbnail hash (-5) comes FIRST, then content block hashes in upload order.
            // Snapshot the mutable thumbnail-hash var so the local val read inside the
            // buildList block doesn't need !!, and so manifest + commit see a consistent
            // value even if a future hot-fix reassigns the var between these two reads.
            val capturedEncThumbnailHash = encThumbnailHash
            val manifestHashes = buildList {
                if (thumbToken != null && capturedEncThumbnailHash != null) add(capturedEncThumbnailHash)
                addAll(blockInfos.map { it.hashBytes })
            }
            val manifestSignature = cryptoHelper.signManifest(manifestHashes, signingKey.unlockedKeyBytes)
            val modTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(item.dateTaken))
            // xAttr.BlockSizes carries PLAINTEXT block boundaries so the Drive Web client
            // can split the ciphertext stream back into PGP packets. See BlockSpill's
            // plaintextSize doc-comment for why storing encrypted sizes corrupts video
            // playback on Drive Web.
            val plaintextBlockSizes = blockInfos.map { it.plaintextSize }
            val xAttr = cryptoHelper.encryptXAttr(
                modificationTimeIso  = modTime,
                sizeBytes            = item.sizeBytes,
                blockSizes           = plaintextBlockSizes,
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
                retryUploadCall { _ ->
                    semaphore.withPermit {
                        manager.invoke {
                            commitRevisionByVolume(volumeId, fileId, revisionId, v2Request)
                        }.valueOrThrow
                    }
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
                    thumbnailList     = if (capturedEncThumbnail != null && capturedEncThumbnailHash != null && thumbToken != null)
                        listOf(CommitThumbnailDto(
                            index = 1,
                            hash  = cryptoHelper.base64Encode(capturedEncThumbnailHash),
                            token = thumbToken,
                            type  = 1,
                        ))
                    else emptyList(),
                )
                retryUploadCall { _ ->
                    semaphore.withPermit {
                        manager.invoke {
                            commitRevision(shareId, fileId, revisionId, commitRequest)
                        }.valueOrThrow
                    }
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

            val scaledBitmap = if (sampled.width > maxPx || sampled.height > maxPx) {
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

            // EXIF orientation rebake. BitmapFactory.decodeStream returns RAW byte pixels
            // (pre-rotation). The compressed thumbnail JPEG below carries no EXIF tag so
            // viewers can't recover the orientation later — the saved blob would render
            // as landscape on Drive Web / Coil / any consumer for a portrait phone photo
            // whose source had EXIF 90°. Apply the rotation directly to the pixels so
            // the saved bytes are already in display orientation.
            val orientation = runCatching {
                context.contentResolver.openInputStream(androidUri)?.use {
                    androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                    )
                } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)

            val bitmap = applyExifRotationToBitmap(scaledBitmap, orientation)

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

    /** Returns [bitmap] rotated/mirrored to its display orientation. Recycles the input
     *  when a new bitmap is allocated. Covers the 8 standard EXIF orientation tags. */
    private fun applyExifRotationToBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL -> return bitmap
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.preScale(-1f, 1f)
            }
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
