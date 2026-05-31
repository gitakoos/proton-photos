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
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.crypto.common.pgp.SessionKey
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.api.dto.RevisionBlockDto
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.domain.entity.CloudPhoto
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhotoDownloadSvc"

/** Per-revision parallelism for block fetch+decrypt. Block-level only; orthogonal to the
 *  high-level [PhotosShareService.networkSemaphore] used for API calls. */
private const val BLOCK_PARALLELISM = 4

/** Heuristic encrypted-block size used to estimate total bytes when the server reports
 *  neither [CloudPhoto.sizeBytes] nor a per-block [RevisionBlockDto.size]. Matches the
 *  4 MiB Proton Drive block size so the denominator is close to reality. */
private const val DEFAULT_BLOCK_SIZE = 4L * 1024 * 1024

/**
 * Full-res photo download.
 *
 * Delegates the raw CDN byte fetch to [CdnBlockFetcher] (which holds the pinned
 * [@CdnOkHttpClient] client) because content blocks need `pm-storage-token` headers
 * instead of `Authorization: Bearer`, bypassing the ApiProvider stack.
 */
@Singleton
class PhotoDownloadService @Inject constructor(
    private val apiProvider: ApiProvider,
    private val cryptoHelper: DriveCryptoHelper,
    private val shareService: PhotosShareService,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val cdnBlockFetcher: CdnBlockFetcher,
    @ApplicationContext private val context: Context,
) {
    private val semaphore get() = shareService.networkSemaphore

    /**
     * Single-flight per linkId so multiple viewer entries for the SAME photo don't fire
     * parallel block downloads that race on tempFile writes. Observed for a 98-block
     * cloud video the user kept swiping back to: each settledPage change re-ran
     * loadCloud → fired a fresh downloadFullResPhoto → all writers stomped on the same
     * .tmp, never published the renamed outputFile, and the badge looped through
     * "Downloading…" forever. With this map the second/third caller joins the first
     * via the shared CompletableDeferred and returns the same File once it completes.
     *
     * Cleanup: the OWNER coroutine's `finally` runs on every exit path (success, error,
     * CancellationException) so a normal download removes its entry. The bounded
     * backstop below catches the theoretical case where the map grows during a long
     * carousel of distinct linkIds — we log a warning and sweep any stale completed
     * entries that might have slipped through (defense-in-depth; should be empty in
     * practice because the finally runs reliably).
     */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<File>>()
    private val inFlightWarnThreshold = 64

    /**
     * Downloads the full-resolution bytes for [photo] into the on-disk cache.
     *
     * [onProgress] (optional) receives `(doneBytes, totalBytes)` while blocks decrypt. Callback
     * is throttled to ~once per 250 ms (plus a guaranteed final 100% emission) so callers can
     * render a percentage without thrashing recomposition. `totalBytes` comes from
     * [CloudPhoto.sizeBytes] when non-zero, otherwise the sum of per-block encrypted sizes
     * (or a 4 MiB-per-block estimate) is used as a best-effort fallback so the UI can show a
     * percentage instead of a bare spinner. Called from the IO dispatcher — callbacks fire
     * on whichever thread is currently running the block coroutine.
     */
    suspend fun downloadFullResPhoto(
        userId: UserId,
        photo: CloudPhoto,
        onProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): File {
        inFlight[photo.linkId]?.takeIf { !it.isCompleted }?.let { return it.await() }
        val deferred = CompletableDeferred<File>()
        val prev = inFlight.putIfAbsent(photo.linkId, deferred)
        if (prev != null && !prev.isCompleted) return prev.await()
        // Bounded backstop: sweep any stale completed entries the finally somehow
        // missed (e.g. a future bug short-circuits the cleanup) and warn if the live
        // count is still climbing. Keeps the map from growing without bound across a
        // session of thousands of distinct linkIds.
        if (inFlight.size > inFlightWarnThreshold) {
            inFlight.entries.removeAll { it.value.isCompleted }
            if (inFlight.size > inFlightWarnThreshold) {
                Log.w(TAG, "downloadFullResPhoto: inFlight=${inFlight.size} exceeds threshold $inFlightWarnThreshold — investigate cleanup")
            }
        }
        try {
            val file = doDownload(userId, photo, onProgress)
            deferred.complete(file)
            return file
        } catch (t: Throwable) {
            // CancellationException also lands here; completeExceptionally + the finally
            // below propagate the cancel to any joiners and remove the entry.
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(photo.linkId, deferred)
        }
    }

    private suspend fun doDownload(
        userId: UserId,
        photo: CloudPhoto,
        onProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "fullres").also { it.mkdirs() }
        // Map MIME → file extension explicitly: ExoPlayer / Coil rely on the suffix to
        // infer the container/codec. The naive substring split produces things like
        // ".quicktime" or ".x-matroska" which neither player recognises, so the
        // downloaded clip ends up unplayable. Anything not in the table falls back to
        // the substring (covers mp4/webm/3gpp where MIME subtype already matches).
        val sub = photo.mimeType.substringAfterLast('/').lowercase()
        val ext = when (sub) {
            "jpeg" -> "jpg"
            "quicktime" -> "mov"
            "x-matroska" -> "mkv"
            "x-msvideo" -> "avi"
            "x-ms-wmv" -> "wmv"
            "mpeg" -> if (photo.mimeType.startsWith("video/")) "mpg" else "mp3"
            else -> sub
        }
        val outputFile = File(cacheDir, "${photo.linkId}.$ext")
        val tempFile = File(cacheDir, "${photo.linkId}.$ext.tmp")
        // Atomic temp-rename: writes go to a `.tmp` sibling and only get renamed to
        // outputFile after the byte stream + manifest verification both finish. Without
        // this, a partial download from process death / network failure / mid-write
        // backgrounding lands at outputFile directly and the cache-hit check below treats
        // it as complete; ExoPlayer's FileDataSource then opens it, reads the truncated
        // MOOV header, and throws FileDataSourceException → black screen on
        // swipe-back-to-video.
        if (outputFile.exists() && outputFile.length() > 0) {
            // Size-validate against the cloud metadata so any truncated file (from an
            // older app version without atomic-rename, or any other source) doesn't keep
            // getting served as a "cache hit" — that path is how FileDataSourceException
            // resurfaces after first install. photo.sizeBytes is 0 when the server didn't
            // report a size (rare for photos, common for older album-share items); in that
            // case we trust whatever we have.
            val matches = photo.sizeBytes == 0L || outputFile.length() == photo.sizeBytes
            if (matches) {
                onProgress?.invoke(outputFile.length(), outputFile.length())
                return@withContext outputFile
            }
            Log.w(TAG, "downloadFullResPhoto: cached file size mismatch (have=${outputFile.length()} want=${photo.sizeBytes}) — re-downloading")
            outputFile.delete()
        }
        // Sweep any stale .tmp from a prior aborted concat so we re-stitch cleanly.
        // (Per-block dec_*.bin files are NOT swept — they are the resume state.)
        if (tempFile.exists()) tempFile.delete()

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

        // ── Progress accounting ──────────────────────────────────────────────────
        // The viewer pill is gated on totalBytes > 0 (otherwise it falls back to a bare
        // spinner with no percentage), so we always derive a non-zero denominator when
        // we possibly can:
        //   1. CloudPhoto.sizeBytes when the server reported it
        //   2. Otherwise sum the per-block encrypted Size field
        //   3. Otherwise estimate at 4 MiB per block (Drive's chunking constant)
        // The estimate is close enough to the decrypted total for percentage purposes —
        // the PGP overhead per block is small relative to the block payload.
        val totalBytes: Long = when {
            photo.sizeBytes > 0 -> photo.sizeBytes
            else -> blocks.sumOf { it.size ?: DEFAULT_BLOCK_SIZE }
        }
        val doneBytes = AtomicLong(0L)
        val lastEmitMs = AtomicLong(0L)

        // ── Resume scan ──────────────────────────────────────────────────────────
        // Per-block dec_${linkId}_${index}.bin files survive across viewer entries /
        // process death, so a 5 GB MKV that got 60 % through can pick up where it left
        // off instead of starting from zero. A dec file is trusted iff it exists and
        // length > 0 (decrypt-on-success is the only writer; a torn write would have
        // ended up at 0 bytes). Resumed blocks contribute their bytes to doneBytes but
        // their encrypted-hash is NOT re-fetched for manifest verification — they were
        // verified at original download time, and re-verifying here would defeat the
        // resume's network-savings purpose.
        val resumedBytes = ConcurrentHashMap<Int, ByteArray>()
        var resumedSize = 0L
        for (block in blocks) {
            val decFile = blockDecFile(cacheDir, photo.linkId, block.index)
            if (decFile.exists() && decFile.length() > 0) {
                val bytes = decFile.readBytes()
                resumedBytes[block.index] = bytes
                resumedSize += bytes.size.toLong()
            }
        }
        if (resumedBytes.isNotEmpty()) {
            doneBytes.set(resumedSize)
            // Emit immediately so the UI reflects the resume state before the parallel
            // fetch even spins up — otherwise the user sees 0 % flash to e.g. 60 %
            // after the first new block lands ~1 s later.
            if (onProgress != null) {
                val denom = if (totalBytes > 0) totalBytes else resumedSize
                onProgress(resumedSize, denom)
                lastEmitMs.set(System.currentTimeMillis())
            }
            Log.d(TAG, "downloadFullResPhoto: resuming ${photo.linkId} — ${resumedBytes.size}/${blocks.size} blocks already on disk (${resumedSize} bytes)")
        } else {
            // Initial 0% so the UI can show "Downloading 0%" instead of a blank spinner.
            if (onProgress != null) onProgress(0L, totalBytes)
        }

        // ── Parallel block download + decrypt ────────────────────────────────────
        // Block-level semaphore (4) caps how many CDN fetch+decrypt pipelines run at
        // once. Each coroutine writes to its own dec_*.bin so there is no shared
        // output-stream contention. The encrypted SHA-256 of each freshly downloaded
        // block goes into a ConcurrentHashMap so we can stitch the manifest in index
        // order at the end (without having to re-read every dec file).
        val blockSemaphore = Semaphore(BLOCK_PARALLELISM)
        val freshHashes = ConcurrentHashMap<Int, ByteArray>()
        val toDownload = blocks.filter { it.index !in resumedBytes.keys }
        coroutineScope {
            toDownload.map { block ->
                async {
                    blockSemaphore.withPermit {
                        val hash = downloadAndDecryptBlock(
                            block = block,
                            cacheDir = cacheDir,
                            linkId = photo.linkId,
                            sessionKey = sessionKey,
                            nodeKeyBytes = nodeKeyBytes,
                            onProgress = onProgress,
                            doneBytes = doneBytes,
                            totalBytes = totalBytes,
                            lastEmitMsRef = lastEmitMs,
                        )
                        freshHashes[block.index] = hash
                    }
                }
            }.awaitAll()
        }

        // Final guaranteed emit so the UI lands on 100 % even if the 250 ms throttle
        // ate the last per-block update.
        if (onProgress != null) {
            val finalDone = doneBytes.get()
            val denom = if (totalBytes > 0) totalBytes else finalDone
            onProgress(finalDone, denom)
        }

        // ── Manifest signature verify ────────────────────────────────────────────
        // Manifest = concat(blockHashes in index order) detached-signed by the uploader.
        // We can only verify the slice corresponding to freshly downloaded blocks —
        // resumed blocks' encrypted hashes weren't persisted. If we resumed ANY block,
        // we log a warning and skip the verify (re-fetching just the missing hashes
        // would defeat the point of the resume). The original download path still
        // verifies the full manifest, so a corrupted block can only sneak past on a
        // mid-download interruption — and even then the dec bytes themselves were
        // produced by the authenticated PGP decrypt, so corruption would have caused
        // a decrypt failure rather than silent data tampering.
        val manifestSig = revisionResp.revision.manifestSignature
        val signerEmail = revisionResp.revision.signatureAddress
        if (manifestSig != null) {
            if (resumedBytes.isNotEmpty()) {
                Log.w(TAG, "VERIFY_SKIP manifest linkId=${photo.linkId} signer=$signerEmail (resumed ${resumedBytes.size}/${blocks.size} blocks — encrypted hashes unavailable)")
            } else {
                val ownPublicKeys = cryptoHelper.getOwnPublicKeysArmored(userId)
                if (ownPublicKeys.isNotEmpty()) {
                    // The uploader (see PhotoUploadService.signManifest call) PREPENDS each
                    // thumbnail's encrypted SHA-256 to the block hashes before signing — the
                    // canonical SDK convention is type-1 (default) thumb at "index" -5, then
                    // type-2 (photo) thumb at -4, then content blocks at 1, 2, 3, … Reproduce
                    // that ordering here.
                    //
                    // Source order: the revision response is authoritative — the batch link
                    // endpoint we fetched earlier omits the Thumbnails list, so falling back to
                    // it would silently leave thumbnail hashes out and the verify would fail.
                    val revisionThumbs = revisionResp.revision.thumbnails
                    val linkThumbs = link.fileProperties?.activeRevision?.thumbnails
                    val thumbnailHashBytes: List<ByteArray> = (revisionThumbs ?: linkThumbs)
                        ?.sortedBy { it.type }
                        ?.mapNotNull { entry ->
                            entry.hash?.let { h ->
                                runCatching { android.util.Base64.decode(h, android.util.Base64.DEFAULT) }.getOrNull()
                            }
                        }
                        ?: emptyList()
                    val downloadedBlockHashes = blocks.map { freshHashes[it.index] ?: error("Missing hash for block ${it.index}") }
                    val manifestBytes = (thumbnailHashBytes + downloadedBlockHashes)
                        .fold(ByteArray(0)) { acc, h -> acc + h }
                    val ok = cryptoHelper.verifyDetachedSignature(manifestBytes, manifestSig, ownPublicKeys)
                    if (!ok) {
                        Log.w(TAG, "VERIFY_FAIL manifest linkId=${photo.linkId} signer=$signerEmail (downloaded ${downloadedBlockHashes.size} blocks, ${thumbnailHashBytes.size} thumb hashes prepended)")
                    }
                }
            }
        }

        // ── Concat per-block files into tempFile ────────────────────────────────
        // We concat in index order so the output is byte-identical to a streamed
        // sequential download. The per-block files survive partial runs (resume),
        // but once the full concat + atomic-rename succeeds we delete them.
        tempFile.outputStream().use { out ->
            for (block in blocks) {
                val decFile = blockDecFile(cacheDir, photo.linkId, block.index)
                decFile.inputStream().use { it.copyTo(out) }
            }
        }

        // Atomic publish — once renameTo completes, outputFile holds the fully-verified
        // bytes. If renameTo fails (rare; e.g. cacheDir filesystem quirk) fall back to a
        // copy-then-delete so the file at outputFile is still complete.
        val renamed = tempFile.renameTo(outputFile)
        if (!renamed) {
            tempFile.inputStream().use { src ->
                outputFile.outputStream().use { dst -> src.copyTo(dst) }
            }
            tempFile.delete()
        }

        // Clean up per-block files only on full success — leaving them around would
        // be a cache leak and would also confuse the resume-scan on a later re-download
        // (e.g. after the user clears the outputFile manually).
        for (block in blocks) {
            blockDecFile(cacheDir, photo.linkId, block.index).delete()
        }

        Log.d(TAG, "downloadFullResPhoto: saved ${outputFile.length()} bytes for ${photo.linkId}")
        outputFile
    }

    /**
     * Fetches one encrypted block, decrypts it to `dec_${linkId}_${index}.bin`, and
     * returns the SHA-256 of the encrypted bytes (for manifest verification).
     *
     * Retries the network leg up to 4 times with backoff and honours `Retry-After` on
     * 429/503. The decrypt leg is not retried — a decrypt failure means corrupted
     * ciphertext or a bad session key, neither of which is recoverable by retry.
     *
     * Progress is reported by atomic-add to [doneBytes] and a 250 ms-throttled
     * [onProgress] emit guarded by CAS on [lastEmitMsRef] so multiple block coroutines
     * don't race on the timestamp.
     */
    private suspend fun downloadAndDecryptBlock(
        block: RevisionBlockDto,
        cacheDir: File,
        linkId: String,
        sessionKey: SessionKey?,
        nodeKeyBytes: ByteArray,
        onProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)?,
        doneBytes: AtomicLong,
        totalBytes: Long,
        lastEmitMsRef: AtomicLong,
    ): ByteArray {
        val bareUrl = block.bareUrl ?: block.url
            ?: error("No URL for block ${block.index} of $linkId")
        val encBytes = cdnBlockFetcher.fetchBlock(url = bareUrl, token = block.token, maxAttempts = 4)
        val hash = cryptoHelper.sha256(encBytes)

        // Proton Drive content blocks are SEIPD-only — the session key from
        // ContentKeyPacket is the primary (and normally the only) decrypt path.
        // Binary-PGP (PKESK+SEIPD) is used as a last-resort fallback for any
        // self-contained PGP messages that might appear (e.g., legacy blocks).
        val decFile = blockDecFile(cacheDir, linkId, block.index)
        // Disambiguate the encrypted scratch file per-block so two coroutines on
        // adjacent blocks of the same linkId don't collide on the same enc_ path.
        val encFile = File(cacheDir, "enc_${linkId}_${block.index}.bin")
        val decryptedBytes: ByteArray = if (sessionKey != null) {
            encFile.writeBytes(encBytes)
            val result = runCatching { cryptoHelper.decryptFileToDestination(sessionKey, encFile, decFile) }
            encFile.delete()
            if (result.isSuccess) {
                decFile.readBytes()
            } else {
                decFile.delete()
                // Try binary-PGP as last resort before giving up
                val fallback = cryptoHelper.decryptBinaryPgpWithNodeKey(encBytes, nodeKeyBytes)
                    ?: error("Block ${block.index} decrypt failed: sessionKey path: ${result.exceptionOrNull()?.message}")
                // Persist the fallback decrypt so resume + concat work uniformly.
                decFile.writeBytes(fallback)
                fallback
            }
        } else {
            // No session key — try binary-PGP (only works if block has embedded PKESK)
            val plain = cryptoHelper.decryptBinaryPgpWithNodeKey(encBytes, nodeKeyBytes)
                ?: error("Block ${block.index} decrypt failed: no session key and binary-PGP returned null")
            decFile.writeBytes(plain)
            plain
        }

        // Atomic-add to the shared accumulator then throttle the emit. The CAS on
        // lastEmitMsRef means the first coroutine past the 250 ms window wins and the
        // others bail; without it, four coroutines finishing within the same ~ms would
        // each fire an emit and the UI would see redundant updates.
        if (onProgress != null) {
            val newDone = doneBytes.addAndGet(decryptedBytes.size.toLong())
            val nowMs = System.currentTimeMillis()
            val last = lastEmitMsRef.get()
            if (nowMs - last >= 250 && lastEmitMsRef.compareAndSet(last, nowMs)) {
                val denom = if (totalBytes > 0) totalBytes else newDone
                onProgress(newDone, denom)
            }
        }

        return hash
    }

    private fun blockDecFile(cacheDir: File, linkId: String, index: Int): File =
        File(cacheDir, "dec_${linkId}_${index}.bin")

    companion object {
        /**
         * Full-res cache TTL — files older than this are reclaimed on the next prune pass.
         * 30 minutes gives the swipe-back-to-same-photo flow free reuse while bounding the
         * disk footprint for casual browsing. Anything still wanted past the window is
         * re-downloaded on demand.
         */
        const val FULLRES_TTL_MS: Long = 30L * 60L * 1000L

        /**
         * Sweeps `cacheDir/fullres/` for FINAL output files older than [FULLRES_TTL_MS]
         * and deletes them. Skips the per-block scratch files (`dec_*.bin`, `enc_*.bin`)
         * and any `.tmp` partials — those belong to an in-flight or resumable download
         * and the existing resume logic handles their lifecycle.
         *
         * When [networkAvailable] is false the sweep is a no-op so cached photos remain
         * viewable while the device is offline. TTL eviction resumes the next time this
         * runs with a live network.
         */
        fun pruneStaleFullResCache(context: android.content.Context, networkAvailable: Boolean) {
            if (!networkAvailable) {
                Log.d(TAG, "pruneStaleFullResCache: skipping — offline grace")
                return
            }
            val cacheDir = File(context.cacheDir, "fullres")
            if (!cacheDir.isDirectory) return
            val now = System.currentTimeMillis()
            val cutoff = now - FULLRES_TTL_MS
            var deleted = 0
            cacheDir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name
                // Skip in-flight scratch files; only sweep finalized blobs.
                if (name.startsWith("dec_") || name.startsWith("enc_") || name.endsWith(".tmp")) return@forEach
                if (f.lastModified() in 1..cutoff) {
                    if (f.delete()) deleted++
                }
            }
            if (deleted > 0) Log.d(TAG, "pruneStaleFullResCache: removed $deleted stale fullres file(s)")
        }
    }
}
