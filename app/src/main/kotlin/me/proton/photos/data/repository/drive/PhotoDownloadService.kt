package me.proton.photos.data.repository.drive

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.photos.data.api.DriveApiService
import me.proton.photos.data.crypto.DriveCryptoHelper
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.util.retryAfterMs
import me.proton.photos.util.retryWithBackoff
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhotoDownloadSvc"

/**
 * Full-res photo download.
 *
 * Owns the block-CDN OkHttpClient because content blocks need `pm-storage-token` headers
 * instead of `Authorization: Bearer`, bypassing the ApiProvider stack.
 */
@Singleton
class PhotoDownloadService @Inject constructor(
    private val apiProvider: ApiProvider,
    private val cryptoHelper: DriveCryptoHelper,
    private val shareService: PhotosShareService,
    private val linkDetailHelpers: LinkDetailHelpers,
    @ApplicationContext private val context: Context,
) {
    private val semaphore get() = shareService.networkSemaphore
    private val cdnHttpClient = OkHttpClient()

    /**
     * Downloads the full-resolution bytes for [photo] into the on-disk cache.
     *
     * [onProgress] (optional) receives `(doneBytes, totalBytes)` while blocks decrypt. Callback
     * is throttled to ~once per 250 ms (plus a guaranteed final 100% emission) so callers can
     * render a percentage without thrashing recomposition. `totalBytes` comes from
     * [CloudPhoto.sizeBytes] when non-zero, otherwise the in-flight ciphertext sum is used as
     * a best-effort fallback. Called from the IO dispatcher — callbacks fire on whichever
     * thread is currently running the block loop.
     */
    suspend fun downloadFullResPhoto(
        userId: UserId,
        photo: CloudPhoto,
        onProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "fullres").also { it.mkdirs() }
        val ext = photo.mimeType.substringAfterLast('/').let { if (it == "jpeg") "jpg" else it }
        val outputFile = File(cacheDir, "${photo.linkId}.$ext")
        if (outputFile.exists() && outputFile.length() > 0) {
            // Cache hit — emit a single 100% so spinners that observe progress can close.
            onProgress?.invoke(outputFile.length(), outputFile.length())
            return@withContext outputFile
        }

        val rootLinkKeyBytes = shareService.getRootLinkKeyBytes(userId) ?: error("Cannot decrypt root link key")
        val manager = apiProvider.get<DriveApiService>(userId)

        // ── Node key ─────────────────────────────────────────────────────────────
        val linkDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, photo.volumeId, listOf(photo.linkId))
        val link = linkDetailMap[photo.linkId]?.link ?: error("Link not found: ${photo.linkId}")
        val nodeKeyArmored = link.nodeKey ?: error("No nodeKey for ${photo.linkId}")
        val nodePassphraseArmored = link.nodePassphrase ?: error("No nodePassphrase for ${photo.linkId}")

        val parentLinkId = link.parentLinkId
        val parentKeyBytes: ByteArray = if (parentLinkId == null || parentLinkId == shareService.photosRootLinkId()) {
            rootLinkKeyBytes
        } else {
            val albumDetailMap = linkDetailHelpers.batchFetchLinkDetails(userId, photo.volumeId, listOf(parentLinkId))
            val albumLink = albumDetailMap[parentLinkId]?.link
            if (albumLink?.nodeKey != null && albumLink.nodePassphrase != null) {
                try {
                    cryptoHelper.decryptNodeKey(albumLink.nodeKey, albumLink.nodePassphrase, rootLinkKeyBytes)
                } catch (e: Exception) {
                    Log.w(TAG, "downloadFullResPhoto: album key decrypt failed: ${e.message}")
                    rootLinkKeyBytes
                }
            } else rootLinkKeyBytes
        }
        val nodeKeyBytes = cryptoHelper.decryptNodeKey(nodeKeyArmored, nodePassphraseArmored, parentKeyBytes)

        // ── Revision ID ───────────────────────────────────────────────────────────
        // Priority:
        //   1. stored entity revisionId (from buildPhotoEntity, already resolved)
        //   2. Link.FileProperties.ActiveRevision.ID  ← canonical per official SDK
        //   3. Photo.ActiveRevision.RevisionID        ← Photos-API field
        //   4. Link.ActiveRevision.ID                 ← legacy fallback
        //   5. listRevisions API call
        val revisionId: String = photo.revisionId.ifEmpty {
            link.fileProperties?.activeRevision?.id?.takeIf { it.isNotEmpty() }
                ?: linkDetailMap[photo.linkId]?.photo?.activeRevision?.revisionId?.takeIf { it.isNotEmpty() }
                ?: link.activeRevision?.id?.takeIf { it.isNotEmpty() }
        } ?: run {
            Log.d(TAG, "downloadFullResPhoto: revisionId missing, trying listRevisions for ${photo.linkId}")
            try {
                val revList = semaphore.withPermit {
                    manager.invoke { listRevisions(photo.shareId, photo.linkId) }.valueOrThrow
                }
                (revList.revisions.firstOrNull { it.state == 1 } ?: revList.revisions.firstOrNull())?.id
            } catch (e: Exception) {
                Log.w(TAG, "downloadFullResPhoto: listRevisions failed: ${e.message}")
                null
            }
        } ?: error("No revisionId for ${photo.linkId}")

        // ── Fetch revision (for block download URLs) ──────────────────────────────
        val revisionResp = semaphore.withPermit {
            try {
                manager.invoke { getRevisionByVolume(photo.volumeId, photo.linkId, revisionId) }.valueOrThrow
            } catch (e: Exception) {
                Log.w(TAG, "downloadFullResPhoto: volume getRevision failed (${e.message}), trying share-based")
                manager.invoke { getRevision(photo.shareId, photo.linkId, revisionId) }.valueOrThrow
            }
        }

        // ── Session key via CKP ───────────────────────────────────────────────────
        // Content blocks in Proton Drive are SEIPD-only packets — they do NOT contain an
        // embedded key packet (PKESK).  The session key must come from ContentKeyPacket
        // which is a separate PKESK encrypted to the node's public key.
        //
        // ContentKeyPacket resolution order (most-reliable first):
        //   1. Photo.ContentKeyPacket  — primary location for Photos (FileProperties is null for photos)
        //   2. FileProperties.ContentKeyPacket — primary for standard Drive files
        //   3. Revision.ContentKeyPacket / Revision.Photo.ContentKeyPacket — legacy fallbacks
        //   4. ActiveRevision.ContentKeyPacket — wrong place but kept as last resort
        val ckpBatchPhoto = linkDetailMap[photo.linkId]?.photo?.contentKeyPacket   // ← PRIMARY for photos
        val ckpFileProps  = link.fileProperties?.contentKeyPacket                  // standard Drive files
        val ckpRev        = revisionResp.revision.contentKeyPacket                 // revision endpoint (legacy)
        val ckpPhoto      = revisionResp.revision.photo?.contentKeyPacket          // photo sub-object in revision
        val ckpLink       = link.activeRevision?.contentKeyPacket                  // wrong location, kept as fallback
        Log.d(TAG, "downloadFullResPhoto CKP sources: batchPhoto=${ckpBatchPhoto?.take(20)} fileProps=${ckpFileProps?.take(20)} rev=${ckpRev?.take(20)} photoRev=${ckpPhoto?.take(20)} link=${ckpLink?.take(20)}")
        Log.d(TAG, "downloadFullResPhoto revision fields: id=${revisionResp.revision.id} blocks=${revisionResp.revision.blocks.size} state=${revisionResp.revision.state} manifestSig=${revisionResp.revision.manifestSignature?.take(10)}")
        var ckp = ckpBatchPhoto ?: ckpFileProps ?: ckpRev ?: ckpPhoto ?: ckpLink

        // Fallback: the Photos batch API (POST drive/photos/volumes/{v}/links) omits
        // FileProperties.ContentKeyPacket.  The canonical single-link endpoint
        // GET drive/shares/{shareId}/links/{linkId} (no "v2" prefix) returns the full
        // LinkDto including FileProperties.ContentKeyPacket.
        if (ckp == null) {
            Log.d(TAG, "downloadFullResPhoto: all batch CKP sources null — fetching via getFullLinkDetails")
            ckp = runCatching {
                val resp = semaphore.withPermit {
                    manager.invoke { getFullLinkDetails(photo.shareId, photo.linkId) }.valueOrThrow
                }
                val fromFileProps = resp.link.fileProperties?.contentKeyPacket
                val fromActiveRev = resp.link.activeRevision?.contentKeyPacket
                Log.d(TAG, "downloadFullResPhoto: getFullLinkDetails fileProps=${fromFileProps?.take(20) ?: "null"} activeRev=${fromActiveRev?.take(20) ?: "null"}")
                fromFileProps ?: fromActiveRev
            }.getOrElse { e ->
                Log.w(TAG, "downloadFullResPhoto: getFullLinkDetails failed: ${e.message}")
                null
            }
        }

        val sessionKey = ckp?.let { cryptoHelper.decryptSessionKey(it, nodeKeyBytes) }
        if (ckp != null) Log.d(TAG, "downloadFullResPhoto: CKP present (${ckp.take(20)}…), sessionKey=${if (sessionKey != null) "OK" else "FAILED"}")
        else Log.d(TAG, "downloadFullResPhoto: no CKP in any source — will attempt per-block binary-PGP (thumbnails only)")

        val blocks = revisionResp.revision.blocks.sortedBy { it.index }
        if (blocks.isEmpty()) error("No blocks in revision for ${photo.linkId}")

        // Accumulate raw SHA-256 of EACH downloaded encrypted block so we can verify the
        // ManifestSignature once all blocks land. A mismatch would mean either a corrupted
        // block, server-side tampering, or a stale revision — log it but still serve the bytes.
        val downloadedBlockHashes = mutableListOf<ByteArray>()

        // Progress accounting — tracks decrypted-byte total so the UI percentage matches
        // CloudPhoto.sizeBytes (which is plaintext-size). Throttled to 250 ms between emits.
        var doneBytes = 0L
        val totalBytes = if (photo.sizeBytes > 0) photo.sizeBytes else 0L
        var lastEmitMs = 0L
        // Initial 0% so the UI can show "Downloading 0% — 0 MB / N MB" instead of a blank spinner.
        if (onProgress != null) onProgress(0L, totalBytes)

        // Blocks are downloaded directly via OkHttpClient using the BareURL + pm-storage-token
        // header from the revision response. The Drive API downloadBlock endpoint cannot be used
        // here because ApiProvider.invoke{} expects a JSON response body — block data is raw
        // binary, causing a JSON parse error. We use the same pm-storage-token header pattern
        // as block uploads (not Authorization: Bearer, which returns HTTP 400 for CDN endpoints).
        outputFile.outputStream().use { out ->
            for (block in blocks) {
                val bareUrl = block.bareUrl ?: block.url
                    ?: error("No URL for block ${block.index} of ${photo.linkId}")
                val reqBuilder = Request.Builder().url(bareUrl)
                block.token?.let { reqBuilder.header("pm-storage-token", it) }
                val encBytes = retryWithBackoff(maxAttempts = 4) { attempt ->
                    cdnHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        if (resp.code == 429 || resp.code == 503) {
                            val ra = resp.retryAfterMs()
                            if (ra != null) delay(ra)
                            error("HTTP ${resp.code} on block ${block.index} download (attempt ${attempt + 1})")
                        }
                        if (!resp.isSuccessful) error("Block ${block.index} download failed: HTTP ${resp.code}")
                        resp.body?.bytes() ?: error("Empty body for block ${block.index}")
                    }
                }
                downloadedBlockHashes += cryptoHelper.sha256(encBytes)

                // Proton Drive content blocks are SEIPD-only — the session key from
                // ContentKeyPacket is the primary (and normally the only) decrypt path.
                // Binary-PGP (PKESK+SEIPD) is used as a last-resort fallback for any
                // self-contained PGP messages that might appear (e.g., legacy blocks).
                val decryptedBytes: ByteArray = if (sessionKey != null) {
                    val encFile = File(cacheDir, "enc_${photo.linkId}_${block.index}")
                    val decFile = File(cacheDir, "dec_${photo.linkId}_${block.index}")
                    encFile.writeBytes(encBytes)
                    val result = runCatching { cryptoHelper.decryptFileToDestination(sessionKey, encFile, decFile) }
                    encFile.delete()
                    if (result.isSuccess) {
                        val bytes = decFile.readBytes()
                        decFile.delete()
                        bytes
                    } else {
                        decFile.delete()
                        // Try binary-PGP as last resort before giving up
                        cryptoHelper.decryptBinaryPgpWithNodeKey(encBytes, nodeKeyBytes)
                            ?: error("Block ${block.index} decrypt failed: sessionKey path: ${result.exceptionOrNull()?.message}")
                    }
                } else {
                    // No session key — try binary-PGP (only works if block has embedded PKESK)
                    cryptoHelper.decryptBinaryPgpWithNodeKey(encBytes, nodeKeyBytes)
                        ?: error("Block ${block.index} decrypt failed: no session key and binary-PGP returned null")
                }
                out.write(decryptedBytes)

                // Accumulate decrypted-byte progress and emit at most every 250 ms.
                // If the server didn't give us a sizeBytes (rare), grow the denominator
                // alongside the numerator so the percentage stays sane (showing 100% only
                // when the loop actually finishes).
                if (onProgress != null) {
                    doneBytes += decryptedBytes.size.toLong()
                    val denom = if (totalBytes > 0) totalBytes else doneBytes
                    val nowMs = System.currentTimeMillis()
                    val isLastBlock = block.index == blocks.last().index
                    if (isLastBlock || nowMs - lastEmitMs >= 250) {
                        lastEmitMs = nowMs
                        onProgress(doneBytes, denom)
                    }
                }
            }
        }

        // Verify ManifestSignature once all blocks are in. The manifest = concat(blockHashes)
        // signed by the uploader. We only fast-verify when the signer is our own primary address.
        val manifestSig = revisionResp.revision.manifestSignature
        val signerEmail = revisionResp.revision.signatureAddress
        if (manifestSig != null) {
            val ownPublicKeys = cryptoHelper.getOwnPublicKeysArmored(userId)
            if (ownPublicKeys.isNotEmpty()) {
                val manifestBytes = downloadedBlockHashes.fold(ByteArray(0)) { acc, h -> acc + h }
                val ok = cryptoHelper.verifyDetachedSignature(manifestBytes, manifestSig, ownPublicKeys)
                if (!ok) {
                    Log.w(TAG, "VERIFY_FAIL manifest linkId=${photo.linkId} signer=$signerEmail (downloaded ${downloadedBlockHashes.size} blocks)")
                }
            }
        }

        Log.d(TAG, "downloadFullResPhoto: saved ${outputFile.length()} bytes for ${photo.linkId}")
        outputFile
    }
}
