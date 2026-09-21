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
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.CloudAlbumDao
import eu.akoos.photos.data.db.dao.ImportAlbumMemberDao
import eu.akoos.photos.data.db.dao.ImportHistoryDao
import eu.akoos.photos.data.db.dao.ImportStagedDao
import eu.akoos.photos.data.db.dao.ImportUploadedDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.ImportAlbumMemberEntity
import eu.akoos.photos.data.db.entity.ImportHistoryEntity
import eu.akoos.photos.data.db.entity.ImportStagedEntity
import eu.akoos.photos.data.db.entity.ImportUploadedEntity
import eu.akoos.photos.data.importer.TakeoutZipReader
import eu.akoos.photos.data.repository.drive.UploadPhase
import eu.akoos.photos.data.repository.drive.UploadXAttrMetadata
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.importer.ImportAlbumMode
import eu.akoos.photos.domain.importer.SidecarMeta
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.presentation.metadata.FilenameDate
import eu.akoos.photos.util.AlbumListEventBus
import eu.akoos.photos.util.ImportDiagnostics
import eu.akoos.photos.util.Mp4CreationTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import me.proton.core.domain.entity.UserId
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext

private const val TAG = "ImportTakeout"

/** The server caps an add-to-album request at this many links, so member adds are chunked to it. */
private const val ALBUM_ADD_CHUNK = 10

/** How many import uploads run at once, matching the backup path's own upload parallelism. The zip is read
 *  one entry at a time (a single stream), but the slow upload of each prepared file is handed to a bounded
 *  pool so several are in flight together. Three matches the backup and keeps within the shared network
 *  gate and the crypto process's tuned concurrency; going higher risks over-subscribing both, so it stays
 *  at parity rather than chasing marginal fill of the pipe. */
private const val IMPORT_UPLOAD_PARALLELISM = 3

/** Live progress for one import run: how many media entries are done out of the total, the entry in
 *  flight and, while it uploads, its phase and byte curve. [phase] is null between entries. */
data class ImportProgress(
    val done: Int,
    val total: Int,
    val currentName: String,
    val phase: UploadPhase?,
    val phaseDoneBytes: Long,
    val phaseTotalBytes: Long,
)

/** The tally an import run ends with. [imported] + [alreadyInDrive] + [skipped] + [failed] equals
 *  [total]: every media entry in the zip lands in exactly one of the four. [alreadyInDrive] counts an
 *  entry whose exact bytes Drive already held, so it was resolved without an upload. [albumsCreated] and
 *  [photosAddedToAlbums] are the album-phase outcome, orthogonal to the four upload tallies (the same
 *  photo can join several albums), so they do not enter the total identity. */
data class ImportSummary(
    val total: Int,
    val imported: Int,
    val alreadyInDrive: Int,
    val skipped: Int,
    val failed: Int,
    val albumsCreated: Int = 0,
    val photosAddedToAlbums: Int = 0,
)

/**
 * Uploads the reviewed set of a Google Takeout / Ente import into Drive Photos, carrying the capture
 * date, GPS and caption the staging pass already resolved into each entry's row. It drives
 * [TakeoutZipReader] over the picked `.zip` and the same upload seam every other upload path uses.
 *
 * Only the entries the review kept take part: the staged rows supply the metadata and the set to send,
 * so the walk streams a kept entry to a single temp file, stamps it with what the row knows (the caption
 * into an image's EXIF, the capture date into a video's mvhd), hashes it, uploads it, and deletes the
 * temp before the reader advances, so the whole export never has to fit on disk or in memory at once.
 *
 * The date/metadata mapping is factored into pure helpers on the companion so it is verifiable on the
 * JVM without Android; [uploadStaged] is the Android-facing shell that does the I/O.
 */
