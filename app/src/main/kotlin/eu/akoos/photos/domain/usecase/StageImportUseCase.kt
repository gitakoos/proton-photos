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

package eu.akoos.photos.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.ImportStagedDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.ImportStagedEntity
import eu.akoos.photos.data.importer.ImportScanCache
import eu.akoos.photos.data.importer.TakeoutZipReader
import eu.akoos.photos.domain.importer.ImportFailure
import eu.akoos.photos.domain.importer.TakeoutSidecar
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.ImportDiagnostics
import eu.akoos.photos.util.ImportEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext

private const val TAG = "StageImport"

/** Where the staging pass is: reading the archive for the first time, stepping over what an earlier
 *  attempt already staged, or staging new entries. */
enum class StagePhase { SCAN, CATCH_UP, STAGE }

/** One progress tick of [StageImportUseCase.stage]: the [phase], entries [done] of [total], and how many
 *  file bytes of [bytesTotal] (0 when the provider did not report the size) the read has consumed. */
data class StageProgress(
    val phase: StagePhase,
    val done: Int,
    val total: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
)

/** What a staging pass produced: the rows now staged, whether the archive stopped being readable partway
 *  ([endedEarly], with [readPercent] of the file reached), the rest being a normal completion. */
data class StageResult(val staged: Int, val endedEarly: Boolean, val readPercent: Int)

/**
 * The staging pass of the import review flow: it walks a picked Takeout/Ente `.zip` once for the
 * sidecar index and media count, then a second time to resolve each media entry's metadata and cache a
 * small thumbnail, writing one [ImportStagedEntity] per entry. Nothing is uploaded here; the review
 * screen (a later piece) reads the staged rows, and the upload pass (a later piece) sends the kept ones.
 *
 * The metadata resolution reuses [ImportTakeoutUseCase]'s pure companion helpers, so a staged row
 * carries the same title, date, GPS and description the direct import would apply. Each entry is copied
 * to a single temp file (a thumbnail decode needs a random-access, re-readable source, which the
 * forward-only zip stream is not), the thumbnail is decoded downscaled, and the temp is deleted before
 * the reader advances, so the whole export never has to fit on disk at once and only one full entry is
 * ever materialised. A per-entry failure is logged and skipped; cancellation propagates.
 *
 * The worker calls [stage] on [kotlinx.coroutines.Dispatchers.IO]: the reader's per-entry callback is
 * synchronous, so each entry's suspend work (the DAO writes, the progress callback) is bridged onto the
 * caller's job with [runBlocking], exactly as [ImportTakeoutUseCase.import] does.
 */
