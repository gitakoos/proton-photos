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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.util.PhotoTagDetector
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
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import me.proton.core.crypto.common.pgp.SessionKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
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
 * Phase reported via `uploadFile`'s `onProgress` callback. Each phase emits its own 0 →
 * totalBytes curve with a guaranteed final 100% tick so the bar settles cleanly.
 */
enum class UploadPhase { Encrypting, Uploading }

/**
 * Source-derived metadata for the photo xAttr Camera/Location blocks, resolved BEFORE any strip
 * pass and already gated against the user's [eu.akoos.photos.util.MetadataStripConfig] so the
 * encrypted xAttr can never re-leak a field the file itself had erased. All fields are nullable —
 * a null block is simply omitted from the xAttr JSON.
 *
 *  - [latitude]/[longitude]: present only when GPS is NOT stripped.
 *  - [cameraOrientation]: raw EXIF orientation int (1..8), always present when known (never stripped).
 *  - [cameraCaptureTimeIso]: ISO_INSTANT capture time — the floored upload time when timestamps
 *    are stripped, else the real EXIF/MediaStore capture time.
 *  - [cameraDevice]: EXIF model, present only when camera info is NOT stripped.
 *  - [subjectCoordinates]: [Top,Left,Bottom,Right] from EXIF SubjectArea, present only when camera
 *    info is NOT stripped.
 *  - [displayWidth]/[displayHeight]: rotation-corrected dimensions for xAttr Media — already
 *    W/H-swapped when the EXIF orientation or video rotation is 90/270. Null falls back to the
 *    MediaStore values on [eu.akoos.photos.domain.entity.LocalMediaItem].
 */
data class UploadXAttrMetadata(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val cameraOrientation: Int? = null,
    val cameraCaptureTimeIso: String? = null,
    val cameraDevice: String? = null,
    val subjectCoordinates: IntArray? = null,
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
)

/**
 * Concurrent block encrypt+sign+spill slots. Three keeps the CPU busy on PGP/AES without
 * thrashing on context switches, and at ~9 MB heap per in-flight block stays well under the
 * largeHeap ceiling even on a 2 GB device.
 */
private const val ENCRYPT_PARALLELISM = 3

/** Throttle window for [PhotoUploadService.uploadFile]'s onProgress callback (per phase). */
private const val PROGRESS_THROTTLE_MS = 250L

/**
 * Drive thumbnail types + max edges, matching ConfigurationProvider.thumbnailDefault/thumbnailPhoto.
 * DEFAULT (type 1) is the 512px grid preview; PHOTO (type 2) is the 1920px viewer preview. Drive
 * assigns thumbnail blocks the negative manifest indices [THUMBNAIL_DEFAULT_INDEX]/[THUMBNAIL_PHOTO_INDEX].
 */
private const val THUMBNAIL_TYPE_DEFAULT = 1
private const val THUMBNAIL_TYPE_PHOTO = 2
private const val THUMBNAIL_DEFAULT_MAX_PX = 512
private const val THUMBNAIL_PHOTO_MAX_PX = 1920
private const val THUMBNAIL_DEFAULT_INDEX = -5
private const val THUMBNAIL_PHOTO_INDEX = -4
// Thumbnail size budget, matching the official client: the DEFAULT (grid) thumbnail caps at 64 KiB,
// the PHOTO (viewer) thumbnail at 1 MiB. We step JPEG quality down until the encoded bytes fit.
private const val THUMBNAIL_DEFAULT_MAX_BYTES = 64 * 1024
private const val THUMBNAIL_PHOTO_MAX_BYTES = 1024 * 1024

/**
 * Per-call retry for upload-side metadata endpoints. 5 attempts × 1500 ms base / 15 s cap
 * covers the typical 5–10 s DNS / mobile-radio reattach window. `shouldRetry` matches both
 * exception class names and message substrings so an IOException wrapped inside a ProtonCore
 * ApiException still retries.
 */