@Singleton
class ImportTakeoutUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drivePhotoRepository: DrivePhotoRepository,
    private val writeLocalPhotoMetadataUseCase: WriteLocalPhotoMetadataUseCase,
    private val importStagedDao: ImportStagedDao,
    private val importHistoryDao: ImportHistoryDao,
    private val importUploadedDao: ImportUploadedDao,
    private val importAlbumMemberDao: ImportAlbumMemberDao,
    private val cloudAlbumDao: CloudAlbumDao,
    private val photoListingDao: PhotoListingDao,
    private val albumListEvents: AlbumListEventBus,
    private val appDatabase: AppDatabase,
) {

    /**
     * Uploads the review-kept, not-yet-sent entries for [zipId] into [userId]'s Drive Photos and records
     * the run in import history, returning a per-outcome [ImportSummary].
     *
     * The set to send is the staged rows [ImportStagedDao.pendingUpload] returns (kept and not uploaded),
     * keyed by entry name; the walk of [openZip] streams only those entries and skips the rest unread.
     * Each sent entry carries the metadata its staged row already resolved. [onProgress] receives a
     * running [ImportProgress]. A single entry that fails is logged and counted, never fatal, so one
     * unreadable photo cannot abort the rest; cooperative cancellation is honoured between and during
     * entries. [fileName] names the archive in the history row.
     *
     * A history row is written whichever way the run ends, including an empty pending set, so a finished
     * run is recorded exactly once; [runId] groups the run's per-photo undo ledger and tags that history
     * row, and is guarded so a resumed completion records the run once.
     *
     * [albumMode] decides what happens to the export's albums after the uploads: nothing, empty album
     * shells, or shells with their photos added. The album phase runs once the uploads are done (on the
     * empty-pending path too, so a run killed after its last upload but before the albums were built still
     * completes them), and never fails the import: the photos are already in Drive, so an album failure is
     * logged and the run still reports success.
     */
    suspend fun uploadStaged(
        userId: UserId,
        zipId: String,
        runId: String,
        fileName: String,
        albumMode: ImportAlbumMode,
        openZip: () -> InputStream,
        onProgress: suspend (ImportProgress) -> Unit,
    ): ImportSummary {
        val pending = importStagedDao.pendingUpload(zipId).associateBy { it.entryName }
        val total = pending.size
        if (pending.isEmpty()) {
            // Nothing left to send. A fresh run with no kept entries records a zero row; a resume whose
            // every entry was already handled before the kill reports the full run from the ledger, so a
            // completion that raced a process death is not under-counted. The album phase still runs, so a
            // kill after the last upload but before the albums were built finishes them on this resume.
            val albums = runAlbumPhase(userId, runId, albumMode)
            val summary = buildSummary(runId, zipId, skipped = 0)
                .copy(albumsCreated = albums.albumsCreated, photosAddedToAlbums = albums.photosAddedToAlbums)
            recordHistory(
                runId, zipId, fileName,
                total = summary.total, imported = summary.imported,
                skipped = summary.skipped + summary.alreadyInDrive, failed = summary.failed,
            )
            ImportDiagnostics.recordUpload(
                uploaded = summary.imported,
                alreadyInDrive = summary.alreadyInDrive,
                skipped = summary.skipped,
                failed = summary.failed,
                albumsCreated = summary.albumsCreated,
                photosAddedToAlbums = summary.photosAddedToAlbums,
            )
            return summary
        }

        // The content this run has already sent, keyed by Drive's HMAC ContentHash to the upload that
        // created its link (held as a Deferred the duplicates await). It is read and written only from the
        // sequential reader below, so a plain map needs no lock and leadership falls to the first
        // occurrence in zip order, exactly as a one-at-a-time run resolved it. A second copy of the same
        // bytes later in the archive (the same photo in another export album) resolves to that link and
        // records its own album membership behind the single upload.
        val inRunUpload = HashMap<String, CompletableDeferred<String?>>()

        // Bounded-parallel upload, mirroring the backup path. The zip is still read one entry at a time (a
        // single stream) and the reader does every ordered step itself (extract, metadata stamp, hash,
        // dedup decision), but the slow network upload of a genuinely new file is offloaded to a pool of at
        // most [IMPORT_UPLOAD_PARALLELISM], so several uploads run at once instead of strictly in turn. A
        // permit is taken before a new file's upload and released when it finishes, which also caps how
        // many extracted temp files exist at once. Only the upload is parallel, so the ledger, the dedup and
        // the album membership keep their sequential meaning.
        val uploadSemaphore = Semaphore(IMPORT_UPLOAD_PARALLELISM)
        // Progress + the skipped tally only; the per-outcome counts (imported / already-in-Drive / failed)
        // are read back from the durable ledger in buildSummary, so a resumed run still reports its whole
        // self and a failure needs no separate counter here.
        val processedCount = AtomicInteger(0)
        // Throughput instrumentation, stripped in release with the rest of the logs: how long the run took
        // and how many bytes it actually sent, so a slow import is legible in a debug trace.
        val runStart = System.currentTimeMillis()
        val uploadedBytes = AtomicLong(0)

        val parentContext = coroutineContext[Job] ?: EmptyCoroutineContext
        coroutineScope {
            val uploadScope = this
            val jobs = mutableListOf<Deferred<Unit>>()

            TakeoutZipReader(openZip).forEachMedia { name, input ->
                val row = pending[name] ?: return@forEachMedia
                // The reader stays on the caller's job so cancellation flows in; it prepares each entry in
                // order here and offloads only the upload to [uploadScope].
                runBlocking(parentContext) {
                    ensureActive()
                    val temp = File.createTempFile("import_", tempSuffix(name), context.cacheDir)
                    val sha1: String
                    val contentHash: String?
                    val item: LocalMediaItem
                    val xAttr: UploadXAttrMetadata
                    try {
                        temp.outputStream().use { input.copyTo(it) }
                        val meta = SidecarMeta(
                            takenMs = row.dateMs,
                            lat = row.lat,
                            lng = row.lng,
                            description = row.description,
                            title = row.title,
                        )
                        val mimeType = mimeTypeFor(name)
                        val dateMs = row.dateMs
                        val tempUri = Uri.fromFile(temp).toString()
                        // Both stamps change the bytes, so they must precede the hash: Drive's ContentHash is
                        // computed from exactly what is uploaded. The caption write self-skips a container it
                        // cannot address; the mvhd stamp only matters for a video with a real date.
                        row.description?.takeIf { it.isNotBlank() }?.let { desc ->
                            runCatching {
                                writeLocalPhotoMetadataUseCase.writeDescriptiveText(
                                    tempUri,
                                    description = TextTagEdit.SetTo(desc),
                                )
                            }
                        }
                        if (dateMs != null && isVideo(mimeType)) {
                            runCatching { Mp4CreationTime.stamp(temp, dateMs) }
                        }
                        sha1 = sha1Hex(temp)
                        contentHash = drivePhotoRepository.cloudContentHash(sha1)
                        item = buildLocalMediaItem(tempUri, meta, name, temp.length(), mimeType, dateMs)
                        xAttr = buildXAttr(meta, dateMs, width = null, height = null, durationMs = null)
                    } catch (e: CancellationException) {
                        temp.delete()
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "prepare failed for $name: ${e.message}")
                        temp.delete()
                        val done = processedCount.incrementAndGet()
                        onProgress(ImportProgress(done, total, name, null, 0L, 0L))
                        return@runBlocking
                    }

                    val displayName = row.title?.takeIf { it.isNotBlank() } ?: TakeoutZipReader.baseName(name)
                    val leaderDeferred = contentHash?.let { inRunUpload[it] }
                    when {
                        // A duplicate of bytes an earlier entry this run is already uploading: do not send a
                        // second copy. Await that upload's link and record this entry's own album membership
                        // behind it. The temp is not needed here, so it goes now.
                        leaderDeferred != null -> {
                            row.thumbPath?.let { path -> runCatching { File(path).delete() } }
                            temp.delete()
                            jobs += uploadScope.async<Unit> {
                                val linkId = leaderDeferred.await()
                                if (linkId != null) {
                                    // One transaction so a kill cannot leave the row marked uploaded without
                                    // its ledger + membership (which would miscount it and drop it from undo).
                                    appDatabase.withTransaction {
                                        importStagedDao.markUploaded(zipId, name)
                                        importUploadedDao.insert(
                                            ImportUploadedEntity(
                                                runId = runId,
                                                linkId = linkId,
                                                sha1 = sha1,
                                                name = displayName,
                                                dateMs = row.dateMs,
                                                alreadyInDrive = true,
                                            ),
                                        )
                                        recordAlbumMembership(runId, row, albumMode, linkId)
                                    }
                                    Log.d(TAG, "entry ${processedCount.get() + 1}/$total: in-run duplicate, linked")
                                }
                                val done = processedCount.incrementAndGet()
                                onProgress(ImportProgress(done, total, name, null, 0L, 0L))
                            }
                        }
                        else -> {
                            // Cross-run dedup: Drive already held these exact bytes before this run started.
                            val crossRunHit = contentHash != null &&
                                photoListingDao.findExistingContentHashes(userId.id, listOf(contentHash)).isNotEmpty()
                            if (crossRunHit) {
                                val crossRunLink = contentHash?.let { photoListingDao.linkIdByContentHash(userId.id, it) }
                                Log.d(TAG, "entry ${processedCount.get() + 1}/$total: already in Drive, skipped")
                                appDatabase.withTransaction {
                                    importStagedDao.markUploaded(zipId, name)
                                    if (crossRunLink != null) {
                                        importUploadedDao.insert(
                                            ImportUploadedEntity(
                                                runId = runId,
                                                linkId = crossRunLink,
                                                sha1 = sha1,
                                                name = displayName,
                                                dateMs = row.dateMs,
                                                alreadyInDrive = true,
                                            ),
                                        )
                                    }
                                    recordAlbumMembership(runId, row, albumMode, crossRunLink)
                                }
                                row.thumbPath?.let { path -> runCatching { File(path).delete() } }
                                temp.delete()
                                val done = processedCount.incrementAndGet()
                                onProgress(ImportProgress(done, total, name, null, 0L, 0L))
                            } else {
                                // A genuinely new file: this entry leads its content. Register the Deferred
                                // before the reader moves on so any later duplicate finds it, take a permit
                                // (bounding concurrent uploads and live temps), and offload the upload.
                                val deferred = CompletableDeferred<String?>()
                                if (contentHash != null) inRunUpload[contentHash] = deferred
                                uploadSemaphore.acquire()
                                jobs += uploadScope.async<Unit> {
                                    try {
                                        // Time the send and surface the phase the shared upload service reports
                                        // (encrypting, then uploading), so a debug trace shows what each photo
                                        // is doing and how long it takes. Stripped in release.
                                        val uploadStart = System.currentTimeMillis()
                                        var lastPhase: UploadPhase? = null
                                        val linkId = drivePhotoRepository.uploadFile(userId, item, sha1, item.uri, xAttr) { phase, _, _ ->
                                            if (phase != lastPhase) {
                                                lastPhase = phase
                                                val label = if (phase == UploadPhase.Encrypting) "encrypting" else "uploading"
                                                Log.d(TAG, "$displayName: $label")
                                            }
                                        }
                                        uploadedBytes.addAndGet(item.sizeBytes)
                                        Log.d(TAG, "$displayName: done ${item.sizeBytes / 1024}KB in ${System.currentTimeMillis() - uploadStart}ms")
                                        appDatabase.withTransaction {
                                            importStagedDao.markUploaded(zipId, name)
                                            importUploadedDao.insert(
                                                ImportUploadedEntity(
                                                    runId = runId,
                                                    linkId = linkId,
                                                    sha1 = sha1,
                                                    name = displayName,
                                                    dateMs = row.dateMs,
                                                ),
                                            )
                                            recordAlbumMembership(runId, row, albumMode, linkId)
                                        }
                                        contentHash?.let { hash ->
                                            runCatching { photoListingDao.seedContentHash(userId.id, linkId, hash) }
                                        }
                                        deferred.complete(linkId)
                                    } catch (e: CancellationException) {
                                        deferred.complete(null)
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w(TAG, "upload failed for $name: ${e.message}")
                                        deferred.complete(null)
                                    } finally {
                                        if (!deferred.isCompleted) deferred.complete(null)
                                        row.thumbPath?.let { path -> runCatching { File(path).delete() } }
                                        temp.delete()
                                        uploadSemaphore.release()
                                        val done = processedCount.incrementAndGet()
                                        onProgress(ImportProgress(done, total, name, null, 0L, 0L))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Every prepared upload is in flight or queued behind a permit; wait for them all before the
            // album phase, so every membership row the reconstruct reads is already recorded.
            jobs.awaitAll()
        }

        // Upload throughput for a debug trace: total bytes actually sent over the wall time of the send
        // phase, so a slow run shows whether the bytes or the per-file overhead dominated. Stripped in
        // release.
        val uploadElapsedMs = System.currentTimeMillis() - runStart
        val sentMb = uploadedBytes.get() / 1_000_000.0
        val mbPerSec = if (uploadElapsedMs > 0) sentMb / (uploadElapsedMs / 1000.0) else 0.0
        Log.d(TAG, "import send: %.1f MB in %.1fs = %.2f MB/s".format(sentMb, uploadElapsedMs / 1000.0, mbPerSec))

        // Every kept entry has been uploaded or resolved; rebuild the export's albums from the membership
        // rows recorded along the way. This never fails the import: the photos are already in Drive.
        val albumStart = System.currentTimeMillis()
        val albums = runAlbumPhase(userId, runId, albumMode)
        if (albumMode != ImportAlbumMode.NONE) {
            Log.d(TAG, "import albums: ${albums.albumsCreated} created, ${albums.photosAddedToAlbums} added in ${System.currentTimeMillis() - albumStart}ms")
        }

        // A pending entry the walk never met (the archive no longer holds it) is this run's skip count:
        // the entries this invocation could not find. The imported and already-in-Drive tallies and the
        // run total are read back from the ledger and the staged set, so a run killed and resumed reports
        // its whole self, matching the ledger the history detail shows, not just this invocation's slice.
        val skipped = total - processedCount.get()
        val summary = buildSummary(runId, zipId, skipped = skipped)
            .copy(albumsCreated = albums.albumsCreated, photosAddedToAlbums = albums.photosAddedToAlbums)
        // The history row keeps its four columns, so the already-in-Drive tally folds into its skipped
        // count rather than taking a new column and a migration.
        recordHistory(
            runId, zipId, fileName,
            total = summary.total, imported = summary.imported,
            skipped = summary.skipped + summary.alreadyInDrive, failed = summary.failed,
        )
        ImportDiagnostics.recordUpload(
            uploaded = summary.imported,
            alreadyInDrive = summary.alreadyInDrive,
            skipped = summary.skipped,
            failed = summary.failed,
            albumsCreated = summary.albumsCreated,
            photosAddedToAlbums = summary.photosAddedToAlbums,
        )
        return summary
    }

    /**
     * The run's outcome tallied from durable state rather than one invocation's counters, so a run that
     * was killed and resumed still reports its whole self. [imported] and [alreadyInDrive] come from the
     * run's undo ledger (every real upload, and every recorded pre-existing match); [total] is the kept
     * staged rows for the zip; [failed] is whatever the total does not otherwise account for, never below
     * zero. [skipped] is the entries this invocation's walk could not find in the archive, which durable
     * state does not record, so the caller passes it in. Keeps imported + alreadyInDrive + skipped +
     * failed equal to total.
     */
    private suspend fun buildSummary(runId: String, zipId: String, skipped: Int): ImportSummary {
        val imported = importUploadedDao.realUploadCount(runId)
        val alreadyInDrive = importUploadedDao.alreadyInDriveCount(runId)
        val total = importStagedDao.includedTotal(zipId)
        val failed = (total - imported - alreadyInDrive - skipped).coerceAtLeast(0)
        return ImportSummary(
            total = total, imported = imported, alreadyInDrive = alreadyInDrive, skipped = skipped, failed = failed,
        )
    }

    /**
     * Records one album-membership edge for [row] when the run reconstructs albums and this entry belongs
     * to one. A no-op for [ImportAlbumMode.NONE], for a timeline entry (null [ImportStagedEntity.albumName]),
     * or when the entry resolved to no link ([linkId] null, nothing to attach). Called once per entry, so
     * the same photo appearing in two export albums records one row per album under the one link.
     */
    private suspend fun recordAlbumMembership(
        runId: String,
        row: ImportStagedEntity,
        albumMode: ImportAlbumMode,
        linkId: String?,
    ) {
        if (albumMode == ImportAlbumMode.NONE) return
        val albumName = row.albumName ?: return
        val resolved = linkId ?: return
        importAlbumMemberDao.insert(ImportAlbumMemberEntity(runId = runId, albumName = albumName, linkId = resolved))
    }

    /**
     * Runs the album phase for [albumMode], wrapping [reconstructAlbums] so an album failure never fails
     * the import (the photos are already uploaded); cancellation still propagates. A zero-result no-op for
     * [ImportAlbumMode.NONE], which records no membership rows to act on anyway.
     */
    private suspend fun runAlbumPhase(userId: UserId, runId: String, albumMode: ImportAlbumMode): AlbumPhaseResult {
        if (albumMode == ImportAlbumMode.NONE) return AlbumPhaseResult(0, 0)
        return try {
            reconstructAlbums(userId, runId, albumMode).also { result ->
                // Album-phase tally, stripped in release; counts only, no album name.
                Log.d(TAG, "album phase (${albumMode.name}): created ${result.albumsCreated}, addedPhotos ${result.photosAddedToAlbums}")
                // A newly created or newly populated album is not in the cached list the Albums tab paints
                // from, so signal the change and it re-fetches on its own rather than waiting for a manual
                // pull-to-refresh.
                if (result.albumsCreated > 0 || result.photosAddedToAlbums > 0) albumListEvents.notifyChanged()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "album phase failed: ${e.message}")
            AlbumPhaseResult(0, 0)
        }
    }

    /**
     * Recreates the run's export albums in Drive from the membership rows the upload pass recorded. For
     * each album name the run carried it reuses a cached owned album of that exact name when one exists (so
     * re-importing the same export does not duplicate the album), otherwise it creates the album. When
     * [albumMode] is [ImportAlbumMode.WITH_PHOTOS] it then adds the album's member links, chunked to the
     * server's ten-per-request cap; [ImportAlbumMode.SHELLS_ONLY] stops after creating the empty album. A
     * single album or chunk that fails is logged and skipped, so one bad album cannot strand the rest.
     * Returns how many albums were newly created and how many photo links the server accepted into albums.
     */
    private suspend fun reconstructAlbums(userId: UserId, runId: String, albumMode: ImportAlbumMode): AlbumPhaseResult {
        val albums = importAlbumMemberDao.albumsForRun(runId)
        if (albums.isEmpty()) return AlbumPhaseResult(0, 0)
        // Reuse-by-name off the cached owned-album list so a repeat import lands in the same album rather
        // than a duplicate. Case-sensitive exact match keyed by name; a read failure falls back to create.
        val existingLinkIdByName = runCatching { cloudAlbumDao.getOwned() }
            .getOrElse { emptyList() }
            .associate { it.name to it.linkId }

        var albumsCreated = 0
        var photosAddedToAlbums = 0
        for (albumName in albums) {
            try {
                coroutineContext.ensureActive()
                val existing = existingLinkIdByName[albumName]
                val targetLinkId = if (existing != null) {
                    existing
                } else {
                    val created = drivePhotoRepository.createDriveAlbum(userId, albumName)
                    albumsCreated++
                    created.linkId
                }
                if (albumMode == ImportAlbumMode.WITH_PHOTOS) {
                    importAlbumMemberDao.linkIdsForAlbum(runId, albumName).chunked(ALBUM_ADD_CHUNK).forEach { chunk ->
                        try {
                            coroutineContext.ensureActive()
                            val result = drivePhotoRepository.addPhotosToAlbum(userId, targetLinkId, chunk)
                            photosAddedToAlbums += result.succeededLinkIds.size
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "album chunk add failed for '$albumName': ${e.message}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "album reconstruct failed for '$albumName': ${e.message}")
            }
        }
        return AlbumPhaseResult(albumsCreated, photosAddedToAlbums)
    }

    /** The album phase's tally: albums newly created this run, and photo links accepted into albums. */
    private data class AlbumPhaseResult(val albumsCreated: Int, val photosAddedToAlbums: Int)

    /**
     * Writes the one durable record of a finished run into `import_history`, tagged with [runId] so the
     * undo ledger resolves back to it. Guarded against a duplicate: a resumed completion that reaches the
     * end a second time leaves the first summary in place rather than inserting another row for the run.
     */
    /**
     * Records a history row for a run that was cancelled part-way, from whatever its ledger already holds,
     * so a stopped import still shows in Recent imports with the photos it managed to send (tappable, and
     * undoable) instead of vanishing. A no-op once the run has any history row, so a later clean completion
     * still wins. Runs under [kotlinx.coroutines.NonCancellable] at the call site, since it fires exactly
     * when the run's own scope is being torn down.
     */
    suspend fun recordPartialHistory(runId: String, zipId: String, fileName: String) {
        val imported = importUploadedDao.realUploadCount(runId)
        val alreadyInDrive = importUploadedDao.alreadyInDriveCount(runId)
        if (imported == 0 && alreadyInDrive == 0) return
        val total = importStagedDao.includedTotal(zipId)
        recordHistory(
            runId = runId,
            zipId = zipId,
            fileName = fileName,
            total = total,
            imported = imported,
            skipped = (total - imported).coerceAtLeast(0),
            failed = 0,
        )
    }

    private suspend fun recordHistory(
        runId: String,
        zipId: String,
        fileName: String,
        total: Int,
        imported: Int,
        skipped: Int,
        failed: Int,
    ) {
        // Upsert by run id: a run recorded as a partial on an in-app cancel becomes its final tally when a
        // resume completes, rather than being skipped (which would strand the partial counts) or inserted
        // twice (which would leave two rows for one archive).
        if (importHistoryDao.byRun(runId) != null) {
            importHistoryDao.updateRun(
                runId = runId,
                total = total,
                uploaded = imported,
                skipped = skipped,
                failed = failed,
                importedAt = System.currentTimeMillis(),
            )
            return
        }
        importHistoryDao.insert(
            ImportHistoryEntity(
                zipId = zipId,
                fileName = fileName,
                importedAt = System.currentTimeMillis(),
                total = total,
                uploaded = imported,
                skipped = skipped,
                failed = failed,
                runId = runId,
            ),
        )
    }

    /** The temp-file suffix that keeps [entryName]'s extension (so a downstream reader can still sniff
     *  the container), or null when the name has none. */
    private fun tempSuffix(entryName: String): String? {
        val ext = extensionOf(TakeoutZipReader.baseName(entryName))
        return if (ext.isEmpty()) null else ".$ext"
    }

    /** Hex SHA-1 of [file], the content digest [DrivePhotoRepository.uploadFile] requires (Drive pins
     *  its ContentHash to SHA-1). Streamed so a large file is never fully buffered. */
    private fun sha1Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {

        /** The capture instant to stamp: the sidecar's recorded date when it has one, else the date the
         *  file name encodes ([FilenameDate]), else null so the upload keeps no explicit date. */
        internal fun resolveDateMs(meta: SidecarMeta?, entryName: String, nowMs: Long): Long? =
            meta?.takenMs ?: FilenameDate.parse(TakeoutZipReader.baseName(entryName), nowMs)

        /** The upload item for one entry. [dateMs] null means no date was recoverable, so the item is
         *  marked non-explicit and dated 0 rather than claiming a fabricated capture time. The display
         *  name prefers the sidecar's title, falling back to the entry's own base name. */
        internal fun buildLocalMediaItem(
            uri: String,
            meta: SidecarMeta?,
            entryName: String,
            sizeBytes: Long,
            mimeType: String,
            dateMs: Long?,
        ): LocalMediaItem =
            LocalMediaItem(
                uri = uri,
                dateTaken = dateMs ?: 0L,
                displayName = meta?.title?.takeIf { it.isNotBlank() } ?: TakeoutZipReader.baseName(entryName),
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                bucketName = null,
                dateTakenIsExplicit = dateMs != null,
            )

        /** The encrypted xAttr blocks for one entry: GPS straight from the sidecar (already null when
         *  absent), the capture time as an ISO-8601 UTC instant when known, and the passed-through
         *  dimensions/duration when the caller could resolve them cheaply. */
        internal fun buildXAttr(
            meta: SidecarMeta?,
            dateMs: Long?,
            width: Int?,
            height: Int?,
            durationMs: Long?,
        ): UploadXAttrMetadata =
            UploadXAttrMetadata(
                latitude = meta?.lat,
                longitude = meta?.lng,
                cameraCaptureTimeIso = dateMs?.let { Instant.ofEpochMilli(it).toString() },
                displayWidth = width,
                displayHeight = height,
                durationMillis = durationMs,
            )

        /** The MIME type [entryName]'s extension implies, over the containers a Takeout export can hold.
         *  An unknown or missing extension falls back to a generic binary type. */
        internal fun mimeTypeFor(entryName: String): String =
            when (extensionOf(TakeoutZipReader.baseName(entryName))) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic" -> "image/heic"
                "heif" -> "image/heif"
                "avif" -> "image/avif"
                "tif", "tiff" -> "image/tiff"
                "bmp" -> "image/bmp"
                "dng" -> "image/x-adobe-dng"
                "mp4", "m4v", "mp" -> "video/mp4"
                "mov" -> "video/quicktime"
                "3gp", "3gpp" -> "video/3gpp"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                else -> "application/octet-stream"
            }

        /** True when [mimeType] names a video container. */
        internal fun isVideo(mimeType: String): Boolean = mimeType.startsWith("video/", ignoreCase = true)

        /** The lowercase extension of [base] without its dot, or empty when it has none. */
        internal fun extensionOf(base: String): String {
            val dot = base.lastIndexOf('.')
            if (dot < 0 || dot == base.length - 1) return ""
            return base.substring(dot + 1).lowercase()
        }
    }
}