@Singleton
class StageImportUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importStagedDao: ImportStagedDao,
    private val drivePhotoRepository: DrivePhotoRepository,
    private val photoListingDao: PhotoListingDao,
) {

    /**
     * Stages every media entry the zip [openZip] supplies under [zipId] and reports how it went.
     * [onProgress] receives the phase, a running (done, total) and how far into the file the read is.
     * [zipBytes] is the archive's size when the provider knows it (non-positive otherwise), used for the
     * percentages and to recognise a saved scan as belonging to this file. [userId] is the account whose
     * Drive the stage-time dedup check queries, so a row can be badged as already present before anything
     * uploads.
     *
     * The pass is resumable. The first walk (the sidecar index) is saved when it ends, and the staged rows
     * are written in batches as the second walk goes, so a run that was interrupted (the app closed, the
     * process killed, the source dropped out) continues on the next attempt: the saved index replaces the
     * first walk, and the entries already staged are stepped over without being copied, decoded or hashed
     * again. A saved scan exists only for a run that did not finish, so a re-pick of an already staged zip
     * is a fresh start: the prior run's cached thumbnails are deleted from disk and its staged rows are
     * dropped before the walk begins, so it never leaves orphaned files or stale rows behind.
     *
     * A failure that is not about one entry (storage full, the source unreadable) aborts the pass with
     * everything staged so far kept; a damaged entry is skipped, and a damaged archive the stream cannot
     * step past ends the pass early with the readable part staged ([StageResult.endedEarly]).
     */
    suspend fun stage(
        userId: UserId,
        zipId: String,
        zipBytes: Long,
        openZip: () -> InputStream,
        onProgress: suspend (StageProgress) -> Unit,
    ): StageResult {
        val filesDir = context.filesDir
        val scanCache = ImportScanCache(File(context.cacheDir, ImportScanCache.DIR_NAME))
        val savedScan = scanCache.load(zipId, zipBytes)
        val resumed = savedScan != null
        if (!resumed) ImportEventLog.reset(filesDir)
        ImportEventLog.record(
            filesDir,
            "stage ${if (resumed) "resume" else "start"} archiveMb=${zipBytes / MB} " +
                "cacheFreeMb=${context.cacheDir.usableSpace / MB} sdk=${Build.VERSION.SDK_INT}",
        )

        var phase = "open"
        var scanEndedEarly = false
        try {
            val alreadyStaged: Set<String>
            if (resumed) {
                alreadyStaged = importStagedDao.entryNames(zipId).toHashSet()
                ImportEventLog.record(filesDir, "resume alreadyStaged=${alreadyStaged.size}")
            } else {
                importStagedDao.thumbPaths(zipId).forEach { path ->
                    path?.let { runCatching { File(it).delete() } }
                }
                importStagedDao.clearForZip(zipId)
                alreadyStaged = emptySet()
            }

            val thumbDir = File(context.cacheDir, THUMB_DIR).apply { mkdirs() }
            val reader = TakeoutZipReader(openZip)
            // Each synchronous reader callback is bridged onto the caller's job so cancellation flows in.
            val parentContext = coroutineContext[Job] ?: EmptyCoroutineContext

            val scan = savedScan ?: run {
                phase = "scan"
                runBlocking(parentContext) { onProgress(StageProgress(StagePhase.SCAN, 0, 0, 0L, zipBytes)) }
                var lastTick = -1L
                var lastDecile = -1
                val fresh = reader.scan { bytes ->
                    val tick = if (zipBytes > 0) bytes * 1000 / zipBytes else bytes / SCAN_TICK_BYTES
                    if (tick != lastTick) {
                        lastTick = tick
                        runBlocking(parentContext) {
                            ensureActive()
                            onProgress(StageProgress(StagePhase.SCAN, 0, 0, bytes, zipBytes))
                        }
                        val decile = if (zipBytes > 0) (bytes * 10 / zipBytes).toInt() else -1
                        if (decile != lastDecile && decile >= 0) {
                            lastDecile = decile
                            ImportEventLog.record(filesDir, "scan ${decile * 10}% readMb=${bytes / MB}")
                        }
                    }
                }
                scanEndedEarly = fresh.walk.endedEarly || fresh.walk.endedShort(zipBytes)
                ImportEventLog.record(
                    filesDir,
                    "scan done media=${fresh.mediaCount} sidecars=${fresh.sidecars.size} " +
                        "damaged=${fresh.walk.damagedEntries} endedEarly=$scanEndedEarly" +
                        (if (scanEndedEarly) " atMb=${fresh.walk.bytesRead / MB}" else "") +
                        (fresh.walk.endedBy?.let { " by=${ImportFailure.describe(it)}" } ?: ""),
                )
                scanCache.save(zipId, zipBytes, fresh)
                fresh
            }
            val index = scan.sidecars
            val total = scan.mediaCount

            phase = "stage"
            var processed = alreadyStaged.size
            var failedEntries = 0
            var bytesDone = 0L
            var lastDecile = -1
            var lastCatchUpTick = -1L
            var worked = false
            val buffer = ArrayList<ImportStagedEntity>(BATCH_SIZE)
            runBlocking(parentContext) {
                onProgress(StageProgress(if (resumed) StagePhase.CATCH_UP else StagePhase.STAGE, processed, total, 0L, zipBytes))
            }

            // The entry is fully processed (copied, thumbed, its temp deleted) before the reader advances,
            // one at a time, and a failure of one entry's own work is logged and skipped.
            val walk = try {
                reader.forEachMedia(
                    onBytes = { bytes ->
                        bytesDone = bytes
                        // While stepping over entries staged by the earlier attempt nothing else ticks, so
                        // report how far the catch-up has read instead of leaving the bar still.
                        if (!worked && resumed) {
                            val tick = if (zipBytes > 0) bytes * 1000 / zipBytes else bytes / SCAN_TICK_BYTES
                            if (tick != lastCatchUpTick) {
                                lastCatchUpTick = tick
                                runBlocking(parentContext) {
                                    ensureActive()
                                    onProgress(StageProgress(StagePhase.CATCH_UP, processed, total, bytes, zipBytes))
                                }
                            }
                        }
                    },
                ) { name, input ->
                    if (name in alreadyStaged) return@forEachMedia
                    worked = true
                    runBlocking(parentContext) {
                        ensureActive()
                        val temp = File.createTempFile("stage_", tempSuffix(name), context.cacheDir)
                        try {
                            temp.outputStream().use { input.copyTo(it) }

                            val meta = TakeoutSidecar.candidateMediaKeys(TakeoutZipReader.baseName(name))
                                .firstNotNullOfOrNull { index[it] }
                            val mimeType = ImportTakeoutUseCase.mimeTypeFor(name)
                            val dateMs = ImportTakeoutUseCase.resolveDateMs(meta, name, System.currentTimeMillis())

                            // Best-effort thumbnail: any decode failure leaves thumbPath null so the review shows
                            // a placeholder rather than failing the whole entry. Cancellation still propagates.
                            val thumbPath = try {
                                writeThumbnail(temp, mimeType, thumbDir, stableName(zipId, name))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "thumbnail failed for $name: ${e.message}")
                                null
                            }

                            // Stage-time dedup: hash the same temp the thumbnail came from and ask whether Drive
                            // already holds these exact bytes, so the review can badge a pre-existing photo before
                            // anything uploads. Already on the worker's IO dispatcher. Any failure (no root hash
                            // key, a listing read that throws) degrades to "treat as new": the upload pass runs its
                            // own dedup, so a false negative costs only a redundant upload that is then resolved
                            // without a second copy. Never fatal to staging; cancellation still propagates.
                            val alreadyInDrive = try {
                                val sha1 = sha1Hex(temp)
                                val hash = drivePhotoRepository.cloudContentHash(sha1)
                                hash != null &&
                                    photoListingDao.findExistingContentHashes(userId.id, listOf(hash)).isNotEmpty()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "dedup check failed for $name: ${e.message}")
                                false
                            }
                            // Per-entry badge decision, stripped in release. No name or hash, only the running index
                            // and the verdict, so a debug trace shows why a review row is or is not badged.
                            Log.d(TAG, "staged ${processed + 1}/$total: ${if (alreadyInDrive) "already in Drive" else "new"}")

                            val size = temp.length()
                            buffer += ImportStagedEntity(
                                zipId = zipId,
                                entryName = name,
                                title = meta?.title?.takeIf { it.isNotBlank() } ?: TakeoutZipReader.baseName(name),
                                dateMs = dateMs,
                                lat = meta?.lat,
                                lng = meta?.lng,
                                description = meta?.description,
                                sizeBytes = size,
                                thumbPath = thumbPath,
                                excluded = false,
                                uploaded = false,
                                stagedAt = System.currentTimeMillis(),
                                albumName = TakeoutSidecar.albumFolderOf(name),
                                alreadyInDrive = alreadyInDrive,
                            )
                            processed++
                            if (buffer.size >= BATCH_SIZE) {
                                importStagedDao.upsert(buffer.toList())
                                buffer.clear()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A fault about the entry's own bytes skips it; one about the storage or the place the
                            // zip is read from would fail every entry after it, so it stops the pass instead.
                            if (e is java.io.IOException && !ImportFailure.isArchiveFault(e)) throw e
                            Log.w(TAG, "stage failed for $name: ${e.message}")
                            failedEntries++
                            processed++
                        } finally {
                            // Staging keeps only the small thumbnail, never the full file.
                            temp.delete()
                        }
                        onProgress(StageProgress(StagePhase.STAGE, processed, total, bytesDone, zipBytes))
                        val decile = if (total > 0) processed * 10 / total else -1
                        if (decile != lastDecile && decile >= 0) {
                            lastDecile = decile
                            ImportEventLog.record(filesDir, "stage ${decile * 10}% $processed/$total failedEntries=$failedEntries")
                        }
                    }
                }
            } finally {
                // Whatever the pass reached is kept, so a stop or a failure resumes from here, not from zero.
                if (buffer.isNotEmpty()) {
                    withContext(NonCancellable) {
                        importStagedDao.upsert(buffer.toList())
                        buffer.clear()
                    }
                }
            }

            val staged = importStagedDao.countForZip(zipId)
            val walkEndedEarly = walk.endedEarly || walk.endedShort(zipBytes)
            val endedEarly = scanEndedEarly || walkEndedEarly
            val readPercent = when {
                !endedEarly -> 100
                zipBytes > 0 -> (walk.bytesRead * 100 / zipBytes).toInt().coerceIn(0, 99)
                else -> 0
            }
            ImportEventLog.record(
                filesDir,
                "stage done staged=$staged failedEntries=$failedEntries damaged=${walk.damagedEntries} " +
                    "endedEarly=$endedEarly" +
                    (if (walkEndedEarly) " atMb=${walk.bytesRead / MB}" else "") +
                    (walk.endedBy?.let { " by=${ImportFailure.describe(it)}" } ?: ""),
            )
            scanCache.delete(zipId)
            ImportDiagnostics.recordStage(staged)
            return StageResult(staged = staged, endedEarly = endedEarly, readPercent = readPercent)
        } catch (e: CancellationException) {
            ImportEventLog.record(filesDir, "stage interrupted phase=$phase")
            throw e
        } catch (e: Exception) {
            ImportEventLog.record(filesDir, "stage failed phase=$phase ${ImportFailure.describe(e)}")
            ImportDiagnostics.recordError(e.javaClass.simpleName)
            throw e
        }
    }

    /** The temp-file suffix that keeps [entryName]'s extension so a decoder can still sniff the
     *  container, or null when the name has none. */
    private fun tempSuffix(entryName: String): String? {
        val ext = ImportTakeoutUseCase.extensionOf(TakeoutZipReader.baseName(entryName))
        return if (ext.isEmpty()) null else ".$ext"
    }

    /** Hex SHA-1 of [file], streamed so a large entry is never fully buffered. Mirrors the upload pass's
     *  digest so the stage-time dedup check keys on the same content hash the upload one resolves against. */
    private fun sha1Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Decodes a downscaled JPEG thumbnail of [source] into [thumbDir] under [stableName], returning its
     * absolute path, or null when nothing decodable could be produced. An image takes the bounds-then
     * sample-size decode with its EXIF orientation baked in; a video takes a still frame off
     * [MediaMetadataRetriever]. A zero-length result is treated as a failure and cleaned up.
     */
    private fun writeThumbnail(source: File, mimeType: String, thumbDir: File, stableName: String): String? {
        val outFile = File(thumbDir, "$stableName.jpg")
        val ok = if (ImportTakeoutUseCase.isVideo(mimeType)) {
            writeVideoThumb(source, outFile)
        } else {
            writeImageThumb(source, outFile)
        }
        return if (ok && outFile.length() > 0L) {
            outFile.absolutePath
        } else {
            outFile.delete()
            null
        }
    }

    /**
     * Decodes [source] as an image to a thumbnail JPEG at [outFile]. A bounds-only pass reads the native
     * size so the sample-size decode never materialises a huge bitmap; the EXIF orientation is baked into
     * the pixels (BitmapFactory ignores the tag) and the result is scaled to the thumbnail edge. Every
     * intermediate bitmap is recycled. Returns true when a file was written.
     */
    private fun writeImageThumb(source: File, outFile: File): Boolean {
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            source.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return false

            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(srcW, srcH, THUMB_EDGE) }
            decoded = source.inputStream().use { BitmapFactory.decodeStream(it, null, opts) } ?: return false

            oriented = ExifHelper.applyOrientation(decoded, ExifHelper.readOrientation(source))
            scaled = scaleToLongEdge(oriented, THUMB_EDGE)

            return FileOutputStream(outFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, fos)
            }
        } finally {
            if (scaled != null && scaled !== oriented) scaled.recycle()
            if (oriented != null && oriented !== decoded) oriented.recycle()
            decoded?.recycle()
        }
    }

    /**
     * Grabs a still frame from the video at [source] and writes it as a thumbnail JPEG at [outFile].
     * [MediaMetadataRetriever.getScaledFrameAtTime] decodes straight to a downscaled target on O_MR1+,
     * so a large frame is never fully materialised; older levels take the full frame then scale. The
     * retriever is always released. Returns true when a file was written.
     */
    private fun writeVideoThumb(source: File, outFile: File): Boolean {
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            retriever.setDataSource(source.absolutePath)
            val vw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && vw > 0 && vh > 0) {
                val sample = sampleSizeFor(vw, vh, THUMB_EDGE)
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (vw / sample).coerceAtLeast(1),
                    (vh / sample).coerceAtLeast(1),
                )
            } else {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: return false

            scaled = scaleToLongEdge(frame, THUMB_EDGE)
            return FileOutputStream(outFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, fos)
            }
        } finally {
            runCatching { retriever.release() }
            if (scaled != null && scaled !== frame) scaled.recycle()
            frame?.recycle()
        }
    }

    /** Largest power-of-two sample size that keeps the sampled longest edge at or above [cap], so the
     *  precise scale afterwards downsizes rather than upsizes. */
    private fun sampleSizeFor(width: Int, height: Int, cap: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= cap) sample *= 2
        return sample
    }

    /** Scales [src] down so its longest edge equals [maxLongEdge], preserving aspect ratio. Returns
     *  [src] unchanged when it already fits (never upscales). */
    private fun scaleToLongEdge(src: Bitmap, maxLongEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxLongEdge) return src
        val ratio = maxLongEdge.toFloat() / longest.toFloat()
        val newW = (src.width * ratio).toInt().coerceAtLeast(1)
        val newH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    companion object {

        /** Cache subdirectory holding staged thumbnails. Mirrored in the app's clear-caches sweep. */
        internal const val THUMB_DIR = "import_thumbs"

        /** Thumbnail longest edge in pixels, enough for a crisp review tile without holding real images. */
        private const val THUMB_EDGE = 256

        /** JPEG quality for a review thumbnail. */
        private const val THUMB_QUALITY = 80

        private const val MB = 1_048_576L

        /** Without a known archive size, progress ticks once per this many bytes read. */
        private const val SCAN_TICK_BYTES = 64L * MB

        /** How many staged rows to buffer before a batch insert, so the DB is not hit per entry. */
        private const val BATCH_SIZE = 50

        /**
         * A deterministic, collision-free base name for the cached thumbnail of [entryName] within
         * [zipId]'s run: the hex SHA-1 of the two joined by a zero byte that cannot occur in either
         * string, so distinct entries never share a file and re-staging the same entry reuses one name.
         * Pure (no Android, no I/O), so a plain JVM test pins it.
         */
        internal fun stableName(zipId: String, entryName: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(zipId.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(entryName.toByteArray(Charsets.UTF_8))
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