private suspend fun <T> retryUploadCall(block: suspend (attempt: Int) -> T): T =
    eu.akoos.photos.util.retryWithBackoff(
        maxAttempts = 5,
        baseMs = 1500,
        maxBackoffMs = 15_000,
        shouldRetry = { e ->
            // Class names first (locale-independent, survives translated vendor JVM messages);
            // substring fallback for wrapper paths where Throwable.cause is dropped.
            val chain = generateSequence<Throwable>(e) { it.cause }.take(4).toList()
            val classMatches = chain.any { t ->
                val n = t.javaClass.name
                n.contains("UnknownHost") || n.contains("SocketTimeout") ||
                    n.contains("SocketException") || n.contains("SSLException") ||
                    n.contains("InterruptedIOException") || n.contains("ConnectException")
            }
            if (classMatches) return@retryWithBackoff true
            val msgs = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
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
 * True when [e] is a transient network/IO failure worth resuming from — the signal to KEEP
 * the encrypted-blocks tempDir for the next attempt. Non-retryable failures (auth, quota,
 * malformed content) return false so the finally block wipes the cache. Mirrors
 * [retryUploadCall]'s `shouldRetry` so the two decisions stay aligned.
 */
private fun isRetryableUploadFailure(e: Throwable): Boolean {
    if (e is IOException) return true
    val msgs = generateSequence<Throwable>(e) { it.cause }
        .take(4)
        .mapNotNull { it.message }
        .joinToString(" ")
        .lowercase()
    if (msgs.contains("429") || msgs.contains("502") ||
        msgs.contains("503") || msgs.contains("504")) return true
    return msgs.contains("unable to resolve") ||
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
}

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

    // Per-file CDN/encrypt semaphores are allocated INSIDE uploadFile() (not as instance
    // fields) so a single big video can't hold all the slots and starve sibling photo uploads
    // running in parallel. The global cryptoLock in DriveCryptoHelper still serialises PGP work.

    /**
     * Encrypts and uploads [item] to the Photos volume. [sha1HexContentDigest] MUST be the
     * lowercase SHA-1 hex of the plaintext bytes (see [DrivePhotoRepository.uploadFile]).
     */
    suspend fun uploadFile(
        userId: UserId,
        item: LocalMediaItem,
        sha1HexContentDigest: String,
        uploadUri: String,
        xAttrMetadata: UploadXAttrMetadata = UploadXAttrMetadata(),
        onProgress: ((phase: UploadPhase, doneBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val hash = sha1HexContentDigest
            // Per-file slot pools so every concurrent upload makes progress on its own budget.
            val cdnUploadSlots = Semaphore(2)
            val encryptSlots = Semaphore(ENCRYPT_PARALLELISM)

            // Stable tempDir name keyed on the ORIGINAL media URI + size, so a retry-eligible
            // failure leaves the encrypted blocks here for the next uploadFile() call to resume
            // from. Keyed on item.uri (the source content:// URI), NOT uploadUri — with
            // strip-on-upload the latter is a fresh temp file each run, which would defeat resume.
            val tempDir = File(
                context.cacheDir,
                UploadResumeManifest.stableTempDirName(item.uri, item.sizeBytes),
            ).also { it.mkdirs() }

            // Proton photo category tags (Motion Photo, Panorama, Video, Raw, Screenshot) sent
            // on the commit; without them the web can't file the photo or play a Live Photo.
            val photoTags = PhotoTagDetector.detectTags(context, Uri.parse(item.uri), item.mimeType, item.displayName, item.sizeBytes)

            // The finally block inspects the failure category (wipe vs preserve-for-resume), so
            // catch, categorise, then rethrow.
            // Tracks the created (uncommitted) node so a non-retryable failure can best-effort delete
            // it instead of leaving an orphan link on Drive.
            var orphanFileId: String? = null
            var orphanShareId: String? = null
            try {
            val volumeId = shareService.getVolumeId(userId)
            val shareId = shareService.getShareId(userId, volumeId)
            val rootLinkId = shareService.photosRootLinkId() ?: error("Root link ID not loaded")

            val signingKey = cryptoHelper.getAddressSigningKey(userId)
            val manager = apiProvider.get<DriveApiService>(userId)

            // Must match the value used to produce any cached blocks; the manifest persists it
            // so a future change invalidates stale manifests automatically.
            val blockSize = 4 * 1024 * 1024
            val totalBytes = item.sizeBytes
            val numBlocks = if (totalBytes <= 0L) 1
                else ((totalBytes + blockSize - 1L) / blockSize).toInt().coerceAtLeast(1)

            // Resume when a manifest agrees with the current source (same block count, blockSize,
            // sizeBytes) — skips the create-photo + getVerificationData calls. Else wipe + restart.
            val priorManifest = UploadResumeManifest.load(tempDir)?.takeIf { m ->
                m.totalBlocks == numBlocks &&
                    m.blockSize == blockSize &&
                    m.sizeBytes == totalBytes
            }
            if (priorManifest == null && tempDir.listFiles()?.isNotEmpty() == true) {
                // Tempdir present but no usable manifest (older format or changed source) — wipe
                // rather than trust mystery files.
                Log.d(TAG, "uploadFile: tempDir ${tempDir.name} present without valid manifest — wiping")
                tempDir.deleteRecursively()
                tempDir.mkdirs()
            }
            val resuming = priorManifest != null
            if (resuming) {
                Log.d(TAG, "uploadFile: resuming from manifest — ${priorManifest!!.completedBlocks}/$numBlocks blocks already encrypted")
            }

            // Per-file crypto state — restored from the manifest (resume) or freshly generated.
            // Resume reuses the SAME session key so cached blocks decrypt server-side.
            val sessionKey: SessionKey
            val verificationCodeBase64: String
            val verificationCodeBytes: ByteArray
            val fileId: String
            val revisionId: String
            var useVolumeEndpoints: Boolean
            val contentKeyPacketBase64: String
            val contentKeyPacketSignature: String
            val nodePublicKeyArmored: String

            if (resuming) {
                val m = priorManifest!!
                sessionKey = SessionKey(UploadResumeManifest.decodeB64(m.sessionKeyB64))
                verificationCodeBase64 = m.verificationCodeB64
                verificationCodeBytes = UploadResumeManifest.decodeB64(verificationCodeBase64)
                fileId = m.fileId
                revisionId = m.revisionId
                useVolumeEndpoints = m.useVolumeEndpoints
                contentKeyPacketBase64 = m.contentKeyPacketB64
                contentKeyPacketSignature = m.contentKeyPacketSignature
                nodePublicKeyArmored = m.nodePublicKeyArmored
            } else {
                // NodePassphrase is encrypted to the PARENT link's public key — the parent is
                // the photos root, so use the root link's armored key.
                val nodeKey = cryptoHelper.generateNodeKey()
                val rootLinkArmoredKey = shareService.rootLinkArmoredKey()
                    ?: run { shareService.getRootLinkKeyBytes(userId); shareService.rootLinkArmoredKey() }
                    ?: error("Root link armored key not available")
                val rootLinkPublicKey = cryptoHelper.withCryptoLock {
                    cryptoContext.pgpCrypto.getPublicKey(rootLinkArmoredKey)
                }
                val nodePassphraseEncrypted = cryptoHelper.encryptDataToPgpMessage(nodeKey.passphraseBytes, rootLinkPublicKey)
                val nodePassphraseSignature = cryptoHelper.signData(nodeKey.passphraseBytes, signingKey.unlockedKeyBytes)

                // Name encrypted to the PARENT (root link) key, not the file's node key, and
                // signed so the web client can verify nameAuthor.
                val encryptedName = cryptoHelper.encryptName(item.displayName, rootLinkPublicKey, signingKey.unlockedKeyBytes)

                // Hash = HMAC-SHA256(plaintextName, rootNodeHashKey) for collision detection.
                // Abort if the key is missing rather than fall back to the SHA-1 content hex,
                // which the server rejects and which leaves a half-completed zombie upload.
                val nameHashForCreate = shareService.rootNodeHashKeyBytes()?.let {
                    cryptoHelper.computeNameHash(item.displayName, it)
                } ?: error("uploadFile: rootNodeHashKey not cached — refusing to upload with a garbage name hash")

                sessionKey = cryptoHelper.generateSessionKey()
                nodePublicKeyArmored = nodeKey.publicKeyArmored
                val contentKeyPacketBytes = cryptoHelper.encryptSessionKeyToNode(sessionKey, nodePublicKeyArmored)
                contentKeyPacketBase64 = cryptoHelper.base64Encode(contentKeyPacketBytes)
                // Sign the RAW session-key bytes, not the PKESK: the web client decrypts the CKP
                // first then verifies against the plaintext key, so signing the packet yields
                // "Signed digest did not match".
                contentKeyPacketSignature = cryptoHelper.signData(sessionKey.key, signingKey.unlockedKeyBytes)

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
                        tags = photoTags,
                        // The web client reads the CKP from Photo.ContentKeyPacket.
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
                        // CKP at link level too so the server populates FileProperties.ContentKeyPacket.
                        contentKeyPacket = contentKeyPacketBase64,
                        contentKeyPacketSignature = contentKeyPacketSignature,
                    ),
                )

                // Photos stream endpoint first, fall back to v2/volumes (useVolumeEndpoints=true
                // then routes the upload/commit paths to the volume variants too). Both create
                // paths retry so a single DNS hiccup at upload start isn't a hard failure.
                useVolumeEndpoints = false
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
                val createdIds = if (streamResult.isSuccess) {
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
                fileId = createdIds.first
                revisionId = createdIds.second

                // Fetch the verification code before the stream loop so per-block verifier tokens
                // (= base64(verificationCodeBytes XOR encBlock)) compute inline, no second pass.
                verificationCodeBase64 = retryUploadCall { _ ->
                    semaphore.withPermit {
                        manager.invoke {
                            getVerificationData(shareId, fileId, revisionId)
                        }.valueOrThrow
                    }
                }.verificationCode
                verificationCodeBytes = Base64.decode(verificationCodeBase64, Base64.DEFAULT)
            }

            // The node now exists on Drive (created this run, or carried over from a resume manifest);
            // record it so a non-retryable failure below can best-effort delete the orphan.
            orphanFileId = fileId
            orphanShareId = shareId

            data class BlockSpill(
                val file: File,
                val encSignature: String,
                val hashBytes: ByteArray,
                val verifierTokenBase64: String,
                val encSize: Long,
                /**
                 * Plaintext byte count before encryption. Goes into xAttr.BlockSizes, which Drive
                 * Web uses to split the ciphertext stream back into per-block PGP packets. Storing
                 * encSize instead would offset every boundary by the ~200B packet overhead and
                 * misalign the decoder → video downloads but won't play on Web.
                 */
                val plaintextSize: Long,
            )
            // Parallel encrypt pipeline. The source is opened once; FileChannel.read(buf, position)
            // is thread-safe for the position arg, so coroutines pread independently. Each permit:
            // pread → PGP encrypt+sign (serialized via withCryptoLock for the libgojni single-thread
            // invariant) → SHA-256 + verifier XOR → spill → throttled progress emit. The BlockSpill
            // array MUST stay index-ordered — the manifest signs hash concatenation in that order.
            val blockArr = arrayOfNulls<BlockSpill>(numBlocks)

            // On resume, reconstruct already-spilled BlockSpills from disk + manifest so the
            // encrypt loop skips them.
            if (resuming) {
                val m = priorManifest!!
                m.blocks.forEach { bm ->
                    val cachedFile = File(tempDir, "block_${bm.index}.enc")
                    blockArr[bm.index] = BlockSpill(
                        file = cachedFile,
                        encSignature = bm.encSignature,
                        hashBytes = UploadResumeManifest.decodeB64(bm.hashB64),
                        verifierTokenBase64 = bm.verifierTokenB64,
                        encSize = bm.encSize,
                        plaintextSize = bm.plaintextSize,
                    )
                }
            }

            // Atomically rewrite progress.json. The mutex keeps the encode-and-rename
            // indivisible so two concurrently-finished blocks can't trash the JSON.
            val manifestMutex = Mutex()
            suspend fun persistManifestSnapshot() {
                manifestMutex.withLock {
                    val completedList = blockArr.filterNotNull().sortedBy { spill ->
                        // index isn't stored directly; recover it from the filename.
                        spill.file.nameWithoutExtension.substringAfter("block_").toInt()
                    }
                    val perBlock = completedList.map { spill ->
                        val idx = spill.file.nameWithoutExtension.substringAfter("block_").toInt()
                        UploadResumeManifest.BlockManifest(
                            index = idx,
                            encSignature = spill.encSignature,
                            hashB64 = UploadResumeManifest.encodeB64(spill.hashBytes),
                            verifierTokenB64 = spill.verifierTokenBase64,
                            encSize = spill.encSize,
                            plaintextSize = spill.plaintextSize,
                        )
                    }
                    UploadResumeManifest.save(
                        tempDir,
                        UploadResumeManifest(
                            completedBlocks = perBlock.size,
                            totalBlocks = numBlocks,
                            blockSize = blockSize,
                            sizeBytes = totalBytes,
                            fileId = fileId,
                            revisionId = revisionId,
                            useVolumeEndpoints = useVolumeEndpoints,
                            sessionKeyB64 = UploadResumeManifest.encodeB64(sessionKey.key),
                            verificationCodeB64 = verificationCodeBase64,
                            contentKeyPacketB64 = contentKeyPacketBase64,
                            contentKeyPacketSignature = contentKeyPacketSignature,
                            nodePublicKeyArmored = nodePublicKeyArmored,
                            blocks = perBlock,
                        ),
                    )
                }
            }

            // Fresh uploads write an empty manifest up front so an early failure (e.g. block 0)
            // still leaves the header metadata + fileId for a later resume.
            if (!resuming) {
                persistManifestSnapshot()
            }

            val doneEncryptedBytes = AtomicLong(
                blockArr.filterNotNull().sumOf { it.plaintextSize }
            )
            val lastEncryptEmitMs = AtomicLong(0L)
            // Open the source PFD once, shared across the parallel encrypt tasks. Skipped when
            // every block is already encrypted (only a later step failed last attempt).
            val remainingIndices = (0 until numBlocks).filter { blockArr[it] == null }
            if (remainingIndices.isNotEmpty()) {
                val sourcePfd: ParcelFileDescriptor = context.contentResolver
                    .openFileDescriptor(Uri.parse(uploadUri), "r")
                    ?: error("Cannot openFileDescriptor: $uploadUri")
                try {
                    FileInputStream(sourcePfd.fileDescriptor).use { fis ->
                        val sourceChannel: FileChannel = fis.channel
                        coroutineScope {
                            remainingIndices.map { idx ->
                                async(Dispatchers.IO) {
                                    encryptSlots.withPermit {
                                        val offset = idx.toLong() * blockSize
                                        // Size the buffer to the remaining bytes (final block may be
                                        // short). When sizeBytes is missing (e.g. MediaStore SIZE not
                                        // yet published for a fresh photo) ask for a full block so we
                                        // read what's there rather than truncate.
                                        val want = if (totalBytes > 0L)
                                            minOf(blockSize.toLong(), totalBytes - offset).toInt()
                                                .coerceAtLeast(0)
                                        else blockSize
                                        if (want == 0) {
                                            // Zero-length tail block; filterNotNull() drops this slot.
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

                                        // PGP encrypt + sign serialize through the global cryptoLock;
                                        // pread/sha256/verifier/spill stay parallel.
                                        val (encBlock, encSig) = cryptoHelper.withCryptoLock {
                                            val eb = cryptoHelper.encryptBlock(chunk, sessionKey)
                                            val es = cryptoHelper.signBlockEncrypted(
                                                chunk, signingKey.unlockedKeyBytes, nodePublicKeyArmored,
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
                                        // Write the bytes FIRST, then the manifest — load() discards
                                        // a manifest whose claimed blocks aren't backed on disk.
                                        blockFile.writeBytes(encBlock)
                                        blockArr[idx] = BlockSpill(
                                            file = blockFile,
                                            encSignature = encSig,
                                            hashBytes = hashBytes,
                                            verifierTokenBase64 = tokenBase64,
                                            encSize = encBlock.size.toLong(),
                                            plaintextSize = chunk.size.toLong(),
                                        )
                                        // Non-fatal if this fails — the bytes are on disk; worst case
                                        // the next resume re-encrypts a few blocks.
                                        runCatching { persistManifestSnapshot() }
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
            }
            // Final 100% Encrypting tick so the bar lands cleanly at the phase boundary.
            if (onProgress != null) {
                val finalDone = doneEncryptedBytes.get()
                val denom = if (totalBytes > 0L) totalBytes else finalDone
                onProgress(UploadPhase.Encrypting, denom, denom)
            }
            val blockInfos: List<BlockSpill> = blockArr.filterNotNull()
            Log.d(TAG, "uploadFile: spilled ${blockInfos.size} block(s) to ${tempDir.absolutePath}")

            // Generate + encrypt the JPEG thumbnails. Drive uses two types: DEFAULT (type=1, ≤512px,
            // the grid preview) and PHOTO (type=2, ≤1920px, the viewer preview). Both encrypt with
            // the same session key. The PHOTO thumbnail is image-only and skipped when the source is
            // already at or below 1920px (mirrors Drive Android's isBiggerThenPhotoThumbnail gate),
            // so a small image doesn't ship a redundant second copy.
            // Any image (incl. RAW/DNG — decodeFileDescriptor reaches their embedded preview) or
            // video. A format the decoder can't handle just yields no thumbnail (the generator is
            // null-safe), so being inclusive here costs nothing and matches the official client's
            // "any Image category" gate — without it a RAW upload ships with no preview at all.
            val supportedThumbnailMime = item.mimeType.startsWith("image/") ||
                item.mimeType.startsWith("video/")
            val isImagePhoto = item.mimeType.startsWith("image/")
            val wantPhotoThumbnail = isImagePhoto &&
                (item.width > THUMBNAIL_PHOTO_MAX_PX || item.height > THUMBNAIL_PHOTO_MAX_PX ||
                    (item.width == 0 && item.height == 0)) // unknown dims → generate; scaler is a no-op if small

            // One encrypted thumbnail awaiting upload. [token] is filled after the CDN PUT.
            data class ThumbSpill(
                val type: Int,
                val plain: ByteArray,
                val enc: ByteArray,
                val hash: ByteArray,
                var token: String? = null,
            )
            val thumbSpills = mutableListOf<ThumbSpill>()
            if (supportedThumbnailMime) {
                // DEFAULT (512px) for every supported source.
                runCatching { generateThumbnailBytes(uploadUri, item.mimeType, THUMBNAIL_DEFAULT_MAX_PX) }
                    .getOrNull()?.let { plain ->
                        runCatching { cryptoHelper.encryptBlock(plain, sessionKey) }.getOrNull()?.let { enc ->
                            runCatching { cryptoHelper.sha256(enc) }.getOrNull()?.let { h ->
                                thumbSpills.add(ThumbSpill(THUMBNAIL_TYPE_DEFAULT, plain, enc, h))
                            }
                        }
                    }
                // PHOTO (1920px), image-only.
                if (wantPhotoThumbnail) {
                    runCatching { generateImageThumbnailBytes(uploadUri, item.mimeType, THUMBNAIL_PHOTO_MAX_PX, THUMBNAIL_PHOTO_MAX_BYTES) }
                        .getOrNull()?.let { plain ->
                            runCatching { cryptoHelper.encryptBlock(plain, sessionKey) }.getOrNull()?.let { enc ->
                                runCatching { cryptoHelper.sha256(enc) }.getOrNull()?.let { h ->
                                    thumbSpills.add(ThumbSpill(THUMBNAIL_TYPE_PHOTO, plain, enc, h))
                                }
                            }
                        }
                }
            }
            // DEFAULT bytes power the immediate local gallery thumbnail cache after commit.
            val thumbnailBytes: ByteArray? = thumbSpills.firstOrNull { it.type == THUMBNAIL_TYPE_DEFAULT }?.plain
            Log.d(TAG, "uploadFile: thumbnails generated=${thumbSpills.map { it.type }}")

            // POST drive/blocks — all context in the body. Thumbnail blocks sort at negative indices
            // (-5 DEFAULT, -4 PHOTO), so their hashes lead the manifest below.
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
                thumbnailList = thumbSpills.map { ThumbnailUploadInfoDto(type = it.type) },
            )
            // Fall back to a thumbnail-free request if the server rejects this one, so the content
            // upload always succeeds.
            val uploadLinksResp = try {
                semaphore.withPermit {
                    manager.invoke { requestUploadLinks(blockUploadRequest) }.valueOrThrow
                }
            } catch (e: Exception) {
                if (thumbSpills.isNotEmpty()) {
                    Log.w(TAG, "uploadFile: requestUploadLinks with thumbnails failed (${e.message}), retrying without thumbnails")
                    thumbSpills.clear()
                    semaphore.withPermit {
                        manager.invoke {
                            requestUploadLinks(blockUploadRequest.copy(thumbnailList = emptyList()))
                        }.valueOrThrow
                    }
                } else throw e
            }

            // Upload each block to its CDN bare URL via the Proton API layer (which adds the
            // Authorization + x-pm-appversion headers the CDN requires). sortedUploadLinks is
            // index-ordered so idx aligns with blockInfos. Bounded to 2 in flight; any failure
            // cancels the rest via coroutineScope.
            val sortedUploadLinks = uploadLinksResp.uploadLinks.sortedBy { it.index }
            val doneUploadedBytes = AtomicLong(0L)
            val lastUploadEmitMs = AtomicLong(0L)
            coroutineScope {
                sortedUploadLinks.mapIndexed { idx, uploadLink ->
                    async {
                        val info = blockInfos[idx]
                        if (!info.file.exists()) error("upload temp block evicted: ${info.file.absolutePath}")
                        // Same retry envelope as the metadata calls so mid-block SSL/DNS flakes
                        // are covered from one tuning point.
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
                        // Count plaintext bytes so the denominator matches the Encrypting phase.
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

            // Upload each generated thumbnail to its matching CDN link. ThumbnailLinks entries
            // identify the slot via ThumbnailType (1 = DEFAULT, 2 = PHOTO), not Index. A link whose
            // type matches no spill is ignored; a spill with no link is dropped from the commit.
            val thumbLinksByType = uploadLinksResp.thumbnailLinks.associateBy { it.thumbnailType }
            for (spill in thumbSpills) {
                val link = thumbLinksByType[spill.type]
                    // Single-thumbnail servers may return one untyped link — accept it for DEFAULT.
                    ?: uploadLinksResp.thumbnailLinks.firstOrNull()?.takeIf {
                        spill.type == THUMBNAIL_TYPE_DEFAULT && uploadLinksResp.thumbnailLinks.size == 1
                    }
                if (link == null) {
                    Log.w(TAG, "uploadFile: no ThumbnailLink for type=${spill.type} — omitting from commit")
                    continue
                }
                try {
                    retryUploadCall { _ ->
                        val thumbPart = MultipartBody.Part.createFormData(
                            "Block", "blob",
                            spill.enc.toRequestBody("application/octet-stream".toMediaType()),
                        )
                        cdnUploadSlots.withPermit {
                            manager.invoke {
                                uploadBlockCdn(
                                    url = link.bareUrl,
                                    block = thumbPart,
                                    storageToken = link.token,
                                )
                            }.valueOrThrow
                        }
                    }
                    spill.token = link.token
                    Log.d(TAG, "uploadFile: thumbnail CDN upload OK (${spill.enc.size}B type=${spill.type})")
                } catch (e: Exception) {
                    Log.w(TAG, "uploadFile: thumbnail CDN upload failed (type=${spill.type}): ${e.message}")
                }
            }
            // Only thumbnails whose CDN PUT succeeded go into the manifest + commit.
            val committedThumbs = thumbSpills.filter { it.token != null }.sortedBy { it.type }

            // Manifest = concat(sha256(encryptedBlock)) over all committed blocks, sorted by
            // index ascending. Thumbnail indices are negative (-5 DEFAULT, -4 PHOTO) so the
            // thumbnail hashes come first in type order, then content blocks in upload order.
            val manifestHashes = buildList {
                committedThumbs.forEach { add(it.hash) }
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
            // xAttr Media carries DISPLAY dimensions (W/H swapped for rotated media); fall back to
            // the unrotated MediaStore values only when the caller didn't resolve them.
            val xAttrWidth = xAttrMetadata.displayWidth ?: item.width
            val xAttrHeight = xAttrMetadata.displayHeight ?: item.height
            val xAttr = cryptoHelper.encryptXAttr(
                modificationTimeIso  = modTime,
                sizeBytes            = item.sizeBytes,
                blockSizes           = plaintextBlockSizes,
                width                = xAttrWidth,
                height               = xAttrHeight,
                durationMillis       = item.duration,
                nodePublicKeyArmored = nodePublicKeyArmored,
                signerKeyBytes       = signingKey.unlockedKeyBytes,
                sha1HexDigest        = hash,
                latitude             = xAttrMetadata.latitude,
                longitude            = xAttrMetadata.longitude,
                cameraOrientation    = xAttrMetadata.cameraOrientation,
                cameraCaptureTimeIso = xAttrMetadata.cameraCaptureTimeIso,
                cameraDevice         = xAttrMetadata.cameraDevice,
                subjectCoordinates   = xAttrMetadata.subjectCoordinates,
            )

            // Use the correct commit endpoint based on how the file was created:
            //   • createFileByVolume (useVolumeEndpoints=true) → commitRevisionByVolume (v2)
            //     The v2 endpoint implicitly commits all uploaded blocks/thumbnails via CDN.
            //     No BlockList or ThumbnailList needed in the request body.
            //   • createPhoto / stream path (useVolumeEndpoints=false) → legacy share-based commit
            //     Requires explicit BlockList, ThumbnailList, ContentKeyPacket in the request.
            // Drive's wire ContentHash on a photo upload is HMAC-SHA256(rootNodeHashKey, sha256-hex-of-content),
            // NOT the bare SHA-256 of the content. Drive web's `photosTransferPayloadBuilder` rejects
            // payloads whose ContentHash doesn't verify against the photos-root NodeHashKey with the
            // misleading error "Cannot build photo payload without a content hash". Fall back to the bare
            // SHA-256 only when the root NodeHashKey isn't available (cold start) — at that point the
            // upload was already going to fail other consistency checks anyway, so logging is fine.
            val photoContentHash = shareService.rootNodeHashKeyBytes()?.let { rootHashKey ->
                cryptoHelper.computeNameHash(hash, rootHashKey)
            } ?: hash.also {
                Log.w(TAG, "uploadFile: photos root NodeHashKey unavailable — falling back to bare SHA-256 for ContentHash; expect Drive web rejection")
            }
            if (useVolumeEndpoints) {
                val v2Request = CommitRevisionV2Request(
                    manifestSignature = manifestSignature,
                    signatureAddress  = signingKey.email,
                    xAttr             = xAttr,
                    photo             = PhotoMetaDto(captureTime = item.dateTaken / 1000L, contentHash = photoContentHash, tags = photoTags.map { it.toLong() }),
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
                    photo             = PhotoMetaDto(captureTime = item.dateTaken / 1000L, contentHash = photoContentHash, tags = photoTags.map { it.toLong() }),
                    contentKeyPacket  = contentKeyPacketBase64,
                    contentKeyPacketSignature = contentKeyPacketSignature,
                    // One CommitThumbnailDto per uploaded thumbnail. Index is the negative block
                    // index (-5 DEFAULT, -4 PHOTO) Drive assigns thumbnail blocks.
                    thumbnailList     = committedThumbs.map { spill ->
                        CommitThumbnailDto(
                            index = if (spill.type == THUMBNAIL_TYPE_PHOTO) THUMBNAIL_PHOTO_INDEX else THUMBNAIL_DEFAULT_INDEX,
                            hash  = cryptoHelper.base64Encode(spill.hash),
                            token = spill.token!!,
                            type  = spill.type,
                        )
                    },
                )
                retryUploadCall { _ ->
                    semaphore.withPermit {
                        manager.invoke {
                            commitRevision(shareId, fileId, revisionId, commitRequest)
                        }.valueOrThrow
                    }
                }
            }
            Log.d(TAG, "uploadFile: committed fileId=$fileId v2=$useVolumeEndpoints (thumbnails=${committedThumbs.map { it.type }})")

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
            // Success: wipe tempDir now that the bytes are committed to the CDN. Done
            // INSIDE the success branch (not a finally) so the failure branches below
            // can hold the wipe back when the failure is retry-eligible.
            runCatching { tempDir.deleteRecursively() }
                .onFailure { Log.w(TAG, "uploadFile: success cleanup of ${tempDir.absolutePath} failed: ${it.message}") }
            fileId
            } catch (e: CancellationException) {
                // User-initiated cancel (back-out of the editor, app being closed, parent
                // batch being torn down). No point hoarding a partial upload's blocks
                // since the user explicitly walked away. Wipe and rethrow so structured
                // concurrency tears down cleanly.
                runCatching { tempDir.deleteRecursively() }
                    .onFailure { Log.w(TAG, "uploadFile: cancel cleanup of ${tempDir.absolutePath} failed: ${it.message}") }
                throw e
            } catch (e: Throwable) {
                // Non-cancel failure. Split by retry-eligibility:
                //  - retryable (network / IO / transient) → KEEP tempDir so the caller's
                //    next batch run resumes from the manifest instead of restarting from
                //    block 0. For a 100 MB photo at 4 MB blocks this saves up to 25
                //    redundant Go-runtime encrypt + SHA-256 + verifier-XOR passes.
                //  - non-retryable (auth, quota, malformed input, anything we won't redo)
                //    → wipe, since no future attempt will benefit from the cached state.
                if (isRetryableUploadFailure(e)) {
                    Log.d(TAG, "uploadFile: retryable failure (${e.javaClass.simpleName}: ${e.message}) — preserving tempDir ${tempDir.name} for resume")
                } else {
                    Log.d(TAG, "uploadFile: non-retryable failure (${e.javaClass.simpleName}: ${e.message}) — wiping tempDir ${tempDir.name}")
                    runCatching { tempDir.deleteRecursively() }
                        .onFailure { Log.w(TAG, "uploadFile: failure cleanup of ${tempDir.absolutePath} failed: ${it.message}") }
                    // Best-effort: drop the uncommitted node so a non-retryable failure doesn't leave
                    // an orphan link on Drive (repeated failures would otherwise accumulate them).
                    val orphan = orphanFileId
                    val orphanShare = orphanShareId
                    if (orphan != null && orphanShare != null) {
                        runCatching {
                            apiProvider.get<DriveApiService>(userId).invoke {
                                deleteLinks(orphanShare, eu.akoos.photos.data.api.dto.DeleteLinksRequest(listOf(orphan)))
                            }.valueOrThrow
                        }.onFailure { Log.w(TAG, "uploadFile: orphan node cleanup (deleteLinks $orphan) failed: ${it.message}") }
                    }
                }
                throw e
            }
        }

    /**
     * Generates a JPEG thumbnail from the given media URI. Downscales the image so the
     * longest edge is at most [maxPx] pixels (default 512), then compresses to JPEG at 80 %.
     * Returns null for non-image/video MIME types, when the content resolver cannot open
     * the stream, or when decoding fails.
     */
    private fun generateThumbnailBytes(uri: String, mimeType: String, maxPx: Int = 512): ByteArray? {
        val maxBytes = if (maxPx <= THUMBNAIL_DEFAULT_MAX_PX) THUMBNAIL_DEFAULT_MAX_BYTES else THUMBNAIL_PHOTO_MAX_BYTES
        return when {
            mimeType.startsWith("video/") -> generateVideoThumbnailBytes(uri, maxPx, maxBytes)
            mimeType.startsWith("image/") -> generateImageThumbnailBytes(uri, mimeType, maxPx, maxBytes)
            else -> null
        }
    }

    /** Compresses [bitmap] to JPEG, stepping quality down a ladder until the result fits [maxBytes]
     *  (or the floor quality is hit). Mirrors the official client's thumbnail size budget instead of
     *  a fixed quality, so grid thumbnails don't bloat the encrypted block and its later download. */
    private fun compressJpegToBudget(bitmap: Bitmap, maxBytes: Int): ByteArray {
        var bytes = ByteArray(0)
        for (q in intArrayOf(90, 80, 70, 60, 50, 40, 30, 20, 10)) {
            bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, q, out); out.toByteArray()
            }
            if (bytes.size <= maxBytes) break
        }
        return bytes
    }

    private fun generateVideoThumbnailBytes(uri: String, maxPx: Int, maxBytes: Int): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            val androidUri = Uri.parse(uri)
            if (androidUri.scheme == "file") {
                retriever.setDataSource(androidUri.path ?: return@runCatching null)
            } else {
                retriever.setDataSource(context, androidUri)
            }
            // Grab the first decodable frame. Try a sync frame near 1s, then frame 0, then ANY frame
            // (OPTION_CLOSEST, not just a keyframe) at 0 and near the middle — a clip with no early
            // keyframe, an unusual codec, or a very short duration still yields a thumbnail instead
            // of silently producing none.
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rawFrame = retriever.getFrameAtTime(1_000_000 /* 1s */, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: (if (durationMs > 0) retriever.getFrameAtTime(durationMs * 500L, MediaMetadataRetriever.OPTION_CLOSEST) else null)
            if (rawFrame == null) {
                Log.w(TAG, "generateVideoThumbnailBytes: no decodable frame for $uri (durationMs=$durationMs)")
                return@runCatching null
            }
            // Upload the RAW frame in storage orientation — do NOT rotate it. Drive web rotates the
            // thumbnail by the container's rotation tag at display time (the same way it rotates the
            // video itself), so pre-rotating here double-rotates it and shows it sideways on the web.
            // Matches the official client (ThumbnailUtils.createVideoThumbnail keeps the raw frame).
            val frame = rawFrame
            val scaled = if (frame.width > maxPx || frame.height > maxPx) {
                val scale = maxPx.toFloat() / maxOf(frame.width, frame.height)
                val sw = (frame.width * scale).toInt().coerceAtLeast(1)
                val sh = (frame.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(frame, sw, sh, true).also {
                    if (it !== frame) frame.recycle()
                }
            } else frame
            compressJpegToBudget(scaled, maxBytes).also { scaled.recycle() }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrElse { e ->
        Log.w(TAG, "generateVideoThumbnailBytes failed for $uri: ${e.message}")
        null
    }

    private fun generateImageThumbnailBytes(uri: String, mimeType: String, maxPx: Int, maxBytes: Int): ByteArray? {
        return runCatching {
            val androidUri = Uri.parse(uri)
            // Decode through a SEEKABLE file descriptor, not an InputStream. RAW/DNG decoding needs
            // random access to reach the embedded preview, so decodeStream returns null for them while
            // decodeFileDescriptor succeeds — this is what lets a RAW image get a thumbnail at all (the
            // official client decodes the same way). The fd path is a strict superset for JPEG/PNG/HEIC.
            context.contentResolver.openFileDescriptor(androidUri, "r")?.use { pfd ->
                val fd = pfd.fileDescriptor
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(fd, null, opts)
                if (opts.outMimeType == null) return null
                val w = opts.outWidth.coerceAtLeast(1)
                val h = opts.outHeight.coerceAtLeast(1)
                var sample = 1
                var tw = w; var th = h
                while (tw / 2 >= maxPx || th / 2 >= maxPx) { sample *= 2; tw = w / sample; th = h / sample }

                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val sampled = BitmapFactory.decodeFileDescriptor(fd, null, decodeOpts) ?: return null

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

                // EXIF orientation rebake — BitmapFactory.decodeFileDescriptor returns raw sensor
                // pixels and never auto-rotates, so bake the orientation into EVERY image (incl. RAW,
                // whose embedded preview is also in sensor orientation). The saved thumbnail JPEG
                // carries no EXIF tag, so without this a portrait phone photo with EXIF 90° renders
                // sideways on Drive Web (which shows the thumbnail pixels as-is). ExifInterface reads
                // the orientation tag for JPEG / HEIC / RAW alike.
                val orientation = runCatching {
                    context.contentResolver.openInputStream(androidUri)?.use {
                        androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                        )
                    } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)

                val bitmap = applyExifRotationToBitmap(scaledBitmap, orientation)
                compressJpegToBudget(bitmap, maxBytes).also { bitmap.recycle() }
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
