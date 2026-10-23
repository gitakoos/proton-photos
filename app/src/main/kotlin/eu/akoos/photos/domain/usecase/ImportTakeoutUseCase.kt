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
import eu.akoos.photos.data.importer.ImportDriveMatcher
import eu.akoos.photos.data.importer.ImportEntries
import eu.akoos.photos.data.importer.ImportFileDate
import eu.akoos.photos.data.importer.ImportSource
import eu.akoos.photos.data.preferences.CompressionPreferences
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.upload.UploadCompressionStep
import eu.akoos.photos.data.repository.drive.UploadPhase
import eu.akoos.photos.data.repository.drive.UploadXAttrMetadata
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.compression.CompressionSettings
import eu.akoos.photos.domain.importer.ImportAlbumMode
import eu.akoos.photos.domain.importer.ImportCaptureDate
import eu.akoos.photos.domain.importer.ImportFailure
import eu.akoos.photos.domain.importer.SidecarMeta
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.presentation.metadata.FilenameDate
import eu.akoos.photos.util.AlbumListEventBus
import eu.akoos.photos.util.ImportDiagnostics
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.Mp4CreationTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import me.proton.core.domain.entity.UserId
import java.io.File
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

/** Drive's Live Photo category tag, committed with the still of a pair. */
private const val LIVE_PHOTO_TAG = 3

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
 *  [total]: every photo in the zip, a Live Photo once, lands in exactly one of the four. [alreadyInDrive] counts an
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
 * The run's four upload tallies from its undo [ledger] and the zip's [staged] rows, counting a Live Photo
 * once. A video's ledger row folds into its still's when the still is in this ledger too; otherwise the
 * video stands for itself (it finished a Live Photo an earlier run started, or its still row is gone). A
 * pending row is a photo still to send unless it is a video whose still is itself pending; a video that
 * did not go up behind a still sent here leaves that Live Photo unfinished, so it counts as still to send.
 * [notFound] of those pending photos are skipped (never met in the source), the rest failed.
 */
internal fun importTally(
    ledger: List<ImportUploadedEntity>,
    staged: List<ImportStagedEntity>,
    notFound: Int,
): ImportSummary {
    val byName = staged.associateBy { it.entryName }
    val ledgerLinks = ledger.mapTo(HashSet()) { it.linkId }
    fun stillCarries(still: ImportStagedEntity?) = still?.linkId?.let { it in ledgerLinks } == true
    val folded = staged.mapNotNullTo(HashSet()) { r ->
        r.linkId?.takeIf { r.liveOf != null && stillCarries(byName[r.liveOf]) }
    }
    // A Live Photo whose still went up here while its video did not is unfinished: it counts as a photo
    // still to send, not as an import, so the summary agrees with the review that offers it again.
    val unfinished = staged.mapNotNullTo(HashSet()) { r ->
        if (r.liveOf == null || r.excluded || r.uploaded) return@mapNotNullTo null
        byName[r.liveOf]?.takeIf { stillCarries(it) }?.linkId
    }
    val counted = ledger.filterNot { it.linkId in folded || it.linkId in unfinished }
    val imported = counted.count { !it.alreadyInDrive }
    val alreadyInDrive = counted.size - imported
    val pending = staged.count { r ->
        if (r.excluded || r.uploaded) return@count false
        val still = r.liveOf?.let { byName[it] } ?: return@count true
        still.uploaded
    }
    val skipped = notFound.coerceIn(0, pending)
    return ImportSummary(
        total = imported + alreadyInDrive + pending,
        imported = imported,
        alreadyInDrive = alreadyInDrive,
        skipped = skipped,
        failed = pending - skipped,
    )
}

/**
 * Uploads the reviewed set of a Google Takeout / Ente import into Drive Photos, carrying the capture
 * date, GPS and caption the staging pass already resolved into each entry's row. It drives
 * the picked source's reader (a `.zip` or a folder) and the same upload seam every other upload path uses.
 *
 * Only the entries the review kept take part: the staged rows supply the metadata and the set to send,
 * so the walk streams a kept entry to a single temp file, stamps it with what the row knows (the caption
 * into an image's EXIF, the capture date into a video's mvhd), compresses it with the backup's options,
 * hashes it, uploads it, and deletes the temp before the reader advances, so the whole export never has to
 * fit on disk or in memory at once. [ImportDriveMatcher] decides first whether Drive already holds it.
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
    private val compressionStep: UploadCompressionStep,
    private val driveMatcher: ImportDriveMatcher,
) {

    /**
     * Uploads the review-kept, not-yet-sent entries for [zipId] into [userId]'s Drive Photos and records
     * the run in import history, returning a per-outcome [ImportSummary].
     *
     * The set to send is the staged rows [ImportStagedDao.pendingUpload] returns (kept and not uploaded),
     * keyed by entry name; the walk of [source] streams only those entries and skips the rest unread.
     * Each sent entry carries the metadata its staged row already resolved. [onProgress] receives a
     * running [ImportProgress]. A single entry that fails is logged and counted, never fatal, so one
     * unreadable photo cannot abort the rest; cooperative cancellation is honoured between and during
     * entries. The source's display name names the run in the history row.
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
        albumMode: ImportAlbumMode,
        source: ImportSource,
        onProgress: suspend (ImportProgress) -> Unit,
    ): ImportSummary {
        val fileName = source.displayName
        val allPending = importStagedDao.pendingUpload(zipId)
        // A Live Photo's video goes up in a second pass, behind the still it belongs to, once the still's
        // Drive link is known; everything else is the first pass.
        val pending = allPending.filter { it.liveOf == null }.associateBy { it.entryName }
        val pendingVideos = allPending.filter { it.liveOf != null }.associateBy { it.entryName }
        if (allPending.isEmpty()) {
            // Nothing left to send. A fresh run with no kept entries records a zero row; a resume whose
            // every entry was already handled before the kill reports the full run from the ledger, so a
            // completion that raced a process death is not under-counted. The album phase still runs, so a
            // kill after the last upload but before the albums were built finishes them on this resume.
            val albums = runAlbumPhase(userId, runId, albumMode)
            val summary = buildSummary(runId, zipId, notFound = 0)
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

        // The content this run has already resolved, keyed by the SHA-1 of the entry's archive bytes to the
        // link it landed under (held as a Deferred the duplicates await). It is read and written only from
        // the sequential reader below, so a plain map needs no lock and leadership falls to the first
        // occurrence in zip order, exactly as a one-at-a-time run resolved it. A second copy of the same
        // bytes later in the archive (the same photo in another export album) resolves to that link and
        // records its own album membership behind the single upload.
        val inRunUpload = HashMap<String, CompletableDeferred<String?>>()

        // The backup's compression options apply to the import too; its strip options do not, and only
        // decide whether a same-name twin of a backed-up photo counts as the same photo.
        val prefs = context.settingsDataStore.data.first()
        val compression = CompressionPreferences.settings(prefs)
        val stripOnUpload = prefs[SettingsKeys.STRIP_ON_UPLOAD] ?: false

        // Bounded-parallel upload, mirroring the backup path. The zip is still read one entry at a time (a
        // single stream) and the reader does every ordered step itself (extract, metadata stamp, hash,
        // dedup decision, compression), but the slow network upload of a genuinely new file is offloaded to
        // a pool of at most [IMPORT_UPLOAD_PARALLELISM], so several uploads run at once instead of strictly
        // in turn. A permit is taken before a new file's upload and released when it finishes, which also
        // caps how many extracted temp files exist at once. Only the upload is parallel, so the ledger, the
        // dedup and the album membership keep their sequential meaning.
        val uploadSemaphore = Semaphore(IMPORT_UPLOAD_PARALLELISM)
        // Both halves of a Live Photo go up as they are: a re-encoded still or video would no longer match
        // the other half.
        val staged = importStagedDao.allForZip(zipId).associateBy { it.entryName }
        val stillsWithVideo = staged.values.mapNotNull { it.liveOf }.toSet()
        // The photos this invocation counts, for progress and the not-found tally: a Live Photo is its
        // still, and its video stands for itself only once the still is no longer pending. The per-outcome
        // counts are read back from durable state in buildSummary, so a resumed run still reports its whole
        // self.
        val units = allPending
            .filterNot { r -> r.liveOf != null && staged[r.liveOf]?.uploaded == false }
            .mapTo(HashSet()) { it.entryName }
        val total = units.size
        val handled = ConcurrentHashMap.newKeySet<String>()
        fun handle(name: String): Int {
            if (name in units) handled.add(name)
            return handled.size
        }
        // Throughput instrumentation, stripped in release with the rest of the logs: how long the run took
        // and how many bytes it actually sent, so a slow import is legible in a debug trace.
        val runStart = System.currentTimeMillis()
        val uploadedBytes = AtomicLong(0)

        val parentContext = coroutineContext[Job] ?: EmptyCoroutineContext
        // The Drive link and the capture date of each still, read from the staged rows once the first pass
        // is done (so a still that went up before a kill counts too, and a date the first pass filled in).
        var stillLinks: Map<String, String> = emptyMap()
        var stillDateMs: Map<String, Long?> = emptyMap()
        coroutineScope {
            val uploadScope = this
            val jobs = mutableListOf<Deferred<Unit>>()

            /** Records [row] as resolved by [linkId] that already held its bytes, in one transaction so a
             *  kill cannot leave the row marked uploaded without its ledger and membership. */
            suspend fun recordInDrive(row: ImportStagedEntity, linkId: String, sha1: String, raw: String, name: String, dateMs: Long) {
                appDatabase.withTransaction {
                    importStagedDao.markUploadedWithLink(zipId, row.entryName, linkId)
                    importUploadedDao.insert(
                        ImportUploadedEntity(
                            runId = runId,
                            linkId = linkId,
                            sha1 = sha1,
                            name = name,
                            dateMs = dateMs,
                            alreadyInDrive = true,
                            sourceSha1 = raw,
                            userId = userId.id,
                        ),
                    )
                    if (row.liveOf == null) recordAlbumMembership(runId, row, albumMode, linkId)
                }
            }

            fun walk(rows: Map<String, ImportStagedEntity>) = source.reader.forEachMediaEntry { entry, input ->
                val name = entry.name
                val row = rows[name] ?: return@forEachMediaEntry
                // The reader stays on the caller's job so cancellation flows in; it prepares each entry in
                // order here and offloads only the upload to [uploadScope].
                runBlocking(parentContext) {
                    ensureActive()
                    val temp = File.createTempFile("import_", tempSuffix(name), context.cacheDir)
                    val displayName = row.title?.takeIf { it.isNotBlank() } ?: ImportEntries.baseName(name)
                    val raw: String
                    val rowDateMs: Long
                    val plan: EntryPlan
                    try {
                        temp.outputStream().use { input.copyTo(it) }
                        val mimeType = mimeTypeFor(name)
                        // A row staged before every entry got a date is dated here, from the untouched temp.
                        rowDateMs = row.dateMs ?: run {
                            val nowMs = System.currentTimeMillis()
                            ImportCaptureDate.resolve(
                                sidecarMs = null,
                                fileMs = { ImportFileDate.read(context, temp, mimeType) },
                                nameMs = FilenameDate.parse(ImportEntries.baseName(name), nowMs),
                                entryModifiedMs = entry.modifiedMs,
                                nowMs = nowMs,
                            ).also { importStagedDao.setDateMs(zipId, name, it) }
                        }
                        val captureMs = row.liveOf?.let { stillDateMs[it] } ?: rowDateMs
                        // The archive's own bytes: the key a duplicate in this run, and a later run of the
                        // same photo, is recognised by, whatever the caption, the date stamp or the
                        // compression do to the bytes afterwards.
                        raw = sha1Hex(temp)
                        val leader = inRunUpload[raw]
                        plan = if (leader != null) {
                            EntryPlan.Duplicate(leader)
                        } else {
                            prepare(
                                userId = userId,
                                row = row,
                                entryName = name,
                                temp = temp,
                                mimeType = mimeType,
                                captureMs = captureMs,
                                raw = raw,
                                displayName = displayName,
                                compression = compression,
                                stripOnUpload = stripOnUpload,
                                pairMember = row.liveOf != null || name in stillsWithVideo,
                            )
                        }
                    } catch (e: CancellationException) {
                        temp.delete()
                        throw e
                    } catch (e: Exception) {
                        temp.delete()
                        // A fault about the entry's own bytes skips it; one about the storage or the place the
                        // zip is read from would fail every entry after it, so it stops the run instead and the
                        // rows still pending are sent when it is retried.
                        if (e is java.io.IOException && !ImportFailure.isArchiveFault(e)) throw e
                        Log.w(TAG, "prepare failed for $name: ${e.message}")
                        val done = handle(name)
                        onProgress(ImportProgress(done, total, name, null, 0L, 0L))
                        return@runBlocking
                    }

                    when (plan) {
                        // A duplicate of bytes an earlier entry this run already resolved: do not send a
                        // second copy. Await that entry's link and record this entry's own album membership
                        // behind it. The temp is not needed here, so it goes now.
                        is EntryPlan.Duplicate -> {
                            row.thumbPath?.let { path -> runCatching { File(path).delete() } }
                            temp.delete()
                            jobs += uploadScope.async<Unit> {
                                val linkId = plan.leader.await()
                                if (linkId != null) {
                                    recordInDrive(row, linkId, raw, raw, displayName, rowDateMs)
                                    Log.d(TAG, "entry ${handled.size + 1}/$total: in-run duplicate, linked")
                                }
                                val done = handle(name)
                                onProgress(ImportProgress(done, total, name, null, 0L, 0L))
                            }
                        }
                        // Drive already held this photo before this run reached it.
                        is EntryPlan.InDrive -> {
                            inRunUpload[raw] = CompletableDeferred(plan.linkId)
                            Log.d(TAG, "entry ${handled.size + 1}/$total: already in Drive, skipped")
                            recordInDrive(row, plan.linkId, plan.sha1, raw, displayName, rowDateMs)
                            row.thumbPath?.let { path -> runCatching { File(path).delete() } }
                            temp.delete()
                            val done = handle(name)
                            onProgress(ImportProgress(done, total, name, null, 0L, 0L))
                        }
                        // A genuinely new file: this entry leads its content. Register the Deferred before
                        // the reader moves on so any later duplicate finds it, take a permit (bounding
                        // concurrent uploads and live temps), and offload the upload.
                        is EntryPlan.Upload -> {
                            val deferred = CompletableDeferred<String?>()
                            inRunUpload[raw] = deferred
                            uploadSemaphore.acquire()
                            jobs += uploadScope.async<Unit> {
                                try {
                                    // Time the send and surface the phase the shared upload service reports
                                    // (encrypting, then uploading), so a debug trace shows what each photo
                                    // is doing and how long it takes. Stripped in release.
                                    val uploadStart = System.currentTimeMillis()
                                    var lastPhase: UploadPhase? = null
                                    val item = plan.item
                                    val linkId = drivePhotoRepository.uploadFileLinked(
                                        userId, item, plan.sha1, plan.uploadUri, plan.xAttr,
                                        mainPhotoLinkId = row.liveOf?.let { stillLinks[it] },
                                        extraTags = if (name in stillsWithVideo) setOf(LIVE_PHOTO_TAG) else emptySet(),
                                    ) { phase, _, _ ->
                                        if (phase != lastPhase) {
                                            lastPhase = phase
                                            val label = if (phase == UploadPhase.Encrypting) "encrypting" else "uploading"
                                            Log.d(TAG, "${item.displayName}: $label")
                                        }
                                    }
                                    uploadedBytes.addAndGet(item.sizeBytes)
                                    Log.d(TAG, "${item.displayName}: done ${item.sizeBytes / 1024}KB in ${System.currentTimeMillis() - uploadStart}ms")
                                    appDatabase.withTransaction {
                                        importStagedDao.markUploadedWithLink(zipId, name, linkId)
                                        importUploadedDao.insert(
                                            ImportUploadedEntity(
                                                runId = runId,
                                                linkId = linkId,
                                                sha1 = plan.sha1,
                                                name = item.displayName,
                                                dateMs = rowDateMs,
                                                sourceSha1 = raw,
                                                userId = userId.id,
                                            ),
                                        )
                                        if (row.liveOf == null) recordAlbumMembership(runId, row, albumMode, linkId)
                                    }
                                    drivePhotoRepository.cloudContentHash(plan.sha1)?.let { hash ->
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
                                    plan.compressed?.delete()
                                    temp.delete()
                                    uploadSemaphore.release()
                                    val done = handle(name)
                                    onProgress(ImportProgress(done, total, name, null, 0L, 0L))
                                }
                            }
                        }
                    }
                }
            }

            walk(pending)
            // Every prepared upload is in flight or queued behind a permit; wait for them all before the
            // album phase, so every membership row the reconstruct reads is already recorded.
            jobs.awaitAll()

            // The videos of the Live Photos, now that their stills are in Drive: each one joins its still.
            // A video whose still did not go up stays pending for the next run.
            if (pendingVideos.isNotEmpty()) {
                val after = importStagedDao.allForZip(zipId).associateBy { it.entryName }
                stillLinks = after.values.mapNotNull { r -> r.linkId?.let { r.entryName to it } }.toMap()
                stillDateMs = after.mapValues { it.value.dateMs }
                // A still that is in Drive without a known link (it was resolved as already there) cannot take its
                // video, and neither can a still whose row is gone, so that video goes up as a video of its own
                // rather than waiting for a link that never comes.
                val ready = pendingVideos.filterValues { v ->
                    val still = v.liveOf?.let { after[it] }
                    still == null || still.uploaded
                }
                if (ready.isNotEmpty()) {
                    walk(ready)
                    jobs.awaitAll()
                }
            }
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

        // A pending photo the walk never met (the archive no longer holds it) is this run's skip count:
        // the photos this invocation could not find. The imported and already-in-Drive tallies and the
        // run total are read back from the ledger and the staged set, so a run killed and resumed reports
        // its whole self, matching the ledger the history detail shows, not just this invocation's slice.
        val summary = buildSummary(runId, zipId, notFound = (units - handled).size)
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

    /** What becomes of one entry: a copy of bytes an earlier entry of this run resolved, a photo Drive
     *  already holds under [InDrive.linkId], or an upload of the bytes at [Upload.uploadUri]. */
    private sealed interface EntryPlan {
        class Duplicate(val leader: CompletableDeferred<String?>) : EntryPlan
        class InDrive(val linkId: String, val sha1: String) : EntryPlan
        class Upload(
            val item: LocalMediaItem,
            val sha1: String,
            val uploadUri: String,
            val xAttr: UploadXAttrMetadata,
            /** The compressed temp [uploadUri] names, or null when the extracted file itself is sent. */
            val compressed: File?,
        ) : EntryPlan
    }

    /**
     * Readies the extracted [temp] of an entry no earlier entry of this run shares bytes with. The caption
     * goes into an image's EXIF and the capture date into a video's mvhd, then the entry is looked up in
     * Drive under its archive digest [raw] and its stamped digest. A photo that is not there yet is
     * compressed with the backup's options (never a half of a Live Photo, [pairMember]), and the bytes to
     * send are looked up once more. Drive's ContentHash is computed from exactly what is uploaded, so the
     * upload carries the digest of the file it sends, and a format the compression changed renames it.
     */
    private suspend fun prepare(
        userId: UserId,
        row: ImportStagedEntity,
        entryName: String,
        temp: File,
        mimeType: String,
        captureMs: Long,
        raw: String,
        displayName: String,
        compression: CompressionSettings,
        stripOnUpload: Boolean,
        pairMember: Boolean,
    ): EntryPlan {
        val tempUri = Uri.fromFile(temp).toString()
        val caption = row.description?.takeIf { it.isNotBlank() }
        // The caption write self-skips a container it cannot address; a video whose mvhd already records
        // the date is left as it is.
        caption?.let { writeCaption(tempUri, it) }
        if (isVideo(mimeType)) {
            runCatching { Mp4CreationTime.stampIfChanged(temp, captureMs) }
        }
        val stamped = if (caption == null && !isVideo(mimeType)) raw else sha1Hex(temp)
        driveMatcher.find(
            userId,
            ImportDriveMatcher.Entry(listOf(raw, stamped), raw, displayName, mimeType, captureMs),
            compression,
            stripOnUpload,
            confirmLedgerLink = true,
        )?.let { return EntryPlan.InDrive(it, stamped) }

        val compressed = if (pairMember) null else compress(tempUri, mimeType, captureMs, compression)
        try {
            // A HEIC the first caption write could not reach comes out of compression as a JPEG that can
            // carry the caption.
            if (compressed != null && caption != null && !isVideo(mimeType)) {
                writeCaption(Uri.fromFile(compressed.first).toString(), caption)
            }
            val sent = compressed?.first ?: temp
            val sha1 = if (compressed != null) sha1Hex(sent) else stamped
            if (compressed != null) {
                driveMatcher.linkByContent(userId, listOf(sha1))?.let { linkId ->
                    sent.delete()
                    return EntryPlan.InDrive(linkId, sha1)
                }
            }
            val (uploadName, uploadMime) = UploadFormatNaming.forProducedFormat(displayName, mimeType, compressed?.second)
            val meta = SidecarMeta(
                takenMs = captureMs,
                lat = row.lat,
                lng = row.lng,
                description = row.description,
                title = row.title,
            )
            // The item keeps the extracted file as its source, so the category detection reads the
            // original; its name, type and size follow the bytes sent.
            val item = buildLocalMediaItem(tempUri, meta, entryName, sent.length(), mimeType, captureMs)
                .copy(displayName = uploadName, mimeType = uploadMime)
            val xAttr = buildXAttr(meta, captureMs, width = null, height = null, durationMs = null)
            return EntryPlan.Upload(item, sha1, Uri.fromFile(sent).toString(), xAttr, compressed?.first)
        } catch (e: Throwable) {
            compressed?.first?.delete()
            throw e
        }
    }

    /** The backup's compression of the file at [uri]: the smaller temp and the format it holds, or null to
     *  send the file as it is. */
    private suspend fun compress(
        uri: String,
        mimeType: String,
        captureMs: Long,
        settings: CompressionSettings,
    ): Pair<File, String>? {
        if (isVideo(mimeType)) {
            if (!compressionStep.compressesVideo(mimeType, settings)) return null
            val attempt = compressionStep.video(uri, settings, captureMs)
            Log.d(TAG, "video compression ${attempt.outcome} ${attempt.reason ?: ""}")
            return attempt.file?.let { it to UploadFormatNaming.MP4 }
        }
        val result = compressionStep.photo(uri, mimeType, settings, MetadataStripConfig())
        result.outcome?.let { Log.d(TAG, "photo compression $it ${result.reason ?: ""}") }
        val file = result.file ?: return null
        return file to (result.producedMime ?: UploadFormatNaming.JPEG)
    }

    private suspend fun writeCaption(uri: String, caption: String) {
        runCatching {
            writeLocalPhotoMetadataUseCase.writeDescriptiveText(uri, description = TextTagEdit.SetTo(caption))
        }
    }

    /**
     * The run's outcome tallied from durable state rather than one invocation's counters, so a run that
     * was killed and resumed still reports its whole self (see [importTally]). [notFound] is how many
     * photos this invocation's walk could not find in the archive, which durable state does not record.
     * Read before the worker clears the settled staged rows.
     */
    private suspend fun buildSummary(runId: String, zipId: String, notFound: Int): ImportSummary =
        importTally(importUploadedDao.allByRun(runId), importStagedDao.allForZip(zipId), notFound)

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
        // The photos it never reached count as skipped, not failed.
        val tally = importTally(importUploadedDao.allByRun(runId), importStagedDao.allForZip(zipId), Int.MAX_VALUE)
        if (tally.imported == 0 && tally.alreadyInDrive == 0) return
        recordHistory(
            runId = runId,
            zipId = zipId,
            fileName = fileName,
            total = tally.total,
            imported = tally.imported,
            skipped = tally.skipped + tally.alreadyInDrive,
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
        val ext = extensionOf(ImportEntries.baseName(entryName))
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

        /** The date an entry's sidecar records, else the date its file name encodes ([FilenameDate]), else
         *  null. Live Photo pairing compares these; the upload date is [ImportCaptureDate]'s. */
        internal fun resolveDateMs(meta: SidecarMeta?, entryName: String, nowMs: Long): Long? =
            meta?.takenMs ?: FilenameDate.parse(ImportEntries.baseName(entryName), nowMs)

        /** The upload item for one entry, dated [dateMs] (see [ImportCaptureDate]). The display name
         *  prefers the sidecar's title, falling back to the entry's own base name. */
        internal fun buildLocalMediaItem(
            uri: String,
            meta: SidecarMeta?,
            entryName: String,
            sizeBytes: Long,
            mimeType: String,
            dateMs: Long,
        ): LocalMediaItem =
            LocalMediaItem(
                uri = uri,
                dateTaken = dateMs,
                displayName = meta?.title?.takeIf { it.isNotBlank() } ?: ImportEntries.baseName(entryName),
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                bucketName = null,
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
            when (extensionOf(ImportEntries.baseName(entryName))) {
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
